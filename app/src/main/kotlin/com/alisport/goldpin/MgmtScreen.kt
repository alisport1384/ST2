package com.alisport.goldpin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.alisport.goldpin.util.Fa
import com.alisport.goldpin.util.Palette
import com.alisport.goldpin.util.Ui

// ═══════════════════════════════════════════════════════════════════════════════
//  تب «مدیریت سرمایه» — ثابت و مستقل از نماد انتخابی
//
//  خواستهٔ مالک:
//   • همهٔ نمادهایی که اپ روی آن‌ها معامله می‌کند را نشان بدهد،
//   • سود/زیان **جمعی دقیق** بدهد (تحقق‌یافته + شناور، و افت سرمایهٔ جمعی)،
//   • با عوض‌شدن نماد انتخابی **هیچ تغییری نکند** (به Hub نگاه می‌کند، نه به نماد نما)،
//   • نمودار جمعی + نمودار هر نماد + خلاصهٔ سود/زیان هر نماد،
//   • معاملات فریزشده در بک‌تست هم همین‌جا شمرده شوند.
// ═══════════════════════════════════════════════════════════════════════════════

/** نمودار خطی سادهٔ منحنی سرمایه (بدون وابستگی بیرونی — روی Canvas). */
class MgmtCurveView(context: Context) : View(context) {

    private var pts: List<Pair<Long, Double>> = emptyList()
    private var initial = 1.0
    private var lineColor = Palette.accent
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.4f; strokeJoin = Paint.Join.ROUND
    }
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f; color = Palette.line
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.dim; textSize = 24f
    }

    fun setData(points: List<Pair<Long, Double>>, startEquity: Double, color: Int = Palette.accent) {
        pts = points
        initial = if (startEquity.isFinite() && startEquity > 0) startEquity else 1.0
        lineColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawColor(Palette.panel2)
        val data = if (pts.isEmpty()) listOf(0L to initial) else pts
        var lo = data.minOf { it.second }
        var hi = data.maxOf { it.second }
        if (hi - lo < 1e-9) { lo -= 1.0; hi += 1.0 }
        val pad = (hi - lo) * 0.08
        lo -= pad; hi += pad
        val n = data.size
        fun x(i: Int) = if (n <= 1) w else (i.toFloat() / (n - 1)) * w
        fun y(v: Double) = (h - ((v - lo) / (hi - lo)) * h).toFloat()
        // خط سرمایهٔ اولیه
        canvas.drawLine(0f, y(initial), w, y(initial), basePaint)
        val path = Path()
        val area = Path()
        path.moveTo(x(0), y(data[0].second))
        area.moveTo(x(0), y(data[0].second))
        for (i in 1 until n) {
            path.lineTo(x(i), y(data[i].second))
            area.lineTo(x(i), y(data[i].second))
        }
        area.lineTo(x(n - 1), h); area.lineTo(x(0), h); area.close()
        val up = data.last().second >= initial
        val base = if (up) Palette.up else Palette.down
        fillPaint.color = Color.argb(38, Color.red(base), Color.green(base), Color.blue(base))
        canvas.drawPath(area, fillPaint)
        linePaint.color = if (lineColor == Palette.accent) base else lineColor
        canvas.drawPath(path, linePaint)
        canvas.drawText(Fa.n(hi - pad, 0), 6f, 26f, textPaint)
        canvas.drawText(Fa.n(lo + pad, 0), 6f, h - 8f, textPaint)
    }
}

object MgmtScreen {

    private fun card(ctx: Context, title: String, color: Int = Palette.gold): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Palette.panel, 10f, ctx)
            setPadding(Ui.dp(ctx, 10f), Ui.dp(ctx, 8f), Ui.dp(ctx, 10f), Ui.dp(ctx, 10f))
            addView(Ui.tv(ctx, title, 13f, color, true))
        }

    private fun row(ctx: Context, k: String, v: String, color: Int = Palette.txt): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(ctx, 2f), 0, Ui.dp(ctx, 2f))
            addView(Ui.tv(ctx, k, 11.5f, Palette.dim), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.tv(ctx, v, 12f, color, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f))
        }

    private fun money(v: Double): String = Fa.signed(v, 2)

    /**
     * ساخت کل تب مدیریت.
     *
     * ⚠ این تابع فقط `Hub.total()` را می‌خواند؛ هیچ‌جا از `Hub.view` استفاده نمی‌کند،
     * پس با عوض‌شدن نماد انتخابی عددهایش تکان نمی‌خورند.
     */
    @SuppressLint("SetTextI18n")
    fun build(ctx: Context): View {
        val t = Hub.total()
        val act = ctx as? MainActivity
        val scroll = ScrollView(ctx).apply { setBackgroundColor(Palette.bg) }
        val col0 = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 8f), Ui.dp(ctx, 8f), Ui.dp(ctx, 8f), Ui.dp(ctx, 18f))
        }
        scroll.addView(col0)

        // ── هدر: جمع کل (مستقل از نماد) ────────────────────────────────────────
        val head = card(ctx, "مدیریت سرمایه — همهٔ نمادها (ثابت، مستقل از نماد انتخابی)", Palette.gold)
        head.addView(row(ctx, "نمادهای فعال", Hub.specs.joinToString(" + ") { it.short } +
            (if (Hub.liveRunning()) "" else "  (لایو خاموش)"), Palette.accent))
        head.addView(row(ctx, "سرمایهٔ اولیهٔ جمعی", Fa.n(t.startEquity, 2)))
        head.addView(row(ctx, "موجودی جمعی (تحقق‌یافته)", Fa.n(t.balance, 2),
            if (t.balance >= t.startEquity) Palette.up else Palette.down))
        head.addView(row(ctx, "اکوییتی جمعی (با شناور)", Fa.n(t.equity, 2),
            if (t.equity >= t.startEquity) Palette.up else Palette.down))
        head.addView(row(ctx, "سود/زیان تحقق‌یافته", money(t.realized),
            if (t.realized >= 0) Palette.up else Palette.down))
        head.addView(row(ctx, "سود/زیان شناور (باز)", money(t.unrealized),
            if (t.unrealized >= 0) Palette.up else Palette.down))
        head.addView(row(ctx, "بازده کل", Fa.n(if (t.startEquity > 0) 100.0 * (t.balance - t.startEquity) / t.startEquity else 0.0, 2) + "٪",
            if (t.balance >= t.startEquity) Palette.up else Palette.down))
        head.addView(row(ctx, "معاملات بسته / باز", "${Fa.d(t.closedCount.toString())} / ${Fa.d(t.openCount.toString())}"))
        head.addView(row(ctx, "نرخ برد جمعی", Fa.n(t.winRate, 1) + "٪"))
        head.addView(row(ctx, "بیشترین افت سرمایهٔ جمعی", Fa.n(t.maxDD, 2), Palette.down))
        col0.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = Ui.dp(ctx, 8f) })

        // ── نمودار جمعی ────────────────────────────────────────────────────────
        val chartCard = card(ctx, "منحنی سرمایهٔ جمعی (همهٔ نمادها)", Palette.accent)
        val chart = MgmtCurveView(ctx).apply { setData(t.curve, t.startEquity, Palette.accent) }
        chartCard.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 150f))
            .apply { topMargin = Ui.dp(ctx, 6f) })
        col0.addView(chartCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = Ui.dp(ctx, 8f) })

        // ── هر نماد: خلاصه + نمودار خودش ───────────────────────────────────────
        t.perSymbol.forEach { p ->
            val c = card(ctx, "نماد ${p.label}", if (p.realized >= 0) Palette.up else Palette.down)
            c.addView(row(ctx, "وضعیت",
                when {
                    p.frozen -> "❄ فریز (لایو متوقف — بک‌تست در جریان)"
                    p.live -> "● زنده"
                    else -> "○ لایو خاموش"
                },
                when {
                    p.frozen -> Palette.gold
                    p.live -> Palette.up
                    else -> Palette.dim
                }))
            c.addView(row(ctx, "موجودی / اکوییتی", "${Fa.n(p.balance, 2)} / ${Fa.n(p.equity, 2)}"))
            c.addView(row(ctx, "سود/زیان تحقق‌یافته", money(p.realized), if (p.realized >= 0) Palette.up else Palette.down))
            c.addView(row(ctx, "سود/زیان شناور", money(p.unrealized), if (p.unrealized >= 0) Palette.up else Palette.down))
            c.addView(row(ctx, "۲۴ ساعت گذشته", money(p.netToday), if (p.netToday >= 0) Palette.up else Palette.down))
            c.addView(row(ctx, "معاملات بسته / باز", "${Fa.d(p.closedCount.toString())} / ${Fa.d(p.openCount.toString())}"))
            c.addView(row(ctx, "برده / نرخ برد", "${Fa.d(p.wins.toString())} / ${Fa.n(p.winRate, 1)}٪"))
            c.addView(row(ctx, "بیشترین افت", Fa.n(p.maxDD, 2), Palette.down))
            val mc = MgmtCurveView(ctx).apply { setData(p.curve, p.startEquity, if (p.realized >= 0) Palette.up else Palette.down) }
            c.addView(mc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 110f))
                .apply { topMargin = Ui.dp(ctx, 6f) })
            // رفتن به نمای همان نماد — فقط نما عوض می‌شود؛ هیچ حالتی ریست نمی‌شود
            val b = Ui.btn(ctx, "نمایش ${p.short}", Palette.panel2, Palette.gold, 11.5f)
            b.setOnClickListener {
                Hub.setView(ctx, p.id)
                act?.showTabPublic(0)
            }
            c.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = Ui.dp(ctx, 6f) })
            col0.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = Ui.dp(ctx, 8f) })
        }

        // ── یادداشت سیاست ──────────────────────────────────────────────────────
        val note = card(ctx, "سیاست این تب", Palette.violet)
        note.addView(Ui.tv(ctx, "• عددهای بالا جمع همهٔ نمادهاست و با عوض‌شدن نماد انتخابی تغییر نمی‌کند.\n" +
            "• هر نماد موتور، کارگزار، تنظیمات و فایل ذخیرهٔ خودش را دارد؛ انتخاب نماد فقط نما را عوض می‌کند.\n" +
            "• اگر هر بخشی به بک‌تست برود، لایو همهٔ نمادها متوقف می‌شود و معاملات باز همان لحظه فریز می‌شوند و همین‌جا شمرده می‌شوند.\n" +
            "• نتیجهٔ بک‌تست فقط با درخواست بک‌تست تازه یا حذف صریح از بین می‌رود.\n" +
            "• هر جفت‌ارز میز مستقل خودش را دارد (پارامتر، سرمایه، افت، آزمون ۲۰ ساله) و هیچ عددی از آن با طلا/نفت جمع نمی‌شود.",
            11f, Palette.dim))
        col0.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ── میزهای جفت‌ارز (۱٫۵٫۰۶) — یک کارت مستقل برای هر جفت، مثل طلا و نفت ──
        run {
            val t2 = FxLive.total()
            val head2 = card(ctx, "میزهای جفت‌ارز — جمع ${Fa.d(t2.enabledCount.toString())} میز فعال (جدا از طلا و نفت)", Palette.violet)
            head2.addView(row(ctx, "سرمایهٔ همهٔ میزها", Fa.n(t2.capital, 2)))
            head2.addView(row(ctx, "موجودی جمعی", Fa.n(t2.balance, 2),
                if (t2.balance >= t2.capital) Palette.up else Palette.down))
            head2.addView(row(ctx, "اکوییتی جمعی", Fa.n(t2.equity, 2),
                if (t2.equity >= t2.capital) Palette.up else Palette.down))
            head2.addView(row(ctx, "سود/زیان تحقق‌یافته", money(t2.realized),
                if (t2.realized >= 0) Palette.up else Palette.down))
            head2.addView(row(ctx, "شناور", money(t2.floating),
                if (t2.floating >= 0) Palette.up else Palette.down))
            head2.addView(row(ctx, "معاملات بسته / باز", "${Fa.d(t2.closedCount.toString())} / ${Fa.d(t2.openCount.toString())}"))
            head2.addView(row(ctx, "نرخ برد جمعی", Fa.n(t2.winRate, 1) + "٪"))
            head2.addView(row(ctx, "بیشترین افت جمعی", Fa.n(t2.maxDD, 2), Palette.down))
            if (t2.curve.size > 1) {
                val ch = MgmtCurveView(ctx).apply { setData(t2.curve, t2.capital, Palette.violet) }
                head2.addView(ch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 130f))
                    .apply { topMargin = Ui.dp(ctx, 6f) })
            }
            head2.addView(Ui.tv(ctx, "این جمع هیچ‌وقت با جمع طلا/نفت قاطی نمی‌شود: سرمایه، " +
                "موجودی و افت هر میز جداست.", 10f, Palette.dim))
            col0.addView(head2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = Ui.dp(ctx, 8f) })
        }

        // ── یک کارت برای هر میز جفت‌ارز: دقیقاً همان الگوی نمادها ──────────────
        for (d in FxLive.desks) {
            val sm = d.summary()
            val p = d.profile
            val color = when {
                sm.running -> Palette.up
                sm.enabled -> Palette.gold
                else -> Palette.dim
            }
            val c = card(ctx, "میز ${p.id} — ${p.label}",
                if (sm.realized + sm.floating >= 0) Palette.up else Palette.down)
            c.addView(row(ctx, "وضعیت",
                when {
                    sm.running -> "● روشن (کاغذی)"
                    sm.enabled -> "◐ فعال، خاموش"
                    else -> "○ غیرفعال"
                }, color))
            c.addView(row(ctx, "پارامتر خودش",
                "${p.tfFa} · ${p.modeFa} · N=${p.lookback} · atr=${Fa.n(p.atrMult, 2)} · " +
                    "هدف ${Fa.n(p.targetR, 1)}R" + (if (p.partial) " · پله" else ""), Palette.dim))
            c.addView(row(ctx, "ریسک این میز", Fa.n(sm.riskPct, 2) + "٪ سرمایه",
                if (sm.riskPct <= 0.25) Palette.up else Palette.gold))
            c.addView(row(ctx, "سرمایهٔ میز", Fa.n(sm.capital, 2)))
            c.addView(row(ctx, "موجودی / اکوییتی", "${Fa.n(sm.balance, 2)} / ${Fa.n(sm.equity, 2)}"))
            c.addView(row(ctx, "سود/زیان تحقق‌یافته", money(sm.realized),
                if (sm.realized >= 0) Palette.up else Palette.down))
            c.addView(row(ctx, "سود/زیان شناور", money(sm.floating),
                if (sm.floating >= 0) Palette.up else Palette.down))
            c.addView(row(ctx, "معاملات بسته / باز", "${Fa.d(sm.closedCount.toString())} / ${Fa.d(sm.openCount.toString())}" +
                (if (sm.openCount > 0) " · ${if (sm.openDir > 0) "خرید" else "فروش"}" else "")))
            c.addView(row(ctx, "برده / نرخ برد", "${Fa.d(sm.wins.toString())} / ${Fa.n(sm.winRate, 1)}٪"))
            c.addView(row(ctx, "میانگین R این میز", Fa.signed(sm.avgR) + "R",
                if (sm.avgR >= 0) Palette.up else Palette.down))
            c.addView(row(ctx, "بیشترین افت", Fa.n(sm.maxDD, 2) + " (" + Fa.n(sm.maxDDPct, 1) + "٪)", Palette.down))
            sm.backtest?.let { b ->
                c.addView(row(ctx, "آزمون ۲۰ سالهٔ همین میز",
                    "${Fa.d(b.trades.toString())} معامله · ${Fa.n(b.avgR, 3)}R · PF ${Fa.n(b.pf, 2)} · " +
                        "سال+ ${Fa.d(b.positiveYears.toString())}/${Fa.d(b.yearCount.toString())}",
                    if (b.avgR >= 0) Palette.up else Palette.down))
            } ?: c.addView(row(ctx, "آزمون ۲۰ سالهٔ همین میز",
                "اجرا نشده — از تنظیمات، دکمهٔ «بک‌تست ۲۰ سالهٔ همین میز»",
                Palette.dim))
            if (sm.curve.size > 1) {
                val mc = MgmtCurveView(ctx).apply {
                    setData(sm.curve, sm.capital, if (sm.realized >= 0) Palette.up else Palette.down)
                }
                c.addView(mc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 110f))
                    .apply { topMargin = Ui.dp(ctx, 6f) })
            }
            c.addView(row(ctx, "توجه", "این میز سرمایه و افت خودش را دارد؛ به جمع طلا/نفت اضافه نمی‌شود.", Palette.dim))
            col0.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = Ui.dp(ctx, 8f) })
        }

        return scroll
    }
}
