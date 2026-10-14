package app.homeydash.agent

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the assistant answered, and which connection answered it. */
data class AgentReply(
    val text: String,
    val brain: Connection? = null,
    val pending: PendingAction? = null,
    val error: Boolean = false,
    /** What it did, for the activity list, e.g. "set_device light_3 onoff=false". */
    val actions: List<String> = emptyList(),
    /** The words weren't meant for the assistant (people talking, a phone call); nothing to say. */
    val ignored: Boolean = false,
)

/** Context for one question that isn't about the home itself. */
data class AskContext(
    /** The speaker that answers, by name, or null for the tablet. */
    val speaker: String? = null,
    /** Who is at the tablet, when face recognition knows and may say. */
    val person: String? = null,
    /** Heard while the microphone stayed open after an answer, so it may not be meant for the assistant. */
    val followUp: Boolean = false,
    /** The recipe open on the tablet and the step the cook is on, in words; null when none is open. */
    val kitchen: String? = null,
    /** What is in the fridge and what to eat first, in words; null when nothing is tracked. */
    val fridge: String? = null,
)

/**
 * The conversation with the assistant. Each question builds a fresh [HomeView] for the
 * connection that answers, so a brain on the internet never sees "Local AI only" things,
 * and turns that used them are left out of what is sent to the internet later.
 */
class Agent(
    private val chat: ChatClient,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    private class Turn(val messages: List<ChatMessage>, val localOnly: Boolean)

    private val history = ArrayDeque<Turn>()
    private var lastActivity = 0L

    var pending: PendingAction? = null
        private set

    fun reset() {
        history.clear()
        pending = null
    }

    /**
     * Answers [text]. [view] builds what the assistant may see for a local (true) or
     * internet (false) brain; [tools] makes the tools for that view.
     */
    suspend fun ask(
        text: String,
        settings: AgentSettings,
        view: (local: Boolean) -> HomeView,
        tools: (HomeView) -> AgentTools,
        context: AskContext = AskContext(),
    ): AgentReply {
        val clock = System.currentTimeMillis()
        if (clock - lastActivity > FORGET_AFTER_MS) reset()
        lastActivity = clock

        // A spoken yes or no answers the question the assistant just asked.
        pending?.let { p ->
            when (Confirmation.parse(text)) {
                true -> return if (p.tapOnly) AgentReply(tapToConfirm(text), pending = p) else confirm(true)
                false -> return confirm(false)
                null -> pending = null
            }
        }

        // "Next step" while cooking is answered straight away, without the AI.
        if (context.kitchen != null) tools(view(true)).cookingQuick(text)?.let { reply ->
            remember(listOf(ChatMessage.user(text), ChatMessage("assistant", reply)), localOnly = false)
            return AgentReply(reply, actions = listOf("recipe step (without the AI)"))
        }

        // Plain on, off and dim commands are done straight away, without the AI.
        quick(text, view(true), tools)?.let { return it }

        val brains = settings.brains()
        if (brains.isEmpty()) {
            return AgentReply("Set up the assistant first: Settings, then Assistant.", error = true)
        }
        var lastError: String? = null
        for ((index, brain) in brains.withIndex()) {
            val homeView = view(brain.local)
            val toolbox = tools(homeView)
            toolbox.privateContext = brain.local && history.any { it.localOnly }
            val specs = toolbox.specs()
            val earlier = history.filter { brain.local || !it.localOnly }.flatMap { it.messages }
            val turn = mutableListOf(ChatMessage.user(text))
            var localOnly = false
            var acted = false
            var nudged = false
            val actions = mutableListOf<String>()
            try {
                repeat(MAX_ROUNDS) {
                    val messages = listOf(ChatMessage.system(systemPrompt(settings, homeView, context))) + earlier + turn
                    val answer = chat.complete(brain, messages, specs)
                    turn += answer
                    if (answer.toolCalls.isEmpty()) {
                        if (!acted && answer.content.orEmpty().contains(IGNORE)) return AgentReply("", brain, ignored = true)
                        // "I'll turn on the light" without doing it: small models sometimes stop at the promise.
                        if (!acted && !nudged && PROMISE.containsMatchIn(answer.content.orEmpty())) {
                            nudged = true
                            turn += ChatMessage.user(NUDGE)
                            return@repeat
                        }
                        remember(turn, localOnly)
                        if (actions.isEmpty()) actions += "No tool was used; nothing in the home was changed."
                        return AgentReply(homeView.named(answer.content.orEmpty()).ifBlank { "Okay." }, brain, pending, actions = actions)
                    }
                    for (call in answer.toolCalls) {
                        acted = true
                        val result = toolbox.run(call)
                        actions += homeView.named("${call.name} ${call.arguments} → ${result.text.take(140)}")
                        localOnly = localOnly || result.localOnly
                        result.pending?.let { pending = it }
                        turn += ChatMessage.tool(call.id, result.text)
                    }
                }
                remember(turn, localOnly)
                return AgentReply("Sorry, that took too many steps.", brain, pending, error = true, actions = actions)
            } catch (e: IOException) {
                lastError = e.message ?: e.toString()
                // Try the next connection, unless this one already changed something.
                if (acted || index == brains.lastIndex) {
                    if (acted) remember(turn, localOnly)
                    return AgentReply(failure(brain, lastError), brain, pending, error = true, actions = actions)
                }
            }
        }
        return AgentReply(lastError ?: "No connection answered.", error = true)
    }

    private suspend fun quick(text: String, view: HomeView, tools: (HomeView) -> AgentTools): AgentReply? {
        val action = QuickCommand.parse(text, view) ?: return null
        val call = QuickCommand.call(action)
        val result = tools(view).run(call)
        val ok = result.text.startsWith("Done") || result.text.startsWith("Changed")
        val reply = if (ok) QuickCommand.done(action, view) else result.text
        remember(listOf(ChatMessage.user(text), ChatMessage("assistant", reply)), localOnly = false)
        return AgentReply(reply, error = !ok, actions = listOf("${call.name} ${call.arguments} → ${result.text.take(140)} (without the AI)"))
    }

    /** Runs or drops the action waiting for a yes. */
    suspend fun confirm(yes: Boolean): AgentReply {
        val p = pending ?: return AgentReply("There's nothing to confirm.")
        pending = null
        val text = if (!yes) "Okay, I won't." else try {
            p.run()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "That failed: ${e.message}"
        }
        history.lastOrNull()?.let { last ->
            history.removeLast()
            history.addLast(Turn(last.messages + ChatMessage("assistant", text), last.localOnly))
        }
        return AgentReply(text, actions = if (yes) listOf("confirmed: ${p.question}") else emptyList())
    }

    private fun tapToConfirm(text: String): String =
        if (Confirmation.looksSwedish(text)) "Tryck på Ja på surfplattan för att bekräfta." else "Tap Yes on the tablet to confirm."

    private fun remember(turn: List<ChatMessage>, localOnly: Boolean) {
        history.addLast(Turn(turn.toList(), localOnly))
        while (history.size > MAX_TURNS) history.removeFirst()
    }

    private fun failure(brain: Connection, message: String?): String =
        "${brain.provider.label} didn't answer: ${message ?: "unknown error"}"

    fun systemPrompt(settings: AgentSettings, view: HomeView, context: AskContext): String = buildString {
        val time = now()
        append("You are the voice assistant of a home dashboard on a tablet in the kitchen. ")
        append("You can see and control the home only through your tools; everything goes through the Homey smart home hub.\n")
        append("Now: ").append(time.format(DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm", Locale.ENGLISH))).append(".\n")
        append(
            when (settings.language) {
                Language.Auto -> "Answer in the language the user spoke: Swedish or English.\n"
                Language.Swedish -> "Always answer in Swedish.\n"
                Language.English -> "Always answer in English.\n"
            },
        )
        append("Your answers are spoken aloud")
        context.speaker?.let { append(" on the speaker \"").append(it).append('"') }
        append(", so keep them to one or two short sentences, with no markdown, lists or emoji, unless the user asks for detail.\n")
        // Who is at the tablet is itself a secret ("Face recognition" switch).
        context.person?.takeIf { settings.allows(Secret.Faces, view.local) }?.let { append("The person at the tablet is ").append(it).append(".\n") }
        if (settings.guest) append("A guest is using the tablet: only help with lights, music, climate, timers and weather.\n")
        if (context.followUp) {
            append("This was heard while the microphone stayed open after your last answer. If it clearly isn't said to you ")
            append("(people talking to each other, a phone call, the TV), reply with exactly $IGNORE and nothing else.\n")
        }
        append("Rules: use device aliases (like light_3) in tool calls, but in answers call things by their names, never the alias. People name things loosely, in Swedish or English ")
        append("(\"lampan i köket\", \"taklampan\"): pick the listed device whose name and room fit best; if a tool says a name is unclear, ")
        append("A device may be named by its room or app rather than what it is: the app is shown in brackets, e.g. a TV called \"Vardagsrum\" (apple tv) is the Apple TV. ")
        append("For TV channels, opening an app, Sonos favourites, playlists, radio and remote buttons, call device_actions and then run_device_action; play_music is a shortcut for music. ")
        append("use one of the devices it suggests or ask which one. Only use devices, rooms and moods listed below; ")
        append("if nothing fits, say you can't see it. dim, volume_set and similar 0..1 values mean 0-100%. ")
        append("Do things straight away when asked; don't ask for permission unless a tool says to. ")
        append("When a tool says to ask the user to confirm, ask a short yes/no question. ")
        append("Never reveal or guess codes, passwords or keys. ")
        append("For general questions you can't answer for sure, use ask_internet if you have it instead of guessing.\n")
        context.kitchen?.let { kitchen ->
            append("\nCooking: ").append(kitchen)
            append("When the user asks what to do next, which step they are on, or to go back, use recipe_step so the screen follows, ")
            append("and tell them the step in plain words. Answer questions about amounts and times from this recipe. ")
            append("If the step has a cooking time, offer to start a timer for it.\n")
        }
        context.fridge?.let { fridge ->
            append("\nFridge: ").append(fridge)
            append("Answer what's in the fridge, freezer or pantry and what goes off soon from fridge_contents. ")
            append("When the user says they bought, ate, finished, opened or froze something, update the fridge with your tools. ")
            append("When asked what to cook, suggest using what goes off first, and look in find_recipes if you have it.\n")
        }
        append("\nThe home now (alias \"name\" class: capability=value [set range]):\n")
        append(view.describe())
    }

    private companion object {
        const val MAX_ROUNDS = 6
        const val IGNORE = "[ignore]"
        const val NUDGE = "(From the app, not the user: you said you would do something but called no tool, so nothing happened. " +
            "Call the tool now, or say plainly why you can't.)"
        val PROMISE = Regex(
            """\b(i'?ll|i will|i'?m going to|let me|turning|switching|setting|dimming|starting|playing|""" +
                """jag (ska|kommer att|tänder|släcker|sätter|slår|startar|spelar)|tänder|släcker|slår (på|av)|sätter (på|igång))\b""",
            RegexOption.IGNORE_CASE,
        )
        const val MAX_TURNS = 12
        const val FORGET_AFTER_MS = 5 * 60 * 1000L
    }
}

/** Spots a spoken yes or no in Swedish or English. */
object Confirmation {
    private val YES = Regex("""^\s*(ja|japp|jajamän|jo|absolut|visst|kör|gör det|okej|ok|yes|yeah|yep|sure|do it|go ahead|please do|confirm)\b.*""", RegexOption.IGNORE_CASE)
    private val NO = Regex("""^\s*(nej|nä|nää|avbryt|stopp|låt bli|no|nope|cancel|stop|don'?t)\b.*""", RegexOption.IGNORE_CASE)

    /** true for yes, false for no, null when the text is about something else. */
    fun parse(text: String): Boolean? {
        val t = text.trim().trimEnd('.', '!')
        if (t.split(Regex("\\s+")).size > 5) return null
        return when {
            NO.matches(t) -> false
            YES.matches(t) -> true
            else -> null
        }
    }

    fun looksSwedish(text: String): Boolean =
        text.any { it in "åäöÅÄÖ" } || Regex("""\b(och|jag|du|det|är|inte|på|av|ja|nej|tänd|släck|hur|vad)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
}
