package app.homeydash.agent

import java.util.Base64
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The wake name the way one person says it, learned from teaching their voice: the sound of each
 * "Hey Nova" kept as a short run of spectra (no recording), and later found again in what the
 * microphone hears by dynamic time warping, the way the first speech recognisers matched words.
 * The spotter's model only knows English; this knows exactly how Gabriel says "Hej Nova",
 * Swedish vowels and all. Each person's own repeats set how close a match must be.
 */
object SaidName {
    /** Spectral bands kept (pairs of the 80 Kaldi bands averaged). */
    const val BANDS = 40
    /** Frames are 20 ms apart (two 10 ms Kaldi frames averaged). */
    const val FRAME_MS = 20
    /** Quieter than this at the edges (natural-log energy below the loudest frame, about 26 dB) counts as silence and is cut. */
    private const val EDGE = 6f
    private const val MIN_FRAMES = 10
    private const val MAX_FRAMES = 150
    /** Learned bars are kept within these, so a person who said it the same way every time isn't held to an impossible bar. */
    const val MIN_BAR = 0.12f
    const val MAX_BAR = 0.28f
    /** The bar is the furthest of their own repeats from the others, with this much room. */
    private const val ROOM = 1.25f

    /** A spoken name as frames of [BANDS] unit vectors. */
    class Shape(val frames: Array<FloatArray>) {
        val size: Int get() = frames.size
    }

    /** Where a name was found in what was heard: how far it is from the closest taught one, and where it ended (in frames from the start). */
    data class Match(val distance: Float, val end: Int)

    /**
     * The shape of the name in [samples] (16 kHz), with the silence either side cut; null when
     * too short. Each band's average over the whole clip is already taken away ([Fbank]), as it
     * is for what is heard later, so the room and the microphone don't count.
     */
    fun of(samples: ShortArray): Shape? {
        val f = pooled(Fbank.features(samples))
        if (f.isEmpty()) return null
        val energy = FloatArray(f.size) { t -> f[t].average().toFloat() }
        val top = energy.maxOrNull() ?: return null
        val first = energy.indexOfFirst { it > top - EDGE }
        val last = energy.indexOfLast { it > top - EDGE }
        if (first < 0 || last - first + 1 < MIN_FRAMES) return null
        val cut = f.copyOfRange(first, min(last + 1, first + MAX_FRAMES))
        return Shape(unit(cut))
    }

    /** What the microphone heard (any length), ready to be searched with [find]. */
    fun heard(samples: ShortArray): Shape = Shape(unit(pooled(Fbank.features(samples))))

    /**
     * The closest any of [names] comes to a stretch of [heard] (subsequence DTW: the name may
     * start and end anywhere in it), as the mean cosine distance along the best path. Null
     * without anything to compare.
     */
    fun find(names: List<Shape>, heard: Shape): Match? {
        var best: Match? = null
        for (n in names) {
            val m = distance(n, heard) ?: continue
            if (best == null || m.distance < best.distance) best = m
        }
        return best
    }

    /**
     * How close a match must be for this person: the furthest any of their own taught repeats
     * is from the closest of the others, with room, kept within [MIN_BAR]..[MAX_BAR]. Null with
     * fewer than two repeats.
     */
    fun bar(names: List<Shape>): Float? {
        if (names.size < 2) return null
        var worst = 0f
        for (i in names.indices) {
            val others = names.filterIndexed { j, _ -> j != i }
            val d = find(others, names[i])?.distance ?: continue
            worst = max(worst, d)
        }
        return (worst * ROOM).coerceIn(MIN_BAR, MAX_BAR)
    }

    /** One template against what was heard; null when either is empty. */
    fun distance(name: Shape, heard: Shape): Match? {
        val m = name.size
        val n = heard.size
        if (m == 0 || n == 0) return null
        // cost[j]: the cheapest path through the name's frames so far ending at heard frame j.
        var cost = FloatArray(n)
        var next = FloatArray(n)
        val a0 = name.frames[0]
        for (j in 0 until n) cost[j] = 1f - dot(a0, heard.frames[j])
        for (i in 1 until m) {
            val a = name.frames[i]
            for (j in 0 until n) {
                // From the frame before in both, in the name only (the name said faster than
                // taught), or in what was heard only (said slower, held longer).
                var c = cost[j]
                if (j > 0) c = min(c, min(cost[j - 1], next[j - 1]))
                next[j] = c + 1f - dot(a, heard.frames[j])
            }
            val t = cost; cost = next; next = t
        }
        // Averaged over the name's own length, so a long name isn't held to a stricter bar.
        var best = Float.MAX_VALUE
        var end = 0
        for (j in 0 until n) {
            val v = cost[j] / m
            if (v < best) { best = v; end = j }
        }
        return Match(best, end)
    }

    /** A shape as text for saving: bytes of each value times 127, in Base64. */
    fun encode(shape: Shape): String {
        val bytes = ByteArray(shape.size * BANDS)
        for (t in 0 until shape.size) for (b in 0 until BANDS) bytes[t * BANDS + b] = (shape.frames[t][b] * 127f).toInt().coerceIn(-127, 127).toByte()
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun decode(text: String): Shape? {
        val bytes = runCatching { Base64.getDecoder().decode(text) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size % BANDS != 0) return null
        val frames = Array(bytes.size / BANDS) { t -> FloatArray(BANDS) { b -> bytes[t * BANDS + b] / 127f } }
        return Shape(unit(frames))
    }

    /** Pairs of bands and pairs of frames averaged: 40 bands every 20 ms. */
    private fun pooled(f: Array<FloatArray>): Array<FloatArray> {
        val frames = f.size / 2
        return Array(frames) { t ->
            FloatArray(BANDS) { b -> (f[2 * t][2 * b] + f[2 * t][2 * b + 1] + f[2 * t + 1][2 * b] + f[2 * t + 1][2 * b + 1]) / 4f }
        }
    }

    private fun unit(f: Array<FloatArray>): Array<FloatArray> = Array(f.size) { t ->
        val row = f[t]
        var s = 0f
        for (v in row) s += v * v
        val n = sqrt(s)
        if (n == 0f) row.copyOf() else FloatArray(row.size) { row[it] / n }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
