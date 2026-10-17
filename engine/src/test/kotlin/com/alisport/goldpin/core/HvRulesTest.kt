package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * قانون‌های ناحیهٔ حجم زیاد (HV) — بند §۴-۸ و §۱۸-۱۷ سند، تک‌به‌تک روی کد.
 *
 * هر آزمون یک جملهٔ سند را می‌سنجد:
 *  • شروع اسکن فقط بعد از تأیید LV و از اولین برگشت به باکس LV (§۴-۸ شروع اسکن، §۱۸-۷)
 *  • مارک = «اولین» کندل بستهٔ تریگر۲ با حجم بیشتر از کندل قبلی (§۴-۸، §۱۸-۱۷)
 *  • ابطال مارک با عبور یک تیک از سمت دور (§۴-۸)
 *  • تأیید فقط با «بسته شدن» آن‌طرف باکس (§۴-۸)
 *  • سطح سفارش = سقف + ۲ تیک (خرید) / کف − ۲ تیک (فروش) و همیشه مثبت (§۴-۸)
 *  • حد ضرر = کف − ۲ تیک (خرید) / سقف + ۲ تیک (فروش) (§۴-۸)
 *  • پر شدن در کندل مسلح‌شدن ممنوع (§۴-۸)
 *  • انقضا: عبور باز/کلوز از HH/LL یا گذشت یک کندل ساختار کامل (§۴-۸)
 */
class HvRulesTest {

    /**
     * دام: یک ستاپ در مرحلهٔ ۴ (باکس LV تأییدشده) با سطوح دور از بقیهٔ قانون‌ها
     * تا فقط رفتار HV سنجیده شود.
     */
    private class Rig(bull: Boolean = true) {
        val cfg = Settings()
        val e = Engine(cfg)
        val b = PaperBroker(cfg)
        val s: Setup
        private var prevVol = 1_000.0     // حجم کندل قبل از شروع آزمون

        init {
            e.broker = b; b.engine = e
            e.HH = 400.0
            e.LL = 50.0
            s = Setup(1L, if (bull) 1 else -1, 4, 300.0, 100.0, 1, 0).apply {
                midRef = if (bull) 50.0 else 350.0      // دور از همهٔ کندل‌های آزمون
                midConfirmBi = 0
                lvBi = 1; lvConfBi = 1
                lvH = 210.0; lvL = 200.0                // باکس LV خرید
                lvReEntered = false
                zBi = 0
            }
            e.setups.add(s)
        }

        /** یک کندل تریگر۲ بسته + هم‌گام‌سازی «حجم کندل قبلی» مثل processClosed. */
        fun bar(bi: Int, o: Double, h: Double, l: Double, c: Double, v: Double): Candle {
            val cd = Candle(bi, 1_700_000_000_000L + bi * 60_000L, o, h, l, c, v)
            e.curBi = bi; e.curT = cd.t
            e.prevChartVol = prevVol
            invoke("trigger2Engine", cd)
            prevVol = v
            return cd
        }

        private fun invoke(name: String, vararg args: Any?) {
            val types = args.map { it!!::class.java as Class<*> }.toTypedArray()
            val m = Engine::class.java.getDeclaredMethod(name, *types)
            m.isAccessible = true
            m.invoke(e, *args)
        }
    }

    // ═══ شروع اسکن (بند §۴-۸ + §۱۸-۷) ═══════════════════════════════════════════
    @Test
    fun `HV scan does not start before the first return to the LV box`() {
        val r = Rig()
        // سه کندل بالای باکس LV با حجم صعودی — ولی هنوز برگشتی به LV رخ نداده
        r.bar(1, 214.0, 216.0, 212.0, 215.0, 2_000.0)
        r.bar(2, 215.0, 218.0, 213.0, 217.0, 3_000.0)
        r.bar(3, 217.0, 219.0, 214.0, 218.0, 4_000.0)
        assertEquals(-1, r.s.hvBi, "بدون برگشت به باکس LV هیچ کندلی نباید مارک شود")
        assertFalse(r.s.lvReEntered)

        // اولین برگشت واقعی روی تریگر۲: کندل با باکس LV برخورد می‌کند و حجمش کمتر از قبلی است
        r.bar(4, 216.0, 217.0, 205.0, 211.0, 50.0)
        assertTrue(r.s.lvReEntered, "برگشت به باکس LV باید روی تریگر۲ دیده شود (§۱۸-۷)")
        assertEquals(-1, r.s.hvBi, "کندل برگشت با حجم کمتر مارک نمی‌شود")

        // اولین افزایش حجم بعد از شروع اسکن ⇒ مارک همان کندل
        r.bar(5, 211.0, 216.0, 210.5, 215.0, 60.0)
        assertEquals(5, r.s.hvBi, "اولین کندل با حجم بیشتر از قبلی مارک می‌شود (§۱۸-۱۷)")
        assertEquals(216.0, r.s.hvH, 1e-9)
        assertEquals(210.5, r.s.hvL, 1e-9)
        assertEquals(5, r.s.stage, "بعد از مارک، مرحله ۵")
    }

    @Test
    fun `only the first volume increase is marked, later bigger volumes do not move the mark`() {
        val r = Rig()
        r.bar(1, 205.0, 215.0, 205.0, 214.0, 10.0)   // برگشت به LV (حجم کمتر از ۱۰۰۰)
        r.bar(2, 214.0, 216.0, 211.0, 215.0, 30.0)    // اولین افزایش ⇒ مارک
        r.bar(3, 215.0, 219.0, 214.0, 218.0, 900.0)   // حجم بزرگ‌تر ولی مارک جابه‌جا نمی‌شود
        assertEquals(2, r.s.hvBi)
        assertEquals(216.0, r.s.hvH, 1e-9)
    }

    // ═══ ابطال مارک (§۴-۸) ══════════════════════════════════════════════════════
    @Test
    fun `buy mark is deleted when price crosses one tick below the marked candle low`() {
        val r = Rig()
        r.bar(1, 205.0, 215.0, 205.0, 214.0, 10.0)
        r.bar(2, 214.0, 216.0, 211.0, 215.0, 30.0)    // مارک: hvL=211
        r.bar(3, 214.0, 215.5, 211.0, 213.0, 10.0)    // لمس دقیق کف = هنوز باطل نیست
        assertEquals(2, r.s.hvBi, "تساوی با کف، عبور نیست")
        r.bar(4, 213.0, 213.5, 210.99, 212.0, 10.0)   // یک تیک زیر کف ⇒ باطل
        assertEquals(-1, r.s.hvBi, "عبور یک تیک از کف، مارک را پاک می‌کند")
        assertEquals(4, r.s.stage)
    }

    @Test
    fun `sell mark is deleted when price crosses one tick above the marked candle high`() {
        val r = Rig(bull = false)
        r.s.lvH = 190.0; r.s.lvL = 180.0
        r.bar(1, 190.0, 190.0, 180.0, 181.0, 10.0)    // برگشت به باکس LV فروش
        r.bar(2, 181.0, 184.0, 179.0, 180.0, 30.0)    // مارک: hvH=184
        assertEquals(2, r.s.hvBi)
        r.bar(3, 180.0, 184.0, 179.5, 182.0, 10.0)    // لمس سقف
        assertEquals(2, r.s.hvBi)
        r.bar(4, 182.0, 184.01, 181.0, 183.0, 10.0)   // یک تیک بالای سقف ⇒ باطل
        assertEquals(-1, r.s.hvBi)
    }

    // ═══ تأیید (§۴-۸) ═══════════════════════════════════════════════════════════
    @Test
    fun `buy confirmation needs a close above the box top, equality is not enough`() {
        val r = Rig()
        r.bar(1, 205.0, 215.0, 205.0, 214.0, 10.0)
        r.bar(2, 214.0, 216.0, 211.0, 215.0, 30.0)   // مارک hvH=216
        r.bar(3, 215.0, 216.0, 214.0, 216.0, 10.0)   // کلوز دقیقاً روی سقف ⇒ تأیید نمی‌شود
        assertEquals(5, r.s.stage, "تساوی با سقف، «بالاتر» نیست")
        assertEquals(0, r.b.pendingOrders.size)
        r.bar(4, 216.0, 216.5, 215.5, 216.01, 10.0)  // یک تیک بالاتر ⇒ تأیید
        assertEquals(6, r.s.stage)
        assertEquals(1, r.b.pendingOrders.size)
    }

    // ═══ سطح سفارش و حد ضرر (§۴-۸) ══════════════════════════════════════════════
    @Test
    fun `buy order sits two ticks over the box top and the stop two ticks under the bottom`() {
        val r = Rig()
        r.bar(1, 205.0, 215.0, 205.0, 214.0, 10.0)
        r.bar(2, 214.0, 216.0, 211.0, 215.0, 30.0)   // مارک hvH=216 hvL=211
        r.bar(3, 216.0, 216.5, 215.5, 216.2, 10.0)   // تأیید
        assertEquals(6, r.s.stage)
        val o = r.b.pendingOrders.single()
        assertEquals(216.02, o.price, 1e-9, "سطح سفارش خرید = سقف + ۲ تیک")
        assertEquals(210.98, o.sl, 1e-9, "حد ضرر خرید = کف − ۲ تیک")
    }

    @Test
    fun `sell order sits two ticks under the box bottom and the stop two ticks over the top`() {
        val r = Rig(bull = false)
        r.s.lvH = 190.0; r.s.lvL = 180.0
        r.bar(1, 190.0, 190.0, 180.0, 181.0, 10.0)   // برگشت
        r.bar(2, 181.0, 184.0, 179.0, 180.0, 30.0)   // مارک hvH=184 hvL=179
        r.bar(3, 180.0, 180.5, 178.5, 178.99, 10.0)  // کلوز زیر کف ⇒ تأیید
        assertEquals(6, r.s.stage)
        val o = r.b.pendingOrders.single()
        assertEquals(178.98, o.price, 1e-9, "سطح سفارش فروش = کف − ۲ تیک")
        assertEquals(184.02, o.sl, 1e-9, "حد ضرر فروش = سقف + ۲ تیک")
        assertEquals(-1, o.dir)
    }

    @Test
    fun `order level is clamped positive when the box bottom is closer than the buffer to zero`() {
        val c = Settings()
        assertEquals(0.01, c.entryLevel(false, 1.0, 0.0), 1e-12, "سطح سفارش همیشه مثبت است")
        assertEquals(2.02, c.entryLevel(true, 2.0, 1.0), 1e-12)
    }

    // ═══ پر شدن و عمر سفارش (§۴-۸) ══════════════════════════════════════════════
    @Test
    fun `the armed order never fills on its own arming candle but fills on the next touch`() {
        val r = Rig()
        r.bar(1, 205.0, 215.0, 205.0, 214.0, 10.0)
        r.bar(2, 214.0, 216.0, 211.0, 215.0, 30.0)   // مارک
        val cd3 = r.bar(3, 216.0, 216.5, 215.5, 216.2, 10.0)   // تأیید + مسلح‌شدن
        assertEquals(6, r.s.stage)
        val o = r.b.pendingOrders.single()
        assertEquals(216.02, o.price, 1e-9)

        // همان کندل مسلح‌شدن (low=215.5 سطح را لمس کرده) نباید پر کند
        r.b.onBar(cd3, live = false)
        assertEquals(0, r.b.trades.size, "پر شدن در کندل مسلح‌شدن ممنوع است")

        val cd4 = r.bar(4, 216.3, 216.4, 216.0, 216.1, 10.0)
        r.b.onBar(cd4, live = false)
        assertEquals(1, r.b.trades.size, "کندل بعدی که سطح سفارش را لمس کند پر می‌شود")
        assertEquals(216.02, r.b.trades[0].entry, 1e-9)
    }

    @Test
    fun `an unfilled order expires after one full structure candle`() {
        val r = Rig()                                   // tfS=3600 · tf2=60 ⇒ n=60 کندل
        r.bar(1, 205.0, 215.0, 205.0, 214.0, 10.0)
        r.bar(2, 214.0, 216.0, 211.0, 215.0, 30.0)
        r.bar(3, 216.0, 216.5, 215.5, 216.2, 10.0)      // مسلح در کندل ۳ (armBi=3)
        assertEquals(6, r.s.stage)

        // کندل‌ها دور از سطح سفارش می‌مانند تا پر نشود؛ HH=400 هم عبور نمی‌کند
        for (bi in 4..62) r.bar(bi, 218.0, 219.0, 217.0, 218.0, 10.0)
        assertEquals(6, r.s.stage, "تا پیش از گذشت ۶۰ کندل، سفارش زنده است")

        r.bar(63, 218.0, 219.0, 217.0, 218.0, 10.0)     // 63 − 3 = 60 ⇒ انقضا
        assertEquals(OrderStatus.CANCELLED_EXPIRED, r.b.orders.last().status)
        assertTrue(r.b.orders.last().cancelReason!!.startsWith("گذشت یک کندل ساختار کامل"))
    }

    @Test
    fun `an unfilled buy order expires when the candle opens above HH`() {
        val r = Rig()
        r.e.HH = 220.0
        r.bar(1, 205.0, 215.0, 205.0, 214.0, 10.0)
        r.bar(2, 214.0, 216.0, 211.0, 215.0, 30.0)
        r.bar(3, 216.0, 216.5, 215.5, 216.2, 10.0)      // مسلح؛ priceExpiryRef = HH = 220
        assertEquals(220.0, r.s.priceExpiryRef, 1e-9)

        r.bar(4, 220.01, 221.0, 219.0, 220.5, 10.0)     // باز شدن بالای HH
        assertEquals(OrderStatus.CANCELLED_EXPIRED, r.b.orders.last().status)
        assertEquals(4, r.s.stage, "بعد از انقضای قیمتی، ستاپ برای مسلح‌مجدد می‌ماند")
        assertTrue(r.s.rearmAllowed)
    }

    // ═══ مسلح‌شدن دوباره (§۴-۸) ═════════════════════════════════════════════════
    @Test
    fun `after expiry a new order is armed only at a better price`() {
        val r = Rig()
        r.e.HH = 220.0
        r.bar(1, 205.0, 215.0, 205.0, 214.0, 10.0)
        r.bar(2, 214.0, 216.0, 211.0, 215.0, 30.0)
        r.bar(3, 216.0, 216.5, 215.5, 216.2, 10.0)      // مسلح در 216.02
        r.bar(4, 220.01, 221.0, 219.0, 220.5, 10.0)     // انقضای قیمتی
        assertEquals(4, r.s.stage)

        // مارک و تأیید تازه، ولی سطح سفارش بالاتر از سفارش قبلی ⇒ «بهتر» نیست
        r.bar(5, 219.0, 220.0, 218.0, 219.0, 20.0)      // مارک hvH=220
        assertEquals(5, r.s.hvBi)
        r.bar(6, 219.5, 220.5, 219.0, 220.4, 10.0)      // تأیید ⇒ باید رد شود
        assertEquals(4, r.s.stage, "ورود ۲۲۰٫۰۲ پایین‌تر از ۲۱۶٫۰۲ نیست")
        assertEquals(0, r.b.pendingOrders.size, "سفارش جدیدی ثبت نمی‌شود")

        // حالا مارک پایین‌تر ⇒ سفارش بهتر (پایین‌تر) و مسلح
        r.bar(7, 214.0, 214.5, 213.0, 213.6, 25.0)      // مارک hvH=214.5
        r.bar(8, 213.8, 214.7, 213.2, 214.6, 10.0)      // تأیید (کلوز بالای 214.5)
        assertEquals(6, r.s.stage)
        assertEquals(214.52, r.b.pendingOrders.single().price, 1e-9)
    }

    // ═══ فقط یک سفارش هم‌زمان (§۴-۸) ════════════════════════════════════════════
    @Test
    fun `a second confirmation cannot arm a second order while one is pending`() {
        val r = Rig()
        r.bar(1, 205.0, 215.0, 205.0, 214.0, 10.0)
        r.bar(2, 214.0, 216.0, 211.0, 215.0, 30.0)
        r.bar(3, 216.0, 216.5, 215.5, 216.2, 10.0)      // مسلح
        assertEquals(1, r.b.pendingOrders.size)

        // ستاپ دوم مستقل هم تأیید می‌شود؛ نباید سفارش دوم ثبت شود
        val s2 = Setup(2L, 1, 5, 300.0, 100.0, 2, 0).apply {
            midRef = 50.0; midConfirmBi = 0
            lvBi = 1; lvConfBi = 1; lvH = 210.0; lvL = 200.0
            lvReEntered = true; hvBi = 5; hvH = 212.0; hvL = 211.0
        }
        r.e.setups.add(s2)
        r.bar(4, 212.0, 213.0, 211.5, 212.5, 10.0)      // کلوز بالای 212 ⇒ تأیید ستاپ دوم
        assertEquals(1, r.b.pendingOrders.size, "در هر لحظه فقط یک سفارش مجاز است")
    }

    // ═══ دامنهٔ تریگرها در شروع اسکن (§۱۸-۷) ═══════════════════════════════════
    @Test
    fun `trigger-1 touch tracks consumption only and never sets the scan start index`() {
        val r = Rig()
        r.e.curBi = 3; r.e.curT = 1_700_000_180_000L
        val q1 = Candle(3, 1_700_000_180_000L, 205.0, 211.0, 204.0, 205.5, 10.0) // لمس باکس LV
        val m = Engine::class.java.getDeclaredMethod("trigger1Engine", Candle::class.java)
        m.isAccessible = true
        m.invoke(r.e, q1)
        assertTrue(r.s.lvTouched, "لمس باکس LV برای مصرف باید ثبت شود")
        assertEquals(-1, r.s.hvScanBi, "شاخص شروع اسکن HV فقط روی تریگر۲ ثبت می‌شود (§۱۸-۷)")
        assertEquals(-1, r.s.hvBi, "لمس تریگر۱ نباید کندل HV مارک کند")
    }

    @Test
    fun `with the first-return option off the scan starts right after LV confirmation`() {
        val r = Rig()
        r.e.cfg.hvScanFirstTouch = false
        r.s.lvConfBi = 1
        r.bar(2, 216.0, 217.0, 215.0, 216.5, 10.0)   // کندل بعد از تایید؛ حجم کمتر از قبلی ⇒ بدون مارک
        assertEquals(-1, r.s.hvBi)
        r.bar(3, 216.5, 217.5, 216.0, 217.0, 40.0)   // اولین افزایش حجم ⇒ مارک، بدون نیاز به برگشت
        assertEquals(3, r.s.hvBi, "با خاموش‌بودن گزینهٔ «اولین برگشت»، اسکن از بعد از تایید LV شروع می‌شود")
        assertFalse(r.s.lvReEntered, "برگشتی رخ نداده است")
    }
}
