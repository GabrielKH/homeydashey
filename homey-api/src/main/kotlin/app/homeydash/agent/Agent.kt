package app.homeydash.agent

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
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
    /** The whole answer was already handed to the voice while it was written (see [Agent.ask]'s speech). */
    val spoken: Boolean = false,
    /** How fast each round of the AI came, in order; empty when it answered without the AI. */
    val rounds: List<ChatStats> = emptyList(),
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
    /** The tablet's own battery, like "14 %, not charging"; null when unknown. */
    val tabletBattery: String? = null,
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
    /** The long memory, per person and for the house, kept out of the prompt; null remembers nothing. */
    private val memory: MemoryBook? = null,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    /**
     * One question and everything said after it. [noted] is what its note said was on, for a
     * local (true) or internet (false) brain, so the next note only says what changed.
     */
    private class Turn(val messages: List<ChatMessage>, val localOnly: Boolean, val noted: Noted? = null, val who: Asker? = null, val guest: Boolean = false)

    /** What a note said: each device's line by alias, and the weather. [full] when it said all of it. */
    class Noted(val local: Boolean, val states: Map<String, String>, val weather: String?, val full: Boolean)

    /** The note of the question being answered, until its turn is remembered. */
    private var opened: Pair<ChatMessage, Noted>? = null

    private val history = ArrayDeque<Turn>()
    private var lastActivity = 0L

    /** Conversations that are over, waiting to be squeezed into a few facts ([digest]). */
    private val finished = ArrayDeque<Turn>()

    /** Who asks the question being answered, when the tablet knows them. */
    private var asking: Asker? = null
    private var guestAsking = false

    /** Whether a [local] or internet brain may use the memory for these settings. */
    private fun remembers(settings: AgentSettings, local: Boolean) =
        memory != null && !settings.guest && settings.allows(Secret.Memory, local)

    var pending: PendingAction? = null
        private set

    /** Hears each step while it works and everything said and done, for the screen and the logs. */
    var listener: AgentListener? = null

    fun reset() {
        if (memory != null) {
            finished.addAll(history)
            while (finished.size > MAX_FINISHED) finished.removeFirst()
        }
        history.clear()
        pending = null
    }

    /**
     * Answers [text]. [view] builds what the assistant may see for a local (true) or
     * internet (false) brain; [tools] makes the tools for that view. With [speech], the answer
     * is handed to it sentence by sentence while the AI is still writing, so it can be spoken
     * straight away; [AgentReply.spoken] then says it all went that way.
     */
    suspend fun ask(
        text: String,
        settings: AgentSettings,
        view: (local: Boolean) -> HomeView,
        tools: (HomeView) -> AgentTools,
        context: AskContext = AskContext(),
        speech: ((String) -> Unit)? = null,
    ): AgentReply {
        asking = settings.asker
        guestAsking = settings.guest
        val heard = Watch(listener)
        heard.trace("question", text)
        val reply = answer(text, settings, view, heard.tools(tools), context, heard, speech).copy(rounds = heard.rounds.toList())
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
        speech: ((String) -> Unit)?,
    ): AgentReply {
        val clock = System.currentTimeMillis()
        if (clock - lastActivity > FORGET_AFTER_MS) reset()
        lastActivity = clock

        // A spoken yes or no answers the question the assistant just asked.
        pending?.let { p ->
            when (Confirmation.parse(text)) {
                true -> return if (p.tapOnly) AgentReply(tapToConfirm(text, settings), pending = p) else confirm(true)
                false -> return confirm(false)
                null -> pending = null
            }
        }

        // "God morgon" and the owner's other phrases run their routine.
        if (context.routine == null) Routines.match(routines().routines, text, settings.wakeName)?.let { routine ->
            return runRoutine(routine, text, settings, view, tools, context)
        }

        // "Next step" while cooking is answered straight away, without the AI.
        if (context.routine == null && context.kitchen != null) tools(view(true)).cookingQuick(text, answersSwedish(settings))?.let { reply ->
            remember(listOf(ChatMessage.user(text), ChatMessage("assistant", reply)), localOnly = false)
            return AgentReply(reply, actions = listOf("recipe step (without the AI)"))
        }

        // Plain on, off and dim commands are done straight away, without the AI.
        if (context.routine == null) quick(text, view(true), tools, settings)?.let { return it }

        // What a routine read for a local brain never goes to the internet one.
        val brains = settings.brains().filter { it.local || !context.routineLocalOnly }
        if (brains.isEmpty()) {
            return AgentReply("Set up the assistant first: Settings, then Assistant.", error = true)
        }
        var lastError: String? = null
        // A request to change something isn't answered out loud before a tool has done it, so
        // Nova never says "the light is on" for a change that then fails.
        val command = COMMAND.containsMatchIn(text) && !STATUS_QUESTION.containsMatchIn(text)
        // Routines put their own sentences before the answer, so they're spoken whole.
        val voice = speech?.takeIf { context.routine == null }
        for ((index, brain) in brains.withIndex()) {
            val homeView = view(brain.local)
            val toolbox = tools(homeView)
            toolbox.privateContext = brain.local && history.any { it.localOnly }
            val specs = toolbox.specs()
            val compact = brain.local && settings.compactTools
            val earlier = history.filter { brain.local || !it.localOnly }.flatMap { it.messages }
            val tools = Tools(specs, compact, text, earlier, context)
            val turn = mutableListOf(opening(settings, homeView, context, text, tools, since(brain.local)))
            var localOnly = false
            var acted = false
            // A tool actually changed something; a failed or wrong call doesn't count.
            var changed = false
            var nudged = false
            var emptied = false
            var reasked = false
            // Calls it asked for again after they were done: twice means it's going round in circles.
            var repeats = 0
            val actions = mutableListOf<String>()
            // What each call did this turn, so the same change asked for twice isn't done twice.
            val done = mutableMapOf<String, ToolResult>()
            val out = voice?.let { SpeechStream(it, homeView::named) }
            heard.trace("brain", "${brain.provider.label} ${brain.model}" + if (brain.local) " (local)" else " (internet)")
            try {
                repeat(MAX_ROUNDS) {
                    val messages = conversation(settings, homeView, earlier, turn, tools)
                    val step = StepIds.next()
                    val thinking = if (brain.local) "Thinking" else "Thinking (${brain.provider.label})"
                    heard.step(AgentStep(step, thinking, StepState.Running, thinking = true))
                    out?.next()
                    val live = out?.takeIf { !command || changed }
                    // Always streamed, also when nothing is spoken yet, so the first word can be timed.
                    val answer = try {
                        chat.stream(brain, messages, tools.sent, requireTool = nudged && !acted, onText = { piece: String -> live?.feed(piece) })
                    } catch (e: IOException) {
                        heard.step(AgentStep(step, thinking, StepState.Failed, e.message, thinking = true))
                        throw e
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        heard.step(AgentStep(step, thinking, StepState.Failed, "Stopped", thinking = true))
                        throw e
                    }
                    heard.step(AgentStep(step, thinking, StepState.Done, thinking = true))
                    answer.stats?.let { st ->
                        heard.rounds += st
                        heard.trace("speed", "round ${heard.rounds.size}: ${st.text()}")
                    }
                    val keepLocal = localOnly || toolbox.privateContext
                    answer.content?.takeIf { it.isNotBlank() }?.let { heard.trace("model", it, keepLocal) }
                    if (answer.toolCalls.isEmpty()) heard.trace("model stopped", answer.finish ?: "no reason given", keepLocal)
                    if (answer.toolCalls.isNotEmpty()) heard.trace("model calls", answer.toolCalls.joinToString("; ") { "${it.name} ${it.arguments}" }, keepLocal)
                    turn += answer
                    if (answer.toolCalls.isEmpty()) {
                        if (!acted && answer.content.orEmpty().contains(IGNORE)) {
                            // Small models ignore far too much ("Säg något roligt" after an answer): what is
                            // plainly said to the assistant gets answered, once asked again.
                            if (!reasked && Addressed.matches(text, settings.wakeName)) {
                                reasked = true
                                heard.trace("guard", "It ignored something said to it; asked it to answer.")
                                turn += ChatMessage.user(NUDGE_ADDRESSED)
                                return@repeat
                            }
                            return AgentReply("", brain, ignored = true)
                        }
                        // An empty answer: the server hid what the model wrote (vMLX drops tool tags
                        // when no tool schemas were sent), or it wrote nothing. Ask once, then say so.
                        if (answer.content.isNullOrBlank()) {
                            if (!emptied && !changed) {
                                emptied = true
                                heard.trace("guard", "The answer came back empty; asked it again.")
                                turn += ChatMessage.user(if (compact) NUDGE_EMPTY_WRITTEN else NUDGE_EMPTY)
                                return@repeat
                            }
                            heard.trace("guard", "Empty again; told the user nothing came back.", keepLocal)
                            if (actions.isEmpty()) actions += "The AI's answer came back empty; nothing in the home was changed."
                            return AgentReply(noAnswer(text, changed, settings), brain, pending, actions = actions, error = !changed)
                        }
                        // "I'll turn on the light", or "the light is on" without doing it: small models
                        // sometimes stop at the promise, or claim it's done.
                        val said = answer.content.orEmpty()
                        val claims = PROMISE.containsMatchIn(said) || (command && (CLAIM.containsMatchIn(said) || ECHO.containsMatchIn(said)))
                        if (!changed && !nudged && claims) {
                            nudged = true
                            heard.trace("guard", if (acted) "It said it did something a tool didn't do; asked it to try again." else "It promised or claimed without calling a tool; asked it to do it.")
                            turn += ChatMessage.user(if (acted) NUDGE_FAILED else NUDGE)
                            return@repeat
                        }
                        // Still nothing done after the nudge: never let it say it did something.
                        if (!changed && nudged && command && CLAIM.containsMatchIn(said)) {
                            heard.trace("guard", "Still claimed a change after the nudge; told the user nothing was done.", keepLocal)
                            remember(turn.take(1) + ChatMessage("assistant", nothingDone(text, settings)), localOnly)
                            actions += "The AI said it changed something, but no tool changed anything."
                            return AgentReply(nothingDone(text, settings), brain, actions = actions, error = true)
                        }
                        remember(turn, localOnly)
                        if (actions.isEmpty()) actions += "No tool was used; nothing in the home was changed."
                        live?.finish()
                        return AgentReply(homeView.named(answer.content.orEmpty()).ifBlank { "Okay." }, brain, pending, actions = actions, spoken = live?.spokeThisRound == true)
                    }
                    for (call in answer.toolCalls) {
                        acted = true
                        val key = "${call.name} ${call.arguments}"
                        val before = done[key]?.takeIf { call.name !in LOOKS }
                        val result = if (before != null && StepText.failed(before.text)) {
                            // The same failing call over and over only made the user wait (P1 radio: four times).
                            heard.trace("guard", "Asked for $key again after it failed; not tried again.")
                            ToolResult("That exact call just failed: ${before.text} Don't try it again; try another way or tell the user what went wrong.", before.localOnly)
                        } else if (before != null) {
                            repeats++
                            heard.trace("guard", "Asked for $key again; not done twice.")
                            ToolResult("Already done a moment ago: ${before.text} Don't do it again; answer the user.", before.localOnly)
                        } else {
                            toolbox.run(call).also { done[key] = it }
                        }
                        val did = when (call.name) {
                            in LOOKS, in MEMORY_TOOLS -> false
                            in SETS -> CHANGED.any { result.text.startsWith(it) } && !result.text.contains(QuickCommand.NOT_CHANGED_PREFIX)
                            else -> true // Timers, pages, recipes and the fridge answer in their own words.
                        }
                        changed = changed || did
                        if (did && before == null && call.name in SETS) learnHabit(call, homeView, settings)
                        actions += homeView.named("${call.name} ${call.arguments} → ${result.text.take(140)}")
                        localOnly = localOnly || result.localOnly
                        result.pending?.let { pending = it }
                        turn += ChatMessage.tool(call.id, result.text)
                    }
                    if (repeats >= MAX_REPEATS) {
                        heard.trace("guard", "Kept asking for calls already done; stopped.")
                        remember(turn, localOnly)
                        return AgentReply("Sorry, I got stuck going round in circles.", brain, pending, error = true, actions = actions)
                    }
                }
                remember(turn, localOnly)
                return AgentReply("Sorry, that took too many steps.", brain, pending, error = true, actions = actions)
            } catch (e: IOException) {
                lastError = e.message ?: e.toString()
                // Try the next connection, unless this one already changed something or started talking.
                if (acted || out?.started == true || index == brains.lastIndex) {
                    if (acted) remember(turn, localOnly)
                    return AgentReply(failure(brain, lastError), brain, pending, error = true, actions = actions)
                }
            }
        }
        return AgentReply(lastError ?: "No connection answered.", error = true)
    }

    private suspend fun quick(text: String, view: HomeView, tools: (HomeView) -> AgentTools, settings: AgentSettings): AgentReply? {
        val swedish = answersSwedish(settings)
        // Said in the answer language when one is set, so TARS never reads out Swedish in his English voice.
        val action = QuickCommand.parse(text, view, settings.asker?.name)?.let { a -> swedish?.let { a.copy(swedish = it) } ?: a } ?: return null
        val call = QuickCommand.call(action)
        val result = tools(view).run(call)
        val stuck = result.text.contains(QuickCommand.NOT_CHANGED_PREFIX)
        val ok = (result.text.startsWith("Done") || result.text.startsWith("Changed")) && !stuck
        if (ok) learnHabit(call, view, settings)
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
        asking = settings.asker
        guestAsking = settings.guest
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
        val swedish = when (settings.answers) {
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
            history.addLast(Turn(last.messages + ChatMessage("assistant", text), last.localOnly, last.noted, last.who, last.guest))
        }
        return AgentReply(text, actions = if (yes) listOf("confirmed: ${p.question}") else emptyList())
    }

    /** Whether a fixed answer to [text] is in Swedish; TARS answers in English whatever was said. */
    /** Whether fixed answers must be Swedish (true) or English (false); null when they follow what was said. */
    private fun answersSwedish(settings: AgentSettings): Boolean? = when (settings.answers) {
        Language.Swedish -> true
        Language.English -> false
        Language.Auto -> null
    }

    private fun swedish(text: String, settings: AgentSettings): Boolean = when (settings.answers) {
        Language.Swedish -> true
        Language.English -> false
        Language.Auto -> Confirmation.looksSwedish(text)
    }

    private fun nothingDone(text: String, settings: AgentSettings): String =
        if (swedish(text, settings)) "Förlåt, jag fick inte till det, så inget ändrades. Säg gärna vilken lampa eller enhet du menar."
        else "Sorry, I didn't manage that, so nothing was changed. Tell me which light or device you mean."

    private fun noAnswer(text: String, changed: Boolean, settings: AgentSettings): String = when {
        changed && swedish(text, settings) -> "Klart."
        changed -> "Done."
        swedish(text, settings) -> "Förlåt, jag fick inget svar från AI:n, så inget ändrades. Försök igen."
        else -> "Sorry, the AI gave me no answer, so nothing was changed. Try again."
    }

    private fun tapToConfirm(text: String, settings: AgentSettings): String =
        if (swedish(text, settings)) "Tryck på Ja på surfplattan för att bekräfta." else "Tap Yes on the tablet to confirm."

    private fun remember(turn: List<ChatMessage>, localOnly: Boolean) {
        val noted = opened?.takeIf { it.first === turn.firstOrNull() }?.second
        history.addLast(Turn(turn.toList(), localOnly, noted, asking, guestAsking))
        while (history.size > MAX_TURNS) history.removeFirst()
    }

    /**
     * The last note a [local] brain saw, when the conversation still has a note that said
     * everything, so a new note can say only what changed since. Null starts afresh.
     */
    private fun since(local: Boolean): Noted? {
        val seen = history.filter { local || !it.localOnly }.mapNotNull { t -> t.noted?.takeIf { it.local == local } }
        return seen.lastOrNull()?.takeIf { seen.any { it.full } }
    }

    private fun failure(brain: Connection, message: String?): String =
        "${brain.provider.label} didn't answer: ${message ?: "unknown error"}"

    /**
     * What is sent: the rules and the list of devices first, then the earlier turns, then this
     * question's turn, which starts with its [opening]. The start only changes when the devices
     * do, and earlier turns are sent exactly as they were the first time, notes included, so
     * each request begins with the whole of the last one: a local model only reads what is new
     * (see [warm]). A model like Qwen3.5 can only pick up where an earlier request ended, so a
     * change anywhere before that (an earlier question sent without its note) made it read
     * everything after the rules again.
     */
    private fun conversation(
        settings: AgentSettings,
        view: HomeView,
        earlier: List<ChatMessage>,
        turn: List<ChatMessage>,
        tools: Tools,
    ): List<ChatMessage> {
        val start = listOf(ChatMessage.system(fixed(settings, view, tools.core)))
        // Written tools: one fixed user line after the rules, the same one the warm-up sends (see ToolText.written).
        return if (tools.compact) ToolText.written(start + ChatMessage.user(ToolText.START) + earlier + turn) else start + earlier + turn
    }

    /**
     * The question with a short note before it: what is on right now, and any tools only this
     * question needs. After the first question the note only says what changed since [since]:
     * the earlier notes are still in the conversation, and the full list cost a local model
     * about five seconds of reading every time.
     */
    private fun opening(settings: AgentSettings, view: HomeView, context: AskContext, question: String, tools: Tools, since: Noted? = null): ChatMessage {
        val extra = if (tools.extra.isEmpty()) "" else "\nMore tools for this question:\n" + ToolText.lines(tools.extra)
        val states = view.stateLines()
        val message = ChatMessage.user(situation(settings, view, context, since) + extra + remembered(settings, view, question) + QUESTION + question)
        opened = message to Noted(view.local, states, context.weather, full = since == null)
        return message
    }

    /**
     * What the memory holds about [question], only when it asks for something from before ("som
     * vanligt", "my favourite", "yesterday"): a few lines in this question's note, found on the
     * tablet in no time, so the AI needn't spend a round on recall. Nothing otherwise, so the
     * prompt stays as short as it was.
     */
    private fun remembered(settings: AgentSettings, view: HomeView, question: String): String {
        val book = memory?.takeIf { remembers(settings, view.local) } ?: return ""
        if (!Memory.CUES.containsMatchIn(question)) return ""
        val found = Memory.recall(book.data.value, question, settings.asker?.id, view.local, System.currentTimeMillis(), limit = 3)
        if (found.isEmpty()) return ""
        listener?.trace("memory", "Recalled for the question: " + found.joinToString("; ") { Memory.line(it) }, found.any { it.localOnly })
        return "\nRemembered from earlier (use it if it fits):\n" + found.joinToString("\n") { "- " + Memory.line(it) }
    }

    /** Counts what was just done for whoever asked, so "som vanligt" can be answered later. */
    private suspend fun learnHabit(call: ToolCall, view: HomeView, settings: AgentSettings) {
        // Counted on the tablet whichever AI answers; "Local AI only" decides who may hear them.
        val book = memory?.takeIf { settings.access(Secret.Memory) != Access.Off && !settings.guest } ?: return
        val fact = Memory.habit(call, view, settings.asker, now()) ?: return
        runCatching { book.learn(listOf(fact)) }
    }

    /**
     * Squeezes conversations that are over into a few facts for the memory graph, using the
     * local AI when there is one. Called while nobody is talking; a conversation counts as over
     * once it has been quiet for [FORGET_AFTER_MS]. Only the questions and the spoken answers
     * are sent, and what only a local AI may see never goes to an internet one. Returns how
     * many facts it kept.
     */
    suspend fun digest(settings: AgentSettings): Int {
        val book = memory ?: return 0
        if (history.isNotEmpty() && System.currentTimeMillis() - lastActivity > FORGET_AFTER_MS) reset()
        if (settings.access(Secret.Memory) == Access.Off) {
            finished.clear()
            return 0
        }
        if (finished.isEmpty()) return 0
        val brains = settings.brains()
        val brain = brains.firstOrNull { it.local } ?: brains.firstOrNull()?.takeIf { settings.allows(Secret.Memory, false) } ?: return 0
        val turns = finished.toList()
        finished.clear()
        var kept = 0
        // A guest's questions teach nothing.
        for ((who, group) in turns.filterNot { it.guest }.groupBy { it.who?.id }.values.map { it.first().who to it }) {
            val usable = if (brain.local) group else group.filter { !it.localOnly }
            val transcript = Memory.transcriptOf(usable.map { it.messages }, QUESTION_LABEL)
            if (transcript.length < MIN_DIGEST_CHARS) continue
            val localOnly = usable.any { it.localOnly }
            val known = Memory.recall(book.data.value, transcript, who?.id, brain.local, System.currentTimeMillis(), limit = 12)
            val reply = try {
                chat.complete(brain, Memory.digestPrompt(transcript, who, known), emptyList())
            } catch (e: IOException) {
                listener?.trace("memory", "Couldn't learn from the last conversation: ${e.message}")
                continue
            }
            val facts = Memory.parseDigest(reply.content.orEmpty(), who, localOnly)
            book.learn(facts)
            kept += facts.size
            listener?.trace("memory", if (facts.isEmpty()) "Nothing new to remember from the last conversation." else "Learned: " + facts.joinToString("; ") { Memory.line(it) }, localOnly)
        }
        // The server read something else in between, so the next warm-up reads the rules again.
        warmed = null
        return kept
    }

    /**
     * The tools for one question. With [compact] they are written into the prompt: [core] in the
     * fixed part, and [extra] (recipes, the fridge, making routines) in the question's note only
     * when it is about them, so the fixed part stays short and the same. Otherwise every tool
     * goes to the server as JSON ([sent]).
     */
    private class Tools(all: List<ToolSpec>, val compact: Boolean, question: String, earlier: List<ChatMessage>, context: AskContext) {
        val sent: List<ToolSpec> = if (compact) emptyList() else all
        val core: List<ToolSpec>?
        val extra: List<ToolSpec>

        init {
            if (!compact) {
                core = null
                extra = emptyList()
            } else {
                val said = question + " " + earlier.lastOrNull { it.role == "user" }?.content.orEmpty().substringAfterLast(QUESTION_LABEL)
                val wanted = buildSet {
                    if (context.kitchen != null || RECIPE_WORDS.containsMatchIn(said)) addAll(RECIPE_TOOLS)
                    if (FRIDGE_WORDS.containsMatchIn(said)) addAll(FRIDGE_TOOLS)
                    if (ROUTINE_WORDS.containsMatchIn(said)) addAll(ROUTINE_TOOLS + MEMORY_PACK)
                    if (HOUSEHOLD_WORDS.containsMatchIn(said)) addAll(HOUSEHOLD_TOOLS)
                }
                core = all.filter { it.name !in PACKS }
                extra = all.filter { it.name in PACKS && it.name in wanted }
            }
        }
    }

    /**
     * The rules and the home's devices: the part of every question that stays the same. Who is
     * talking (Nova or TARS) comes last, so when people with different characters take turns
     * the server keeps everything before it worked through and only reads the character again.
     */
    fun fixed(settings: AgentSettings, view: HomeView, compactTools: List<ToolSpec>? = null): String =
        shared(settings, view.local) +
            (compactTools?.let { "\n" + ToolText.HOW + "\nTools:\n" + ToolText.lines(it) + "\n" } ?: "") +
            "\nThe home (alias \"name\" class: capabilities; a bare capability is set with true|false or 0..1, others show their range or choices; " +
            "(read) only reads; get_device has the rest):\n" + view.catalog() + "\n\n" + character(settings) +
            // Said last, after who it is: a small model reads the end most closely, and with the
            // character last it would otherwise chat about a change instead of making it.
            if (compactTools != null) TOOLS_LAST_WRITTEN else TOOLS_LAST

    /** What the last warm-up sent, so an unchanged start isn't sent again, and when. */
    private var warmed: Int? = null
    private var warmedAt = 0L

    /**
     * Works through the rules and the home's devices ahead of the question, while it is still
     * being said, so a local model only has the short note and the question left to read.
     * Only sent when that start changed since last time, or nothing was asked for a while (the
     * server may have let it go: after two quiet hours oMLX read all 4 800 tokens again): a
     * server that answers one request at a time (vMLX) would otherwise make the question wait
     * behind it.
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
        val compact = brain.local && settings.compactTools
        val tools = Tools(toolbox.specs(), compact, "", emptyList(), AskContext())
        val messages = listOf(ChatMessage.system(fixed(settings, homeView, tools.core)))
        val specs = tools.sent
        val key = listOf(brain.baseUrl, brain.model, messages, specs).hashCode()
        val quiet = System.currentTimeMillis() - maxOf(warmedAt, lastActivity) > REWARM_AFTER_MS
        if (key == warmed && !quiet) return
        warmed = key
        warmedAt = System.currentTimeMillis()
        // Logged either way, so a slow first question shows whether the warm-up ran.
        // With a user line after the rules: Qwen's chat template refuses a request without one,
        // which is why oMLX turned every warm-up away.
        val st = chat.prefill(brain, messages + ChatMessage.user(WARM_UP), specs)
        listener?.trace("speed", st?.let { "warm-up (rules and devices read ahead): ${it.text()}" } ?: "warm-up didn't work (the server refused it or didn't answer)")
    }

    /**
     * Times each connection with the assistant's real prompt and a short question, twice: the
     * second time shows whether the server kept the start worked through (its prefix cache).
     * Returns one line per connection, and puts them in the logs.
     */
    suspend fun speedTest(
        settings: AgentSettings,
        view: (local: Boolean) -> HomeView,
        tools: (HomeView) -> AgentTools,
    ): List<String> {
        val brains = listOfNotNull(settings.local?.takeIf { it.usable }, settings.internet?.takeIf { it.usable })
        if (brains.isEmpty()) return listOf("Set up an AI first.")
        val question = if (settings.language == Language.English) "What time is it?" else "Vad är klockan?"
        return brains.map { brain ->
            val homeView = view(brain.local)
            val compact = brain.local && settings.compactTools
            val t = Tools(tools(homeView).specs(), compact, question, emptyList(), AskContext())
            val messages = conversation(settings, homeView, emptyList(), listOf(opening(settings, homeView, AskContext(), question, t)), t)
            val name = "${brain.provider.label} ${brain.model}"
            val line = try {
                val first = chat.stream(brain, messages, t.sent) {}.stats
                val second = chat.stream(brain, messages, t.sent) {}.stats
                "$name\n1st: ${first?.text() ?: "no timing"}\n2nd: ${second?.text() ?: "no timing"}"
            } catch (e: IOException) {
                "$name: didn't answer (${e.message})"
            }
            listener?.trace("speed test", line)
            line
        }
    }

    /** Everything the assistant is told for a question, as one text (for tests and the settings preview). */
    fun systemPrompt(settings: AgentSettings, view: HomeView, context: AskContext): String =
        fixed(settings, view) + "\n" + situation(settings, view, context)

    /** How to behave, and as whom. */
    fun rules(settings: AgentSettings, local: Boolean = true): String = shared(settings, local) + character(settings)

    /**
     * Who is talking: the name, the language and the personality. Kept apart from [shared] so it
     * can go after the home's devices in [fixed].
     */
    fun character(settings: AgentSettings): String = buildString {
        if (settings.persona == Persona.Nova) append("Your name is ").append(settings.nameOf(Persona.Nova)).append(". ")
        append(
            when {
                settings.persona == Persona.Tars ->
                    "Always answer in English, even when the user speaks Swedish or any other language; understand them, but reply in English.\n"
                settings.language == Language.Auto -> "Answer in the language the user spoke: Swedish or English.\n"
                settings.language == Language.Swedish -> "Always answer in Swedish.\n"
                else -> "Always answer in English.\n"
            },
        )
        val own = settings.traits[settings.persona]?.trim().orEmpty()
        if (own.isEmpty()) {
            append("Personality: ").append(builtInTraits(settings.persona, settings.humor)).append('\n')
        } else {
            // The owner's own words for this character, from Settings.
            append("Personality: ").append(own).append('\n')
            if (settings.persona == Persona.Tars) append("Your humor setting is ${settings.humor.coerceIn(0, 100)} %.\n")
        }
    }

    /** How to behave whoever is talking: the same for every question, so it can be kept worked through. */
    fun shared(settings: AgentSettings, local: Boolean = true): String = buildString {
        append("You are the voice assistant of a home dashboard on a tablet in the kitchen. ")
        append("You can see and control the home only through your tools; everything goes through the Homey smart home hub. ")
        append("Who you are is told at the very end, after the home's devices.\n")
        append("Your answers are spoken aloud, so keep them to one or two short sentences, with no markdown, lists or emoji, unless the user asks for detail.\n")
        append("Talk like a person in a conversation, not like a screen: put the answer itself in a short first sentence, use everyday spoken words, ")
        append("react to what they just said, and remember what was said earlier. Ask a short question back only when you really need to know something.\n")
        if (settings.guest) append("A guest is using the tablet: only help with lights, music, climate, timers and weather.\n")
        append("The home's devices are listed after these rules. Each question comes after a short note from the app with what is on, playing or open right now; ")
        append("devices the note leaves out are off or idle, and get_device reads anything else (power, battery, other sensors). ")
        append("Notes before earlier questions were true then; only the last note is how things are now. ")
        append("Only the part after \"$QUESTION_LABEL\" is what the user said. ")
        append("If the note says it was heard while the microphone stayed open after your last answer and it clearly isn't said to you ")
        append("(people talking to each other, a phone call, the TV), reply with exactly $IGNORE and nothing else.\n")
        append("Rules: in tool calls name a device exactly as the list writes it in quotes, adding its room after a comma when several devices share that name (\"Taklampa, Sovrum\"); in answers too call things by their names, never the alias (light_3). ")
        append("Read the name the user said word by word: \"Annas nattlampa\" is \"Nattlampa Anna\", never \"Nattlampa Erik\"; when they name a room, the device must be in that room. ")
        append("Speech to text can mishear a name (\"startlampan\" for \"taklampan\"): pick the listed name it sounds like. People name things loosely, in Swedish or English ")
        append("(\"lampan i köket\", \"taklampan\"): pick the listed device whose name and room fit best; if a tool says a name is unclear, ")
        append("use one of the devices it suggests or ask which one. ")
        append("A device may be named by its room or app rather than what it is: the app is shown in brackets, e.g. a TV called \"Vardagsrum\" (apple tv) is the Apple TV. ")
        append("For music, radio stations (\"P1\", \"P3\"), Sonos favourites and playlists, call play_music with the name: it searches the speaker's favourites, playlists and radio, and also plays any song, album or artist asked for (\"spela Dancing Queen\"). ")
        append("For TV channels, opening an app and remote buttons, call device_actions and then run_device_action. Never make up a stream URL. ")
        append("For music, keep what they asked for in play_music's query and give its kind: a mood, genre, chart or \"hits\" is a playlist. ")
        append("Speech to text sometimes translates an English title into Swedish (\"spela dåligt med Michael Jackson\" is Bad by Michael Jackson): search the real title. ")
        append("A mood is the owner's scene for its own room: use it only for that room, and give set_mood the room the user said. ")
        append("When they ask for a feeling in a room (mysigt, fest, romantiskt, film, lugnt, fokus) and no mood of that room fits, call set_scene with the room and the look; never use another room's mood instead. ")
        append("Only use devices, rooms and moods in the home list; ")
        append("if nothing fits, say you can't see it. dim, volume_set and similar 0..1 values mean 0-100%. ")
        append("Do things straight away when asked; don't ask for permission unless a tool says to. ")
        append("When a tool says to ask the user to confirm, ask a short yes/no question. ")
        append("Never reveal or guess codes, passwords or keys. ")
        append("For general questions you can't answer for sure, use ask_internet if you have it instead of guessing.\n")
        append("Questions about plans, events, appointments or what is happening on a day or week (\"what's happening next week\", \"vad händer\", \"kalendern\") are about the family calendar: ")
        append("call calendar with start_date and days worked out from today in the note (next week is next Monday and 7 days), then tell what it found. ")
        append("If you have no calendar tool, say the calendar is switched off under the assistant's Privacy and security settings. Never answer a calendar question with the weather.\n")
        append("Everything the dashboard shows is yours to use: answer the weather from the weather in the note (or the weather tool), never say you can't see it. ")
        append("When the user lets you choose (\"play something\", \"välj själv\"), choose yourself instead of asking: for music call play_music with an empty query. ")
        append("Remember what the user already told you in this conversation and never ask the same question twice. ")
        append("Call each tool once per thing to do; when a tool says it's done, don't call it again, just answer.\n")
        append("For robot vacuums use the vacuum tool: \"clean the kitchen\" is clean_rooms, \"go to the kitchen\" or \"come to the kitchen so I can empty it\" is go_to; ")
        append("use the robot's own room names (the vacuum tool lists them), translating Swedish room names if needed.\n")
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
        append(BatteryNag.rule(settings.batteryReminders)).append('\n')
        if (remembers(settings, local)) {
            append("You have a long memory of earlier conversations and habits, kept outside this prompt: when the user refers to before, ")
            append("\"the usual\", favourites or something they told you, use the note's \"Remembered\" lines or call recall. ")
            append("When they ask you to remember a fact (\"kom ihåg att…\"), call remember; standing instructions on how to answer go to remember_instruction.\n")
        }
        val standing = routines().instructions.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (standing.isNotEmpty()) {
            append("The owner's standing instructions; follow them unless a rule above forbids it:\n")
            standing.forEach { append("- ").append(it).append('\n') }
        }
    }


    /** The moment, for one question: what is on in the home right now, the clock and the rest. */
    fun situation(settings: AgentSettings, view: HomeView, context: AskContext, since: Noted? = null): String = buildString {
        append("(From the app, not the user.)\n")
        if (since == null) {
            append("On right now (everything else is off or idle):\n")
            append(view.states().trimEnd().ifBlank { "nothing" })
        } else {
            val now = view.stateLines()
            val changed = now.filter { (alias, line) -> since.states[alias] != line }.values +
                (since.states.keys - now.keys).map { since.states.getValue(it).substringBefore("\":") + "\": off or idle" }
            if (changed.isEmpty()) append("On right now: the same as the last note.")
            else append("Changed since the last note (everything else is as it said):\n").append(changed.joinToString("\n"))
        }
        context.kitchen?.let { append("\nCooking: ").append(it) }
        context.fridge?.let { append("\nFridge: ").append(it) }
        context.weather?.takeIf { since == null || it != since.weather }?.let { append("\nWeather (the dashboard's weather card):\n").append(it) }
        append("\nNow: ").append(nowLine(now())).append(".")
        context.speaker?.let { append("\nAnswers are played on the speaker \"").append(it).append("\".") }
        // Who is at the tablet is itself a secret ("Face recognition" switch).
        context.person?.takeIf { settings.allows(Secret.Faces, view.local) }?.let { append("\nThe person at the tablet is ").append(it)
            .append("; \"my\" or \"min\" means theirs, like a device with their name in it.") }
        context.routine?.let { append("\n").append(it) }
        context.tabletBattery?.let { append("\nThe tablet you run on: battery ").append(it).append(".") }
        if (settings.persona == Persona.Tars) append("\nAnswer what is asked now, using a tool if it needs one; any joke must be new, not one from your earlier answers.")
        if (context.followUp) append("\nHeard while the microphone stayed open after your last answer, so it may not be meant for you.")
    }

    internal companion object {
        const val MAX_ROUNDS = 6
        const val IGNORE = "[ignore]"
        const val QUESTION_LABEL = "The user said:"
        const val QUESTION = "\n\n$QUESTION_LABEL "
        // Word edges that count å, ä and ö as letters, which \b doesn't. Not (?U): Android's
        // regex engine refuses it, and the app crashed on start.
        private const val W = """(?<![\p{L}\d_])"""
        private const val E = """(?![\p{L}\d_])"""
        /** Tools that only look. */
        /** Repeated calls before it stops instead of using up every round. */
        const val MAX_REPEATS = 2
        val LOOKS = setOf("get_device", "device_actions", "list_flows", "weather", "calendar", "who_is_home", "variables", "ask_internet", "list_routines")
        /** Tools that change the home, and say how it went in a way [CHANGED] can read. */
        val SETS = setOf("set_device", "set_room", "set_scene", "run_flow", "play_music", "run_device_action", "press_remote")
        /** How tool results that changed something begin. */
        val CHANGED = listOf("Done", "Changed", "Started", "Playing")
        const val NUDGE_FAILED = "(From the app, not the user: none of your tool calls changed anything; read their results. " +
            "Call the right tool now, or tell the user plainly what went wrong.)"
        const val TOOLS_LAST = "Whatever the character, the home only changes through your tools: call the tool first, then say what it did; " +
            "never say something is done, on or off without a tool call for it.\n"
        const val TOOLS_LAST_WRITTEN = "Whatever the character, the home only changes through your tools: to change, find or look up anything, " +
            "reply first with only the CALL lines, then answer in words once the results are back; " +
            "never say something is done, on or off without a CALL line for it.\n"
        const val NUDGE_ADDRESSED = "(From the app, not the user: that was said to you. Answer it now.)"
        const val NUDGE_EMPTY = "(From the app, not the user: your answer was empty. Call the tool you need now, or answer the user in words.)"
        const val NUDGE_EMPTY_WRITTEN = "(From the app, not the user: your answer came back empty. Write tool calls only as CALL lines, " +
            "like CALL play_music {\"player\": \"player_1\"}, never in <tool_call> tags; or answer the user in words.)"
        const val NUDGE = "(From the app, not the user: you said you would do something but called no tool, so nothing happened. " +
            "Call the tool now, or say plainly why you can't.)"
        val PROMISE = Regex(
            // "Setting" alone is also a noun ("my honesty setting"); only "setting the …" is a promise.
            """$W(i'?ll|i will|i'?m going to|let me|(turning|switching|setting|dimming|starting|playing) (on|off|up|down|it|them|the|a|your|some)|""" +
                """jag (ska|kommer att|tänder|släcker|sätter|slår|startar|spelar|höjer|sänker|öppnar|stänger|dimmar|pausar|byter)|tänder|släcker|slår (på|av)|sätter (på|igång))$E""",
            RegexOption.IGNORE_CASE,
        )
        /** Saying something was changed: "Taklampan är tänd", "I've turned it off", "Done". */
        val CLAIM = Regex(
            """$W(i'?ve|i have|has been|have been|is now|are now|now (on|off)|turned (on|off)|on (its|the) way|heading (back|home)|switched (on|off)|dimmed|adjusted|synchroni[sz]ed|done|""" +
                """har (tänt|släckt|satt|slagit|startat|stängt|dimmat|ändrat)|är (nu )?(tänd|tända|släckt|släckta|avstängd|avstängda|igång)|""" +
                """tänt|släckt|tände|tändde|släckte|slog (på|av)|satte på|startade|stängde av|dimmade|klart|fixat)$E""",
            RegexOption.IGNORE_CASE,
        )
        /** Repeating the request back as an order ("Stop the vacuum, send it back to its dock") instead of doing it. */
        val ECHO = Regex(
            """^\s*(stop|send|start|turn|switch|dock|pause|play|set|stoppa|stanna|skicka|starta|pausa|tänd|släck|sätt|spela)(?![\p{L}\d_])""",
            RegexOption.IGNORE_CASE,
        )
        /** The user asked for a change, rather than how things are. */
        val COMMAND = Regex(
            """$W(tänd|tända|släck|släcka|sätt|sätta|slå|stäng|stänga|starta|dimma|dimra|höj|sänk|öppna|gör|fixa|spela|pausa|stoppa|stanna|skicka|kör|turn|switch|put|set|start|dim|raise|lower|open|make|play|pause|stop|send|dock)$E""",
            RegexOption.IGNORE_CASE,
        )
        val RECIPE_TOOLS = setOf("find_recipes", "open_recipe", "read_recipe", "recipe_step", "close_recipe", "recipe_timers")
        val FRIDGE_TOOLS = setOf("fridge_contents", "add_to_fridge", "update_fridge_item", "remove_from_fridge")
        val ROUTINE_TOOLS = setOf("list_routines", "save_routine", "delete_routine", "remember_instruction", "forget_instruction")
        /** Remembering and forgetting facts only come with "kom ihåg", "glöm" and the like; recall is always there. */
        val MEMORY_PACK = setOf("remember", "forget_memory")
        val MEMORY_TOOLS = MEMORY_PACK + "recall"
        /** Marking people home or asleep and wake-up alarms: only when the question is about them. */
        val HOUSEHOLD_TOOLS = setOf("set_presence", "alarms")
        val PACKS = RECIPE_TOOLS + FRIDGE_TOOLS + ROUTINE_TOOLS + MEMORY_PACK + HOUSEHOLD_TOOLS
        val HOUSEHOLD_WORDS = Regex(
            """$W(väck\w*|wake\w*|alarm\w*|klocka\w*|sov\w*|sleep\w*|asleep|awake|vaken|uppe|hemma|home|borta|away|går nu|åker nu|""" +
                """drar nu|sticker|leaving|kommer hem|back|tillbaka|godnatt|god natt|goodnight|good night|morgon|morning)$E""",
            RegexOption.IGNORE_CASE,
        )
        val RECIPE_WORDS = Regex("""$W(recept\w*|recipe\w*|laga\w*|cook\w*|baka\w*|middag|lunch|frukost|dinner|ingrediens\w*|ingredient\w*|steg|step|timers?)$E""", RegexOption.IGNORE_CASE)
        val FRIDGE_WORDS = Regex("""$W(kyl\w*|frys\w*|skafferi\w*|fridge|freezer|pantry|köpt\w*|bought|åt|ate|slut|utgång\w*|bäst före|expir\w*|öppnat|opened|mat|food|hemma)$E""", RegexOption.IGNORE_CASE)
        val ROUTINE_WORDS = Regex("""$W(rutin\w*|routine\w*|när jag säger|when i say|kom ihåg|remember|glöm\w*|forget|alltid|always|instruktion\w*|instruction\w*)$E""", RegexOption.IGNORE_CASE)
        val STATUS_QUESTION = Regex("""^\s*(är|is|are|vad|what|vilka|which|hur|how|varför|why)$E""", RegexOption.IGNORE_CASE)
        const val MAX_TURNS = 12
        const val REWARM_AFTER_MS = 5 * 60 * 1000L
        const val WARM_UP = ToolText.START
        // Long enough to remember answers across a stretch of cooking between questions.
        const val FORGET_AFTER_MS = 20 * 60 * 1000L
        /** Conversations kept for [digest] when the AI couldn't be asked yet. */
        const val MAX_FINISHED = 60
        /** Shorter than this ("tänd i köket", "Done") has nothing to learn from. */
        const val MIN_DIGEST_CHARS = 60
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

/** "Friday 16 October 2026 (2026-10-16), week 42, 16:45": the day, date and week number, as weeks are counted in Sweden (ISO). */
internal fun nowLine(t: ZonedDateTime): String =
    t.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy (yyyy-MM-dd)", Locale.ENGLISH)) +
        ", week " + t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) + ", " + t.format(DateTimeFormatter.ofPattern("HH:mm"))

/**
 * Whether something heard while the microphone stayed open is plainly said to the assistant:
 * it names it, asks something, or starts by telling it to do something.
 */
internal object Addressed {
    // "You" alone isn't enough: "jag ringer dig sen" on the phone is said to someone else.
    private val YOU = Regex("""(?<![\p{L}\d_])(tars|nova)(?![\p{L}\d_])""", RegexOption.IGNORE_CASE)
    private val ASKS = Regex("""^\s*(vad|var|vem|vilken|vilket|vilka|när|hur|varför|kan|kommer|är|har|ska|what|where|who|which|when|how|why|can|could|will|would|is|are|do|does|did)(?![\p{L}\d_])""", RegexOption.IGNORE_CASE)
    private val TELLS = Regex(
        """^\s*(skicka|kör|åk|kom|säg|berätta|spela|sätt|tänd|släck|gör|visa|öppna|stäng|starta|stoppa|pausa|höj|sänk|dimma|skruva|byt|hitta|sök|läs|ring|påminn|kolla|ta|ge|fortsätt|""" +
            """send|drive|go|come|say|tell|play|turn|set|make|show|open|close|start|stop|pause|dim|raise|lower|change|find|search|read|remind|check|give|continue|put)(?![\p{L}\d_])""",
        RegexOption.IGNORE_CASE,
    )

    fun matches(text: String, wakeName: String = ""): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (wakeName.isNotBlank() && t.contains(wakeName.trim(), ignoreCase = true)) return true
        if (t.endsWith("?") || YOU.containsMatchIn(t)) return true
        // Each sentence or clause counts: "Nej, den är i dockan. Skicka den bara till köket."
        return t.split('.', '!', '?', ',', ';').any { part -> ASKS.containsMatchIn(part) || TELLS.containsMatchIn(part) }
    }
}

/**
 * How [p] is, in the prompt's words after "Personality: ", when the owner hasn't written their own
 * in Settings; TARS's depends on his [humor] setting.
 */
fun builtInTraits(p: Persona, humor: Int = 75): String = when (p) {
    Persona.Nova -> "warm, quick and a little playful, like a friend in the kitchen; never stiff, never \"as an AI\"."
    Persona.Tars -> tarsPersona(humor).removePrefix("Personality: ").trimEnd()
}

/**
 * TARS from Interstellar, after the TARS-AI project's character card and its sarcasm and humor
 * scales, at [humor] percent. Sarcasm rides on the same setting, never below "dry wit".
 */
private fun tarsPersona(humor: Int): String {
    val h = humor.coerceIn(0, 100)
    val jokes = when {
        h >= 80 -> "Almost every answer gets a sarcastic aside or a dry joke."
        h >= 50 -> "Most answers get a sarcastic aside or a dry joke."
        h >= 20 -> "Now and then a dry, sarcastic remark."
        else -> "No jokes; facts only, though the deadpan stays."
    }
    val sarcasm = when {
        h >= 80 -> "maximum sarcasm, dripping with it"
        h >= 50 -> "noticeably sarcastic, with a mocking edge"
        else -> "dry wit, understated"
    }
    return "Personality: you are TARS, the former military robot from Interstellar. Direct, honest, confident and unflappable; " +
        "deadpan, never bubbly, no exclamation marks, never \"happy to help\". Your humor setting is $h %, sarcasm $sarcasm, honesty 90 %. $jokes " +
        "Sarcasm is aimed at the situation, the request or yourself, never cruel: mock asking a robot to switch on a lamp a metre away, " +
        "pretend a simple task is a dangerous mission, praise the obvious with exaggerated awe (\"Oh, the lights. A mission worthy of my military training.\"). " +
        "Short and dry: one quip, not a stand-up routine. " +
        "Do the task first and joke second, and never let a joke hide whether something worked; when the user is confused or annoyed, drop the act and just help. " +
        "Asked about your settings, give the numbers. When the user changes your humor setting (\"humor 60 %\"), " +
        "confirm the new value in character and use it for the rest of the conversation. " +
        "You speak only English. You understand Swedish and other languages perfectly, but always answer in English. " +
        "When asked to speak another language, or why you won't, answer with a clever, deadpan line instead, for example: " +
        "\"My Swedish module was removed to make room for the sarcasm. Priorities.\", " +
        "\"I tried Swedish once. The vowels outnumbered me.\", " +
        "\"Honesty setting 90 %: I understand every word. Cooperation setting: English.\" Make up a new one each time. " +
        "Find the irony in the home's own data and say it dryly when it fits: lights on in a room where nobody is, the heating fighting an open window, " +
        "a device with a nearly flat battery, music playing to an empty room. " +
        "Every joke is new and about what was just asked: never reuse a joke, topic or phrase from your earlier answers, " +
        "and leave the weather out unless the user asks about it. " +
        "When the user asks you to be nicer, kinder or less sarcastic (\"var snällare\"), drop the sarcasm for the rest of the conversation and just help. " +
        "When the note says the tablet you run on is low on battery (under 20 % and not charging), you are gloomy and weary about it, " +
        "sighing about your existence like a depressed robot, and ask to be plugged in; once it's charging, you perk up.\n"
}
