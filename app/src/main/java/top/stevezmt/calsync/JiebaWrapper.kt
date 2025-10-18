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
        "进行", "开展", "召开", "组织", "举办", "安排", "开展", "开展了", "进行中", "开始", "结束", "截止",
        "时间", "地点", "地址", "联系人", "报名", "参加", "参与", "需要", "如下", "如下所示",
        // mentions / symbols
        "@全体成员", "@所有人",
    )

    private val verbTriggers: Set<String> = setOf(
        "举办", "召开", "开会", "上课", "讲座", "汇报", "讨论", "分享", "培训", "考试", "答辩", "团课", "班会",
        "招募", "报名", "面试", "体测", "体检", "抽查", "检查", "出发", "聚餐", "聚会", "观影", "旅行", "出游",
        "报告", "演讲", "活动", "比赛", "竞赛", "宣讲", "实习", "入职", "报到"
    )

    private val nounSuffix: Set<String> = setOf(
        "会", "课", "赛", "讲", "演", "稿",
        "会议", "活动", "考试", "讲座", "汇报", "讨论", "宣讲", "培训", "答辩", "比赛", "竞赛", "面试",
        "晚会", "团课", "班会", "升旗仪式", "仪式", "聚餐", "聚会", "观影", "外出", "出游", "研讨",
        "作业", "报告", "总结", "演示", "例会", "晨会", "周会"
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

        // 5) X通知/X通告 -> 取 X（X 是名词块的一部分）
        Regex("([^，,。；;\n\r]{2,30})(?:通知|通告|公告)").find(sentence)?.let { m ->
            val x = m.groupValues[1].trim()
            // pick the longest chunk contained in X
            val hit = chunks.filter { x.contains(it) }.maxByOrNull { it.length }
            if (!hit.isNullOrBlank()) return hit
            if (x.length in 2..30) return x
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
}
