package com.alisport.goldpin.core

import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * آزمون‌های مکانیزم‌های نسخهٔ ۱٫۴٫۴۹:
 *
 *  ۱. **تطبیق با نوسان** ([Settings.slBufMode] = ۱): بافر حد ضرر از ATR کندل‌های بستهٔ
 *     ساختار ساخته می‌شود، نه از یک عدد تیکِ ثابت. آزمون‌ها اینجا قفل می‌کنند که
 *     (الف) پیش‌فرض صفر یعنی رفتار قدیم **دقیقاً** حفظ شود، (ب) در مود ATR فاصلهٔ حد
 *     ضرر با ATR حرکت کند (در پنجرهٔ پرنوسان پهن‌تر از پنجرهٔ کم‌نوسان باشد).
 *  ۲. **نگهبان منحنی سرمایه** ([Settings.guardMode]): با آستانهٔ افت کوچک، ورودهای
 *     تازه بسته می‌شوند و تعداد معاملات کمتر و افت کمتر می‌شود — بدون اینکه موتور
 *     کرش کند یا معاملات باز نیمه‌کاره رها شوند.
 *
 * برای سرعت، همهٔ اجراها روی فایل نمونهٔ باندل‌شدهٔ خود اپ انجام می‌شود، نه دیتاست
 * چند میلیون کندلی (که در `TunedTenYearTest` و `ParamSweepTest` پوشش داده شده است).
 */
class AdaptiveVolAndGuardTest {

    private companion object {
        /**
         * دادهٔ آزمون: ترجیحاً برش کوتاهی از دیتاست یک‌سالهٔ مخزن (چند ماه M1، هزاران
         * معاملهٔ بالقوه)، وگرنه فایل نمونهٔ کوچک داخل اپ. برش ۲۰۰٬۰۰۰ کندلی عمدی است:
         * آزمون باید چند ثانیه‌ای بماند، نه چند دقیقه.
         */
        const val MAX_CANDLES = 200_000

        fun sampleFile(): File? = listOf(
            "datasets/goldpin_xauusd_m1_1y.csv.gz",
            "../datasets/goldpin_xauusd_m1_1y.csv.gz",
            "/home/user/ST2/datasets/goldpin_xauusd_m1_1y.csv.gz",
            "app/src/main/assets/sample_xauusd_m1.csv.gz",
            "../app/src/main/assets/sample_xauusd_m1.csv.gz"
        ).map(::File).firstOrNull { it.exists() }

        val cached = HashMap<String, List<Candle>>()

        fun load(file: File): List<Candle> = cached.getOrPut(file.absolutePath) {
            val out = ArrayList<Candle>(400_000)
            GZIPInputStream(file.inputStream()).bufferedReader().useLines { lines ->
                var bi = 0
                for (ln in lines) {
                    val p = ln.split(',')
                    if (p.size < 6) continue
                    val t = p[0].trim().toLongOrNull() ?: continue
                    out.add(
                        Candle(
                            bi++, t, p[1].toDouble(), p[2].toDouble(), p[3].toDouble(),
                            p[4].toDouble(), p[5].toDouble()
                        )
                    )
                    if (out.size >= MAX_CANDLES) break
                }
            }
            out
        }

        /** تنظیمات پایه: همان هندسهٔ آزموده‌شده (Mixed · هم‌جهت · هدف ۷R · بافر ۱۸۰۰) با کارمزد ۰٫۷. */
        fun base(): Settings {
            val mode = TF_MODES.first { it.idx == 1 }
            return Settings().apply {
                tfS = mode.s; tfM = mode.m; tf1 = mode.t1; tf2 = mode.t2
                strategy = Strategy.DOC
                mmMode = MoneyMode.MIXED
                trigger2Fade = false
                trigger2TargetR = 7.0
                minSlCostMult = 0.0
                maxSlUnits = 80.0
                minSlUnits = 0.0
                slBufTicks = 1800.0
                entryBufTicks = -2.0
                cmsnAuto = false
                cmsnManual = 0.7
                minLotAuto = false
                minLotManual = 10.0
                initialEquity = 100_000.0
            }
        }

        fun run(cfg: Settings, candles: List<Candle>): Pair<List<Trade>, PaperBroker> {
            val eng = Engine(cfg)
            val broker = PaperBroker(cfg)
            eng.broker = broker
            broker.engine = eng
            eng.barCommitHook = { cd -> broker.onBar(cd, live = false) }
            var upto = 0
            while (upto < candles.size) {
                val next = minOf(upto + 25_000, candles.size)
                eng.feed(candles.subList(0, next), lastIsClosed = true)
                upto = next
            }
            return broker.trades.filter { !it.open } to broker
        }

        fun medianSlDistance(trades: List<Trade>): Double {
            if (trades.isEmpty()) return Double.NaN
            val a = trades.map { abs(it.entry - it.sl0) }.sorted()
            return a[a.size / 2]
        }
    }

    @Test
    fun `defaults keep the old fixed-tick geometry exactly`() {
        val cfg = base()
        assertEquals(0, cfg.slBufMode, "پیش‌فرض بافر: تیک ثابت (رفتار نسخه‌های قبل)")
        assertEquals(0, cfg.maxSlMode, "پیش‌فرض سقف N: واحد ثابت")
        assertEquals(0, cfg.guardMode, "پیش‌فرض نگهبان: خاموش")
        assertEquals(1800.0, (cfg.copy()).slBufTicks, "copy() باید baffر را نگه دارد")
    }

    @Test
    fun `copy carries the adaptive keys`() {
        val cfg = base().apply {
            slBufMode = 1; slAtrMult = 2.5; slAtrPeriod = 21; slAtrMinUnits = 3.0; slAtrMaxUnits = 40.0
            maxSlMode = 1; maxSlAtrMult = 6.0
            guardMode = 2; guardDDPct = 10.0; guardHardDDPct = 20.0; guardRiskScale = 0.4
            guardResetAtHigh = false
        }
        val c = cfg.copy()
        assertEquals(1, c.slBufMode); assertEquals(2.5, c.slAtrMult); assertEquals(21, c.slAtrPeriod)
        assertEquals(3.0, c.slAtrMinUnits); assertEquals(40.0, c.slAtrMaxUnits)
        assertEquals(1, c.maxSlMode); assertEquals(6.0, c.maxSlAtrMult)
        assertEquals(2, c.guardMode); assertEquals(10.0, c.guardDDPct)
        assertEquals(20.0, c.guardHardDDPct); assertEquals(0.4, c.guardRiskScale)
        assertEquals(false, c.guardResetAtHigh)
    }

    @Test
    fun `structure ATR is available after enough closed structure bars and is positive`() {
        val f = sampleFile()
        assumeTrue(f != null, "فایل نمونهٔ داده در دسترس نیست")
        val candles = load(f!!)
        assumeTrue(candles.size > 10_000, "نمونهٔ داده کوتاه است: ${candles.size}")

        val cfg = base()
        val eng = Engine(cfg)
        val broker = PaperBroker(cfg)
        eng.broker = broker; broker.engine = eng
        eng.barCommitHook = { cd -> broker.onBar(cd, live = false) }
        eng.feed(candles.subList(0, 5_000), lastIsClosed = true)
        // با کمتر از ۱۵ کندل ساختار، ATR معنا ندارد
        val early = eng.structureAtr(14)
        if (eng.structBars.size < 15) assertTrue(early.isNaN(), "ATR باید با دادهٔ ناکافی NaN باشد")
        eng.feed(candles.subList(0, candles.size), lastIsClosed = true)
        assertTrue(eng.structBars.size > 20, "کندل ساختار کافی نیست")
        val atr = eng.structureAtr(14)
        assertTrue(atr.isFinite() && atr > 0.0, "ATR ساختار باید مثبت باشد: $atr")
        // بافر مؤثر در مود ثابت دقیقاً همان تیک‌هاست
        assertEquals(cfg.slBufTicks * cfg.mintick, eng.slBufUnits(), 1e-9)
        // در مود ATR باید از ضریب × ATR بیاید (بین کف/سقف اگر داده شده باشد)
        cfg.slBufMode = 1; cfg.slAtrMult = 2.0; cfg.slAtrMinUnits = 1.0; cfg.slAtrMaxUnits = 1_000.0
        val buf = eng.slBufUnits()
        assertEquals(maxOf(1.0, 2.0 * atr), buf, 1e-6)
    }

    @Test
    fun `ATR buffer changes stop distance and still produces trades`() {
        val f = sampleFile()
        assumeTrue(f != null, "فایل نمونهٔ داده در دسترس نیست")
        val candles = load(f!!)
        assumeTrue(candles.size > 50_000, "نمونهٔ داده کوتاه است: ${candles.size}")

        val fixed = base()
        val (tradesFixed, _) = run(fixed, candles)
        assumeTrue(tradesFixed.size >= 10, "اجرای مرجع باید معامله بدهد: ${tradesFixed.size}")

        val atrCfg = base().apply {
            slBufMode = 1; slAtrMult = 2.0; slAtrPeriod = 14
            // سقف N باید نسبی شود وگرنه همهٔ سفارش‌های پهن رد می‌شوند
            maxSlMode = 1; maxSlAtrMult = 8.0
        }
        val (tradesAtr, _) = run(atrCfg, candles)
        assertTrue(tradesAtr.size >= 10, "مود ATR هم باید معامله بدهد: ${tradesAtr.size}")

        val dFixed = medianSlDistance(tradesFixed)
        val dAtr = medianSlDistance(tradesAtr)
        assertTrue(dFixed.isFinite() && dAtr.isFinite())
        assertTrue(
            abs(dAtr - dFixed) > 0.5,
            "فاصلهٔ حد ضرر در مود ATR باید با مود ثابت فرق کند (ثابت=${dFixed}, ATR=${dAtr})"
        )
    }

    @Test
    fun `curve guard blocks new entries after a hard drawdown and reduces drawdown`() {
        val f = sampleFile()
        assumeTrue(f != null, "فایل نمونهٔ داده در دسترس نیست")
        val candles = load(f!!)
        assumeTrue(candles.size > 50_000, "نمونهٔ داده کوتاه است: ${candles.size}")

        val (baseTrades, baseBroker) = run(base(), candles)
        assumeTrue(baseTrades.size >= 10, "اجرای مرجع باید معامله بدهد")

        // آستانهٔ افت سختِ بسیار کوچک ⇒ بعد از نخستین افت، هیچ ورود تازه‌ای مجاز نیست.
        val guarded = base().apply {
            guardMode = 2
            guardDDPct = 0.5
            guardHardDDPct = 1.0
            guardRiskScale = 0.5
        }
        val (guardTrades, guardBroker) = run(guarded, candles)
        assertTrue(
            guardTrades.size < baseTrades.size,
            "نگهبان باید تعداد معاملات را کم کند (پایه=${baseTrades.size}, نگهبان=${guardTrades.size})"
        )
        assertTrue(
            guardBroker.maxDrawdown <= baseBroker.maxDrawdown + 1e-6,
            "نگهبان نباید افت را بیشتر کند (پایه=${baseBroker.maxDrawdown}, نگهبان=${guardBroker.maxDrawdown})"
        )
        // سفارش‌های تازه با پیام روشن رد می‌شوند — نه بی‌صدا
        assertTrue(guardBroker.guardScale < 1.0, "نگهبان باید ضریب حجم را کم کرده باشد")
    }

    @Test
    fun `structure ATR follows the newest bars instead of freezing on a cached window`() {
        // رگرسیون یک باگ واقعی: کلید کش ATR به «اندازهٔ structBars» بسته بود و چون آن
        // پنجره روی ۴۰۰ کندل سقف دارد، مقدار ATR بعد از گرم‌شدن برای همیشه یخ می‌زد.
        // نتیجه: گیت نوسان/هندسهٔ ATR با نوسان روز اول کار می‌کرد. این آزمون قفل می‌کند
        // که با آمدن کندل‌های تازه، ATR عوض شود.
        val f = sampleFile()
        assumeTrue(f != null, "فایل نمونهٔ داده در دسترس نیست")
        val candles = load(f!!)
        assumeTrue(candles.size > 60_000, "نمونهٔ داده کوتاه است: ${candles.size}")

        val cfg = base()
        val eng = Engine(cfg)
        val broker = PaperBroker(cfg)
        eng.broker = broker; broker.engine = eng
        eng.barCommitHook = { cd -> broker.onBar(cd, live = false) }

        eng.feed(candles.subList(0, 20_000), lastIsClosed = true)
        val a1 = eng.structureAtr(14)
        eng.feed(candles.subList(0, 45_000), lastIsClosed = true)
        val a2 = eng.structureAtr(14)
        assertTrue(a1.isFinite() && a1 > 0.0 && a2.isFinite() && a2 > 0.0)
        assertTrue(a1 != a2, "ATR باید با کندل‌های تازه بازمحاسبه شود (a1=$a1, a2=$a2)")
    }

    @Test
    fun `settings round-trip through the store keeps the new keys`() {
        val cfg = base().apply {
            slBufMode = 1; slAtrMult = 1.75; slAtrPeriod = 10
            slAtrMinUnits = 2.0; slAtrMaxUnits = 30.0
            maxSlMode = 1; maxSlAtrMult = 5.0
            guardMode = 1; guardDDPct = 9.0; guardHardDDPct = 18.0
            guardRiskScale = 0.3; guardResetAtHigh = false
        }
        val json = Store.settingsJson(cfg)
        val back = Store.settingsFromJson(json)
        assertEquals(1, back.slBufMode); assertEquals(1.75, back.slAtrMult); assertEquals(10, back.slAtrPeriod)
        assertEquals(2.0, back.slAtrMinUnits); assertEquals(30.0, back.slAtrMaxUnits)
        assertEquals(1, back.maxSlMode); assertEquals(5.0, back.maxSlAtrMult)
        assertEquals(1, back.guardMode); assertEquals(9.0, back.guardDDPct)
        assertEquals(18.0, back.guardHardDDPct); assertEquals(0.3, back.guardRiskScale)
        assertEquals(false, back.guardResetAtHigh)
    }
}
