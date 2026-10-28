"""
مرحله ۲ — اعتبارسنجی سخت‌گیرانهٔ پارامترهای مرحلهٔ ۱
====================================================
هر پارامتر برنده باید از این هفت فیلتر بگذرد، وگرنه «بیش‌برازش» اعلام می‌شود:

 ۱) همسایگی: ۲۷ ترکیب اطراف پارامتر (±یک پله در N، ATR، هدف) — چند درصدشان مثبت‌اند؟
 ۲) هزینهٔ سنگین‌تر: ۱٫۵× و ۲× هزینه + ۱ پیپ اسلیپیج در هر طرف.
 ۳) ابهام کندل: بدترین‌حالت در برابر خوش‌بینانه (SL اول / پله اول).
 ۴) سواپ خاموش: آیا سود از سواپ می‌آید یا از خودِ معامله؟
 ۵) تاریخ بلند ۲۰ سالهٔ D1: همان خانوادهٔ قاعده در مقیاس روزانه.
 ۶) سال‌به‌سال: چند سال مثبت از چند سال؟
 ۷) آزمون صفر (null): ۲۰۰ ورودِ تصادفی با همان مدیریت ⇒ توزیع avgR.
    اگر پارامتر برنده داخل این توزیع باشد، «سیگنال» چیزی بیش از بخت نیست.
"""
import json, time
import numpy as np
import pandas as pd
import fx_lab as L

S1 = pd.read_csv("/home/user/research/fx_stage1.csv")
OUT_ROWS = []


def rand_entry_test(pair, tf, atr_mult, k, exit_model, n_iter=200, seed=7, p_rate=0.02):
    """
    توزیع صفر: زمان ورود **تصادفی** (نه شکست) با همان مدیریت خروج، هزینه و سواپ.
    اگر پارامتر برنده از این توزیع بهتر نباشد، سیگنال شکست هیچ اطلاعاتی نداشته است.

    ورودها به‌ترتیب زمانی و با قید «یک پوزیشن باز» پردازش می‌شوند — دقیقاً مثل
    موتور واقعی؛ نسخهٔ اول این تابع ترتیب تصادفی را رعایت نمی‌کرد و تعداد معامله
    به ۸ می‌رسید (خطای همان نسخه، اصلاح شد).
    """
    rng = np.random.default_rng(seed)
    df = L.load(pair, tf)
    h, l, c = df["h"].values, df["l"].values, df["c"].values
    A = L.atr_series(df)
    idx = df.index
    days = idx.asi8 // 86_400_000_000_000
    hour = idx.hour.values
    n = len(df)
    cost, sw_l, sw_s = L.cost_price(pair, float(c[-1]))
    out = []
    warm = 20
    for _ in range(n_iter):
        pick = (rng.random(n) < p_rate)
        pick[:warm] = False
        pick[n - 61:] = False
        Rs = []
        i = warm
        while i < n - 61:
            if not pick[i] or not (A[i] > 0) or not np.isfinite(A[i]):
                i += 1; continue
            sig = 1 if rng.random() < 0.5 else -1
            risk = atr_mult * A[i]
            costR = cost / risk
            if costR > 0.2:
                i += 1; continue
            entry = c[i]
            sl = entry - sig * risk
            tp = entry + sig * k * risk
            p1 = entry + sig * risk
            realized, qty, nights = 0.0, 1.0, 0
            last_day = days[i] - (1 if hour[i] < 21 else 0)
            exit_r, exit_i = None, i
            j = i + 1
            while j < min(i + 61, n):
                d = days[j] - (1 if hour[j] < 21 else 0)
                while last_day < d:
                    last_day += 1
                    dow = (last_day + 3) % 7
                    if dow >= 5:
                        continue
                    nights += 3 if dow == 2 else 1
                bh, bl, bc = h[j], l[j], c[j]
                hit_sl = bl <= sl if sig == 1 else bh >= sl
                hit_p1 = bh >= p1 if sig == 1 else bl <= p1
                hit_tp = bh >= tp if sig == 1 else bl <= tp
                if exit_model == "partial" and qty > 0.5 and hit_sl and hit_p1:
                    exit_r = ((sl - entry) * sig) / risk; exit_i = j; break
                if hit_sl:
                    exit_r = ((sl - entry) * sig) / risk; exit_i = j; break
                if exit_model == "partial" and qty > 0.5 and hit_p1:
                    realized += 0.5; qty = 0.5; sl = entry
                if hit_tp:
                    exit_r = k; exit_i = j; break
                exit_r = ((bc - entry) * sig) / risk; exit_i = j
                j += 1
            if exit_r is None:
                k2 = min(i + 60, n - 1)
                exit_r = ((c[k2] - entry) * sig) / risk; exit_i = k2
            Rs.append(realized + qty * exit_r - costR +
                      ((sw_l if sig == 1 else sw_s) * nights) / risk)
            i = exit_i + 1
        if len(Rs) >= 30:
            out.append(float(np.mean(Rs)))
    return np.array(out)


def yearly_breakdown(d):
    g = d.groupby(d["t"].dt.year)["R"].sum()
    return " · ".join(f"{y}:{v:+.0f}" for y, v in g.items())


rows = []
t0 = time.time()
for pair in L.PAIRS:
    s = S1[S1.pair == pair].copy()
    s["score"] = s.apply(lambda r: L.score(r.to_dict()), axis=1)
    s = s.sort_values("score", ascending=False)
    best = s.iloc[0]
    tf, mode, N, am, k, ex = (best.tf, best["mode"], int(best.N), float(best.atr),
                              float(best.k), best["exit"])

    base = L.metrics(L.backtest(pair, tf, mode, N, am, k, ex, gate=0.20))

    # ۱) همسایگی
    neigh = []
    for dN in (-24, -12, 12, 24):
        for dam in (-0.5, 0.5):
            for dk in (-1.0, 1.0):
                nn, aa, kk = max(6, N + dN), max(0.5, am + dam), max(1.5, k + dk)
                m = L.metrics(L.backtest(pair, tf, mode, nn, aa, kk, ex, gate=0.20))
                if m:
                    neigh.append(m["avgR"])
    neigh_pos = 100.0 * np.mean([x > 0 for x in neigh]) if neigh else 0.0

    # ۲) هزینه
    c15 = L.metrics(L.backtest(pair, tf, mode, N, am, k, ex, gate=0.20, cost_mult=1.5))
    c20 = L.metrics(L.backtest(pair, tf, mode, N, am, k, ex, gate=0.20, cost_mult=2.0))

    # ۳) ابهام کندل
    opt = L.metrics(L.backtest(pair, tf, mode, N, am, k, ex, gate=0.20, same_bar="optimist"))

    # ۴) سواپ خاموش
    nsw = L.metrics(L.backtest(pair, tf, mode, N, am, k, ex, gate=0.20, use_swap=False))

    # ۵) ۲۰ سال D1 — همان N و atr و k در مقیاس روزانه
    d1 = L.metrics(L.backtest(pair, "d1", mode, N, am, k, ex, gate=0.20, max_hold=90))

    # ۶) سال‌به‌سال
    d_base = L.backtest(pair, tf, mode, N, am, k, ex, gate=0.20)

    # ۷) آزمون صفر
    null = rand_entry_test(pair, tf, am, k, ex)
    pval = float((null >= base["avgR"]).mean()) if len(null) else 1.0

    rows.append(dict(pair=pair, tf=tf, mode=mode, N=N, atr=am, k=k, exit=ex,
                     trades=base["trades"], avgR=base["avgR"], pf=base["pf"],
                     h1avg=base["h1avg"], h2avg=base["h2avg"],
                     pos_years=f"{base['pos_years']}/{base['years']}",
                     dd=base["dd"], costR=base["costR"],
                     neigh_pos=neigh_pos,
                     avgR_c15=c15["avgR"] if c15 else np.nan,
                     avgR_c20=c20["avgR"] if c20 else np.nan,
                     avgR_opt=opt["avgR"] if opt else np.nan,
                     avgR_noswap=nsw["avgR"] if nsw else np.nan,
                     d1_avgR=d1["avgR"] if d1 else np.nan,
                     d1_trd=d1["trades"] if d1 else 0,
                     d1_pfyrs=f"{d1['pos_years']}/{d1['years']}" if d1 else "-",
                     null_p=pval, null_mean=float(null.mean()) if len(null) else np.nan,
                     years_txt=yearly_breakdown(d_base)))
    print(f"{pair}: {tf}/{mode}/N{N}/atr{am}/k{k}/{ex} · avgR {base['avgR']:+.3f} "
          f"PF {base['pf']:.2f} · همسایه {neigh_pos:.0f}% · هزینه۲× "
          f"{(c20['avgR'] if c20 else float('nan')):+.3f} · D1 "
          f"{(d1['avgR'] if d1 else float('nan')):+.3f} · p_صفر {pval:.3f} "
          f"({time.time()-t0:.0f}s)", flush=True)

df = pd.DataFrame(rows)
df.to_csv("/home/user/research/fx_stage2.csv", index=False)
print("\nذخیره شد: fx_stage2.csv")
