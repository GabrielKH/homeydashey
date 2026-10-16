package app.homeydash.agent

/** Where the assistant is in a conversation, for the [Ticker]. */
enum class TickerPhase { Idle, Listening, Thinking, Speaking }

/**
 * The three small lines under the minimised assistant: what it just did, faded above,
 * what it is doing now, and below it what comes next. The AI doesn't say its plan
 * ahead, so the next line is what always follows: the answer after the work, and
 * listening again after speaking when the conversation goes on.
 */
data class Ticker(val prev: String, val now: String, val next: String) {
    companion object {
        fun of(
            phase: TickerPhase,
            heard: String?,
            partial: String,
            steps: List<AgentStep>,
            followUp: Boolean,
        ): Ticker {
            val said = heard?.trim()?.takeIf { it.isNotEmpty() }?.let { "Heard “$it”" }.orEmpty()
            val running = steps.lastOrNull { it.state == StepState.Running }
            val done = steps.lastOrNull { it.state != StepState.Running }
            return when (phase) {
                TickerPhase.Listening -> Ticker(
                    prev = "",
                    now = partial.trim().ifEmpty { "Listening…" },
                    next = if (partial.isBlank()) "" else "Working it out",
                )
                TickerPhase.Thinking -> Ticker(
                    prev = done?.let(::label) ?: said,
                    now = running?.text ?: "Thinking…",
                    next = "Answering",
                )
                TickerPhase.Speaking -> Ticker(
                    prev = done?.let(::label) ?: said,
                    now = "Answering",
                    next = if (followUp) "Listening for a follow-up" else "",
                )
                TickerPhase.Idle -> Ticker(prev = done?.let(::label) ?: said, now = "", next = "")
            }
        }

        private fun label(step: AgentStep): String =
            if (step.state == StepState.Failed) "Couldn't: ${step.text}" else step.text
    }
}
