package com.alisport.goldpin.core

import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * §۲۰ سند — مدیریت مالی و مدیریت زیان (ManageAllLost / Mixed) + پر شدن سفارش با قیمت زنده (§۱/§۳/§۶).
 *
 * مثال‌های عددی همان‌هایی‌اند که در سند آمده‌اند. سند عددها را به ۲ رقم اعشار «کف» کرده
 * (۱۶٫۶۶۷ → ۱۶٫۶۶ ، ۳۴٫۰۷۸ → ۳۴٫۰۷)؛ پس گام حجم ۰٫۰۱ و تحمل مقایسه ۰٫۰۱ است.
 */
class Doc20Test {

    private fun near(expected: Double, actual: Double, tol: Double = 0.01, what: String = "") =
        assertTrue(abs(expected - actual) <= tol, "$what انتظار=$expected واقعی=$actual")

    /** تنظیمات تست: بافر سطح سفارش صفر ⇒ سطح سفارش دقیقاً hvH؛ همه‌چیز دستی و قابل پیش‌بینی. */
    private fun cfg(mode: Int, equity: Double): Settings = Settings().apply {
        mmMode = mode
        initialEquity = equity
        entryBufTicks = 0.0
        riskPct = 1.0
        maxLeverage = 500.0
        lotStep = 0.01
        cmsnAuto = false
        cmsnManual = 0.0
    }

    private class Rig(val cfg: Settings) {
        val b = PaperBroker(cfg)
        var bi = 100
        var nextSetup = 1L
        val alerts = ArrayList<String>()
        init { b.alertSink = { kind, _, _ -> alerts.add(kind) } }

        fun bar(o: Double, h: Double, l: Double, c: Double): Candle {
            val cd = Candle(bi, bi * 60_000L, o, h, l, c, 100.0)
            bi++
            return cd
        }

        /** سفارش خرید با ورود/SL/حدسود نهایی دلخواه؛ TP1/TP2 ندارد (کل حجم در حدسود نهایی). */
        fun armBuy(entry: Double, sl: Double, tpx: Double): Pair<Setup, Order> {
            val s = Setup(nextSetup++, 1, 6, entry + 5, entry - 5, 0, 0)
            s.hvH = entry; s.hvL = entry - 1.0       // سطح سفارش = hvH (بافر ۰)
            s.sl = sl; s.tpx = tpx
            val arm = bar(entry + 2, entry + 3, entry + 1, entry + 2)
            assertTrue(b.registerLimit(s, arm), "ثبت سفارش رد شد: ${b.lastError}")
            return s to b.pendingOrder!!
        }

        /** کندل بعدی: ورود لمس می‌شود و (بسته به low/high) در همان کندل SL یا حدسود نهایی می‌خورد. */
        fun fillAndHitSl(entry: Double, sl: Double) {
            b.onBar(bar(entry + 1, entry + 2, sl - 1.0, sl))
        }
        fun fillAndHitTp(entry: Double, tpx: Double) {
            b.onBar(bar(entry + 1, tpx + 1.0, entry - 0.5, tpx))
        }
    }

    // ═══ §۲۰-۱ ManageAllLost — مثال سند، سه معامله ═════════════════════════════
    @Test
    fun `ManageAllLost reproduces the three example trades of the doc`() {
        val r = Rig(cfg(MoneyMode.MANAGE_ALL_LOST, 10_000.0).apply { minLotAuto = false; minLotManual = 10.0 })
        // شرایط حاضر سند: capital = 9,900 ، lost = 100
        r.b.balance = 9_900.0; r.b.equity = 9_900.0; r.b.mm.lost = 100.0

        // معامله ۱ — SL: entry 2000 ، FinalTP 2015 ، SL 1995 ⇒ div = 15 ⇒ lot = 100/15 + 10 = 16.667 (سند: 16.66)
        val (_, o1) = r.armBuy(2000.0, 1995.0, 2015.0)
        near(16.66, o1.qty, what = "lot معامله ۱")
        assertTrue(o1.mmNote.contains("ManageAllLost"), "توضیح محاسبه باید در سفارش بماند: ${o1.mmNote}")
        r.fillAndHitSl(2000.0, 1995.0)
        near(9816.7, r.b.balance, what = "capital بعد از معامله ۱")
        near(183.3, r.b.mm.lost, what = "lost بعد از معامله ۱")

        // معامله ۲ — SL: entry 1900 ، FinalTP 1920 ، SL 1897 ⇒ div = 20 ⇒ lot = 183.3/20 + 10 = 19.165 (سند: 19.16)
        val (_, o2) = r.armBuy(1900.0, 1897.0, 1920.0)
        near(19.16, o2.qty, what = "lot معامله ۲")
        r.fillAndHitSl(1900.0, 1897.0)
        near(240.78, r.b.mm.lost, what = "lost بعد از معامله ۲")
        // ⚠ سند capital را 9757.87 نوشته؛ ولی 9816.7 − 3×19.16 = 9759.22 (با lost=240.78 خود سند هم‌خوان است).
        near(9759.22, r.b.balance, what = "capital بعد از معامله ۲")

        // معامله ۳ — TP: entry 1811 ، FinalTP 1821 ، SL 1808 ⇒ div = 10 ⇒ lot = 240.78/10 + 10 = 34.078 (سند: 34.07)
        val (_, o3) = r.armBuy(1811.0, 1808.0, 1821.0)
        near(34.07, o3.qty, what = "lot معامله ۳")
        r.fillAndHitTp(1811.0, 1821.0)
        near(0.0, r.b.mm.lost, what = "lost بعد از TP")
        // سود = 10 × 34.07 = 340.70 ؛ سند: 9757.87 + 340.70 = 10098.57 (با capital درست معامله ۲: 10099.92)
        near(9759.22 + 340.70, r.b.balance, what = "capital بعد از معامله ۳")
        near(10098.57, r.b.balance, tol = 1.4, what = "capital بعد از معامله ۳ نسبت به عدد سند")
    }

    @Test
    fun `ManageAllLost - first trade with zero Lost uses MinLot and leverage cap is the last limit`() {
        val c = cfg(MoneyMode.MANAGE_ALL_LOST, 10_000.0).apply { minLotAuto = false; minLotManual = 10.0 }
        val r = Rig(c)
        val (_, o) = r.armBuy(2000.0, 1995.0, 2015.0)
        near(10.0, o.qty, what = "Lot = MinLot وقتی Lost=0")

        // سقف اهرم: Lost بسیار بزرگ ⇒ Lot خام خیلی بزرگ؛ سقف = equity × اهرم ÷ قیمت
        val c2 = cfg(MoneyMode.MANAGE_ALL_LOST, 10_000.0).apply { minLotAuto = false; minLotManual = 10.0; maxLeverage = 5.0 }
        val r2 = Rig(c2)
        r2.b.mm.lost = 1_000_000.0
        val (_, o2) = r2.armBuy(2000.0, 1995.0, 2015.0)
        near(10_000.0 * 5.0 / 2000.0, o2.qty, what = "حجم باید به سقف اهرم §۲۰ محدود شود")
        assertTrue(o2.mmNote.contains("سقف اهرم"), "یادداشت باید ذکر کند سقف اهرم اعمال شد: ${o2.mmNote}")
    }

    @Test
    fun `MinLot auto is NakedCapital times risk over 100 and manual overrides it`() {
        val c = cfg(MoneyMode.MANAGE_ALL_LOST, 1_000.0)
        val b = PaperBroker(c)
        near(10.0, b.mm.minLot(), what = "NakedCapital=1000, risk=1% ⇒ MinLot=10 (مثال Mixed سند)")
        c.riskPct = 2.0
        near(20.0, b.mm.minLot(), what = "risk=2%")
        c.minLotAuto = false; c.minLotManual = 3.5
        near(3.5, b.mm.minLot(), what = "MinLot دستی")
    }

    // ═══ §۲۰-۲ Mixed ═════════════════════════════════════════════════════════
    @Test
    fun `Mixed follows ManageNothing then ManageCM and resets cmsnLost on final TP`() {
        // مثال سند: NakedCapital = 1000 ، MinLot = 10 (خودکار) ، cmsn ≈ 0.5 به‌ازای هر واحد حجم (۵ برای lot=10)
        val c = cfg(MoneyMode.MIXED, 1_000.0).apply { cmsnManual = 0.5 }
        val r = Rig(c)
        near(1200.0, r.b.mm.growthLimit(), what = "M = NakedCapital × 1.2")

        // معامله ۱ — TP: lot = 10 ، cmsn = 5 ، capital → 1100
        val (_, o1) = r.armBuy(2000.0, 1990.0, 2010.5)
        near(10.0, o1.qty, what = "lot معامله ۱ (ManageNothing)")
        assertFalse(o1.mmCm, "Capital ≤ M ⇒ فاز ManageNothing")
        r.fillAndHitTp(2000.0, 2010.5)
        near(1100.0, r.b.balance, what = "Capital بعد از معامله ۱ (۱۰۵ سود − ۵ کمیسیون)")
        near(5.0, r.b.trades[0].commission, what = "cmsn معامله ۱")

        // معامله ۲ — TP: lot = 10 ، cmsn = 5 ، capital → 1350
        val (_, o2) = r.armBuy(2000.0, 1990.0, 2025.5)
        near(10.0, o2.qty, what = "lot معامله ۲ (Capital=1100 ≤ M)")
        r.fillAndHitTp(2000.0, 2025.5)
        near(1350.0, r.b.balance, what = "Capital بعد از معامله ۲")
        near(0.0, r.b.mm.cmsnLost, what = "cmsnLost بعد از TP")

        // از اینجا Capital > M ⇒ هر معامله‌ای که ضرر کند، کمیسیونش در معامله بعدی حساب می‌شود.
        // معامله ۳ — SL (div=10 ⇒ lot = 0/10 + 10 = 10 ، cmsn = 5)
        val (_, o3) = r.armBuy(2000.0, 1998.0, 2010.0)
        near(10.0, o3.qty, what = "lot معامله ۳")
        assertTrue(o3.mmCm, "Capital=1350 > M ⇒ فاز ManageCM")
        r.fillAndHitSl(2000.0, 1998.0)
        near(5.0, r.b.mm.cmsnLost, what = "cmsnLost بعد از معامله ۳ (فقط کمیسیون ضرردهندهٔ ManageCM)")

        // معامله ۳ب — SL (div=20): lot = 5/20 + 10 = 10.25 ⇒ cmsn = 5.125 ⇒ cmsnLost = 10.125
        val (_, o3b) = r.armBuy(2000.0, 1998.0, 2020.0)
        near(10.25, o3b.qty, what = "lot معامله ۳ب")
        r.fillAndHitSl(2000.0, 1998.0)
        near(10.125, r.b.mm.cmsnLost, tol = 0.02, what = "cmsnLost پیش از معامله ۴")

        // معامله ۴ — SL: div = 10 ، lot = 10.125/10 + 10 = 11.01 (سند: 11) ؛ بعد از ضرر cmsnLost ≈ 16 (سند: 16)
        val (_, o4) = r.armBuy(2000.0, 1998.0, 2010.0)
        near(11.0, o4.qty, tol = 0.02, what = "lot معامله ۴ (سند: 11)")
        r.fillAndHitSl(2000.0, 1998.0)
        near(16.0, r.b.mm.cmsnLost, tol = 0.5, what = "cmsnLost بعد از معامله ۴ (سند: 16)")

        // معامله ۷ — TP: سند cmsnLost=10.8 ، div=18 ⇒ lot = 10.8/18 + 10 = 10.6 ، cmsn = 5.3 ؛ بعد از TP نهایی cmsnLost = 0
        r.b.mm.cmsnLost = 10.8
        val (_, o7) = r.armBuy(2000.0, 1998.0, 2018.0)
        near(10.6, o7.qty, what = "lot معامله ۷ (سند: 10.6)")
        r.fillAndHitTp(2000.0, 2018.0)
        near(5.3, r.b.trades.last().commission, what = "cmsn معامله ۷ (سند: 5.3)")
        near(0.0, r.b.mm.cmsnLost, what = "بعد از رسیدن به حد سود نهایی cmsnLost صفر می‌شود")
    }

    @Test
    fun `Mixed - when Capital falls back below M the lot is MinLot again`() {
        val c = cfg(MoneyMode.MIXED, 1_000.0).apply { cmsnManual = 0.5 }
        val r = Rig(c)
        r.b.balance = 1_150.0; r.b.equity = 1_150.0     // ≤ 1200
        r.b.mm.cmsnLost = 50.0
        val (_, o) = r.armBuy(2000.0, 1998.0, 2010.0)
        near(10.0, o.qty, what = "Capital ≤ M ⇒ Lot = MinLot حتی اگر cmsnLost صفر نباشد")
        assertFalse(o.mmCm)
    }

    // ═══ مود خاموش = دقیقاً §۵ ════════════════════════════════════════════════
    @Test
    fun `mode NONE keeps the paper-5 sizing and charges no commission`() {
        val c = Settings().apply { entryBufTicks = 0.0; cmsnManual = 1.0; cmsnAuto = false }   // cmsn تنظیم شده ولی مود خاموش است
        val r = Rig(c)
        val expected = r.b.qtyFor(2000.0, 1995.0)
        val (_, o) = r.armBuy(2000.0, 1995.0, 2015.0)
        near(expected, o.qty, 1e-9, "حجم مود خاموش باید همان §۵ باشد")
        near(100_000.0 * 0.01 / 5.0, o.qty, 1e-6, "ریسک ۱٪ از ۱۰۰٬۰۰۰ ÷ فاصلهٔ SL=۵")
        assertEquals("", o.mmNote)
        r.fillAndHitSl(2000.0, 1995.0)
        assertEquals(0.0, r.b.trades[0].commission)
        near(100_000.0 - 1000.0, r.b.balance, 1e-6, "ضرر دقیقاً ۱٪")
        near(0.0, r.b.mm.lost, 1e-12, "Lost در مود خاموش نباید ثبت شود")
    }

    // ═══ Lost: خالص / ریست در حد سود نهایی ═══════════════════════════════════
    @Test
    fun `Lost net recovery is optional - final TP reset can be switched off`() {
        val c = cfg(MoneyMode.MANAGE_ALL_LOST, 10_000.0).apply {
            minLotAuto = false; minLotManual = 1.0; lostResetAtFinalTp = false
        }
        val r = Rig(c)
        r.b.mm.lost = 1_000.0
        // lot = 1000/10 + 1 = 101 ⇒ سود حدسود نهایی = 101 × 10 = 1010 ⇒ Lost کاملاً جبران (کف ۰)
        val (_, o) = r.armBuy(2000.0, 1990.0, 2010.0)
        near(101.0, o.qty, what = "lot")
        r.fillAndHitTp(2000.0, 2010.0)
        near(0.0, r.b.mm.lost, what = "Lost با سود خالص ۱۰۱۰ کاملاً جبران می‌شود")

        // سود کمتر از Lost (div دستی بزرگ‌تر از فاصلهٔ واقعی): جبران جزئی؛ بدون ریست
        val c2 = cfg(MoneyMode.MANAGE_ALL_LOST, 10_000.0).apply {
            minLotAuto = false; minLotManual = 1.0; lostResetAtFinalTp = false; divAuto = false; divManual = 100.0
        }
        val r2 = Rig(c2)
        r2.b.mm.lost = 1_000.0
        val (_, o2) = r2.armBuy(2000.0, 1990.0, 2010.0)     // lot = 1000/100 + 1 = 11 ⇒ سود = 110
        near(11.0, o2.qty, what = "lot با div دستی")
        r2.fillAndHitTp(2000.0, 2010.0)
        near(890.0, r2.b.mm.lost, what = "Lost فقط به اندازهٔ سود واقعی کم می‌شود")

        // همان حالت با ریست فعال (پیش‌فرض): بعد از حد سود نهایی Lost صفر می‌شود (سند: lost = 0.0 بعد از TP)
        val c3 = cfg(MoneyMode.MANAGE_ALL_LOST, 10_000.0).apply {
            minLotAuto = false; minLotManual = 1.0; lostResetAtFinalTp = true; divAuto = false; divManual = 100.0
        }
        val r3 = Rig(c3)
        r3.b.mm.lost = 1_000.0
        r3.armBuy(2000.0, 1990.0, 2010.0)
        r3.fillAndHitTp(2000.0, 2010.0)
        near(0.0, r3.b.mm.lost, what = "ریست در حد سود نهایی")
    }

    @Test
    fun `commission is fixed at entry and charged to the trade pnl`() {
        val c = cfg(MoneyMode.MANAGE_ALL_LOST, 10_000.0).apply { minLotAuto = false; minLotManual = 10.0; cmsnManual = 0.5 }
        val r = Rig(c)
        r.armBuy(2000.0, 1995.0, 2015.0)
        r.fillAndHitSl(2000.0, 1995.0)
        val t = r.b.trades[0]
        near(5.0, t.commission, what = "cmsn = ۰٫۵ × ۱۰")
        near(-55.0, t.pnl(), what = "pnl = −۵×۱۰ − ۵")
        near(10_000.0 - 55.0, r.b.balance, what = "balance")
        near(55.0, r.b.mm.lost, what = "Lost شامل کمیسیون")
    }

    @Test
    fun `spread is a pure function of the settings - broker value only when auto and already fetched`() {
        val c = cfg(MoneyMode.MIXED, 1_000.0).apply { cmsnAuto = true; cmsnManual = 0.2 }
        val b = PaperBroker(c)
        near(0.2, b.mm.spread(), what = "اسپرد بروکر هنوز دریافت نشده ⇒ دستی")
        assertFalse(b.mm.spreadIsBroker())
        c.cmsnAutoValue = 0.45
        near(0.45, b.mm.spread(), what = "خودکار و دریافت‌شده ⇒ مقدار بروکر")
        assertTrue(b.mm.spreadIsBroker())
        c.cmsnAuto = false
        near(0.2, b.mm.spread(), what = "خودکار خاموش ⇒ دستی")
        // مقدار ذخیره‌شده بدون شبکه و بدون وضعیت پنهان: دو کارگزار با تنظیمات یکسان ⇒ اسپرد یکسان
        c.cmsnAuto = true
        near(PaperBroker(c).mm.spread(), b.mm.spread(), 1e-12, "اسپرد فقط تابع تنظیمات است")
    }

    // ═══ ذخیره و بازیابی ═════════════════════════════════════════════════════
    @Test
    fun `settings and money-management state survive save and load`() {
        val c = cfg(MoneyMode.MIXED, 1_000.0).apply {
            minLotAuto = false; minLotManual = 2.5; divAuto = false; divManual = 12.0
            cmsnAuto = false; cmsnManual = 0.4; mixedGrowthPct = 30.0; maxLeverage = 250.0
            lotStep = 0.1; lostResetAtFinalTp = false; entryBufTicks = 3.0
            cmsnAutoValue = 0.37; cmsnAutoAt = 1_760_000_000_000L
        }
        val e = Engine(c)
        val r = Rig(c)
        e.broker = r.b; r.b.engine = e
        r.b.balance = 1_300.0; r.b.equity = 1_300.0
        r.b.mm.cmsnLost = 7.5
        r.armBuy(2000.0, 1998.0, 2010.0)
        r.fillAndHitSl(2000.0, 1998.0)       // یک معامله با کمیسیون و Lost
        r.armBuy(2000.0, 1998.0, 2010.0)       // یک سفارش معلق با یادداشت §۲۰
        val lostBefore = r.b.mm.lost
        val cmsnLostBefore = r.b.mm.cmsnLost
        val candles = listOf(Candle(0, 0L, 1.0, 2.0, 0.5, 1.5, 10.0))

        val ld = Store.load(Store.save(c, e, r.b, candles))
        val lc = ld.cfg
        assertEquals(MoneyMode.MIXED, lc.mmMode)
        assertFalse(lc.minLotAuto); assertEquals(2.5, lc.minLotManual)
        assertFalse(lc.divAuto); assertEquals(12.0, lc.divManual)
        assertFalse(lc.cmsnAuto); assertEquals(0.4, lc.cmsnManual)
        assertEquals(30.0, lc.mixedGrowthPct); assertEquals(250.0, lc.maxLeverage)
        assertEquals(0.1, lc.lotStep); assertFalse(lc.lostResetAtFinalTp)
        assertEquals(3.0, lc.entryBufTicks, "entryBufTicks قبلاً ذخیره نمی‌شد و بعد از بازیابی به ۲ برمی‌گشت")
        assertEquals(0.37, lc.cmsnAutoValue, "اسپرد دریافت‌شدهٔ بروکر باید ذخیره شود تا بعد از راه‌اندازی دوباره عدد تغییر نکند")
        assertEquals(1_760_000_000_000L, lc.cmsnAutoAt)
        near(lostBefore, ld.broker.mm.lost, 1e-9, "Lost")
        near(cmsnLostBefore, ld.broker.mm.cmsnLost, 1e-9, "cmsnLost")
        val t = ld.broker.trades[0]
        near(r.b.trades[0].commission, t.commission, 1e-9, "commission معامله")
        assertTrue(t.commission > 0.0)
        near(r.b.trades[0].pnl(), t.pnl(), 1e-9, "pnl")
        val pend = ld.broker.pendingOrder
        assertNotNull(pend)
        assertEquals(r.b.pendingOrder!!.mmNote, pend.mmNote)
        assertTrue(pend.mmNote.contains("Mixed"))
        assertEquals(r.b.pendingOrder!!.mmCm, pend.mmCm)
    }

    @Test
    fun `old save files without the new keys load with defaults`() {
        // فایل ذخیرهٔ نسخهٔ ۱٫۴٫۱۸: هیچ کلید §۲۰ و entryBufTicks ندارد.
        val c = Settings()
        val e = Engine(c)
        val b = PaperBroker(c)
        e.broker = b; b.engine = e
        val json = Store.save(c, e, b, listOf(Candle(0, 0L, 1.0, 2.0, 0.5, 1.5, 10.0)))
        var old = json
        for (k in listOf("mmMode", "minLotAuto", "minLotManual", "divAuto", "divManual", "cmsnAuto", "cmsnManual",
            "mixedGrowthPct", "levModel", "lotStep", "lostResetAtFinalTp", "entryBufTicks", "cmsnAutoValue", "cmsnAutoAt")) {
            old = old.replace("\"$k\"", "\"zz_$k\"")
        }
        old = old.replace("\"mmLost\"", "\"zz_mmLost\"").replace("\"mmCmsnLost\"", "\"zz_mmCmsnLost\"")
        val ld = Store.load(old)
        val d = Settings()
        assertEquals(MoneyMode.NONE, ld.cfg.mmMode)
        assertEquals(d.minLotAuto, ld.cfg.minLotAuto); assertEquals(d.minLotManual, ld.cfg.minLotManual)
        assertEquals(d.divAuto, ld.cfg.divAuto); assertEquals(d.cmsnAuto, ld.cfg.cmsnAuto)
        assertEquals(d.mixedGrowthPct, ld.cfg.mixedGrowthPct); assertEquals(d.maxLeverage, ld.cfg.maxLeverage)
        assertEquals(d.lotStep, ld.cfg.lotStep); assertEquals(d.lostResetAtFinalTp, ld.cfg.lostResetAtFinalTp)
        assertEquals(d.entryBufTicks, ld.cfg.entryBufTicks)
        assertEquals(0.0, ld.cfg.cmsnAutoValue); assertEquals(0L, ld.cfg.cmsnAutoAt)
        assertEquals(0.0, ld.broker.mm.lost); assertEquals(0.0, ld.broker.mm.cmsnLost)
    }

    // ═══ اهرم: فقط یک متغیر ═══════════════════════════════════════════════════
    @Test
    fun `there is one leverage variable and its default is 10 in every mode`() {
        assertEquals(10.0, Settings().maxLeverage, "§۵ سند: اهرم پیش‌فرض ۱۰ (کاربر می‌تواند تغییر دهد)")
        // همان maxLeverage هم §۵ (مود خاموش) را محدود می‌کند و هم §۲۰ را
        for (mode in listOf(MoneyMode.NONE, MoneyMode.MANAGE_ALL_LOST)) {
            val c = cfg(mode, 10_000.0).apply { minLotAuto = false; minLotManual = 1_000.0; maxLeverage = 5.0 }
            val r = Rig(c)
            r.armBuy(2000.0, 1999.0, 2010.0)
            val q = r.b.pendingOrder!!.qty
            near(10_000.0 * 5.0 / 2000.0, q, 1e-6, "مود $mode: حجم به equity × اهرم ÷ قیمت محدود می‌شود")
        }
    }

    @Test
    fun `leverage migrates from the old two-variable files only when the user never changed it`() {
        fun jsonWith(maxLev: Double, marker: Boolean, mm: Double?): String {
            val c = Settings().apply { maxLeverage = maxLev }
            val e = Engine(c); val b = PaperBroker(c); e.broker = b; b.engine = e
            var j = Store.save(c, e, b, listOf(Candle(0, 0L, 1.0, 2.0, 0.5, 1.5, 10.0)))
            if (!marker) j = j.replace("\"levModel\"", "\"zz_levModel\"")
            if (mm != null) j = j.replace("\"maxLeverage\"", "\"mmMaxLeverage\":$mm,\"maxLeverage\"")
            return j
        }
        assertEquals(10.0, Store.load(jsonWith(10.0, marker = false, mm = null)).cfg.maxLeverage, "فایل قدیمی: همان عدد ذخیره‌شده می‌ماند")
        assertEquals(250.0, Store.load(jsonWith(10.0, marker = false, mm = 250.0)).cfg.maxLeverage, "فایل ۱٫۴٫۱۹: مقدار §۲۰ کاربر حفظ می‌شود")
        assertEquals(3.0, Store.load(jsonWith(3.0, marker = false, mm = null)).cfg.maxLeverage, "عدد دلخواه کاربر دست‌نخورده می‌ماند")
        assertEquals(10.0, Store.load(jsonWith(10.0, marker = true, mm = null)).cfg.maxLeverage, "فایل جدید: ۱۰ انتخاب خود کاربر است")
    }

    // ═══ §۲۰ بند ۵ — crt فقط در ManageAllLost ══════════════════════════════════
    @Test
    fun `crt switches the lot to Lost over div once Lost passes the threshold of Capital`() {
        val c = cfg(MoneyMode.MANAGE_ALL_LOST, 10_000.0).apply { minLotAuto = false; minLotManual = 10.0 }
        val r = Rig(c)
        r.b.balance = 1_000.0                  // Capital کوچک ⇒ آستانهٔ ۱۰٪ = ۱۰۰
        r.b.mm.lost = 500.0                    // Lost > آستانه ⇒ crt فعال
        val (_, o) = r.armBuy(2000.0, 1995.0, 2015.0)
        near(500.0 / 15.0, o.qty, 0.01, "crt: Lot = Lost ÷ div بدون MinLot")
        assertTrue(o.mmNote.contains("crt"), "توضیح حجم باید crt را نشان دهد: ${o.mmNote}")

        // زیر آستانه، فرمول عادی ManageAllLost برمی‌گردد
        val c2 = cfg(MoneyMode.MANAGE_ALL_LOST, 10_000.0).apply { minLotAuto = false; minLotManual = 10.0 }
        val r2 = Rig(c2)
        r2.b.balance = 10_000.0
        r2.b.mm.lost = 100.0                   // ۱۰۰ < ۱۰٪ از ۱۰٬۰۰۰
        val (_, o2) = r2.armBuy(2000.0, 1995.0, 2015.0)
        near(100.0 / 15.0 + 10.0, o2.qty, 0.01, "زیر آستانه: Lot = Lost/div + MinLot")

        // crt در Mixed اعمال نمی‌شود
        val c3 = cfg(MoneyMode.MIXED, 10_000.0).apply { minLotAuto = false; minLotManual = 10.0 }
        val r3 = Rig(c3)
        r3.b.balance = 1_000.0
        r3.b.mm.lost = 500.0
        val (_, o3) = r3.armBuy(2000.0, 1995.0, 2015.0)
        near(10.0, o3.qty, 0.01, "Mixed: crt نقشی ندارد؛ Capital ≤ M ⇒ Lot = MinLot")
    }

    // ═══ حسابداری: هر مفهوم یک متغیر، یک جای به‌روزرسانی ═══════════════════════
    @Test
    fun `Capital moves only when a trade fully closes while equity follows the open trade`() {
        val c = cfg(MoneyMode.MANAGE_ALL_LOST, 10_000.0).apply { minLotAuto = false; minLotManual = 10.0; cmsnManual = 0.5 }
        val r = Rig(c)
        val s = Setup(1L, 1, 6, 2005.0, 1995.0, 0, 0)
        s.hvH = 2000.0; s.hvL = 1999.0; s.sl = 1990.0; s.tpx = 2030.0; s.tp1 = 2010.0; s.tp2 = 2020.0
        assertTrue(r.b.registerLimit(s, r.bar(2002.0, 2003.0, 2001.0, 2002.0)), "ثبت سفارش: ${r.b.lastError}")
        near(10.0, r.b.pendingOrder!!.qty, what = "Lot = MinLot")

        r.b.onBar(r.bar(2001.0, 2002.0, 1999.5, 2001.0))                 // پر شدن؛ کلوز ۲۰۰۱ ⇒ شناور +۱۰
        val t = r.b.openTrades.single()
        near(5.0, t.commission, what = "cmsn = ۰٫۵ × ۱۰")
        near(10_000.0, r.b.balance, 1e-9, "Capital با پر شدن (و کمیسیون) تغییر نمی‌کند")
        near(10_000.0 - 5.0 + 10.0, r.b.equity, 1e-6, "equity = −کمیسیون + شناور")

        r.b.onBar(r.bar(2005.0, 2011.0, 2004.0, 2008.0))                 // TP1: ۳٫۳ واحد در ۲۰۱۰ ⇒ +۳۳
        near(10_000.0, r.b.balance, 1e-9, "Capital با پلهٔ خروج TP1 هم تغییر نمی‌کند")
        near(10_000.0 + 33.0 - 5.0 + (2008.0 - 2000.0) * 6.7, r.b.equity, 1e-6, "equity پلهٔ خروج‌شده را دارد")
        near(0.0, r.b.mm.lost, 1e-12, "Lost هنوز تغییری نکرده")

        r.b.onBar(r.bar(2005.0, 2006.0, 1999.0, 2000.5))                 // برگشت به ورود ⇒ سر‌به‌سر؛ معامله کامل بسته می‌شود
        assertEquals(0, r.b.openTrades.size)
        near(10_000.0 + 33.0 - 5.0, r.b.balance, 1e-6, "Capital = اولیه + سود/زیان خالص کل معامله")
        near(r.b.balance, r.b.equity, 1e-9, "بدون معامله باز: equity = Capital")
        near(r.b.trades[0].pnl(), r.b.balance - 10_000.0, 1e-9, "Capital − اولیه = مجموع سود/زیان معاملات بسته‌شده")
    }

    @Test
    fun `Capital and Lost change at the very same event`() {
        val c = cfg(MoneyMode.MANAGE_ALL_LOST, 10_000.0).apply { minLotAuto = false; minLotManual = 10.0; cmsnManual = 0.5 }
        val r = Rig(c)
        r.armBuy(2000.0, 1995.0, 2015.0)
        r.b.onBar(r.bar(2001.0, 2002.0, 1999.5, 2001.0))                 // پر شد، SL نخورده
        near(10_000.0, r.b.balance, 1e-9, "Capital قبل از بسته شدن")
        near(0.0, r.b.mm.lost, 1e-12, "Lost قبل از بسته شدن")
        r.b.onBar(r.bar(2000.0, 2000.5, 1994.0, 1995.0))                 // SL
        near(10_000.0 - 55.0, r.b.balance, 1e-9, "Capital بعد از بسته شدن")
        near(55.0, r.b.mm.lost, 1e-9, "Lost بعد از بسته شدن (همان رخداد)")
    }

    @Test
    fun `an old save file with a still-open trade is migrated so nothing is counted twice`() {
        val c = cfg(MoneyMode.MANAGE_ALL_LOST, 10_000.0).apply { minLotAuto = false; minLotManual = 10.0; cmsnManual = 0.5 }
        val e = Engine(c)
        val r = Rig(c)
        e.broker = r.b; r.b.engine = e
        val s = Setup(1L, 1, 6, 2005.0, 1995.0, 0, 0)
        s.hvH = 2000.0; s.hvL = 1999.0; s.sl = 1990.0; s.tpx = 2030.0; s.tp1 = 2010.0; s.tp2 = 2020.0
        r.b.registerLimit(s, r.bar(2002.0, 2003.0, 2001.0, 2002.0))
        r.b.onBar(r.bar(2001.0, 2002.0, 1999.5, 2001.0))
        r.b.onBar(r.bar(2005.0, 2011.0, 2004.0, 2008.0))                 // TP1 خورد؛ معامله هنوز باز است
        val t = r.b.openTrades.single()
        val inOldBalance = t.fills.sumOf { it.pnl } - t.commission        // مدل قدیمی این را زودتر داخل balance می‌ریخت
        near(28.0, inOldBalance, 1e-9, "۳۳ سود TP1 − ۵ کمیسیون")
        val candles = listOf(Candle(0, 0L, 1.0, 2.0, 0.5, 1.5, 10.0))

        val modern = Store.save(c, e, r.b, candles)
        val newLoaded = Store.load(modern).broker
        near(r.b.balance, newLoaded.balance, 1e-9, "فایل جدید دست‌نخورده بارگذاری می‌شود")

        // شبیه‌سازی فایل قدیمی: بدون balModel و با balance مدل قدیمی
        val oldBalance = r.b.balance + inOldBalance
        var old = modern.replace("\"balModel\"", "\"zz_balModel\"")
        old = Regex("\"balance\":[^,}]+").replaceFirst(old, "\"balance\":$oldBalance")
        val oldLoaded = Store.load(old).broker
        near(r.b.balance, oldLoaded.balance, 1e-9, "balance فایل قدیمی به مدل جدید مهاجرت می‌کند")
        near(r.b.equity, oldLoaded.equity, 1e-9, "equity هیچ‌گاه عوض نمی‌شود")

        // و ادامه: بستن معامله بعد از بارگذاری باید همان Capital نهایی را بدهد
        val rr = Rig(c); rr.b.balance = oldLoaded.balance
        near(10_000.0, rr.b.balance, 1e-9, "پایهٔ ادامه = سرمایهٔ بسته‌شدهٔ قبل از معامله")
    }

    // ═══ §1/§3/§6 — پر شدن سفارش لیمیت با قیمت زنده ═══════════════════════════
    @Test
    fun `limit order fills as soon as the forming candle touches the level, never in the arming bar`() {
        val r = Rig(cfg(MoneyMode.NONE, 100_000.0))
        val (_, o) = r.armBuy(2000.0, 1995.0, 2015.0)
        val armBi = o.placedBi

        // همان کندل مسلح‌شدن: پر شدن مجاز نیست (حتی اگر لمس کند)
        assertEquals(0, r.b.onLiveCandle(Candle(armBi, 0L, 2001.0, 2002.0, 1999.0, 2000.5, 1.0)))
        assertTrue(r.b.hasPending)

        // کندل بعدی در حال تشکیل است و هنوز به سطح نرسیده
        assertEquals(0, r.b.onLiveCandle(Candle(armBi + 1, 0L, 2003.0, 2004.0, 2001.0, 2002.0, 1.0)))
        assertTrue(r.b.hasPending)

        // رسید به سطح سفارش (low ≤ 2000) ⇒ همان لحظه پر می‌شود
        val forming = Candle(armBi + 1, (armBi + 1) * 60_000L, 2003.0, 2004.0, 1999.5, 2001.0, 1.0)
        assertEquals(1, r.b.onLiveCandle(forming))
        assertFalse(r.b.hasPending)
        assertEquals(1, r.b.openTrades.size)
        assertEquals(OrderStatus.FILLED, o.status)
        assertEquals(armBi + 1, r.b.openTrades[0].entryBi)
        assertEquals(1, r.alerts.count { it == AlertKind.FILLED }, "هشدار «پر شد» دقیقاً یک بار")

        // تیک زندهٔ بعدی نباید دوباره پر کند
        assertEquals(0, r.b.onLiveCandle(forming))

        // همان کندل که بسته می‌شود: سفارش دوباره پر نمی‌شود؛ فقط مدیریت معامله اجرا می‌شود
        r.b.onBar(Candle(armBi + 1, forming.t, 2003.0, 2004.0, 1999.5, 2001.0, 1.0))
        assertEquals(1, r.b.trades.size, "نباید معامله‌ی دوم ساخته شود")
        assertEquals(1, r.alerts.count { it == AlertKind.FILLED })
        assertTrue(r.b.openTrades.size == 1, "معامله هنوز باز است")
    }

    @Test
    fun `live fill and closed-candle fill give the same trade`() {
        fun run(live: Boolean): Trade {
            val r = Rig(cfg(MoneyMode.NONE, 100_000.0))
            val (_, o) = r.armBuy(2000.0, 1995.0, 2015.0)
            val k = Candle(o.placedBi + 1, (o.placedBi + 1) * 60_000L, 2003.0, 2004.0, 1999.5, 2001.0, 1.0)
            if (live) r.b.onLiveCandle(k)
            r.b.onBar(k)
            // کندل بعد: حد ضرر
            r.b.onBar(Candle(k.bi + 1, (k.bi + 1) * 60_000L, 2000.0, 2000.5, 1994.0, 1995.5, 1.0))
            return r.b.trades.single()
        }
        val a = run(live = false)
        val b = run(live = true)
        assertEquals(a.entry, b.entry); assertEquals(a.entryBi, b.entryBi); assertEquals(a.qty, b.qty)
        assertEquals(a.reason, b.reason)
        near(a.pnl(), b.pnl(), 1e-9, "pnl")
    }

    /**
     * §۴-۸: سفارشی که کندلِ جدید «بالاتر از HH» (فروش: پایین‌تر از LL) باز شود، یا یک کندل ساختار کامل از مسلح‌شدنش
     * بگذرد، منقضی است و موتور آن را با کلوز همان کندل لغو می‌کند؛ پر کردن زنده نباید این اولویت را بشکند.
     */
    @Test
    fun `live fill never beats the engine expiry rules - open beyond HH and a full structure candle`() {
        val c = cfg(MoneyMode.NONE, 100_000.0)          // tfS=H1 ، tf2=M1 ⇒ ۶۰ کندل تریگر۲
        fun rig(): Triple<Rig, Engine, Setup> {
            val r = Rig(c)
            val e = Engine(c)
            r.b.engine = e
            val (s, _) = r.armBuy(2000.0, 1995.0, 2015.0)
            s.armBi = r.bi - 1
            s.priceExpiryRef = 2010.0                   // آخرین پین مخالف (HH) هنگام مسلح‌شدن
            e.setups.add(s)
            return Triple(r, e, s)
        }

        // ۱) کندل بالاتر از HH باز می‌شود (open=2011 > 2010) و بعد تا سطح ورود پایین می‌آید ⇒ پر نمی‌شود
        run {
            val (r, _, s) = rig()
            val nxt = s.armBi + 1
            assertEquals(0, r.b.onLiveCandle(Candle(nxt, nxt * 60_000L, 2011.0, 2012.0, 1999.0, 2000.5, 1.0)),
                "open بالاتر از HH ⇒ سفارش در لحظهٔ باز شدن منقضی شده است")
            assertTrue(r.b.hasPending, "سفارش تا کلوز (انقضای موتور) در انتظار می‌ماند")
        }
        // ۲) کندل زیر HH باز می‌شود و به سطح ورود می‌رسد ⇒ پر می‌شود (تا اینجا اولویت با ورود است نه کلوز)
        run {
            val (r, _, s) = rig()
            val nxt = s.armBi + 1
            assertEquals(1, r.b.onLiveCandle(Candle(nxt, nxt * 60_000L, 2009.0, 2012.0, 1999.0, 2011.0, 1.0)),
                "open زیر HH و لمس سطح ⇒ پر می‌شود حتی اگر کلوز بالاتر از HH برود")
        }
        // ۳) یک کندل ساختار کامل (۶۰ کندل M1) از مسلح‌شدن گذشته ⇒ سفارش منقضی است؛ پر نمی‌شود
        run {
            val (r, _, s) = rig()
            val late = s.armBi + 60
            assertEquals(0, r.b.onLiveCandle(Candle(late, late * 60_000L, 2003.0, 2004.0, 1999.0, 2001.0, 1.0)),
                "پس از یک کندل ساختار کامل، سفارش دیگر معتبر نیست")
            val ok = s.armBi + 59
            assertEquals(1, r.b.onLiveCandle(Candle(ok, ok * 60_000L, 2003.0, 2004.0, 1999.0, 2001.0, 1.0)),
                "یک کندل زودتر هنوز معتبر است")
        }
        // ۴) فروش — آینه: open پایین‌تر از LL ⇒ پر نمی‌شود
        run {
            val r = Rig(c)
            val e = Engine(c)
            r.b.engine = e
            val s = Setup(77L, -1, 6, 2005.0, 1995.0, 0, 0)
            s.hvL = 2000.0; s.hvH = 2001.0; s.sl = 2005.0; s.tpx = 1985.0
            val arm = r.bar(1998.0, 1999.0, 1997.0, 1998.0)
            assertTrue(r.b.registerLimit(s, arm), "ثبت سفارش فروش رد شد: ${r.b.lastError}")
            s.armBi = arm.bi; s.priceExpiryRef = 1990.0
            e.setups.add(s)
            val nxt = arm.bi + 1
            assertEquals(0, r.b.onLiveCandle(Candle(nxt, nxt * 60_000L, 1989.0, 2001.0, 1988.0, 1999.0, 1.0)),
                "فروش: open پایین‌تر از LL ⇒ منقضی")
        }
    }

    // ═══ سرتاسری: روی ۴۰٬۰۰۰ کندل نمونه ═════════════════════════════════════
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

    private fun runEngine(c: Settings, candles: List<Candle>): Pair<Engine, PaperBroker> {
        val e = Engine(c)
        val b = PaperBroker(c)
        e.broker = b; b.engine = e
        e.barCommitHook = { cd -> b.onBar(cd, live = false) }
        e.feed(candles, lastIsClosed = true)
        return e to b
    }

    @Test
    fun `end to end on the sample - balance equals initial plus all trade pnl in every mode`() {
        val candles = sample()
        var anyTrades = 0
        for (mode in listOf(MoneyMode.NONE, MoneyMode.MANAGE_ALL_LOST, MoneyMode.MIXED)) {
            val c = Settings().apply {
                mmMode = mode
                initialEquity = 10_000.0
                cmsnAuto = false; cmsnManual = 0.3
                minLotAuto = false; minLotManual = 1.0   // نمونه قیمت‌های واقعی طلاست؛ حجم کوچک ⇒ سقف اهرم کمتر درگیر می‌شود
                mixedGrowthPct = 0.5                      // Mixed زود وارد ManageCM شود
            }
            val (_, b) = runEngine(c, candles)
            val sumPnl = b.trades.sumOf { it.pnl() }
            near(c.initialEquity + sumPnl, b.balance, 1e-6, "mode=$mode: balance = initial + Σ pnl")
            if (mode == MoneyMode.NONE) {
                assertTrue(b.trades.all { it.commission == 0.0 }, "مود خاموش بی‌کمیسیون")
                near(0.0, b.mm.lost, 1e-12, "Lost در مود خاموش")
            } else {
                val closed = b.closedTrades()
                if (closed.isNotEmpty()) assertTrue(b.trades.all { it.commission > 0.0 }, "هر معامله کمیسیون دارد")
                assertTrue(b.mm.lost >= 0.0 && b.mm.cmsnLost >= 0.0)
                // هر سفارش در مود روشن یادداشت محاسبهٔ حجم دارد
                assertTrue(b.orders.all { it.mmNote.isNotEmpty() })
                assertTrue(b.orders.all { it.qty > 0.0 })
            }
            anyTrades += b.trades.size
            println("Doc20 e2e mode=${MoneyMode.label(mode)} orders=${b.orders.size} trades=${b.trades.size} lost=${"%.2f".format(b.mm.lost)} cmsnLost=${"%.2f".format(b.mm.cmsnLost)} balance=${"%.2f".format(b.balance)}")
        }
        assertTrue(anyTrades > 0, "روی ۴۰٬۰۰۰ کندل نمونه باید حداقل یک معامله رخ دهد")
    }

    @Test
    fun `end to end - lost never negative and matches closed trades in ManageAllLost without reset`() {
        val c = Settings().apply {
            mmMode = MoneyMode.MANAGE_ALL_LOST
            initialEquity = 10_000.0; minLotAuto = false; minLotManual = 1.0
            lostResetAtFinalTp = false; cmsnAuto = false; cmsnManual = 0.0
        }
        val (_, b) = runEngine(c, sample())
        // بازسازی مستقل Lost از روی معاملات بستهٔ ترتیبی: زیان خالص جمع، سود خالص کم‌کن (کف ۰)
        var lost = 0.0
        for (t in b.closedTrades().sortedBy { it.exitBi }) {
            val n = t.pnl()
            lost = if (n < 0) lost + (-n) else maxOf(0.0, lost - n)
        }
        near(lost, b.mm.lost, 1e-6, "Lost باید با بازسازی مستقل یکی باشد")
    }
}
