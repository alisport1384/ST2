package com.alisport.goldpin

import com.alisport.goldpin.core.Candle
import com.alisport.goldpin.core.MoneyMode
import com.alisport.goldpin.core.Tf
import com.alisport.goldpin.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.zip.GZIPInputStream

/**
 * کارگزار کاغذی تابعی قطعی از (کندل‌ها + تنظیمات) است: هر چند بار `rebuild()` بزنیم باید دقیقاً همان
 * سفارش‌ها، معاملات، موجودی، Lost و cmsnLost بیرون بیاید.
 *
 * (باگ قدیمی: مسیر دانلود با تایم‌فریم چارت ≠ مود، موتور را از صفر بازپخش می‌کرد ولی کارگزار را پاک نمی‌کرد
 *  ⇒ سفارش/معامله/موجودی دوبرابر و Lost با ارزش اولیهٔ غلط شروع می‌شد ⇒ «هر بار یک جور حساب می‌کند».)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RebuildIdempotenceTest {

    private fun sample(n: Int): List<Candle> {
        val rows = ArrayList<DoubleArray>()
        val f = java.io.File("src/main/assets/sample_xauusd_m1.csv.gz").let { if (it.exists()) it else java.io.File("../app/src/main/assets/sample_xauusd_m1.csv.gz") }
        GZIPInputStream(f.inputStream()).bufferedReader().useLines { ls -> ls.forEach { ln ->
            val p = ln.split(",")
            if (p.size >= 6) runCatching {
                rows.add(doubleArrayOf(p[0].toDouble(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble()))
            }
        } }
        return rows.take(n).mapIndexed { i, r -> Candle(i, r[0].toLong(), r[1], r[2], r[3], r[4], r[5]) }
    }

    private fun snapshot(s: AppState): String {
        val b = s.broker
        val trades = b.trades.joinToString(";") {
            "${it.id}|${it.dir}|${it.entryBi}|${it.exitBi}|${"%.3f".format(it.qty)}|${"%.4f".format(it.pnl())}|${"%.4f".format(it.commission)}"
        }
        return "orders=${b.orders.size} trades=${b.trades.size} balance=${"%.4f".format(b.balance)} " +
            "lost=${"%.4f".format(b.mm.lost)} cmsnLost=${"%.4f".format(b.mm.cmsnLost)} [$trades]"
    }

    @Test
    fun `rebuilding again and again gives identical results in every money mode`() {
        Log.init(RuntimeEnvironment.getApplication(), "test")
        val candles = sample(12_000)
        for (mode in listOf(MoneyMode.NONE, MoneyMode.MANAGE_ALL_LOST, MoneyMode.MIXED)) {
            val s = AppState()
            s.chartTfSec = Tf.M1; s.cfg.tf2 = Tf.M1
            s.cfg.mmMode = mode
            s.cfg.initialEquity = 10_000.0
            s.cfg.minLotAuto = false; s.cfg.minLotManual = 10.0
            s.cfg.cmsnAuto = false; s.cfg.cmsnManual = 0.3
            s.cfg.mixedGrowthPct = 0.5
            s.setCandles(candles)
            val first = snapshot(s)
            assertTrue("در مود $mode باید معامله‌ای رخ دهد", s.broker.trades.isNotEmpty())
            s.rebuild(); assertEquals("مود $mode — بازسازی دوم", first, snapshot(s))
            s.rebuild(); assertEquals("مود $mode — بازسازی سوم", first, snapshot(s))
        }
    }

    @Test
    fun `rebuild always starts the broker from scratch even when it already holds state`() {
        Log.init(RuntimeEnvironment.getApplication(), "test")
        val s = AppState()
        s.chartTfSec = Tf.M1; s.cfg.tf2 = Tf.M1
        s.cfg.mmMode = MoneyMode.MANAGE_ALL_LOST
        s.cfg.initialEquity = 10_000.0; s.cfg.minLotAuto = false; s.cfg.minLotManual = 10.0
        s.setCandles(sample(12_000))
        val clean = snapshot(s)
        // کارگزار «کثیف»: Lost و موجودی غیرصفر و یک معامله/سفارش قدیمی
        s.broker.mm.lost = 5_000.0; s.broker.mm.cmsnLost = 77.0; s.broker.balance = 123.0
        s.rebuild()
        assertEquals("بازسازی باید وضعیت قبلی کارگزار را کاملاً دور بریزد", clean, snapshot(s))
    }
}
