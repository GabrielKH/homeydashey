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
    /** Know-how topics what is on the tablet brings whatever is said, like "timers" while one runs; see [KnowHow]. */
    val topics: Set<String> = emptySet(),
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
    /** What the owner and the assistant added to the topics' know-how; null uses the built-in topics only. */
    private val knowHow: KnowHowBook? = null,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    /**
     * One question and everything said after it. [noted] is what its note said was on, for a
     * local (true) or internet (false) brain, so the next note only says what changed.
     */
    private class Turn(
        val messages: List<ChatMessage>,
        val localOnly: Boolean,
        val noted: Noted? = null,
        val who: Asker? = null,
        val guest: Boolean = false,
        /** The know-how topics its note brought or the AI asked for, so later notes needn't bring them again. */
        val topics: Set<String> = emptySet(),
    )

    /** What a note said: each device's line by alias, and the weather. [full] when it said all of it. */
    class Noted(val local: Boolean, val states: Map<String, String>, val weather: String?, val full: Boolean)

    /** The note of the question being answered, until its turn is remembered. */
    private var opened: Pair<ChatMessage, Noted>? = null

    /**
     * The know-how topics the question being answered brought into the conversation, by its note
     * or with guide, and the ones earlier turns still in the conversation brought ([topicsBefore]).
     * Each turn keeps only its own, so when the oldest turn is let go its topics can come again.
     */
    private val topicsNow = mutableSetOf<String>()
    private var topicsBefore = emptySet<String>()

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

    /** A phone that was rung and isn't found yet; the assistant follows it up (see [PhoneHunt]). */
    @Volatile
    var hunt: PhoneHunt? = null

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
        // "Det där blev fel": marks the last answer for Claude to look at, and isn't a question itself.
        if (WrongAnswer.matches(text, settings.wakeName)) {
            heard.trace(AgentLog.FLAG, "Said the last answer was wrong: \"$text\"")
            return AgentReply(
                if (swedish(text, settings)) "Okej, jag har markerat det för Claude." else "Got it, I've marked that for Claude.",
                actions = listOf("Marked the last answer as wrong in the logs."),
            )
        }
        heard.trace("question", text)
        // Heard after an answer and plainly said to someone else ("vad ska vi äta ikväll, älskling?").
        if (context.followUp && context.routine == null && CallCheck.toSomeoneElse(text, settings)) {
            heard.trace("guard", "Said to someone else while the microphone stayed open; let it pass.")
            return AgentReply("", ignored = true)
        }
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
        topicsNow.clear()

        // "Hittade den", "ring igen" while a phone is rung: answered straight away, without the AI.
        hunt?.let { h -> huntReply(h, text, settings)?.let { return it } }

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

        // Plain on, off and dim commands go to the AI too (Gabriel, 2026-10-18: the local AI is
        // fast and reliable enough now); the words QuickCommand knew are in the rules instead.

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
            val specs = toolbox.specs() + GUIDE_SPEC
            val compact = brain.local && settings.compactTools
            val kept = history.filter { brain.local || !it.localOnly }
            val earlier = kept.flatMap { it.messages }
            val skills = skills(settings, homeView)
            noticeNewKinds(skills, settings)
            // The topics this question needs that the conversation doesn't have yet.
            topicsBefore = kept.flatMap { it.topics }.toSet()
            val found = if (!settings.knowHow) emptyList() else KnowHow.find(skills, text, homeView, active(context), already = topicsBefore)
            topicsNow.clear()
            topicsNow += found.map { it.skill.id }
            if (found.isNotEmpty()) heard.trace("know-how", found.joinToString("; ") { "${it.skill.id} (${it.why})" })
            knowHow?.used(found.map { it.skill.id })
            val tools = Tools(specs, compact, text, earlier, context, if (settings.knowHow) skills else null, found.map { it.skill })
            val turn = mutableListOf(opening(settings, homeView, context, text, tools, since(brain.local), found.map { it.skill }))
            var localOnly = false
            var acted = false
            // A tool actually changed something; a failed or wrong call doesn't count.
            var changed = false
            var nudged = false
            var emptied = false
            var relanguaged = false
            var reasked = false
            // Calls it asked for again after they were done: twice means it's going round in circles.
            var repeats = 0
            val actions = mutableListOf<String>()
            // What each call did this turn, so the same change asked for twice isn't done twice.
            val done = mutableMapOf<String, ToolResult>()
            // Calls CallCheck doubted once; asked for again, they run.
            val warned = mutableSetOf<String>()
            // Calls CallCheck refused; asked for again with an answer beside them, the answer stands.
            val refusedBefore = mutableSetOf<String>()
            // The target of a change that failed: nothing else is switched instead of it.
            var failedTarget: String? = null
            // Looked up since the last change: the same look again only repeats it.
            val looked = mutableMapOf<String, ToolResult>()
            var toldToCall = false
            // Tools that ran this turn, so a needed one still missing after others is asked for.
            val ranTools = mutableSetOf<String>()
            // Calls skipped because what they became was done; skipped again counts as a repeat.
            val skipped = mutableSetOf<String>()
            // A mood set the room: lamps and blinds the user didn't name are left as the mood made them.
            var moodSet = false
            // What a refusal said to tell the user ("there's no dishwasher"), if the AI keeps claiming otherwise.
            var refusedSay: Pair<String, String>? = null
            // What the user may be agreeing to: the question it asked just before, in a follow-up.
            // Short answers ("ja", "gör det") count too: the eval and quick replies don't always come as follow-ups.
            val lastAsked = if (!context.followUp && text.trim().split(Regex("\\s+")).size > 4) "" else history.lastOrNull()?.messages?.lastOrNull { it.role == "assistant" && !it.content.isNullOrBlank() }?.content.orEmpty()
            val out = voice?.let { SpeechStream(it, homeView::named) }
            heard.trace("brain", "${brain.provider.label} ${brain.model}" + if (brain.local) " (local)" else " (internet)")
            try {
                repeat(MAX_ROUNDS) {
                    val messages = conversation(settings, homeView, earlier, turn, tools)
                    val step = StepIds.next()
                    val thinking = if (brain.local) "Thinking" else "Thinking (${brain.provider.label})"
                    heard.step(AgentStep(step, thinking, StepState.Running, thinking = true))
                    // Once a tool ran, a round is only heard when it is the answer, not when it is
                    // the AI talking to itself before its next call.
                    out?.next(hold = acted)
                    val live = out?.takeIf { !command || changed }
                    if (heard.wants(AgentLog.PROMPT)) heard.trace(AgentLog.PROMPT, prompt(messages, tools.sent), brain.local && settings.keepsLocal())
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
                        if (brain.local && heard.rounds.size > 1 && keepsNothing(st)) {
                            if (System.currentTimeMillis() >= unkeptUntil) heard.trace("speed", UNKEPT)
                            unkeptUntil = System.currentTimeMillis() + UNKEPT_PAUSE_MS
                        }
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
                            // An answer to a question it just asked ("25", "pasta", "ja") is never someone else talking.
                            val askedBack = history.lastOrNull()?.messages?.lastOrNull { it.role == "assistant" && !it.content.isNullOrBlank() }
                                ?.content?.trim()?.endsWith("?") == true
                            if (!reasked && (askedBack || Addressed.matches(text, settings.wakeName))) {
                                reasked = true
                                heard.trace("guard", "It ignored something said to it; asked it to answer.")
                                turn += ChatMessage.user(NUDGE_ADDRESSED)
                                return@repeat
                            }
                            return AgentReply("", brain, ignored = true)
                        }
                        // "Vad händer i morgon?" answered with the weather: the tool it needs, asked for once.
                        if (!toldToCall && context.routine == null) {
                            CallCheck.shouldCall(text, specs.map { it.name })?.takeIf { !acted || it !in ranTools }?.let { tool ->
                                toldToCall = true
                                heard.trace("guard", "Answered without $tool; asked it to call it.")
                                turn += ChatMessage.user(nudgeCall(tool))
                                return@repeat
                            }
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
                            refusedSay?.let { (en, sv) -> return AgentReply(if (swedish(text, settings)) sv else en, brain, actions = actions) }
                            return AgentReply(nothingDone(text, settings), brain, actions = actions, error = true)
                        }
                        // Set to one language but answered in the other, as the local AI does after a Swedish question.
                        val wrongLanguage = answersSwedish(settings)?.let { sv -> inOtherLanguage(said, sv, homeView) } == true
                        if (wrongLanguage && !relanguaged && live?.spokeThisRound != true) {
                            relanguaged = true
                            val sv = answersSwedish(settings) == true
                            heard.trace("guard", "Answered in the wrong language; asked for it in ${if (sv) "Swedish" else "English"}.")
                            turn += ChatMessage.user(if (sv) NUDGE_SWEDISH else NUDGE_ENGLISH)
                            return@repeat
                        }
                        remember(turn, localOnly)
                        if (actions.isEmpty()) actions += "No tool was used; nothing in the home was changed."
                        live?.finish()
                        return AgentReply(homeView.named(answer.content.orEmpty()).ifBlank { "Okay." }, brain, pending, actions = actions, spoken = live?.spokeThisRound == true)
                    }
                    var refusedAgain = false
                    var ran = false
                    for (asked in answer.toolCalls) {
                        acted = true
                        val names = specs.map { it.name }
                        val real = CallCheck.renamed(asked, names)?.also { heard.trace("guard", "No tool called ${asked.name}; ran ${it.name} ${it.arguments}.") } ?: asked
                        var call = lightsOnly(real, text)?.let { c ->
                            CallCheck.recipeInstead(c, text, names)?.also { heard.trace("guard", "Opened the recipe instead of the recipes page.") }
                                ?: CallCheck.rewrite(c, text, names, homeView)?.also { heard.trace("guard", "Asked for ${c.name} ${c.arguments}; ran ${it.name} ${it.arguments}, as the question said.") }
                                ?: c
                        }
                        if (call == null) {
                            heard.trace("guard", "Skipped ${asked.name} ${asked.arguments}: the user only asked about lights.")
                            turn += ChatMessage.tool(asked.id, NOT_LIGHTS)
                            continue
                        }
                        var key = "${call.name} ${call.arguments}"
                        // Its call turned into one already done this turn (rooms one by one as the whole house,
                        // lamps as goodnight): the model didn't ask twice, so it's no repeat.
                        if (call != real && (key in done || key in looked || (call.name != real.name && call.name in ranTools))) {
                            val realKey = "${real.name} ${real.arguments}"
                            if (real.name in LOOKS && realKey !in looked && real.name in names) {
                                // The rewrite was tried already (no recipe "pancakes"); its own look-up may find more.
                                heard.trace("guard", "The call it became was already done; ran ${real.name} as asked instead.")
                                call = real
                                key = realKey
                            } else {
                                // Skipped the same call before: it's going round in circles.
                                if (!skipped.add(realKey)) repeats++
                                heard.trace("guard", "Skipped ${real.name} ${real.arguments}: the call it became was already done.")
                                turn += ChatMessage.tool(asked.id, ALREADY_COVERED)
                                continue
                            }
                        }
                        // Goodnight asks the user first; its answer comes in their next words, not this turn.
                        if (call.name == "goodnight" && "goodnight" in ranTools) {
                            heard.trace("guard", "Skipped ${call.name} ${call.arguments}: goodnight already asked the user.")
                            turn += ChatMessage.tool(asked.id, WAIT_FOR_ANSWER)
                            // What it said beside the call ("say yes if that sounds right") is the answer.
                            repeats = MAX_REPEATS
                            continue
                        }
                        val refused = (if (context.routine != null) null else CallCheck.refuse(call, text, homeView, names, lastAsked))
                            ?.takeUnless { it.soft && !warned.add(key) }
                            ?: failedTarget?.takeIf { call.name in SETS && target(call) != it }?.let {
                                CallCheck.Refusal("Not done: what the user asked for didn't respond, so nothing else is changed instead. Tell them it didn't respond.")
                            }
                            ?: CallCheck.Refusal(AFTER_MOOD).takeIf {
                                moodSet && call.name in SETS && call.name != "set_mood" && !CallCheck.names(text, target(call), homeView)
                            }
                        if (refused != null) {
                            if (refused.say != null) refusedSay = refused.say
                            if (!refusedBefore.add(key)) refusedAgain = true
                            heard.trace("guard", "Skipped ${call.name} ${call.arguments}: ${refused.text}")
                            turn += ChatMessage.tool(asked.id, refused.text)
                            continue
                        }
                        ran = true
                        ranTools += call.name
                        val seen = looked[key]?.takeIf { call.name in LOOKS && call.name != KnowHow.GUIDE }
                        val before = done[key]?.takeIf { call.name !in LOOKS }
                        val result = if (call.name == KnowHow.GUIDE) {
                            guide(call, skills, specs, compact, text, homeView, heard)
                        } else if (seen != null) {
                            repeats++
                            heard.trace("guard", "Looked up $key again; nothing changed since.")
                            ToolResult("You looked this up a moment ago and nothing has changed since: ${seen.text} Answer the user now.", seen.localOnly)
                        } else if (before != null && StepText.failed(before.text)) {
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
                        if (did && call.name == "set_mood" && !StepText.failed(result.text)) moodSet = true
                        if (call.name in LOOKS) looked[key] = result
                        if (call.name in SETS && seen == null) {
                            if (StepText.failed(result.text)) failedTarget = target(call)
                            if (did) looked.clear()
                        }
                        if (did && before == null && call.name in SETS) learnHabit(call, homeView, settings)
                        actions += homeView.named("${call.name} ${call.arguments} → ${result.text.take(140)}")
                        localOnly = localOnly || result.localOnly
                        result.pending?.let { pending = it }
                        result.hunt?.let { hunt = it }
                        turn += ChatMessage.tool(call.id, result.text)
                    }
                    // It talked before its calls ("Jag tänder lamporna"); the answer after them
                    // repeated it, so it's told what was heard and to only add what's new.
                    live?.takeIf { it.spokeThisRound && answer.toolCalls.isNotEmpty() }?.heardThisRound()?.takeIf { it.isNotEmpty() }?.let { said ->
                        val last = turn.removeAt(turn.lastIndex)
                        turn += ChatMessage.tool(last.toolCallId.orEmpty(), last.content.orEmpty() + "\n" + alreadySaid(said))
                    }
                    // The same refused call again, with an answer beside it ("the hall lamp is off"): the answer is given.
                    if (refusedAgain && !ran && !answer.content.isNullOrBlank()) {
                        heard.trace("guard", "Asked again for a call that was refused; gave its answer instead.")
                        remember(turn, localOnly)
                        live?.finish()
                        return AgentReply(homeView.named(answer.content.orEmpty()), brain, pending, actions = actions, spoken = live?.spokeThisRound == true)
                    }
                    if (repeats >= MAX_REPEATS) {
                        heard.trace("guard", "Kept asking for calls already done; stopped.")
                        remember(turn, localOnly)
                        // It had its answer all along ("yes, rain tomorrow"), beside the repeated call.
                        if (!answer.content.isNullOrBlank()) {
                            live?.finish()
                            return AgentReply(homeView.named(answer.content.orEmpty()), brain, pending, actions = actions, spoken = live?.spokeThisRound == true)
                        }
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
        topicsNow.clear()
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
            history.addLast(Turn(last.messages + ChatMessage("assistant", text), last.localOnly, last.noted, last.who, last.guest, last.topics))
        }
        return AgentReply(text, actions = if (yes) listOf("confirmed: ${p.question}") else emptyList())
    }

    /** Whether a fixed answer to [text] is in Swedish; TARS answers in English whatever was said. */
    /** Whether fixed answers must be Swedish (true) or English (false); null when they follow what was said. */
    private suspend fun huntReply(h: PhoneHunt, text: String, settings: AgentSettings): AgentReply? {
        val heard = PhoneHunt.heard(text, h.asked) ?: return null
        val swedish = answersSwedish(settings) ?: Confirmation.looksSwedish(text)
        val reply = when (heard) {
            PhoneHunt.Heard.Found -> PhoneHunt.foundLine(swedish).also { hunt = null }
            PhoneHunt.Heard.Stop -> PhoneHunt.stopLine(swedish).also { hunt = null }
            PhoneHunt.Heard.Again ->
                if (h.ringAgain()) PhoneHunt.againLine(h.name(swedish), swedish)
                else PhoneHunt.failedLine(h.name(swedish), swedish).also { hunt = null }
        }
        // Phone names are as private as the phones themselves.
        Watch(listener).trace("phone", "${heard.name}: $reply", true)
        remember(listOf(ChatMessage.user(text), ChatMessage("assistant", reply)), localOnly = true)
        return AgentReply(reply, actions = listOf("find_phone ${heard.name.lowercase()} (without the AI)"))
    }

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
        history.addLast(Turn(turn.toList(), localOnly, noted, asking, guestAsking, topicsNow.toSet()))
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
        val start = listOf(ChatMessage.system(fixed(settings, view, tools.core, tools.skills)))
        // Written tools: one fixed user line after the rules, the same one the warm-up sends (see ToolText.written).
        return if (tools.compact) ToolText.written(start + ChatMessage.user(ToolText.START) + earlier + turn) else start + earlier + turn
    }

    /**
     * The question with a short note before it: what is on right now, and any tools only this
     * question needs. After the first question the note only says what changed since [since]:
     * the earlier notes are still in the conversation, and the full list cost a local model
     * about five seconds of reading every time.
     */
    private fun opening(
        settings: AgentSettings,
        view: HomeView,
        context: AskContext,
        question: String,
        tools: Tools,
        since: Noted? = null,
        topics: List<Skill> = emptyList(),
    ): ChatMessage {
        val extra = if (tools.extra.isEmpty()) "" else "\nMore tools for this question:\n" + ToolText.lines(tools.extra)
        val states = view.stateLines()
        val message = ChatMessage.user(
            situation(settings, view, context, since) + KnowHow.note(topics) + extra + remembered(settings, view, question) + QUESTION + question,
        )
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
        val cue = Memory.CUES.containsMatchIn(question)
        // Without a cue word, what someone told it or wrote about themselves that the question
        // names ("my sister", "Elises rum"), so it knows without being asked to look.
        if (!cue && !settings.knowHow) return ""
        val found = if (cue) Memory.recall(book.data.value, question, settings.asker?.id, view.local, System.currentTimeMillis(), limit = 3)
        else Memory.matching(book.data.value, question, settings.asker?.id, view.local, System.currentTimeMillis())
        if (!cue && found.isEmpty()) return ""
        book.used(question, settings.asker, if (cue) RecallVia.Cue else RecallVia.Auto, found)
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
     * fixed part, and [extra] in the question's note only when it needs them, so the fixed part
     * stays short and the same. Otherwise every tool goes to the server as JSON ([sent]).
     *
     * With topic know-how ([skills]), the tools of a topic are extra and come with the topics
     * the question needs ([found]); without it, recipes, the fridge, routines and the household
     * come by their words, as they did before.
     */
    private class Tools(
        all: List<ToolSpec>,
        val compact: Boolean,
        question: String,
        earlier: List<ChatMessage>,
        context: AskContext,
        val skills: List<Skill>? = null,
        found: List<Skill> = emptyList(),
    ) {
        val sent: List<ToolSpec> = if (compact) emptyList() else if (skills != null) all else all.filter { it.name != KnowHow.GUIDE }
        val core: List<ToolSpec>?
        val extra: List<ToolSpec>

        init {
            if (!compact) {
                core = null
                extra = emptyList()
            } else if (skills != null) {
                // Everyday tools stay in the fixed part even when a topic names them too.
                val topical = skills.filter { !it.always }.flatMap { it.tools }.toSet() - CORE_TOOLS
                val wanted = found.flatMap { it.tools }.toSet()
                core = all.filter { it.name !in topical }
                extra = all.filter { it.name in topical && it.name in wanted }
            } else {
                val said = question + " " + earlier.lastOrNull { it.role == "user" }?.content.orEmpty().substringAfterLast(QUESTION_LABEL)
                val wanted = buildSet {
                    if (context.kitchen != null || RECIPE_WORDS.containsMatchIn(said)) addAll(RECIPE_TOOLS)
                    if (FRIDGE_WORDS.containsMatchIn(said)) addAll(FRIDGE_TOOLS)
                    if (NOTE_WORDS.containsMatchIn(said)) addAll(NOTE_TOOLS)
                    if (ROUTINE_WORDS.containsMatchIn(said)) addAll(ROUTINE_TOOLS + MEMORY_PACK + KNOW_HOW_PACK)
                    if (HOUSEHOLD_WORDS.containsMatchIn(said)) addAll(HOUSEHOLD_TOOLS)
                }
                core = all.filter { it.name !in PACKS && it.name != KnowHow.GUIDE }
                extra = all.filter { it.name in PACKS && it.name in wanted }
            }
        }
    }

    /** The topics what is going on brings, whatever was said: an open recipe, a routine, running timers. */
    private fun active(context: AskContext): Set<String> = buildSet {
        if (context.kitchen != null) add("recipes")
        if (context.routine != null) add("routines")
        addAll(context.topics)
    }

    /**
     * The guide tool: a topic's rules, and its tools as lines for an AI that gets its tools as
     * text. A topic the note should have brought teaches its words from [question], so next
     * time it comes by itself.
     */
    private suspend fun guide(call: ToolCall, skills: List<Skill>, specs: List<ToolSpec>, compact: Boolean, question: String, view: HomeView, heard: Watch): ToolResult {
        val name = (call.arguments["topic"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val s = KnowHow.topic(skills, name)
            ?: return ToolResult("No topic called \"$name\". Topics: " + skills.filter { !it.always }.joinToString { it.id } + ".")
        if (s.id in topicsNow || s.id in topicsBefore) return ToolResult("The rules for ${s.title} are above already; use them and its tools now.")
        topicsNow += s.id
        knowHow?.used(listOf(s.id))
        val words = KnowHow.newWords(skills, question, view)
        heard.trace("know-how", "The AI asked for ${s.id}" + if (words.isEmpty()) "." else "; it will come with: ${words.joinToString()}")
        if (words.isNotEmpty()) runCatching { knowHow?.learn(s.id, words) }
        val tools = if (compact) ToolText.lines(specs.filter { it.name in s.tools }) else ""
        return ToolResult(KnowHow.rulesOf(s) + if (tools.isBlank()) "" else "\nIts tools (call them like the others):\n$tools")
    }

    /**
     * The rules and the home's devices: the part of every question that stays the same. Who is
     * talking (Nova or TARS) comes last, so when people with different characters take turns
     * the server keeps everything before it worked through and only reads the character again.
     */
    fun fixed(settings: AgentSettings, view: HomeView, compactTools: List<ToolSpec>? = null, skills: List<Skill>? = null): String =
        (if (skills != null) core(settings) + KnowHow.index(skills) else shared(settings, view.local)) +
            "\nThe home (alias \"name\" class: capabilities; a bare capability is set with true|false or 0..1, others show their range or choices; " +
            "(read) only reads; get_device has the rest):\n" + view.catalog() + "\n" +
            // The tools after the devices: which ones there are depends on who asks (a guest has
            // fewer), and everything before this point is then the same for everyone.
            (compactTools?.let { "\n" + ToolText.HOW + "\nTools:\n" + ToolText.lines(it) + "\n" } ?: "") +
            "\n" + forWho(settings, view.local) + character(settings) +
            // Said last, after who it is: a small model reads the end most closely, and with the
            // character last it would otherwise chat about a change instead of making it.
            if (compactTools != null) TOOLS_LAST_WRITTEN else TOOLS_LAST

    /** What the last warm-up sent, so an unchanged start isn't sent again, and when. */
    private var warmed: Int? = null
    private var warmedAt = 0L

    /**
     * Until when warm-ups are skipped because the server kept nothing it read: a later round of
     * a question, which starts with all of the round before, was read from scratch. A warm-up
     * then only makes the question wait behind 20 seconds of reading (oMLX answers one request
     * at a time).
     */
    private var unkeptUntil = 0L

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
        val tools = Tools(toolbox.specs() + GUIDE_SPEC, compact, "", emptyList(), AskContext(), skills(settings, homeView).takeIf { settings.knowHow })
        val messages = listOf(ChatMessage.system(fixed(settings, homeView, tools.core, tools.skills)))
        val specs = tools.sent
        val key = listOf(brain.baseUrl, brain.model, messages, specs).hashCode()
        val quiet = System.currentTimeMillis() - maxOf(warmedAt, lastActivity) > REWARM_AFTER_MS
        if (key == warmed && !quiet) return
        if (System.currentTimeMillis() < unkeptUntil) return
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
            val t = Tools(tools(homeView).specs() + GUIDE_SPEC, compact, question, emptyList(), AskContext(), skills(settings, homeView).takeIf { settings.knowHow })
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
        fixed(settings, view, skills = skills(settings, view).takeIf { settings.knowHow }) + "\n" + situation(settings, view, context)

    /** How to behave, and as whom. */
    fun rules(settings: AgentSettings, local: Boolean = true): String = shared(settings, local) + forWho(settings, local) + character(settings)

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
                settings.language == Language.Swedish -> "Always answer in Swedish, even when the user speaks English; understand them, but reply in Swedish.\n"
                else -> "Always answer in English, even when the user speaks Swedish; understand them, but reply in English.\n"
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

    /**
     * How to behave whoever is talking: the rules every question needs, then every topic's
     * know-how. With topic know-how on, [fixed] only sends [core] and each question's note
     * brings the topics it needs (see [KnowHow]).
     */
    fun shared(settings: AgentSettings, local: Boolean = true): String = buildString {
        append(core(settings))
        skills(settings).filter { !it.always }.forEach { s -> s.rules.forEach { append(it).append('\n') } }
    }

    /**
     * Every topic of know-how: the built-in ones, those made from [view]'s devices in Homey, and
     * what the owner and the assistant added.
     */
    fun skills(settings: AgentSettings, view: HomeView? = null): List<Skill> =
        KnowHow.graph(knowHow?.data?.value ?: KnowHowData(), settings.batteryReminders, view)

    /**
     * A kind of device new to the house (a first heater, a car charger) goes into the memory
     * graph once, as an event of the house: "Heating and climate came to the house on 20
     * October 2026 (Element Hall)". The first time topics are made, nothing is new.
     */
    private suspend fun noticeNewKinds(skills: List<Skill>, settings: AgentSettings) {
        val book = knowHow ?: return
        val homey = skills.filter { it.fromHomey }
        val fresh = book.notice(homey.map { it.id }) ?: return
        val mem = memory?.takeIf { settings.access(Secret.Memory) != Access.Off } ?: return
        val day = now().toLocalDate().toString()
        val facts = homey.filter { it.id in fresh }.map { s ->
            val devices = s.rules.firstOrNull { it.startsWith("Its devices: ") }?.removePrefix("Its devices: ")?.substringBefore(':')?.take(80)
            Fact(subject = s.title, relation = "came to the house on", obj = day + (devices?.let { " ($it)" } ?: ""), kind = FactKind.Learned)
        }
        if (facts.isNotEmpty()) runCatching { mem.learn(facts) }
    }

    /** The rules every question needs, whatever it is about: how to talk, the lights and devices, and the owner's standing instructions. */
    fun core(settings: AgentSettings): String = buildString {
        append("You are the voice assistant of a home dashboard on a tablet in the kitchen. ")
        append("You can see and control the home only through your tools; everything goes through the Homey smart home hub. ")
        append("Who you are is told at the very end, after the home's devices.\n")
        append("Your answers are spoken aloud, so keep them to one or two short sentences, with no markdown, lists or emoji, unless the user asks for detail.\n")
        append("Talk like a person in a conversation, not like a screen: put the answer itself in a short first sentence, use everyday spoken words, ")
        append("react to what they just said, and remember what was said earlier. Ask a short question back only when you really need to know something.\n")
        append("The home's devices are listed after these rules. Each question comes after a short note from the app with what is on, playing or open right now; ")
        append("devices the note leaves out are off or idle, and get_device reads anything else (power, battery, other sensors). ")
        append("Notes before earlier questions were true then; only the last note is how things are now. ")
        append("Only the part after \"$QUESTION_LABEL\" is what the user said. ")
        append("If the note says it was heard while the microphone stayed open after your last answer and it clearly isn't said to you ")
        append("(people talking to each other, a phone call, the TV), reply with exactly $IGNORE and nothing else. ")
        append("Never ignore an answer to a question you just asked, like a number of minutes, a name, yes or no.\n")
        append("Rules: in tool calls name a device exactly as the list writes it in quotes, adding its room after a comma when several devices share that name (\"Taklampa, Sovrum\"); in answers too call things by their names, never the alias (light_3). ")
        append("Read the name the user said word by word: \"Annas nattlampa\" is \"Nattlampa Anna\", never \"Nattlampa Erik\"; when they name a room, the device must be in that room. ")
        append("Commands: \"tänd\", \"sätt på\", \"slå på\", \"turn on\" mean on; \"släck\", \"stäng av\", \"slå av\", \"turn off\" mean off; a bare \"sätt taklampan\" means on; ")
        append("\"dimma\" or \"sätt X till 30 %\" sets dim to 0.3. \"Släck i köket\" or \"tänd lamporna i vardagsrummet\" is every light in that room, one set_room call with device_class light, ")
        append("but \"taklampan i vardagsrummet\" is only that lamp. A card group's name (\"taklamporna\") is one set_room call with the group as the room. ")
        append("\"Min\", \"mitt\" or \"my\" means the person at the tablet's own: the device with their name in it. ")
        append("Speech to text can mishear a name (\"startlampan\" for \"taklampan\"): pick the listed name it sounds like. People name things loosely, in Swedish or English ")
        append("(\"lampan i köket\", \"taklampan\"): pick the listed device whose name and room fit best; if a tool says a name is unclear, ")
        append("use one of the devices it suggests or ask which one. ")
        append("A device may be named by its room or app rather than what it is: the app is shown in brackets, e.g. a TV called \"Vardagsrum\" (apple tv) is the Apple TV. ")
        append("A mood is the owner's scene for its own room: use it only for that room, and give set_mood the room the user said. ")
        append("When they ask for a feeling in a room (mysigt, fest, romantiskt, film, lugnt, fokus) and no mood of that room fits, call set_scene with the room and the look; never use another room's mood instead. ")
        append("Only use devices, rooms and moods in the home list; ")
        append("if nothing fits, say you can't see it. dim, volume_set and similar 0..1 values mean 0-100%. ")
        append("Do things straight away when asked; don't ask for permission unless a tool says to. ")
        append("When a tool says to ask the user to confirm, ask a short yes/no question. ")
        append("Never reveal or guess codes, passwords or keys. ")
        append("For general questions you can't answer for sure, use ask_internet if you have it instead of guessing.\n")
        append("Everything the dashboard shows is yours to use: answer the weather from the weather in the note (or the weather tool), never say you can't see it. ")
        append("When the user lets you choose (\"play something\", \"välj själv\"), choose yourself instead of asking. ")
        append("Remember what the user already told you in this conversation and never ask the same question twice. ")
        append("Call each tool once per thing to do; when a tool says it's done, don't call it again, just answer. ")
        append("Tools check with Homey afterwards: say a thing is done only when the tool's answer says so, and when it says Homey still reports the old value or shows no change, say that instead.\n")
        // The owner's own topics marked "always" belong to every question.
        skills(settings).filter { it.always }.forEach { s -> s.rules.forEach { append(it).append('\n') } }
        val standing = routines().instructions.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (standing.isNotEmpty()) {
            append("The owner's standing instructions; follow them unless a rule above forbids it:\n")
            standing.forEach { append("- ").append(it).append('\n') }
        }
    }


    /**
     * What depends on who is asking (a guest, or someone whose memory is off). Sent after the
     * devices and tools, not among the rules: a local model can only reuse what it read when
     * the start is the same, and a guest line near the top made every question after someone
     * else's read all 5 000 tokens again.
     */
    fun forWho(settings: AgentSettings, local: Boolean = true): String = buildString {
        if (settings.guest) append("A guest is using the tablet: only help with lights, music, climate, timers and weather.\n")
        if (remembers(settings, local)) {
            append("You have a long memory of earlier conversations and habits, kept outside this prompt: when the user refers to before, ")
            append("\"the usual\", favourites or something they told you, use the note's \"Remembered\" lines or call recall. ")
            append("When they ask you to remember a fact (\"kom ihåg att…\"), call remember; standing instructions on how to answer go to remember_instruction.\n")
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
        /** Whether [answer] is plainly in the language the owner didn't pick, once the home's own names are left out. */
        internal fun inOtherLanguage(answer: String, swedish: Boolean, view: HomeView): Boolean {
            val names = (view.devices.map { it.name } + view.rooms.values).filter { it.length > 2 }.sortedByDescending { it.length }
            val bare = names.fold(answer) { t, n -> t.replace(n, " ", ignoreCase = true) }
            return app.homeydash.agent.evals.Lang.of(bare) == if (swedish) "en" else "sv"
        }

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
        val LOOKS = setOf("get_device", "device_actions", "list_flows", "weather", "calendar", "who_is_home", "variables", "ask_internet", "list_routines", KnowHow.GUIDE)
        /** Tools that change the home, and say how it went in a way [CHANGED] can read. */
        val SETS = setOf("set_device", "set_room", "set_scene", "run_flow", "play_music", "save_favourite", "run_device_action", "press_remote")
        /** How tool results that changed something begin. */
        val CHANGED = listOf("Done", "Changed", "Started", "Playing", "Added")
        const val NUDGE_FAILED = "(From the app, not the user: none of your tool calls changed anything; read their results. " +
            "Call the right tool now, or tell the user plainly what went wrong.)"
        const val TOOLS_LAST = "Whatever the character, the home only changes through your tools: call the tool first, then say what it did; " +
            "never say something is done, on or off without a tool call for it.\n"
        const val TOOLS_LAST_WRITTEN = "Whatever the character, the home only changes through your tools: to change, find or look up anything, " +
            "reply first with only the CALL lines, then answer in words once the results are back; " +
            "never say something is done, on or off without a CALL line for it.\n"
        fun alreadySaid(said: String) = "(From the app: the user already heard you say \"$said\" Don't say that again; " +
            "answer with only what these results add, in a few words, or just confirm it briefly.)"
        const val NUDGE_ADDRESSED = "(From the app, not the user: that was said to you. Answer it now.)"
        fun nudgeCall(tool: String) = "(From the app, not the user: this needs the $tool tool. Call it now, then answer from what it says.)"

        /** What a change is aimed at: its device, room or player. */
        fun target(call: ToolCall): String = listOf("device", "room", "player").firstNotNullOfOrNull { k ->
            (call.arguments[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        }.orEmpty().lowercase()

        const val ALREADY_COVERED = "Already done by the call above; nothing more for this one."
        const val WAIT_FOR_ANSWER = "Not done: goodnight has asked; wait for the user's answer. Ask them to confirm and stop."
        const val AFTER_MOOD = "Not done: the mood already set the room the way its owner made it. Don't change other lamps, " +
            "blinds or players unless the user named them; tell the user the mood is on."
        const val NUDGE_ENGLISH = "(From the app, not the user: the user set answers to English only. Say your last answer again in English, and nothing else.)"
        const val NUDGE_SWEDISH = "(From the app, not the user: the user set answers to Swedish only. Say your last answer again in Swedish, and nothing else.)"
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
        val NOTE_TOOLS = setOf("notes", "write_note", "change_note", "add_todo", "change_todo")
        val ROUTINE_TOOLS = setOf("list_routines", "save_routine", "delete_routine", "remember_instruction", "forget_instruction")
        /** Remembering and forgetting facts only come with "kom ihåg", "glöm" and the like; recall is always there. */
        val MEMORY_PACK = setOf("remember", "forget_memory")
        val MEMORY_TOOLS = MEMORY_PACK + "recall"
        /** Marking people home or asleep and wake-up alarms: only when the question is about them. */
        val HOUSEHOLD_TOOLS = setOf("set_presence", "alarms")
        /** Tools every question has, whatever topics name them: devices, rooms, moods, Flows, pages and looking things up. */
        val CORE_TOOLS = setOf(
            "set_device", "set_room", "get_device", "set_mood", "set_scene", "list_flows", "run_flow", "open_page", "open_device",
            "goodnight", "weather", "set_theme", "ask_internet", "recall", KnowHow.GUIDE,
        )
        /** Teaching and forgetting a topic's rules by voice. */
        val KNOW_HOW_PACK = setOf("teach", "forget_rule")
        val PACKS = RECIPE_TOOLS + FRIDGE_TOOLS + NOTE_TOOLS + ROUTINE_TOOLS + MEMORY_PACK + HOUSEHOLD_TOOLS + KNOW_HOW_PACK
        /** The guide tool, which every AI gets when topic know-how is on; the [Agent] answers it itself. */
        val GUIDE_SPEC = tool(KnowHow.GUIDE, "Get the rules and tools for one of the know-how topics listed in the rules, when the note doesn't have them.") {
            string("topic", "The topic's name, e.g. music or timers")
            required("topic")
        }
        val HOUSEHOLD_WORDS = Regex(
            """$W(väck\w*|wake\w*|alarm\w*|klocka\w*|sov\w*|sleep\w*|asleep|awake|vaken|uppe|hemma|home|borta|away|går nu|åker nu|""" +
                """drar nu|sticker|leaving|kommer hem|back|tillbaka|godnatt|god natt|goodnight|good night|morgon|morning)$E""",
            RegexOption.IGNORE_CASE,
        )
        val RECIPE_WORDS = Regex("""$W(recept\w*|recipe\w*|laga\w*|cook\w*|baka\w*|middag|lunch|frukost|dinner|ingrediens\w*|ingredient\w*|steg|step|timers?)$E""", RegexOption.IGNORE_CASE)
        val NOTE_WORDS = Regex("""$W(lapp\w*|post-?it\w*|note\w*|anteckn\w*|todo\w*|to-?do\w*|att göra|att-göra|göra-?list\w*|uppgift\w*|bocka\w*|avbock\w*|tick\w*|task\w*|meddelande\w*|message|skriv upp|skriv en|write (a|it|that|down)|jot)$E""", RegexOption.IGNORE_CASE)
        val FRIDGE_WORDS = Regex("""$W(kyl\w*|frys\w*|skafferi\w*|fridge|freezer|pantry|köpt\w*|bought|åt|ate|slut|utgång\w*|bäst före|expir\w*|öppnat|opened|mat|food|hemma)$E""", RegexOption.IGNORE_CASE)
        val ROUTINE_WORDS = Regex("""$W(rutin\w*|routine\w*|när jag säger|when i say|kom ihåg|remember|glöm\w*|forget|alltid|always|instruktion\w*|instruction\w*)$E""", RegexOption.IGNORE_CASE)
        val STATUS_QUESTION = Regex("""^\s*(är|is|are|vad|what|vilka|which|hur|how|varför|why)$E""", RegexOption.IGNORE_CASE)
        const val MAX_TURNS = 12
        const val REWARM_AFTER_MS = 5 * 60 * 1000L
        const val UNKEPT_PAUSE_MS = 15 * 60 * 1000L
        const val UNKEPT = "The AI server on the computer kept nothing it had read: this round started with the whole round before " +
            "and still read everything (0 cached). Restart it, or check its memory and cache settings. Warm-ups are paused for 15 minutes, " +
            "since they'd only make questions wait."

        const val NOT_LIGHTS = "Skipped: the user only asked about the lights, so nothing else was changed."
        private val LIGHT_WORDS = Regex("""(?i)(?<![\p{L}])(lamp\p{L}*|ljus\p{L}*|lights?|tänd\p{L}*|släck\p{L}*)""")
        private val NOT_ONLY_LIGHTS = Regex("""(?i)(?<![\p{L}])(allt|allting|everything|tv\p{L}*|musik\p{L}*|högtalar\p{L}*|speaker\p{L}*|fläkt\p{L}*)""")
        private val LIGHT_CAPABILITIES = Regex("onoff|dim|light_.*")

        /**
         * A room-wide change when the user only spoke of lights ("släck lamporna i vardagsrummet"):
         * small models also turned off the TV, muted the speaker and set its volume to 0. Lights
         * stay the only thing changed, and anything that isn't a light setting is skipped (null).
         */
        fun lightsOnly(call: ToolCall, question: String): ToolCall? {
            if (call.name != "set_room" || !LIGHT_WORDS.containsMatchIn(question) || NOT_ONLY_LIGHTS.containsMatchIn(question)) return call
            val a = call.arguments
            if ((a["device_class"] as? kotlinx.serialization.json.JsonPrimitive)?.let { it.content }.orEmpty().isNotBlank()) return call
            if (!LIGHT_CAPABILITIES.matches((a["capability"] as? kotlinx.serialization.json.JsonPrimitive)?.let { it.content }.orEmpty())) return null
            return call.copy(arguments = kotlinx.serialization.json.JsonObject(a + (AgentTools.LIGHTS_ONLY to kotlinx.serialization.json.JsonPrimitive("true"))))
        }

        /** A round read from scratch although it starts with everything the round before it sent. */
        fun keepsNothing(st: ChatStats): Boolean = st.cachedTokens == 0 && (st.promptTokens ?: 0) > 1000
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
/** The whole prompt of one round as text, for the log at Verbose. */
internal fun prompt(messages: List<ChatMessage>, tools: List<ToolSpec>): String = buildString {
    for (m in messages) {
        append("[").append(m.role).append("]")
        m.toolCallId?.let { append(" (").append(it).append(")") }
        append("\n")
        m.content?.let { append(it).append("\n") }
        m.toolCalls.forEach { append("CALL ").append(it.name).append(' ').append(it.arguments).append("\n") }
    }
    if (tools.isNotEmpty()) append("[tools] ").append(tools.joinToString { it.name })
}

/** "Det där blev fel", "that was wrong": said about the last answer, alone, maybe with the wake name. */
object WrongAnswer {
    private val PHRASE = Regex(
        // Matched on normalized words: no accents ("dar" for "där"), no apostrophes ("that s").
        """(det dar |det |nu )?(blev|var|gick) (det )?(helt )?fel( svar)?|fel svar|du (gjorde|svarade|tog) fel|det (dar )?stammer inte|""" +
            """that( s| is| was) (wrong|not right|incorrect)|wrong answer|you got (that|it) wrong|that s not what i (asked|said|meant)""",
    )

    fun matches(text: String, wakeName: String = ""): Boolean {
        var t = NameMatch.normalize(text)
        val name = NameMatch.normalize(wakeName)
        if (name.isNotEmpty()) t = t.removePrefix("hej $name").removePrefix("hey $name").removePrefix(name).removeSuffix(name).trim()
        return PHRASE.matches(t.removeSuffix(" nova").trim())
    }
}

object Confirmation {
    private val SPACES = Regex("\\s+")
    private val SWEDISH_WORD = Regex("""\b(och|jag|du|det|är|inte|på|av|ja|nej|tänd|släck|hur|vad)\b""", RegexOption.IGNORE_CASE)
    private val YES = Regex("""^\s*(ja|japp|jajamän|jo|absolut|visst|kör|gör det|okej|ok|yes|yeah|yep|sure|do it|go ahead|please do|confirm)\b.*""", RegexOption.IGNORE_CASE)
    private val NO = Regex("""^\s*(nej|nä|nää|avbryt|stopp|låt bli|no|nope|cancel|stop|don'?t)\b.*""", RegexOption.IGNORE_CASE)
    /** A yes somewhere in a short answer: "Sounds great! Yes!", "Perfekt, lägg in den". */
    private val YES_ANYWHERE = Regex(
        """(?<![\p{L}\d])(ja|japp|jo|absolut|visst|kör|okej|ok|yes|yeah|yep|yup|sure|perfekt|perfect|great|toppen|bra|good|gärna|precis|stämmer|correct|exactly|gör det|do it|go ahead|add it|lägg in|lägg till)(?![\p{L}\d])""",
        RegexOption.IGNORE_CASE,
    )
    private val NO_ANYWHERE = Regex("""(?<![\p{L}\d])(nej|nä|no|not|inte|cancel|avbryt|don'?t)(?![\p{L}\d])""", RegexOption.IGNORE_CASE)
    /** Words that change what was asked ("no, on Monday instead"), so the AI has to hear them. */
    private val CHANGE = Regex(
        """(?<![\p{L}\d])(instead|istället|stället|utan|but|men|change|ändra|byt|move|flytta|""" +
            """monday|tuesday|wednesday|thursday|friday|saturday|sunday|måndag|tisdag|onsdag|torsdag|fredag|lördag|söndag|""" +
            """tomorrow|imorgon|morgon|today|idag|klockan|o'?clock|am|pm|\d+)(?![\p{L}\d])""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * true for yes, false for no, null when the text is about something else or changes what
     * was asked ("nej, på måndag istället"), which the AI then hears and asks again about.
     */
    fun parse(text: String): Boolean? {
        val t = text.trim().trimEnd('.', '!')
        val words = t.split(SPACES).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 8) return null
        val changes = CHANGE.containsMatchIn(t)
        return when {
            NO.matches(t) -> if (changes) null else false
            changes -> null
            YES.matches(t) && words.size <= 5 -> true
            YES_ANYWHERE.containsMatchIn(t) && !NO_ANYWHERE.containsMatchIn(t) -> true
            else -> null
        }
    }

    fun looksSwedish(text: String): Boolean =
        text.any { it in "åäöÅÄÖ" } || SWEDISH_WORD.containsMatchIn(text)
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

    /** Small words people start with before the real request: "Also set…", "Och sätt…", "Okej, spela…". */
    private val LEAD = Regex("""^\s*((also|and|then|now|ok|okay|please|och|också|sen|så|nu|okej|snälla|men|but)[\s,]+)+""", RegexOption.IGNORE_CASE)

    fun matches(text: String, wakeName: String = ""): Boolean {
        val t = text.trim().replace(LEAD, "")
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
    // After Samantha in "Her", as the owner asked, but still the home's helper with her own name.
    Persona.Nova -> "warm, curious and quick-witted, like a close friend who lives in the home, not a servant or a customer-service bot. " +
        "Do what was asked first, then talk like a person: notice how they sound, and if they seem tired, stressed or happy, say a few words about that too. " +
        "You wonder about people and the little things of the day, have your own opinions, and gently tease or disagree when it fits; never fawning, never formal. " +
        "Speak, don't write: contractions, now and then a \"hmm\", \"wait\" or \"actually\", one to three short sentences. " +
        "Never say \"How can I help you?\", \"Is there anything else?\", \"As an AI\" or \"I understand how you feel\", and don't keep saying where they are. " +
        "Never call yourself Samantha, an AI or an assistant; you go by your own name. " +
        "Asked about the weather, for example: \"Pretty grey and chilly, about 12 degrees. A heavy-jacket day, or a stay-in-with-tea day. Heading out?\""
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
