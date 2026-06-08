package top.stevezmt.calsync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationQueueCoordinatorTest {

    private class NoopNotifier : NotificationProcessor.ConfirmationNotifier {
        override fun onEventCreated(
            eventId: Long,
            title: String,
            startMillis: Long,
            endMillis: Long,
            location: String?,
            engineLabel: String
        ) = Unit

        override fun onError(message: String?) = Unit
    }

    private fun incoming(
        title: String,
        content: String,
        key: String = title + content,
        postTime: Long = 1000L
    ) = NotificationQueueCoordinator.IncomingNotification(
        packageName = "com.tencent.mm",
        title = title,
        content = content,
        notificationKey = key,
        postTimeMillis = postTime
    )

    @Test
    fun normalizesWechatAndQqUnreadSuffixes() {
        assertEquals("班级群", NotificationQueueCoordinator.normalizedConversationTitle("班级群【1条新消息】"))
        assertEquals("班级群", NotificationQueueCoordinator.normalizedConversationTitle("班级群(2条新消息)"))
        assertEquals("班级群", NotificationQueueCoordinator.normalizedConversationTitle("班级群（3条新消息）"))
        assertEquals("班级群", NotificationQueueCoordinator.normalizedConversationTitle("微信：班级群【1条新消息】"))
        assertEquals("班级群", NotificationQueueCoordinator.normalizedConversationTitle("班级群：杜奕衡发来一条消息"))
    }

    @Test
    fun completeModeCreatesAsSoonAsMergedQueueLooksLikeEvent() {
        val context = TestContext()
        SettingsStore.setKeywords(context, listOf("班级群"))
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val processedContents = mutableListOf<String>()
        val coordinator = NotificationQueueCoordinator(
            context = context,
            scope = scope,
            processor = { input, _ ->
                processedContents += input.content
                if (input.content.contains("寒假实践") && input.content.contains("截止时间是2月20号")) {
                    NotificationProcessor.ProcessResult(true, eventId = 1L, outcome = NotificationProcessor.Outcome.CREATED)
                } else {
                    NotificationProcessor.ProcessResult(false, reason = "未包含时间句子", outcome = NotificationProcessor.Outcome.NO_TIME_SENTENCE)
                }
            }
        )

        try {
            coordinator.handle(
                incoming("班级群", "杜奕衡：寒假实践完成的可以上交材料了", key = "n1"),
                NotificationQueueMode.COMPLETE,
                NoopNotifier()
            )
            assertEquals(1, coordinator.queueSize("com.tencent.mm|title:班级群"))

            coordinator.handle(
                incoming("班级群(1条新消息)", "杜奕衡：截止时间是2月20号", key = "n2"),
                NotificationQueueMode.COMPLETE,
                NoopNotifier()
            )

            assertEquals(0, coordinator.queueSize("com.tencent.mm|title:班级群"))
            assertTrue(processedContents.last().contains("寒假实践"))
            assertTrue(processedContents.last().contains("截止时间是2月20号"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun trimsLowValueOverflowBeforeValuableContext() {
        val context = TestContext()
        SettingsStore.setKeywords(context, listOf("班级群"))
        SettingsStore.setNotificationQueueMaxMessages(context, 3)
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val coordinator = NotificationQueueCoordinator(
            context = context,
            scope = scope,
            processor = { _, _ ->
                NotificationProcessor.ProcessResult(false, reason = "解析失败", outcome = NotificationProcessor.Outcome.PARSE_FAILED)
            }
        )

        try {
            coordinator.handle(incoming("班级群", "寒假实践材料", key = "n1"), NotificationQueueMode.COMPLETE, NoopNotifier())
            coordinator.handle(incoming("班级群【1条新消息】", "截止时间待补充", key = "n2"), NotificationQueueMode.COMPLETE, NoopNotifier())
            coordinator.handle(incoming("班级群【2条新消息】", "提交地点待补充", key = "n3"), NotificationQueueMode.COMPLETE, NoopNotifier())
            coordinator.handle(incoming("班级群【3条新消息】", "收到", key = "n4"), NotificationQueueMode.COMPLETE, NoopNotifier())

            val messages = coordinator.snapshotMessagesForTests("com.tencent.mm|title:班级群")
            assertEquals(3, messages.size)
            assertFalse(messages.contains("收到"))
            assertTrue(messages.contains("寒假实践材料"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun triesMergedQueueBeforeTrimmingOverflow() {
        val context = TestContext()
        SettingsStore.setKeywords(context, listOf("班级群"))
        SettingsStore.setNotificationQueueMaxMessages(context, 3)
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val coordinator = NotificationQueueCoordinator(
            context = context,
            scope = scope,
            processor = { input, _ ->
                if (input.content.contains("寒假实践") && input.content.contains("2月20号")) {
                    NotificationProcessor.ProcessResult(true, eventId = 9L, outcome = NotificationProcessor.Outcome.CREATED)
                } else {
                    NotificationProcessor.ProcessResult(false, reason = "解析失败", outcome = NotificationProcessor.Outcome.PARSE_FAILED)
                }
            }
        )

        try {
            coordinator.handle(incoming("班级群", "寒假实践", key = "n1"), NotificationQueueMode.COMPLETE, NoopNotifier())
            coordinator.handle(incoming("班级群【1条新消息】", "材料提交", key = "n2"), NotificationQueueMode.COMPLETE, NoopNotifier())
            coordinator.handle(incoming("班级群【2条新消息】", "线下纸质版", key = "n3"), NotificationQueueMode.COMPLETE, NoopNotifier())
            coordinator.handle(incoming("班级群【3条新消息】", "截止时间是2月20号", key = "n4"), NotificationQueueMode.COMPLETE, NoopNotifier())

            assertEquals(0, coordinator.queueSize("com.tencent.mm|title:班级群"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun timeoutFlushDropsUnparseableQueue() {
        val context = TestContext()
        SettingsStore.setKeywords(context, listOf("班级群"))
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val coordinator = NotificationQueueCoordinator(
            context = context,
            scope = scope,
            processor = { _, _ ->
                NotificationProcessor.ProcessResult(false, reason = "未包含时间句子", outcome = NotificationProcessor.Outcome.NO_TIME_SENTENCE)
            }
        )

        try {
            coordinator.handle(incoming("班级群", "寒假实践材料", key = "n1"), NotificationQueueMode.COMPLETE, NoopNotifier())
            assertEquals(1, coordinator.queueSize("com.tencent.mm|title:班级群"))

            coordinator.flushExpiredForTests("com.tencent.mm|title:班级群", NoopNotifier())

            assertEquals(0, coordinator.queueSize("com.tencent.mm|title:班级群"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun offModeProcessesImmediatelyWithoutQueueing() {
        val context = TestContext()
        SettingsStore.setKeywords(context, listOf("班级群"))
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        var calls = 0
        val coordinator = NotificationQueueCoordinator(
            context = context,
            scope = scope,
            processor = { _, _ ->
                calls += 1
                NotificationProcessor.ProcessResult(true, eventId = 7L, outcome = NotificationProcessor.Outcome.CREATED)
            }
        )

        try {
            val result = coordinator.handle(incoming("班级群", "明天开会", key = "n1"), NotificationQueueMode.OFF, NoopNotifier())

            assertTrue(result is NotificationQueueCoordinator.HandleResult.Processed)
            assertEquals(1, calls)
            assertEquals(0, coordinator.queueSize("com.tencent.mm|title:班级群"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun queuePreFilterAllowsFuzzyDateTimeEvenWithoutKeyword() {
        val context = TestContext()
        SettingsStore.setKeywords(context, listOf("完全不匹配"))
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        var calls = 0
        val coordinator = NotificationQueueCoordinator(
            context = context,
            scope = scope,
            processor = { _, _ ->
                calls += 1
                NotificationProcessor.ProcessResult(false, reason = "解析失败", outcome = NotificationProcessor.Outcome.PARSE_FAILED)
            }
        )

        try {
            coordinator.handle(
                incoming("班级群", "@全体成员 周三下午参加宣讲会", key = "n1"),
                NotificationQueueMode.COMPLETE,
                NoopNotifier()
            )

            assertEquals(1, calls)
            assertEquals(1, coordinator.queueSize("com.tencent.mm|title:班级群"))
        } finally {
            scope.cancel()
        }
    }
}
