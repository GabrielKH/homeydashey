package app.homeydash.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class TurnEndTest {
    @Test fun `finished questions are finished`() {
        assertTrue(TurnEnd.complete("Tänd lampan i köket."))
        assertTrue(TurnEnd.complete("Turn the kitchen light on."))
        assertTrue(TurnEnd.complete("Stäng av"))
        assertTrue(TurnEnd.complete("What's the weather like tomorrow?"))
        assertTrue(TurnEnd.complete("Can you turn on the lights please"))
    }

    @Test fun `hesitations and open endings wait for the rest`() {
        assertFalse(TurnEnd.complete("Tänd lampan i öh"))
        assertFalse(TurnEnd.complete("Can you, um"))
        assertFalse(TurnEnd.complete("Turn on the"))
        assertFalse(TurnEnd.complete("Sätt på musik och"))
        assertFalse(TurnEnd.complete("Jag vill att du..."))
        assertFalse(TurnEnd.complete("Play something by,"))
        assertFalse(TurnEnd.complete("Hey TARS.", wakeName = "TARS"))
        assertFalse(TurnEnd.complete(""))
        assertFalse(TurnEnd.complete("Turn on the lights", held = true))
    }

    @Test fun `hesitation sounds are left out for the AI`() {
        assertEquals("Tänd lampan i köket.", TurnEnd.clean("Öh, tänd ehm lampan i köket."))
        assertEquals("Play some jazz.", TurnEnd.clean("Um, play some jazz, uh."))
    }

    private fun frame(amp: Double, i: Int) = ShortArray(800) { (amp * sin(2 * PI * 150 * (i * 800 + it) / 16000.0)).toInt().toShort() }

    @Test fun `a long even sound before the pause counts as held`() {
        val quiet = ShortArray(800)
        val held = Endpointer(16000)
        repeat(4) { held.feed(quiet, 800) }
        repeat(16) { held.feed(frame(5000.0, it), 800) }
        repeat(16) { held.feed(quiet, 800) }
        assertEquals(Endpointer.State.Paused, held.state)
        assertTrue(held.held)

        // Syllables: loud and soft by turns, about four a second.
        val words = Endpointer(16000)
        repeat(4) { words.feed(quiet, 800) }
        repeat(16) { words.feed(frame(if (it % 5 < 2) 6000.0 else 1800.0, it), 800) }
        repeat(16) { words.feed(quiet, 800) }
        assertEquals(Endpointer.State.Paused, words.state)
        assertFalse(words.held)
    }
}
