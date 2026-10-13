package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * سند §18 (توضیحات تکمیلی و استثناها) — سناریوی عددی.
 *
 * همهٔ تایم‌فریم‌ها ۱ دقیقه‌اند تا هر کندل، کندل ساختار باشد.
 */
class Doc18Test {

    private fun c(bi: Int, o: Double, h: Double, l: Double, cl: Double, v: Double = 100.0) =
        Candle(bi, 1_700_000_000_000L + bi * 60_000L, o, h, l, cl, v)

    private fun m1() = Settings().also { it.tfS = Tf.M1; it.tfM = Tf.M1; it.tf1 = Tf.M1; it.tf2 = Tf.M1 }

    /** کندل ساختار با شروع کندل بعدی بسته می‌شود؛ یک کندل دنباله (بدون جفت) اضافه می‌شود تا کندل n-1 بسته شود. */
    private fun runN(e: Engine, bars: List<Candle>, n: Int) {
        val sub = bars.subList(0, n)
        val last = sub.last()
        val tail = Candle(last.bi + 1, last.t + 60_000L, last.c, last.c, last.c, last.c, 1.0)
        e.runAll(sub + tail)
    }

    /**
     * §18-6 / §18-3: تا تعیین تکلیف IC (ابطال یا Ready) هیچ کاندید دیگری پذیرفته نمی‌شود.
     *
     * کندل‌ها (o,h,l,c):
     *  0: 100,105, 98,103  صعودی
     *  1: 104,106, 99,100  نزولی  ⇒ جفت (0,1) = کاندید بالاترین‌ها؛ IC=کندل۱ (سقف ۱۰۶)، سطح تایید=۹۹، سطح ابطال=max(103,104)=104
     *  2: 100,102, 97,101  صعودی  ⇒ جفت (1,2) جفتِ پایین‌ترین‌هاست، ولی IC قبلی هنوز تعیین تکلیف نشده ⇒ کاندید جدید پذیرفته نمی‌شود
     *  3: 101,104,100,103  صعودی  ⇒ کلوز ۱۰۳ > ۱۰۲ (اگر کاندید کندل۲ پذیرفته شده بود، Ready BU می‌شد — طبق سند نباید بشود)
     *  4: 103,107,102,105  صعودی  ⇒ کلوز ۱۰۵ > ۱۰۴ ⇒ IC بالاترین‌ها باطل
     */
    @Test
    fun `no second IC candidate while the first IC is unresolved`() {
        val e = Engine(m1())
        val bars = listOf(
            c(0, 100.0, 105.0, 98.0, 103.0),
            c(1, 104.0, 106.0, 99.0, 100.0),
            c(2, 100.0, 102.0, 97.0, 101.0),
            c(3, 101.0, 104.0, 100.0, 103.0),
            c(4, 103.0, 107.0, 102.0, 105.0)
        )
        val readyLogs = ArrayList<String>()
        e.logSink = { _, m, d -> if (m.contains("Ready")) readyLogs.add("$m $d") }

        runN(e, bars, 3)
        assertEquals(1, e.pins.size, "فقط یک کاندید IC مجاز است (§18-3/§18-6)")
        assertEquals(-1, e.pins[0].kind, "کاندید فعال باید همان IC بالاترین‌ها باشد")

        runN(e, bars, 4)
        assertFalse(e.buActive, "کاندید دوم نباید پذیرفته و Ready شده باشد")
        assertTrue(e.lastBUpin.isNaN())
        assertTrue(readyLogs.isEmpty(), "هیچ Ready نباید ثبت شود: $readyLogs")

        runN(e, bars, bars.size)
        assertTrue(e.pins.isEmpty(), "IC بالاترین‌ها با کلوز بالای سطح ابطال باید دور ریخته شود")
        assertFalse(e.buActive); assertFalse(e.beActive)
        assertEquals(0, e.trend)
    }

    /**
     * §18-6 (جهت مثبت): بعد از تعیین تکلیف IC، کاندید بعدی پذیرفته می‌شود و توالی رعایت می‌شود.
     *
     *  0: 100,105, 98,103  صعودی
     *  1: 104,106, 99,100  نزولی  ⇒ IC بالاترین‌ها (تایید=۹۹، ابطال=۱۰۴)
     *  2: 100,101, 95, 96  نزولی  ⇒ کلوز ۹۶ < ۹۹ ⇒ ReadyBE ⇒ pinBE=۱۰۶ ؛ حالا فقط کاندید BU مجاز است
     *  3:  96, 98, 94, 97  صعودی  ⇒ جفت (2,3) = کاندید پایین‌ترین‌ها؛ IC=کندل۳ (کف ۹۴)، تایید=۹۸، ابطال=min(96,96)=96
     *  4:  97,100, 96, 99  صعودی  ⇒ کلوز ۹۹ > ۹۸ ⇒ ReadyBU ⇒ pinBU=۹۴ ⇒ تمپ‌باکس ۹۴..۱۰۶
     *  5:  99,108, 98,107  صعودی  ⇒ کلوز ۱۰۷ > ۱۰۶ ⇒ روند صعودی
     */
    @Test
    fun `after the IC is resolved the next opposite candidate is accepted and the trend forms`() {
        val e = Engine(m1())
        val bars = listOf(
            c(0, 100.0, 105.0, 98.0, 103.0),
            c(1, 104.0, 106.0, 99.0, 100.0),
            c(2, 100.0, 101.0, 95.0, 96.0),
            c(3, 96.0, 98.0, 94.0, 97.0),
            c(4, 97.0, 100.0, 96.0, 99.0),
            c(5, 99.0, 108.0, 98.0, 107.0)
        )
        runN(e, bars, 3)
        assertTrue(e.beActive); assertEquals(106.0, e.lastBEpin, 1e-9)
        assertTrue(e.pins.isEmpty())

        runN(e, bars, 4)
        assertEquals(1, e.pins.size); assertEquals(1, e.pins[0].kind)
        assertEquals(94.0, e.pins[0].lo, 1e-9); assertEquals(96.0, e.pins[0].ref, 1e-9)

        runN(e, bars, 5)
        assertTrue(e.buActive); assertEquals(94.0, e.lastBUpin, 1e-9)
        assertEquals(106.0, e.tempBoxTop, 1e-9); assertEquals(94.0, e.tempBoxBot, 1e-9)

        runN(e, bars, bars.size)
        assertEquals(1, e.trend)
    }

    /**
     * برچسب HH/HL روی چارت: در روند صعودی اولین pinBE ⇒ HH و pinBU قبل از آن ⇒ HL (مارکر ساختار).
     * ادامهٔ سناریوی قبل:
     *  6: 107,110,106,109  صعودی
     *  7: 110,112,108,109  نزولی  ⇒ IC بالاترین‌ها: IC=کندل۷ (سقف ۱۱۲)، تایید=۱۰۸، ابطال=max(109,110)=110
     *  8: 108,109,104,105  نزولی  ⇒ کلوز ۱۰۵ < ۱۰۸ ⇒ ReadyBE ⇒ pinBE=۱۱۲ ⇒ HH=۱۱۲ ، HL=۹۴
     */
    @Test
    fun `HH and HL labels are emitted as structure markers`() {
        val e = Engine(m1())
        val bars = listOf(
            c(0, 100.0, 105.0, 98.0, 103.0),
            c(1, 104.0, 106.0, 99.0, 100.0),
            c(2, 100.0, 101.0, 95.0, 96.0),
            c(3, 96.0, 98.0, 94.0, 97.0),
            c(4, 97.0, 100.0, 96.0, 99.0),
            c(5, 99.0, 108.0, 98.0, 107.0),
            c(6, 107.0, 110.0, 106.0, 109.0),
            c(7, 110.0, 112.0, 108.0, 109.0),
            c(8, 108.0, 109.0, 104.0, 105.0)
        )
        runN(e, bars, bars.size)
        assertEquals(1, e.trend)
        assertEquals(112.0, e.HH, 1e-9)
        assertEquals(94.0, e.HL, 1e-9)
        val hh = e.markers.filter { it.kind == MarkerKind.STRUCT && it.text == "HH" }
        val hl = e.markers.filter { it.kind == MarkerKind.STRUCT && it.text == "HL" }
        assertEquals(1, hh.size, "برچسب HH باید روی چارت باشد")
        assertEquals(1, hl.size, "برچسب HL باید روی چارت باشد")
        assertEquals(112.0, hh[0].price, 1e-9); assertEquals(7, hh[0].bi)
        assertEquals(94.0, hl[0].price, 1e-9); assertEquals(3, hl[0].bi)
    }

    /** قرینه: LL/LH در روند نزولی. */
    @Test
    fun `LL and LH labels are emitted as structure markers`() {
        val e = Engine(m1())
        val bars = listOf(
            c(0, 100.0, 102.0, 95.0, 97.0),    // نزولی
            c(1, 96.0, 101.0, 94.0, 100.0),    // صعودی ⇒ IC پایین‌ترین‌ها: IC=کندل۱ (کف ۹۴)، تایید=۱۰۱، ابطال=min(97,96)=96
            c(2, 100.0, 105.0, 99.0, 104.0),   // کلوز ۱۰۴ > ۱۰۱ ⇒ ReadyBU ⇒ pinBU=۹۴
            c(3, 104.0, 106.0, 102.0, 103.0),  // نزولی ⇒ IC بالاترین‌ها: IC=کندل۳ (سقف ۱۰۶)، تایید=۱۰۲، ابطال=max(104,104)=104
            c(4, 103.0, 104.0, 100.0, 101.0),  // کلوز ۱۰۱ < ۱۰۲ ⇒ ReadyBE ⇒ pinBE=۱۰۶ ⇒ تمپ‌باکس ۹۴..۱۰۶
            c(5, 101.0, 102.0, 92.0, 93.0),    // کلوز ۹۳ < ۹۴ ⇒ روند نزولی
            c(6, 93.0, 94.0, 90.0, 91.0),      // نزولی
            c(7, 91.0, 93.0, 88.0, 92.0),      // صعودی ⇒ IC پایین‌ترین‌ها: IC=کندل۷ (کف ۸۸)، تایید=۹۳، ابطال=min(91,91)=91
            c(8, 92.0, 95.0, 91.5, 94.0)       // کلوز ۹۴ > ۹۳ ⇒ ReadyBU ⇒ pinBU=۸۸ ⇒ LL=۸۸ ، LH=۱۰۶
        )
        runN(e, bars, bars.size)
        assertEquals(-1, e.trend)
        assertEquals(88.0, e.LL, 1e-9)
        assertEquals(106.0, e.LH, 1e-9)
        val ll = e.markers.filter { it.kind == MarkerKind.STRUCT && it.text == "LL" }
        val lh = e.markers.filter { it.kind == MarkerKind.STRUCT && it.text == "LH" }
        assertEquals(1, ll.size); assertEquals(1, lh.size)
        assertEquals(88.0, ll[0].price, 1e-9); assertEquals(7, ll[0].bi)
        assertEquals(106.0, lh[0].price, 1e-9); assertEquals(3, lh[0].bi)
    }

    /** §18-8: کندلی که تایید میانی را باطل می‌کند می‌تواند خودش تایید میانی جدید باشد. */
    @Test
    fun `the middle candle that breaks the confirmation becomes the new confirmation`() {
        val e = Engine(Settings())
        val s = Setup(1L, 1, 3, 95.0, 90.0, 1, 0).apply { midRef = 100.0; midConfirmBi = 1; touchMidStart = 0L }
        e.setups.add(s)
        e.curBi = 2
        val t = 1_700_000_700_000L
        e.curT = t
        val mc = Candle(2, t, 101.0, 104.0, 98.0, 102.0, 500.0)
        val mth = Engine::class.java.getDeclaredMethod("middleEngine", Candle::class.java).also { it.isAccessible = true }
        mth.invoke(e, mc)
        assertEquals(98.0, s.midRef, 1e-9, "کف همان کندل شکننده سطح تایید جدید است")
        assertEquals(2, s.stage)
        assertEquals(2, s.midBi)
    }

    /** §18-10: بعد از مسلح شدن، مصرف یا ابطال LV سفارش مسلح‌شده را لغو می‌کند. */
    @Test
    fun `LV consumption after arming cancels the pending order`() {
        val cfg = Settings().also { it.tfS = Tf.M1; it.tfM = Tf.M1; it.tf1 = Tf.M1; it.tf2 = Tf.M1 }
        val e = Engine(cfg); val b = PaperBroker(cfg); e.broker = b; b.engine = e
        val s = Setup(1L, 1, 6, 220.0, 200.0, 1, 0).apply {
            midRef = 195.0; midConfirmBi = 1
            lvBi = 2; lvConfBi = 2; lvH = 212.0; lvL = 205.0; lvReEntered = true; lvTouched = true
            hvBi = 4; hvConfBi = 5; hvH = 214.0; hvL = 213.0; sl = 212.98; tp1 = 220.0; tp2 = 222.0; tpx = 230.0
        }
        e.setups.add(s)
        e.curBi = 6; e.curT = 1_700_001_200_000L
        val bar = Candle(6, e.curT, 214.5, 215.0, 214.2, 214.8, 100.0)
        assertTrue(b.registerLimit(s, bar))
        assertEquals(1, b.pendingOrders.size)
        val q1 = Candle(7, e.curT + 60_000L, 213.0, 215.0, 212.5, 214.0, 90.0)  // کلوز بالای سقف LV ⇒ مصرف
        val mth = Engine::class.java.getDeclaredMethod("trigger1Engine", Candle::class.java).also { it.isAccessible = true }
        e.curBi = 7; e.curT = q1.t
        mth.invoke(e, q1)
        assertTrue(s.lvUsed)
        assertEquals(0, b.pendingOrders.size, "سفارش مسلح‌شده باید لغو شود (§18-10)")
        assertEquals(8, s.stage)
    }

    /** §18-13: وجود معاملهٔ باز مانع مسلح شدن سفارش جدید نیست؛ فقط یک سفارش معلق مجاز است. */
    @Test
    fun `an open trade does not block arming but a second pending order does`() {
        val cfg = Settings()
        val b = PaperBroker(cfg)
        val s1 = Setup(1L, 1, 5, 220.0, 200.0, 1, 0).apply { hvH = 210.0; hvL = 208.0; sl = 207.98; tp1 = 215.0; tp2 = 218.0; tpx = 225.0 }
        val s2 = Setup(2L, 1, 5, 215.0, 195.0, 2, 0).apply { hvH = 205.0; hvL = 203.0; sl = 202.98; tp1 = 210.0; tp2 = 213.0; tpx = 220.0 }
        val s3 = Setup(3L, 1, 5, 215.0, 195.0, 3, 0).apply { hvH = 204.0; hvL = 202.0; sl = 201.98; tp1 = 209.0; tp2 = 212.0; tpx = 219.0 }
        val bar = Candle(10, 1_700_001_000_000L, 211.0, 212.0, 210.0, 211.0, 100.0)
        assertTrue(b.registerLimit(s1, bar))
        b.onBar(Candle(11, bar.t + 60_000L, 211.0, 211.5, 209.0, 211.0, 100.0))   // پر شدن سفارش اول
        assertEquals(1, b.openTrades.size); assertEquals(0, b.pendingOrders.size)
        assertTrue(b.registerLimit(s2, Candle(12, bar.t + 120_000L, 211.0, 212.0, 210.0, 211.0, 100.0)), "با معاملهٔ باز، مسلح شدن مجاز است")
        assertFalse(b.registerLimit(s3, Candle(12, bar.t + 120_000L, 211.0, 212.0, 210.0, 211.0, 100.0)), "دو سفارش معلق هم‌زمان مجاز نیست")
    }

    /** §4-8 / §18-5: لنگر فیبوناچی و سطح انقضا = HH (خرید). */
    @Test
    fun `arming uses HH for fib anchor and price expiry`() {
        val cfg = Settings().apply { minTPunits = 10.0 }
        val e = Engine(cfg); val b = PaperBroker(cfg); e.broker = b; b.engine = e
        e.HH = 200.0; e.HL = 150.0; e.trend = 1
        e.highestBE = 230.0; e.lastBEpin = 190.0
        val s = Setup(1L, 1, 5, 220.0, 210.0, 1, 0).apply { hvH = 160.0; hvL = 159.0; midRef = 150.0 }
        val bar = Candle(10, 1_700_000_600_000L, 161.0, 162.0, 160.0, 161.0, 100.0)
        val mth = Engine::class.java.getDeclaredMethod("armSetup", Setup::class.java, Candle::class.java, Boolean::class.java).also { it.isAccessible = true }
        mth.invoke(e, s, bar, true)
        assertEquals(200.0, s.priceExpiryRef, 1e-9)
        // R = 200-150 = 50 ⇒ TP1 = 150+19.1 ، TP2 = 175 ، TPX = 150+63.6
        assertEquals(169.1, s.tp1, 1e-9); assertEquals(175.0, s.tp2, 1e-9); assertEquals(213.6, s.tpx, 1e-9)
    }
}
