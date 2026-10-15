package com.alisport.goldpin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.alisport.goldpin.core.Candle
import com.alisport.goldpin.core.Fill
import com.alisport.goldpin.core.Trade
import com.alisport.goldpin.ui.ChartView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** درخواست کاربر: گزینهٔ اختیاری (پیش‌فرض خاموش) برای نشان‌دادن نتیجهٔ معاملات روی چارت. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class TradeResultsOverlayTest {

    private fun candles() = List(160) { i ->
        Candle(i, 1_700_000_000_000L + i * 60_000L, 2000.0 + (i % 7) * 0.1, 2012.0, 1988.0, 2000.0, 50.0)
    }

    private fun trade(id: Long, dir: Int, entryBi: Int, exitBi: Int?, entry: Double, exit: Double?, commission: Double, partial: Pair<Double, Double>? = null): Trade {
        val t = Trade(id, id, id, dir, 1_700_000_000_000L + entryBi * 60_000L, entryBi, entry, entry - 5.0 * dir, entry + 10.0 * dir,
            entry + 20.0 * dir, entry + 30.0 * dir, 10.0, 0.0, 0.0, 1.0)
        t.fills.add(Fill(t.entryT, entryBi, entry, 10.0, "ENTRY", 0.0))
        if (partial != null) t.fills.add(Fill(t.entryT, entryBi, partial.first, partial.second, "TP1", (partial.first - entry) * dir * partial.second))
        if (exit != null && exitBi != null) {
            val used = partial?.second ?: 0.0
            t.fills.add(Fill(1_700_000_000_000L + exitBi * 60_000L, exitBi, exit, 10.0 - used, "X", (exit - entry) * dir * (10.0 - used)))
            t.exitT = 1_700_000_000_000L + exitBi * 60_000L; t.exitBi = exitBi; t.exitPx = exit
        }
        t.commission = commission
        return t
    }

    /** Robolectric (حالت legacy) پیکسل نمی‌کشد؛ پس خودِ فراخوانی‌های رسم را (رنگ، خط‌چین بودن، متن) ضبط می‌کنیم. */
    private class Rec(bmp: Bitmap) : Canvas(bmp) {
        class Line(val color: Int, val dashed: Boolean)
        val lines = ArrayList<Line>()
        val texts = ArrayList<String>()
        override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) {
            lines.add(Line(paint.color, paint.pathEffect != null))
        }
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) { texts.add(text) }
    }

    private fun render(cv: ChartView): Rec {
        cv.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.EXACTLY))
        cv.layout(0, 0, 1080, 1500)
        val rec = Rec(Bitmap.createBitmap(1080, 1500, Bitmap.Config.ARGB_8888))
        cv.draw(rec)
        return rec
    }

    @Test
    fun `trade results are off by default and draw green red and grey marks when switched on`() {
        val ctx = RuntimeEnvironment.getApplication()
        assertFalse("پیش‌فرض خاموش", ChartView(ctx).showTradeResults)
        assertFalse("پیش‌فرض خاموش در وضعیت اپ", AppState().showTradeResults)

        val cs = candles()
        // همه در ~۶۰ کندل آخر (پنجرهٔ پیش‌فرضِ چارت) و داخل بازهٔ قیمت کندل‌ها
        val win = trade(1, 1, 102, 122, 1992.0, 2008.0, commission = 5.0)               // سود
        val loss = trade(2, 1, 126, 136, 2008.0, 1994.0, commission = 5.0)              // ضرر
        val be = trade(3, -1, 140, 148, 2000.0, 2000.0, commission = 5.0)               // سر‌به‌سر (کمیسیون‌دار)
        val open = trade(4, 1, 151, null, 2001.0, null, commission = 0.0)               // باز
        fun chart(on: Boolean) = ChartView(ctx).apply {
            candles = cs; trades = listOf(win, loss, be, open)
            showMarkers = false; showZones = false; showTradeLines = false; showGrid = false; showVolume = false
            showWatermark = false; showLegend = false; showStatusBadges = false; showLastPriceLine = false
            showTradeResults = on
        }
        val resWin = Color.parseColor("#2ECC71"); val resLoss = Color.parseColor("#FF4D4F"); val resBe = Color.parseColor("#B0BAC9")
        val off = render(chart(false))
        val on = render(chart(true))
        for (c in listOf(resWin, resLoss, resBe))
            assertTrue("خاموش ⇒ هیچ خطِ نتیجه‌ای کشیده نمی‌شود", off.lines.none { it.color == c })
        assertTrue("خاموش ⇒ برچسب نتیجه‌ای نیست", off.texts.none { it.startsWith("BE") || it.startsWith("+") || it.startsWith("−") || it.startsWith("باز") })

        assertTrue("سود ⇒ خط سبز توپر", on.lines.any { it.color == resWin && !it.dashed })
        assertTrue("ضرر ⇒ خط قرمز توپر", on.lines.any { it.color == resLoss && !it.dashed })
        assertTrue("سر‌به‌سر ⇒ خط‌چین خاکستری", on.lines.any { it.color == resBe && it.dashed })
        assertTrue("معاملهٔ باز ⇒ خط‌چین (رنگ تأکید)", on.lines.any { it.color == com.alisport.goldpin.util.Palette.accent && it.dashed })
        assertTrue("برچسب سود با علامت +: ${on.texts}", on.texts.any { it.startsWith("+") })
        assertTrue("برچسب ضرر با علامت −: ${on.texts}", on.texts.any { it.startsWith("−") })
        assertTrue("برچسب سر‌به‌سر: ${on.texts}", on.texts.any { it.startsWith("BE") })
        assertTrue("برچسب معاملهٔ باز: ${on.texts}", on.texts.any { it.startsWith("باز") })
        // مبلغ برچسب = سود/زیان «خالص» (با کمیسیون): سود ۱۶۰−۵ = ۱۵۵ ، ضرر −۱۴۰−۵ = −۱۴۵
        assertEquals(155.0, win.pnl(), 1e-9); assertEquals(-145.0, loss.pnl(), 1e-9)
        assertTrue(on.texts.any { it == com.alisport.goldpin.util.Fa.signed(155.0) })
        assertTrue(on.texts.any { it == com.alisport.goldpin.util.Fa.signed(-145.0) })
        // رنگ هر معامله از نتیجهٔ قیمتی می‌آید، نه از کمیسیون: سر‌به‌سرِ کمیسیون‌دار قرمز نمی‌شود
        assertEquals(1, win.outcome()); assertEquals(-1, loss.outcome()); assertEquals(0, be.outcome())
        assertTrue(be.pnl() < 0.0)
    }

    @Test
    fun `no trades or no candles does not crash the overlay`() {
        val ctx = RuntimeEnvironment.getApplication()
        render(ChartView(ctx).apply { showTradeResults = true })
        render(ChartView(ctx).apply { candles = candles(); showTradeResults = true })
        render(ChartView(ctx).apply { trades = listOf(trade(1, 1, 5, 9, 2000.0, 2005.0, 0.0)); showTradeResults = true })
    }
}
