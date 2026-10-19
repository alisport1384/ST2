package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * تست‌های واحد مستقیم برای قاعده‌های عددی سند که پیش‌تر فقط به‌صورت غیرمستقیم
 * (از مسیر موتور) پوشش داده می‌شدند. هدف: قفل‌کردن دقیق فرمول‌های سند تا هیچ
 * بازنویسی بعدی، عددها را بی‌سر‌و‌صدا جابه‌جا نکند.
 */
class DocRulesUnitTest {

    // ── §۴-۱: انتخاب کندل مهم + قاعدهٔ تساوی ────────────────────────────────────

    @Test
    fun `doc 4-1 pickHigh prefers higher high and on equal highs the lower low`() {
        val a = Candle(0, 0L, 10.0, 20.0, 5.0, 12.0, 1.0)     // high 20 , low 5
        val b = Candle(1, 0L, 12.0, 21.0, 8.0, 11.0, 1.0)     // high 21 , low 8
        assertEquals(21.0, pickHighOf(a, b).first, "سقف بالاتر = کندل مهم بالاترین‌ها")

        // سقف برابر ⇒ کندلی که کف کمتری دارد کندل مهم است (متن صریح سند)
        val c = Candle(2, 0L, 10.0, 20.0, 4.0, 12.0, 1.0)     // high 20 , low 4
        val d = Candle(3, 0L, 12.0, 20.0, 9.0, 11.0, 1.0)     // high 20 , low 9
        assertEquals(4.0, pickHighOf(c, d).second, "در تساوی سقف، کف کمتر باید انتخاب شود")
    }

    @Test
    fun `doc 4-1 pickLow prefers lower low and on equal lows the lower high`() {
        val a = Candle(0, 0L, 10.0, 20.0, 5.0, 12.0, 1.0)
        val b = Candle(1, 0L, 12.0, 21.0, 8.0, 11.0, 1.0)
        assertEquals(5.0, pickLowOf(a, b).second, "کف کمتر = کندل مهم پایین‌ترین‌ها")

        // کف برابر ⇒ کندلی که سقف کمتری دارد کندل مهم است
        val c = Candle(2, 0L, 10.0, 18.0, 5.0, 12.0, 1.0)     // high 18 , low 5
        val d = Candle(3, 0L, 12.0, 19.0, 5.0, 11.0, 1.0)     // high 19 , low 5
        assertEquals(18.0, pickLowOf(c, d).first, "در تساوی کف، سقف کمتر باید انتخاب شود")
    }

    // ── §۴-۵: ناحیهٔ ردشده سهمیهٔ دو ناحیه را مصرف می‌کند ─────────────────────────

    @Test
    fun `doc 4-5 rejected zone takes one of the two slots and close equal to zone top is rejected`() {
        // پروفایل با دو قله: اولی زیر کلوز (پذیرفته) و دومی بالای کلوز (ردشده)
        // ایندکس ردیف‌ها از کف: 0..15
        val vol = doubleArrayOf(
            1.0, 2.0, 4.0, 2.0, 1.0,      // قلهٔ اول روی ردیف ۲ ، دره روی ردیف ۴
            1.0, 2.0, 4.0, 2.0, 1.0,      // قلهٔ دوم روی ردیف ۷ ، دره روی ردیف ۹
            1.0, 1.0, 1.0, 1.0, 1.0, 1.0
        )
        val prof = Profile(vol, 100.0, 1.0, 108.0, Double.NaN, Double.NaN)
        val c = VolumeProfile.zoneCandidates(prof, rows = 16, bull = true, cClose = 108.0, minRows = 1)
        assertEquals(1, c.accepted.size, "ناحیهٔ اول (زیر کلوز) باید پذیرفته شود")
        assertEquals(1, c.rejected.size, "ناحیهٔ دوم (بالای کلوز) باید رد شود")
        assertEquals(listOf(1, 2), (c.accepted + c.rejected).map { it.idx }.sorted(),
            "شماره‌ها مشترک و حداکثر دو نامزد در کل است (Z1/Z2)")

        // شرط اعتبار اکید است: zTop == close هم رد می‌شود (سند: «پایین‌تر از کلوز»)
        val onlyFirst = VolumeProfile.zoneCandidates(prof, rows = 16, bull = true, cClose = 105.0, minRows = 1)
        assertTrue(onlyFirst.accepted.isEmpty(), "ناحیه‌ای که سقفش برابر کلوز است نباید پذیرفته شود")
        assertEquals(105.0, onlyFirst.rejected.first().top, 1e-9,
            "زیرِ ناحیهٔ اول = مرز ردیف قبل از قله و سقفش = مرز بالای دره")
    }

    @Test
    fun `doc 4-5 mirror bear zone uses zBot strictly above close`() {
        // همان پروفایل، پیمایش از سقف: قلهٔ اول (بالا) پذیرفته، قلهٔ دوم رد
        val vol = doubleArrayOf(
            1.0, 1.0, 1.0, 1.0, 1.0, 1.0,
            1.0, 2.0, 4.0, 2.0, 1.0,
            1.0, 2.0, 4.0, 2.0, 1.0
        )
        val prof = Profile(vol, 100.0, 1.0, 0.0, Double.NaN, Double.NaN)
        val c = VolumeProfile.zoneCandidates(prof, rows = 16, bull = false, cClose = 107.0, minRows = 1)
        assertEquals(1, c.accepted.size, "ناحیهٔ بالایی باید پذیرفته شود (کفش بالاتر از کلوز)")
        assertTrue(c.accepted.first().bot > 107.0, "شرط پذیرش نزولی: kaf ناحیه بالاتر از کلوز")
        assertEquals(1, c.rejected.size)
    }

    // ── §۴-۸: سطح سفارش = سقف/کف باکس ± ۱ تیک ─────────────────────────────────

    @Test
    fun `doc 4-8 entry level is box top plus one tick for buy and box bottom minus one tick for sell`() {
        val cfg = Settings()
        assertEquals(100.01, cfg.entryLevel(true, 100.0, 90.0), 1e-9)
        assertEquals(89.99, cfg.entryLevel(false, 100.0, 90.0), 1e-9)
        // سطح سفارش هرگز منفی نمی‌شود (سند: «سطح سفارش همیشه عددی مثبت است»)
        assertEquals(0.02, cfg.entryLevel(false, 0.05, 0.03), 1e-9)
        assertEquals(0.01, cfg.entryLevel(false, 0.02, 0.005), 1e-9, "کفِ سطح سفارش = یک تیک")
        // حد ضرر = کف ناحیه − ۲ تیک (خرید) / سقف ناحیه + ۲ تیک (فروش)
        assertEquals(89.98, 90.0 - cfg.slBufTicks * cfg.mintick, 1e-9)
        assertEquals(100.02, 100.0 + cfg.slBufTicks * cfg.mintick, 1e-9)
    }

    // ── §۴-۸: فالبک ۱۰ واحدی و نسبت‌های ۳۸٪/۵۰٪ ─────────────────────────────────

    @Test
    fun `doc 4-8 final TP closer than ten units falls back to entry plus ten with 38 and 50 legs`() {
        // R = HH − کف تایید = 2015 − 2001 = 14 ⇒ TPX فیبو = 2001 + 1.272×14 = 2018.808
        val bull = calculateTargetLevels(true, entry = 2012.0, anchor0 = 2015.0, anchor1 = 2001.0, minDistance = 10.0)
        assertTrue(bull.fibDeleted, "فاصلهٔ TP تا ورود (۶٫۸) کمتر از ۱۰ است ⇒ فیبوناچی این معامله حذف می‌شود")
        assertEquals(2022.0, bull.tpx, 1e-9, "حد سود نهایی = ورود + ۱۰")
        assertEquals(2015.82, bull.tp1, 1e-9, "پلهٔ اول = ۳۸٪ از فاصلهٔ جدید")
        assertEquals(2017.0, bull.tp2, 1e-9, "پلهٔ دوم = ۵۰٪ از فاصلهٔ جدید")

        val bear = calculateTargetLevels(false, entry = 1988.0, anchor0 = 1985.0, anchor1 = 1999.0, minDistance = 10.0)
        assertTrue(bear.fibDeleted)
        assertEquals(1978.0, bear.tpx, 1e-9)
        assertEquals(1984.18, bear.tp1, 1e-9)
        assertEquals(1983.0, bear.tp2, 1e-9)
    }

    @Test
    fun `doc 4-8 invalid fib anchors keep only the ten unit final TP`() {
        var t = calculateTargetLevels(true, 2000.0, 1990.0, 2005.0, 10.0)
        assertTrue(t.fibDeleted)
        assertEquals(2010.0, t.tpx, 1e-9)
        assertTrue(t.tp1.isNaN() && t.tp2.isNaN(), "بدون فیبو، پله‌های ۳۸/۵۰ وجود ندارد")

        t = calculateTargetLevels(true, 2000.0, Double.NaN, Double.NaN, 10.0)
        assertEquals(2010.0, t.tpx, 1e-9)

        // ✳ استراتژی ۲ (tp بدون فیبو): همان مسیر «فیبوی نامعتبر» با واحد دلخواه
        t = calculateTargetLevels(true, 2000.0, Double.NaN, Double.NaN, 7.0)
        assertEquals(2007.0, t.tpx, 1e-9)
    }

    @Test
    fun `doc 4-8 entry above the 382 fib gives TP1 at 0-5 and final at 1-272`() {
        // R = 2015 − 2001 = 14 ⇒ 0.382 = 2006.348 ، 0.5 = 2008 ، 1.272 = 2018.808
        val t = calculateTargetLevels(true, entry = 2007.5, anchor0 = 2015.0, anchor1 = 2001.0, minDistance = 10.0)
        assertFalse(t.fibDeleted)
        assertEquals(2008.0, t.tp1, 1e-9, "ورود بعد از ۰٫۳۸۲ (۲۰۰۶٫۳۴۸) ⇒ پلهٔ اول = ۰٫۵ فیبو")
        assertTrue(t.tp2.isNaN(), "در این حالت فقط دو پله داریم (۵۰/۵۰)")
        assertEquals(2018.808, t.tpx, 1e-9)
    }

    // ── §۴-۸: انقضای «یک کندل ساختار» و عبور باز/کلوز ────────────────────────────

    @Test
    fun `doc 4-8 one structure candle means tfS over tf2 trigger bars`() {
        assertFalse(fullStructureElapsed(armBi = 100, currentBi = 159, tfS = 3600, tf2 = 60))
        assertTrue(fullStructureElapsed(armBi = 100, currentBi = 160, tfS = 3600, tf2 = 60),
            "ساختار ۱ ساعته و تریگر ۲ یک‌دقیقه‌ای ⇒ n = ۶۰")
        assertTrue(fullStructureElapsed(1, 3, tfS = 7200, tf2 = 3600), "ساختار ۲ ساعته ⇒ n = ۲")
        assertFalse(fullStructureElapsed(-1, 500, 3600, 60), "بدون مسلح‌شدن هرگز منقضی نیست")
    }

    @Test
    fun `doc 4-8 expiry uses open and close only wicks and equality do not expire`() {
        assertFalse(priceExpiryCrossed(bull = true, open = 99.0, close = 99.5, level = 99.5))
        assertTrue(priceExpiryCrossed(bull = true, open = 99.6, close = 99.0, level = 99.5))
        assertTrue(priceExpiryCrossed(bull = true, open = 99.0, close = 99.6, level = 99.5))
        assertFalse(priceExpiryCrossed(bull = false, open = 100.5, close = 100.0, level = 100.0))
        assertTrue(priceExpiryCrossed(bull = false, open = 99.9, close = 100.0, level = 100.0))
        assertFalse(priceExpiryCrossed(true, 100.0, 100.0, Double.NaN), "سطح نامعلوم ⇒ هرگز منقضی نیست")
    }

    // ── §۲۰: فرمول‌های MinLot / div / M / crt ───────────────────────────────────

    @Test
    fun `doc 20 formulas min lot div growth limit and crt behave as documented`() {
        val cfg = Settings().apply {
            mmMode = MoneyMode.MANAGE_ALL_LOST
            initialEquity = 10_000.0
            riskPct = 1.0
            minLotAuto = true
        }
        val mm = MoneyManager(cfg)
        assertEquals(100.0, mm.minLot(), 1e-9, "MinLot = (NakedCapital × risk)/100")
        assertEquals(15.0, mm.div(2000.0, 2015.0), 1e-9, "div = |ورود − حد سود نهایی|")
        assertEquals(5.0, mm.div(2010.0, 2005.0), 1e-9, "div همیشه مثبت است")

        // lot = Lost/div + MinLot  (مثال سند: 100/15 + 10 = 16.66)
        cfg.minLotAuto = false; cfg.minLotManual = 10.0
        mm.lost = 100.0
        val lot = mm.lotFor(capital = 9_900.0, entry = 2000.0, finalTp = 2015.0)
        assertEquals(16.666666, lot.raw, 1e-5)
        assertEquals(16.66, mm.floorToStep(lot.raw), 1e-9, "گام حجم ۰٫۰۱ — نمونهٔ سند ۱۶٫۶۶")

        // M = NakedCapital × 1.2 و دو برابر شدن NakedCapital در مود Mixed
        cfg.mmMode = MoneyMode.MIXED
        assertEquals(12_000.0, mm.growthLimit(), 1e-9)
        mm.onCapitalUpdated(20_000.0)
        assertEquals(20_000.0, mm.nakedCapital(), 1e-9, "Capital/Naked ≥ ۲ ⇒ Naked دو برابر")
    }
}

// دسترسی به توابع داخلی موتور برای تست (Kotlin: internal در همان ماژول قابل استفاده است)
private fun pickHighOf(a: Candle, b: Candle): Triple<Double, Double, Int> {
    val e = Engine(Settings())
    return e.pickHigh(a, b)
}

private fun pickLowOf(a: Candle, b: Candle): Triple<Double, Double, Int> {
    val e = Engine(Settings())
    return e.pickLow(a, b)
}
