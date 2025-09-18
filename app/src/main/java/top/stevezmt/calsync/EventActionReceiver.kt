package top.stevezmt.calsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.widget.Toast
import android.util.Log

class EventActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_DELETE_EVENT = "top.stevezmt.calsync.ACTION_DELETE_EVENT"
        const val EXTRA_EVENT_ID = "extra_event_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val action = intent.action
            if (action == ACTION_DELETE_EVENT) {
                val id = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
                if (id > 0) {
                    val uri = Uri.withAppendedPath(CalendarContract.Events.CONTENT_URI, id.toString())
                    val rows = context.contentResolver.delete(uri, null, null)
                    if (rows > 0) {
                        NotificationUtils.cancelEventNotifications(context, id)
                        Toast.makeText(context, "已删除事件", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "删除事件失败", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "事件ID无效", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("EventActionReceiver", "failed to handle action", e)
            Toast.makeText(context, "处理操作出错: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
