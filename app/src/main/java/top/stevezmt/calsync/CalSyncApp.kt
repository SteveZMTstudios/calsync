package top.stevezmt.calsync

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.view.isNotEmpty

class CalSyncApp : Application() {
    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        // Register a lifecycle callback to apply top inset padding to each activity's content view
        registerActivityLifecycleCallbacks(object: ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                try {
                    val content = activity.findViewById<View>(android.R.id.content)
                    // content is a FrameLayout whose first child is the activity's root view; apply on it
                    val root = if (content is android.view.ViewGroup && content.isNotEmpty()) content.getChildAt(0) else content
                    root?.let { InsetsHelper.applyTopInsetOnce(it) }
                } catch (_: Exception) {
                    // ignore
                }
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        // set default uncaught exception handler
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                // Persist stack to a file to survive intent size limits and allow cross-process read
                val stack = Log.getStackTraceString(e)
                val msg = "${e.javaClass.simpleName}: ${e.message}\n\n$stack"
                try { Log.e("CalSync", "Uncaught exception in thread ${t.name}", e) } catch (_: Throwable) {}
                try {
                    openFileOutput("last_crash.txt", MODE_PRIVATE).bufferedWriter().use { it.write(msg) }
                } catch (_: Throwable) {}

                // Schedule a deferred broadcast in ~2 seconds using AlarmManager
                val intent = android.content.Intent(this, CrashNotifierReceiver::class.java).apply {
                    action = CrashNotifierReceiver.ACTION_SHOW_CRASH
                    putExtra(CrashNotifierReceiver.EXTRA_STACK, msg.take(2000)) // backup
                }
                val flags = if (android.os.Build.VERSION.SDK_INT >= 23)
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                else android.app.PendingIntent.FLAG_UPDATE_CURRENT
                val pi = android.app.PendingIntent.getBroadcast(this, 0xC0DE, intent, flags)
                val am = getSystemService(android.app.AlarmManager::class.java)
                val triggerAt = System.currentTimeMillis() + 2000
                try {
                    if (am != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        if (am.canScheduleExactAlarms()) {
                            try {
                                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi)
                            } catch (e: SecurityException) {
                                Log.w("CalSync", "No SCHEDULE_EXACT_ALARM permission: ${e.message}")
                            }
                        } else {
                            Log.w("CalSync", "App not allowed to schedule exact alarms")
                        }
                    } else if (am != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        try {
                            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi)
                        } catch (e: SecurityException) {
                            Log.w("CalSync", "No SCHEDULE_EXACT_ALARM permission: ${e.message}")
                        }
                    } else {
                        try {
                            am?.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi)
                        } catch (e: SecurityException) {
                            Log.w("CalSync", "No SCHEDULE_EXACT_ALARM permission: ${e.message}")
                        }
                    }
                } catch (_: Throwable) {}
            } catch (_: Throwable) { }
            // Forward to previous handler so system shows crash dialog / logs; if none, kill process
            if (old != null) {
                old.uncaughtException(t, e)
            } else {
                try { Log.e("CalSync", "No previous UncaughtExceptionHandler; killing process") } catch (_: Throwable) {}
                try { android.os.Process.killProcess(android.os.Process.myPid()) } catch (_: Throwable) {}
                try { System.exit(10) } catch (_: Throwable) {}
            }
        }
    }
}
