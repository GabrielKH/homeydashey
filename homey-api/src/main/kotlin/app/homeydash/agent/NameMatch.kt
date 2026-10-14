package app.homeydash.agent

import java.text.Normalizer

/**
 * Loose matching of what someone said against device, room and mood names, in Swedish
 * or English: "kökslampan", "lampan i köket" and "kitchen lamp" can all find "Kök lampa".
 * Speech recognition and small models both get names slightly wrong, so exact matching
 * isn't enough.
 */
object NameMatch {
    private val STOP = setOf(
        "the", "a", "an", "in", "on", "at", "of", "my", "please", "turn", "switch", "set",
        "i", "på", "pa", "och", "and", "min", "mitt", "mina", "den", "det", "de", "till", "från", "fran", "rummet",
    )

    /** Lowercase, without accents (å→a, ö→o), punctuation or extra spaces. */
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace('ø', 'o').replace('æ', 'a')
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun tokens(text: String): List<String> = normalize(text).split(' ').filter { it.isNotEmpty() && it !in STOP }

    /**
     * Whether two words are the same word: equal, one a form of the other ("kok" and
     * "koket", "lampa" and "lampan"), or one letter off in a longer word.
     */
    fun sameWord(a: String, b: String): Boolean {
        if (a == b) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        if (short.length >= 3 && long.startsWith(short) && long.length - short.length <= 4) return true
        if (short.length >= 5 && long.length - short.length <= 1 && edits(short, long) <= 1) return true
        return false
    }

    /**
     * Words people use for the same kind of thing, so "växtlampan" finds "Växt spotlight"
     * and "uttaget" finds "Plugg". Accents are already gone (växt → vaxt).
     */
    private val FAMILIES = listOf(
        listOf("lampa", "lamp", "light", "ljus", "spot", "spotlight", "spotlights", "belysning", "slinga", "ledslinga",
            "led", "strip", "lightstrip", "plafond", "armatur", "glodlampa", "pendel", "bulb"),
        listOf("uttag", "plugg", "kontakt", "plug", "socket", "outlet", "smartplug"),
        listOf("hogtalare", "speaker", "sonos"),
        listOf("tv", "teve", "television", "tvn"),
        listOf("flakt", "fan"),
        listOf("element", "radiator", "heater", "varmare", "golvvarme"),
        listOf("gardin", "persienn", "rullgardin", "blinds", "screen", "markis"),
        listOf("vaxt", "vaxter", "plant", "plants", "blomma", "blommor"),
    )

    /** Whether two words name the same kind of thing ([FAMILIES]), though they aren't the same word. */
    fun related(a: String, b: String): Boolean =
        FAMILIES.any { family -> family.any { sameWord(it, a) } && family.any { sameWord(it, b) } }

    /** Two compounds with the same start and related ends: "vaxtlampan" and "vaxtljus". */
    private fun sameStemOtherFamily(a: String, b: String): Boolean {
        val common = a.zip(b).takeWhile { (x, y) -> x == y }.size
        // The ends may start alike too ("vaxt|lampan", "vaxt|ljus"), so try each split.
        return (3..common).any { stem -> a.length - stem >= 3 && b.length - stem >= 3 && related(a.substring(stem), b.substring(stem)) }
    }

    private fun alike(a: String, b: String) = sameWord(a, b) || related(a, b)

    /**
     * How well [query] names something called [name] in [room]: 0 for not at all, about 1
     * for the whole name, a little more when the room is named too.
     */
    fun score(query: String, name: String, room: String? = null): Double {
        val q = tokens(query)
        if (q.isEmpty()) return 0.0
        val nameWords = tokens(name)
        val roomWords = room?.let(::tokens).orEmpty()
        if (nameWords.isEmpty()) return 0.0
        val qJoined = q.joinToString("")
        val nameJoined = nameWords.joinToString("")
        // What's left of a compound word once the name's own words are taken out:
        // "vaxtlampan" without "vaxt" leaves "lampan".
        val rest = q.flatMap { w ->
            val parts = nameWords.filter { it.length >= 3 && it != w && w.contains(it) }
            if (parts.isEmpty()) emptyList() else parts.fold(w) { acc, p -> acc.replace(p, " ") }.split(' ').filter { it.length >= 3 }
        }
        // A word of the same family counts for less than the word itself, so "lamp" still prefers
        // "Kitchen lamp" over "Spots".
        fun hit(n: String): Double = when {
            q.any { sameWord(it, n) } || (n.length >= 3 && qJoined.contains(n)) || rest.any { sameWord(it, n) } -> 1.0
            q.any { sameStemOtherFamily(it, n) } -> STEM_RELATED
            (q + rest).any { related(it, n) } -> RELATED
            else -> 0.0
        }
        var nameHits = nameWords.sumOf(::hit)
        // Compound words: "kökslampan" holds "kök" and "lampa".
        if (nameHits < nameWords.size && nameJoined.length >= 4 && (qJoined.contains(nameJoined) || sameWord(qJoined, nameJoined))) {
            nameHits = nameWords.size.toDouble()
        }
        if (nameHits == 0.0) return 0.0
        val roomHit = roomWords.isNotEmpty() && roomWords.any { r -> q.any { sameWord(it, r) } || (r.length >= 3 && qJoined.contains(r)) }
        val used = q.count { w -> (nameWords + roomWords).any { alike(w, it) || (it.length >= 3 && w.contains(it)) } }
        val extra = q.size - used
        return nameHits / nameWords.size + (if (roomHit) 0.3 else 0.0) - 0.1 * extra.coerceAtMost(3)
    }

    /** The best of [items] for [query] when it clearly wins, else null. */
    fun <T> best(query: String, items: List<T>, scoreOf: (T) -> Double): T? {
        val ranked = rank(query, items, scoreOf)
        val top = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)
        if (top.second < MIN_SCORE) return null
        if (second != null && second.second > top.second - CLEAR_LEAD) return null
        return top.first
    }

    /** [items] that match [query] at all, best first. */
    fun <T> rank(query: String, items: List<T>, scoreOf: (T) -> Double): List<Pair<T, Double>> =
        items.map { it to scoreOf(it) }.filter { it.second > 0.0 }.sortedByDescending { it.second }

    private const val MIN_SCORE = 0.5
    private const val RELATED = 0.6
    private const val STEM_RELATED = 0.8
    private const val CLEAR_LEAD = 0.15

    private fun edits(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            cur.copyInto(prev)
        }
        return prev[b.length]
    }
}
