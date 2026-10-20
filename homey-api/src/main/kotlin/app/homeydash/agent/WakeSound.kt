package app.homeydash.agent

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** The little sound the assistant makes when it hears its name, before it listens. */
enum class WakeSound(val label: String, val detail: String) {
    Voice("\"Ja?\"", "It answers in its own voice: \"Ja?\", \"Japp?\" or \"Ja, du?\", a little different each time."),
    Hum("Mmm", "A soft hum that rises a little, like Siri's \"mm?\"."),
    Huh("Huuh", "A breathy, funky \"huuh?\"."),
    Chime("Chime", "Two short, soft tones."),
    Off("Off", "No sound; the face shows it's listening."),
}

/**
 * Makes the wake sounds on the tablet, so they need no files and play at once. The voiced ones
 * are built like a voice: a buzz at a pitch that glides up (a question), shaped by the
 * resonances of a hummed "m" or an open "uh", with a breath of noise in front of the "h".
 */
object WakeSounds {
    const val RATE = 24000

    /** 16-bit mono samples at [RATE]; empty for [WakeSound.Off]. */
    fun render(sound: WakeSound): ShortArray = when (sound) {
        WakeSound.Off -> ShortArray(0)
        // Said in the voice (see ThinkingSounds.answers); the hum stands in until that is made.
        WakeSound.Voice -> render(WakeSound.Hum)
        WakeSound.Chime -> chime()
        // A hummed "m": one strong low resonance, the rest muffled by the closed lips.
        WakeSound.Hum -> voiced(
            seconds = 0.42,
            pitch = { t -> 185.0 + 50.0 * smooth(t) },
            formants = listOf(Formant(260.0, 90.0, 1.0), Formant(1050.0, 260.0, 0.04), Formant(2300.0, 300.0, 0.025)),
            tilt = 1.6,
            breath = 0.0,
            level = 0.5,
        )
        // "Huuh?": a breath, then an open vowel that dips and swings up.
        WakeSound.Huh -> voiced(
            seconds = 0.5,
            pitch = { t -> 165.0 - 18.0 * sin(PI * minOf(t * 2.5, 1.0)) + 90.0 * smooth(((t - 0.35) / 0.65).coerceIn(0.0, 1.0)) },
            formants = listOf(Formant(620.0, 90.0, 1.0), Formant(1180.0, 110.0, 0.55), Formant(2450.0, 160.0, 0.18)),
            tilt = 1.25,
            breath = 0.12,
            level = 0.45,
        )
    }

    /** How long [sound] plays, in milliseconds. */
    fun durationMs(sound: WakeSound): Long = render(sound).size * 1000L / RATE

    private class Formant(val freq: Double, val width: Double, val gain: Double) {
        fun at(f: Double): Double {
            val x = (f - freq) / (width / 2)
            return gain / (1 + x * x)
        }
    }

    private fun smooth(t: Double): Double {
        val x = t.coerceIn(0.0, 1.0)
        return x * x * (3 - 2 * x)
    }

    /**
     * A voice-like sound: harmonics of the [pitch] (Hz at 0..1 of the way through), each as
     * loud as the [formants] are at its frequency, with [breath] seconds of hiss first.
     */
    private fun voiced(
        seconds: Double,
        pitch: (Double) -> Double,
        formants: List<Formant>,
        tilt: Double,
        breath: Double,
        level: Double,
    ): ShortArray {
        val breathN = (breath * RATE).toInt()
        val voiceN = (seconds * RATE).toInt()
        val out = DoubleArray(breathN + voiceN)
        // The breath: noise through the vowel's own resonances, swelling then giving way to the voice.
        if (breathN > 0) {
            val random = Random(7)
            val filters = formants.map { Resonator(it.freq, it.width * 3, RATE) }
            for (i in out.indices) {
                val t = i.toDouble() / breathN
                val env = if (t < 1) smooth(t) else exp(-(t - 1) * 6)
                if (env < 1e-3 && t > 1) break
                val n = random.nextDouble(-1.0, 1.0)
                out[i] += 0.18 * env * filters.zip(formants).sumOf { (r, f) -> r.next(n) * f.gain }
            }
        }
        var phase = 0.0
        for (i in 0 until voiceN) {
            val t = i.toDouble() / voiceN
            val f0 = pitch(t) * (1 + 0.012 * sin(2 * PI * 5.5 * i / RATE))
            phase += 2 * PI * f0 / RATE
            var s = 0.0
            var k = 1
            while (k * f0 < 5000) {
                s += formants.sumOf { it.at(k * f0) } / Math.pow(k.toDouble(), tilt) * sin(k * phase)
                k++
            }
            // Fade in over 40 ms and out over the last 35 % so it ends softly, like a voice.
            val attack = smooth(i / (0.04 * RATE))
            val release = smooth((1 - t) / 0.35)
            out[breathN + i] += s * attack * release
        }
        return toShorts(out, level)
    }

    /** Two soft bell tones a fifth apart, the second starting as the first fades. */
    private fun chime(): ShortArray {
        val n = (0.42 * RATE).toInt()
        val out = DoubleArray(n)
        listOf(0.0 to 880.0, 0.11 to 1318.5).forEach { (start, f) ->
            val s0 = (start * RATE).toInt()
            for (i in s0 until n) {
                val t = (i - s0).toDouble() / RATE
                val env = smooth(t / 0.008) * exp(-t * 11)
                out[i] += env * (sin(2 * PI * f * t) + 0.25 * sin(2 * PI * 2 * f * t))
            }
        }
        return toShorts(out, 0.35)
    }

    private fun toShorts(x: DoubleArray, level: Double): ShortArray {
        val peak = x.maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: 1.0
        return ShortArray(x.size) { i -> (x[i] / peak * level * 32767).toInt().coerceIn(-32768, 32767).toShort() }
    }

    /** A two-pole band-pass filter, for shaping the breath. */
    private class Resonator(freq: Double, width: Double, rate: Int) {
        private val r = exp(-PI * width / rate)
        private val a1 = 2 * r * kotlin.math.cos(2 * PI * freq / rate)
        private val a2 = -r * r
        private val g = 1 - r
        private var y1 = 0.0
        private var y2 = 0.0
        fun next(x: Double): Double {
            val y = g * x + a1 * y1 + a2 * y2
            y2 = y1
            y1 = y
            return y
        }
    }
}
