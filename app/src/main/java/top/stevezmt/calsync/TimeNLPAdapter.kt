package top.stevezmt.calsync

import android.util.Log
import top.stevezmt.calsync.timenlp.internal.TimeNormalizer
import java.util.Calendar

object TimeNLPAdapter {
    private const val TAG = "TimeNLPAdapter"
    private var initialized = false
    private lateinit var normalizer: TimeNormalizer

    // Simple parse result with a confidence score (0..1)
    data class ParseSlot(val startMillis: Long, val endMillis: Long?, val text: String, val confidence: Double = 1.0)

    private data class CacheKey(val text: String, val baseMinute: Long)

    // very small LRU cache
    private const val CACHE_SIZE = 64
    private val cache: LinkedHashMap<CacheKey, List<ParseSlot>> = object : LinkedHashMap<CacheKey, List<ParseSlot>>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, List<ParseSlot>>?): Boolean {
            return size > CACHE_SIZE
        }
    }

    fun init() {
        if (initialized) return
        normalizer = TimeNormalizer()
        // no external model file for the simplified integration; the internal regex is used
        initialized = true
    }

    fun parse(text: String, baseMillis: Long = System.currentTimeMillis()): List<ParseSlot> {
        if (!initialized) init()
        // key by minute to avoid cache miss due to millisecond differences
        val baseMinute = baseMillis / (60*1000L)
        val key = CacheKey(text, baseMinute)
        synchronized(cache) {
            cache[key]?.let { return it }
        }

        val cal = Calendar.getInstance()
        cal.timeInMillis = baseMillis
        normalizer.parse(text, cal)
        val units = normalizer.timeUnits
        // Debugging aid: log units for Friday 3 to 5 range to diagnose merging
        if (text.contains("周") && text.contains("3点") && text.contains("5点")) {
            Log.d(TAG, "[TimeNLPAdapter DEBUG] parsing text='" + text + "' units.size=" + units.size)
            for ((idx, u) in units.withIndex()) {
                val rc = u.resolvedTime
                val c = Calendar.getInstance()
                if (rc != null) c.timeInMillis = rc
                Log.d(TAG, "[TimeNLPAdapter DEBUG] unit[" + idx + "] exp='" + u.exp + "' resolved='" + (if (rc==null) "null" else (c.get(Calendar.YEAR).toString()+"-"+(c.get(Calendar.MONTH)+1)+"-"+c.get(Calendar.DAY_OF_MONTH)+" "+c.get(Calendar.HOUR_OF_DAY)+":"+c.get(Calendar.MINUTE))) + "'")
            }
        }
        // Quick direct patterns for relative durations not always captured as time-info units
        // e.g. "3个半小时后", "1天2小时后" — build slots directly from base
        fun parseChineseOrArabicK(s: String): Int {
            try { return s.toInt() } catch (_: Exception) {}
            val map = mapOf('零' to 0,'〇' to 0,'一' to 1,'二' to 2,'两' to 2,'三' to 3,'四' to 4,'五' to 5,'六' to 6,'七' to 7,'八' to 8,'九' to 9)
            var temp = 0
            for (c in s) {
                when (c) {
                    '十' -> { if (temp == 0) temp = 1; temp *= 10 }
                    '百' -> { if (temp == 0) temp = 1; temp *= 100 }
                    else -> if (map.containsKey(c)) { temp = temp * 10 + map[c]!! }
                }
            }
            if (temp == 0) return 0
            return temp
        }
        // direct half-hour
        val halfMatch = Regex("([一二三四五六七八九十百零0-9]+)个?半小时后").find(text)
        if (halfMatch != null) {
            val n = parseChineseOrArabicK(halfMatch.groupValues[1])
            val bc = Calendar.getInstance(); bc.timeInMillis = baseMillis
            bc.add(Calendar.HOUR_OF_DAY, n)
            bc.add(Calendar.MINUTE, 30)
            val slot = ParseSlot(bc.timeInMillis, bc.timeInMillis + 60*60*1000L, halfMatch.value, 0.96)
            synchronized(cache) { cache[key] = listOf(slot) }
            return listOf(slot)
        }
        val combinedMatch = Regex("([一二三四五六七八九十百零0-9]+)天([一二三四五六七八九十百零0-9]+)小时后").find(text)
        if (combinedMatch != null) {
            val d = parseChineseOrArabicK(combinedMatch.groupValues[1])
            val h = parseChineseOrArabicK(combinedMatch.groupValues[2])
            val bc = Calendar.getInstance(); bc.timeInMillis = baseMillis
            bc.add(Calendar.DAY_OF_MONTH, d)
            bc.add(Calendar.HOUR_OF_DAY, h)
            val slot = ParseSlot(bc.timeInMillis, bc.timeInMillis + 60*60*1000L, combinedMatch.value, 0.96)
            synchronized(cache) { cache[key] = listOf(slot) }
            return listOf(slot)
        }
        val out = mutableListOf<ParseSlot>()
        val consumed = BooleanArray(units.size)

        fun hasDateInfo(exp: String): Boolean {
            if (exp.contains("年") || exp.contains("月") || exp.contains("日") || exp.contains("号") || exp.contains("周") || exp.contains("周末") || exp.contains("本周") || exp.contains("下周")) return true
            // slash date like 10/05
            if (Regex("(?<!\\d)(\\d{1,2})/(\\d{1,2})(?!\\d)").containsMatchIn(exp)) return true
            // dot date like 9.28
            if (Regex("(?<!\\d)(\\d{1,2})[.](\\d{1,2})(?!\\d)").containsMatchIn(exp)) return true
            // dash date like 9-29
            if (Regex("(?<!\\d)(\\d{1,2})-(\\d{1,2})(?!\\d)").containsMatchIn(exp)) return true
            return false
        }
        fun hasTimeInfo(exp: String): Boolean {
            return exp.contains("点") || exp.contains(":") || exp.contains("：") || exp.contains("上午") || exp.contains("下午") || exp.contains("晚上") || exp.contains("凌晨") || exp.contains("早上") || exp.contains("中午") || exp.contains("傍晚")
        }

        var i = 0
        while (i < units.size) {
            if (consumed[i]) { i++; continue }
            val u = units[i]
            val exp = u.exp
            val tval = u.resolvedTime
            // if this unit already contains both date and time, emit directly
                // Special-case: if this unit contains both date+time but the original text has a range connector
                // between this unit and the next (e.g. '周五3点到5点'), handle as a range instead of emitting single
            if (hasDateInfo(exp) && hasTimeInfo(exp) && i+1 < units.size && !consumed[i+1]) {
                val u2 = units[i+1]
                val exp2 = u2.exp
                // try find occurrences of the unit expressions in the original text to inspect the between substring
                val idx1 = text.indexOf(exp)
                if (idx1 >= 0) {
                    val startSearch = idx1 + exp.length
                    val idx2 = text.indexOf(exp2, startSearch)
                    if (idx2 >= 0) {
                        val between = text.substring(startSearch, idx2)
                        if (between.contains("到") || between.contains("-") || between.contains("~")) {
                            val tval2 = u2.resolvedTime
                            if (tval != null && tval2 != null) {
                                val sc = Calendar.getInstance(); sc.timeInMillis = tval
                                val ec = Calendar.getInstance(); ec.timeInMillis = tval2
                                if (ec.get(Calendar.YEAR) == sc.get(Calendar.YEAR) && ec.get(Calendar.DAY_OF_YEAR) == sc.get(Calendar.DAY_OF_YEAR)) {
                                    // same day
                                } else {
                                    ec.set(Calendar.YEAR, sc.get(Calendar.YEAR))
                                    ec.set(Calendar.MONTH, sc.get(Calendar.MONTH))
                                    ec.set(Calendar.DAY_OF_MONTH, sc.get(Calendar.DAY_OF_MONTH))
                                }
                                val sh = sc.get(Calendar.HOUR_OF_DAY)
                                val eh = ec.get(Calendar.HOUR_OF_DAY)
                                if (sh in 0..6 && eh in 0..6) {
                                    sc.add(Calendar.HOUR_OF_DAY, 12)
                                    ec.add(Calendar.HOUR_OF_DAY, 12)
                                } else if (sh >= 12 && eh in 0..6) {
                                    ec.add(Calendar.HOUR_OF_DAY, 12)
                                }
                                out.add(ParseSlot(sc.timeInMillis, ec.timeInMillis,
                                    "$exp to $exp2", 0.97))
                                consumed[i] = true
                                consumed[i+1] = true
                                i += 2
                                continue
                            }
                        }
                    }
                }
            }

            // try merge date-only + time-only (current + next)
            if (hasDateInfo(exp) && !hasTimeInfo(exp) && i+1 < units.size && !consumed[i+1]) {
                val u2 = units[i+1]
                val exp2 = u2.exp
                if (hasTimeInfo(exp2)) {
                    val dateMillis = tval
                    val timeMillis = u2.resolvedTime
                    if (dateMillis != null && timeMillis != null) {
                        val dc = Calendar.getInstance(); dc.timeInMillis = dateMillis
                        val tc = Calendar.getInstance(); tc.timeInMillis = timeMillis
                        var hour = tc.get(Calendar.HOUR_OF_DAY)
                        val minute = tc.get(Calendar.MINUTE)
                        // Heuristic: if parsed time is a small hour (<=6), assume user meant PM when pairing with a date
                        if (hour in 0..6) hour += 12
                        dc.set(Calendar.HOUR_OF_DAY, hour)
                        dc.set(Calendar.MINUTE, minute)
                        dc.set(Calendar.SECOND, 0)
                        out.add(ParseSlot(dc.timeInMillis, dc.timeInMillis + 60*60*1000L,
                            "$exp $exp2", 0.98))
                        consumed[i] = true
                        consumed[i+1] = true
                        i += 2
                        continue
                    }
                }
            }

            // try merge time-only preceded by date-only (prev)
            if (!hasDateInfo(exp) && hasTimeInfo(exp) && i-1 >= 0 && !consumed[i-1]) {
                val uPrev = units[i-1]
                val expPrev = uPrev.exp
                if (hasDateInfo(expPrev)) {
                    val dateMillis = uPrev.resolvedTime
                    val timeMillis = tval
                    if (dateMillis != null && timeMillis != null) {
                        val dc = Calendar.getInstance(); dc.timeInMillis = dateMillis
                        val tc = Calendar.getInstance(); tc.timeInMillis = timeMillis
                        dc.set(Calendar.HOUR_OF_DAY, tc.get(Calendar.HOUR_OF_DAY))
                        dc.set(Calendar.MINUTE, tc.get(Calendar.MINUTE))
                        dc.set(Calendar.SECOND, 0)
                        out.add(ParseSlot(dc.timeInMillis, dc.timeInMillis + 60*60*1000L,
                            "$expPrev $exp", 0.98))
                        consumed[i] = true
                        consumed[i-1] = true
                        i++
                        continue
                    }
                }
            }

            // try detect simple range like '3点到5点' or '15:00-17:00'
            if (tval != null && i+1 < units.size && !consumed[i+1]) {
                val u2 = units[i+1]
                val exp2 = u2.exp
                val tval2 = u2.resolvedTime
                if ((exp.contains("到") || exp.contains("-") || exp.contains("~")) && tval2 != null) {
                    // ensure end inherits date from start if missing
                    val sc = Calendar.getInstance(); sc.timeInMillis = tval
                    val ec = Calendar.getInstance(); ec.timeInMillis = tval2
                    if (ec.get(Calendar.YEAR) == sc.get(Calendar.YEAR) && ec.get(Calendar.DAY_OF_YEAR) == sc.get(Calendar.DAY_OF_YEAR)) {
                        // already same day
                    } else {
                        // overwrite date parts from start
                        ec.set(Calendar.YEAR, sc.get(Calendar.YEAR))
                        ec.set(Calendar.MONTH, sc.get(Calendar.MONTH))
                        ec.set(Calendar.DAY_OF_MONTH, sc.get(Calendar.DAY_OF_MONTH))
                    }
                    // Heuristic: if the start is in PM (>=12) and end is a small hour (<=6)
                    // it's likely the user meant PM as well (e.g., "3点到5点" in afternoon -> 15:00-17:00)
                    val sh = sc.get(Calendar.HOUR_OF_DAY)
                    val eh = ec.get(Calendar.HOUR_OF_DAY)
                    // If both are small hours (e.g., 3 and 5) on a weekday range like "周五3点到5点",
                    // user likely meant afternoon -> convert both to PM
                    if (sh in 0..6 && eh in 0..6) {
                        sc.add(Calendar.HOUR_OF_DAY, 12)
                        ec.add(Calendar.HOUR_OF_DAY, 12)
                    } else if (sh >= 12 && eh in 0..6) {
                        // if start is already PM and end parsed as small hour, make end PM too
                        ec.add(Calendar.HOUR_OF_DAY, 12)
                    }
                    out.add(ParseSlot(sc.timeInMillis, ec.timeInMillis, "$exp to $exp2", 0.97))
                    consumed[i] = true
                    consumed[i+1] = true
                    i += 2
                    continue
                }
            }

            // fallback: emit single unit
            if (tval != null) {
                // handle relative half-hour expressions explicitly when TimeUnit didn't normalize as expected
                try {
                    // val halfMatch = Regex("(\\d+)\\s*个?半\\s*小时").find(exp)
//                    if (halfMatch != null) {
//                    }
                } catch (_: Exception) {
                    // ignore and fallback
                }
                out.add(ParseSlot(tval, tval + 60*60*1000L, exp, 0.9))
            }
            consumed[i] = true
            i++
        }

        val result = out.toList()
        synchronized(cache) {
            cache[key] = result
        }
        return result
    }
}
