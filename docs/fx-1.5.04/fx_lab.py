"""
fx_lab.py — آزمایشگاه جفت‌ارز GoldPin (۱٫۵٫۰۴)
==============================================
هدف: پیدا کردن پارامتر بهینهٔ **هر جفت‌ارز جداگانه** و سنجش اینکه آیا سبد FX
می‌تواند کمبود سود طلا را جبران کند یا نه.

قواعد مدل (هم‌راستا با موتور GoldPin تا جای ممکن):
  • یک پوزیشن باز در هر جفت (مثل موتور: هر نماد یک پوزیشن).
  • ورود: بسته‌شدن قیمت بیرون از سقف/کفِ N کندل.
  • جهت: `trend` = هم‌جهت شکست، `fade` = پادِ شکست.
  • حد ضرر: atrMult × ATR(14). هدف نهایی: k×R.
  • مدل خروج: `single` (یک هدف) یا `partial` (نصف در ۱R + سر‌به‌سر + هدف k).
  • هزینه: اسپرد Pro ECN آلپاری + ۵ دلار در لات رفت‌وبرگشت.
  • سواپ: نرخ رسمی آلپاری، رول ۲۱:۰۰ UTC، چهارشنبه ×۳، شنبه/یکشنبه صفر
    (همان قاعده‌ای که در ۱٫۵٫۰۳ در موتور پیاده شد).
  • ابهام کندل مشترک: اگر در یک کندل هم SL و هم پله لمس شود، پیش‌فرض
    «بدترین حالت» (SL اول) است؛ با `same_bar="optimist"` برعکسش هم آزموده می‌شود.

واحدها: همه‌چیز در R. هیچ عددی به دلار تبدیل نمی‌شود تا خطای واحد پیش نیاید.
"""
from __future__ import annotations

import json
import os
import numpy as np
import pandas as pd

DATA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")
PAIRS = ["EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "USDCAD", "USDCHF",
         "NZDUSD", "EURJPY", "GBPJPY", "EURGBP", "AUDJPY", "EURCHF"]
AL = json.load(open("/tmp/fx/alpari_fx.json"))["proEcnMt5"]
COMMISSION_PER_LOT = 5.0          # رفت‌وبرگشت، Pro ECN
DIGITS = {p: (3 if p.endswith("JPY") else 5) for p in PAIRS}
DIGITS["XAUUSD"] = 2

_CACHE: dict = {}


def load(pair: str, tf: str) -> pd.DataFrame:
    """tf ∈ {'h1','h4','d1'} — از فایل‌های خام h1/d1 با تجمیع."""
    key = (pair, tf)
    if key in _CACHE:
        return _CACHE[key]
    src = "d1" if tf == "d1" else "h1"
    df = pd.read_csv(f"{DATA}/{pair}_{src}.csv")
    df["dt"] = pd.to_datetime(df["t"], unit="s", utc=True)
    df = df.set_index("dt")[["o", "h", "l", "c", "v"]]
    if src == "h1" and tf == "h4":
        df = df.resample("4h").agg({"o": "first", "h": "max", "l": "min",
                                    "c": "last", "v": "sum"}).dropna()
    df = df[(df["h"] > 0) & (df["l"] > 0) & (df["c"] > 0)]
    if tf in ("h1", "h4"):
        df = df[df.index.dayofweek < 5]        # آخر هفته‌های ناقص را دور بریز
    _CACHE[key] = df
    return df


def atr_series(df: pd.DataFrame, n: int = 14) -> np.ndarray:
    h, l, c = df["h"].values, df["l"].values, df["c"].values
    pc = np.concatenate([[c[0]], c[:-1]])
    tr = np.maximum(h - l, np.maximum(np.abs(h - pc), np.abs(l - pc)))
    out = np.full(len(tr), np.nan)
    cs = np.cumsum(np.nan_to_num(tr))
    for i in range(n - 1, len(tr)):
        out[i] = (cs[i] - (cs[i - n] if i >= n else 0.0)) / n
    return out


# نرخ تبدیل «یک واحد ارز مظنه» به دلار — از آخرین قیمت‌های همان دیتاست.
# چرا لازم است: هزینهٔ بروکر به **دلار** اعلام می‌شود، ولی معامله در واحد **قیمت**
# همان جفت انجام می‌شود. برای جفت‌های دلاری (EURUSD) هر دو یکی‌اند، برای بقیه نه.
def _usd_per_quote() -> dict:
    def last(pair, side="c"):
        df = load(pair, "d1")
        return float(df[side].iloc[-1])
    return {"USD": 1.0, "JPY": 1.0 / last("USDJPY"), "CAD": 1.0 / last("USDCAD"),
            "CHF": 1.0 / last("USDCHF"),
            "GBP": last("GBPUSD")}      # دلار به‌ازای یک پوند


_CONV = None


def cost_price(pair: str, price: float) -> tuple[float, float, float]:
    """
    بازگشت: (هزینهٔ رفت‌وبرگشت، سواپ خرید، سواپ فروش) در واحد قیمتِ نماد.

      • اسپرد در «پوینت» (۱۰^-رقم) اعلام می‌شود.
      • کمیسیون Pro ECN: ۲٫۵ دلار در لات در هر طرف ⇒ ۵ دلار رفت‌وبرگشت روی
        ۱۰۰٬۰۰۰ واحد پایه.
      • سواپ: دلار بر لات شبانه.
      • تبدیل دلار → واحد قیمت: تقسیم بر «دلار به‌ازای یک واحد ارز مظنه».
        ⚠ دو نسخهٔ قبلی این تبدیل را اشتباه می‌کردند (یکی سواپ را صفر می‌کرد،
        یکی برای جفت‌های کراس اشتباه بود)؛ این نسخه آزمون واحد دارد.
    """
    global _CONV
    if pair == "XAUUSD":
        spread = 0.237                        # دلار بر اونس
        comm = 2.0 * price * 18.0 / 1e6       # ۱۸ دلار بر یک میلیون، دو طرف
        return spread + comm, -0.6367, +0.4101

    if _CONV is None:
        _CONV = _usd_per_quote()
    quote = pair[3:]
    f = 1.0 / _CONV[quote]                     # واحد قیمت به‌ازای یک دلار

    a = AL[pair]
    point = 10.0 ** (-DIGITS[pair])
    spread = a["spread"] * point
    comm = (COMMISSION_PER_LOT / 100_000.0) * f
    swap_l = (a["swapL"] / 100_000.0) * f
    swap_s = (a["swapS"] / 100_000.0) * f
    return spread + comm, swap_l, swap_s


def backtest(pair: str, tf: str = "h4", mode: str = "fade", N: int = 24,
             atr_mult: float = 1.5, k: float = 4.0, exit_model: str = "partial",
             gate: float = 0.20, hour_from: int = 0, hour_to: int = 24,
             max_hold: int = 60, same_bar: str = "worst",
             use_swap: bool = True, cost_mult: float = 1.0) -> pd.DataFrame:
    df = load(pair, tf)
    if len(df) < 300:
        return pd.DataFrame(columns=["t", "R", "costR", "nights", "Rgross"])
    o, h, l, c = (df[x].values for x in ("o", "h", "l", "c"))
    idx = df.index
    A = atr_series(df)
    px = float(c[-1])
    cost, swap_l, swap_s = cost_price(pair, px)
    cost *= cost_mult

    hi = pd.Series(h).rolling(N).max().shift(1).values
    lo = pd.Series(l).rolling(N).min().shift(1).values
    hour = idx.hour.values
    days = (idx.asi8 // 86_400_000_000_000)

    rows = []
    i = max(N, 15)
    n = len(df)
    while i < n - 1:
        a = A[i]
        if not (a > 0) or not np.isfinite(a):
            i += 1; continue
        if hour[i] < hour_from or hour[i] >= hour_to:
            i += 1; continue
        if c[i] > hi[i]:
            sig = -1 if mode == "fade" else 1
        elif c[i] < lo[i]:
            sig = 1 if mode == "fade" else -1
        else:
            i += 1; continue

        entry = c[i]
        risk = atr_mult * a
        costR = cost / risk
        if gate > 0 and costR > gate:
            i += 1; continue

        sl = entry - sig * risk
        tp = entry + sig * k * risk
        p1 = entry + sig * risk
        realized = 0.0
        qty = 1.0
        nights = 0
        last_day = days[i] - (1 if hour[i] < 21 else 0)
        exit_r = None
        exit_i = i
        j = i + 1
        end = min(i + 1 + max_hold, n)
        while j < end:
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
                if same_bar == "worst":
                    exit_r = ((sl - entry) * sig) / risk
                    exit_i = j
                    break
            if hit_sl:
                exit_r = ((sl - entry) * sig) / risk
                exit_i = j
                break
            if exit_model == "partial" and qty > 0.5 and hit_p1:
                realized += 0.5
                qty = 0.5
                sl = entry
            if hit_tp:
                exit_r = k
                exit_i = j
                break
            exit_r = ((bc - entry) * sig) / risk
            exit_i = j
            j += 1
        if exit_r is None:
            k2 = min(i + max_hold, n - 1)
            exit_r = ((c[k2] - entry) * sig) / risk
            exit_i = k2

        gross = realized + qty * exit_r
        swapR = 0.0
        if use_swap:
            swapR = ((swap_l if sig == 1 else swap_s) * nights) / risk
        rows.append((idx[i], gross - costR + swapR, costR, nights, gross))
        # ⚠ یک پوزیشن در هر جفت (مثل موتور: هر نماد یک پوزیشن باز).
        # بدون این خط، معامله‌های هم‌پوشان ساخته می‌شدند و آمار مصنوعی خوب می‌شد.
        i = max(i + 1, exit_i + 1)
    return pd.DataFrame(rows, columns=["t", "R", "costR", "nights", "Rgross"])


def metrics(d: pd.DataFrame, split=None) -> dict | None:
    if len(d) < 30:
        return None
    R = d["R"].values
    gains = R[R > 0].sum()
    losses = -R[R <= 0].sum()
    split = split or d["t"].iloc[len(d) // 2]
    h1 = d.loc[d["t"] < split, "R"]
    h2 = d.loc[d["t"] >= split, "R"]
    yearly = d.groupby(d["t"].dt.year)["R"].sum()
    return dict(
        trades=len(d), avgR=float(R.mean()), pf=float(gains / max(losses, 1e-9)),
        sumR=float(R.sum()), win=float(100 * (R > 0).mean()),
        h1R=float(h1.sum()), h2R=float(h2.sum()),
        h1avg=float(h1.mean()) if len(h1) else 0.0,
        h2avg=float(h2.mean()) if len(h2) else 0.0,
        years=len(yearly), pos_years=int((yearly > 0).sum()),
        worst_year=float(yearly.min()) if len(yearly) else 0.0,
        costR=float(d["costR"].median()), nights=float(d["nights"].mean()),
        dd=float(_max_dd(R)),
    )


def _max_dd(R: np.ndarray) -> float:
    eq = np.cumsum(R)
    peak = np.maximum.accumulate(np.concatenate([[0.0], eq]))[1:]
    return float((peak - eq).max()) if len(eq) else 0.0


def score(m: dict) -> float:
    """
    امتیاز محافظه‌کارانه: به کوچک‌ترین نیمه وزن می‌دهد (نه به بهترین نیمه)،
    سال‌های مثبت را پاداش و افت سرمایه را جریمه می‌کند. هدف: پارامتری که
    «در بدترین دوره هم کار کند»، نه پارامتری که در کل دوره درخشان است.
    """
    if not m or m["trades"] < 100:
        return -9e9
    worst = min(m["h1avg"], m["h2avg"])
    pos_ratio = m["pos_years"] / max(m["years"], 1)
    return (worst * 100          # بدترین نیمه
            + m["avgR"] * 50     # انتظار کل
            + pos_ratio * 5      # پایداری سالانه
            - m["dd"] / max(m["trades"], 1) * 10)
