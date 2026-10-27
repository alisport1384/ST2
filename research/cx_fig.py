import csv, os
from datetime import datetime, timezone
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

OUT="/home/user/ST2work/out/cx20y"
SEL=[("B1-L0","baseline v1.4.51 (cx=0)"),
     ("FINAL-defaults","FINAL defaults (cx=3, gate .33, box .10)"),
     ("B1-g35box10","gate .35 + box .10 (best avgR)"),
     ("B1-g35box10m0","same, risk-based sizing 1%")]

def curve(tag):
    p=os.path.join(OUT,f"{tag}_trades.csv")
    rows=list(csv.DictReader(open(p)))
    rows.sort(key=lambda r:int(r["entryT"]))
    eq=100000.0; xs=[]; ys=[]; peak=eq; dds=[]
    for r in rows:
        eq+=float(r["pnl"]); peak=max(peak,eq)
        xs.append(datetime.fromtimestamp(int(r["exitT"])/1000,tz=timezone.utc))
        ys.append(eq); dds.append(-(peak-eq)/peak*100.0)
    return xs,ys,dds

fig,axs=plt.subplots(2,1,figsize=(11,7),dpi=135,sharex=True,gridspec_kw=dict(height_ratios=[2.4,1]))
for tag,lab in SEL:
    xs,ys,dds=curve(tag)
    axs[0].plot(xs,ys,lw=1.6,label=f"{lab}  ({ys[-1]-100000:+.0f}$)")
    axs[1].plot(xs,dds,lw=1.2,label=lab)
axs[0].axhline(100000,color="k",lw=.6,ls="--")
axs[0].set_ylabel("equity $ (start 100,000)")
axs[0].set_title("GoldPin v1.4.52 — 20y XAUUSD M1, commission 0.7, money mode MIXED")
axs[0].legend(fontsize=8); axs[0].grid(alpha=.25)
axs[1].set_ylabel("drawdown %")
axs[1].grid(alpha=.25); axs[1].legend(fontsize=7)
fig.autofmt_xdate()
fig.tight_layout()
fig.savefig("/home/user/ST2work/docs/cx20y_v1.4.52.png")
print("saved")
