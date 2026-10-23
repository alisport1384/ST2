package com.alisport.goldpin.data

import android.content.Context
import android.net.Uri
import com.alisport.goldpin.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

// ═══════════════════════════════════════════════════════════════════════════════
//  ذخیره/بازیابی روی حافظهٔ دستگاه
//    ۱) خودکار: پوشهٔ اختصاصی اپ (روی «حافظه» و قابل برداشت با کابل)
//    ۲) دستی : انتخاب محل توسط کاربر (SAF) — هر جا که بخواهد
//  فایل ذخیره‌شده شامل کل وضعیت است: تنظیمات، کندل‌ها، باکس‌های ناحیه (حتی
//  آن‌هایی که پاک شده‌اند)، سفارش‌ها، معاملات و موجودی — بازگشت دقیق و بی‌کم‌وکاست.
// ═══════════════════════════════════════════════════════════════════════════════
object Storage {

    const val FILE_NAME = "goldpin_state.json.gz"
    /** نشست بک‌تست داخلی — جدا از فایل زنده تا هیچ‌وقت با فید شبکه قاطی نشود (طرح B). */
    const val INTERNAL_FILE_NAME = "goldpin_backtest.json.gz"
    /** فایل دادهٔ یک‌سالهٔ M1 در حافظهٔ اپ (از دارایی داخل APK استخراج می‌شود). */
    const val DATA_FILE_NAME = "goldpin_xauusd_m1_1y.csv.gz"
    /**
     * دارایی **باندل‌شدهٔ** دادهٔ یک‌ساله — همان فایل، داخل خود APK.
     *
     * پیش‌تر این داده فقط با دانلود از GitHub Release به‌دست می‌آمد؛ حالا کل یک سال
     * (۳۵۱٬۸۹۴ کندل M1 با حجم) همراه اپ نصب می‌شود و دانلود اختیاری است.
     */
    const val BUNDLED_DATA_ASSET = "goldpin_xauusd_m1_1y.csv.gz"

    fun dir(ctx: Context): File {
        val ext = ctx.getExternalFilesDir(null)
        return ext ?: ctx.filesDir
    }

    fun autoFile(ctx: Context): File = File(dir(ctx), FILE_NAME)

    fun internalFile(ctx: Context): File = File(dir(ctx), INTERNAL_FILE_NAME)

    fun dataFile(ctx: Context): File = File(dir(ctx), DATA_FILE_NAME)

    /** فایل موقت دانلود داده؛ فقط بعد از تأیید هش به نام نهایی تغییر نام می‌دهد. */
    fun dataPartFile(ctx: Context): File = File(dir(ctx), DATA_FILE_NAME + ".part")

    // ── دادهٔ یک‌سالهٔ باندل‌شدهٔ داخل APK ──────────────────────────────────────
    // همهٔ مسیرهای بک‌تست همان فایل `dataFile(ctx)` را می‌خوانند؛ پس کافی است دارایی
    // یک‌بار (و فقط وقتی نیست یا ناقص است) کنار همان نام استخراج شود. با این کار نه
    // موتور، نه کارگزار و نه فایل نشست تغییری می‌بینند.

    /** آیا دارایی یک‌ساله داخل این APK بسته‌بندی شده است؟ */
    fun bundledDataAvailable(ctx: Context): Boolean = try {
        ctx.assets.open(BUNDLED_DATA_ASSET).use { it.read() >= 0 }
        true
    } catch (e: Exception) {
        false
    }

    /**
     * اندازهٔ دقیق دارایی باندل‌شده (برای تشخیص فایل استخراج‌شدهٔ ناقص).
     *
     * `openFd` فقط وقتی کار می‌کند که دارایی در APK فشرده نشده باشد (`noCompress += "gz"`
     * در build.gradle). اگر نشد، ‏`-1` برمی‌گردد و بررسی اندازه ساده‌تر می‌شود.
     */
    fun bundledDataAssetBytes(ctx: Context): Long = try {
        ctx.assets.openFd(BUNDLED_DATA_ASSET).use { it.length }
    } catch (e: Exception) {
        -1L
    }

    /**
     * تضمین وجود فایل دادهٔ یک‌ساله: اگر نیست یا اندازه‌اش با دارایی نمی‌خواند، از داخل
     * APK استخراج می‌شود. خروجی همیشه همان [dataFile] است ⇒ بقیهٔ کد دست‌نخورده می‌ماند.
     *
     * نوشتن اتمیک است (اول فایل موقت، بعد rename) تا قطع شدن اپ وسط کار، فایل سالم را
     * خراب نکند.
     */
    fun ensureBundledData(ctx: Context): File {
        val dest = dataFile(ctx)
        val want = bundledDataAssetBytes(ctx)
        if (dest.exists() && dest.length() > 1_000_000 && (want < 0L || dest.length() == want)) return dest
        val t0 = System.currentTimeMillis()
        val d = dir(ctx)
        if (!d.exists()) d.mkdirs()
        val tmp = File(d, DATA_FILE_NAME + ".asset")
        try {
            ctx.assets.open(BUNDLED_DATA_ASSET).use { ins ->
                java.io.BufferedOutputStream(tmp.outputStream(), 1 shl 16).use { out -> ins.copyTo(out) }
            }
            if (want > 0L && tmp.length() != want) {
                throw FeedException("استخراج دادهٔ باندل‌شده ناتمام ماند (بایت=${tmp.length()} انتظار=${want})")
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        Log.i(Log.CAT_STORE, "دادهٔ یک‌ساله از داخل APK استخراج شد",
            "مسیر=${dest.absolutePath} بایت=${dest.length()} مدت=${System.currentTimeMillis() - t0}ms")
        return dest
    }

    /**
     * نوشتن اتمیک وضعیت: ابتدا در فایل موقت، سپس جای‌گزینی با فایل اصلی.
     * اگر اپ وسط ذخیره کشته شود، فایل سالم قبلی خراب نمی‌شود.
     */
    fun writeText(file: File, json: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        GZIPOutputStream(tmp.outputStream().buffered()).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            // اگر جای‌گزینی ممکن نشد، مستقیم می‌نویسیم
            GZIPOutputStream(file.outputStream().buffered()).use { it.write(json.toByteArray(Charsets.UTF_8)) }
            tmp.delete()
        }
        Log.d(Log.CAT_STORE, "فایل وضعیت نوشته شد", "مسیر=${file.absolutePath} بایت=${file.length()}")
    }

    fun readText(file: File): String {
        if (!file.exists()) throw FeedException("فایل ذخیره وجود ندارد")
        val t0 = System.currentTimeMillis()
        val txt = try {
            readStream(file.inputStream(), gz = true)
        } catch (e: Exception) {
            // فایل نیمه‌نوشته یا خراب (مثلاً اپ وسط ذخیره بسته شده) → اگر فایل سالم قبلی هست از آن بخوان
            Log.w(Log.CAT_STORE, "فایل ذخیرهٔ اصلی خوانده نشد؛ تلاش برای فایل پشتیبان", e.message ?: "")
            val bak = File(file.parentFile, file.name + ".bak")
            if (bak.exists()) {
                readStream(bak.inputStream(), gz = true)
            } else {
                throw FeedException("فایل ذخیرهٔ وضعیت خراب یا ناتمام است (لطفاً دوباره ذخیره کنید)")
            }
        }
        Log.d(Log.CAT_STORE, "فایل وضعیت خوانده شد", "مسیر=${file.absolutePath} بایت=${file.length()} مدت=${System.currentTimeMillis() - t0}ms")
        return txt
    }

    fun writeUri(ctx: Context, uri: Uri, json: String) {
        val os: OutputStream = ctx.contentResolver.openOutputStream(uri, "wt")
            ?: throw FeedException("نوشتن در مسیر انتخابی ممکن نشد")
        GZIPOutputStream(os.buffered()).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        Log.i(Log.CAT_STORE, "ذخیره در مسیر انتخابی", "uri=$uri بایت=${json.length}")
    }

    fun readUri(ctx: Context, uri: Uri): String {
        val ins: InputStream = ctx.contentResolver.openInputStream(uri)
            ?: throw FeedException("خواندن از مسیر انتخابی ممکن نشد")
        val txt = readStream(ins, gz = true)
        Log.i(Log.CAT_STORE, "خواندن از مسیر انتخابی", "uri=$uri بایت=${txt.length}")
        return txt
    }

    /** خواندن با تشخیص خودکار gzip */
    fun readStream(ins: InputStream, gz: Boolean): String {
        val buffered = ins.buffered(1 shl 16)
        buffered.mark(4)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        val isGz = (b0 == 0x1f && b1 == 0x8b) || gz
        val stream = if (isGz) GZIPInputStream(buffered) else buffered
        return stream.readBytes().toString(Charsets.UTF_8)
    }

    /** نوشتن یک متن ساده (گزارش CSV) */
    fun writeReport(ctx: Context, uri: Uri, text: String) {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { os ->
            os.write(text.toByteArray(Charsets.UTF_8))
        } ?: throw FeedException("نوشتن گزارش ممکن نشد")
        Log.i(Log.CAT_STORE, "گزارش نوشته شد", "uri=$uri بایت=${text.length}")
    }
}
