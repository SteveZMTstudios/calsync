package top.stevezmt.calsync

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import java.util.Locale
import java.util.Date
import java.text.SimpleDateFormat
import android.os.Bundle
import android.widget.Toast
import android.content.pm.ApplicationInfo
import android.content.ComponentName
// ...existing imports...

@SuppressLint("Registered")
class NotificationMonitorService : NotificationListenerService() {
    private val TAG = "NotificationMonitor"
    private val scope = CoroutineScope(Dispatchers.Default)
    // recent notifications are stored in NotificationCache
    private val debugLogging: Boolean
        get() = try {
            (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (e: Exception) { false }

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
                        val c = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) nm?.getNotificationChannel(ch) else null
                        if (c != null) {
                            Log.i(TAG, "channel=${c.id} importance=${c.importance} name=${c.name} showBadge=${c.canShowBadge()}")
                        } else {
                            Log.i(TAG, "channel=$ch not found")
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
            val msgBundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
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
                .filter { !it.isNullOrBlank() }
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
                val ts = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())
                val summary = "[$ts] $pkg - ${title.ifBlank { content.take(40) }}"
                NotificationCache.add(summary)
            } catch (_: Throwable) {}
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle notification", e)
            sendErrorNotification("通知处理失败: ${e.message}")
        }
    }

    // expose recent notifications snapshot for diagnostics
    fun getRecentNotificationsSnapshot(): List<String> {
        return NotificationCache.snapshot()
    }

    private fun processNotification(pkg: String, title: String, content: String) {
        val res = NotificationProcessor.process(applicationContext, NotificationProcessor.ProcessInput(pkg, title, content), object: NotificationProcessor.ConfirmationNotifier{
            override fun onEventCreated(eventId: Long, title: String, startMillis: Long, endMillis: Long, location: String?) {
                // Do not post the extra "已添加...日程" confirmation notification here.
                // The event-created notification is already posted by NotificationProcessor -> NotificationUtils.sendEventCreated.
                try {
                    // also log created events into NotificationCache and broadcast to UI
                    val ts = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())
                    val entry = "[$ts] event_created id=$eventId title=$title start=${startMillis}"
                    NotificationCache.add(entry)
                    try {
                        val b = android.content.Intent(NotificationUtils.ACTION_EVENT_CREATED)
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
        })
        Log.d(TAG, "process result: $res")
    }

    // Note: confirmation notifications are posted by NotificationUtils.sendEventCreated from the processor.

    private fun sendErrorNotification(msg: String?) {
        // Avoid posting notifications for internal errors; log a concise message instead
        try { Log.w(TAG, "error: ${msg ?: "unknown"}") } catch (_: Throwable) {}
    }

    @Suppress("DEPRECATION") // Bundle.get is deprecated in Java bindings; kept for broad compatibility when printing values
    private fun dumpExtras(extras: Bundle, maxLen: Int = 4000): String {
        val keys = extras.keySet().sorted()
        val sb = StringBuilder()
        for (k in keys) {
            val v = try { extras.get(k) } catch (e: Exception) { "<error:${e.message}>" }
            sb.append(k).append('=').append(valueToString(v)).append('\n')
            if (sb.length >= maxLen) { sb.append("...truncated..."); break }
        }
        return sb.toString()
    }

    private fun valueToString(v: Any?): String = when (v) {
        null -> "null"
        is CharSequence -> v.toString()
        is Bundle -> "Bundle{" + v.keySet().joinToString() + "}"
        is Array<*> -> "Array(size=${v.size})"
        is IntArray -> "IntArray(size=${v.size})"
        else -> v.javaClass.simpleName + ':' + v.toString()
    }
}
