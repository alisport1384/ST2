package com.alisport.goldpin.core

import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * آزمون نگهبانِ بیست‌سالهٔ تنظیم ارسالی — فقط با `GOLD_TEST_20Y=1` اجرا می‌شود.
 *
 * اجرای کامل روی ۶٬۵۶۳٬۷۲۳ کندل ده‌ها دقیقه طول می‌کشد و نباید suite عادی را کند
 * کند؛ ولی عدد بیست‌ساله همان عددی است که در برچسب‌های «تطبیق» به کاربر قول داده
 * شده، پس باید جایی قفل شده باشد، نه فقط در CSVهای `sweep/`. اجرا:
 *
 * ```
 * GOLD_TEST_20Y=1 SWEEP_HEAP=900m ./gradlew :engine:test --tests "*TunedTwentyYearTest*"
 * ```
 *
 * عددهای مرجع (`sweep/sweep_phase24_20y.csv`، استراتژی ۱ · Mixed · کارمزد ۰٫۷):
 *   تنظیم نسخهٔ ۳ (بافر ۱۸۰۰ · N=۸۰): ۴٬۵۲۴ معامله · نرخ برد ۴۹٫۰٪ · انتظار
 *   ‎−۰٫۰۲۰R‎ · خالص ‎−۱۴٬۷۱۵$‎ · ضریب سود ۰٫۹۳۷ · افت ‎۲۶٬۶۸۲$‎.
 *   نسخهٔ ۲ (بافر ۹۰۰) روی همین بازه ‎−۰٫۰۳۶R‎ و ‎−۱۵٬۵۶۷$‎ بود و پیش‌فرض سند ۷
 *   صفر سفارش. این آزمون قفل می‌کند که به آن وضعیت برنگردیم، و صادقانه نگه می‌دارد
 *   که عدد بیست‌ساله هنوز منفی است — برنده‌بودن نسخهٔ ۳ «کم‌زیان‌ترین بودن» است.
 */
class TunedTwentyYearTest {

    private companion object {
        fun dataFile(): File? = listOf(
            "datasets/goldpin_xauusd_m1_20y.csv.gz",
            "../datasets/goldpin_xauusd_m1_20y.csv.gz",
            "/home/user/ST2new/datasets/goldpin_xauusd_m1_20y.csv.gz"
        ).map(::File).firstOrNull { it.exists() }

        fun loadCandles(f: File): List<Candle> {
            val out = ArrayList<Candle>(6_600_000)
            GZIPInputStream(f.inputStream().buffered(1 shl 16)).bufferedReader().useLines { lines ->
                var bi = 0
                for (ln in lines) {
                    val p = ln.split(',')
                    if (p.size < 6) continue
                    val t = p[0].trim().toLongOrNull() ?: continue
                    out.add(Candle(bi++, t, p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble()))
                }
            }
            return out
        }
    }

    @Test
    fun `shipped Tuned v3 is the least-bad measured setting on the full twenty years`() {
        assumeTrue(System.getenv("GOLD_TEST_20Y") == "1", "فقط با GOLD_TEST_20Y=1 اجرا می‌شود")
        val f = dataFile()
        assumeTrue(f != null, "فایل دادهٔ بیست‌ساله در دسترس نیست")
        val candles = loadCandles(f!!)
        assertTrue(candles.size > 6_000_000, "دادهٔ بیست‌ساله باید کامل باشد: ${candles.size}")

        val mode = TF_MODES.first { it.idx == 1 }
        val cfg = Settings().apply {
            tfS = mode.s; tfM = mode.m; tf1 = mode.t1; tf2 = mode.t2
            mmMode = MoneyMode.MIXED
            minLotAuto = false
            minLotManual = 10.0
            initialEquity = 100_000.0
            Tuned.apply(this)
        }
        val eng = Engine(cfg)
        val broker = PaperBroker(cfg)
        eng.broker = broker
        broker.engine = eng
        eng.barCommitHook = { cd -> broker.onBar(cd, live = false) }
        eng.runAll(candles)

        val s = Reports.summary(broker.trades)
        val winRate = if (s.trades > 0) s.wins * 100.0 / s.trades else 0.0
        println(
            "SHIPPED-20Y: trades=${s.trades} win%=${"%.2f".format(winRate)} avgR=${"%.4f".format(s.avgR)} " +
                "net=${"%.0f".format(s.net)} pf=${"%.3f".format(s.profitFactor)} maxDD=${"%.0f".format(s.maxDD)}"
        )

        assertTrue(s.trades in 3_800..5_400, "تعداد معامله بیرون از بازهٔ مرجع است: ${s.trades}")
        // نسخهٔ ۳ باید از نسخهٔ ۲ (‎−۰٫۰۳۶R‎) بهتر بماند و به محدودهٔ نسخهٔ ۱ برنگردد.
        assertTrue(s.avgR > -0.032, "انتظار بیست‌ساله نباید از نسخهٔ ۲ بدتر شود: avgR=${s.avgR}")
        assertTrue(s.net > -22_000.0, "زیان بیست‌ساله بیرون از بازهٔ مرجع است: net=${s.net}")
        assertTrue(s.profitFactor > 0.88, "ضریب سود بیست‌ساله: pf=${s.profitFactor}")
        assertTrue(s.maxDD < 34_000.0, "افت سرمایهٔ بیست‌ساله: ${s.maxDD}")
    }
}
