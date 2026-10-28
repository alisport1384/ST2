package com.alisport.goldpin.core

// ═══════════════════════════════════════════════════════════════════════════════
//  میز جفت‌ارز (FX Desk) — نسخهٔ ۱٫۵٫۰۴
//
//  چرا یک ماژول جدا و نه دست‌کاری موتور طلا/نفت؟
//   • خواستهٔ صریح مالک: رفتار طلا و نفت نباید عوض شود. این ماژول هیچ خطی از
//     `Engine`/`PaperBroker` طلا را تغییر نمی‌دهد و مستقل اجرا می‌شود.
//   • موتور اصلی روی «جعبه‌های حجمی برآمده از M1 طلا» بنا شده و روی شبکهٔ اعشاری
//     FX هیچ معامله‌ای تولید نمی‌کند (اندازه‌گیری‌شده: tf=2 ⇒ entry=0، tf≥3 ⇒ lv=0).
//   • قاعدهٔ جفت‌ارز در آزمایشگاه (`research/fx_lab.py`) آزموده و پارامترهایش
//     با آزمون بیرون‌نمونه (walk-forward) داوری شده است؛ این فایل همان قاعده را
//     با همان واحدها پیاده می‌کند: R، هزینهٔ آلپاری، سواپ ۲۱:۰۰ UTC.
//
//  قاعده (هر جفت مستقل):
//    ورود: بسته‌شدن قیمت بیرون از سقف/کفِ N کندلِ بستهٔ تایم‌فریم خودش.
//    جهت: `fade` = پادِ شکست، `trend` = هم‌جهت شکست.
//    حد ضرر: atrMult × ATR(14). هدف: targetR × R.
//    پله: اگر partial روشن باشد، نصف حجم در ۱R بسته و حد ضرر به سر‌به‌سر می‌رود.
//    یک پوزیشن در هر جفت؛ بدون هرم‌سازی.
// ═══════════════════════════════════════════════════════════════════════════════

/** یک جفت‌ارز با پارامترهای آزموده‌شده. `oosAvgR` نتیجهٔ بیرون‌نمونه است، نه آرزو. */
data class FxProfile(
    val id: String,
    val label: String,
    val digits: Int,
    val tfSec: Long,
    val dirMode: Int,        // -1 = fade (پاد شکست) · +1 = trend (هم‌جهت شکست)
    val lookback: Int,
    val atrMult: Double,
    val targetR: Double,
    val partial: Boolean,
    val onByDefault: Boolean,
    val oosAvgR: Double,     // میانگین R بیرون‌نمونه (۶۰٪ انتخاب / ۴۰٪ داوری)
    val oosTrades: Int,
    val note: String
) {
    val point: Double get() = Math.pow(10.0, -digits.toDouble())
    val modeFa: String get() = if (dirMode > 0) "هم‌جهت شکست" else "پاد شکست"
}

object FxPairs {

    /**
     * جدول نهایی. منبع هر عدد: `research/fx_walkforward.csv` (داوری ۴۰٪ آخر داده).
     * ⚠ فقط سه جفت بیرون‌نمونه مثبت داشتند؛ بقیه با `onByDefault=false` می‌آیند تا
     * کاربر بداند دارد چه چیزی را روشن می‌کند. هیچ عددی پنهان نشده.
     */
    val ALL: List<FxProfile> = listOf(
        FxProfile("EURUSD", "یورو/دلار — EURUSD", 5, 3_600, -1, 12, 1.0, 7.0, false, true,
            +0.245, 291, "پایدارترین: درون‌نمونه +۰٫۲۵۰ / بیرون‌نمونه +۰٫۲۴۵ با ۲۹۱ معاملهٔ داوری"),
        FxProfile("GBPUSD", "پوند/دلار — GBPUSD", 5, 14_400, -1, 24, 1.0, 7.0, false, true,
            +0.343, 64, "درون‌نمونه +۰٫۴۱۰ / بیرون‌نمونه +۰٫۳۴۳ · نمونهٔ داوری کم است"),
        FxProfile("AUDUSD", "دلار استرالیا/دلار — AUDUSD", 5, 14_400, -1, 48, 2.0, 3.0, false, true,
            +0.078, 47, "درون‌نمونه +۰٫۴۳۶ ولی بیرون‌نمونه فقط +۰٫۰۷۸ — افت شدید، با احتیاط"),
        FxProfile("USDJPY", "دلار/ین — USDJPY", 3, 3_600, +1, 24, 1.5, 7.0, false, false,
            +0.081, 175, "بیرون‌نمونه مثبت شد ولی درون‌نمونه منفی بود ⇒ پارامتر تأییدشده نیست"),
        FxProfile("GBPJPY", "پوند/ین — GBPJPY", 3, 14_400, -1, 12, 1.5, 4.0, false, false,
            +0.043, 57, "بیرون‌نمونه تقریباً صفر · سواپ فروش سنگین"),
        FxProfile("USDCHF", "دلار/فرانک — USDCHF", 5, 14_400, -1, 96, 1.0, 4.0, false, false,
            -0.087, 37, "درون‌نمونه +۰٫۲۴۶ ⇒ بیرون‌نمونه −۰٫۰۸۷ (وارونگی کامل)"),
        FxProfile("USDCAD", "دلار/دلار کانادا — USDCAD", 5, 14_400, -1, 24, 2.0, 2.0, false, false,
            -0.173, 56, "وارونگی در داوری"),
        FxProfile("NZDUSD", "دلار نیوزیلند/دلار — NZDUSD", 5, 14_400, -1, 12, 2.0, 3.0, false, false,
            -0.364, 57, "بدترین وارونگی: درون‌نمونه +۰٫۵۴۹ ⇒ بیرون‌نمونه −۰٫۳۶۴"),
        FxProfile("EURJPY", "یورو/ین — EURJPY", 3, 14_400, -1, 12, 1.5, 7.0, false, false,
            -0.271, 58, "وارونگی در داوری"),
        FxProfile("AUDJPY", "دلار استرالیا/ین — AUDJPY", 3, 14_400, +1, 48, 1.0, 4.0, false, false,
            -0.224, 53, "وارونگی در داوری"),
        FxProfile("EURGBP", "یورو/پوند — EURGBP", 5, 3_600, -1, 96, 1.0, 4.0, false, false,
            -0.273, 109, "اسپرد نسبت به ATR بزرگ است"),
        FxProfile("EURCHF", "یورو/فرانک — EURCHF", 5, 14_400, -1, 24, 1.0, 7.0, false, false,
            -0.616, 51, "بدترین جفت؛ روی این جفت قاعده کار نمی‌کند")
    )

    fun byId(id: String): FxProfile =
        ALL.firstOrNull { it.id.equals(id, ignoreCase = true) }
            ?: throw IllegalArgumentException("جفت‌ارز ناشناخته: $id")

    val ENABLED_BY_DEFAULT: List<FxProfile> get() = ALL.filter { it.onByDefault }
}

/** هزینهٔ واقعی بروکر برای یک جفت‌ارز (آلپاری Pro ECN). */
data class FxCost(
    val spreadPoints: Double,
    val swapLongPerLot: Double,    // دلار بر لات در هر شب
    val swapShortPerLot: Double,
    val usdPerQuote: Double        // دلار به‌ازای یک واحد ارز مظنه
) {
    companion object {
        /**
         * نرخ تبدیل برای جملهٔ «ارز مظنه → دلار». اپ این را از قیمت زندهٔ
         * USDJPY/USDCAD/USDCHF/GBPUSD پر می‌کند؛ پیش‌فرض فقط برای بک‌تست است.
         */
        @Volatile var usdPerQuote: Map<String, Double> = mapOf(
            "USD" to 1.0, "JPY" to 0.0066, "CAD" to 0.72, "CHF" to 1.13, "GBP" to 1.27
        )
    }
}

/** یک معاملهٔ باز/بسته در میز جفت‌ارز. */
class FxTrade(
    val id: Long,
    val pair: String,
    val dir: Int,               // +1 خرید، -1 فروش
    val entryT: Long,
    val entryPx: Double,
    var qtyLots: Double,
    val sl0: Double,
    val riskUsd: Double,
    val targetR: Double
) {
    var sl: Double = sl0
    var tp: Double = entryPx + dir * targetR * riskPx()
    var partialTaken = false
    var exitT: Long = 0
    var exitPx: Double = Double.NaN
    var open = true
    var swapUsd = 0.0
    var swapNights = 0
    var lastRollDay = Long.MIN_VALUE
    var grossUsd = 0.0
    var barsOpen = 0
    var commissionUsd = 0.0
    var spreadCostUsd = 0.0

    /** فاصلهٔ حد ضرر اولیه بر حسب قیمت — مبنای همهٔ محاسبات R. */
    fun riskPx(): Double = Math.abs(entryPx - sl0)

    /** سود/زیان خالص دلاری (شامل سواپ و هزینه‌ها). */
    fun netUsd(): Double = if (open) floatingUsd() - commissionUsd - spreadCostUsd + swapUsd
                           else grossUsd - commissionUsd - spreadCostUsd + swapUsd

    fun floatingUsd(): Double =
        grossUsd // پوزیشن باز: grossUsd فقط بخش بسته‌شده را دارد؛ شناور در onBar اضافه می‌شود

    fun realizedR(): Double = if (riskUsd <= 0) 0.0 else netUsd() / riskUsd
}

/**
 * میز جفت‌ارز — تغذیه با کندل‌های بستهٔ تجمیع‌شدهٔ هر جفت، یک پوزیشن باز در هر جفت.
 *
 * @param capital سرمایهٔ اسمی میز (برای محاسبهٔ ریسک دلاری هر معامله).
 * @param riskPct ریسک هر معامله بر حسب درصد سرمایه.
 */
class FxDesk(
    var capital: Double = 100_000.0,
    var riskPct: Double = 0.5,
    var commissionPerLotRoundTurn: Double = 5.0,  // ۲٫۵ دلار هر طرف
    /** سقف نگه‌داری بر حسب کندل — همان ۶۰ کندلی که در آزمایشگاه آزموده شد. */
    var maxHoldBars: Int = 60
) {

    val positions = LinkedHashMap<String, FxTrade>()
    val closed = ArrayList<FxTrade>()
    var nextId = 1L

    /** آمار زندهٔ هر جفت (برای نمایش در رابط). */
    val trades = LinkedHashMap<String, Int>()
    val wins = LinkedHashMap<String, Int>()
    val sumR = LinkedHashMap<String, Double>()

    var feed: (String) -> FxCost? = { null }     // تزریق از لایهٔ اپ

    fun reset() {
        positions.clear(); closed.clear(); trades.clear(); wins.clear(); sumR.clear()
        nextId = 1L
    }

    fun netUsd(): Double = closed.sumOf { it.netUsd() } + positions.values.sumOf { it.netUsd() }

    fun balance(): Double = capital + closed.sumOf { it.netUsd() }

    /**
     * یک کندل بسته در تایم‌فریم جفت. کندل باید همان تایم‌فریم [FxProfile.tfSec] باشد.
     *
     * @param history کندل‌های قبلی همان جفت (به‌ترتیب زمان، شامل [bar]).
     */
    fun onBar(p: FxProfile, history: List<Candle>, bar: Candle) {
        val cost = feed(p.id) ?: return

        // ۱) اول پوزیشن باز: سواپ، حد ضرر، هدف، پله
        positions[p.id]?.let { t -> manage(p, t, bar, cost); if (!t.open) return }

        // ۲) سیگنال روی کندل بستهٔ فعلی
        val n = history.size
        if (n < Math.max(p.lookback, 15) + 1) return
        var hi = Double.NEGATIVE_INFINITY
        var lo = Double.POSITIVE_INFINITY
        for (i in n - 1 - p.lookback until n - 1) {
            hi = Math.max(hi, history[i].h)
            lo = Math.min(lo, history[i].l)
        }
        val atr = FxMath.atr(history, 14, n - 1)
        if (atr.isNaN() || atr <= 0.0) return

        val sig = when {
            bar.c > hi -> if (p.dirMode < 0) -1 else 1
            bar.c < lo -> if (p.dirMode < 0) 1 else -1
            else -> 0
        }
        if (sig == 0) return
        if (positions.containsKey(p.id)) return

        val riskPx = p.atrMult * atr
        if (riskPx <= 0.0) return
        val spreadPx = cost.spreadPoints * p.point
        val costPx = spreadPx + commissionPerLotRoundTurn / 100_000.0 / cost.usdPerQuote
        if (costPx / riskPx > 0.20) return          // همان گیت هزینهٔ آزمایشگاه

        val riskUsd = Math.max(balance() * riskPct / 100.0, 1.0)
        val usdPerPxPerUnit = cost.usdPerQuote      // دلار به‌ازای یک واحد قیمت، برای یک واحد پایه
        val units = riskUsd / (riskPx * usdPerPxPerUnit)
        val lots = units / 100_000.0
        if (lots <= 0.0) return

        val t = FxTrade(
            id = nextId++, pair = p.id, dir = sig, entryT = bar.t, entryPx = bar.c,
            qtyLots = lots, sl0 = bar.c - sig * riskPx, riskUsd = riskUsd,
            targetR = p.targetR
        )
        t.tp = bar.c + sig * p.targetR * riskPx
        // کارمزد هر طرف جدا حساب می‌شود: نصفِ رفت‌وبرگشت در ورود، نصفِ دیگر در خروج
        t.commissionUsd = commissionPerLotRoundTurn / 2.0 * lots
        t.spreadCostUsd = spreadPx * units * usdPerPxPerUnit
        t.lastRollDay = FxMath.rollDay(bar.t, ROLL_HOUR_UTC) - 1
        positions[p.id] = t
    }

    private fun manage(p: FxProfile, t: FxTrade, bar: Candle, cost: FxCost) {
        t.barsOpen++
        // سواپ هر رول‌اور ۲۱:۰۰ UTC، چهارشنبه سه‌برابر، شنبه/یکشنبه صفر
        val cur = FxMath.rollDay(bar.t, ROLL_HOUR_UTC)
        if (cur > t.lastRollDay) {
            var d = t.lastRollDay + 1
            var nights = 0
            while (d <= cur) {
                if (!FxMath.isWeekendRoll(d)) nights += if (FxMath.isTripleRoll(d, 3)) 3 else 1
                d++
            }
            if (nights > 0) {
                val rate = if (t.dir > 0) cost.swapLongPerLot else cost.swapShortPerLot
                val amount = rate * t.qtyLots * nights
                t.swapUsd += amount
                t.swapNights += nights
            }
            t.lastRollDay = cur
        }

        val units = t.qtyLots * 100_000.0
        val usdPerUnit = cost.usdPerQuote
        val hitSl = if (t.dir > 0) bar.l <= t.sl else bar.h >= t.sl
        val hitTp = if (t.dir > 0) bar.h >= t.tp else bar.l <= t.tp
        val hitP1 = if (!t.partialTaken && p.partial)
            (if (t.dir > 0) bar.h >= t.entryPx + t.riskPx() else bar.l <= t.entryPx - t.riskPx())
        else false

        // بدترین حالت در کندل مشترک: حد ضرر اول
        if (hitSl) { closeAt(t, t.sl, bar.t, units, usdPerUnit); return }
        if (hitP1) {
            val half = t.qtyLots / 2.0
            t.grossUsd += (t.entryPx + t.dir * t.riskPx() - t.entryPx) * t.dir * (half * 100_000.0) * usdPerUnit
            t.qtyLots -= half
            t.commissionUsd += commissionPerLotRoundTurn / 2.0 * half
            t.partialTaken = true
            t.sl = t.entryPx
        }
        if (hitTp) { closeAt(t, t.tp, bar.t, units, usdPerUnit); return }
        // سقف نگه‌داری: بستن با قیمت بستهٔ همان کندل (همان قاعدهٔ آزمایشگاه)
        if (t.barsOpen >= maxHoldBars) closeAt(t, bar.c, bar.t, t.qtyLots * 100_000.0, usdPerUnit)
    }

    private fun closeAt(t: FxTrade, px: Double, time: Long, units: Double, usdPerUnit: Double) {
        t.grossUsd += (px - t.entryPx) * t.dir * units * usdPerUnit
        t.commissionUsd += commissionPerLotRoundTurn / 2.0 * (units / 100_000.0)
        t.exitPx = px
        t.exitT = time
        t.open = false
        positions.remove(t.pair)
        closed.add(t)
        trades[t.pair] = (trades[t.pair] ?: 0) + 1
        val r = t.realizedR()
        sumR[t.pair] = (sumR[t.pair] ?: 0.0) + r
        if (r > 0) wins[t.pair] = (wins[t.pair] ?: 0) + 1
    }

    /** بستن اجباری همهٔ پوزیشن‌ها با آخرین قیمت (پایان بک‌تست / توقف لایو). */
    fun closeAll(priceOf: (String) -> Double, timeMs: Long) {
        for (t in positions.values.toList()) {
            val px = priceOf(t.pair)
            if (px.isNaN() || px <= 0.0) continue
            val cost = feed(t.pair) ?: continue
            closeAt(t, px, timeMs, t.qtyLots * 100_000.0, cost.usdPerQuote)
        }
    }

    companion object { const val ROLL_HOUR_UTC = 21 }
}

/** کمکی‌های عددی میز جفت‌ارز (بدون وابستگی به اندروید). */
object FxMath {

    fun atr(bars: List<Candle>, period: Int, lastIdx: Int): Double {
        if (lastIdx < period) return Double.NaN
        var sum = 0.0
        for (i in lastIdx - period + 1..lastIdx) {
            val pc = bars[i - 1].c
            val tr = Math.max(bars[i].h - bars[i].l,
                Math.max(Math.abs(bars[i].h - pc), Math.abs(bars[i].l - pc)))
            sum += tr
        }
        return sum / period
    }

    /** شمارهٔ روزِ رول (روزی که ۲۱:۰۰ UTC آن شروع می‌شود). */
    fun rollDay(tMs: Long, rollHourUtc: Int): Long =
        Math.floorDiv(tMs - rollHourUtc * 3_600_000L, 86_400_000L)

    /** شنبه(5) و یکشنبه(6) — بازار بسته، سواپ نداریم. */
    fun isWeekendRoll(day: Long): Boolean = (((day + 3) % 7 + 7) % 7) >= 5

    /** چهارشنبه (سه‌برابر). */
    fun isTripleRoll(day: Long, tripleDow: Int): Boolean = (((day + 3) % 7 + 7) % 7) == (tripleDow - 1).toLong()
}

// ═══════════════════════════════════════════════════════════════════════════════
//  جدول هزینهٔ آلپاری برای جفت‌ارزها (Pro ECN، دادهٔ رسمی ۲۰۲۶-۱۰-۱۴)
//
//  منبع: همان فایل رسمی که در ۱٫۵٫۰۲ برای طلا/نفت استفاده شد
//  (`https://cdn.builder.io/...instrument-specification-data`).
//  اسپرد در «پوینت»؛ سواپ به دلار بر لات در هر شب (خرید/فروش).
// ═══════════════════════════════════════════════════════════════════════════════
object FxCostTable {

    private data class Row(
        val spreadPoints: Double,
        val swapLong: Double,
        val swapShort: Double,
        val quote: String
    )

    private val ALPARI_PRO_ECN: Map<String, Row> = mapOf(
        "EURUSD" to Row(1.00, -6.82, 1.36, "USD"),
        "GBPUSD" to Row(2.00, -3.29, -1.44, "USD"),
        "USDJPY" to Row(2.04, -0.14, -23.84, "JPY"),
        "AUDUSD" to Row(4.00, -0.16, -2.56, "USD"),
        "USDCAD" to Row(6.00, 4.51, -10.31, "CAD"),
        "USDCHF" to Row(6.00, 5.40, -13.80, "CHF"),
        "NZDUSD" to Row(6.00, -3.33, 0.39, "USD"),
        "EURJPY" to Row(7.00, -7.84, -21.64, "JPY"),
        "GBPJPY" to Row(8.00, -1.30, -32.70, "JPY"),
        "EURGBP" to Row(6.00, -11.48, -3.18, "GBP"),
        "AUDJPY" to Row(1.00, 5.39, -14.68, "JPY"),
        "EURCHF" to Row(9.00, 3.32, -9.87, "CHF")
    )

    fun costFor(pairId: String, usdPerQuote: Map<String, Double> = FxCost.usdPerQuote): FxCost? {
        val r = ALPARI_PRO_ECN[pairId.uppercase()] ?: return null
        val conv = usdPerQuote[r.quote] ?: return null
        return FxCost(r.spreadPoints, r.swapLong, r.swapShort, conv)
    }

    val pairs: Set<String> get() = ALPARI_PRO_ECN.keys
}
