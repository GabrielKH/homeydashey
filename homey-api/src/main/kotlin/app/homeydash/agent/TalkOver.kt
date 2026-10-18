package app.homeydash.agent

import kotlin.math.sqrt

/**
 * Notices someone starting to talk while the assistant is speaking, from the microphone with
 * the tablet's echo cancelling on. What's left of its own voice and the room sets a running
 * floor; sound clearly above it for long enough counts as someone talking. Whose voice it is
 * gets checked afterwards (a voiceprint), since what's left of its own voice can be loud too.
 * The first moments only learn the floor, so the start of its own voice can't set it off, and
 * [easy] lets quieter, shorter talk through (see [Interrupt]).
 */
class TalkOver(private val rate: Int, private val easy: Boolean = false) {
    private val louder = if (easy) EASY_LOUDER else LOUDER
    private val quietest = if (easy) EASY_QUIETEST else QUIETEST
    private val talkMs = if (easy) EASY_TALK_MS else TALK_MS
    /** How much has been heard while still learning the floor. */
    private var warm = 0
    private var warmSum = 0.0
    /** The usual loudness of what the microphone hears while it talks. */
    private var floor = -1.0
    /** How long sound has stayed above the floor, allowing short gaps between words. */
    private var loud = 0
    private var gap = 0
    /** After a check, wait a little before the next one. */
    private var rest = 0

    /** Takes the next [n] samples of [buf]; true when someone has been talking long enough to check who. */
    fun feed(buf: ShortArray, n: Int): Boolean {
        if (n <= 0) return false
        var sum = 0.0
        for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
        val rms = sqrt(sum / n)
        if (rest > 0) {
            rest -= n
            settle(rms)
            return false
        }
        if (warm < rate * WARM_MS / 1000) {
            // Learning what its own voice sounds like through the echo cancelling: the mean, so
            // a loud first word sets the floor high rather than one quiet moment setting it low.
            warm += n
            warmSum += rms * n
            floor = warmSum / warm
            return false
        }
        if (rms > maxOf(floor * louder, quietest)) {
            loud += n
            gap = 0
        } else {
            gap += n
            settle(rms)
            if (gap > rate * GAP_MS / 1000) loud = 0
        }
        if (loud >= rate * talkMs / 1000) {
            loud = 0
            rest = rate * REST_MS / 1000
            return true
        }
        return false
    }

    private fun settle(rms: Double) {
        floor = if (floor < 0) rms else floor * 0.9 + rms * 0.1
    }

    companion object {
        /** How much louder than the floor talking is. */
        const val LOUDER = 3.5
        const val EASY_LOUDER = 2.5
        /** Below this nothing counts, however quiet the room (16-bit samples). */
        const val QUIETEST = 1200.0
        const val EASY_QUIETEST = 600.0
        /** How long someone must talk before it checks who. */
        const val TALK_MS = 900
        const val EASY_TALK_MS = 600
        /** At the start it only listens to learn the floor. */
        const val WARM_MS = 800
        /** How far above their voiceprint bar the voice must be, so a near miss doesn't stop it. */
        const val MARGIN = 0.08f
        const val EASY_MARGIN = 0f
        /** A pause between words up to this long doesn't count as stopping. */
        const val GAP_MS = 250
        const val REST_MS = 900
        /** How much of the recent sound goes to the voiceprint. */
        const val SOUND_MS = 1500
    }
}
