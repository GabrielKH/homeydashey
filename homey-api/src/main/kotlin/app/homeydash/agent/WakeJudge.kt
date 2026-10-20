package app.homeydash.agent

import kotlin.math.exp
import kotlin.math.ln

/**
 * Decides whether a spotted wake name was really someone calling the assistant, from several
 * witnesses heard one after the other: Wald's sequential probability ratio test. Each witness
 * adds its log-likelihood ratio to a running score that starts from the prior; the first time
 * the score crosses [upper] it wakes, the first time it drops under [lower] the hit is dropped,
 * and while it is in between the next, costlier witness is asked. That needs the fewest
 * witnesses on average of any test with the same two error rates, which are chosen up front:
 * [alpha] false wakes per hit and [beta] missed calls.
 *
 * The witnesses are only called when needed, so the cheap ones (the spotter, the voiceprint)
 * decide most hits in a few milliseconds and the Whisper check on the Mac runs only for the
 * unclear ones.
 */
class WakeJudge(alpha: Double = DEFAULT_ALPHA, beta: Double = DEFAULT_BETA) {
    /** What one witness said: its [name] for the log, its evidence [lambda] (log-likelihood ratio), and a few words. */
    data class Evidence(val name: String, val lambda: Double, val note: String = "")

    /** A witness: null when it has nothing to say (no voiceprints taught, no model yet). */
    fun interface Witness {
        fun hear(): Evidence?
    }

    data class Verdict(val wake: Boolean, val score: Double, val heard: List<Evidence>, val undecided: Boolean) {
        /** One plain line for the log: "spotter +2.5 · Gabriel's voice +3.1 → woke (5.6)". */
        fun line(): String = heard.joinToString(" · ") { e -> e.name + (if (e.note.isNotBlank()) " (${e.note})" else "") + " " + signed(e.lambda) } +
            " → " + (if (wake) "woke" else if (undecided) "left undecided, dropped" else "dropped") + " (%.1f)".format(score)
    }

    /** Wake above this. */
    val upper: Double = ln((1 - beta) / alpha)
    /** Drop below this. */
    val lower: Double = ln(beta / (1 - alpha))

    /**
     * Hears [witnesses] in order until the score, starting from the log odds of [prior],
     * crosses a line. [armed] (a hit a moment ago that ended undecided) halves the bar to wake,
     * as Apple's second, lower threshold does. Undecided after every witness counts as dropped:
     * a wake needs the evidence.
     */
    fun judge(prior: Double, witnesses: List<Witness>, armed: Boolean = false): Verdict {
        val p = prior.coerceIn(0.01, 0.99)
        var score = ln(p / (1 - p))
        val top = if (armed) upper / 2 else upper
        val heard = ArrayList<Evidence>()
        for (w in witnesses) {
            if (score >= top) return Verdict(true, score, heard, false)
            if (score <= lower) return Verdict(false, score, heard, false)
            val e = w.hear() ?: continue
            heard += e
            score += e.lambda
        }
        return when {
            score >= top -> Verdict(true, score, heard, false)
            score <= lower -> Verdict(false, score, heard, false)
            else -> Verdict(false, score, heard, true)
        }
    }

    companion object {
        const val DEFAULT_ALPHA = 0.02
        const val DEFAULT_BETA = 0.05

        private fun signed(x: Double) = (if (x >= 0) "+" else "") + "%.1f".format(x)

        /** The false-wake rate per spotter hit the microphone sensitivity (0–100) asks for: strict 0.005 to easy 0.05. */
        fun alpha(sensitivity: Int): Double = 0.005 * exp(ln(10.0) * sensitivity.coerceIn(0, 100) / 100.0)

        /**
         * The prior that a spotted name was a call, from what Homey knows of the room: media
         * playing there, nobody home, a known face at the tablet, and the house's share of real
         * wakes at this hour ([hourShare], 1.0 when unknown).
         */
        fun prior(mediaPlaying: Boolean, nobodyHome: Boolean, faceKnown: Boolean, hourShare: Double = 1.0): Double {
            var odds = 1.0
            if (mediaPlaying) odds *= 0.3
            if (nobodyHome) odds *= 0.1
            if (faceKnown) odds *= 3.0
            odds *= hourShare.coerceIn(0.2, 3.0)
            return (odds / (1 + odds)).coerceIn(0.02, 0.95)
        }

        /**
         * The spotter's word: a hit over its threshold is evidence for, more when the name came
         * with a greeting ("hey Nova": two words are harder to hear by chance than one).
         */
        fun spotter(withGreeting: Boolean): Evidence = Evidence("spotter", if (withGreeting) 3.5 else 2.5, if (withGreeting) "with greeting" else "name alone")

        /**
         * The voiceprint's word from how far over (or under) their bar the closest known voice
         * is: clearly theirs is strong evidence for, clearly nobody's is evidence against, and
         * near the bar it says little. Null [margin] = no voiceprints to compare with.
         */
        fun voice(margin: Float?, who: String?): Evidence? {
            if (margin == null) return null
            // Over their bar at all already counts for: a cold or a quiet "hey" keeps the margin small.
            val lambda = if (margin >= 0 && who != null) (1.5 + margin * 10.0).coerceAtMost(3.5) else (margin * 12.0).coerceIn(-3.0, 3.5)
            return Evidence("voice", lambda, if (who != null) "$who's" else "nobody known")
        }

        /**
         * How close what was heard came to the name as the people here taught it ([SaidName]):
         * well inside their bar is strong evidence, near it some, far outside it against. With a
         * known voice that wakes it; near the bar, Whisper has the last word. Null when nobody
         * taught the name.
         */
        fun said(distance: Float?, bar: Float?): Evidence? {
            if (distance == null || bar == null) return null
            val share = distance / bar
            val lambda = when {
                share <= 0.8f -> 3.5
                share <= 1.0f -> 2.5
                share <= 1.15f -> 0.5
                else -> -1.5
            }
            return Evidence("said like taught", lambda, "%.0f %% of the bar".format(share * 100))
        }

        /**
         * The score as a share of what waking needs, 0–100 %, for a meter: 0 at the line where a
         * hit is dropped, 100 at the line where it wakes.
         */
        fun share(verdict: Verdict, judge: WakeJudge): Int =
            (((verdict.score - judge.lower) / (judge.upper - judge.lower)) * 100).toInt().coerceIn(0, 100)

        /** The tablet's own voice (its text-to-speech is a taught voice too): near-certain evidence against. */
        fun itself(): Evidence = Evidence("voice", -8.0, "its own")

        /** A stored false wake that sounds the same: [ber] is the best bit error rate against them. */
        fun fingerprint(ber: Double?): Evidence? = when {
            ber == null -> null
            ber < SoundPrint.SAME -> Evidence("fingerprint", -8.0, "same sound as a past false wake, %.0f %% off".format(ber * 100))
            ber < SoundPrint.CLOSE -> Evidence("fingerprint", -2.0, "close to a past false wake, %.0f %% off".format(ber * 100))
            else -> Evidence("fingerprint", 0.3, "no match")
        }

        /** Whisper's word on the clip: the name is there, absent, or another word stood in its place. */
        fun whisper(text: String, names: List<String>): Evidence {
            val plain = NameMatch.normalize(text)
            val said = names.any { n -> NameMatch.normalize(n).let { it.isNotEmpty() && (" $plain ").contains(" $it ") } }
            return when {
                said -> Evidence("whisper", 3.0, "\"$text\"")
                plain.isBlank() -> Evidence("whisper", -3.0, "heard nothing")
                else -> Evidence("whisper", -4.0, "heard \"$text\"")
            }
        }
    }
}
