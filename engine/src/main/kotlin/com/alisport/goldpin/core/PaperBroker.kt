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
//  §4-8 / §10 / §18-12 / §18-13 سند: در هر لحظه حداکثر ۱ سفارش معلق؛ وجود معاملهٔ باز
//  مانع مسلح‌شدن سفارش جدید نیست (چند معامله باز می‌توانند هم‌زمان باشند).
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

    /** چند سفارش/پوزیشن فعال هم‌زمان (تصمیم ۲۰۲۶-۱۰-۱۰). */
    private val pendings = ArrayList<Order>()
    private val opens = ArrayList<Trade>()

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
    private fun fmt(v: Double): String = if (v.isNaN()) "-" else String.format("%.2f", v)
    private fun alarm(kind: String, title: String, body: String) { alertSink?.invoke(kind, title, body) }

    fun reset() {
        orders.clear(); trades.clear(); pendings.clear(); opens.clear()
        balance = cfg.initialEquity; equity = cfg.initialEquity
        maxEquity = cfg.initialEquity; maxDrawdown = 0.0
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
        val activeCount = pendings.size
        if (activeCount >= 1) {
            lastError = "فقط ۱ سفارش معلق هم‌زمان در چارت مجاز است"
            return false
        }
        // هم‌پوشانی روی یک ستاپ: اگر همین setupId در حال حاضر سفارش معلق دارد، رد شود.
        if (pendings.any { it.setupId == s.id }) {
            lastError = "برای ستاپ #${s.id} قبلاً سفارش معلق ثبت شده"
            return false
        }
        // §4-8 سند: سطح سفارش = سقف باکس HV + ۲ تیک بافر (خرید) / کف باکس HV − ۲ تیک بافر (فروش)
        val entryPx = cfg.entryLevel(s.bull, s.hvH, s.hvL)
        var mmNote = ""
        var mmCm = false
        val qty: Double
        if (mm.active) {
            // §۲۰ سند: Lot = Lost/div + MinLot (ManageAllLost) یا ManageNothing/ManageCM (Mixed).
            // سقف اهرم (تنها متغیر اهرم، [Settings.maxLeverage]) آخرین حدّ است: «حجم نهایی نباید از سرمایه × اهرم ÷ قیمت بیشتر شود».
            val m = mm.lotFor(balance, entryPx, s.tpx)
            val cap = marginCapQty(entryPx, cfg.maxLeverage)
            var q = min(m.raw, cap)
            q = mm.floorToStep(q)
            if (cfg.roundQty) q = floor(q)
            qty = max(q, 0.0)
            mmCm = m.cm
            mmNote = m.note + (if (m.raw > cap) " · سقف اهرم ${MoneyManager.n2(cfg.maxLeverage)}× اعمال شد" else "") +
                " → حجم ${MoneyManager.n2(qty)}"
        } else {
            qty = qtyFor(entryPx, s.sl)
        }
        if (qty <= 0) {
            lastError = "حجم صفر بود (مارجین کافی نیست یا حدضرر صفر)"
            return false
        }
        val o = Order(
            id = orderSeq++, setupId = s.id, dir = s.dir, type = "LIMIT",
            price = entryPx, sl = s.sl, tp1 = s.tp1, tp2 = s.tp2, tpX = s.tpx,
            qty = qty, zoneTop = s.zTop, zoneBot = s.zBot,
            placedT = bar.t, placedBi = bar.bi
        )
        o.mmNote = mmNote
        o.mmCm = mmCm
        s.orderId = o.id
        orders.add(o)
        pendings.add(o)
        lg("ORDER", "سفارش ورود ثبت شد (آماده) · ستاپ #${s.id}",
            "type=${if (s.dir == 1) "BUY_LIMIT" else "SELL_LIMIT"} price=${fmt(entryPx)} sl=${fmt(s.sl)} tp1=${fmt(s.tp1)} tp2=${fmt(s.tp2)} tp=${fmt(s.tpx)} qty=${fmt(qty)} active=$activeCount+1/${cfg.maxSetups} bar=${bar.bi}" +
                (if (mmNote.isNotEmpty()) " mm=[$mmNote]" else ""))
        alarm(AlertKind.ARMED, if (s.dir == 1) "سفارش خرید آماده شد" else "سفارش فروش آماده شد",
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
        // ① پر شدن سفارش‌های لیمیت (روی یک کپی از pendings تا در fillEntry تغییرات لیست به iteration آسیب نزند)
        val pendingSnapshot = ArrayList(pendings)
        for (p in pendingSnapshot) {
            if (p.status != OrderStatus.PENDING) continue
            val touched = if (p.dir == 1) cd.l <= p.price else cd.h >= p.price
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
            }
            val touched = if (p.dir == 1) forming.l <= p.price else forming.h >= p.price
            if (touched) { fillEntry(p, forming, live = true); n++ }
        }
        if (n > 0) refreshEquity(forming)      // معامله باز شد ⇒ equity (کمیسیون/شناور) همان لحظه درست باشد
        return n
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
        // §۲۰ cmsn: اسپرد تحمیلی هر معامله هنگام ورود تعیین و روی خودِ معامله ثبت می‌شود (در مود خاموش ۰ است)؛
        // از سود/زیان همین معامله کم می‌شود و با بسته شدن کامل وارد موجودی (Capital) می‌گردد.
        val cmsn = mm.commissionFor(o.qty)
        if (cmsn > 0.0) tr.commission = cmsn
        trades.add(tr)
        opens.add(tr)
        lg("TRADE", "سفارش پر شد → ورود انجام شد · معامله #${tr.id}" + (if (live) " (قیمت زنده)" else ""),
            "dir=${if (tr.dir == 1) "BUY" else "SELL"} entry=${fmt(tr.entry)} sl=${fmt(tr.sl0)} tp=${fmt(tr.tpX)} qty=${fmt(tr.qty)} bar=${cd.bi}" +
                (if (cmsn > 0.0) " cmsn=${fmt(cmsn)}" else ""))
        alarm(AlertKind.FILLED, if (tr.dir == 1) "ورود خرید انجام شد" else "ورود فروش انجام شد",
            "معامله #${tr.id} · ورود ${fmt(tr.entry)} · حدضرر ${fmt(tr.sl0)} · حدسود ${fmt(tr.tpX)}")
    }

    private fun manageBar(t: Trade, cd: Candle) {
        val bull = t.dir == 1
        val sameEntryBar = cd.bi == t.entryBi
        val slLvl = if (t.be) t.entry else if (!t.slAdj.isNaN()) t.slAdj else t.sl0
        val q1 = t.qty * 0.33
        val q2 = t.qty * 0.33

        // §4-9 ترتیب ثابت: حد ضرر، حد سود نهایی، TP1، TP2.
        // حد ضرر در کندلی که SL و TP لمس شوند برنده است.
        val hitStop = if (bull) cd.l <= slLvl else cd.h >= slLvl
        if (hitStop) {
            closeAll(t, slLvl, cd.t, cd.bi, if (t.be || !t.slAdj.isNaN()) "خروج سر‌به‌سر" else "حد ضرر")
            return
        }
        val hitFinal = if (bull) cd.h >= t.tpX else cd.l <= t.tpX
        if (hitFinal) {
            closeAll(t, t.tpX, cd.t, cd.bi, "حد سود نهایی (1.272)")
            return
        }

        // §6-21: اگر همان کندل ورود، هم TP1 و هم ورود لمس شوند، TP1 ثبت نمی‌شود؛
        // کل معامله در ورود بسته می‌شود. این استثنا بعد از اولویت SL و TPX است.
        if (sameEntryBar && !t.tp1.isNaN()) {
            val tp1Hit = if (bull) cd.h >= t.tp1 else cd.l <= t.tp1
            val entryTouched = if (bull) cd.l <= t.entry else cd.h >= t.entry
            if (tp1Hit && entryTouched) {
                t.be = true
                closeAll(t, t.entry, cd.t, cd.bi, "خروج سر‌به‌سر (TP1 و ورود یک کندل)")
                return
            }
        }

        val tp1Done = t.fills.any { it.kind == "TP1" }
        val remaining = t.qty - t.fills.filter { it.kind != "ENTRY" }.sumOf { it.qty }
        if (!tp1Done && !t.tp1.isNaN()) {
            val hit = if (bull) cd.h >= t.tp1 else cd.l <= t.tp1
            if (hit) {
                addExit(t, cd, t.tp1, min(q1, remaining), "TP1")
                t.be = true
            }
        }

        val remainingNow = t.qty - t.fills.filter { it.kind != "ENTRY" }.sumOf { it.qty }
        val tp2Done = t.fills.any { it.kind == "TP2" }
        if (t.open && t.be && !tp2Done && !t.tp2.isNaN() && remainingNow > 1e-9) {
            val hit = if (bull) cd.h >= t.tp2 else cd.l <= t.tp2
            if (hit) addExit(t, cd, t.tp2, min(q2, remainingNow), "TP2")
        }

        // اگر TP1 همین کندل حد ضرر را به ورود برد و قیمت به ورود برگشت،
        // کل باقیمانده در همین کندل بسته می‌شود؛ موتور و دفتر Broker یکی می‌مانند.
        val returnedToEntry = if (bull) cd.l <= t.entry else cd.h >= t.entry
        if (t.open && t.be && returnedToEntry) {
            closeAll(t, t.entry, cd.t, cd.bi, "خروج سر‌به‌سر")
        }

        // §4-9 سند («خیلی مهم»): اولین کندل تریگر۲ که کاملاً آن‌سوی سطح سفارش بسته شود
        // (بدون حتی یک تیک لمس سطح) → حد ضرر یک‌بار به سطح سفارش منتقل می‌شود.
        if (t.open && !t.be && t.slAdj.isNaN() && !sameEntryBar) {
            val clear = if (bull) cd.l > t.entry else cd.h < t.entry
            if (clear) {
                t.slAdj = t.entry
                lg("TRADE", "کندل تریگر۲ کاملاً آن‌سوی سطح سفارش بسته شد → حدضرر به سطح سفارش منتقل شد · معامله #${t.id}",
                    "sl=${fmt(t.entry)} bar=${cd.bi}")
            }
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
    }

    fun openTradePnl(): Double = opens.filter { it.open }.sumOf { it.pnl() }

    fun closedTrades(): List<Trade> = trades.filter { !it.open }
}
