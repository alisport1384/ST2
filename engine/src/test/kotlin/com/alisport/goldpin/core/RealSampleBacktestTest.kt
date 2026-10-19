package com.alisport.goldpin.core

import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * بک‌تست روی دادهٔ نمونهٔ واقعی همراه اپ (XAUUSD ‏M1):
 * • طبق §4-9 سند، هر معامله‌ای که به حد سود نهایی رسیده باید پله‌های TP1/TP2 معتبرش را
 *   در گزارش سفارش‌ها داشته باشد (پیش‌تر در کندل‌های پهن/گپ‌دار گم می‌شدند).
 * • طبق ساعت استاندارد بازار، هیچ پرشدنی در تعطیلات شنبه و یکشنبه نباید رخ داده باشد
 *   و هیچ کندل ساختاری نباید روی زمان بسته‌بودن بازار نشسته باشد.
 */
class RealSampleBacktestTest {

    /** بارگذاری نمونهٔ ۴۰٬۰۰۰ کندلی و اجرای کامل موتور + کارگزار کاغذی. */
    private fun runSample(onBar: ((Candle, PaperBroker) -> Unit)? = null): Pair<Engine, PaperBroker> {
        val asset = listOf(
            "app/src/main/assets/sample_xauusd_m1.csv.gz",
            "../app/src/main/assets/sample_xauusd_m1.csv.gz",
            "/home/user/ST2/app/src/main/assets/sample_xauusd_m1.csv.gz"
        ).map(::File).firstOrNull { it.exists() }
        assumeTrue(asset != null, "فایل نمونهٔ داده در دسترس نیست")

        val candles = ArrayList<Candle>()
        GZIPInputStream(asset!!.inputStream()).bufferedReader().useLines { lines ->
            var bi = 0
            for (ln in lines) {
                val p = ln.split(',')
                if (p.size < 6) continue
                val t = p[0].trim().toLongOrNull() ?: continue
                candles.add(
                    Candle(
                        bi++, t,
                        p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(),
                        p[5].toDouble()
                    )
                )
            }
        }
        assertTrue(candles.size > 10_000, "دادهٔ نمونه باید بارگذاری شود")

        val mode = TF_MODES.first { it.idx == 1 }
        val cfg = Settings().apply { tfS = mode.s; tfM = mode.m; tf1 = mode.t1; tf2 = mode.t2 }
        val eng = Engine(cfg)
        val broker = PaperBroker(cfg)
        eng.broker = broker
        broker.engine = eng
        eng.barCommitHook = { cd ->
            broker.onBar(cd, live = false)
            onBar?.invoke(cd, broker)
        }

        var upto = 0
        val step = 20_000
        while (upto < candles.size) {
            val next = minOf(upto + step, candles.size)
            eng.feed(candles.subList(0, next), lastIsClosed = false)
            upto = next
        }
        eng.feed(candles, lastIsClosed = true)
        return eng to broker
    }

    @Test
    fun `real sample backtest - final TP trades keep TP1 TP2 legs and no weekend fills`() {
        val (eng, broker) = runSample()

        val closed = broker.trades.filter { !it.open }
        val finalTp = closed.filter { tr -> tr.fills.any { it.kind == "TPX" } }
        println(
            "بک‌تست نمونه: معاملات بسته=${closed.size} " +
                "رسیده به حد سود نهایی=${finalTp.size} موجودی=${"%.2f".format(broker.balance)}"
        )

        // هیچ پرشدنی در بازار بسته (شنبه و یکشنبه تا بازگشایی ۲۲:۰۰ UTC) نباید باشد
        val weekendFills = broker.trades.flatMap { it.fills }.filter { MarketHours.isClosed(it.t) }
        assertTrue(weekendFills.isEmpty(), "پرشدن در تعطیلات آخر هفته نباید رخ داده باشد: $weekendFills")
        assertTrue(eng.structBars.none { MarketHours.isClosed(it.t) }, "کندل ساختار روی زمان تعطیل بازار نباید باشد")

        // §18-26/§18-27 سند: هیچ ورودی خارج از پنجرهٔ سفارش‌گیری (۰۱:۳۰ تا ۲۰:۰۰ UTC) و
        // هیچ پرشدنی بعد از رأس ۲۰:۰۰ نباید باشد؛ معاملات رأس ۲۰:۰۰ با «پایان زمان» بسته می‌شوند.
        val allFills = broker.trades.flatMap { it.fills }
        val outsideEntries = allFills.filter { it.kind == "ENTRY" && !MarketHours.isOrderWindow(it.t) }
        assertTrue(outsideEntries.isEmpty(), "ورود خارج از پنجرهٔ سفارش‌گیری نباید رخ داده باشد: $outsideEntries")
        val afterEnd = allFills.filter { MarketHours.minuteOfDayUtc(it.t) > MarketHours.ORDER_END_MIN_UTC }
        assertTrue(afterEnd.isEmpty(), "پرشدن بعد از رأس ۲۰:۰۰ UTC نباید رخ داده باشد: $afterEnd")
        println("بسته‌شدن با پایان زمان در نمونه: ${closed.count { it.reason.contains("پایان زمان") }} معامله")

        // هر معاملهٔ رسیده به حد سود نهایی: پله‌های معتبر TP1/TP2 باید در گزارش باشند (§4-9)
        for (tr in finalTp) {
            val kinds = tr.fills.map { it.kind }
            if (!tr.tp1.isNaN()) assertTrue("TP1" in kinds, "معامله #${tr.id} به حد نهایی رسیده ولی TP1 در گزارش نیست: $kinds")
            if (!tr.tp2.isNaN()) assertTrue("TP2" in kinds, "معامله #${tr.id} به حد نهایی رسیده ولی TP2 در گزارش نیست: $kinds")
            // مجموع حجم پله‌های خروجی دقیقاً حجم معامله است (نه بیشتر، نه کمتر)
            val exitQty = tr.fills.filter { it.kind != "ENTRY" }.sumOf { it.qty }
            assertTrue(kotlin.math.abs(exitQty - tr.qty) < 1e-9, "معامله #${tr.id}: حجم خروجی $exitQty با حجم معامله ${tr.qty} نمی‌خواند")
        }
    }
    /**
     * قواعد ساختاری سند که روی دادهٔ واقعی هم باید بی‌قید و شرط برقرار بمانند:
     *  • §۴-۸ / §۱۸-۱۲و۱۳: در هر لحظه حداکثر یک سفارش (معلق یا باز) — استراتژی ۱.
     *  • §۴-۸: «پر شدن در همان کندل مسلح‌شدن مجاز نیست».
     *  • §۱۸-۳۱: فاصلهٔ حد ضرر از سطح سفارش هرگز از N بیشتر نیست.
     *  • §۴-۸: سطح سفارش/SL/TP در جهت درست و با ترتیب درست (۰٫۳۸۲ < ۰٫۵ < ۱٫۲۷۲).
     */
    @Test
    fun `real sample backtest keeps order and level invariants from the doc`() {
        val maxActive = intArrayOf(0)
        val (eng, broker) = runSample { _, b ->
            val n = b.pendingOrders.size + b.openTrades.size
            if (n > maxActive[0]) maxActive[0] = n
        }

        assertTrue(broker.orders.isNotEmpty(), "در نمونه باید حداقل یک سفارش ثبت شود")
        assertTrue(maxActive[0] <= 1, "در هر لحظه حداکثر یک سفارش مجاز است، ولی ${maxActive[0]} دیده شد (§4-8/§18-13)")

        for (o in broker.orders) {
            // پر شدن در کندل مسلح‌شدن ممنوع
            if (o.filledBi != null) {
                assertTrue(o.filledBi != o.placedBi, "سفارش #${o.id} در همان کندل مسلح‌شدن پر شده است (§4-8)")
            }
            // SL>N هرگز نباید ثبت شود
            assertTrue(kotlin.math.abs(o.price - o.sl) <= eng.cfg.maxSlUnits + 1e-9,
                "سفارش #${o.id}: فاصلهٔ SL (${"%.2f".format(kotlin.math.abs(o.price - o.sl))}) از N=${eng.cfg.maxSlUnits} بیشتر است (§18-31)")
            // آخرین پلهٔ فیبو (اگر فیبو معتبر نباشد NaN است ⇒ فقط حد سود ۱۰ واحدی)
            val lastFib = if (!o.tp2.isNaN()) o.tp2 else o.tp1
            if (o.dir == 1) {
                assertTrue(o.sl < o.price, "خرید: حد ضرر باید زیر سطح سفارش باشد (#${o.id})")
                if (!o.tp1.isNaN()) assertTrue(o.tp1 > o.price, "خرید: TP1 باید بالای ورود باشد (#${o.id})")
                if (!o.tp2.isNaN()) assertTrue(o.tp2 > o.tp1, "خرید: TP2 باید بالای TP1 باشد (#${o.id})")
                if (lastFib.isNaN()) {
                    // §۴-۸: فیبوی نامعتبر یا فالبک ⇒ فقط حد سود نهایی = ورود + ۱۰
                    assertTrue(kotlin.math.abs(o.tpX - o.price - eng.cfg.minTPunits) < 1e-6,
                        "خرید بدون فیبو: TPX باید ورود+${eng.cfg.minTPunits} باشد (#${o.id}) → ${o.tpX - o.price}")
                } else {
                    assertTrue(o.tpX > lastFib, "خرید: حد سود نهایی باید آخرین پله باشد (#${o.id})")
                }
            } else {
                assertTrue(o.sl > o.price, "فروش: حد ضرر باید بالای سطح سفارش باشد (#${o.id})")
                if (!o.tp1.isNaN()) assertTrue(o.tp1 < o.price, "فروش: TP1 باید زیر ورود باشد (#${o.id})")
                if (!o.tp2.isNaN()) assertTrue(o.tp2 < o.tp1, "فروش: TP2 باید زیر TP1 باشد (#${o.id})")
                if (lastFib.isNaN()) {
                    assertTrue(kotlin.math.abs(o.price - o.tpX - eng.cfg.minTPunits) < 1e-6,
                        "فروش بدون فیبو: TPX باید ورود−${eng.cfg.minTPunits} باشد (#${o.id}) → ${o.price - o.tpX}")
                } else {
                    assertTrue(o.tpX < lastFib, "فروش: حد سود نهایی باید آخرین پله باشد (#${o.id})")
                }
            }
        }

        // هر معامله‌ای که بسته شده، پله‌های خروجش دقیقاً روی سطح‌های ثبت‌شده است (بدون اسلیپیج روی TP)
        for (tr in broker.trades.filter { !it.open }) {
            for (f in tr.fills.filter { it.kind != "ENTRY" }) {
                val level = when (f.kind) {
                    "TP1" -> tr.tp1
                    "TP2" -> tr.tp2
                    "TPX" -> tr.tpX
                    else -> f.price
                }
                if (!level.isNaN()) {
                    assertTrue(kotlin.math.abs(f.price - level) < 1e-9,
                        "معامله #${tr.id}: پلهٔ ${f.kind} روی ${f.price} بسته شد ولی سطحش $level است")
                }
            }
        }
        println("قواعد ساختاری سند روی نمونه برقرار است: سفارش=${broker.orders.size} بیشینهٔ هم‌زمانی=${maxActive[0]}")
    }
}
