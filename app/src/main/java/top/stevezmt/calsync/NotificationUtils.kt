package top.stevezmt.calsync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationUtils {
	const val CHANNEL_CONFIRM = "calsync_confirm"
	const val CHANNEL_ERROR = "calsync_error"
	// CHANNEL_EVENT_CREATED removed: event-created notifications will use CHANNEL_CONFIRM

	// Broadcast action and extras for in-app notifications when an event is created
	const val ACTION_EVENT_CREATED = "top.stevezmt.calsync.ACTION_EVENT_CREATED"
	const val EXTRA_EVENT_ID = "extra_event_id"
	const val EXTRA_EVENT_TITLE = "extra_event_title"
	const val EXTRA_EVENT_START = "extra_event_start"
	// baseMillis used by parser when creating this event (System.currentTimeMillis captured at processing start)
	const val EXTRA_EVENT_BASE = "extra_event_base"

	// Debug log broadcast (for MainActivity UI)
	const val ACTION_DEBUG_LOG = "top.stevezmt.calsync.ACTION_DEBUG_LOG"
	const val EXTRA_DEBUG_LINE = "extra_debug_line"

	fun sendDebugLog(context: Context, line: String) {
		try {
			val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
			val entry = "[$ts] $line"
			try { NotificationCache.add(context, entry) } catch (_: Throwable) {}
			try {
				val i = Intent(ACTION_DEBUG_LOG)
				i.setPackage(context.packageName)
				i.putExtra(EXTRA_DEBUG_LINE, entry)
				context.sendBroadcast(i)
			} catch (_: Throwable) {}
		} catch (_: Throwable) {}
	}

	fun ensureChannels(context: Context) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val nm = context.getSystemService(NotificationManager::class.java)
			if (nm?.getNotificationChannel(CHANNEL_CONFIRM) == null) {
				nm?.createNotificationChannel(
					NotificationChannel(CHANNEL_CONFIRM, "CalSync 确认", NotificationManager.IMPORTANCE_DEFAULT)
				)
			}
			if (nm?.getNotificationChannel(CHANNEL_ERROR) == null) {
				nm?.createNotificationChannel(
					NotificationChannel(CHANNEL_ERROR, "CalSync 错误", NotificationManager.IMPORTANCE_HIGH)
				)
			}
			// no separate "日历创建" channel; use CHANNEL_CONFIRM for event-created notices
		}
	}

	fun sendError(context: Context, msg: String?) {
		// Post a simple non-persistent error notification (keeps backward compatibility)
		ensureChannels(context)
		val n = NotificationCompat.Builder(context, CHANNEL_ERROR)
			.setSmallIcon(R.mipmap.ic_launcher)
			.setContentTitle("CalSync 错误")
			.setContentText(msg ?: "未知错误")
			.setStyle(NotificationCompat.BigTextStyle().bigText(msg ?: "未知错误"))
			.setAutoCancel(true)
			.build()
		safeNotify(context, System.currentTimeMillis().toInt(), n)
	}

	/**
	 * Post an error notification that includes the stack trace and a copy-to-clipboard action.
	 * This notification is persistent/ongoing so the user can inspect it; tapping it opens MainActivity.
	 */
	fun sendError(context: Context, t: Throwable) {
		ensureChannels(context)
		val stack = Log.getStackTraceString(t)
		val body = (t.message ?: t::class.java.name) + "\n\n" + stack

		// Intent to open MainActivity when tapping the notification
		val openIntent = Intent(context, MainActivity::class.java).apply {
			flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
		}
		val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		val openPi = PendingIntent.getActivity(context, 0xABCDEF, openIntent, flags)

		// Broadcast intent to copy stacktrace to clipboard
		val copyIntent = Intent(context, ErrorNotificationReceiver::class.java).apply {
			action = ErrorNotificationReceiver.ACTION_COPY_STACK
			putExtra(ErrorNotificationReceiver.EXTRA_STACK, body)
		}
		val copyPi = PendingIntent.getBroadcast(context, 0xDEADBEEF.toInt(), copyIntent, flags)

		val n = NotificationCompat.Builder(context, CHANNEL_ERROR)
			.setSmallIcon(R.mipmap.ic_launcher)
			.setContentTitle("CalSync 错误：${t::class.java.simpleName}")
			.setContentText(t.message ?: "错误发生")
			.setStyle(NotificationCompat.BigTextStyle().bigText(body))
			.setContentIntent(openPi)
			.addAction(android.R.drawable.ic_menu_save, "复制到剪贴板", copyPi)
			// Allow user to swipe/clear the error notification. Keep copy action available.
			.setOngoing(false)
			.setAutoCancel(true)
			.build()

		safeNotify(context, System.currentTimeMillis().toInt(), n)
	}

	/**
	 * Safely notify, checking POST_NOTIFICATIONS permission on Android 13+ and catching SecurityException.
	 */
	private fun safeNotify(context: Context, id: Int, notification: Notification) {
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
					Log.w("NotificationUtils", "POST_NOTIFICATIONS not granted; skipping notification id=$id")
					return
				}
			}
			NotificationManagerCompat.from(context).notify(id, notification)
		} catch (se: SecurityException) {
			Log.w("NotificationUtils", "SecurityException while posting notification id=$id", se)
		} catch (t: Throwable) {
			// Fallback: log unexpected errors but don't crash
			Log.e("NotificationUtils", "Error while posting notification id=$id", t)
		}
	}

	fun sendEventCreated(context: Context, eventId: Long, startMillis: Long, title: String, location: String?) {
		ensureChannels(context)
		val fmt = java.text.SimpleDateFormat("M月d日 H:mm", java.util.Locale.getDefault())
		val header = fmt.format(java.util.Date(startMillis)) + " 日历已创建"
		val content = title + (if (!location.isNullOrBlank()) " @ $location" else "")
		val deleteIntent = Intent(context, EventActionReceiver::class.java).apply {
			action = EventActionReceiver.ACTION_DELETE_EVENT
			putExtra(EventActionReceiver.EXTRA_EVENT_ID, eventId)
		}

		// Intent to view the created event in the calendar app when the notification body is clicked
		val viewIntent = Intent(Intent.ACTION_VIEW).apply {
			data = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
		}
		val viewFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		val viewPi = PendingIntent.getActivity(context, eventId.toInt(), viewIntent, viewFlags)

		val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		val pi = PendingIntent.getBroadcast(context, (eventId xor 0x777).toInt(), deleteIntent, flags)
		// Use the confirmation channel for created-event notifications to avoid duplicate channels
		val n = NotificationCompat.Builder(context, CHANNEL_CONFIRM)
			.setSmallIcon(R.mipmap.ic_launcher)
			.setContentTitle(header)
			.setContentText(content)
			.setStyle(NotificationCompat.BigTextStyle().bigText(content))
			.addAction(android.R.drawable.ic_menu_delete, "删除事件", pi)
			.setContentIntent(viewPi)
			.setAutoCancel(true)
			.build()
		safeNotify(context, ((eventId shl 1) % Int.MAX_VALUE).toInt(), n)
	}

	fun cancelEventNotifications(context: Context, eventId: Long) {
		// IDs used: confirmation (eventId % Int.MAX_VALUE); created ((eventId shl 1) % Int.MAX_VALUE)
		val nm = NotificationManagerCompat.from(context)
		nm.cancel((eventId % Int.MAX_VALUE).toInt())
		nm.cancel(((eventId shl 1) % Int.MAX_VALUE).toInt())
	}
}
