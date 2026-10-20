package app.homeydash.agent

/**
 * The wake names as the keyword spotter wants them: its model (a small zipformer transducer
 * trained on English, shipped in the app) knows about 500 sub-word tokens, and a keyword is a
 * line of those tokens with a boost and a threshold. "Nova" and "TARS" aren't English words
 * either, so the tokens only describe their sounds, and each name also gets the ways people
 * here say it (NOWA, NOVER, TARZ) and the greetings in front of it (hey, hi, okay, hej).
 */
object WakeKeywords {
    val GREETINGS = listOf("HEY", "HI", "OKAY", "HEJ")

    /** What a spotted keyword stands for: which of the names (by index) and whether a greeting came first. */
    data class Tag(val name: Int, val greeting: Boolean)

    /**
     * [phrase] as the model's tokens, by greedy longest match over [tokens] (the entries of its
     * tokens.txt): "HEY NOVA" becomes "▁HE Y ▁NO V A". Null when a letter has no token.
     */
    fun encode(phrase: String, tokens: Set<String>): String? {
        val out = ArrayList<String>()
        for (word in phrase.uppercase().split(' ').filter { it.isNotEmpty() }) {
            val text = "▁" + word.filter { it.isLetter() }
            var at = 0
            while (at < text.length) {
                var best = ""
                for (end in text.length downTo at + 1) {
                    val piece = text.substring(at, end)
                    if (piece in tokens) { best = piece; break }
                }
                if (best.isEmpty()) {
                    // The word mark alone is a token too; a letter nothing covers ends it.
                    if (at == 0 && text.length > 1 && "▁" in tokens) { out += "▁"; at = 1; continue }
                    return null
                }
                out += best
                at += best.length
            }
        }
        return out.joinToString(" ").takeIf { it.isNotEmpty() }
    }

    /**
     * Every way to write [phrase] in the fewest of the model's tokens: "TARS" is "▁TA R S" by
     * greedy match, but the model itself hears "▁T AR S", and a keyword only fires on the
     * tokens the model would emit. Empty when a letter has no token.
     */
    fun encodings(phrase: String, tokens: Set<String>): List<String> {
        var out = listOf("")
        for (word in phrase.uppercase().split(' ').filter { it.isNotEmpty() }) {
            val ways = splits("▁" + word.filter { it.isLetter() }, tokens)
            if (ways.isEmpty()) return emptyList()
            val fewest = ways.minOf { it.size }
            val best = ways.filter { it.size == fewest }.take(MAX_SPLITS).map { it.joinToString(" ") }
            out = out.flatMap { a -> best.map { b -> if (a.isEmpty()) b else "$a $b" } }
        }
        return out.filter { it.isNotEmpty() }
    }

    /** All the ways to cut [text] into entries of [tokens]. */
    private fun splits(text: String, tokens: Set<String>): List<List<String>> {
        if (text.isEmpty()) return listOf(emptyList())
        val out = ArrayList<List<String>>()
        for (end in 1..text.length) {
            val piece = text.substring(0, end)
            if (piece in tokens) splits(text.substring(end), tokens).forEach { out += listOf(piece) + it }
        }
        return out
    }

    /** [name] and the ways it tends to be said: a Swedish "Nova" lands as NOVA, NOVAH, NOVER or NOWA. */
    fun variants(name: String): List<String> {
        val n = name.uppercase().filter { it.isLetter() || it == ' ' }.trim()
        if (n.isEmpty()) return emptyList()
        val out = linkedSetOf(n)
        if (n.endsWith("A")) { out += n + "H"; out += n.dropLast(1) + "ER" }
        if ('V' in n) out += n.replace('V', 'W')
        if (n.endsWith("S")) { out += n.dropLast(1) + "Z"; out += n + "E" }
        // What the model tends to hear instead, from spoken test clips: NOVAL, NOVAK, TALS, TAS.
        if (n.last() in "AEIOU") { out += n + "L"; out += n + "K" }
        if ('R' in n.drop(1)) { out += n.replace('R', 'L'); out += n.replace("R", "") }
        return out.toList()
    }

    /**
     * The keywords for [names], one line each, as the spotter's stream takes them: tokens, a
     * boost (higher hears it more readily), a threshold, and a tag ("@g0": a greeting and name
     * 0) to tell them apart when one is spotted.
     */
    fun keywords(names: List<String>, tokens: Set<String>, boost: Float = BOOST, threshold: Float = THRESHOLD): List<String> {
        val lines = ArrayList<String>()
        names.forEachIndexed { i, name ->
            for (v in variants(name)) {
                encodings(v, tokens).forEach { lines += "$it :%.1f #%.2f @n$i".format(boost, threshold) }
                for (g in GREETINGS) encodings("$g $v", tokens).forEach { lines += "$it :%.1f #%.2f @g$i".format(boost, threshold) }
            }
        }
        return lines.distinct()
    }

    /** The lines of [keywords] as one string for the spotter's stream. */
    fun stream(lines: List<String>): String = lines.joinToString("/")

    /** The tag a spotted [keyword] carries ("@g0" or the bare tag "g0"); null when it isn't one of ours. */
    fun tag(keyword: String): Tag? {
        val t = keyword.trim().substringAfterLast('@').trim()
        if (t.length < 2 || (t[0] != 'g' && t[0] != 'n')) return null
        val i = t.substring(1).toIntOrNull() ?: return null
        return Tag(i, t[0] == 'g')
    }

    /** The token entries of a tokens.txt ("▁NO 123" per line). */
    fun tokensOf(text: String): Set<String> = text.lineSequence().map { it.substringBefore(' ').trim() }.filter { it.isNotEmpty() }.toSet()

    private const val MAX_SPLITS = 4
    // Measured on 198 spoken clips (three voices, three speeds): with every token split and the
    // extra variants, 2.0/0.15 heard 52 of 90 calls against 30 for the first setting, and let 19
    // of 108 other sentences through to the wake judge, which drops those.
    const val BOOST = 2.0f
    const val THRESHOLD = 0.15f
}
