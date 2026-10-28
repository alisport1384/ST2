package com.alisport.goldpin.core

// ═══════════════════════════════════════════════════════════════════════════════
//  بروکر و هزینهٔ واقعی معامله — نسخهٔ ۱٫۵٫۰۲
//
//  چرا این فایل: تا ۱٫۵٫۰۱ «کارمزد» یک عدد دستی بود (طلا ۰٫۷ · نفت ۰٫۳) که مالک
//  وارد می‌کرد. آن اعداد با جدول رسمی بروکر جور نبود: آلپاری روی نفت ۰٫۰۱۵ تا ۰٫۰۷
//  دلار بر بشکه می‌گیرد، نه ۰٫۳. عدد اشتباه، همهٔ آستانه‌های فیلتر کارمزد و در نتیجه
//  کل تنظیم پارامترها را جابه‌جا می‌کند (همان کاری که در ۱٫۵٫۰۱ کرد و پارامترها را
//  بی‌دلیل بی‌ش‌رأی محافظه‌کار کرد).
//
//  قاعدهٔ این فایل:
//   ۱) هر بروکر یک «پروفایل» است با عددهای **رسمی خودش** برای هر نماد:
//      حداقل اسپرد (پوینت) + کمیسیون هر طرف (دلار بر میلیون ارزش اسمی) + سواپ شبانه.
//   ۲) تبدیل به واحد اپ (همان `cmsnManual` = دلار بر واحد قیمت در رفت‌وبرگشت) یک‌جا و
//      آزمون‌پذیر در همین فایل انجام می‌شود، نه در ذهن کاربر:
//         هزینه = اسپرد + ۲ × قیمت × (نرخ کمیسیون ÷ ۱٬۰۰۰٬۰۰۰)
//      توجه: بخش کمیسیون **مستقل از اندازهٔ قرارداد** است (صورت و مخرج هم را حذف
//      می‌کنند)، پس با هر اندازهٔ لات همان عدد می‌ماند.
//   ۳) برای بروکرهای آینده فقط یک [BrokerProfile] تازه به [Brokers.ALL] اضافه می‌شود؛
//      هیچ‌جای دیگر کد لازم نیست عوض شود (خواستهٔ مالک: «در آینده چند بروکر وصل کنم»).
//
//  منبع عددهای آلپاری: ویجت رسمی Contract Specifications خود آلپاری
//  (`https://alpari.com/en/trading-terms/fees/contract-specifications/`)، ردیف هر نوع
//  حساب، دادهٔ اعمال‌شده در ۲۰۲۶-۱۰-۱۴. جزئیات و جدول کامل: `docs/ALPARI-KARMOZD-VA-SPREAD.md`.
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * هزینهٔ یک نماد در یک نوع حساب.
 *
 * @param symbolId شناسهٔ نماد اپ ([Symbols.GOLD.id] / [Symbols.OIL.id])
 * @param ticker نام نماد در پلتفرم بروکر (برای نمایش)
 * @param spreadPoints حداقل اسپرد رسمی بر حسب پوینت (`null` = بروکر منتشر نکرده)
 * @param pointSize اندازهٔ یک پوینت بر حسب واحد قیمت (طلا ۰٫۰۱ دلار/اونس · نفت ۰٫۰۱ دلار/بشکه)
 * @param contractSize اندازهٔ قرارداد یک لات (طلا ۱۰۰ اونس · نفت ۱٬۰۰۰ بشکه)
 * @param commissionPerMillionPerSide کمیسیون هر طرف: دلار به‌ازای هر ۱٬۰۰۰٬۰۰۰ دلار ارزش اسمی
 * @param swapLong swap خرید: دلار بر هر **واحد** (اونس/بشکه) در هر شب
 * @param swapShort swap فروش: همان واحد
 */
data class BrokerSymbol(
    val symbolId: String,
    val ticker: String,
    val spreadPoints: Double?,
    val pointSize: Double,
    val contractSize: Double,
    val commissionPerMillionPerSide: Double,
    val swapLong: Double?,
    val swapShort: Double?
) {
    /** اسپرد بر حسب واحد قیمت (دلار بر اونس/بشکه) — `null` اگر بروکر عددی منتشر نکرده باشد. */
    val spreadPerUnit: Double? get() = spreadPoints?.let { it * pointSize }

    /**
     * بخش کمیسیون رفت‌وبرگشت بر حسب واحد قیمت.
     *
     * کمیسیون = ۲ طرف × (قیمت × اندازهٔ قرارداد × نرخ ÷ ۱٬۰۰۰٬۰۰۰) دلار در هر لات؛
     * تقسیم بر اندازهٔ قرارداد ⇒ ۲ × قیمت × نرخ ÷ ۱٬۰۰۰٬۰۰۰ **دلار بر واحد**.
     * پس مستقل از اندازهٔ قرارداد است و فقط با قیمت بالا/پایین می‌رود.
     */
    fun commissionPerUnit(price: Double): Double =
        2.0 * price * commissionPerMillionPerSide / 1_000_000.0

    /** کل هزینهٔ رفت‌وبرگشت بر حسب واحد قیمت — همان واحد `cmsnManual` در [Settings]. */
    fun costPerUnit(price: Double): Double? =
        spreadPerUnit?.let { it + commissionPerUnit(price) }

    /** کل هزینه به درصد قیمت (برای مقایسهٔ بروکرها با هم). */
    fun costPct(price: Double): Double? =
        costPerUnit(price)?.let { if (price > 0.0) it / price * 100.0 else null }

    /** هزینهٔ کل یک لات رفت‌وبرگشت به دلار. */
    fun costPerLot(price: Double): Double? = costPerUnit(price)?.let { it * contractSize }
}

/**
 * یک بروکر + یک نوع حساب. `id` پایدار است (کلید ذخیره — هرگز عوض نشود).
 */
data class BrokerProfile(
    val id: String,
    val brokerFa: String,
    val accountFa: String,
    val platformFa: String,
    val dataDate: String,
    val sourceUrl: String,
    val noteFa: String,
    val symbols: Map<String, BrokerSymbol>
) {
    val labelFa: String get() = "$brokerFa — $accountFa ($platformFa)"
    fun symbol(symbolId: String): BrokerSymbol? = symbols[symbolId]
}

object Brokers {

    /** بروکر مرجع فعلی (تصمیم مالک: «فعلاً مرجع آلپاری باشد»). */
    const val DEFAULT_ID = "alpari-pro-ecn-mt5"

    /** شناسهٔ حالت «کارمزد دستی» — تنظیم کاربر دست‌نخورده می‌ماند. */
    const val CUSTOM_ID = "custom"

    const val DATA_DATE = "2026-10-14"
    const val SOURCE_URL = "https://alpari.com/en/trading-terms/fees/contract-specifications/"

    private const val GOLD_COMM = 18.0     // دلار بر میلیون ارزش اسمی، هر طرف (فلزات Pro ECN)
    private const val OIL_COMM = 25.0      // دلار بر میلیون ارزش اسمی، هر طرف (کالاها Pro ECN)

    // ── سواپ شبانه (واحد اصلی بروکر → تبدیل به دلار بر واحد) ───────────────────
    // طلا: «By points» ⇒ ۶۳٫۶۷ پوینت × ۰٫۰۱ = ۰٫۶۳۶۷ دلار بر اونس در شب (= ۶۳٫۶۷ دلار در لات).
    // نفت: «By dollars»  ⇒ ۷۵٫۸۰ دلار بر لات در شب ÷ ۱٬۰۰۰ = ۰٫۰۷۵۸ دلار بر بشکه در شب.
    private const val GOLD_SWAP_LONG = -0.6367
    private const val GOLD_SWAP_SHORT = +0.4101
    private const val OIL_SWAP_LONG = +0.03432
    private const val OIL_SWAP_SHORT = -0.07580

    private fun gold(
        ticker: String,
        spreadPoints: Double?,
        comm: Double
    ) = BrokerSymbol(
        symbolId = Symbols.GOLD.id, ticker = ticker,
        spreadPoints = spreadPoints, pointSize = 0.01, contractSize = 100.0,
        commissionPerMillionPerSide = comm,
        swapLong = GOLD_SWAP_LONG, swapShort = GOLD_SWAP_SHORT
    )

    private fun oil(
        ticker: String,
        spreadPoints: Double?,
        comm: Double
    ) = BrokerSymbol(
        symbolId = Symbols.OIL.id, ticker = ticker,
        spreadPoints = spreadPoints, pointSize = 0.01, contractSize = 1000.0,
        commissionPerMillionPerSide = comm,
        swapLong = OIL_SWAP_LONG, swapShort = OIL_SWAP_SHORT
    )

    // ═══ آلپاری — Pro ECN MT5 (ارزان‌ترین حساب، پیش‌فرض) ══════════════════════
    val ALPARI_PRO_ECN_MT5 = BrokerProfile(
        id = DEFAULT_ID, brokerFa = "آلپاری", accountFa = "Pro ECN", platformFa = "MT5",
        dataDate = DATA_DATE, sourceUrl = SOURCE_URL,
        noteFa = "خام‌ترین اسپرد + کمیسیون: فلزات ۱۸ دلار و کالاها ۲۵ دلار به‌ازای هر یک میلیون " +
            "دلار ارزش اسمی (هر طرف). حداقل واریز ۵۰۰ دلار. ارزان‌ترین حالت برای هر دو نماد.",
        symbols = mapOf(
            Symbols.GOLD.id to gold("XAUUSD", 9.08, GOLD_COMM),
            Symbols.OIL.id to oil("WTI", 1.0, OIL_COMM)
        )
    )

    // ═══ آلپاری — Pro ECN MT4 (اسپرد طلا یکسان؛ اسپرد نفت را آلپاری منتشر نکرده) ═══
    val ALPARI_PRO_ECN_MT4 = BrokerProfile(
        id = "alpari-pro-ecn-mt4", brokerFa = "آلپاری", accountFa = "Pro ECN", platformFa = "MT4",
        dataDate = DATA_DATE, sourceUrl = SOURCE_URL,
        noteFa = "همان کمیسیون Pro ECN. ⚠ آلپاری برای WTI در MT4 عدد حداقل اسپرد منتشر نکرده است؛ " +
            "تا وقتی خودتان در MT5/ترمینال چک نکرده‌اید از این پروفایل برای نفت استفاده نکنید.",
        symbols = mapOf(
            Symbols.GOLD.id to gold("XAUUSD", 9.08, GOLD_COMM),
            Symbols.OIL.id to oil("WTI", null, OIL_COMM)
        )
    )

    // ═══ آلپاری — ECN MT4 (بدون کمیسیون، اسپرد متوسط) ═════════════════════════
    val ALPARI_ECN_MT4 = BrokerProfile(
        id = "alpari-ecn-mt4", brokerFa = "آلپاری", accountFa = "ECN", platformFa = "MT4",
        dataDate = DATA_DATE, sourceUrl = SOURCE_URL,
        noteFa = "بدون کمیسیون؛ همهٔ هزینه داخل اسپرد. حداقل واریز ۳۰۰ دلار.",
        symbols = mapOf(
            Symbols.GOLD.id to gold("XAUUSD", 31.9, 0.0),
            Symbols.OIL.id to oil("WTI", 3.0, 0.0)
        )
    )

    // ═══ آلپاری — Standard MT5 ═════════════════════════════════════════════════
    val ALPARI_STANDARD_MT5 = BrokerProfile(
        id = "alpari-standard-mt5", brokerFa = "آلپاری", accountFa = "Standard", platformFa = "MT5",
        dataDate = DATA_DATE, sourceUrl = SOURCE_URL,
        noteFa = "بدون کمیسیون، اسپرد پهن‌تر (حساب رایج خرده‌فروشی). حداقل واریز ۱۰۰ دلار.",
        symbols = mapOf(
            Symbols.GOLD.id to gold("XAUUSD", 35.9, 0.0),
            Symbols.OIL.id to oil("WTI", 6.0, 0.0)
        )
    )

    // ═══ آلپاری — Standard MT4 ═════════════════════════════════════════════════
    val ALPARI_STANDARD_MT4 = BrokerProfile(
        id = "alpari-standard-mt4", brokerFa = "آلپاری", accountFa = "Standard", platformFa = "MT4",
        dataDate = DATA_DATE, sourceUrl = SOURCE_URL,
        noteFa = "بدون کمیسیون؛ اسپرد نفت یک پوینت از Standard MT5 پهن‌تر است.",
        symbols = mapOf(
            Symbols.GOLD.id to gold("XAUUSD", 35.9, 0.0),
            Symbols.OIL.id to oil("WTI", 7.0, 0.0)
        )
    )

    /** همهٔ پروفایل‌های شناخته‌شده — بروکر بعدی فقط یک ردیف تازه در همین فهرست است. */
    val ALL: List<BrokerProfile> = listOf(
        ALPARI_PRO_ECN_MT5,
        ALPARI_PRO_ECN_MT4,
        ALPARI_ECN_MT4,
        ALPARI_STANDARD_MT5,
        ALPARI_STANDARD_MT4
    )

    val default: BrokerProfile get() = ALL.first { it.id == DEFAULT_ID }

    /** پروفایل با شناسه؛ `null` = ناشناس یا حالت «دستی». */
    fun byId(id: String?): BrokerProfile? = ALL.firstOrNull { it.id == id }

    fun isCustom(id: String?): Boolean = id == null || id == CUSTOM_ID || byId(id) == null

    /**
     * قیمت جانشین وقتی قیمت زنده/آخرین کندل در دسترس نیست.
     * فقط برای ساختن عدد کارمزد در لحظهٔ «ست کردن بروکر» استفاده می‌شود؛ خودِ ذخیره
     * قیمتِ واقعیِ همان لحظه را نگه می‌دارد ([Settings.brokerRefPrice]).
     */
    fun fallbackPrice(symbolId: String): Double =
        if (Symbols.byId(symbolId).oil) 100.0 else 4000.0

    /** کارمزد رفت‌وبرگشت بر حسب واحد قیمت (واحد `cmsnManual`)؛ `null` اگر عدد رسمی نبود. */
    fun costPerUnit(profile: BrokerProfile?, symbolId: String, price: Double): Double? {
        val p = profile ?: return null
        val px = if (price > 0.0) price else fallbackPrice(symbolId)
        return p.symbol(symbolId)?.costPerUnit(px)
    }

    /** خلاصهٔ فارسی یک پروفایل برای یک نماد (برای رابط و گزارش). */
    fun summaryFa(profile: BrokerProfile, symbolId: String, price: Double): String {
        val s = profile.symbol(symbolId) ?: return "این بروکر برای ${Symbols.byId(symbolId).short} عددی ندارد"
        val px = if (price > 0.0) price else fallbackPrice(symbolId)
        val sp = s.spreadPerUnit
        val cm = s.commissionPerUnit(px)
        val tot = s.costPerUnit(px)
        val unit = Symbols.byId(symbolId).unit
        if (tot == null) {
            return "${s.ticker}: اسپرد رسمی منتشر نشده (کمیسیون ${Fa2.n(cm, 4)} دلار بر $unit)"
        }
        return "${s.ticker} @ ${Fa2.n(px, 2)}: اسپرد ${Fa2.n(sp ?: 0.0, 4)} + کمیسیون " +
            "${Fa2.n(cm, 4)} = **${Fa2.n(tot, 4)} دلار بر $unit** رفت‌وبرگشت " +
            "(${Fa2.n(tot * s.contractSize, 2)} دلار در لات · ${Fa2.n(tot / px * 100.0, 4)}٪ قیمت)"
    }

    /**
     * خط سواپ — از ۱٫۵٫۰۳ سواپ شبانه **واقعاً** در موتور محاسبه می‌شود
     * ([com.alisport.goldpin.core.PaperBroker] · `accrueSwap`)، پس این متن باید
     * قاعده‌ها را بگوید، نه اینکه بگوید حساب نمی‌شود.
     */
    fun swapWarningFa(profile: BrokerProfile, symbolId: String): String {
        val s = profile.symbol(symbolId) ?: return ""
        val unit = Symbols.byId(symbolId).unit
        val l = s.swapLong ?: return ""
        val sh = s.swapShort ?: return ""
        return "سواپ شبانه: خرید ${Fa2.n(l, 4)} · فروش ${Fa2.n(sh, 4)} دلار بر $unit در هر شب " +
            "(سه‌برابر در چهارشنبه · شب شنبه و یکشنبه نداریم چون بازار بسته است) — در بک‌تست، " +
            "در «سود/زیان خالص» و افت سرمایه حساب می‌شود."
    }
}

/** قالب‌بندی سادهٔ عدد با رقم اعشار مشخص — برای موتور بدون وابستگی به لایهٔ UI. */
internal object Fa2 {
    fun n(v: Double, d: Int): String {
        if (v.isNaN() || v.isInfinite()) return "—"
        val neg = v < 0
        val b = java.math.BigDecimal(Math.abs(v).toString()).setScale(d, java.math.RoundingMode.HALF_UP)
        val s = b.toPlainString()
        return (if (neg) "−" else "") + s
    }
}
