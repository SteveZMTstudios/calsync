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

    // Parsing engines (extensible)
    private const val KEY_PARSING_ENGINE = "parsing_engine" // Int id, see ParseEngine
    private const val KEY_EVENT_ENGINE = "event_engine" // Int id, see EventParseEngine

    // Local AI model (optional)
    private const val KEY_AI_GGUF_URI = "ai_gguf_uri"
    private const val KEY_AI_SYSTEM_PROMPT = "ai_system_prompt"

    // Battery saver: lightweight guess before full parsing
    private const val KEY_GUESS_BEFORE_PARSE = "guess_before_parse"
    private const val KEY_PRIVACY_ACCEPTED = "privacy_accepted"

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
