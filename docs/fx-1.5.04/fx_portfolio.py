"""
fx_portfolio.py — آزمون «خانوادهٔ قاعده» و سبد طلا + جفت‌ارز
============================================================
دو سؤال:
  ۱) آیا یک قاعدهٔ **مشترک** (نه پارامترِ بهینه‌شدهٔ هر جفت) روی همهٔ جفت‌ها کار می‌کند؟
     اگر بله، لبه ساختاری است؛ اگر نه، هر «بهترین پارامتر» فقط بیش‌برازش جست‌وجو است.
  ۲) آیا سبد جفت‌ارزها کمبود سود طلا را جبران می‌کند؟ (بازده، افت، همبستگی)
"""
import itertools
import numpy as np
import pandas as pd
import fx_lab as L

RISK_PER_TRADE = 0.005          # ۰٫۵٪ سرمایه در هر معامله
CAPITAL = 100_000.0
ALL = L.PAIRS + ["XAUUSD"]


def daily_curve(d: pd.DataFrame, risk=RISK_PER_TRADE):
    """منحنی روزانه از سری R هر معامله (ریسک ثابت درصدی)."""
    if d.empty:
        return pd.Series(dtype=float)
    s = d.set_index("t")["R"] * risk * CAPITAL
    return s.groupby(s.index.floor("D")).sum()


# ── ۱) خانوادهٔ قاعده ────────────────────────────────────────────────────────
def family_test():
    rows = []
    fams = list(itertools.product(("fade", "trend"), ("h4", "h1"), (24, 48, 96),
                                  (1.0, 1.5, 2.0), (3.0, 4.0, 7.0)))
    for mode, tf, N, am, k in fams:
        res = []
        for p in L.PAIRS:
            m = L.metrics(L.backtest(p, tf, mode, N, am, k, "single", gate=0.20))
            if m and m["trades"] >= 60:
                res.append((p, m["avgR"]))
        if len(res) < 8:
            continue
        vals = np.array([v for _, v in res])
        rows.append(dict(mode=mode, tf=tf, N=N, atr=am, k=k, n_pairs=len(res),
                         pos=int((vals > 0).sum()), mean_avgR=float(vals.mean()),
                         med_avgR=float(np.median(vals)),
                         # t آمارهٔ سادهٔ عرضی: میانگین ÷ خطای معیار
                         t=float(vals.mean() / (vals.std(ddof=1) / np.sqrt(len(vals))))))
    return pd.DataFrame(rows).sort_values(["pos", "t"], ascending=False)


# ── ۲) سبد ─────────────────────────────────────────────────────────────────
CHOSEN = {   # از مرحلهٔ ۲ (پارامتر بهینهٔ هر جفت) — برای مقایسه
    "EURUSD": ("h1", "fade", 12, 1.0, 7.0),
    "GBPUSD": ("h4", "fade", 24, 1.0, 7.0),
    "USDJPY": ("h1", "trend", 24, 1.5, 7.0),
    "AUDUSD": ("h4", "fade", 48, 2.0, 3.0),
    "USDCAD": ("h4", "fade", 12, 2.0, 2.0),
    "USDCHF": ("h4", "fade", 48, 1.0, 7.0),
    "NZDUSD": ("h4", "fade", 96, 1.0, 4.0),
    "EURJPY": ("h1", "fade", 48, 3.0, 7.0),
    "GBPJPY": ("h4", "fade", 12, 1.5, 4.0),
    "EURGBP": ("h1", "fade", 48, 1.5, 2.0),
    "AUDJPY": ("h4", "fade", 12, 3.0, 3.0),
    "EURCHF": ("h4", "fade", 24, 2.0, 2.0),
}


def build_book(family=None, pairs=None, use_gold=True):
    """منحنی روزانهٔ سبد. اگر family داده شود، برای همهٔ جفت‌ها همان قاعده به کار می‌رود."""
    book, trades = {}, {}
    for p in (pairs or L.PAIRS):
        cfg = family or CHOSEN.get(p)
        if cfg is None:
            continue
        tf, mode, N, am, k = cfg
        d = L.backtest(p, tf, mode, N, am, k, "single", gate=0.20)
        book[p] = daily_curve(d)
        trades[p] = L.metrics(d)
    if use_gold:
        d = L.backtest("XAUUSD", "h4", "fade", 24, 1.5, 4.0, "single", gate=1.0)
        book["XAUUSD"] = daily_curve(d)
        trades["XAUUSD"] = L.metrics(d)
    return book, trades


def summarize(book, name):
    df = pd.DataFrame(book).fillna(0.0).sort_index()
    port = df.sum(axis=1)                    # هر جفت ریسک ۰٫۵٪ مستقل
    eq = CAPITAL + port.cumsum()
    peak = eq.cummax()
    dd = (peak - eq).max()
    years = max((df.index[-1] - df.index[0]).days / 365.25, 0.1)
    cagr = (eq.iloc[-1] / CAPITAL) ** (1 / years) - 1
    monthly = port.resample("ME").sum()
    sharpe = monthly.mean() / monthly.std() * np.sqrt(12) if monthly.std() > 0 else np.nan
    return dict(name=name, net=float(port.sum()), cagr=100 * cagr, dd=float(dd),
                dd_pct=100 * dd / CAPITAL, sharpe=float(sharpe),
                months=len(monthly), pos_months=int((monthly > 0).sum()),
                best_month=float(monthly.max()), worst_month=float(monthly.min()))


if __name__ == "__main__":
    print("=== ۱) آزمون خانوادهٔ قاعده (قاعدهٔ یکسان روی همهٔ جفت‌ها) ===")
    fam = family_test()
    fam.to_csv("/home/user/research/fx_families.csv", index=False)
    print(fam.head(12).to_string(index=False, float_format=lambda v: f"{v:7.3f}"))

    print("\n=== ۲) سبد با پارامتر بهینهٔ هر جفت (stage2) ===")
    book_opt, tr_opt = build_book(family=None)
    rows = []
    for p, m in tr_opt.items():
        rows.append(dict(pair=p, trades=m["trades"], avgR=m["avgR"], pf=m["pf"],
                         h1=m["h1avg"], h2=m["h2avg"], pos_years=f"{m['pos_years']}/{m['years']}",
                         dd_R=m["dd"]))
    print(pd.DataFrame(rows).to_string(index=False, float_format=lambda v: f"{v:7.3f}"))

    gold = {k: v for k, v in book_opt.items() if k == "XAUUSD"}
    fx = {k: v for k, v in book_opt.items() if k != "XAUUSD"}
    print("\n--- خلاصهٔ سبد (ریسک ۰٫۵٪ در هر معامله، سرمایه ۱۰۰٬۰۰۰) ---")
    summ = [summarize(gold, "طلا تنها (همان مدل)"),
            summarize(fx, "سبد ۱۲ جفت‌ارز"),
            summarize(book_opt, "طلا + ۱۲ جفت‌ارز")]
    print(pd.DataFrame(summ).to_string(index=False, float_format=lambda v: f"{v:8.2f}"))

    # همبستگی ماهانه
    dfg = pd.DataFrame(gold).fillna(0).resample("ME").sum().sum(axis=1)
    dff = pd.DataFrame(fx).fillna(0).resample("ME").sum().sum(axis=1)
    j = pd.concat([dfg, dff], axis=1, keys=["gold", "fx"]).dropna()
    print(f"\nهمبستگی ماهانهٔ طلا و سبد FX: {j['gold'].corr(j['fx']):+.3f} "
          f"(n={len(j)} ماه)")
    print("همبستگی جفت‌به‌جفت با طلا:")
    cor = {}
    for p, s in fx.items():
        m = pd.concat([dfg, s.resample("ME").sum()], axis=1, keys=["g", p]).dropna()
        if len(m) > 6:
            cor[p] = m["g"].corr(m[p])
    print(pd.Series(cor).sort_values().to_string(float_format=lambda v: f"{v:+.3f}"))
