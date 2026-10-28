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
//
//  نسخهٔ ۱٫۵٫۰۲ — کارمزد از پروفایل بروکر می‌آید ([Brokers])، نه از عدد ثابت:
//  پیش‌فرض = آلپاری Pro ECN. هر پروفایل عدد رسمی خودش را دارد و واحدش هم همان واحد
//  `cmsnManual` اپ است (دلار بر اونس/بشکه در رفت‌وبرگشت)، پس هیچ تبدیلی در رابط لازم نیست.
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
    fun applyOilDefaults(s: Settings, cmsn: Double = DEFAULT_OIL_CMSN) {
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
        //  ↑ نگه‌داشتن ۸ فقط برای کارمزد مرجع ۰٫۳۰ درست است؛ [applyBrokerCost] همین را با
        //    کارمزد واقعی بروکر نرمال می‌کند تا آستانهٔ مطلق همان [OIL_COST_FLOOR_UNITS] بماند.
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
        // ── کارمزد واقعی بروکر ────────────────────────────────────────────────
        //  ۱٫۵٫۰۱ با فرض «۰٫۳ دلار بر بشکه» تنظیم شد؛ آن فرض از جدول رسمی بروکر
        //  بیرون نیامده بود. جدول واقعی آلپاری روی نفت ۰٫۰۱۵ (Pro ECN) تا ۰٫۰۷
        //  (Standard MT4) دلار بر بشکه است — ۴ تا ۲۰ برابر ارزان‌تر.
        //  از ۱٫۵٫۰۲ عدد کارمزد از [Brokers] می‌آید و پارامترهای زیر (که با فرض
        //  گران‌ترین حالت انتخاب شده بودند) عمداً دست‌نخورده مانده‌اند تا مبنای
        //  آزمون ۲۰سالهٔ ۱٫۵٫۰۱ حفظ شود؛ با کارمزد ارزان‌تر فقط بهتر عمل می‌کنند.
        s.cmsnAuto = false
        s.cmsnManual = cmsn
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

    /** کارمزد مرجع نفتی که پارامترهای ۱٫۵٫۰۱ با آن آزموده شدند (فقط برای مقایسه/گزارش). */
    const val DEFAULT_OIL_CMSN = 0.30

    /**
     * کف فاصلهٔ حد ضرر نفت بر حسب **واحد قیمت** که پارامترهای ۱٫۵٫۰۱ با آن آزموده شدند
     * (= ضریب فیلتر ۸ × کارمزد مرجع ۰٫۳۰).
     *
     * چرا این عدد ثابت می‌ماند: اندازه‌گیری روی ۲۰۲۲–۲۰۲۶ نشان داد وقتی فیلتر کارمزد با
     * کارمزد ارزان باز می‌شود، ۲۷۸ معاملهٔ کم‌کیفیت وارد می‌شوند و سود خالص به صفر می‌رسد
     * (سود ناخالص ≈ هزینه). پس با هر بروکری، *آستانهٔ مطلق* ۲٫۴ دلار حفظ می‌شود و ضریب
     * فیلتر فقط از نو نرمال می‌شود: `ضریب = ۲٫۴ ÷ کارمزد بروکر`.
     * یعنی کارمزد ارزان‌تر **هزینه** را کم می‌کند، نه اینکه اجازهٔ معامله‌های بد بدهد.
     */
    const val OIL_COST_FLOOR_UNITS = 2.4

    /**
     * پیش‌فرض طلا = همان تنظیم آزموده‌شدهٔ ۲۰سالهٔ [Tuned] — تنها کارمزد از بروکر می‌آید.
     * (پارامترهای طلا با کارمزد ۰٫۷ بهینه شده‌اند؛ با کارمزد کمتر آلپاری همان پارامترها
     * هزینهٔ کمتری می‌دهند، پس دست‌نخورده می‌مانند.)
     */
    fun applyGoldDefaults(s: Settings, cmsn: Double = Tuned.CMSN) {
        Tuned.apply(s, cmsn)
        s.cmsnAuto = false
        s.cmsnManual = cmsn
    }

    /**
     * اعمال پیش‌فرض‌های یک نماد **با کارمزد بروکر**.
     *
     * @param price قیمت مرجع (آخرین قیمت کندل/فید)؛ اگر ≤ ۰ باشد از [Brokers.fallbackPrice] استفاده می‌شود.
     * @param brokerId شناسهٔ پروفایل؛ `null` ⇒ همین [Settings.brokerId] و اگر آن هم ناشناس بود، پیش‌فرض آلپاری.
     * @return کارمزدی که واقعاً اعمال شد (دلار بر واحد قیمت، رفت‌وبرگشت).
     */
    fun applyDefaults(s: Settings, spec: SymbolSpec, price: Double = 0.0, brokerId: String? = null): Double {
        val profile = Brokers.byId(brokerId ?: s.brokerId) ?: Brokers.default
        val px = if (price > 0.0) price else Brokers.fallbackPrice(spec.id)
        val cost = Brokers.costPerUnit(profile, spec.id, px) ?: if (spec.oil) DEFAULT_OIL_CMSN else Tuned.CMSN
        if (spec.oil) applyOilDefaults(s, cost) else applyGoldDefaults(s, cost)
        s.brokerId = profile.id
        s.brokerRefPrice = px
        s.brokerAppliedAt = System.currentTimeMillis()
        return cost
    }

    /**
     * ست کردن پروفایل بروکر روی تنظیمات **بدون** دست‌زدن به پارامترهای استراتژی.
     * فقط کارمزد و فیلدهای بروکر عوض می‌شوند؛ بقیهٔ تنظیم دستی کاربر محترم می‌ماند.
     * @return کارمزد اعمال‌شده یا `null` اگر بروکر برای این نماد عدد رسمی نداشته باشد.
     */
    fun applyBrokerCost(s: Settings, spec: SymbolSpec, profile: BrokerProfile, price: Double): Double? {
        val px = if (price > 0.0) price else Brokers.fallbackPrice(spec.id)
        val cost = Brokers.costPerUnit(profile, spec.id, px) ?: return null
        // آستانهٔ مطلق فعلی فیلتر کارمزد (واحد قیمت) — قبل از عوض‌کردن کارمزد نگه داشته می‌شود
        val oldCost = if (s.cmsnAuto && s.cmsnAutoValue > 0.0) s.cmsnAutoValue else Math.max(0.0, s.cmsnManual)
        val oldFloor = if (oldCost > 0.0) s.minSlCostMult * oldCost else 0.0

        s.brokerId = profile.id
        s.brokerRefPrice = px
        s.brokerAppliedAt = System.currentTimeMillis()
        s.cmsnAuto = false
        s.cmsnManual = cost
        // سواپ شبانه هم از همان پروفایل می‌آید (۱٫۵٫۰۳)
        profile.symbol(spec.id)?.let { bs ->
            val sl = bs.swapLong ?: 0.0
            val ss = bs.swapShort ?: 0.0
            if (sl != 0.0 || ss != 0.0) {
                s.swapEnabled = true
                s.swapLong = sl
                s.swapShort = ss
            }
        }

        // ── نرمال‌کردن ضریب فیلتر کارمزد تا آستانهٔ **مطلق** تغییر نکند ─────────────
        //  چرا لازم است: اندازه‌گیری روی نفت ۲۰۲۲–۲۰۲۶ نشان داد اگر فقط کارمزد کم شود و
        //  ضریب ۸ بماند، آستانه از ۲٫۴ دلار به ۰٫۱۲ می‌آید، ۲۷۸ معاملهٔ کم‌کیفیت وارد
        //  می‌شوند و سود خالص به ≈صفر می‌رسد (۲۷۸ معامله · PF ۱٫۰۰ · افت ۴۴۴$).
        //  پس آستانهٔ آزموده‌شده دست‌نخورده می‌ماند و ضریب بازمحاسبه می‌شود:
        //      نفت: آستانهٔ ثابت [OIL_COST_FLOOR_UNITS] (۲٫۴ دلار)
        //      طلا: اگر کاربر فیلتر را روشن کرده باشد، همان آستانهٔ فعلی خودش
        if (cost > 0.0) {
            val floor = if (spec.oil) OIL_COST_FLOOR_UNITS else oldFloor
            if (floor > 0.0) s.minSlCostMult = Math.round(floor / cost * 10.0) / 10.0
        }
        return cost
    }
}
