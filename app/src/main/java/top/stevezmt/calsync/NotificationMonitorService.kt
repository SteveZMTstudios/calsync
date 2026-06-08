package top.stevezmt.calsync

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// ...existing imports...

@SuppressLint("Registered")
class NotificationMonitorService : NotificationListenerService() {
    private val TAG = "NotificationMonitor"
    private val scope = CoroutineScope(Dispatchers.Default)
    private val queueCoordinator by lazy { NotificationQueueCoordinator(applicationContext, scope) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected")
            // Log notification permission and channel states to help diagnose ROM-level suppression
            try {
                val nm = applicationContext.getSystemService(NotificationManager::class.java)
                val enabled = androidx.core.app.NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()
                Log.i(TAG, "notificationsEnabled=$enabled")
                val channelsToCheck = listOf(NotificationUtils.CHANNEL_CONFIRM, NotificationUtils.CHANNEL_ERROR)
                for (ch in channelsToCheck) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val c = nm?.getNotificationChannel(ch)
                            if (c != null) {
                                Log.i(TAG, "channel=${c.id} importance=${c.importance} name=${c.name} showBadge=${c.canShowBadge()}")
                            } else {
                                Log.i(TAG, "channel=$ch not found")
                            }
                        } else {
                            // NotificationChannel API is not available before O; just log that channel info isn't supported.
                            Log.i(TAG, "channel=$ch (not supported below O)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "failed to inspect channel $ch", e)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "failed to query NotificationManager", e)
            }
        // Minimal behavior: avoid showing debug toasts in production and avoid posting debug notifications
        if (SettingsStore.isKeepAliveEnabled(this)) {
            try { startService(Intent(this, KeepAliveService::class.java)) } catch (e: Exception) { 
                try { scope.launch(Dispatchers.Main) { Toast.makeText(applicationContext, "启动 KeepAliveService 失败: ${e.message}", Toast.LENGTH_LONG).show() } } catch (_: Throwable) {}
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Notification listener disconnected")
            // Avoid posting debug notifications or toasts here
        
        // 在ColorOS等定制系统上尝试重新绑定服务
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                Log.i(TAG, "Attempting to request rebind notification listener service")
                requestRebind(ComponentName(this, NotificationMonitorService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "requestRebind failed", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val action = intent?.action
            if (action == "top.stevezmt.calsync.ACTION_DUMP_ACTIVE_NOTIFS") {
                try {
                    val active = activeNotifications
                    // Only log non-sensitive summary information
                    Log.i(TAG, "dumping active notifications count=${active.size}")
                } catch (e: Exception) {
                    Log.w(TAG, "failed to dump active notifications", e)
                }
            }
        } catch (_: Throwable) {}
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        try {
            Log.i(TAG, "onNotificationPosted -> pkg=${sbn.packageName} id=${sbn.id}")
            val pkg = sbn.packageName ?: return
            if (pkg == applicationContext.packageName) {
                Log.d(TAG, "skip self notification")
                return
            }
            val notification = sbn.notification ?: return
            val extras = notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val conversationTitle = extras.getCharSequence("android.conversationTitle")?.toString()
            val primary = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            val summary = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
            val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() } ?: emptyList()
            // MessagingStyle 支持 (QQ/微信聊天类通知经常走这里)
            @Suppress("DEPRECATION") // getParcelableArray is deprecated on newer SDKs; safe here for backward compatibility
            // Avoid referencing Notification.EXTRA_MESSAGES directly because it requires API 24;
            // use the raw key so this compiles with minSdk 23.
            val msgBundles = extras.getParcelableArray("android.app.extra.MESSAGES")
            val messages = msgBundles?.mapNotNull { b ->
                if (b is Bundle) {
                    val text = b.getCharSequence("text")?.toString()
                    val sender = b.getCharSequence("sender")?.toString()
                    if (!text.isNullOrBlank()) {
                        if (!sender.isNullOrBlank()) "$sender: $text" else text
                    } else null
                } else null
            } ?: emptyList()
            val content = (listOfNotNull(primary, bigText, subText, summary) + lines + messages)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString("\n")

            // Avoid showing debug toasts and avoid logging full notification content or extras

            scope.launch {
                try {
                    val result = processNotification(
                        pkg = pkg,
                        title = title,
                        content = content,
                        notificationKey = sbn.key ?: "${pkg}:${sbn.id}:${sbn.tag.orEmpty()}",
                        conversationTitle = conversationTitle,
                        postTimeMillis = sbn.postTime
                    )
                    NotificationCache.add(applicationContext, formatNotificationDebugEntry(pkg, title, content, result))
                } catch (e: Exception) {
                    Log.e(TAG, "processNotification failed", e)
                    NotificationCache.add(
                        applicationContext,
                        formatNotificationDebugEntry(pkg, title, content, "处理异常: ${e.message ?: e::class.java.simpleName}")
                    )
                    sendErrorNotification("处理通知失败: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle notification", e)
            sendErrorNotification("通知处理失败: ${e.message}")
        }
    }

    private fun processNotification(pkg: String, title: String, content: String, notificationKey: String, conversationTitle: String?, postTimeMillis: Long): NotificationQueueCoordinator.HandleResult {
        val notifier = object: NotificationProcessor.ConfirmationNotifier{
            override fun onCandidateEvent(title: String, startMillis: Long, endMillis: Long?, location: String?, sourceSentence: String, engineLabel: String) {
                try {
                    ResultLogCache.add(applicationContext, formatCandidateResult(title, startMillis, endMillis, location, engineLabel))
                } catch (_: Throwable) {}
            }

            override fun onEventCreated(eventId: Long, title: String, startMillis: Long, endMillis: Long, location: String?, engineLabel: String) {
                // Do not post the extra "已添加...日程" confirmation notification here.
                // NotificationProcessor already posts the user notification and broadcasts the live UI update.
                try {
                    ResultLogCache.add(applicationContext, formatSavedResult(eventId, title, startMillis, endMillis, location, engineLabel))
                } catch (_: Throwable) {}
            }
            override fun onError(message: String?) {
                sendErrorNotification(message)
            }
            override fun onDebugLog(line: String) {
                // Do not include full notification content; keep it concise.
                try { NotificationUtils.sendDebugLog(applicationContext, "[notif] $line") } catch (_: Throwable) {}
            }
            override fun onInfoLog(line: String) {
                try { ResultLogCache.add(applicationContext, line) } catch (_: Throwable) {}
            }
        }
        val mode = SettingsStore.getNotificationQueueMode(applicationContext)
        val result = queueCoordinator.handle(
            NotificationQueueCoordinator.IncomingNotification(
                packageName = pkg,
                title = title,
                content = content,
                notificationKey = notificationKey,
                conversationTitle = conversationTitle,
                postTimeMillis = postTimeMillis
            ),
            mode,
            notifier
        )
        Log.d(TAG, "process result: $result")
        return result
    }

    // Formats a parsed candidate before insertion so users can see what the app found.
    private fun formatCandidateResult(title: String, startMillis: Long, endMillis: Long?, location: String?, engineLabel: String): String {
        val endText = endMillis?.let { " - ${formatResultTime(it)}" } ?: ""
        val locationText = if (!location.isNullOrBlank()) "\n地点: $location" else ""
        return "发现可能日程: $title\n引擎: $engineLabel\n时间: ${formatResultTime(startMillis)}$endText$locationText"
    }

    // Formats the durable calendar outcome shown on the main screen history.
    private fun formatSavedResult(eventId: Long, title: String, startMillis: Long, endMillis: Long, location: String?, engineLabel: String): String {
        val locationText = if (!location.isNullOrBlank()) "\n地点: $location" else ""
        return "已保存日历: $title\n事件ID: $eventId\n引擎: $engineLabel\n时间: ${formatResultTime(startMillis)} - ${formatResultTime(endMillis)}$locationText"
    }

    private fun formatResultTime(millis: Long): String {
        return java.text.SimpleDateFormat("M月d日 H:mm", java.util.Locale.getDefault()).format(java.util.Date(millis))
    }

    private fun formatNotificationDebugEntry(
        pkg: String,
        title: String,
        content: String,
        result: NotificationQueueCoordinator.HandleResult
    ): String {
        val status = when (result) {
            is NotificationQueueCoordinator.HandleResult.Processed -> {
                val outcome = result.result.outcome
                val reason = result.result.reason?.let { ": $it" }.orEmpty()
                "已处理($outcome)$reason"
            }
            is NotificationQueueCoordinator.HandleResult.Queued -> "已入队: ${result.groupKey} (${result.size}条)"
            is NotificationQueueCoordinator.HandleResult.Dropped -> "已丢弃: ${result.reason}"
        }
        return formatNotificationDebugEntry(pkg, title, content, status)
    }

    private fun formatNotificationDebugEntry(
        pkg: String,
        title: String,
        content: String,
        status: String
    ): String {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val safeTitle = title.ifBlank { "(空)" }
        val safeContent = content.ifBlank { "(空)" }
        return "[$ts] $pkg\n状态: $status\n标题: $safeTitle\n正文:\n$safeContent"
    }

    // Note: confirmation notifications are posted by NotificationUtils.sendEventCreated from the processor.

    private fun sendErrorNotification(msg: String?) {
        // Avoid posting notifications for internal errors; log a concise message instead
        try { Log.w(TAG, "error: ${msg ?: "unknown"}") } catch (_: Throwable) {}
    }

}
