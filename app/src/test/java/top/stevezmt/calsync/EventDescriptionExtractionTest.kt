package top.stevezmt.calsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class EventDescriptionExtractionTest {

    @Test
    fun testTeamDinner() {
        val text = "周六晚上7点团队聚餐"
        val r = DateTimeParser.parseDateTime(text)
        assertNotNull(r)
        assertEquals("团队聚餐", r!!.title)
    }

    @Test
    fun testProductReview() {
        val text = "下周三14:30产品评审"
        val r = DateTimeParser.parseDateTime(text)
        assertNotNull(r)
        assertEquals("产品评审", r!!.title)
    }

    @Test
    fun testOnlineDiscussionWithRemember() {
        val text = "记得晚上8点线上讨论"
        val r = DateTimeParser.parseDateTime(text)
        assertNotNull(r)
        assertEquals("记得线上讨论", r!!.title)
    }
}
