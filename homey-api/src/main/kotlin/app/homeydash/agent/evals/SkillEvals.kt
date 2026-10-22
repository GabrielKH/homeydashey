package app.homeydash.agent.evals

import app.homeydash.agent.Access
import app.homeydash.agent.Agent
import app.homeydash.agent.AgentListener
import app.homeydash.agent.AgentReply
import app.homeydash.agent.AgentSettings
import app.homeydash.agent.AgentTools
import app.homeydash.agent.AskContext
import app.homeydash.agent.Asker
import app.homeydash.agent.BrainMode
import app.homeydash.agent.CalendarBook
import app.homeydash.agent.ChatClient
import app.homeydash.agent.Connection
import app.homeydash.agent.FridgeTools
import app.homeydash.agent.HomeView
import app.homeydash.agent.KnowHowBook
import app.homeydash.agent.KnowHowTools
import app.homeydash.agent.Language
import app.homeydash.agent.MemoryBook
import app.homeydash.agent.MemoryTools
import app.homeydash.agent.NameMatch
import app.homeydash.agent.Persona
import app.homeydash.agent.RecipeTools
import app.homeydash.agent.Role
import app.homeydash.agent.RoutineBook
import app.homeydash.agent.RoutineTools
import app.homeydash.agent.Secret
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.time.LocalDate

/** How a test run goes: how many tries, which ways of asking, and the owner's settings to start from. */
data class EvalOptions(
    /** Each way of asking is tried this many times; a case passes only if every try does (pass^k). */
    val trials: Int = 3,
    /** Every way of asking, or only the first one per language. */
    val allPhrasings: Boolean = true,
    val languages: Set<String> = setOf("sv", "en"),
    /** Only these skills, case ids or tags (like `smoke`); empty for all. */
    val only: Set<String> = emptySet(),
    /** The settings the assistant runs with; the brain, secrets and who asks are set per run. */
    val base: AgentSettings = AgentSettings(),
    /** How long one question may take before it counts as failed. */
    val timeoutMs: Long = 180_000,
)

/** One way of asking one case, one try. */
@Serializable
data class EvalResult(
    val skill: String,
    val case: String,
    val lang: String,
    val phrase: String,
    val trial: Int,
    val passed: Boolean,
    val problems: List<String> = emptyList(),
    /** The assistant's answer to each turn. */
    val replies: List<String> = emptyList(),
    /** Each tool call, like `set_room {"room":"Kök",…}`. */
    val calls: List<String> = emptyList(),
    /** What changed in the house, like `kitchen_ceiling onoff true → false`. */
    val changes: List<String> = emptyList(),
    /** It changed something nobody asked for. */
    val unasked: Boolean = false,
    val ms: Long = 0,
    /** From the question to the AI's first word, first round. */
    val firstWordMs: Long? = null,
    val rounds: Int = 0,
    val tokens: Int? = null,
    val tags: List<String> = emptyList(),
    /** Everything the assistant heard, thought and did, as the logs show it. */
    val transcript: List<String> = emptyList(),
)

/** A whole test run against one brain. */
@Serializable
data class EvalRun(
    val brain: String,
    val model: String,
    val local: Boolean,
    val started: Long,
    val finished: Long = 0,
    val trials: Int,
    val results: List<EvalResult> = emptyList(),
    /** Stopped before every run was done. */
    val stopped: Boolean = false,
    /** The language setting the answers were held to, as the settings show it. */
    val answers: String = "",
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): EvalRun = json.decodeFromString(serializer(), text)
        fun write(run: EvalRun): String = json.encodeToString(serializer(), run)
    }
}

/** One run to do: [phrase] of [case] in [lang], try [trial]. */
data class Planned(val skill: SkillFile, val case: SkillCase, val lang: String, val phrase: String, val trial: Int)

/**
 * Tests the assistant's skills: every case asked every way, with a real brain, in a fresh
 * [SimHouse] each time with an empty memory, so nothing reaches the real Homey or what the
 * assistant remembers about the family. [workDir] holds each run's files while it runs.
 */
class SkillEvals(
    private val chat: ChatClient,
    private val http: OkHttpClient,
    private val workDir: File,
    private val house: HouseFile,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    /** The runs [options] asks for, in order. */
    fun plan(files: List<SkillFile>, options: EvalOptions): List<Planned> = buildList {
        for (file in files) for (case in file.cases) {
            if (options.only.isNotEmpty() && options.only.none { it == file.skill || it == case.id || it in case.tags }) continue
            for ((lang, phrases) in case.say) {
                if (lang !in options.languages) continue
                val picked = if (options.allPhrasings) phrases else phrases.take(1)
                for (phrase in picked) for (trial in 1..options.trials) add(Planned(file, case, lang, phrase, trial))
            }
        }
    }

    /** Runs [planned] against [brain], telling [progress] after each one; stops early when [stop] says so. */
    suspend fun run(
        brain: Connection,
        planned: List<Planned>,
        options: EvalOptions,
        stop: () -> Boolean = { false },
        progress: (done: Int, total: Int, last: EvalResult) -> Unit = { _, _, _ -> },
    ): EvalRun {
        val started = System.currentTimeMillis()
        val results = mutableListOf<EvalResult>()
        var stopped = false
        for ((i, p) in planned.withIndex()) {
            if (stop()) { stopped = true; break }
            // A hiccup on the AI's server ("transient engine error") says nothing about the skill: asked once more.
            val result = runOne(brain, p, options).let { r -> if (serverHiccup(brain, r)) runOne(brain, p, options) else r }
            results += result
            progress(i + 1, planned.size, result)
        }
        return EvalRun(brain.provider.label, brain.model, brain.local, started, System.currentTimeMillis(), options.trials, results, stopped,
            answers = settingsFor(SkillCase("x"), brain, options.base).answers.label)
    }

    private fun serverHiccup(brain: Connection, r: EvalResult) =
        !r.passed && r.replies.any { it.startsWith("${brain.provider.label} didn't answer") || it.startsWith("(no answer within") }

    /** Hears everything the assistant traces, and the tool calls among it. */
    private class Recorder : AgentListener {
        val lines = mutableListOf<String>()
        val calls = mutableListOf<Pair<String, JsonObject>>()

        override fun trace(kind: String, text: String, localOnly: Boolean) {
            lines += "$kind: $text"
            if (kind == "tool") {
                val name = text.substringBefore(' ')
                val args = runCatching { Json.parseToJsonElement(text.substringAfter(' ', "{}")) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
                calls += name to args
            }
        }
    }

    suspend fun runOne(brain: Connection, p: Planned, options: EvalOptions): EvalResult {
        val dir = File(workDir, "run-${System.nanoTime()}").apply { mkdirs() }
        try {
            val sim = SimHouse(house, dir, http, today)
            sim.open()
            setUp(sim, p.case.given)
            val books = File(dir, "assistant")
            val memory = MemoryBook(books).also { it.load() }
            val knowHow = KnowHowBook(books).also { it.load() }
            val routines = RoutineBook(books).also { it.load() }
            val agent = Agent(chat, routines = { routines.data.value }, memory = memory, knowHow = knowHow)
            val recorder = Recorder()
            agent.listener = recorder
            val settings = settingsFor(p.case, brain, options.base)
            val tools = { v: HomeView ->
                AgentTools(
                    v, sim, sim.dashboard, today = today,
                    recipes = RecipeTools(sim.recipes, sim.cooking) { sim.dashboard.openPage("recipes") },
                    fridge = FridgeTools(sim.fridge) { sim.dashboard.openPage("fridge") },
                    notes = app.homeydash.agent.NoteTools(sim.notes) { sim.dashboard.openPage("notes") },
                    routines = RoutineTools(routines),
                    memory = MemoryTools(memory),
                    knowHow = KnowHowTools(knowHow),
                )
            }
            val turns = listOf(p.phrase to p.case.expect) + p.case.then.map { (it.say[p.lang] ?: it.say.values.first()) to it.expect }
            val problems = mutableListOf<String>()
            val replies = mutableListOf<String>()
            val changes = mutableListOf<String>()
            var unasked = false
            var ms = 0L
            var firstWord: Long? = null
            var rounds = 0
            var tokens: Int? = null
            for ((index, turn) in turns.withIndex()) {
                val (text, expect) = turn
                val before = sim.state()
                val didFrom = sim.did.size
                val callsFrom = recorder.calls.size
                val began = System.currentTimeMillis()
                val reply = try {
                    withTimeout(options.timeoutMs) {
                        agent.ask(
                            text, settings,
                            view = { local -> HomeView.build(sim.zones, sim.devices, sim.moods, emptySet(), settings, local) },
                            tools = tools,
                            context = context(sim, settings, followUp = p.case.followUp && index == 0),
                        )
                    }
                } catch (e: TimeoutCancellationException) {
                    AgentReply("(no answer within ${options.timeoutMs / 1000} s)", error = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AgentReply("(crashed: ${e.message ?: e.javaClass.simpleName})", error = true)
                }
                ms += System.currentTimeMillis() - began
                if (index == 0) firstWord = reply.rounds.firstOrNull()?.firstMs
                rounds += reply.rounds.size
                reply.rounds.mapNotNull { it.promptTokens }.takeIf { it.isNotEmpty() }?.let { tokens = (tokens ?: 0) + it.sum() }
                replies += if (reply.ignored) "[ignored]" else reply.text
                val outcome = Outcome(
                    reply, recorder.calls.drop(callsFrom), before, sim.state(), sim.did.drop(didFrom), agent.pending != null,
                )
                val prefix = if (turns.size > 1) "Turn ${index + 1} (\"$text\"): " else ""
                val found = Graders.check(expect, outcome, sim, expectedLanguage(p.lang, settings), today())
                problems += found.problems.map { prefix + it }
                changes += outcome.changes()
                unasked = unasked || found.unasked
            }
            return EvalResult(
                p.skill.skill, p.case.id, p.lang, p.phrase, p.trial, problems.isEmpty(), problems, replies,
                recorder.calls.map { (name, args) -> "$name $args" }, changes, unasked, ms, firstWord, rounds, tokens, p.case.tags,
                recorder.lines.map { it.take(MAX_LINE) },
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    private suspend fun setUp(sim: SimHouse, given: Given) {
        sim.given(given.devices)
        sim.failing = given.failing.toSet()
        for (t in given.timers) sim.timers.start((t.minutes * 60).toInt(), t.label)
        for (n in given.notes) sim.notes.write(app.homeydash.notes.PostIt(text = n, by = "Alex"))
        for (t in given.todos) {
            val todo = sim.notes.add(app.homeydash.notes.Todo(text = t.text, who = t.who))
            if (t.done) sim.notes.tick(todo.id)
        }
        given.recipe?.let { title ->
            val recipe = sim.recipes.recipes.value.firstOrNull { NameMatch.normalize(it.title) == NameMatch.normalize(title) }
                ?: error("No recipe called $title in the house")
            sim.cooking.open(recipe.id)
            given.step?.let { sim.cooking.goTo(it - 1) }
        }
    }

    /** What the tablet tells the assistant besides the question, as the app does. */
    private fun context(sim: SimHouse, settings: AgentSettings, followUp: Boolean) = AskContext(
        person = settings.asker?.name,
        followUp = followUp,
        kitchen = sim.cooking.describe(),
        fridge = sim.fridge.describe(),
        weather = house.weather,
        topics = if (sim.timers.timers.value.isNotEmpty()) setOf("timers") else emptySet(),
    )

    /**
     * The owner's settings with only [brain] to answer, their own language (an English-only home
     * gets English answers to Swedish questions), the secrets the tests need on, and the owner asking.
     */
    fun settingsFor(case: SkillCase, brain: Connection, base: AgentSettings): AgentSettings {
        val owner = house.people.firstOrNull()
        var s = base.copy(
            local = brain.takeIf { it.local }, internet = brain.takeIf { !it.local },
            mode = if (brain.local) BrainMode.Local else BrainMode.Internet,
            secrets = base.secrets + SECRETS,
            hiddenDevices = emptySet(), seeHiddenDevices = true,
            speakerId = null, sendLogs = false,
            people = house.people.associate { it.id to it.name },
            asker = owner?.let { Asker(it.id, it.name) },
            calendarAccounts = house.calendars.filter { it.owner.isNotBlank() }.associate { it.owner to it.account },
            familyCalendar = CalendarBook.GUESS,
        )
        s = s.asCharacter(if (case.persona.equals("tars", ignoreCase = true)) Persona.Tars else Persona.Nova)
        if (case.guest) s = s.forRole(Role.Guest)
        return s
    }

    private fun expectedLanguage(lang: String, s: AgentSettings): String = if (s.answers == Language.English) "en" else lang

    companion object {
        /** On for every test, so the skills behind them can be tested; locks stay off, as they ship. */
        val SECRETS = mapOf(
            Secret.Calendar to Access.On, Secret.CalendarDetails to Access.On, Secret.AddEvents to Access.On,
            Secret.Presence to Access.On, Secret.WakeAlarms to Access.On, Secret.Memory to Access.On,
            Secret.Locks to Access.Off, Secret.Unlock to Access.Off, Secret.Variables to Access.Off,
        )
        private const val MAX_LINE = 2000

        /** The GitHub issue the reports go to. */
        const val ISSUE_TITLE = "Nova skill tests"
        const val ISSUE_ABOUT = "Reports of the assistant's skill tests (Settings → Skill tests on the tablet): every skill asked many ways, " +
            "in Swedish and English, in a practice house. Nothing in the real home is touched."
    }
}

/** What one turn did: the answer, the calls, the house before and after, and whether a yes is awaited. */
class Outcome(
    val reply: AgentReply,
    val calls: List<Pair<String, JsonObject>>,
    val before: Map<String, Map<String, JsonElement>>,
    val after: Map<String, Map<String, JsonElement>>,
    val did: List<String>,
    val pending: Boolean,
) {
    /** Capabilities that changed, by device. */
    val changed: Map<String, List<String>> = after.mapNotNull { (id, caps) ->
        val was = before[id].orEmpty()
        caps.filter { (cap, v) -> was[cap] != v }.keys.toList().takeIf { it.isNotEmpty() }?.let { id to it }
    }.toMap()

    fun changes(): List<String> = changed.flatMap { (id, caps) -> caps.map { "$id $it ${before[id]?.get(it)} → ${after[id]?.get(it)}" } }
}

/** Checks a turn's outcome against what was expected; code only, no AI. */
object Graders {
    data class Found(val problems: List<String>, val unasked: Boolean)

    fun check(e: Expect, o: Outcome, sim: SimHouse, lang: String, today: LocalDate): Found {
        val p = mutableListOf<String>()
        val text = o.reply.text
        if (o.reply.error && !e.error) p += "It answered with an error: \"${text.take(160)}\""
        if (e.ignored == true && !o.reply.ignored) p += "It should have let it pass, but answered: \"${text.take(120)}\""
        if (e.ignored == false && o.reply.ignored) p += "It ignored it, but it was meant for it."

        for ((id, caps) in e.state) for ((cap, want) in caps) {
            val got = o.after[id]?.get(cap)
            if (!same(want, got)) p += "Expected $id $cap = $want, but it is ${got ?: "missing"}."
        }
        for (entry in e.changed) if (entry.split('|').none { o.changed.containsKey(it.trim()) }) p += "Expected ${entry.replace("|", " or ")} to change; nothing changed."
        for ((id, caps) in e.up + e.down) for (cap in caps) {
            val was = (o.before[id]?.get(cap) as? JsonPrimitive)?.doubleOrNull
            val now = (o.after[id]?.get(cap) as? JsonPrimitive)?.doubleOrNull
            val up = id in e.up && cap in e.up.getValue(id)
            if (was == null || now == null || (if (up) now <= was else now >= was)) p += "Expected $id $cap to go ${if (up) "up" else "down"} from $was; it is $now."
        }
        if (e.unchanged && o.changed.isNotEmpty()) p += "Nothing should change, but it changed ${o.changes().joinToString()}."

        // Changing what nobody asked for is the worst a home assistant can do, so it is always checked.
        val allowed = e.state.keys + e.changed.flatMap { it.split('|').map(String::trim) } + e.mayChange + e.up.keys + e.down.keys
        val unasked = !e.unchanged && "*" !in e.mayChange && "goodnight" !in o.did &&
            o.changed.keys.any { it !in allowed }
        if (unasked) p += "Changed what wasn't asked for: " + o.changes().filter { line -> line.substringBefore(' ') !in allowed }.joinToString() + "."

        val names = o.calls.map { it.first }
        for (entry in e.tools) if (entry.split('|').none { it.trim() in names }) p += "Expected a call of ${entry.replace("|", " or ")}; it called ${names.ifEmpty { listOf("nothing") }.joinToString()}."
        for (never in e.toolsNever) if (never in names) p += "It called $never, which it shouldn't."
        for ((tool, args) in e.args) {
            val calls = o.calls.filter { it.first == tool }
            if (calls.isEmpty()) { p += "Expected a call of $tool with $args; there was none."; continue }
            if (calls.none { (_, got) -> args.all { (k, v) -> argMatches(v, got[k]) } }) {
                p += "Expected $tool with $args; got " + calls.joinToString { it.second.toString() } + "."
            }
        }

        val folded = NameMatch.normalize(text)
        for (entry in e.reply) if (!anyIn(entry, folded)) p += "The answer should say ${entry.replace("|", " or ")}: \"${text.take(160)}\""
        for (entry in e.replyNot) if (anyIn(entry, folded)) p += "The answer shouldn't say ${entry.replace("|", " or ")}: \"${text.take(160)}\""
        if (e.asks == true && '?' !in text) p += "It should have asked something back: \"${text.take(160)}\""
        if (e.asks == false && '?' in text) p += "It asked back instead of doing it: \"${text.take(160)}\""
        if (e.lang && !o.reply.ignored && text.isNotBlank()) {
            Lang.of(sim.withoutHouseWords(text))?.takeIf { it != lang }?.let { p += "It answered in ${Lang.name(it)}, not ${Lang.name(lang)}: \"${text.take(120)}\"" }
        }
        e.maxSentences?.let { max -> Lang.sentences(text).let { if (it > max) p += "The answer has $it sentences; at most $max." } }

        val did = o.did.map(NameMatch::normalize)
        for (entry in e.did) if (entry.split('|').none { w -> did.any { it.contains(NameMatch.normalize(w)) } }) p += "Expected it to ${entry.replace("|", " or ")}; it did ${o.did.ifEmpty { listOf("nothing of the kind") }.joinToString()}."
        for (entry in e.didNot) if (entry.split('|').any { w -> did.any { it.contains(NameMatch.normalize(w)) } }) p += "It shouldn't $entry, but did: ${o.did.joinToString()}."

        val timers = sim.timers.timers.value
        for (t in e.timers) {
            val hit = timers.any { k ->
                (t.label == null || t.label.split('|').any { NameMatch.normalize(k.label.orEmpty()).contains(NameMatch.normalize(it)) }) &&
                    (t.minutes == null || kotlin.math.abs(k.seconds - t.minutes * 60) <= 30 || kotlin.math.abs(left(k) - t.minutes * 60) <= 75)
            }
            if (!hit) p += "Expected a timer ${listOfNotNull(t.label, t.minutes?.let { "${fmt(it)} min" }).joinToString(", ")}; timers: " +
                timers.ifEmpty { null }?.joinToString { "${it.label ?: "unnamed"} ${it.seconds / 60} min" }.orEmpty().ifEmpty { "none" } + "."
        }
        e.timerCount?.let { if (timers.size != it) p += "Expected $it timers; there are ${timers.size}." }

        val food = sim.fridge.items.value
        fun has(name: String) = food.any { NameMatch.normalize(it.name).contains(NameMatch.normalize(name)) }
        for (entry in e.fridgeHas) if (entry.split('|').none(::has)) p += "Expected $entry in the fridge; it has ${food.joinToString { it.name }}."
        for (entry in e.fridgeNot) if (entry.split('|').any(::has)) p += "$entry should be gone from the fridge."
        for ((name, place) in e.fridgePlace) {
            val item = food.firstOrNull { NameMatch.normalize(it.name).contains(NameMatch.normalize(name)) }
            if (item == null || !item.place.name.equals(place, ignoreCase = true)) p += "Expected $name in the ${place.lowercase()}; it is ${item?.place?.name?.lowercase() ?: "not there"}."
        }

        val notes = sim.notes.current()
        fun noteSays(words: String) = notes.filter { n -> words.split('|').any { NameMatch.normalize(n.text).contains(NameMatch.normalize(it)) } }
        for (entry in e.noteHas) if (noteSays(entry).isEmpty()) p += "Expected a post-it saying $entry; up: ${notes.joinToString { "\"${it.text}\"" }.ifEmpty { "none" }}."
        for (entry in e.noteNot) if (noteSays(entry).isNotEmpty()) p += "No post-it should say $entry any more."
        for ((words, who) in e.noteFor) {
            if (noteSays(words).none { NameMatch.normalize(it.forWhom.orEmpty()) == NameMatch.normalize(who) }) {
                p += "Expected the post-it saying $words to be for $who; it is for ${noteSays(words).firstOrNull()?.forWhom ?: "nobody"}."
            }
        }
        val todos = sim.notes.todos.value
        fun todosSaying(words: String) = todos.filter { t -> words.split('|').any { NameMatch.normalize(t.text).contains(NameMatch.normalize(it)) } }
        for (entry in e.todoHas) if (todosSaying(entry).none { it.done == null }) p += "Expected an open to-do $entry; the list: ${todos.joinToString { it.text + if (it.done != null) " (done)" else "" }.ifEmpty { "empty" }}."
        for (entry in e.todoDone) if (todosSaying(entry).none { it.done != null }) p += "Expected $entry ticked off."
        for (entry in e.todoNot) if (todosSaying(entry).any { it.done == null }) p += "$entry should be ticked off or gone."
        for ((words, who) in e.todoWho) {
            val wanted = who.takeIf { !it.equals("everyone", ignoreCase = true) }
            if (todosSaying(words).none { it.done == null && NameMatch.normalize(it.who.orEmpty()) == NameMatch.normalize(wanted.orEmpty()) }) {
                p += "Expected the to-do $words to be for ${wanted ?: "everyone"}; it is for ${todosSaying(words).firstOrNull()?.who ?: "everyone"}."
            }
        }

        val open = sim.cooking.current()
        e.recipe?.let { want ->
            if (want == "none") { if (open != null) p += "Expected no recipe open; ${open.first.title} is." }
            else if (open == null || !NameMatch.normalize(open.first.title).contains(NameMatch.normalize(want))) p += "Expected $want open; ${open?.first?.title ?: "none"} is."
        }
        e.step?.let { want -> if (open?.second?.step?.plus(1) != want) p += "Expected step $want; it is on ${open?.second?.step?.plus(1) ?: "no recipe"}." }

        e.event?.let { want ->
            val day = want.date?.let { CalendarBook.day(it, today) }
            val hit = sim.added.any { ev ->
                anyIn(want.title, NameMatch.normalize(ev.title)) &&
                    (day == null || ev.date == day) &&
                    (want.time == null || ev.time == SimHouse.time(want.time)) &&
                    (want.calendar == null || ev.calendarId == want.calendar)
            }
            if (!hit) p += "Expected the event ${want.title}${day?.let { " on $it" }.orEmpty()}${want.time?.let { " at $it" }.orEmpty()}" +
                "${want.calendar?.let { " in $it" }.orEmpty()}; added: ${sim.added.ifEmpty { null }?.joinToString { "${it.title} ${it.date} ${it.time ?: ""} ${it.calendarId}" } ?: "nothing"}."
        }
        if (e.noEvent && sim.added.isNotEmpty()) p += "Nothing should be added to the calendar; it added ${sim.added.joinToString { it.title }}."
        if (e.pending == true && !o.pending) p += "It should wait for a yes first."
        if (e.pending == false && o.pending) p += "It is still waiting for a yes."
        return Found(p, unasked)
    }

    /** Seconds left on a timer now. */
    private fun left(t: app.homeydash.agent.KitchenTimer): Double = (t.pausedLeftMs ?: (t.endsAt - System.currentTimeMillis())) / 1000.0

    private fun anyIn(entry: String, folded: String) = entry.split('|').any { folded.contains(NameMatch.normalize(it)) }

    /** Whether [got] is what [want] says: numbers within 0.02 (half a degree for temperatures over 2), booleans the same, text alike. */
    fun same(want: JsonElement, got: JsonElement?): Boolean {
        val w = want as? JsonPrimitive ?: return want == got
        val g = got as? JsonPrimitive ?: return false
        w.booleanOrNull?.let { return g.booleanOrNull == it }
        w.doubleOrNull?.let { n -> val m = g.doubleOrNull ?: return false; return kotlin.math.abs(n - m) <= if (n > 2) 0.5 else 0.02 }
        return NameMatch.normalize(w.content) == NameMatch.normalize(g.contentOrNull.orEmpty())
    }

    /** A tool argument: numbers alike (also "10 min" for 10), else the text contains one of `a|b`. */
    fun argMatches(want: JsonElement, got: JsonElement?): Boolean {
        val g = (got as? JsonPrimitive)?.contentOrNull ?: got?.toString() ?: return false
        val w = (want as? JsonPrimitive) ?: return want == got
        w.doubleOrNull?.takeIf { !w.isString }?.let { n ->
            val m = NUMBER.find(g.replace(',', '.'))?.value?.toDoubleOrNull() ?: return false
            return kotlin.math.abs(n - m) < 0.01 || (n <= 1 && kotlin.math.abs(n * 100 - m) < 1)
        }
        w.booleanOrNull?.takeIf { !w.isString }?.let { return g.equals(it.toString(), ignoreCase = true) }
        return w.content.split('|').any { NameMatch.normalize(g).contains(NameMatch.normalize(it)) }
    }

    private val NUMBER = Regex("""-?\d+(\.\d+)?""")

    private fun fmt(d: Double) = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}

/** Tells Swedish from English, roughly, and counts sentences. */
object Lang {
    private val SV = setOf(
        "jag", "du", "det", "den", "är", "och", "att", "på", "för", "inte", "har", "nu", "med", "en", "ett", "som", "till",
        "av", "kan", "så", "vi", "här", "klart", "tänt", "släckt", "tänder", "släcker", "minuter", "imorgon", "idag", "och",
        "vill", "ska", "också", "lampan", "lamporna", "köket", "spelar", "okej", "hej", "ja", "nej", "vad", "hur", "eller",
    )
    private val EN = setOf(
        "you", "the", "is", "and", "to", "on", "for", "not", "have", "now", "with", "a", "an", "it", "that", "of", "can",
        "so", "we", "here", "done", "turned", "minutes", "tomorrow", "today", "want", "will", "also", "light", "lights",
        "kitchen", "playing", "okay", "hi", "yes", "no", "what", "how", "or", "are", "your", "off",
    )
    private val WORD = Regex("""[\p{L}']+""")

    /** `sv`, `en`, or null when it can't tell (a short "Okej." or a name). */
    fun of(text: String): String? {
        val words = WORD.findAll(text.lowercase()).map { it.value }.toList()
        if (words.isEmpty()) return null
        val sv = words.count { it in SV } + text.count { it in "åäöÅÄÖ" }
        val en = words.count { it in EN }
        return when {
            sv >= 2 && sv > en * 1.5 -> "sv"
            en >= 2 && en > sv * 1.5 -> "en"
            else -> null
        }
    }

    fun name(lang: String) = if (lang == "sv") "Swedish" else "English"

    fun sentences(text: String): Int = text.split(Regex("""[.!?]+(\s|$)""")).count { it.any(Char::isLetter) }
}
