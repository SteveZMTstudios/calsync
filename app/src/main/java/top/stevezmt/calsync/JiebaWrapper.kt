package top.stevezmt.calsync

import com.huaban.analysis.jieba.JiebaSegmenter

object JiebaWrapper {
    private val segmenter = JiebaSegmenter()

    /**
     * Return candidate noun-like tokens using simple segmentation (no POS reliance).
     */
    fun nounCandidates(sentence: String): List<String> {
        val toks = segmenter.sentenceProcess(sentence)
        val candidates = ArrayList<String>()
        for (w in toks) {
            val word = w.trim()
            if (word.length in 2..30) {
                // filter out purely numeric/date/time tokens
                if (!word.matches(Regex("^[0-9\\-:年月日点]+$"))) {
                    candidates.add(word)
                }
            }
        }
        return candidates
    }

    /**
     * Combine top-N frequent tokens into a short title-like phrase.
     * We count token frequency in the sentence and join the top `n` tokens with no separator.
     * This aims to produce a short, readable candidate like '数学期中考试' from tokens ["数学","期中","考试"].
     */
    fun combinedTopTokens(sentence: String, n: Int = 4): String? {
        val toks = nounCandidates(sentence)
        if (toks.isEmpty()) return null
        val freq = LinkedHashMap<String, Int>()
        for (t in toks) freq[t] = (freq[t] ?: 0) + 1
        // sort by frequency desc then by original appearance order
        val ordered = toks.distinct()
        val sorted = ordered.sortedWith(compareByDescending<String> { freq[it] ?: 0 }.thenBy { ordered.indexOf(it) })
        val chosen = sorted.take(n).joinToString(separator = "") { it }
        val trimmed = chosen.trim()
        return if (trimmed.length in 2..60) trimmed else null
    }

    // --- Improved title extraction with light-weight POS-like heuristics ---
    private val stopwords: Set<String> = setOf(
        // polite / function words
        "请", "请各位", "请大家", "大家", "注意", "通知", "关于", "有关", "以及", "并", "和", "与", "的", "了",
        "我们", "你们", "各位", "同学", "老师", "同事", "部门", "单位", "公司", "学院", "学校", "班级",
        // prepositions
        "在", "于", "到", "至",
        "进行", "开展", "召开", "组织", "举办", "安排", "开展", "开展了", "进行中", "开始", "结束", "截止",
        "时间", "地点", "地址", "联系人", "报名", "参加", "参与", "需要", "如下", "如下所示",
        // mentions / symbols
        "@全体成员", "@所有人",
    )

    private val verbTriggers: Set<String> = setOf(
    "举办", "召开", "开会", "上课", "讲座", "汇报", "讨论", "分享", "培训", "考试", "答辩", "班会", "开展",
        "招募", "报名", "面试", "体测", "体检", "抽查", "检查", "出发", "聚餐", "聚会", "观影", "旅行", "出游",
        "报告", "演讲", "活动", "比赛", "竞赛", "宣讲", "实习", "入职", "报到"
    )

    private val nounSuffix: Set<String> = setOf(
        "会", "课", "赛", "讲", "演", "稿",
        "会议", "活动", "考试", "讲座", "汇报", "讨论", "宣讲", "培训", "答辩", "比赛", "竞赛", "面试",
        "晚会", "团课", "班会", "升旗仪式", "仪式", "聚餐", "聚会", "观影", "外出", "出游", "研讨", "训练",
        "作业", "报告", "总结", "演示", "例会", "晨会", "周会"
    )

    private val eventKeywords: Set<String> = setOf(
        "训练", "讨论", "评审", "聚餐", "读书会", "讲座", "会议", "面试", "答辩", "考试", "活动", "汇报", "启动会",
        "说明会", "研讨会", "研讨", "例会", "晚会", "开幕", "典礼", "开学", "面谈", "签约", "宣讲", "比赛", "考核", "体检", "接种", "疫苗",
        "团课", // 强事件词，避免被“本学期第/第一次”这类修饰词干扰
        // online/remote
        "线上", "在线", "团课"
    )

    private val locationKeywords: Set<String> = setOf(
        "体育馆", "图书馆", "礼堂", "操场", "教室", "会议室", "报告厅", "机房", "实验室", "学生活动中心", "学生中心",
        "行政楼", "总务处", "大厅", "餐厅", "食堂", "礼堂", "中心", "大楼", "教学楼", "办公室", "系办公室",
        // medical & campus hospitals
        "医院", "卫生院", "校医院", "门诊", "门诊部",
        // vaccination sites
        "接种点", "疫苗接种点"
    )

    // 内容噪声/状态关键词：链接、发送、平台、指引等，不应成为标题的一部分
    private val noiseKeywords: Set<String> = setOf(
        "链接", "网址", "二维码", "会议号", "密码", "ID", "号",
        "已发送", "发送", "已通知", "通知到", "已确认", "确认", "取消", "已取消",
        "加入", "进入", "点击", "扫码", "查看", "详情",
        // common platforms
        "Zoom", "zoom", "腾讯会议", "钉钉", "飞书", "Teams", "Meeting"
    )

    private fun looksLikeTimeToken(tok: String): Boolean {
        if (tok.isEmpty()) return false
        if (tok.any { it.isDigit() }) return true
        val tMarkers = listOf("年", "月", "日", "号", "周", "星期", "上午", "下午", "中午", "晚上", "凌晨", "今", "明", "后", "晚", "早")
        return tMarkers.any { tok.contains(it) } || tok.contains(":") || tok.contains("：") || tok.contains("点")
    }

    private fun isStop(tok: String) = tok.length <= 1 || stopwords.contains(tok)

    private fun isEventish(tok: String): Boolean {
        if (isStop(tok) || looksLikeTimeToken(tok)) return false
        if (nounSuffix.any { tok.endsWith(it) }) return true
        // Allow typical content nouns with length 2~8
        return tok.length in 2..12
    }

    private fun cleanForTitle(sentence: String): String {
        var s = sentence
        fun rm(re: Regex) { s = re.replace(s, " ") }
        // basic time/date cleanup (lightweight, not relying on DateTimeParser internals)
        rm(Regex("[0-9]{1,2}[:：][0-5][0-9]")) // 14:30
        rm(Regex("[0-9]{1,2}点(?:[0-5][0-9])?"))
        rm(Regex("[一二三四五六七八九十百零两0-9]{1,2}月[一二三四五六七八九十零两0-9]{1,2}[日号]?"))
        rm(Regex("(?:周|星期)[一二三四五六日天]"))
        rm(Regex("今天|明天|后天|大后天|今晚|明晚|今早|明早|上午|下午|中午|晚上|凌晨"))
        // relative
        rm(Regex("[一二三四五六七八九十百零两0-9个半半]+(年|个月|月|周|星期|天|日|小时|分钟|分|秒)(后|前)"))
        // cleanup extra whitespace/punct
            s = s.replace(Regex("[\t\r\n]+"), " ")
                .replace(Regex("\u3000+"), " ")
                .replace(Regex("\\s{2,}"), " ")
            .trim()
        return s
    }

    private fun chunkNounPhrases(tokens: List<String>): List<String> {
        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        for (t in tokens) {
            val tok = t.trim()
            if (tok.isEmpty()) continue
            if (isEventish(tok)) {
                val beforeLen = current.length
                if (beforeLen > 0 && beforeLen + tok.length > 30) {
                    // flush
                    val cand = current.toString().trim()
                    if (cand.length in 2..30) chunks.add(cand)
                    current = StringBuilder()
                }
                current.append(tok)
            } else {
                if (current.isNotEmpty()) {
                    val cand = current.toString().trim()
                    if (cand.length in 2..30) chunks.add(cand)
                    current = StringBuilder()
                }
            }
        }
        if (current.isNotEmpty()) {
            val cand = current.toString().trim()
            if (cand.length in 2..30) chunks.add(cand)
        }
        return chunks.distinct()
    }

    private fun preferByPatterns(sentence: String, chunks: List<String>): String? {
        // 1) 《书名号/引号》内的标题
        Regex("《([^》]{2,40})》").find(sentence)?.let { return it.groupValues[1].trim() }
        Regex("[“\"']([^”\"']{2,40})[”\"']").find(sentence)?.let { return it.groupValues[1].trim() }

        // 2) 主题/标题/事项：
            Regex("""(?:主题|标题|事项)[:：]\s*([^，,。；;\n\r]{2,40})""").find(sentence)?.let { return it.groupValues[1].trim() }

        // 3) 关于X的通知/安排 -> 取 X
            Regex("""关于\s*([^的，,。；;\n\r]{2,30})\s*的(?:通知|安排|事宜|事项)""").find(sentence)?.let { return it.groupValues[1].trim() }

        // 3.5) X通知：Y -> 仅当 Y 像“事件名”时才取 Y；否则跳过（交由 3/6 规则或后续规则处理）
        run {
            val m = Regex("""(?:[^，,。；;\n\r]{0,20})?(通知|通告|公告)[:：]\s*([^，,。；;\n\r]{2,40})""").find(sentence)
            if (m != null) {
                val tail = m.groupValues.getOrNull(2)?.trim()
                if (!tail.isNullOrBlank()) {
                    val isEventLike = nounSuffix.any { tail.endsWith(it) } || eventKeywords.any { tail.contains(it) }
                    // 过滤明显非事件性的通用短语（如“仍然开放/正常开放/照常开放/已调整”等）
                    val nonEventHints = listOf("仍然开放", "正常开放", "照常开放", "开放时间", "时间调整", "已调整", "安排如下")
                    val looksNonEvent = nonEventHints.any { tail.contains(it) }
                    if (isEventLike && !looksNonEvent) return tail
                }
            }
        }

        // 4) 动词+名词短语：触发动词后面的名词块
        for (v in verbTriggers) {
            val idx = sentence.indexOf(v)
            if (idx >= 0) {
                // look for the first chunk that starts after the verb
                val cand = chunks.firstOrNull { chunk ->
                    val pos = sentence.indexOf(chunk)
                    pos >= 0 && pos >= idx && pos - idx <= 8
                }
                if (!cand.isNullOrBlank()) return cand
            }
        }

        // 5) 若出现“(疫苗|接种|体检|考试|会议|讲座)”等明显事件词，优先返回相应块
        run {
            val eventWord = listOf("疫苗", "接种", "体检", "考试", "会议", "讲座", "汇报", "聚餐", "评审", "研讨会", "研讨", "线上", "在线")
                .firstOrNull { sentence.contains(it) }
            if (eventWord != null) {
                val hit = chunks.firstOrNull { it.contains(eventWord) }
                if (!hit.isNullOrBlank()) return hit
            }
        }

        // 6) X通知/X通告 -> 取 X；但若 X 像部门/机构（如“教务处/学院/系办”等），则不要用 X，改取后续主题或继续其他规则
        Regex("([^，,。；;\n\r]{2,30})(?:通知|通告|公告)").find(sentence)?.let { m ->
            val x = m.groupValues[1].trim()
            val deptLike = Regex("(教务处|学工处|人事处|财务处|学生处|研究生院|科研处|后勤处|总务处|党委|团委|学院|系办公室|系办|教研室|办公室|中心)$").containsMatchIn(x)
            if (!deptLike) {
                // pick the longest chunk contained in X
                val hit = chunks.filter { x.contains(it) }.maxByOrNull { it.length }
                if (!hit.isNullOrBlank()) return hit
                if (x.length in 2..30) return x
            }
            // 若是部门类，则不返回 X，交由后续规则（或 3.5 已处理“通知：Y”）。
        }

        return null
    }

    /**
     * Extract a short, meaningful event title using segmentation + rule heuristics.
     * Keep Chinese-first UX and avoid over-engineering; prefer concise nouns like “期中考试/班会/例会”.
     */
    fun extractTitle(sentence: String): String? {
        if (sentence.isBlank()) return null
        val cleaned = cleanForTitle(sentence)
        // segment on cleaned sentence
        val toks = segmenter.sentenceProcess(cleaned)
        val chunks = chunkNounPhrases(toks)

        // Rule preferences
        val preferred = preferByPatterns(sentence, chunks) ?: preferByPatterns(cleaned, chunks)
        if (!preferred.isNullOrBlank()) {
            val t = preferred.trim().trimEnd('。', '，', ',', '：', ':')
            if (t.length in 2..40) return t
        }

        // Otherwise pick the best chunk: longest that ends with nouny suffix, else longest
        val nouny = chunks.filter { c -> nounSuffix.any { c.endsWith(it) } }
        val best = (nouny.maxByOrNull { it.length } ?: chunks.maxByOrNull { it.length })
        val final = best?.trim()?.trimEnd('。', '，', ',', '：', ':')
        return if (!final.isNullOrBlank() && final.length in 2..40) final else null
    }

    /**
     * POS-like event description extractor (no real POS in jieba-analysis):
     * - Tokenize sentence
     * - Drop tokens that look like time/date or function/stop words
     * - Keep noun-ish/verb-noun tokens and stitch contiguous ones
     * - Prefer chunks appearing after the last time token
     */
    fun extractEventDescription(sentence: String): String? {
        if (sentence.isBlank()) return null
        // 强制规则：如出现“接种点/疫苗接种点”，直接返回“接种”作为简洁事件名
        if (sentence.contains("接种点") || sentence.contains("疫苗接种点")) {
            return "接种"
        }
        // 强事件直返：若句子包含“团课”，直接返回“团课”以避免被序数/前缀干扰
        if (sentence.contains("团课")) {
            return "团课"
        }
        // 1) 获取时间边界（最后一个时间片段的结束下标）
        var lastTimeEnd = -1
        run {
            val timeLike = listOf(
                Regex("[0-9]{1,2}[:：][0-5][0-9]"), // 14:30
                Regex("[0-9一二三四五六七八九十]{1,2}点(?:[0-5][0-9])?"),
                Regex("(?:周|星期)[一二三四五六日天]"),
                Regex("[一二三四五六七八九十零两0-9]{1,2}月[一二三四五六七八九十零两0-9]{1,2}[日号]?")
            )
            for (re in timeLike) {
                re.findAll(sentence).forEach { m -> lastTimeEnd = maxOf(lastTimeEnd, m.range.last + 1) }
            }
        }
        // 2) 去除时间短语后得到用于拼接的文本
        val cleaned = cleanForTitle(sentence)
        // 3) 分词并过滤时间/停用词，拼接连续事件相关 token
        val toks = segmenter.sentenceProcess(cleaned)
        val chunks = chunkNounPhrases(toks)
        // 标准化候选短语：清理前导修饰、残缺序数、地点/噪声尾巴
        fun normalized(chunk: String): String {
            var c = chunk.trim()
            c = c.replace(Regex("^(请各位|请大家|请)?(在|于|到|至)"), "")
            if (c.startsWith("请")) c = c.removePrefix("请")
            c = c.trimStart('在','于','到','至')
            // 去除学期/周/月等语境性前缀，保留核心名词
            c = c.replace(Regex("^(本学期|本次|本周|这周|本月|本年度|本年)"), "").trim()
            // 规整“第X次/第X期/第X届/第X轮/第X批”等序数短语：
            // - 若序数前缀后面没有明显事件名词，则移除该序数
            // - 若存在名词，如“第一次团课”，保留完整短语
            run {
                // 若以“第X(次|期|届|轮|批)”开头且后面紧随的不是事件后缀或关键词，则去掉该前缀
                val ordPrefix = Regex("^第[一二三四五六七八九十零两0-9]+(?:次|期|届|轮|批)")
                if (ordPrefix.containsMatchIn(c)) {
                    val after = c.replace(ordPrefix, "").trim()
                    val looksEvent = nounSuffix.any { after.endsWith(it) } || eventKeywords.any { after.contains(it) }
                    if (!looksEvent) {
                        c = after
                    }
                }
                // 若以“本学期第/本次第/第”开头但后续缺失量词（如“本学期第”）或末尾停在“第...”，裁剪掉尾部残缺序数
                c = c.replace(Regex("^(?:本学期|本次)?第$"), "").trim()
                c = c.replace(Regex("第[一二三四五六七八九十零两0-9]*$"), "").trim()
            }
            // 若包含明显地点关键词或噪声关键词（链接/发送/平台名等），则在其出现处不断截断，直到不再包含这些关键词
            var changed = true
            while (changed) {
                changed = false
                val allKw = locationKeywords + noiseKeywords
                val hit = allKw.firstOrNull { kw -> c.indexOf(kw) >= 0 }
                if (hit != null) {
                    val cut = c.indexOf(hit)
                    if (cut >= 0) { c = c.substring(0, cut).trim(); changed = true }
                }
            }
            return c.trim()
        }
        // 优先使用模式偏好（如『关于X的通知』、『主题:』等）；并对得到的片段做标准化清洗
        run {
            val preferred = preferByPatterns(sentence, chunks) ?: preferByPatterns(cleaned, chunks)
            val t0 = preferred?.trim()?.trimEnd('。', '，', ',', '：', ':')
            if (!t0.isNullOrBlank()) {
                // 若 prefer 命中“关于X的通知/安排”，优先返回 X 中的地点类主语（如 图书馆），避免被后续 location 截断成空
                val locHit = locationKeywords.firstOrNull { kw -> t0.contains(kw) }
                if (locHit != null) return locHit
                val t = normalized(t0)
                if (!t.isNullOrBlank() && t.length in 2..40) return t
            }
        }
        if (chunks.isEmpty()) return null
        // 4) 若存在在原句中出现在最后时间之后的块，优先这些
        val afterTimeChunks = if (lastTimeEnd >= 0) {
            chunks.filter { ch ->
                val idx = sentence.indexOf(ch)
                idx >= 0 && idx >= lastTimeEnd
            }
        } else emptyList()
        val candList = if (afterTimeChunks.isNotEmpty()) afterTimeChunks else chunks
        fun score(chunk: String): Int {
            val n = normalized(chunk)
            var s = n.length
            val hasEvent = eventKeywords.any { n.contains(it) }
            val hasLoc = locationKeywords.any { n.contains(it) }
            val hasNoise = noiseKeywords.any { n.contains(it) }
            if (nounSuffix.any { n.endsWith(it) }) s += 8
            if (hasEvent) s += 12
            if (verbTriggers.any { n.contains(it) }) s += 3
            if (hasLoc && !hasEvent) s -= 20
            if (hasNoise && !hasEvent) s -= 25
            if (n.contains("集合") && !hasEvent) s -= 10
            if (chunk.startsWith("在") || chunk.startsWith("于") || chunk.startsWith("到") || chunk.contains("集合")) s -= 5
            return s
        }

        // 4.5) 强优先：若候选中存在包含明显事件关键词的块（如 接种/疫苗/体检/考试/会议/讲座 等），直接在这些块中选最佳
        run {
            val eventChunks = candList.filter { ch -> eventKeywords.any { ek -> ch.contains(ek) } }
            if (eventChunks.isNotEmpty()) {
                val bestEvt = eventChunks.maxByOrNull { score(it) } ?: eventChunks.maxByOrNull { it.length }
                var res = bestEvt?.let {
                    if (it.contains("接种点")) "接种" else normalized(it)
                }
                if (!res.isNullOrBlank() && res.length in 2..40) return res
            }
        }
        val preferred = candList.maxByOrNull { score(it) } ?: candList.maxByOrNull { it.length }
        var result = preferred?.let { normalized(it) }
        // 修正：若结果是残缺序数（如“本学期第”“第X次”“第一次的”），尝试保留后续的事件名；否则直接回落到句中强事件词
        if (!result.isNullOrBlank()) {
            // 裁掉前导修饰词：本学期/本周/这学期/本次 等
            result = result!!.replace(Regex("^(本学期|这学期|本周|本次|此次|第一次|第[一二三四五六七八九十0-9]+次)"), "").trim()
            // 若仍然是“本学期第”这类残片，直接用句中包含的强事件词（如 团课/考试/讲座/会议/答辩/体检 等）
            if (result!!.isBlank() || result == "本学期第" || result.startsWith("第") && result.length <= 3) {
                val strong = listOf("团课", "考试", "讲座", "会议", "答辩", "汇报", "评审", "体检", "聚餐", "读书会")
                val hit = strong.firstOrNull { sentence.contains(it) }
                if (hit != null) result = hit
            }
        }
        return result?.takeIf { it.length in 2..40 }
    }
}
