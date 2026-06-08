package top.stevezmt.calsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class NotificationProcessorFunctionalTest {

    private class RecordingNotifier : NotificationProcessor.ConfirmationNotifier {
        val createdEvents = mutableListOf<Long>()
        val errors = mutableListOf<String?>()
        val debugLines = mutableListOf<String>()
        val infoLines = mutableListOf<String>()
        val candidates = mutableListOf<String>()
        val candidateStarts = mutableListOf<Long>()
        val candidateEngines = mutableListOf<String>()

        override fun onCandidateEvent(
            title: String,
            startMillis: Long,
            endMillis: Long?,
            location: String?,
            sourceSentence: String,
            engineLabel: String
        ) {
            candidates += title
            candidateStarts += startMillis
            candidateEngines += engineLabel
        }

        override fun onEventCreated(
            eventId: Long,
            title: String,
            startMillis: Long,
            endMillis: Long,
            location: String?,
            engineLabel: String
        ) {
            createdEvents += eventId
        }

        override fun onError(message: String?) {
            errors += message
        }

        override fun onDebugLog(line: String) {
            debugLines += line
        }

        override fun onInfoLog(line: String) {
            infoLines += line
        }
    }

    @Test
    fun processRejectsMessagesWithoutConfiguredKeywords() {
        val context = TestContext()
        val notifier = RecordingNotifier()
        SettingsStore.setKeywords(context, listOf("开会"))

        val result = NotificationProcessor.process(
            context,
            NotificationProcessor.ProcessInput(
                packageName = "pkg.demo",
                title = "课程提醒",
                content = "明天上课"
            ),
            notifier
        )

        assertFalse(result.handled)
        assertEquals("未匹配关键字", result.reason)
        assertTrue(notifier.createdEvents.isEmpty())
    }

    @Test
    fun processBlocksPackagesOutsideSelectedListForRealNotifications() {
        val context = TestContext()
        val notifier = RecordingNotifier()
        SettingsStore.setKeywords(context, listOf("开会"))
        SettingsStore.setSelectedSourceApps(context, listOf("allowed.pkg"), listOf("Allowed"))

        val result = NotificationProcessor.process(
            context,
            NotificationProcessor.ProcessInput(
                packageName = "blocked.pkg",
                title = "开会通知",
                content = "明天上午9点开会"
            ),
            notifier
        )

        assertFalse(result.handled)
        assertEquals("包名未在选择列表", result.reason)
        assertTrue(notifier.createdEvents.isEmpty())
    }

    @Test
    fun processAlwaysRejectsSelfNotifications() {
        val context = TestContext().apply {
            packageNameValue = "top.stevezmt.calsync"
        }
        val notifier = RecordingNotifier()
        SettingsStore.setKeywords(context, listOf("日历已创建"))

        val result = NotificationProcessor.process(
            context,
            NotificationProcessor.ProcessInput(
                packageName = "top.stevezmt.calsync",
                title = "10月23日 16:00 日历已创建",
                content = "宣讲会 @ 35B4"
            ),
            notifier
        )

        assertFalse(result.handled)
        assertEquals("忽略自身通知", result.reason)
        assertTrue(notifier.candidates.isEmpty())
        assertTrue(notifier.createdEvents.isEmpty())
    }

    @Test
    fun processLetsTestNotificationsBypassSelectedPackageFilter() {
        val context = TestContext()
        val notifier = RecordingNotifier()
        SettingsStore.setKeywords(context, listOf("开会"))
        SettingsStore.setSelectedSourceApps(context, listOf("allowed.pkg"), listOf("Allowed"))

        val result = NotificationProcessor.process(
            context,
            NotificationProcessor.ProcessInput(
                packageName = "blocked.pkg",
                title = "开会通知",
                content = "只是测试，没有时间信息",
                isTest = true
            ),
            notifier
        )

        assertFalse(result.handled)
        assertEquals("未包含时间句子", result.reason)
        assertTrue(notifier.debugLines.any { it.startsWith("process start") })
    }

    @Test
    fun processRejectsNonScheduleTextWithoutUserFacingBatterySwitch() {
        val context = TestContext()
        val notifier = RecordingNotifier()
        SettingsStore.setKeywords(context, listOf("通知"))
        SettingsStore.setCustomRules(context, emptyList())

        val result = NotificationProcessor.process(
            context,
            NotificationProcessor.ProcessInput(
                packageName = "pkg.demo",
                title = "通知",
                content = "下午104的课挪至207进行，请留意开关机房"
            ),
            notifier
        )

        assertFalse(result.handled)
        assertEquals("未包含时间句子", result.reason)
    }

    @Test
    fun processBypassesKeywordFilterForFuzzyDateTimeInjection() {
        val context = TestContext()
        val notifier = RecordingNotifier()
        SettingsStore.setKeywords(context, listOf("完全不匹配"))
        val base = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2025)
            set(Calendar.MONTH, Calendar.OCTOBER)
            set(Calendar.DAY_OF_MONTH, 18)
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val result = NotificationProcessor.process(
            context,
            NotificationProcessor.ProcessInput(
                packageName = "pkg.demo",
                title = "班级群",
                content = "@全体成员 周三下午参加宣讲会 ，地点在35B4",
                baseMillisOverride = base.timeInMillis
            ),
            notifier
        )

        assertFalse(result.handled)
        assertTrue(result.reason?.startsWith("插入日历失败") == true)
        assertTrue(notifier.infoLines.contains("已按\"下午=13:00\"补全含糊时间"))
        assertTrue(notifier.candidates.isNotEmpty())
        val start = Calendar.getInstance().apply { timeInMillis = notifier.candidateStarts.first() }
        assertEquals(2025, start.get(Calendar.YEAR))
        assertEquals(Calendar.OCTOBER, start.get(Calendar.MONTH))
        assertEquals(22, start.get(Calendar.DAY_OF_MONTH))
        assertEquals(13, start.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, start.get(Calendar.MINUTE))
    }

    @Test
    fun processReportsCandidateWhenParsingSucceedsEvenIfCalendarInsertFails() {
        val context = TestContext()
        val notifier = RecordingNotifier()
        SettingsStore.setKeywords(context, listOf("开会"))

        val result = NotificationProcessor.process(
            context,
            NotificationProcessor.ProcessInput(
                packageName = "pkg.demo",
                title = "开会通知",
                content = "明天上午9点开会"
            ),
            notifier
        )

        assertFalse(result.handled)
        assertTrue(result.reason?.startsWith("插入日历失败") == true)
        assertTrue(notifier.candidates.isNotEmpty())
        assertTrue(notifier.candidateEngines.all { it.isNotBlank() })
        assertTrue(notifier.createdEvents.isEmpty())
    }
}
