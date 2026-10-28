package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ═══════════════════════════════════════════════════════════════════════════════
//  آزمون سواپ شبانه (۱٫۵٫۰۳)
//
//  قاعده‌هایی که این آزمون قفل می‌کند:
//   ۱) هر شبِ رول‌اور یک سواپ روی حجمِ باقی‌مانده می‌دهد (علامت‌دار).
//   ۲) شب چهارشنبه سه‌برابر است.
//   ۳) شنبه و یکشنبه شب سواپ ندارند (بازار بسته) و در سه‌برابرِ چهارشنبه حساب شده‌اند.
//   ۴) خاموش‌کردن `swapEnabled` هیچ سواپی نمی‌دهد.
//   ۵) سواپ در `pnl()` و در نتیجه در موجودی/افت سرمایه دیده می‌شود.
// ═══════════════════════════════════════════════════════════════════════════════

class SwapTest {

    private val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    private fun ms(s: String): Long = f.parse(s).time

    private fun cfg(swapL: Double = -1.0, swapS: Double = -2.0, on: Boolean = true) = Settings().apply {
        swapEnabled = on
        swapLong = swapL
        swapShort = swapS
        swapTripleDow = 3          // چهارشنبه
        swapRollHourUtc = 21       // ۲۱:۰۰ UTC
        contractSize = 1.0
        initialEquity = 100_000.0
    }

    /** یک معاملهٔ باز که هر ۶ ساعت یک کندل می‌بیند؛ هیچ حد ضرر/حد سودی لمس نمی‌شود. */
    private fun run(c: Settings, dir: Int, from: String, bars: Int): Trade {
        val entry = ms(from)
        val b = PaperBroker(c)
        val t = Trade(
            id = 1, orderId = 1, setupId = 1, dir = dir, entryT = entry, entryBi = 0,
            entry = 100.0,
            sl0 = if (dir == 1) 90.0 else 110.0,
            tp1 = Double.NaN, tp2 = Double.NaN,
            tpX = if (dir == 1) 10_000.0 else -10_000.0,
            qty = 1.0, zoneTop = 100.0, zoneBot = 90.0, contractSize = 1.0
        )
        b.trades.add(t); b.opens.add(t)
        var tt = entry
        repeat(bars) {
            b.onBar(Candle(0, tt, 100.0, 100.2, 99.8, 100.0, 1.0))
            tt += 6 * 3_600_000L
        }
        return t
    }

    @Test
    fun `یک هفته کامل — پنج‌شنبه تا دوشنبه، چهارشنبه سه‌برابر`() {
        // سه‌شنبه ۲۰۲۶-۱۰-۲۷ ۰۶:۲۰ تا سه‌شنبه بعد (۳۰ کندل ۶ ساعته ≈ ۷٫۵ روز)
        val t = run(cfg(), 1, "2026-10-27 06:20", 30)
        // رول‌اورها: سه‌شنبه ۱ · چهارشنبه ۳ · پنج‌شنبه ۱ · جمعه ۱ · (شنبه/یکشنبه ۰) · دوشنبه ۱
        assertEquals(7, t.swapNights, "شب‌های سواپ")
        assertEquals(-7.0, t.swap, 1e-9, "۷ شب × ۱ دلار")
        assertTrue(t.pnl() < t.grossPnl(), "سواپ باید از سود کم کند")
    }

    @Test
    fun `سواپ فروش با نرخ فروش حساب می‌شود`() {
        val t = run(cfg(swapL = -1.0, swapS = -2.0), -1, "2026-10-27 06:20", 30)
        assertEquals(7, t.swapNights)
        assertEquals(-14.0, t.swap, 1e-9)
    }

    @Test
    fun `خاموش‌کردن سواپ هیچ هزینه‌ای نمی‌دهد`() {
        val t = run(cfg(on = false), 1, "2026-10-27 06:20", 30)
        assertEquals(0, t.swapNights)
        assertEquals(0.0, t.swap, 1e-12)
    }

    @Test
    fun `سواپ مثبت (اعتبار) هم پشتیبانی می‌شود`() {
        // سواپ خرید نفت در آلپاری +۰٫۰۳۴۳۲ دلار بر بشکه در شب است
        val t = run(cfg(swapL = 0.03432, swapS = -0.0758), 1, "2026-10-27 06:20", 30)
        assertEquals(7, t.swapNights)
        assertTrue(t.swap > 0.0, "سواپ خرید نفت باید مثبت باشد، بود: ${t.swap}")
        assertEquals(7 * 0.03432, t.swap, 1e-9)
    }

    @Test
    fun `معاملهٔ کوتاه‌تر از یک شب سواپ نمی‌دهد`() {
        // ۰۶:۲۰ تا ۱۸:۲۰ همان روز — پیش از رول‌اور ۲۱:۰۰
        val t = run(cfg(), 1, "2026-10-27 06:20", 3)
        assertEquals(0, t.swapNights)
        assertEquals(0.0, t.swap, 1e-12)
    }

    @Test
    fun `حجم باقی‌مانده مبنای سواپ است`() {
        val c = cfg()
        val b = PaperBroker(c)
        val entry = ms("2026-10-27 06:20")
        val t = Trade(
            id = 1, orderId = 1, setupId = 1, dir = 1, entryT = entry, entryBi = 0,
            entry = 100.0, sl0 = 90.0, tp1 = Double.NaN, tp2 = Double.NaN, tpX = 10_000.0,
            qty = 2.0, zoneTop = 100.0, zoneBot = 90.0, contractSize = 1.0
        )
        // نصف حجم قبلاً بسته شده است
        t.fills.add(Fill(entry, 0, 101.0, 1.0, "TP1", 1.0))
        b.trades.add(t); b.opens.add(t)
        var tt = entry
        repeat(30) { b.onBar(Candle(0, tt, 100.0, 100.2, 99.8, 100.0, 1.0)); tt += 6 * 3_600_000L }
        assertEquals(7, t.swapNights)
        assertEquals(-7.0, t.swap, 1e-9, "فقط ۱ واحد باقی‌مانده سواپ می‌دهد (نه ۲)")
    }

    @Test
    fun `سواپ در پروفایل آلپاری برای هر دو نماد ثبت شده است`() {
        val o = Brokers.ALPARI_PRO_ECN_MT5.symbol(Symbols.OIL.id)!!
        val g = Brokers.ALPARI_PRO_ECN_MT5.symbol(Symbols.GOLD.id)!!
        assertEquals(-0.0758, o.swapShort!!, 1e-9)
        assertEquals(-0.6367, g.swapLong!!, 1e-9)
        // اعمال پروفایل بروکر، سواپ را هم روی تنظیمات می‌نشاند
        val s = Settings()
        Symbols.applyBrokerCost(s, Symbols.OIL, Brokers.ALPARI_PRO_ECN_MT5, 101.2)
        assertEquals(-0.0758, s.swapShort, 1e-9)
        assertEquals(0.03432, s.swapLong, 1e-9)
        assertTrue(s.swapEnabled)
        assertEquals(3, s.swapTripleDow)
        assertEquals(21, s.swapRollHourUtc)
    }
}
