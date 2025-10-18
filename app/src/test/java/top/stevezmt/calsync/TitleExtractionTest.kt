package top.stevezmt.calsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TitleExtractionTest {

    @Test
    fun testExtractSimpleMeeting() {
        val r = DateTimeParser.parseDateTime("明天下午3点 开班会，地点：教学楼A101")
        assertNotNull(r)
        val t = r!!.title ?: ""
        // Accept either 精确"班会"或包含该关键词的短语
        assert(t == "班会" || t.contains("班会")) { "Unexpected title: '$t'" }
    }

    @Test
    fun testExtractExam() {
        val r = DateTimeParser.parseDateTime("9月30日 14:00 数学期中考试，地址：第二教学楼301")
        assertNotNull(r)
        val t = r!!.title ?: ""
        // 允许 '考试'/'期中考试'/'数学期中考试' 等合理变体
        assert(t.contains("考试")) { "Unexpected title: '$t'" }
    }

    @Test
    fun testAboutNotificationPattern() {
        val r = DateTimeParser.parseDateTime("关于毕业设计答辩的通知 本周五下午1:30 开始，地点：报告厅")
        assertNotNull(r)
        val t = r!!.title ?: ""
        assert(t.contains("答辩")) { "Unexpected title: '$t'" }
    }
}
