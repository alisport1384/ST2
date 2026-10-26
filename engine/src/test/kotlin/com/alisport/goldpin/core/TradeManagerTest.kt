package com.alisport.goldpin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * قرارداد سیستم مدیریت معاملهٔ باز (۱٫۴٫۵۱).
 *
 * چرا این آزمون‌ها: منطق مدیریت در [PaperBroker] به معاملهٔ باز واقعی نیاز دارد و آزمون
 * «کندل ساختگی → معامله» شکننده است. در عوض این‌جا همان چیزهایی قفل می‌شوند که اگر
 * کسی اشتباهی عوض کند، رفتار بی‌صدا تغییر می‌کند: پیش‌فرض‌ها، مهاجرت فایل قدیمی،
 * ذخیره/بازیابی تنظیمات و سازگاری `copy()`.
 */
class TradeManagerTest {

    @Test
    fun shippedDefaultsUseSmartManagement() {
        val c = Settings()
        Tuned.apply(c)
        assertEquals(1, c.mgmtMode, "مدیریت هوشمند باید پیش‌فرض ارسالی باشد")
        assertEquals(Tuned.MGMT_HOLD_MIN_R, c.mgmtHoldMinR)
        assertEquals(Tuned.MGMT_HOLD_DAYS, c.mgmtHoldMaxDays)
        assertEquals(Tuned.MGMT_TRAIL_START_R, c.mgmtTrailStartR)
        assertEquals(Tuned.MGMT_TRAIL_DIST_R, c.mgmtTrailDistR)
        assertEquals(Tuned.MGMT_VOL_GATE_PCT, c.mgmtVolGatePct)
        // ترتیب معنادار پارامترها: تریلینگ باید بعد از شروعش فاصله داشته باشد
        assertTrue(c.mgmtTrailStartR > c.mgmtTrailDistR, "شروع تریلینگ باید بزرگ‌تر از فاصلهٔ آن باشد")
        assertTrue(c.mgmtVolGatePct in 0.0..1.0, "دروازهٔ نوسان باید درصد معقول باشد")
    }

    @Test
    fun stockSettingsStayClassic() {
        // Settings() خالی باید رفتار سند §۱۸-۲۷ (کلاسیک) بماند — هیچ آزمون دیگری
        // بی‌آنکه Tuned را صدا بزند نباید ناخواسته مدیریت هوشمند بگیرد.
        val c = Settings()
        assertEquals(0, c.mgmtMode)
        assertEquals(1, c.mgmtHoldMaxDays)
    }

    @Test
    fun copyCarriesManagementKeys() {
        val a = Settings()
        a.mgmtMode = 1
        a.mgmtHoldMinR = 0.25
        a.mgmtHoldMaxDays = 2
        a.mgmtTrailStartR = 4.0
        a.mgmtTrailDistR = 1.25
        a.mgmtVolGatePct = 0.35
        val b = a.copy()
        assertEquals(1, b.mgmtMode)
        assertEquals(0.25, b.mgmtHoldMinR)
        assertEquals(2, b.mgmtHoldMaxDays)
        assertEquals(4.0, b.mgmtTrailStartR)
        assertEquals(1.25, b.mgmtTrailDistR)
        assertEquals(0.35, b.mgmtVolGatePct)
        // تغییر نسخه باید مستقل باشد (کپی عمیق مقادیر)
        b.mgmtMode = 0
        assertEquals(1, a.mgmtMode)
    }

    @Test
    fun settingsRoundTripPersistsManagement() {
        val a = Settings()
        Tuned.apply(a)
        a.mgmtHoldMinR = 0.5
        a.mgmtTrailStartR = 2.5
        a.mgmtVolGatePct = 0.0
        val json = Store.settingsJson(a)
        val b = Store.settingsFromJson(json)
        assertEquals(a.mgmtMode, b.mgmtMode)
        assertEquals(a.mgmtHoldMinR, b.mgmtHoldMinR)
        assertEquals(a.mgmtHoldMaxDays, b.mgmtHoldMaxDays)
        assertEquals(a.mgmtTrailStartR, b.mgmtTrailStartR)
        assertEquals(a.mgmtTrailDistR, b.mgmtTrailDistR)
        assertEquals(a.mgmtVolGatePct, b.mgmtVolGatePct)
    }

    @Test
    fun untouchedV5MigratesToSmartManagement() {
        // فایلی که دقیقاً خروجی دست‌نخوردهٔ نسخهٔ ۵ (۱٫۴٫۴۹) است ⇒ مدیریت هوشمند روشن می‌شود
        val v5 = Settings().apply {
            trigger2Fade = false; trigger2TargetR = 7.0; minSlCostMult = 0.0
            slBufTicks = 1800.0; entryBufTicks = -2.0; maxSlUnits = 80.0; minSlUnits = 0.0
            slBufMode = 0; minAtrPct = Tuned.MIN_ATR_PCT
            entryHourFrom = Tuned.ENTRY_HOUR_FROM; entryHourTo = Tuned.ENTRY_HOUR_TO
            mgmtMode = 0
        }
        assertTrue(Tuned.isUntouchedTunedV5(v5), "این ترکیب باید «دست‌نخوردهٔ نسخهٔ ۵» شناخته شود")
        // یک تغییر دستی کوچک ⇒ دیگر مهاجرت نمی‌کند
        val touched = v5.copy().also { it.slBufTicks = 1200.0 }
        assertFalse(Tuned.isUntouchedTunedV5(touched), "تغییر دستی بافر نباید مهاجرت کند")
    }

    @Test
    fun mgmtKeysDoNotDisturbStrategyGeometry() {
        // روشن‌کردن مدیریت هوشمند نباید هندسهٔ استراتژی را عوض کند (فقط خروج را).
        val a = Settings().apply { Tuned.apply(this) }
        val b = a.copy().also { it.mgmtMode = 0 }
        assertEquals(a.slBufTicks, b.slBufTicks)
        assertEquals(a.entryBufTicks, b.entryBufTicks)
        assertEquals(a.maxSlUnits, b.maxSlUnits)
        assertEquals(a.trigger2TargetR, b.trigger2TargetR)
        assertEquals(a.minAtrPct, b.minAtrPct)
        assertEquals(a.entryHourFrom, b.entryHourFrom)
        assertNotEquals(a.mgmtMode, b.mgmtMode)
    }
}
