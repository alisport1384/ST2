"""مرحله ۱: جست‌وجوی درشت پارامتر برای هر جفت‌ارز (H4 و H1)."""
import itertools, json, sys, time
import pandas as pd
import fx_lab as L

OUT = "/home/user/research/fx_stage1.csv"

def run_pair(pair):
    rows = []
    for tf, mode, N, am, k, ex in itertools.product(
            ("h4", "h1"), ("fade", "trend"), (12, 24, 48, 96),
            (1.0, 1.5, 2.0, 3.0), (2.0, 3.0, 4.0, 7.0), ("partial", "single")):
        d = L.backtest(pair, tf, mode, N, am, k, ex, gate=0.20)
        m = L.metrics(d)
        if not m:
            continue
        rows.append(dict(pair=pair, tf=tf, mode=mode, N=N, atr=am, k=k, exit=ex, **m))
    return rows

all_rows = []
t0 = time.time()
for p in L.PAIRS:
    r = run_pair(p)
    all_rows += r
    best = max(r, key=L.score)
    print(f"{p}: {len(r)} ترکیب · بهترین امتیاز {L.score(best):.2f} · "
          f"{best['tf']}/{best['mode']}/N{best['N']}/atr{best['atr']}/k{best['k']}/{best['exit']} "
          f"avgR={best['avgR']:.3f} pf={best['pf']:.2f} trd={best['trades']} "
          f"({time.time()-t0:.0f}s)", flush=True)
pd.DataFrame(all_rows).to_csv(OUT, index=False)
print("ذخیره شد:", OUT, len(all_rows), "ردیف")
