package com.alisport.goldpin.data

import android.content.Context
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * دادهٔ یک‌سالهٔ بک‌تست حالا **داخل خود APK** بسته‌بندی شده است.
 *
 * این آزمون همان مسیری را اجرا می‌کند که اپ روی گوشی می‌رود:
 * دارایی داخل APK → استخراج در حافظهٔ اپ → همان `dataFile(ctx)` که موتور می‌خواند →
 * پارسر CSV. یعنی «بک‌تست داخلی بدون دانلود» واقعاً کار می‌کند.
 *
 * آزمون عمداً به نام/قالب دارایی وابسته نیست: بسته‌بندی اندروید دارایی `.gz` را باز می‌کند
 * و پسوندش را برمی‌دارد، پس مقایسه با **محتوای همان دارایی** انجام می‌شود نه با یک عدد ثابت
 * (هش و اندازهٔ خود دیتاست را `BundledOneYearDataTest` در ماژول موتور قفل می‌کند).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StorageBundledDataTest {

    private companion object {
        /** ۲۰۲۵-۰۸-۲۱T00:00:00Z */
        const val FIRST_MS = 1_755_734_400_000L

        /** ۲۰۲۶-۰۸-۲۰T23:59:00Z */
        const val LAST_MS = 1_787_270_340_000L
        const val BARS = 351_894
    }

    private fun ctx(): Context = RuntimeEnvironment.getApplication()

    private fun sha256Stream(ins: java.io.InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        ins.buffered(1 shl 16).use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = s.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        val sb = StringBuilder()
        for (b in md.digest()) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    @Test
    fun oneYearDataIsBundledInApkAndExtractedToTheSessionDataFile() {
        val c = ctx()
        assertTrue("دارایی یک‌ساله باید داخل APK باشد", Storage.bundledDataAvailable(c))

        val assetSha = Storage.openBundledDataAsset(c).use { sha256Stream(it) }
        val f = Storage.ensureBundledData(c)

        assertTrue("فایل داده باید ساخته شود", f.exists())
        assertEquals("باید همان فایل دادهٔ نشست داخلی باشد", Storage.dataFile(c).absolutePath, f.absolutePath)
        assertTrue("فایل داده باید بیش از یک مگابایت باشد", f.length() > 1_000_000)
        assertEquals("محتوای استخراج‌شده باید بایت‌به‌بایت دارایی باشد", assetSha, Feed.sha256(f))
    }

    @Test
    fun extractionRunsOnceAndIsNeverRepeated() {
        val c = ctx()
        val first = Storage.ensureBundledData(c)
        val stamp = first.lastModified()
        val second = Storage.ensureBundledData(c)
        assertEquals(first.absolutePath, second.absolutePath)
        assertEquals("فایل سالم نباید دوباره نوشته شود", stamp, second.lastModified())
    }

    @Test
    fun truncatedOrMissingFileIsRestoredFromTheApk() {
        val c = ctx()
        val f = Storage.ensureBundledData(c)
        // سناریوی واقعی: فایل نیمه‌نوشته یا دست‌کاری‌شده روی حافظهٔ اپ
        f.writeBytes(ByteArray(4096))
        val broken = f.length()

        val again = Storage.ensureBundledData(c)
        assertNotEquals("فایل ناقص نباید همان‌طور بماند", broken, again.length())
        val assetSha = Storage.openBundledDataAsset(c).use { sha256Stream(it) }
        assertEquals(assetSha, Feed.sha256(again))

        val leftovers = f.parentFile?.listFiles { file -> file.name.endsWith(".asset") }
        assertEquals("فایل موقت .asset باید پاک شود", 0, leftovers?.size ?: 0)
    }

    @Test
    fun extractedDataParsesToTheFullYearOfVolumeBearingCandles() {
        val f = Storage.ensureBundledData(ctx())
        val candles = f.inputStream().use { Feed.parseCsvStream(it) }

        assertEquals(BARS, candles.size)
        assertEquals(FIRST_MS, candles.first().t)
        assertEquals(LAST_MS, candles.last().t)
        assertTrue("همهٔ کندل‌ها باید حجم واقعی داشته باشند", candles.all { it.v > 0.0 })
    }
}
