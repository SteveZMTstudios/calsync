package top.stevezmt.calsync

import android.content.Context
import top.stevezmt.calsync.timenlp.internal.TimeNormalizer
import java.util.*

object TimeNLPAdapter {
    private var initialized = false
    private lateinit var normalizer: TimeNormalizer

    // Simple parse result with a confidence score (0..1)
    data class ParseSlot(val startMillis: Long, val endMillis: Long?, val text: String, val confidence: Double = 1.0)

    private data class CacheKey(val text: String, val baseMinute: Long)

    // very small LRU cache
    private val CACHE_SIZE = 64
    private val cache: LinkedHashMap<CacheKey, List<ParseSlot>> = object : LinkedHashMap<CacheKey, List<ParseSlot>>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, List<ParseSlot>>?): Boolean {
            return size > CACHE_SIZE
        }
    }

    fun init(context: Context) {
        if (initialized) return
        normalizer = TimeNormalizer()
        // no external model file for the simplified integration; the internal regex is used
        initialized = true
    }

    fun parse(context: Context, text: String, baseMillis: Long = System.currentTimeMillis()): List<ParseSlot> {
        if (!initialized) init(context)
        // key by minute to avoid cache miss due to millisecond differences
        val baseMinute = baseMillis / (60*1000L)
        val key = CacheKey(text, baseMinute)
        synchronized(cache) {
            cache[key]?.let { return it }
        }

        val cal = Calendar.getInstance()
        cal.timeInMillis = baseMillis
        normalizer.parse(text, cal)
        val units = normalizer.getTimeUnits()
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
            val exp = u.getExp()
            val tval = u.getResolvedTime()
            // if this unit already contains both date and time, emit directly
            if (hasDateInfo(exp) && hasTimeInfo(exp)) {
                if (tval != null) out.add(ParseSlot(tval, tval + 60*60*1000L, exp, 0.95))
                consumed[i] = true
                i++
                continue
            }

            // try merge date-only + time-only (current + next)
            if (hasDateInfo(exp) && !hasTimeInfo(exp) && i+1 < units.size && !consumed[i+1]) {
                val u2 = units[i+1]
                val exp2 = u2.getExp()
                if (hasTimeInfo(exp2)) {
                    val dateMillis = tval
                    val timeMillis = u2.getResolvedTime()
                    if (dateMillis != null && timeMillis != null) {
                        val dc = Calendar.getInstance(); dc.timeInMillis = dateMillis
                        val tc = Calendar.getInstance(); tc.timeInMillis = timeMillis
                        dc.set(Calendar.HOUR_OF_DAY, tc.get(Calendar.HOUR_OF_DAY))
                        dc.set(Calendar.MINUTE, tc.get(Calendar.MINUTE))
                        dc.set(Calendar.SECOND, 0)
                        out.add(ParseSlot(dc.timeInMillis, dc.timeInMillis + 60*60*1000L, exp + " " + exp2, 0.98))
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
                val expPrev = uPrev.getExp()
                if (hasDateInfo(expPrev)) {
                    val dateMillis = uPrev.getResolvedTime()
                    val timeMillis = tval
                    if (dateMillis != null && timeMillis != null) {
                        val dc = Calendar.getInstance(); dc.timeInMillis = dateMillis
                        val tc = Calendar.getInstance(); tc.timeInMillis = timeMillis
                        dc.set(Calendar.HOUR_OF_DAY, tc.get(Calendar.HOUR_OF_DAY))
                        dc.set(Calendar.MINUTE, tc.get(Calendar.MINUTE))
                        dc.set(Calendar.SECOND, 0)
                        out.add(ParseSlot(dc.timeInMillis, dc.timeInMillis + 60*60*1000L, expPrev + " " + exp, 0.98))
                        consumed[i] = true
                        consumed[i-1] = true
                        i++
                        continue
                    }
                }
            }

            // fallback: emit single unit
            if (tval != null) out.add(ParseSlot(tval, tval + 60*60*1000L, exp, 0.9))
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
