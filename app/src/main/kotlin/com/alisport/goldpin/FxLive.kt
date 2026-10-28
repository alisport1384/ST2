package com.alisport.goldpin

import android.content.Context
import com.alisport.goldpin.core.Candle
import com.alisport.goldpin.core.FxBacktest
import com.alisport.goldpin.core.FxBacktestResult
import com.alisport.goldpin.core.FxCost
import com.alisport.goldpin.core.FxCostTable
import com.alisport.goldpin.core.FxDesk
import com.alisport.goldpin.core.FxPairs
import com.alisport.goldpin.core.FxProfile
import com.alisport.goldpin.core.FxTrade
import com.alisport.goldpin.core.FxYearStat
import com.alisport.goldpin.data.Feed
import com.alisport.goldpin.util.Fa
import com.alisport.goldpin.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// ═══════════════════════════════════════════════════════════════════════════════
//  میز جفت‌ارز — نسخهٔ ۱٫۵٫۰۶ «یک میز کامل برای هر جفت، مثل طلا و نفت»
//
//  خواستهٔ مالک (این نسخه):
//   ۱) برای هر جفت‌ارز انتخابی، **یک میز مستقل** مثل طلا و نفت.
//   ۲) هر میز **پارامترهای مخصوص خودش** را داشته باشد (همان پارامترهایی که
//      walk-forward ۲۰ ساله برای همان جفت انتخاب کرده: تایم‌فریم، حالت، N، ATR، هدف، پله).
//   ۳) هر میز **آزمون ۲۰ سالهٔ خودش** را داشته باشد — درون‌اپ روی دادهٔ واقعی، با
//      همان کد موتور (`FxDesk`) که اجرای زنده از آن استفاده می‌کند.
//   ۴) هر میز در **تب مدیریت جداگانه** محاسبه شود: سرمایه، موجودی، اکوییتی، سود/زیان
//      تحقق‌یافته و شناور، افت سرمایه، نرخ برد و منحنی سرمایه — دقیقاً همان الگوی طلا/نفت.
//
//  قاعده‌های تغییرناپذیر:
//   • هیچ خطی از `Engine`/`PaperBroker` طلا و نفت خوانده یا تغییر داده نمی‌شود.
//   • سرمایهٔ هر میز **جدا** است؛ جمع میزهای جفت‌ارز هیچ‌وقت با جمع طلا/نفت قاطی نمی‌شود.
//   • سوئیچ هر میز مستقل است؛ روشن‌کردن یک جفت به بقیه کاری ندارد.
//   • نتایج فقط با بک‌تست تازه یا حذف صریح از بین می‌روند و در SharedPreferences می‌مانند.
// ═══════════════════════════════════════════════════════════════════════════════

/** خلاصهٔ حسابداری یک میز جفت‌ارز (هم‌ارز `PairStat` طلا/نفت). */
data class FxDeskSummary(
    val id: String,
    val label: String,
    val enabled: Boolean,
    val running: Boolean,
    val capital: Double,
    val balance: Double,
    val equity: Double,
    val realized: Double,
    val floating: Double,
    val closedCount: Int,
    val openCount: Int,
    val wins: Int,
    val winRate: Double,
    val maxDD: Double,
    val maxDDPct: Double,
    val sumR: Double,
    val avgR: Double,
    val curve: List<Pair<Long, Double>>,
    val riskPct: Double,
    val lastPrice: Double,
    val openDir: Int,
    val openEntry: Double,
    val lastError: String?,
    val lastTickMs: Long,
    val bars: Int,
    val backtest: FxBacktestResult?
)

/**
 * یک میز مستقل برای یک جفت‌ارز.
 *
 * همهٔ حالت‌های یک نماد طلا/نفت را دارد: سرمایه، ریسک، موتور، سفارش‌ها، معاملات،
 * منحنی سرمایه، نتیجهٔ بک‌تست، و فایل ذخیرهٔ خودش.
 */
class FxPairDesk(val profile: FxProfile) {

    // ── تنظیمات مستقل این میز ──────────────────────────────────────────────────
    var enabled: Boolean = profile.onByDefault
    var running: Boolean = false
        private set

    /** سرمایهٔ اسمی همین میز (دلار) — کاملاً جدا از میزهای دیگر و از طلا/نفت. */
    var capital: Double = 10_000.0
        private set

    /** ریسک هر معامله بر حسب درصد سرمایهٔ همین میز: ۰٫۲۵ (ب) یا ۰٫۵ (الف). */
    var riskPct: Double = 0.25
        private set

    /** کمیسیون آلپاری Pro ECN: ۲٫۵ دلار هر طرف ⇒ ۵ دلار رفت‌وبرگشت برای هر لات. */
    val commissionPerLot: Double = 5.0

    val desk = FxDesk(capital = 10_000.0, riskPct = 0.25, commissionPerLotRoundTurn = 5.0)

    // ── وضعیت اجرای زنده ──────────────────────────────────────────────────────
    @Volatile var lastError: String? = null
        private set
    @Volatile var lastTickMs: Long = 0L
        private set
    @Volatile var lastPrice: Double = Double.NaN
    var lastBars: Int = 0
        private set

    /** کندل‌های خام ساعتی همین جفت. */
    private val raw = ArrayList<Candle>()
    /** باکت‌های تایم‌فریم همین جفت. */
    private val buckets = LinkedHashMap<Long, Candle>()
    private var lastEmit: Long = Long.MIN_VALUE

    // ── نتیجهٔ بک‌تست ۲۰ سالهٔ همین میز ────────────────────────────────────────
    @Volatile var backtest: FxBacktestResult? = null
    @Volatile var backtestBusy: Boolean = false
        private set
    @Volatile var backtestNote: String? = null

    // ───────────────────────────────────────────────────────────────────────────
    //  تنظیمات
    // ───────────────────────────────────────────────────────────────────────────

    fun setEnabled(ctx: Context?, on: Boolean) {
        enabled = on
        if (!on) stop(ctx)
        persist()
        Log.i(Log.CAT_CFG, "میز ${profile.id}: ${if (on) "فعال" else "غیرفعال"} شد", "")
    }

    fun setRisk(pct: Double) {
        riskPct = pct.coerceIn(0.05, 5.0)
        desk.riskPct = riskPct
        persist()
        Log.i(Log.CAT_CFG, "ریسک میز ${profile.id} عوض شد", "${Fa.n(riskPct, 2)}٪ از سرمایه")
    }

    fun setCapital(usd: Double) {
        capital = usd.coerceIn(100.0, 10_000_000.0)
        desk.capital = capital
        persist()
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  چرخهٔ اجرای کاغذی — مستقل از میزهای دیگر
    // ───────────────────────────────────────────────────────────────────────────

    fun start(ctx: Context?) {
        if (running) return
        if (ctx != null) ctxRef = ctx.applicationContext
        enabled = true
        running = true
        lastError = null
        desk.capital = capital
        desk.riskPct = riskPct
        desk.feed = { FxCostTable.costFor(profile.id, FxLive.usdPerQuote) }
        Log.i(Log.CAT_LIVE, "میز ${profile.id} روشن شد",
            "ریسک=${Fa.n(riskPct, 2)}٪ سرمایه=${Fa.n(capital, 0)}")
        FxLive.ensureLoop()
    }

    fun stop(ctx: Context?) {
        if (!running) return
        running = false
        if (ctx != null) ctxRef = ctx.applicationContext
        persist()
        Log.i(Log.CAT_LIVE, "میز ${profile.id} خاموش شد", "")
    }

    /**
     * یک تیک این میز: گرفتن کندل ساعتی همین جفت، تجمیع به تایم‌فریم خودش، و دادن
     * **فقط باکت‌های بسته** به موتور. هیچ کندل نیمه‌کاره‌ای معامله نمی‌سازد.
     */
    fun tick() {
        val cost = FxCostTable.costFor(profile.id, FxLive.usdPerQuote)
        if (cost == null) { lastError = "هزینهٔ بروکر برای ${profile.id} تعریف نشده"; return }
        desk.feed = { cost }

        val firstTime = raw.isEmpty()
        val list = try {
            Feed.yahooChart("${profile.id}=X", "1h", if (firstTime) "730d" else "5d")
        } catch (e: Exception) {
            lastError = Fa.short(e.message, 140)
            return
        }
        if (list.isEmpty()) { lastError = "فید خالی برگرداند"; return }
        lastError = null

        val lastT = raw.lastOrNull()?.t ?: 0L
        val fresh = list.filter { it.t > lastT }.sortedBy { it.t }
        if (fresh.isNotEmpty()) {
            raw.addAll(fresh)
            if (raw.size > 20_000) raw.subList(0, raw.size - 20_000).clear()
            // تجمیع به تایم‌فریم همین جفت
            val tfMs = profile.tfSec * 1000L
            for (c in fresh) {
                val b = Math.floorDiv(c.t, tfMs) * tfMs
                val prev = buckets[b]
                buckets[b] = if (prev == null) Candle(c.bi, b, c.o, c.h, c.l, c.c, c.v)
                else Candle(prev.bi, b, prev.o, Math.max(prev.h, c.h), Math.min(prev.l, c.l), c.c, prev.v + c.v)
            }
            if (buckets.size > 3000) {
                val keep = buckets.keys.sorted().takeLast(3000).toSet()
                buckets.keys.retainAll(keep)
            }
        }
        lastPrice = raw.lastOrNull()?.c ?: Double.NaN
        lastBars = raw.size
        lastTickMs = System.currentTimeMillis()

        // فقط باکت‌های بسته و تازه
        val tfMs = profile.tfSec * 1000L
        val lastRawT = raw.lastOrNull()?.t ?: return
        val ready = buckets.keys.filter { it > lastEmit && it + tfMs <= lastRawT }.sorted()
        if (ready.isEmpty()) return
        val keys = buckets.keys.sorted()
        for (b in ready) {
            val bar = buckets[b] ?: continue
            val hist = keys.filter { it <= b }.mapNotNull { buckets[it] }
            desk.onBar(profile, hist, bar)
            lastEmit = b
        }
        persist()
        FxLive.fire()
    }

    /** اجرای تاریخچهٔ موجود روی موتور (بدون درخواست تازه) — میز از صفر شروع نکند. */
    fun replay() {
        val cost = FxCostTable.costFor(profile.id, FxLive.usdPerQuote) ?: return
        desk.feed = { cost }
        val tfMs = profile.tfSec * 1000L
        val map = LinkedHashMap<Long, Candle>()
        for (c in raw) {
            val b = Math.floorDiv(c.t, tfMs) * tfMs
            val prev = map[b]
            map[b] = if (prev == null) Candle(c.bi, b, c.o, c.h, c.l, c.c, c.v)
            else Candle(prev.bi, b, prev.o, Math.max(prev.h, c.h), Math.min(prev.l, c.l), c.c, c.v)
        }
        desk.positions.remove(profile.id)
        val keys = map.keys.sorted()
        for (b in keys) {
            val hist = keys.filter { it <= b }.mapNotNull { map[it] }
            desk.onBar(profile, hist, map[b]!!)
        }
        lastEmit = keys.lastOrNull() ?: Long.MIN_VALUE
        persist()
        FxLive.fire()
    }

    /** پاک‌کردن نتایج همین میز (فقط همین جفت؛ بقیه و طلا/نفت دست نمی‌خورند). */
    fun clearResults() {
        desk.reset()
        lastEmit = Long.MIN_VALUE
        buckets.clear()
        persist()
        FxLive.fire()
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  بک‌تست ۲۰ سالهٔ همین میز (درون‌اپ، روی دادهٔ واقعی ریلیز)
    // ───────────────────────────────────────────────────────────────────────────

    /**
     * آزمون ۲۰ سالهٔ همین جفت با پارامترهای خودش.
     *
     * داده از بستهٔ رسمی ریلیز (`goldpin_fx_20y_d1.zip`) گرفته و یک‌بار در حافظهٔ اپ
     * کش می‌شود؛ بعد از آن هیچ دانلودی لازم نیست. اگر شبکه نبود، از کش می‌خواند.
     */
    fun runBacktest(ctx: Context, onDone: (String) -> Unit) {
        if (backtestBusy) { onDone("آزمون همین میز در جریان است"); return }
        backtestBusy = true
        backtestNote = "در حال گرفتن دادهٔ ۲۰ ساله…"
        val app = ctx.applicationContext
        Thread {
            var msg: String
            try {
                val zip = FxLive.datasetZip(app, "goldpin_fx_20y_d1.zip") { note -> backtestNote = note }
                val csv = FxBacktest.pairCsvFromZip(zip, profile.id)
                if (csv == null) {
                    msg = "فایل ${profile.id} در بستهٔ ۲۰ ساله نبود"
                } else {
                    val bars = FxBacktest.parseCsv(csv)
                    val tfBars = if (profile.tfSec == 86_400L) bars
                                 else FxBacktest.aggregate(bars, profile.tfSec)
                    val cost = FxCostTable.costFor(profile.id, FxLive.usdPerQuote)
                    if (cost == null) {
                        msg = "هزینهٔ بروکر برای ${profile.id} تعریف نشده"
                    } else {
                        backtestNote = "در حال آزمون ${profile.id} روی ${Fa.d(tfBars.size.toString())} کندل…"
                        val r = FxBacktest.run(
                            profile, tfBars, cost,
                            capital = capital, riskPct = riskPct,
                            commissionPerLot = commissionPerLot,
                            source = "goldpin_fx_20y_d1.zip"
                        )
                        backtest = r
                        msg = "${profile.id}: ${Fa.d(r.trades.toString())} معامله · " +
                            "میانگین ${Fa.n(r.avgR, 3)}R · PF ${Fa.n(r.pf, 2)}"
                        Log.i(Log.CAT_APP, "بک‌تست ۲۰ سالهٔ میز ${profile.id} تمام شد", msg)
                    }
                }
            } catch (e: Throwable) {
                msg = "خطا: " + Fa.short(e.message ?: e.javaClass.simpleName, 120)
                Log.w(Log.CAT_APP, "بک‌تست میز ${profile.id} ناموفق", msg)
            }
            backtestBusy = false
            persist()
            FxLive.onUi { FxLive.fire() }
            FxLive.onUi { onDone(msg) }
        }.also { it.isDaemon = true; it.name = "fx-bt-${profile.id}" }.start()
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  حسابداری این میز (هم‌ارز محاسبهٔ طلا/نفت در تب مدیریت)
    // ───────────────────────────────────────────────────────────────────────────

    fun summary(): FxDeskSummary {
        val closed = desk.closed.toList()
        val realized = closed.sumOf { it.netUsd() }
        val pos = desk.positions[profile.id]
        val floating = pos?.let { floatingUsd(it) } ?: 0.0
        val eq = capital + realized + floating

        val curve = ArrayList<Pair<Long, Double>>(closed.size + 1)
        curve.add(0L to capital)
        var run = capital
        var peak = capital
        var dd = 0.0
        for (t in closed.sortedBy { it.exitT }) {
            run += t.netUsd()
            curve.add(t.exitT to run)
            peak = Math.max(peak, run)
            dd = Math.max(dd, peak - run)
        }
        val wins = closed.count { it.netUsd() > 0 }
        val sumR = closed.sumOf { it.realizedR() }
        return FxDeskSummary(
            id = profile.id, label = profile.label, enabled = enabled, running = running,
            capital = capital, balance = capital + realized, equity = eq,
            realized = realized, floating = floating,
            closedCount = closed.size, openCount = if (pos != null) 1 else 0,
            wins = wins,
            winRate = if (closed.isEmpty()) 0.0 else 100.0 * wins / closed.size,
            maxDD = dd,
            maxDDPct = if (capital > 0) 100.0 * dd / capital else 0.0,
            sumR = sumR,
            avgR = if (closed.isEmpty()) 0.0 else sumR / closed.size,
            curve = curve, riskPct = riskPct,
            lastPrice = if (lastPrice.isNaN()) Double.NaN else lastPrice,
            openDir = pos?.dir ?: 0, openEntry = pos?.entryPx ?: Double.NaN,
            lastError = lastError, lastTickMs = lastTickMs, bars = raw.size,
            backtest = backtest
        )
    }

    private fun floatingUsd(t: FxTrade): Double {
        val px = raw.lastOrNull()?.c ?: return 0.0
        val cost = FxCostTable.costFor(profile.id, FxLive.usdPerQuote) ?: return 0.0
        val units = t.qtyLots * 100_000.0
        return t.grossUsd + (px - t.entryPx) * t.dir * units * cost.usdPerQuote -
            t.commissionUsd - t.spreadCostUsd + t.swapUsd
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  ذخیره/بازیابی — هر میز کلید خودش را دارد
    // ───────────────────────────────────────────────────────────────────────────

    private var ctxRef: Context? = null

    private fun prefs() = ctxRef?.getSharedPreferences("goldpin_fx", Context.MODE_PRIVATE)

    fun persist() {
        val p = prefs() ?: return
        try {
            val root = JSONObject()
            root.put("enabled", enabled)
            root.put("running", running)
            root.put("capital", capital)
            root.put("riskPct", riskPct)
            val arr = JSONArray()
            for (t in desk.closed) {
                arr.put(JSONObject().apply {
                    put("id", t.id); put("dir", t.dir)
                    put("entryT", t.entryT); put("entryPx", t.entryPx)
                    put("qty", t.qtyLots); put("sl0", t.sl0); put("riskUsd", t.riskUsd)
                    put("targetR", t.targetR); put("exitT", t.exitT); put("exitPx", t.exitPx)
                    put("swap", t.swapUsd); put("nights", t.swapNights)
                    put("gross", t.grossUsd); put("comm", t.commissionUsd)
                    put("spread", t.spreadCostUsd); put("partial", t.partialTaken)
                })
            }
            root.put("closed", arr)
            backtest?.let { r ->
                root.put("bt", JSONObject().apply {
                    put("pair", r.pair); put("tfFa", r.tfFa); put("modeFa", r.modeFa)
                    put("paramsFa", r.paramsFa); put("bars", r.bars)
                    put("fromT", r.fromT); put("toT", r.toT)
                    put("trades", r.trades); put("wins", r.wins)
                    put("sumR", r.sumR); put("avgR", r.avgR); put("pf", r.pf)
                    put("netUsd", r.netUsd); put("maxDdUsd", r.maxDdUsd); put("maxDdR", r.maxDdR)
                    put("startCapital", r.startCapital); put("riskPct", r.riskPct)
                    put("commission", r.commissionPerLot)
                    put("runAt", r.runAtMs); put("source", r.source)
                    put("curve", JSONArray().apply {
                        val step = Math.max(1, r.curve.size / 400)
                        var i = 0
                        while (i < r.curve.size) { put(JSONArray().put(r.curve[i].first).put(r.curve[i].second)); i += step }
                        if (r.curve.isNotEmpty()) put(JSONArray().put(r.curve.last().first).put(r.curve.last().second))
                    })
                    put("years", JSONArray().apply {
                        for (y in r.years) put(JSONArray().put(y.year).put(y.netUsd).put(y.trades).put(y.sumR))
                    })
                })
            }
            p.edit().putString("pair_${profile.id}", root.toString()).apply()
        } catch (e: Exception) {
            Log.w(Log.CAT_CFG, "ذخیرهٔ میز ${profile.id} ناموفق", Fa.short(e.message, 100))
        }
    }

    fun restore(ctx: Context) {
        ctxRef = ctx.applicationContext
        val p = prefs() ?: return
        val s = p.getString("pair_${profile.id}", null) ?: return
        try {
            val root = JSONObject(s)
            enabled = root.optBoolean("enabled", enabled)
            capital = root.optDouble("capital", capital)
            riskPct = root.optDouble("riskPct", riskPct)
            desk.capital = capital
            desk.riskPct = riskPct
            desk.feed = { FxCostTable.costFor(profile.id, FxLive.usdPerQuote) }
            val arr = root.optJSONArray("closed") ?: JSONArray()
            desk.closed.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = FxTrade(
                    id = o.optLong("id", (i + 1).toLong()), pair = profile.id,
                    dir = o.optInt("dir", 1), entryT = o.optLong("entryT"),
                    entryPx = o.optDouble("entryPx"), qtyLots = o.optDouble("qty"),
                    sl0 = o.optDouble("sl0"), riskUsd = o.optDouble("riskUsd"),
                    targetR = o.optDouble("targetR", 4.0)
                )
                t.exitT = o.optLong("exitT"); t.exitPx = o.optDouble("exitPx")
                t.swapUsd = o.optDouble("swap"); t.swapNights = o.optInt("nights")
                t.grossUsd = o.optDouble("gross"); t.commissionUsd = o.optDouble("comm")
                t.spreadCostUsd = o.optDouble("spread"); t.partialTaken = o.optBoolean("partial")
                t.open = false
                desk.closed.add(t)
            }
            desk.nextId = (desk.closed.maxOfOrNull { it.id } ?: 0L) + 1
            root.optJSONObject("bt")?.let { b ->
                val curve = ArrayList<Pair<Long, Double>>()
                b.optJSONArray("curve")?.let { ca ->
                    for (i in 0 until ca.length()) {
                        val a = ca.getJSONArray(i)
                        curve.add(a.getLong(0) to a.getDouble(1))
                    }
                }
                val years = ArrayList<FxYearStat>()
                b.optJSONArray("years")?.let { ya ->
                    for (i in 0 until ya.length()) {
                        val a = ya.getJSONArray(i)
                        years.add(FxYearStat(a.getInt(0), a.getDouble(1), a.getInt(2), a.getDouble(3)))
                    }
                }
                backtest = FxBacktestResult(
                    pair = b.optString("pair", profile.id), label = profile.label,
                    tfFa = b.optString("tfFa", profile.tfFa),
                    modeFa = b.optString("modeFa", profile.modeFa),
                    paramsFa = b.optString("paramsFa", ""),
                    bars = b.optInt("bars"), fromT = b.optLong("fromT"), toT = b.optLong("toT"),
                    trades = b.optInt("trades"), wins = b.optInt("wins"),
                    sumR = b.optDouble("sumR"), avgR = b.optDouble("avgR"), pf = b.optDouble("pf"),
                    netUsd = b.optDouble("netUsd"), maxDdUsd = b.optDouble("maxDdUsd"),
                    maxDdR = b.optDouble("maxDdR"),
                    startCapital = b.optDouble("startCapital", capital),
                    riskPct = b.optDouble("riskPct", riskPct),
                    commissionPerLot = b.optDouble("commission", commissionPerLot),
                    curve = curve, years = years,
                    runAtMs = b.optLong("runAt"), source = b.optString("source", "")
                )
            }
            Log.i(Log.CAT_CFG, "میز ${profile.id} بازیابی شد",
                "معاملات=${desk.closed.size} ریسک=${Fa.n(riskPct, 2)}٪ " +
                    "بک‌تست=${if (backtest != null) "دارد" else "ندارد"}")
        } catch (e: Exception) {
            Log.w(Log.CAT_CFG, "بازیابی میز ${profile.id} ناموفق", Fa.short(e.message, 120))
        }
    }
}

/** جمع میزهای جفت‌ارز — همیشه **جدا** از جمع طلا/نفت. */
data class FxPortfolio(
    val capital: Double, val balance: Double, val equity: Double,
    val realized: Double, val floating: Double,
    val closedCount: Int, val openCount: Int, val winRate: Double,
    val maxDD: Double, val curve: List<Pair<Long, Double>>,
    val enabledCount: Int, val runningCount: Int,
    val desks: List<FxDeskSummary>
)

object FxLive {

    /** یک میز برای هر جفت — به همان ترتیب جدول پارامترها. */
    val desks: List<FxPairDesk> = FxPairs.ALL.map { FxPairDesk(it) }

    fun desk(id: String): FxPairDesk? = desks.firstOrNull { it.profile.id == id }

    val enabledDesks: List<FxPairDesk> get() = desks.filter { it.enabled }
    val anyRunning: Boolean get() = desks.any { it.running }

    /** نرخ تبدیل ارز مظنه به دلار (برای جفت‌های کراس) — مشترک بین میزها. */
    val usdPerQuote: MutableMap<String, Double> = java.util.concurrent.ConcurrentHashMap(
        mapOf("USD" to 1.0, "JPY" to 0.0066, "CAD" to 0.72, "CHF" to 1.13, "GBP" to 1.27)
    )

    @Volatile private var quotesLoaded = false
    @Volatile private var loop: Thread? = null
    @Volatile private var ctxRef: Context? = null

    fun onUi(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
        else android.os.Handler(android.os.Looper.getMainLooper()).post { block() }
    }

    fun fire() = onUi { try { Hub.fire() } catch (_: Throwable) { } }

    // ── چرخهٔ عمر ──────────────────────────────────────────────────────────────
    fun init(ctx: Context) {
        ctxRef = ctx.applicationContext
        desks.forEach { it.restore(ctx) }
        Log.i(Log.CAT_APP, "میزهای جفت‌ارز آماده شدند",
            "تعداد=${desks.size} فعال=${enabledDesks.joinToString { it.profile.id }}")
    }

    fun persistAll() = desks.forEach { it.persist() }

    fun start(ctx: Context, id: String) {
        desk(id)?.start(ctx)
        ensureLoop()
        fire()
    }

    fun stop(ctx: Context, id: String) {
        desk(id)?.stop(ctx)
        fire()
    }

    fun startAllEnabled(ctx: Context) {
        enabledDesks.forEach { it.start(ctx) }
        ensureLoop()
        fire()
    }

    fun stopAll(ctx: Context) {
        desks.forEach { it.stop(ctx) }
        fire()
    }

    /** حلقهٔ مشترک: هر ۶۰ ثانیه همهٔ میزهای روشن را تیک می‌زند. */
    fun ensureLoop() {
        if (loop?.isAlive == true) return
        loop = Thread {
            while (desks.any { it.running }) {
                try {
                    refreshQuotes()
                    for (d in desks.toList()) {
                        if (!d.running) continue
                        try { d.tick() } catch (t: Throwable) {
                            Log.w(Log.CAT_FEED, "خطای میز ${d.profile.id}", Fa.short(t.message, 120))
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(Log.CAT_FEED, "خطای حلقهٔ جفت‌ارز", Fa.short(t.message, 120))
                }
                var slept = 0
                while (slept < 60_000 && desks.any { it.running }) { Thread.sleep(500); slept += 500 }
            }
            fire()
        }.also { it.isDaemon = true; it.name = "fx-desks"; it.start() }
    }

    /** نرخ تبدیل ارزهای مظنه به دلار — لازم برای EURCHF/USDCAD/USDCHF و جفت‌های ین. */
    private fun refreshQuotes() {
        if (quotesLoaded) return
        try {
            for ((quote, ref) in listOf("JPY" to "USDJPY", "CAD" to "USDCAD", "CHF" to "USDCHF", "GBP" to "GBPUSD")) {
                val c = try { Feed.yahooChart("$ref=X", "1d", "5d") } catch (_: Exception) { emptyList() }
                val last = c.lastOrNull()?.c ?: continue
                usdPerQuote[quote] = if (ref == "GBPUSD") last else 1.0 / last
            }
            usdPerQuote["USD"] = 1.0
            quotesLoaded = true
            Log.i(Log.CAT_FEED, "نرخ تبدیل جفت‌ارزها به‌روز شد", usdPerQuote.toString())
        } catch (e: Exception) {
            Log.w(Log.CAT_FEED, "نرخ تبدیل به‌روز نشد", Fa.short(e.message, 80))
        }
    }

    fun refreshQuotesNow() { quotesLoaded = false; refreshQuotes() }

    // ── جمع همهٔ میزها (همیشه جدا از طلا/نفت) ─────────────────────────────────
    fun total(): FxPortfolio {
        val sums = desks.map { it.summary() }
        val cap = sums.sumOf { it.capital }
        val realized = sums.sumOf { it.realized }
        val floating = sums.sumOf { it.floating }
        val closed = sums.sumOf { it.closedCount }
        val wins = sums.sumOf { it.wins }

        // منحنی جمعی: همهٔ معاملات بستهٔ همهٔ میزها به ترتیب زمان خروج
        val all = ArrayList<Triple<Long, Double, Int>>()   // (زمان، سود خالص، میز)
        for (d in desks) for (t in d.desk.closed) all.add(Triple(t.exitT, t.netUsd(), 0))
        all.sortBy { it.first }
        val fused = ArrayList<Pair<Long, Double>>(all.size + 1)
        fused.add(0L to cap)
        var run = cap
        for (a in all) { run += a.second; fused.add(a.first to run) }
        var peak = cap
        var dd = 0.0
        for (p in fused) { peak = Math.max(peak, p.second); dd = Math.max(dd, peak - p.second) }

        return FxPortfolio(
            capital = cap, balance = cap + realized, equity = cap + realized + floating,
            realized = realized, floating = floating,
            closedCount = closed, openCount = sums.sumOf { it.openCount },
            winRate = if (closed == 0) 0.0 else 100.0 * wins / closed,
            maxDD = dd, curve = fused,
            enabledCount = sums.count { it.enabled }, runningCount = sums.count { it.running },
            desks = sums
        )
    }

    // ── دادهٔ ۲۰ ساله (بستهٔ رسمی ریلیز) ──────────────────────────────────────
    private val DATASET_URLS = listOf(
        "https://github.com/alisport1384/ST2/releases/download/data-fx-v1/",
        "https://github.com/alisport1384/ST1/releases/download/data-fx-v1/"
    )

    /**
     * بایت‌های بستهٔ داده را برمی‌گرداند: اول کش داخلی، بعد دانلود از ST2 و بعد ST1.
     * کش در `filesDir/fxdata/` می‌ماند، پس آزمون دوم به بعد آفلاین هم کار می‌کند.
     */
    fun datasetZip(ctx: Context, name: String, onNote: (String) -> Unit = {}): ByteArray {
        val dir = File(ctx.filesDir, "fxdata").apply { mkdirs() }
        val f = File(dir, name)
        if (f.exists() && f.length() > 100_000) return f.readBytes()
        var lastErr: String? = null
        for (base in DATASET_URLS) {
            try {
                onNote("دانلود $name از ${if (base.contains("ST2")) "ST2" else "ST1"}…")
                val bytes = httpGet(base + name, 60_000, 180_000)
                if (bytes.size > 100_000) {
                    f.writeBytes(bytes)
                    onNote("ذخیره شد (${Fa.d((bytes.size / 1024).toString())} کیلوبایت)")
                    return bytes
                }
                lastErr = "حجم ناچیز: ${bytes.size}"
            } catch (e: Exception) {
                lastErr = Fa.short(e.message ?: e.javaClass.simpleName, 90)
            }
        }
        throw IllegalStateException("دانلود داده ممکن نشد — $lastErr")
    }

    private fun httpGet(url: String, connectMs: Int, readMs: Int): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connectMs
        conn.readTimeout = readMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (GoldPin)")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.readBytes()
        } finally { conn.disconnect() }
    }
}
