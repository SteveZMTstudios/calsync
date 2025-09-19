package top.stevezmt.calsync

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri

class NotificationStatusActivity : AppCompatActivity() {
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_status)

        // Enable back button in action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        statusView = findViewById(R.id.statusText)
        statusView.movementMethod = ScrollingMovementMethod()

        findViewById<Button>(R.id.btnOpenListenerSettings).setOnClickListener {
            // Open notification listener settings
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                appendLine("failed to open notification listener settings: ${e.message}")
            }
        }

        findViewById<Button>(R.id.btnOpenAppNotification).setOnClickListener {
            // Open app notification settings
            try {
                val intent = Intent()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    intent.action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                } else {
                    intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    intent.data = "package:$packageName".toUri()
                }
                startActivity(intent)
            } catch (e: Exception) {
                appendLine("failed to open app notification settings: ${e.message}")
            }
        }

        findViewById<Button>(R.id.btnSendTest).setOnClickListener {
            // Send a visible test notification using NotificationUtils
            try {
                NotificationUtils.sendError(applicationContext, "CalSync 测试通知：time=${System.currentTimeMillis()}")
                appendLine("Sent test notification (via NotificationUtils.sendError)")
            } catch (e: Exception) {
                appendLine("failed to send test notification: ${e.message}")
            }
        }

        findViewById<Button>(R.id.btnCrashTest).setOnClickListener {
            // Provide two options: actually crash (throws) or send an error notification
            try {
                // Deliberately throw to simulate a crash — this will crash the activity
                throw RuntimeException("Test crash from NotificationStatusActivity")
            } catch (e: RuntimeException) {
                // Also report via error notification so tester can verify notification channel
                try { NotificationUtils.sendError(applicationContext, e) } catch (_: Throwable) {}
                // Re-throw to actually crash (so tester can observe system crash behavior)
                throw e
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            val enabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
            appendLine("appNotificationsEnabled=$enabled")

            // Check if notification listener is enabled for this component
            val listenerEnabled = isNotificationListenerEnabled()
            appendLine("notificationListenerEnabled=$listenerEnabled")

            // Check our channels
            val channels = listOf(NotificationUtils.CHANNEL_CONFIRM, NotificationUtils.CHANNEL_ERROR)
            for (ch in channels) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val c = nm?.getNotificationChannel(ch)
                    if (c != null) {
                        appendLine("channel=${c.id} importance=${c.importance} name=${c.name} showBadge=${c.canShowBadge()}")
                    } else {
                        appendLine("channel=$ch not found")
                    }
                } else {
                    // Notification channels are not available before API 26
                    appendLine("channel=$ch not supported (SDK < 26)")
                }
            }
            appendLine("\n--- 最近收到的通知 (最多 ${NotificationCache.snapshot().size}) ---")
            NotificationCache.snapshot().take(50).forEach { appendLine(it) }
        } catch (e: Exception) {
            appendLine("failed to refresh status: ${e.message}")
        }
    }

    private fun appendLine(s: String) {
        statusView.append(s)
        statusView.append("\n")
    }

    private fun isNotificationListenerEnabled(): Boolean {
        try {
            val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(this, NotificationMonitorService::class.java)
            val meFlat = me.flattenToString()
            return flat.split(':').any { it.equals(meFlat, ignoreCase = true) }
        } catch (_: Exception) {
            return false
        }
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
