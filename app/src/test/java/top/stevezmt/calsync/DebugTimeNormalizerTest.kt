package top.stevezmt.calsync

import org.junit.Test
import top.stevezmt.calsync.timenlp.internal.TimeNormalizer
import java.text.SimpleDateFormat
import java.util.*

class DebugTimeNormalizerTest {
    @Test
    fun dumpUnits() {
        val base = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2025)
            set(Calendar.MONTH, Calendar.SEPTEMBER)
            set(Calendar.DAY_OF_MONTH, 16)
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val inputs = listOf("本周五下午3点汇报", "下周五", "下下周三早上8点会议")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        for (s in inputs) {
            println("--- INPUT: $s ---")
            val tn = TimeNormalizer()
            tn.parse(s, base)
            val units = tn.getTimeUnits()
            if (units.isEmpty()) { println("  no units") ; continue }
            for (u in units) {
                val resolved = u.getResolvedTime()
                val text = u.getExp()
                val rstr = if (resolved==null) "null" else fmt.format(Date(resolved))
                println("  exp='$text' resolved=$rstr")
            }
        }
    }
}
