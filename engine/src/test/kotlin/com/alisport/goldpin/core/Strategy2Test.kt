package com.alisport.goldpin.core

import java.util.zip.GZIPInputStream
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * استراتژی دوم (انتخابی؛ پیش‌فرض خاموش = استراتژی ۱ = سند):
 *  • تعیین روند حذف می‌شود (بدون کندل مهم/پین/HH/HL/LL/LH)؛
 *  • روی همهٔ کندل‌های ساختار ناحیه ثبت می‌شود: سبز ⇒ ناحیهٔ خرید، قرمز ⇒ ناحیهٔ فروش، با همان الگوریتم و ضوابط؛
 *  • حد سود فقط «واحد» است (پیش‌فرض ۱۰)، بدون فیبو و بدون TP1/TP2؛
 *  • هم‌زمان یک سفارش خرید و یک سفارش فروش مجاز است، ولی از هر جهت فقط یکی (معلق یا باز)؛
 *  • بقیهٔ شرایط دقیقاً همان استراتژی ۱.
 */
class Strategy2Test {

    private fun sample(): List<Candle> {
        val rows = ArrayList<DoubleArray>()
        GZIPInputStream(java.io.File("../app/src/main/assets/sample_xauusd_m1.csv.gz").inputStream())
            .bufferedReader().useLines { ls -> ls.forEach { ln ->
                val p = ln.split(",")
                if (p.size >= 6) runCatching {
                    rows.add(doubleArrayOf(p[0].toDouble(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble()))
                }
            } }
        return rows.mapIndexed { i, r -> Candle(i, r[0].toLong(), r[1], r[2], r[3], r[4], r[5]) }
    }

    private fun run(c: Settings, candles: List<Candle>, perBar: ((Engine, PaperBroker) -> Unit)? = null): Pair<Engine, PaperBroker> {
        val e = Engine(c); val b = PaperBroker(c)
        e.broker = b; b.engine = e
        e.barCommitHook = { cd -> b.onBar(cd, live = false); perBar?.invoke(e, b) }
        e.feed(candles, lastIsClosed = true)
        return e to b
    }

    private fun s2(units: Double = 10.0) = Settings().apply { strategy = Strategy.NO_TREND; tpUnits = units }

    // ── استراتژی ۱ دست‌نخورده است ───────────────────────────────────────────────
    @Test
    fun `default is the document strategy and its numbers on the sample did not change`() {
        val c = Settings()
        assertEquals(Strategy.DOC, c.strategy, "پیش‌فرض: استراتژی ۱ (سند)")
        assertEquals(10.0, c.tpUnits, 0.0)
        val (e, b) = run(c, sample())
        val sm = Reports.summary(b.trades)
        assertEquals(103, sm.trades)
        assertEquals(12, sm.wins); assertEquals(48, sm.losses); assertEquals(43, sm.breakeven)
        assertEquals(9592.28, sm.net, 0.01)
        assertTrue(e.markers.any { it.kind == MarkerKind.TREND_UP || it.kind == MarkerKind.TREND_DOWN }, "استراتژی ۱ روند می‌سازد")
        assertTrue(b.orders.any { !it.tp1.isNaN() }, "استراتژی ۱ فیبو دارد")
    }

    // ── بدون روند، ناحیه روی همهٔ کندل‌ها ──────────────────────────────────────────
    @Test
    fun `strategy 2 has no trend and registers zones on both green and red structure candles`() {
        val candles = sample()
        val (e, _) = run(s2(), candles)
        assertEquals(0, e.trend, "روند وجود ندارد")
        assertTrue(e.pins.isEmpty(), "کندل مهم/پین ثبت نمی‌شود")
        assertTrue(e.HH.isNaN() && e.HL.isNaN() && e.LL.isNaN() && e.LH.isNaN())
        assertTrue(e.markers.none {
            it.kind == MarkerKind.PIN_BU || it.kind == MarkerKind.PIN_BE || it.kind == MarkerKind.TREND_UP ||
                it.kind == MarkerKind.TREND_DOWN || it.kind == MarkerKind.STRUCT
        }, "برچسب روند/پین/ساختار نباید باشد")

        // رنگ هر کندل ساختار (۱ ساعته) را مستقل از موتور از روی ۱دقیقه‌ای‌ها می‌سازیم؛ کلید = ایندکس اولین ۱دقیقه‌ایِ ساعت
        val bullish = HashMap<Int, Boolean>()
        for ((_, cs) in candles.groupBy { Tf.bucket(Tf.H1, it.t) }) bullish[cs.first().bi] = cs.last().c > cs.first().o
        assertTrue(e.zones.isNotEmpty())
        for (z in e.zones) {
            val bull = bullish[z.createdBi]
            assertTrue(bull != null, "ناحیه فقط روی کندل بستهٔ ساختار ثبت می‌شود (bi=${z.createdBi})")
            assertEquals(bull, z.dir == 1, "کندل سبز ⇒ ناحیهٔ خرید، کندل قرمز ⇒ ناحیهٔ فروش (bi=${z.createdBi})")
        }
        assertTrue(e.zones.any { it.dir == 1 } && e.zones.any { it.dir == -1 }, "هم ناحیهٔ خرید هم ناحیهٔ فروش")
        // همان ضابطهٔ الگوریتم: ناحیهٔ خرید زیر کلوز کندل، ناحیهٔ فروش بالای کلوز؛ حداکثر ۲ ناحیه در هر کندل (قبول‌شده یا ردشده)
        val perCandle = e.zones.groupBy { it.createdBi }
        assertTrue(perCandle.values.all { it.size <= 2 }, "حداکثر دو ناحیه (قبول‌شده یا ردشده) در هر کندل ساختار")
        assertTrue(e.setups.all { it.priceExpiryRef.isNaN() }, "انقضای «عبور از HH/LL» بدون روند معنا ندارد")
    }

    @Test
    fun `strategy 1 zones still follow the trend only`() {
        val candles = sample()
        val (e, _) = run(Settings(), candles)
        val bullish = HashMap<Int, Boolean>()
        for ((_, cs) in candles.groupBy { Tf.bucket(Tf.H1, it.t) }) bullish[cs.first().bi] = cs.last().c > cs.first().o
        // هر ناحیه هم‌رنگ کندل ساختارش است (روند صعودی ⇒ فقط کندل سبز، نزولی ⇒ فقط قرمز)
        for (z in e.zones) assertEquals(bullish[z.createdBi], z.dir == 1)
        val (e2, _) = run(s2(), candles)
        assertTrue(e2.zones.size > e.zones.size, "استراتژی ۲ ناحیه‌های بیشتری ثبت می‌کند (روی همهٔ کندل‌ها)")
    }

    // ── حد سود فقط واحد ─────────────────────────────────────────────────────────
    @Test
    fun `strategy 2 take profit is exactly entry plus or minus the units with no TP1 and TP2`() {
        for (units in listOf(10.0, 17.5)) {
            val (_, b) = run(s2(units), sample())
            assertTrue(b.orders.size > 20, "نمونه باید سفارش بسازد")
            for (o in b.orders) {
                assertTrue(o.tp1.isNaN() && o.tp2.isNaN(), "TP1/TP2 ندارد")
                val d = if (o.dir == 1) o.tpX - o.price else o.price - o.tpX
                assertEquals(units, d, 1e-9, "فاصلهٔ حد سود = $units واحد")
            }
            var tpxTrades = 0
            for (t in b.trades) {
                assertTrue(t.fills.none { it.kind == "TP1" || it.kind == "TP2" })
                val tpx = t.fills.firstOrNull { it.kind == "TPX" } ?: continue
                tpxTrades++
                assertEquals(t.tpX, tpx.price, 1e-9)
                assertEquals(t.qty, tpx.qty, 1e-9, "کل حجم یک‌جا روی حد سود بسته می‌شود")
            }
            assertTrue(tpxTrades > 0, "حداقل یک معامله به حد سود برسد (units=$units)")
        }
    }

    // ── یک سفارش از هر جهت ─────────────────────────────────────────────────────
    @Test
    fun `strategy 2 never holds two orders of the same direction but does hold a buy and a sell together`() {
        var maxBuy = 0; var maxSell = 0; var both = 0
        val (_, b) = run(s2(), sample()) { _, br ->
            val buys = br.pendingOrders.count { it.dir == 1 } + br.openTrades.count { it.dir == 1 }
            val sells = br.pendingOrders.count { it.dir == -1 } + br.openTrades.count { it.dir == -1 }
            maxBuy = max(maxBuy, buys); maxSell = max(maxSell, sells)
            if (buys > 0 && sells > 0) both++
        }
        assertTrue(b.orders.any { it.dir == 1 } && b.orders.any { it.dir == -1 }, "هر دو جهت سفارش دارند")
        assertEquals(1, maxBuy, "از خرید حداکثر یکی (معلق یا باز)")
        assertEquals(1, maxSell, "از فروش حداکثر یکی (معلق یا باز)")
        assertTrue(both > 0, "خرید و فروش هم‌زمان دیده شود (کندل‌های هم‌زمان: $both)")
    }

    private fun setup(id: Long, dir: Int, hvH: Double, hvL: Double, sl: Double, tpx: Double) =
        Setup(id, dir, 5, 400.0, 100.0, id.toInt(), 0).apply {
            this.hvH = hvH; this.hvL = hvL; this.sl = sl; this.tp1 = Double.NaN; this.tp2 = Double.NaN; this.tpx = tpx
        }

    @Test
    fun `broker rule - one order per direction in strategy 2 and one pending order overall in strategy 1`() {
        val t0 = 1_700_001_000_000L
        fun bar(bi: Int, o: Double, h: Double, l: Double, c: Double) = Candle(bi, t0 + (bi - 10) * 60_000L, o, h, l, c, 100.0)
        // استراتژی ۲
        val b = PaperBroker(s2())
        val buy1 = setup(1, 1, 210.0, 208.0, 207.98, 220.02)
        val buy2 = setup(2, 1, 205.0, 203.0, 202.98, 215.02)
        val sell1 = setup(3, -1, 224.0, 222.0, 224.02, 211.98)
        assertTrue(b.registerLimit(buy1, bar(10, 211.0, 212.0, 210.5, 211.0)))
        assertTrue(b.registerLimit(sell1, bar(10, 211.0, 212.0, 210.5, 211.0)), "فروش هم‌زمان با خرید مجاز است")
        assertFalse(b.registerLimit(buy2, bar(10, 211.0, 212.0, 210.5, 211.0)), "دو خرید معلق مجاز نیست")
        assertTrue(b.lastError!!.contains("هر جهت"))
        // خرید پر می‌شود (معامله باز) ⇒ خرید دومِ جدید همچنان رد می‌شود؛ فروش معلق سر جایش است
        b.onBar(bar(11, 211.0, 211.5, 209.5, 210.0))
        assertEquals(1, b.openTrades.size); assertEquals(1, b.pendingOrders.size)
        assertTrue(b.hasActiveOrder(1) && b.hasActiveOrder(-1))
        assertFalse(b.registerLimit(buy2, bar(12, 211.0, 212.0, 210.5, 211.0)), "با یک خرید باز، خرید دوم مجاز نیست")
        // خرید به حد سود می‌رسد ⇒ جهت خرید آزاد می‌شود
        b.onBar(bar(12, 210.5, 221.0, 210.4, 220.5))
        assertEquals(0, b.openTrades.size)
        assertFalse(b.hasActiveOrder(1)); assertTrue(b.hasActiveOrder(-1))
        assertTrue(b.registerLimit(buy2, bar(13, 205.5, 206.0, 204.0, 205.0)), "بعد از بسته شدن خرید اول، خرید جدید مجاز است")

        // استراتژی ۱ (سند): یک سفارش معلق در کل، بدون توجه به جهت
        val b1 = PaperBroker(Settings())
        assertTrue(b1.registerLimit(buy1, bar(10, 211.0, 212.0, 210.5, 211.0)))
        assertFalse(b1.registerLimit(sell1, bar(10, 211.0, 212.0, 210.5, 211.0)), "در استراتژی ۱ سفارش معلق دوم (حتی خلاف جهت) مجاز نیست")
    }

    // ── پایداری و ذخیره/بازیابی ──────────────────────────────────────────────────
    @Test
    fun `strategy 2 results are reproducible`() {
        val candles = sample()
        fun sig(b: PaperBroker) = b.trades.joinToString("|") { "${it.id},${it.dir},${it.entryBi},${it.exitBi},${"%.4f".format(it.pnl())}" }
        assertEquals(sig(run(s2(), candles).second), sig(run(s2(), candles).second))
    }

    @Test
    fun `strategy and units survive the round trip and old files fall back to strategy 1`() {
        val candles = sample().subList(0, 3000)
        val cfg = s2(12.5)
        val (e, b) = run(cfg, candles)
        val json = Store.save(cfg, e, b, candles)
        val ld = Store.load(json)
        assertEquals(Strategy.NO_TREND, ld.cfg.strategy)
        assertEquals(12.5, ld.cfg.tpUnits, 1e-9)

        @Suppress("UNCHECKED_CAST")
        val root = (Json.parse(json) as Map<String, Any?>).toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val st = (root["settings"] as Map<String, Any?>).toMutableMap()
        st.remove("strategy"); st.remove("tpUnits")
        root["settings"] = st
        val old = Store.load(Json.write(root))
        assertEquals(Strategy.DOC, old.cfg.strategy, "فایل قدیمی ⇒ استراتژی ۱")
        assertEquals(10.0, old.cfg.tpUnits, 1e-9)
    }
}
