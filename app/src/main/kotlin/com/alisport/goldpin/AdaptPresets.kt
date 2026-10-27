package com.alisport.goldpin

import com.alisport.goldpin.core.MoneyMode
import com.alisport.goldpin.core.Strategy
import com.alisport.goldpin.core.Tuned

// ═══════════════════════════════════════════════════════════════════════════════
//  پیش‌تنظیم‌های بخش «تطبیق» — هر عدد از جاروب پارامتر روی دادهٔ واقعی **بیست‌ساله**
//  آمده است (۶٬۵۶۳٬۷۲۳ کندل M1 طلا · ۲۰۰۶-۰۸-۲۱ تا ۲۰۲۶-۰۸-۲۰ · کارمزد ۰٫۷).
//  روش: جست‌وجو روی آموزش ۲۰۰۶–۲۰۱۶، اعتبارسنجی روی نگه‌داشته‌شدهٔ ۲۰۱۷–۲۰۲۶،
//  کنترل جدا روی رژیم اخیر ۲۰۲۳–۲۰۲۶ و عددهای زیر روی **کل بیست سال**
//  (گزارش خام: `sweep/sweep_phase2*.csv` در مخزن).
//
//  سنجه‌ها همان‌هایی است که اپ نشان می‌دهد: تعداد معامله، نرخ برد، سود خالص پس از
//  کسر کارمزد واقعی، ضریب سود (PF)، بیشترین افت سرمایه و ماه‌های مثبت (از ۲۴۱ ماه).
//
//  صادقانه: هیچ‌کدام از این ترکیب‌ها روی کل بیست سال سودده نیست؛ لبهٔ این خانواده
//  با کارمزد ۰٫۷ عمدتاً در رژیم پرنوسانِ ۲۰۲۳ به بعد وجود دارد. بهترین‌ها آن‌هایی‌اند
//  که در رژیم اخیر مثبت و در کل بازه کم‌زیان‌ترین‌اند. هرجا ترکیبی منفی یا خطرناک
//  است (از جمله Manage All Lost که حساب را نابود می‌کند)، با همان عدد واقعی‌اش
//  آمده تا کاربر با عدد تصمیم بگیرد، نه با امید.
// ═══════════════════════════════════════════════════════════════════════════════

val ADAPT_PRESETS: List<AppState.AdaptPreset> = listOf(
    // ═══════════════════════════════════════════════════════════════════════════
    //  نسخهٔ ۱٫۴٫۵۲ — «موتور ترکیبی» (پیش‌فرض جدید)
    //
    //  سازوکار: چهار لایهٔ مستقل که هر کدام جدا روی ۲۰ سال آزموده شد
    //    ۱) گیت رژیم نوسان: ورود فقط وقتی ATR%/قیمت ≥ ۰٫۳۳٪ (رژیم آرام: ۰٫۴۰٪)
    //    ۲) گیت ساختار: ارتفاع باکس HV/LV ≥ ۰٫۱۰ × ATR ساختار
    //    ۳) حجم: مرکب سقف‌دار ۱٫۵× (سرمایه فقط تا ۱۵۰٪ پایه در حجم اثر می‌کند)
    //    ۴) پهن‌کردن داده‌محور حد ضرر تا ۱٫۱۵× + تریل ۱٫۱۰× در رژیم پرنوسان
    //  اندازه‌گیری روی همان ۲٫۰۳۱ معاملهٔ بیست‌ساله (کارمزد ۰٫۷):
    //      پایهٔ ۱٫۴٫۵۱: ‎+۱۰٬۵۸۳$‎ · ‎+۰٫۰۳۱۱R‎ · PF ۱٫۰۸۴ · افت ۷٬۸۸۴$ (۷٫۷۹٪)
    //      ترکیبی:       ‎+۱۹٬۴۷۷$‎ · ‎+۰٫۱۳۹۷R‎ · PF ۱٫۳۲۸ · افت ۳٬۵۳۴$ (۳٫۴۳٪)
    //  صادقانه: ۸۷٪ این سود از ۲۰ ماه آخر (۲۰۲۵–۲۰۲۶/۰۸) است و دورهٔ ۲۰۱۱–۲۰۱۵ هنوز
    //  منفی است (‎−۱٬۹۷۷$‎). لایهٔ «کم‌کردن حجم در استرس» آزموده و **حذف** شد چون
    //  بازده/افت را بدتر می‌کرد. گزارش: docs/GOZARESH-AZMAYESH-CX-20Y-v1.4.52.md
    // ═══════════════════════════════════════════════════════════════════════════
    AppState.AdaptPreset(
        id = "s1-composite",
        label = "★★★ موتور ترکیبی (پیش‌فرض ۱٫۴٫۵۲) — ‎+۱۹٬۴۷۷$‎ با افت ۳٬۴۳٪",
        strategy = Strategy.DOC,
        mm = MoneyMode.MIXED,
        fade = false,
        targetR = Tuned.TARGET_R,
        tpUnits = 10.0,
        costMult = 0.0,
        minSl = 0.0,
        maxSl = Tuned.MAX_SL_UNITS,
        cmsn = Tuned.CMSN,
        slBufTicks = Tuned.SL_BUF_TICKS,
        entryBufTicks = Tuned.ENTRY_BUF_TICKS,
        note = "چهار لایهٔ آزمون‌شده روی ۲۰ سال: گیت رژیم نوسان (ATR% ≥ ۰٫۳۳) + گیت ارتفاع باکس " +
            "(≥ ۰٫۱۰×ATR) + حجم مرکب سقف‌دار ۱٫۵× + پهن‌کردن داده‌محور حد ضرر تا ۱٫۱۵× و تریل " +
            "۱٫۱۰× در رژیم پرنوسان. نتیجه در برابر پایهٔ ۱٫۴٫۵۱: سود ‎+۱۰٬۵۸۳$‎ ← ‎+۱۹٬۴۷۷$‎ · " +
            "انتظار هر معامله ‎+۰٫۰۳۱R‎ ← ‎+۰٫۱۳۹۷R‎ · PF ۱٫۰۸۴ ← ۱٫۳۲۸ · افت ۷٬۸۸۴$ ← ۳٬۵۳۴$ " +
            "(تعداد معامله ۱٬۸۰۶ ← ۶۵۱). ⚠ صادقانه: ۸۷٪ سود از ۲۰۲۵–۲۰۲۶ است و ۲۰۱۱–۲۰۱۵ منفی " +
            "می‌ماند؛ لایهٔ «کم‌کردن حجم در استرس» آزموده و حذف شد. " +
            "Manage All Lost با روشن‌بودن ترکیبی قفل است. گزارش: docs/GOZARESH-AZMAYESH-CX-20Y-v1.4.52.md",
        hourFrom = Tuned.ENTRY_HOUR_FROM,
        hourTo = Tuned.ENTRY_HOUR_TO,
        minAtrPct = Tuned.MIN_ATR_PCT,
        mgmtMode = 1,
        holdMinR = Tuned.MGMT_HOLD_MIN_R,
        holdDays = Tuned.MGMT_HOLD_DAYS,
        trailStartR = Tuned.MGMT_TRAIL_START_R,
        trailDistR = Tuned.MGMT_TRAIL_DIST_R,
        mgmtVolGate = Tuned.MGMT_VOL_GATE_PCT,
        cxMode = 3,
        cxGate = 0.33,
        cxGateCalm = 0.40,
        cxBox = 0.10,
        cxSize = 1,
        cxCap = 1.5,
        cxStress = 1.0,
        cxWiden = 1.15,
        cxTrail2 = 1.10
    ),
    // ═══════════════════════════════════════════════════════════════════════════
    //  نسخهٔ ۱٫۴٫۵۱ — سیستم مدیریت معاملهٔ باز
    //
    //  اندازه‌گیری روی ۲٬۰۳۱ معاملهٔ بیست‌سالهٔ همین تنظیم (کارمزد ۰٫۷، شبیه‌سازی مسیر
    //  واقعی M1 با همبستگی ۰٫۹۹۸ به موتور): میانگین MFE هر معامله ۱٫۳۸R بود و ثبت‌شده
    //  ۰٫۰۰R. علت: قاعدهٔ سند «همهٔ معاملات رأس ۲۰:۰۰ بسته شوند» سودهای در حال رشد را
    //  می‌بُرید. با «نگه‌داشتن فقط برنده‌ها + تریل کند روی کلوز ساعتی»:
    //      کلاسیک ‎+۰٫۰۰۳R‎  →  هوشمند ‎+۰٫۰۳۷R‎ به‌ازای هر معامله
    //      چهار دورهٔ پنج‌ساله: ‎+۰٫۰۰۸‎ / ‎+۰٫۰۱۰‎ / ‎+۰٫۰۵۹‎ / ‎+۰٫۰۵۸R (هر چهار بهتر)
    //  هر مکانیزم تکی (سر‌به‌سر، تریلینگ تیک‌به‌تیک، پلهٔ سود، حد ضرر زمانی) آزموده شد و
    //  همه بدتر بودند؛ جدول کامل در گزارش ۱٫۴٫۵۱.
    // ═══════════════════════════════════════════════════════════════════════════
    AppState.AdaptPreset(
        id = "s1-mgmt-smart",
        label = "★★★ مدیریت هوشمند معاملهٔ باز (پیش‌فرض ۱٫۴٫۵۱)",
        strategy = Strategy.DOC,
        mm = MoneyMode.MIXED,
        fade = false,
        targetR = Tuned.TARGET_R,
        tpUnits = 10.0,
        costMult = 0.0,
        minSl = 0.0,
        maxSl = Tuned.MAX_SL_UNITS,
        cmsn = Tuned.CMSN,
        slBufTicks = Tuned.SL_BUF_TICKS,
        entryBufTicks = Tuned.ENTRY_BUF_TICKS,
        note = "پنجرهٔ ۰۵–۱۵ UTC · گیت نوسان ورود ۰٫۲۵٪ · مدیریت هوشمند: در پایان روز فقط بازنده‌ها بسته می‌شوند، برنده یک شب می‌ماند و با تریل ۳٫۵/۱٫۵R روی کلوز ساعتی محافظت می‌شود؛ دروازهٔ نوسان ۰٫۳۰٪. آزمون موتور روی ۲۰ سال: ۱٬۸۰۶ معامله · انتظار هر معامله ‎+۰٫۰۳۱۱R‎ · سود خالص ‎+۱۰٬۵۸۳$‎ · PF ۱٫۰۸۴ · بیشترین افت ۷٬۸۸۴$ (کلاسیک: ‎+۰٫۰۰۶۵R‎ · ‎+۲٬۴۹۶$‎ · افت ۱۰٬۶۶۲$). گزارش: docs/GOZARESH-MODIRIYAT-v1.4.51.md",
        hourFrom = Tuned.ENTRY_HOUR_FROM,
        hourTo = Tuned.ENTRY_HOUR_TO,
        minAtrPct = Tuned.MIN_ATR_PCT,
        mgmtMode = 1,
        holdMinR = Tuned.MGMT_HOLD_MIN_R,
        holdDays = Tuned.MGMT_HOLD_DAYS,
        trailStartR = Tuned.MGMT_TRAIL_START_R,
        trailDistR = Tuned.MGMT_TRAIL_DIST_R,
        mgmtVolGate = Tuned.MGMT_VOL_GATE_PCT
    ),
    AppState.AdaptPreset(
        id = "s1-mgmt-adapt",
        label = "مدیریت هوشمند با دروازهٔ تطبیقی-صدکی (آزمایشی)",
        strategy = Strategy.DOC,
        mm = MoneyMode.MIXED,
        fade = false,
        targetR = Tuned.TARGET_R,
        tpUnits = 10.0,
        costMult = 0.0,
        minSl = 0.0,
        maxSl = Tuned.MAX_SL_UNITS,
        cmsn = Tuned.CMSN,
        slBufTicks = Tuned.SL_BUF_TICKS,
        entryBufTicks = Tuned.ENTRY_BUF_TICKS,
        note = "همان مدیریت هوشمند، ولی دروازهٔ نوسان از توزیع ATR% دوازده روز اخیر خودِ بازار " +
            "درمی‌آید (صدک ۶۰٪) به‌جای عدد ثابت ۰٫۳۰٪ — برای رژیم‌های نوسانی ناشناخته آینده. " +
            "آزمون موتور ۲۰ساله: انتظار ‎+۰٫۰۲۴۲R‎ · سود خالص ‎+۸٬۶۳۲$‎ · افت ۹٬۹۰۴$ — " +
            "یعنی از دروازهٔ ثابت ضعیف‌تر (‎+۰٫۰۳۱۱R‎ · ‎+۱۰٬۵۸۳$‎). صادقانه: فعلاً پیش‌فرض نیست.",
        hourFrom = Tuned.ENTRY_HOUR_FROM,
        hourTo = Tuned.ENTRY_HOUR_TO,
        minAtrPct = Tuned.MIN_ATR_PCT,
        mgmtMode = 2,
        holdMinR = Tuned.MGMT_HOLD_MIN_R,
        holdDays = Tuned.MGMT_HOLD_DAYS,
        trailStartR = Tuned.MGMT_TRAIL_START_R,
        trailDistR = Tuned.MGMT_TRAIL_DIST_R,
        mgmtVolGate = Tuned.MGMT_VOL_GATE_PCT
    ),
    AppState.AdaptPreset(
        id = "s1-mgmt-holdonly",
        label = "مدیریت: فقط نگه‌داشتن برنده (بدون تریلینگ)",
        strategy = Strategy.DOC,
        mm = MoneyMode.MIXED,
        fade = false,
        targetR = Tuned.TARGET_R,
        tpUnits = 10.0,
        costMult = 0.0,
        minSl = 0.0,
        maxSl = Tuned.MAX_SL_UNITS,
        cmsn = Tuned.CMSN,
        slBufTicks = Tuned.SL_BUF_TICKS,
        entryBufTicks = Tuned.ENTRY_BUF_TICKS,
        note = "همان تنظیم بالا ولی تریلینگ خاموش (۰). در شبیه‌سازی ۲۰ساله ‎+۰٫۰۲۳R‎ — " +
            "کمتر از نسخهٔ تریل‌دار، ولی ساده‌تر و بدون حد ضرر متحرک.",
        hourFrom = Tuned.ENTRY_HOUR_FROM,
        hourTo = Tuned.ENTRY_HOUR_TO,
        minAtrPct = Tuned.MIN_ATR_PCT,
        mgmtMode = 1,
        trailStartR = 0.0,
        mgmtVolGate = Tuned.MGMT_VOL_GATE_PCT
    ),
    AppState.AdaptPreset(
        id = "s1-mgmt-nogate",
        label = "مدیریت هوشمند بدون دروازهٔ نوسان (همهٔ معاملات)",
        strategy = Strategy.DOC,
        mm = MoneyMode.MIXED,
        fade = false,
        targetR = Tuned.TARGET_R,
        tpUnits = 10.0,
        costMult = 0.0,
        minSl = 0.0,
        maxSl = Tuned.MAX_SL_UNITS,
        cmsn = Tuned.CMSN,
        slBufTicks = Tuned.SL_BUF_TICKS,
        entryBufTicks = Tuned.ENTRY_BUF_TICKS,
        note = "دروازهٔ نوسان ۰ (همیشه فعال). در شبیه‌سازی ۲۰ساله ‎+۰٫۰۲۹R‎ ولی در دو دورهٔ " +
            "قدیمی از نسخهٔ دروازه‌دار بدتر بود؛ برای مقایسه روی دادهٔ خودتان.",
        hourFrom = Tuned.ENTRY_HOUR_FROM,
        hourTo = Tuned.ENTRY_HOUR_TO,
        minAtrPct = Tuned.MIN_ATR_PCT,
        mgmtMode = 1,
        mgmtVolGate = 0.0
    ),
    AppState.AdaptPreset(
        id = "s1-mgmt-classic",
        label = "مدیریت کلاسیک سند (§۱۸-۲۷) — برای مقایسه",
        strategy = Strategy.DOC,
        mm = MoneyMode.MIXED,
        fade = false,
        targetR = Tuned.TARGET_R,
        tpUnits = 10.0,
        costMult = 0.0,
        minSl = 0.0,
        maxSl = Tuned.MAX_SL_UNITS,
        cmsn = Tuned.CMSN,
        slBufTicks = Tuned.SL_BUF_TICKS,
        entryBufTicks = Tuned.ENTRY_BUF_TICKS,
        note = "همهٔ معاملات رأس ۲۰:۰۰ UTC بسته می‌شوند (رفتار همهٔ نسخه‌های قبل). " +
            "انتظار ۲۰ساله ‎+۰٫۰۰۳R‎ — خط پایه‌ای که مدیریت هوشمند با آن سنجیده شد.",
        hourFrom = Tuned.ENTRY_HOUR_FROM,
        hourTo = Tuned.ENTRY_HOUR_TO,
        minAtrPct = Tuned.MIN_ATR_PCT,
        mgmtMode = 0
    ),
    // ═══════════════════════════════════════════════════════════════════════════
    //  نسخهٔ ۱٫۴٫۴۹ — دو مکانیزم تازه، هر دو با عدد اندازه‌گیری‌شده روی ۱۰ سال
    // ═══════════════════════════════════════════════════════════════════════════
    AppState.AdaptPreset(
        id = "s1-session-hour",
        label = "★ پنجرهٔ ساعتی ۰۵–۱۵ UTC — نسخهٔ ۴ (کل ده سال مثبت شد)",
        strategy = Strategy.DOC,
        mm = MoneyMode.MIXED,
        fade = false,
        targetR = Tuned.TARGET_R,
        tpUnits = 10.0,
        costMult = 0.0,
        minSl = 0.0,
        maxSl = Tuned.MAX_SL_UNITS,
        cmsn = Tuned.CMSN,
        slBufTicks = Tuned.SL_BUF_TICKS,
        entryBufTicks = Tuned.ENTRY_BUF_TICKS,
        hourFrom = Tuned.ENTRY_HOUR_FROM,
        hourTo = Tuned.ENTRY_HOUR_TO,
        note = "همان هندسهٔ نسخهٔ ۳، فقط ورود تازه به ساعات ۰۵ تا ۱۵ UTC محدود شده " +
            "(سشن لندن و شروع نیویورک). اندازه‌گیری روی ۳٬۴۰۲٬۱۲۶ کندل M1 ده‌ساله با کارمزد ۰٫۷: " +
            "۲٬۴۱۰ معامله · انتظار ‎−۰٫۰۰۶R‎ · خالص ‎−۴۳۹$‎ · افت ۱۲٬۳۹۷$ " +
            "⟶ ‎۱٬۹۷۲ معامله · انتظار ‎+۰٫۰۰۷R‎ · خالص ‎+۲٬۶۰۵$‎ · ضریب سود ۱٫۰۲۲ · افت ۸٬۶۹۳$ · " +
            "۵۷ ماه مثبت از ۱۲۱ (قبلاً ۴۸). یعنی ۱۸٪ معاملهٔ کمتر، ولی هر معاملهٔ حذف‌شده میانگین ضررده بود. " +
            "⚠ صادقانه: سال‌های ۲۰۱۹ و ۲۰۲۱ و ۲۰۲۲ در همین تنظیم هم منفی‌اند؛ «همیشه صعودی» نیست. " +
            "معاملات باز بیرون پنجره دست‌نخورده مدیریت می‌شوند و هیچ سفارشی نیمه‌کاره لغو نمی‌شود."
    ),
    AppState.AdaptPreset(
        id = "s1-session-hour-guard",
        label = "★ همان + نگهبان منحنی سرمایه (افت ۱۲٪ ⇒ نصف ریسک · افت ۲۵٪ ⇒ توقف)",
        strategy = Strategy.DOC,
        mm = MoneyMode.MIXED,
        fade = false,
        targetR = Tuned.TARGET_R,
        tpUnits = 10.0,
        costMult = 0.0,
        minSl = 0.0,
        maxSl = Tuned.MAX_SL_UNITS,
        cmsn = Tuned.CMSN,
        slBufTicks = Tuned.SL_BUF_TICKS,
        entryBufTicks = Tuned.ENTRY_BUF_TICKS,
        hourFrom = Tuned.ENTRY_HOUR_FROM,
        hourTo = Tuned.ENTRY_HOUR_TO,
        guardMode = 2,
        guardDD = 12.0,
        guardHardDD = 25.0,
        guardScale = 0.5,
        note = "همان ترکیب بالا، به‌علاوهٔ نگهبان منحنی: با افت ۱۲٪ از اوج سرمایه، حجم معاملات " +
            "تازه نصف می‌شود و با افت ۲۵٪ هیچ ورود تازه‌ای ثبت نمی‌شود تا سرمایه به اوج تازه برسد. " +
            "فلسفه‌اش این است که منحنی «برنگردد»: افت‌های عمیق همان چیزی هستند که حساب را از بازی بیرون می‌کنند. " +
            "⚠ نگهبان لبه نمی‌سازد؛ اگر انتظار سیستم منفی باشد فقط کندتر ضرر می‌دهید. " +
            "عدد دقیقش را در «بهینه‌ساز خودکار» با دادهٔ خودتان ببینید."
    ),
    AppState.AdaptPreset(
        id = "s1-session-volgate",
        label = "★★ هموارترین: پنجرهٔ ساعتی + گیت نوسان ۰٫۲۵٪",
        strategy = Strategy.DOC,
        mm = MoneyMode.MIXED,
        fade = false,
        targetR = Tuned.TARGET_R,
        tpUnits = 10.0,
        costMult = 0.0,
        minSl = 0.0,
        maxSl = Tuned.MAX_SL_UNITS,
        cmsn = Tuned.CMSN,
        slBufTicks = Tuned.SL_BUF_TICKS,
        entryBufTicks = Tuned.ENTRY_BUF_TICKS,
        hourFrom = Tuned.ENTRY_HOUR_FROM,
        hourTo = Tuned.ENTRY_HOUR_TO,
        minAtrPct = 0.25,
        note = "پنجرهٔ ساعتی ۰۵–۱۵ UTC به‌علاوهٔ گیت نوسان: اگر ATR ساختار کمتر از ۰٫۲۵٪ قیمت " +
            "باشد، ستاپ تازه ساخته نمی‌شود (ستاپ‌های موجود مسیر خودشان را می‌روند). " +
            "اندازه‌گیری روی ۳ سال آخر (۲۰۲۳-۰۸ تا ۲۰۲۶-۰۸) با کارمزد ۰٫۷: " +
            "۶۳۸ معامله · انتظار ‎+۰٫۰۷۸R‎ · خالص ‎+۹٬۷۵۵$‎ · بدترین ۱۲ ماه ‎−۱٬۷۴۳$‎ " +
            "⟶ ۴۲۳ معامله · انتظار ‎+۰٫۱۱۵R‎ · خالص ‎+۹٬۶۸۶$‎ · ضریب سود ۱٫۲۲۳ · " +
            "بدترین ۱۲ ماه فقط ‎−۶۱۷$‎. یعنی یک‌سوم معاملات کمتر، همان سود، و منحنی خیلی هموارتر. " +
            "آستانه‌اش به رژیم بازار وابسته است؛ با «بهینه‌ساز خودکار» روی دادهٔ خودتان بسنجید."
    ),
    AppState.AdaptPreset(
        id = "s1-atr-geometry",
        label = "هندسهٔ نسبی به نوسان (۱٫۵×ATR) — آزموده و **رد شد**",
        strategy = Strategy.DOC,
        mm = MoneyMode.MIXED,
        fade = false,
        targetR = Tuned.TARGET_R,
        tpUnits = 10.0,
        costMult = 0.0,
        minSl = 0.0,
        maxSl = Tuned.MAX_SL_UNITS,
        cmsn = Tuned.CMSN,
        slBufTicks = Tuned.SL_BUF_TICKS,
        entryBufTicks = Tuned.ENTRY_BUF_TICKS,
        slBufMode = 1,
        slAtrMult = 1.5,
        note = "ایده‌اش درست است (بافر ثابت ۱۸ دلار در طلای ۱٬۳۴۰ دلاری ۱٫۳٪ قیمت است و در طلای " +
            "۳٬۱۰۰ دلاری ۰٫۶٪)، ولی اندازه‌گیری ده‌ساله آن را رد کرد: با ۱٫۵×ATR حد ضرر حدود ۳٫۷ دلار " +
            "می‌شود، نسبت کارمزد به R به ۰٫۱۸R می‌رسد و نتیجه ۳٬۴۰۹ معامله · انتظار ‎−۰٫۱۳۳R‎ · " +
            "خالص ‎−۱۸٬۵۰۳$‎ (بدتر از بافر ثابت). این دکمه فقط برای دیدن همان عدد در اپ است؛ " +
            "برای استفادهٔ واقعی ضریب را بزرگ (هم‌مرتبهٔ ۷–۱۴×ATR) بگذارید و نتیجه را بسنجید."
    ),
    AppState.AdaptPreset(
        id = "s1-mix-best",
        label = "★ بهینهٔ بیست‌ساله — استراتژی ۱ · Mixed · هم‌جهت ساختار",
        strategy = Strategy.DOC,
        mm = MoneyMode.MIXED,
        fade = Tuned.TRIGGER2_FADE,
        targetR = Tuned.TARGET_R,
        tpUnits = 10.0,
        costMult = Tuned.COST_MULT,
        minSl = 0.0,
        maxSl = Tuned.MAX_SL_UNITS,
        cmsn = Tuned.CMSN,
        slBufTicks = Tuned.SL_BUF_TICKS,
        entryBufTicks = Tuned.ENTRY_BUF_TICKS,
        note = "کل ۲۰ سال: ۴٬۵۲۴ معامله · نرخ برد ۴۹٫۰٪ · انتظار ‎−۰٫۰۲۰R‎ · خالص ‎−۱۴٬۷۱۵ دلار · " +
            "ضریب سود ۰٫۹۴ · بیشترین افت ۲۶٬۶۸۲ دلار · ۸۷ ماه مثبت از ۲۴۱. " +
            "ولی در رژیم اخیر ۲۰۲۳–۲۰۲۶ (در تنظیم دخیل نبود): ‎+۰٫۰۳۳R‎ · ‎+۸٬۰۴۶ دلار · ضریب سود ۱٫۱۰؛ " +
            "و سال آخر داده: ‎+۰٫۰۹۲R‎ · ‎+۷٬۳۷۷ دلار. روی کل ده سال هم تقریباً سربه‌سر است (‎−۴۳۹ دلار). " +
            "تنظیم قبلی روی همین ۲۰ سال ‎−۱۵٬۵۶۷ دلار بود."
    ),
    AppState.AdaptPreset(
        id = "s1-mix-lowdd",
        label = "کم‌افت‌ترین روی ۲۰ سال — استراتژی ۱ · Mixed · تایم‌فریم روزانه",
        strategy = Strategy.DOC,
        mm = MoneyMode.MIXED,
        fade = false,
        targetR = 7.0,
        tpUnits = 10.0,
        costMult = 0.0,
        minSl = 0.0,
        maxSl = 80.0,
        cmsn = Tuned.CMSN,
        slBufTicks = 1800.0,
        entryBufTicks = -2.0,
        tfIdx = 3,
        note = "ساختار روزانه / ۴ساعته / ۱ساعته / تریگر ۱۵دقیقه ⇒ معامله کم ولی آرام‌ترین منحنی: " +
            "کل ۲۰ سال ۴۸۴ معامله · انتظار ‎−۰٫۰۵۳R‎ · خالص فقط ‎−۴٬۷۸۰ دلار (کمترین زیان دلاری) · " +
            "ضریب سود ۰٫۷۸ · بیشترین افت فقط ۵٬۲۲۳ دلار (کمترین بین همه) · ۹۱ ماه مثبت از ۲۴۱. " +
            "بازده هر معامله ضعیف‌تر است، ولی چون به‌ندرت معامله می‌کند، منحنی سودش کم‌عمق‌ترین است؛ " +
            "برای کسی که افت کم را به سود ترجیح می‌دهد."
    ),
    AppState.AdaptPreset(
        id = "s1-off-best",
        label = "استراتژی ۱ · مود خاموش (§۵) · همان هندسهٔ بهینه",
        strategy = Strategy.DOC,
        mm = MoneyMode.NONE,
        fade = false,
        targetR = Tuned.TARGET_R,
        tpUnits = 10.0,
        costMult = 0.0,
        minSl = 0.0,
        maxSl = Tuned.MAX_SL_UNITS,
        cmsn = Tuned.CMSN,
        slBufTicks = Tuned.SL_BUF_TICKS,
        entryBufTicks = Tuned.ENTRY_BUF_TICKS,
        note = "همان ۴٬۵۲۴ معاملهٔ پیش‌تنظیم ستاره‌دار. ⚠ در این مود اپ کارمزد را از سود کم نمی‌کند (§۵): " +
            "عدد نمایشی اپ روی ۲۰ سال ‎+۹۱٬۰۸۷ دلار است، ولی با کارمزد واقعی ۰٫۷ می‌شود ‎−۹۴٬۶۲۸ دلار " +
            "و بیشترین افت با ریسک ۱٪ در هر معامله ۳۶٬۱۱۲ دلار است. " +
            "اگر این مود را می‌خواهید، «ریسک هر معامله» را به ۰٫۱ تا ۰٫۲ درصد کم کنید."
    ),
    AppState.AdaptPreset(
        id = "s1-mal-20y",
        label = "مدیریت همهٔ باخت‌ها (Manage All Lost) — همان هندسهٔ بهینه",
        strategy = Strategy.DOC,
        mm = MoneyMode.MANAGE_ALL_LOST,
        fade = false,
        targetR = Tuned.TARGET_R,
        tpUnits = 10.0,
        costMult = 0.0,
        minSl = 0.0,
        maxSl = Tuned.MAX_SL_UNITS,
        cmsn = Tuned.CMSN,
        slBufTicks = Tuned.SL_BUF_TICKS,
        entryBufTicks = Tuned.ENTRY_BUF_TICKS,
        note = "⚠⚠ در اجرای پیوستهٔ ۲۰ساله این مود حساب را **نابود می‌کند**: حجمِ بازیابی در دههٔ اول " +
            "به زیان ‎−۱۰۱٬۹۰۲ دلار و افت ‎۱۰۴٬۵۱۹ دلار (بیشتر از کل سرمایه) رسید و بعد از آن حسابی برای " +
            "معامله نماند (فقط ۱٬۷۱۶ معامله تا نابودی). اگر حساب را تازه از ۲۰۱۷ شروع کنید، همین مود " +
            "‎+۲۰٬۴۶۶ دلار نشان می‌دهد، ولی با افت ‎۷۸٬۸۱۱ دلاری وسط راه. حجم بازیابی «لبه» نمی‌سازد؛ " +
            "فقط شانسِ توالی معاملات را اهرم می‌کند. فقط با چشم باز و سرمایهٔ جدا استفاده شود."
    ),
    AppState.AdaptPreset(
        id = "s1-mix-fade",
        label = "تریگر ۲ معکوس (سند ۶) — بهترین تنظیم یافت‌شده",
        strategy = Strategy.DOC,
        mm = MoneyMode.MIXED,
        fade = true,
        targetR = 7.0,
        tpUnits = 10.0,
        costMult = 0.0,
        minSl = 0.0,
        maxSl = 80.0,
        cmsn = Tuned.CMSN,
        slBufTicks = 1800.0,
        entryBufTicks = -2.0,
        note = "⚠ روی کل ۲۰ سال منفی است: ۴٬۶۵۴ معامله · نرخ برد ۴۸٫۲٪ · انتظار ‎−۰٫۰۳۲R‎ · " +
            "زیان ‎−۲۶٬۴۳۴ دلار · ضریب سود ۰٫۸۹ · بیشترین افت ۳۰٬۴۹۸ دلار، با وجود ۱۰۲ ماه مثبت از ۲۴۱ " +
            "(ماه‌های مثبتش زیاد ولی کوچک‌اند). در دههٔ اول کم‌زیان‌تر از هم‌جهت بود و در دههٔ اخیر بدتر؛ " +
            "مجموعاً ورودِ برخلاف ساختار برتری ندارد."
    ),
    AppState.AdaptPreset(
        id = "s2-mgmt-smart",
        label = "استراتژی ۲ (بدون روند) + مدیریت هوشمند تطبیقی",
        strategy = Strategy.NO_TREND,
        mm = MoneyMode.MIXED,
        fade = false,
        targetR = 0.0,
        tpUnits = 10.0,
        costMult = 0.0,
        minSl = 0.0,
        maxSl = 80.0,
        cmsn = Tuned.CMSN,
        slBufTicks = 1800.0,
        entryBufTicks = -2.0,
        note = "آزمون موتور روی ۲۰ سال (مدیریت هوشمند با دروازهٔ ثابت): ۴٬۰۱۵ معامله · انتظار ‎−۰٫۰۰۳۴R‎ · " +
            "سود خالص ‎−۲٬۵۴۱$‎ · PF ۰٫۹۹۱ (بدون مدیریت: ‎−۰٫۰۰۹۱R‎ · ‎−۷٬۶۸۱$‎ · PF ۰٫۹۷۳). " +
            "مدیریت هوشمند ‎+۵٬۱۴۰$‎ بهتر کرد ولی بازده را مثبت نکرد — لبهٔ استراتژی ۲ با کارمزد ۰٫۷ منفی است.",
        hourFrom = Tuned.ENTRY_HOUR_FROM,
        hourTo = Tuned.ENTRY_HOUR_TO,
        minAtrPct = Tuned.MIN_ATR_PCT,
        mgmtMode = 1,
        holdMinR = Tuned.MGMT_HOLD_MIN_R,
        holdDays = Tuned.MGMT_HOLD_DAYS,
        trailStartR = Tuned.MGMT_TRAIL_START_R,
        trailDistR = Tuned.MGMT_TRAIL_DIST_R,
        mgmtVolGate = Tuned.MGMT_VOL_GATE_PCT
    ),
    AppState.AdaptPreset(
        id = "s2-mix-best",
        label = "استراتژی ۲ (بدون روند) — بهترین تنظیم یافت‌شده",
        strategy = Strategy.NO_TREND,
        mm = MoneyMode.MIXED,
        fade = false,
        // در استراتژی ۲ فقط وقتی «هدف بر حسب R» صفر باشد، حد سود واحدی اثر می‌کند.
        targetR = 0.0,
        tpUnits = 50.0,
        costMult = 0.0,
        minSl = 0.0,
        maxSl = 80.0,
        cmsn = Tuned.CMSN,
        slBufTicks = 1800.0,
        entryBufTicks = -2.0,
        note = "⚠ روی کل ۲۰ سال منفی است: ۹٬۵۷۸ معامله · نرخ برد ۴۸٫۴٪ · انتظار ‎−۰٫۰۳۲R‎ · " +
            "زیان ‎−۵۶٬۵۸۹ دلار · ضریب سود ۰٫۸۹ · بیشترین افت ۶۰٬۷۲۹ دلار. " +
            "معامله زیاد با لبهٔ منفی یعنی کارمزد بیشتر؛ از استراتژی ۱ ضعیف‌تر ماند. " +
            "نکته: «حد سود واحدی» فقط وقتی اثر می‌کند که «هدف تریگر ۲ بر حسب R» صفر باشد."
    ),
    AppState.AdaptPreset(
        id = "doc7-defaults",
        label = "بازگشت به پیش‌فرض سند ۷ (معکوس · ۴R · فیلتر ۶ · N=۳)",
        strategy = Strategy.DOC,
        mm = MoneyMode.MIXED,
        fade = true,
        targetR = 4.0,
        tpUnits = 10.0,
        costMult = 6.0,
        minSl = 0.0,
        maxSl = 3.0,
        cmsn = Tuned.CMSN,
        slBufTicks = 2.0,
        entryBufTicks = 1.0,
        note = "⚠ با کارمزد ۰٫۷ این پیش‌فرض **صفر سفارش** می‌دهد (روی یک سال، ده سال و بیست سال آزموده شد): " +
            "آستانهٔ فیلتر کارمزد (۶ × ۰٫۷ = ۴٫۲) از سقف N=۳ بزرگ‌تر است، پس هر سفارشی رد می‌شود. " +
            "این دکمه فقط برای مقایسه با سند است؛ بعد از آن «تطبیق با کارمزد» را بزنید."
    )
)
