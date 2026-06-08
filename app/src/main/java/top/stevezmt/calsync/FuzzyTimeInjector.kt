package top.stevezmt.calsync

import android.content.Context

object FuzzyTimeInjector {
    data class Injection(
        val text: String,
        val word: String,
        val minutesOfDay: Int
    ) {
        val timeLabel: String = SettingsStore.formatMinutesOfDay(minutesOfDay)
        val logMessage: String = "已按\"$word=$timeLabel\"补全含糊时间"
    }

    private const val chineseNumberChars = "零一二三四五六七八九十百两"
    private val dateAnchorRegex = Regex(
        "(今天|明天|后天|大后天|今晚|明晚|今早|明早)" +
            "|(?:(?:本周|这周|下周|下下周)?(?:周|星期)[一二三四五六日天])" +
            "|(?:(?:本周|这周|下周|下下周)[一二三四五六日天])" +
            "|(?:(?:\\d{4}年)?(?:\\d{1,2}|[$chineseNumberChars]+)月(?:\\d{1,2}|[$chineseNumberChars]+)[日号]?)" +
            "|(?:\\d{1,2}[./-]\\d{1,2})"
    )
    private val explicitClockRegex = Regex(
        "(?:(?:上午|下午|中午|晚上|凌晨|早上|今晚|明晚)\\s*)?" +
            "(?:[0-2]?\\d|[$chineseNumberChars]+)" +
            "\\s*(?:(?:[:：]\\s*[0-5]?\\d)|点\\s*半?|半)"
    )

    fun inject(context: Context, text: String): Injection? {
        return inject(text, SettingsStore.getFuzzyTimePairs(context))
    }

    fun canInject(context: Context, text: String): Boolean {
        return inject(context, text) != null
    }

    fun inject(text: String, pairs: List<SettingsStore.FuzzyTimePair>): Injection? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null
        if (!hasDateAnchor(trimmed)) return null
        if (hasExplicitClock(trimmed)) return null

        val match = findBestWordMatch(trimmed, pairs) ?: return null
        val label = SettingsStore.formatMinutesOfDay(match.pair.minutesOfDay)
        val injected = trimmed.substring(0, match.end) + label + trimmed.substring(match.end)
        return Injection(injected, match.pair.word, match.pair.minutesOfDay)
    }

    fun hasDateAnchor(text: String): Boolean = dateAnchorRegex.containsMatchIn(text)

    fun hasExplicitClock(text: String): Boolean {
        return explicitClockRegex.findAll(text).any { match ->
            val value = match.value
            val end = match.range.last + 1
            val next = text.getOrNull(end)
            val containsDelimiter = value.contains(':') || value.contains('：') || value.contains('点') || value.contains('半')
            val followedByDigitWithoutDelimiter = next?.isDigit() == true && !containsDelimiter
            !followedByDigitWithoutDelimiter
        }
    }

    private data class WordMatch(
        val pair: SettingsStore.FuzzyTimePair,
        val start: Int,
        val end: Int
    )

    private fun findBestWordMatch(text: String, pairs: List<SettingsStore.FuzzyTimePair>): WordMatch? {
        return SettingsStore.cleanFuzzyTimePairs(pairs)
            .mapNotNull { pair ->
                val index = text.indexOf(pair.word)
                if (index < 0) null else WordMatch(pair, index, index + pair.word.length)
            }
            .minWithOrNull(compareBy<WordMatch> { it.start }.thenByDescending { it.pair.word.length })
    }
}
