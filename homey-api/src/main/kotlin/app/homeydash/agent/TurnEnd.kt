package app.homeydash.agent

/**
 * Whether someone has finished what they were saying, like a person would judge it: "öhh",
 * "and…" or "turn on the" at the end means they're still thinking, so the assistant waits
 * instead of answering half a question. Used on what Whisper heard up to a pause.
 */
object TurnEnd {
    /**
     * True when [text] reads as a finished question or request. [held] is a long, even sound
     * just before the pause (a drawn-out "ööhh" or "mmm"), which Whisper often leaves out.
     */
    fun complete(text: String, wakeName: String = "", held: Boolean = false): Boolean {
        if (held) return false
        val raw = text.trim()
        if (raw.isEmpty()) return false
        // Whisper writes a trailing hesitation as "…", "...", "-" or a comma.
        if (raw.endsWith("…") || raw.endsWith("..") || raw.endsWith(",") || raw.endsWith("-") || raw.endsWith("–")) return false
        val words = NameMatch.normalize(raw).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        // Only the name ("Hey TARS"): the question comes next.
        if (wakeName.isNotBlank() && WakeName.heard(raw, wakeName)) return false
        val last = words.last()
        if (last in FILLERS) return false
        if (last in OPEN_ENDINGS) return false
        return true
    }

    /** [text] without its hesitation sounds, for the AI: "Öh, tänd, ehm, lampan" is "tänd lampan". */
    fun clean(text: String): String {
        val kept = ArrayList<String>()
        for (w in text.trim().split(Regex("\\s+"))) {
            if (NameMatch.normalize(w) !in FILLERS) {
                kept += w
                continue
            }
            // "jazz, uh." keeps its full stop: "jazz."
            val end = w.takeLastWhile { it in ".!?" }
            if (end.isNotEmpty() && kept.isNotEmpty()) kept[kept.size - 1] = kept.last().trimEnd(',') + end
        }
        if (kept.isEmpty()) return ""
        val first = kept[0]
        kept[0] = first.replaceFirstChar { it.uppercase() }
        return kept.joinToString(" ")
    }

    /** Hesitation sounds in Swedish and English, as [NameMatch.normalize] writes them. */
    val FILLERS = setOf(
        "oh", "ohm", "ohh", "ohhm", "ah", "ahm", "ahh", "eh", "ehm", "ehh", "em", "hm", "hmm", "hmmm",
        "mm", "mmm", "um", "umm", "uh", "uhm", "uhh", "er", "erm", "oeh", "aeh",
    )

    /**
     * Words a finished sentence hardly ends with: "and", "the", "to", "att", "och", "till".
     * Particles like "on", "off", "på" and "av" are left out: "turn the light on" is done.
     */
    private val OPEN_ENDINGS = setOf(
        // English
        "and", "or", "but", "because", "if", "the", "a", "an", "to", "of", "in", "at", "for", "with",
        "from", "into", "about", "my", "your", "our", "their", "his", "her", "its",
        // Swedish (normalized: å/ä/ö become a/a/o)
        "och", "eller", "men", "att", "som", "till", "med", "for", "om", "en", "ett", "min", "mitt",
        "mina", "din", "ditt", "dina", "vilken", "vilket", "alltsa", "liksom", "eftersom", "fran",
        "nagon", "nagot", "nagra", "i",
    )
}
