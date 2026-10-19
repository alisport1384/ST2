package com.alisport.goldpin.core

import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * «بستن و باز کردن اپ نباید چیزی از وضعیت را عوض کند»:
 * وضعیت موتور را با [Store.save] ذخیره و با [Store.load] بازیابی می‌کنیم و **همهٔ**
 * فیلدهای موتور و کارگزار را با بازتاب (reflection) و به‌صورت بازگشتی مقایسه می‌کنیم.
 *
 * چرا این تست لازم است: اپ هنگام بالا آمدن، تاریخچه را دوباره پردازش **نمی‌کند**؛
 * فقط [Store.load] + [Engine.copyStateFrom] را اجرا می‌کند. پس هر فیلدی که در
 * save/restore جا بیفتد تا ابد خالی می‌ماند. نمونهٔ واقعی: `t1History`/`t2History`
 * (اسکن LV/HV برای ستاپ‌های نیمه‌کاره)، `times` (زمان هر کندل برای جای مارکر) و
 * `zoneRefH/zoneRefL` (دامنهٔ مرجع پروفایل حجم).
 */
class StoreRoundtripTest {

    private fun loadSample(max: Int): List<Candle> {
        val asset = listOf(
            "app/src/main/assets/sample_xauusd_m1.csv.gz",
            "../app/src/main/assets/sample_xauusd_m1.csv.gz"
        ).map(::File).firstOrNull { it.exists() }
        assumeTrue(asset != null, "فایل نمونهٔ داده در دسترس نیست")
        val out = ArrayList<Candle>(max)
        GZIPInputStream(asset!!.inputStream()).bufferedReader().useLines { lines ->
            for (ln in lines) {
                if (out.size >= max) break
                val p = ln.split(',')
                if (p.size < 6) continue
                val t = p[0].trim().toLongOrNull() ?: continue
                out.add(Candle(out.size, t, p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble()))
            }
        }
        return out
    }

    private class Cmp {
        val diffs = ArrayList<String>()

        /** فیلدهایی که وضعیت استراتژی نیستند (وابستگی اجرایی/کش) و نباید در فایل باشند. */
        private val runtimeOnly = setOf(
            "broker", "engine", "alertSink", "logSink", "barCommitHook", "liveCandle", "cfg"
        )

        fun walk(path: String, a: Any?, b: Any?) {
            if (a === b) return
            if (a == null || b == null) { diffs.add("$path: $a ≠ $b"); return }
            if (a is Number && b is Number) {
                val da = a.toDouble(); val db = b.toDouble()
                val same = (da.isNaN() && db.isNaN()) || da == db
                if (!same) diffs.add("$path: $a ≠ $b")
                return
            }
            if (a is String || a is Boolean || a is Enum<*>) {
                if (a != b) diffs.add("$path: $a ≠ $b"); return
            }
            if (a is List<*> && b is List<*>) {
                if (a.size != b.size) { diffs.add("$path: اندازهٔ لیست ${a.size} ≠ ${b.size}"); return }
                for (i in a.indices) walk("$path[$i]", a[i], b[i])
                return
            }
            if (a is Map<*, *> && b is Map<*, *>) {
                if (a.size != b.size) { diffs.add("$path: اندازهٔ نگاشت ${a.size} ≠ ${b.size}"); return }
                for (k in a.keys) {
                    if (!b.containsKey(k)) { diffs.add("$path: کلید $k در نسخهٔ بازیابی‌شده نیست"); continue }
                    walk("$path[$k]", a[k], b[k])
                }
                return
            }
            if (a is DoubleArray && b is DoubleArray) {
                if (a.size != b.size) { diffs.add("$path: اندازهٔ آرایه"); return }
                for (i in a.indices) {
                    val same = (a[i].isNaN() && b[i].isNaN()) || a[i] == b[i]
                    if (!same) { diffs.add("$path[$i]: ${a[i]} ≠ ${b[i]}"); return }
                }
                return
            }
            if (a.javaClass != b.javaClass) { diffs.add("$path: نوع ${a.javaClass.simpleName} ≠ ${b.javaClass.simpleName}"); return }
            // مقایسهٔ بازگشتی دو نمونهٔ هم‌نوع (data class یا کلاس معمولی)
            for (f in fieldsOf(a.javaClass)) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (f.name in runtimeOnly) continue
                if (f.name.contains("$")) continue
                if (f.type.name.contains("Function")) continue
                val va = read(f, a); val vb = read(f, b)
                walk("$path.${f.name}", va, vb)
            }
        }

        private fun read(f: Field, o: Any): Any? = try { f.isAccessible = true; f.get(o) } catch (_: Throwable) { null }

        private fun fieldsOf(c: Class<*>): List<Field> {
            val out = ArrayList<Field>()
            var k: Class<*>? = c
            while (k != null && k != Any::class.java) { out.addAll(k.declaredFields); k = k.superclass }
            return out
        }
    }

    @Test
    fun `store roundtrip keeps every engine and broker field after a restart`() {
        val candles = loadSample(9000)
        assumeTrue(candles.isNotEmpty(), "دادهٔ نمونه بارگذاری نشد")

        val cfg = Settings().apply {
            val m = TF_MODES.first { it.idx == 1 }
            tfS = m.s; tfM = m.m; tf1 = m.t1; tf2 = m.t2
        }
        val eng = Engine(cfg)
        val broker = PaperBroker(cfg)
        eng.broker = broker
        broker.engine = eng
        eng.barCommitHook = { cd -> broker.onBar(cd, live = false) }
        eng.feed(candles, lastIsClosed = true)

        assertTrue(eng.pins.isNotEmpty() || eng.zones.isNotEmpty(), "موتور در این بازه هیچ پین/ناحیه‌ای نساخت")
        assertTrue(eng.t1History.isNotEmpty() && eng.t2History.isNotEmpty(), "تاریخچهٔ تریگر۱/۲ باید پر باشد")
        assertTrue(eng.setups.isNotEmpty(), "هیچ ستاپی ساخته نشد")

        val json = Store.save(cfg, eng, broker, candles)
        val back = Store.load(json)
        val e2 = back.eng

        val cmp = Cmp()
        cmp.walk("engine", eng, e2)
        cmp.walk("broker", broker, back.broker)
        assertTrue(cmp.diffs.isEmpty(), "این فیلدها در ذخیره/بازیابی یکسان نیستند:\n" + cmp.diffs.take(40).joinToString("\n"))

        assertTrue(eng.pins.size == e2.pins.size, "تعداد کاندید پین: ${eng.pins.size} → ${e2.pins.size}")
        assertTrue(eng.setups.size == e2.setups.size, "تعداد ستاپ: ${eng.setups.size} → ${e2.setups.size}")
        assertTrue(eng.zones.size == e2.zones.size, "تعداد ناحیه: ${eng.zones.size} → ${e2.zones.size}")
        assertTrue(eng.markers.size == e2.markers.size, "تعداد مارکر: ${eng.markers.size} → ${e2.markers.size}")
        assertTrue(eng.t1History.size == e2.t1History.size, "تاریخچهٔ تریگر۱: ${eng.t1History.size} → ${e2.t1History.size}")
        assertTrue(eng.t2History.size == e2.t2History.size, "تاریخچهٔ تریگر۲: ${eng.t2History.size} → ${e2.t2History.size}")

        // ادامهٔ کار باید بدون خطا و بدون تکرار مارکر/پین باشد
        val m0 = e2.markers.size
        e2.feed(candles, lastIsClosed = true)
        assertTrue(e2.markers.size >= m0, "موتور بازیابی‌شده باید بدون خطا ادامه دهد")
    }

    @Test
    fun `copyStateFrom mirrors every strategy field into a fresh engine`() {
        val candles = loadSample(9000)
        assumeTrue(candles.isNotEmpty(), "دادهٔ نمونه بارگذاری نشد")
        val cfg = Settings().apply {
            val m = TF_MODES.first { it.idx == 1 }
            tfS = m.s; tfM = m.m; tf1 = m.t1; tf2 = m.t2
        }
        val eng = Engine(cfg)
        val broker = PaperBroker(cfg)
        eng.broker = broker; broker.engine = eng
        eng.barCommitHook = { cd -> broker.onBar(cd, live = false) }
        eng.feed(candles, lastIsClosed = true)
        assertTrue(eng.setups.isNotEmpty(), "هیچ ستاپی ساخته نشد")

        // همان مسیری که App.kt هنگام بازیابی طی می‌کند: reset + copyStateFrom
        val fresh = Engine(cfg)
        fresh.reset()
        fresh.copyStateFrom(eng)

        val freshBroker = PaperBroker(cfg)
        freshBroker.reset()
        freshBroker.copyStateFrom(broker)

        val cmp = Cmp()
        cmp.walk("engine", eng, fresh)
        cmp.walk("broker", broker, freshBroker)
        assertTrue(cmp.diffs.isEmpty(), "copyStateFrom این فیلدها را کپی نکرد:\n" + cmp.diffs.take(40).joinToString("\n"))
        assertTrue(eng.t1History.size == fresh.t1History.size && eng.t2History.size == fresh.t2History.size,
            "تاریخچهٔ تریگر۱/۲ در copyStateFrom جا افتاده")
    }

    /**
     * آزمون رفتاری «ری‌استارت»: یک اجرای پیوسته با اجرایی که در میانهٔ کار ذخیره/بازیابی شده
     * باید **دقیقاً** یک نتیجه بدهد — همان مارکرها (زمان/شمارهٔ کندل/قیمت/متن)، همان ستاپ‌ها،
     * همان معاملات و همان موجودی. این همان سناریوی واقعی «بستن و باز کردن اپ» است.
     */
    @Test
    fun `a restart in the middle of the run does not change the outcome`() {
        val all = loadSample(12000)
        assumeTrue(all.size >= 8000, "دادهٔ نمونه کافی نیست")
        val split = all.size / 2
        val cfg = Settings().apply {
            val m = TF_MODES.first { it.idx == 1 }
            tfS = m.s; tfM = m.m; tf1 = m.t1; tf2 = m.t2
        }

        fun makeEngine(): Pair<Engine, PaperBroker> {
            val e = Engine(cfg); val b = PaperBroker(cfg)
            e.broker = b; b.engine = e
            e.barCommitHook = { cd -> b.onBar(cd, live = false) }
            return e to b
        }

        // اجرای پیوسته
        val (a, ab) = makeEngine()
        a.feed(all.subList(0, split), lastIsClosed = true)
        val markersAtSplit = a.markers.size
        val tradesAtSplit = ab.trades.size
        val savedJson = Store.save(cfg, a, ab, all.subList(0, split))
        a.feed(all.subList(split, all.size), lastIsClosed = true)

        // اجرای با «بستن و باز کردن اپ» در میانه
        val ld = Store.load(savedJson)
        val (b2, bb2) = makeEngine()
        b2.reset(); b2.copyStateFrom(ld.eng)
        bb2.reset(); bb2.copyStateFrom(ld.broker)
        b2.broker = bb2; bb2.engine = b2
        b2.barCommitHook = { cd -> bb2.onBar(cd, live = false) }
        b2.feed(all.subList(split, all.size), lastIsClosed = true)

        // تست باید واقعاً چیزی را بسنجد: نیمهٔ دوم باید فعالیت داشته باشد
        assertTrue(a.markers.size > markersAtSplit || ab.trades.size > tradesAtSplit,
            "نیمهٔ دوم داده هیچ فعالیتی نداشت؛ آزمون ری‌استارت بی‌معنا می‌شد")

        fun sig(m: List<Marker>) = m.map { "${it.t}/${it.bi}/${it.price}/${it.kind}/${it.text}" }
        val sa = sig(a.markers); val sb = sig(b2.markers)
        val firstDiff = sa.zip(sb).firstOrNull { it.first != it.second }
        assertTrue(sa.size == sb.size, "تعداد مارکر بعد از ری‌استارت فرق کرد: ${sa.size} ≠ ${sb.size}")
        assertTrue(firstDiff == null, "اولین مارکر متفاوت بعد از ری‌استارت: قبل=${firstDiff?.first} بعد=${firstDiff?.second}")
        assertTrue(a.setups.map { it.stage } == b2.setups.map { it.stage },
            "مرحلهٔ ستاپ‌ها بعد از ری‌استارت فرق کرد")
        assertTrue(a.zones.size == b2.zones.size, "تعداد ناحیه‌ها فرق کرد")
        assertTrue(ab.trades.size == bb2.trades.size,
            "تعداد معاملات فرق کرد: ${ab.trades.size} ≠ ${bb2.trades.size}")
        assertTrue(ab.orders.size == bb2.orders.size,
            "تعداد سفارش‌ها فرق کرد: ${ab.orders.size} ≠ ${bb2.orders.size}")
        assertTrue(kotlin.math.abs(ab.balance - bb2.balance) < 1e-6,
            "موجودی بعد از ری‌استارت فرق کرد: ${ab.balance} ≠ ${bb2.balance}")
    }
}
