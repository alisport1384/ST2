# دیتاست‌های بک‌تست طلا — XAUUSD، کندل M1

داده‌ها **نه داخل APK و نه داخل مخزن** نگهداری می‌شوند؛ هر پنج برش به‌صورت asset در بخش
[**Releases**](https://github.com/alisport1384/ST2/releases/tag/data-v1) با تگ `data-v1` منتشر شده‌اند و اپ هر بازه را پس از انتخاب کاربر جداگانه از همان‌جا دانلود می‌کند. مزیت: کلون مخزن چند مگابایت
است، نه چند صد مگابایت، و دانلود داخل اپ از CDN ریلیز سریع‌تر و پایدارتر است. دیتاست و نتیجهٔ اجرای بک‌تست هر بازه در فایل مستقل ذخیره می‌شود.

| بازه | فایل | کندل | اندازهٔ فشرده | شروع UTC | پایان UTC | SHA-256 |
|---|---|---:|---:|---|---|---|
| ۱ سال | [`goldpin_xauusd_m1_1y.csv.gz`](https://github.com/alisport1384/ST2/releases/download/data-v1/goldpin_xauusd_m1_1y.csv.gz) | 351,894 | 5,128,001 بایت | 2025-08-21 00:00 | 2026-08-20 23:59 | `15fffd94b9c1970080d6012ff2aa3ae307d18a872381c677e44d16b5fd08db44` |
| ۲ سال | [`goldpin_xauusd_m1_2y.csv.gz`](https://github.com/alisport1384/ST2/releases/download/data-v1/goldpin_xauusd_m1_2y.csv.gz) | 701,067 | 9,844,331 بایت | 2024-08-21 00:00 | 2026-08-20 23:59 | `873d4ffc765723abfada8ef55fbf8b9bb9c7432125187395972f51af94496e9f` |
| ۳ سال | [`goldpin_xauusd_m1_3y.csv.gz`](https://github.com/alisport1384/ST2/releases/download/data-v1/goldpin_xauusd_m1_3y.csv.gz) | 1,040,730 | 14,310,975 بایت | 2023-08-21 00:00 | 2026-08-20 23:59 | `56751049149f3df1ed134fdbc5e667ec57655f445edde5820ef9d9fed6697fcc` |
| ۱۰ سال | [`goldpin_xauusd_m1_10y.csv.gz`](https://github.com/alisport1384/ST2/releases/download/data-v1/goldpin_xauusd_m1_10y.csv.gz) | 3,402,126 | 43,314,146 بایت (حدود 41.3 MiB) | 2016-08-21 23:00 | 2026-08-20 23:59 | `c0e5c8d2151605db8d0cf1c75f3f1d6cca0ecc5304bad233f536ffe45d7f7881` |
| ۲۰ سال | [`goldpin_xauusd_m1_20y.csv.gz`](https://github.com/alisport1384/ST2/releases/download/data-v1/goldpin_xauusd_m1_20y.csv.gz) | 6,563,723 | 77,779,089 بایت (حدود 74.2 MiB) | 2006-08-21 00:02 | 2026-08-20 23:59 | `ffbc7c1bd8723728714dfc2f476102f7887be3797d0caf187b622aa533c830c9` |

همهٔ فایل‌ها شامل سرستون و ردیف‌های `timestamp(ms,UTC),open,high,low,close,volume` هستند. حجم، tick volume منبع است. در برش ۲۰ساله کندل بی‌حجم، نامعتبر یا timestamp تکراری وجود ندارد؛ ۱٬۰۷۱ فاصلهٔ بیش از ۲۰ ساعت گزارش شده است. فایل `.meta.json` کنار دیتاست، آمار ساخت را ثبت می‌کند.

## رفتار اپ

- از تنظیمات، بازهٔ ۱، ۲، ۳، ۱۰ یا ۲۰ سال را انتخاب و همان فایل را دانلود کنید؛ اپ اول از
  `ST2/releases/download/data-v1` و در صورت خطا از آینهٔ `ST1` می‌گیرد؛ SHA-256 قبل از تأیید فایل بررسی می‌شود.
- برای اجرای بک‌تست، نشست داخلی را روشن کنید. با تغییر بازه، «اجرای مجدد» دیتاست انتخابی را بارگذاری می‌کند.
- داده و نتیجهٔ هر بازه جدا هستند؛ حذف، دانلود یا اجرای یک بازه به بقیه دست نمی‌زند. هیچ دیتاستی داخل APK نیست.
- برای تاریخچه‌های بلند، کندل‌ها با ستون‌های primitive نگه‌داری می‌شوند؛ زمان‌های دقیقه‌ای هم فشرده می‌شوند تا حافظهٔ کمتری مصرف شود.
- دیتاست ۲۰ساله حدود ۷۴٫۲ MiB فشرده است و ۶٫۵۶ میلیون کندل دارد. پردازش آن ممکن است ده‌ها دقیقه طول بکشد و روی گوشی‌های کم‌حافظه محدودیت داشته باشد.

## منبع و بازتولید

هر پنج برش از [Kaggle: majidmehdipour/xauusd-ohlcv-1m](https://www.kaggle.com/datasets/majidmehdipour/xauusd-ohlcv-1m) ساخته شده‌اند (مجوز MIT؛ فایل منبع 6,886,934 ردیف دارد). بازهٔ ۲۰ساله از `2006-08-21` تا `2026-08-21` (حد بالا غیرشامل) انتخاب شده؛ دادهٔ منبع در ۲۰۲۶-۰۸-۲۰ پایان می‌یابد. انتهای دیتاست ۲۰ساله با تمام ردیف‌های فایل ۱۰ساله، بایت‌به‌بایت یکسان است.

ساخت برش‌های کوتاه‌تر با `tools/build_sample.py` انجام می‌شود. برای برش‌های بلند از نسخهٔ دسته‌ای سریع `tools/build_long_sample.py` استفاده کنید (نیازمند `pandas` و `numpy`). هر دو ابزار زمان `Europe/Berlin` را با DST به UTC تبدیل، آخرهفتهٔ بستهٔ بازار را حذف، OHLC را بررسی و خروجی gzip قطعی (`mtime=0`) تولید می‌کنند. برای بازسازی ۲۰ سال:

```bash
python3 -m pip install pandas numpy
python3 tools/build_long_sample.py --src /path/XAUUSD_OHLCV_1M.csv \
  --from 2006-08-21 --to 2026-08-21 \
  --out datasets/goldpin_xauusd_m1_20y.csv.gz
```

## دریافت مستقیم (بدون اپ)

هر فایل مستقیماً از Release قابل دانلود است — همین URLهایی که اپ استفاده می‌کند:

```bash
BASE=https://github.com/alisport1384/ST2/releases/download/data-v1
curl -fLO $BASE/goldpin_xauusd_m1_1y.csv.gz
curl -fLO $BASE/goldpin_xauusd_m1_2y.csv.gz
curl -fLO $BASE/goldpin_xauusd_m1_3y.csv.gz
curl -fLO $BASE/goldpin_xauusd_m1_10y.csv.gz
curl -fLO $BASE/goldpin_xauusd_m1_20y.csv.gz
```

| برش | لینک دانلود | آینهٔ دوم |
|---|---|---|
| ۱ سال | [data-v1/1y](https://github.com/alisport1384/ST2/releases/download/data-v1/goldpin_xauusd_m1_1y.csv.gz) | [ST1](https://github.com/alisport1384/ST1/releases/download/data-v1/goldpin_xauusd_m1_1y.csv.gz) |
| ۲ سال | [data-v1/2y](https://github.com/alisport1384/ST2/releases/download/data-v1/goldpin_xauusd_m1_2y.csv.gz) | [ST1](https://github.com/alisport1384/ST1/releases/download/data-v1/goldpin_xauusd_m1_2y.csv.gz) |
| ۳ سال | [data-v1/3y](https://github.com/alisport1384/ST2/releases/download/data-v1/goldpin_xauusd_m1_3y.csv.gz) | [ST1](https://github.com/alisport1384/ST1/releases/download/data-v1/goldpin_xauusd_m1_3y.csv.gz) |
| ۱۰ سال | [data-v1/10y](https://github.com/alisport1384/ST2/releases/download/data-v1/goldpin_xauusd_m1_10y.csv.gz) | [ST1](https://github.com/alisport1384/ST1/releases/download/data-v1/goldpin_xauusd_m1_10y.csv.gz) |
| ۲۰ سال | [data-v1/20y](https://github.com/alisport1384/ST2/releases/download/data-v1/goldpin_xauusd_m1_20y.csv.gz) | [ST1](https://github.com/alisport1384/ST1/releases/download/data-v1/goldpin_xauusd_m1_20y.csv.gz) |

## برای کار روی کد (تست‌ها و هارنس CLI)

تست‌های `engine` و `app` و همچنین `cli/OptimizeKt` دیتاست را از پوشهٔ محلی `datasets/` می‌خوانند.
یک بار اسکریپت زیر را بزنید تا هر پنج فایل دانلود و SHA-256 بررسی شود:

```bash
./tools/fetch_datasets.sh            # همه
./tools/fetch_datasets.sh 1y 10y     # فقط دو برش
```

این پوشه در `.gitignore` است، پس چیزی به مخزن برنمی‌گردد. اگر فایل نباشد، تست‌های وابسته به
داده با `assumeTrue` **skip** می‌شوند (build نمی‌شکند).

---

## دیتاست‌های نفت WTI (USOIL) — ریلیز `data-oil-v1`

| بازه | فایل | کندل | اندازهٔ فشرده | شروع UTC | پایان UTC | SHA-256 |
|---|---|---:|---:|---|---|---|
| ۱ سال | [`goldpin_usoil_m1_1y.csv.gz`](https://github.com/alisport1384/ST2/releases/download/data-oil-v1/goldpin_usoil_m1_1y.csv.gz) | 347,718 | 4,073,006 بایت | 2025-10-23 00:00 | 2026-10-22 04:28 | `616cb8f68ccce3793c529282a86e9500f1e16ea6e54b55376f07e44150bc8590` |
| ۲ سال | [`goldpin_usoil_m1_2y.csv.gz`](https://github.com/alisport1384/ST2/releases/download/data-oil-v1/goldpin_usoil_m1_2y.csv.gz) | 689,491 | 7,545,461 بایت | 2024-10-23 00:00 | 2026-10-22 04:28 | `78e4a8fdbce2fcd4a39a37f01f3e3a22ffb73cb70aa38cb25594c9447f7d6d0a` |
| ۳ سال | [`goldpin_usoil_m1_3y.csv.gz`](https://github.com/alisport1384/ST2/releases/download/data-oil-v1/goldpin_usoil_m1_3y.csv.gz) | 1,033,192 | 11,087,423 بایت | 2023-10-23 00:00 | 2026-10-22 04:28 | `9394e460c705bdcddc2b3a843beb8ff3769fb72e436565bb8cc84521afc6b21b` |
| ۱۰ سال | [`goldpin_usoil_m1_10y.csv.gz`](https://github.com/alisport1384/ST2/releases/download/data-oil-v1/goldpin_usoil_m1_10y.csv.gz) | 3,361,756 | 35,630,197 بایت | 2016-10-23 22:00 | 2026-10-22 04:28 | `7c1f7cb1be6bbb3033be3d6c96555a6cebc21b151e4bda1b2cb8fb0a352465ec` |
| ۲۰ سال | [`goldpin_usoil_m1_20y.csv.gz`](https://github.com/alisport1384/ST2/releases/download/data-oil-v1/goldpin_usoil_m1_20y.csv.gz) | 6,829,560 | 69,076,333 بایت | 2005-01-03 00:00 | 2026-10-22 04:28 | `cee6b304a900e6f0bc2d3a33ee201523c2905714d9ece805aeabfc9028ab6313` |

- منبع خام: مخزن عمومی [`parshant65-lang/WTI-Crude-Oil-1m-2005-2015`](https://github.com/parshant65-lang/WTI-Crude-Oil-1m-2005-2015-Historical-Data) و [`...-2016-2025`](https://github.com/parshant65-lang/WTI-Crude-Oil-1m-2016-2025-Historical-Data) — OHLCV یک‌دقیقه‌ای با حجم تیکی.
- ساخت: ۶٬۷۱۹ فایل روزانه دانلود شد (۰ خطا)، ردیف‌ها بر timestamp مرتب و تکراری‌ها حذف شد؛ ۰ ردیف نامعتبر، ۰ کندل بی‌حجم.
- واحد قیمت: **دلار بر بشکه** (حداقل ۱۱٫۱۷۵ و حداکثر ۱۴۶٫۸۹۳ در کل بازه). ستون‌ها مثل طلا: `timestamp(ms,UTC),open,high,low,close,volume`.
- `.meta.json` هر برش کنار همین فایل در مخزن است.
- اپ این فایل‌ها را فقط وقتی دانلود می‌کند که **نماد نفت** انتخاب شده باشد (اول `ST2`، بعد آینهٔ `ST1`)، و SHA-256 را قبل از پذیرش بررسی می‌کند.
- ⚠ اسپرد/کارمزد پیش‌فرض نفت **۰٫۰۳ دلار بر بشکه** است، نه ۰٫۷ طلا؛ توضیح در [`docs/GOZARESH-OIL-v1.5.00.md`](../docs/GOZARESH-OIL-v1.5.00.md).
