package top.stevezmt.calsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.*

/**
 * Tests rely on the TimeNLPAdapter + DateTimeParser rule fallback.
 * We freeze a base date: 2025-09-16 10:00:00 local time (Tuesday).
 * For context-based parse we call TimeNLPAdapter directly since public API of DateTimeParser(context, ...) needs Android Context.
 * These are logic / algorithm tests only (not instrumentation). Some expectations use relative differences.
 */
class DateTimeParserTest {

    private val baseCal: Calendar = Calendar.getInstance().apply {
        set(Calendar.YEAR, 2025)
        set(Calendar.MONTH, Calendar.SEPTEMBER) // 8 internally
        set(Calendar.DAY_OF_MONTH, 16) // Tuesday
        set(Calendar.HOUR_OF_DAY, 10)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun parseSlots(text: String): List<TimeNLPAdapter.ParseSlot> {
        // Use internal adapter with provided base time
        return TimeNLPAdapter.parse(DummyContext, text, baseCal.timeInMillis)
    }

    @Test
    fun testTonightEight() {
        val slots = parseSlots("今晚8点开会")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(Calendar.SEPTEMBER, cal.get(Calendar.MONTH))
        assertEquals(16, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(20, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testTomorrowMorningNine() {
        val slots = parseSlots("明天上午9点集合")
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(17, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testNextWeekWednesdayEvening() {
        val slots = parseSlots("下周三晚上6点讨论")
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // Base date 2025-09-16 Tue. Next week Wed should be 2025-09-24
        assertEquals(24, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(18, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testWeekendPhrase() {
        val slots = parseSlots("这个周末活动")
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // 2025-09-16 Tue -> that weekend Saturday is 2025-09-20
        assertEquals(20, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testAfterTomorrowEarlyMorning() {
        val slots = parseSlots("后天凌晨1点值班")
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // 后天 from 16th -> 18th
        assertEquals(18, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(1, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testAfterNight() {
        val slots = parseSlots("后晚开会")
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // 后晚 interpreted as day+2 evening (20:00) -> 18th 20:00
        assertEquals(18, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(20, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testTomorrowEvening730() {
        val slots = parseSlots("明晚7:30 开会")
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(17, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(19, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testThisFridayAfternoon() {
        val slots = parseSlots("本周五下午3点汇报")
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // 2025-09-16 Tue -> this week's Friday is 2025-09-19
        assertEquals(19, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(15, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testUserScenarioFriday1310() {
        // User scenario: now is 2025-09-17 Wed 23:45, message: "周五下午13:10"
        val base = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2025)
            set(Calendar.MONTH, Calendar.SEPTEMBER)
            set(Calendar.DAY_OF_MONTH, 17) // Wednesday
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 45)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val slots = TimeNLPAdapter.parse(DummyContext, "周五下午13:10", base.timeInMillis)
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // Expect Friday 2025-09-19 13:10
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(Calendar.SEPTEMBER, cal.get(Calendar.MONTH))
        assertEquals(19, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(13, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(10, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testNextWeekFridayDefault() {
        val slots = parseSlots("下周五")
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // Next week Friday from base 2025-09-16 is 2025-09-26
        assertEquals(26, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testTwoWeeksLaterWednesdayMorning() {
        val slots = parseSlots("下下周三早上8点会议")
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // Expected 2025-10-01
        assertEquals(1, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, cal.get(Calendar.HOUR_OF_DAY))
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
    fun testExplicitStartTimestamp() {
        // exact explicit start time using hyphenated date and ASCII colon
        val r = DateTimeParser.parseDateTime("开始时间：2025-08-3 14:30 课程")
        assertNotNull(r)
        val cal = Calendar.getInstance().apply { timeInMillis = r!!.startMillis }
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(Calendar.AUGUST, cal.get(Calendar.MONTH))
        assertEquals(3, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testExplicitStartEndRange() {
        val r = DateTimeParser.parseDateTime("开始时间：2025-08-03 14:30 结束时间：2025-08-03 16:00 会议")
    assertNotNull(r)
    val rr = r!!
    val s = Calendar.getInstance().apply { timeInMillis = rr.startMillis }
    val e = Calendar.getInstance().apply { timeInMillis = rr.endMillis!! }
        assertEquals(2025, s.get(Calendar.YEAR))
        assertEquals(Calendar.AUGUST, s.get(Calendar.MONTH))
        assertEquals(3, s.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, s.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, s.get(Calendar.MINUTE))
        assertEquals(16, e.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, e.get(Calendar.MINUTE))
    }

    @Test
    fun testExplicitStartFullwidthColon() {
        // fullwidth colon should be accepted
        val r = DateTimeParser.parseDateTime("开始时间：2025-08-03 14：30 课程")
        assertNotNull(r)
        val cal = Calendar.getInstance().apply { timeInMillis = r!!.startMillis }
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(Calendar.AUGUST, cal.get(Calendar.MONTH))
        assertEquals(3, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testChineseMonthDayWithTime() {
        // use TimeNLPAdapter with frozen base to parse explicit Chinese month/day string
        val slots = parseSlots("8月3日 14:30 会议")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(Calendar.AUGUST, cal.get(Calendar.MONTH))
        assertEquals(3, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testChineseMonthDayWithDaySuffix() {
        val slots = parseSlots("8月3号14:30 报到")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(Calendar.AUGUST, cal.get(Calendar.MONTH))
        assertEquals(3, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    // ---- 20 more colloquial/exact tests ----
    @Test
    fun testColloqFri1330() {
        val slots = parseSlots("周五下午13:30 开课")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(19, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(13, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testColloqThisFri1330() {
        val slots = parseSlots("本周五下午1:30 有课")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(19, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(13, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testColloqNextFri1330() {
        val slots = parseSlots("下周五下午1:30 会议")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(26, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(13, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testAfterTomorrowMorning8() {
        val slots = parseSlots("后天早上8点 值班")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(18, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testTomorrowMorning8() {
        val slots = parseSlots("明天早上8点 集合")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(17, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testSep30Default9() {
        val slots = parseSlots("9月30号 活动")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(Calendar.SEPTEMBER, cal.get(Calendar.MONTH))
        assertEquals(30, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testSep30At14() {
        val slots = parseSlots("9月30日 14:00 会议")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(30, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testNov19Afternoon6() {
        val slots = parseSlots("十一月19号下午6点 晚会")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(Calendar.NOVEMBER, cal.get(Calendar.MONTH))
        assertEquals(19, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(18, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testNov19At18() {
        val slots = parseSlots("11月19日 18:00 活动")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(Calendar.NOVEMBER, cal.get(Calendar.MONTH))
        assertEquals(19, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(18, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testThuEvening7() {
        val slots = parseSlots("周四晚上7点 讨论")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(18, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(19, cal.get(Calendar.HOUR_OF_DAY))
    }

    // @Test
    // fun testThisWeekendPhrase2() {
    //     val slots = parseSlots("这个周末有活动")
    //     assertNotNull(slots.firstOrNull())
    //     val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
    //     assertEquals(20, cal.get(Calendar.DAY_OF_MONTH))
    // }

    @Test
    fun testThisSaturday14() {
        val slots = parseSlots("本周六下午两点 聚会")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(20, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testSundayNoon() {
        val slots = parseSlots("周日中午12点 聚餐")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(21, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(12, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testTonight9() {
        val slots = parseSlots("今晚9点 看球")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(16, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(21, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testTodayAfternoon3() {
        val slots = parseSlots("今天下午3点 预约")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(16, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(15, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testTomorrowAfternoon4() {
        val slots = parseSlots("明天下午四点 培训")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(17, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(16, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testThisWed10() {
        val slots = parseSlots("本周三上午10点 讨论")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(17, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(10, cal.get(Calendar.HOUR_OF_DAY))
    }

    // ---- 15 additional instant-message style tests ----
    @Test
    fun testIM_LunchTomorrow1230() {
        val slots = parseSlots("明天中午12:30 一起吃饭")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(17, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(12, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testIM_Tonight630() {
        val slots = parseSlots("今晚6:30 出发")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(16, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(18, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testIM_TomorrowAfternoon330() {
        val slots = parseSlots("明天下午3:30 视频会")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(17, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(15, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testIM_NextSatMorningNine() {
        val slots = parseSlots("下周六上午9点 慢跑")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // base Tue 16 -> next week's Saturday is 2025-09-27
        assertEquals(27, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testIM_ThisMon830() {
        val slots = parseSlots("周一8:30 汇报")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // this week's Monday after base Tue -> next Monday 22
        assertEquals(22, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testIM_TwoDaysAfterSeven() {
        val slots = parseSlots("后天7点 开始")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(18, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(7, cal.get(Calendar.HOUR_OF_DAY))
    }

    // @Test
    // fun testIM_EveningPhrase() {
    //     val slots = parseSlots("改天晚上8点 线上")
    //     assertNotNull(slots.firstOrNull())
    //     val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
    //     // interpret as next occurrence: from base Tue -> this weekend? expect 20th 20:00
    //     assertEquals(20, cal.get(Calendar.DAY_OF_MONTH))
    //     assertEquals(20, cal.get(Calendar.HOUR_OF_DAY))
    // }

    @Test
    fun testIM_TomorrowMorning() {
        val slots = parseSlots("明天早上 集合")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // default to 9:00
        assertEquals(17, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testIM_NowPlusOneHour() {
        val slots = parseSlots("一小时后 提醒我")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // base 10:00 -> 11:00
        assertEquals(16, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(11, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testIM_TonightMidnight() {
        val slots = parseSlots("今晚午夜 开始")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // midnight means next day 00:00 -> 17th 00:00
        assertEquals(17, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testIM_ThisEvening745() {
        val slots = parseSlots("今晚7点45 分电影")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(16, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(19, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(45, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testIM_NextThuMorning() {
        val slots = parseSlots("下周四上午9点 面试")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // next Thursday from base Tue16 -> 2025-09-25
        assertEquals(25, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
    }

    // @Test
    // fun testIM_EndOfMonthNoon() {
    //     val slots = parseSlots("本月最后一天中午12点 总结")
    //     assertNotNull(slots.firstOrNull())
    //     val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
    //     // September 30
    //     assertEquals(30, cal.get(Calendar.DAY_OF_MONTH))
    //     assertEquals(12, cal.get(Calendar.HOUR_OF_DAY))
    // }

    @Test
    fun testIM_ExplicitDateShort() {
        val slots = parseSlots("10/05 14:00 聚会")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // interpreted as 2025-10-05
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(Calendar.OCTOBER, cal.get(Calendar.MONTH))
        assertEquals(5, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testNextMondayDefault2() {
        val slots = parseSlots("下周一")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // next week's Monday should be 2025-09-22
        assertEquals(22, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testExplicitEndOfYear() {
        val r = DateTimeParser.parseDateTime("开始时间：2025-12-31 23:59 年终总结")
        assertNotNull(r)
        val cal = Calendar.getInstance().apply { timeInMillis = r!!.startMillis }
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(Calendar.DECEMBER, cal.get(Calendar.MONTH))
        assertEquals(31, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(23, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testExplicitStartOct5() {
        val r = DateTimeParser.parseDateTime("开始时间：2025-10-05 09:00 例会")
        assertNotNull(r)
        val cal = Calendar.getInstance().apply { timeInMillis = r!!.startMillis }
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(Calendar.OCTOBER, cal.get(Calendar.MONTH))
        assertEquals(5, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
    }
    @Test
    fun testREAL_Friday1310() {
        val slots = parseSlots("周五下午13:10开团课")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(19, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(13, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(10, cal.get(Calendar.MINUTE))
    }

    // --- 15 new IM-style tests (relative and absolute formats) ---
    @Test
    fun testIM_TomorrowEvening1815() {
        val slots = parseSlots("明天18:15 看电影")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // base 2025-09-16 -> tomorrow 17th 18:15
        assertEquals(17, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(18, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(15, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testIM_DayAfterTomorrow0930() {
        val slots = parseSlots("大后天9:30 早会")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // base 16 -> 大后天 = 19th
        assertEquals(19, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testIM_NextMon1400() {
        val slots = parseSlots("下周一14:00 报告")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // next week's Monday from base 16 -> 2025-09-22 +7 = 22
        assertEquals(22, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testIM_LastSun0700() {
        val slots = parseSlots("周日7:00 运动")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // this week's Sunday is 21st
        assertEquals(21, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(7, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testIM_Sep28Dot1330() {
        val slots = parseSlots("9.28 13:30 聚会")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // interpreted as 2025-09-28
        assertEquals(28, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(13, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testIM_ChineseSep28_0900() {
        val slots = parseSlots("九月28号 09:00 签到")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(28, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testIM_SlashSep30_0830() {
        val slots = parseSlots("9/30 08:30 报到")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(30, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testIM_DashSep29_1730() {
        val slots = parseSlots("9-29 17:30 下班聚")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(29, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(17, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testIM_TomorrowMorning0830_colloq() {
        val slots = parseSlots("明天早上8:30 上课")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(17, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testIM_Tonight2359() {
        val slots = parseSlots("今晚23:59 截止")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // tonight -> 16th 23:59
        assertEquals(16, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(23, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testIM_NextThu1200() {
        val slots = parseSlots("下周四12:00 吃饭")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // next Thursday -> 25th
        assertEquals(25, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(12, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testIM_ExplicitChineseNumeric_9dot30_1005() {
        val slots = parseSlots("9.30 10:05 会议")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(30, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(10, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(5, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testIM_RelativeNextSat0930() {
        val slots = parseSlots("下周六9:30 跑步")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // next week's Saturday -> 27th
        assertEquals(27, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testIM_ShortForm_10slash05_0730() {
        val slots = parseSlots("10/05 07:30 早餐")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // interpreted as 2025-10-05
        assertEquals(5, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.OCTOBER, cal.get(Calendar.MONTH))
        assertEquals(7, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testIM_SingleDigitDotDate() {
        val slots = parseSlots("9.8 9:30 聚会")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(8, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testIM_SingleDigitDashDate() {
        val slots = parseSlots("9-8 09:05 会议")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(8, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(5, cal.get(Calendar.MINUTE))
    }
    
    @Test
    fun testIM_TomorrowAfternoon6() {
        val slots = parseSlots("明天下午6点开会")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(17, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(18, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testIM_DotDateNoSpaceJoin() {
        val slots = parseSlots("9.28-13:30 聚会")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        assertEquals(28, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(13, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testIM_MorningZeroHour() {
        val slots = parseSlots("明早0点 提醒")
        assertNotNull(slots.firstOrNull())
        val cal = Calendar.getInstance().apply { timeInMillis = slots.first().startMillis }
        // base 2025-09-16 -> 明早0点 -> 2025-09-17 00:00
        assertEquals(17, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
    }

    object DummyContext: android.content.ContextWrapper(null)
}
