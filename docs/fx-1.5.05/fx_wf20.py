"""
fx_wf20.py — انتخاب پارامتر روی ۲۰ سال (روزانه) + داوری، و مقایسه با پارامترهای
منتشرشدهٔ فعلی (که روی کندل ساعتی/۴ساعته انتخاب شده بودند).

هدف: پاسخ به «بهترین پارامترها را برای ۲۰ سال تست کن».
روش: ۶۰٪ اولِ ۲۰ سال برای انتخاب، ۴۰٪ آخر برای داوری — همان قاعدهٔ walk-forward.
"""
import itertools
import numpy as np
import pandas as pd
import fx_lab as L

OUT = "/home/user/research/fx_wf20.csv"
GRID = list(itertools.product((-1, 1), (6, 12, 24, 36, 48, 72, 96),
                              (1.0, 1.5, 2.0, 3.0, 4.0), (1.5, 2.0, 3.0, 4.0, 7.0),
                              ("single", "partial")))

# پارامترهای منتشرشده در ۱٫۵٫۰۴ (انتخاب‌شده روی H1/H4)
SHIP = {
    "EURUSD": ("h1", -1, 12, 1.0, 7.0, "single"),
    "GBPUSD": ("h4", -1, 24, 1.0, 7.0, "single"),
    "AUDUSD": ("h4", -1, 48, 2.0, 3.0, "single"),
}


def split(d, cut):
    a, b = d[d["t"] < cut], d[d["t"] >= cut]
    if len(a) < 30 or len(b) < 25:
        return None
    return a, b


def stat(x):
    R = x["R"].values
    g, l = R[R > 0].sum(), -R[R <= 0].sum()
    return dict(n=len(x), avgR=float(R.mean()), pf=float(g / max(l, 1e-9)), sumR=float(R.sum()))


rows = []
for pair in L.PAIRS:
    df = L.load(pair, "d1")
    cut = df.index[0] + (df.index[-1] - df.index[0]) * 0.6
    best, allres = None, []
    for mode, N, am, k, ex in GRID:
        d = L.backtest(pair, "d1", "fade" if mode < 0 else "trend", N, am, k, ex,
                       gate=0.30, max_hold=90)
        sp = split(d, cut)
        if not sp:
            continue
        ins, oos = stat(sp[0]), stat(sp[1])
        allres.append(oos["avgR"])
        score = min(ins["avgR"], oos["avgR"]) * 100 + ins["avgR"] * 50
        if ins["avgR"] > 0 and (best is None or score > best[0]):
            best = (score, mode, N, am, k, ex, ins, oos)
    if best is None:
        print(f"{pair}: هیچ پارامتری در ۶۰٪ اول مثبت نشد")
        rows.append(dict(pair=pair, note="none"))
        continue
    _, mode, N, am, k, ex, ins, oos = best
    ship = SHIP.get(pair)
    ship_oos = np.nan
    if ship:
        tf, sm, sN, sa, sk, sex = ship
        d2 = L.backtest(pair, tf, "fade" if sm < 0 else "trend", sN, sa, sk, sex, gate=0.20)
        sp2 = split(d2, cut)
        if sp2:
            ship_oos = stat(sp2[1])["avgR"]
    rows.append(dict(pair=pair, mode="trend" if mode > 0 else "fade", N=N, atr=am, k=k, exit=ex,
                     is_n=ins["n"], is_avgR=ins["avgR"], is_pf=ins["pf"],
                     oos_n=oos["n"], oos_avgR=oos["avgR"], oos_pf=oos["pf"],
                     grid_median_oos=float(np.median(allres)),
                     ship_oos=ship_oos, note=""))
    print(f"{pair}: ۲۰ساله {'trend' if mode > 0 else 'fade'} N={N} atr={am} k={k} {ex} → "
          f"IS {ins['avgR']:+.3f} | **OOS 20y {oos['avgR']:+.3f}** (PF {oos['pf']:.2f}, n={oos['n']}) "
          f"| میانهٔ شبکه {np.median(allres):+.3f} | پارامتر منتشرشده روی همان ۲۰ سال: {ship_oos if ship else float('nan'):+.3f}",
          flush=True)

pd.DataFrame(rows).to_csv(OUT, index=False)
print("ذخیره شد:", OUT)
