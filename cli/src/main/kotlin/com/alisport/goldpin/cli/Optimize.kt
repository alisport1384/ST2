package com.alisport.goldpin.cli

import com.alisport.goldpin.core.*
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.max

// ═══════════════════════════════════════════════════════════════════════════════
//  هارنس بهینه‌سازی/پژوهش خط فرمان
//
//  هدف: اجرای صدها ترکیب پارامتر روی دادهٔ واقعی طلا بدون Gradle/تست، با خروجی
//  ماشین‌خوان (CSV) و ریزش معامله‌به‌معامله (برای پژوهش فیلترها در پایتون).
//
//  نمونه:
//    ./cli --data datasets/goldpin_xauusd_m1_3y.csv.gz --spec "s=1,mm=2,k=7,N=80,b=1800,eb=-2" \
//          --trades /tmp/trades.csv --out /tmp/one.csv
//
//  spec کلیدها (همه اختیاری، پیش‌فرض = همان Tuned):
//    s=1|2            استراتژی (۱ سند · ۲ بدون روند)
//    mm=0|1|2         مود مالی (۰ خاموش · ۱ ManageAllLost · ۲ Mixed)
//    fade=0|1         تریگر ۲ معکوس
//    k=7.0            هدف بر حسب R (۰ = پله‌های فیبو / در استراتژی ۲ = حد سود واحدی)
//    tp=10            tpUnits (استراتژی ۲)
//    N=80             سقف فاصلهٔ حد ضرر (واحد قیمت) — در مود ATR: ضریب ATR
//    minSl=0          حداقل فاصلهٔ حد ضرر
//    cost=0           فیلتر کارمزد (§۵-۳) — ضریب × هزینهٔ هر واحد
//    b=1800           بافر حد ضرر (تیک) — فقط در مود ثابت
//    atrMult=4.0      ضریب ATR برای بافر (فقط وقتی bufMode=1)
//    bufMode=0|1      ۰ = بافر ثابت تیک · ۱ = بافر نسبی به ATR کندل‌های ساختار
//    eb=-2            بافر سطح سفارش (تیک)
//    cmsn=0.7         کارمزد (واحد قیمت) — روی هر معامله کسر می‌شود
//    risk=1.0         درصد ریسک هر معامله
//    tf=1..5          مود تایم‌فریم
//    setups=1         سقف ستاپ هم‌زمان
//    guard=0/1        نگهبان منحنی سرمایه (توقف/کاهش ریسک در افت)
//    guardDD=15       آستانهٔ افت (درصد) برای توقف کامل ورودهای تازه
//    guardScale=0.5   ضریب ریسک در حالت افت
//  پنجرهٔ زمانی: --from-year 2006 --to-year 2017 (UTC، to غیرشامل)
// ═══════════════════════════════════════════════════════════════════════════════

private fun readCandles(path: String, fromYear: Int, toYear: Int): List<Candle> {
    val f = File(path)
    if (!f.exists()) error("فایل داده پیدا نشد: $path")
    val stream = f.inputStream().buffered(1 shl 16).let {
        if (path.endsWith(".gz")) GZIPInputStream(it) else it
    }
    val out = ArrayList<Candle>(1 shl 21)
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    val slice = fromYear != Int.MIN_VALUE || toYear != Int.MAX_VALUE
    var bi = 0
    // تجزیهٔ دستی (بدون split/regex) — روی ۶٫۵ میلیون ردیف، تفاوتش چند صد مگابایت
    // زبالهٔ GC و چند ده ثانیه زمان است.
    val br = java.io.BufferedReader(java.io.InputStreamReader(stream, Charsets.UTF_8), 1 shl 16)
    while (true) {
        val ln = br.readLine() ?: break
        if (ln.isEmpty()) continue
        val c0 = ln[0]
        if (c0 == 't' || c0 == 'T' || c0 == '#' || c0 == ';') continue
        // timestamp,open,high,low,close[,volume]
        var p = 0
        val n = ln.length
        fun next(): Double {
            while (p < n && (ln[p] == ' ' || ln[p] == '"')) p++
            val st = p
            while (p < n && ln[p] != ',' && ln[p] != '"') p++
            val v = ln.substring(st, p).trim().toDoubleOrNull() ?: Double.NaN
            p++ // از کاما رد شو
            return v
        }
        val tD = next()
        if (tD.isNaN()) continue
        val t = tD.toLong()
        val o = next(); val h = next(); val l = next(); val c = next()
        if (o.isNaN() || h.isNaN() || l.isNaN() || c.isNaN()) continue
        val v = if (p < n) next().let { if (it.isNaN()) 0.0 else it } else 0.0
        if (slice) {
            cal.timeInMillis = t
            val y = cal.get(java.util.Calendar.YEAR)
            if (y < fromYear || y >= toYear) continue
        }
        out.add(Candle(bi++, t, o, h, l, c, v))
    }
    br.close()
    return out
}

private class Conf {
    var tag = "cfg"
    var strategy = Strategy.DOC
    var mm = MoneyMode.MIXED
    var fade = false
    var k = Tuned.TARGET_R
    var tp = 10.0
    var nMax = Tuned.MAX_SL_UNITS
    var minSl = 0.0
    var cost = Tuned.COST_MULT
    var buf = Tuned.SL_BUF_TICKS
    var bufMode = 0
    var atrMult = 1.5
    var atrPeriod = 14
    var minBufUnits = 0.0
    var maxBufUnits = 0.0
    var eb = Tuned.ENTRY_BUF_TICKS
    var cmsn = Tuned.CMSN
    var tick = 0.0            // ۰ = پیش‌فرض (mintick از Tuned)
    var swapOn = true
    var swapL = 0.0
    var swapS = 0.0
    var risk = 1.0
    var tf = 1
    var setups = 1
    var hourFrom = 1
    var hourTo = 20
    var minAtrPct = 0.0
    var maxSlMode = 0
    var maxSlAtr = 4.0
    var guard = 0
    var guardDD = 15.0
    var guardHard = 25.0
    var guardScale = 0.5
    var guardReset = 0
    var mgmt = 0
    var holdR = Tuned.MGMT_HOLD_MIN_R
    var holdDays = Tuned.MGMT_HOLD_DAYS
    var trailStart = Tuned.MGMT_TRAIL_START_R
    var trailDist = Tuned.MGMT_TRAIL_DIST_R
    var mgmtGate = Tuned.MGMT_VOL_GATE_PCT
    // موتور ترکیبی ۱٫۴٫۵۲ (پیش‌فرض = همان پیش‌فرض جدید اپ)
    var cx = 3
    var cxGate = 0.33
    var cxGateCalm = 0.40
    var cxBox = 0.10
    var cxSize = 1
    var cxCap = 1.5
    var cxStress = 1.0
    var cxWiden = 1.15
    var cxTrail2 = 1.10

    fun parse(spec: String, tagOverride: String?) {
        for (kv in spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val idx = kv.indexOf('=')
            if (idx <= 0) continue
            val key = kv.substring(0, idx).trim()
            val v = kv.substring(idx + 1).trim()
            when (key) {
                "s" -> strategy = v.toInt()
                "mm" -> mm = v.toInt()
                "fade" -> fade = v == "1" || v.equals("true")
                "k" -> k = v.toDouble()
                "tp" -> tp = v.toDouble()
                "N" -> nMax = v.toDouble()
                "minSl" -> minSl = v.toDouble()
                "cost" -> cost = v.toDouble()
                "b" -> buf = v.toDouble()
                "bufMode" -> bufMode = v.toInt()
                "atrMult" -> atrMult = v.toDouble()
                "atrPeriod" -> atrPeriod = v.toInt()
                "minBuf" -> minBufUnits = v.toDouble()
                "maxBuf" -> maxBufUnits = v.toDouble()
                "eb" -> eb = v.toDouble()
                "cmsn" -> cmsn = v.toDouble()
                "tick" -> tick = v.toDouble()
                "swap" -> swapOn = v == "1" || v.equals("true")
                "swapL" -> swapL = v.toDouble()
                "swapS" -> swapS = v.toDouble()
                "risk" -> risk = v.toDouble()
                "tf" -> tf = v.toInt()
                "setups" -> setups = v.toInt()
                "hFrom" -> hourFrom = v.toInt()
                "hTo" -> hourTo = v.toInt()
                "atrPct" -> minAtrPct = v.toDouble()
                "maxSlMode" -> maxSlMode = v.toInt()
                "maxSlAtr" -> maxSlAtr = v.toDouble()
                "guard" -> guard = v.toInt()
                "guardDD" -> guardDD = v.toDouble()
                "guardScale" -> guardScale = v.toDouble()
                "guardHard" -> guardHard = v.toDouble()
                "guardReset" -> guardReset = v.toInt()
                "mgmt" -> mgmt = v.toInt()
                "holdR" -> holdR = v.toDouble()
                "holdDays" -> holdDays = v.toInt()
                "trailStart" -> trailStart = v.toDouble()
                "trailDist" -> trailDist = v.toDouble()
                "mgmtGate" -> mgmtGate = v.toDouble()
                "cx" -> cx = v.toInt()
                "cxGate" -> cxGate = v.toDouble()
                "cxGateCalm" -> cxGateCalm = v.toDouble()
                "cxBox" -> cxBox = v.toDouble()
                "cxSize" -> cxSize = v.toInt()
                "cxCap" -> cxCap = v.toDouble()
                "cxStress" -> cxStress = v.toDouble()
                "cxWiden" -> cxWiden = v.toDouble()
                "cxTrail2" -> cxTrail2 = v.toDouble()
                "tag" -> tag = v
                else -> {}
            }
        }
        if (tagOverride != null) tag = tagOverride
    }

    fun toSettings(): Settings {
        val mode = TF_MODES.firstOrNull { it.idx == tf } ?: TF_MODES[0]
        return Settings().apply {
            tfMode = mode.idx; tfS = mode.s; tfM = mode.m; tf1 = mode.t1; tf2 = mode.t2
            strategy = this@Conf.strategy
            mmMode = this@Conf.mm
            trigger2Fade = this@Conf.fade
            trigger2TargetR = this@Conf.k
            minSlCostMult = this@Conf.cost
            maxSlUnits = this@Conf.nMax
            minSlUnits = this@Conf.minSl
            tpUnits = this@Conf.tp
            slBufTicks = this@Conf.buf
            slBufMode = this@Conf.bufMode
            slAtrPeriod = this@Conf.atrPeriod
            slAtrMult = this@Conf.atrMult
            slAtrMinUnits = this@Conf.minBufUnits
            slAtrMaxUnits = this@Conf.maxBufUnits
            entryBufTicks = this@Conf.eb
            riskPct = this@Conf.risk
            maxSetups = this@Conf.setups
            entryHourFrom = this@Conf.hourFrom
            entryHourTo = this@Conf.hourTo
            minAtrPct = this@Conf.minAtrPct
            maxSlMode = this@Conf.maxSlMode
            maxSlAtrMult = this@Conf.maxSlAtr
            guardMode = this@Conf.guard
            guardDDPct = this@Conf.guardDD
            guardHardDDPct = this@Conf.guardHard
            guardRiskScale = this@Conf.guardScale
            guardResetAtHigh = this@Conf.guardReset == 1
            mgmtMode = this@Conf.mgmt
            mgmtHoldMinR = this@Conf.holdR
            mgmtHoldMaxDays = this@Conf.holdDays
            mgmtTrailStartR = this@Conf.trailStart
            mgmtTrailDistR = this@Conf.trailDist
            mgmtVolGatePct = this@Conf.mgmtGate
            cxMode = this@Conf.cx
            cxRegimeGate = this@Conf.cxGate
            cxRegimeGateCalm = this@Conf.cxGateCalm
            cxBoxMinUnits = this@Conf.cxBox
            cxSizeMode = this@Conf.cxSize
            cxCompoundCap = this@Conf.cxCap
            cxStressFloor = this@Conf.cxStress
            cxSlWidenMax = this@Conf.cxWiden
            cxTrailRegime2 = this@Conf.cxTrail2
            cmsnAuto = false
            cmsnManual = this@Conf.cmsn
            if (this@Conf.tick > 0.0) mintick = this@Conf.tick
            swapEnabled = swapOn
            swapLong = this@Conf.swapL
            swapShort = this@Conf.swapS
            minLotAuto = false
            minLotManual = 10.0
            initialEquity = 100_000.0
        }
    }
}

private class Stat(
    val cfg: Conf,
    val trades: Int,
    val wins: Int,
    val gross: Double,
    val comm: Double,
    val net: Double,
    val netCost: Double,
    val avgRCost: Double,
    val pf: Double,
    val maxDD: Double,
    val finalEquity: Double,
    val riskDollars: Double,
    val monthly: List<Pair<String, Double>>,
    val secs: Double,
    val swap: Double = 0.0,
    val swapNights: Int = 0
) {
    val winRate get() = if (trades > 0) wins * 100.0 / trades else 0.0
    val posMonths get() = monthly.count { it.second > 0 }
    /** بدترین بازده ۱۲ ماه متوالی (دلار) — سنجهٔ «صعودی بودن» منحنی. */
    val worst12: Double get() {
        if (monthly.size < 12) return monthly.sumOf { it.second }
        var worst = Double.MAX_VALUE
        for (i in 0..monthly.size - 12) {
            var s = 0.0
            for (j in i until i + 12) s += monthly[j].second
            if (s < worst) worst = s
        }
        return worst
    }
}

private fun runOne(c: Conf, candles: List<Candle>, diag: Boolean = false): Pair<Stat, List<Trade>> {
    val t0 = System.currentTimeMillis()
    val cfg = c.toSettings()
    val eng = Engine(cfg)
    val broker = PaperBroker(cfg)
    eng.broker = broker
    broker.engine = eng
    eng.barCommitHook = { cd -> broker.onBar(cd, live = false) }
    var upto = 0
    val step = 25_000
    while (upto < candles.size) {
        val next = minOf(upto + step, candles.size)
        eng.feed(candles.subList(0, next), lastIsClosed = true)
        upto = next
    }
    if (diag) {
        val atr = eng.structureAtr(cfg.slAtrPeriod)
        val lastPx = candles.last().c
        println(
            "DIAG structBars=${eng.structBars.size} ATR(${cfg.slAtrPeriod})=%.4f price=%.2f atrPct=%.4f%% ".format(
                atr, lastPx, atr / lastPx * 100.0
            ) + "hourBlocked=${eng.cnt.hourBlocked} atrBlocked=${eng.cnt.atrBlocked} " +
                "windowBlocked=${eng.cnt.windowBlocked} slN=${eng.cnt.slNBlocked} orders=${broker.orders.size} " +
                "setup=${eng.cnt.setup} touch=${eng.cnt.touch} mid=${eng.cnt.mid} lv=${eng.cnt.lv} entry=${eng.cnt.entry} zone=${eng.cnt.zoneCount} rej=${eng.cnt.zoneRejected} slCost=${eng.cnt.slCostBlocked}"
        )
    }
    val closed = broker.trades.filter { !it.open }.sortedBy { it.exitT }
    val s = Reports.summary(broker.trades)
    var gross = 0.0; var comm = 0.0; var riskDollars = 0.0
    var wins = 0
    val monthly = LinkedHashMap<String, Double>()
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    var swapSum = 0.0
    for (t in closed) {
        gross += t.grossPnl()
        comm += c.cmsn * t.qty * t.contractSize
        swapSum += t.swap
        riskDollars += t.risk * t.qty * t.contractSize
        // ۱٫۵٫۰۳: نتیجهٔ خالص = قیمتی + سواپ − کمیسیون (سواپ قبلاً در محاسبه نبود و
        // بک‌تست نفت را خوش‌بینانه می‌کرد)
        if (t.grossPnl() + t.swap - c.cmsn * t.qty * t.contractSize > 0) wins++
        cal.timeInMillis = t.exitT!!
        val key = "%04d-%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1)
        monthly[key] = (monthly[key] ?: 0.0) + (t.grossPnl() + t.swap - c.cmsn * t.qty * t.contractSize)
    }
    val netCost = gross + swapSum - comm
    val st = Stat(
        c, s.trades, wins, gross, comm, s.net, netCost,
        if (riskDollars > 0) netCost / riskDollars else 0.0,
        s.profitFactor, s.maxDD, broker.equity, riskDollars, monthly.toList(),
        (System.currentTimeMillis() - t0) / 1000.0, swapSum, broker.swapNightsTotal
    )
    return st to closed
}

fun main(args: Array<String>) {
    var data = ""
    var specs = ArrayList<String>()
    var out = ""
    var tradesOut = ""
    var diag = false
    var optMonths = 0
    var fromYear = Int.MIN_VALUE
    var toYear = Int.MAX_VALUE
    var pathsOut = ""
    var lookahead = 2880
    var fxWf = false
    var fxCostMult = 1.0
    var fxSweep = false
    var fxTfSec = 0L
    var fxPair = ""
    var fxDir = ""
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--data" -> data = args[++i]
            "--spec" -> specs.add(args[++i])
            "--specs" -> File(args[++i]).readLines().filter { it.isNotBlank() && !it.startsWith("#") }
                .forEach { specs.add(it.trim()) }
            "--out" -> out = args[++i]
            "--trades" -> tradesOut = args[++i]
            "--diag" -> diag = true
            "--optmonths" -> optMonths = args[++i].toInt()
            "--from-year" -> fromYear = args[++i].toInt()
            "--to-year" -> toYear = args[++i].toInt()
            "--paths" -> pathsOut = args[++i]
            "--lookahead" -> lookahead = args[++i].toInt()
            "--fx" -> fxPair = args[++i]
            "--fxdir" -> fxDir = args[++i]
            "--fxwf" -> fxWf = true
            "--fxcost" -> fxCostMult = args[++i].toDouble()
            "--fxsweep" -> fxSweep = true
            "--fxtf" -> fxTfSec = args[++i].toLong()
            else -> {}
        }
        i++
    }
    // ── میز جفت‌ارز (۱٫۵٫۰۴): اجرای قاعدهٔ آزموده‌شده روی فایل CSV/دایرکتوری ──
    if (fxSweep) { runFxSweep(fxPair, fxDir, data, fxTfSec, fxCostMult); return }
    if (fxPair.isNotEmpty() || fxDir.isNotEmpty()) { runFxDesk(fxPair, fxDir, data, fxWf, fxCostMult, fxSweep, fxTfSec); return }
    if (data.isEmpty()) { println("--data لازم است"); return }
    val t0 = System.currentTimeMillis()
    val candles = readCandles(data, fromYear, toYear)
    println("DATA ${candles.size} کندل در ${(System.currentTimeMillis() - t0) / 1000.0} ثانیه")
    if (candles.isEmpty()) return
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    println("WINDOW ${fmt.format(java.util.Date(candles.first().t))} → ${fmt.format(java.util.Date(candles.last().t))}")

    // ── اجرای «بهینه‌ساز درون‌اپ» روی همین داده (اعتبارسنجی همان مسیری که گوشی اجرا می‌کند) ──
    if (optMonths > 0) {
        val base = (specs.firstOrNull()?.let { Conf().apply { parse(it, null) } } ?: Conf()).toSettings()
        val endT = candles.last().t
        val fromT = endT - optMonths * 30L * 24L * 3_600_000L
        val slice = candles.filter { it.t >= fromT }
        println("OPTIMIZE پنجره: ${slice.size} کندل (${optMonths} ماه آخر)")
        val rep = Optimizer.optimize(base, slice, progress = { d, t, l -> println("OPT $d/$t $l") })
        println("OPT BEST ${rep.summary()}")
        for (r in rep.rows) {
            println(String.format("OPTROW %-32s trd=%-5d avgR=%+.4f net=%-9.0f pf=%-5.2f dd=%-7.0f mo+=%d/%d score=%.2f",
                r.label, r.trades, r.avgRCost, r.netCost, r.pf, r.maxDD, r.posMonths, r.months, r.score))
        }
        return
    }

    val header = "tag,strategy,mm,fade,k,tp,N,minSl,cost,buf,bufMode,eb,cmsn,risk,tf,trades,wins,winRate," +
        "gross,comm,net,netCost,avgRCost,pf,maxDD,finalEquity,riskDollars,posMonths,months,worst12,seconds," +
        "mgmt,holdR,holdDays,trailStart,trailDist,mgmtGate,swapSum,swapNights"
    val w = if (out.isNotEmpty()) File(out).bufferedWriter() else null
    w?.apply { write(header); newLine() }
    println(String.format("%-34s %6s %8s %9s %9s %8s %7s %7s %8s %6s %7s", "tag", "trd", "win%", "netCost", "avgRCost", "pf", "maxDD", "worst12", "final", "mo+", "secs"))
    for (spec in specs) {
        val c = Conf().apply { parse(spec, null) }
        val (r, trades) = runOne(c, candles, diag)
        println(String.format("%-34s %6d %8.1f %9.0f %9.4f %8.3f %7.0f %8.0f %8.0f %3d/%-3d %7.1f  swap=%.1f(%d شب)",
            c.tag, r.trades, r.winRate, r.netCost, r.avgRCost, r.pf, r.maxDD, r.worst12, r.finalEquity, r.posMonths, r.monthly.size, r.secs, r.swap, r.swapNights))
        w?.apply {
            write(listOf(
                c.tag, c.strategy, c.mm, c.fade, c.k, c.tp, c.nMax, c.minSl, c.cost, c.buf, c.bufMode, c.eb,
                c.cmsn, c.risk, c.tf, r.trades, r.wins, "%.2f".format(r.winRate), "%.2f".format(r.gross),
                "%.2f".format(r.comm), "%.2f".format(r.net), "%.2f".format(r.netCost), "%.4f".format(r.avgRCost),
                "%.3f".format(r.pf), "%.2f".format(r.maxDD), "%.2f".format(r.finalEquity),
                "%.2f".format(r.riskDollars), r.posMonths,
                r.monthly.joinToString("|") { "${it.first}:${"%.0f".format(it.second)}" },
                "%.2f".format(r.worst12), "%.1f".format(r.secs),
                c.mgmt, c.holdR, c.holdDays, c.trailStart, c.trailDist, c.mgmtGate,
                "%.2f".format(r.swap), r.swapNights
            ).joinToString(",")); newLine(); flush()
        }
        if (tradesOut.isNotEmpty()) dumpTrades(c, candles, trades, tradesOut)
        if (pathsOut.isNotEmpty()) dumpPaths(c, candles, trades, pathsOut, lookahead)
    }
    w?.close()
}

/** یک اجرا و ریزش همهٔ معاملات بسته با ویژگی‌های لحظهٔ ورود (برای پژوهش فیلترها). */
private fun dumpTrades(c: Conf, candles: List<Candle>, closed: List<Trade>, path: String) {
    val w = File(path).bufferedWriter()
    w.write("entryT,iso,dir,entry,sl,tp,exitPx,reason,qty,riskP,riskUsd,gross,comm,pnl,R,exitT,hourUtc,dowUtc,atrH1_14,atrH4_14,boxH,distSl,equityBefore")
    w.newLine()
    // ATR روی کندل‌های ۱ ساعته و ۴ ساعته برای ویژگی‌های رژیم
    val h1 = aggregate(candles, Tf.H1)
    val h4 = aggregate(candles, Tf.H4)
    val a1 = atrSeries(h1, 14)
    val a4 = atrSeries(h4, 14)
    val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    for (t in closed) {
        val h = ((t.entryT % 86_400_000L) / 3_600_000L).toInt()
        val dow = Tf.utcDowMonday0(t.entryT)
        val ca1 = atrAt(h1, a1, t.entryT, Tf.H1)
        val ca4 = atrAt(h4, a4, t.entryT, Tf.H4)
        val boxH = abs(t.zoneTop - t.zoneBot)
        w.write(listOf(
            t.entryT, iso.format(java.util.Date(t.entryT)), t.dir, "%.2f".format(t.entry), "%.2f".format(t.sl0),
            "%.2f".format(t.tpX), t.exitPx?.let { "%.2f".format(it) } ?: "", t.reason,
            "%.4f".format(t.qty), "%.4f".format(t.risk), "%.2f".format(t.risk * t.qty * t.contractSize),
            "%.2f".format(t.grossPnl()), "%.2f".format(c.cmsn * t.qty * t.contractSize),
            "%.2f".format(t.pnl()), "%.4f".format(t.rMultiple()), t.exitT ?: 0L,
            h, dow, "%.2f".format(ca1), "%.2f".format(ca4), "%.2f".format(boxH),
            "%.2f".format(abs(t.entry - t.sl0)), "%.2f".format(t.entry * t.qty)
        ).joinToString(",")); w.newLine()
    }
    w.close()
    println("TRADES → $path")
}

/**
 * ریزش **مسیر قیمت** هر معامله برای پژوهش سیستم مدیریت معاملهٔ باز.
 *
 * برای هر معامله بسته‌شده، کندل‌ها از کندل ورود تا `lookahead` کندل بعد (پیش‌فرض ۲۸۸۰ = دو روز M1)
 * نوشته می‌شوند — نه فقط تا خروج. دلیل: بخشی از پژوهش «نگه‌داشتن معامله بعد از رأس ۲۰:۰۰ UTC»
 * است، پس مسیر باید از خروج پیش‌فرضِ فعلی هم فراتر برود.
 *
 * ستون‌ها: id,t,o,h,l,c  (t میلی‌ثانیهٔ UTC)
 */
private fun dumpPaths(c: Conf, candles: List<Candle>, closed: List<Trade>, path: String, lookahead: Int) {
    val w = java.io.BufferedWriter(java.io.OutputStreamWriter(java.io.FileOutputStream(path), Charsets.UTF_8), 1 shl 20)
    w.write("id,t,o,h,l,c")
    w.newLine()
    var rows = 0
    for ((idx, t) in closed.withIndex()) {
        val a = t.entryBi.coerceIn(0, candles.size - 1)
        val b = minOf(a + lookahead, candles.size - 1)
        for (k in a..b) {
            val cd = candles[k]
            w.write(idx.toString()); w.write(","); w.write(cd.t.toString()); w.write(",")
            w.write("%.2f".format(cd.o)); w.write(","); w.write("%.2f".format(cd.h)); w.write(",")
            w.write("%.2f".format(cd.l)); w.write(","); w.write("%.2f".format(cd.c)); w.newLine()
            rows++
        }
    }
    w.close()
    println("PATHS → $path ($rows ردیف · ${closed.size} معامله · lookahead=$lookahead)")
}

private fun aggregate(src: List<Candle>, tf: Int): List<Candle> {
    val out = ArrayList<Candle>(src.size / 30 + 16)
    val agg = Agg()
    var prevT: Long? = null
    var bi = 0
    for (c in src) {
        val isNew = prevT == null || Tf.bucket(tf, c.t) != Tf.bucket(tf, prevT)
        agg.step(isNew, bi, Tf.bucket(tf, c.t), c.o, c.h, c.l, c.c, c.v)
        if (agg.closedNow) agg.cl?.let { cl -> out.add(Candle(bi++, cl.t, cl.o, cl.h, cl.l, cl.c, cl.v)) }
        prevT = c.t
    }
    agg.cl?.let { cl -> out.add(Candle(bi, cl.t, cl.o, cl.h, cl.l, cl.c, cl.v)) }
    return out
}

/** Wilder ATR روی لیست کندل‌ها؛ میانه با میانگین سادهٔ ۱۴ دوره شروع می‌شود. */
private fun atrSeries(bars: List<Candle>, period: Int): DoubleArray {
    val a = DoubleArray(bars.size) { Double.NaN }
    var sum = 0.0
    for (i in bars.indices) {
        val tr = if (i == 0) bars[i].h - bars[i].l else max(
            bars[i].h - bars[i].l,
            max(abs(bars[i].h - bars[i - 1].c), abs(bars[i].l - bars[i - 1].c))
        )
        if (i < period) { sum += tr; if (i == period - 1) a[i] = sum / period }
        else a[i] = (a[i - 1] * (period - 1) + tr) / period
    }
    return a
}

private fun atrAt(bars: List<Candle>, a: DoubleArray, t: Long, tfSec: Int): Double {
    if (a.isEmpty() || bars.isEmpty()) return Double.NaN
    // آخرین کندلِ بسته‌شده که شروعش پیش از لحظهٔ ورود است
    var lo = 0; var hi = bars.size - 1; var idx = -1
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (bars[mid].t + tfSec * 1000L <= t) { idx = mid; lo = mid + 1 } else hi = mid - 1
    }
    return if (idx >= 0) a[idx] else Double.NaN
}

// ═══════════════════════════════════════════════════════════════════════════════
//  میز جفت‌ارز — اجرای همان کدی که در اپ اجرا می‌شود، روی دادهٔ محلی
//
//  ./cli --fx EURUSD --data /tmp/fx/EURUSD.csv
//  ./cli --fxdir /tmp/fx            # همهٔ جفت‌ها، هر کدام در فایل <PAIR>.csv
// ═══════════════════════════════════════════════════════════════════════════════
private fun runFxDesk(pairId: String, dir: String, singleFile: String,
                      walkForward: Boolean = false, costMult: Double = 1.0,
                      sweep: Boolean = false, tfOverride: Long = 0L) {
    // نرخ تبدیل «ارز مظنه → دلار» از آخرین کندل فایل‌های مرجع (برای بک‌تست محلی)
    val usdPerQuote = HashMap<String, Double>()
    usdPerQuote["USD"] = 1.0
    for ((quote, ref) in listOf("JPY" to "USDJPY", "CAD" to "USDCAD", "CHF" to "USDCHF")) {
        val f = File(dir, "$ref.csv")
        if (f.exists()) {
            val c = readCandles(f.absolutePath, Int.MIN_VALUE, Int.MAX_VALUE)
            if (c.isNotEmpty()) usdPerQuote[quote] = 1.0 / c.last().c
        }
    }
    usdPerQuote["GBP"] = run {
        val f = File(dir, "GBPUSD.csv")
        if (f.exists()) readCandles(f.absolutePath, Int.MIN_VALUE, Int.MAX_VALUE).lastOrNull()?.c ?: 1.27 else 1.27
    }

    val targets = if (pairId.isNotEmpty()) listOf(FxPairs.byId(pairId))
    else FxPairs.ALL

    println(String.format("%-8s %-7s %-5s %6s %7s %7s %7s %9s %9s %6s",
        "جفت", "تایم", "حالت", "معامله", "برد٪", "میانگینR", "PF", "سود خالص$", "میانگینR/سال", "شب"))

    var totalR = 0.0; var totalTrd = 0
    for (p in targets) {
        val path = if (pairId.isNotEmpty()) singleFile else File(dir, "${p.id}.csv").absolutePath
        val f = File(path)
        if (!f.exists()) { println("${p.id}: فایل نیست ($path)"); continue }
        // فایل‌های جفت‌ارز معمولاً **ثانیه**ای‌اند؛ موتور میلی‌ثانیه می‌خواهد.
        val raw = readCandles(path, Int.MIN_VALUE, Int.MAX_VALUE)
        if (raw.isEmpty()) continue
        val secScale = if (raw.last().t < 100_000_000_000L) 1000L else 1L
        val base = if (secScale == 1L) raw else raw.map { Candle(it.bi, it.t * secScale, it.o, it.h, it.l, it.c, it.v) }
        val cost0 = FxCostTable.costFor(p.id, usdPerQuote) ?: run {
            println("${p.id}: هزینهٔ بروکر تعریف نشده"); null
        } ?: continue
        val cost = if (costMult == 1.0) cost0 else cost0.copy(spreadPoints = cost0.spreadPoints * costMult)

        // تجمیع به تایم‌فریم خود جفت
        val tfMs = p.tfSec * 1000L
        val agg = LinkedHashMap<Long, Candle>()
        for (c in base) {
            val b = Math.floorDiv(c.t, tfMs) * tfMs
            val prev = agg[b]
            agg[b] = if (prev == null) Candle(c.bi, b, c.o, c.h, c.l, c.c, c.v)
            else Candle(prev.bi, b, prev.o, Math.max(prev.h, c.h), Math.min(prev.l, c.l), c.c, prev.v + c.v)
        }
        val bars = agg.values.toList()

        fun runOn(slice: List<Candle>): List<FxTrade> {
            val d = FxDesk(capital = 100_000.0, riskPct = 0.5,
                commissionPerLotRoundTurn = 5.0 * costMult)
            d.feed = { cost }
            val h2 = ArrayList<Candle>(slice.size)
            for (b in slice) { h2.add(b); d.onBar(p, h2, b) }
            if (slice.isNotEmpty()) d.closeAll({ slice.last().c }, slice.last().t)
            return d.closed
        }
        if (walkForward) {
            val cutT = bars.first().t + ((bars.last().t - bars.first().t) * 0.6).toLong()
            val ins = runOn(bars.filter { it.t < cutT })
            val oos = runOn(bars.filter { it.t >= cutT })
            fun st(x: List<FxTrade>): String = if (x.isEmpty()) "—"
                else String.format("n=%d avgR=%+.3f PF=%.2f", x.size, x.sumOf { it.realizedR() } / x.size,
                    x.filter { it.realizedR() > 0 }.sumOf { it.realizedR() } /
                        Math.max(-x.filter { it.realizedR() <= 0 }.sumOf { it.realizedR() }, 1e-9))
            println(String.format("%-8s %-4s %-6s | درون‌نمونه %-26s | **بیرون‌نمونه %-26s**",
                p.id, "H" + (p.tfSec / 3600), if (p.dirMode > 0) "trend" else "fade", st(ins), st(oos)))
            continue
        }

        val tr = runOn(bars)
        if (tr.isEmpty()) { println("${p.id}: صفر معامله"); continue }
        val wins = tr.count { it.realizedR() > 0 }
        val sumR = tr.sumOf { it.realizedR() }
        val gains = tr.filter { it.realizedR() > 0 }.sumOf { it.realizedR() }
        val losses = -tr.filter { it.realizedR() <= 0 }.sumOf { it.realizedR() }
        val years = Math.max((bars.last().t - bars.first().t) / (365.25 * 24 * 3_600_000.0), 0.1)
        val net = tr.sumOf { it.netUsd() }
        val nights = tr.map { it.swapNights }.average()
        totalR += sumR; totalTrd += tr.size
        println(String.format("%-8s %-7s %-5s %6d %6.1f%% %+7.3f %7.2f %9.0f %9.2f %6.1f",
            p.id, "H" + (p.tfSec / 3600), if (p.dirMode > 0) "trend" else "fade",
            tr.size, 100.0 * wins / tr.size, sumR / tr.size,
            gains / Math.max(losses, 1e-9), net, sumR / years, nights))
    }
    if (totalTrd > 0) println(String.format("— جمع: %d معامله · میانگین R وزنی %+.3f —",
        totalTrd, totalR / totalTrd))
}

// ═══════════════════════════════════════════════════════════════════════════════
//  جست‌وجوی پارامتر روی میز جفت‌ارز با کد خودِ اپ (برای آزمون ۲۰ ساله)
//
//  ./cli --fxsweep --fxtf 86400 --fxdir <dir>     # دادهٔ روزانه ۲۰ ساله
// ═══════════════════════════════════════════════════════════════════════════════
private class FxSweepRow(
    val mode: Int, val N: Int, val atr: Double, val k: Double, val partial: Boolean,
    val trades: Int, val avgR: Double, val pf: Double, val h1: Double, val h2: Double,
    val posYears: Int, val years: Int, val dd: Double
)

private fun fxScore(avgR: Double, h1: Double, h2: Double, dd: Double, trades: Int): Double {
    if (trades < 40) return -9e9
    val worst = Math.min(h1, h2)
    return worst * 100.0 + avgR * 50.0 - (dd / Math.max(trades, 1)) * 10.0
}

/** اجرای یک ترکیب روی یک جفت و برگرداندن آمار. */
private fun fxRun(pair: FxProfile, bars: List<Candle>, cost: FxCost,
                  mode: Int, N: Int, atr: Double, k: Double, partial: Boolean,
                  costMult: Double): FxSweepRow? {
    val prof = FxProfile(pair.id, pair.label, pair.digits, pair.tfSec, mode, N, atr, k,
        partial, pair.onByDefault, pair.oosAvgR, pair.oosTrades, pair.avgR20, pair.rec3AvgR, pair.note)
    val d = FxDesk(capital = 100_000.0, riskPct = 0.5,
        commissionPerLotRoundTurn = 5.0 * costMult)
    d.feed = { cost }
    val hist = ArrayList<Candle>(bars.size)
    for (b in bars) { hist.add(b); d.onBar(prof, hist, b) }
    if (bars.isNotEmpty()) d.closeAll({ bars.last().c }, bars.last().t)
    val tr = d.closed
    if (tr.isEmpty()) return null
    val R = tr.map { it.realizedR() }
    val avg = R.average()
    val gains = R.filter { it > 0 }.sum(); val losses = -R.filter { it <= 0 }.sum()
    val cut = bars.first().t + ((bars.last().t - bars.first().t) * 0.6).toLong()
    val h1 = tr.filter { it.entryT < cut }.map { it.realizedR() }.let { if (it.isEmpty()) 0.0 else it.average() }
    val h2 = tr.filter { it.entryT >= cut }.map { it.realizedR() }.let { if (it.isEmpty()) 0.0 else it.average() }
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    val yearly = HashMap<Int, Double>()
    for (x in tr) {
        cal.timeInMillis = x.exitT
        val y = cal.get(java.util.Calendar.YEAR)
        yearly[y] = (yearly[y] ?: 0.0) + x.realizedR()
    }
    var eq = 0.0; var peak = 0.0; var dd = 0.0
    for (v in R) { eq += v; peak = Math.max(peak, eq); dd = Math.max(dd, peak - eq) }
    return FxSweepRow(mode, N, atr, k, partial, tr.size, avg,
        gains / Math.max(losses, 1e-9), h1, h2,
        yearly.count { it.value > 0 }, yearly.size, dd)
}

private fun runFxSweep(pairId: String, dir: String, singleFile: String,
                       tfOverride: Long, costMult: Double) {
    val usdPerQuote = HashMap<String, Double>()
    usdPerQuote["USD"] = 1.0
    for ((quote, ref) in listOf("JPY" to "USDJPY", "CAD" to "USDCAD", "CHF" to "USDCHF")) {
        val f = File(dir, "$ref.csv")
        if (f.exists()) {
            val c = readCandles(f.absolutePath, Int.MIN_VALUE, Int.MAX_VALUE)
            if (c.isNotEmpty()) usdPerQuote[quote] = 1.0 / c.last().c
        }
    }
    usdPerQuote["GBP"] = File(dir, "GBPUSD.csv").let { f ->
        if (f.exists()) readCandles(f.absolutePath, Int.MIN_VALUE, Int.MAX_VALUE).lastOrNull()?.c ?: 1.27 else 1.27
    }

    val targets = if (pairId.isNotEmpty()) listOf(FxPairs.byId(pairId)) else FxPairs.ALL
    println("جفت · بهترین ترکیب روی همین داده (امتیاز = بدترین نیمه + انتظار − افت) · پارامتر منتشرشده روی همین داده")
    for (p0 in targets) {
        val p = if (tfOverride > 0) FxProfile(p0.id, p0.label, p0.digits, tfOverride,
            p0.dirMode, p0.lookback, p0.atrMult, p0.targetR, p0.partial, p0.onByDefault,
            p0.oosAvgR, p0.oosTrades, p0.avgR20, p0.rec3AvgR, p0.note) else p0
        val path = if (pairId.isNotEmpty()) singleFile else File(dir, "${p.id}.csv").absolutePath
        if (!File(path).exists()) { println("${p.id}: فایل نیست"); continue }
        val raw = readCandles(path, Int.MIN_VALUE, Int.MAX_VALUE)
        val secScale = if (raw.last().t < 100_000_000_000L) 1000L else 1L
        val base = if (secScale == 1L) raw else raw.map { Candle(it.bi, it.t * secScale, it.o, it.h, it.l, it.c, it.v) }
        val tfMs = p.tfSec * 1000L
        val agg = LinkedHashMap<Long, Candle>()
        for (c in base) {
            val b = Math.floorDiv(c.t, tfMs) * tfMs
            val prev = agg[b]
            agg[b] = if (prev == null) Candle(c.bi, b, c.o, c.h, c.l, c.c, c.v)
            else Candle(prev.bi, b, prev.o, Math.max(prev.h, c.h), Math.min(prev.l, c.l), c.c, prev.v + c.v)
        }
        val bars = agg.values.toList()
        if (bars.size < 300) { println("${p.id}: کندل کافی نیست (${bars.size})"); continue }
        val cost = FxCostTable.costFor(p.id, usdPerQuote) ?: continue

        // پارامتر منتشرشده (همان که در اپ است) روی همین داده
        val ship = fxRun(p, bars, cost, p.dirMode, p.lookback, p.atrMult, p.targetR, p.partial, costMult)

        var best: FxSweepRow? = null
        var bestScore = -9e9
        for (mode in intArrayOf(-1, 1))
            for (N in intArrayOf(6, 12, 24, 36, 48, 72, 96))
                for (atr in doubleArrayOf(1.0, 1.5, 2.0, 3.0, 4.0))
                    for (k in doubleArrayOf(1.5, 2.0, 3.0, 4.0, 7.0))
                        for (partial in booleanArrayOf(false, true)) {
                            val r = fxRun(p, bars, cost, mode, N, atr, k, partial, costMult) ?: continue
                            val sc = fxScore(r.avgR, r.h1, r.h2, r.dd, r.trades)
                            if (sc > bestScore) { bestScore = sc; best = r }
                        }
        fun fmt(r: FxSweepRow?) = if (r == null) "—" else String.format(
            "%s N=%d atr=%.1f k=%.1f %s | n=%d avgR=%+.3f PF=%.2f نیمه۱=%+.3f نیمه۲=%+.3f سال+ %d/%d افت=%.1fR",
            if (r.mode > 0) "trend" else "fade", r.N, r.atr, r.k,
            if (r.partial) "partial" else "single", r.trades, r.avgR, r.pf, r.h1, r.h2,
            r.posYears, r.years, r.dd)
        println(String.format("%-7s | %s", p.id, fmt(best)))
        println(String.format("%-7s | منتشرشده: %s", "", fmt(ship)))
    }
}
