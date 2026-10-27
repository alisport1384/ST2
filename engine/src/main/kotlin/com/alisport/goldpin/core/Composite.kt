package com.alisport.goldpin.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// ═══════════════════════════════════════════════════════════════════════════════
//  موتور ترکیبی — نسخهٔ ۱٫۴٫۵۲
//
//  این کلاس «وضعیت بازار و حساب» را می‌سازد و سه ضریب تصمیم می‌دهد:
//      fRegime   شدت/رژیم نوسان      (معنادار در آزمون ۲۰ساله: ‎+۰٫۰۹۹R‎)
//      fStress   استرس حساب          (نویز در پیش‌بینی نتیجه ⟹ فقط کم‌کردن ریسک)
//      fShape    شکل منحنی سرمایه    (شرایط استرس «نموداری»)
//
//  ⚠ حذف‌شده‌ها (تصمیم مالک، ۴ آبان ۱۴۰۵): «جریان پول CMF» و «تقویم خبر».
//    دلیل: در آزمون ۲۰ساله با فاصلهٔ اطمینان بوت‌استرپ، اثرشان از صفر قابل تفکیک نبود
//    (CMF: ‎+۰٫۰۲۲R‎ با CI [−۰٫۰۹۲,+۰٫۱۴۳] · خبر: ‎+۰٫۰۷۲R‎ با CI [−۰٫۰۶۰,+۰٫۲۱۵]).
//
//  قاعدهٔ طراحی: همهٔ ویژگی‌ها **فقط از کندل بستهٔ گذشته** ساخته می‌شوند و هیچ‌کدام
//  ورودی آینده یا کندل جاری را نمی‌بینند.
// ═══════════════════════════════════════════════════════════════════════════════
class CompositeState {

    // ── نمونه‌های بازار (پنجره‌های ثابت؛ مصرف حافظه ثابت است) ────────────────────
    /** ATR% یک نمونه در هر ساعت — پنجرهٔ ۳۰۰ ساعت (≈ ۱۲ روز معاملاتی). */
    private val atrHourly = ArrayDeque<Double>()
    private var atrHourBucket = Long.MIN_VALUE
    /** دامنهٔ (h−l) هر کندل — پنجرهٔ ۱۴۴۰ کندل برای «انبساط دامنه». */
    private val ranges = ArrayDeque<Double>()
    private var rangesSum = 0.0

    /** ATR/قیمت×۱۰۰ در لحظهٔ جاری (همان چیزی که گیت نوسان استفاده می‌کند). */
    var atrP = 0.0
        private set
    /** صدک atrP در پنجرهٔ ۳۰۰ ساعته (۰..۱). */
    var atrPctile = 0.5
        private set
    /** انبساط دامنه: میانگین ۶۰ کندل اخیر ÷ میانگین ۱۴۴۰ کندل اخیر. */
    var expansion = 1.0
        private set
    /** ۰ = آرام · ۱ = عادی · ۲ = پرنوسان. */
    var regime = 1
        private set

    // ── وضعیت حساب (در هر درخواست ورود از فهرست معاملات بسته محاسبه می‌شود) ─────
    var ddPct = 0.0
        private set
    var recent20R = 0.0
        private set
    var lossStreak = 0
        private set
    /** ۰ سالم · ۱ هشدار · ۲ بحرانی (شرایط استرس نموداری). */
    var shapeState = 0
        private set
    private var shapeT = 0.0
    private var shapeDD50 = 0.0

    // ── ضریب‌های خروجی ───────────────────────────────────────────────────────────
    var fRegime = 1.0
        private set
    var fStress = 1.0
        private set
    var fShape = 1.0
        private set

    /** شمارنده‌های نمایشی: چند ورود به‌خاطر رژیم/باکس رد شد. */
    var rejectedRegime = 0
        private set
    var rejectedBox = 0
        private set

    /** افزایش شمارندهٔ «رد به‌خاطر رژیم» (از کارگزار صدا زده می‌شود). */
    fun rejectedRegimeX() { rejectedRegime++ }
    /** افزایش شمارندهٔ «رد به‌خاطر ساختار» (از کارگزار صدا زده می‌شود). */
    fun rejectedBoxX() { rejectedBox++ }

    /** توضیح یک‌خطی آخرین تصمیم (برای لاگ «کارت تصمیم»). */
    var lastNote = ""
        private set

    fun reset() {
        atrHourly.clear(); ranges.clear(); rangesSum = 0.0; atrHourBucket = Long.MIN_VALUE
        atrP = 0.0; atrPctile = 0.5; expansion = 1.0; regime = 1
        ddPct = 0.0; recent20R = 0.0; lossStreak = 0; shapeState = 0
        shapeT = 0.0; shapeDD50 = 0.0
        fRegime = 1.0; fStress = 1.0; fShape = 1.0
        rejectedRegime = 0; rejectedBox = 0; lastNote = ""
    }

    /**
     * به‌روزرسانی وضعیت بازار با هر کندل بسته.
     * @param atrPctNow ATR/قیمت×۱۰۰ در همین لحظه (NaN اگر آماده نیست)
     */
    fun onBar(t: Long, high: Double, low: Double, close: Double, atrPctNow: Double) {
        if (high.isFinite() && low.isFinite() && high > low) {
            val r = high - low
            ranges.addLast(r); rangesSum += r
            while (ranges.size > WINDOW_BARS) rangesSum -= ranges.removeFirst()
        }
        if (atrPctNow.isFinite() && atrPctNow > 0.0 && close > 0.0) {
            atrP = atrPctNow
            val h1 = t / 3_600_000L
            if (h1 != atrHourBucket) {
                atrHourBucket = h1
                atrHourly.addLast(atrPctNow)
                while (atrHourly.size > WINDOW_HOURS) atrHourly.removeFirst()
            }
        }
        atrPctile = percentileOf(atrHourly, atrP)
        expansion = if (ranges.size >= WINDOW_BARS && rangesSum > 0.0) {
            var m60 = 0.0
            val n = ranges.size
            for (i in n - 60 until n) m60 += ranges[i]      // ۶۰ کندل اخیر
            m60 /= 60.0
            val mAll = rangesSum / n
            if (mAll > 0.0) m60 / mAll else 1.0
        } else 1.0
        regime = when {
            atrPctile < 0.35 && expansion < 0.90 -> 0
            atrPctile > 0.70 || expansion > 1.25 -> 2
            else -> 1
        }
    }

    /**
     * ضریب حجم بر اساس رژیم. سه حالت:
     *  آرام (۰): ۰٫۵ — بازار کم‌نوسان برای این استراتژی لبه ندارد و شب‌ماندن ریسک گپ است
     *  عادی (۱): ۱٫۰
     *  پرنوسان (۲): ۰٫۷۵ — ریسک گپ/لغزش بیشتر است
     */
    fun computeRegimeFactor() {
        fRegime = when (regime) {
            0 -> 0.50
            2 -> 0.75
            else -> 1.0
        }
    }

    /**
     * حداقل ATR% لازم برای ورود، بر اساس رژیم.
     * رژیم ۰ (آرام) سخت‌گیرانه‌تر است چون آزمون نشان داد در بازار آرام فیلترها آسیب می‌زنند.
     */
    fun requiredAtrPct(cfg: Settings): Double =
        if (regime == 0) cfg.cxRegimeGateCalm else cfg.cxRegimeGate

    /** آیا نوسان برای ورود در این رژیم کافی است؟ (گیت سخت L5-۲) */
    fun regimeAllows(cfg: Settings): Boolean {
        val need = requiredAtrPct(cfg)
        if (need <= 0.0) return true
        if (atrP <= 0.0) return true            // آماده‌نبودن داده نباید مانع شود
        return atrP >= need
    }

    /**
     * گیت ساختار: باکس ساختار باید به اندازهٔ کافی بزرگ باشد.
     * @param boxH ارتفاع باکس (دلار) · @param atr ATR ساختار (دلار)
     */
    fun boxAllows(cfg: Settings, boxH: Double, atr: Double): Boolean {
        if (cfg.cxBoxMinUnits <= 0.0) return true
        if (!atr.isFinite() || atr <= 0.0) return true
        return boxH >= cfg.cxBoxMinUnits * atr
    }

    /**
     * به‌روزرسانی وضعیت حساب از فهرست بازده معاملات بسته (بر حسب R).
     * @param equity سرمایهٔ جاری · @param peak اوج سرمایه
     */
    fun onAccount(equity: Double, peak: Double, closedR: List<Double>, cfg: Settings) {
        ddPct = if (peak > 0.0) max(0.0, (peak - equity) / peak * 100.0) else 0.0
        val last20 = if (closedR.size > 20) closedR.subList(closedR.size - 20, closedR.size) else closedR
        recent20R = if (last20.isEmpty()) 0.0 else last20.average()
        var s = 0
        for (i in closedR.indices.reversed()) {
            if (closedR[i] < 0.0) s++ else break
        }
        lossStreak = s
        // ── شکل منحنی: t آمارهٔ میانگین R پنجرهٔ ۵۰ معاملهٔ اخیر + عمق افت آن پنجره
        val n = closedR.size
        val wSize = min(50, n)
        val start = n - wSize
        shapeT = if (wSize >= 20) {
            var m = 0.0
            for (i in start until n) m += closedR[i]
            m /= wSize
            var v = 0.0
            for (i in start until n) v += (closedR[i] - m) * (closedR[i] - m)
            val sd = sqrt(v / (wSize - 1).coerceAtLeast(1))
            if (sd > 1e-9) m * sqrt(wSize.toDouble()) / sd else 0.0
        } else 0.0
        var cum = 0.0; var pk = 0.0; var dd = 0.0
        for (i in start until n) {
            cum += closedR[i]
            if (cum > pk) pk = cum
            dd = max(dd, pk - cum)
        }
        shapeDD50 = dd
        shapeState = when {
            shapeT <= -2.0 && shapeDD50 >= 8.0 -> 2
            shapeT <= -2.0 || shapeDD50 >= 12.0 -> 1
            else -> 0
        }
        // ── ضریب استرس: ملایم و پیوسته؛ هرگز صفر نمی‌شود (سیستم قفل نشود)
        var f = 1.0
        if (ddPct > 12.0) f -= 0.40
        if (ddPct > 20.0) f -= 0.15
        if (ddPct > 30.0) f -= 0.10
        if (recent20R < -0.25) f -= 0.10
        if (lossStreak >= 6) f -= 0.10
        fStress = f.coerceIn(cfg.cxStressFloor.coerceIn(0.05, 1.0), 1.0)
        // ── ضریب شکل منحنی
        fShape = when (shapeState) {
            2 -> 0.60
            1 -> 0.80
            else -> 1.0
        }
    }

    /** ضریب نهایی حجم = حاصل‌ضرب سه لایه، کلمپ‌شده در [کف, ۱]. */
    fun sizeFactor(cfg: Settings): Double {
        computeRegimeFactor()
        val floor = cfg.cxStressFloor.coerceIn(0.05, 1.0)
        return (fRegime * fStress * fShape).coerceIn(floor, 1.0)
    }

    /**
     * ضریب پهن‌کردن حد ضرر (L7). پیوسته و محدود — نه عدد جادویی.
     * شاهد: «باکس بزرگ‌تر» ‎+۰٫۱۵۴R‎ (CI [+۰٫۰۰۴,+۰٫۳۱۲]) و در شبیه‌سازی مسیر، حد ضررِ
     * کمی پهن‌تر بهتر بود؛ ولی جدول اثر غیریکنوا بود، پس با سقف سخت [cxSlWidenMax] می‌بندیم.
     */
    fun slWiden(cfg: Settings, boxH: Double, atr: Double): Double {
        if (cfg.cxMode < 3 || cfg.cxSlWidenMax <= 1.0) return 1.0
        var w = 1.0
        val av = if (atrP > 0.0) atrP else 0.0
        w += 0.15 * (((av - 0.30) / 0.30).coerceIn(0.0, 1.0))
        if (atr.isFinite() && atr > 0.0 && boxH > 0.0) {
            val rel = boxH / atr
            w += 0.10 * ((rel / 0.20 - 1.0).coerceIn(0.0, 1.0))
        }
        return w.coerceIn(1.0, cfg.cxSlWidenMax)
    }

    /** متن کارت تصمیم (یک خط) برای لاگ. */
    fun note(cfg: Settings, k: Double) {
        lastNote = "رژیم=$regime (صدک ${fmt2(atrPctile * 100)}٪ · انبساط ${fmt2(expansion)}) · " +
            "ATR%=${fmt2(atrP)} لازم=${fmt2(requiredAtrPct(cfg))} · " +
            "افت=${fmt2(ddPct)}٪ ۲۰معامله=${fmt2(recent20R)}R سری‌باخت=$lossStreak شکل=$shapeState · " +
            "ضریب حجم=${fmt2(k)}"
    }

    private fun fmt2(v: Double): String = if (v.isNaN()) "-" else String.format("%.2f", v)

    /** صدک [v] در [arr] (۰..۱) — مرتب‌سازی روی کپی؛ اندازه پنجره کوچک است. */
    private fun percentileOf(arr: ArrayDeque<Double>, v: Double): Double {
        val n = arr.size
        if (n < 20) return 0.5
        var below = 0
        for (x in arr) if (x <= v) below++
        return below.toDouble() / n
    }

    companion object {
        /** پنجرهٔ صدک ATR (ساعت). */
        const val WINDOW_HOURS = 300
        /** پنجرهٔ انبساط دامنه (کندل M1). */
        const val WINDOW_BARS = 1440
    }
}
