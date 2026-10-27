#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ساخت گزارش HTML فارسی (RTL) از نتایج جاروب ۲۰سالهٔ موتور ترکیبی ۱٫۴٫۵۲."""
import base64, csv, os
from datetime import datetime, timezone

OUT = "/home/user/ST2work/out/cx20y"
PNG = "/home/user/ST2work/docs/cx20y_v1.4.52.png"
DST = "/home/user/ST2work/docs/GOZARESH-AZMAYESH-CX-20Y-v1.4.52.html"

FA = {"B1-L0": "پایهٔ ۱٫۴٫۵۱ (cx=0)", "FINAL-defaults": "پیکربندی نهایی (پیش‌فرض ۱٫۴٫۵۲)"}

rows = list(csv.DictReader(open(os.path.join(OUT, "summary.csv"))))
blocks = list(csv.DictReader(open(os.path.join(OUT, "blocks.csv"))))
order = {r["tag"]: i for i, r in enumerate(rows)}
blk = {}
for b in blocks:
    blk.setdefault(b["tag"], {})[b["block"]] = b
blk_names = sorted({b["block"] for b in blocks})


def year_table(tag, path):
    rs = list(csv.DictReader(open(path)))
    acc = {}
    for r in rs:
        y = datetime.fromtimestamp(int(r["entryT"]) / 1000, tz=timezone.utc).year
        d = acc.setdefault(y, [0, 0.0, 0.0])
        d[0] += 1; d[1] += float(r["pnl"]); d[2] += float(r["R"])
    out = []
    for y in sorted(acc):
        n, p, rr = acc[y]
        out.append((y, n, p, rr / n))
    return out


def num(x, d=2):
    try:
        return f"{float(x):,.{d}f}"
    except Exception:
        return str(x)


def cls(x):
    try:
        return "pos" if float(x) > 0 else ("neg" if float(x) < 0 else "")
    except Exception:
        return ""


def summary_table():
    head = ("برچسب", "معاملات", "نرخ برد٪", "سود خالص $", "میانگین R", "ضریب سود",
            "افت $", "افت٪", "بدترین ۱۲ماه $", "سری باخت", "payoff", "شارپ R", "ماه مثبت")
    h = "<tr>" + "".join(f"<th>{c}</th>" for c in head) + "</tr>"
    body = []
    for r in rows:
        t = r["tag"]
        name = FA.get(t, t)
        style = ' class="hl"' if t in FA else ""
        body.append(
            f"<tr{style}><td>{name}</td><td>{r['trades']}</td><td>{num(r['win%'],1)}</td>"
            f"<td class='{cls(r['net$'])}'>{num(r['net$'],0)}</td><td class='{cls(r['avgR'])}'>{num(r['avgR'],4)}</td>"
            f"<td>{num(r['PF'],3)}</td><td>{num(r['maxDD$'],0)}</td><td>{num(r['DD%'],2)}</td>"
            f"<td class='{cls(r['worst12$'])}'>{num(r['worst12$'],0)}</td><td>{r['streak']}</td>"
            f"<td>{num(r['payoff'],2)}</td><td>{num(r['sharpeR'],2)}</td><td>{r['mo+']}</td></tr>")
    return f"<table>{h}{''.join(body)}</table>"


def block_table():
    h = "<tr><th>برچسب</th>" + "".join(f"<th>{b}</th>" for b in blk_names) + "</tr>"
    body = []
    for tag in [t for t in rows_i]:
        if tag not in blk:
            continue
        d = blk[tag]
        tds = "".join(
            f"<td class='{cls(d[b]['net$'])}'>{num(d[b]['net$'],0)}$ <br><span class='sub'>{num(d[b]['avgR'],3)}R</span></td>"
            if b in d else "<td>—</td>" for b in blk_names)
        body.append(f"<tr><td>{FA.get(tag, tag)}</td>{tds}</tr>")
    return f"<table>{h}{''.join(body)}</table>"


rows_i = [r["tag"] for r in rows]

yt_b = year_table("B1-L0", os.path.join(OUT, "B1-L0_trades.csv"))
yt_f = year_table("FINAL-defaults", os.path.join(OUT, "FINAL-defaults_trades.csv"))
years = sorted({y for y, *_ in yt_b} | {y for y, *_ in yt_f})
yb = {y: (n, p, a) for y, n, p, a in yt_b}
yf = {y: (n, p, a) for y, n, p, a in yt_f}
yrows = []
for y in years:
    b = yb.get(y, (0, 0.0, 0.0)); f = yf.get(y, (0, 0.0, 0.0))
    yrows.append(
        f"<tr><td>{y}</td><td>{b[0]}</td><td class='{cls(b[1])}'>{num(b[1],0)}</td><td class='{cls(b[2])}'>{num(b[2],3)}</td>"
        f"<td>{f[0]}</td><td class='{cls(f[1])}'>{num(f[1],0)}</td><td class='{cls(f[2])}'>{num(f[2],3)}</td></tr>")
year_html = ("<table><tr><th>سال</th><th colspan='3'>پایهٔ ۱٫۴٫۵۱ (cx=0)</th>"
             "<th colspan='3'>پیکربندی نهایی (cx=3)</th></tr>"
             "<tr><th></th><th>معامله</th><th>سود $</th><th>میانگین R</th>"
             "<th>معامله</th><th>سود $</th><th>میانگین R</th></tr>" + "".join(yrows) + "</table>")

png_b64 = base64.b64encode(open(PNG, "rb").read()).decode()

html = f"""<!DOCTYPE html>
<html lang="fa" dir="rtl"><head><meta charset="utf-8">
<title>گزارش آزمون ۲۰سالهٔ موتور ترکیبی — GoldPin 1.4.52</title>
<style>
 body{{font-family:Tahoma,"Segoe UI",sans-serif;background:#0f1116;color:#e8eaf0;line-height:1.9;margin:0;padding:28px 6vw}}
 h1{{font-size:26px;border-bottom:2px solid #2b6cb0;padding-bottom:10px}}
 h2{{font-size:20px;margin-top:34px;color:#8ecae6}}
 h3{{font-size:16px;color:#c8d3e0;margin-top:22px}}
 table{{border-collapse:collapse;width:100%;font-size:12.5px;margin:12px 0;background:#161a22}}
 th,td{{border:1px solid #2a3140;padding:5px 7px;text-align:center;white-space:nowrap}}
 th{{background:#1d2635;color:#9fb3c8}}
 tr.hl{{background:#1b2b1f}}
 td.pos{{color:#5cd68a}} td.neg{{color:#ff7b72}}
 .sub{{color:#8b98a8;font-size:11px}}
 .box{{background:#141a24;border-right:4px solid #2b6cb0;padding:12px 16px;margin:16px 0;border-radius:6px}}
 .warn{{border-right-color:#e07b39}}
 .bad{{border-right-color:#d64545}}
 .ok{{border-right-color:#3fa34d}}
 code{{background:#1d2635;padding:2px 5px;border-radius:4px;font-family:Consolas,monospace;direction:ltr;display:inline-block}}
 ul,ol{{padding-right:22px}}
 img{{width:100%;border-radius:8px;border:1px solid #2a3140;margin:10px 0}}
 .kpi{{display:flex;gap:12px;flex-wrap:wrap;margin:14px 0}}
 .kpi div{{flex:1;min-width:150px;background:#161a22;border:1px solid #2a3140;border-radius:8px;padding:10px 12px}}
 .kpi b{{display:block;font-size:19px;color:#8ecae6}}
 small{{color:#8b98a8}}
</style></head><body>

<h1>گزارش آزمون ۲۰سالهٔ موتور ترکیبی — GoldPin v1.4.52</h1>
<p><small>تاریخ اجرا: ۵ آبان ۱۴۰۵ (2026-10-26) · داده: <code>datasets/goldpin_xauusd_m1_20y.csv.gz</code>
(۶٬۵۶۳٬۷۲۳ کندل M1، ۲۰۰۶-۰۸-۲۱ تا ۲۰۲۶-۰۸-۲۰، SHA-256 <code>ffbc7c1b…c830c9</code>) ·
کمیسیون ۰٫۷ در همهٔ اجراها · هیچ اجرایی حذف نشده است.</small></p>

<h2>۰) خلاصهٔ اجرایی</h2>
<div class="kpi">
 <div><small>پایهٔ ۱٫۴٫۵۱</small><b>+10,583$</b><small>میانگین R ‎+0.0311‎ · افت ۷٫۸۸۴$ (۷٫۷۹٪)</small></div>
 <div><small>پیکربندی نهایی ۱٫۴٫۵۲</small><b>+19,477$</b><small>میانگین R ‎+0.1397‎ · افت ۳٫۵۳۴$ (۳٫۴۳٪)</small></div>
 <div><small>تغییر</small><b>+84٪ سود</b><small>افت ۵۵٪ کمتر · ۶۴٪ معاملهٔ کمتر</small></div>
</div>
<div class="box ok">
<b>حکم:</b> موتور ترکیبی در پیکربندی نهایی، سود خالص ۲۰ساله را از <b>+10,583$</b> به <b>+19,477$</b> می‌برد،
افت را از <b>۷٬۸۸۴$ (۷٫۷۹٪)</b> به <b>۳٬۵۳۴$ (۳٫۴۳٪)</b> کم می‌کند و میانگین بازده هر معامله را
<b>۴٫۵ برابر</b> می‌کند (‎+0.0311R‎ ← ‎+0.1397R‎). ضریب سود ۱٫۰۸۴ ← ۱٫۳۲۸.
</div>
<div class="box warn">
<b>ولی چیزی پوشانده نشده:</b> از این +19,477$، مقدار <b>+16,856$ (۸۷٪)</b> فقط از
<b>۲۰ ماه آخر</b> (۲۰۲۵ تا ۲۰۲۶/۰۸) آمده است. دورهٔ <b>۲۰۱۱–۲۰۱۵ همچنان منفی</b> است
(−1,977$ / ‎−0.0595R‎) و ۲۰۱۸ <b>هیچ معامله‌ای</b> ندارد. یعنی این موتور «سود یکنواخت ۲۰ساله» نمی‌سازد؛
سود را در روندهای قوی طلا متمرکز می‌کند و در بازارهای بی‌جهت، کم‌ضرر یا صفر می‌ماند.
</div>

<h2>۱) چه چیزی در این نسخه عوض شد (کد)</h2>
<ol>
<li><b>موتور ترکیبی</b> (<code>core/Composite.kt</code> + <code>PaperBroker</code> + <code>Engine</code>): گیت رژیم نوسان،
گیت ارتفاع باکس ساختار، حجم هوشمند، پهن‌کردن داده‌محور حد ضرر، قفل‌شدن <code>ManageAllLost</code> وقتی
<code>cxMode&gt;0</code> است، و «کارت تصمیم» یک‌خطی در لاگ.</li>
<li><b>لایهٔ حجم به مسیر مدیر پول هم وصل شد</b> (باگ واقعی): در مودهای مالی <code>MIXED</code>/<code>ManageAllLost</code>
حجم را مدیر پول می‌ساخت و ضریب لایهٔ حجم <b>بی‌اثر</b> می‌شد؛ سه اجرای <code>cxSize=0/1/2</code> عیناً یک عدد
می‌دادند. حالا ضریب روی همان مسیر هم اعمال می‌شود.</li>
<li><b>تریل رژیم پرنوسان</b> (<code>cxTrailRegime2</code>) که در بیلد قبل سیم‌کشی نشده بود، وصل شد.</li>
<li><b>سه بهینه‌سازی کارایی</b> در <code>Engine.kt</code>: بافرهای <code>t2History/microS/structBars/pins/t1History</code>
از <code>ArrayList.removeAt(0)</code> (هزینهٔ O(n) در هر کندل) به <code>ArrayDeque.removeFirst()</code> تغییر کردند،
و باکس‌های مُرده هر ۲٬۰۰۰ کندل هرس می‌شوند. اثر: اجرای ۱۰ساله از <b>۴۳۴ ثانیه به ۶۰ ثانیه</b>
و اجرای ۲۰ساله از «بیش از ۱۰ دقیقه/توقف» به <b>۱۵۰ ثانیه</b> رسید.</li>
<li><b>اثبات بی‌اثری:</b> بعد از این تغییرات، اجرای پایهٔ <code>cx=0</code> دقیقاً همان عدد ۱٫۴٫۵۱ را داد:
<b>+10,583.0$ · ‎+0.0311R‎ · PF 1.084 · افت 7,883.8$</b> ⟹ بهینه‌سازی‌ها و کد جدید، رفتار قبلی را
حتی یک سنت جابه‌جا نکردند.</li>
</ol>

<h2>۲) روش آزمون</h2>
<ul>
<li>هارنس: <code>cli</code> → <code>OptimizeKt</code> با <code>--spec</code> (همان مسیر معاملاتی اپ؛ سفارش لیمیت، حد ضرر،
پایان زمان ۲۰:۰۰ UTC، مدیریت هوشمند، مدیر پول).</li>
<li>پارامترهای پایه در همهٔ اجراها: <code>s=1 (DOC) · mm=2 (MIXED) · k=7 · N=80 · b=1800 · eb=-2 · cmsn=0.7</code></li>
<li>دو پایهٔ ورودی: <b>A</b> = ساعت ۱–۲۰ و فیلتر ATR خاموش، مدیریت خاموش/روشن · <b>B</b> = ساعت ۵–۱۵،
<code>minAtrPct=0.25</code>، مدیریت هوشمند (پایهٔ «تنظیم‌شده» = همان چیزی که عدد ۱٫۴٫۵۱ را می‌دهد).</li>
<li>۳۵ اجرای ۲۰ساله (۲۰ + ۱۴ + ۶ + ۳ + اجرای نهایی) — همه در جدول §۴ آمده‌اند.</li>
<li>سرمایهٔ شروع ۱۰۰٬۰۰۰$. «افت٪» از منحنی معامله‌به‌معامله محاسبه شده (نه فقط <code>maxDD</code> موتور).</li>
</ul>

<h2>۳) نتیجهٔ جست‌وجوی پارامتر — پیکربندی نهایی</h2>
<table>
<tr><th>لایه</th><th>مقدار نهایی</th><th>چرا این مقدار</th></tr>
<tr><td>گیت رژیم نوسان</td><td>ATR% ≥ <b>0.33</b> (رژیم آرام: 0.40)</td><td>۰٫۳۰ → ‎+0.072R‎ · <b>۰٫۳۳ → ‎+0.131R‎</b> · ۰٫۳۵ → ‎+0.143R‎ · ۰٫۴۰ → ‎+0.063R‎ (بیش‌فیلتر، سقوط)</td></tr>
<tr><td>گیت ساختار</td><td>ارتفاع باکس ≥ <b>0.10 × ATR</b></td><td>بدون گیت: افت ۴٬۹۲۱$ · با گیت: افت ۳٬۱۰۸$ و میانگین R بالاتر</td></tr>
<tr><td>حجم</td><td>مرکب سقف‌دار <b>1.5×</b> (<code>cxSizeMode=1</code>)</td><td>مرکب کامل در ۲۰ سال ‎−۲٫۹٪‎ با افت ۴۶٪ می‌داد؛ اندازهٔ ثابت سود کمتری داشت</td></tr>
<tr><td>پهن‌کردن حد ضرر</td><td><b>1.15×</b> (حداکثر)</td><td>۱٫۳۵ اثر بیشتری نداشت (‎+0.1405R‎ در برابر ‎+0.1430R‎) ⟹ مقدار کوچک‌تر انتخاب شد</td></tr>
<tr><td>تریل رژیم پرنوسان</td><td><b>1.10×</b></td><td>با آن افت ۳٬۱۰۸ → ۳٬۰۴۸$؛ اثر کوچک اما مثبت</td></tr>
<tr><td>لایهٔ استرس (کم‌کردن حجم در افت)</td><td><b>خاموش</b> (<code>cxStressFloor=1.0</code>)</td><td>با استرس ‎+79,804$‎/افت ۱۳٫۲٪ · بدون استرس ‎+111,223$‎/افت ۱۶٫۲٪ ⟹ بازده به‌ازای افت بدون استرس بهتر (۵٫۵۲ در برابر ۴٫۹۴) ⟹ طبق تصمیم شما حذف شد</td></tr>
<tr><td><code>ManageAllLost</code></td><td><b>قفل</b> وقتی <code>cxMode&gt;0</code></td><td>تصمیم شما (۴ آبان) — در کد اعمال شد</td></tr>
<tr><td>جریان پول (CMF) و تقویم خبر</td><td><b>حذف کامل</b></td><td>تصمیم شما؛ کدشان هرگز نوشته نشد</td></tr>
</table>

<h2>۴) جدول کامل همهٔ اجراها (۲۰ سال، بدون حذف)</h2>
<p><small>ستون‌های «افت٪/سری باخت/payoff/شارپ» از ریز معاملات محاسبه شده‌اند. سطرهای سبز = پایه و پیکربندی نهایی.</small></p>
{summary_table()}

<h2>۵) آمار کامل پیکربندی نهایی</h2>
<table>
<tr><th>سنجه</th><th>پایهٔ ۱٫۴٫۵۱ (cx=0)</th><th>نهایی ۱٫۴٫۵۲ (cx=3)</th></tr>
<tr><td>تعداد معامله</td><td>1,806</td><td>651</td></tr>
<tr><td>سود خالص (پس از کمیسیون ۰٫۷)</td><td>+10,583$</td><td>+19,477$</td></tr>
<tr><td>میانگین بازده هر معامله (R)</td><td>+0.0311</td><td>+0.1397</td></tr>
<tr><td>نرخ برد</td><td>40.6٪</td><td>38.2٪</td></tr>
<tr><td>ضریب سود (PF)</td><td>1.084</td><td>1.328</td></tr>
<tr><td>payoff (میانگین برد ÷ میانگین باخت)</td><td>1.59</td><td>2.14</td></tr>
<tr><td>حداکثر افت ($ / ٪)</td><td>7,884$ / 7.79٪</td><td>3,534$ / 3.43٪</td></tr>
<tr><td>بدترین ۱۲ ماه متوالی</td><td>−3,054$</td><td>−2,572$</td></tr>
<tr><td>بلندترین سری باخت</td><td>12</td><td>12</td></tr>
<tr><td>ماه‌های مثبت</td><td>97 / 225</td><td>71 / 150</td></tr>
<tr><td>شارپ (بر حسب R معاملات)</td><td>0.92</td><td>2.35</td></tr>
<tr><td>سال‌های مثبت</td><td>10 / 21</td><td>11 / 20</td></tr>
</table>

<h3>پایداری در بلوک‌های پنج‌ساله (همهٔ اجراها)</h3>
{block_table()}

<h3>سال‌به‌سال</h3>
{year_html}

<img src="data:image/png;base64,{png_b64}" alt="equity curves">

<h2>۶) لایه‌هایی که رد شدند (صادقانه)</h2>
<ul>
<li><b>گیت باکس با آستانهٔ ۰٫۱۷×ATR روی گیت ۰٫۳۰:</b> سود را از 13,220$ به 4,463$ آورد ⟹ رد شد.
با آستانهٔ ۰٫۱۰ روی گیت ۰٫۳۳ مفید بود ⟹ قبول شد. یعنی «گیت باکس» فقط در ترکیب درست کمک می‌کند و
عدد ۰٫۱۵۴R سند، به‌تنهایی، به سود دلاری ترجمه نمی‌شود.</li>
<li><b>پهن‌کردن حد ضرر ۱٫۳۵×:</b> روی گیت ۰٫۳۰ میانگین R را از 0.072 به 0.056 آورد (بدتر)؛
روی گیت ۰٫۳۳ بی‌تفاوت بود. ⟹ به ۱٫۱۵ محدود شد.</li>
<li><b>گیت ATR = ۰٫۴۰:</b> میانگین R از 0.1407 به 0.0626 سقوط کرد و سود از 14,861$ به 6,145$ ⟹ بیش‌فیلتر.</li>
<li><b>گیت ATR = ۰٫۲۵:</b> 0.0450R — بهتر از خاموش، ولی نصف ۰٫۳۳.</li>
<li><b>لایهٔ حجم در مود MIXED:</b> سه حالت <code>cxSize=0/1/2</code> <b>عیناً</b> یک نتیجه دادند
(‎+4,462.69$‎ در هر سه) چون در این مود حجم را مدیر پول تعیین می‌کند؛ فقط ضریب رژیم/استرس اثر می‌گذارد.
اثر واقعی اندازه در مود ریسک‌محور دیده شد: ثابت ‎+32,635$‎ · مرکب سقف‌دار ‎+42,040$‎ · مرکب کامل ‎+40,290$‎.</li>
<li><b>لایهٔ استرس:</b> حذف شد (بند §۳).</li>
<li><b>پایهٔ «پیش‌فرض کارخانه» (k=4, N=3, cost=6):</b> در همین هارنس <b>صفر معامله</b> می‌دهد
(فیلتر هزینه با N=3 همهٔ ستاپ‌ها را می‌بندد). یعنی بعضی از «تنظیمات پیش‌فرض» عملاً مرده‌اند — این
هم ثبت شد و چیزی از آن به‌عنوان مبنا استفاده نشد.</li>
</ul>

<h2>۷) ریسک‌ها و ایرادهایی که باید بدانید</h2>
<div class="box bad">
<ol>
<li><b>تمرکز سود در ۲۰۲۵–۲۰۲۶:</b> ۸۷٪ سود ۲۰ساله از ۲۰ ماه آخر است. اگر روند طلا تمام شود،
انتظار سود باید پایین بیاید (هر دو نسخه همین‌طور هستند؛ موتور ترکیبی فقط زیان دوره‌های بی‌روند را کم می‌کند).</li>
<li><b>دورهٔ ۲۰۱۱–۲۰۱۵ منفی است</b> (‎−1,977$‎ / ‎−0.0595R‎) — بهتر از پایه (‎−2,987$‎) ولی هنوز منفی.</li>
<li><b>۲۰۱۸ صفر معامله</b> شد (گیت‌ها همه را بستند) — ریسک «قفل‌شدن» در بازارهای کم‌نوسان.</li>
<li><b>ریسک بیش‌برازش:</b> پارامترها روی همان ۲۰ سال انتخاب شده‌اند. برای اعتبار واقعی، آزمون
خارج از نمونه (سال‌های آینده یا بازار دیگر) لازم است. انتخاب ۰٫۳۳ در برابر ۰٫۳۵ اختلاف کمی داشت
(‎+14,908$‎ در برابر ‎+13,744$‎) و ممکن است نویز باشد.</li>
<li><b>تعداد معامله کم شده</b> (۶۵۱ در ۲۰ سال ≈ ۳۳ در سال): نمونهٔ آماری برای آمارهای سالانه نازک است.</li>
<li><b>هزینه‌ها:</b> فقط کمیسیون ۰٫۷ لحاظ شده؛ اسلیپیج/کمیسیون واقعی بروکر و سوآپ لحاظ نشده.</li>
</ol>
</div>

<h2>۸) تنظیمات نهایی (پیش‌فرض جدید اپ)</h2>
<table>
<tr><th>کلید</th><th>مقدار</th></tr>
<tr><td><code>cxMode</code></td><td>3</td></tr>
<tr><td><code>cxRegimeGate</code> / <code>cxRegimeGateCalm</code></td><td>0.33 / 0.40</td></tr>
<tr><td><code>cxBoxMinUnits</code></td><td>0.10</td></tr>
<tr><td><code>cxSizeMode</code> / <code>cxCompoundCap</code></td><td>1 / 1.5</td></tr>
<tr><td><code>cxSlWidenMax</code> / <code>cxTrailRegime2</code></td><td>1.15 / 1.10</td></tr>
<tr><td><code>cxStressFloor</code></td><td>1.0 (لایهٔ استرس خاموش)</td></tr>
</table>
<p>بازگشت به رفتار ۱٫۴٫۵۱ فقط با یک کلید ممکن است: <code>cxMode=0</code>.</p>

<h3>بازتولید</h3>
<p><code>./run_cx.sh specs_stage4.txt</code> یا تک‌اجرا:</p>
<p><code>java -Xmx1050m -XX:+UseSerialGC -cp "cli/build/install/cli/lib/*" com.alisport.goldpin.cli.OptimizeKt
--data datasets/goldpin_xauusd_m1_20y.csv.gz --spec "s=1,mm=2,k=7,b=1800,N=80,eb=-2,cmsn=0.7,hFrom=5,hTo=15,atrPct=0.25,mgmt=1,tag=FINAL" --out out/cx20y/FINAL.csv</code></p>
<p><small>خروجی خام: <code>out/cx20y/*.csv</code> (خلاصه)، <code>*_trades.csv</code> (ریز معاملات)،
<code>summary.csv</code>، <code>blocks.csv</code> · اسکریپت‌ها: <code>research/cx_report.py</code>، <code>research/cx_fig.py</code></small></p>

<h2>۹) قدم بعدی</h2>
<ol>
<li>ساخت APK ۱٫۴٫۵۲ و باز کردن بخش «تطبیق» برای دیدن/تغییر همین ۹ کلید (فعل جدید: پیش‌تنظیم «ترکیبی»).</li>
<li>آزمون خارج از نمونه: اجرای همین پیکربندی روی دادهٔ ۲۰۰۳–۲۰۰۶ (اگر تأمین شود) یا روی نماد دیگر.</li>
<li>اگر بخواهید: نسخهٔ ریسک‌محور ۱٪ (جدول §۴، سطر <code>B1-g35box10m0</code>) سود ‎+79,804$‎ با افت
۱۶٪ می‌دهد — پرمعامله‌تر و پرافت‌تر؛ تصمیمش با شماست.</li>
</ol>
</body></html>
"""

open(DST, "w").write(html)
print("wrote", DST, len(html), "bytes")
