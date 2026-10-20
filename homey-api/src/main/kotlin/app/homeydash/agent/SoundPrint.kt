package app.homeydash.agent

import kotlin.math.abs
import kotlin.math.min

/**
 * An acoustic fingerprint of a short clip, the way Haitsma and Kalker (and Alexa, for adverts)
 * make one: for each frame, one bit per band pair saying whether the energy difference between
 * neighbouring bands grew since the frame before. The sign of that double difference survives
 * loudness, distance and a different loudspeaker, so the same advert heard again on another
 * evening gives nearly the same bits, while a person saying the name never does. Two prints of
 * the same recording differ in well under [SAME] of their bits; unrelated sounds in about half.
 */
class SoundPrint(val frames: IntArray) {
    val size: Int get() = frames.size

    /**
     * The share of bits that differ from [other], at the best alignment within [maxShift]
     * frames; 1.0 when either print is too short to compare.
     */
    fun bitErrorRate(other: SoundPrint, maxShift: Int = MAX_SHIFT): Double {
        if (frames.size < MIN_FRAMES || other.frames.size < MIN_FRAMES) return 1.0
        var best = 1.0
        for (shift in -maxShift..maxShift) {
            val a0 = maxOf(0, shift)
            val b0 = maxOf(0, -shift)
            val n = min(frames.size - a0, other.frames.size - b0)
            if (n < MIN_FRAMES) continue
            var wrong = 0
            for (i in 0 until n) wrong += Integer.bitCount(frames[a0 + i] xor other.frames[b0 + i])
            best = min(best, wrong.toDouble() / (n * BITS))
        }
        return best
    }

    /** Whether [other] is the same sound. */
    fun same(other: SoundPrint): Boolean = bitErrorRate(other) < SAME

    /** The bits as text, for a memory fact. */
    fun encode(): String = frames.joinToString(",") { Integer.toHexString(it) }

    companion object {
        /** Band pairs per frame, so a frame fits an Int. */
        const val BITS = 32
        /** Every third 10 ms feature frame: 30 ms steps, enough for a jingle, a third of the work. */
        private const val STEP = 3
        const val MIN_FRAMES = 10
        const val MAX_SHIFT = 5
        /** Under this share of differing bits it is the same recording (Haitsma and Kalker use 0.35). */
        const val SAME = 0.35
        /** Under this it is probably the same, and counts a little. */
        const val CLOSE = 0.45

        /** The print of 16 kHz [samples]; empty when shorter than a few frames. */
        fun of(samples: ShortArray): SoundPrint {
            val f = Fbank.features(samples)
            val rows = (f.size - 1) / STEP
            if (rows < 1) return SoundPrint(IntArray(0))
            val out = IntArray(rows)
            for (r in 0 until rows) {
                val t = (r + 1) * STEP
                val now = f[t]
                val before = f[t - STEP]
                var bits = 0
                for (m in 0 until BITS) {
                    val b = m * 2
                    val d = (now[b] - now[b + 2]) - (before[b] - before[b + 2])
                    if (d > 0) bits = bits or (1 shl m)
                }
                out[r] = bits
            }
            return SoundPrint(out)
        }

        /** A print back from [encode]'s text; null when it isn't one. */
        fun decode(text: String): SoundPrint? {
            if (text.isBlank()) return null
            val frames = text.split(',').map { runCatching { java.lang.Long.parseLong(it.trim(), 16).toInt() }.getOrNull() ?: return null }
            return SoundPrint(frames.toIntArray())
        }

        /** The best bit error rate of [print] against [known], or null with nothing to compare with. */
        fun closest(print: SoundPrint, known: List<SoundPrint>): Double? =
            known.minOfOrNull { print.bitErrorRate(it) }?.let { abs(it) }
    }
}
