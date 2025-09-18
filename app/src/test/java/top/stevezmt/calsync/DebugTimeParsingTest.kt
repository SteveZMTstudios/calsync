package top.stevezmt.calsync

import org.junit.Test
import java.util.*

class DebugTimeParsingTest {
    private val baseCal: Calendar = Calendar.getInstance().apply {
        set(Calendar.YEAR, 2025)
        set(Calendar.MONTH, Calendar.SEPTEMBER)
        set(Calendar.DAY_OF_MONTH, 16)
        set(Calendar.HOUR_OF_DAY, 10)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun parseSlots(text: String) = TimeNLPAdapter.parse(DateTimeParserTest.DummyContext, text, baseCal.timeInMillis)

    @Test
    fun dumpSamples() {
        val samples = listOf("本周五下午3点汇报", "下周五", "周四晚上7点 讨论", "本周六下午两点 聚会")
        for (s in samples) {
            val slots = parseSlots(s)
            println("Input: '$s' -> slots size=${slots.size}")
            for ((i, slot) in slots.withIndex()) {
                val cal = Calendar.getInstance().apply { timeInMillis = slot.startMillis }
                println("  [$i] start=${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)+1}-${cal.get(Calendar.DAY_OF_MONTH)} ${cal.get(Calendar.HOUR_OF_DAY)}:${cal.get(Calendar.MINUTE)} text='${slot.text}'")
            }
        }
    }
}
