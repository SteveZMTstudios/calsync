package top.stevezmt.calsync

import android.content.Context
import androidx.core.content.edit

object ResultLogCache {
    private val deque = ArrayDeque<String>()
    private const val LIMIT = 50
    private const val ENTRY_MAX_CHARS = 1200
    private const val PREFS = "calsync_result_log_cache"
    private const val KEY_LOGS = "recent_results"
    private var loaded = false

    // Lazily loads user-facing parse/calendar results without mixing notification capture diagnostics.
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LOGS, null)
        if (!raw.isNullOrBlank()) {
            try {
                for (item in decode(raw)) {
                    if (isUserFacingResult(item)) deque.addLast(item)
                }
                while (deque.size > LIMIT) deque.removeFirst()
            } catch (_: Exception) {
            }
        }
        loaded = true
    }

    // Stores newest-first entries so the main screen can restore recent meaningful outcomes.
    fun add(context: Context, entry: String) {
        if (!isUserFacingResult(entry)) return
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
        prefs.edit { putString(KEY_LOGS, encode(deque)) }
    }

    private fun isUserFacingResult(entry: String): Boolean {
        val text = entry.trim()
        if (text.isBlank()) return false
        if (text.startsWith("未保存日程")) return false
        if (text.startsWith("未处理")) return false
        if (text.contains("未匹配关键字")) return false
        if (text.contains("未包含时间句子")) return false
        return true
    }

    // Minimal JSON-string-array reader keeps this cache usable in local JVM tests without Android org.json.
    private fun decode(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty() || text.first() != '[' || text.last() != ']') return emptyList()

        val out = mutableListOf<String>()
        val current = StringBuilder()
        var inString = false
        var escaping = false

        for (i in 1 until text.length - 1) {
            val ch = text[i]
            if (escaping) {
                current.append(unescape(ch))
                escaping = false
                continue
            }
            when {
                ch == '\\' && inString -> escaping = true
                ch == '"' -> {
                    if (inString) {
                        out += current.toString()
                        current.setLength(0)
                    }
                    inString = !inString
                }
                inString -> current.append(ch)
            }
        }
        return if (inString || escaping) emptyList() else out
    }

    // Writes the same compact array shape as the old JSONArray-based caches for easy migration.
    private fun encode(entries: Iterable<String>): String = buildString {
        append('[')
        entries.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            append('"')
            append(escape(entry))
            append('"')
        }
        append(']')
    }

    private fun escape(value: String): String = buildString {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }

    private fun unescape(ch: Char): Char {
        return when (ch) {
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            '\\', '"' -> ch
            else -> ch
        }
    }
}
