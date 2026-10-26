#!/usr/bin/env python3
"""
نمودار منحنی سرمایه از خروجی جاروب (فایل CSV هارنس `cli`).

هر ردیف فایل جاروب یک ترکیب پارامتر است و ستون `months` سود/زیان هر ماه را
به شکل `YYYY-MM:مقدار|...` نگه می‌دارد. این اسکریپت آن را به منحنی تجمعی تبدیل
می‌کند و یک PNG می‌سازد (برای گزارش‌ها/مقایسهٔ مکانیزم‌ها).

نمونه:
  python3 tools/plot_curves.py --csv out/sweep_10y.csv --out docs/equity_10y.png \
      --title "XAUUSD M1 · 10y · cmsn=0.7"
"""
import argparse
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import csv
from datetime import datetime


def parse_months(s: str):
    out = []
    if not s:
        return out
    for part in s.split("|"):
        if ":" not in part:
            continue
        k, v = part.rsplit(":", 1)
        try:
            out.append((k, float(v)))
        except ValueError:
            continue
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--title", default="")
    ap.add_argument("--max-curves", type=int, default=8)
    ap.add_argument("--initial", type=float, default=100000.0)
    args = ap.parse_args()

    rows = []
    with open(args.csv, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            m = parse_months(r.get("months", ""))
            if m:
                rows.append((r, m))

    if not rows:
        raise SystemExit("ستون months در فایل نبود — جاروب را با --out دوباره اجرا کنید")

    # مرتب‌سازی بر اساس سود خالص پس‌از‌کارمزد (netCost)
    rows.sort(key=lambda x: float(x[0].get("netCost") or 0), reverse=True)
    rows = rows[: args.max_curves]

    fig, ax = plt.subplots(figsize=(12, 6.5), dpi=130)
    for r, m in rows:
        eq = args.initial
        xs, ys = [], []
        for k, v in m:
            eq += v
            try:
                xs.append(datetime.strptime(k, "%Y-%m"))
            except ValueError:
                continue
            ys.append(eq)
        if not xs:
            continue
        tag = r.get("tag", "?")
        net = float(r.get("netCost") or 0)
        ax.plot(xs, ys, linewidth=1.6, label=f"{tag}  (net={net:,.0f} · PF={float(r.get('pf') or 0):.2f})")

    ax.axhline(args.initial, color="#888", linewidth=0.8, linestyle="--")
    ax.set_title(args.title or "Equity curve", fontsize=12)
    ax.set_ylabel("Equity (USD)")
    ax.grid(alpha=0.25)
    ax.legend(fontsize=8, loc="best")
    fig.autofmt_xdate()
    fig.tight_layout()
    fig.savefig(args.out)
    print("saved", args.out)


if __name__ == "__main__":
    main()
