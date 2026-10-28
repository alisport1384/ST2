package com.alisport.goldpin.core

// ═══════════════════════════════════════════════════════════════════════════════
//  نمادهای قابل معامله — طلا (XAUUSD) و نفت WTI (USOIL)
//
//  چرا این فایل: نسخهٔ ۱٫۴٫۵۲ فقط یک نماد می‌شناخت و همهٔ عددهای پیش‌فرض
//  (بافر حد ضرر بر حسب تیک، سقف N، گیت نوسان، اسپرد، پنجرهٔ ساعت ورود) برای
//  طلای ۲٬۰۰۰–۴٬۰۰۰ دلاری کالیبره شده‌اند. نفت حدود ۴۰ تا ۱۵۰ دلار است و
//  نوسان نسبی‌اش ۲ تا ۳ برابر طلا؛ با عددهای طلا، حد ضرر مطلق ۱۸ دلاری روی نفت
//  می‌شد ۲۵٪ قیمت. پس هر نماد «پروفایل عددی» خودش را دارد.
//
//  قاعدهٔ طلایی این نسخه: انتخاب نماد **فقط نمای برنامه** را عوض می‌کند؛
//  تشخیص و اجرا برای هر دو نماد هم‌زمان و مستقل ادامه دارد (Hub).
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * مشخصات یک نماد معاملاتی.
 *
 * @param id شناسهٔ پایدار (برای نام فایل ذخیره و کلید نقشهٔ نمادها — هرگز عوض نشود)
 * @param label نام فارسی برای رابط
 * @param feedSymbol نماد یاهو فایننس برای فید زنده و تاریخچهٔ آنلاین
 * @param unit واحد قیمت («اونس» / «بشکه»)
 * @param decimals تعداد رقم اعشار نمایش قیمت
 * @param tick اندازهٔ یک تیک قیمت (واحد پول)
 * @param oil پروفایل نفتی؟ (برای انتخاب پیش‌فرض‌ها و مسیر قیمت لحظه‌ای)
 */
data class SymbolSpec(
    val id: String,
    val label: String,
    val feedSymbol: String,
    val unit: String,
    val decimals: Int,
    val tick: Double,
    val oil: Boolean = false
) {
    /** عنوان کوتاه برای تب مدیریت */
    val short: String get() = if (oil) "نفت" else "طلا"
}

object Symbols {

    val GOLD = SymbolSpec(
        id = "XAUUSD",
        label = "طلا — XAUUSD (دلار/اونس)",
        feedSymbol = "GC=F",
        unit = "اونس",
        decimals = 2,
        tick = 0.01,
        oil = false
    )

    /**
     * نفت WTI با نماد یاهو `CL=F` (فیوچرز سبک شیرین) — همان مسیری که طلا با `GC=F` می‌رود.
     * قیمت‌ها دلار بر بشکه؛ یک تیک = ۰٫۰۱ دلار.
     */
    val OIL = SymbolSpec(
        id = "USOIL",
        label = "نفت WTI — USOIL (دلار/بشکه)",
        feedSymbol = "CL=F",
        unit = "بشکه",
        decimals = 2,
        tick = 0.01,
        oil = true
    )

    val ALL: List<SymbolSpec> = listOf(GOLD, OIL)

    fun byId(id: String?): SymbolSpec = ALL.firstOrNull { it.id == id } ?: GOLD

    fun indexOf(id: String?): Int = ALL.indexOfFirst { it.id == id }.coerceAtLeast(0)

    /**
     * پروفایل عددی نفت — همهٔ عددها از جاروب پارامتر روی دادهٔ **واقعی M1 نفت**
     * (۶٬۸۲۹٬۵۶۰ کندل · ۲۰۰۵-۰۱-۰۳ تا ۲۰۲۶-۱۰-۲۲ · اسپرد ۰٫۰۳ دلار بر بشکه) آمده‌اند:
     *
     *  • بافر حد ضرر ۶۰ تیک = ۰٫۶۰ دلار ≈ ۱٫۷× ATR ساعتی نفت (ATR% میانهٔ نفت ۰٫۵۱٪
     *    در برابر ۰٫۲۶٪ طلا). عدد طلا (۱۸۰۰ تیک = ۱۸ دلار) روی نفت می‌شد ۲۵٪ قیمت.
     *  • سقف N = ۲ واحد قیمت (طلا: ۸۰) — همان نسبت به ATR.
     *  • گیت نوسان ورود ۰٫۶۰٪ (طلا: ۰٫۲۵٪) — چون ATR٪ نفت ۲٫۳ برابر طلاست؛ با ۰٫۲۵
     *    نفت عملاً همیشه مجاز بود و نتیجه منفی می‌شد.
     *  • هدف تریگر ۲ = ۱۰R (طلا: ۷R) — نفت روندهای بلندتر و بازگشت‌های تندتر دارد.
     *  • اسپرد ۰٫۳۰ دلار بر بشکه (تصمیم مالک؛ طلا: ۰٫۷ دلار بر اونس). این «کارمزد» است، نه عدد ثابت:
     *    در کد کمیسیون = اسپرد × حجم × اندازهٔ قرارداد، پس عدد باید هم‌واحد قیمت نماد باشد.
     *    ⚠ صادقانه: ۰٫۳ دلار روی قیمت ۷۰ دلار یعنی ۰٫۴۳٪ قیمت — حدود ۱۸ برابر گران‌تر از
     *    نسبت طلا. با ۰٫۳ هر ترکیبی که حد ضرر باریک دارد شکست می‌خورد؛ به همین دلیل
     *    بافر ۲۰۰ تیک، سقف N=۸، فیلتر هزینه ۸× و هدف ۱۲R انتخاب شده‌اند.
     *    (این اعداد از جاروب ۳۸ ترکیب روی دادهٔ ۲۰ ساله بیرون آمده‌اند.)
     */
    fun applyOilDefaults(s: Settings) {
        s.strategy = Strategy.DOC
        s.mmMode = MoneyMode.MIXED
        s.trigger2Fade = true          // ✅ معکوس‌سازی تریگر ۲: تنها خانواده‌ای که روی نفت لبه دارد
        s.trigger2TargetR = 12.0       // ✅ هدف ۱۲R — با اسپرد ۰٫۳، فقط هدف‌های بزرگ زنده می‌مانند
        s.tpUnits = 10.0
        s.minTPunits = 1.0
        s.maxSlUnits = 8.0             // ✅ سقف N = ۸ واحد قیمت (طلا: ۸۰)
        s.maxSlMode = 0
        s.slBufMode = 0
        s.slBufTicks = 200.0           // ✅ بافر = ۲٬۰ دلار (طلا: ۱۸ دلار)
        s.entryBufTicks = -2.0
        s.minSlUnits = 0.0
        s.minSlCostMult = 8.0          // ✅ کلید اصلی: R باید ≥ ۸ × اسپرد باشد (≥ ۲٫۴ دلار)
        s.mintick = 0.01
        s.slipTicks = 1.0
        s.minAtrPct = 0.70             // ✅ ورود فقط در نوسان ≥ ۰٫۷۰٪ (طلا: ۰٫۲۵٪)
        s.entryHourFrom = 5
        s.entryHourTo = 15
        s.mgmtMode = 1
        s.mgmtHoldMinR = Tuned.MGMT_HOLD_MIN_R
        s.mgmtHoldMaxDays = Tuned.MGMT_HOLD_DAYS
        s.mgmtTrailStartR = Tuned.MGMT_TRAIL_START_R
        s.mgmtTrailDistR = Tuned.MGMT_TRAIL_DIST_R
        s.mgmtVolGatePct = Tuned.MGMT_VOL_GATE_PCT
        // ── کارمزد واقعی بروکر نفت: ۰٫۳ دلار بر بشکه ─────────────────────────
        //  ۰٫۳ روی قیمت ۷۰ دلار = ۰٫۴۳٪ قیمت؛ در مقابل ۰٫۷ روی طلای ۳٬۰۰۰ = ۰٫۰۲۳٪.
        //  یعنی کارمزد نفت ~۱۸ برابر سنگین‌تر است. تنها راه زنده‌ماندن:
        //  حد ضرر پهن (≥ ۲٫۴ دلار) + هدف بزرگ (۱۲R) + فیلتر هزینه ۸× + معاملهٔ کم.
        s.cmsnAuto = false
        s.cmsnManual = 0.30
        // موتور ترکیبی روی نفت: فقط لایهٔ گیت رژیم سود می‌دهد
        s.cxMode = 1
        s.cxRegimeGate = 0.60
        s.cxRegimeGateCalm = 0.65
        s.cxBoxMinUnits = 0.10
        s.cxSizeMode = 1
        s.cxCompoundCap = 1.5
        s.cxStressFloor = 1.0
        s.cxSlWidenMax = 1.15
        s.cxTrailRegime2 = 1.10
        s.useRiskPct = true
        s.riskPct = 1.0
        s.initialEquity = 100000.0
        s.contractSize = 1.0
    }

    /** اعمال پیش‌فرض‌های مخصوص نماد (طلا = همان پیش‌فرض‌های ۱٫۴٫۵۲، بدون تغییر). */
    fun applyDefaults(s: Settings, spec: SymbolSpec) {
        if (spec.oil) applyOilDefaults(s)
    }
}
