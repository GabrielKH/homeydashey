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
    /** Routine steps that wait until the answer has been spoken, like starting the radio; returns what they did. */
    val then: (suspend () -> List<String>)? = null,
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
    /** The dashboard's weather card in a few lines (now and the next days); null before it has loaded. */
    val weather: String? = null,
    /** A routine the user started, with what its steps did and found, in words; see [RoutineText]. */
    val routine: String? = null,
    /** The routine read something only a local brain may see. */
    val routineLocalOnly: Boolean = false,
)

/**
 * The conversation with the assistant. Each question builds a fresh [HomeView] for the
 * connection that answers, so a brain on the internet never sees "Local AI only" things,
 * and turns that used them are left out of what is sent to the internet later.
 */
class Agent(
    private val chat: ChatClient,
    /** The owner's routines and standing instructions. */
    private val routines: () -> RoutineData = { RoutineData() },
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    private class Turn(val messages: List<ChatMessage>, val localOnly: Boolean)

    private val history = ArrayDeque<Turn>()
    private var lastActivity = 0L

    var pending: PendingAction? = null
        private set

    /** Hears each step while it works and everything said and done, for the screen and the logs. */
    var listener: AgentListener? = null

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
        val heard = Watch(listener)
        heard.trace("question", text)
        val reply = answer(text, settings, view, heard.tools(tools), context, heard)
        heard.trace(
            when {
                reply.ignored -> "ignored"
                reply.error -> "error"
                else -> "answer"
            },
            reply.text, heard.local,
        )
        return reply
    }

    private suspend fun answer(
        text: String,
        settings: AgentSettings,
        view: (local: Boolean) -> HomeView,
        tools: (HomeView) -> AgentTools,
        context: AskContext,
        heard: Watch,
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

        // "God morgon" and the owner's other phrases run their routine.
        if (context.routine == null) Routines.match(routines().routines, text, settings.wakeName)?.let { routine ->
            return runRoutine(routine, text, settings, view, tools, context)
        }

        // "Next step" while cooking is answered straight away, without the AI.
        if (context.routine == null && context.kitchen != null) tools(view(true)).cookingQuick(text)?.let { reply ->
            remember(listOf(ChatMessage.user(text), ChatMessage("assistant", reply)), localOnly = false)
            return AgentReply(reply, actions = listOf("recipe step (without the AI)"))
        }

        // Plain on, off and dim commands are done straight away, without the AI.
        if (context.routine == null) quick(text, view(true), tools)?.let { return it }

        // What a routine read for a local brain never goes to the internet one.
        val brains = settings.brains().filter { it.local || !context.routineLocalOnly }
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
            // A tool actually changed something; a failed or wrong call doesn't count.
            var changed = false
            var nudged = false
            val actions = mutableListOf<String>()
            // What each call did this turn, so the same change asked for twice isn't done twice.
            val done = mutableMapOf<String, ToolResult>()
            heard.trace("brain", "${brain.provider.label} ${brain.model}" + if (brain.local) " (local)" else " (internet)")
            try {
                repeat(MAX_ROUNDS) {
                    val messages = conversation(settings, homeView, context, earlier, turn)
                    val step = StepIds.next()
                    val thinking = if (brain.local) "Thinking" else "Thinking (${brain.provider.label})"
                    heard.step(AgentStep(step, thinking, StepState.Running, thinking = true))
                    val answer = try {
                        chat.complete(brain, messages, specs)
                    } catch (e: IOException) {
                        heard.step(AgentStep(step, thinking, StepState.Failed, e.message, thinking = true))
                        throw e
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        heard.step(AgentStep(step, thinking, StepState.Failed, "Stopped", thinking = true))
                        throw e
                    }
                    heard.step(AgentStep(step, thinking, StepState.Done, thinking = true))
                    val keepLocal = localOnly || toolbox.privateContext
                    answer.content?.takeIf { it.isNotBlank() }?.let { heard.trace("model", it, keepLocal) }
                    if (answer.toolCalls.isNotEmpty()) heard.trace("model calls", answer.toolCalls.joinToString("; ") { "${it.name} ${it.arguments}" }, keepLocal)
                    turn += answer
                    if (answer.toolCalls.isEmpty()) {
                        if (!acted && answer.content.orEmpty().contains(IGNORE)) return AgentReply("", brain, ignored = true)
                        // "I'll turn on the light", or "the light is on" without doing it: small models
                        // sometimes stop at the promise, or claim it's done.
                        val said = answer.content.orEmpty()
                        val command = COMMAND.containsMatchIn(text) && !STATUS_QUESTION.containsMatchIn(text)
                        val claims = PROMISE.containsMatchIn(said) || (command && CLAIM.containsMatchIn(said))
                        if (!changed && !nudged && claims) {
                            nudged = true
                            heard.trace("guard", if (acted) "It said it did something a tool didn't do; asked it to try again." else "It promised or claimed without calling a tool; asked it to do it.")
                            turn += ChatMessage.user(if (acted) NUDGE_FAILED else NUDGE)
                            return@repeat
                        }
                        // Still nothing done after the nudge: never let it say it did something.
                        if (!changed && nudged && command && CLAIM.containsMatchIn(said)) {
                            heard.trace("guard", "Still claimed a change after the nudge; told the user nothing was done.", keepLocal)
                            remember(turn.take(1) + ChatMessage("assistant", nothingDone(text)), localOnly)
                            actions += "The AI said it changed something, but no tool changed anything."
                            return AgentReply(nothingDone(text), brain, actions = actions, error = true)
                        }
                        remember(turn, localOnly)
                        if (actions.isEmpty()) actions += "No tool was used; nothing in the home was changed."
                        return AgentReply(homeView.named(answer.content.orEmpty()).ifBlank { "Okay." }, brain, pending, actions = actions)
                    }
                    for (call in answer.toolCalls) {
                        acted = true
                        val key = "${call.name} ${call.arguments}"
                        val before = done[key]?.takeIf { call.name !in LOOKS && !StepText.failed(it.text) }
                        val result = if (before != null) {
                            heard.trace("guard", "Asked for $key again; not done twice.")
                            ToolResult("Already done a moment ago: ${before.text} Don't do it again; answer the user.", before.localOnly)
                        } else {
                            toolbox.run(call).also { done[key] = it }
                        }
                        changed = changed || when (call.name) {
                            in LOOKS -> false
                            in SETS -> CHANGED.any { result.text.startsWith(it) } && !result.text.contains(QuickCommand.NOT_CHANGED_PREFIX)
                            else -> true // Timers, pages, recipes and the fridge answer in their own words.
                        }
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
        val stuck = result.text.contains(QuickCommand.NOT_CHANGED_PREFIX)
        val ok = (result.text.startsWith("Done") || result.text.startsWith("Changed")) && !stuck
        val reply = when {
            ok -> QuickCommand.done(action, view)
            stuck || result.text.startsWith(QuickCommand.UNREACHABLE_PREFIX) ->
                QuickCommand.notChanged(action, view)
            else -> result.text
        }
        remember(listOf(ChatMessage.user(text), ChatMessage("assistant", reply)), localOnly = false)
        return AgentReply(reply, error = !ok, actions = listOf("${call.name} ${call.arguments} → ${result.text.take(140)} (without the AI)"))
    }

    /**
     * Runs [routine] for [text] (what the user said, or its name when started by hand): the steps
     * up to the answer, then the AI answers following the routine's instructions, and the
     * remaining steps run once the answer has been spoken ([AgentReply.then]). Without an AI
     * the Say steps and what was read are spoken as they are.
     */
    suspend fun runRoutine(
        routine: Routine,
        text: String,
        settings: AgentSettings,
        view: (local: Boolean) -> HomeView,
        tools: (HomeView) -> AgentTools,
        context: AskContext = AskContext(),
    ): AgentReply {
        lastActivity = System.currentTimeMillis()
        pending = null
        val heard = Watch(listener)
        heard.trace("routine", "${routine.name} (heard: $text)")
        @Suppress("NAME_SHADOWING") val tools = heard.tools(tools)
        val brain = settings.brains().firstOrNull()
        val homeView = view(brain?.local ?: true)
        val toolbox = tools(homeView)
        val player = Routines.defaultPlayer(homeView)
        val date = now().toLocalDate()
        val run = RoutineRunner(toolbox, homeView, date, player).run(routine.beforeAnswer)
        val later = routine.afterAnswer
        val then: (suspend () -> List<String>)? = if (later.isEmpty()) null else {
            { RoutineRunner(tools(view(brain?.local ?: true)), homeView, date, player).run(later).actions }
        }
        val swedish = when (settings.language) {
            Language.Swedish -> true
            Language.English -> false
            Language.Auto -> Confirmation.looksSwedish(text) || Routines.normalize(text) == Routines.normalize(routine.name)
        }
        val plain = RoutineText.plain(run, swedish)
        val actions = listOf("routine ${routine.name}") + run.actions
        if (brain == null || (routine.instructions.isBlank() && run.info.isEmpty())) {
            remember(listOf(ChatMessage.user(text), ChatMessage("assistant", plain)), run.localOnly)
            heard.trace("answer", plain, run.localOnly)
            return AgentReply(plain, actions = actions, then = then)
        }
        val answer = ask(
            text, settings, view, tools,
            context.copy(routine = RoutineText.forBrain(routine, run, later), routineLocalOnly = run.localOnly, followUp = false),
        )
        if (answer.error) {
            // The steps still ran: say what they found, and show what went wrong with the AI.
            return AgentReply(plain, answer.brain, actions = actions + answer.actions + "The AI didn't answer: ${answer.text}", then = then)
        }
        val spoken = (run.said + answer.text).filter { it.isNotBlank() }.joinToString(" ")
        return answer.copy(text = spoken, actions = actions + answer.actions, then = then)
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

    private fun nothingDone(text: String): String =
        if (Confirmation.looksSwedish(text)) "Förlåt, jag fick inte till det, så inget ändrades. Säg gärna vilken lampa eller enhet du menar."
        else "Sorry, I didn't manage that, so nothing was changed. Tell me which light or device you mean."

    private fun tapToConfirm(text: String): String =
        if (Confirmation.looksSwedish(text)) "Tryck på Ja på surfplattan för att bekräfta." else "Tap Yes on the tablet to confirm."

    private fun remember(turn: List<ChatMessage>, localOnly: Boolean) {
        history.addLast(Turn(turn.toList(), localOnly))
        while (history.size > MAX_TURNS) history.removeFirst()
    }

    private fun failure(brain: Connection, message: String?): String =
        "${brain.provider.label} didn't answer: ${message ?: "unknown error"}"

    /**
     * What is sent: the rules first, then the earlier turns, then the home as it is now
     * together with the question. The rules never change between questions, so a local model
     * keeps them worked through from last time and only reads what is new (see [warm]).
     */
    private fun conversation(
        settings: AgentSettings,
        view: HomeView,
        context: AskContext,
        earlier: List<ChatMessage>,
        turn: List<ChatMessage>,
    ): List<ChatMessage> {
        val question = turn.first()
        val now = ChatMessage.user(situation(settings, view, context) + QUESTION + question.content.orEmpty())
        return listOf(ChatMessage.system(rules(settings))) + earlier + now + turn.drop(1)
    }

    /**
     * Works through the rules and the home ahead of the question, while it is still being
     * said, so a local model only has the question itself left to read when it arrives.
     */
    suspend fun warm(
        settings: AgentSettings,
        view: (local: Boolean) -> HomeView,
        tools: (HomeView) -> AgentTools,
        context: AskContext = AskContext(),
    ) {
        val brain = settings.brains().firstOrNull() ?: return
        if (brain.provider != Provider.Ollama) return
        val homeView = view(brain.local)
        val toolbox = tools(homeView)
        val fresh = System.currentTimeMillis() - lastActivity <= FORGET_AFTER_MS
        toolbox.privateContext = brain.local && fresh && history.any { it.localOnly }
        val earlier = if (fresh) history.filter { brain.local || !it.localOnly }.flatMap { it.messages } else emptyList()
        val messages = listOf(ChatMessage.system(rules(settings))) + earlier +
            ChatMessage.user(situation(settings, homeView, context) + QUESTION)
        chat.prefill(brain, messages, toolbox.specs())
    }

    /** Everything the assistant is told for a question, as one text (for tests and the settings preview). */
    fun systemPrompt(settings: AgentSettings, view: HomeView, context: AskContext): String =
        rules(settings) + "\n\n" + situation(settings, view, context)

    /** How to behave: the same for every question, so it can be kept worked through. */
    fun rules(settings: AgentSettings): String = buildString {
        append("You are the voice assistant of a home dashboard on a tablet in the kitchen. ")
        append("You can see and control the home only through your tools; everything goes through the Homey smart home hub.\n")
        append(
            when (settings.language) {
                Language.Auto -> "Answer in the language the user spoke: Swedish or English.\n"
                Language.Swedish -> "Always answer in Swedish.\n"
                Language.English -> "Always answer in English.\n"
            },
        )
        append("Your answers are spoken aloud, so keep them to one or two short sentences, with no markdown, lists or emoji, unless the user asks for detail.\n")
        if (settings.guest) append("A guest is using the tablet: only help with lights, music, climate, timers and weather.\n")
        append("Each question comes after a note from the app with the home as it is now; only the part after \"$QUESTION_LABEL\" is what the user said. ")
        append("If the note says it was heard while the microphone stayed open after your last answer and it clearly isn't said to you ")
        append("(people talking to each other, a phone call, the TV), reply with exactly $IGNORE and nothing else.\n")
        append("Rules: use device aliases (like light_3) in tool calls, but in answers call things by their names, never the alias. People name things loosely, in Swedish or English ")
        append("(\"lampan i köket\", \"taklampan\"): pick the listed device whose name and room fit best; if a tool says a name is unclear, ")
        append("use one of the devices it suggests or ask which one. ")
        append("A device may be named by its room or app rather than what it is: the app is shown in brackets, e.g. a TV called \"Vardagsrum\" (apple tv) is the Apple TV. ")
        append("For TV channels, opening an app, Sonos favourites, playlists, radio and remote buttons, call device_actions and then run_device_action; play_music is a shortcut for music. ")
        append("Only use devices, rooms and moods listed in the note; ")
        append("if nothing fits, say you can't see it. dim, volume_set and similar 0..1 values mean 0-100%. ")
        append("Do things straight away when asked; don't ask for permission unless a tool says to. ")
        append("When a tool says to ask the user to confirm, ask a short yes/no question. ")
        append("Never reveal or guess codes, passwords or keys. ")
        append("For general questions you can't answer for sure, use ask_internet if you have it instead of guessing.\n")
        append("Everything the dashboard shows is yours to use: answer the weather from the weather in the note (or the weather tool), never say you can't see it. ")
        append("When the user lets you choose (\"play something\", \"välj själv\"), choose yourself instead of asking: for music call play_music with an empty query. ")
        append("Remember what the user already told you in this conversation and never ask the same question twice. ")
        append("Call each tool once per thing to do; when a tool says it's done, don't call it again, just answer.\n")
        append("For a TV or media box (Apple TV and the like) use press_remote to press its buttons (\"down x3, ok\"), run_device_action for channels and apps, ")
        append("and open_device to show its remote on the tablet.\n")
        append("When a recipe is open and the user asks what to do next, which step they are on, or to go back, use recipe_step so the screen follows, ")
        append("and tell them the step in plain words. Answer questions about amounts and times from that recipe. ")
        append("If the step has a cooking time, offer to start a timer for it. When the user wants timers for the whole recipe (\"timers for everything\", \"sätt alla timers\"), ")
        append("call recipe_timers once instead of going through the steps.\n")
        append("Answer what's in the fridge, freezer or pantry and what goes off soon from fridge_contents. ")
        append("When the user says they bought, ate, finished, opened or froze something, update the fridge with your tools. ")
        append("When asked what to cook, suggest using what goes off first, and look in find_recipes if you have it.\n")
        append("The owner has routines: when the note says a routine was started, the app has already done its steps; ")
        append("follow the routine's instructions, using your tools for anything they ask that isn't done yet, and answer from what it found. ")
        append("When the user asks you to make or change a routine (\"when I say X, do Y\") or to always do something, save it with your tools.\n")
        val standing = routines().instructions.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (standing.isNotEmpty()) {
            append("The owner's standing instructions; follow them unless a rule above forbids it:\n")
            standing.forEach { append("- ").append(it).append('\n') }
        }
    }

    /**
     * The home and the moment, for one question. The home comes first and the clock last,
     * so the part that stays the same from one question to the next is as long as it can be.
     */
    fun situation(settings: AgentSettings, view: HomeView, context: AskContext): String = buildString {
        append("(From the app, not the user.)\nThe home now (alias \"name\" class: capability=value [set range]):\n")
        append(view.describe())
        context.kitchen?.let { append("\nCooking: ").append(it) }
        context.fridge?.let { append("\nFridge: ").append(it) }
        context.weather?.let { append("\nWeather (the dashboard's weather card):\n").append(it) }
        append("\nNow: ").append(now().format(DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm", Locale.ENGLISH))).append(".")
        context.speaker?.let { append("\nAnswers are played on the speaker \"").append(it).append("\".") }
        // Who is at the tablet is itself a secret ("Face recognition" switch).
        context.person?.takeIf { settings.allows(Secret.Faces, view.local) }?.let { append("\nThe person at the tablet is ").append(it).append(".") }
        context.routine?.let { append("\n").append(it) }
        if (context.followUp) append("\nHeard while the microphone stayed open after your last answer, so it may not be meant for you.")
    }

    private companion object {
        const val MAX_ROUNDS = 6
        const val IGNORE = "[ignore]"
        const val QUESTION_LABEL = "The user said:"
        const val QUESTION = "\n\n$QUESTION_LABEL "
        // Word edges that count å, ä and ö as letters, which \b doesn't. Not (?U): Android's
        // regex engine refuses it, and the app crashed on start.
        private const val W = """(?<![\p{L}\d_])"""
        private const val E = """(?![\p{L}\d_])"""
        /** Tools that only look. */
        val LOOKS = setOf("get_device", "device_actions", "list_flows", "weather", "calendar", "who_is_home", "variables", "ask_internet", "list_routines")
        /** Tools that change the home, and say how it went in a way [CHANGED] can read. */
        val SETS = setOf("set_device", "set_room", "run_flow", "play_music", "run_device_action", "press_remote")
        /** How tool results that changed something begin. */
        val CHANGED = listOf("Done", "Changed", "Started", "Playing")
        const val NUDGE_FAILED = "(From the app, not the user: none of your tool calls changed anything; read their results. " +
            "Call the right tool now, or tell the user plainly what went wrong.)"
        const val NUDGE = "(From the app, not the user: you said you would do something but called no tool, so nothing happened. " +
            "Call the tool now, or say plainly why you can't.)"
        val PROMISE = Regex(
            """$W(i'?ll|i will|i'?m going to|let me|turning|switching|setting|dimming|starting|playing|""" +
                """jag (ska|kommer att|tänder|släcker|sätter|slår|startar|spelar)|tänder|släcker|slår (på|av)|sätter (på|igång))$E""",
            RegexOption.IGNORE_CASE,
        )
        /** Saying something was changed: "Taklampan är tänd", "I've turned it off", "Done". */
        val CLAIM = Regex(
            """$W(i'?ve|i have|has been|have been|is now|are now|now (on|off)|turned (on|off)|switched (on|off)|done|""" +
                """har (tänt|släckt|satt|slagit|startat|stängt|dimmat|ändrat)|är (nu )?(tänd|tända|släckt|släckta|avstängd|avstängda|igång)|""" +
                """tänt|släckt|tände|släckte|slog (på|av)|satte på|startade|stängde av|dimmade|klart|fixat)$E""",
            RegexOption.IGNORE_CASE,
        )
        /** The user asked for a change, rather than how things are. */
        val COMMAND = Regex(
            """$W(tänd|tända|släck|släcka|sätt|sätta|slå|stäng|stänga|starta|dimma|dimra|höj|sänk|öppna|turn|switch|put|set|start|dim|raise|lower|open)$E""",
            RegexOption.IGNORE_CASE,
        )
        val STATUS_QUESTION = Regex("""^\s*(är|is|are|vad|what|vilka|which|hur|how|varför|why)$E""", RegexOption.IGNORE_CASE)
        const val MAX_TURNS = 12
        // Long enough to remember answers across a stretch of cooking between questions.
        const val FORGET_AFTER_MS = 20 * 60 * 1000L
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
