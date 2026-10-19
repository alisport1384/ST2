package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TradeStopSpecTest {

    @Test
    fun `doc 4-9 sell stop moves to oo on close below entry and closes at oo`() {
        val cfg = Settings().apply { maxSlUnits = 1_000_000.0 }
        val broker = PaperBroker(cfg)
        val t0 = 1_699_948_800_000L // سه‌شنبه 08:00 UTC، داخل پنجرهٔ سفارش‌گیری
        val setup = Setup(1L, -1, 5, 2_020.0, 2_000.0, 1, 0).apply {
            hvH = 2_010.0; hvL = 2_007.0
            sl = 2_010.02; tp1 = 1_990.0; tp2 = 1_980.0; tpx = 1_900.0
        }
        assertTrue(broker.registerLimit(setup, Candle(0, t0, 2_008.0, 2_009.0, 2_007.0, 2_008.0, 10.0)))
        val oo = broker.pendingOrders.single().price
        assertEquals(2_006.99, oo, 1e-9)

        // پرشدن سفارش فروش با لمس سطح oo؛ کندل ورود خودش مشمول انتقال نیست.
        broker.onBar(Candle(1, t0 + 60_000L, 2_008.0, 2_007.5, 2_006.5, 2_007.0, 10.0))
        val trade = broker.openTrades.single()
        assertTrue(trade.slAdj.isNaN())

        // سایه بالای oo است، اما کلوز پایین‌تر است؛ فقط کلوز مبنای انتقال است، نه سایه و نه باکس HV.
        broker.onBar(Candle(2, t0 + 120_000L, 2_006.0, 2_008.0, 2_004.0, 2_005.0, 10.0))
        assertEquals(oo, trade.slAdj, 1e-9)
        assertTrue(trade.open)

        // برگشت به oo حد ضرر را می‌زند و خروج سر‌به‌سرِ قیمتی ثبت می‌شود.
        broker.onBar(Candle(3, t0 + 180_000L, 2_005.0, 2_007.0, 2_004.0, 2_005.5, 10.0))
        assertFalse(trade.open)
        assertEquals("خروج سر‌به‌سر", trade.reason)
        assertEquals(0.0, trade.grossPnl(), 1e-9)
    }
}
