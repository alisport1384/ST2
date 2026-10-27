#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
گزارش‌ساز آزمون ۲۰ سالهٔ موتور ترکیبی (۱٫۴٫۵۲).

ورودی: پوشهٔ out/cx20y  (هر spec: <tag>.csv خلاصه + <tag>_trades.csv ریز معاملات)
خروجی: out/cx20y/summary.csv، out/cx20y/report.md، out/cx20y/blocks.csv،
        و نمودار منحنی سرمایهٔ بهترین پیکربندی: out/cx20y/equity_best.png
"""
import csv, glob, os, math, statistics as st
from datetime import datetime, timezone

OUT = "/home/user/ST2work/out/cx20y"


def load_summary():
    rows = []
    for f in sorted(glob.glob(os.path.join(OUT, "*.csv"))):
        b = os.path.basename(f)
        if b in ("summary.csv", "blocks.csv") or b.endswith("_trades.csv"):
            continue
        if os.path.getsize(f) == 0:
            continue
        with open(f) as fh:
            r = list(csv.DictReader(fh))
        if not r:
            continue
        r[0]["file"] = b[:-4]
        rows.append(r[0])
    return rows


def fnum(d, k, defv=float("nan")):
    try:
        return float(d.get(k, ""))
    except Exception:
        return defv


def trades_stats(path):
    """آمار ریز از فایل معاملات: بلوک‌های ۵ساله، سری باخت، payoff، افت درصدی."""
    if not os.path.exists(path) or os.path.getsize(path) == 0:
        return None
    rows = list(csv.DictReader(open(path)))
    if not rows:
        return None
    for r in rows:
        r["R"] = float(r["R"])
        r["pnl"] = float(r["pnl"])
        r["exitT"] = int(r["exitT"])
        r["entryT"] = int(r["entryT"])
    rows.sort(key=lambda r: r["entryT"])
    eq = 100_000.0
    peak = eq
    maxdd_usd = 0.0
    maxdd_pct = 0.0
    curve = []
    streak = cur = 0
    for r in rows:
        eq += r["pnl"]
        peak = max(peak, eq)
        dd = peak - eq
        if dd > maxdd_usd:
            maxdd_usd = dd
            maxdd_pct = dd / peak * 100.0
        curve.append((r["exitT"], eq))
        if r["pnl"] < 0:
            cur += 1
            streak = max(streak, cur)
        else:
            cur = 0
    wins = [r["pnl"] for r in rows if r["pnl"] > 0]
    loss = [r["pnl"] for r in rows if r["pnl"] < 0]
    rs = [r["R"] for r in rows]
    # بلوک‌های ۵ساله بر اساس سال ورود
    blocks = {}
    for r in rows:
        y = datetime.fromtimestamp(r["entryT"] / 1000, tz=timezone.utc).year
        b = (y - 2006) // 5
        blocks.setdefault(b, []).append(r)
    bl = []
    for b in sorted(blocks):
        g = blocks[b]
        bl.append(dict(
            block=f"{2006 + 5 * b}-{2006 + 5 * b + 4}",
            n=len(g),
            net=sum(x["pnl"] for x in g),
            avgR=sum(x["R"] for x in g) / len(g),
            win=100.0 * sum(1 for x in g if x["pnl"] > 0) / len(g),
        ))
    # Sharpe-ish: میانگین/انحراف R
    mu = st.mean(rs)
    sd = st.pstdev(rs) if len(rs) > 1 else 0.0
    return dict(
        n=len(rows), net=sum(r["pnl"] for r in rows), avgR=mu,
        sdR=sd, sharpe=mu / sd * math.sqrt(len(rs)) if sd > 0 else 0.0,
        maxdd_usd=maxdd_usd, maxdd_pct=maxdd_pct,
        avg_win=st.mean(wins) if wins else 0.0,
        avg_loss=st.mean(loss) if loss else 0.0,
        payoff=(st.mean(wins) / abs(st.mean(loss))) if wins and loss else 0.0,
        max_loss_streak=streak,
        best=max(rs), worst=min(rs),
        blocks=bl, curve=curve,
    )


def main():
    rows = load_summary()
    if not rows:
        print("no data")
        return
    out = []
    for r in rows:
        tag = r["file"]
        t = trades_stats(os.path.join(OUT, f"{tag}_trades.csv"))
        out.append((tag, r, t))
    # جدول خلاصه
    hdr = ("tag", "trades", "win%", "net$", "avgR", "PF", "maxDD$", "worst12$", "final$", "mo+",
           "DD%", "streak", "payoff", "sharpeR")
    lines = ["| " + " | ".join(hdr) + " |", "|" + "---|" * len(hdr)]
    with open(os.path.join(OUT, "summary.csv"), "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(hdr)
        for tag, r, t in out:
            row = [tag, int(fnum(r, "trades")), round(fnum(r, "winRate"), 2), round(fnum(r, "netCost"), 2),
                   round(fnum(r, "avgRCost"), 4), round(fnum(r, "pf"), 3), round(fnum(r, "maxDD"), 2),
                   round(fnum(r, "worst12"), 2), round(fnum(r, "finalEquity"), 2),
                   f"{r.get('posMonths','?')}/{len((r.get('months') or '').split('|')) if r.get('months') else '?'}",
                   round(t["maxdd_pct"], 2) if t else "", t["max_loss_streak"] if t else "",
                   round(t["payoff"], 3) if t else "", round(t["sharpe"], 2) if t else ""]
            w.writerow(row)
            lines.append("| " + " | ".join(str(x) for x in row) + " |")
    # بلوک‌ها
    with open(os.path.join(OUT, "blocks.csv"), "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["tag", "block", "trades", "net$", "avgR", "win%"])
        for tag, r, t in out:
            if not t:
                continue
            for b in t["blocks"]:
                w.writerow([tag, b["block"], b["n"], round(b["net"], 2), round(b["avgR"], 4), round(b["win"], 2)])
    txt = ["# خلاصهٔ جاروب ۲۰سالهٔ موتور ترکیبی", "", *lines, "", "## بلوک‌های پنج‌ساله", ""]
    tags = [x[0] for x in out]
    blk_names = sorted({b["block"] for _, _, t in out if t for b in t["blocks"]})
    txt.append("| tag | " + " | ".join(blk_names) + " |")
    txt.append("|" + "---|" * (len(blk_names) + 1))
    for tag, r, t in out:
        if not t:
            continue
        d = {b["block"]: b for b in t["blocks"]}
        txt.append("| " + tag + " | " + " | ".join(
            f'{d[b]["net"]:+.0f}$ / {d[b]["avgR"]:+.4f}R' if b in d else "-" for b in blk_names) + " |")
    open(os.path.join(OUT, "report.md"), "w").write("\n".join(txt) + "\n")
    print("\n".join(lines))
    print()
    print("\n".join(txt[-len(blk_names) - 3:]))

    # نمودار منحنی سرمایهٔ بهترین (بیشترین avgR بین پیکربندی‌های cx>0)
    def score(x):
        tag, r, t = x
        return fnum(r, "avgRCost")
    best = max([x for x in out if "L0" not in x[0]] or out, key=score)
    if best[2]:
        try:
            import matplotlib
            matplotlib.use("Agg")
            import matplotlib.pyplot as plt
            fig, ax = plt.subplots(figsize=(11, 5), dpi=130)
            for tag, r, t in out:
                if not t:
                    continue
                xs = [datetime.fromtimestamp(p[0] / 1000, tz=timezone.utc) for p in t["curve"]]
                ys = [p[1] for p in t["curve"]]
                lw = 2.0 if tag == best[0] else 1.0
                alpha = 1.0 if tag == best[0] else 0.45
                ax.plot(xs, ys, lw=lw, alpha=alpha, label=f"{tag} ({fnum(r,'netCost'):+.0f}$)")
            ax.axhline(100_000, color="k", lw=0.6, ls="--")
            ax.set_title("GoldPin v1.4.52 composite — 20y equity curves (fixed 1% risk path)")
            ax.set_ylabel("equity $ (start 100,000)")
            ax.legend(fontsize=6, ncol=2)
            ax.grid(alpha=0.25)
            fig.autofmt_xdate()
            fig.tight_layout()
            fig.savefig(os.path.join(OUT, "equity_best.png"))
            print(f"\nBEST by avgR: {best[0]}  avgR={fnum(best[1],'avgRCost'):+.4f}  net={fnum(best[1],'netCost'):+.0f}$")
        except Exception as e:
            print("plot skipped:", e)


if __name__ == "__main__":
    main()
