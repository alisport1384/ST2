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
     *
     * ⚠ دو نام، چون بسته‌بندی اندروید (AGP) دارایی‌های `.gz` را **باز** می‌کند و پسوندشان را
     * حذف می‌کند: در مخزن `goldpin_xauusd_m1_1y.csv.gz` است و داخل APK
     * `assets/goldpin_xauusd_m1_1y.csv` (محتوای CSV باز، ۲۱٬۷۶۵٬۳۶۶ بایت). در APK واقعی
     * v1.4.41 با `unzip -l` دیده و تأیید شد. دقیقاً همان کاری که `Feed.openSampleAsset`
     * برای نمونهٔ کوچک می‌کند؛ هر دو نام امتحان می‌شوند.
     */
    val BUNDLED_DATA_ASSETS = listOf("goldpin_xauusd_m1_1y.csv.gz", "goldpin_xauusd_m1_1y.csv")

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
    //
    // نکتهٔ محتوا: پارسر اپ (`Feed.parseCsvStream`) gzip را از روی امضای بایت می‌شناسد،
    // پس فرقی نمی‌کند فایل روی گوشی gzip باشد (نسخهٔ دانلودی از سرور) یا CSV باز
    // (نسخهٔ استخراج‌شده از APK) — هر دو درست خوانده می‌شوند.

    /** باز کردن دارایی دادهٔ یک‌ساله با هر نامی که بسته‌بندی برایش انتخاب کرده است. */
    fun openBundledDataAsset(ctx: Context): InputStream {
        var last: Exception? = null
        for (n in BUNDLED_DATA_ASSETS) {
            try {
                return ctx.assets.open(n)
            } catch (e: Exception) {
                last = e
            }
        }
        throw FeedException("دارایی دادهٔ یک‌ساله داخل APK پیدا نشد (${last?.message ?: "بدون جزئیات"})")
    }

    /** آیا دارایی یک‌ساله داخل این APK بسته‌بندی شده است؟ */
    fun bundledDataAvailable(ctx: Context): Boolean = try {
        openBundledDataAsset(ctx).use { it.read() >= 0 }
        true
    } catch (e: Exception) {
        false
    }

    /**
     * تضمین وجود فایل دادهٔ یک‌ساله: اگر نیست یا ناقص است، از داخل APK استخراج می‌شود.
     * خروجی همیشه همان [dataFile] است ⇒ بقیهٔ کد دست‌نخورده می‌ماند.
     *
     * نوشتن اتمیک است (اول فایل موقت، بعد rename): اگر اپ وسط استخراج کشته شود، فایل
     * ناقص هرگز جای فایل سالم را نمی‌گیرد و دفعهٔ بعد از نو استخراج می‌شود.
     */
    fun ensureBundledData(ctx: Context): File {
        val dest = dataFile(ctx)
        if (dest.exists() && dest.length() > 1_000_000) return dest
        val t0 = System.currentTimeMillis()
        val d = dir(ctx)
        if (!d.exists()) d.mkdirs()
        val tmp = File(d, DATA_FILE_NAME + ".asset")
        try {
            openBundledDataAsset(ctx).use { ins ->
                java.io.BufferedOutputStream(tmp.outputStream(), 1 shl 16).use { out -> ins.copyTo(out) }
            }
            if (tmp.length() <= 1_000_000) {
                throw FeedException("استخراج دادهٔ باندل‌شده ناتمام ماند (بایت=${tmp.length()})")
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
