package com.alisport.goldpin.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// ═══════════════════════════════════════════════════════════════════════════════
//  کارگزار کاغذی (Paper) — معادل ⑮ در Pine
//  ثبت سفارش لیمیت روی لبهٔ ناحیهٔ ولوم زیاد ، پر شدن ، خروج پله‌ای ۳۳/۳۳/۳۴ ،
//  سر‌به‌سر بعد از TP1 ، انقضای سفارش ، و محاسبهٔ سود/زیان به دلار و R.
//
//  §4-8 / §10 / §18-12 / §18-13 سند: در هر لحظه حداکثر ۱ سفارش (معلق یا باز)؛ وجود معاملهٔ باز
//  مانع مسلح‌شدن سفارش جدید است (استراتژی ۱). استراتژی ۲: از هر جهت فقط یکی.
//
//  §۲۰ سند: مدیریت مالی و مدیریت زیان (ManageAllLost / Mixed) در [MoneyManager]؛
//  حجم سفارش هنگام ثبت (مسلح‌شدن) با Lost/cmsnLost لحظهٔ ثبت ثابت می‌شود.
//
//  حسابداری — هر مفهوم دقیقاً یک متغیر و یک جای به‌روزرسانی دارد:
//    • [balance] (= Capital سند) = سرمایهٔ اولیه + سود/زیان خالص معاملاتِ **کاملاً بسته‌شده**.
//      فقط در [closeAll] عوض می‌شود — همان لحظه‌ای که Lost / cmsnLost هم عوض می‌شوند.
//    • [equity] = balance + سود/زیان معاملات باز (پله‌های خروج‌شده − کمیسیون + شناور).
//      فقط در [refreshEquity] محاسبه می‌شود؛ پایهٔ سقف اهرم و حجم §۵ است.
//    • کمیسیون هر معامله در [Trade.commission] می‌ماند و در سود/زیان همان معامله حساب می‌شود (نه جداگانه از موجودی).
// ═══════════════════════════════════════════════════════════════════════════════
class PaperBroker(private val cfg: Settings) {

    val orders = ArrayList<Order>()
    val trades = ArrayList<Trade>()

    /** §۲۰ سند: Lost / cmsnLost / اسپرد / فرمول Lot. */
    val mm = MoneyManager(cfg)

    var balance = cfg.initialEquity
    var equity = cfg.initialEquity
    var maxEquity = cfg.initialEquity
    var maxDrawdown = 0.0
    var orderSeq = 0L
    var tradeSeq = 0L
    var lastError: String? = null
    var engine: Engine? = null

    // ── نگهبان منحنی سرمایه (CurveGuard) — نسخهٔ ۱٫۴٫۴۹ ───────────────────────
    // ── سیستم مدیریت معاملهٔ باز — نسخهٔ ۱٫۴٫۵۱ ────────────────────────────────
    /**
     * وضعیت مدیریتِ هر معاملهٔ باز (کلید = شناسهٔ معامله).
     *
     * عمداً بیرون از کلاس [Trade] نگه داشته می‌شود: [Trade] در فایل نشست ذخیره می‌شود و
     * افزودن فیلد تازه، خواندن فایل‌های قدیمی را می‌شکند. با بازیابی نشست، این وضعیت از نو
     * ساخته می‌شود (سقف سود از کندل‌های بعد از بازیابی دیده می‌شود؛ حد ضرر و حد سود درست‌اند).
     */
    class MgmtState {
        /** آیا برای این معامله حالت هوشمند فعال است (دروازهٔ نوسان در لحظهٔ ورود پاس شده). */
        var active = false
        /** درصد ATR/قیمت در لحظهٔ ورود (برای گزارش و بازبینی). */
        var atrPct = 0.0
        /** بالاترین حد سود دیده‌شده بر حسب R (بر اساس کلوز کندل‌های بستهٔ ساعتی). */
        var peakR = 0.0
        /** سطح حد ضرر تریلینگ بر حسب R (NaN = هنوز فعال نشده). */
        var trailR = Double.NaN
        /** آخرین سقفِ ثبت‌شده برای سطل ساعت جاری + کلوز آخرین کندل (برای تشخیص بسته‌شدن H1). */
        var h1Bucket = Long.MIN_VALUE
        var prevClose = Double.NaN
        /** چند روز اضافه این معامله نگه داشته شده است. */
        var extraDays = 0
        /** تعداد بارهایی که در پایان روز به‌جای بسته‌شدن نگه داشته شد (برای شمارندهٔ UI). */
        var heldAtEod = 0
    }

    val mgmt = HashMap<Long, MgmtState>()
    /** آخرین روزی (شمارهٔ روز UTC) که بلوک «پایان زمان» اجرا شد — جلوگیری از اجرای تکراری. */
    private var lastEodDay = Long.MIN_VALUE
    /** شمارنده‌های نمایشی: چند معامله در پایان روز نگه داشته شد / چند مورد با تریل بسته شد. */
    var mgmtHeld = 0
        private set
    var mgmtTrailExits = 0
        private set
    /** چند معامله با مدیریت هوشمند باز شد / چند معامله به‌خاطر حالت فید عمداً کلاسیک ماند. */
    var mgmtSmartOn = 0
        private set
    var mgmtFadeClassic = 0
        private set
    /** آخرین آستانهٔ دروازهٔ نوسان که واقعاً استفاده شد (درصد) — برای نمایش در گزارش و UI. */
    var mgmtGateLast = 0.0
        private set
    /**
     * نمونه‌های ATR% برای **دروازهٔ نوسان تطبیقی**: یک نمونه در هر ساعت، فقط آخرین
     * [Tuned.MGMT_ADAPT_WINDOW] ساعت نگه داشته می‌شود.
     *
     * چرا صدک و نه عدد ثابت: آستانهٔ ثابت ۰٫۳۰٪ با نوسان طلای ۲۰۲۰ به‌بعد کالیبره شده است و
     * در دههٔ ۲۰۰۶–۲۰۱۶ (که ATR% ساختاراً متفاوت بود) معنایش عوض می‌شود. دروازهٔ صدکی خودش را
     * با رژیم جاری بازار هم‌تراز می‌کند و در آزمون ۲۰ساله هم بهتر بود (۰٫۰۳۹۷R در برابر ۰٫۰۳۶۸R).
     */
    private val atrSamples = ArrayDeque<Double>()
    private var atrSampleH1 = Long.MIN_VALUE

    /** ضریب حجمِ جاری نگهبان: ۱ = عادی · [Settings.guardRiskScale] = محافظه‌کار · ۰ = توقف ورود تازه. */
    var guardScale = 1.0
        private set
    /** آخرین افت اندازه‌گیری‌شده از اوج سرمایه (درصد) — برای پنل/گزارش. */
    var guardDdPct = 0.0
        private set
    /** قفل محافظه‌کاری: وقتی افت از آستانهٔ نرم گذشت روشن می‌شود و فقط با اوج تازه پاک می‌شود. */
    private var guardLatched = false

    /**
     * به‌روزرسانی وضعیت نگهبان از افت فعلی سرمایه.
     *
     * منطق: افت ≥ [Settings.guardDDPct] ⇒ ریسک × [Settings.guardRiskScale]؛ افت ≥
     * [Settings.guardHardDDPct] (فقط مود ۲) ⇒ توقف کامل ورود تازه. با [Settings.guardResetAtHigh]
     * قفل محافظه‌کاری فقط با رسیدن سرمایه به اوج تازه باز می‌شود (سخت‌گیرانه‌ترین حالت)؛
     * در غیر این صورت با کم‌شدن افت زیر آستانه.
     *
     * ⚠ این نگهبان «لبه» نمی‌سازد؛ فقط شدت افت را کم می‌کند. اگر انتظار سیستم منفی باشد،
     * فقط کندتر ضرر می‌دهد — همین را در گزارش‌ها هم صریح نوشته‌ایم.
     */
    private fun updateGuard() {
        if (cfg.guardMode == 0) { guardScale = 1.0; guardDdPct = 0.0; guardLatched = false; return }
        val peak = if (maxEquity > 0.0) maxEquity else cfg.initialEquity
        val dd = if (peak > 0.0) (peak - equity) / peak * 100.0 else 0.0
        guardDdPct = dd
        if (dd <= 1e-9) guardLatched = false
        if (dd >= cfg.guardDDPct) guardLatched = true
        if (!cfg.guardResetAtHigh) guardLatched = dd >= cfg.guardDDPct
        val hard = if (cfg.guardMode >= 2) cfg.guardHardDDPct else Double.MAX_VALUE
        guardScale = when {
            dd >= hard -> 0.0
            guardLatched -> cfg.guardRiskScale.coerceIn(0.01, 1.0)
            else -> 1.0
        }
    }

    /** آیا نگهبان اجازهٔ ثبت سفارش تازه می‌دهد؟ (توقف کامل = ریسک صفر) */
    fun guardAllowsNewEntry(): Boolean {
        updateGuard()
        return guardScale > 0.0
    }

    /** سفارش‌های معلق و پوزیشن‌های باز (استراتژی ۱: حداکثر یکی؛ استراتژی ۲: از هر جهت یکی). */
    private val pendings = ArrayList<Order>()
    private val opens = ArrayList<Trade>()

    /** تعداد سفارش معلق + معاملهٔ باز؛ با [dir] فقط همان جهت (۱ خرید / ‎−۱ فروش). */
    fun activeCount(dir: Int? = null): Int =
        pendings.count { dir == null || it.dir == dir } + opens.count { it.open && (dir == null || it.dir == dir) }

    /** آیا در جهت [dir] به سقف «حداکثر ستاپ هم‌زمان» رسیده‌ایم؟ */
    fun hasActiveOrder(dir: Int): Boolean = activeCount(dir) >= max(1, cfg.maxSetups)

    /** سازگاری با کدهای قدیمی که یک سفارش/پوزیشن انتظار داشتند (اولین مورد). */
    val hasOpenTrade: Boolean get() = opens.isNotEmpty()
    val hasPending: Boolean get() = pendings.isNotEmpty()
    val openTrade: Trade? get() = opens.firstOrNull { it.open }
    val pendingOrder: Order? get() = pendings.firstOrNull()
    val openTrades: List<Trade> get() = opens.filter { it.open }
    val pendingOrders: List<Order> get() = pendings.toList()

    var logSink: ((String, String, String?) -> Unit)? = null
    var alertSink: ((String, String, String) -> Unit)? = null

    private fun lg(cat: String, msg: String, data: String? = null) { logSink?.invoke(cat, msg, data) }

    /** وضعیت موتور ترکیبی (۱٫۴٫۵۲) — رژیم بازار، استرس حساب و ضریب‌های تصمیم. */
    val cx = CompositeState()
    private fun fmt(v: Double): String = if (v.isNaN()) "-" else String.format("%.2f", v)
    private fun alarm(kind: String, title: String, body: String) { alertSink?.invoke(kind, title, body) }

    fun reset() {
        orders.clear(); trades.clear(); pendings.clear(); opens.clear()
        balance = cfg.initialEquity; equity = cfg.initialEquity
        maxEquity = cfg.initialEquity; maxDrawdown = 0.0
        guardScale = 1.0; guardDdPct = 0.0; guardLatched = false
        // شمارندهٔ شناسه‌ها هم از صفر (مثل شمارندهٔ ستاپ‌های موتور)؛ وگرنه بعد از هر بازسازی شمارهٔ
        // سفارش/معامله ادامه پیدا می‌کرد (#۲۵، #۵۰ …) و دو اجرای یکسان ظاهراً یکسان نبودند.
        orderSeq = 0L; tradeSeq = 0L
        lastError = null
        mm.reset()
    }

    /** مارجینِ در استفاده توسط سفارش‌های معلق و پوزیشن‌های باز. */
    private fun usedMargin(px: Double): Double {
        var u = 0.0
        for (o in pendings) u += o.qty * o.price
        for (t in opens) if (t.open) u += t.fills.filter { it.kind == "ENTRY" }.sumOf { it.qty } * t.entry
        return u
    }

    /** §5: سقف حجمِ باقی‌ماندهٔ مجاز با اهرم [leverage] — (equity × اهرم − مارجین مصرف‌شده) ÷ قیمت. */
    private fun marginCapQty(px: Double, leverage: Double): Double {
        if (px <= 0) return 0.0
        val capMargin = equity * leverage
        val availMargin = max(0.0, capMargin - usedMargin(px))
        return availMargin / px
    }

    /** حجم بر اساس ریسک یا درصد سرمایه — با درنظرگرفتن مارجین مصرف‌شده. */
    /**
     * حجم در حالت موتور ترکیبی (۱٫۴٫۵۲).
     *
     * چرا جدا از [qtyFor]: `qtyFor` همیشه از `equity` استفاده می‌کند ⟹ مرکب کامل. روی ۲۰ سال
     * همین مرکب با ریسک ۱٪ نتیجهٔ ‎−۲٫۹٪‎ با افت ۴۶٪ داد، در حالی که اندازهٔ ثابت ‎+۲٫۵٪‎ با
     * افت ۱۰٪. پس پیش‌فرض «مرکب سقف‌دار» است: سرمایه تا [Settings.cxCompoundCap]× پایه در
     * حجم اثر می‌کند و بعد از آن حجم از سود قفل می‌شود (دنبالهٔ زیان بُریده می‌شود).
     */
    fun qtyForCx(px: Double, sl: Double): Double {
        if (cfg.cxMode <= 1) return qtyFor(px, sl)
        if (px <= 0.0) return 0.0
        val risk = abs(px - sl)
        if (risk <= 0.0) return 0.0
        val base = cfg.initialEquity
        val effEquity = when (cfg.cxSizeMode) {
            0 -> base                                                       // اندازهٔ ثابت
            1 -> min(equity, base * cfg.cxCompoundCap.coerceAtLeast(0.1))   // مرکب سقف‌دار
            else -> equity                                                  // مرکب کامل
        }
        val f = cx.sizeFactor(cfg)
        var q = if (cfg.useRiskPct) effEquity * cfg.riskPct / 100.0 / risk
        else effEquity * cfg.equityPct / 100.0 / px
        q *= f
        q = min(q, marginCapQty(px, cfg.maxLeverage))
        if (cfg.roundQty) q = floor(q)
        cx.note(cfg, f)
        return max(q, 0.0)
    }

    fun qtyFor(px: Double, sl: Double): Double {
        if (px <= 0) return 0.0
        val risk = abs(px - sl)
        // از equity (نه balance) استفاده می‌کنیم تا چند پوزیشن باز جمع شوند.
        val eq = equity
        var q = if (cfg.useRiskPct) {
            if (risk > 0) eq * cfg.riskPct / 100.0 / risk else 0.0
        } else {
            eq * cfg.equityPct / 100.0 / px
        }
        // سقف مارجین کل نسبت به equity × maxLeverage
        q = min(q, marginCapQty(px, cfg.maxLeverage))
        if (cfg.roundQty) q = floor(q)
        return max(q, 0.0)
    }

    /** ثبت سفارش لیمیت — §4-8 و §18-12/§18-13: حداکثر ۱ سفارش معلق در هر لحظه؛ معاملهٔ باز مانع سفارش جدید نیست. */
    fun registerLimit(s: Setup, bar: Candle): Boolean {
        if (MarketHours.isClosed(bar.t)) {
            lastError = "بازار تعطیل است (تعطیلات شنبه و یکشنبه) — سفارش ثبت نمی‌شود"
            return false
        }
        // §18-26/§18-29/§18-30 سند: سفارش‌گیری و مسلح‌شدن فقط از ۰۱:۳۰ تا ۲۰:۰۰ UTC مجاز است.
        // رد شدن این‌جا ستاپ را نمی‌کشد؛ موتور مارک HV را پاک می‌کند و ستاپ فعال می‌ماند تا
        // اولین تأیید تازهٔ HV بعد از ۰۱:۳۰ طبق برنامه مسلح شود.
        if (!MarketHours.isOrderWindow(bar.t)) {
            lastError = "خارج از پنجرهٔ سفارش‌گیری (۰۱:۳۰ تا ۲۰:۰۰ UTC) — سفارش ثبت نمی‌شود (§18-26 سند)"
            return false
        }
        // نگهبان منحنی سرمایه: در افت سخت، هیچ ورود تازه‌ای ثبت نمی‌شود (سفارش‌های باز و
        // معاملات جاری دست‌نخورده مدیریت می‌شوند — فقط ریسک تازه بسته می‌شود).
        if (!guardAllowsNewEntry()) {
            lastError = "نگهبان منحنی سرمایه: افت ${fmt(guardDdPct)}٪ از اوج — ورود تازه متوقف است (CurveGuard)"
            return false
        }
        // ── موتور ترکیبی (۱٫۴٫۵۲): گیت‌های سخت لایهٔ ۵ ─────────────────────────────
        // ⚠ عمداً **پیش از** شمارش ظرفیت و ساخت سفارش اجرا می‌شود تا محاسبهٔ حجم و
        // هندسه هیچ‌گاه روی معامله‌ای که مجاز نیست انجام نشود.
        if (cfg.cxMode > 0) {
            val a = engine?.structureAtr(cfg.slAtrPeriod) ?: Double.NaN
            if (a.isFinite() && a > 0.0 && bar.c > 0.0) cx.onBar(bar.t, bar.h, bar.l, bar.c, a / bar.c * 100.0)
            cx.onAccount(equity, maxEquity, trades.filter { !it.open }.map { rNow(it, it.exitPx ?: it.entry) }, cfg)
            if (!cx.regimeAllows(cfg)) {
                cx.rejectedRegimeX()
                lastError = "موتور ترکیبی: نوسان ${fmt(cx.atrP)}٪ کمتر از گیت رژیم ${fmt(cx.requiredAtrPct(cfg))}٪ است — ورود ثبت نمی‌شود"
                lg("ORDER", "سفارش ثبت نشد — گیت رژیم",
                    "ATR%=${fmt(cx.atrP)} لازم=${fmt(cx.requiredAtrPct(cfg))} رژیم=${cx.regime} bar=${bar.bi}")
                return false
            }
            val boxH = abs(s.hvH - s.hvL)
            if (!cx.boxAllows(cfg, boxH, a)) {
                cx.rejectedBoxX()
                lastError = "موتور ترکیبی: ارتفاع باکس (${fmt(boxH)}) کمتر از ${fmt(cfg.cxBoxMinUnits)}×ATR است — ورود ثبت نمی‌شود"
                lg("ORDER", "سفارش ثبت نشد — گیت ساختار",
                    "boxH=${fmt(boxH)} ATR=${fmt(a)} لازم=${fmt(cfg.cxBoxMinUnits * a)} bar=${bar.bi}")
                return false
            }
        }
        val activeCount = activeCount()
        if (cfg.strategy == Strategy.NO_TREND) {
            // استراتژی ۲: خرید و فروش هم‌زمان مجازند؛ از هر جهت فقط یک سفارش (معلق یا باز).
            if (hasActiveOrder(s.tradeDir)) {
                lastError = "از هر جهت فقط یک سفارش (معلق یا باز) مجاز است"
                return false
            }
        } else if (activeCount >= max(1, cfg.maxSetups)) {
            lastError = "سقف سفارش (معلق یا باز) هم‌زمان در چارت پر است"
            return false
        }
        // هم‌پوشانی روی یک ستاپ: اگر همین setupId در حال حاضر سفارش معلق دارد، رد شود.
        if (pendings.any { it.setupId == s.id }) {
            lastError = "برای ستاپ #${s.id} قبلاً سفارش معلق ثبت شده"
            return false
        }
        // §4-8 سند: سطح سفارش = سقف باکس HV + ۱ تیک بافر (خرید) / کف باکس HV − ۱ تیک بافر (فروش)
        val entryPx = cfg.entryLevel(s.bull, s.hvH, s.hvL)
        // §18-31 سند: سفارشی که فاصلهٔ حد ضررش از سطح سفارش بیشتر از N است ثبت نمی‌شود و
        // باطل است (علت: SL>N)؛ در بک‌تست هم سفارشی حساب نمی‌شود. گارد اصلی در موتور است
        // (HV و ستاپ می‌مانند)؛ این‌جا خط دفاع دوم برای ثبت مستقیم است.
        val nMax = engine?.effectiveMaxSlUnits() ?: cfg.maxSlUnits
        if (!s.sl.isNaN() && abs(entryPx - s.sl) > nMax) {
            lastError = "فاصلهٔ حد ضرر از سطح سفارش (${fmt(abs(entryPx - s.sl))}) بیشتر از N=${fmt(nMax)} است — سفارش ثبت نمی‌شود (SL>N، §18-31 سند)"
            return false
        }
        var mmNote = ""
        var mmCm = false
        val qty: Double
        // تصمیم مالک (۴ آبان ۱۴۰۵): با روشن‌بودن موتور ترکیبی، مود ManageAllLost **قفل** است.
        // دلیل عددی: اجرای پیوستهٔ ۲۰سالهٔ همین مود حساب را نابود کرد (‎−۱۰۱٬۹۰۱$‎، افت ۱۰۴٬۵۱۹$).
        val mmActive = mm.active && !(cfg.cxMode > 0 && cfg.mmMode == MoneyMode.MANAGE_ALL_LOST)
        if (mmActive) {
            // §۲۰ سند: Lot = Lost/div + MinLot (ManageAllLost) یا ManageNothing/ManageCM (Mixed).
            // سقف اهرم (تنها متغیر اهرم، [Settings.maxLeverage]) آخرین حدّ است: «حجم نهایی نباید از سرمایه × اهرم ÷ قیمت بیشتر شود».
            val m = mm.lotFor(balance, entryPx, s.tpx)
            val cap = marginCapQty(entryPx, cfg.maxLeverage)
            var q = min(m.raw, cap) * guardScale
            // موتور ترکیبی (۱٫۴٫۵۲): ضریب اندازهٔ لایهٔ ۶ باید روی **هر** مسیر حجم‌گذاری
            // (مدیر پول هم شامل) اثر بگذارد؛ وگرنه با mm=1/2 لایهٔ حجم بی‌اثر می‌شد.
            if (cfg.cxMode > 1) {
                val cf = cx.sizeFactor(cfg)
                cx.note(cfg, cf)
                q *= cf
            }
            q = mm.floorToStep(q)
            if (cfg.roundQty) q = floor(q)
            qty = max(q, 0.0)
            mmCm = m.cm
            mmNote = m.note + (if (m.raw > cap) " · سقف اهرم ${MoneyManager.n2(cfg.maxLeverage)}× اعمال شد" else "") +
                " → حجم ${MoneyManager.n2(qty)}"
        } else {
            qty = qtyForCx(entryPx, s.sl) * guardScale
            if (cfg.cxMode > 0) lg("ORDER", "کارت تصمیم ترکیبی — سفارش ثبت شد", cx.lastNote)
        }
        if (qty <= 0) {
            lastError = "حجم صفر بود (مارجین کافی نیست یا حدضرر صفر)"
            return false
        }
        val o = Order(
            id = orderSeq++, setupId = s.id, dir = s.tradeDir, type = "LIMIT",
            price = entryPx, sl = s.sl, tp1 = s.tp1, tp2 = s.tp2, tpX = s.tpx,
            qty = qty, zoneTop = s.zTop, zoneBot = s.zBot,
            placedT = bar.t, placedBi = bar.bi
        )
        o.mmNote = mmNote
        o.mmCm = mmCm
        // سند ۶: سطح از سمت ساختار لمس می‌شود، ولی پوزیشن در جهت معامله باز می‌شود.
        o.touchDir = if (cfg.trigger2Fade) s.dir else s.tradeDir
        o.hvTop = s.hvH
        o.hvBot = s.hvL
        s.orderId = o.id
        orders.add(o)
        pendings.add(o)
        lg("ORDER", "سفارش ورود ثبت شد (آماده) · ستاپ #${s.id}",
            "type=${if (s.tradeDir == 1) "BUY_LIMIT" else "SELL_LIMIT"} price=${fmt(entryPx)} sl=${fmt(s.sl)} tp1=${fmt(s.tp1)} tp2=${fmt(s.tp2)} tp=${fmt(s.tpx)} qty=${fmt(qty)} active=$activeCount+1/${cfg.maxSetups} bar=${bar.bi}" +
                (if (mmNote.isNotEmpty()) " mm=[$mmNote]" else ""))
        alarm(AlertKind.ARMED, if (s.tradeDir == 1) "سفارش خرید آماده شد" else "سفارش فروش آماده شد",
            "ستاپ #${s.id} · ورود ${fmt(entryPx)} · حدضرر ${fmt(s.sl)} · حدسود ${fmt(s.tpx)} · حجم ${fmt(qty)}")
        return true
    }

    fun cancel(
        order: Order,
        reason: String,
        t: Long,
        bi: Int,
        status: Int = OrderStatus.CANCELLED_INVALID
    ) {
        lg("ORDER", "سفارش لغو شد · #${order.id}", "reason=$reason status=$status")
        order.status = status
        order.cancelReason = reason
        order.closedT = t
        pendings.removeAll { it.id == order.id }
        // معامله باز را نمی‌بندیم (همان منطق ۷-۲).
    }

    /**
     * ستاپ باطل شد.
     * سفارش معلق لغو می‌شود؛ معاملهٔ پرشده تا حد سود/ضرر می‌رود.
     */
    fun onSetupInvalidated(s: Setup, t: Long? = null, bi: Int? = null) {
        val o = orders.lastOrNull { it.setupId == s.id && it.status == OrderStatus.PENDING }
        if (o != null) cancel(o, "ستاپ باطل شد", t ?: o.placedT, bi ?: o.placedBi)
    }

    /** انقضای واقعی طبق §4-8: وضعیت، زمان و هشدار سفارش هر سه ثبت می‌شوند. */
    fun onSetupExpired(s: Setup, t: Long, bi: Int, reason: String) {
        val o = orders.lastOrNull { it.setupId == s.id && it.status == OrderStatus.PENDING } ?: return
        cancel(o, reason, t, bi, OrderStatus.CANCELLED_EXPIRED)
        alarm(AlertKind.CANCEL, "سفارش ورود منقضی شد", "ستاپ #${s.id} · $reason · قیمت ${fmt(o.price)}")
    }

    fun restorePending(o: Order?) { if (o != null && !pendings.any { it.id == o.id }) pendings.add(o) }
    fun restoreOpen(t: Trade?) { if (t != null && t.open && !opens.any { it.id == t.id }) opens.add(t) }

    fun cancelPendingManually(t: Long, bi: Int) {
        ArrayList(pendings).forEach { cancel(it, "لغو دستی توسط کاربر", t, bi, OrderStatus.CANCELLED_MANUAL) }
    }

    /** پردازش یک کندل برای همهٔ سفارش‌ها و پوزیشن‌های باز. */
    fun onBar(cd: Candle, live: Boolean = false) {
        // تعطیلات شنبه و یکشنبه: در بازار بسته هیچ سفارشی پر و هیچ معامله‌ای مدیریت نمی‌شود.
        if (MarketHours.isClosed(cd.t)) return
        // نمونهٔ ATR% برای دروازهٔ نوسان (هر ساعت یک نمونه) — پیش از هر تصمیم این کندل
        if (cfg.mgmtMode != 0) sampleAtrPct(cd)
        // موتور ترکیبی: وضعیت بازار هر کندل (فقط کندل بسته؛ بدون نگاه به آینده)
        if (cfg.cxMode > 0) {
            val a = engine?.structureAtr(cfg.slAtrPeriod) ?: Double.NaN
            val atrPct = if (a.isFinite() && a > 0.0 && cd.c > 0.0) a / cd.c * 100.0 else Double.NaN
            cx.onBar(cd.t, cd.h, cd.l, cd.c, atrPct)
        }
        // §18-27 سند: رأس ۲۰:۰۰ UTC تمامی معاملات باز بسته و نتایج محاسبه و ثبت می‌شوند
        // (دلیل: پایان زمان) و سفارش‌های معلق هم لغو می‌شوند تا شبانه پر نشوند.
        // قیمت بستن = قیمت لحظهٔ ۲۰:۰۰ = بازشدن اولین کندلِ ساعت ۲۰:۰۰ به بعد؛ این خروج
        // بر هر بررسی حد ضرر/حد سود/پرشدنِ همان کندل مقدم است.
        if (MarketHours.isOrderTimeExit(cd.t)) {
            // این بلوک فقط **یک‌بار در هر روز** اجرا می‌شود؛ چون شرط «بعد از ۲۰:۰۰» تا پایان
            // روز برقرار است و در حالت هوشمند بعضی معاملات عمداً باز می‌مانند (۱٫۴٫۵۱).
            val dayNo = cd.t / 86_400_000L
            if (dayNo != lastEodDay) {
                lastEodDay = dayNo
                for (p in ArrayList(pendings)) {
                    if (p.status == OrderStatus.PENDING)
                        cancel(p, "پایان زمان (رأس ۲۰:۰۰ UTC)", cd.t, cd.bi, OrderStatus.CANCELLED_EXPIRED)
                }
                for (t in ArrayList(opens)) {
                    if (!t.open) continue
                    val st = mgmt[t.id]
                    // تصمیم مدیریت معاملهٔ باز: بازنده بسته می‌شود، برندهٔ در سود یک روز می‌ماند.
                    val hold = st != null && st.active && st.extraDays < cfg.mgmtHoldMaxDays &&
                        rNow(t, cd.o) >= cfg.mgmtHoldMinR
                    if (hold) {
                        val m = st!!
                        m.extraDays += 1
                        m.heldAtEod += 1
                        mgmtHeld += 1
                        lg("TRADE", "پایان زمان — معامله #${t.id} در سود بود و یک روز دیگر نگه داشته شد",
                            "R=${fmt(rNow(t, cd.o))} peak=${fmt(m.peakR)} trail=${fmt(m.trailR)} " +
                                "روز اضافه=${m.extraDays}/${cfg.mgmtHoldMaxDays}")
                        alarm(AlertKind.INFO, "معاملهٔ برنده نگه داشته شد",
                            "معامله #${t.id} · ${fmt(rNow(t, cd.o))}R · یک روز دیگر باز می‌ماند (مدیریت هوشمند)")
                    } else {
                        closeAll(t, cd.o, cd.t, cd.bi, "پایان زمان")
                    }
                }
            }
            // پس از ۲۰:۰۰ سفارش‌گیری سند تمام است؛ ولی معامله‌ای که در مدیریت هوشمند عمداً
            // باز مانده باید حد ضرر/حد سود/تریلینگش ادامه پیدا کند — پس فقط وقتی برمی‌گردیم
            // که معاملهٔ بازی نمانده باشد (در مود کلاسیک همیشه همین‌طور است).
            if (opens.isEmpty()) { refreshEquity(cd); return }
        }
        // ① پر شدن سفارش‌های لیمیت (روی یک کپی از pendings تا در fillEntry تغییرات لیست به iteration آسیب نزند)
        val pendingSnapshot = ArrayList(pendings)
        for (p in pendingSnapshot) {
            if (p.status != OrderStatus.PENDING) continue
            val touched = if (p.touchDir == 1) cd.l <= p.price else cd.h >= p.price
            val sameBarAsArmed = cd.bi == p.placedBi
            if (touched && !sameBarAsArmed) fillEntry(p, cd)
        }
        // ② مدیریت پوزیشن‌های باز (کپی تا manageBar کلوز نکند و iteration نشکند)
        for (t in ArrayList(opens)) {
            if (t.open) manageBar(t, cd)
        }
        // ③ به‌روزرسانی موجودی
        refreshEquity(cd)
    }

    /**
     * §1/§3/§6 سند: «پر شدن سفارش لیمیت با قیمت زنده انجام می‌شود» — ولی تصمیم‌های استراتژی فقط با کندل بسته.
     * [forming] کندلِ در حال تشکیل (باز) است: به‌محض اینکه high/low آن به سطح سفارش رسید، سفارش پر می‌شود.
     * (قیمت spot طلا با فیوچرز یاهو چند دلار اختلاف دارد؛ پس برای پر شدن از همان داده‌ی کندل نمودار استفاده می‌شود.)
     *
     * پر شدن در کندلِ مسلح‌شدن مجاز نیست. وقتی همین کندل بسته شود، [onBar] دوباره آن را می‌بیند؛
     * چون سفارش دیگر معلق نیست، دوباره پر نمی‌شود و فقط مدیریت معامله (SL/TP) روی کندل بسته اجرا می‌شود.
     * این تابع هیچ‌وقت خروج/مدیریت معامله انجام نمی‌دهد (فقط پر شدن + به‌روزرسانی equity).
     *
     * @return تعداد سفارش‌های پرشده
     */
    fun onLiveCandle(forming: Candle): Int {
        // آخر هفته حتی اگر منبع قیمت لحظه‌ای عدد بدهد، بازار بسته است و سفارشی پر نمی‌شود.
        if (MarketHours.isClosed(forming.t)) return 0
        // §18-26/§18-27 سند: از رأس ۲۰:۰۰ UTC تا فعال‌شدن دوبارهٔ سفارش‌گیری در ۰۱:۳۰ هیچ
        // سفارشی با قیمت زنده پر نمی‌شود (سفارش‌های معلق رأس ۲۰:۰۰ لغو شده‌اند).
        if (!MarketHours.isOrderWindow(forming.t)) return 0
        if (pendings.isEmpty()) return 0
        var n = 0
        for (p in ArrayList(pendings)) {
            if (p.status != OrderStatus.PENDING) continue
            if (forming.bi <= p.placedBi) continue
            // هم‌ترازی با اولویت‌های موتور (§4-8): سفارشی که با «باز شدن» کندل بالاتر از HH (فروش: پایین‌تر از LL)
            // منقضی می‌شود یا یک کندل ساختار کامل از مسلح‌شدنش گذشته، پر نمی‌شود؛ موتور آن را با کلوز همین کندل منقضی می‌کند.
            val st = engine?.setups?.firstOrNull { it.id == p.setupId }
            if (st != null) {
                val ref = st.priceExpiryRef
                if (!ref.isNaN() && (if (p.dir == 1) forming.o > ref else forming.o < ref)) continue
                if (fullStructureElapsed(st.armBi, forming.bi, cfg.tfS, cfg.tf2)) continue
                if (!st.lvUsed && st.lvBi >= 0 && st.lvConfBi >= 0 && st.lvUses >= 2 &&
                    forming.l <= st.lvH && forming.h >= st.lvL) continue
            }
            val touched = if (p.touchDir == 1) forming.l <= p.price else forming.h >= p.price
            if (touched) { fillEntry(p, forming, live = true); n++ }
        }
        if (n > 0) refreshEquity(forming)      // معامله باز شد ⇒ equity (کمیسیون/شناور) همان لحظه درست باشد
        return n
    }

    /** یک نمونهٔ ATR% در هر ساعت ثبت می‌کند (پنجرهٔ لغزان برای دروازهٔ تطبیقی). */
    private fun sampleAtrPct(cd: Candle) {
        val h1 = cd.t / 3_600_000L
        if (h1 == atrSampleH1) return
        atrSampleH1 = h1
        val a = engine?.structureAtr(cfg.slAtrPeriod) ?: return
        if (a.isNaN() || a <= 0.0 || cd.c <= 0.0) return
        atrSamples.addLast(a / cd.c * 100.0)
        while (atrSamples.size > Tuned.MGMT_ADAPT_WINDOW) atrSamples.removeFirst()
    }

    /**
     * آستانهٔ دروازهٔ نوسان برای **همین لحظه**.
     * - `mgmtMode = 1` ⇒ آستانهٔ ثابت `Settings.mgmtVolGatePct` (رفتار نسخهٔ اول ۱٫۴٫۵۱)
     * - `mgmtMode = 2` ⇒ صدک `Tuned.MGMT_ADAPT_Q` از نمونه‌های ساعت‌های اخیر (تطبیقی)،
     *   با کف `Tuned.MGMT_ADAPT_FLOOR` تا در بازار بسیار آرام آستانه صفر نشود.
     *   تا وقتی نمونه‌ها به حد کافی نرسیده‌اند (گرم‌شدن) آستانهٔ ثابت استفاده می‌شود.
     */
    private fun gateNow(): Double {
        if (cfg.mgmtMode != 2) return cfg.mgmtVolGatePct
        if (atrSamples.size < 60) return cfg.mgmtVolGatePct
        val sorted = atrSamples.sorted()
        val idx = ((sorted.size - 1) * Tuned.MGMT_ADAPT_Q).toInt().coerceIn(0, sorted.size - 1)
        return maxOf(sorted[idx], Tuned.MGMT_ADAPT_FLOOR)
    }

    private fun fillEntry(o: Order, cd: Candle, live: Boolean = false) {
        o.status = OrderStatus.FILLED
        o.filledT = cd.t
        o.filledBi = cd.bi
        pendings.removeAll { it.id == o.id }
        val tr = Trade(
            id = tradeSeq++, orderId = o.id, setupId = o.setupId, dir = o.dir,
            entryT = cd.t, entryBi = cd.bi, entry = o.price,
            sl0 = o.sl, tp1 = o.tp1, tp2 = o.tp2, tpX = o.tpX, qty = o.qty,
            zoneTop = o.zoneTop, zoneBot = o.zoneBot, contractSize = cfg.contractSize
        )
        tr.fills.add(Fill(cd.t, cd.bi, o.price, o.qty, "ENTRY", 0.0))
        tr.mmCm = o.mmCm
        tr.hvTop = o.hvTop
        tr.hvBot = o.hvBot
        // §۲۰ cmsn: اسپرد تحمیلی هر معامله هنگام ورود تعیین و روی خودِ معامله ثبت می‌شود (در مود خاموش ۰ است)؛
        // از سود/زیان همین معامله کم می‌شود و با بسته شدن کامل وارد موجودی (Capital) می‌گردد.
        val cmsn = mm.commissionFor(o.qty)
        if (cmsn > 0.0) tr.commission = cmsn
        // ── مدیریت معاملهٔ باز (۱٫۴٫۵۱): وضعیت این معامله و دروازهٔ رژیم نوسان در لحظهٔ ورود
        val st = MgmtState()
        val atr = engine?.structureAtr(cfg.slAtrPeriod) ?: Double.NaN
        st.atrPct = if (!atr.isNaN() && atr > 0.0 && o.price > 0.0) atr / o.price * 100.0 else 0.0
        val gate = gateNow()
        mgmtGateLast = gate
        // حالت فید (تریگر ۲ معکوس): اندازه‌گیری ۲۰ساله نشان داد شب‌ماندن و تریلینگ در این حالت
        // زیان می‌دهد (−۰٫۰۴۰۸R در برابر −۰٫۰۳۶۰R)، چون معاملهٔ فید خلاف روند باز می‌شود و
        // بازگشت شبانه علیه آن است؛ پس عمداً کلاسیک می‌ماند.
        if (cfg.mgmtMode != 0 && cfg.trigger2Fade) {
            st.active = false
            mgmtFadeClassic += 1
        } else {
            st.active = cfg.mgmtMode != 0 && (gate <= 0.0 || st.atrPct >= gate)
        }
        if (st.active) mgmtSmartOn += 1
        mgmt[tr.id] = st
        if (cfg.mgmtMode != 0) lg("TRADE", "مدیریت معاملهٔ باز برای #${tr.id}",
            "ATR%=${"%.3f".format(st.atrPct)} · دروازهٔ ${"%.2f".format(gate)}% (" +
                (if (cfg.mgmtMode == 2) "تطبیقی-صدکی" else "ثابت") + ")" +
                (if (cfg.trigger2Fade) " · حالت فید ⇒ کلاسیک"
                 else if (st.active) " ⇒ هوشمند فعال" else " ⇒ کلاسیک"))
        trades.add(tr)
        opens.add(tr)
        lg("TRADE", "سفارش پر شد → ورود انجام شد · معامله #${tr.id}" + (if (live) " (قیمت زنده)" else ""),
            "dir=${if (tr.dir == 1) "BUY" else "SELL"} entry=${fmt(tr.entry)} sl=${fmt(tr.sl0)} tp=${fmt(tr.tpX)} qty=${fmt(tr.qty)} bar=${cd.bi}" +
                (if (cmsn > 0.0) " cmsn=${fmt(cmsn)}" else ""))
        alarm(AlertKind.FILLED, if (tr.dir == 1) "ورود خرید انجام شد" else "ورود فروش انجام شد",
            "معامله #${tr.id} · ورود ${fmt(tr.entry)} · حدضرر ${fmt(tr.sl0)} · حدسود ${fmt(tr.tpX)}")
    }

    /** چند R سود/ضرر در قیمت [px] (R = فاصلهٔ ورود تا حد ضرر اولیه). */
    fun rNow(t: Trade, px: Double): Double {
        val d = t.risk
        if (d <= 0.0 || px.isNaN()) return 0.0
        return if (t.dir == 1) (px - t.entry) / d else (t.entry - px) / d
    }

    /**
     * به‌روزرسانی سقف/تریلینگ هوشمند با **کلوز کندل‌های بستهٔ ساعتی**.
     *
     * چرا روی بستهٔ ساعتی و نه هر تیک: آزمون ۲۰ساله نشان داد تریلینگِ تیک‌به‌تیک با نویز
     * بازار فعال می‌شود و کل سود را می‌خورد (‎−۰٫۰۳R‎ به‌ازای هر معامله)؛ همان تریلینگ روی
     * بستهٔ ساعتی زیان‌ده نبود. معاملهٔ این استراتژی به‌طور میانگین ۱۱ ساعت باز است، پس
     * ساعت، واحد طبیعی تصمیم است.
     */
    private fun updateMgmt(t: Trade, cd: Candle) {
        val st = mgmt[t.id] ?: return
        if (!st.active) return
        val h1 = cd.t / 3_600_000L
        if (st.h1Bucket == Long.MIN_VALUE) {
            st.h1Bucket = h1
        } else if (h1 != st.h1Bucket) {
            // کندل قبلی، آخرین کندلِ یک ساعت بود ⇒ کلوزش را به‌عنوان «کلوز ساعت» ثبت کن
            if (!st.prevClose.isNaN()) {
                val r = rNow(t, st.prevClose)
                if (r > st.peakR) st.peakR = r
                if (cfg.mgmtTrailStartR > 0.0 && st.peakR >= cfg.mgmtTrailStartR) {
                    // موتور ترکیبی (۱٫۴٫۵۲) — لایهٔ ۷: در رژیم پرنوسان (۲) فاصلهٔ تریل کمی
                    // بازتر می‌شود تا نویزِ نوسان، سود باز را نبندد؛ ضریب سقف‌دار [cxTrailRegime2].
                    var tdR = cfg.mgmtTrailDistR
                    if (cfg.cxMode >= 2 && cx.regime == 2) tdR *= cfg.cxTrailRegime2.coerceIn(1.0, 2.0)
                    val cand = st.peakR - tdR
                    if (st.trailR.isNaN() || cand > st.trailR) st.trailR = cand
                }
            }
            st.h1Bucket = h1
        }
        st.prevClose = cd.c
    }

    private fun manageBar(t: Trade, cd: Candle) {
        val bull = t.dir == 1
        val sameEntryBar = cd.bi == t.entryBi
        // سطح حد ضررِ در جریان در **شروع** این کندل.
        // §4-9 سند: قاعدهٔ «انتقال حد ضرر به سطح oo با کلوز تریگر۲» در ویرایش فعلی سند داخل بلوک
        // «به تعویق افتاده و در کد ایجاد نمی‌شود» قرار گرفته است؛ پس حد ضرر فقط از حالت
        // اولیه (sl0) یا سر‌به‌سرِ پس از حد سود اول (entry) می‌آید و هیچ جابه‌جایی دیگری ندارد.
        // مدیریت هوشمند (۱٫۴٫۵۱): سقف/تریل را روی کلوز ساعت به‌روز کن، بعد مؤثرترین
        // (بالاترین برای خرید) سطح حد ضرر را انتخاب کن: ساختاری، سر‌به‌سر، یا تریلینگ.
        updateMgmt(t, cd)
        val st = mgmt[t.id]
        val baseSl = if (t.be) t.entry else t.sl0
        var slLvl = baseSl
        if (st != null && st.active && !st.trailR.isNaN() && t.be.not()) {
            val trPx = t.entry + (if (bull) 1.0 else -1.0) * st.trailR * t.risk
            val better = if (bull) trPx > slLvl else trPx < slLvl
            if (better) slLvl = trPx
        }

        // §4-9 ترتیب ثابت: حد ضرر → حد سود نهایی → حد سود اول → حد سود دوم.
        // اگر در یک کندل هم حد ضرر و هم حد سود لمس شود، حد ضرر برنده است.
        val hitStop = if (bull) cd.l <= slLvl else cd.h >= slLvl
        if (hitStop) {
            val trailHit = st != null && st.active && !st.trailR.isNaN() && !t.be && slLvl != baseSl
            if (trailHit) mgmtTrailExits += 1
            val isBe = t.be
            if (isBe) t.commission = 0.0
            // §۵ سند: لغزش یک تیک فقط روی خروج با حد ضرر (در جهت ضرر). خروج سر‌به‌سر طبق
            // §۱۸-۲۴ باید دقیقاً روی سطح oo و بدون هزینه باشد، پس لغزش نمی‌خورد.
            val why = when {
                isBe -> "خروج سر‌به‌سر"
                trailHit -> "حد ضرر تریلینگ"
                else -> "حد ضرر"
            }
            closeAll(t, cfg.exitFill(bull, slLvl, isBe), cd.t, cd.bi, why)
            return
        }
        // §4-9 سند — بلوک «خیلی مهم»ِ انتقال حد ضرر به سطح oo با کلوز تریگر۲ **اجرا نمی‌شود**:
        // در ویرایش فعلی سند این بخش داخل یک بلوک حصارکشی‌شده با عنوان
        // «(این بخش به تعویق افتاده و در کد ایجاد نمی‌شود)» قرار دارد. پس نه شرط باکس HV، نه
        // شرط کل کندل آن‌سوی oo و نه شرط کلوزِ آن‌سوی oo در کد وجود ندارد و حد ضرر تا فعال شدن
        // سر‌به‌سر (حد سود اول) یا خروج، ثابت می‌ماند.
        // §4-9 «حد سود اول و قیمت ورود در یک کندل»:
        // اگر در کندل ورود هم قیمت ورود و هم حد سود اول (۰.۳۸۲ فیبو) لمس شود،
        // ورود ثبت می‌شود، حد سود اول ناحیهٔ ۰.۵ فیبو و حد سود نهایی ۱.۲۷۲ فیبو محاسبه می‌شود
        // و ۵۰٪ معامله در ۰.۵ فیبو (با انتقال حد ضرر به نقطهٔ ورود پس از لمس ۰.۵) و ۵۰٪ باقی در ۱.۲۷۲ بسته می‌شود.
        // این تبدیل باید پیش از سنجش پله‌ها و حد سود نهایی انجام شود تا اگر همان کندل تا
        // حد سود نهایی هم رفت، پله‌ها با سطوح درست (۰.۵ و ۱.۲۷۲) ثبت شوند.
        if (sameEntryBar && !t.tp1.isNaN() && !t.tp2.isNaN()) {
            val touched382OnEntry = if (bull) cd.h >= t.tp1 else cd.l <= t.tp1
            if (touched382OnEntry) {
                t.tp1 = t.tp2
                t.tp2 = Double.NaN
                lg("TRADE", "لمس هم‌زمان ورود و ۰.۳۸۲ فیبو در یک کندل → حد سود اول به ۰.۵ فیبو (۵۰٪/۵۰٪) منتقل شد · معامله #${t.id}",
                    "tp1=${fmt(t.tp1)} tpX=${fmt(t.tpX)} bar=${cd.bi}")
            }
        }

        // §4-9 و §18-20 و §18-24:
        // • حالت سه‌پله‌ای (ورود قبل از ۰.۳۸۲ فیبو): ۳۳٪ در ۰.۳۸۲ (همراه با سر‌به‌سر بدون کمیسیون)، ۳۳٪ در ۰.۵، ۳۴٪ در ۱.۲۷۲.
        // • حالت دوپله‌ای (ورود بعد از ۰.۳۸۲ فیبو یا لمس هم‌زمان ورود و ۰.۳۸۲ در یک کندل ⇒ tp2=NaN):
        //   ۵۰٪ در ۰.۵ فیبو (همراه با سر‌به‌سر بدون کمیسیون) و ۵۰٪ باقی در ۱.۲۷۲ فیبو.
        val isTwoStep = t.tp2.isNaN()
        val q1 = t.qty * (if (isTwoStep) 0.50 else 0.33)
        val q2 = t.qty * 0.33
        fun crossedLevel(level: Double): Boolean = if (bull) cd.h >= level else cd.l <= level
        fun remainingQty(): Double = t.qty - t.fills.filter { it.kind != "ENTRY" }.sumOf { it.qty }

        val hitFinal = if (bull) cd.h >= t.tpX else cd.l <= t.tpX
        if (hitFinal) {
            // §4-9: معامله سه پله دارد و هر پله در سطح خودش بسته و در گزارش ثبت می‌شود.
            // قیمت برای رسیدن به حد سود نهایی ناچار از سطح‌های TP1 و TP2 گذشته است؛ پس اگر
            // پله‌ای در کندل‌های قبلی ثبت نشده، در همین کندل و پیش از بستن باقی‌مانده در
            // حد سود نهایی ثبتش می‌کنیم (۳۳٪/۳۳٪ و باقی‌مانده در ۱.۲۷۲ — یا ۵۰٪/۵۰٪ دوپله‌ای).
            // پیش‌تر کل حجم باقی‌مانده یکجا با TPX بسته می‌شد: در گزارش سفارش‌ها TP1/TP2
            // دیده نمی‌شدند و سود هم با قیمت نهاییِ کل حجم بیش‌ازحد حساب می‌شد.
            if (!t.tp1.isNaN() && t.fills.none { it.kind == "TP1" } && crossedLevel(t.tp1)) {
                t.be = true
                t.commission = 0.0
                addExit(t, cd, t.tp1, min(q1, remainingQty()), "TP1")
            }
            if (!t.tp2.isNaN() && t.fills.none { it.kind == "TP2" } && crossedLevel(t.tp2)) {
                addExit(t, cd, t.tp2, min(q2, remainingQty()), "TP2")
            }
            closeAll(t, t.tpX, cd.t, cd.bi, "حد سود نهایی (1.272)")
            return
        }

        val tp1Done = t.fills.any { it.kind == "TP1" }
        val remaining = t.qty - t.fills.filter { it.kind != "ENTRY" }.sumOf { it.qty }
        if (!tp1Done && !t.tp1.isNaN()) {
            val hit = if (bull) cd.h >= t.tp1 else cd.l <= t.tp1
            if (hit) {
                t.be = true
                t.commission = 0.0
                addExit(t, cd, t.tp1, min(q1, remaining), "TP1")
            }
        }

        val remainingNow = t.qty - t.fills.filter { it.kind != "ENTRY" }.sumOf { it.qty }
        val tp2Done = t.fills.any { it.kind == "TP2" }
        if (t.open && t.be && !tp2Done && !t.tp2.isNaN() && remainingNow > 1e-9) {
            val hit = if (bull) cd.h >= t.tp2 else cd.l <= t.tp2
            if (hit) addExit(t, cd, t.tp2, min(q2, remainingNow), "TP2")
        }

        // اگر در کندلی پس از کندل ورود، TP1 حد ضرر را به ورود برد و قیمت در همان کندل به ورود برگشت،
        // باقی‌ماندهٔ حجم در نقطهٔ ورود بدون کمیسیون بسته می‌شود.
        val returnedToEntry = if (bull) cd.l <= t.entry else cd.h >= t.entry
        if (!sameEntryBar && t.open && t.be && returnedToEntry) {
            t.commission = 0.0
            closeAll(t, t.entry, cd.t, cd.bi, "خروج سر‌به‌سر")
        }
    }

    private fun addExit(t: Trade, cd: Candle, px: Double, qty: Double, kind: String) {
        val q = if (qty <= 0) t.qty - t.fills.filter { it.kind != "ENTRY" }.sumOf { it.qty } else qty
        if (q <= 0) return
        val pnl = if (t.dir == 1) (px - t.entry) * q * t.contractSize else (t.entry - px) * q * t.contractSize
        t.fills.add(Fill(cd.t, cd.bi, px, q, kind, pnl))
        lg("TRADE", "پلهٔ خروج $kind اجرا شد · معامله #${t.id}",
            "px=${fmt(px)} qty=${fmt(q)} pnl=${fmt(pnl)} سود/زیان معامله تاکنون=${fmt(t.pnl())}")
        val kindAlert = when (kind) {
            "TP1" -> AlertKind.TP1; "TP2" -> AlertKind.TP2; "TPX" -> AlertKind.TPX
            "SL" -> AlertKind.SL; "BE" -> AlertKind.BE; else -> AlertKind.CLOSE
        }
        alarm(kindAlert, "خروج $kind", "معامله #${t.id} · قیمت ${fmt(px)} · حجم ${fmt(q)} · سود/زیان ${fmt(pnl)} دلار")
    }

    private fun closeAll(t: Trade, px: Double, time: Long, bi: Int, reason: String) {
        val used = t.fills.filter { it.kind != "ENTRY" }.sumOf { it.qty }
        val remaining = t.qty - used
        val kind = when {
            reason.contains("سر‌به‌سر") -> "BE"
            reason.startsWith("حد ضرر") -> "SL"
            reason.contains("1.272") -> "TPX"
            else -> "CLOSE"
        }
        if (kind == "BE") {
            // §18-20 و §18-24: سر به سر باید به شکلی منتقل شود که کارمزد کمیسیون معامله صفر شود! یعنی دقیقا به نقطه ورود بدون کمیسیون.
            t.commission = 0.0
        }
        if (remaining > 1e-9) {
            val pnl = if (t.dir == 1) (px - t.entry) * remaining * t.contractSize else (t.entry - px) * remaining * t.contractSize
            t.fills.add(Fill(time, bi, px, remaining, kind, pnl))
        }
        t.exitT = time; t.exitBi = bi; t.exitPx = px
        t.reason = reason
        opens.removeAll { it.id == t.id }
        // Capital: تنها جای به‌روزرسانی — سود/زیان خالص کل معامله (همهٔ پله‌ها − کمیسیون) یک‌جا.
        balance += t.pnl()
        // §۲۰: Lost / cmsnLost بعد از بسته شدن کامل معامله به‌روز می‌شود (مود خاموش: بی‌اثر).
        val lostBefore = mm.lost; val cmsnLostBefore = mm.cmsnLost
        mm.onTradeClosed(t)
        mm.onCapitalUpdated(balance)   // §۲۰-۲: Capital ≥ ۲×NakedCapital ⇒ NakedCapital دو برابر می‌شود
        if (mm.active) lg("TRADE", "مدیریت مالی §۲۰ بعد از معامله #${t.id}",
            "Lost ${fmt(lostBefore)} → ${fmt(mm.lost)} · cmsnLost ${fmt(cmsnLostBefore)} → ${fmt(mm.cmsnLost)} · Capital=${fmt(balance)}")
        lg("TRADE", "معامله #${t.id} بسته شد", "reason=$reason exit=${fmt(px)} pnl=${fmt(t.pnl())} R=${fmt(t.rMultiple())} موجودی=${fmt(balance)}")
        val pnl = t.pnl()
        alarm(AlertKind.CLOSE, "معامله بسته شد · ${t.reason}",
            "معامله #${t.id} · خروج ${fmt(px)} · سود/زیان ${fmt(pnl)} دلار · ${fmt(t.rMultiple())}R")
    }

    /**
     * equity = Capital + سود/زیان همهٔ معاملات باز: پله‌های خروج‌شده، منهای کمیسیون، به‌علاوهٔ شناورِ باقی‌ماندهٔ حجم
     * با قیمت [cd].close. تنها جای محاسبهٔ equity؛ افت سرمایه هم از همین مقدار سنجیده می‌شود.
     */
    fun refreshEquity(cd: Candle) {
        var open = 0.0
        for (t in opens) {
            if (!t.open) continue
            val used = t.fills.filter { it.kind != "ENTRY" }.sumOf { it.qty }
            val rem = t.qty - used
            val unreal = if (t.dir == 1) (cd.c - t.entry) * rem * t.contractSize
                         else (t.entry - cd.c) * rem * t.contractSize
            open += t.pnl() + unreal
        }
        equity = balance + open
        maxEquity = max(maxEquity, equity)
        val dd = maxEquity - equity
        if (dd > maxDrawdown) maxDrawdown = dd
        // نگهبان وضعیتش را از همین equity می‌گیرد (یک محل به‌روزرسانی، بدون محاسبهٔ دوباره).
        updateGuard()
    }

    fun openTradePnl(): Double = opens.filter { it.open }.sumOf { it.pnl() }

    fun closedTrades(): List<Trade> = trades.filter { !it.open }
}
