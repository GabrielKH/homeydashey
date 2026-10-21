package app.homeydash.agent

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * The kitchen timers' sounds, made on the tablet like [WakeSounds]: soft bell notes, warm
 * rather than shrill, so a countdown is noticed across the kitchen without startling anyone.
 * [notice] goes before each spoken "fem minuter kvar"; [alarm] plays when a timer is done and
 * again until it is stopped or muted, a little louder each time.
 */
object TimerSounds {
    const val RATE = 24000

    /** Two notes rising a fifth (G5, D6): "something to know". */
    fun notice(): ShortArray = bells(listOf(0.0 to 784.0, 0.16 to 1174.7), seconds = 1.1, level = 0.32)

    /**
     * Done: three rising notes and a high one (C6, E6, G6, C7), twice. [round] (from 0) raises
     * the level a little each time it repeats, up to [MAX_ROUND].
     */
    fun alarm(round: Int = 0): ShortArray {
        val notes = listOf(0.0 to 1046.5, 0.14 to 1318.5, 0.28 to 1568.0, 0.46 to 2093.0)
        val twice = notes + notes.map { (t, f) -> t + 1.1 to f }
        val level = 0.34 + 0.06 * round.coerceIn(0, MAX_ROUND)
        return bells(twice, seconds = 2.4, level = level)
    }

    const val MAX_ROUND = 6

    /** Bell-like notes: a quick soft attack, a long ring, a quiet octave above and a touch of the fifth. */
    private fun bells(notes: List<Pair<Double, Double>>, seconds: Double, level: Double): ShortArray {
        val n = (seconds * RATE).toInt()
        val out = DoubleArray(n)
        for ((start, f) in notes) {
            val s0 = (start * RATE).toInt()
            for (i in s0 until n) {
                val t = (i - s0).toDouble() / RATE
                val attack = (t / 0.006).coerceAtMost(1.0)
                val env = attack * exp(-t * 4.2)
                out[i] += env * (sin(2 * PI * f * t) + 0.18 * exp(-t * 6.0) * sin(2 * PI * 2 * f * t) + 0.06 * sin(2 * PI * 3 * f * t))
            }
        }
        // Fades out over the last 60 ms so it never ends in a click.
        val fade = (0.06 * RATE).toInt().coerceAtMost(n)
        for (i in 0 until fade) out[n - 1 - i] *= i.toDouble() / fade
        val peak = out.maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: 1.0
        return ShortArray(n) { i -> (out[i] / peak * level * 32767).toInt().coerceIn(-32768, 32767).toShort() }
    }
}
