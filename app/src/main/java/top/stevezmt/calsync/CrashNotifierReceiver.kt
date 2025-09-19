package top.stevezmt.calsync

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class CrashNotifierReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SHOW_CRASH = "top.stevezmt.calsync.ACTION_SHOW_CRASH"
        const val EXTRA_STACK = "extra_stack"
        private const val CRASH_FILE = "last_crash.txt"
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != ACTION_SHOW_CRASH) return
        try {
            NotificationUtils.ensureChannels(context)
        } catch (_: Throwable) {}

        // Prefer reading crash info from file to avoid intent size limits
        val fromFile = try {
            context.openFileInput(CRASH_FILE).bufferedReader().use { it.readText() }
        } catch (_: Throwable) { null }
        val stack = fromFile ?: intent.getStringExtra(EXTRA_STACK) ?: "未知崩溃"

        // Content intent: open app main UI
        val openIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val flags = if (Build.VERSION.SDK_INT >= 23)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val openPi = PendingIntent.getActivity(context, 0xCAFE, openIntent, flags)

        // Copy action reuses ErrorNotificationReceiver to put the stack to clipboard
        val copyIntent = Intent(context, ErrorNotificationReceiver::class.java).apply {
            action = ErrorNotificationReceiver.ACTION_COPY_STACK
            putExtra(ErrorNotificationReceiver.EXTRA_STACK, stack)
        }
        val copyPi = PendingIntent.getBroadcast(context, 0xBABE, copyIntent, flags)

        val n = NotificationCompat.Builder(context, NotificationUtils.CHANNEL_ERROR)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("CalSync 上次崩溃")
            .setContentText(stack.lines().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(stack))
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_save, "复制到剪贴板", copyPi)
            .setAutoCancel(true)
            .build()

        try { NotificationManagerCompat.from(context).notify(0xC0FFEE, n) } catch (_: Throwable) {}

        // Best effort cleanup to avoid duplicate reposts next boot
        try { context.deleteFile(CRASH_FILE) } catch (_: Throwable) {}
    }
}
