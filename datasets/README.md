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

## دیتاست‌های جفت‌ارز (FX) — ۱۲ جفت، تگ [`data-fx-v1`](https://github.com/alisport1384/ST2/releases/tag/data-fx-v1)

هر بسته یک ZIP است و ۱۲ فایل `<PAIR>.csv` با سرستون `t,o,h,l,c,v` دارد
(`t` = ثانیهٔ UTC، `v` برای فارکس صفر است چون فید رایگان حجم نمی‌دهد).

| بازه | تایم‌فریم | فایل | کندل هر جفت | بازهٔ زمانی | اندازه | SHA-256 |
|---|---|---|---:|---|---:|---|
| ۱ سال | ساعتی | [`goldpin_fx_1y_h1.zip`](https://github.com/alisport1384/ST2/releases/download/data-fx-v1/goldpin_fx_1y_h1.zip) | 6,080 | 2025-10-28 → 2026-10-28 | 1,407,493 بایت | `02eae0e83cb527267d01b63719cb74641193e83af63bc3ab7d8386bb4fb9ce06` |
| ۳ سال | ساعتی | [`goldpin_fx_3y_h1.zip`](https://github.com/alisport1384/ST2/releases/download/data-fx-v1/goldpin_fx_3y_h1.zip) | 17,194 | 2024-01-11 → 2026-10-28 | 4,070,241 بایت | `cdc1753a9a61aa81f296fb61d5cd6905bc77a7fee22e017648a67245a0165169` |
| ۱۰ سال | روزانه | [`goldpin_fx_10y_d1.zip`](https://github.com/alisport1384/ST2/releases/download/data-fx-v1/goldpin_fx_10y_d1.zip) | 2,600 | 2016-10-31 → 2026-10-28 | 852,492 بایت | `b21511be10d2dcd1742fa3470a6b634ee82a74783dee0f28935e5064a8b8cc37` |
| ۲۰ سال | روزانه | [`goldpin_fx_20y_d1.zip`](https://github.com/alisport1384/ST2/releases/download/data-fx-v1/goldpin_fx_20y_d1.zip) | 5,183 | 2006-11-02 → 2026-10-28 | 1,627,300 بایت | `769bcc2f3349d72350589fb5d061ce7aa866aa828660549f9c282ef547871b6f` |

این چهار بسته **همان دادهای** هستند که آزمون walk-forward ۲۰ ساله و اسکن ۱۲ جفت با آن اجرا شد
(اعداد در [`docs/GOZARESH-FX-ANALYZE-v1.5.05.md`](GOZARESH-FX-ANALYZE-v1.5.05.md)). روش بک‌تست:

```bash
unzip goldpin_fx_20y_d1.zip -d fx20
java -cp cli.jar com.alisport.goldpin.cli.OptimizeKt --fxdir fx20 --fxwf          # داوری بیرون‌نمونه
java -cp cli.jar com.alisport.goldpin.cli.OptimizeKt --fxdir fx20 --fxsweep --fxtf 86400   # جست‌وجوی پارامتر
```

منبع: Yahoo Finance (`{PAIR}=X`، `interval=1h|1d`). کندل‌های ساعتی فقط حدود ۲٫۸ سال عقب‌تر می‌روند،
پس برش‌های ۱۰ و ۲۰ ساله ناچار **روزانه**‌اند. مانیفست کامل:
[`docs/fx-1.5.05/dataset-manifest.json`](docs/fx-1.5.05/dataset-manifest.json) (این فایل نسبی در مخزن `datasets/` است).
