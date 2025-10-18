package top.stevezmt.calsync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class KeepAliveService : Service() {
    companion object {
        private const val CHANNEL_ID = "keep_alive_channel"
        private const val CHANNEL_NAME = "后台保持"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            // Use NotificationCompat to remain compatible with pre-O devices when channel API isn't present
            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("正在后台监听通知")
                .setContentText("保持运行以解析通知中的日程")
                .setSmallIcon(android.R.drawable.ic_menu_today)
                .setOngoing(true)
            if (Build.VERSION.SDK_INT >= 31) {
                // setCategory exists on newer platform Notification; NotificationCompat maps appropriately
                builder.setCategory(android.app.Notification.CATEGORY_SERVICE)
            }
            startForeground(NOTIFICATION_ID, builder.build())
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_MIN)
                ch.setShowBadge(false)
                mgr.createNotificationChannel(ch)
            }
        }
    }
}
