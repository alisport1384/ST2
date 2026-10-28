package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

// ═══════════════════════════════════════════════════════════════════════════════
//  آزمون میز جفت‌ارز (۱٫۵٫۰۴)
//
//  قاعده‌هایی که این آزمون قفل می‌کند:
//   ۱) ورود فقط با بسته‌شدن بیرون از سقف/کف N کندل و فقط در جهت همان جفت.
//   ۲) یک پوزیشن باز در هر جفت (بدون هرم‌سازی).
//   ۳) خروج روی هدف = دقیقاً targetR منهای هزینه و به‌علاوهٔ سواپ.
//   ۴) گیت هزینه: اگر هزینه از ۲۰٪ R بیشتر شود، معامله ساخته نمی‌شود.
//   ۵) سواپ: چهارشنبه ×۳، شنبه/یکشنبه صفر (همان قاعدهٔ ۱٫۵٫۰۳).
//   ۶) ریسک دلاری هر معامله = riskPct از موجودی.
// ═══════════════════════════════════════════════════════════════════════════════

class FxDeskTest {

    private val h = 3_600_000L

    /** کندل ساده: زمان، باز، بیشینه، کمینه، بسته. */
    private fun bar(t: Long, o: Double, hi: Double, lo: Double, c: Double, v: Double = 1.0) =
        Candle(0, t, o, hi, lo, c, v)

    /** سری آرام با ATR مشخص: دامنهٔ هر کندل = range. */
    private fun flat(n: Int, px: Double, range: Double, t0: Long = 1_700_000_000_000L): List<Candle> =
        (0 until n).map { i ->
            bar(t0 + i * h, px, px + range / 2, px - range / 2, px)
        }

    private fun profile(mode: Int = -1, lookback: Int = 12, atrMult: Double = 1.0,
                        targetR: Double = 4.0, partial: Boolean = false) =
        FxProfile("EURUSD", "یورو/دلار", 5, h, mode, lookback, atrMult, targetR, partial,
            true, 0.0, 0, 0.0, 0.0, "آزمون")

    private fun desk(cost: FxCost = FxCost(1.0, 0.0, 0.0, 1.0)): FxDesk {
        val d = FxDesk(capital = 100_000.0, riskPct = 0.5)
        d.feed = { cost }
        return d
    }

    @Test
    fun `شکست سقف در حالت fade یک فروش می‌سازد`() {
        val p = profile(mode = -1)
        val d = desk()
        val hist = flat(40, 1.1000, 0.0020).toMutableList()
        val sig = bar(hist.last().t + h, 1.1000, 1.1040, 1.0990, 1.1040)   // بسته بالای سقف
        hist.add(sig)
        d.onBar(p, hist, sig)
        val t = d.positions["EURUSD"]
        assertTrue(t != null, "باید پوزیشن ساخته شود")
        assertEquals(-1, t!!.dir, "حالت fade روی شکست سقف باید فروش باشد")
    }

    @Test
    fun `شکست سقف در حالت trend یک خرید می‌سازد`() {
        val p = profile(mode = +1)
        val d = desk()
        val hist = flat(40, 1.1000, 0.0020).toMutableList()
        val sig = bar(hist.last().t + h, 1.1000, 1.1040, 1.0990, 1.1040)
        hist.add(sig)
        d.onBar(p, hist, sig)
        assertEquals(1, d.positions["EURUSD"]!!.dir)
    }

    @Test
    fun `بدون شکست هیچ پوزیشنی ساخته نمی‌شود`() {
        val p = profile()
        val d = desk()
        val hist = flat(40, 1.1000, 0.0020).toMutableList()
        val inside = bar(hist.last().t + h, 1.1000, 1.1005, 1.0995, 1.1002)
        hist.add(inside)
        d.onBar(p, hist, inside)
        assertTrue(d.positions.isEmpty())
    }

    @Test
    fun `خروج روی هدف برابر targetR منهای هزینه‌هاست`() {
        val p = profile(mode = +1, atrMult = 1.0, targetR = 4.0)
        val d = desk(FxCost(spreadPoints = 0.0, swapLongPerLot = 0.0, swapShortPerLot = 0.0, usdPerQuote = 1.0))
        val hist = flat(40, 1.1000, 0.0020).toMutableList()
        val sig = bar(hist.last().t + h, 1.1000, 1.1040, 1.0990, 1.1030)
        hist.add(sig)
        d.onBar(p, hist, sig)
        val t = d.positions["EURUSD"]!!
        val tp = t.tp
        val exit = bar(sig.t + h, 1.1030, tp + 1e-9, 1.1029, tp)
        d.onBar(p, hist + exit, exit)
        assertTrue(d.closed.size == 1, "معامله باید بسته شود")
        val r = d.closed[0].realizedR()
        // هدف ۴R است؛ فقط کارمزد (۵ دلار در لات ⇒ ~۰٫۰۰۵R) از آن کم می‌شود
        assertTrue(r in 3.90..4.01, "R باید نزدیک ۴ باشد، بود: $r")
    }

    @Test
    fun `گیت هزینه معاملهٔ بی‌ارزش را رد می‌کند`() {
        val p = profile(mode = +1, atrMult = 0.01, targetR = 4.0)   // ریسک بسیار کوچک
        val d = desk(FxCost(spreadPoints = 20.0, swapLongPerLot = 0.0, swapShortPerLot = 0.0, usdPerQuote = 1.0))
        val hist = flat(40, 1.1000, 0.0020).toMutableList()
        val sig = bar(hist.last().t + h, 1.1000, 1.1040, 1.0990, 1.1030)
        hist.add(sig)
        d.onBar(p, hist, sig)
        assertTrue(d.positions.isEmpty(), "هزینه > ۲۰٪ R ⇒ باید رد شود")
    }

    @Test
    fun `یک پوزیشن در هر جفت`() {
        val p = profile(mode = +1)
        val d = desk()
        val hist = flat(40, 1.1000, 0.0020).toMutableList()
        val sig1 = bar(hist.last().t + h, 1.1000, 1.1040, 1.0990, 1.1030)
        d.onBar(p, hist + sig1, sig1)
        assertEquals(1, d.positions.size)
        val sig2 = bar(sig1.t + h, 1.1030, 1.1070, 1.1020, 1.1060)
        d.onBar(p, hist + sig1 + sig2, sig2)
        assertEquals(1, d.positions.size, "بدون هرم‌سازی؛ پوزیشن دوم نباید باز شود")
        assertTrue(d.positions["EURUSD"]!!.entryT == sig1.t)
    }

    @Test
    fun `ریسک دلاری هر معامله برابر درصد تعیین‌شده است`() {
        val p = profile(mode = +1, atrMult = 1.0)
        val d = desk()
        val hist = flat(40, 1.1000, 0.0020).toMutableList()
        val sig = bar(hist.last().t + h, 1.1000, 1.1040, 1.0990, 1.1030)
        hist.add(sig)
        d.onBar(p, hist, sig)
        val t = d.positions["EURUSD"]!!
        assertEquals(500.0, t.riskUsd, 0.01)          // ۰٫۵٪ از ۱۰۰٬۰۰۰
        val px = Math.abs(t.entryPx - t.sl0)
        val notionalRisk = t.qtyLots * 100_000.0 * px * 1.0
        assertEquals(500.0, notionalRisk, 0.5, "حجم باید طوری باشد که ضرر روی SL = ۵۰۰ دلار شود")
    }

    @Test
    fun `سواپ چهارشنبه سه‌برابر و آخر هفته صفر است`() {
        // ۱۹۷۰-۰۱-۰۱ پنج‌شنبه بود ⇒ روز ۴ = دوشنبه، ۶ = چهارشنبه، ۲ = شنبه، ۳ = یکشنبه.
        // ساعت ۲۲:۰۰ را می‌گیریم تا از رول‌اورِ ۲۱:۰۰ همان روز گذشته باشیم.
        val day = 86_400_000L
        val at22 = 22 * 3_600_000L
        val mon = FxMath.rollDay(4L * day + at22, 21)
        val wed = FxMath.rollDay(6L * day + at22, 21)
        val sat = FxMath.rollDay(2L * day + at22, 21)
        val sun = FxMath.rollDay(3L * day + at22, 21)

        assertFalse(FxMath.isWeekendRoll(mon), "دوشنبه رول دارد")
        assertTrue(FxMath.isTripleRoll(mon, 3).not(), "دوشنبه سه‌برابر نیست")
        assertTrue(FxMath.isTripleRoll(wed, 3), "چهارشنبه سه‌برابر است")
        assertFalse(FxMath.isWeekendRoll(wed), "چهارشنبه رول دارد")
        assertTrue(FxMath.isWeekendRoll(sat), "شنبه رول ندارد")
        assertTrue(FxMath.isWeekendRoll(sun), "یکشنبه رول ندارد")
    }

    @Test
    fun `سواپ در سود و زیان خالص لحاظ می‌شود`() {
        val p = profile(mode = +1, atrMult = 1.0, targetR = 50.0)   // هدف دور تا معامله باز بماند
        val d = desk(FxCost(0.0, swapLongPerLot = -10.0, swapShortPerLot = +10.0, usdPerQuote = 1.0))
        val hist = flat(40, 1.1000, 0.0020).toMutableList()
        val sig = bar(hist.last().t + h, 1.1000, 1.1040, 1.0990, 1.1030)
        hist.add(sig)
        d.onBar(p, hist, sig)
        val t = d.positions["EURUSD"]!!
        var last = sig
        // ۵ روز نگه‌داری: دوشنبه تا جمعه ⇒ ۴ شب (= ۱+۳+۱+۱ با یک چهارشنبه)
        for (i in 1..5 * 24) {
            last = bar(sig.t + i * h, 1.1030, 1.1035, 1.1028, 1.1032)
            d.onBar(p, hist + last, last)
            if (!t.open) break
        }
        assertTrue(t.swapNights >= 3, "باید چند شب سواپ گرفته باشد، بود: ${t.swapNights}")
        assertTrue(t.swapUsd < 0.0, "با نرخ منفیِ خرید، سواپ باید هزینه باشد")
        assertTrue(t.netUsd() < t.grossUsd, "خالص باید از ناخالص کمتر باشد")
    }

    @Test
    fun `پروفایل‌ها همه سالم و بدون تکرارند`() {
        val ids = FxPairs.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "شناسهٔ تکراری")
        assertEquals(12, ids.size)
        for (p in FxPairs.ALL) {
            assertTrue(p.lookback in 6..200, "${p.id}: lookback نامعتبر")
            assertTrue(p.atrMult in 0.2..5.0, "${p.id}: atrMult نامعتبر")
            assertTrue(p.targetR in 1.0..20.0, "${p.id}: targetR نامعتبر")
            assertTrue(p.digits == 3 || p.digits == 5, "${p.id}: digits")
        }
        // پیش‌فرض = جفت‌هایی که هم ۲۰ سال و هم ۳ سال آخر مثبت‌اند
        assertTrue(FxPairs.ENABLED_BY_DEFAULT.size >= 3, "پیش‌فرض باید دست‌کم سه جفت باشد")
        for (p in FxPairs.ENABLED_BY_DEFAULT) {
            assertTrue(p.avgR20 > 0.0, "${p.id}: جفتِ پیش‌فرض باید ۲۰ سالهٔ مثبت داشته باشد")
            assertTrue(p.rec3AvgR > 0.0, "${p.id}: جفتِ پیش‌فرض باید ۳ سال آخر مثبت داشته باشد")
        }
    }

    // ── ۱٫۵٫۰۶: آزمون ۲۰ سالهٔ هر میز ─────────────────────────────────────────

    @Test
    fun `پارس CSV بستهٔ ریلیز درست کار می‌کند`() {
        val csv = "t,o,h,l,c,v\n" +
            "1600000000,1.1000,1.1050,1.0990,1.1040,0\n" +
            "1600086400,1.1040,1.1060,1.1010,1.1020,0\n"
        val bars = FxBacktest.parseCsv(csv)
        assertEquals(2, bars.size, "سرستون باید رد شود")
        assertEquals(1600000000L * 1000L, bars[0].t, "ثانیه باید به میلی‌ثانیه تبدیل شود")
        assertEquals(1.1050, bars[0].h, 1e-9)
        assertEquals(1.0990, bars[0].l, 1e-9)
        assertEquals(1.1020, bars[1].c, 1e-9)
    }

    @Test
    fun `بیرون‌کشیدن یک جفت از ZIP همان فایل درست است`() {
        val csv = "t,o,h,l,c,v\n1600000000,1.1,1.2,1.0,1.15,0\n"
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("EURUSD.csv")); z.write(csv.toByteArray()); z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("GBPUSD.csv")); z.write(csv.toByteArray()); z.closeEntry()
        }
        val got = FxBacktest.pairCsvFromZip(bos.toByteArray(), "GBPUSD")
        assertTrue(got != null && got.contains("1.15"), "فایل GBPUSD باید بیرون کشیده شود")
        assertNull(FxBacktest.pairCsvFromZip(bos.toByteArray(), "USDCHF"), "جفتِ نبوده باید null بدهد")
    }

    @Test
    fun `بک‌تست یک جفت حسابداری سازگار می‌دهد`() {
        // سری مصنوعی با روند صعودی و نوسان کافی برای ساخت معامله
        val bars = ArrayList<Candle>()
        var px = 100.0
        var t = 1_500_000_000_000L
        val rnd = java.util.Random(7)
        for (i in 0 until 600) {
            val drift = 0.02
            val o = px
            val c = px + drift + rnd.nextGaussian() * 0.6
            val h = Math.max(o, c) + Math.abs(rnd.nextGaussian()) * 0.3
            val l = Math.min(o, c) - Math.abs(rnd.nextGaussian()) * 0.3
            bars.add(Candle(i, t, o, h, l, c, 0.0))
            px = c; t += 86_400_000L
        }
        val p = FxProfile("TEST", "تست", 3, 86_400L, 1, 12, 1.5, 4.0, false, false,
            0.0, 0, 0.0, 0.0, "مصنوعی")
        val cost = FxCost(1.0, 0.0, 0.0, 1.0)
        val r = FxBacktest.run(p, bars, cost, capital = 10_000.0, riskPct = 0.25, commissionPerLot = 5.0)
        assertTrue(r.trades > 0, "باید معامله بسازد")
        assertEquals(r.bars, 600)
        // منحنی سرمایه باید با سرمایهٔ اولیه شروع و با سرمایه+سود تمام شود
        assertEquals(10_000.0, r.curve.first().second, 1e-6)
        assertEquals(10_000.0 + r.netUsd, r.curve.last().second, 1e-6)
        // میانگین R باید با مجموع R و تعداد معامله بخواند
        assertEquals(r.sumR / r.trades, r.avgR, 1e-9)
        assertTrue(r.yearCount >= 1)
        assertTrue(r.maxDdUsd >= 0.0)
    }

    @Test
    fun `تجمیع ساعتی به روزانه فقط باکت بسته می‌دهد`() {
        val base = 1_600_000_000_000L - (1_600_000_000_000L % 86_400_000L)
        val bars = ArrayList<Candle>()
        for (i in 0 until 24) bars.add(Candle(i, base + i * 3_600_000L, 1.0, 1.1, 0.9, 1.0 + i * 0.001, 0.0))
        // یک کندل نیمه‌کارهٔ روز بعد
        bars.add(Candle(24, base + 86_400_000L, 1.0, 1.2, 0.9, 1.1, 0.0))
        val agg = FxBacktest.aggregate(bars, 86_400L)
        assertEquals(1, agg.size, "روز نیمه‌کاره نباید بیاید")
        assertEquals(1.1, agg[0].h, 1e-9)
        assertEquals(0.9, agg[0].l, 1e-9)
    }
}
