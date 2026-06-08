package top.stevezmt.calsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TimeNLPAdapterFunctionalTest {

    @Test
    fun testParseSimpleDateTime() {
        val baseCal = Calendar.getInstance()
        baseCal.set(2026, Calendar.APRIL, 9, 10, 0, 0)
        
        val slots = TimeNLPAdapter.parse("下周三开会", baseCal.timeInMillis)
        assertTrue(slots.isNotEmpty())
        assertTrue(slots.first().startMillis > 0L)
    }

    @Test
    fun testParseEveningShift() {
        val baseCal = Calendar.getInstance()
        baseCal.set(2026, Calendar.APRIL, 9, 10, 0, 0)

        val slots = TimeNLPAdapter.parse("晚上吃大餐", baseCal.timeInMillis)
        assertTrue(slots.isNotEmpty())
        
        val cal = Calendar.getInstance()
        cal.timeInMillis = slots.first().startMillis
        assertTrue(cal.get(Calendar.HOUR_OF_DAY) >= 18)
    }

    @Test
    fun testParseNotTime() {
        val baseCal = Calendar.getInstance()
        baseCal.set(2026, Calendar.APRIL, 9, 10, 0, 0)

        val slots = TimeNLPAdapter.parse("这个没有时间信息的内容", baseCal.timeInMillis)
        assertTrue(slots.isEmpty())
    }

    @Test
    fun testRelativeTime() {
        val baseCal = Calendar.getInstance()
        baseCal.set(2026, Calendar.APRIL, 9, 10, 0, 0)

        val slots = TimeNLPAdapter.parse("2小时后培训", baseCal.timeInMillis)
        assertTrue(slots.isNotEmpty())
        
        val cal = Calendar.getInstance()
        cal.timeInMillis = slots.first().startMillis
        assertEquals(12, cal.get(Calendar.HOUR_OF_DAY))
    }
}
