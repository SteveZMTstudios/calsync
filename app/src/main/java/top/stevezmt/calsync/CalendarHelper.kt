package top.stevezmt.calsync

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import java.util.TimeZone

object CalendarHelper {
    private const val TAG = "CalendarHelper"

    fun insertEvent(context: Context, title: String, description: String, startMillis: Long, endMillis: Long?, location: String? = null): Long? {
        try {
            val cr = context.contentResolver
            val selected = SettingsStore.getSelectedCalendarId(context)
            val calendarId = chooseCalendarId(selected, getPrimaryCalendarId(cr))
            if (calendarId == null) {
                Log.w(TAG, "No writable calendar found")
                return null
            }
            val reminderMinutes = SettingsStore.getReminderMinutes(context)
            val values = buildEventValues(
                title = title,
                description = description,
                startMillis = startMillis,
                endMillis = endMillis,
                calendarId = calendarId,
                location = location,
                reminderMinutes = reminderMinutes
            )
            val uri: Uri? = cr.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                Log.i(TAG, "Inserted event: $uri")
                val eventId = try {
                    android.content.ContentUris.parseId(uri)
                } catch (_: Exception) {
                    null
                }

                if (eventId != null) {
                    if (reminderMinutes >= 0) {
                        try {
                            val reminderValues = buildReminderValues(eventId, reminderMinutes)
                            cr.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
                            Log.i(TAG, "Added reminder: $reminderMinutes minutes before")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to add reminder", e)
                        }
                    }
                }
                return eventId
            } else {
                Log.w(TAG, "Failed to insert event")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing calendar permissions", e)
            try { NotificationUtils.sendError(context, e) } catch (_: Throwable) {}
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert event", e)
            try { NotificationUtils.sendError(context, e) } catch (_: Throwable) {}
        }
        return null
    }

    internal fun chooseCalendarId(selected: Long?, primary: Long?): Long? = selected ?: primary

    // Builds event fields in one place so calendar insertion behavior stays testable without Android providers.
    internal fun buildEventValues(
        title: String,
        description: String,
        startMillis: Long,
        endMillis: Long?,
        calendarId: Long,
        location: String?,
        reminderMinutes: Int
    ): ContentValues {
        return ContentValues().apply {
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis ?: (startMillis + 60 * 60 * 1000L))
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            if (!location.isNullOrBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (reminderMinutes >= 0) {
                put(CalendarContract.Events.HAS_ALARM, 1)
            }
        }
    }

    // Reminder insertion is separate from event insertion because some calendars reject reminder rows.
    internal fun buildReminderValues(eventId: Long, reminderMinutes: Int): ContentValues {
        return ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, reminderMinutes)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
    }

    data class CalendarInfo(val id: Long, val name: String, val accountEmail: String? = null) {
        val displayLabel: String
            get() = if (accountEmail.isNullOrBlank()) name else "$name\n$accountEmail"
    }

    // Lists visible calendars and carries account email separately so UI labels don't pollute saved names.
    fun listWritableCalendars(context: Context): List<CalendarInfo> {
        val cr = context.contentResolver
        val result = mutableListOf<CalendarInfo>()
        try {
            val uri = CalendarContract.Calendars.CONTENT_URI
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.OWNER_ACCOUNT,
                CalendarContract.Calendars.VISIBLE
            )
            val cursor = cr.query(uri, projection, "(${CalendarContract.Calendars.VISIBLE}=1)", null, null)
            cursor?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val accountName = c.getString(2)
                    val ownerAccount = c.getString(3)
                    val display = c.getString(1) ?: accountName ?: ownerAccount ?: "(未命名)"
                    val email = extractEmail(accountName) ?: extractEmail(ownerAccount)
                    result.add(CalendarInfo(id, display, email))
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "No permission to read calendars", e)
        }
        return result
    }

    internal fun extractEmail(value: String?): String? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        val match = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").find(text)
        return match?.value
    }

    private fun getPrimaryCalendarId(cr: android.content.ContentResolver): Long? {
        val uri = CalendarContract.Calendars.CONTENT_URI
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.VISIBLE)
        val cursor = cr.query(uri, projection, "((${CalendarContract.Calendars.VISIBLE}=1))", null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getLong(0)
            }
        }
        return null
    }
}
