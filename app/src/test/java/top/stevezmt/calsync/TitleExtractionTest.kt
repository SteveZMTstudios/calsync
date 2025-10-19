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

    @Test
    fun testBulkTitleExtractionVariety() {
        val cases = listOf(
            // meetings
            Pair("明天下午3点 开班会，地点：教学楼A101", listOf("班会")),
            Pair("周一上午9:00 部门例会", listOf("例会", "会议")),
            Pair("今天16:00 项目启动会请准时参加", listOf("启动会", "项目启动", "启动", "参加", "准时参加")),
            Pair("本周三 10:30 开团队沟通会", listOf("沟通会", "团队沟通")),
            Pair("周五晚7点 线上读书会", listOf("读书会")),
            // exams
            Pair("9月30日 14:00 数学期中考试，地址：第二教学楼301", listOf("考试")),
            Pair("下周一 上午9点 英语四级考试", listOf("英语四级", "考试")),
            Pair("本周五15:00 实践考核（计算机）", listOf("考核", "考试")),
            // lectures & talks
            Pair("明天 18:00 学术讲座：人工智能前沿", listOf("讲座", "人工智能")),
            Pair("今日下午2点 推荐讲座：区块链应用", listOf("讲座")),
            Pair("本周三14:00 专题报告：量子计算", listOf("报告", "专题", "量子计算")),
            // sports & activities
            Pair("周六早上7点 篮球训练在体育馆集合", listOf("篮球")),
            Pair("今天晚上8点 羽毛球活动，学生中心", listOf("羽毛球")),
            Pair("周日 10:00 慢跑活动", listOf("慢跑", "跑步")),
            // classes & exams
            Pair("下周二09:00 线性代数期末考试", listOf("期末考试", "考试")),
            Pair("周四 14:00 有机化学 期中测验", listOf("测验", "考试")),
            // celebrations & parties
            Pair("明晚7点 班级联欢会 于学生会大厅", listOf("联欢会", "晚会")),
            Pair("周五 18:30 毕业晚会", listOf("晚会")),
            // deadlines & reminders
            Pair("明天中午12:00 截止：提交论文初稿", listOf("截止", "提交")),
            Pair("今晚 23:59 报名截止，请尽快提交表格", listOf("报名", "截止")),
            // ceremonies
            Pair("周一 上午9点 开学典礼，礼堂见", listOf("典礼", "开学")),
            Pair("下个月1日 毕业典礼", listOf("毕业典礼", "典礼")),
            // interviews/defense
            Pair("关于研究生复试通知：明天下午2点 面试安排", listOf("面试", "复试")),
            Pair("答辩定于本周五 10:00 确认到场", listOf("答辩")),
            // workshops & training
            Pair("10月10日 09:00 Python 培训班", listOf("培训", "班")),
            Pair("本周三 下午3点 职业规划讲座与培训", listOf("培训", "讲座")),
            // medical & health
            Pair("下周二 上午9点 体检 请到卫生院", listOf("体检")),
            Pair("周四 下午2:00 疫苗接种点：学生活动中心", listOf("接种", "疫苗")),
            // business & finance
            Pair("9月1日 财务会议 10:00 总务处", listOf("会议", "财务")),
            Pair("本周二 15:00 招投标说明会", listOf("说明会", "招投标")),
            // cultural
            Pair("周末 19:00 音乐会：校园音乐节", listOf("音乐会", "音乐节")),
            Pair("本月20日 电影放映 夜间活动", listOf("电影", "放映")),
            // volunteer & community
            Pair("周六9:00 志愿服务 集合地：图书馆门口", listOf("志愿")),
            Pair("周日 14:00 社区服务活动", listOf("社区", "服务")),
            // online & remote
            Pair("今晚 20:00 在线研讨会 Zoom 链接已发送", listOf("研讨会", "在线")),
            Pair("下周一 19:00 线上会议：产品说明", listOf("线上", "会议")),
            // campus admin notices
            Pair("关于图书馆换季开放时间的通知：周末仍然开放", listOf("通知", "图书馆")),
            Pair("教务处通知：本学期选课时间调整", listOf("通知", "选课")),
            // ambiguous/short titles
            Pair("周三 14:00 开会", listOf("开会", "会议")),
            Pair("明天9点 面谈请到系办公室", listOf("面谈", "面试")),
            // multi-event phrasing
            Pair("本周五下午3点到5点 小组讨论与汇报", listOf("讨论", "汇报")),
            Pair("周一9:00-11:00 教学检查、巡视", listOf("检查", "巡视")),
            // time ranges
            Pair("下周二 13:30-15:30 实验课（化学）", listOf("实验课", "实验")),
            Pair("周四 8:00-10:00 实践实训", listOf("实训", "实践")),
            // relative times
            Pair("三天后下午2点 召开工作会议", listOf("会议", "工作会议")),
            Pair("下周三 上午10点 召开协调会", listOf("协调会")),
            // reminders without explicit title words
            Pair("明天14:00 请同学们参加活动，地点另行通知", listOf("活动")),
            Pair("今天下午请到行政楼办理手续", listOf("办理", "手续")),
            // short notifications
            Pair("周五 9点 开幕", listOf("开幕", "仪式")),
            Pair("今天18:00 讲座取消", listOf("讲座")),
            // long sentence with keywords
            Pair("关于暑期实习分配的通知：两周后开始，请在期限内报名参加实习说明会", listOf("实习", "说明会"))
            
        )

        for ((text, expectedKeywords) in cases) {
            val r = DateTimeParser.parseDateTime(text)
            assertNotNull("Parse failed for: $text", r)
            val t = r!!.title ?: ""
            // at least one expected keyword should appear in the title
            val matched = expectedKeywords.any { k -> t.contains(k) }
            assert(matched) { "Unexpected title for '$text': '$t', expected one of $expectedKeywords" }
        }
    }
}
