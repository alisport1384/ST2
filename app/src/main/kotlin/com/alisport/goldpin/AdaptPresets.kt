package com.alisport.goldpin

import com.alisport.goldpin.core.MoneyMode
import com.alisport.goldpin.core.Strategy
import com.alisport.goldpin.core.Tuned

// ═══════════════════════════════════════════════════════════════════════════════
//  پیش‌تنظیم‌های بخش «تطبیق» — هر عدد از جاروب پارامتر روی دادهٔ واقعی **ده‌ساله**
//  آمده است (۳٬۴۰۲٬۱۲۶ کندل M1 طلا · ۲۰۱۶-۰۸-۲۱ تا ۲۰۲۶-۰۸-۲۰ · کارمزد ۰٫۷).
//  روش: جست‌وجو روی آموزش ۲۰۱۶–۲۰۲۲، اعتبارسنجی روی نگه‌داشته‌شدهٔ ۲۰۲۳–۲۰۲۶ و
//  عددهای زیر روی **کل دهه** (گزارش خام: `sweep/sweep_phase1*.csv` در مخزن).
//
//  سنجه‌ها همان‌هایی است که اپ نشان می‌دهد: تعداد معامله، نرخ برد، سود خالص پس از
//  کسر کارمزد واقعی، ضریب سود (PF)، بیشترین افت سرمایه و ماه‌های مثبت (از ۱۲۱ ماه).
//
//  صادقانه: هیچ‌کدام از این ترکیب‌ها روی کل دهه سود بزرگ نمی‌سازد؛ بهترینشان
//  تقریباً سربه‌سر است و در رژیم ۲۰۲۳–۲۰۲۶ مثبت. تنظیم قبلی (بهینهٔ یک‌ساله) روی
//  همین دهه ‎−۲۰٬۵۸۷$‎ می‌داد و پیش‌فرض سند ۷ صفر سفارش. هرجا ترکیبی منفی است،
//  با همان عدد منفی‌اش آمده تا کاربر با عدد واقعی تصمیم بگیرد، نه با امید.
// ═══════════════════════════════════════════════════════════════════════════════

val ADAPT_PRESETS: List<AppState.AdaptPreset> = listOf(
    AppState.AdaptPreset(
        id = "s1-mix-best",
        label = "★ بهینهٔ ده‌ساله — استراتژی ۱ · Mixed · هم‌جهت ساختار",
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
        note = "کل ۱۰ سال: ۲٬۶۸۵ معامله · نرخ برد ۴۲٫۷٪ · انتظار ‎−۰٫۰۱۳R‎ · خالص ‎−۱٬۲۶۴ دلار · " +
            "ضریب سود ۰٫۹۹ · بیشترین افت ۹٬۵۰۲ دلار · ۴۸ ماه مثبت از ۱۲۱. " +
            "روی ۲۰۲۳–۲۰۲۶ (در تنظیم دخیل نبود): ‎+۰٫۰۲۲R‎ · ‎+۴٬۳۲۷ دلار · ضریب سود ۱٫۰۶؛ " +
            "سال آخر داده: ‎+۰٫۰۸۹R‎ · ‎+۴٬۸۷۸ دلار. تنظیم قبلی (بهینهٔ یک‌ساله) روی همین دهه ‎−۲۰٬۵۸۷ دلار بود."
    ),
    AppState.AdaptPreset(
        id = "s1-mix-lowdd",
        label = "کم‌افت‌ترین — استراتژی ۱ · Mixed · تایم‌فریم روزانه",
        strategy = Strategy.DOC,
        mm = MoneyMode.MIXED,
        fade = false,
        targetR = 7.0,
        tpUnits = 10.0,
        costMult = 0.0,
        minSl = 0.0,
        maxSl = 20.0,
        cmsn = Tuned.CMSN,
        slBufTicks = 600.0,
        entryBufTicks = -2.0,
        tfIdx = 3,
        note = "ساختار روزانه / ۴ساعته / ۱ساعته / تریگر ۱۵دقیقه ⇒ معامله کم ولی آرام: " +
            "کل ۱۰ سال ۲۴۶ معامله · انتظار ‎−۰٫۰۳۱R‎ · خالص ‎−۶۸۶ دلار · ضریب سود ۰٫۹۲ · " +
            "بیشترین افت فقط ۱٬۲۸۷ دلار (کمترین بین همه) · ۴۳ ماه مثبت. " +
            "تقریباً سربه‌سر با نوسان ناچیز؛ برای کسی که افت کم را به سود ترجیح می‌دهد."
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
        note = "همان ۲٬۶۸۵ معاملهٔ پیش‌تنظیم ستاره‌دار. ⚠ در این مود اپ کارمزد را از سود کم نمی‌کند (§۵): " +
            "عدد نمایشی اپ روی ۱۰ سال ‎+۲۷۳٬۰۷۶ دلار است، ولی با کارمزد واقعی ۰٫۷ می‌شود ‎−۴۴٬۸۸۰ دلار " +
            "و بیشترین افت با ریسک ۱٪ در هر معامله به ۱۰۰٬۵۶۳ دلار می‌رسد (حجم بزرگ می‌شود). " +
            "اگر این مود را می‌خواهید، «ریسک هر معامله» را به ۰٫۱ تا ۰٫۲ درصد کم کنید."
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
        maxSl = 20.0,
        cmsn = Tuned.CMSN,
        slBufTicks = 600.0,
        entryBufTicks = -2.0,
        note = "⚠ روی هر سه پنجره منفی بود (آموزش، نگه‌داشته‌شده و کل دهه): ۳٬۱۴۴ معامله · " +
            "نرخ برد ۳۷٫۱٪ · انتظار ‎−۰٫۰۹۶R‎ · زیان ‎−۲۱٬۱۷۳ دلار · ضریب سود ۰٫۸۵ · " +
            "بیشترین افت ۲۲٬۶۹۷ دلار. یعنی در این دهه ورودِ برخلاف ساختار برتری ندارد؛ " +
            "اگر روشنش می‌کنید، بدانید چه عددی پشتش است."
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
        maxSl = 40.0,
        cmsn = Tuned.CMSN,
        slBufTicks = 600.0,
        entryBufTicks = -2.0,
        note = "⚠ روی کل دهه منفی است: ۶٬۳۲۸ معامله · نرخ برد ۳۷٫۲٪ · انتظار ‎−۰٫۰۶۴R‎ · " +
            "زیان ‎−۲۶٬۴۴۱ دلار · ضریب سود ۰٫۹۰ · بیشترین افت ۳۳٬۵۷۰ دلار. " +
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
        note = "⚠ با کارمزد ۰٫۷ این پیش‌فرض **صفر سفارش** می‌دهد (هم روی یک سال، هم روی ده سال آزموده شد): " +
            "آستانهٔ فیلتر کارمزد (۶ × ۰٫۷ = ۴٫۲) از سقف N=۳ بزرگ‌تر است، پس هر سفارشی رد می‌شود. " +
            "این دکمه فقط برای مقایسه با سند است؛ بعد از آن «تطبیق با کارمزد» را بزنید."
    )
)
