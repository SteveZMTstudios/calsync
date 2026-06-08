package top.stevezmt.calsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStoreFunctionalTest {

    @Test
    fun defaultsCoverCoreFunctionalSettings() {
        val context = TestContext()

        assertEquals(listOf("通知", "班级群"), SettingsStore.getKeywords(context))
        assertNull(SettingsStore.getSelectedCalendarId(context))
        assertNull(SettingsStore.getSelectedCalendarName(context))
        assertEquals(
            listOf("今天:0", "今晚:0:pm", "明早:1:am", "明天:1", "后天:2", "大后天:3", "下周:7"),
            SettingsStore.getRelativeDateWords(context)
        )
        assertTrue(SettingsStore.isTimeNLPEnabled(context))
        assertEquals(ParseEngine.BUILTIN, SettingsStore.getParsingEngine(context))
        assertEquals(EventParseEngine.BUILTIN, SettingsStore.getEventParsingEngine(context))
        assertEquals(10, SettingsStore.getReminderMinutes(context))
        assertEquals(NotificationQueueMode.OFF, SettingsStore.getNotificationQueueMode(context))
        assertEquals(40, SettingsStore.getNotificationQueueTimeoutSeconds(context))
        assertEquals(3, SettingsStore.getNotificationQueueMaxMessages(context))
        assertEquals(SettingsStore.defaultFuzzyTimePairs(), SettingsStore.getFuzzyTimePairs(context))
        assertFalse(SettingsStore.isGuessBeforeParseEnabled(context))
        assertEquals(1, SettingsStore.getPreferFutureOption(context))
        assertEquals(true, SettingsStore.getPreferFutureBoolean(context))
        assertFalse(SettingsStore.isKeepAliveEnabled(context))
        assertTrue(SettingsStore.getCustomRules(context).isEmpty())
        assertTrue(SettingsStore.getSelectedSourceAppPkgs(context).isEmpty())
        assertEquals(emptyList<String>(), SettingsStore.getSelectedSourceAppNames(context))
        assertFalse(SettingsStore.isPrivacyAccepted(context))
        assertNull(SettingsStore.getAiGgufModelUri(context))
        assertNotNull(SettingsStore.getAiSystemPrompt(context))
        assertTrue(SettingsStore.getAiSystemPrompt(context).contains("start"))
        assertNull(SettingsStore.getLastBackupTimestamp(context))
        assertNull(SettingsStore.getLastBackupName(context))
    }

    @Test
    fun roundTripsFlagsMetadataAndAiConfiguration() {
        val context = TestContext()

        SettingsStore.setPrivacyAccepted(context, true)
        SettingsStore.setKeepAliveEnabled(context, true)
        SettingsStore.setReminderMinutes(context, 30)
        SettingsStore.setNotificationQueueMode(context, NotificationQueueMode.COMPLETE)
        SettingsStore.setNotificationQueueTimeoutSeconds(context, 2)
        SettingsStore.setNotificationQueueMaxMessages(context, 99)
        SettingsStore.setGuessBeforeParseEnabled(context, true)
        SettingsStore.setAiGgufModelUri(context, "content://models/calendar.gguf")
        SettingsStore.setAiSystemPrompt(context, "prompt-body")
        SettingsStore.setSelectedCalendar(context, 42L, "Campus")
        SettingsStore.setLastBackupInfo(context, 123456789L, "backup-1.json")
        SettingsStore.setKeywords(context, listOf("开会", "考试"))
        SettingsStore.setFuzzyTimePairs(
            context,
            listOf(
                SettingsStore.FuzzyTimePair("下午", 13 * 60),
                SettingsStore.FuzzyTimePair("下午", 14 * 60),
                SettingsStore.FuzzyTimePair("坏时间", 2000),
                SettingsStore.FuzzyTimePair("", 9 * 60)
            )
        )

        assertTrue(SettingsStore.isPrivacyAccepted(context))
        assertTrue(SettingsStore.isKeepAliveEnabled(context))
        assertEquals(30, SettingsStore.getReminderMinutes(context))
        assertEquals(NotificationQueueMode.COMPLETE, SettingsStore.getNotificationQueueMode(context))
        assertEquals(5, SettingsStore.getNotificationQueueTimeoutSeconds(context))
        assertEquals(10, SettingsStore.getNotificationQueueMaxMessages(context))
        assertTrue(SettingsStore.isGuessBeforeParseEnabled(context))
        assertEquals("content://models/calendar.gguf", SettingsStore.getAiGgufModelUri(context))
        assertEquals("prompt-body", SettingsStore.getAiSystemPrompt(context))
        assertEquals(42L, SettingsStore.getSelectedCalendarId(context))
        assertEquals("Campus", SettingsStore.getSelectedCalendarName(context))
        assertEquals(123456789L, SettingsStore.getLastBackupTimestamp(context))
        assertEquals("backup-1.json", SettingsStore.getLastBackupName(context))
        assertEquals(listOf("开会", "考试"), SettingsStore.getKeywords(context))
        assertEquals(listOf(SettingsStore.FuzzyTimePair("下午", 14 * 60)), SettingsStore.getFuzzyTimePairs(context))
    }

    @Test
    fun fuzzyTimePairsCanBeResetAndParsedFromBackupShapes() {
        val context = TestContext()

        SettingsStore.setFuzzyTimePairs(
            context,
            SettingsStore.parseFuzzyTimePairs("""[{"word":"午休后","minutes":810},{"word":"晚上","minutes":1140}]""")
        )
        assertEquals(
            listOf(
                SettingsStore.FuzzyTimePair("午休后", 13 * 60 + 30),
                SettingsStore.FuzzyTimePair("晚上", 19 * 60)
            ),
            SettingsStore.getFuzzyTimePairs(context)
        )

        SettingsStore.setFuzzyTimePairs(context, SettingsStore.parseFuzzyTimePairs("下午=14:00,晚自习前=18:30"))
        assertEquals(
            listOf(
                SettingsStore.FuzzyTimePair("下午", 14 * 60),
                SettingsStore.FuzzyTimePair("晚自习前", 18 * 60 + 30)
            ),
            SettingsStore.getFuzzyTimePairs(context)
        )

        SettingsStore.resetFuzzyTimePairs(context)
        assertEquals(SettingsStore.defaultFuzzyTimePairs(), SettingsStore.getFuzzyTimePairs(context))
    }

    @Test
    fun preferFutureAndRelativeWordsHandleAllStates() {
        val context = TestContext()

        SettingsStore.setPreferFutureOption(context, 0)
        assertNull(SettingsStore.getPreferFutureBoolean(context))

        SettingsStore.setPreferFutureOption(context, 1)
        assertEquals(true, SettingsStore.getPreferFutureBoolean(context))

        SettingsStore.setPreferFutureOption(context, 2)
        assertEquals(false, SettingsStore.getPreferFutureBoolean(context))

        SettingsStore.setPreferFutureOption(context, 99)
        assertEquals(true, SettingsStore.getPreferFutureBoolean(context))

        SettingsStore.setRelativeDateWords(context, listOf("今晚:0:pm", "明晚:1:pm"))
        assertEquals(listOf("今晚:0:pm", "明晚:1:pm"), SettingsStore.getRelativeDateWords(context))

        SettingsStore.resetRelativeWords(context)
        assertEquals(
            listOf("今天:0", "今晚:0:pm", "明早:1:am", "明天:1", "后天:2", "大后天:3", "下周:7"),
            SettingsStore.getRelativeDateWords(context)
        )
    }

    @Test
    fun legacyAndMultiSelectSourceAppsRemainCompatible() {
        val context = TestContext()

        SettingsStore.setSelectedSourceApp(context, "legacy.pkg", "Legacy App")
        assertEquals("legacy.pkg", SettingsStore.getSelectedSourceAppPkg(context))
        assertEquals("Legacy App", SettingsStore.getSelectedSourceAppName(context))
        assertEquals(listOf("legacy.pkg"), SettingsStore.getSelectedSourceAppPkgs(context))
        assertEquals(listOf("Legacy App"), SettingsStore.getSelectedSourceAppNames(context))

        SettingsStore.setSelectedSourceApps(
            context,
            listOf("app.one", "app.two"),
            listOf("App One", "App Two")
        )
        assertEquals(listOf("app.one", "app.two"), SettingsStore.getSelectedSourceAppPkgs(context))
        assertEquals(listOf("App One", "App Two"), SettingsStore.getSelectedSourceAppNames(context))
    }

    @Test
    fun engineEnumsFallbackForUnknownIds() {
        assertEquals(ParseEngine.BUILTIN, ParseEngine.fromId(-1))
        assertEquals(EventParseEngine.BUILTIN, EventParseEngine.fromId(999))
        assertEquals("内置引擎", ParseEngine.BUILTIN.toString())
        assertEquals("ML Kit", EventParseEngine.ML_KIT.toString())
    }
}
