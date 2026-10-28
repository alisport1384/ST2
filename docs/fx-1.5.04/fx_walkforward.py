"""
fx_walkforward.py — آزمون واقعی بیرون‌نمونه (Walk-Forward)
==========================================================
روش: ۶۰٪ اول داده فقط برای **انتخاب** پارامتر، ۴۰٪ آخر فقط برای **داوری**.
این تنها آزمونی است که بیش‌برازشِ جست‌وجو را حذف می‌کند.

اگر OOS مثبت باشد ⇒ پارامتر واقعاً کار می‌کند.
اگر OOS منفی/صفر باشد ⇒ هر عدد خوبِ کل-دوره، محصول جست‌وجو بوده است.
"""
import itertools
import numpy as np
import pandas as pd
import fx_lab as L

OUT = "/home/user/research/fx_walkforward.csv"
GRID = list(itertools.product(("h4", "h1"), ("fade", "trend"), (12, 24, 48, 96),
                              (1.0, 1.5, 2.0, 3.0), (2.0, 3.0, 4.0, 7.0),
                              ("single", "partial")))


def split_metrics(d, cut):
    a = d[d["t"] < cut]
    b = d[d["t"] >= cut]
    def st(x):
        if len(x) < 20:
            return None
        R = x["R"].values
        g = R[R > 0].sum(); l = -R[R <= 0].sum()
        return dict(n=len(x), avgR=float(R.mean()), pf=float(g / max(l, 1e-9)),
                    sumR=float(R.sum()))
    return st(a), st(b)


rows = []
for pair in L.PAIRS:
    span = L.load(pair, "h1").index
    cut = span[0] + (span[-1] - span[0]) * 0.6
    best = None
    oos_all = []
    for tf, mode, N, am, k, ex in GRID:
        d = L.backtest(pair, tf, mode, N, am, k, ex, gate=0.20)
        ins, oos = split_metrics(d, cut)
        if not ins or not oos:
            continue
        oos_all.append((ins["avgR"], oos["avgR"]))
        if ins["n"] < 60 or ins["avgR"] <= 0:
            continue
        if best is None or ins["avgR"] > best[0]:
            best = (ins["avgR"], tf, mode, N, am, k, ex, ins, oos)
    if best is None:
        print(f"{pair}: هیچ پارامتری در نمونهٔ اول مثبت نشد", flush=True)
        rows.append(dict(pair=pair, note="no IS winner"))
        continue
    _, tf, mode, N, am, k, ex, ins, oos = best
    arr = np.array(oos_all)
    rows.append(dict(pair=pair, tf=tf, mode=mode, N=N, atr=am, k=k, exit=ex,
                     is_n=ins["n"], is_avgR=ins["avgR"], is_pf=ins["pf"],
                     oos_n=oos["n"], oos_avgR=oos["avgR"], oos_pf=oos["pf"],
                     oos_sumR=oos["sumR"],
                     grid_mean_oos=float(arr[:, 1].mean()),
                     grid_med_oos=float(np.median(arr[:, 1])),
                     pct_grid_better=float(100 * (arr[:, 1] > oos["avgR"]).mean()),
                     note=""))
    print(f"{pair}: IS {tf}/{mode}/N{N}/atr{am}/k{k}/{ex} → IS avgR {ins['avgR']:+.3f} "
          f"| **OOS avgR {oos['avgR']:+.3f}** (PF {oos['pf']:.2f}, n={oos['n']}) "
          f"| میانهٔ کل شبکه در OOS {np.median(arr[:, 1]):+.3f}", flush=True)

pd.DataFrame(rows).to_csv(OUT, index=False)
print("\nذخیره شد:", OUT)
