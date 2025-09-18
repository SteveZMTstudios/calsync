package top.stevezmt.calsync

import android.util.Log
import java.util.*
import java.text.SimpleDateFormat
import java.util.regex.Pattern

object DateTimeParser {
    private val TAG = "DateTimeParser"

    data class ParseResult(val startMillis: Long, val endMillis: Long?, val title: String? = null, val location: String? = null)

    // Patterns for Chinese-style dates/times. These are examples and should be extended.
    // Accept both ASCII colon and fullwidth colon
    private val colon = "[:：]"
    private val monthDayPattern = Pattern.compile("(\\d{1,2}|[一二三四五六七八九十百]+)月(\\d{1,2}|[一二三四五六七八九十]+)(?:日|号)?")
    private val monthDayRangePattern = Pattern.compile("(\\d{1,2}|[一二三四五六七八九十百]+)月(\\d{1,2}|[一二三四五六七八九十]+)(?:日|号)?\\s*[~-至到]+\\s*(\\d{1,2}|[一二三四五六七八九十百]+)月?(\\d{1,2}|[一二三四五六七八九十]+)(?:日|号)?")
    // unified time pattern: optional am/pm token, hour (arabic or chinese numerals), optional minute
    // Added 今晚 / 明晚 to capture evening context directly so "今晚8点" 不再被误判为上午 8 点
    private val timePattern = Pattern.compile("(上午|下午|中午|晚上|凌晨|今晚|明晚)?\\s*([0-9]{1,2}|[一二三四五六七八九十百]+)(?:${colon}([0-5]?\\d))?点?")
    private val weekdayTimePattern = Pattern.compile("((?:周|星期)[一二三四五六日天])(?:[上下午]|上午|下午)?\\s*(\\d{1,2})${colon}(\\d{1,2})")
    // default pattern, but we will allow configurable relative words
    private val defaultRelativePattern = Pattern.compile("(今天|明天|后天|大后天|今晚|明晚|今日|明日)")

    // Extract the sentence bounded by punctuation that contains a date/time-looking substring
    fun extractSentenceContainingDate(context: android.content.Context, text: String): String {
        val list = extractAllSentencesContainingDate(context, text)
        return if (list.isEmpty()) "" else list.first()
    }

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
            || Regex("还有[一二三四五六七八九十百零0-9]+(个)?分(?:钟)?").containsMatchIn(s)
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
            if (hasIndicator && hour in 0..23) return true
        }
        return false
    }

    // === Public APIs (unchanged signature) ===
    fun parseDateTime(sentence: String): ParseResult? = RuleBasedStrategy.tryParseStandalone(sentence)

    fun parseDateTime(context: android.content.Context, sentence: String): ParseResult? {
        // Prefer explicit rule-based parsing first (handles tokens like 周五/本周五 reliably).
        try {
            val rule = RuleBasedStrategyWithContext(context)
            val r = rule.tryParse(sentence)
            if (r != null) return r
        } catch (e: Exception) {
            Log.w(TAG, "RuleBaseCtx failed: ${e.message}")
            try { NotificationUtils.sendError(context, e) } catch (_: Throwable) {}
        }

        // Fallback to TimeNLP only if enabled and rule-based didn't match
        if (SettingsStore.isTimeNLPEnabled(context)) {
            try {
                val nlp = TimeNLPStrategy(context)
                val r2 = nlp.tryParse(sentence)
                if (r2 != null) return r2
            } catch (e: Exception) {
                Log.w(TAG, "TimeNLP failed: ${e.message}")
                try { NotificationUtils.sendError(context, e) } catch (_: Throwable) {}
            }
        }
        return null
    }

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
            val slots = TimeNLPAdapter.parse(context, sentence)
            if (slots.isEmpty()) return null
            val s = slots.first()
            val (t, loc) = extractTitleAndLocation(sentence)
            return ParseResult(s.startMillis, s.endMillis, t, loc)
        }
        fun tryParseWithBase(sentence: String, baseMillis: Long): ParseResult? {
            val slots = TimeNLPAdapter.parse(context, sentence, baseMillis)
            if (slots.isEmpty()) return null
            val s = slots.first()
            val (t, loc) = extractTitleAndLocation(sentence)
            return ParseResult(s.startMillis, s.endMillis, t, loc)
        }
    }

    // Original rule-based without context (legacy API)
    private object RuleBasedStrategy: ParsingStrategy {
        override fun name() = "RuleBaseNoCtx"
        override fun tryParse(sentence: String): ParseResult? = parseDateTimeInternal(sentence, defaultRelativePattern)
        fun tryParseStandalone(sentence: String) = tryParse(sentence)
    }

    // Rule-based with custom settings context
    private class RuleBasedStrategyWithContext(private val ctx: android.content.Context): ParsingStrategy {
        override fun name() = "RuleBaseCtx"
        override fun tryParse(sentence: String): ParseResult? {
            val map = buildRelativeTokenMap(ctx)
            return parseDateTimeInternal(sentence, null, map)
        }
        fun tryParseWithBase(sentence: String, baseMillis: Long): ParseResult? {
            val map = buildRelativeTokenMap(ctx)
            return parseDateTimeInternal(sentence, null, map, baseMillis)
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
                if (colonSplit.size >= 1) token = colonSplit[0].substringAfter('"').substringBeforeLast('"').ifBlank { colonSplit[0] }
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

    // Create Calendar with optional fixed base time
    private fun newCal(baseMillis: Long?): Calendar {
        val c = Calendar.getInstance()
        if (baseMillis != null) c.timeInMillis = baseMillis
        return c
    }

    private fun parseDateTimeInternal(
        sentence: String,
        relativePattern: Pattern?,
        relativeMap: LinkedHashMap<String, RelativeSpec>? = null,
        baseMillis: Long? = null
    ): ParseResult? {
        Log.d(TAG, "parseDateTimeInternal - input: '$sentence'")
        try {
            val now = newCal(baseMillis)

            // Chaoxing style countdown: 还有24个小时 / 还有2天3小时 / 还有90分钟 / 还有1天2小时30分钟5秒
            // Use a simple, balanced regex to quickly detect countdown phrases (we parse units sequentially below).
            val countdownRegex = Regex("还有[一二三四五六七八九十百零0-9]+(?:个)?(?:天|小时|分(?:钟)?|秒)")
            // Simpler robust approach: use iterative token extraction
            val durationToken = Regex("还有([一二三四五六七八九十百零0-9]+)(?:个)?天")
            val hourToken = Regex("还有|(?<!天)([一二三四五六七八九十百零0-9]+)(?:个)?小时")
            // Instead of a single giant regex (容易错), we parse sequentially.
            if (sentence.contains("还有") && sentence.contains("小时") || sentence.contains("还有") && sentence.contains("天") || sentence.contains("还有") && sentence.contains("分钟") || sentence.contains("还有") && sentence.contains("分") || sentence.contains("还有") && sentence.contains("秒")) {
                var remain = sentence.substring(sentence.indexOf("还有"))
                var days = 0; var hours = 0; var minutes = 0; var seconds = 0
                fun extract(re: Regex, unit: String, assign: (Int)->Unit) {
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
                extract(Regex("还有([一二三四五六七八九十百零0-9]+)个?天"), "天") { days = it }
                extract(Regex("([一二三四五六七八九十百零0-9]+)个?小时"), "小时") { hours = it }
                extract(Regex("([一二三四五六七八九十百零0-9]+)个?分(?:钟)?"), "分钟") { minutes = it }
                extract(Regex("([一二三四五六七八九十百零0-9]+)个?秒"), "秒") { seconds = it }
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
            // 1) Try explicit month/day range
            val range = monthDayRangePattern.matcher(sentence)
            if (range.find()) {
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
                // try find time-of-day in same sentence (support Chinese numerals)
                val timeM = timePattern.matcher(sentence)
                if (timeM.find()) {
                    val ampm = timeM.group(1)
                    val hourStr = timeM.group(2)
                    val minStr = timeM.group(3)
                    val h = hourStr?.let { if (it.matches(Regex("\\d+"))) it.toInt() else toArabic(it) } ?: 9
                    val min = minStr?.toIntOrNull() ?: 0
                    cal.set(Calendar.HOUR_OF_DAY, adjustHourByAmPm(h, ampm))
                    cal.set(Calendar.MINUTE, min)
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
            val timeOnlyM = timePattern.matcher(sentence)
            while (timeOnlyM.find()) {
                val matched = timeOnlyM.group()
                val ampm = timeOnlyM.group(1)
                val hourStr = timeOnlyM.group(2)
                val minStr = timeOnlyM.group(3)
                val hasIndicator = ampm != null || minStr != null || matched.contains("点") || matched.contains(":") || matched.contains("：")
                val hour = hourStr?.let { if (it.matches(Regex("\\d+"))) it.toInt() else toArabic(it) } ?: continue
                if (!hasIndicator || hour !in 0..23) continue
                val minute = minStr?.toIntOrNull() ?: 0
                val cal = newCal(baseMillis)
                cal.set(Calendar.HOUR_OF_DAY, adjustHourByAmPm(hour, ampm))
                cal.set(Calendar.MINUTE, minute)
                cal.set(Calendar.SECOND, 0)
                if (cal.timeInMillis < now.timeInMillis) cal.add(Calendar.DAY_OF_MONTH, 1)
                val (t, loc) = extractTitleAndLocation(sentence)
                Log.d(TAG, "time-only matched: ${Date(cal.timeInMillis)}")
                return ParseResult(cal.timeInMillis, cal.timeInMillis + 60 * 60 * 1000L, t, loc)
            }

            // 5) relative tokens mapping
            if (relativeMap != null) {
                val matched = relativeMap.keys.firstOrNull { sentence.contains(it) }
                if (matched != null) {
                    val spec = relativeMap[matched]!!
                    val cal = newCal(baseMillis)
                    cal.add(Calendar.DAY_OF_MONTH, spec.offsetDays)
                    // find explicit time; if none use default 9:00 or 19:00 for pm tokens
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
                        if (!hasIndicator || hCand !in 0..23) continue
                        explicitAmpm = ampm2
                        minute = minStr?.toIntOrNull() ?: 0
                        hour = hCand
                        break
                    }
                    if (hour == null) {
                        hour = if (spec.ampm == "pm") 19 else if (spec.ampm == "am") 9 else 9
                    }
                    val finalHour = if (explicitAmpm != null) adjustHourByAmPm(hour, explicitAmpm) else when (spec.ampm) {
                        "pm" -> if (hour < 12) hour + 12 else hour
                        "am" -> if (hour == 12) 0 else hour
                        else -> hour
                    }
                    cal.set(Calendar.HOUR_OF_DAY, finalHour)
                    cal.set(Calendar.MINUTE, minute)
                    cal.set(Calendar.SECOND, 0)
                    val (t, loc) = extractTitleAndLocation(sentence)
                    Log.d(TAG, "relative token matched for '$matched': ${Date(cal.timeInMillis)}")
                    return ParseResult(cal.timeInMillis, cal.timeInMillis + 60 * 60 * 1000L, t, loc)
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
        Log.d(TAG, "parseDateTimeInternal - no match for input: '$sentence'")
        return null
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

        // location patterns
    val locRegex = Regex("(?:地点|地址|场地|地点：|地点:|位置|集合地点)\\s*[:：]?\\s*([\\u4e00-\\u9fa5A-Za-z0-9\\-—–,，。、\\s]{2,60})")
        val locMatch = locRegex.find(sentence)
        if (locMatch != null) {
            location = locMatch.groupValues.getOrNull(1)?.trim()?.trimEnd('。', '，', ',')
        }

        // Try jieba combined top tokens first (if available) to get concise title phrases
        try {
            val combined = JiebaWrapper.combinedTopTokens(sentence, 4)
            if (!combined.isNullOrBlank()) {
                title = combined
            } else {
                val jiebaCands = JiebaWrapper.nounCandidates(sentence)
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
        } catch (_: Throwable) {
            // ignore segmentation errors and continue with heuristics
        }

        // title heuristics: look for verb + noun patterns
        val verbNoun = Regex("(开展|举办|召集|报名|招募|招|进行|开展|开展本学期第一次|开展本学期第一次|通知|请|组织|召开|申请|发起|开展本学期第一次)([\u4e00-\u9fa5A-Za-z0-9]{1,20})")
        val vn = verbNoun.find(sentence)
        if (vn != null) {
            val noun = vn.groupValues.getOrNull(2)
            if (!noun.isNullOrBlank()) {
                title = noun.trim()
            }
        }

        // if still no title, look for common event nouns inside sentence (e.g. 开会, 团课)
        if (title == null) {
            val commonEvents = listOf("开会", "会议", "团课", "大扫除", "志愿者", "报名", "截止", "考试", "活动", "志愿者")
            for (ev in commonEvents) {
                if (sentence.contains(ev)) {
                    title = ev
                    break
                }
            }
        }

        // fallback: try extract short meaningful phrase before location or before time
        if (title == null) {
            // remove leading polite tokens and mentions
            var s = sentence.replace(Regex("@全体成员|@所有人|请大家|各位|各位同学|各位老师|各位班主任|各位学委"), "")
            // split by punctuation and newlines
            val parts = s.split(Regex("[，,。.!！?？；;\\n\\r]"))
            for (p in parts) {
                val cleaned = p.trim()
                if (cleaned.length in 2..40 && !Regex("(请|注意|提醒|网址|链接|查看|详情|报名|要求)").containsMatchIn(cleaned)) {
                    // if contains a location marker, skip as title
                    if (Regex("地点|地址|时间|时间：|时间:").containsMatchIn(cleaned)) continue
                    // if contains date/time, skip
                    if (Regex("\\d{1,2}[:：\\.]\\d{1,2}|上午|下午|中午|晚上|明天|后天|今天|周|星期").containsMatchIn(cleaned)) {
                        // still might contain title before time, try split by spaces
                    }
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

        // If still no title, try take substring after last time/date token
        if (title == null) {
            try {
                var lastEnd = -1
                val tm = timePattern.matcher(sentence)
                while (tm.find()) lastEnd = maxOf(lastEnd, tm.end())
                val md = monthDayPattern.matcher(sentence)
                while (md.find()) lastEnd = maxOf(lastEnd, md.end())
                val wd = weekdayTimePattern.matcher(sentence)
                while (wd.find()) lastEnd = maxOf(lastEnd, wd.end())
                // also consider explicit '开始'/'结束' timestamps
                val startIdx = sentence.indexOf("开始时间")
                val endIdx = sentence.indexOf("结束时间")
                if (startIdx >= 0) lastEnd = maxOf(lastEnd, startIdx)
                if (endIdx >= 0) lastEnd = maxOf(lastEnd, endIdx)
                if (lastEnd >= 0 && lastEnd < sentence.length - 1) {
                    var tail = sentence.substring(lastEnd).trim()
                    // drop leading connectors
                    tail = tail.replaceFirst(Regex("^[\\\"::：\\-\\—\\s]*(为|是|为期|：|:)") , "").trim()
                    // cut at punctuation
                    tail = tail.split(Regex("[，,。.!！?？；;\\n\\r]"))[0].trim()
                    if (tail.length in 2..60 && !Regex("\\d{1,2}[:：\\.]\\d{1,2}|上午|下午|中午|晚上|明天|后天|今天|周|星期").containsMatchIn(tail)) {
                        title = tail.take(60)
                    }
                }
            } catch (_: Exception) {}
        }

        // final cleanups
        title = title?.trim()?.trimEnd('。', '，', ',', ':', '：')
        location = location?.trim()?.trimEnd('。', '，', ',')
        return Pair(title, location)
    }

    private fun adjustHourByAmPm(hour: Int, ampm: String?): Int {
        if (ampm == null) return hour
        val a = ampm.replace("\uFEFF", "")
        return when {
            // Evening / night contexts
            a.contains("下午") || a.contains("PM", true) || a.contains("晚") || a.contains("今晚") || a.contains("明晚") -> if (hour < 12) hour + 12 else hour
            // Early morning contexts: 凌晨 1/2/3/4/5 点 属于 0~5 点; 若写 12 凌晨则 interpret 为 0
            a.contains("凌晨") -> when {
                hour == 12 -> 0
                hour in 1..5 -> hour // keep 1~5
                else -> hour // others unchanged
            }
            a.contains("上午") || a.contains("AM", true) || a.contains("早") -> if (hour == 12) 0 else hour
            else -> hour
        }
    }
}
