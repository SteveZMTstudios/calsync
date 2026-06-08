package top.stevezmt.calsync

import android.content.Context
import androidx.core.content.edit

object SettingsStore {
    private const val PREFS = "calsync_prefs"
    private const val KEY_KEYWORDS = "keywords"
    private const val KEY_CAL_ID = "calendar_id"
    private const val KEY_CAL_NAME = "calendar_name"
    private const val KEY_RELATIVE_WORDS = "relative_date_words"
    private const val KEY_CUSTOM_RULES = "custom_rules"
    private const val KEY_KEEP_ALIVE = "keep_alive"
    private const val KEY_SELECTED_APP_PKG = "selected_app_pkg"
    private const val KEY_SELECTED_APP_NAME = "selected_app_name"
    private const val KEY_SELECTED_APP_PKGS = "selected_app_pkgs" // comma separated list
    private const val KEY_SELECTED_APP_NAMES = "selected_app_names" // comma separated list parallel to pkgs
    private const val KEY_ENABLE_TIMENLP = "enable_timenlp"
    private const val KEY_PREFER_FUTURE = "prefer_future_option" // 0=auto,1=prefer future,2=disable
    private const val KEY_LAST_BACKUP_TS = "last_backup_ts"
    private const val KEY_LAST_BACKUP_NAME = "last_backup_name"
    private const val KEY_REMINDER_MINUTES = "reminder_minutes" // -1 for none, 0 for at time, >0 for minutes before
    private const val KEY_NOTIFICATION_QUEUE_MODE = "notification_queue_mode"
    private const val KEY_NOTIFICATION_QUEUE_TIMEOUT_SECONDS = "notification_queue_timeout_seconds"
    private const val KEY_NOTIFICATION_QUEUE_MAX_MESSAGES = "notification_queue_max_messages"
    private const val KEY_FUZZY_TIME_PAIRS = "fuzzy_time_pairs"

    // Parsing engines (extensible)
    private const val KEY_PARSING_ENGINE = "parsing_engine" // Int id, see ParseEngine
    private const val KEY_EVENT_ENGINE = "event_engine" // Int id, see EventParseEngine

    // Local AI model (optional)
    private const val KEY_AI_GGUF_URI = "ai_gguf_uri"
    private const val KEY_AI_SYSTEM_PROMPT = "ai_system_prompt"

    // Battery saver: lightweight guess before full parsing
    private const val KEY_GUESS_BEFORE_PARSE = "guess_before_parse"
    private const val KEY_PRIVACY_ACCEPTED = "privacy_accepted"

    data class FuzzyTimePair(val word: String, val minutesOfDay: Int)

    fun isPrivacyAccepted(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_PRIVACY_ACCEPTED, false)
    }

    fun setPrivacyAccepted(context: Context, accepted: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putBoolean(KEY_PRIVACY_ACCEPTED, accepted) }
    }

    fun getKeywords(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_KEYWORDS, null)
        return if (raw.isNullOrBlank()) {
            listOf("通知", "班级群")
        } else {
            raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    fun setKeywords(context: Context, keywords: List<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_KEYWORDS, keywords.joinToString(",")) }
    }

    fun setSelectedCalendar(context: Context, id: Long, name: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putLong(KEY_CAL_ID, id).putString(KEY_CAL_NAME, name) }
    }

    fun getSelectedCalendarId(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getLong(KEY_CAL_ID, -1L)
        return if (id <= 0) null else id
    }

    fun getSelectedCalendarName(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CAL_NAME, null)
    }

    fun getRelativeDateWords(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RELATIVE_WORDS, null)
        return if (raw.isNullOrBlank()) defaultRelativeWords() else raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun isTimeNLPEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLE_TIMENLP, true)
    }

    fun getParsingEngine(context: Context): ParseEngine {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ParseEngine.fromId(prefs.getInt(KEY_PARSING_ENGINE, ParseEngine.BUILTIN.id))
    }

    fun setParsingEngine(context: Context, engine: ParseEngine) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit {
            putInt(KEY_PARSING_ENGINE, engine.id)
            // Invariant: (datetime==AI/ML) <=> (event==AI/ML)
            when (engine) {
                ParseEngine.AI_GGUF -> putInt(KEY_EVENT_ENGINE, EventParseEngine.AI_GGUF.id)
                ParseEngine.ML_KIT -> putInt(KEY_EVENT_ENGINE, EventParseEngine.ML_KIT.id)
                else -> putInt(KEY_EVENT_ENGINE, EventParseEngine.BUILTIN.id)
            }
        }
    }

    fun getEventParsingEngine(context: Context): EventParseEngine {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return EventParseEngine.fromId(prefs.getInt(KEY_EVENT_ENGINE, EventParseEngine.BUILTIN.id))
    }

    fun setEventParsingEngine(context: Context, engine: EventParseEngine) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit {
            putInt(KEY_EVENT_ENGINE, engine.id)
            // Invariant: (datetime==AI/ML) <=> (event==AI/ML)
            when (engine) {
                EventParseEngine.AI_GGUF -> putInt(KEY_PARSING_ENGINE, ParseEngine.AI_GGUF.id)
                EventParseEngine.ML_KIT -> putInt(KEY_PARSING_ENGINE, ParseEngine.ML_KIT.id)
                else -> {
                    // If user turns off AI/ML for event parsing, turn off for datetime too.
                    val current = getParsingEngine(context)
                    if (current == ParseEngine.AI_GGUF || current == ParseEngine.ML_KIT) {
                        putInt(KEY_PARSING_ENGINE, ParseEngine.BUILTIN.id)
                    }
                }
            }
        }
    }

    fun getReminderMinutes(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_REMINDER_MINUTES, 10) // Default 10 minutes
    }

    fun setReminderMinutes(context: Context, minutes: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putInt(KEY_REMINDER_MINUTES, minutes) }
    }

    fun getNotificationQueueMode(context: Context): NotificationQueueMode {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return NotificationQueueMode.fromId(prefs.getInt(KEY_NOTIFICATION_QUEUE_MODE, NotificationQueueMode.OFF.id))
    }

    fun setNotificationQueueMode(context: Context, mode: NotificationQueueMode) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putInt(KEY_NOTIFICATION_QUEUE_MODE, mode.id) }
    }

    fun getNotificationQueueTimeoutSeconds(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_NOTIFICATION_QUEUE_TIMEOUT_SECONDS, 40).coerceIn(5, 300)
    }

    fun setNotificationQueueTimeoutSeconds(context: Context, seconds: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putInt(KEY_NOTIFICATION_QUEUE_TIMEOUT_SECONDS, seconds.coerceIn(5, 300)) }
    }

    fun getNotificationQueueMaxMessages(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_NOTIFICATION_QUEUE_MAX_MESSAGES, 3).coerceIn(2, 10)
    }

    fun setNotificationQueueMaxMessages(context: Context, maxMessages: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putInt(KEY_NOTIFICATION_QUEUE_MAX_MESSAGES, maxMessages.coerceIn(2, 10)) }
    }

    fun getFuzzyTimePairs(context: Context): List<FuzzyTimePair> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_FUZZY_TIME_PAIRS, null) ?: return defaultFuzzyTimePairs()
        return parseFuzzyTimePairs(raw).ifEmpty {
            if (raw.trim() == "[]") emptyList() else defaultFuzzyTimePairs()
        }
    }

    fun setFuzzyTimePairs(context: Context, pairs: List<FuzzyTimePair>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cleaned = cleanFuzzyTimePairs(pairs)
        prefs.edit { putString(KEY_FUZZY_TIME_PAIRS, fuzzyTimePairsToString(cleaned)) }
    }

    fun resetFuzzyTimePairs(context: Context) {
        setFuzzyTimePairs(context, defaultFuzzyTimePairs())
    }

    fun defaultFuzzyTimePairs(): List<FuzzyTimePair> = listOf(
        FuzzyTimePair("早上", 8 * 60),
        FuzzyTimePair("上午", 9 * 60),
        FuzzyTimePair("午休后", 13 * 60),
        FuzzyTimePair("下午", 13 * 60),
        FuzzyTimePair("晚上", 19 * 60)
    )

    fun cleanFuzzyTimePairs(pairs: List<FuzzyTimePair>): List<FuzzyTimePair> {
        val ordered = linkedMapOf<String, Int>()
        for (pair in pairs) {
            val word = pair.word.trim()
            if (word.isBlank()) continue
            if (pair.minutesOfDay !in 0..1439) continue
            ordered[word] = pair.minutesOfDay
        }
        return ordered.map { (word, minutes) -> FuzzyTimePair(word, minutes) }
    }

    fun parseFuzzyTimePairs(value: Any?): List<FuzzyTimePair> {
        return try {
            val pairs = mutableListOf<FuzzyTimePair>()
            when (value) {
                is String -> {
                    val trimmed = value.trim()
                    if (trimmed.startsWith("[")) {
                        Regex("\\{\\s*\"word\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"\\s*,\\s*\"minutes\"\\s*:\\s*(-?\\d+)\\s*}")
                            .findAll(trimmed)
                            .map { match ->
                                FuzzyTimePair(unescapeJsonString(match.groupValues[1]), match.groupValues[2].toIntOrNull() ?: -1)
                            }
                            .forEach { pairs += it }
                    } else {
                        trimmed.split(',', '，', ';', '；', '\n', '\r')
                            .mapNotNull { parseLooseFuzzyTimePair(it) }
                            .forEach { pairs += it }
                    }
                }
            }
            cleanFuzzyTimePairs(pairs)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseLooseFuzzyTimePair(raw: String): FuzzyTimePair? {
        val parts = raw.split("->", "=", ":", limit = 2).map { it.trim() }
        if (parts.size != 2) return null
        val minutes = parseTimeLabelToMinutes(parts[1]) ?: return null
        return FuzzyTimePair(parts[0], minutes)
    }

    private fun fuzzyTimePairsToString(pairs: List<FuzzyTimePair>): String {
        return cleanFuzzyTimePairs(pairs).joinToString(prefix = "[", postfix = "]") {
            "{\"word\":\"${escapeJsonString(it.word)}\",\"minutes\":${it.minutesOfDay}}"
        }
    }

    private fun escapeJsonString(value: String): String {
        return buildString {
            for (ch in value) {
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
    }

    private fun unescapeJsonString(value: String): String {
        val out = StringBuilder()
        var escaped = false
        for (ch in value) {
            if (escaped) {
                out.append(
                    when (ch) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        else -> ch
                    }
                )
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else {
                out.append(ch)
            }
        }
        if (escaped) out.append('\\')
        return out.toString()
    }

    fun parseTimeLabelToMinutes(label: String): Int? {
        val match = Regex("^(\\d{1,2})[:：](\\d{2})$").find(label.trim()) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    fun formatMinutesOfDay(minutes: Int): String {
        val safe = minutes.coerceIn(0, 1439)
        return "%02d:%02d".format(safe / 60, safe % 60)
    }

    fun isGuessBeforeParseEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_GUESS_BEFORE_PARSE, false)
    }

    fun setGuessBeforeParseEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putBoolean(KEY_GUESS_BEFORE_PARSE, enabled) }
    }

    fun getAiGgufModelUri(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_AI_GGUF_URI, null)
    }

    fun setAiGgufModelUri(context: Context, uri: String?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_AI_GGUF_URI, uri) }
    }

    fun getAiSystemPrompt(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_AI_SYSTEM_PROMPT, defaultAiSystemPrompt()) ?: defaultAiSystemPrompt()
    }

    fun setAiSystemPrompt(context: Context, prompt: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_AI_SYSTEM_PROMPT, prompt) }
    }

    private fun defaultAiSystemPrompt(): String {
        return """
你是一个日程解析器。请从输入文本中提取一个事件的开始时间(start)与结束时间(end)，并尽量给出简短标题(title)和地点(location)。
输出必须是 JSON：{\"start\":<epochMillis>,\"end\":<epochMillis|null>,\"title\":<string|null>,\"location\":<string|null>}。
若无法解析，输出空 JSON：{}。
""".trimIndent()
    }

    // preferFuture option: tri-state
    // 0 = Auto (let parser decide), 1 = Prefer future, 2 = Disable prefer future
    fun getPreferFutureOption(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_PREFER_FUTURE, 1) // default to 1 -> prefer future
    }

    fun setPreferFutureOption(context: Context, option: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putInt(KEY_PREFER_FUTURE, option) }
    }

    // Helper: returns nullable Boolean: null = Auto, true = prefer future, false = disable
    fun getPreferFutureBoolean(context: Context): Boolean? {
        return when (getPreferFutureOption(context)) {
            0 -> null
            1 -> true
            2 -> false
            else -> true
        }
    }

    fun setRelativeDateWords(context: Context, words: List<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_RELATIVE_WORDS, words.joinToString(",")) }
    }

    private fun defaultRelativeWords(): List<String> = listOf(
        "今天:0",
        "今晚:0:pm",
        "明早:1:am",
        "明天:1",
        "后天:2",
        "大后天:3",
        "下周:7"
    )

    fun resetRelativeWords(context: Context) {
        setRelativeDateWords(context, defaultRelativeWords())
    }

    fun isKeepAliveEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_KEEP_ALIVE, false)
    }

    fun setKeepAliveEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putBoolean(KEY_KEEP_ALIVE, enabled) }
    }

    fun getCustomRules(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CUSTOM_RULES, null)
        return if (raw.isNullOrBlank()) emptyList() else raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun setCustomRules(context: Context, rules: List<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_CUSTOM_RULES, rules.joinToString(",")) }
    }

    fun setSelectedSourceApp(context: Context, pkg: String?, name: String?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_SELECTED_APP_PKG, pkg).putString(KEY_SELECTED_APP_NAME, name) }
    }

    fun getSelectedSourceAppPkg(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SELECTED_APP_PKG, null)
    }

    fun getSelectedSourceAppName(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SELECTED_APP_NAME, null)
    }

    // ===== New multi-select APIs =====
    fun setSelectedSourceApps(context: Context, pkgs: List<String>, names: List<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit {
            putString(KEY_SELECTED_APP_PKGS, pkgs.joinToString(","))
                .putString(KEY_SELECTED_APP_NAMES, names.joinToString(","))
        }
    }

    fun getSelectedSourceAppPkgs(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SELECTED_APP_PKGS, null) ?: return legacySingleIfExists(context)
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun getSelectedSourceAppNames(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SELECTED_APP_NAMES, null) ?: return listOfNotNull(getSelectedSourceAppName(context))
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun legacySingleIfExists(context: Context): List<String> {
        val single = getSelectedSourceAppPkg(context)
        return if (single.isNullOrBlank()) emptyList() else listOf(single)
    }

    fun setLastBackupInfo(context: Context, timestamp: Long, displayName: String?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit {
            putLong(KEY_LAST_BACKUP_TS, timestamp)
            putString(KEY_LAST_BACKUP_NAME, displayName)
        }
    }

    fun getLastBackupTimestamp(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ts = prefs.getLong(KEY_LAST_BACKUP_TS, -1L)
        return if (ts <= 0) null else ts
    }

    fun getLastBackupName(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_BACKUP_NAME, null)
    }
}
