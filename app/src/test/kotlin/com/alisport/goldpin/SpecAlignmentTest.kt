package com.alisport.goldpin

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.alisport.goldpin.core.Candle
import com.alisport.goldpin.core.MoneyMode
import com.alisport.goldpin.core.Setup
import com.alisport.goldpin.core.Tf
import com.alisport.goldpin.data.Feed
import com.alisport.goldpin.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Method

/**
 * هم‌ترازی با سند (نسخهٔ ۱٫۴٫۱۹):
 *  • §6: «اگر بیش از ۹۰٪ کندل‌ها حجم صفر داشته باشند» ⇒ حجم ساختگی (دقیقاً ۹۰٪ هنوز ساختگی نیست).
 *  • §20 cmsn «خودکار»: اسپرد زنده از پاسخ Swissquote (ask − bid).
 *  • §20: بخش مدیریت مالی در تب تنظیمات ساخته می‌شود و مود با دکمه عوض می‌شود.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SpecAlignmentTest {

    private fun series(n: Int, zeros: Int): List<Candle> = List(n) { i ->
        Candle(i, 1_700_000_000_000L + i * 60_000L, 2000.0, 2001.0, 1999.0, 2000.5, if (i < zeros) 0.0 else 50.0)
    }

    @Test
    fun `exactly ninety percent zero volume stays real, more than ninety percent becomes synthetic`() {
        val c90 = series(100, 90)
        val r90 = Feed.synthesizeVolumeIfMissing(c90)
        assertEquals("دقیقاً ۹۰٪ صفر ⇒ حجم‌ها دست‌نخورده (کندل‌های صفر با حجم صفر به موتور می‌روند)",
            c90.map { it.v }, r90.map { it.v })

        val c91 = series(100, 91)
        val r91 = Feed.synthesizeVolumeIfMissing(c91)
        assertTrue("بیش از ۹۰٪ صفر ⇒ حجم ساختگی از دامنهٔ کندل", r91.all { it.v > 0.0 })

        val none = series(100, 0)
        assertEquals(none.map { it.v }, Feed.synthesizeVolumeIfMissing(none).map { it.v })
        assertTrue(Feed.synthesizeVolumeIfMissing(emptyList()).isEmpty())
    }

    @Test
    fun `swissquote reply gives bid ask and a sane spread`() {
        val body = """[{"topo":{"platform":"SoftDelta","server":"Live1"},"spreadProfilePrices":[
            {"spreadProfile":"standard","bidSpread":0,"askSpread":0,"bid":2650.12,"ask":2650.62},
            {"spreadProfile":"prime","bidSpread":0,"askSpread":0,"bid":2650.2,"ask":2650.5}],"ts":1730000000000}]"""
        val ba = Feed.parseSwissquoteBidAsk(body)
        assertNotNull(ba)
        assertEquals(2650.12, ba!!.first, 1e-9)
        assertEquals(2650.62, ba.second, 1e-9)
        assertEquals(0.5, ba.second - ba.first, 1e-6)
        assertNull(Feed.parseSwissquoteBidAsk("[]"))
        assertNull(Feed.parseSwissquoteBidAsk("{not json"))
        assertNull(Feed.parseSwissquoteBidAsk("""[{"spreadProfilePrices":[{"bid":1.0}]}]"""))
    }

    private fun texts(v: View, out: MutableList<TextView> = ArrayList()): List<TextView> {
        if (v is TextView) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    @Test
    fun `settings tab has the money management section and the mode button switches the mode`() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = c.get()
        shadowOf(Looper.getMainLooper()).idle()
        val s = AppState.instance
        val prevMode = s.cfg.mmMode
        try {
            assertEquals("پیش‌فرض مود مدیریت مالی خاموش است (تصمیم مالک)", MoneyMode.NONE, com.alisport.goldpin.core.Settings().mmMode)
            s.cfg.mmMode = MoneyMode.NONE
            val m: Method = act.javaClass.getDeclaredMethod("showTab", Int::class.javaPrimitiveType)
            m.isAccessible = true
            m.invoke(act, 4)
            shadowOf(Looper.getMainLooper()).idle()
            val root = act.window.decorView.findViewById<ViewGroup>(android.R.id.content)
            var all = texts(root).map { it.text.toString() }
            assertTrue("عنوان بخش §۲۰ باید در تنظیمات باشد", all.any { it.contains("مدیریت مالی و مدیریت زیان") })
            assertTrue(all.any { it.contains("ManageAllLost") })
            assertTrue(all.any { it.contains("بافر سطح سفارش") })
            assertTrue(all.any { it.contains("NakedCapital") })

            val btn = texts(root).filterIsInstance<Button>().first { it.text.toString().contains("مود: Mixed") }
            btn.performClick()
            assertEquals(MoneyMode.MIXED, s.cfg.mmMode)
            shadowOf(Looper.getMainLooper()).idle()
            all = texts(root).map { it.text.toString() }
            assertTrue("بعد از انتخاب مود، تنظیمات دوباره ساخته می‌شود و همچنان بخش §۲۰ را دارد",
                all.any { it.contains("وضعیت مدیریت مالی") })
        } finally {
            s.cfg.mmMode = prevMode
            c.pause().stop().destroy()
        }
    }

    /**
     * §۶: «با قیمت زنده، کارگزار کاغذی سفارش را پر می‌کند» — مسیر واقعی AppState.feedLiveTail:
     * تا وقتی کندلِ در حال تشکیل به سطح سفارش نرسیده پر نمی‌شود؛ به‌محض رسیدن (بدون بسته شدن کندل) پر می‌شود.
     */
    @Test
    fun `live tick fills a pending limit order from the forming candle`() {
        Log.init(RuntimeEnvironment.getApplication(), "test")
        val s = AppState()
        s.chartTfSec = Tf.M1
        s.cfg.tf2 = Tf.M1
        val step = 60_000L
        val t0 = (System.currentTimeMillis() / step) * step - 500 * step
        // سری تخت: هیچ پین/ستاپی نمی‌سازد، پس سفارش دستی ما تنها سفارش معلق است
        val base = List(400) { i -> Candle(i, t0 + i * step, 2000.0, 2000.4, 1999.6, 2000.0, 10.0) }
        s.setCandles(base)
        assertEquals(400, s.engine.processed)
        assertFalse("پیش‌شرط: موتور خودش سفارشی نگذاشته", s.broker.hasPending)

        val st = Setup(77L, 1, 6, 2005.0, 1995.0, 0, 0)
        st.hvH = 2000.0; st.hvL = 1999.0; st.sl = 1996.0; st.tpx = 2012.0
        assertTrue("ثبت سفارش دستی رد شد: ${s.broker.lastError}", s.broker.registerLimit(st, base.last()))
        val level = s.broker.pendingOrder!!.price

        fun forming(low: Double) = Candle(0, base.last().t + step, level + 1.0, level + 2.0, low, level + 0.5, 5.0)

        s.feedLiveTail(listOf(base.last(), forming(level + 0.3)))      // هنوز به سطح نرسیده
        assertTrue("تا لمس نشده، سفارش معلق می‌ماند", s.broker.hasPending)
        assertEquals(0, s.broker.openTrades.size)

        s.feedLiveTail(listOf(base.last(), forming(level - 0.1)))      // همان کندلِ در حال تشکیل، low به سطح رسید
        assertFalse("با لمس سطح، سفارش همان لحظه پر می‌شود", s.broker.hasPending)
        assertEquals(1, s.broker.openTrades.size)
        assertEquals(level, s.broker.openTrades[0].entry, 1e-9)
        assertEquals("کندل هنوز بسته نشده؛ موتور آن را پردازش نکرده", 400, s.engine.processed)
    }
}
