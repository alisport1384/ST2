package com.alisport.goldpin.data

import android.content.Context
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
 * این آزمون همان مسیری را اجرا می‌کند که اپ روی گوشی می‌رود: دارایی → استخراج در حافظهٔ
 * اپ → همان `dataFile(ctx)` که موتور می‌خواند. یعنی «بدون دانلود» واقعاً کار می‌کند.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StorageBundledDataTest {

    private companion object {
        const val EXPECT_BYTES = 5_128_001L
        const val EXPECT_SHA256 = "15fffd94b9c1970080d6012ff2aa3ae307d18a872381c677e44d16b5fd08db44"
    }

    private fun ctx(): Context = RuntimeEnvironment.getApplication()

    @Test
    fun oneYearDataIsBundledInApkAndExtractedByteForByte() {
        val c = ctx()
        assertTrue("دارایی یک‌ساله باید داخل APK باشد", Storage.bundledDataAvailable(c))
        assertEquals(Storage.DATA_FILE_NAME, Storage.BUNDLED_DATA_ASSET)

        val f = Storage.ensureBundledData(c)
        assertTrue("فایل داده باید ساخته شود", f.exists())
        assertEquals(Storage.dataFile(c).absolutePath, f.absolutePath)
        assertEquals(EXPECT_BYTES, f.length())
        assertEquals(EXPECT_SHA256, Feed.sha256(f))
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
        // سناریوی واقعی: «حذف داده» یا فایل نیمه‌نوشته
        f.writeBytes(ByteArray(4096))
        assertTrue(f.length() < EXPECT_BYTES)

        val again = Storage.ensureBundledData(c)
        assertEquals(EXPECT_BYTES, again.length())
        assertEquals(EXPECT_SHA256, Feed.sha256(again))
        // فایل موقت استخراج هم نباید روی حافظه بماند
        val leftovers = f.parentFile?.listFiles { file -> file.name.endsWith(".asset") }
        assertTrue("فایل موقت .asset باید پاک شود", (leftovers?.size ?: 0) == 0)
        assertNotEquals(0L, again.length())
    }
}
