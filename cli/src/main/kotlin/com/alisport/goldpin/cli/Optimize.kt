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
            cmsnAuto = false
            cmsnManual = this@Conf.cmsn
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
    val secs: Double
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
                "windowBlocked=${eng.cnt.windowBlocked} slN=${eng.cnt.slNBlocked} orders=${broker.orders.size}"
        )
    }
    val closed = broker.trades.filter { !it.open }.sortedBy { it.exitT }
    val s = Reports.summary(broker.trades)
    var gross = 0.0; var comm = 0.0; var riskDollars = 0.0
    var wins = 0
    val monthly = LinkedHashMap<String, Double>()
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    for (t in closed) {
        gross += t.grossPnl()
        comm += c.cmsn * t.qty * t.contractSize
        riskDollars += t.risk * t.qty * t.contractSize
        if (t.grossPnl() - c.cmsn * t.qty * t.contractSize > 0) wins++
        cal.timeInMillis = t.exitT!!
        val key = "%04d-%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1)
        monthly[key] = (monthly[key] ?: 0.0) + (t.grossPnl() - c.cmsn * t.qty * t.contractSize)
    }
    val netCost = gross - comm
    val st = Stat(
        c, s.trades, wins, gross, comm, s.net, netCost,
        if (riskDollars > 0) netCost / riskDollars else 0.0,
        s.profitFactor, s.maxDD, broker.equity, riskDollars, monthly.toList(),
        (System.currentTimeMillis() - t0) / 1000.0
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
            else -> {}
        }
        i++
    }
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
        "mgmt,holdR,holdDays,trailStart,trailDist,mgmtGate"
    val w = if (out.isNotEmpty()) File(out).bufferedWriter() else null
    w?.apply { write(header); newLine() }
    println(String.format("%-34s %6s %8s %9s %9s %8s %7s %7s %8s %6s %7s", "tag", "trd", "win%", "netCost", "avgRCost", "pf", "maxDD", "worst12", "final", "mo+", "secs"))
    for (spec in specs) {
        val c = Conf().apply { parse(spec, null) }
        val (r, trades) = runOne(c, candles, diag)
        println(String.format("%-34s %6d %8.1f %9.0f %9.4f %8.3f %7.0f %8.0f %8.0f %3d/%-3d %7.1f",
            c.tag, r.trades, r.winRate, r.netCost, r.avgRCost, r.pf, r.maxDD, r.worst12, r.finalEquity, r.posMonths, r.monthly.size, r.secs))
        w?.apply {
            write(listOf(
                c.tag, c.strategy, c.mm, c.fade, c.k, c.tp, c.nMax, c.minSl, c.cost, c.buf, c.bufMode, c.eb,
                c.cmsn, c.risk, c.tf, r.trades, r.wins, "%.2f".format(r.winRate), "%.2f".format(r.gross),
                "%.2f".format(r.comm), "%.2f".format(r.net), "%.2f".format(r.netCost), "%.4f".format(r.avgRCost),
                "%.3f".format(r.pf), "%.2f".format(r.maxDD), "%.2f".format(r.finalEquity),
                "%.2f".format(r.riskDollars), r.posMonths,
                r.monthly.joinToString("|") { "${it.first}:${"%.0f".format(it.second)}" },
                "%.2f".format(r.worst12), "%.1f".format(r.secs),
                c.mgmt, c.holdR, c.holdDays, c.trailStart, c.trailDist, c.mgmtGate
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
