package top.stevezmt.calsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast

class ErrorNotificationReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_COPY_STACK = "top.stevezmt.calsync.ACTION_COPY_STACK"
        const val EXTRA_STACK = "extra_stack"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action == ACTION_COPY_STACK) {
            val stack = intent.getStringExtra(EXTRA_STACK) ?: ""
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("CalSync Error", stack)
                cm.setPrimaryClip(clip)
                Toast.makeText(context, "已复制错误信息到剪贴板", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "复制失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
