package top.stevezmt.calsync

import org.junit.Assert.*
import org.junit.Test
import java.util.*

/**
 * Representative tests with base date frozen to 2025-09-19 10:00 (Friday).
 */
class DateTimeParserTest_20250919 {

    private val baseCal: Calendar = Calendar.getInstance().apply {
        set(Calendar.YEAR, 2025)
        set(Calendar.MONTH, Calendar.SEPTEMBER)
        set(Calendar.DAY_OF_MONTH, 19) // Friday
        set(Calendar.HOUR_OF_DAY, 10)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun parseSlots(text: String): List<TimeNLPAdapter.ParseSlot> {
        return TimeNLPAdapter.parse(text, baseCal.timeInMillis)
    }

    @Test
    fun testTonightEight() {
        val slots = parseSlots("今晚8点开会")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // base is 19th -> tonight is 19th 20:00
        assertEquals(19, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(20, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testTomorrowMorningNine() {
        val slots = parseSlots("明天上午9点集合")
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(20, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testNextWeekWednesdayEvening() {
        val slots = parseSlots("下周三晚上6点讨论")
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // base Fri 19 -> next week Wed should be 2025-09-24
        assertEquals(24, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(18, cal.get(Calendar.HOUR_OF_DAY))
    }
    @Test
    fun testTomorrowAfternoon4() {
    }
    @Test
    fun testExplicitChineseDate() {
        val slots = parseSlots("二零二五年九月二十六日上午十点 会议")
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(Calendar.SEPTEMBER, cal.get(Calendar.MONTH))
        assertEquals(26, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(10, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testExplicitShortSlash() {
        val slots = parseSlots("10/05 14:00 聚会")
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // interpreted as 2025-10-05
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(Calendar.OCTOBER, cal.get(Calendar.MONTH))
        assertEquals(5, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testRelativePlusOneHour() {
        val slots = parseSlots("一小时后 提醒我")
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // base 10:00 -> 11:00 same day
        assertEquals(19, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(11, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testTonightMidnight() {
        val slots = parseSlots("今晚午夜 开始")
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // midnight -> next day 00:00 -> 20th 00:00
        assertEquals(20, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testThisSaturday14() {
        val slots = parseSlots("本周六下午两点 聚会")
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // this Saturday from Fri19 -> 20th
        assertEquals(20, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testNextMondayDefault() {
        val slots = parseSlots("下周一")
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // next week's Monday from base 19 -> 2025-09-22
        assertEquals(22, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testDayAfterTomorrowSeven() {
        val slots = parseSlots("后天7点 开始")
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // from 19 -> 后天 = 21
        assertEquals(21, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(7, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testChineseMonthDayWithTime() {
        val slots = parseSlots("8月3日 14:30 会议")
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // explicit 8/3 should be 2025-08-03
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(Calendar.AUGUST, cal.get(Calendar.MONTH))
        assertEquals(3, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testRangeStartEndParsing() {
        val r = DateTimeParser.parseDateTime("开始时间：2025-10-05 09:00 结束时间：2025-10-05 10:30 例会")
        assertNotNull(r)
        val rr = r!!
        val s = Calendar.getInstance().apply { timeInMillis = rr.startMillis }
        val e = Calendar.getInstance().apply { timeInMillis = rr.endMillis!! }
        assertEquals(5, s.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, s.get(Calendar.HOUR_OF_DAY))
        assertEquals(10, e.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, e.get(Calendar.MINUTE))
    }

    @Test
    fun testShortDotDateNoSpace() {
        val slots = parseSlots("9.28-13:30 聚会")
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(28, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(13, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    object DummyContext: android.content.ContextWrapper(null)
    }


