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
            val notification = sbn.notification ?: return
            val extras = notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
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
                    processNotification(pkg, title, content)
                } catch (e: Exception) {
                    Log.e(TAG, "processNotification failed", e)
                    sendErrorNotification("处理通知失败: ${e.message}")
                }
            }
            // add to recent notifications cache
            try {
                val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                val summary = "[$ts] $pkg - ${title.ifBlank { content.take(40) }}"
                NotificationCache.add(applicationContext, summary)
            } catch (_: Throwable) {}
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle notification", e)
            sendErrorNotification("通知处理失败: ${e.message}")
        }
    }

    private fun processNotification(pkg: String, title: String, content: String) {
        val res = NotificationProcessor.process(applicationContext, NotificationProcessor.ProcessInput(pkg, title, content), object: NotificationProcessor.ConfirmationNotifier{
            override fun onEventCreated(eventId: Long, title: String, startMillis: Long, endMillis: Long, location: String?) {
                // Do not post the extra "已添加...日程" confirmation notification here.
                // The event-created notification is already posted by NotificationProcessor -> NotificationUtils.sendEventCreated.
                try {
                    // also log created events into NotificationCache and broadcast to UI
                    val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                    val entry = "[$ts] event_created id=$eventId title=$title start=${startMillis}"
                    NotificationCache.add(applicationContext, entry)
                    try {
                        val b = Intent(NotificationUtils.ACTION_EVENT_CREATED)
                        b.setPackage(applicationContext.packageName)
                        b.putExtra(NotificationUtils.EXTRA_EVENT_ID, eventId)
                        b.putExtra(NotificationUtils.EXTRA_EVENT_TITLE, title)
                        b.putExtra(NotificationUtils.EXTRA_EVENT_START, startMillis)
                        sendBroadcast(b)
                    } catch (_: Throwable) {}
                } catch (_: Throwable) {}
            }
            override fun onError(message: String?) {
                sendErrorNotification(message)
            }
            override fun onDebugLog(line: String) {
                // Do not include full notification content; keep it concise.
                try { NotificationUtils.sendDebugLog(applicationContext, "[notif] $line") } catch (_: Throwable) {}
            }
        })
        Log.d(TAG, "process result: $res")
    }

    // Note: confirmation notifications are posted by NotificationUtils.sendEventCreated from the processor.

    private fun sendErrorNotification(msg: String?) {
        // Avoid posting notifications for internal errors; log a concise message instead
        try { Log.w(TAG, "error: ${msg ?: "unknown"}") } catch (_: Throwable) {}
    }

}
