package com.alisport.goldpin.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import com.alisport.goldpin.core.Candle
import com.alisport.goldpin.core.Marker
import com.alisport.goldpin.core.MarkerKind
import com.alisport.goldpin.core.Trade
import com.alisport.goldpin.core.ZoneBox
import com.alisport.goldpin.core.ZoneStatus
import com.alisport.goldpin.util.Fa
import com.alisport.goldpin.util.Palette
import com.alisport.goldpin.util.Ui
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// ═══════════════════════════════════════════════════════════════════════════════
//  ChartView — چارت کندل با رفتار «مثل TradingView»
//
//  ژست‌ها (مطابق رفتار TradingView موبایل):
//   • دو انگشت Pinch/Spread → زوم هم‌زمان محور زمان و محور قیمت، با لنگر روی
//     نقطهٔ انگشتان (کندلِ زیر انگشت ثابت می‌ماند)
//   • دو انگشت کشیدن (بدون تغییر فاصله) → جابه‌جایی افقی+عمودی
//   • یک انگشت کشیدن روی چارت → جابه‌جایی (عمودی = مقیاس دستی می‌شود)
//   • یک انگشت کشیدن روی محور قیمت (راست) → فشرده/باز کردن مقیاس قیمت
//   • یک انگشت کشیدن روی محور زمان (پایین) → زوم زمان
//   • دو ضربه: روی چارت = بازنشانی نما ، روی محور قیمت = بازگشت به مقیاس خودکار
//   • نگه‌داشتن انگشت → کراس‌هیر + منوی زمینه (قابل تنظیم از بیرون)
//   • پرتاب اینرسی (fling) مثل TradingView
//
//  رسم: کندل + حجم در پنل جدا، شبکه، محور قیمت با اعداد گرد، برچسب آخرین قیمت،
//       لِجند OHLC، واترمارک، باکس‌های ناحیه، برچسب‌های استراتژی و خطوط معامله.
// ═══════════════════════════════════════════════════════════════════════════════

/** سطوح یک ستاپ برای نمایش روی چارت */
class SetupOverlay(
    val id: Long,
    val dir: Int,
    val stage: Int,
    val zTop: Double,
    val zBot: Double,
    val lvH: Double,
    val lvL: Double,
    val hvH: Double,
    val hvL: Double,
    val entry: Double,
    val sl: Double,
    val tp1: Double,
    val tp2: Double,
    val tpx: Double,
    val midRef: Double,
    val zBi: Int,
    val lvUsed: Boolean = false,
    val lvConfBi: Int = 0,
    val zT: Long = 0L,
    val midT: Long = 0L,
    val lvT: Long = 0L,
    val hvT: Long = 0L,
    val armT: Long = 0L,
    val fib0: Double = Double.NaN,
    val fib1: Double = Double.NaN,
    val fib382: Double = Double.NaN,
    val fib500: Double = Double.NaN,
    val fib1272: Double = Double.NaN,
    val fibValid: Boolean = false
)

class ChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── داده ───────────────────────────────────────────────────────────────────
    var candles: List<Candle> = emptyList()
    var zones: List<ZoneBox> = emptyList()
    var t1Zones: List<ZoneBox> = emptyList()
    var t2Zones: List<ZoneBox> = emptyList()
    var markers: List<Marker> = emptyList()
    var overlays: List<SetupOverlay> = emptyList()
    var trades: List<Trade> = emptyList()
    var pocPrice: Double = Double.NaN

    /** تایم‌فریم چارت (برای برچسب محور زمان و لِجند) */
    var chartTfSec: Int = 60
    /** نام نماد برای لِجند */
    var symbolName: String = "GC=F"

    // ── تنظیمات نمایش (کلی + جزئی به تفکیک هر جزء) ─────────────────────────────
    var showVolume = true
    var showZeroVolumeHighlight = true
    var showZones = true
    var showActiveStructureBoxes = true
    var showDeletedStructureBoxes = true
    var showRejectedStructureBoxes = true
    var showTrigger1Boxes = true
    var showTrigger2Boxes = true
    var showBoxLabels = true

    var showMarkers = true
    var showPinMarkers = true
    var showTrendMarkers = true
    var showTouchMarkers = true
    var showMidMarkers = true
    var showTrigger1Markers = true
    var showTrigger2Markers = true
    var showTradeMarkers = true
    /** نتیجهٔ هر معامله روی چارت: سود سبز (با مبلغ)، ضرر قرمز (با مبلغ)، سر‌به‌سر خط‌چین؛ پیش‌فرض خاموش. */
    var showTradeResults = false
    var showInvalidationMarkers = true
    var showMarkerText = true

    var showTradeLines = true
    var showSetupZoneLines = true
    var showMidLine = true
    var showTrigger1Lines = true
    var showTrigger2Lines = true
    var showEntryLine = true
    var showSlLine = true
    var showTpStepsLines = true
    var showTpFinalLine = true
    var showPocLine = true

    var showGrid = true
    var showLastPriceLine = true
    var showWatermark = true
    var showLegend = true
    var showStatusBadges = true
    /** نشانگر به‌روزرسانی زنده */
    var livePulse = false

    // ── وضعیت نما ──────────────────────────────────────────────────────────────
    /** فاصلهٔ کندل‌ها بر حسب پیکسل */
    var barW = 6f
        private set
    /** ایندکس کندلِ لبهٔ راست */
    var rightIdx = 0f
        private set
    /** مرکز محور قیمت و بازهٔ آن */
    var priceCenter = 0.0
        private set
    var priceSpan = 1.0
        private set
    /** مقیاس خودکار قیمت روشن است؟ */
    var autoPrice = true
        private set
    /** دنبال کردن آخرین کندل */
    var followLive = true
        private set

    var minBarW = 0.7f
    var maxBarW = 70f

    var onInfo: ((String) -> Unit)? = null
    var onNeedOlder: (() -> Unit)? = null
    var onContextMenu: (() -> Unit)? = null

    /**
     * مدت نگه‌داشتن انگشت برای باز شدن منوی چارت.
     *
     * پیش‌تر ۴۲۰ms بود که با ژست‌های کشیدن/زوم تداخل می‌کرد و منو ناخواسته باز
     * می‌شد. به درخواست کاربر ۲ ثانیه اضافه شد ⇒ ۲۴۲۰ms.
     */
    var longPressMs: Long = 2420L
    var onGesture: ((String) -> Unit)? = null

    // ── ابعاد ──────────────────────────────────────────────────────────────────
    private var chartW = 0
    private var chartH = 0
    val axisWdp = 62f
    val timeHdp = 22f
    private val axisW get() = Ui.dp(context, axisWdp).toFloat()
    private val timeH get() = Ui.dp(context, timeHdp).toFloat()
    private val plotW get() = (chartW - axisW).coerceAtLeast(1f)

    // ── رنگ‌ها (تم تیرهٔ TradingView) ──────────────────────────────────────────
    private val bg = Color.parseColor("#0E1116")
    private val gridCol = Color.parseColor("#1B2230")
    private val axisText = Color.parseColor("#9AA6B8")
    private val axisTextDim = Color.parseColor("#5E6A7C")
    private val upCol = Color.parseColor("#26A69A")
    private val dnCol = Color.parseColor("#EF5350")
    private val crossCol = Color.parseColor("#8FA3BF")

    // ── Paint ها ───────────────────────────────────────────────────────────────
    private val pWick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = max(1f, Ui.dp(context, 1f).toFloat()) }
    private val pBody = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pVol = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gridCol; strokeWidth = 1f }
    private val pGridDash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gridCol; strokeWidth = 1f; pathEffect = DashPathEffect(floatArrayOf(3f, 5f), 0f)
    }
    private val pAxis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#121821") }
    private val pTxt = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = axisText; textSize = Ui.dp(context, 10.5f).toFloat() }
    private val pTxtSm = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = axisText; textSize = Ui.dp(context, 9.5f).toFloat() }

    /** فقط برای برچسب کوچک «UTC» روی نوار محور زمان (رنگ و اندازهٔ جدا تا pTxtSm را تغییر ندهد). */
    private val pUTC = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = axisTextDim; textSize = Ui.dp(context, 8.5f).toFloat() }
    private val pLegend = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = Ui.dp(context, 11f).toFloat(); isFakeBoldText = true }
    private val pZoneFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pZoneLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = max(1f, Ui.dp(context, 1.2f).toFloat())
    }
    private val pDotted = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = max(1f, Ui.dp(context, 1f).toFloat())
        pathEffect = DashPathEffect(floatArrayOf(5f, 5f), 0f)
    }
    private val pDashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = max(1f, Ui.dp(context, 1.1f).toFloat())
        pathEffect = DashPathEffect(floatArrayOf(11f, 6f), 0f)
    }
    private val resWinCol = Color.parseColor("#2ECC71")
    private val resLossCol = Color.parseColor("#FF4D4F")
    private val resBeCol = Color.parseColor("#B0BAC9")
    private val pTradeRes = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = max(2f, Ui.dp(context, 2f).toFloat()); strokeCap = Paint.Cap.ROUND
    }
    private val pTradeResDash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = max(1.5f, Ui.dp(context, 1.6f).toFloat())
        pathEffect = DashPathEffect(floatArrayOf(14f, 9f), 0f)
    }
    private val pCross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f; color = crossCol
        pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
    }
    private val pBubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2B3546") }
    private val pBubbleTxt = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = Ui.dp(context, 10.5f).toFloat()
    }
    private val pLabelBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLabelTxt = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = Ui.dp(context, 9.5f).toFloat()
    }
    private val pPoc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFC107"); strokeWidth = max(1f, Ui.dp(context, 1f).toFloat())
        pathEffect = DashPathEffect(floatArrayOf(2f, 5f), 0f)
    }
    private val pWater = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#15FFFFFF"); textSize = Ui.dp(context, 42f).toFloat(); isFakeBoldText = true
    }
    private val pAuto = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5A6BFF"); textSize = Ui.dp(context, 9f).toFloat()
    }
    private val path = Path()

    // ── تعامل ──────────────────────────────────────────────────────────────────
    private val scroller = OverScroller(context)
    private var flingVx = 0f
    private var dragging = false
    private var lastX = 0f
    private var lastY = 0f
    private var axisDrag = false
    private var timeDrag = false
    private var manualPriceDrag = false
    private var multi = false
    private var downX = 0f
    private var downY = 0f
    private var pinchStartDist = 1f
    private var pinchFocusX = 0f
    private var pinchFocusY = 0f
    private var pinchIdx = 0f          // ایندکسِ زیر انگشتان در شروع پینچ
    private var pinchPrice = 0.0       // قیمتِ زیر انگشتان در شروع پینچ

    // کراس‌هیر
    private var crossOn = false
    private var crossX = 0f
    private var crossY = 0f
    /** کراس‌هیر به کندل و سطح قیمت قفل می‌شود (نه به پیکسل) تا با جابه‌جایی/زوم چارت همراه بماند. */
    private var crossIdx = -1
    private var crossPrice = Double.NaN
    /** انگشت روی چارت در حالی که کراس‌هیر روشن است ⇒ کشیدن کراس‌هیر (نه جابه‌جایی چارت). */
    private var crossDragging = false
    private var hoverIdx = -1
    private var longPressRunnable: Runnable? = null

    // دو ضربه
    private var lastTapT = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private val slop = Ui.dp(context, 6f).toFloat()
    private val touchSlop = Ui.dp(context, 10f).toFloat()

    // ══════════════════════════════════════════════════════════════════════════
    //  API عمومی (قابل تست)
    // ══════════════════════════════════════════════════════════════════════════

    /** تبدیل ایندکس کندل به مکان افقی */
    fun xOf(idx: Float): Float = plotW - (rightIdx - idx) * barW

    /** نگاشت زمان کندلِ موتور به ایندکس کندلِ نمایش‌داده‌شده در تایم‌فریم چارت فعلی */
    fun idxOfTime(t: Long, fallbackBi: Int = 0): Int {
        if (candles.isEmpty()) return 0
        if (t <= 0L) return fallbackBi.coerceIn(0, candles.size - 1)
        var lo = 0
        var hi = candles.size - 1
        if (t <= candles[lo].t) return lo
        if (t >= candles[hi].t) return hi
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val mt = candles[mid].t
            when {
                mt == t -> return mid
                mt < t -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return hi.coerceIn(0, candles.size - 1)
    }

    /** ایندکس کندل زیر مکان افقی داده‌شده */
    fun indexAtX(x: Float): Float = rightIdx - (plotW - x) / barW

    /** تبدیل قیمت به مکان عمودی */
    fun yOf(price: Double): Float {
        if (priceSpan <= 0.0) return chartH / 2f
        val top = priceCenter + priceSpan / 2.0
        return ((top - price) / priceSpan * (chartH - timeH)).toFloat()
    }

    /** قیمت زیر مکان عمودی داده‌شده */
    fun priceAtY(y: Float): Double {
        val top = priceCenter + priceSpan / 2.0
        return top - (y / (chartH - timeH).coerceAtLeast(1f)) * priceSpan
    }

    /**
     * زوم هم‌زمان زمان و قیمت با لنگر روی نقطهٔ داده‌شده (رفتار پینچ TradingView).
     * @param factor بزرگ‌تر از ۱ = زوم به داخل (کندل‌ها بازتر)
     */
    fun zoomBoth(factor: Float, focusX: Float, focusY: Float) {
        if (candles.isEmpty() || factor <= 0f) return
        val fx = focusX.coerceIn(0f, plotW)
        val fy = focusY.coerceIn(0f, (chartH - timeH).coerceAtLeast(1f))
        val idxAtFocus = indexAtX(fx)
        val priceAtFocus = priceAtY(fy)

        // زمان: فاصلهٔ کندل‌ها
        barW = (barW * factor).coerceIn(minBarW, maxBarW)
        rightIdx = idxAtFocus + (plotW - fx) / barW

        // قیمت: بازهٔ عمودی (همان ضریب)
        autoPrice = false
        val cur = if (priceSpan.isFinite() && priceSpan > 0) priceSpan else priceSpanFloor()
        val span2 = (cur / factor).coerceIn(priceSpanFloor(), 1e9)
        priceSpan = span2
        priceCenter = priceAtFocus - (0.5 - fy / (chartH - timeH).coerceAtLeast(1f)) * span2

        clampRight()
        invalidate()
    }

    /** زوم محور زمان با لنگر افقی مشخص */
    fun zoomTime(factor: Float, focusX: Float) {
        if (candles.isEmpty() || factor <= 0f) return
        val fx = focusX.coerceIn(0f, plotW)
        val idxAtFocus = indexAtX(fx)
        barW = (barW * factor).coerceIn(minBarW, maxBarW)
        rightIdx = idxAtFocus + (plotW - fx) / barW
        clampRight()
        invalidate()
    }

    /** جابه‌جایی با پیکسل */
    fun panPixels(dx: Float, dy: Float) {
        if (candles.isEmpty()) return
        rightIdx -= dx / barW
        if (abs(dy) > touchSlop) {
            autoPrice = false
            priceCenter += dy * priceSpan / (chartH - timeH).coerceAtLeast(1f)
        }
        clampRight()
        invalidate()
    }

    /** بازنشانی کامل نما (دکمهٔ «تنظیم نما») — مثل TradingView */
    fun resetView() {
        followLive = true
        autoPrice = true
        barW = Ui.dp(context, 6f).toFloat()
        rightIdx = (candles.size - 1).toFloat().coerceAtLeast(0f)
        applyAutoPrice()
        invalidate()
    }

    /** فقط بازگشت مقیاس قیمت به حالت خودکار (دو ضربه روی محور قیمت) */
    fun resetPrice() {
        autoPrice = true
        applyAutoPrice()
        invalidate()
    }

    /** رفتن به آخرین کندل */
    fun goLive() {
        followLive = true
        rightIdx = (candles.size - 1).toFloat().coerceAtLeast(0f)
        invalidate()
    }

    fun zoomIn() {
        zoomTime(1.35f, plotW / 2f)
        onGesture?.invoke("زوم به داخل")
    }

    fun zoomOut() {
        zoomTime(1f / 1.35f, plotW / 2f)
        onGesture?.invoke("زوم به بیرون")
    }

    fun scrollByBars(bars: Float) {
        followLive = false
        rightIdx += bars
        clampRight()
        invalidate()
    }

    /** به‌روزرسانی پس از تغییر داده (دنبال لایو بودن حفظ می‌شود) */
    fun refresh() {
        val maxIdx = (candles.size - 1).toFloat().coerceAtLeast(0f)
        if (followLive) rightIdx = maxIdx
        if (rightIdx > maxIdx) rightIdx = maxIdx
        if (rightIdx < 0f) rightIdx = 0f
        applyAutoPrice()
        invalidate()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  محاسبات
    // ══════════════════════════════════════════════════════════════════════════
    private fun clampRight() {
        val maxIdx = (candles.size - 1).toFloat().coerceAtLeast(0f)
        if (rightIdx > maxIdx) rightIdx = maxIdx
        if (rightIdx < 2f) rightIdx = 2f
        if (rightIdx < maxIdx - 1f) followLive = false
        if (rightIdx >= maxIdx - 0.5f) followLive = true
        if (rightIdx <= 2f && onNeedOlder != null) onNeedOlder?.invoke()
    }

    private fun visibleRange(): Pair<Int, Int> {
        if (candles.isEmpty()) return 0 to -1
        val w = if (barW.isFinite() && barW > 0.01f) barW else minBarW
        val r = if (rightIdx.isFinite()) rightIdx else 0f
        val from = floor(r - plotW / w - 1f).toInt().coerceIn(0, candles.size - 1)
        val to = ceil(r + 1f).toInt().coerceIn(0, candles.size - 1)
        return from to max(from, to)
    }

    private fun loHiVisible(): Pair<Double, Double> {
        val (from, to) = visibleRange()
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        if (from <= to) {
            for (i in from..to) {
                val c = candles[i]
                if (c.l < lo) lo = c.l
                if (c.h > hi) hi = c.h
            }
        }
        if (lo == Double.MAX_VALUE) { lo = 0.0; hi = 1.0 }
        val firstT = if (candles.isNotEmpty()) candles.first().t else 0L
        if (showZones) {
            for (z in zones) {
                if (z.status == ZoneStatus.CAP) continue
                if (z.status == ZoneStatus.ACTIVE && !showActiveStructureBoxes) continue
                if (z.status == ZoneStatus.REJECTED && !showRejectedStructureBoxes) continue
                if ((z.status == ZoneStatus.DELETED || z.status == ZoneStatus.CONSUMED) && !showDeletedStructureBoxes) continue
                if (z.status != ZoneStatus.ACTIVE && z.endT > 0L && z.endT < firstT) continue
                val sBi = idxOfTime(z.createdT, z.createdBi)
                val eBi = if (z.status == ZoneStatus.ACTIVE) candles.size - 1 else idxOfTime(z.endT, z.endBi)
                if (sBi <= to && eBi >= from) {
                    if (z.bot < lo) lo = z.bot
                    if (z.top > hi) hi = z.top
                }
            }
            if (showTrigger1Boxes) {
                for (z in t1Zones) {
                    if (z.status != ZoneStatus.ACTIVE) continue
                    val sBi = idxOfTime(z.createdT, z.createdBi)
                    if (sBi <= to) {
                        if (z.bot < lo) lo = z.bot
                        if (z.top > hi) hi = z.top
                    }
                }
            }
            if (showTrigger2Boxes) {
                for (z in t2Zones) {
                    if (z.status != ZoneStatus.ACTIVE) continue
                    val sBi = idxOfTime(z.createdT, z.createdBi)
                    if (sBi <= to) {
                        if (z.bot < lo) lo = z.bot
                        if (z.top > hi) hi = z.top
                    }
                }
            }
        }
        if (showTradeLines) for (o in overlays) {
            val arr = doubleArrayOf(
                if (showSetupZoneLines) o.zTop else Double.NaN,
                if (showSetupZoneLines) o.zBot else Double.NaN,
                if (showTrigger1Lines) o.lvH else Double.NaN,
                if (showTrigger1Lines) o.lvL else Double.NaN,
                if (showTrigger2Lines) o.hvH else Double.NaN,
                if (showTrigger2Lines) o.hvL else Double.NaN,
                if (showEntryLine) o.entry else Double.NaN,
                if (showSlLine) o.sl else Double.NaN,
                if (showTpStepsLines) o.tp1 else Double.NaN,
                if (showTpStepsLines) o.tp2 else Double.NaN,
                if (showTpFinalLine) o.tpx else Double.NaN
            )
            for (v in arr) if (!v.isNaN()) { if (v < lo) lo = v; if (v > hi) hi = v }
        }
        return lo to hi
    }

    /** مقیاس خودکار: بازهٔ دیدنی‌ها + حاشیهٔ ۸٪ */
    private fun applyAutoPrice() {
        if (!autoPrice || chartH == 0) return
        val (lo, hi) = loHiVisible()
        if (!lo.isFinite() || !hi.isFinite()) return
        if (hi <= lo) {
            // دادهٔ تخت (همهٔ قیمت‌ها برابر) یا بازهٔ خالی → دامنهٔ پیش‌فرض سالم
            val ref = if (hi.isFinite() && hi != 0.0) kotlin.math.abs(hi) else 1.0
            priceCenter = if (hi.isFinite()) hi else 0.0
            priceSpan = max(0.05, ref * 0.004)
            return
        }
        val pad = (hi - lo) * 0.08
        val center = (hi + lo) / 2.0
        priceCenter = center
        priceSpan = ((hi - lo) + 2 * pad).takeIf { it.isFinite() && it > 0 } ?: max(0.05, hi * 0.004)
    }

    /** گام گرد برای اعداد محور قیمت */
    private fun niceStep(raw: Double): Double {
        if (!raw.isFinite() || raw <= 0.0) return 1.0
        val exp = floor(log10(raw))
        val base = 10.0.pow(exp)
        val f = raw / base
        val mult = when {
            f <= 1 -> 1.0; f <= 2 -> 2.0; f <= 2.5 -> 2.5; f <= 5 -> 5.0; else -> 10.0
        }
        val out = mult * base
        return if (out.isFinite() && out > 0.0) out else 1.0
    }

    /**
     * کمترین دامنهٔ مجاز قیمت — نسبت به قیمت مرجع.
     * بدون این کف، دامنهٔ صفر یا فوق‌کوچک باعث حلقهٔ بی‌پایان در رسم شبکه می‌شود.
     */
    private fun priceSpanFloor(): Double {
        val ref = when {
            priceCenter.isFinite() && priceCenter != 0.0 -> kotlin.math.abs(priceCenter)
            candles.isNotEmpty() -> kotlin.math.abs(candles.last().c)
            else -> 1.0
        }
        return max(0.05, ref * 1e-7)
    }

    /**
     * پاک‌سازی مقادیر نما — کلید جلوگیری از فریز:
     * دامنهٔ قیمت، عرض کندل و موقعیت باید متناهی و در محدودهٔ سالم باشند.
     */
    private fun sanitizeView() {
        if (!barW.isFinite() || barW < minBarW * 0.5f) barW = minBarW
        if (barW > maxBarW * 2f) barW = maxBarW
        val maxIdx = (candles.size - 1).coerceAtLeast(0).toFloat()
        if (!rightIdx.isFinite()) rightIdx = maxIdx
        rightIdx = rightIdx.coerceIn(0f, maxIdx)
        if (!priceCenter.isFinite()) priceCenter = if (candles.isEmpty()) 0.0 else candles.last().c
        val floorSpan = priceSpanFloor()
        if (!priceSpan.isFinite() || priceSpan < floorSpan) priceSpan = floorSpan
        if (priceSpan > 1e12) priceSpan = 1e12
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  لمس
    // ══════════════════════════════════════════════════════════════════════════
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        chartW = w; chartH = h
        resetView()
        super.onSizeChanged(w, h, ow, oh)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scroller.forceFinished(true)
                dragging = true
                multi = false
                downX = event.x; downY = event.y
                lastX = event.x; lastY = event.y
                axisDrag = event.x >= plotW
                timeDrag = event.y >= chartH - timeH
                // کراس‌هیر با لمس تازه خاموش نمی‌شود؛ اگر روشن باشد، همین انگشت آن را می‌کشد.
                crossDragging = crossOn && !axisDrag && !timeDrag
                if (crossDragging) setCross(event.x, event.y)
                scheduleLongPress(event.x, event.y)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelPendingLongPress()
                multi = true
                crossDragging = false
                pinchStartDist = spacing(event).coerceAtLeast(1f)
                pinchFocusX = focusX(event)
                pinchFocusY = focusY(event)
                pinchIdx = indexAtX(pinchFocusX)
                pinchPrice = priceAtY(pinchFocusY)
                barWAtPinchStart = barW
                pinchSpanStart = priceSpan
                lastX = pinchFocusX; lastY = pinchFocusY
            }

            MotionEvent.ACTION_MOVE -> {
                // ⚠ کشیدن **تک‌انگشتی** هم باید تایمر لمس طولانی را لغو کند. پیش‌تر
                // فقط `pointerCount >= 2` لغو می‌کرد، یعنی یک پنِ آرامِ تک‌انگشتی پس از
                // گذشت آستانه، ناخواسته منوی چارت را باز می‌کرد. با زیاد شدن آستانه به
                // ۲۴۲۰ms این حالت خیلی محتمل‌تر شد، پس با عبور از touch-slop لغو می‌شود.
                if (event.pointerCount < 2) {
                    val slop = Ui.dp(context, 10f).toFloat()
                    if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                        cancelPendingLongPress()
                    }
                }
                if (event.pointerCount >= 2) {
                    cancelPendingLongPress()
                    val dist = spacing(event)
                    val fx = focusX(event)
                    val fy = focusY(event)
                    // ① پینچ: زوم هم‌زمان دو محور با لنگر روی انگشتان
                    if (pinchStartDist > 0f) {
                        val f = dist / pinchStartDist
                        if (abs(f - 1f) > 0.002f) {
                            barW = (barWAtPinchStart * f).coerceIn(minBarW, maxBarW)
                            autoPrice = false
                            priceSpan = (pinchSpanStart / f).coerceIn(0.05, 1e9)
                        }
                    }
                    // ② جابه‌جایی دو انگشتی: انگشتانِ جابه‌جاشده لنگر را می‌کشند
                    val idxUnder = pinchIdx - (fx - pinchFocusX) / barWAtPinchStart
                    rightIdx = idxUnder + (plotW - fx) / barW
                    val pxUnder = pinchPrice + (fy - pinchFocusY) / (chartH - timeH).coerceAtLeast(1f) * pinchSpanStart
                    priceCenter = pxUnder - (0.5 - fy / (chartH - timeH).coerceAtLeast(1f)) * priceSpan
                    clampRight()
                    invalidate()
                } else {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (!crossDragging && (abs(event.x - downX) > slop || abs(event.y - downY) > slop)) cancelLongPress()
                    when {
                        crossDragging -> {     // کراس‌هیر را کندل‌به‌کندل و سطح‌به‌سطح بکش
                            setCross(event.x, event.y)
                            updateHover()
                            invalidate()
                        }
                        axisDrag || event.x >= plotW -> {   // کشیدن روی محور قیمت
                            manualPriceDrag = true
                            autoPrice = false
                            val f = 1.0 + (dy / (chartH - timeH).coerceAtLeast(1f)) * 1.6
                            priceSpan = (priceSpan * f).coerceIn(0.05, 1e9)
                            invalidate()
                        }
                        timeDrag || event.y >= chartH - timeH -> {   // کشیدن روی محور زمان
                            zoomTime(1f + (dx / plotW) * 1.4f, plotW)
                        }
                        else -> {
                            panPixels(dx, dy)
                            flingVx = dx
                        }
                    }
                    lastX = event.x; lastY = event.y
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // از حالت دو انگشتی به یک انگشتی: مقادیر مرجع را به‌روز کن
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining); lastY = event.getY(remaining)
                pinchStartDist = 0f
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelPendingLongPress()
                dragging = false
                if (crossDragging) {
                    crossDragging = false
                    val tapped = abs(event.x - downX) < touchSlop && abs(event.y - downY) < touchSlop
                    if (tapped) {
                        // ضربهٔ دوباره روی چارت ⇒ کراس‌هیر خاموش
                        crossOn = false
                        hoverIdx = -1
                        onInfo?.invoke("")
                        lastTapT = SystemClock.uptimeMillis(); lastTapX = event.x; lastTapY = event.y
                    }
                    // پس از کشیدن، کراس‌هیر روی چارت می‌ماند
                    invalidate()
                    return true
                }
                if (manualPriceDrag) { manualPriceDrag = false }
                // تشخیص دو ضربه
                val now = SystemClock.uptimeMillis()
                val isTap = abs(event.x - downX) < touchSlop && abs(event.y - downY) < touchSlop
                if (isTap) {
                    if (now - lastTapT < 320 && kotlin.math.hypot((event.x - lastTapX).toDouble(), (event.y - lastTapY).toDouble()) < Ui.dp(context, 40f)) {
                        // دو ضربه
                        if (event.x >= plotW) {
                            resetPrice()
                            onGesture?.invoke("مقیاس قیمت خودکار شد")
                        } else {
                            resetView()
                            onGesture?.invoke("نما بازنشانی شد")
                        }
                        lastTapT = 0L
                        return true
                    }
                    lastTapT = now; lastTapX = event.x; lastTapY = event.y
                    if (event.x < plotW && event.y < chartH - timeH) {
                        crossOn = true
                        setCross(event.x, event.y)
                        updateHover(); invalidate()
                    }
                    return true
                }
                // پرتاب اینرسی
                if (!multi && abs(flingVx) > Ui.dp(context, 2f)) {
                    scroller.fling(
                        (rightIdx * 1000).toInt(), 0,
                        (-flingVx * 1000 / barW).toInt(), 0,
                        Int.MIN_VALUE, Int.MAX_VALUE, 0, 0
                    )
                    postInvalidateOnAnimation()
                    onGesture?.invoke("پرتاب")
                }
                flingVx = 0f
                if (multi) { /* زوم دو انگشتی تمام شد */ }
                multi = false
            }
        }
        return true
    }

    private var barWAtPinchStart = 6f
    private var pinchSpanStart = 1.0

    private fun spacing(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 1f
        return kotlin.math.hypot((e.getX(0) - e.getX(1)).toDouble(), (e.getY(0) - e.getY(1)).toDouble()).toFloat()
    }

    private fun focusX(e: MotionEvent): Float {
        if (e.pointerCount < 2) return e.x
        return (e.getX(0) + e.getX(1)) / 2f
    }

    private fun focusY(e: MotionEvent): Float {
        if (e.pointerCount < 2) return e.y
        return (e.getY(0) + e.getY(1)) / 2f
    }

    private fun scheduleLongPress(x: Float, y: Float) {
        cancelPendingLongPress()
        val r = Runnable {
            crossOn = true
            setCross(x, y)
            crossDragging = true
            updateHover()
            invalidate()
            onContextMenu?.invoke()
        }
        longPressRunnable = r
        postDelayed(r, longPressMs)
    }

    private fun cancelPendingLongPress() {
        longPressRunnable?.let { removeCallbacks(it) }
        longPressRunnable = null
    }

    /**
     * قفل کردن کراس‌هیر روی نزدیک‌ترین کندل (خط عمودی) و نزدیک‌ترین سطح قیمتِ گرد
     * (خط افقی؛ یک‌دهم فاصلهٔ خطوط شبکه). مختصات پیکسلی در هر رسم از همین‌ها ساخته می‌شود.
     */
    private fun setCross(x: Float, y: Float) {
        // ⚠ `coerceIn(min, max)` با max < min استثنا می‌دهد؛ پیش از اولین layout کران صفر تضمین می‌شود.
        val cx = x.coerceIn(0f, maxOf(0f, plotW - 1f))
        val cy = y.coerceIn(0f, maxOf(0f, chartH - timeH - 1f))
        crossIdx = if (candles.isEmpty()) -1 else Math.round(indexAtX(cx)).coerceIn(0, candles.size - 1)
        val raw = priceAtY(cy)
        crossPrice = if (priceSpan.isFinite() && priceSpan > 0) {
            val step = niceStep(priceSpan / 60.0)
            if (step > 0 && step.isFinite()) Math.round(raw / step) * step else raw
        } else raw
        syncCrossPixels()
    }

    /** مختصات پیکسلی کراس‌هیر از کندل/سطح قفل‌شده (پس از هر جابه‌جایی/زوم چارت). */
    private fun syncCrossPixels() {
        if (crossIdx in candles.indices) crossX = xOf(crossIdx.toFloat())
        if (!crossPrice.isNaN()) crossY = yOf(crossPrice)
    }

    private fun updateHover() {
        val i = if (crossIdx >= 0) crossIdx else Math.round(indexAtX(crossX))
        hoverIdx = if (i in candles.indices) i else -1
        if (hoverIdx >= 0) {
            val c = candles[hoverIdx]
            onInfo?.invoke(
                "${Fa.jalali(c.t, timeZone = utcTz)} UTC   O ${Fa.n(c.o)}   H ${Fa.n(c.h)}   L ${Fa.n(c.l)}   C ${Fa.n(c.c)}   V ${Fa.vol(c.v)}"
            )
        }
    }

    /** چرخهٔ اینرسی (چون در View معمولی Choreographer در دسترس نیست) */
    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            rightIdx = scroller.currX / 1000f
            clampRight()
            postInvalidateOnAnimation()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  رسم
    // ══════════════════════════════════════════════════════════════════════════
    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(bg)
        if (chartW == 0 || chartH == 0) return
        if (candles.isNotEmpty()) sanitizeView()
        if (candles.isEmpty()) {
            pTxt.textSize = Ui.dp(context, 12.5f).toFloat()
            pTxt.color = axisText
            drawTextAuto(canvas, "داده‌ای نیست — تب تنظیمات ← دانلود تاریخچه",
                Ui.dp(context, 14f).toFloat(), chartH / 2f, pTxt)
            pTxt.textSize = Ui.dp(context, 10.5f).toFloat()
            return
        }
        val h = chartH - timeH
        val volH = if (showVolume) h * 0.18f else 0f
        val priceH = h - volH

        // ── واترمارک ──
        if (showWatermark) {
            canvas.save()
            canvas.rotate(-6f, chartW / 2f, chartH / 3f)
            canvas.drawText("GoldPin · XAUUSD", Ui.dp(context, 18f).toFloat(), chartH / 3f, pWater)
            canvas.restore()
        }

        val (from, to) = visibleRange()

        // ── محور قیمت (پس‌زمینه) ──
        // ⚠ ترتیب مهم است: این مستطیل باید **قبل** از نوشتن اعداد محور رسم شود؛ پیش‌تر
        // بعد از حلقهٔ شبکه رسم می‌شد و درست روی همان اعداد می‌افتاد، بنابراین محور
        // قیمت در اسکرین‌شات کاربر کاملاً خالی بود.
        canvas.drawRect(plotW, 0f, chartW.toFloat(), chartH.toFloat(), pAxis)

        // ── شبکه و محور قیمت ──
        if (showGrid && priceSpan.isFinite() && priceSpan > 0) {
            val step = niceStep(priceSpan / 6.0)
            var p = floor((priceCenter - priceSpan / 2) / step) * step
            var guard = 0
            while (p <= priceCenter + priceSpan / 2 && guard++ < 80) {
                val y = yOf(p)
                if (y in 0f..priceH) {
                    canvas.drawLine(0f, y, plotW, y, pGrid)
                    val lbl = fmtPrice(p)
                    canvas.drawText(lbl, plotW + Ui.dp(context, 5f), y + Ui.dp(context, 3.4f), pTxt)
                }
                val np = p + step
                if (np == p || !np.isFinite()) break     // دقت اعشاری اجازهٔ پیشرفت نمی‌دهد → توقف
                p = np
            }
        }
        // ── پنل حجم ──
        if (showVolume && volH > 0) {
            var maxV = 0.0
            for (i in from..to) if (candles[i].v > maxV) maxV = candles[i].v
            val base = priceH + volH
            for (i in from..to) {
                val c = candles[i]
                val x = xOf(i.toFloat())
                if (x < -barW || x > plotW + barW) continue
                val w = max(barW * 0.7f, 1f)
                if (c.v <= 0.0) {
                    if (showZeroVolumeHighlight) {
                        // §6 سند نهایی: کندل‌هایی که حجم صفر دارند روی چارت حجم با رنگ متمایز مشخص می‌شوند
                        val zeroH = max(volH * 0.15f, Ui.dp(context, 4f).toFloat())
                        pVol.color = (Palette.gold and 0x00FFFFFF) or 0xCC000000.toInt()
                        canvas.drawRect(x - w / 2f, base - zeroH, x + w / 2f, base, pVol)
                    }
                } else if (maxV > 0) {
                    val bh = (c.v / maxV * volH).toFloat()
                    pVol.color = (if (c.c >= c.o) upCol else dnCol) and 0x66FFFFFF
                    canvas.drawRect(x - w / 2f, base - bh, x + w / 2f, base, pVol)
                }
            }
            canvas.drawLine(0f, priceH, plotW, priceH, pGrid)
        }

        // ── از این‌جا تا پایان برچسب‌های استراتژی، رسم داخل مستطیل نمودار بریده می‌شود ──
        // (پیش‌تر متن برچسب‌ها می‌توانست تا داخل ستون محور قیمت بیرون بزند؛ نمونه‌اش در
        // اسکرین‌شات کاربر: «کندل مهم بالاترین» نیمه‌بریده در ستون محور دیده می‌شد.)
        canvas.save()
        canvas.clipRect(0f, 0f, plotW, h)

        // ── باکس‌های ناحیه ──
        if (showZones) drawZones(canvas, from, to, priceH)

        // ── POC ──
        if (showPocLine && !pocPrice.isNaN()) {
            val y = yOf(pocPrice)
            if (y in 0f..priceH) {
                canvas.drawLine(0f, y, plotW, y, pPoc)
                canvas.drawText("POC " + fmtPrice(pocPrice), Ui.dp(context, 3f).toFloat(), y - Ui.dp(context, 2.5f).toFloat(), pTxtSm)
            }
        }

        // ── کندل‌ها ──
        val bodyW = max(barW * 0.72f, 1f)
        for (i in from..to) {
            val c = candles[i]
            val x = xOf(i.toFloat())
            if (x < -barW || x > plotW + barW) continue
            val up = c.c >= c.o
            val col = if (up) upCol else dnCol
            pWick.color = col
            canvas.drawLine(x, yOf(c.h), x, yOf(c.l), pWick)
            val y1 = yOf(max(c.o, c.c))
            val y2 = yOf(min(c.o, c.c))
            pBody.color = col
            canvas.drawRect(x - bodyW / 2f, y1, x + bodyW / 2f, max(y2, y1 + 1f), pBody)
        }

        // ── خطوط معامله ──
        if (showTradeLines) drawTradeLines(canvas, priceH)

        // ── برچسب‌های استراتژی ──
        if (showMarkers) drawMarkers(canvas, from, to, priceH)

        // ── نتیجهٔ معاملات (اختیاری، پیش‌فرض خاموش) ──
        if (showTradeResults) drawTradeResults(canvas, from, to, priceH)

        canvas.restore()

        // ── محور زمان (تراز بر مرزهای طبیعی زمان — مانند تریدینگ‌ویو) ──
        // پیش‌تر برچسب‌ها «هر N کندل» انتخاب می‌شدند، پس روی زمان‌های دلخواه می‌افتادند
        // (مثلاً ۱۴:۳۷). حالا فقط روی مرزهای طبیعی (ساعت، نیم‌روز، روز، هفته) می‌نشینند
        // و در اولین کندلِ هر روز، **تاریخ** نشان داده می‌شود.
        canvas.drawRect(0f, h, chartW.toFloat(), chartH.toFloat(), pAxis)
        // منطقهٔ زمانی محور را صریح بنویس؛ همهٔ برچسب‌ها (ساعت، تاریخ، کراس‌هیر) به UTC است
        // چون سشن، پنجرهٔ سفارش‌گیری (۰۱:۳۰–۲۰:۰۰) و «پایان زمان» در سند به UTC تعریف شده‌اند.
        pUTC.color = axisTextDim
        canvas.drawText("UTC", Ui.dp(context, 2f).toFloat(), chartH - Ui.dp(context, 5f).toFloat(), pUTC)
        val tickMs = timeTickSec().toLong() * 1000L
        var lastTick = Long.MIN_VALUE
        var prevDay = Long.MIN_VALUE
        var drawn = 0
        var k = max(0, from)
        val kEnd = min(candles.size - 1, to)
        while (k <= kEnd && drawn < 400) {               // کران سخت: حداکثر ۴۰۰ برچسب
            val t = candles[k].t
            val tick = t / tickMs
            if (tick != lastTick) {
                lastTick = tick
                val x = xOf(k.toFloat())
                if (x in 0f..plotW) {
                    canvas.drawLine(x, 0f, x, h, pGridDash)
                    val day = t / 86_400_000L
                    val newDay = day != prevDay
                    prevDay = day
                    val lbl = timeLabel(t, tickMs, newDay)
                    val tw = pTxtSm.measureText(lbl)
                    val paint = if (newDay && tickMs < 86_400_000L) pTxt else pTxtSm
                    canvas.drawText(lbl, (x - tw / 2).coerceIn(Ui.dp(context, 26f).toFloat(), plotW - tw - 2f),
                        chartH - Ui.dp(context, 5f).toFloat(), paint)
                    drawn++
                }
            }
            k++
        }

        // ── برچسب آخرین قیمت ──
        if (showLastPriceLine) {
            val last = candles.last()
            val lastY = yOf(last.c)
            if (lastY in 0f..priceH) {
                val up = last.c >= last.o
                pDashed.color = if (up) upCol else dnCol
                canvas.drawLine(0f, lastY, plotW, lastY, pDashed)
                val txt = fmtPrice(last.c)
                pLabelBg.color = (if (up) upCol else dnCol) and 0x00FFFFFF or 0xFF000000.toInt()
                val tw = min(pLabelTxt.measureText(txt) + Ui.dp(context, 8f), axisW - 2f)
                canvas.drawRoundRect(RectF(plotW + 1f, lastY - Ui.dp(context, 8f), plotW + tw, lastY + Ui.dp(context, 8f)),
                    Ui.dp(context, 2f).toFloat(), Ui.dp(context, 2f).toFloat(), pLabelBg)
                canvas.drawText(txt, plotW + Ui.dp(context, 4f), lastY + Ui.dp(context, 3.5f), pLabelTxt)
            }
        }

        // ── نشانگر مقیاس خودکار ──
        if (showStatusBadges && autoPrice) {
            drawTextAuto(canvas, "خودکار", plotW + Ui.dp(context, 4f), Ui.dp(context, 12f).toFloat(), pAuto)
        }

        // ── لِجند بالا-چپ ──
        if (showLegend) drawLegend(canvas)

        // ── کراس‌هیر ──
        if (crossOn) syncCrossPixels()
        if (crossOn && crossX >= 0f && crossX < plotW && crossY >= 0f && crossY <= h) {
            canvas.drawLine(crossX, 0f, crossX, h, pCross)
            canvas.drawLine(0f, crossY, plotW, crossY, pCross)
            val px = if (crossPrice.isNaN()) priceAtY(crossY) else crossPrice
            bubble(canvas, plotW + 1f, crossY - Ui.dp(context, 9f), axisW - 2f, fmtPrice(px))
            if (crossIdx in candles.indices) {
                val t = timeLabelFull(candles[crossIdx].t)
                val w = textWidth(t, pBubbleTxt) + Ui.dp(context, 10f)
                bubble(canvas, (crossX - w / 2).coerceIn(0f, plotW - w), h + 1f, w, t)
            }
        }

        // ── نشانگر لایو ──
        if (showStatusBadges && livePulse && followLive) {
            val r = Ui.dp(context, 3f).toFloat()
            canvas.drawCircle(plotW - r * 3, Ui.dp(context, 10f).toFloat(), r, pBody.apply { color = upCol })
        }
    }

    private fun fmtPrice(p: Double): String = when {
        p >= 1000 -> String.format("%,.2f", p)
        p >= 10 -> String.format("%.5f", p)
        else -> String.format("%.2f", p)
    }

    /**
     * گام برچسب‌های محور زمان، بر حسب **ثانیه** و همیشه روی یک مرز طبیعی.
     *
     * مثل تریدینگ‌ویو: تعداد برچسب‌ها با زوم کم و زیاد نمی‌شود؛ آنچه عوض می‌شود
     * «دانه‌بندی» است — از دقیقه به ۵دقیقه، ۱۵دقیقه، ساعت، ۴ساعت، روز، هفته.
     * چون برچسب فقط وقتی `t / tickMs` عوض می‌شود رسم می‌گردد، مرزها همیشه روی
     * زمان‌های رُند می‌نشینند (۱۴:۰۰ نه ۱۴:۳۷).
     */
    private fun timeTickSec(): Int {
        val bars = (plotW / barW).coerceAtLeast(1f)
        val wantSec = ((bars / 6f).coerceAtLeast(1f) * chartTfSec).toLong()
        val steps = listOf(60L, 300L, 900L, 1800L, 3600L, 7200L, 14400L, 21600L,
            43200L, 86400L, 172800L, 604800L)
        return (steps.firstOrNull { it >= wantSec } ?: 604800L).toInt()
    }

    /**
     * برچسب محور زمان.
     * @param tickMs گام فعلی محور
     * @param newDay آیا این کندل اولین کندلِ روزِ جدید است
     *
     * قاعده (همان تریدینگ‌ویو): در تایم‌فریم داخل‌روزی ساعت نشان داده می‌شود و در
     * اولین کندل هر روز، تاریخ جایش را می‌گیرد. در تایم‌فریم روزانه و بالاتر فقط تاریخ.
     */
    private fun timeLabel(t: Long, tickMs: Long, newDay: Boolean): String {
        // ⚠ همه‌چیز UTC: ساعت برچسب‌ها، تشخیص «روز جدید» و تاریخ شمسی باید یک منطقهٔ
        // زمانی داشته باشند. پیش‌تر ساعت UTC بود ولی `Fa.jalaliShort` تاریخ را به وقت
        // محلی دستگاه می‌گرفت؛ روی دستگاه با اختلاف ساعت، برچسب تاریخ یک روز جابه‌جا
        // می‌شد (مرز روز با UTC سنجیده می‌شد، تاریخ با وقت محلی نوشته می‌شد).
        if (tickMs >= 86_400_000L || newDay) return Fa.jalaliShort(t, utcTz)
        val c = java.util.Calendar.getInstance(utcTz).apply { timeInMillis = t }
        return Fa.d(String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE)))
    }

    private fun timeLabelFull(t: Long): String = Fa.jalali(t, timeZone = utcTz)

    private val utcTz: java.util.TimeZone = java.util.TimeZone.getTimeZone("UTC")

    // ══════════════════════════════════════════════════════════════════════════
    //  نوشتن متن فارسی (RTL)
    //
    //  ⚠ ایرادِ دیده‌شده در اسکرین‌شات: برچسب‌های فارسی با `Canvas.drawText` نوشته
    //  می‌شدند. Canvas هیچ بازآرایی دوجهته (bidi) انجام نمی‌دهد؛ نتیجه این بود که
    //  حروفِ هر کلمه درست ولی **ترتیب کلمات برعکس** بود («کندل مهم بالاترین» به شکل
    //  «بالاترین مهم کندل» دیده می‌شد). برای متن فارسی باید از StaticLayout با جهت
    //  RTL استفاده شود تا هم شکل‌دهی حروف و هم ترتیب کلمات/اعداد درست باشد.
    // ══════════════════════════════════════════════════════════════════════════
    private val rtlCache = HashMap<String, StaticLayout>()

    /**
     * آیا رشته «متن فارسی» است (حرف دارد)؟
     *
     * ⚠ ایراد مهمی که با بازرسی محور زمان در اسکرین‌شات پیدا شد: بازهٔ قبلی
     * `'\u0600'..'\u06FF'` **ارقام فارسی/عربی** (۰-۹ = U+06F0..U+06F9 و ٠-٩ = U+0660..U+0669)
     * را هم متن فارسی حساب می‌کرد. بنابراین برچسب‌هایی مثل «۰۵/۰۹/۱۱» یا «۱۲:۰۰» که
     * `Fa.d()` آن‌ها را به رقم فارسی تبدیل کرده با StaticLayout در جهت RTL چیده می‌شدند.
     * در الگوریتم دوجهته، ارقام «AN» هستند و جداکنندهٔ «/» یا «:» بین آن‌ها هم جهت AN
     * می‌گیرد؛ نتیجه این بود که **ترتیب بخش‌ها برعکس** می‌شد: تاریخِ ۱۴۰۵/۰۹/۱۱ روی محور
     * به شکل «۱۱/۰۹/۰۵» و ساعت ۲۱:۳۰ به شکل «۳۰:۲۱» دیده می‌شد. خودِ ارقام هم در
     * بلوک RTL ممکن است جابه‌جا شوند.
     *
     * حالا فقط **حروف** فارسی/عربی باعث چیدمان RTL می‌شوند؛ رشته‌ای که فقط رقم و
     * جداکننده دارد با `canvas.drawText` و در جهت LTR نوشته می‌شود (همان چیزی که
     * برای عدد/تاریخ/ساعت درست است). رشته‌های مختلط (مثل «ورود خرید @ ۴۱۹۲٫۶۰»)
     * همچنان RTL چیده می‌شوند تا ترتیب کلمات درست بماند و عدد هم به‌صورت LTR داخل
     * متن جای بگیرد.
     */
    private fun hasFa(s: String): Boolean = s.any { ch ->
        when {
            ch in '\u0621'..'\u064A' -> true                 // حروف عربی/فارسی پایه (ا ب پ ت … ی)
            ch in '\u0671'..'\u06D5' -> ch !in '\u06F0'..'\u06F9'  // حروف فارسی/علائم، بدون ارقام ۰-۹
            ch in '\uFB50'..'\uFDFF' -> true                 // فرم‌های نمایشی عربی A
            ch in '\uFE70'..'\uFEFF' -> true                 // فرم‌های نمایشی عربی B
            else -> false
        }
    }

    private fun rtlLayout(text: String, paint: TextPaint): StaticLayout {
        val key = text + "\u0000" + paint.textSize + "\u0000" + paint.isFakeBoldText
        rtlCache[key]?.let { return it }
        // عرض کمی بزرگ‌تر از اندازهٔ متن تا هیچ‌وقت شکسته نشود
        val w = ceil(paint.measureText(text)).toInt().coerceAtLeast(1) + 6
        val sl = StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .setIncludePad(false)
            .build()
        if (rtlCache.size > 512) rtlCache.clear()
        rtlCache[key] = sl
        return sl
    }

    /** عرض واقعی متن (فارسی = عرض Layout، لاتین = measureText). */
    private fun textWidth(text: String, paint: TextPaint): Float =
        if (hasFa(text)) rtlLayout(text, paint).width.toFloat() else paint.measureText(text)

    /** رسم متن با درنظرگرفتن جهت RTL؛ [left] لبهٔ چپ کادر متن و [baseline] خط پایه است. */
    private fun drawTextAuto(canvas: Canvas, text: String, left: Float, baseline: Float, paint: TextPaint) {
        if (hasFa(text)) {
            val sl = rtlLayout(text, paint)
            canvas.save()
            canvas.translate(left, baseline - sl.getLineBaseline(0))
            sl.draw(canvas)
            canvas.restore()
        } else {
            canvas.drawText(text, left, baseline, paint)
        }
    }

    private fun bubble(canvas: Canvas, x: Float, y: Float, w: Float, text: String) {
        val r = RectF(x, y, x + w, y + Ui.dp(context, 18f))
        canvas.drawRoundRect(r, Ui.dp(context, 3f).toFloat(), Ui.dp(context, 3f).toFloat(), pBubble)
        drawTextAuto(canvas, text, r.left + Ui.dp(context, 5f), r.bottom - Ui.dp(context, 4.5f), pBubbleTxt)
    }

    /**
     * برچسبِ کادرِ رنگی با پس‌زمینه (مثل TradingView) — همیشه داخل ناحیهٔ چارت می‌ماند،
     * در صورت برخورد با برچسبِ قبلی جابه‌جا می‌شود و هرگز روی محور قیمت سرریز نمی‌کند.
     */
    private fun labelBox(
        canvas: Canvas,
        text: String,
        anchorX: Float,
        anchorY: Float,
        above: Boolean,
        bgColor: Int,
        priceH: Float,
        used: MutableList<RectF>,
        textColor: Int = Color.WHITE
    ) {
        val t = text.lineSequence().first().trim()
        if (t.isEmpty()) return
        val padX = Ui.dp(context, 5f)
        val labelH = Ui.dp(context, 16f).toFloat()
        val gap = Ui.dp(context, 4f).toFloat()
        val w = textWidth(t, pLabelTxt) + padX * 2
        var left = anchorX + gap
        if (left + w > plotW - 2f) left = anchorX - gap - w
        left = left.coerceIn(2f, (plotW - w - 2f).coerceAtLeast(2f))
        var top = if (above) anchorY - gap - labelH else anchorY + gap
        // اگر روی برچسب دیگری افتاد، تا ۵ پله جابه‌جا کن
        var tries = 0
        while (tries < 5 && used.any { RectF.intersects(it, RectF(left, top, left + w, top + labelH)) }) {
            top += if (above) -(labelH + 2f) else (labelH + 2f)
            tries++
        }
        top = top.coerceIn(2f, (priceH - labelH - 2f).coerceAtLeast(2f))
        val rect = RectF(left, top, left + w, top + labelH)
        used.add(rect)
        pLabelBg.color = bgColor
        canvas.drawRoundRect(rect, Ui.dp(context, 3f).toFloat(), Ui.dp(context, 3f).toFloat(), pLabelBg)
        pLabelTxt.color = textColor
        drawTextAuto(canvas, t, left + padX, top + labelH * 0.72f, pLabelTxt)
        pLabelTxt.color = Color.WHITE
    }

    private fun drawLegend(canvas: Canvas) {
        val i = if (hoverIdx in candles.indices) hoverIdx else candles.size - 1
        if (i < 0) return
        val c = candles[i]
        var x = Ui.dp(context, 8f).toFloat()
        val y = Ui.dp(context, 14f).toFloat()
        pLegend.color = Palette.txt
        canvas.drawText("$symbolName · ${com.alisport.goldpin.core.Tf.label(chartTfSec)}", x, y, pLegend)
        x += pLegend.measureText("$symbolName · ${com.alisport.goldpin.core.Tf.label(chartTfSec)}") + Ui.dp(context, 10f)
        pLegend.color = if (c.c >= c.o) upCol else dnCol
        val parts = listOf("O ${Fa.n(c.o)}", "H ${Fa.n(c.h)}", "L ${Fa.n(c.l)}", "C ${Fa.n(c.c)}")
        for (s in parts) {
            canvas.drawText(s, x, y, pLegend)
            x += pLegend.measureText(s) + Ui.dp(context, 8f)
            if (x > plotW - Ui.dp(context, 40f)) break
        }
        if (showVolume) {
            pLegend.color = axisText
            canvas.drawText("V ${Fa.vol(c.v)}", x, y, pLegend)
        }
    }

    private fun drawZones(canvas: Canvas, from: Int, to: Int, priceH: Float) {
        val firstT = if (candles.isNotEmpty()) candles.first().t else 0L
        for (z in zones) {
            if (z.status == ZoneStatus.CAP) continue
            if (z.status == ZoneStatus.ACTIVE && !showActiveStructureBoxes) continue
            if (z.status == ZoneStatus.REJECTED && !showRejectedStructureBoxes) continue
            if ((z.status == ZoneStatus.DELETED || z.status == ZoneStatus.CONSUMED) && !showDeletedStructureBoxes) continue
            if (z.status != ZoneStatus.ACTIVE && z.endT > 0L && z.endT < firstT) continue
            val startIdx = idxOfTime(z.createdT, z.createdBi).toFloat()
            val endIdx = if (z.status == ZoneStatus.ACTIVE) rightIdx + 2f else idxOfTime(z.endT, z.endBi).toFloat()
            val x1 = xOf(startIdx)
            val x2 = xOf(endIdx)
            if (x2 < -barW || x1 > plotW + barW) continue
            val left = max(min(x1, x2), -barW)
            val right = min(max(x1, x2), plotW + barW)
            val yT = yOf(z.top)
            val yB = yOf(z.bot)
            val active = z.status == ZoneStatus.ACTIVE
            val color = when {
                z.status == ZoneStatus.REJECTED -> Color.parseColor("#7A8798")
                z.status == ZoneStatus.DELETED || z.status == ZoneStatus.CONSUMED -> Color.parseColor("#5A6472")
                z.dir == 1 -> upCol
                else -> Color.parseColor("#FF8A3D")
            }
            pZoneFill.color = (color and 0x00FFFFFF) or ((if (active) 34 else 14) shl 24)
            pZoneFill.style = Paint.Style.FILL
            canvas.drawRect(left, yT, right, yB, pZoneFill)
            val lp = if (active) pZoneLine else pDotted
            lp.color = (color and 0x00FFFFFF) or (if (active) 0xB0000000.toInt() else 0x55000000)
            canvas.drawLine(left, yT, right, yT, lp)
            canvas.drawLine(left, yB, right, yB, lp)
            if (showBoxLabels && active && barW > 3.5f) {
                pLabelBg.color = (color and 0x00FFFFFF) or 0xB0000000.toInt()
                canvas.drawRoundRect(
                    RectF(left + 2f, yT + 2f, left + Ui.dp(context, 30f), yT + Ui.dp(context, 15f)),
                    Ui.dp(context, 3f).toFloat(), Ui.dp(context, 3f).toFloat(), pLabelBg
                )
                canvas.drawText("Z${Fa.d(z.idx.toString())}", left + Ui.dp(context, 5f), yT + Ui.dp(context, 12.5f), pLabelTxt)
            }
        }
        // باکس‌های تاییدشدهٔ تریگر ۱ (ولوم کم) و تریگر ۲ (ولوم زیاد) ثبت‌شده در حافظهٔ مود انتخاب‌شده
        if (showTrigger1Boxes) {
            for (z in t1Zones) {
                if (z.status != ZoneStatus.ACTIVE) continue
                val startIdx = idxOfTime(z.createdT, z.createdBi).toFloat()
                val x1 = xOf(startIdx)
                val x2 = xOf(rightIdx + 2f)
                if (x2 < -barW || x1 > plotW + barW) continue
                val left = max(min(x1, x2), -barW)
                val right = min(max(x1, x2), plotW + barW)
                val yT = yOf(z.top)
                val yB = yOf(z.bot)
                pZoneFill.color = (Palette.gold and 0x00FFFFFF) or (28 shl 24)
                pZoneFill.style = Paint.Style.FILL
                canvas.drawRect(left, yT, right, yB, pZoneFill)
                pDotted.color = (Palette.gold and 0x00FFFFFF) or 0xB0000000.toInt()
                canvas.drawLine(left, yT, right, yT, pDotted)
                canvas.drawLine(left, yB, right, yB, pDotted)
                if (showBoxLabels && barW > 3.5f) {
                    pLabelBg.color = (Palette.gold and 0x00FFFFFF) or 0xB0000000.toInt()
                    canvas.drawRoundRect(
                        RectF(left + 2f, yT + 2f, left + Ui.dp(context, 28f), yT + Ui.dp(context, 15f)),
                        Ui.dp(context, 3f).toFloat(), Ui.dp(context, 3f).toFloat(), pLabelBg
                    )
                    canvas.drawText("T1", left + Ui.dp(context, 5f), yT + Ui.dp(context, 12.5f), pLabelTxt)
                }
            }
        }
        if (showTrigger2Boxes) {
            for (z in t2Zones) {
                if (z.status != ZoneStatus.ACTIVE) continue
                val startIdx = idxOfTime(z.createdT, z.createdBi).toFloat()
                val x1 = xOf(startIdx)
                val x2 = xOf(rightIdx + 2f)
                if (x2 < -barW || x1 > plotW + barW) continue
                val left = max(min(x1, x2), -barW)
                val right = min(max(x1, x2), plotW + barW)
                val yT = yOf(z.top)
                val yB = yOf(z.bot)
                pZoneFill.color = (Palette.accent and 0x00FFFFFF) or (28 shl 24)
                pZoneFill.style = Paint.Style.FILL
                canvas.drawRect(left, yT, right, yB, pZoneFill)
                pDotted.color = (Palette.accent and 0x00FFFFFF) or 0xB0000000.toInt()
                canvas.drawLine(left, yT, right, yT, pDotted)
                canvas.drawLine(left, yB, right, yB, pDotted)
                if (showBoxLabels && barW > 3.5f) {
                    pLabelBg.color = (Palette.accent and 0x00FFFFFF) or 0xB0000000.toInt()
                    canvas.drawRoundRect(
                        RectF(left + 2f, yT + 2f, left + Ui.dp(context, 28f), yT + Ui.dp(context, 15f)),
                        Ui.dp(context, 3f).toFloat(), Ui.dp(context, 3f).toFloat(), pLabelBg
                    )
                    canvas.drawText("T2", left + Ui.dp(context, 5f), yT + Ui.dp(context, 12.5f), pLabelTxt)
                }
            }
        }
    }

    private fun isMarkerKindVisible(kind: Int): Boolean = when (kind) {
        MarkerKind.PIN_BU, MarkerKind.PIN_BE, MarkerKind.STRUCT -> showPinMarkers
        MarkerKind.TREND_UP, MarkerKind.TREND_DOWN -> showTrendMarkers
        2 -> showTouchMarkers
        3 -> showMidMarkers
        4, 5 -> showTrigger1Markers
        6, 7 -> showTrigger2Markers
        8, 9 -> showTradeMarkers
        10, 11 -> showInvalidationMarkers
        else -> true
    }

    /**
     * برچسب‌های استراتژی (کندل مهم/پین، روند، رخدادها).
     *
     * ⚠ دو ایراد جدی که در اسکرین‌شات کاربر دیده می‌شد این‌جا اصلاح شده است:
     *  ۱) متن فارسی با `drawText` نوشته می‌شد و ترتیب کلمات برعکس بود (مثلاً
     *     «بالاترین مهم کندل» به‌جای «کندل مهم بالاترین»)؛ حالا همهٔ متن‌ها از مسیر
     *     RTL ([drawTextAuto]) می‌روند.
     *  ۲) مثلث پین‌ها سمت اشتباه رسم می‌شد: pinBE (سقف/مقاومت) باید **بالای** سقفِ
     *     کندل مهم و رو به بالا باشد و pinBU (کف/حمایت) **زیر** کف آن و رو به پایین؛
     *     پیش‌تر برعکس بود و مارکر روی بدنهٔ کندل می‌افتاد.
     *  ۳) برچسب‌ها بدون پس‌زمینه و بدون محدودکردن به ناحیهٔ نمودار نوشته می‌شدند،
     *     روی کندل‌های همسایه می‌افتادند و تا ستون محور قیمت بیرون می‌زدند؛ حالا هر
     *     برچسب داخل کادر رنگی خودش است، به ناحیهٔ نمودار محدود می‌شود و با برچسب‌های
     *     دیگر برخورد نمی‌کند.
     */
    private fun drawMarkers(canvas: Canvas, from: Int, to: Int, priceH: Float) {
        val firstT = if (candles.isNotEmpty()) candles.first().t else 0L
        val used = ArrayList<RectF>()
        val r = Ui.dp(context, 3f).toFloat()
        for (m in markers) {
            if (!isMarkerKindVisible(m.kind)) continue
            if (m.t > 0L && m.t < firstT) continue
            val mBi = idxOfTime(m.t, m.bi)
            if (mBi < from - 2 || mBi > to + 2) continue
            val x = xOf(mBi.toFloat())
            if (x < -barW || x > plotW + barW) continue
            val y = yOf(m.price)
            if (y < -30 || y > priceH + 40) continue
            val col = when (m.kind) {
                MarkerKind.PIN_BU -> Palette.accent
                MarkerKind.PIN_BE -> Color.parseColor("#C2417C")
                MarkerKind.TREND_UP -> upCol
                MarkerKind.TREND_DOWN -> dnCol
                5 -> Palette.gold
                7 -> upCol
                8 -> Palette.gold
                9 -> Palette.violet
                10 -> dnCol
                11 -> Palette.grey
                else -> Palette.grey
            }
            if (m.kind == MarkerKind.STRUCT) {
                // برچسب ساختار HH / HL / LL / LH روی کندل مهمِ پین (سند §۴-۱ «روند»)
                val isHigh = m.text == "HH" || m.text == "LH"
                val structCol = if (isHigh) Color.parseColor("#C2417C") else Palette.accent
                val labelW = textWidth(m.text, pLabelTxt) + Ui.dp(context, 10f)
                val labelH = Ui.dp(context, 16f).toFloat()
                val gap = Ui.dp(context, 12f).toFloat()
                val left = (x - labelW / 2f).coerceIn(2f, (plotW - labelW - 2f).coerceAtLeast(2f))
                val wantedTop = if (isHigh) y - gap - labelH else y + gap
                val top = wantedTop.coerceIn(2f, (priceH - labelH - 2f).coerceAtLeast(2f))
                pLabelBg.color = (structCol and 0x00FFFFFF) or 0xE6000000.toInt()
                val rect = RectF(left, top, left + labelW, top + labelH)
                used.add(rect)
                canvas.drawRoundRect(rect, Ui.dp(context, 3f).toFloat(), Ui.dp(context, 3f).toFloat(), pLabelBg)
                pLabelTxt.color = Color.WHITE
                drawTextAuto(canvas, m.text, left + Ui.dp(context, 5f), top + labelH * 0.72f, pLabelTxt)
                continue
            }
            if (m.kind == MarkerKind.TREND_UP || m.kind == MarkerKind.TREND_DOWN) {
                val up = m.kind == MarkerKind.TREND_UP
                val tipY = if (up) y - r * 3.2f else y + r * 3.2f
                val baseY = if (up) y + r * 0.6f else y - r * 0.6f
                pBody.color = col
                path.reset()
                path.moveTo(x, tipY)
                path.lineTo(x - r * 2.6f, baseY)
                path.lineTo(x + r * 2.6f, baseY)
                path.close()
                canvas.drawPath(path, pBody)
                if (showMarkerText && m.text.isNotEmpty()) {
                    labelBox(canvas, m.text, x, y, above = up, bgColor = (col and 0x00FFFFFF) or 0xD9000000.toInt(),
                        priceH = priceH, used = used)
                }
                continue
            }
            // ── پین‌ها و رخدادها ──
            val isPin = m.kind == MarkerKind.PIN_BU || m.kind == MarkerKind.PIN_BE
            val above = m.kind == MarkerKind.PIN_BE     // pinBE بالای سقف، pinBU زیر کف
            val tipY = if (above) y - r * 5.4f else y + r * 5.4f
            val baseY = if (above) y - r * 1.2f else y + r * 1.2f
            pBody.color = col
            path.reset()
            if (isPin) {
                path.moveTo(x, tipY)
                path.lineTo(x - r * 2.6f, baseY)
                path.lineTo(x + r * 2.6f, baseY)
            } else {
                // رخدادهای دیگر: مثلث کوچک رو به قیمت
                path.moveTo(x, y + (if (above) -2f else 2f))
                path.lineTo(x - r * 1.8f, baseY)
                path.lineTo(x + r * 1.8f, baseY)
            }
            path.close()
            canvas.drawPath(path, pBody)
            if (showMarkerText && m.text.isNotEmpty() && barW > 3f && m.kind != 2 && m.kind != 6) {
                labelBox(canvas, m.text, x, if (isPin) tipY else baseY, above = above,
                    bgColor = (col and 0x00FFFFFF) or 0xCC000000.toInt(), priceH = priceH, used = used)
            }
        }
        // قیمتِ هر مارکر با یک نقطهٔ کوچک روی نوک مثلث مشخص می‌شود تا سطح دقیق معلوم باشد
    }

    /**
     * نتیجهٔ هر معامله روی چارت: یک خط از (کندل و قیمت ورود) تا (کندل و قیمت خروج)
     *  • سود (حداقل یک پلهٔ سودده): خط سبز + برچسب سبز با مبلغ خالص، مثل «+۱۷۶٫۶۰»
     *  • ضرر (SL): خط قرمز + برچسب قرمز با مبلغ خالص، مثل «−۵۸٫۹۷»
     *  • سر‌به‌سر (خروج روی ورود، بدون سود قیمتی): خط‌چین خاکستری + «BE» (اگر کمیسیون دارد، مبلغش هم)
     *  • معاملهٔ باز: خط‌چین آبی تا آخرین کندل + سود/زیان شناورِ همین لحظه
     * رنگ و تعداد از [Trade.outcome] می‌آید (مستقل از کمیسیون)؛ مبلغ همیشه سود/زیان خالص دلاری است.
     */
    private fun drawTradeResults(canvas: Canvas, from: Int, to: Int, priceH: Float) {
        if (trades.isEmpty() || candles.isEmpty()) return
        val used = ArrayList<RectF>()
        val dot = max(2.5f, Ui.dp(context, 2.6f).toFloat())
        val labelH = Ui.dp(context, 15f).toFloat()
        val pad = Ui.dp(context, 4f).toFloat()
        val gap = Ui.dp(context, 5f).toFloat()
        val round = Ui.dp(context, 3f).toFloat()
        for (t in trades) {
            val i0 = idxOfTime(t.entryT, t.entryBi)
            val isOpen = t.open
            val i1 = if (isOpen) candles.size - 1 else idxOfTime(t.exitT ?: t.entryT, t.exitBi ?: t.entryBi)
            if (i1 < from - 1 || i0 > to + 1) continue
            val x0 = xOf(i0.toFloat()); val x1 = xOf(i1.toFloat())
            val endPx = if (isOpen) candles.last().c else (t.exitPx ?: t.entry)
            val y0 = yOf(t.entry); val y1 = yOf(endPx)
            if ((y0 < -priceH && y1 < -priceH) || (y0 > priceH * 2 && y1 > priceH * 2)) continue
            val oc = if (isOpen) 2 else t.outcome()
            val net = t.pnl()
            val col = when (oc) { 1 -> resWinCol; -1 -> resLossCol; 0 -> resBeCol; else -> Palette.accent }
            val p = if (oc == 1 || oc == -1) pTradeRes else pTradeResDash
            p.color = col
            canvas.drawLine(x0, y0, x1, y1, p)
            pBody.color = col
            canvas.drawCircle(x0, y0, dot, pBody)
            canvas.drawCircle(x1, y1, dot, pBody)

            val text = when (oc) {
                1, -1 -> Fa.signed(net)
                0 -> if (abs(net) >= 0.005) "BE " + Fa.signed(net) else "BE"
                else -> "باز " + Fa.signed(net)
            }
            val w = textWidth(text, pLabelTxt) + pad * 2
            var left = x1 + gap
            if (left + w > plotW - 2f) left = x1 - gap - w
            left = left.coerceIn(2f, (plotW - w - 2f).coerceAtLeast(2f))
            val above = y1 <= y0                                     // خروج بالاتر از ورود ⇒ برچسب بالا
            var top = if (above) y1 - gap - labelH else y1 + gap
            // اگر روی برچسب دیگری افتاد، جابه‌جا شود (حداکثر چند بار)
            var tries = 0
            while (tries < 4) {
                val r = RectF(left, top, left + w, top + labelH)
                if (used.none { RectF.intersects(it, r) }) break
                top += if (above) -(labelH + 2f) else (labelH + 2f)
                tries++
            }
            top = top.coerceIn(2f, (priceH - labelH - 2f).coerceAtLeast(2f))
            val rect = RectF(left, top, left + w, top + labelH)
            used.add(rect)
            pLabelBg.color = (col and 0x00FFFFFF) or 0xE6000000.toInt()
            canvas.drawRoundRect(rect, round, round, pLabelBg)
            pLabelTxt.color = if (oc == 0) Color.parseColor("#1B2230") else Color.WHITE
            drawTextAuto(canvas, text, left + pad, top + labelH * 0.72f, pLabelTxt)
            pLabelTxt.color = Color.WHITE
        }
    }

    private fun drawTradeLines(canvas: Canvas, priceH: Float) {
        // برچسب سطوح (ناحیه/تایید میانی/فیبو/ولوم/ورود/حدضرر/TP) همه از یک x شروع
        // می‌شوند؛ بدون مدیریت برخورد، چند برچسب روی هم می‌افتادند و ناخوانا می‌شدند.
        val usedLabels = ArrayList<RectF>()
        fun line(price: Double, color: Int, label: String, fromIdx: Float) {
            if (price.isNaN()) return
            val y = yOf(price)
            if (y < 0 || y > priceH) return
            pDashed.color = color
            val x0 = max(0f, xOf(fromIdx))
            canvas.drawLine(x0, y, plotW, y, pDashed)
            if (label.isNotEmpty()) {
                pLabelBg.color = (color and 0x00FFFFFF) or 0xCC000000.toInt()
                val padX = Ui.dp(context, 4f)
                val w = textWidth(label, pLabelTxt) + padX * 2
                var left = x0.coerceIn(2f, (plotW - w - 2f).coerceAtLeast(2f))
                var top = y - Ui.dp(context, 7.5f)
                // اگر روی برچسب قبلی افتاد، به راست و بعد به پایین جابه‌جا شود
                var tries = 0
                while (tries < 6 && usedLabels.any { RectF.intersects(it, RectF(left, top, left + w, top + Ui.dp(context, 15f))) }) {
                    if (left + w + Ui.dp(context, 42f) < plotW) left += Ui.dp(context, 42f)
                    else { top += Ui.dp(context, 16f); left = x0.coerceIn(2f, (plotW - w - 2f).coerceAtLeast(2f)) }
                    tries++
                }
                top = top.coerceIn(2f, (priceH - Ui.dp(context, 15f) - 2f).coerceAtLeast(2f))
                usedLabels.add(RectF(left, top, left + w, top + Ui.dp(context, 15f)))
                canvas.drawRoundRect(RectF(left, top, left + w, top + Ui.dp(context, 15f)),
                    Ui.dp(context, 3f).toFloat(), Ui.dp(context, 3f).toFloat(), pLabelBg)
                drawTextAuto(canvas, label, left + padX, top + Ui.dp(context, 11f), pLabelTxt)
            }
        }
        for (o in overlays) {
            if (o.stage == 8) continue
            val zIdx = idxOfTime(o.zT, o.zBi).toFloat()
            val midIdx = idxOfTime(if (o.midT > 0L) o.midT else o.zT, o.zBi).toFloat()
            val lvIdx = idxOfTime(if (o.lvT > 0L) o.lvT else o.zT, o.zBi).toFloat()
            val hvIdx = idxOfTime(if (o.hvT > 0L) o.hvT else o.zT, o.zBi).toFloat()
            val armIdx = idxOfTime(if (o.armT > 0L) o.armT else if (o.hvT > 0L) o.hvT else o.zT, o.zBi).toFloat()
            if (showSetupZoneLines) {
                line(o.zTop, Color.parseColor("#39B27C"), "ناحیه", zIdx)
                line(o.zBot, Color.parseColor("#39B27C"), "", zIdx)
            }
            if (showMidLine && !o.midRef.isNaN()) {
                line(o.midRef, Color.parseColor("#BA68C8"), "تایید میانی", midIdx)
            }
            if (o.fibValid && o.stage in 2..7) {
                if (showMidLine && !o.fib0.isNaN()) {
                    line(o.fib0, Color.parseColor("#9575CD"), if (o.dir == 1) "فیبو 0% (HH)" else "فیبو 0% (LL)", midIdx)
                }
                if (o.stage < 6) {
                    if (showTpStepsLines) {
                        line(o.fib382, Color.parseColor("#FFB74D"), "فیبو 38.2%", midIdx)
                        line(o.fib500, Color.parseColor("#FFB74D"), "فیبو 50%", midIdx)
                    }
                    if (showTpFinalLine) {
                        line(o.fib1272, Palette.violet, "فیبو 127.2%", midIdx)
                    }
                }
            }
            if (showTrigger1Lines && o.stage >= 4 && !o.lvUsed && o.lvConfBi >= 0) {
                line(o.lvH, Palette.gold, "ولوم کم", lvIdx)
                line(o.lvL, Palette.gold, "", lvIdx)
            }
            if (showTrigger2Lines && o.stage >= 5 && !o.hvH.isNaN()) {
                line(o.hvH, Palette.accent, "ولوم زیاد", hvIdx)
                line(o.hvL, Palette.accent, "", hvIdx)
            }
            if (o.stage >= 6) {
                if (showEntryLine) line(o.entry, upCol, "ورود", armIdx)
                if (showSlLine) line(o.sl, dnCol, "حدضرر", armIdx)
                if (showTpStepsLines) {
                    line(o.tp1, Color.parseColor("#FFB74D"), "TP1", armIdx)
                    line(o.tp2, Color.parseColor("#FFB74D"), "TP2", armIdx)
                }
                if (showTpFinalLine) line(o.tpx, Palette.violet, "TP نهایی", armIdx)
            }
        }
        val t = trades.lastOrNull { it.open } ?: trades.lastOrNull()
        if (t != null) {
            val tIdx = idxOfTime(t.entryT, t.entryBi).toFloat()
            if (showEntryLine) line(t.entry, upCol, if (t.open) "ورود باز" else "ورود", tIdx)
            if (showSlLine) line(if (t.be) t.entry else if (!t.slAdj.isNaN()) t.slAdj else t.sl0, dnCol, if (t.be) "سربه‌سر" else "حدضرر", tIdx)
            if (showTpFinalLine) line(t.tpX, Palette.violet, "TP", tIdx)
        }
    }
}
