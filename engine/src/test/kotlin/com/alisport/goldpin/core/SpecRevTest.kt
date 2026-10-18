package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** هم‌ترازی با سند: §۵ اهرم ۵۰۰ ، §۴-۸ بافر ۱ تیک ، §۱۸-۱۹ ستاپ ۱ ، §۲۰-۲ دو برابر شدن NakedCapital ، فلات در قله. */
class SpecRevTest {
    @Test
    fun `document defaults`() {
        val c = Settings()
        assertEquals(500.0, c.maxLeverage); assertEquals(1.0, c.entryBufTicks); assertEquals(1, c.maxSetups)
    }

    @Test
    fun `NakedCapital doubles in Mixed when capital reaches twice of it`() {
        val c = Settings().apply { mmMode = MoneyMode.MIXED; initialEquity = 1000.0 }
        val mm = MoneyManager(c)
        mm.onCapitalUpdated(1999.0); assertEquals(1000.0, mm.nakedCapital())
        mm.onCapitalUpdated(2000.0); assertEquals(2000.0, mm.nakedCapital())
        mm.onCapitalUpdated(8000.0); assertEquals(4000.0 * 2, mm.nakedCapital())
        mm.reset(); assertEquals(1000.0, mm.nakedCapital())
        c.mmMode = MoneyMode.MANAGE_ALL_LOST
        mm.onCapitalUpdated(5000.0); assertEquals(1000.0, mm.nakedCapital())
    }

    @Test
    fun `old save files migrate to document defaults`() {
        val c = Settings().apply { maxLeverage = 10.0; entryBufTicks = 2.0; maxSetups = 6 }
        val e = Engine(c); val b = PaperBroker(c); e.broker = b; b.engine = e
        val j = Store.save(c, e, b, listOf(Candle(0, 0L, 1.0, 2.0, 0.5, 1.5, 10.0)))
        val old = j.replace("\"specRev\"", "\"zz_specRev\"")
        val l = Store.load(old).cfg
        assertEquals(500.0, l.maxLeverage); assertEquals(1.0, l.entryBufTicks); assertEquals(1, l.maxSetups)
        assertEquals(10.0, Store.load(j).cfg.maxLeverage)
    }

    @Test
    fun `plateau peak at the table edge still builds a zone`() {
        val rv = doubleArrayOf(50.0, 50.0, 40.0, 30.0, 35.0, 20.0, 10.0)
        val p = Profile(rv, 1000.0, 10.0, Double.NaN, Double.NaN, Double.NaN)
        val z = VolumeProfile.zones(p, 7, bull = true, cClose = 2000.0, minRows = 1)
        assertEquals(1, z.size); assertEquals(1, z[0].loIdx); assertEquals(3, z[0].hiIdx)
    }
}
