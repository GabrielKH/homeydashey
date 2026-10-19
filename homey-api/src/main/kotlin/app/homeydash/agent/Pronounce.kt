package app.homeydash.agent

/**
 * Swedish words respelled so the voices say them right. Piper's voices (Alma, TARS) read Swedish
 * by spelling rules, which make a vowel short before two consonants: "taklampan" comes out as
 * "tack-lampan" because nothing tells it the word is tak + lampan. A hyphen at the seam gives
 * the first part back its own long vowel ("tak-lampan" is said "taak-lampan"). Only what is
 * heard changes; the text on screen keeps the real spelling.
 */
object Pronounce {
    /** First parts with a long vowel that the rules shorten in a compound. */
    private const val FIRST = "tak|sov|mat|glas|bok"

    /** Home words they are joined to, so "taktik" or "matte" stay as they are. */
    private const val SECOND = "lamp|fläkt|list|ljus|belysning|rum|bord|sal|dörr|hyll|fönster|spot|skåp|bänk"

    private val SEAM = Regex("(?i)(?<![\\p{L}])($FIRST)(?=(?:$SECOND)\\p{L}*)")

    fun sayable(text: String, swedish: Boolean): String = if (swedish) SEAM.replace(text) { "${it.value}-" } else text
}
