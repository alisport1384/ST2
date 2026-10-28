package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

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
            true, 0.0, 0, "آزمون")

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
        assertEquals(3, FxPairs.ENABLED_BY_DEFAULT.size, "پیش‌فرض فقط سه جفتِ بازماندهٔ داوری")
    }
}
