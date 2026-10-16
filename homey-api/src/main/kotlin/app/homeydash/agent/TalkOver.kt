package app.homeydash.agent

import kotlin.math.sqrt

/**
 * Notices someone starting to talk while the assistant is speaking, from the microphone with
 * the tablet's echo cancelling on. What's left of its own voice and the room sets a running
 * floor; sound clearly above it for long enough counts as someone talking. Whose voice it is
 * gets checked afterwards (a voiceprint), since what's left of its own voice can be loud too.
 */
class TalkOver(private val rate: Int) {
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
        if (floor < 0) {
            floor = rms
            return false
        }
        if (rms > maxOf(floor * LOUDER, QUIETEST)) {
            loud += n
            gap = 0
        } else {
            gap += n
            settle(rms)
            if (gap > rate * GAP_MS / 1000) loud = 0
        }
        if (loud >= rate * TALK_MS / 1000) {
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
        const val LOUDER = 2.5
        /** Below this nothing counts, however quiet the room (16-bit samples). */
        const val QUIETEST = 500.0
        /** How long someone must talk before it checks who. */
        const val TALK_MS = 600
        /** A pause between words up to this long doesn't count as stopping. */
        const val GAP_MS = 250
        const val REST_MS = 900
        /** How much of the recent sound goes to the voiceprint. */
        const val SOUND_MS = 1500
    }
}
