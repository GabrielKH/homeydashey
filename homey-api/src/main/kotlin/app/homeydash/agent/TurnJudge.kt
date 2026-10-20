package app.homeydash.agent

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How long to wait at a pause before taking the question as finished, judged like a listener
 * does: by counting the cues people give when handing over a turn (Gravano and Hirschberg's
 * seven, the five a tablet can hear). Complete words, a quieter ending, a falling or sharply
 * rising tone, no speeding up and no held "ööhh" each count one. Many cues and it answers
 * almost at once; none and it waits for the rest. The wait scales with the person's own pause
 * habit from the memory graph.
 */
object TurnJudge {
    /** The judgement at one pause: how many cues, the wait in ms, and which cues were there, for the log. */
    data class Judgement(val cues: Int, val waitMs: Long, val found: List<String>, val missing: List<String>) {
        fun line(): String = "$cues cue${if (cues == 1) "" else "s"} (${found.joinToString(", ").ifEmpty { "none" }}" +
            (if (missing.isNotEmpty()) "; not " + missing.joinToString(", ") else "") + ") → wait $waitMs ms"
    }

    const val CUES = 5
    const val MIN_WAIT_MS = 300L
    const val MAX_WAIT_MS = 3000L
    /** The last stretch of speech the ending is judged on. */
    private const val ENDING_MS = 300
    private const val RATE_WINDOW_MS = 600
    /** Quieter than this share of the turn's usual loudness counts as trailing off. */
    private const val QUIET_SHARE = 0.7
    /** Faster than this share of the usual rate means more is coming. */
    private const val FAST_SHARE = 1.2
    private const val FALLING_HZ_PER_S = -20.0
    private const val RISING_HZ_PER_S = 40.0

    /**
     * The wait for [cues] of [CUES], times [habit] (1.0 by default; a slow talker's is bigger):
     * 0.3 s with all five, 3 s with none, falling fast as soon as a couple are there.
     */
    fun wait(cues: Int, habit: Double = 1.0): Long {
        val share = 1.0 - cues.coerceIn(0, CUES).toDouble() / CUES
        val ms = MIN_WAIT_MS + (MAX_WAIT_MS - MIN_WAIT_MS) * share * share * habit.coerceIn(0.6, 1.6)
        return ms.roundToInt().toLong().coerceIn(MIN_WAIT_MS, (MAX_WAIT_MS * 1.6).toLong())
    }

    /**
     * Judges the pause after [turn] (the person's speech so far, 16 kHz, silence included) with
     * what Whisper made of it ([text]) and whether a held sound came just before the pause.
     */
    fun judge(text: String, wakeName: String, held: Boolean, turn: ShortArray, habit: Double = 1.0): Judgement {
        val found = ArrayList<String>()
        val missing = ArrayList<String>()
        fun cue(name: String, there: Boolean) = if (there) found += name else missing += name
        cue("complete words", TurnEnd.complete(text, wakeName, false))
        cue("no held sound", !held)
        val p = Prosody.of(turn)
        cue("trailing off", p.endingLoudness() < QUIET_SHARE)
        val slope = p.endingPitchSlope()
        cue("falling or asking tone", slope != null && (slope < FALLING_HZ_PER_S || slope > RISING_HZ_PER_S))
        cue("not speeding up", p.endingRate() <= FAST_SHARE)
        return Judgement(found.size, wait(found.size, habit), found, missing)
    }

    /**
     * The loudness, pitch and syllable rate of a stretch of speech, frame by frame (10 ms), for
     * the cues. Cheap: autocorrelation pitch on the voiced frames, peaks of the loudness
     * envelope for syllables.
     */
    class Prosody private constructor(private val rms: DoubleArray, private val pitch: DoubleArray, private val speech: BooleanArray) {
        /** The turn's usual peak loudness: the upper quarter of its speech frames. */
        private val usual: Double = rms.filterIndexed { i, _ -> speech[i] }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size * 3 / 4] }

        /** The loudest of the last [ENDING_MS] of speech against the turn's usual peaks, 1.0 when alike. */
        fun endingLoudness(): Double {
            if (usual <= 0) return 1.0
            val tail = lastSpeech(ENDING_MS / FRAME_MS)
            if (tail.isEmpty()) return 1.0
            return tail.maxOf { rms[it] } / usual
        }

        /** How the pitch moved over the last [ENDING_MS] of speech, in Hz per second; null without enough voiced frames. */
        fun endingPitchSlope(): Double? {
            val voiced = lastSpeech(ENDING_MS / FRAME_MS).filter { pitch[it] > 0 }
            if (voiced.size < 4) return null
            // A syllable's quiet onset often reads an octave low; those frames are left out.
            val middle = voiced.map { pitch[it] }.sorted()[voiced.size / 2]
            val tail = voiced.filter { pitch[it] > middle * 0.7 }
            if (tail.size < 4) return null
            val xs = tail.map { it * FRAME_MS / 1000.0 }
            val ys = tail.map { pitch[it] }
            val mx = xs.average()
            val my = ys.average()
            var num = 0.0
            var den = 0.0
            for (i in xs.indices) {
                num += (xs[i] - mx) * (ys[i] - my)
                den += (xs[i] - mx) * (xs[i] - mx)
            }
            return if (den == 0.0) null else num / den
        }

        /** Syllables per second over the last [RATE_WINDOW_MS] against the whole turn's, 1.0 when alike. */
        fun endingRate(): Double {
            val all = syllablesPerSecond(0, rms.size)
            if (all <= 0) return 1.0
            val from = max(0, rms.size - RATE_WINDOW_MS / FRAME_MS)
            return syllablesPerSecond(from, rms.size) / all
        }

        /** The speech frames in the last [n] frames up to where speech ended, in order. */
        private fun lastSpeech(n: Int): List<Int> {
            val end = speech.indexOfLast { it }
            if (end < 0) return emptyList()
            return (max(0, end - n + 1)..end).filter { speech[it] }
        }

        /** Peaks of the smoothed loudness within [from] until [to), per second of speech. */
        private fun syllablesPerSecond(from: Int, to: Int): Double {
            if (to - from < 5) return 0.0
            val smooth = DoubleArray(to - from) { i ->
                var s = 0.0
                var k = 0
                for (j in -2..2) {
                    val t = from + i + j
                    if (t in from until to) { s += rms[t]; k++ }
                }
                s / k
            }
            // A syllable peak is judged against the loudness around it, so a quiet ending's
            // syllables count as much as a loud start's.
            val around = LOCAL_MS / FRAME_MS
            var peaks = 0
            var last = -100
            for (i in 1 until smooth.size - 1) {
                var local = 0.0
                for (j in max(0, i - around)..minOf(smooth.size - 1, i + around)) if (smooth[j] > local) local = smooth[j]
                if (local <= 0) continue
                if (smooth[i] > smooth[i - 1] && smooth[i] >= smooth[i + 1] && smooth[i] > local * PEAK_SHARE && i - last >= MIN_SYLLABLE_MS / FRAME_MS) {
                    peaks++
                    last = i
                }
            }
            val speechFrames = (from until to).count { speech[it] }
            if (speechFrames == 0) return 0.0
            return peaks / (speechFrames * FRAME_MS / 1000.0)
        }

        companion object {
            const val RATE = 16000
            const val FRAME_MS = 10
            private const val FRAME = RATE * FRAME_MS / 1000
            private const val PITCH_WINDOW = RATE * 30 / 1000
            private const val MIN_LAG = RATE / 400
            private const val MAX_LAG = RATE / 60
            private const val PEAK_SHARE = 0.5
            private const val LOCAL_MS = 250
            private const val MIN_SYLLABLE_MS = 100
            private const val VOICED = 0.5
            private const val OCTAVE = 0.9

            fun of(samples: ShortArray): Prosody {
                val frames = samples.size / FRAME
                val rms = DoubleArray(frames) { t ->
                    var s = 0.0
                    for (i in 0 until FRAME) { val v = samples[t * FRAME + i].toDouble(); s += v * v }
                    sqrt(s / FRAME)
                }
                // Speech frames: clearly above the quietest tenth of the stretch, so a trailing-off
                // ending still counts as speech.
                val sorted = rms.sorted()
                val floor = if (sorted.isEmpty()) 0.0 else sorted[sorted.size / 10]
                val loud = if (sorted.isEmpty()) 0.0 else sorted[sorted.size * 9 / 10]
                val bar = max(floor * 2.0, loud * 0.08)
                val speech = BooleanArray(frames) { rms[it] > bar }
                val pitch = DoubleArray(frames) { t -> if (speech[t]) pitchAt(samples, t * FRAME) else 0.0 }
                return Prosody(rms, pitch, speech)
            }

            /** The pitch of the 30 ms from [start] by autocorrelation, in Hz; 0 when unvoiced. */
            private fun pitchAt(samples: ShortArray, start: Int): Double {
                val end = start + PITCH_WINDOW
                // Every lag must fit, or a lag cut short reads as a pitch that isn't there.
                if (end + MAX_LAG > samples.size) return 0.0
                var energy = 0.0
                for (i in start until end) { val v = samples[i].toDouble(); energy += v * v }
                if (energy <= 0) return 0.0
                val r = DoubleArray(MAX_LAG + 1)
                var best = 0.0
                for (lag in MIN_LAG..MAX_LAG) {
                    var c = 0.0
                    for (i in start until end) c += samples[i].toDouble() * samples[i + lag]
                    r[lag] = c / energy
                    if (r[lag] > best) best = r[lag]
                }
                if (best < VOICED) return 0.0
                // Twice the period correlates as well as the period itself: the shortest lag near the best wins.
                val lag = (MIN_LAG..MAX_LAG).firstOrNull { r[it] >= best * OCTAVE } ?: return 0.0
                return RATE.toDouble() / lag
            }
        }
    }
}
