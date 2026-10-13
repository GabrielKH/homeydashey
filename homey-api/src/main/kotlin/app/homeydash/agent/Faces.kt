package app.homeydash.agent

import kotlin.math.sqrt

/**
 * Someone the tablet knows: a few face and voice "fingerprints" (embeddings), never photos
 * or recordings. Either list may be empty.
 */
data class KnownPerson(
    val id: String,
    val name: String,
    val role: Role,
    val samples: List<FloatArray>,
    val voice: List<FloatArray> = emptyList(),
)

/** Matches a face embedding against the people enrolled on this tablet. */
object FaceMatcher {
    /** Cosine similarity above which two MobileFaceNet embeddings count as the same person. */
    const val THRESHOLD = 0.62f

    fun normalize(v: FloatArray): FloatArray {
        val n = sqrt(v.fold(0f) { acc, x -> acc + x * x })
        return if (n == 0f) v else FloatArray(v.size) { v[it] / n }
    }

    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
        }
        val d = sqrt(na) * sqrt(nb)
        return if (d == 0f) 0f else dot / d
    }

    /** The best face match for [embedding], or null when nobody is close enough. */
    fun match(embedding: FloatArray, people: List<KnownPerson>, threshold: Float = THRESHOLD): KnownPerson? =
        best(embedding, people, threshold) { it.samples }

    /** Cosine similarity above which two Vosk speaker vectors count as the same voice. */
    const val VOICE_THRESHOLD = 0.55f

    /**
     * How sure a wake must be of the voice before the tablet learns from it. This is higher
     * than [VOICE_THRESHOLD], so it doesn't learn someone else's voice.
     */
    const val VOICE_LEARN = 0.65f

    /** Most voice fingerprints kept per person; the oldest go first. */
    const val MAX_VOICE = 40

    /** How closely [vector] matches [person]'s voice, 0 to 1. */
    fun voiceScore(vector: FloatArray, person: KnownPerson): Float = person.voice.maxOfOrNull { similarity(vector, it) } ?: 0f

    /** [voice] with [more] fingerprints added, keeping the newest [MAX_VOICE]. */
    fun withVoice(voice: List<FloatArray>, more: List<FloatArray>): List<FloatArray> = (voice + more.map(::normalize)).takeLast(MAX_VOICE)

    /** The best voice match for a Vosk speaker vector, or null. */
    fun matchVoice(vector: FloatArray, people: List<KnownPerson>, threshold: Float = VOICE_THRESHOLD): KnownPerson? =
        best(vector, people, threshold) { it.voice }

    private fun best(v: FloatArray, people: List<KnownPerson>, threshold: Float, samples: (KnownPerson) -> List<FloatArray>): KnownPerson? =
        people.map { p -> p to (samples(p).maxOfOrNull { similarity(v, it) } ?: 0f) }
            .filter { it.second >= threshold }
            .maxByOrNull { it.second }
            ?.first
}

/**
 * The assistant's name, said to wake it ("Hey Nova"). Spotted on the tablet by a small
 * offline speech model limited to these phrases, so it hears nothing else.
 */
object WakeName {
    /** Phrases that wake the assistant for [name]. */
    fun phrases(name: String): List<String> {
        val n = clean(name)
        if (n.isEmpty()) return emptyList()
        return listOf("hey $n", "hi $n", "okay $n", n)
    }

    /**
     * The Vosk grammar: the wake phrases, everyday words for other speech to land on
     * instead of the name, and "[unk]". Without the decoys, any sound close enough is
     * forced onto the name; [vocabulary] keeps only words the model knows.
     */
    fun grammar(name: String, vocabulary: Set<String>? = null): String {
        val own = clean(name).split(' ').toSet()
        val decoys = DECOYS.filter { it !in own && (vocabulary == null || it in vocabulary) }
        return (phrases(name) + decoys + "[unk]").joinToString(",", "[", "]") { "\"" + it + "\"" }
    }

    /** Short everyday words; also ones that sound like common names, so they don't wake it. */
    private val DECOYS = listOf(
        "yes", "no", "okay", "hello", "hey", "hi", "so", "now", "know", "go", "oh", "the", "and", "you", "what", "call",
        "phone", "nine", "over", "home", "hold", "love", "good", "right", "well", "yeah", "noah", "nora", "nova scotia",
        "number", "never", "nothing", "anna", "hannah", "emma", "max", "mom", "dad", "stop", "play", "today", "tomorrow",
    )

    /** Whether a recognised [text] is the wake name. */
    fun heard(text: String, name: String): Boolean {
        val t = clean(text)
        return t.isNotEmpty() && phrases(name).any { it == t }
    }

    /** The words of [name] the speech model doesn't know; it can only wake on known words. */
    fun unknownWords(name: String, vocabulary: Set<String>): List<String> =
        clean(name).split(' ').filter { it.isNotEmpty() && it !in vocabulary }

    fun clean(text: String): String =
        text.lowercase().replace(Regex("[^a-z' ]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
}
