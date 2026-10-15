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
import com.alisport.goldpin.util.Fa
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

    /**
     * همهٔ کارهای پس‌زمینهٔ اپ (بازیابی/نمونهٔ شروعِ Activity، بازسازی موتور، دریافت اسپرد) تمام شوند؛ وگرنه ممکن است
     * تنظیمات و کندل‌های آزمون را وسط کار دوباره بنویسند. پرچم `busy` به‌تنهایی کافی نیست (singleton مشترک بین آزمون‌هاست
     * و کارِ آزمون قبلی می‌تواند آن را زودتر پاک کند)؛ پس اجرای‌کننده‌های تک‌نخیِ io و net را تخلیه می‌کنیم:
     * یک کار خالی که برگشت یعنی همهٔ کارهای صف‌شدهٔ قبل از آن تمام شده‌اند.
     */
    private fun waitUntilIdle(s: AppState) {
        repeat(3) {
            s.net.submit {}.get(90, java.util.concurrent.TimeUnit.SECONDS)
            s.io.submit {}.get(90, java.util.concurrent.TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        }
        val t0 = System.currentTimeMillis()
        while (s.busy && System.currentTimeMillis() - t0 < 90_000) {
            Thread.sleep(50); shadowOf(Looper.getMainLooper()).idle()
        }
        assertFalse("کارهای پس‌زمینه باید تمام شوند", s.busy)
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
        s.spreadFetcher = { null }            // آزمون هرمتیک: شبکهٔ واقعی دخالت نکند
        waitUntilIdle(s)
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
            assertTrue("دکمهٔ دریافت اسپرد بروکر باید باشد (cmsn خودکار روشن است)", texts(root).filterIsInstance<Button>().any { it.text.toString().contains("اسپرد بروکر") })
            assertFalse("اهرم یک متغیر است: ردیف جداگانهٔ «سقف اهرم §۲۰» نباید باشد", all.any { it.contains("سقف اهرم در مودهای") })
            assertTrue("مود خاموش ⇒ تذکر می‌دهد MinLot/div/cmsn بی‌اثرند", all.any { it.contains("مود خاموش است") })
            assertTrue("کادر وضعیت هم Capital و هم Equity را با نام جدا نشان می‌دهد",
                all.any { it.contains("Capital") } && all.any { it.contains("Equity") })

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

    /**
     * کادر «وضعیت مدیریت مالی» نباید کهنه بماند: بعد از تغییر مود، بازسازی در پس‌زمینه تمام می‌شود و همان لحظه
     * عددهای Lost / Capital کادر باید با کارگزار یکی شوند (قبلاً تا بیرون‌رفتن و برگشتن به تب، عدد قدیمی می‌ماند).
     */
    @Test
    fun `money management status box follows the broker after a mode change`() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = c.get()
        shadowOf(Looper.getMainLooper()).idle()
        val s = AppState.instance
        s.spreadFetcher = { null }            // آزمون هرمتیک: شبکهٔ واقعی دخالت نکند
        waitUntilIdle(s)
        val prev = s.cfg.mmMode
        val prevEq = s.cfg.initialEquity; val prevAuto = s.cfg.minLotAuto; val prevMan = s.cfg.minLotManual
        try {
            s.cfg.mmMode = MoneyMode.NONE
            s.cfg.initialEquity = 10_000.0; s.cfg.minLotAuto = false; s.cfg.minLotManual = 10.0
            s.chartTfSec = Tf.M1; s.cfg.tf2 = Tf.M1
            val rows = ArrayList<Candle>()
            java.util.zip.GZIPInputStream(java.io.File("src/main/assets/sample_xauusd_m1.csv.gz").let { if (it.exists()) it else java.io.File("../app/src/main/assets/sample_xauusd_m1.csv.gz") }.inputStream())
                .bufferedReader().useLines { ls -> ls.forEach { ln ->
                    val p = ln.split(",")
                    if (rows.size < 12_000 && p.size >= 6) runCatching {      // سطر سرستون را رد می‌کند
                        rows.add(Candle(rows.size, p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble()))
                    }
                } }
            s.setCandles(rows)
            val m: Method = act.javaClass.getDeclaredMethod("showTab", Int::class.javaPrimitiveType)
            m.isAccessible = true
            m.invoke(act, 4)
            shadowOf(Looper.getMainLooper()).idle()
            val root = act.window.decorView.findViewById<ViewGroup>(android.R.id.content)

            texts(root).filterIsInstance<Button>().first { it.text.toString().contains("مود: ManageAllLost") }.performClick()
            waitUntilIdle(s)                                           // منتظر پایان بازسازی پس‌زمینه
            assertEquals(MoneyMode.MANAGE_ALL_LOST, s.cfg.mmMode)
            assertTrue("پس از بازسازی باید Lost مثبت باشد (نمونهٔ ۱۲ هزارکندلی)", s.broker.mm.lost > 0.0)

            val box = root.findViewWithTag<ViewGroup>("mm_status_box")
            assertNotNull("کادر وضعیت باید در تنظیمات باشد", box)
            val shown = texts(box).map { it.text.toString() }
            assertTrue("Lost کادر باید با کارگزار یکی باشد: $shown vs ${Fa.n(s.broker.mm.lost, 2)}",
                shown.contains(Fa.n(s.broker.mm.lost, 2)))
            assertTrue("Capital کادر باید با کارگزار یکی باشد: $shown",
                shown.contains(Fa.n(s.broker.balance, 2)))
        } finally {
            s.cfg.mmMode = prev
            s.cfg.initialEquity = prevEq; s.cfg.minLotAuto = prevAuto; s.cfg.minLotManual = prevMan
            c.pause().stop().destroy()
        }
    }

    private fun sampleRows(n: Int): List<Candle> {
        val rows = ArrayList<Candle>()
        java.util.zip.GZIPInputStream(java.io.File("src/main/assets/sample_xauusd_m1.csv.gz").let { if (it.exists()) it else java.io.File("../app/src/main/assets/sample_xauusd_m1.csv.gz") }.inputStream())
            .bufferedReader().useLines { ls -> ls.forEach { ln ->
                val p = ln.split(",")
                if (rows.size < n && p.size >= 6) runCatching {
                    rows.add(Candle(rows.size, p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble()))
                }
            } }
        return rows
    }

    private fun parentOf(v: View): ViewGroup = v.parent as ViewGroup

    /**
     * گزارش کاربر: «با بیشتر کردن MinLot اعداد تغییر نمی‌کردند». علت: «MinLot دستی» با «خودکار»ِ روشن نادیده گرفته می‌شد.
     * حالا هر مفهوم یک ردیف دارد: خودکار روشن ⇒ فیلد دستی اصلاً نیست؛ خاموش‌کردن ⇒ عددِ مؤثر وارد فیلد می‌شود و تغییرش اعداد را عوض می‌کند.
     */
    @Test
    fun `MinLot has a single control and raising it really changes the lots`() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = c.get()
        shadowOf(Looper.getMainLooper()).idle()
        val s = AppState.instance
        s.spreadFetcher = { null }
        waitUntilIdle(s)
        val prev = s.cfg.copy()
        try {
            s.cfg.mmMode = MoneyMode.MANAGE_ALL_LOST
            s.cfg.initialEquity = 10_000.0; s.cfg.minLotAuto = true; s.cfg.minLotManual = 1.0
            s.cfg.riskPct = 1.0; s.cfg.maxLeverage = 500.0; s.cfg.lotStep = 0.01; s.cfg.roundQty = false
            s.cfg.cmsnAuto = false; s.cfg.cmsnManual = 0.0
            s.chartTfSec = Tf.M1; s.cfg.tf2 = Tf.M1
            s.setCandles(sampleRows(12_000))
            val firstAuto = s.broker.orders.first().qty
            assertEquals("MinLot خودکار = ۱۰٬۰۰۰ × ۱ ÷ ۱۰۰", 100.0, firstAuto, 1e-9)

            val m: Method = act.javaClass.getDeclaredMethod("showTab", Int::class.javaPrimitiveType)
            m.isAccessible = true
            m.invoke(act, 4)
            shadowOf(Looper.getMainLooper()).idle()
            val root = act.window.decorView.findViewById<ViewGroup>(android.R.id.content)

            assertTrue("با خودکارِ روشن فیلد عددِ دستی نباید وجود داشته باشد",
                texts(root).none { it.text.toString().contains("MinLot (دستی") })
            assertTrue("عددِ مؤثر نمایش داده می‌شود", texts(root).any { it.text.toString().contains("MinLot مؤثر") })

            val label = texts(root).first { it.text.toString().contains("MinLot — خودکار") }
            val sw = (0 until parentOf(label).childCount).map { parentOf(label).getChildAt(it) }.filterIsInstance<android.widget.Switch>().first()
            sw.performClick()                                   // خودکار را خاموش می‌کند
            waitUntilIdle(s)
            assertFalse(s.cfg.minLotAuto)
            assertEquals("عددِ مؤثر، نقطهٔ شروعِ دستی می‌شود (اعداد نمی‌پرند)", 100.0, s.cfg.minLotManual, 1e-9)

            val root2 = act.window.decorView.findViewById<ViewGroup>(android.R.id.content)
            val manualLabel = texts(root2).first { it.text.toString().contains("MinLot (دستی") }
            val row = parentOf(manualLabel)
            val edit = (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<android.widget.EditText>().first()
            val ok = (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<Button>().first { it.text.toString() == "ثبت" }
            assertEquals("فیلد با عددِ مؤثر پر شده", "100", edit.text.toString())
            edit.setText("20")
            ok.performClick()
            waitUntilIdle(s)
            assertEquals(20.0, s.cfg.minLotManual, 1e-9)
            assertEquals("با MinLot دستیِ تازه، حجمِ اولین سفارش عوض می‌شود", 20.0, s.broker.orders.first().qty, 1e-9)
            assertTrue(s.broker.orders.all { it.qty >= 20.0 })
        } finally {
            val d = s.cfg
            d.mmMode = prev.mmMode; d.initialEquity = prev.initialEquity; d.minLotAuto = prev.minLotAuto; d.minLotManual = prev.minLotManual
            d.cmsnAuto = prev.cmsnAuto; d.cmsnManual = prev.cmsnManual
            d.riskPct = prev.riskPct; d.maxLeverage = prev.maxLeverage; d.lotStep = prev.lotStep; d.roundQty = prev.roundQty
            c.pause().stop().destroy()
        }
    }

    /** دکمهٔ گردِ بارگذاری مجدد در هدر هست و بازمحاسبه از صفر دقیقاً همان نتیجه را می‌دهد (حتی اگر وضعیت کارگزار خراب شده باشد). */
    @Test
    fun `round reload button exists and recomputing everything from scratch gives the same numbers`() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = c.get()
        shadowOf(Looper.getMainLooper()).idle()
        val s = AppState.instance
        s.spreadFetcher = { null }
        waitUntilIdle(s)
        val prev = s.cfg.copy()
        try {
            val btn = act.window.decorView.findViewWithTag<View>("reload_all")
            assertNotNull("دکمهٔ گرد ↻ در هدر", btn)
            assertTrue("روی دکمه کلیک‌شنو وصل است", btn.hasOnClickListeners())
            assertEquals("↻", (btn as TextView).text.toString())

            s.cfg.mmMode = MoneyMode.MANAGE_ALL_LOST
            s.cfg.initialEquity = 10_000.0; s.cfg.minLotAuto = false; s.cfg.minLotManual = 10.0
            s.cfg.cmsnAuto = false; s.cfg.cmsnManual = 0.3
            s.chartTfSec = Tf.M1; s.cfg.tf2 = Tf.M1
            s.setCandles(sampleRows(12_000))
            fun snap() = "orders=${s.broker.orders.size} trades=${s.broker.trades.size} bal=${"%.4f".format(s.broker.balance)} lost=${"%.4f".format(s.broker.mm.lost)} ids=${s.broker.trades.take(3).map { it.id }}"
            val clean = snap()
            assertTrue(s.broker.trades.isNotEmpty())
            s.broker.mm.lost = 9_999.0; s.broker.balance = 1.0           // خراب‌کاری عمدی
            s.reloadAll(download = false)
            waitUntilIdle(s)
            assertEquals("بارگذاری مجدد باید همه‌چیز را از صفر و یکسان بسازد", clean, snap())
        } finally {
            val d = s.cfg
            d.mmMode = prev.mmMode; d.initialEquity = prev.initialEquity; d.minLotAuto = prev.minLotAuto; d.minLotManual = prev.minLotManual
            d.cmsnAuto = prev.cmsnAuto; d.cmsnManual = prev.cmsnManual
            c.pause().stop().destroy()
        }
    }
}
