package top.stevezmt.calsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action ?: return
        if (a == "top.stevezmt.calsync.ACTION_DUMP_ACTIVE_NOTIFS") {
            try {
                // start the listener service which will handle the dump in onStartCommand
                val si = Intent(context, NotificationMonitorService::class.java)
                si.action = a
                context.startService(si)
            } catch (e: Exception) {
                Log.w("DebugCommandReceiver", "failed to start service for dump: ${e.message}")
            }
        }
    }
}
