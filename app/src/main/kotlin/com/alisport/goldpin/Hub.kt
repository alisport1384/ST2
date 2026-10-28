package com.alisport.goldpin

import android.content.Context
import com.alisport.goldpin.core.SymbolSpec
import com.alisport.goldpin.core.Symbols
import com.alisport.goldpin.util.Fa
import com.alisport.goldpin.util.Log

// ═══════════════════════════════════════════════════════════════════════════════
//  هاب نمادها — قلب نسخهٔ چندنمادی
//
//  قاعده‌های تغییرناپذیر این نسخه (خواستهٔ صریح مالک):
//   ۱) هر نماد **وضعیت کامل و مستقل** خودش را دارد: تنظیمات، موتور، کارگزار،
//      کندل‌ها، سفارش‌ها، معاملات، نتیجهٔ بک‌تست، فایل ذخیره.
//   ۲) انتخاب نماد **فقط نما** را عوض می‌کند. هیچ بازسازی، هیچ ریست، هیچ
//      توقف موتور و هیچ پاک‌شدن حالتی رخ نمی‌دهد.
//   ۳) هر دو نماد هم‌زمان زنده معامله می‌کنند؛ حلقهٔ لایو روی *همهٔ* نمادها تیک می‌زند.
//   ۴) رفتن به بک‌تست، لایو را برای **همهٔ** نمادها متوقف می‌کند و معاملات باز
//      همه در همان لحظه «فریز» می‌شوند و در تب مدیریت می‌مانند.
// ═══════════════════════════════════════════════════════════════════════════════
object Hub {

    /** ترتیب نمایش در منو و تب مدیریت. */
    val specs: List<SymbolSpec> = Symbols.ALL

    private val states = LinkedHashMap<String, AppState>()

    /** نمادِ در حال نمایش — تنها چیزی که با انتخاب کاربر عوض می‌شود. */
    @Volatile
    var viewId: String = Symbols.GOLD.id
        private set

    /** نماد نمای فعلی (برای رابط). */
    val view: AppState get() = state(viewId)

    /** آیا هاب ساخته شده است؟ */
    val ready: Boolean get() = states.isNotEmpty()

    /** لیسنرهای رابط (همهٔ نمادها به این یک فهرست خبر می‌دهند). */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun state(id: String): AppState {
        val key = Symbols.byId(id).id
        return states[key] ?: synchronized(this) {
            states[key] ?: AppState(Symbols.byId(key)).also { states[key] = it }
        }
    }

    fun state(spec: SymbolSpec): AppState = state(spec.id)

    fun all(): List<AppState> = specs.map { state(it) }

    /** راه‌اندازی یک‌باره: هر دو نماد ساخته، تنظیم پیش‌فرض نماد، و نمای ذخیره‌شده بازیابی می‌شود. */
    fun init(ctx: Context) {
        AppState.appContextRef = ctx.applicationContext
        val p = ctx.getSharedPreferences("goldpin", Context.MODE_PRIVATE)
        viewId = Symbols.byId(p.getString("view_symbol", Symbols.GOLD.id)).id
        specs.forEach { spec ->
            val st = state(spec)
            if (!st.stateLoaded) st.initApp(ctx)
        }
        Log.i(Log.CAT_APP, "هاب نمادها آماده شد",
            "نمادها=${specs.joinToString { it.id }} نمای فعال=$viewId")
    }

    /**
     * تغییر نمادِ **نمای** برنامه.
     *
     * ⚠ این تابع هیچ‌وقت `rebuild`، `reset`، `wipe` یا توقف لایو صدا نمی‌زند.
     * فقط `viewId` عوض می‌شود و رابط دوباره ترسیم می‌شود. وضعیت هر دو نماد
     * دقیقاً همان‌جایی است که لحظهٔ قبل بود.
     */
    fun setView(ctx: Context, id: String): String {
        val spec = Symbols.byId(id)
        if (viewId == spec.id) return "نماد «${spec.short}» همین حالا انتخاب شده است"
        viewId = spec.id
        try {
            ctx.getSharedPreferences("goldpin", Context.MODE_PRIVATE).edit()
                .putString("view_symbol", spec.id).apply()
        } catch (_: Exception) { }
        Log.i(Log.CAT_APP, "نماد نمای برنامه عوض شد", "→ ${spec.id} (هیچ حالتی ریست نشد)")
        fire()
        return "نماد «${spec.label}» انتخاب شد — وضعیت هر دو نماد دست‌نخورده است"
    }

    /**
     * بالا آوردن وضعیت نمادهای دیگر (غیرِ نمای فعال) در پس‌زمینه.
     *
     * در نشست زنده فایل وضعیت کوچک است و هر دو نماد بی‌درنگ آماده می‌شوند.
     * در نشست بک‌تست، بارگذاری هم‌زمان دو دیتاست چند میلیون‌کندلی می‌تواند حافظه را
     * پر کند؛ پس نماد غیرِ فعال **بارگذاری نمی‌شود** و لحظهٔ انتخاب از فایل خودش
     * بازیابی می‌شود — بدون از دست رفتن هیچ حالتی.
     */
    fun restoreOthers(ctx: Context) {
        val cur = view
        all().forEach { st ->
            if (st === cur || st.stateLoaded) return@forEach
            if (st.sessionMode == SessionMode.INTERNAL) {
                Log.i(Log.CAT_APP, "نماد ${st.spec.id} در نشست بک‌تست — بارگذاری به لحظهٔ انتخاب موکول شد")
                return@forEach
            }
            st.io.execute {
                try { st.restoreOrSample(ctx) { } } catch (e: Exception) {
                    Log.e(Log.CAT_STORE, "بازیابی نماد ${st.spec.id} ناموفق", e)
                }
            }
        }
    }

    /** اگر وضعیت این نماد بارگذاری نشده، همین حالا از فایل خودش بازیابی کن (بدون ریست). */
    fun ensureLoaded(ctx: Context, st: AppState, onDone: (String) -> Unit = {}) {
        if (st.stateLoaded) { onDone(""); return }
        all().filter { it !== st && it.internalRawSize() > 2_000_000 }.forEach { it.releaseHeavyData(ctx) }
        st.restoreOrSample(ctx) { m -> onDone(m) }
    }

    // ── لایو ────────────────────────────────────────────────────────────────────
    /** آیا حلقهٔ زنده برای هر نمادی روشن است؟ */
    fun liveRunning(): Boolean = states.values.any { it.liveRunning }

    /** روشن/خاموش کردن لایو برای همهٔ نمادها. */
    fun setLive(on: Boolean) {
        states.values.forEach { it.liveRunning = on }
        fire()
    }

    /**
     * یک تیک لایو برای **همهٔ** نمادها.
     *
     * مستقل از نمادِ در حال نمایش اجرا می‌شود؛ اگر فقط نمای فعال تیک می‌خورد،
     * نماد دیگر «بی‌صدا» می‌ماند و سفارش‌هایش هرگز ساخته نمی‌شد.
     */
    fun tickAll() {
        specs.forEach { spec ->
            val st = state(spec)
            if (!st.liveRunning) return@forEach
            try {
                st.updateLiveOnce()
            } catch (e: Exception) {
                Log.e(Log.CAT_LIVE, "خطای تیک نماد ${spec.id}", e)
            }
        }
    }

    /**
     * توقف لایو برای همه (ورود هر بخش به بک‌تست).
     * معاملات باز همهٔ نمادها همان‌جا فریز می‌شوند و در تب مدیریت می‌مانند.
     */
    fun freezeLive(reason: String) {
        if (!liveRunning()) return
        states.values.forEach { it.liveRunning = false }
        Log.i(Log.CAT_LIVE, "لایو همهٔ نمادها متوقف شد", reason)
        fire()
    }

    /** کمترین خطای پشت‌سرهم بین نمادها (برای عقب‌گرد نمایی حلقهٔ تیک). */
    fun failStreak(): Int = states.values.minOfOrNull { it.liveFailStreak } ?: 0

    // ── رخداد رابط ─────────────────────────────────────────────────────────────
    fun onChange(f: () -> Unit) { if (f !in listeners) listeners.add(f) }

    fun offChange(f: () -> Unit) { listeners.remove(f) }

    private val firing = ThreadLocal.withInitial { false }

    /** خبر دادن به رابط — ضد بازگشت تودرتو. */
    fun fire() {
        if (firing.get()) return
        firing.set(true)
        try {
            listeners.forEach { l -> try { l() } catch (_: Exception) { } }
        } finally { firing.set(false) }
    }

    // ── مدیریت سرمایه (تب مدیریت) ───────────────────────────────────────────────
    /** خلاصهٔ یک نماد برای تب مدیریت. */
    data class SymSummary(
        val id: String,
        val label: String,
        val short: String,
        val live: Boolean,
        val balance: Double,
        val equity: Double,
        val realized: Double,
        val unrealized: Double,
        val netToday: Double,
        val openCount: Int,
        val closedCount: Int,
        val wins: Int,
        val winRate: Double,
        val startEquity: Double,
        val maxDD: Double,
        val curve: List<Pair<Long, Double>>,   // (زمان بسته‌شدن، سرمایهٔ تجمعی)
        val frozen: Boolean
    )

    /** جمع کل همهٔ نمادها — عددی که «مستقل از نماد انتخابی» است. */
    data class Total(
        val startEquity: Double,
        val balance: Double,
        val equity: Double,
        val realized: Double,
        val unrealized: Double,
        val openCount: Int,
        val closedCount: Int,
        val wins: Int,
        val winRate: Double,
        val maxDD: Double,
        val perSymbol: List<SymSummary>,
        val curve: List<Pair<Long, Double>>   // سرمایهٔ تجمعی کل (به ترتیب زمان)
    )

    private fun curveOf(trades: List<com.alisport.goldpin.core.Trade>, start: Double): Pair<List<Pair<Long, Double>>, Double> {
        var eq = start
        var peak = start
        var dd = 0.0
        val pts = ArrayList<Pair<Long, Double>>(trades.size + 1)
        trades.forEach { t ->
            val pnl = t.pnl()
            eq += pnl
            if (eq > peak) peak = eq
            val d = peak - eq
            if (d > dd) dd = d
            pts.add((t.exitT ?: t.entryT) to eq)
        }
        return pts to dd
    }

    fun summary(st: AppState): SymSummary {
        val start = st.cfg.initialEquity
        val trades = st.tradesSnapshot()
        val closed = trades.filter { !it.open }
        val realized = closed.sumOf { it.pnl() }
        val unreal = trades.filter { it.open }.sumOf { runCatching { it.pnl() }.getOrDefault(0.0) }
        val (pts, dd) = curveOf(closed, start)
        val wins = closed.count { it.pnl() > 0.0 }
        // امروز = معاملات بسته‌شده در ۲۴ ساعت گذشته (به وقت دستگاه)
        val dayStart = System.currentTimeMillis() - 24L * 3600_000L
        val netToday = closed.filter { (it.exitT ?: 0L) >= dayStart }.sumOf { it.pnl() }
        return SymSummary(
            id = st.spec.id,
            label = st.spec.label,
            short = st.spec.short,
            live = st.liveRunning,
            balance = start + realized,
            equity = st.broker.equity,
            realized = realized,
            unrealized = unreal,
            netToday = netToday,
            openCount = trades.count { it.open },
            closedCount = closed.size,
            wins = wins,
            winRate = if (closed.isEmpty()) 0.0 else 100.0 * wins / closed.size,
            startEquity = start,
            maxDD = dd,
            curve = pts,
            frozen = !st.liveRunning && trades.any { it.open }
        )
    }

    fun total(): Total {
        val per = all().map { summary(it) }
        val start = per.sumOf { it.startEquity }
        val balance = per.sumOf { it.balance }
        val realized = per.sumOf { it.realized }
        val unreal = per.sumOf { it.unrealized }
        val closed = per.sumOf { it.closedCount }
        val wins = per.sumOf { it.wins }
        // منحنی جمعی: همهٔ نقاط بستهٔ همهٔ نمادها، مرتب بر زمان، با سرمایهٔ اولیهٔ جمعی
        val merged = per.flatMap { s -> s.curve.map { (t, eq) -> t to (eq - s.startEquity) } }
            .sortedBy { it.first }
        var run = start
        val pts = ArrayList<Pair<Long, Double>>(merged.size + 1)
        pts.add(0L to start)
        merged.forEach { (t, delta) -> run += delta; pts.add(t to run) }
        var peak = start; var dd = 0.0
        pts.forEach { (_, eq) -> if (eq > peak) peak = eq; val d = peak - eq; if (d > dd) dd = d }
        return Total(
            startEquity = start,
            balance = balance,
            equity = per.sumOf { it.equity },
            realized = realized,
            unrealized = unreal,
            openCount = per.sumOf { it.openCount },
            closedCount = closed,
            wins = wins,
            winRate = if (closed == 0) 0.0 else 100.0 * wins / closed,
            maxDD = dd,
            perSymbol = per,
            curve = pts
        )
    }

    /** متن خلاصه برای لاگ/گزارش. */
    fun summaryText(): String {
        val t = total()
        val sb = StringBuilder()
        sb.append("مدیریت سرمایه — همهٔ نمادها (ثابت، مستقل از نماد انتخابی)\n")
        sb.append("سرمایهٔ اولیهٔ جمعی: ${Fa.n(t.startEquity, 2)} دلار\n")
        sb.append("موجودی جمعی: ${Fa.n(t.balance, 2)} دلار · اکوییتی جمعی: ${Fa.n(t.equity, 2)} دلار\n")
        sb.append("سود/زیان تحقق‌یافته: ${Fa.n(t.realized, 2)} · شناور: ${Fa.n(t.unrealized, 2)}\n")
        sb.append("معاملات: ${Fa.d(t.closedCount.toString())} بسته · ${Fa.d(t.openCount.toString())} باز · " +
            "برد ${Fa.n(t.winRate, 1)}٪ · بیشترین افت ${Fa.n(t.maxDD, 2)}\n")
        t.perSymbol.forEach { s ->
            sb.append("• ${s.label}: موجودی ${Fa.n(s.balance, 2)} · تحقق ${Fa.n(s.realized, 2)} · " +
                "شناور ${Fa.n(s.unrealized, 2)} · ${Fa.d(s.closedCount.toString())} بسته/" +
                "${Fa.d(s.openCount.toString())} باز · برد ${Fa.n(s.winRate, 1)}٪ · افت ${Fa.n(s.maxDD, 2)}" +
                (if (s.frozen) " · ❄ فریز" else if (s.live) " · ● زنده" else " · ○ غیرفعال") + "\n")
        }
        return sb.toString()
    }
}
