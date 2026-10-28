"""
fx_select.py — انتخاب نهایی پارامتر هر جفت با فیلترهای سخت‌گیرانه
================================================================
فلسفه: «بهترین عدد» را انتخاب نمی‌کنیم؛ پارامتری را انتخاب می‌کنیم که از
هفت آزمونِ مقاومت زنده بیرون بیاید. اگر هیچ پارامتری برای یک جفت زنده نماند،
همان جفت با صراحت «پیشنهاد نمی‌شود» علامت می‌خورد.

فیلترها (همه باید پاس شوند):
  F1 تعداد معامله ≥ ۱۰۰
  F2 هر دو نیمهٔ داده مثبت
  F3 ≥ ۶۰٪ همسایه‌ها (±یک پله در N/ATR/هدف) مثبت
  F4 با هزینهٔ ۲ برابر هنوز مثبت
  F5 آزمون صفر (۲۰۰ ورود تصادفی): p ≤ ۰٫۰۵
  F6 در ۲۰ سال D1 همان قاعده منفی نباشد (≥ −۰٫۰۲R)
  F7 سال‌های مثبت ≥ نصف سال‌ها
"""
import numpy as np
import pandas as pd
import fx_lab as L
import sys
sys.path.insert(0, "/home/user/research")
from fx_stage2 import rand_entry_test, yearly_breakdown

S1 = pd.read_csv("/home/user/research/fx_stage1.csv")
S1["score"] = S1.apply(lambda r: L.score(r.to_dict()), axis=1)

TOP_K = 8
out = []

for pair in L.PAIRS:
    s = S1[S1.pair == pair].sort_values("score", ascending=False).head(TOP_K)
    passed, tried = None, []
    for _, r in s.iterrows():
        tf, mode, N, am, k, ex = (r.tf, r["mode"], int(r.N), float(r.atr),
                                  float(r.k), r["exit"])
        m = L.metrics(L.backtest(pair, tf, mode, N, am, k, ex, gate=0.20))
        if not m or m["trades"] < 100:
            tried.append((r.tag if hasattr(r, "tag") else "", "F1")); continue
        if min(m["h1avg"], m["h2avg"]) <= 0:
            tried.append(("", "F2")); continue
        neigh = [L.metrics(L.backtest(pair, tf, mode, max(6, N + dN), max(0.5, am + da),
                                      max(1.5, k + dk), ex, gate=0.20))
                 for dN in (-24, -12, 12, 24) for da in (-0.5, 0.5) for dk in (-1, 1)]
        neigh = [x["avgR"] for x in neigh if x]
        npos = 100.0 * np.mean([x > 0 for x in neigh])
        if npos < 60:
            tried.append(("", f"F3 ({npos:.0f}%)")); continue
        c2 = L.metrics(L.backtest(pair, tf, mode, N, am, k, ex, gate=0.20, cost_mult=2.0))
        if not c2 or c2["avgR"] <= 0:
            tried.append(("", "F4")); continue
        null = rand_entry_test(pair, tf, am, k, ex, n_iter=200)
        p = float((null >= m["avgR"]).mean()) if len(null) else 1.0
        if p > 0.05:
            tried.append(("", f"F5 (p={p:.2f})")); continue
        d1 = L.metrics(L.backtest(pair, "d1", mode, N, am, k, ex, gate=0.20, max_hold=90))
        if d1 and d1["avgR"] < -0.02:
            tried.append(("", "F6")); continue
        if m["pos_years"] * 2 < m["years"]:
            tried.append(("", "F7")); continue
        passed = dict(pair=pair, tf=tf, mode=mode, N=N, atr=am, k=k, exit=ex,
                      trades=m["trades"], avgR=m["avgR"], pf=m["pf"],
                      h1=m["h1avg"], h2=m["h2avg"], neigh=npos, cost2=c2["avgR"],
                      null_p=p, d1_avgR=(d1["avgR"] if d1 else np.nan),
                      pos_years=f"{m['pos_years']}/{m['years']}", dd_R=m["dd"],
                      costR=m["costR"],
                      verdict="✅ پیشنهاد می‌شود")
        break
    if passed:
        out.append(passed)
        print(f"{pair}: ✅ {passed['tf']}/{passed['mode']}/N{passed['N']}/atr{passed['atr']}"
              f"/k{passed['k']} · avgR {passed['avgR']:+.3f} PF {passed['pf']:.2f} "
              f"trd {passed['trades']} · p_صفر {passed['null_p']:.3f}", flush=True)
    else:
        out.append(dict(pair=pair, tf="-", mode="-", N=0, atr=0.0, k=0.0, exit="-",
                        trades=0, avgR=np.nan, pf=np.nan, h1=np.nan, h2=np.nan,
                        neigh=0.0, cost2=np.nan, null_p=np.nan, d1_avgR=np.nan,
                        pos_years="-", dd_R=np.nan, costR=np.nan,
                        verdict="❌ هیچ پارامتری از فیلترها نگذشت — " + ", ".join(t[1] for t in tried[:3])))
        print(f"{pair}: ❌ {out[-1]['verdict']}", flush=True)

df = pd.DataFrame(out)
df.to_csv("/home/user/research/fx_selected.csv", index=False)
print("\nذخیره شد: fx_selected.csv")
print(df[["pair", "verdict"]].to_string(index=False))
