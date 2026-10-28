package com.alisport.goldpin

import android.content.Context
import com.alisport.goldpin.core.Candle
import com.alisport.goldpin.core.FxCost
import com.alisport.goldpin.core.FxCostTable
import com.alisport.goldpin.core.FxDesk
import com.alisport.goldpin.core.FxPairs
import com.alisport.goldpin.core.FxProfile
import com.alisport.goldpin.core.FxTrade
import com.alisport.goldpin.data.Feed
import com.alisport.goldpin.util.Fa
import com.alisport.goldpin.util.Log
import org.json.JSONArray
import org.json.JSONObject

// ═══════════════════════════════════════════════════════════════════════════════
//  اجرای کاغذی جفت‌ارزها (۱٫۵٫۰۵) — «الف) اتصال کامل» + «ب) ریسک انتخابی»
//
//  چه می‌کند:
//   • شش جفتِ بازماندهٔ آزمون ۲۰ ساله + ۳ ساله (GBPUSD، GBPJPY، EURGBP، EURCHF،
//     USDCAD، USDCHF) را از Yahoo
//     با کندل ساعتی می‌گیرد و کندل‌های **بسته** را به `FxDesk` (همان کد آزمون‌شده)
//     می‌دهد. هیچ کندل بازی پردازش نمی‌شود ⇒ نتیجه با بک‌تست یکی است.
//   • ریسک هر معامله قابل انتخاب: ۰٫۲۵٪ (ب) یا ۰٫۵٪ (الف) از سرمایهٔ میز.
//   • سرمایهٔ میز **جدا** از طلا/نفت است (پیش‌فرض ۱۰٬۰۰۰ دلار) تا هیچ عددی از
//     موتور اصلی جابه‌جا نشود و قاعدهٔ «هر دو نماد زنده» دست‌نخورده بماند.
//   • وضعیت در SharedPreferences ذخیره و بعد از بستن اپ بازیابی می‌شود.
//   • هزینه‌ها: جدول رسمی آلپاری Pro ECN + کمیسیون ۵ دلار در لات رفت‌وبرگشت.
// ═══════════════════════════════════════════════════════════════════════════════

object FxLive {

    // ── تنظیمات ────────────────────────────────────────────────────────────────
    /** سرمایهٔ اسمی میز جفت‌ارز (دلار) — مستقل از حساب طلا/نفت. */
    var capital: Double = 10_000.0
    /** ریسک هر معامله بر حسب درصد سرمایه: ۰٫۲۵ (محافظه‌کارانه) یا ۰٫۵. */
    var riskPct: Double = 0.25
    /** کمیسیون آپاری Pro ECN: ۲٫۵ دلار هر طرف ⇒ ۵ دلار رفت‌وبرگشت در لات. */
    var commissionPerLot: Double = 5.0

    /**
     * جفت‌هایی که اجرا می‌شوند = پارامترهایی که **۲۰ سال + ۳ سال آخر** را پاس کردند.
     * (در ۱٫۵٫۰۵ شش جفت روزانه؛ جفت‌های درون‌روزی خاموش‌اند چون شاهدشان فقط ۲٫۸ سال است.)
     */
    val activePairs: MutableList<String> = FxPairs.ALL.filter { it.onByDefault }.map { it.id }.toMutableList()

    @Volatile var running: Boolean = false
        private set
    @Volatile var lastError: String? = null
    @Volatile var lastTickMs: Long = 0L
    @Volatile var lastBars: Int = 0

    val desk = FxDesk(capital = capital, riskPct = riskPct, commissionPerLotRoundTurn = commissionPerLot)

    /** نماد Yahoo برای هر جفت. */
    private fun yahooSym(pair: String) = "${pair.uppercase()}=X"

    private val bars = LinkedHashMap<String, MutableList<Candle>>()          // کندل خام (۱ ساعتی)
    private val agg = LinkedHashMap<String, LinkedHashMap<Long, Candle>>()   // باکت‌های تایم‌فریم جفت
    private val emitted = LinkedHashMap<String, Long>()                     // آخرین باکتِ داده‌شده به موتور
    @Volatile private var quotesLoaded = false
    private val costCache = LinkedHashMap<String, FxCost>()
    private var thread: Thread? = null
    private var ctxRef: Context? = null
    private var usdPerQuote = mutableMapOf("USD" to 1.0, "JPY" to 0.0066, "CAD" to 0.72,
        "CHF" to 1.13, "GBP" to 1.27)

    private fun profileOf(id: String): FxProfile? = FxPairs.ALL.firstOrNull { it.id == id }

    // ── چرخهٔ عمر ──────────────────────────────────────────────────────────────
    fun start(ctx: Context) {
        if (running) return
        ctxRef = ctx.applicationContext
        running = true
        lastError = null
        Log.i(Log.CAT_LIVE, "میز جفت‌ارز روشن شد",
            "جفت‌ها=${activePairs.joinToString(",")} ریسک=$riskPct٪ سرمایه=$capital")
        desk.capital = capital
        desk.riskPct = riskPct
        desk.feed = { pair -> FxCostTable.costFor(pair, usdPerQuote) }
        thread = Thread {
            while (running) {
                try { poll() } catch (t: Throwable) {
                    lastError = Fa.short(t.message ?: t.javaClass.simpleName, 160)
                    Log.w(Log.CAT_FEED, "خطای میز جفت‌ارز", lastError ?: "")
                }
                var slept = 0
                while (running && slept < 60_000) { Thread.sleep(500); slept += 500 }
            }
        }.also { it.isDaemon = true; it.name = "fx-desk"; it.start() }
        onUi { Hub.fire() }
    }

    fun stop(ctx: Context?) {
        running = false
        thread?.interrupt(); thread = null
        ctxRef = ctx ?: ctxRef
        persist()
        Log.i(Log.CAT_LIVE, "میز جفت‌ارز خاموش شد", "")
        onUi { Hub.fire() }
    }

    /** تنظیم ریسک (۰٫۲۵ یا ۰٫۵) — فوراً روی معامله‌های بعدی اثر می‌گذارد. */
    fun setRisk(pct: Double) {
        riskPct = pct.coerceIn(0.05, 5.0)
        desk.riskPct = riskPct
        persist()
        Log.i(Log.CAT_CFG, "ریسک میز جفت‌ارز عوض شد", "$riskPct٪ از سرمایه")
    }

    fun setPairs(list: List<String>) {
        activePairs.clear(); activePairs.addAll(list.filter { profileOf(it) != null })
        persist()
    }

    // ── یک تیک: گرفتن کندل و دادن کندل‌های بسته به موتور ───────────────────────
    private fun poll() {
        refreshQuotes()
        var added = 0
        for (id in activePairs.toList()) {
            val p = profileOf(id) ?: continue
            // اولین بار تاریخچهٔ دو ساله (برای N=۴۸ روزانه و ATR کافی است)، بعد فقط تازه‌ها
            val firstTime = !bars.containsKey(id)
            val list = try {
                Feed.yahooChart(yahooSym(id), "1h", if (firstTime) "730d" else "5d")
            } catch (e: Exception) {
                lastError = "$id: " + Fa.short(e.message, 120); continue
            }
            if (list.isEmpty()) continue
            val buf = bars.getOrPut(id) { ArrayList() }
            val lastT = buf.lastOrNull()?.t ?: 0L
            val fresh = list.filter { it.t > lastT }.sortedBy { it.t }
            if (fresh.isEmpty()) continue
            buf.addAll(fresh)
            if (buf.size > 20_000) { buf.subList(0, buf.size - 20_000).clear() }
            added += fresh.size
            lastBars = buf.size

            // ── تجمیع به تایم‌فریم خود جفت و دادن فقط باکت‌های **بسته** ──
            val tfMs = p.tfSec * 1000L
            val buckets = agg.getOrPut(id) { LinkedHashMap() }
            for (c in fresh) {
                val b = Math.floorDiv(c.t, tfMs) * tfMs
                val prev = buckets[b]
                buckets[b] = if (prev == null) Candle(c.bi, b, c.o, c.h, c.l, c.c, c.v)
                else Candle(prev.bi, b, prev.o, Math.max(prev.h, c.h), Math.min(prev.l, c.l), c.c, prev.v + c.v)
            }
            val lastRawT = buf.last().t
            val lastEmit = emitted[id] ?: Long.MIN_VALUE
            val ready = buckets.keys.filter { it > lastEmit && it + tfMs <= lastRawT }.sorted()
            for (b in ready) {
                val bar = buckets[b] ?: continue
                // تاریخچه فقط باکت‌های قبل از این کندل (موتور خودش هم می‌بیند)
                val hist = buckets.entries.filter { it.key <= b }.sortedBy { it.key }.map { it.value }
                desk.onBar(p, hist, bar)
                emitted[id] = b
            }
            // باکت‌های خیلی قدیمی را نگه نداریم
            if (buckets.size > 3000) {
                val keep = buckets.keys.sorted().takeLast(3000).toSet()
                buckets.keys.retainAll(keep)
            }
        }
        lastTickMs = System.currentTimeMillis()
        if (added > 0) { persist(); onUi { Hub.fire() } }
    }

    /** نرخ‌های تبدیل ارز مظنه به دلار — برای جفت‌های کراس (EURCHF، USDCAD، USDCHF) لازم است. */
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

    /**
     * یک‌بار اجرای تاریخچهٔ موجود (مثل بک‌تست کاغذی) تا میز از صفر شروع نکند.
     * از همان دادهٔ ذخیره‌شده استفاده می‌کند؛ هیچ درخواست اضافه‌ای نمی‌زند.
     */
    fun replayHistory() {
        for (id in activePairs.toList()) {
            val p = profileOf(id) ?: continue
            val buf = bars[id] ?: continue
            val tfMs = p.tfSec * 1000L
            val buckets = LinkedHashMap<Long, Candle>()
            for (c in buf) {
                val b = Math.floorDiv(c.t, tfMs) * tfMs
                val prev = buckets[b]
                buckets[b] = if (prev == null) Candle(c.bi, b, c.o, c.h, c.l, c.c, c.v)
                else Candle(prev.bi, b, prev.o, Math.max(prev.h, c.h), Math.min(prev.l, c.l), c.c, prev.v + c.v)
            }
            desk.positions.remove(id)
            val keys = buckets.keys.sorted()
            for (b in keys) {
                val hist = keys.filter { it <= b }.mapNotNull { buckets[it] }
                desk.onBar(p, hist, buckets[b]!!)
            }
            emitted[id] = keys.lastOrNull() ?: Long.MIN_VALUE
        }
        persist()
        onUi { Hub.fire() }
    }

    // ── آمار برای رابط ────────────────────────────────────────────────────────
    data class PairStat(
        val pair: String, val label: String, val trades: Int, val wins: Int,
        val sumR: Double, val open: Boolean, val openDir: Int, val openEntry: Double,
        val lastPx: Double, val floatingUsd: Double
    )

    data class Summary(
        val capital: Double, val balance: Double, val equity: Double,
        val realized: Double, val floating: Double, val closedCount: Int,
        val openCount: Int, val winRate: Double, val maxDD: Double,
        val riskPct: Double, val running: Boolean, val pairs: List<PairStat>,
        val curve: List<Pair<Long, Double>>, val lastError: String?
    )

    fun summary(): Summary {
        val closed = desk.closed.toList()
        val realized = closed.sumOf { it.netUsd() }
        val floating = desk.positions.values.sumOf { floatingUsd(it) }
        val eq = capital + realized + floating
        // منحنی سرمایه از ترتیب زمانی معامله‌های بسته
        val curve = ArrayList<Pair<Long, Double>>(closed.size + 1)
        curve.add(0L to capital)
        var run = capital
        for (t in closed.sortedBy { it.exitT }) { run += t.netUsd(); curve.add(t.exitT to run) }
        var peak = capital; var dd = 0.0
        for (pt in curve) { peak = maxOf(peak, pt.second); dd = maxOf(dd, peak - pt.second) }
        val stats = activePairs.map { id ->
            val p = profileOf(id)
            val all = closed.filter { it.pair == id }
            val sumR = all.sumOf { it.realizedR() }
            val wins = all.count { it.realizedR() > 0 }
            val pos = desk.positions[id]
            PairStat(
                pair = id, label = p?.label ?: id, trades = all.size, wins = wins,
                sumR = sumR, open = pos != null, openDir = pos?.dir ?: 0,
                openEntry = pos?.entryPx ?: Double.NaN,
                lastPx = bars[id]?.lastOrNull()?.c ?: Double.NaN,
                floatingUsd = pos?.let { floatingUsd(it) } ?: 0.0
            )
        }
        return Summary(
            capital = capital, balance = capital + realized, equity = eq,
            realized = realized, floating = floating,
            closedCount = closed.size, openCount = desk.positions.size,
            winRate = if (closed.isEmpty()) 0.0 else 100.0 * closed.count { it.netUsd() > 0 } / closed.size,
            maxDD = dd, riskPct = riskPct, running = running, pairs = stats,
            curve = curve, lastError = lastError
        )
    }

    private fun floatingUsd(t: FxTrade): Double {
        val px = bars[t.pair]?.lastOrNull()?.c ?: return 0.0
        val cost = FxCostTable.costFor(t.pair, usdPerQuote) ?: return 0.0
        val units = t.qtyLots * 100_000.0
        return (px - t.entryPx) * t.dir * units * cost.usdPerQuote -
            t.commissionUsd - t.spreadCostUsd + t.swapUsd + t.grossUsd
    }

    // ── ذخیره/بازیابی ─────────────────────────────────────────────────────────
    private fun prefs() = ctxRef?.getSharedPreferences("goldpin_fx", Context.MODE_PRIVATE)

    fun persist() {
        val p = prefs() ?: return
        try {
            val root = JSONObject()
            root.put("capital", capital)
            root.put("riskPct", riskPct)
            root.put("running", running)
            root.put("pairs", JSONArray(activePairs))
            val arr = JSONArray()
            for (t in desk.closed) {
                arr.put(JSONObject().apply {
                    put("id", t.id); put("pair", t.pair); put("dir", t.dir)
                    put("entryT", t.entryT); put("entryPx", t.entryPx)
                    put("qty", t.qtyLots); put("sl0", t.sl0); put("riskUsd", t.riskUsd)
                    put("targetR", t.targetR); put("exitT", t.exitT); put("exitPx", t.exitPx)
                    put("swap", t.swapUsd); put("nights", t.swapNights)
                    put("gross", t.grossUsd); put("comm", t.commissionUsd)
                    put("spread", t.spreadCostUsd); put("partial", t.partialTaken)
                    put("sl", t.sl); put("lastRoll", t.lastRollDay)
                })
            }
            root.put("closed", arr)
            p.edit().putString("state", root.toString()).apply()
        } catch (e: Exception) { Log.w(Log.CAT_CFG, "ذخیرهٔ میز جفت‌ارز ناموفق", Fa.short(e.message, 100)) }
    }

    fun restore(ctx: Context) {
        ctxRef = ctx.applicationContext
        val p = prefs() ?: return
        val s = p.getString("state", null) ?: return
        try {
            val root = JSONObject(s)
            capital = root.optDouble("capital", capital)
            riskPct = root.optDouble("riskPct", riskPct)
            desk.capital = capital
            desk.riskPct = riskPct
            desk.feed = { pair -> FxCostTable.costFor(pair, usdPerQuote) }
            val arr = root.optJSONArray("closed") ?: JSONArray()
            desk.closed.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = FxTrade(
                    id = o.optLong("id", (i + 1).toLong()), pair = o.getString("pair"),
                    dir = o.optInt("dir", 1), entryT = o.optLong("entryT"),
                    entryPx = o.optDouble("entryPx"), qtyLots = o.optDouble("qty"),
                    sl0 = o.optDouble("sl0"), riskUsd = o.optDouble("riskUsd"),
                    targetR = o.optDouble("targetR", 4.0)
                )
                t.exitT = o.optLong("exitT"); t.exitPx = o.optDouble("exitPx")
                t.swapUsd = o.optDouble("swap"); t.swapNights = o.optInt("nights")
                t.grossUsd = o.optDouble("gross"); t.commissionUsd = o.optDouble("comm")
                t.spreadCostUsd = o.optDouble("spread"); t.partialTaken = o.optBoolean("partial")
                t.sl = o.optDouble("sl", t.sl0); t.lastRollDay = o.optLong("lastRoll", Long.MIN_VALUE)
                t.open = false
                desk.closed.add(t)
            }
            desk.nextId = (desk.closed.maxOfOrNull { it.id } ?: 0L) + 1
            val pr = root.optJSONArray("pairs")
            if (pr != null && pr.length() > 0) {
                activePairs.clear()
                for (i in 0 until pr.length()) pr.optString(i).let { if (profileOf(it) != null) activePairs.add(it) }
            }
            Log.i(Log.CAT_CFG, "میز جفت‌ارز بازیابی شد",
                "معاملات=${desk.closed.size} ریسک=$riskPct٪ جفت‌ها=${activePairs.joinToString(",")}")
        } catch (e: Exception) {
            Log.w(Log.CAT_CFG, "بازیابی میز جفت‌ارز ناموفق", Fa.short(e.message, 120))
        }
    }

    private fun onUi(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
        else android.os.Handler(android.os.Looper.getMainLooper()).post { block() }
    }
}
