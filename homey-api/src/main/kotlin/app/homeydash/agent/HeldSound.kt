package app.homeydash.agent

import kotlin.math.sqrt

/**
 * A drawn-out "aaaaa", "ccccc" or "mmmmm": one sound held, with no word in it. The spotter and
 * the voiceprint can both pass one (it is the right person, and a long vowel can come close to
 * "Nova"), but a name always moves from sound to sound: "Hej Nova" goes h-e-j-n-o-v-a, and its
 * spectrum keeps changing. This measures how much it changes across the loud part of a clip.
 */
object HeldSound {
    /** Quieter than this (natural-log energy below the loudest frame, about 26 dB) is left out. */
    private const val LOUD = 6f
    /** Frames compared this far apart (20 ms each), so one sound gliding slowly into the next counts as the same. */
    private const val GAP = 4
    /** Which of the changes, smallest first, speaks for the clip: a word's few big jumps count, a held sound has none. */
    private const val SHARE = 0.75

    /**
     * How much the sound in [samples] (16 kHz) changes: of the log-spectral distances between
     * loud frames [GAP] apart, per band and with loudness itself taken out (a tremolo is no new
     * sound), the one at [SHARE]. Null when too little was loud to say. Spoken names measured
     * 1.24 and up (Piper voices, Swedish and English, three speeds, at 30 dB over the room's
     * noise) and held vowels, hums and hisses 0.56 to 1.02, the highest for the shortest, 0.6 s.
     * At only 20 dB over the noise everything moves less: names 0.95 and up, held sounds to 0.91.
     */
    fun change(samples: ShortArray): Double? {
        val f = Fbank.features(samples, centred = false)
        val frames = f.size / 2
        if (frames < GAP + 6) return null
        val bands = Fbank.BANDS / 2
        val p = Array(frames) { t -> FloatArray(bands) { b -> (f[2 * t][2 * b] + f[2 * t][2 * b + 1] + f[2 * t + 1][2 * b] + f[2 * t + 1][2 * b + 1]) / 4f } }
        val energy = FloatArray(frames) { t -> p[t].max() }
        val top = energy.max()
        val pairs = (0 until frames - GAP).filter { energy[it] > top - LOUD && energy[it + GAP] > top - LOUD }
        if (pairs.size < 4) return null
        val d = pairs.map { t ->
            val a = p[t]
            val c = p[t + GAP]
            val shift = a.average() - c.average()
            var s = 0.0
            for (b in 0 until bands) { val x = a[b] - c[b] - shift; s += x * x }
            sqrt(s / bands)
        }.sorted()
        return d[((d.size - 1) * SHARE).toInt()]
    }
}
