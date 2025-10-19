package top.stevezmt.calsync

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

object DateTimeParser {
    private const val TAG = "DateTimeParser"

    // Public helper: expose current time used by parser (wall-clock now)
    // Returns current time in milliseconds (Calendar.getInstance())
    @JvmStatic
    fun getNowMillis(): Long = Calendar.getInstance().timeInMillis

    @JvmStatic
    fun getNowFormatted(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return fmt.format(Date(getNowMillis()))
    }

    data class ParseResult(val startMillis: Long, val endMillis: Long?, val title: String? = null, val location: String? = null)

    // Patterns for Chinese-style dates/times. These are examples and should be extended.
    // Accept both ASCII colon and fullwidth colon
    private const val colon = "[:：]"
    private val monthDayPattern = Pattern.compile("(\\d{1,2}|[一二三四五六七八九十百]+)月(\\d{1,2}|[一二三四五六七八九十]+)[日号]?")
    // Require end part to have '日/号' and not be followed by letter to avoid matching like '到21B6教室'
    private val monthDayRangePattern = Pattern.compile("(\\d{1,2}|[一二三四五六七八九十百]+)月(\\d{1,2}|[一二三四五六七八九十]+)[日号]?\\s*[~-至到]+\\s*(\\d{1,2}|[一二三四五六七八九十百]+)月?(\\d{1,2}|[一二三四五六七八九十]+)[日号](?![A-Za-z])")
    // unified time pattern: optional am/pm token, hour (arabic or chinese numerals), optional minute
    // Added 今晚 / 明晚 to capture evening context directly so "今晚8点" 不再被误判为上午 8 点
    private val timePattern = Pattern.compile("(上午|下午|中午|晚上|凌晨|今晚|明晚)?\\s*([0-9]{1,2}|[一二三四五六七八九十百]+)(?:${colon}([0-5]?\\d))?点?")
    private val weekdayTimePattern = Pattern.compile("((?:周|星期)[一二三四五六日天])(?:[上下午]|上午|下午)?\\s*(\\d{1,2})${colon}(\\d{1,2})")

    // Extract ALL sentences (segments bounded by punctuation) that contain date/time-like info
    fun extractAllSentencesContainingDate(context: android.content.Context, text: String): List<String> {
    val delimiters = Regex("[。！？.!?；;，,]\\s*")
        val parts = delimiters.split(text)
        val out = mutableListOf<String>()
        for (p in parts) {
            if (p.isBlank()) continue
            if (containsDateLike(context, p)) out.add(p.trim())
        }
        return out
    }

    private fun containsDateLike(context: android.content.Context, s: String): Boolean {
        // check custom rules first
        val custom = SettingsStore.getCustomRules(context)
        for (rule in custom) {
            try {
                val p = Pattern.compile(rule)
                if (p.matcher(s).find()) return true
            } catch (_: Exception) {
            }
        }
        // Countdown style (超星): 还有X天 / 还有X个小时 / 还有X分钟 / 还有X分 / 还有X秒
        if (Regex("还有[一二三四五六七八九十百零0-9]+(个)?天").containsMatchIn(s)
            || Regex("还有[一二三四五六七八九十百零0-9]+(个)?小时").containsMatchIn(s)
            || Regex("还有[一二三四五六七八九十百零0-9]+(个)?分钟?").containsMatchIn(s)
            || Regex("还有[一二三四五六七八九十百零0-9]+(个)?秒").containsMatchIn(s)) {
            return true
        }
        // First check date-related explicit patterns (exclude timePattern for additional validation)
        val dateLike = listOf(monthDayPattern, monthDayRangePattern, weekdayTimePattern)
        for (pat in dateLike) {
            if (pat.matcher(s).find()) return true
        }
        // Refined time detection: ensure a time match has explicit indicator (ampm token / colon / 点 / minutes) and valid hour 0-23
        val tm = timePattern.matcher(s)
        while (tm.find()) {
            val matched = tm.group()
            val ampm = tm.group(1)
            val hourStr = tm.group(2)
            val minuteStr = tm.group(3)
            val hour = hourStr?.let { if (it.matches(Regex("\\d+"))) it.toInt() else toArabic(it) } ?: -1
            val hasIndicator = ampm != null || minuteStr != null || matched.contains("点") || matched.contains(":") || matched.contains("：")
            // Guard: avoid matching inside longer numbers like "下午104" (treat as '10' followed by '4')
            val hourEnd = try { tm.end(2) } catch (_: Throwable) { -1 }
            val nextCh = if (hourEnd in 0 until s.length) s[hourEnd] else null
            val followedByDigitWithoutDelimiter = nextCh?.isDigit() == true && !matched.contains(":") && !matched.contains("：") && !matched.contains("点")
            if (hasIndicator && hour in 0..23 && !followedByDigitWithoutDelimiter) return true
        }
        return false
    }

    // === Public APIs (unchanged signature) ===
    fun parseDateTime(sentence: String): ParseResult? = RuleBasedStrategy.tryParseStandalone(sentence)

    // Overload: allow passing a fixed baseMillis so all calculations in this call share the same "now"
    fun parseDateTime(context: android.content.Context, sentence: String, baseMillis: Long): ParseResult? {
        // Prefer explicit rule-based parsing first (handles tokens like 周五/本周五 reliably).
        try {
            val rule = RuleBasedStrategyWithContext(context)
            val r = rule.tryParseWithBase(sentence, baseMillis)
            if (r != null) return r
        } catch (e: Exception) {
            Log.w(TAG, "RuleBaseCtx(withBase) failed: ${e.message}")
            try { NotificationUtils.sendError(context, e) } catch (_: Throwable) {}
        }

        // Fallback to TimeNLP only if enabled and rule-based didn't match
        if (SettingsStore.isTimeNLPEnabled(context)) {
            // Heuristic: 如果像“下午104的课挪至207进行”这类仅有地点/教室变更且没有明确时间/日期，不要回退到 TimeNLP，避免误触发
            if (shouldSkipTimeNLPFallback(sentence)) return null
            try {
                val nlp = TimeNLPStrategy(context)
                val r2 = nlp.tryParseWithBase(sentence, baseMillis)
                if (r2 != null) return r2
            } catch (e: Exception) {
                Log.w(TAG, "TimeNLP(withBase) failed: ${e.message}")
                try { NotificationUtils.sendError(context, e) } catch (_: Throwable) {}
            }
        }
        return null
    }

    // === Strategy interfaces ===
    private interface ParsingStrategy {
        fun name(): String
        fun tryParse(sentence: String): ParseResult?
    }

    // TimeNLP-based strategy
    private class TimeNLPStrategy(private val context: android.content.Context): ParsingStrategy {
        override fun name() = "TimeNLP"
        override fun tryParse(sentence: String): ParseResult? {
            val slots = TimeNLPAdapter.parse(sentence)
            if (slots.isEmpty()) return null
            val s = slots.first()
            val (t, loc) = extractTitleAndLocation(sentence)
            return ParseResult(s.startMillis, s.endMillis, t, loc)
        }
        fun tryParseWithBase(sentence: String, baseMillis: Long): ParseResult? {
            val slots = TimeNLPAdapter.parse(sentence, baseMillis)
            if (slots.isEmpty()) return null
            val s = slots.first()
            val (t, loc) = extractTitleAndLocation(sentence)
            return ParseResult(s.startMillis, s.endMillis, t, loc)
        }
    }

    // Original rule-based without context (legacy API)
    private object RuleBasedStrategy: ParsingStrategy {
        override fun name() = "RuleBaseNoCtx"
        override fun tryParse(sentence: String): ParseResult? = parseDateTimeInternal(
            sentence,
            relativeMap = buildDefaultRelativeTokenMap(),
            baseMillis = null,
            preferFutureOpt = null
        )
        fun tryParseStandalone(sentence: String) = tryParse(sentence)
    }

    // Rule-based with custom settings context
    private class RuleBasedStrategyWithContext(private val ctx: android.content.Context): ParsingStrategy {
        override fun name() = "RuleBaseCtx"
        override fun tryParse(sentence: String): ParseResult? {
            val map = buildRelativeTokenMap(ctx)
            // read preferFuture tri-state from settings (null=auto, true=prefer future, false=disable)
            val prefer = SettingsStore.getPreferFutureBoolean(ctx)
            return parseDateTimeInternal(sentence, map, baseMillis = null, preferFutureOpt = prefer)
        }
        fun tryParseWithBase(sentence: String, baseMillis: Long): ParseResult? {
            val map = buildRelativeTokenMap(ctx)
            val prefer = SettingsStore.getPreferFutureBoolean(ctx)
            return parseDateTimeInternal(sentence, map, baseMillis, prefer)
        }
    }

    private data class RelativeSpec(val offsetDays: Int, val ampm: String?)

    private fun buildRelativeTokenMap(context: android.content.Context): LinkedHashMap<String, RelativeSpec> {
        // User now inputs tokens in a structured string list in Settings (we still store plain list for compatibility)
        // Expected format each item: token[:offset[:ampm]] or JSON-like ["今晚":0,pm]; but to keep backward compat we parse heuristically.
        val rawList = SettingsStore.getRelativeDateWords(context)
        val map = linkedMapOf<String, RelativeSpec>()
        for (raw in rawList.sortedByDescending { it.length }) { // prefer longer first
            val cleaned = raw.trim().trim('[',']',';')
            // patterns: "今晚":0,pm or 今晚:0:pm or 今晚:0 or 今晚
            val colonSplit = cleaned.split(':')
            var token = cleaned
            var offset = 0
            var ampm: String? = null
            try {
                if (colonSplit.isNotEmpty()) token = colonSplit[0].substringAfter('"').substringBeforeLast('"').ifBlank { colonSplit[0] }
                if (colonSplit.size >= 2) offset = colonSplit[1].split(',')[0].filter { it.isDigit() || it == '-' }.toIntOrNull() ?: 0
                // find am/pm marker (am/pm) after comma or third part
                if (colonSplit.size >= 3) {
                    ampm = colonSplit[2].lowercase().takeIf { it == "am" || it == "pm" }
                } else if (cleaned.contains(",pm", true)) ampm = "pm" else if (cleaned.contains(",am", true)) ampm = "am"
            } catch (_: Exception) {}
            if (token.isNotBlank()) map[token] = RelativeSpec(offset, ampm)
        }
        // defaults if user list empty
        if (map.isEmpty()) {
            map["今天"] = RelativeSpec(0,null)
            map["今晚"] = RelativeSpec(0,"pm")
            map["明天"] = RelativeSpec(1,null)
            map["明晚"] = RelativeSpec(1,"pm")
            map["后天"] = RelativeSpec(2,null)
            map["大后天"] = RelativeSpec(3,null)
            map["明早"] = RelativeSpec(1,"am")
            map["明晨"] = RelativeSpec(1,"am")
            map["明午"] = RelativeSpec(1,"pm")
        }
        return map
    }

    // Default relative tokens when no Settings context is available (for standalone API)
    private fun buildDefaultRelativeTokenMap(): LinkedHashMap<String, RelativeSpec> {
        val map = linkedMapOf<String, RelativeSpec>()
        // Order matters: prefer specific time-of-day tokens first, then day tokens
        map["今晚"] = RelativeSpec(0, "pm")
        map["明晚"] = RelativeSpec(1, "pm")
        map["下午"] = RelativeSpec(0, "pm")
        map["上午"] = RelativeSpec(0, "am")
        map["中午"] = RelativeSpec(0, "pm")
        map["凌晨"] = RelativeSpec(0, null)
        map["今天"] = RelativeSpec(0, null)
        map["明天"] = RelativeSpec(1, null)
        map["后天"] = RelativeSpec(2, null)
        map["大后天"] = RelativeSpec(3, null)
        return map
    }

    // Create Calendar with optional fixed base time
    private fun newCal(baseMillis: Long?): Calendar {
        val c = Calendar.getInstance()
        if (baseMillis != null) c.timeInMillis = baseMillis
        return c
    }

    private fun parseDateTimeInternal(
        sentence: String,
        relativeMap: LinkedHashMap<String, RelativeSpec>? = null,
        baseMillis: Long? = null,
        preferFutureOpt: Boolean? = null,
    ): ParseResult? {
        Log.d(TAG, "parseDateTimeInternal - input: '$sentence'")
        try {
            val now = newCal(baseMillis)
            // -1) Deadline style: "截止到/截至(到) 10月27日( HH:mm)?" -> end at that time (default 23:59), start = end - 30min
            run {
                val deadRe = Regex("(截止(?:到)?|截至(?:到)?)\\s*(?:于)?\\s*(\\d{1,2}|[一二三四五六七八九十百]+)月(\\d{1,2}|[一二三四五六七八九十]+)[日号]?(?:\\s*(上午|下午|中午|晚上|凌晨)?\\s*([0-2]?\\d)(?:$colon([0-5]?\\d))?)?")
                val dm = deadRe.find(sentence)
                if (dm != null) {
                    val mo = toArabic(dm.groupValues[2])
                    val dd = toArabic(dm.groupValues[3])
                    val ampm = dm.groupValues.getOrNull(4)?.ifBlank { null }
                    val hhStr = dm.groupValues.getOrNull(5)?.ifBlank { null }
                    val mmStr = dm.groupValues.getOrNull(6)?.ifBlank { null }
                    val endCal = newCal(baseMillis)
                    endCal.set(Calendar.MONTH, mo - 1)
                    endCal.set(Calendar.DAY_OF_MONTH, dd)
                    if (hhStr != null) {
                        val hh = hhStr.toIntOrNull() ?: 0
                        val adj = adjustHourByAmPm(hh, ampm)
                        endCal.set(Calendar.HOUR_OF_DAY, adj)
                        endCal.set(Calendar.MINUTE, mmStr?.toIntOrNull() ?: 0)
                    } else {
                        // no time -> default to 23:59
                        endCal.set(Calendar.HOUR_OF_DAY, 23)
                        endCal.set(Calendar.MINUTE, 59)
                    }
                    endCal.set(Calendar.SECOND, 0)
                    val end = endCal.timeInMillis
                    val start = end - 30 * 60 * 1000L
                    val (t, loc) = extractTitleAndLocation(sentence)
                    return ParseResult(start, end, t ?: "截止", loc)
                }
            }
            // preferFuture: 尝试从 SettingsStore 取，失败则默认 true（行为参考 xk-time TimeNLP 的 isPreferFuture）
            // preferFuture 智能模式：
            //  - preferFutureOpt == true : 始终向未来滚动（旧行为）
            //  - preferFutureOpt == false: 绝不向未来滚动（保留已过去时间）
            //  - preferFutureOpt == null : AUTO 模式
            //      * 对“仅时间”表达式: 若目标时间已过去且距离当前 < 12 小时 => 滚动到下一天；>=12 小时则认为是未来当日的真正上午/下午且不滚动
            //      * 对“月日”表达式（不含年份）: 若日期已过去且距离当前 < 30 天 => 推到下一年，否则不滚动
            val preferFutureRaw = preferFutureOpt ?: true
            val autoMode = (preferFutureOpt == null)

            // --- (A) 相对偏移解析 参考 xk-time TimeNLP 中 normBaseRelated / normBaseTimeRelated / normCurRelated 的语义思想 ---
            // 支持: 3天后 / 2小时后 / 1个半小时后 / 30分钟后 / 10分钟30秒后 / 半小时后 / 45秒后 / 2天3小时20分钟后
            // 以及 X天前 / X小时前 / X分钟前 / X秒前
            parseRelativeOffset(sentence, baseMillis)?.let { return it }

            // Chaoxing style countdown: 还有24个小时 / 还有2天3小时 / 还有90分钟 / 还有1天2小时30分钟5秒
            // Use a simple, balanced regex to quickly detect countdown phrases (we parse units sequentially below).
            // Simpler robust approach: use iterative token extraction
            // Instead of a single giant regex (容易错), we parse sequentially.
            if (sentence.contains("还有") && sentence.contains("小时") || sentence.contains("还有") && sentence.contains("天") || sentence.contains("还有") && sentence.contains("分钟") || sentence.contains("还有") && sentence.contains("分") || sentence.contains("还有") && sentence.contains("秒")) {
                var remain = sentence.substring(sentence.indexOf("还有"))
                var days = 0; var hours = 0; var minutes = 0; var seconds = 0
                fun extract(re: Regex, assign: (Int)->Unit) {
                    val m = re.find(remain)
                    if (m != null) {
                        val numStr = m.groupValues.filter { it.isNotBlank() }.drop(1).firstOrNull() ?: m.groupValues.getOrNull(1)
                        val v = toArabic(numStr)
                        assign(v)
                        // remove matched segment to avoid double counting
                        val idx = m.range.first
                        val end = m.range.last + 1
                        remain = remain.removeRange(idx, end)
                    }
                }
                extract(Regex("还有([一二三四五六七八九十百零0-9]+)个?天")) { days = it }
                extract(Regex("([一二三四五六七八九十百零0-9]+)个?小时")) { hours = it }
                extract(Regex("([一二三四五六七八九十百零0-9]+)个?分" +
                        "钟?")) { minutes = it }
                extract(Regex("([一二三四五六七八九十百零0-9]+)个?秒")) { seconds = it }
                // 还有24个小时 -> days=0 hours=24
                if (days + hours + minutes + seconds > 0) {
                    Log.d(TAG, "countdown detected: days=$days hours=$hours minutes=$minutes seconds=$seconds")
                    val deadline = newCal(baseMillis).apply {
                        add(Calendar.DAY_OF_MONTH, days)
                        add(Calendar.HOUR_OF_DAY, hours)
                        add(Calendar.MINUTE, minutes)
                        add(Calendar.SECOND, seconds)
                    }
                    val start = deadline.timeInMillis - 30*60*1000L // arbitrary start: deadline 前 30 分钟
                    val (t, loc) = extractTitleAndLocation(sentence)
                    val title = t ?: "截止" // Provide a neutral title if none
                    Log.d(TAG, "countdown parsed: start=${Date(start)} end=${Date(deadline.timeInMillis)} title=$title loc=$loc")
                    return ParseResult(start, deadline.timeInMillis, title, loc)
                }
            }

            // 0) Detect explicit start/end datetime blocks like:
            // 开始时间：2025-09-16 10:49
            // 结束时间：2025-09-19 10:49
            try {
                val startRe = Regex("开始(?:时间)?\\s*[:：]\\s*(\\d{4}-\\d{1,2}-\\d{1,2})\\s*([0-2]?\\d[:：][0-5]\\d)")
                val endRe = Regex("结束(?:时间)?\\s*[:：]\\s*(\\d{4}-\\d{1,2}-\\d{1,2})\\s*([0-2]?\\d[:：][0-5]\\d)")
                val sMatch = startRe.find(sentence)
                val eMatch = endRe.find(sentence)
                if (sMatch != null && eMatch != null) {
                    val sDate = sMatch.groupValues[1].trim()
                    val sTime = sMatch.groupValues[2].replace('：', ':').trim()
                    val eDate = eMatch.groupValues[1].trim()
                    val eTime = eMatch.groupValues[2].replace('：', ':').trim()
                    fun norm(dtDate: String, dtTime: String): String {
                        val parts = dtDate.split('-')
                        val y = parts.getOrNull(0) ?: "0"
                        val m = parts.getOrNull(1)?.padStart(2, '0') ?: "01"
                        val d = parts.getOrNull(2)?.padStart(2, '0') ?: "01"
                        return "$y-$m-$d $dtTime"
                    }
                    val startStr = norm(sDate, sTime)
                    val endStr = norm(eDate, eTime)
                    try {
                        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        val startDt = fmt.parse(startStr)
                        val endDt = fmt.parse(endStr)
                        if (startDt != null && endDt != null) {
                            Log.d(TAG, "explicit datetime range parsed: start=$startStr end=$endStr")
                            val (t, loc) = extractTitleAndLocation(sentence)
                            return ParseResult(startDt.time, endDt.time, t, loc)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "failed to parse explicit datetime strings: $startStr / $endStr", e)
                    }
                } else if (sMatch != null || eMatch != null) {
                    val m = sMatch ?: eMatch!!
                    val dateStr = m.groupValues[1].trim()
                    val timeStr = m.groupValues[2].replace('：', ':').trim()
                    val parts = dateStr.split('-')
                    val y = parts.getOrNull(0) ?: "0"
                    val mo = parts.getOrNull(1)?.padStart(2, '0') ?: "01"
                    val d = parts.getOrNull(2)?.padStart(2, '0') ?: "01"
                    val dtStr = "$y-$mo-$d $timeStr"
                    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    val dt = fmt.parse(dtStr)
                    if (dt != null) {
                        val (t, loc) = extractTitleAndLocation(sentence)
                        return ParseResult(dt.time, dt.time + 60 * 60 * 1000L, t, loc)
                    }
                }
            } catch (_: Exception) {}

            Log.d(TAG, "trying explicit month/day and other patterns")

            // 0.5) 截止到/至 XX-月-日( 时间)? -> 采用当天 23:59（若无时间），作为 end；start 取 end - 1h
            run {
                val deadlineRe = Regex("截止(?:到|至)\\s*(?:(\\d{4})年)?\\s*(\\d{1,2}|[一二三四五六七八九十百]+)月(\\d{1,2}|[一二三四五六七八九十]+)[日号]?\\s*(?:(上午|下午|中午|晚上|凌晨)?\\s*([0-9]{1,2})(?:$colon([0-5]?\\d))?点?)?")
                val m = deadlineRe.find(sentence)
                if (m != null) {
                    val yearStr = m.groupValues.getOrNull(1)
                    val moStr = m.groupValues.getOrNull(2)
                    val dStr = m.groupValues.getOrNull(3)
                    val ampm = m.groupValues.getOrNull(4)
                    val hStr = m.groupValues.getOrNull(5)
                    val minStr = m.groupValues.getOrNull(6)
                    val cal = newCal(baseMillis)
                    val year = yearStr?.toIntOrNull()
                    if (year != null) cal.set(Calendar.YEAR, year)
                    cal.set(Calendar.MONTH, toArabic(moStr) - 1)
                    cal.set(Calendar.DAY_OF_MONTH, toArabic(dStr))
                    if (!hStr.isNullOrBlank()) {
                        val h = hStr.toIntOrNull() ?: toArabic(hStr)
                        val minute = minStr?.toIntOrNull() ?: 0
                        cal.set(Calendar.HOUR_OF_DAY, adjustHourByAmPm(h, ampm))
                        cal.set(Calendar.MINUTE, minute)
                        cal.set(Calendar.SECOND, 0)
                    } else {
                        // 无显式时间，采用当天 23:59
                        cal.set(Calendar.HOUR_OF_DAY, 23)
                        cal.set(Calendar.MINUTE, 59)
                        cal.set(Calendar.SECOND, 0)
                    }
                    val end = cal.timeInMillis
                    val start = end - 60*60*1000L
                    val (t, loc) = extractTitleAndLocation(sentence)
                    val title = t ?: "截止"
                    return ParseResult(start, end, title, loc)
                }
            }

            // 0.7) 相对月份 + 日: 上/本/这/下 个月X日
            run {
                val relMonthDayRe = Regex("(上|本|这|下)个?月\\s*([0-9一二三四五六七八九十零两]{1,2})[日号]?")
                val rm = relMonthDayRe.find(sentence)
                if (rm != null) {
                    val flag = rm.groupValues.getOrNull(1) ?: "本"
                    val dayStr = rm.groupValues.getOrNull(2)
                    val offset = when (flag) {
                        "上" -> -1
                        "下" -> 1
                        else -> 0
                    }
                    val cal = newCal(baseMillis)
                    cal.add(Calendar.MONTH, offset)
                    // clamp day within month
                    val day = toArabic(dayStr)
                    val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    cal.set(Calendar.DAY_OF_MONTH, day.coerceIn(1, maxDay))
                    // default time 09:00, try find explicit time in sentence
                    cal.set(Calendar.HOUR_OF_DAY, 9)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    run {
                        val timeM = timePattern.matcher(sentence)
                        var chosenHour: Int? = null
                        var chosenMinute = 0
                        while (timeM.find()) {
                            val matched = timeM.group()
                            val ampm = timeM.group(1)
                            val hourStr = timeM.group(2)
                            val minStr = timeM.group(3)
                            val hasIndicator = ampm != null || minStr != null || matched.contains("点") || matched.contains(":") || matched.contains("：")
                            val hour = hourStr?.let { if (it.matches(Regex("\\d+"))) it.toInt() else toArabic(it) } ?: continue
                            val hourEnd = try { timeM.end(2) } catch (_: Throwable) { -1 }
                            val nextCh = if (hourEnd in 0 until sentence.length) sentence[hourEnd] else null
                            val followedByDigitWithoutDelimiter = nextCh?.isDigit() == true && !matched.contains(":") && !matched.contains("：") && !matched.contains("点")
                            if (!hasIndicator || hour !in 0..23 || followedByDigitWithoutDelimiter) continue
                            chosenHour = adjustHourByAmPm(hour, ampm)
                            chosenMinute = minStr?.toIntOrNull() ?: 0
                            if (ampm != null || minStr != null || matched.contains(":") || matched.contains("：")) break
                        }
                        if (chosenHour != null) {
                            cal.set(Calendar.HOUR_OF_DAY, chosenHour!!)
                            cal.set(Calendar.MINUTE, chosenMinute)
                        }
                    }
                    val start = cal.timeInMillis
                    val end = start + 60 * 60 * 1000L
                    val (t, loc) = extractTitleAndLocation(sentence)
                    Log.d(TAG, "relative month+day matched: ${Date(start)}")
                    return ParseResult(start, end, t, loc)
                }
            }
            // 1) Try explicit month/day range
            run {
                val range = monthDayRangePattern.matcher(sentence)
                while (range.find()) {
                    val matchedStr = range.group()
                    // 若使用了“到”作为分隔，但右侧不包含“月/日/号”，多半是“到XX教室/地点”，忽略该 range
                    if (matchedStr.contains('到')) {
                        val after = matchedStr.substringAfter('到')
                        if (!after.contains("月") && !after.contains("日") && !after.contains("号")) {
                            continue
                        }
                    }
                    val startMonth = toArabic(range.group(1))
                    val startDay = toArabic(range.group(2))
                    val endMonth = toArabic(range.group(3))
                    val endDay = toArabic(range.group(4))
                    val startCal = newCal(baseMillis)
                    startCal.set(Calendar.MONTH, startMonth - 1)
                    startCal.set(Calendar.DAY_OF_MONTH, startDay)
                    startCal.set(Calendar.HOUR_OF_DAY, 9)
                    startCal.set(Calendar.MINUTE, 0)
                    startCal.set(Calendar.SECOND, 0)

                    val endCal = newCal(baseMillis)
                    endCal.set(Calendar.MONTH, endMonth - 1)
                    endCal.set(Calendar.DAY_OF_MONTH, endDay)
                    endCal.set(Calendar.HOUR_OF_DAY, 17)
                    endCal.set(Calendar.MINUTE, 0)
                    endCal.set(Calendar.SECOND, 0)

                    val (t, loc) = extractTitleAndLocation(sentence)
                    Log.d(TAG, "month/day range matched: start=${Date(startCal.timeInMillis)} end=${Date(endCal.timeInMillis)}")
                    return ParseResult(startCal.timeInMillis, endCal.timeInMillis, t, loc)
                }
            }

            // 2) Try month/day
            val m = monthDayPattern.matcher(sentence)
            if (m.find()) {
                val month = toArabic(m.group(1))
                val day = toArabic(m.group(2))
                val cal = newCal(baseMillis)
                cal.set(Calendar.MONTH, month - 1)
                cal.set(Calendar.DAY_OF_MONTH, day)
                // default time if none
                cal.set(Calendar.HOUR_OF_DAY, 9)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                // try find a valid time-of-day in same sentence (must have indicator; skip false positives like "10月"/"下午104")
                run {
                    val timeM = timePattern.matcher(sentence)
                    var chosenHour: Int? = null
                    var chosenMinute = 0
                    while (timeM.find()) {
                        val matched = timeM.group()
                        val ampm = timeM.group(1)
                        val hourStr = timeM.group(2)
                        val minStr = timeM.group(3)
                        val hasIndicator = ampm != null || minStr != null || matched.contains("点") || matched.contains(":") || matched.contains("：")
                        val hour = hourStr?.let { if (it.matches(Regex("\\d+"))) it.toInt() else toArabic(it) } ?: continue
                        val hourEnd = try { timeM.end(2) } catch (_: Throwable) { -1 }
                        val nextCh = if (hourEnd in 0 until sentence.length) sentence[hourEnd] else null
                        val followedByDigitWithoutDelimiter = nextCh?.isDigit() == true && !matched.contains(":") && !matched.contains("：") && !matched.contains("点")
                        if (!hasIndicator || hour !in 0..23 || followedByDigitWithoutDelimiter) continue
                        chosenHour = adjustHourByAmPm(hour, ampm)
                        chosenMinute = minStr?.toIntOrNull() ?: 0
                        // prefer the first strong indicator (with minutes or am/pm)
                        if (ampm != null || minStr != null || matched.contains(":") || matched.contains("：")) break
                    }
                    if (chosenHour != null) {
                        cal.set(Calendar.HOUR_OF_DAY, chosenHour!!)
                        cal.set(Calendar.MINUTE, chosenMinute)
                    }
                }
                if (cal.timeInMillis < now.timeInMillis) {
                    if (preferFutureRaw && !autoMode) {
                        // 强制偏未来: 直接 +1 年
                        cal.add(Calendar.YEAR, 1)
                    } else if (autoMode) {
                        // AUTO: 仅在距离当前 < 30 天认为用户忘写年份（跨年临近）
                        val diffDays = ((now.timeInMillis - cal.timeInMillis) / (24*3600*1000L)).toInt()
                        if (diffDays in 0..29) {
                            cal.add(Calendar.YEAR, 1)
                        }
                    }
                }
                val end = cal.timeInMillis + 60 * 60 * 1000L
                val (t, loc) = extractTitleAndLocation(sentence)
                Log.d(TAG, "month/day matched: ${Date(cal.timeInMillis)}")
                return ParseResult(cal.timeInMillis, end, t, loc)
            }

            // 3) weekday + time
            val w = weekdayTimePattern.matcher(sentence)
            if (w.find()) {
                val dayToken = w.group(1)
                val hourStr = w.group(2)
                val minuteStr = w.group(3)
                if (hourStr != null) {
                    val hour = hourStr.toInt()
                    val minute = minuteStr?.toIntOrNull() ?: 0
                    val dayOfWeek = parseWeekday(dayToken)
                    if (dayOfWeek != null) {
                        val next = nextWeekdayInCalendar(dayOfWeek, baseMillis)
                        next.set(Calendar.HOUR_OF_DAY, hour)
                        next.set(Calendar.MINUTE, minute)
                        next.set(Calendar.SECOND, 0)
                        val start = next.timeInMillis
                        val (t, loc) = extractTitleAndLocation(sentence)
                        Log.d(TAG, "weekday matched: start=${Date(start)}")
                        return ParseResult(start, start + 60 * 60 * 1000L, t, loc)
                    }
                }
            }

            // 4) time only — scan for first valid time (must have indicator and valid hour)
            val skipTimeOnly = containsWeekScopedHint(sentence)
            if (!skipTimeOnly) {
                val timeOnlyM = timePattern.matcher(sentence)
                while (timeOnlyM.find()) {
                    val matched = timeOnlyM.group()
                    val ampm = timeOnlyM.group(1)
                    val hourStr = timeOnlyM.group(2)
                    val minStr = timeOnlyM.group(3)
                    val hasIndicator = ampm != null || minStr != null || matched.contains("点") || matched.contains(":") || matched.contains("：")
                    val hour = hourStr?.let { if (it.matches(Regex("\\d+"))) it.toInt() else toArabic(it) } ?: continue
                    // Guard against partial numeric like "下午104" -> skip if hour directly followed by another digit and no delimiter
                    val hourEnd = try { timeOnlyM.end(2) } catch (_: Throwable) { -1 }
                    val nextCh = if (hourEnd in 0 until sentence.length) sentence[hourEnd] else null
                    val followedByDigitWithoutDelimiter = nextCh?.isDigit() == true && !matched.contains(":") && !matched.contains("：") && !matched.contains("点")
                    if (!hasIndicator || hour !in 0..23 || followedByDigitWithoutDelimiter) continue
                    val minute = minStr?.toIntOrNull() ?: 0
                    val cal = newCal(baseMillis)
                    val dayOffset = resolveRelativeDayOffset(relativeMap, sentence)
                    if (dayOffset != 0) cal.add(Calendar.DAY_OF_MONTH, dayOffset)
                    cal.set(Calendar.HOUR_OF_DAY, adjustHourByAmPm(hour, ampm))
                    cal.set(Calendar.MINUTE, minute)
                    cal.set(Calendar.SECOND, 0)
                    if (cal.timeInMillis < now.timeInMillis) {
                        if (preferFutureRaw && !autoMode && dayOffset == 0) {
                            cal.add(Calendar.DAY_OF_MONTH, 1)
                        } else if (autoMode && dayOffset == 0) {
                            val diffMillis = now.timeInMillis - cal.timeInMillis
                            val diffHours = diffMillis / (3600*1000L)
                            if (diffHours in 0..11) { // 已过且不足 12 小时，认为指向下一天
                                cal.add(Calendar.DAY_OF_MONTH, 1)
                            }
                        }
                    }
                    val (t, loc) = extractTitleAndLocation(sentence)
                    Log.d(TAG, "time-only matched: ${Date(cal.timeInMillis)}")
                    return ParseResult(cal.timeInMillis, cal.timeInMillis + 60 * 60 * 1000L, t, loc)
                }
            }

            // 5) relative tokens mapping
            if (relativeMap != null) {
                // Guard: 如果句子中包含显式的“(下下周|下周|本周|这周)+周几”，则优先交由后面的“本周/下周 family”处理，
                // 避免这里因为匹配到“下午/上午/今晚”等相对词而将日期错误地解析为今天。
                val hasExplicitWeekday = Regex("(?:下下周|下周|本周|这周)(?:周|星期)?[一二三四五六日天]").containsMatchIn(sentence)
                if (!hasExplicitWeekday) {
                    // 合并所有命中的相对词：日偏移取最大值，时间段(am/pm)取显式时间优先，否则取第一个提供 am/pm 的相对词
                    val matches = relativeMap.entries.filter { sentence.contains(it.key) }
                    if (matches.isNotEmpty()) {
                        val cal = newCal(baseMillis)
                        val dayOffset = matches.maxOf { it.value.offsetDays }
                        cal.add(Calendar.DAY_OF_MONTH, dayOffset)

                        // 查找句中的显式时间
                        val t2 = timePattern.matcher(sentence)
                        var hour: Int? = null
                        var minute = 0
                        var explicitAmpm: String? = null
                        while (t2.find()) {
                            val matched = t2.group()
                            val ampm2 = t2.group(1)
                            val hourStr = t2.group(2)
                            val minStr = t2.group(3)
                            val hasIndicator = ampm2 != null || minStr != null || matched.contains("点") || matched.contains(":") || matched.contains("：")
                            val hCand = hourStr?.let { if (it.matches(Regex("\\d+"))) it.toInt() else toArabic(it) } ?: continue
                            // Guard: skip partial numeric matches like "下午104"
                            val hgEnd = try { t2.end(2) } catch (_: Throwable) { -1 }
                            val nextC = if (hgEnd in 0 until sentence.length) sentence[hgEnd] else null
                            val followedByDigitWithoutDelimiter = nextC?.isDigit() == true && !matched.contains(":") && !matched.contains("：") && !matched.contains("点")
                            if (!hasIndicator || hCand !in 0..23 || followedByDigitWithoutDelimiter) continue
                            explicitAmpm = ampm2
                            minute = minStr?.toIntOrNull() ?: 0
                            hour = hCand
                            break
                        }

                        // 如无显式时间，依据相对词提供的 am/pm 设定默认时间
                        if (hour == null) {
                            val ampmFromToken = matches.firstOrNull { it.value.ampm != null }?.value?.ampm
                            hour = when (ampmFromToken) {
                                "pm" -> 19
                                "am" -> 9
                                else -> 9
                            }
                        }

                        val ampmEffective = explicitAmpm ?: matches.firstOrNull { it.value.ampm != null }?.value?.ampm
                        val finalHour = if (explicitAmpm != null) adjustHourByAmPm(hour, explicitAmpm) else when (ampmEffective) {
                            "pm" -> if (hour < 12) hour + 12 else hour
                            "am" -> if (hour == 12) 0 else hour
                            else -> hour
                        }
                        cal.set(Calendar.HOUR_OF_DAY, finalHour)
                        cal.set(Calendar.MINUTE, minute)
                        cal.set(Calendar.SECOND, 0)
                        val (t, loc) = extractTitleAndLocation(sentence)
                        Log.d(TAG, "relative tokens matched ${matches.map { it.key }} -> ${Date(cal.timeInMillis)}")
                        return ParseResult(cal.timeInMillis, cal.timeInMillis + 60 * 60 * 1000L, t, loc)
                    }
                }
            }

            // 6) 本周/下周 family
            // 5.5) 周末 family：本周末/这周末/这个周末/周末 -> 选择最近的周六 09:00（若含上午/下午/晚上则按语境修正）
            run {
                if (sentence.contains("周末") || sentence.contains("本周末") || sentence.contains("这周末") || sentence.contains("这个周末")) {
                    val wk = newCal(baseMillis)
                    val dowNow = wk.get(Calendar.DAY_OF_WEEK) // 1..7 (Sun..Sat)
                    var diff = Calendar.SATURDAY - dowNow
                    if (diff < 0) diff += 7
                    // 若明确写了“本周末/这周末/这个周末”，且今天已过周六，则推进到下周六
                    if ((sentence.contains("本周末") || sentence.contains("这周末") || sentence.contains("这个周末")) && diff == 0 && wk.get(Calendar.HOUR_OF_DAY) >= 23) {
                        diff = 7
                    }
                    wk.add(Calendar.DAY_OF_MONTH, diff)
                    // 默认 09:00
                    wk.set(Calendar.HOUR_OF_DAY, 9)
                    wk.set(Calendar.MINUTE, 0)
                    wk.set(Calendar.SECOND, 0)
                    // 若句子包含明确的上午/下午/晚上/具体时间，按语义调整
                    run {
                        val tm = timePattern.matcher(sentence)
                        var chosenHour: Int? = null
                        var chosenMinute = 0
                        var ampm: String? = null
                        while (tm.find()) {
                            ampm = tm.group(1)
                            val hourStr = tm.group(2)
                            val minStr = tm.group(3)
                            val h = hourStr?.let { if (it.matches(Regex("\\d+"))) it.toInt() else toArabic(it) } ?: continue
                            chosenHour = h
                            chosenMinute = minStr?.toIntOrNull() ?: 0
                            break
                        }
                        if (chosenHour != null) {
                            wk.set(Calendar.HOUR_OF_DAY, adjustHourByAmPm(chosenHour!!, ampm))
                            wk.set(Calendar.MINUTE, chosenMinute)
                        } else if (sentence.contains("下午") || sentence.contains("晚上") || sentence.contains("晚")) {
                            wk.set(Calendar.HOUR_OF_DAY, 19)
                        } else if (sentence.contains("上午") || sentence.contains("早")) {
                            wk.set(Calendar.HOUR_OF_DAY, 9)
                        }
                    }
                    val start = wk.timeInMillis
                    val end = start + 60*60*1000L
                    val (t, loc) = extractTitleAndLocation(sentence)
                    Log.d(TAG, "weekend matched: ${Date(start)}")
                    return ParseResult(start, end, t, loc)
                }
            }

            // 6) 本周/下周 family
            if (sentence.contains("下周") || sentence.contains("本周") || sentence.contains("这周")) {
                val weekCal = newCal(baseMillis)
                val isNext = sentence.contains("下周")
                val isThis = sentence.contains("本周") || sentence.contains("这周")
                // move to Monday of target week
                val dow = weekCal.get(Calendar.DAY_OF_WEEK) // 1..7 (Sun..Sat)
                var diffToMonday = (Calendar.MONDAY - dow)
                if (diffToMonday > 0) diffToMonday -= 7 // move back to current Monday (or past Monday)
                weekCal.add(Calendar.DAY_OF_MONTH, diffToMonday)
                if (isNext) weekCal.add(Calendar.WEEK_OF_YEAR, 1)
                // check weekday token
                val weekdayMap = mapOf("一" to Calendar.MONDAY, "二" to Calendar.TUESDAY, "三" to Calendar.WEDNESDAY, "四" to Calendar.THURSDAY, "五" to Calendar.FRIDAY, "六" to Calendar.SATURDAY, "日" to Calendar.SUNDAY, "天" to Calendar.SUNDAY)
                var targetCal = weekCal
                val wkRegex = Regex("(?:下周|本周|这周)([一二三四五六日天])")
                val wkMatch = wkRegex.find(sentence)
                if (wkMatch != null) {
                    val dow = weekdayMap[wkMatch.groupValues[1]]
                    if (dow != null) {
                        val baseDow2 = weekCal.get(Calendar.DAY_OF_WEEK)
                        // Translate Calendar's 1..7 (Sun..Sat) to Monday-first 1..7
                        val mondayFirst = { x: Int -> (x + 5) % 7 + 1 }
                        val currentMF = mondayFirst(baseDow2)
                        val targetMF = when (dow) {
                            Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 3; Calendar.THURSDAY -> 4; Calendar.FRIDAY -> 5; Calendar.SATURDAY -> 6; else -> 7
                        }
                        var d2 = targetMF - currentMF
                        if (!isThis && d2 < 0) d2 += 7
                        targetCal = (weekCal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, d2) }
                    }
                }
                // time
                val t2 = timePattern.matcher(sentence)
                var hour = 9
                var minute = 0
                var ampmToken: String? = null
                if (t2.find()) {
                    ampmToken = t2.group(1)
                    val hourStr = t2.group(2)
                    minute = t2.group(3)?.toIntOrNull() ?: 0
                    hour = hourStr?.let { if (it.matches(Regex("\\d+"))) it.toInt() else toArabic(it) } ?: 9
                }
                val finalHour = adjustHourByAmPm(hour, ampmToken ?: if (sentence.contains("晚上")) "晚上" else if (sentence.contains("早")|| sentence.contains("上午")) "上午" else null)
                targetCal.set(Calendar.HOUR_OF_DAY, finalHour)
                targetCal.set(Calendar.MINUTE, minute)
                targetCal.set(Calendar.SECOND, 0)
                val (t, loc) = extractTitleAndLocation(sentence)
                Log.d(TAG, "next-week matched: ${Date(targetCal.timeInMillis)}")
                return ParseResult(targetCal.timeInMillis, targetCal.timeInMillis + 60*60*1000L, t, loc)
            }
        } catch (e: Exception) {
            Log.e(TAG, "parse error", e)
        }
        // 兜底：通知/公告/通告/安排/提醒 等管理类信息，可能不含明确时间；为了给用户一个提醒，默认从“现在+10分钟”开始 1 小时
        run {
            if (Regex("(通知|公告|通告|安排|提醒)").containsMatchIn(sentence)) {
                // 保护：若是仅地点/教室变更、且没有明确时间，不应创建事件（否则会误报）
                if (shouldSkipTimeNLPFallback(sentence)) {
                    Log.d(TAG, "fallback notice skipped due to room-change without time")
                    return null
                }
                val now = newCal(baseMillis)
                now.add(Calendar.MINUTE, 10)
                val start = now.timeInMillis
                val end = start + 60*60*1000L
                val (t, loc) = extractTitleAndLocation(sentence)
                Log.d(TAG, "fallback notice matched: ${Date(start)}")
                return ParseResult(start, end, t ?: "通知", loc)
            }
        }
        Log.d(TAG, "parseDateTimeInternal - no match for input: '$sentence'")
        return null
    }

    private fun containsWeekScopedHint(sentence: String): Boolean {
        if (sentence.contains("周末") || sentence.contains("本周末") || sentence.contains("这周末") || sentence.contains("这个周末")) return true
        if (sentence.contains("下下周")) return true
        if (sentence.contains("下周") || sentence.contains("本周") || sentence.contains("这周")) return true
        return Regex("(?:下下周|下周|本周|这周)(?:周|星期)?[一二三四五六日天]").containsMatchIn(sentence)
    }

    private fun resolveRelativeDayOffset(relativeMap: LinkedHashMap<String, RelativeSpec>?, sentence: String): Int {
        if (relativeMap == null) return 0
        val matches = relativeMap.entries.filter { sentence.contains(it.key) && it.value.offsetDays != 0 }
        if (matches.isEmpty()) return 0
        val positives = matches.map { it.value.offsetDays }.filter { it > 0 }
        val maxPositive = positives.maxOrNull()
        if (maxPositive != null) return maxPositive
        val negatives = matches.map { it.value.offsetDays }.filter { it < 0 }
        val minNegative = negatives.minOrNull()
        if (minNegative != null) return minNegative
        return 0
    }

    // Strict check: does sentence contain a safe time token (with indicator and without trailing digits)?
    private fun hasSafeTimeToken(sentence: String): Boolean {
        val m = timePattern.matcher(sentence)
        while (m.find()) {
            val matched = m.group()
            val ampm = m.group(1)
            val hourStr = m.group(2)
            val minStr = m.group(3)
            val hasIndicator = ampm != null || minStr != null || matched.contains("点") || matched.contains(":") || matched.contains("：")
            val hour = hourStr?.let { if (it.matches(Regex("\\d+"))) it.toInt() else toArabic(it) } ?: continue
            val hourEnd = try { m.end(2) } catch (_: Throwable) { -1 }
            val nextCh = if (hourEnd in 0 until sentence.length) sentence[hourEnd] else null
            val followedByDigitWithoutDelimiter = nextCh?.isDigit() == true && !matched.contains(":") && !matched.contains("：") && !matched.contains("点")
            if (hasIndicator && hour in 0..23 && !followedByDigitWithoutDelimiter) return true
        }
        return false
    }

    private fun looksLikeRoomChange(sentence: String): Boolean {
        // e.g., "下午104的课挪至207进行" / "原104教室的课改到207教室"
        if (!Regex("(挪|改|调整|换|移).{0,8}(至|到)").containsMatchIn(sentence)) return false
        if (!Regex("(教室|机房|实验室|报告厅|会议室|课)").containsMatchIn(sentence)) return false
        // avoid matching obvious date phrases
        if (monthDayPattern.matcher(sentence).find()) return false
        return true
    }

    // 解析相对偏移: 将『X天后』、『2小时30分钟后』等转为绝对时间 (start=end-1h 默认)；返回 null 表示不匹配
    private fun parseRelativeOffset(sentence: String, baseMillis: Long?): ParseResult? {
        // 触发词: 后, 之后, 以后, 前, 之前, 以前
        if (!sentence.contains("后") && !sentence.contains("前")) return null
        // 快速正则: 捕获形如 2天3小时20分钟10秒后 / 1个半小时后 / 半小时后
        val tailMatcher = Regex("(后|之后|以后|前|之前|以前)").find(sentence) ?: return null
        val directionWord = tailMatcher.value
        val direction = if (directionWord.contains("后") || directionWord.contains("之后") || directionWord.contains("以后")) 1 else -1
        // 抽取单位链
        val unitRegex = Regex("((?:[一二三四五六七八九十百零两0-9]+)?个?半|[一二三四五六七八九十百零两0-9]+|半)个?(年|个月|月|周|星期|天|日|小时|分钟|分|秒)")
        val segment = sentence.substring(0, tailMatcher.range.first + tailMatcher.value.length)
        val matches = unitRegex.findAll(segment).toList()
        if (matches.isEmpty()) return null
        var totalMillis = 0L
        fun numToken(raw: String): Double {
            var r = raw
            var half = false
            if (r.endsWith("半")) { half = true; r = r.removeSuffix("半") }
            val value = when {
                r == "半" || r.isBlank() -> 0.5
                r.matches(Regex("[0-9]+")) -> r.toDouble()
                else -> toArabic(r).toDouble()
            }
            return value + if (half) 0.5 else 0.0
        }
        for (m in matches) {
            val full = m.value
            val unit = m.groupValues.lastOrNull { it.isNotBlank() && it.any { c -> c !in '0'..'9' } } ?: continue
            val value = when {
                full.startsWith("半") -> 0.5
                full.contains("个半") && (unit == "小时" || unit == "月" || unit == "年") -> numToken(full.substringBefore("个半")) + 0.5
                full.endsWith("半${unit}") -> numToken(full.substringBefore("半${unit}")) + 0.5
                else -> numToken(full.removeSuffix(unit))
            }
            val millisPer = when (unit) {
                "年" -> 365L * 24 * 3600 * 1000 // 粗略，不处理闰年
                "个月", "月" -> 30L * 24 * 3600 * 1000 // 粗略
                "周", "星期" -> 7L * 24 * 3600 * 1000
                "天", "日" -> 24L * 3600 * 1000
                "小时" -> 3600L * 1000
                "分钟", "分" -> 60L * 1000
                "秒" -> 1000L
                else -> 0L
            }
            totalMillis += (value * millisPer).toLong()
        }
        if (totalMillis <= 0) return null
        val base = newCal(baseMillis)
        val target = base.timeInMillis + direction * totalMillis
        val start = if (direction > 0) target - 60 * 60 * 1000L else target // 未来: 以截止视角, 过去: 直接事件时刻
        val (t, loc) = extractTitleAndLocation(sentence)
        val title = t ?: if (direction > 0) "提醒" else "事件"
        return ParseResult(start, target, title, loc)
    }

    private fun parseWeekday(sentence: String?): Int? {
        if (sentence == null) return null
        val s = sentence
        return when {
            s.contains("周一") || s.contains("星期一") -> Calendar.MONDAY
            s.contains("周二") || s.contains("星期二") -> Calendar.TUESDAY
            s.contains("周三") || s.contains("星期三") -> Calendar.WEDNESDAY
            s.contains("周四") || s.contains("星期四") -> Calendar.THURSDAY
            s.contains("周五") || s.contains("星期五") -> Calendar.FRIDAY
            s.contains("周六") || s.contains("星期六") -> Calendar.SATURDAY
            s.contains("周日") || s.contains("周天") || s.contains("星期日") || s.contains("星期天") -> Calendar.SUNDAY
            else -> null
        }
    }

    private fun nextWeekdayInCalendar(targetWeekday: Int, baseMillis: Long? = null): Calendar {
        val cal = Calendar.getInstance()
        if (baseMillis != null) cal.timeInMillis = baseMillis
        val today = cal.get(Calendar.DAY_OF_WEEK)
        var diff = targetWeekday - today
        if (diff <= 0) diff += 7
        cal.add(Calendar.DAY_OF_MONTH, diff)
        return cal
    }

    private fun toArabic(s: String?): Int {
        if (s == null) return 0
        val trimmed = s.trim()
        // try parse as integer first
        try {
            return trimmed.toInt()
        } catch (_: Exception) {
        }
        // chinese numerals basic mapping for 0-99
        val map = mapOf('零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        var result = 0
        var temp = 0
        var lastUnit = 1
        for (ch in trimmed) {
            when (ch) {
                '十' -> {
                    if (temp == 0) temp = 1
                    temp *= 10
                    lastUnit = 10
                }
                '百' -> {
                    if (temp == 0) temp = 1
                    temp *= 100
                    lastUnit = 100
                }
                else -> {
                    val v = map[ch] ?: 0
                    if (lastUnit > 1) {
                        temp += v * lastUnit
                        lastUnit = 1
                    } else {
                        temp = temp * 10 + v
                    }
                }
            }
        }
        if (temp != 0) result += temp
        return if (result == 0) trimmed.filter { it.isDigit() }.toIntOrNull() ?: 0 else result
    }

    // Try to extract a concise title and a location from the sentence.
    // Heuristics:
    // - If sentence contains keywords like "地点" or "地点:", take following chunk as location
    // - If sentence contains a short noun phrase following a time verb (开展/举办/召集/报名/招/招募/开展/进行/开展), treat that noun as title
    // - Otherwise fallback to first few meaningful words (exclude words like 请、注意、@全体成员)
    private fun extractTitleAndLocation(sentence: String?): Pair<String?, String?> {
        if (sentence.isNullOrBlank()) return Pair(null, null)
        var title: String? = null
        var location: String? = null

        // 先在原文上提取地点，避免清洗时间短语时影响地点关键字识别
        // 再在去除时间/日期表达后的文本上提取标题

        // 清洗后文本（仅用于标题提取）
        val cleanedForTitle = removeDateTimePhrases(sentence)

        // location patterns（在原文上）
        val locRegex = Regex("(?:地点|地址|场地|地点：|地点:|位置|集合地点)\\s*[:：]?\\s*([\\u4e00-\\u9fa5A-Za-z0-9\\-—–,，。、\\s]{2,60})")
        val locMatch = locRegex.find(sentence)
        if (locMatch != null) {
            location = locMatch.groupValues.getOrNull(1)?.trim()?.trimEnd('。', '，', ',')
        } else {
            // Also handle '到/在 XX教室|机房|实验室|报告厅|会议室' 模式
            val locRegex2 = Regex("(?:到|在|于)\\s*([A-Za-z0-9\\-]{1,8}[A-Za-z]?\\d{0,4}|[\\u4e00-\\u9fa5A-Za-z0-9\\-]{1,20})\\s*(教室|机房|实验室|报告厅|会议室|办公室)")
            val m2 = locRegex2.find(sentence)
            if (m2 != null) {
                val b = m2.groupValues.getOrNull(1)?.trim() ?: ""
                val suf = m2.groupValues.getOrNull(2)?.trim() ?: ""
                val cand = (b + suf).trim()
                if (cand.isNotBlank()) location = cand
            }
        }
        // 额外：'到XX教室' / '到XX机房' / '到教室XX' / '在21B6教室' 模式提取
        if (location.isNullOrBlank()) {
            val toRoom = Regex("到\\s*([A-Za-z0-9\\-]{1,8}\\s*[\\u4e00-\\u9fa5]{0,6}?教室)").find(sentence)
                ?: Regex("到\\s*(教室[0-9A-Za-z\\-]{1,8})").find(sentence)
                ?: Regex("在\\s*([0-9A-Za-z\\-]{1,8}\\s*教室)").find(sentence)
                ?: Regex("在\\s*(教室[0-9A-Za-z\\-]{1,8})").find(sentence)
                ?: Regex("到\\s*([A-Za-z0-9]{1,8}\u0020?机房)").find(sentence)
                ?: Regex("(?:到|在|于)\\s*([\\u4e00-\\u9fa5A-Za-z0-9\\-]{1,12}?办公室)").find(sentence)
            if (toRoom != null) {
                location = toRoom.groupValues.getOrNull(1)?.trim()
            }
        }

        // Prefer the improved title extraction with rule heuristics
        try {
            // 先用新事件描述抽取，倾向于剔除时间后保留名称描述
            val desc = JiebaWrapper.extractEventDescription(sentence)
            val extracted = desc ?: JiebaWrapper.extractTitle(cleanedForTitle)
            if (!extracted.isNullOrBlank()) {
                title = extracted
            } else {
                // fallback to previous lightweight combination to preserve behavior
                val combined = JiebaWrapper.combinedTopTokens(cleanedForTitle, 4)
                if (!combined.isNullOrBlank()) {
                    title = combined
                } else {
                    val jiebaCands = JiebaWrapper.nounCandidates(cleanedForTitle)
                    if (jiebaCands.isNotEmpty()) {
                        for (c in jiebaCands) {
                            val cand = c.trim()
                            if (cand.length in 2..60 && !cand.matches(Regex("^[0-9\\-:年月日点]+$"))) {
                                title = cand
                                break
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // ignore segmentation errors and continue with heuristics
        }

        // Strengthen: if cleaned sentence contains common event nouns, prefer those (e.g., 班会/会议/考试/答辩/讲座/活动/团课/聚会/晚会)
        try {
            val eventPat = Regex("([\\u4e00-\\u9fa5A-Za-z0-9]{0,12}?)(班会|会议|考试|答辩|讲座|研讨会|活动|团课|聚餐|聚会|晚会)")
            val match = eventPat.find(cleanedForTitle)
            if (match != null) {
                val cand = (match.groupValues.getOrNull(1)?.trim() ?: "") + match.groupValues.getOrNull(2).orEmpty()
                val c2 = cand.trim().trimEnd('。','，',',','：',':')
                if (c2.length in 2..40) {
                    // If current title already has a strong event word (体检/接种/疫苗/考试/讲座/会议/答辩/汇报/评审)，不要被泛化词“活动”覆盖
                    val hasStrongEventWord = title?.let { Regex("(体检|接种|疫苗|考试|讲座|研讨会|会议|答辩|汇报|评审)").containsMatchIn(it) } ?: false
                    val candSuffix = match.groupValues.getOrNull(2) ?: ""
                    val isGenericActivity = candSuffix == "活动"
                    val looksLikeLocationActivity = isGenericActivity && (sentence.contains("活动中心") || sentence.contains("学生活动中心"))
                    // Current has suffix?
                    val hasEventSuffix = title?.let { Regex("(班会|会议|考试|答辩|讲座|活动|团课|聚餐|聚会|晚会)").containsMatchIn(it) } ?: false
                    // 覆盖条件：没有强事件词，且不是地点型“活动中心”，且（原本无事件后缀或候选更短更精确）
                    if (!hasStrongEventWord && !looksLikeLocationActivity) {
                        if (!hasEventSuffix || (c2.length > (title?.length ?: 0) && c2.length <= 40)) {
                            title = c2
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // title heuristics: look for verb + noun patterns
        run {
            val verbNoun = Regex("(举办|召集|报名|招募|招|进行|开展|通知|请|组织|召开|申请|发起|开展本学期第一次)([一-龥A-Za-z0-9]{1,20})")
            val vn = verbNoun.find(cleanedForTitle)
            if (vn != null) {
                val noun = vn.groupValues.getOrNull(2)?.trim().orEmpty()
                if (noun.isNotBlank()) {
                    // 避免用“请到/在 XX地点”覆盖已有更好的事件名，如“体检”
                    val looksLikeLocationLead = noun.startsWith("到") || noun.startsWith("在") || noun.startsWith("于")
                    val locHint = Regex("(教室|机房|实验室|报告厅|会议室|体育馆|图书馆|礼堂|餐厅|食堂|医院|卫生院|校医院|门诊|门诊部|办公室)").containsMatchIn(noun)
                    val isLocationish = looksLikeLocationLead || locHint
                    val hasEventWord = Regex("(会议|开会|班会|团课|考试|答辩|讲座|活动|聚餐|聚会|晚会|汇报|评审|体检|面谈|面试)").containsMatchIn(title ?: "")
                    if (!isLocationish || !hasEventWord) {
                        title = noun
                    }
                }
            }
        }

        // if still no title, look for common event nouns inside sentence (e.g. 开会, 团课)
        if (title == null) {
            val commonEvents = listOf("开会", "会议", "团课", "大扫除", "志愿者", "报名", "截止", "考试", "活动", "志愿者")
            for (ev in commonEvents) {
                if (cleanedForTitle.contains(ev)) {
                    title = ev
                    break
                }
            }
        }

        // fallback: try extract short meaningful phrase before location or before time
        if (title == null) {
            // remove leading polite tokens and mentions
            val s = cleanedForTitle.replace(Regex("@全体成员|@所有人|请大家|各位|各位同学|各位老师|各位班主任|各位学委"), "")
            // split by punctuation and newlines
            val parts = s.split(Regex("[，,。.!！?？；;\\n\\r]"))
            for (p in parts) {
                val cleaned = p.trim()
                if (cleaned.length in 2..40 && !Regex("(请|注意|提醒|网址|链接|查看|详情|报名|要求)").containsMatchIn(cleaned)) {
                    // if contains a location marker, skip as title
                    if (Regex("地点|地址|时间|时间：|时间:").containsMatchIn(cleaned)) continue
                    // if contains date/time, skip
//                    if (Regex("\\d{1,2}[:：\\.]\\d{1,2}|上午|下午|中午|晚上|明天|后天|今天|周|星期").containsMatchIn(cleaned)) {
//                        // still might contain title before time, try split by spaces
//                    }
                    title = cleaned.take(40)
                    break
                }
            }
        }

        // If still no title, try common connector patterns like '为XXX' or '是XXX' at sentence tail
        if (title == null) {
            try {
                val forMatch = Regex("为\\s*([^，,。；;\\n\\r]{2,60})").find(sentence)
                if (forMatch != null) {
                    val cand = forMatch.groupValues.getOrNull(1)?.trim()
                    if (!cand.isNullOrBlank()) title = cand
                }
            } catch (_: Exception) {}
        }

        // If still no title, try take substring after last time/date token（在 cleaned 文本上）
        if (title == null) {
            try {
                var lastEnd = -1
                val tm = timePattern.matcher(cleanedForTitle)
                while (tm.find()) lastEnd = maxOf(lastEnd, tm.end())
                val md = monthDayPattern.matcher(cleanedForTitle)
                while (md.find()) lastEnd = maxOf(lastEnd, md.end())
                val wd = weekdayTimePattern.matcher(cleanedForTitle)
                while (wd.find()) lastEnd = maxOf(lastEnd, wd.end())
                // also consider explicit '开始'/'结束' timestamps
                val startIdx = cleanedForTitle.indexOf("开始时间")
                val endIdx = cleanedForTitle.indexOf("结束时间")
                if (startIdx >= 0) lastEnd = maxOf(lastEnd, startIdx)
                if (endIdx >= 0) lastEnd = maxOf(lastEnd, endIdx)
                if (lastEnd >= 0 && lastEnd < cleanedForTitle.length - 1) {
                    var tail = cleanedForTitle.substring(lastEnd).trim()
                    // drop leading connectors
                    tail = tail.replaceFirst(Regex("^[\":：\\-—\\s]*(为|是|为期|：|:)") , "").trim()
                    // cut at punctuation
                    tail = tail.split(Regex("[，,。.!！?？；;\\n\\r]"))[0].trim()
                    if (tail.length in 2..60 && !Regex("\\d{1,2}[:：.]\\d{1,2}|上午|下午|中午|晚上|明天|后天|今天|周|星期").containsMatchIn(tail)) {
                        title = tail.take(60)
                    }
                }
            } catch (_: Exception) {}
        }

        // final cleanups
        // 最终兜底：若标题为残缺序数（如“本学期第”或仅“第X”），尝试回落为句中出现的强事件词（如 团课/考试/讲座/会议 等）
        run {
            val t = title?.trim()
            val isOrdinalOnly = t == "本学期第" || (t != null && Regex("^第[一二三四五六七八九十零两0-9]+$").matches(t))
            if (isOrdinalOnly) {
                val strong = listOf("团课", "考试", "讲座", "会议", "答辩", "汇报", "评审", "体检", "聚餐", "读书会")
                val hit = strong.firstOrNull { sentence.contains(it) }
                if (hit != null) title = hit
            }
        }
        title = title?.trim()?.trimEnd('。', '，', ',', ':', '：')
        location = location?.trim()?.trimEnd('。', '，', ',')
        return Pair(title, location)
    }

    // 检测“仅地点/教室变更，无明确日期时间”的句子，避免 TimeNLP 误判
    private fun shouldSkipTimeNLPFallback(sentence: String): Boolean {
        val s = sentence.trim()
        // 规则1：若是“调课/挪至/改到/换至 ...”类语义，且不含“安全时间 token”（例如只有“下午/早上”或“104/207”这类不完整数字），则直接跳过回退
        if (looksLikeRoomChange(s) && !hasSafeTimeToken(s)) return true
        // 规则2：较保守的兜底（保持兼容现有行为）：包含调课关键词、没有任何日期时间指示、且出现教室样式 token
        val roomChange = Regex("(挪至|挪到|改到|改至|换到|换至|调整到|调整至)").containsMatchIn(s)
        val hasDateTime = containsAnyDateTimeToken(s)
        val hasRoomLike = Regex("(教室|机房|实验室|[A-Za-z]?[0-9]{2,4})").containsMatchIn(s)
        return roomChange && !hasDateTime && hasRoomLike
    }

    private fun containsAnyDateTimeToken(s: String): Boolean {
        // fast-check tokens
        if (Regex("(\\d{1,2}[:：][0-5]\\d|上午|下午|中午|晚上|凌晨|周[一二三四五六日天]|星期[一二三四五六日天]|[一二三四五六七八九十百]+月[一二三四五六七八九十]+(日|号)?)").containsMatchIn(s))
            return true
        // also our main patterns
        if (monthDayPattern.matcher(s).find()) return true
        if (monthDayRangePattern.matcher(s).find()) return true
        if (weekdayTimePattern.matcher(s).find()) return true
        val tm = timePattern.matcher(s)
        while (tm.find()) {
            val matched = tm.group()
            val ampm = tm.group(1)
            val hourStr = tm.group(2)
            val minuteStr = tm.group(3)
            val hour = hourStr?.let { if (it.matches(Regex("\\d+"))) it.toInt() else toArabic(it) } ?: -1
            val hasIndicator = ampm != null || minuteStr != null || matched.contains("点") || matched.contains(":") || matched.contains("：")
            if (hasIndicator && hour in 0..23) return true
        }
        return false
    }

    // 移除文本中的时间/日期/相对日期/倒计时等短语，只保留用于标题提取的“语义剩余”
    private fun removeDateTimePhrases(sentence: String): String {
        var s = sentence
        fun rm(re: Regex) { s = re.replace(s, " ") }
        fun rmAll(pattern: Pattern) { s = pattern.matcher(s).replaceAll(" ") }

        // 1) 显式开始/结束时间戳
        rm(Regex("开始(?:时间)?\\s*[:：]\\s*\\d{4}-\\d{1,2}-\\d{1,2}\\s*[0-2]?\\d[:：][0-5]\\d"))
        rm(Regex("结束(?:时间)?\\s*[:：]\\s*\\d{4}-\\d{1,2}-\\d{1,2}\\s*[0-2]?\\d[:：][0-5]\\d"))

        // 2) 月日范围、月日、周几时间、纯时间
        rmAll(monthDayRangePattern)
        rmAll(monthDayPattern)
        rmAll(weekdayTimePattern)
        rmAll(timePattern)

        // 3) 时间范围：3点到5点 / 7:30-9:00
        val colonClass = "[:：]"
        rm(Regex("([0-9一二三四五六七八九十]{1,2})(?:$colonClass([0-5]?\\d))?点?\\s*(?:到|至|-)\\s*([0-9一二三四五六七八九十]{1,2})(?:$colonClass([0-5]?\\d))?点?"))

        // 4) 相对时间：X天/小时/分钟/秒 后/前
        rm(Regex("(还有)?[一二三四五六七八九十百零两0-9个半半]+(年|个月|月|周|星期|天|日|小时|分钟|分|秒)([一二三四五六七八九十百零两0-9个半半]*(年|个月|月|周|星期|天|日|小时|分钟|分|秒))*\\s*(后|之前|以后|之后|前)"))
        // Chaoxing 倒计时：还有X天/小时/分钟/秒
        rm(Regex("还有[一二三四五六七八九十百零两0-9个半半]+(天|个?小时|个?分钟|分|个?秒)"))

        // 5) 本周/下周/这周 + 周几
        rm(Regex("(?:本周|这周|下周)(?:[一二三四五六日天])?"))

        // 6) 单独相对词：今天/明天/后天/大后天/今晚/明晚/今早/明早/中午/下午/上午/晚上/凌晨
        rm(Regex("今天|明天|后天|大后天|今晚|明晚|今早|明早|中午|下午|上午|晚上|凌晨|本周|这周|下周"))

        // 7) 清理多余空白和分隔符
        s = s.replace(Regex("[\t\r\n]+"), " ")
            .replace(Regex("\u3000+"), " ")
        s = s.replace(Regex("\\s{2,}"), " ").trim()
        // 去掉可能留下的孤立标点
        s = s.replace(Regex("^[，,。:：;；]+"), "").replace(Regex("[，,。:：;；]+$"), "")
        return s
    }

    private fun adjustHourByAmPm(hour: Int, ampm: String?): Int {
        if (ampm == null) return hour
        val a = ampm.replace("\uFEFF", "")
        return when {
            // Evening / night contexts
            a.contains("下午") || a.contains("PM", true) || a.contains("晚") || a.contains("今晚") || a.contains("明晚") -> if (hour < 12) hour + 12 else hour
            // Early morning contexts: 凌晨 1/2/3/4/5 点 属于 0~5 点; 若写 12 凌晨则 interpret 为 0
            a.contains("凌晨") -> when (hour) {
                12 -> 0
                in 1..5 -> hour
                // keep 1~5
                else -> hour
                // others unchanged
            }
            a.contains("上午") || a.contains("AM", true) || a.contains("早") -> if (hour == 12) 0 else hour
            else -> hour
        }
    }

    /*
     * ==== 手工快速测试用例建议 (基于当前实现 + 引入 xk-time 语义要点) ====
     * 1. "3天后截止提交报告" => 目标时间 = now + 3d, start = target -1h, title 包含 "截止" 或提取出 "提交报告"
     * 2. "2小时30分钟后开会" => now + 2h30m
     * 3. "半小时后提醒我喝水" => now + 30m
     * 4. "1个半小时后出发" => now + 1h30m
     * 5. "10分钟30秒后锁屏" => +10m30s
     * 6. "3天前的日志" => target = now - 3d (start=target)
     * 7. "9月23日晚上8点开会" (若今天 9/19) => 今年 9/23 20:00
     * 8. "1月3日早上8点" 在 12月31日 (preferFuture=true) => 下一年 1/3 08:00
     * 9. "周五3点到5点开会" (尚未做级联继承 Range 第二时间的日期, TODO)
     * 10."下午3点讨论" 当前时间上午10点 => 今天 15:00 (若已过则 +1 天)
     */
}
