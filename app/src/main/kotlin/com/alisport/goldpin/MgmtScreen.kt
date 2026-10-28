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
            "• نتیجهٔ بک‌تست فقط با درخواست بک‌تست تازه یا حذف صریح از بین می‌رود.",
            11f, Palette.dim))
        col0.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        return scroll
    }
}
