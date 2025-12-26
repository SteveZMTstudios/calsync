package top.stevezmt.calsync

import android.content.Context


/* dummy implementation for FOSS build to prevent crashes */
internal class MLKitStrategy(private val context: Context) : ParsingStrategy {
    override fun name() = "ML Kit"
    
    override fun tryParse(sentence: String): DateTimeParser.ParseResult? = null

    fun tryParseWithBase(sentence: String, baseMillis: Long): DateTimeParser.ParseResult? = null

    fun extractTitleAndLocation(sentence: String): Pair<String?, String?> = Pair(null, null)
}
