"""
اسکرین نهایی جفت‌ارزها برای GoldPin — ۱٫۵٫۰۳
=============================================
این فایل «مدل نماینده» است، نه موتور GoldPin. صریحاً:

  • داده: ۱۲ جفت‌ارز، کندل ساعتی Yahoo، ۷۳۰ روز (۲۰۲۴-۱۰ تا ۲۰۲۶-۱۰)،
    تجمیع به H4. حجمِ فایل اصلی صفر بود و در موتور مشکل می‌ساخت؛ این‌جا
    حجم لازم نیست.
  • ورود: شکست سقف/کف ۲۴ کندلی H4 (مثل منطق breakout موتور) — در حالت
    `fade` برعکسِ شکست معامله می‌شود، در حالت `trend` هم‌جهت شکست.
  • ریسک: ۱٫۵×ATR(14) روی H4. هدف: k×R با k ∈ {۲,۴,۷}.
  • هزینه: اسپرد Pro ECN آلپاری + کمیسیون ۵ دلار در لات رفت‌وبرگشت.
  • سواپ: نرخ‌های رسمی آلپاری، هر رول‌اور ۲۱:۰۰ UTC، چهارشنبه سه‌برابر،
    شب شنبه/یکشنبه صفر (همان قاعده‌ای که در موتور ۱٫۵٫۰۳ پیاده شد).
  • گیت هزینه: اگر هزینهٔ رفت‌وبرگشت از gate×R بیشتر باشد، معامله رد می‌شود.

حساب‌داری همه‌جا در واحد R است (نه قیمت)، تا خطای واحد پیش نیاید.
"""
import json
import numpy as np
import pandas as pd

PAIRS = ["EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "USDCAD", "USDCHF",
         "NZDUSD", "EURJPY", "GBPJPY", "EURGBP", "AUDJPY", "EURCHF"]
AL = json.load(open("/tmp/fx/alpari_fx.json"))["proEcnMt5"]
COMMISSION_USD_PER_LOT = 5.0      # ۲٫۵ دلار هر طرف
SPLIT = pd.Timestamp("2025-10-28", tz="UTC")


def load(pair):
    df = pd.read_csv(f"/tmp/fx/{pair}.csv", header=None,
                     names=["t", "o", "h", "l", "c", "v"])
    df["dt"] = pd.to_datetime(df["t"], unit="ms", utc=True)
    df = df.set_index("dt")
    return df.resample("4h").agg({"o": "first", "h": "max",
                                  "l": "min", "c": "last"}).dropna()


def atr(df, n=14):
    tr = np.maximum(df["h"] - df["l"],
                    np.maximum((df["h"] - df["c"].shift()).abs(),
                               (df["l"] - df["c"].shift()).abs()))
    return tr.rolling(n).mean()


def costs_price(pair, price):
    """هزینهٔ رفت‌وبرگشت + سواپ، در واحد قیمت (دلار بر یک واحد ارز پایه)."""
    a = AL[pair]
    jpy = pair.endswith("JPY")
    point = 0.01 if jpy else 0.0001
    spread = a["spread"] * point
    # ۵ دلار در لات = ۵ دلار بر ۱۰۰٬۰۰۰ واحد؛ برای جفت‌های JPY حجم لات
    # بر حسب ین است، پس به قیمت تبدیل می‌شود.
    comm = (COMMISSION_USD_PER_LOT / price) / 100_000.0 if jpy \
        else COMMISSION_USD_PER_LOT / 100_000.0
    per_lot = price / 100_000.0          # ارزش یک «واحد نرخ» بر یک واحد پایه
    swap_long = a["swapL"] * per_lot / 100_000.0
    swap_short = a["swapS"] * per_lot / 100_000.0
    return spread + comm, swap_long, swap_short


def backtest(pair, fade=True, k=4.0, gate=0.25, partial=True):
    h4 = load(pair)
    px = float(h4["c"].iloc[-1])
    cost, swapL, swapS = costs_price(pair, px)
    A = atr(h4)
    hi = h4["h"].rolling(24).max().shift(1)
    lo = h4["l"].rolling(24).min().shift(1)
    idx = list(h4.index)
    rows, i = [], 25
    while i < len(h4) - 1:
        av = A.iloc[i]
        if not np.isfinite(av) or av <= 0:
            i += 1; continue
        sig = -1 if h4["c"].iloc[i] > hi.iloc[i] else (
            1 if h4["c"].iloc[i] < lo.iloc[i] else 0)
        if sig == 0:
            i += 1; continue
        if not fade:
            sig = -sig
        entry, risk = h4["c"].iloc[i], 1.5 * av
        costR = cost / risk
        if costR > gate:
            i += 1; continue

        sl = entry - sig * risk
        tp = entry + sig * k * risk
        p1 = entry + sig * risk           # پلهٔ ۱R (نصف حجم)
        realizedR, qty, nights, exitR = 0.0, 1.0, 0, None
        last_day = idx[i].value // 86_400_000_000_000 - (1 if idx[i].hour < 21 else 0)

        for j in range(i + 1, min(i + 31, len(h4))):
            bar = h4.iloc[j]
            day = idx[j].value // 86_400_000_000_000 - (1 if idx[j].hour < 21 else 0)
            while last_day < day:
                last_day += 1
                dow = (last_day + 3) % 7      # ۰=دوشنبه
                if dow >= 5:                  # شنبه/یکشنبه: رول‌اور نداریم
                    continue
                nights += 3 if dow == 2 else 1
            hit_sl = bar["l"] <= sl if sig == 1 else bar["h"] >= sl
            hit_p1 = bar["h"] >= p1 if sig == 1 else bar["l"] <= p1
            hit_tp = bar["h"] >= tp if sig == 1 else bar["l"] <= tp
            # اگر در یک کندل هم حد ضرر و هم پله لمس شد، بدترین حالت را
            # می‌گیریم (حد ضرر اول) — بدون این فرض، نتیجه به‌شکل مصنوعی
            # بهتر می‌شود؛ در گزارش همین حساسیت نشان داده شده است.
            if partial and qty > 0.5 and hit_sl and hit_p1:
                exitR = -1.0; break
            if hit_sl:
                exitR = ((sl - entry) * sig) / risk; break
            if partial and qty > 0.5 and hit_p1:
                realizedR += 0.5; qty = 0.5; sl = entry
            if hit_tp:
                exitR = k; break
            exitR = ((bar["c"] - entry) * sig) / risk
        if exitR is None:
            exitR = ((h4["c"].iloc[min(i + 30, len(h4) - 1)] - entry) * sig) / risk

        swapR = ((swapL if sig == 1 else swapS) * nights) / risk
        rows.append((idx[i], realizedR + qty * exitR - costR + swapR,
                     costR, nights))
        i += 1
    return pd.DataFrame(rows, columns=["t", "R", "costR", "nights"])


def stats(d):
    if len(d) < 40:
        return None
    gains = d.loc[d["R"] > 0, "R"].sum()
    losses = -d.loc[d["R"] <= 0, "R"].sum()
    return dict(trades=len(d), avgR=d["R"].mean(),
                pf=gains / max(losses, 1e-9),
                sumR=d["R"].sum(),
                h1=d.loc[d["t"] < SPLIT, "R"].sum(),
                h2=d.loc[d["t"] >= SPLIT, "R"].sum(),
                costR=d["costR"].median(), nights=d["nights"].mean())


def screen(partial, tag):
    print(f"\n=== {tag} ===")
    print(f"{'pair':7s} {'cfg':26s} {'trd':>4s} {'avgR':>7s} {'PF':>5s} "
          f"{'h1':>7s} {'h2':>7s} {'cost/R':>6s} {'night':>5s}")
    out = []
    for p in PAIRS:
        cands = []
        for fade in (True, False):
            for k in (2.0, 4.0, 7.0):
                for gate in (0.12, 0.25):
                    s = stats(backtest(p, fade, k, gate, partial))
                    if s:
                        cands.append((s["avgR"], p, fade, k, gate, s))
        if not cands:
            continue
        _, p, fade, k, gate, s = max(cands, key=lambda x: x[0])
        out.append(dict(pair=p, fade=fade, k=k, gate=gate, **s))
        print(f"{p:7s} fade={int(fade)} k={k} g={gate:4.2f}   {s['trades']:4d} "
              f"{s['avgR']:7.3f} {s['pf']:5.2f} {s['h1']:7.1f} {s['h2']:7.1f} "
              f"{s['costR']:6.3f} {s['nights']:5.2f}")
    return pd.DataFrame(out)


if __name__ == "__main__":
    a = screen(True, "الف) با پلهٔ ۱R (نصف حجم) و فرض بدترین‌حالت در کندل مشترک")
    b = screen(False, "ب) بدون پله، یک خروج")
    a.to_csv("/home/user/research/fx_final_partial.csv", index=False)
    b.to_csv("/home/user/research/fx_final_single.csv", index=False)
