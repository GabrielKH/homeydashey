package app.homeydash.agent

import org.junit.Test
import org.junit.Assert.assertEquals

class TickerTest {
    private fun step(text: String, state: StepState) = AgentStep(StepIds.next(), text, state)

    @Test
    fun listeningShowsWhatIsHeard() {
        assertEquals(Ticker("", "Listening…", ""), Ticker.of(TickerPhase.Listening, null, "", emptyList(), false))
        assertEquals("vacuum the kitchen", Ticker.of(TickerPhase.Listening, null, "vacuum the kitchen", emptyList(), false).now)
    }

    @Test
    fun workingShowsDoneRunningAndNext() {
        val t = Ticker.of(
            TickerPhase.Thinking, "vacuum the kitchen", "",
            listOf(step("Finding the robot vacuum", StepState.Done), step("Sending Roborock to Kitchen", StepState.Running)),
            false,
        )
        assertEquals(Ticker("Finding the robot vacuum", "Sending Roborock to Kitchen", "Answering"), t)
    }

    @Test
    fun firstStepFollowsWhatWasHeard() {
        val t = Ticker.of(TickerPhase.Thinking, "vacuum the kitchen", "", emptyList(), false)
        assertEquals("Heard “vacuum the kitchen”", t.prev)
        assertEquals("Thinking…", t.now)
    }

    @Test
    fun speakingWaitsForFollowUpOnlyInAConversation() {
        val steps = listOf(step("Playing jazz", StepState.Done))
        assertEquals(Ticker("Playing jazz", "Answering", "Listening for a follow-up"), Ticker.of(TickerPhase.Speaking, "x", "", steps, true))
        assertEquals("", Ticker.of(TickerPhase.Speaking, "x", "", steps, false).next)
        assertEquals("Couldn't: Playing jazz", Ticker.of(TickerPhase.Speaking, "x", "", listOf(step("Playing jazz", StepState.Failed)), false).prev)
    }
}
