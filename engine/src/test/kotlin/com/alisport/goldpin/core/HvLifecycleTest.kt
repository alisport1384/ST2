package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * چرخهٔ کامل عمر ناحیهٔ حجم زیاد: مسلح‌شدن → پر شدن → مدیریت → پایان → مسلح‌شدن دوباره.
 * هر آزمون یک بند از §۴-۸/§۴-۹/§۱۸ سند را می‌سنجد.
 */
class HvLifecycleTest {

    private class Rig(bull: Boolean = true) {
        val cfg = Settings()
        val e = Engine(cfg)
        val b = PaperBroker(cfg)
        val s: Setup
        private var prevVol = 1_000.0

        init {
            e.broker = b; b.engine = e
            e.HH = 400.0
            e.LL = 50.0
            s = Setup(1L, if (bull) 1 else -1, 4, 300.0, 100.0, 1, 0).apply {
                midRef = if (bull) 50.0 else 350.0
                midConfirmBi = 0
                lvBi = 1; lvConfBi = 1; lvH = 210.0; lvL = 200.0
                lvReEntered = false; zBi = 0
            }
            e.setups.add(s)
        }

        fun bar(bi: Int, o: Double, h: Double, l: Double, c: Double, v: Double, manage: Boolean = true): Candle {
            val cd = Candle(bi, 1_700_000_000_000L + bi * 60_000L, o, h, l, c, v)
            e.curBi = bi; e.curT = cd.t
            e.prevChartVol = prevVol
            invoke("trigger2Engine", cd)
            prevVol = v
            if (manage) b.onBar(cd, live = false)
            return cd
        }

        fun armAt(bi: Int): Candle {
            bar(bi - 2, 205.0, 215.0, 205.0, 214.0, 10.0)     // برگشت به LV
            bar(bi - 1, 214.0, 216.0, 211.0, 215.0, 30.0)     // مارک hvH=216 hvL=211
            return bar(bi, 216.0, 216.5, 215.5, 216.2, 10.0)  // تأیید/مسلح در 216.02
        }

        private fun invoke(name: String, vararg args: Any?) {
            val types = args.map { it!!::class.java as Class<*> }.toTypedArray()
            val m = Engine::class.java.getDeclaredMethod(name, *types)
            m.isAccessible = true
            m.invoke(e, *args)
        }
    }

    @Test
    fun `stop moves to the order level once after a trigger-2 candle clears the level`() {
        val r = Rig()
        r.armAt(3)
        assertEquals(216.02, r.b.pendingOrders.single().price, 1e-9)

        r.bar(4, 216.3, 216.4, 216.0, 216.1, 10.0)        // پر شدن روی سطح سفارش
        assertEquals(1, r.b.trades.size)
        assertEquals(210.98, r.s.sl, 1e-9)

        // کندلی که سطح سفارش را لمس می‌کند ⇒ هنوز جابه‌جایی نیست
        r.bar(5, 216.3, 216.4, 216.02, 216.1, 10.0)
        assertFalse(r.s.slMidDone, "لمس سطح سفارش (حتی دقیقاً روی آن) انتقال حدضرر را فعال نمی‌کند")
        assertEquals(210.98, r.b.trades[0].slAdj.let { if (it.isNaN()) 210.98 else it }, 1e-9)

        // کندل کاملاً بالاتر از سطح سفارش ⇒ انتقال یک‌بارهٔ حدضرر به سطح سفارش
        r.bar(6, 216.3, 216.5, 216.10, 216.4, 10.0)
        assertTrue(r.s.slMidDone)
        assertEquals(216.02, r.s.sl, 1e-9, "انجین: حدضرر = سطح سفارش")
        assertEquals(216.02, r.b.trades[0].slAdj, 1e-9, "کارگزار: حدضرر = سطح سفارش")

        // بازگشت قیمت به سطح سفارش ⇒ خروج سر‌به‌سر، نه حد ضرر
        r.bar(7, 216.2, 216.3, 216.0, 216.05, 10.0)
        assertEquals(0, r.b.trades.count { it.open })
        assertEquals("خروج سر‌به‌سر", r.b.trades.last().reason)
    }

    @Test
    fun `the stop-to-order move is skipped on the entry candle and after any TP1 touch`() {
        val r = Rig()
        r.armAt(3)
        r.bar(4, 216.3, 216.4, 216.0, 216.1, 10.0)   // پر شدن
        assertFalse(r.s.slMidDone, "کندل ورود هرگز حدضرر را جابه‌جا نمی‌کند")

        // کندلی که TP1 را لمس می‌کند (و کاملاً بالای ورود است) ⇒ فقط سر‌به‌سر
        r.bar(5, 216.4, 323.0, 216.3, 322.0, 10.0)
        assertTrue(r.s.be, "TP1 ⇒ سر‌به‌سر")
        assertFalse(r.s.slMidDone, "بعد از TP1 قاعدهٔ انتقال حدضرر اجرا نمی‌شود")
        assertEquals(216.02, r.s.sl, 1e-9)
    }

    @Test
    fun `an open trade does not block arming the next order (one pending order only)`() {
        val r = Rig()
        r.armAt(3)
        r.bar(4, 216.3, 216.4, 216.0, 216.1, 10.0)   // پر شدن؛ معامله باز است
        assertEquals(1, r.b.trades.count { it.open })
        assertEquals(0, r.b.pendingOrders.size)

        // ستاپ دوم مستقل روی همان جهت، با ناحیهٔ ساختار دیگر
        val s2 = Setup(2L, 1, 5, 225.0, 215.0, 2, 0).apply {
            midRef = 50.0; midConfirmBi = 0
            lvBi = 1; lvConfBi = 1; lvH = 210.0; lvL = 200.0
            lvReEntered = true; hvBi = 5; hvH = 220.0; hvL = 219.0
        }
        r.e.setups.add(s2)
        r.bar(5, 220.0, 221.0, 219.5, 220.5, 10.0)   // تأیید ستاپ دوم
        assertEquals(1, r.b.pendingOrders.size, "وجود معاملهٔ باز مانع مسلح‌شدن سفارش جدید نیست (§۱۸-۱۳)")
        assertEquals(1, r.b.trades.count { it.open }, "معاملهٔ باز دست‌نخورده می‌ماند")

        // سفارش سوم هم‌زمان مجاز نیست
        val s3 = Setup(3L, 1, 5, 235.0, 225.0, 2, 0).apply {
            midRef = 50.0; midConfirmBi = 0
            lvBi = 1; lvConfBi = 1; lvH = 210.0; lvL = 200.0
            lvReEntered = true; hvBi = 5; hvH = 230.0; hvL = 229.0
        }
        r.e.setups.add(s3)
        r.bar(6, 230.0, 231.0, 229.5, 230.5, 10.0)
        assertEquals(1, r.b.pendingOrders.size, "در هر لحظه فقط یک سفارش معلق (§۴-۸)")
    }

    @Test
    fun `after a stop-out the same setup can rearm only at a better price`() {
        val r = Rig()
        r.armAt(3)
        r.bar(4, 216.3, 216.4, 216.0, 216.1, 10.0)   // پر شدن
        r.bar(5, 216.3, 216.4, 210.5, 211.0, 10.0)   // حد ضرر 210.98
        assertEquals(1, r.b.trades.size)
        assertEquals(0, r.b.trades.count { it.open }, "حد ضرر معامله را بست")
        assertEquals(4, r.s.stage, "بعد از حد ضرر، ستاپ برای مسلح‌شدن دوباره می‌ماند")
        assertEquals(216.02, r.s.prevEntry, 1e-9)

        // مارک جدید بالاتر ⇒ «بهتر» نیست
        r.bar(6, 219.0, 220.0, 218.5, 219.0, 20.0)
        r.bar(7, 219.5, 220.5, 219.0, 220.4, 10.0)
        assertEquals(0, r.b.pendingOrders.size, "سفارش جدید بالاتر از قبلی برای خرید پذیرفته نمی‌شود")
        assertEquals(4, r.s.stage, "پس از رد شدن مسلح‌مجدد، ستاپ به حالت «اسکن» برمی‌گردد")

        // مارک جدید پایین‌تر ⇒ بهتر
        r.bar(8, 214.0, 214.5, 213.0, 213.6, 25.0)
        assertEquals(8, r.s.hvBi)
        r.bar(9, 213.8, 214.8, 213.2, 214.7, 10.0)
        assertEquals(6, r.s.stage)
        assertEquals(214.52, r.b.pendingOrders.single().price, 1e-9, "سطح سفارش مارک جدید: ۲۱۴٫۵ + ۲ تیک")
        assertTrue(r.b.pendingOrders.single().price < r.s.prevEntry + 1e-9)
    }

    @Test
    fun `a final target ends the setup and forbids rearming`() {
        val r = Rig()
        r.armAt(3)
        r.bar(4, 216.3, 216.4, 216.0, 216.1, 10.0)   // پر شدن
        val tpx = r.s.tpx
        r.bar(5, 300.0, tpx + 0.5, 299.0, tpx, 10.0) // حد سود نهایی
        assertEquals(0, r.b.trades.count { it.open })
        assertEquals(8, r.s.stage, "بعد از حد سود نهایی ستاپ تمام است")
        assertFalse(r.s.rearmAllowed)
    }

    @Test
    fun `a confirming candle that closes outside the box is required, the mark candle itself is not confirmation`() {
        val r = Rig()
        r.bar(1, 205.0, 215.0, 205.0, 214.0, 10.0)   // برگشت
        r.bar(2, 214.0, 216.0, 211.0, 215.0, 30.0)   // مارک
        assertEquals(5, r.s.stage)
        assertEquals(0, r.b.pendingOrders.size, "کندل مارک هرگز خودش سفارش نمی‌سازد")
        r.bar(3, 215.0, 216.0, 214.0, 215.9, 10.0)   // بالای سقف بسته نشد
        assertEquals(5, r.s.stage)
        assertEquals(0, r.b.pendingOrders.size)
    }

    @Test
    fun `a rejected arming drops the mark and returns the setup to scanning (no stale HV box)`() {
        val r = Rig()
        r.armAt(3)                                   // سفارش اول مسلح شد
        assertEquals(1, r.b.pendingOrders.size)

        // ستاپ دوم هم مارک و تأیید می‌گیرد، ولی چون سفارش معلق وجود دارد نباید مسلح شود
        val s2 = Setup(2L, 1, 5, 225.0, 215.0, 2, 0).apply {
            midRef = 50.0; midConfirmBi = 0
            lvBi = 1; lvConfBi = 1; lvH = 210.0; lvL = 200.0
            lvReEntered = true; hvBi = 4; hvH = 220.0; hvL = 219.0
        }
        r.e.setups.add(s2)
        r.bar(4, 220.0, 221.0, 219.5, 220.5, 10.0)   // تأیید ستاپ دوم ⇒ رد
        assertEquals(1, r.b.pendingOrders.size, "سفارش دوم مجاز نیست")
        assertEquals(-1, s2.hvBi, "مارک رد‌شده باید پاک شود")
        assertEquals(4, s2.stage, "مرحله باید به «اسکن» برگردد، نه «مارک‌شده» (خط/باکس کهنه روی چارت)")
    }
}
