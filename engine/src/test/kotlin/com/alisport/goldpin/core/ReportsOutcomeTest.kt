package com.alisport.goldpin.core

import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * شمارش TP / SL / سر‌به‌سر باید نتیجهٔ «قیمتیِ» معاملات باشد، نه تابعِ کمیسیون یا مود مالی.
 * (باگ قبلی: win = pnl ≥ 0 ⇒ ۴۷ سر‌به‌سرِ صفر‌دلاری «برد» بودند و با روشن شدن کمیسیون یکباره «باخت» می‌شدند:
 *  ۵۵/۴۸ ⇒ ۱۲/۹۱ فقط با عوض کردن مود.)
 */
class ReportsOutcomeTest {
    private fun sample(): List<Candle> {
        val rows = ArrayList<DoubleArray>()
        GZIPInputStream(java.io.File("../app/src/main/assets/sample_xauusd_m1.csv.gz").inputStream())
            .bufferedReader().useLines { ls -> ls.forEach { ln ->
                val p = ln.split(",")
                if (p.size >= 6) runCatching {
                    rows.add(doubleArrayOf(p[0].toDouble(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble()))
                }
            } }
        return rows.mapIndexed { i, r -> Candle(i, r[0].toLong(), r[1], r[2], r[3], r[4], r[5]) }
    }

    private fun run(c: Settings, candles: List<Candle>): PaperBroker {
        val e = Engine(c); val b = PaperBroker(c)
        e.broker = b; b.engine = e
        e.barCommitHook = { cd -> b.onBar(cd, live = false) }
        e.feed(candles, lastIsClosed = true)
        return b
    }

    private fun trade(entry: Double, exits: List<Pair<Double, Double>>, commission: Double): Trade {
        val t = Trade(0, 0, 0, 1, 0L, 0, entry, entry - 5, entry + 10, entry + 20, entry + 30, 10.0, 0.0, 0.0, 1.0)
        for ((px, q) in exits) t.fills.add(Fill(1L, 1, px, q, "X", (px - entry) * q))
        t.exitT = 2L; t.exitPx = exits.last().first
        t.commission = commission
        return t
    }

    @Test
    fun `outcome is decided by the price result and ignores commission`() {
        val be = trade(2000.0, listOf(2000.0 to 10.0), commission = 5.0)           // سر‌به‌سر ولی کمیسیون دارد
        val beFree = trade(2000.0, listOf(2000.0 to 10.0), commission = 0.0)
        val tp1be = trade(2000.0, listOf(2010.0 to 3.3, 2000.0 to 6.7), commission = 5.0)   // TP1 و بعد سر‌به‌سر
        val sl = trade(2000.0, listOf(1995.0 to 10.0), commission = 5.0)
        val tpx = trade(2000.0, listOf(2030.0 to 10.0), commission = 5.0)
        assertEquals(0, be.outcome()); assertEquals(0, beFree.outcome())
        assertEquals(1, tp1be.outcome()); assertEquals(-1, sl.outcome()); assertEquals(1, tpx.outcome())
        assertTrue(be.pnl() < 0.0, "پولِ سر‌به‌سرِ کمیسیون‌دار منفی است، ولی نتیجهٔ قیمتی‌اش سر‌به‌سر می‌ماند")

        val sum = Reports.summary(listOf(be, beFree, tp1be, sl, tpx))
        assertEquals(2, sum.wins); assertEquals(1, sum.losses); assertEquals(2, sum.breakeven); assertEquals(5, sum.trades)
        val noCm = Reports.summary(listOf(be, beFree, tp1be, sl, tpx).onEach { it.commission = 0.0 })
        assertEquals(sum.wins, noCm.wins); assertEquals(sum.losses, noCm.losses); assertEquals(sum.breakeven, noCm.breakeven)
        // دوره‌ای هم همان
        val rows = Reports.group(listOf(be, beFree, tp1be, sl, tpx), Reports.DAILY)
        assertEquals(2, rows.sumOf { it.wins }); assertEquals(1, rows.sumOf { it.losses }); assertEquals(2, rows.sumOf { it.breakeven })
    }

    @Test
    fun `win loss and breakeven counts on the sample are identical in every money mode and commission`() {
        val candles = sample()
        var ref: Triple<Int, Int, Int>? = null
        for ((mode, cm) in listOf(MoneyMode.NONE to 0.0, MoneyMode.MANAGE_ALL_LOST to 0.0,
            MoneyMode.MANAGE_ALL_LOST to 0.3, MoneyMode.MIXED to 0.3)) {
            val c = Settings().apply { mmMode = mode; cmsnAuto = false; cmsnManual = cm }
            val b = run(c, candles)
            val sm = Reports.summary(b.trades)
            val got = Triple(sm.wins, sm.losses, sm.breakeven)
            if (ref == null) ref = got
            assertEquals(ref, got, "مود $mode با کمیسیون $cm: شمارش برد/باخت/سر‌به‌سر نباید عوض شود")
            assertEquals(sm.trades, sm.wins + sm.losses + sm.breakeven)
            // باخت‌ها دقیقاً همان معاملات «حد ضرر»اند
            assertEquals(b.closedTrades().count { it.reason == "حد ضرر" }, sm.losses)
        }
        assertTrue(ref!!.third > 0, "نمونه باید سر‌به‌سر داشته باشد (قاعدهٔ انتقال حدضرر به ورود)")
    }
}
