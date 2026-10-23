package app.homeydash.agent

/**
 * Everyday home words in Swedish and English, both ways, so "the note about the flowers" finds
 * "Köp blommor till mormor" and "start the dinner mood" finds the mood called Middag. The word
 * pairs are data in the brain ([Cues]: built-in ones plus any the owner taught); names are
 * matched as they are.
 */
object Translate {
    @Volatile private var both: Map<String, Set<String>>? = null

    private fun pairs(): Map<String, Set<String>> = both ?: Cues.translations().filter { it.size == 2 }
        .map { NameMatch.normalize(it[0]) to NameMatch.normalize(it[1]) }
        .let { p -> (p + p.map { it.second to it.first }).groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() } }
        .also { both = it }

    /** The owner's word pairs changed: read them again. */
    internal fun reset() { both = null }

    /** [folded] with English phrases also written the Swedish way ("sofa table" is soffbord), so both are there to match. */
    fun phrases(folded: String): String =
        Cues.phrases().filter { it.size == 2 && folded.contains(it[0]) }.fold(folded) { acc, p -> "$acc ${p[1]}" }

    /** [word] (normalized) and what it is in the other language. */
    fun variants(word: String): Set<String> {
        val both = pairs()
        return setOf(word) + both[word].orEmpty() +
            // Swedish endings: "blommorna", "middagen", "kycklingen" are still the word.
            both.entries.filter { (k, _) -> k.length >= 4 && word.length > k.length && word.startsWith(k) && word.length - k.length <= 3 }.flatMap { it.value }
    }
}
