package top.stevezmt.calsync

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray

object NotificationCache {
    private val deque = ArrayDeque<String>()
    private const val LIMIT = 50
    private const val ENTRY_MAX_CHARS = 1200
    private const val PREFS = "calsync_log_cache"
    private const val KEY_LOGS = "recent_logs"
    private var loaded = false

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LOGS, null)
        if (!raw.isNullOrBlank()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i)
                    if (item.isNotBlank()) deque.addLast(item)
                }
                while (deque.size > LIMIT) deque.removeFirst()
            } catch (_: Exception) {}
        }
        loaded = true
    }

    fun add(context: Context, entry: String) {
        ensureLoaded(context)
        val safeEntry = if (entry.length > ENTRY_MAX_CHARS) entry.take(ENTRY_MAX_CHARS) + "..." else entry
        synchronized(deque) {
            deque.addFirst(safeEntry)
            while (deque.size > LIMIT) deque.removeLast()
            persist(context)
        }
    }

    fun snapshot(context: Context): List<String> {
        ensureLoaded(context)
        synchronized(deque) { return deque.toList() }
    }

    private fun persist(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray()
        deque.forEach { arr.put(it) }
        prefs.edit { putString(KEY_LOGS, arr.toString()) }
    }
}
