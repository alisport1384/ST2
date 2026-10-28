package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ═══════════════════════════════════════════════════════════════════════════════
//  آزمون کارمزد بروکر (۱٫۵٫۰۲)
//
//  منبع عددهای مرجع: جدول رسمی Contract Specifications آلپاری (اعمال ۲۰۲۶-۱۰-۱۴) —
//  همان عددهایی که در `docs/ALPARI-KARMOZD-VA-SPREAD.md` و `config/alpari-costs.json`
//  ثبت شده‌اند. این آزمون جلوی «عدد دستی جابه‌جا شود و کسی نفهمد» را می‌گیرد.
// ═══════════════════════════════════════════════════════════════════════════════

class BrokerCostTest {

    private val gold = Symbols.GOLD.id
    private val oil = Symbols.OIL.id

    /** قیمت‌های مرجع گزارش ۲۰۲۶-۱۰-۲۸: طلا ۴٬۰۶۱ · WTI ۱۰۱٫۲ */
    private val pxGold = 4061.0
    private val pxOil = 101.2

    private fun near(expect: Double, actual: Double?, tol: Double = 5e-6) {
        assertNotNull(actual, "مقدار نباید null باشد")
        assertTrue(kotlin.math.abs(expect - actual) <= tol,
            "انتظار $expect بود، محاسبه $actual شد (اختلاف ${expect - actual})")
    }

    @Test
    fun `پروفایل پیش‌فرض آلپاری Pro ECN است`() {
        assertEquals(Brokers.DEFAULT_ID, Brokers.default.id)
        assertEquals(Brokers.DEFAULT_ID, Brokers.byId(Brokers.DEFAULT_ID)?.id)
        assertEquals(5, Brokers.ALL.size)
        assertTrue(Brokers.ALL.all { it.id.startsWith("alpari-") })
        // بروکر ناشناس ⇒ حالت دستی
        assertNull(Brokers.byId("no-such-broker"))
        assertTrue(Brokers.isCustom("no-such-broker"))
        assertTrue(Brokers.isCustom(Brokers.CUSTOM_ID))
    }

    @Test
    fun `طلا — عددهای رسمی آلپاری`() {
        near(0.2370, Brokers.costPerUnit(Brokers.ALPARI_PRO_ECN_MT5, gold, pxGold), 1e-4)
        near(0.2370, Brokers.costPerUnit(Brokers.ALPARI_PRO_ECN_MT4, gold, pxGold), 1e-4)
        near(0.3190, Brokers.costPerUnit(Brokers.ALPARI_ECN_MT4, gold, pxGold), 1e-4)
        near(0.3590, Brokers.costPerUnit(Brokers.ALPARI_STANDARD_MT5, gold, pxGold), 1e-4)
        near(0.3590, Brokers.costPerUnit(Brokers.ALPARI_STANDARD_MT4, gold, pxGold), 1e-4)
        // درصد قیمت (همان ستون گزارش)
        near(0.005836, Brokers.ALPARI_PRO_ECN_MT5.symbol(gold)!!.costPct(pxGold), 1e-5)
    }

    @Test
    fun `نفت — عددهای رسمی آلپاری`() {
        near(0.01506, Brokers.costPerUnit(Brokers.ALPARI_PRO_ECN_MT5, oil, pxOil), 1e-5)
        near(0.03000, Brokers.costPerUnit(Brokers.ALPARI_ECN_MT4, oil, pxOil), 1e-5)
        near(0.06000, Brokers.costPerUnit(Brokers.ALPARI_STANDARD_MT5, oil, pxOil), 1e-5)
        near(0.07000, Brokers.costPerUnit(Brokers.ALPARI_STANDARD_MT4, oil, pxOil), 1e-5)
        // Pro ECN MT4: آلپاری اسپرد WTI را منتشر نکرده ⇒ عدد نباید ساخته شود
        assertNull(Brokers.costPerUnit(Brokers.ALPARI_PRO_ECN_MT4, oil, pxOil))
        assertNull(Brokers.ALPARI_PRO_ECN_MT4.symbol(oil)!!.spreadPerUnit)
    }

    @Test
    fun `بخش کمیسیون مستقل از اندازهٔ قرارداد است و با قیمت مقیاس می‌گیرد`() {
        val s = BrokerSymbol(
            symbolId = oil, ticker = "WTI", spreadPoints = 0.0, pointSize = 0.01,
            contractSize = 1000.0, commissionPerMillionPerSide = 25.0,
            swapLong = null, swapShort = null
        )
        val s2 = s.copy(contractSize = 10.0)
        near(s.commissionPerUnit(100.0), s2.commissionPerUnit(100.0))
        near(0.005, s.commissionPerUnit(100.0))          // ۲ × ۱۰۰ × ۲۵ ÷ ۱ میلیون
        near(0.010, s.commissionPerUnit(200.0))          // دو برابر قیمت ⇒ دو برابر کمیسیون
    }

    @Test
    fun `کارمزد نفت در آلپاری از فرض ۱٫۵٫۰۱ بسیار ارزان‌تر است`() {
        val proEcn = Brokers.costPerUnit(Brokers.ALPARI_PRO_ECN_MT5, oil, pxOil)!!
        val std = Brokers.costPerUnit(Brokers.ALPARI_STANDARD_MT4, oil, pxOil)!!
        assertTrue(proEcn < Symbols.DEFAULT_OIL_CMSN / 4.0, "Pro ECN باید حداقل ۴ برابر ارزان‌تر باشد")
        assertTrue(std < Symbols.DEFAULT_OIL_CMSN / 4.0, "Standard هم باید حداقل ۴ برابر ارزان‌تر باشد")
        // و نسبت هزینهٔ نفت به طلا دیگر ۱۸ برابر نیست
        val goldCost = Brokers.costPerUnit(Brokers.ALPARI_PRO_ECN_MT5, gold, pxGold)!!
        val ratioPct = (proEcn / pxOil) / (goldCost / pxGold)
        assertTrue(ratioPct < 5.0, "نسبت هزینهٔ درصدی نفت به طلا باید زیر ۵ باشد، بود: $ratioPct")
    }

    @Test
    fun `ست کردن بروکر فقط کارمزد را عوض می‌کند و پارامترها را دست نمی‌زند`() {
        val s = Settings()
        Symbols.applyOilDefaults(s, 0.30)
        val buf = s.slBufTicks
        val nMax = s.maxSlUnits
        val cost8 = s.minSlCostMult
        val k = s.trigger2TargetR
        val fade = s.trigger2Fade

        val applied = Symbols.applyBrokerCost(s, Symbols.OIL, Brokers.ALPARI_PRO_ECN_MT5, pxOil)
        assertNotNull(applied)
        near(0.01506, applied, 1e-5)
        near(0.01506, s.cmsnManual, 1e-5)
        assertEquals(Brokers.DEFAULT_ID, s.brokerId)
        near(pxOil, s.brokerRefPrice)
        assertTrue(s.brokerAppliedAt > 0L)
        assertTrue(!s.cmsnAuto)
        // پارامترهای استراتژی دست‌نخورده
        assertEquals(buf, s.slBufTicks)
        assertEquals(nMax, s.maxSlUnits)
        assertEquals(k, s.trigger2TargetR)
        assertEquals(fade, s.trigger2Fade)
        // ⚠ تنها عددی که عمداً عوض می‌شود: ضریب فیلتر کارمزد، تا **آستانهٔ مطلق** بماند.
        //   اندازه‌گیری ۲۰۲۲–۲۰۲۶: با کارمزد ۰٫۰۱۵ و ضریب ۸ (آستانهٔ ۰٫۱۲) ۲۷۸ معامله و
        //   سود ≈صفر شد؛ با آستانهٔ ثابت ۲٫۴ دلار همان مجموعهٔ کم‌تعدادِ سودده می‌ماند.
        assertNotEquals(cost8, s.minSlCostMult)
        near(2.4, s.minSlCostMult * s.cmsnManual, 1e-3)
    }

    @Test
    fun `پیش‌فرض نصب تازه با کارمزد بروکر ساخته می‌شود`() {
        val s = Settings()
        val c = Symbols.applyDefaults(s, Symbols.OIL, pxOil)
        near(0.01506, c, 1e-5)
        near(0.01506, s.cmsnManual, 1e-5)
        assertEquals(200.0, s.slBufTicks)
        assertEquals(8.0, s.maxSlUnits)
        assertEquals(0.70, s.minAtrPct)
        // آستانهٔ فیلتر کارمزد همان ۲٫۴ دلار آزموده‌شده می‌ماند، هرچند بروکر ارزان‌تر است
        near(Symbols.OIL_COST_FLOOR_UNITS, s.minSlCostMult * s.cmsnManual, 1e-3)
        assertTrue(s.minSlCostMult > 100.0)   // ۲٫۴ ÷ ۰٫۰۱۵۰۶

        val g = Settings()
        val cg = Symbols.applyDefaults(g, Symbols.GOLD, pxGold)
        near(0.2370, cg, 1e-4)
        near(0.2370, g.cmsnManual, 1e-4)
        assertNotEquals(0.0, g.maxSlUnits)
    }

    @Test
    fun `بروکری که اسپرد منتشر نکرده کارمزد را صفر نمی‌کند`() {
        val s = Settings()
        Symbols.applyOilDefaults(s, 0.03)
        val before = s.cmsnManual
        val r = Symbols.applyBrokerCost(s, Symbols.OIL, Brokers.ALPARI_PRO_ECN_MT4, pxOil)
        assertNull(r)
        near(before, s.cmsnManual)          // دست‌نخورده می‌ماند
        assertNotEquals(Brokers.ALPARI_PRO_ECN_MT4.id, s.brokerId)
    }

    @Test
    fun `سواپ شبانه ثبت شده و برای نفت از کمیسیون بزرگ‌تر است`() {
        val o = Brokers.ALPARI_PRO_ECN_MT5.symbol(oil)!!
        assertEquals(-0.07580, o.swapShort!!, 1e-6)   // دلار بر بشکه در شب (فروش)
        assertEquals(0.03432, o.swapLong!!, 1e-6)
        val cost = o.costPerUnit(pxOil)!!
        assertTrue(kotlin.math.abs(o.swapShort!!) > cost, "سواپ شبانهٔ فروش نفت باید از کل کارمزد بزرگ‌تر باشد")
        val g = Brokers.ALPARI_PRO_ECN_MT5.symbol(gold)!!
        assertEquals(-0.6367, g.swapLong!!, 1e-6)
        assertEquals(0.4101, g.swapShort!!, 1e-6)
    }

    @Test
    fun `ذخیره و بازیابی شناسهٔ بروکر`() {
        val s = Settings()
        Symbols.applyBrokerCost(s, Symbols.OIL, Brokers.ALPARI_STANDARD_MT5, pxOil)
        val back = Store.settingsFromJson(Store.settingsJson(s))
        assertEquals(Brokers.ALPARI_STANDARD_MT5.id, back.brokerId)
        near(0.06, back.cmsnManual, 1e-6)
        near(pxOil, back.brokerRefPrice)
        assertTrue(back.brokerAppliedAt > 0L)
    }
}
