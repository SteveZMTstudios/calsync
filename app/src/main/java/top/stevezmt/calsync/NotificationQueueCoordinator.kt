package top.stevezmt.calsync

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NotificationQueueCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val processor: (NotificationProcessor.ProcessInput, NotificationProcessor.ConfirmationNotifier) -> NotificationProcessor.ProcessResult =
        { input, notifier -> NotificationProcessor.process(context, input, notifier) },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    data class IncomingNotification(
        val packageName: String,
        val title: String,
        val content: String,
        val notificationKey: String,
        val conversationTitle: String? = null,
        val postTimeMillis: Long
    )

    sealed class HandleResult {
        data class Processed(val result: NotificationProcessor.ProcessResult) : HandleResult()
        data class Queued(val groupKey: String, val size: Int) : HandleResult()
        data class Dropped(val reason: String) : HandleResult()
    }

    private data class QueuedMessage(
        val text: String,
        val receivedAtMillis: Long,
        val lowValue: Boolean
    )

    private data class QueueState(
        val packageName: String,
        val title: String,
        val fallbackKey: String,
        val messages: MutableList<QueuedMessage> = mutableListOf(),
        val seenTexts: MutableSet<String> = linkedSetOf(),
        val firstReceivedAtMillis: Long,
        var lastReceivedAtMillis: Long,
        var timeoutJob: Job? = null
    )

    private val queues = mutableMapOf<String, QueueState>()
    private val lock = Any()

    fun handle(
        notification: IncomingNotification,
        mode: NotificationQueueMode,
        notifier: NotificationProcessor.ConfirmationNotifier
    ): HandleResult {
        if (mode == NotificationQueueMode.OFF) {
            return HandleResult.Processed(processSingle(notification, notifier))
        }

        val preliminaryFailure = preliminaryFilter(notification)
        if (preliminaryFailure != null) return HandleResult.Processed(preliminaryFailure)

        if (mode == NotificationQueueMode.FAST) {
            val result = processSingle(notification, notifier)
            if (result.handled || !shouldQueueAfterSingleAttempt(result)) {
                return HandleResult.Processed(result)
            }
        }

        val key = buildGroupKey(notification)
        enqueue(notification, key, notifier)
        return HandleResult.Queued(key, queueSize(key))
    }

    fun flushExpiredForTests(groupKey: String, notifier: NotificationProcessor.ConfirmationNotifier): NotificationProcessor.ProcessResult? {
        val state = synchronized(lock) { queues[groupKey] } ?: return null
        return flush(groupKey, state, notifier, removeOnFailure = true)
    }

    fun queueSize(groupKey: String): Int = synchronized(lock) { queues[groupKey]?.messages?.size ?: 0 }

    internal fun snapshotMessagesForTests(groupKey: String): List<String> =
        synchronized(lock) { queues[groupKey]?.messages?.map { it.text }.orEmpty() }

    private fun processSingle(
        notification: IncomingNotification,
        notifier: NotificationProcessor.ConfirmationNotifier
    ): NotificationProcessor.ProcessResult {
        return processor(
            NotificationProcessor.ProcessInput(
                packageName = notification.packageName,
                title = notification.title,
                content = notification.content,
                baseMillisOverride = notification.postTimeMillis
            ),
            notifier
        )
    }

    private fun preliminaryFilter(notification: IncomingNotification): NotificationProcessor.ProcessResult? {
        if (notification.packageName == context.packageName) {
            return NotificationProcessor.ProcessResult(false, reason = "忽略自身通知", outcome = NotificationProcessor.Outcome.FILTERED)
        }

        val keywords = SettingsStore.getKeywords(context)
        val matchesKeyword = keywords.any { keyword ->
            notification.title.contains(keyword, ignoreCase = true) ||
                notification.content.contains(keyword, ignoreCase = true)
        }
        val fuzzyCandidate = FuzzyTimeInjector.canInject(context, notification.title + "。" + notification.content)
        if (!matchesKeyword && !fuzzyCandidate) {
            return NotificationProcessor.ProcessResult(false, reason = "未匹配关键字", outcome = NotificationProcessor.Outcome.FILTERED)
        }

        val selectedPkgs = SettingsStore.getSelectedSourceAppPkgs(context)
        if (selectedPkgs.isNotEmpty() && notification.packageName !in selectedPkgs) {
            return NotificationProcessor.ProcessResult(false, reason = "包名未在选择列表", outcome = NotificationProcessor.Outcome.FILTERED)
        }

        return null
    }

    private fun shouldQueueAfterSingleAttempt(result: NotificationProcessor.ProcessResult): Boolean {
        return result.outcome == NotificationProcessor.Outcome.PREFILTER_REJECTED ||
            result.outcome == NotificationProcessor.Outcome.NO_TIME_SENTENCE ||
            result.outcome == NotificationProcessor.Outcome.PARSE_FAILED
    }

    private fun enqueue(
        notification: IncomingNotification,
        groupKey: String,
        notifier: NotificationProcessor.ConfirmationNotifier
    ) {
        val timeoutMillis = SettingsStore.getNotificationQueueTimeoutSeconds(context) * 1000L
        val maxMessages = SettingsStore.getNotificationQueueMaxMessages(context)
        val now = clock()
        val state = synchronized(lock) {
            val existing = queues[groupKey]
            val state = existing ?: QueueState(
                packageName = notification.packageName,
                title = displayTitle(notification),
                fallbackKey = notification.notificationKey,
                firstReceivedAtMillis = notification.postTimeMillis,
                lastReceivedAtMillis = notification.postTimeMillis
            ).also { queues[groupKey] = it }

            state.lastReceivedAtMillis = notification.postTimeMillis
            for (text in extractMessageTexts(notification)) {
                if (state.seenTexts.add(text)) {
                    state.messages += QueuedMessage(text, now, isLowValueMessage(text))
                }
            }
            state.timeoutJob?.cancel()
            state.timeoutJob = scope.launch {
                delay(timeoutMillis)
                flush(groupKey, state, notifier, removeOnFailure = true)
            }
            state
        }

        val result = flush(groupKey, state, notifier, removeOnFailure = false)
        if (result?.handled == true) {
            synchronized(lock) {
                queues.remove(groupKey)?.timeoutJob?.cancel()
            }
        } else {
            synchronized(lock) {
                if (queues[groupKey] === state) trimQueue(state, maxMessages)
            }
        }
    }

    private fun flush(
        groupKey: String,
        state: QueueState,
        notifier: NotificationProcessor.ConfirmationNotifier,
        removeOnFailure: Boolean
    ): NotificationProcessor.ProcessResult? {
        val snapshot = synchronized(lock) {
            if (queues[groupKey] !== state) return null
            state.messages.map { it.text }
        }
        if (snapshot.isEmpty()) return null

        val result = processor(
            NotificationProcessor.ProcessInput(
                packageName = state.packageName,
                title = state.title,
                content = snapshot.joinToString("\n"),
                baseMillisOverride = state.firstReceivedAtMillis
            ),
            notifier
        )

        if (result.handled || removeOnFailure) {
            synchronized(lock) {
                if (queues[groupKey] === state) {
                    queues.remove(groupKey)?.timeoutJob?.cancel()
                }
            }
        }
        return result
    }

    private fun trimQueue(state: QueueState, maxMessages: Int) {
        while (state.messages.size > maxMessages) {
            val lowValueIndex = state.messages.indexOfFirst { it.lowValue }
            val removeIndex = if (lowValueIndex >= 0) lowValueIndex else 0
            val removed = state.messages.removeAt(removeIndex)
            state.seenTexts.remove(removed.text)
        }
    }

    private fun buildGroupKey(notification: IncomingNotification): String {
        val normalized = normalizedConversationTitle(notification.conversationTitle)
            ?: normalizedConversationTitle(notification.title)
        return if (normalized.isNullOrBlank()) {
            "${notification.packageName}|key:${notification.notificationKey}"
        } else {
            "${notification.packageName}|title:$normalized"
        }
    }

    private fun displayTitle(notification: IncomingNotification): String {
        return normalizedConversationTitle(notification.conversationTitle)
            ?: normalizedConversationTitle(notification.title)
            ?: notification.title.ifBlank { notification.packageName }
    }

    private fun extractMessageTexts(notification: IncomingNotification): List<String> {
        val lines = notification.content
            .split('\n')
            .map { normalizeMessageText(it) }
            .filter { it.isNotBlank() }
        return if (lines.isNotEmpty()) lines else listOfNotNull(normalizeMessageText(notification.title).takeIf { it.isNotBlank() })
    }

    companion object {
        fun normalizedConversationTitle(raw: String?): String? {
            var text = raw?.trim().orEmpty()
            if (text.isBlank()) return null
            text = text
                .replace(Regex("\\s*【\\s*\\d+\\s*条新消息\\s*】\\s*$"), "")
                .replace(Regex("\\s*[（(]\\s*\\d+\\s*条新消息\\s*[）)]\\s*$"), "")
                .replace(Regex("\\s*[（(]?\\s*\\d+\\s*条\\s*[）)]?\\s*$"), "")
                .trim()
            val appPrefix = Regex("^(微信|QQ|TIM)\\s*[:：]\\s*(.+)$", RegexOption.IGNORE_CASE).find(text)
            if (appPrefix != null) {
                text = appPrefix.groupValues[2].trim()
            } else {
                text = text.replace(Regex("\\s*[:：]\\s*[^:：]{1,40}$"), "").trim()
            }
            return text.ifBlank { null }
        }

        fun normalizeMessageText(raw: String): String {
            return raw.trim()
                .replace(Regex("\\s+"), " ")
                .trim('。', '，', ',', '；', ';', '：', ':')
        }

        fun isLowValueMessage(raw: String): Boolean {
            val text = normalizeMessageText(raw).lowercase()
            if (text.isBlank()) return true
            if (text in setOf("收到", "好的", "好", "ok", "okay", "1", "+1", "嗯", "是", "对", "已阅", "辛苦了")) return true
            if (text.length <= 2 && text.none { it.isLetterOrDigit() || isCjk(it) }) return true
            return false
        }

        private fun isCjk(ch: Char): Boolean = ch in '\u4e00'..'\u9fff'
    }
}
