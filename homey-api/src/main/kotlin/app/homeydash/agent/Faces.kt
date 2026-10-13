package app.homeydash.agent

import kotlin.math.sqrt

/** Someone enrolled for face recognition: a few face "fingerprints" (embeddings), never photos. */
data class KnownPerson(val id: String, val name: String, val role: Role, val samples: List<FloatArray>)

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

    /** The best match for [embedding], or null when nobody is close enough. */
    fun match(embedding: FloatArray, people: List<KnownPerson>, threshold: Float = THRESHOLD): KnownPerson? =
        people.map { p -> p to (p.samples.maxOfOrNull { similarity(embedding, it) } ?: 0f) }
            .filter { it.second >= threshold }
            .maxByOrNull { it.second }
            ?.first
}
