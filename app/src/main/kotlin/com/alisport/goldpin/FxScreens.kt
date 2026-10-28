package com.alisport.goldpin

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import com.alisport.goldpin.core.Candle
import com.alisport.goldpin.core.FxBacktest
import com.alisport.goldpin.core.FxCostTable
import com.alisport.goldpin.core.Tf
import com.alisport.goldpin.ui.ChartView
import com.alisport.goldpin.ui.EquityChartView
import com.alisport.goldpin.util.Fa
import com.alisport.goldpin.util.Palette
import com.alisport.goldpin.util.Ui

// ═══════════════════════════════════════════════════════════════════════════════
//  تب‌های جفت‌ارز به‌عنوان نماد (۱٫۵٫۰۷)
//
//  خواستهٔ مالک، بی‌کم‌وکاست:
//   ۱) هر جفت‌ارز در بخش نمادها، دقیقاً مثل طلا و نفت، **تب خودش** را داشته باشد.
//   ۲) **چارت مخصوص خودش**.
//   ۳) **همهٔ تنظیمات و پارامترها** برایش فعال باشد (تایم‌فریم، حالت، N، ATR، هدف،
//      پله، سقف نگه‌داری، ریسک، سرمایه، کمیسیون).
//   ۴) دادهٔ **۱ / ۳ / ۵ / ۱۰ / ۲۰ سال** برایش تعریف شده باشد (چارت + بک‌تست).
//   ۵) در **یک منحنی سرمایهٔ کلی** با طلا و نفت حساب شود (تب مدیریت).
//
//  این فایل فقط UI است؛ حساب و منطق در `FxLive.FxPairDesk` و `Hub` است.
// ═══════════════════════════════════════════════════════════════════════════════

/** کش کندل‌های هر جفت بر حسب بازه (برای چارت و بک‌تست). */
object FxChartStore {

    private val cache = HashMap<String, List<Candle>>()
    private val busy = HashMap<String, Boolean>()

    fun key(pair: String, years: Int) = "$pair|$years"

    fun cached(pair: String, years: Int): List<Candle>? = cache[key(pair, years)]

    fun load(act: MainActivity, pair: String, years: Int, onDone: (String) -> Unit = {}) {
        val k = key(pair, years)
        if (cache[k] != null) { onDone("از حافظه"); return }
        if (busy[k] == true) return
        busy[k] = true
        val app = act.applicationContext
        Thread {
            var msg: String
            try {
                val list = FxLive.candlesFor(app, pair, years)
                if (list.isEmpty()) msg = "داده‌ای برای $pair در بستهٔ ${Fa.d(years.toString())} ساله نبود"
                else { cache[k] = list; msg = "${Fa.d(list.size.toString())} کندل بارگذاری شد" }
            } catch (t: Throwable) {
                msg = "خطای دانلود داده: " + Fa.short(t.message ?: t.javaClass.simpleName, 90)
            }
            busy[k] = false
            act.runOnUiThread {
                onDone(msg)
                act.refreshPublic()
            }
        }.also { it.isDaemon = true }.start()
    }
}

object FxScreens {

    private fun head(ctx: Context, title: String, color: Int = Palette.gold): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.panel)
            setPadding(Ui.dp(ctx, 9f), Ui.dp(ctx, 6f), Ui.dp(ctx, 9f), Ui.dp(ctx, 6f))
            addView(Ui.tv(ctx, title, 12.5f, color, true))
        }

    private fun row(ctx: Context, k: String, v: String, color: Int = Palette.txt): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(ctx, 2f), 0, Ui.dp(ctx, 2f))
            addView(Ui.tv(ctx, k, 11f, Palette.dim), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.tv(ctx, v, 11.5f, color, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
        }

    private fun wrap(ctx: Context): Pair<ScrollView, LinearLayout> {
        val sc = ScrollView(ctx).apply { setBackgroundColor(Palette.bg) }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 7f), Ui.dp(ctx, 7f), Ui.dp(ctx, 7f), Ui.dp(ctx, 20f))
        }
        sc.addView(col)
        return sc to col
    }

    private fun chips(ctx: Context, values: List<Pair<String, Boolean>>, onPick: (Int) -> Unit): LinearLayout {
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        values.forEachIndexed { i, (label, on) ->
            r.addView(Ui.btn(ctx, label,
                if (on) Palette.gold else Palette.panel2,
                if (on) Palette.bg else Palette.txt, 10f).apply { setOnClickListener { onPick(i) } },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = Ui.dp(ctx, 4f) })
        }
        return r
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  ۱) چارت مخصوص همین جفت
    // ───────────────────────────────────────────────────────────────────────────
    fun chart(act: MainActivity): View {
        val d = Hub.fxDesk() ?: return Ui.tv(act, "میز جفت‌ارز پیدا نشد", 13f, Palette.down)
        val p = d.profile
        val sm = d.summary()
        val (sc, col) = wrap(act)

        val h = head(act, "چارت ${p.id} — ${p.label}", if (sm.equity >= sm.capital) Palette.up else Palette.gold)
        h.addView(row(act, "پارامتر فعال",
            "${d.tfFaNow} · ${if (d.dirMode > 0) "هم‌جهت شکست" else "پاد شکست"} · N=${d.lookback} · " +
                "atr=${Fa.n(d.atrMult, 2)} · هدف ${Fa.n(d.targetR, 1)}R" + (if (d.partial) " · پله" else ""), Palette.accent))
        h.addView(row(act, "آخرین قیمت",
            if (sm.lastPrice.isNaN()) "—" else Fa.n(sm.lastPrice, p.digits),
            Palette.txt))
        h.addView(row(act, "وضعیت",
            when {
                sm.running -> "● روشن (کاغذی)"
                sm.enabled -> "◐ فعال، خاموش"
                else -> "○ غیرفعال"
            }, if (sm.running) Palette.up else Palette.dim))
        col.addView(h)

        // انتخاب بازهٔ داده (۱/۳/۵/۱۰/۲۰ سال)
        col.addView(Ui.tv(act, "بازهٔ داده (چارت و بک‌تست)", 11f, Palette.gold, true))
        val ranges = FxLive.RANGES
        col.addView(chips(act, ranges.map { "‏${Fa.d(it.toString())} ساله" to (it == d.backtestYears) }) { i ->
            d.backtestYears = ranges[i]
            FxChartStore.load(act, p.id, ranges[i]) { msg -> act.toastPublic(msg) }
            act.refreshPublic()
        })

        val cand = FxChartStore.cached(p.id, d.backtestYears)
        if (cand == null) {
            col.addView(Ui.tv(act, "دادهٔ این بازه هنوز دانلود نشده — یک‌بار روی بازه بزنید " +
                "(از بستهٔ رسمی ریلیز، بعد آفلاین). تا آن موقع کندل‌های زندهٔ همین میز نمایش داده می‌شود.",
                10f, Palette.dim))
            FxChartStore.load(act, p.id, d.backtestYears) { msg -> act.toastPublic(msg) }
        }

        // تایم‌فریم چارت
        val tfs = listOf(3_600L to "۱ ساعت", 14_400L to "۴ ساعت", 86_400L to "روزانه")
        col.addView(Ui.tv(act, "تایم‌فریم چارت", 11f, Palette.gold, true))
        col.addView(chips(act, tfs.map { it.second to (it.first == d.lastChartTf) }) { i ->
            d.lastChartTf = tfs[i].first
            act.refreshPublic()
        })

        // چارت
        val cv = ChartView(act)
        val base = cand ?: d.rawCandles()
        val tf = d.lastChartTf
        cv.candles = if (base.isEmpty()) emptyList()
                       else if (tf == 3_600L) base
                       else FxBacktest.aggregate(base, tf)
        cv.chartTfSec = tf.toInt()
        cv.symbolName = p.id
        cv.livePulse = sm.running
        cv.showVolume = false
        cv.showZeroVolumeHighlight = false
        cv.showZones = false
        cv.showMarkers = false
        cv.showTradeLines = false
        cv.showSetupZoneLines = false
        cv.showPocLine = false
        cv.showTradeMarkers = false
        cv.showTradeResults = false
        cv.showLastPriceLine = true
        cv.showGrid = true
        cv.showWatermark = true
        col.addView(cv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 330f)))
        cv.refresh()
        col.addView(Ui.tv(act,
            "کندل‌ها: ${Fa.d(cv.candles.size.toString())} · منبع: " +
                (if (cand != null) "بستهٔ ${Fa.d(d.backtestYears.toString())} سالهٔ ریلیز" else "فید زندهٔ همین میز"),
            9.5f, Palette.dim))
        return sc
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  ۲) تنظیمات و پارامترهای همین جفت (همه فعال، مثل طلا و نفت)
    // ───────────────────────────────────────────────────────────────────────────
    fun settings(act: MainActivity): View {
        val d = Hub.fxDesk() ?: return Ui.tv(act, "میز جفت‌ارز پیدا نشد", 13f, Palette.down)
        val p = d.profile
        val sm = d.summary()
        val (sc, col) = wrap(act)

        // ── انتخاب نماد در بالای تنظیمات (طلا / نفت / هر جفت‌ارز) ─────────────
        col.addView(Ui.section(act, "◆ نماد معامله (فقط نما را عوض می‌کند)"))
        for (spec in Hub.specs) {
            val on = Hub.viewId == spec.id
            col.addView(Ui.btn(act,
                (if (on) "✓ " else "") + spec.label,
                if (on) Palette.gold else Palette.panel2, if (on) Palette.bg else Palette.txt, 11f).apply {
                setOnClickListener { act.toastPublic(Hub.setView(act, spec.id)); act.refreshPublic() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = Ui.dp(act, 3f) })
        }
        for (desk in FxLive.desks) {
            val on = Hub.viewId == desk.profile.id
            val s2 = desk.summary()
            col.addView(Ui.btn(act,
                (if (on) "✓ " else "") + desk.profile.label +
                    "  ·  " + (if (s2.running) "● روشن" else if (desk.enabled) "◐ خاموش" else "○ غیرفعال") +
                    "  ·  ${Fa.d(s2.closedCount.toString())} معامله",
                if (on) Palette.gold else Palette.panel2, if (on) Palette.bg else Palette.txt, 10.5f).apply {
                setOnClickListener { act.toastPublic(Hub.setView(act, desk.profile.id)); act.refreshPublic() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = Ui.dp(act, 2f) })
        }

        // ── وضعیت و حساب همین میز ─────────────────────────────────────────────
        val h = head(act, "تنظیمات میز ${p.id} — ${p.label}")
        h.addView(row(act, "سرمایهٔ سهم این میز", Fa.n(sm.capital, 2) + " دلار"))
        h.addView(row(act, "موجودی / اکوییتی", "${Fa.n(sm.balance, 2)} / ${Fa.n(sm.equity, 2)}",
            if (sm.equity >= sm.capital) Palette.up else Palette.down))
        h.addView(row(act, "سود/زیان تحقق‌یافته", Fa.signed(sm.realized), if (sm.realized >= 0) Palette.up else Palette.down))
        h.addView(row(act, "معاملات بسته / باز", "${Fa.d(sm.closedCount.toString())} / ${Fa.d(sm.openCount.toString())}"))
        h.addView(row(act, "نرخ برد / میانگین R", "${Fa.n(sm.winRate, 1)}٪ / ${Fa.signed(sm.avgR)}R"))
        h.addView(row(act, "بیشترین افت", Fa.n(sm.maxDD, 2) + " (" + Fa.n(sm.maxDDPct, 1) + "٪)", Palette.down))
        col.addView(h)

        // ── اجرا و فعال بودن ──────────────────────────────────────────────────
        col.addView(Switch(act).apply {
            text = "  اجرای کاغذی همین میز"
            isChecked = sm.running
            setTextColor(Palette.txt)
            setOnCheckedChangeListener { _, b ->
                if (b) FxLive.start(act, p.id) else FxLive.stop(act, p.id)
                act.refreshPublic()
            }
        })
        col.addView(Switch(act).apply {
            text = "  فعال در فهرست میزها و در تخصیص سرمایه"
            isChecked = sm.enabled
            setTextColor(Palette.txt)
            setOnCheckedChangeListener { _, b ->
                d.setEnabled(act, b)
                Hub.applyCapital(act, Hub.capitalTotal)
                act.refreshPublic()
            }
        })

        // ── پارامترها ─────────────────────────────────────────────────────────
        col.addView(Ui.section(act, "⚙ پارامترهای استراتژی این جفت (قابل تغییر)"))

        col.addView(Ui.tv(act, "تایم‌فریم تحلیل", 11f, Palette.gold, true))
        val tfs = listOf(3_600L to "۱ ساعت", 14_400L to "۴ ساعت", 86_400L to "روزانه")
        col.addView(chips(act, tfs.map { it.second to (it.first == d.tfSec) }) { i ->
            d.tfSec = tfs[i].first; d.persist(); act.refreshPublic()
        })

        col.addView(Ui.tv(act, "حالت ورود", 11f, Palette.gold, true))
        col.addView(chips(act, listOf("هم‌جهت شکست" to (d.dirMode > 0), "پاد شکست" to (d.dirMode < 0))) { i ->
            d.dirMode = if (i == 0) 1 else -1; d.persist(); act.refreshPublic()
        })

        col.addView(stepRow(act, "طول شکست N", "${d.lookback} کندل", {
            d.lookback = (d.lookback - if (d.lookback > 24) 12 else 6).coerceAtLeast(5); d.persist(); act.refreshPublic()
        }, {
            d.lookback = (d.lookback + if (d.lookback >= 24) 12 else 6).coerceAtMost(240); d.persist(); act.refreshPublic()
        }))

        col.addView(Ui.tv(act, "ضریب ATR برای حد ضرر", 11f, Palette.gold, true))
        val atrs = listOf(0.5, 1.0, 1.5, 2.0, 3.0, 4.0)
        col.addView(chips(act, atrs.map { Fa.n(it, 1) to (kotlin.math.abs(it - d.atrMult) < 1e-9) }) { i ->
            d.atrMult = atrs[i]; d.persist(); act.refreshPublic()
        })

        col.addView(stepRow(act, "هدف سود (R)", "${Fa.n(d.targetR, 1)}R", {
            d.targetR = (d.targetR - 0.5).coerceAtLeast(1.0); d.persist(); act.refreshPublic()
        }, {
            d.targetR = (d.targetR + 0.5).coerceAtMost(20.0); d.persist(); act.refreshPublic()
        }))

        col.addView(stepRow(act, "سقف نگه‌داری", "${d.maxHoldBars} کندل", {
            d.maxHoldBars = (d.maxHoldBars - 10).coerceAtLeast(5); d.persist(); act.refreshPublic()
        }, {
            d.maxHoldBars = (d.maxHoldBars + 10).coerceAtMost(500); d.persist(); act.refreshPublic()
        }))

        col.addView(Switch(act).apply {
            text = "  بستن نصف حجم در ۱R و بردن حد ضرر به سر‌به‌سر (پله)"
            isChecked = d.partial
            setTextColor(Palette.txt)
            setOnCheckedChangeListener { _, b -> d.partial = b; d.persist(); act.refreshPublic() }
        })

        col.addView(Ui.tv(act, "ریسک هر معامله (درصد از سرمایهٔ همین میز)", 11f, Palette.gold, true))
        val risks = listOf(0.25, 0.5, 1.0)
        col.addView(chips(act, risks.map { "${Fa.n(it, 2)}٪" to (kotlin.math.abs(it - d.riskPct) < 1e-9) }) { i ->
            d.setRisk(risks[i]); act.refreshPublic()
        })

        col.addView(Ui.tv(act, "کمیسیون رفت‌وبرگشت هر لات", 11f, Palette.gold, true))
        val commOpts = listOf(0.0, 2.5, 5.0)
        val commChips = commOpts.map { v ->
            val on = kotlin.math.abs(d.commissionOverride - v) < 1e-9
            val label = if (v == 0.0) "۰ $" else if (v == 2.5) "۲٫۵ $" else "۵ $ (Pro ECN)"
            label to on
        }
        col.addView(chips(act, commChips) { i ->
            d.setCommission(commOpts[i]); act.refreshPublic()
        })

        val c = FxCostTable.costFor(p.id)
        if (c != null) {
            col.addView(Ui.tv(act,
                "هزینهٔ بروکر ${p.id}: اسپرد ${Fa.n(c.spreadPoints, 2)} پوینت · " +
                    "سواپ خرید ${Fa.signed(c.swapLongPerLot)} / فروش ${Fa.signed(c.swapShortPerLot)} دلار بر لات در شب",
                10f, Palette.dim))
        }

        val rb = Ui.btn(act, "بازگرداندن پارامترها به مقدار آزمون‌شدهٔ ۲۰ ساله",
            Palette.panel2, Palette.gold, 11f)
        rb.setOnClickListener { d.resetParams(); act.toastPublic("پارامترها به مقدار ۲۰ ساله برگشت") }
        col.addView(rb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = Ui.dp(act, 6f) })

        col.addView(Ui.tv(act,
            "پارامتر پیش‌فرض هر جفت از walk-forward ۲۰ ساله آمده است: " +
                "داوری ۸ ساله ${Fa.signed(p.oosAvgR)}R · کل ۲۰ سال ${Fa.signed(p.avgR20)}R · " +
                "۳ سال آخر ${Fa.signed(p.rec3AvgR)}R. تغییر دستی، از همان لحظه در اجرای زنده و بک‌تست اثر می‌گذارد.",
            10f, Palette.dim))

        // ── دادهٔ ۱/۳/۵/۱۰/۲۰ سال ────────────────────────────────────────────
        col.addView(Ui.section(act, "🗂 دادهٔ همین جفت — ۱ / ۳ / ۵ / ۱۰ / ۲۰ سال"))
        for (y in FxLive.RANGES) {
            val cached = FxChartStore.cached(p.id, y)
            val pack = FxLive.packName(y)
            col.addView(Ui.btn(act,
                "${Fa.d(y.toString())} ساله · ${pack}" +
                    (if (cached != null) "  ✅ ${Fa.d(cached.size.toString())} کندل" else "  ⬇ دانلود"),
                if (cached != null) Palette.panel2 else Palette.accent,
                if (cached != null) Palette.up else Palette.bg, 10.5f).apply {
                setOnClickListener {
                    FxChartStore.load(act, p.id, y) { msg -> act.toastPublic(msg) }
                    act.refreshPublic()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = Ui.dp(act, 3f) })
        }

        col.addView(Ui.section(act, "🧪 بک‌تست همین میز"))
        val br = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        for (y in FxLive.RANGES) {
            br.addView(Ui.btn(act, "${Fa.d(y.toString())}س",
                if (y == d.backtestYears) Palette.gold else Palette.panel2,
                if (y == d.backtestYears) Palette.bg else Palette.txt, 10f).apply {
                setOnClickListener {
                    d.runBacktest(act, y) { msg -> act.toastPublic(msg) }
                    act.refreshPublic()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = Ui.dp(act, 4f) })
        }
        col.addView(br)
        d.backtest?.let { b ->
            col.addView(row(act, "نتیجه (${Fa.d(b.bars.toString())} کندل)",
                "${Fa.d(b.trades.toString())} معامله · ${Fa.n(b.avgR, 3)}R · PF ${Fa.n(b.pf, 2)}",
                if (b.avgR >= 0) Palette.up else Palette.down))
        }

        // ── پاک‌کردن همین میز ─────────────────────────────────────────────────
        col.addView(Ui.btn(act, "پاک‌کردن نتایج همین میز", Palette.panel2, Palette.down, 10.5f).apply {
            setOnClickListener {
                d.clearResults(); act.toastPublic("${p.id} پاک شد"); act.refreshPublic()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = Ui.dp(act, 8f) })
        return sc
    }

    private fun stepRow(act: MainActivity, label: String, value: String,
                        onMinus: () -> Unit, onPlus: () -> Unit): LinearLayout {
        val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(Ui.tv(act, label, 11f, Palette.dim), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(Ui.btn(act, "−", Palette.panel2, Palette.txt, 11f).apply { setOnClickListener { onMinus() } },
            LinearLayout.LayoutParams(Ui.dp(act, 40f), ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addView(Ui.tv(act, value, 11.5f, Palette.gold, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(Ui.btn(act, "+", Palette.panel2, Palette.txt, 11f).apply { setOnClickListener { onPlus() } },
            LinearLayout.LayoutParams(Ui.dp(act, 40f), ViewGroup.LayoutParams.WRAP_CONTENT))
        return r
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  ۳) سفارش‌ها و ۴) سود و زیان همین جفت
    // ───────────────────────────────────────────────────────────────────────────
    fun orders(act: MainActivity): View {
        val d = Hub.fxDesk() ?: return Ui.tv(act, "—", 12f, Palette.dim)
        val (sc, col) = wrap(act)
        col.addView(Ui.section(act, "سفارش‌های ${d.profile.id}"))
        val opens = d.desk.positions.values.toList()
        col.addView(Ui.tv(act, "باز: ${Fa.d(opens.size.toString())}", 11.5f, Palette.gold, true))
        for (t in opens) {
            col.addView(Ui.tv(act,
                "${if (t.dir > 0) "خرید" else "فروش"} · ورود ${Fa.n(t.entryPx, d.profile.digits)} · " +
                    "SL ${Fa.n(t.sl, d.profile.digits)} · TP ${Fa.n(t.tp, d.profile.digits)} · " +
                    "حجم ${Fa.n(t.qtyLots, 2)} لات · ریسک ${Fa.n(t.riskUsd, 2)}$",
                10f, Palette.txt))
        }
        val closed = d.desk.closed.sortedByDescending { it.exitT }.take(60)
        col.addView(Ui.tv(act, "بسته‌شده (۶۰ مورد آخر): ${Fa.d(d.desk.closed.size.toString())}", 11.5f, Palette.gold, true))
        for (t in closed) {
            val net = t.netUsd()
            col.addView(Ui.tv(act,
                "${if (t.dir > 0) "خرید" else "فروش"} ${Fa.n(t.entryPx, d.profile.digits)} → " +
                    "${Fa.n(t.exitPx, d.profile.digits)} · ${Fa.n(t.realizedR(), 2)}R · " +
                    "${Fa.signed(net)}$ · کمیسیون ${Fa.n(t.commissionUsd, 2)}$ · سواپ ${Fa.signed(t.swapUsd)}$ " +
                    "(${Fa.d(t.swapNights.toString())} شب)",
                9.5f, if (net >= 0) Palette.up else Palette.down))
        }
        return sc
    }

    fun pnl(act: MainActivity): View {
        val d = Hub.fxDesk() ?: return Ui.tv(act, "—", 12f, Palette.dim)
        val sm = d.summary()
        val (sc, col) = wrap(act)
        col.addView(Ui.section(act, "سود و زیان ${d.profile.id}"))
        col.addView(row(act, "سرمایهٔ میز", Fa.n(sm.capital, 2)))
        col.addView(row(act, "موجودی", Fa.n(sm.balance, 2), if (sm.balance >= sm.capital) Palette.up else Palette.down))
        col.addView(row(act, "اکوییتی", Fa.n(sm.equity, 2), if (sm.equity >= sm.capital) Palette.up else Palette.down))
        col.addView(row(act, "تحقق‌یافته", Fa.signed(sm.realized), if (sm.realized >= 0) Palette.up else Palette.down))
        col.addView(row(act, "شناور", Fa.signed(sm.floating), if (sm.floating >= 0) Palette.up else Palette.down))
        col.addView(row(act, "بازده", Fa.n(if (sm.capital > 0) 100.0 * sm.realized / sm.capital else 0.0, 2) + "٪"))
        col.addView(row(act, "بیشترین افت", Fa.n(sm.maxDD, 2), Palette.down))
        col.addView(row(act, "میانگین R هر معامله", Fa.signed(sm.avgR) + "R", if (sm.avgR >= 0) Palette.up else Palette.down))
        if (sm.curve.size > 1) {
            val eq = EquityChartView(act)
            eq.setCurve(sm.curve, sm.capital)
            col.addView(eq, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 260f)))
        }
        return sc
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  ۵) گزارش‌ها: نتیجهٔ بک‌تست همین جفت، سال‌به‌سال
    // ───────────────────────────────────────────────────────────────────────────
    fun reports(act: MainActivity): View {
        val d = Hub.fxDesk() ?: return Ui.tv(act, "—", 12f, Palette.dim)
        val (sc, col) = wrap(act)
        col.addView(Ui.section(act, "گزارش ${d.profile.id} — ${d.profile.label}"))
        val b = d.backtest
        if (b == null) {
            col.addView(Ui.tv(act, "هنوز بک‌تستی برای این میز اجرا نشده. بازه را انتخاب و اجرا کنید.",
                11f, Palette.dim))
        } else {
            col.addView(row(act, "بازه", "${Fa.d(((b.toT - b.fromT) / 31_536_000_000L).toString())} سال · ${Fa.d(b.bars.toString())} کندل"))
            col.addView(row(act, "پارامتر", b.paramsFa))
            col.addView(row(act, "معاملات / برد", "${Fa.d(b.trades.toString())} / ${Fa.n(b.winRate, 1)}٪"))
            col.addView(row(act, "میانگین R / PF", "${Fa.n(b.avgR, 3)}R / ${Fa.n(b.pf, 2)}",
                if (b.avgR >= 0) Palette.up else Palette.down))
            col.addView(row(act, "سود خالص", Fa.signed(b.netUsd), if (b.netUsd >= 0) Palette.up else Palette.down))
            col.addView(row(act, "بیشترین افت", "${Fa.n(b.maxDdUsd, 0)} دلار (${Fa.n(b.maxDdR, 1)}R)", Palette.down))
            col.addView(row(act, "سال‌های مثبت", "${Fa.d(b.positiveYears.toString())} از ${Fa.d(b.yearCount.toString())}",
                if (b.positiveYears * 2 >= b.yearCount) Palette.up else Palette.down))
            col.addView(row(act, "سرمایه / ریسک / کمیسیون",
                "${Fa.n(b.startCapital, 0)}$ · ${Fa.n(b.riskPct, 2)}٪ · ${Fa.n(b.commissionPerLot, 2)}$/لات"))
            if (b.curve.size > 1) {
                val eq = EquityChartView(act)
                eq.setCurve(b.curve, b.startCapital)
                col.addView(eq, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 220f)))
            }
            col.addView(Ui.tv(act, "سال‌به‌سال (بدون سانسور؛ منفی‌ها هم هستند):", 11f, Palette.gold, true))
            for (y in b.years) {
                col.addView(Ui.tv(act,
                    "${Fa.d(y.year.toString())} · ${Fa.d(y.trades.toString())} معامله · " +
                        "${Fa.signed(y.sumR)}R · ${Fa.signed(y.netUsd)}$",
                    10f, if (y.netUsd >= 0) Palette.up else Palette.down))
            }
        }
        val br = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        for (y in FxLive.RANGES) {
            br.addView(Ui.btn(act, "بک‌تست ${Fa.d(y.toString())}س", Palette.panel2, Palette.txt, 10f).apply {
                setOnClickListener {
                    d.runBacktest(act, y) { msg -> act.toastPublic(msg) }
                    act.refreshPublic()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = Ui.dp(act, 4f) })
        }
        col.addView(br, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = Ui.dp(act, 8f) })

        col.addView(Ui.section(act, "جمع همهٔ میزهای جفت‌ارز"))
        val tot = FxLive.total()
        col.addView(row(act, "سرمایه", Fa.n(tot.capital, 2)))
        col.addView(row(act, "موجودی / اکوییتی", "${Fa.n(tot.balance, 2)} / ${Fa.n(tot.equity, 2)}"))
        col.addView(row(act, "تحقق‌یافته / شناور", "${Fa.signed(tot.realized)} / ${Fa.signed(tot.floating)}",
            if (tot.realized >= 0) Palette.up else Palette.down))
        col.addView(row(act, "معاملات", "${Fa.d(tot.closedCount.toString())} بسته · ${Fa.d(tot.openCount.toString())} باز"))
        col.addView(row(act, "بیشترین افت", Fa.n(tot.maxDD, 2), Palette.down))
        return sc
    }
}
