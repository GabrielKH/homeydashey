package app.homeydash.agent

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Log-mel filterbank features the way Kaldi makes them (25 ms frames every 10 ms, Povey window,
 * pre-emphasis, 80 mel bands), which is what the WeSpeaker voice model was trained on. Each band
 * has its average over the clip taken away, as WeSpeaker does before reading a voice.
 */
object Fbank {
    const val RATE = 16000
    const val BANDS = 80
    private const val FRAME = RATE * 25 / 1000
    private const val SHIFT = RATE * 10 / 1000
    private const val FFT = 512

    private val window = DoubleArray(FRAME) { i -> (0.5 - 0.5 * cos(2 * PI * i / (FRAME - 1))).pow(0.85) }

    /** Triangular mel filters over the FFT bins, from 20 Hz to half the sample rate. */
    private val filters: Array<DoubleArray> = run {
        fun mel(f: Double) = 1127.0 * ln(1 + f / 700.0)
        val lo = mel(20.0)
        val hi = mel(RATE / 2.0)
        val step = (hi - lo) / (BANDS + 1)
        Array(BANDS) { b ->
            val left = lo + b * step
            val centre = left + step
            val right = centre + step
            DoubleArray(FFT / 2 + 1) { k ->
                val m = mel(k.toDouble() * RATE / FFT)
                if (k == 0) 0.0 else max(0.0, min((m - left) / (centre - left), (right - m) / (right - centre)))
            }
        }
    }

    /**
     * Features of 16 kHz [samples] (16-bit scale), one row of [BANDS] per 10 ms; empty when
     * shorter than a frame. [centred] false keeps each band's own level instead.
     */
    fun features(samples: ShortArray, centred: Boolean = true): Array<FloatArray> {
        if (samples.size < FRAME) return emptyArray()
        val frames = 1 + (samples.size - FRAME) / SHIFT
        val re = DoubleArray(FFT)
        val im = DoubleArray(FFT)
        val power = DoubleArray(FFT / 2 + 1)
        val out = Array(frames) { FloatArray(BANDS) }
        for (t in 0 until frames) {
            val start = t * SHIFT
            var mean = 0.0
            for (i in 0 until FRAME) mean += samples[start + i]
            mean /= FRAME
            var previous = samples[start] - mean
            for (i in 0 until FRAME) {
                val x = samples[start + i] - mean
                // Pre-emphasis; Kaldi uses the first sample as its own predecessor.
                re[i] = (x - 0.97 * previous) * window[i]
                previous = x
            }
            for (i in FRAME until FFT) re[i] = 0.0
            im.fill(0.0)
            fft(re, im)
            for (k in 0..FFT / 2) power[k] = re[k] * re[k] + im[k] * im[k]
            for (b in 0 until BANDS) {
                val f = filters[b]
                var e = 0.0
                for (k in power.indices) e += f[k] * power[k]
                out[t][b] = ln(max(e, FLOOR)).toFloat()
            }
        }
        if (!centred) return out
        for (b in 0 until BANDS) {
            var m = 0.0
            for (row in out) m += row[b]
            m /= frames
            for (row in out) row[b] = (row[b] - m).toFloat()
        }
        return out
    }

    /** In-place radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            val wr = cos(angle)
            val wi = sin(angle)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar
                    im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar
                    im[i + k] += ai
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private const val FLOOR = 1.1920929e-7
}

/**
 * Tells voices apart by their voiceprints (the WeSpeaker model's 256 numbers for a stretch
 * of speech). Each person is compared by both their average voiceprint and their closest
 * single one, and needs to beat a bar set from how alike their own teaching samples were,
 * so a steady voice gets a strict bar and a varied one a kinder one.
 */
object VoiceId {
    /** The bar while someone has too few voiceprints to set their own. */
    const val DEFAULT_BAR = 0.42f
    private const val LOWEST_BAR = 0.36f
    private const val HIGHEST_BAR = 0.6f
    /** Most voiceprints kept per person; the oldest go first. */
    const val MAX_PRINTS = 30

    fun normalize(v: FloatArray): FloatArray {
        val n = sqrt(v.fold(0f) { acc, x -> acc + x * x })
        return if (n == 0f) v else FloatArray(v.size) { v[it] / n }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var d = 0f
        for (i in a.indices) d += a[i] * b[i]
        return d
    }

    private fun centroid(prints: List<FloatArray>): FloatArray {
        val c = FloatArray(prints.first().size)
        prints.forEach { p -> for (i in c.indices) c[i] += p[i] }
        return normalize(c)
    }

    /** How much [print] sounds like [prints] (all normalised), about -1 to 1. */
    fun score(print: FloatArray, prints: List<FloatArray>): Float {
        if (prints.isEmpty()) return -1f
        val v = normalize(print)
        val best = prints.maxOf { dot(v, it) }
        return 0.6f * dot(v, centroid(prints)) + 0.4f * best
    }

    /**
     * The score someone's voice must reach: each teaching sample scored against the others,
     * minus two and a half spreads, so nearly all of their own wakes pass.
     */
    fun bar(prints: List<FloatArray>): Float {
        if (prints.size < 4) return DEFAULT_BAR
        val scores = prints.indices.map { i -> score(prints[i], prints.filterIndexed { j, _ -> j != i }) }
        val mean = scores.average()
        val spread = sqrt(scores.sumOf { (it - mean) * (it - mean) } / scores.size)
        return (mean - 2.5 * spread).toFloat().coerceIn(LOWEST_BAR, HIGHEST_BAR)
    }

    /** Whose voice [print] is, or null when nobody's is close enough. */
    fun match(print: FloatArray, people: List<KnownPerson>): KnownPerson? =
        people.filter { it.print.isNotEmpty() }
            .map { p -> p to (score(print, p.print) - bar(p.print)) }
            .filter { it.second >= 0f }
            .maxByOrNull { it.second }
            ?.first

    /** How far above their bar [print] is for [person]; learning only from clear wins keeps strangers out. */
    fun margin(print: FloatArray, person: KnownPerson): Float =
        if (person.print.isEmpty()) -1f else score(print, person.print) - bar(person.print)

    /** [prints] with [more] added, keeping the newest [MAX_PRINTS]. */
    fun with(prints: List<FloatArray>, more: List<FloatArray>): List<FloatArray> = (prints + more.map(::normalize)).takeLast(MAX_PRINTS)
}
