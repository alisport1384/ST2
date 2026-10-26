package com.alisport.goldpin.core

import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * آزمون نگهبانِ ده‌سالهٔ تنظیم ارسالی — فقط با `GOLD_TEST_10Y=1` اجرا می‌شود.
 *
 * چرا نگهبان جدا: اجرای کامل روی ۳٬۴۰۲٬۱۲۶ کندل حدود ده دقیقه طول می‌کشد و نباید
 * suite عادی را کند کند؛ ولی عددی که به کاربر قول داده‌ایم روی همین دهه است، پس
 * باید جایی قفل شده باشد، نه فقط در CSVهای `sweep/`. اجرا:
 *
 * ```
 * GOLD_TEST_10Y=1 SWEEP_HEAP=900m ./gradlew :engine:test --tests "*TunedTenYearTest*"
 * ```
 *
 * عددهای مرجع (`sweep/sweep_phase26_10y.csv`، استراتژی ۱ · Mixed · کارمزد ۰٫۷):
 *   تنظیم نسخهٔ ۳ (بافر ۱۸۰۰ · N=۸۰): ۲٬۴۱۰ معامله · انتظار ‎−۰٫۰۰۶R‎ · خالص ‎−۴۳۹$‎ ·
 *   ضریب سود ۰٫۹۹۷ · افت ‎۱۲٬۳۹۷$ — و روی پنجرهٔ نگه‌داشته‌شدهٔ ۲۰۲۳–۲۰۲۶: ‎+۰٫۰۳۳R‎ ·
 *   ‎+۸٬۰۴۶$‎ (`sweep_phase25_10y_2023-2027.csv`).
 *   نسخهٔ ۱ (بافر ۲۰۰) روی همین دهه ‎−۲۰٬۵۸۷$‎ با ضریب سود ۰٫۷۹۵ داد و پیش‌فرض سند ۷
 *   صفر سفارش. این آزمون قفل می‌کند که به آن وضعیت برنگردیم.
 */
class TunedTenYearTest {

    private companion object {
        fun dataFile(): File? = listOf(
            "datasets/goldpin_xauusd_m1_10y.csv.gz",
            "../datasets/goldpin_xauusd_m1_10y.csv.gz",
            "/home/user/ST2new/datasets/goldpin_xauusd_m1_10y.csv.gz"
        ).map(::File).firstOrNull { it.exists() }

        fun loadCandles(f: File): List<Candle> {
            val out = ArrayList<Candle>(3_500_000)
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
    fun `shipped Tuned v3 stays near breakeven on the full ten years`() {
        assumeTrue(System.getenv("GOLD_TEST_10Y") == "1", "فقط با GOLD_TEST_10Y=1 اجرا می‌شود")
        val f = dataFile()
        assumeTrue(f != null, "فایل دادهٔ ده‌ساله در دسترس نیست")
        val candles = loadCandles(f!!)
        assertTrue(candles.size > 3_000_000, "دادهٔ ده‌ساله باید کامل باشد: ${candles.size}")

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
            "SHIPPED-10Y: trades=${s.trades} win%=${"%.2f".format(winRate)} avgR=${"%.4f".format(s.avgR)} " +
                "net=${"%.0f".format(s.net)} pf=${"%.3f".format(s.profitFactor)} maxDD=${"%.0f".format(s.maxDD)}"
        )

        // حجم معامله در همان مرتبهٔ اندازه‌گیری‌شده بماند (نه صفرِ سند ۷، نه انفجار).
        assertTrue(s.trades in 2_200..3_200, "تعداد معامله بیرون از بازهٔ مرجع است: ${s.trades}")
        // کل دهه تقریباً سربه‌سر باشد؛ نسخهٔ ۱ همین‌جا ‎−۰٫۲۳۴R‎ و نسخهٔ ۲ ‎−۰٫۰۱۳R‎ بود.
        assertTrue(s.avgR > -0.04, "انتظار ده‌ساله نباید به محدودهٔ نسخهٔ ۱ برگردد: avgR=${s.avgR}")
        assertTrue(s.net > -4_000.0, "زیان ده‌ساله نباید به محدودهٔ نسخهٔ ۱ (‎−۲۰٬۵۸۷$‎) برگردد: net=${s.net}")
        assertTrue(s.profitFactor > 0.94, "ضریب سود ده‌ساله: pf=${s.profitFactor}")
        assertTrue(s.maxDD < 14_000.0, "افت سرمایهٔ ده‌ساله: ${s.maxDD}")
    }
}
