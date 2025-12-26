package top.stevezmt.calsync

internal interface ParsingStrategy {
    fun name(): String
    fun tryParse(sentence: String): DateTimeParser.ParseResult?
}
