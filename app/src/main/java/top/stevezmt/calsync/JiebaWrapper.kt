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
}
