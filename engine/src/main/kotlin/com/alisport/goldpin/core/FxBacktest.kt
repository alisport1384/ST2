package com.alisport.goldpin.core

// ═══════════════════════════════════════════════════════════════════════════════
//  بک‌تست مستقل هر جفت‌ارز — ۱٫۵٫۰۶
//
//  خواستهٔ مالک: «برای هر جفت‌ارز یک میز مثل طلا و نفت؛ پارامتر خودش، آزمون ۲۰ سالهٔ
//  خودش، و محاسبهٔ خودش در تب مدیریت». این فایل موتور آزمون هر جفت است:
//
//   • همان `FxDesk` که اجرای زندهٔ همان جفت را انجام می‌دهد ⇒ آزمون و زنده یکی‌اند.
//   • خروجی کامل و بدون سانسور: تعداد معامله، نرخ برد، میانگین R، PF، سود خالص،
//     بیشترین افت (دلار و R)، منحنی سرمایه، و سود/زیان سال‌به‌سال.
//   • سال‌های منفی هم برگردانده می‌شوند (`years`) تا هیچ‌چیز پنهان نماند.
//   • پارسر CSV آمادهٔ همان بسته‌های ریلیز (`t,o,h,l,c,v`) و بیرون‌کشیدن یک جفت از ZIP.
// ═══════════════════════════════════════════════════════════════════════════════

/** سود/زیان یک سال شمسیِ داده (سال میلادی) برای یک جفت. */
data class FxYearStat(val year: Int, val netUsd: Double, val trades: Int, val sumR: Double)

/**
 * نتیجهٔ بک‌تست یک جفت. همهٔ عددها از اجرای واقعی موتور می‌آیند، نه تخمین.
 */
data class FxBacktestResult(
    val pair: String,
    val label: String,
    val tfFa: String,
    val modeFa: String,
    val paramsFa: String,
    val bars: Int,
    val fromT: Long,
    val toT: Long,
    val trades: Int,
    val wins: Int,
    val sumR: Double,
    val avgR: Double,
    val pf: Double,
    val netUsd: Double,
    val maxDdUsd: Double,
    val maxDdR: Double,
    val startCapital: Double,
    val riskPct: Double,
    val commissionPerLot: Double,
    val curve: List<Pair<Long, Double>>,
    val years: List<FxYearStat>,
    val runAtMs: Long,
    val source: String
) {
    val winRate: Double get() = if (trades <= 0) 0.0 else 100.0 * wins / trades
    val positiveYears: Int get() = years.count { it.netUsd > 0 }
    val yearCount: Int get() = years.size
    val yearsSpan: Double get() = if (bars <= 0) 0.0 else (toT - fromT) / (365.25 * 86_400_000.0)
    val avgRPerYear: Double get() = if (yearsSpan <= 0) 0.0 else sumR / yearsSpan
}

object FxBacktest {

    /**
     * اجرای کامل یک جفت روی کندل‌های تایم‌فریم خودش.
     *
     * @param bars کندل‌های **بستهٔ** همان تایم‌فریم (`FxProfile.tfSec`)، مرتب بر اساس زمان.
     * @param cost هزینهٔ بروکر همان جفت (اسپرد + سواپ + نرخ تبدیل).
     */
    fun run(
        p: FxProfile,
        bars: List<Candle>,
        cost: FxCost,
        capital: Double = 10_000.0,
        riskPct: Double = 0.25,
        commissionPerLot: Double = 5.0,
        source: String = ""
    ): FxBacktestResult {
        val desk = FxDesk(capital = capital, riskPct = riskPct, commissionPerLotRoundTurn = commissionPerLot)
        desk.feed = { cost }
        val hist = ArrayList<Candle>(bars.size)
        for (b in bars) {
            hist.add(b)
            desk.onBar(p, hist, b)
        }
        // پوزیشن باز آخر را با آخرین قیمت می‌بندیم تا نتیجه قابل مقایسه باشد.
        val last = bars.lastOrNull()
        if (last != null) desk.closeAll({ last.c }, last.t)

        val closed = desk.closed.sortedBy { it.exitT }
        val sumR = closed.sumOf { it.realizedR() }
        val wins = closed.count { it.netUsd() > 0 }
        val gains = closed.filter { it.netUsd() > 0 }.sumOf { it.netUsd() }
        val losses = -closed.filter { it.netUsd() <= 0 }.sumOf { it.netUsd() }

        val curve = ArrayList<Pair<Long, Double>>(closed.size + 1)
        curve.add((bars.firstOrNull()?.t ?: 0L) to capital)
        var run = capital
        var peak = capital
        var ddUsd = 0.0
        val riskUsdAvg = capital * riskPct / 100.0
        for (t in closed) {
            run += t.netUsd()
            curve.add(t.exitT to run)
            peak = Math.max(peak, run)
            ddUsd = Math.max(ddUsd, peak - run)
        }

        val years = LinkedHashMap<Int, MutableList<FxTrade>>()
        for (t in closed) {
            val y = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = t.exitT
            }.get(java.util.Calendar.YEAR)
            years.getOrPut(y) { ArrayList() }.add(t)
        }
        val yearStats = years.entries.sortedBy { it.key }.map { (y, list) ->
            FxYearStat(y, list.sumOf { it.netUsd() }, list.size, list.sumOf { it.realizedR() })
        }

        return FxBacktestResult(
            pair = p.id, label = p.label, tfFa = p.tfFa, modeFa = p.modeFa,
            paramsFa = "N=${p.lookback} · atr=${p.atrMult} · هدف ${p.targetR}R" + (if (p.partial) " · پله" else ""),
            bars = bars.size,
            fromT = bars.firstOrNull()?.t ?: 0L,
            toT = bars.lastOrNull()?.t ?: 0L,
            trades = closed.size, wins = wins, sumR = sumR,
            avgR = if (closed.isEmpty()) 0.0 else sumR / closed.size,
            pf = if (losses <= 1e-9) (if (gains > 0) 99.0 else 0.0) else gains / losses,
            netUsd = closed.sumOf { it.netUsd() },
            maxDdUsd = ddUsd,
            maxDdR = if (riskUsdAvg <= 0) 0.0 else ddUsd / riskUsdAvg,
            startCapital = capital, riskPct = riskPct, commissionPerLot = commissionPerLot,
            curve = curve, years = yearStats,
            runAtMs = System.currentTimeMillis(), source = source
        )
    }

    /** پارس فایل CSV بسته‌ها: `t,o,h,l,c,v` (t = ثانیهٔ UTC). سرستون اختیاری است. */
    fun parseCsv(text: String): List<Candle> {
        val out = ArrayList<Candle>(8192)
        var i = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line[0].isLetter()) continue                    // سرستون
            val c = line.split(',')
            if (c.size < 5) continue
            val t = c[0].trim().toLongOrNull() ?: continue
            val o = c[1].trim().toDoubleOrNull() ?: continue
            val h = c[2].trim().toDoubleOrNull() ?: continue
            val l = c[3].trim().toDoubleOrNull() ?: continue
            val cl = c[4].trim().toDoubleOrNull() ?: continue
            val v = if (c.size > 5) (c[5].trim().toDoubleOrNull() ?: 0.0) else 0.0
            out.add(Candle(i++, t * 1000L, o, h, l, cl, v))
        }
        return out
    }

    /**
     * بیرون‌کشیدن فایل `<PAIR>.csv` از بایت‌های ZIP بدون وابستگی به اندروید.
     * @return متن CSV یا null اگر آن جفت در بسته نبود.
     */
    fun pairCsvFromZip(zip: ByteArray, pair: String): String? {
        val zin = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zip))
        try {
            var found: String? = null
            while (found == null) {
                val e = zin.nextEntry ?: break
                val name = e.name.substringAfterLast('/')
                if (name.equals("$pair.csv", ignoreCase = true)) {
                    found = zin.readBytes().toString(Charsets.UTF_8)
                }
            }
            return found
        } catch (e: Exception) {
            return null
        } finally {
            try { zin.close() } catch (_: Exception) { }
        }
    }

    /** تجمیع کندل‌های ساعتی به تایم‌فریم جفت (فقط باکت‌های کامل). */
    fun aggregate(bars: List<Candle>, tfSec: Long): List<Candle> {
        val tfMs = tfSec * 1000L
        val map = LinkedHashMap<Long, Candle>()
        for (c in bars) {
            val b = Math.floorDiv(c.t, tfMs) * tfMs
            val prev = map[b]
            map[b] = if (prev == null) Candle(c.bi, b, c.o, c.h, c.l, c.c, c.v)
            else Candle(prev.bi, b, prev.o, Math.max(prev.h, c.h), Math.min(prev.l, c.l), c.c, prev.v + c.v)
        }
        val lastRaw = bars.lastOrNull()?.t ?: return emptyList()
        // فقط باکت‌هایی که کامل بسته شده‌اند (کندل نیمه‌کاره وارد آزمون نمی‌شود)
        return map.entries.filter { it.key + tfMs <= lastRaw }
            .sortedBy { it.key }.map { it.value }
    }
}
