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
}
