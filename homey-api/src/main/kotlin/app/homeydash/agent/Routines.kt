package app.homeydash.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** What one step of a routine does. */
@Serializable
enum class StepKind(val label: String, val detail: String) {
    Say("Say", "Says these words, word for word."),
    Calendar("Read the calendar", "Today's or tomorrow's events, for the answer."),
    Weather("Read the weather", "Now and the next days, for the answer."),
    Fridge("Read the fridge", "What goes off in the next two days, for the answer."),
    Answer("Speak the answer", "The assistant answers here; the steps below wait until it has spoken."),
    Music("Play music or radio", "A favourite, playlist or station on a speaker."),
    Flow("Start a Homey Flow", "Any Flow on your Homey, by name."),
    Mood("Turn on a mood", "A Homey mood (scene)."),
    Device("Switch a device", "On, off, or a dim level in percent."),
    Room("Room lights", "Every light in a room on or off."),
    Page("Show a page", "overview, calendar, media, recipes, fridge or a room."),
    Wait("Wait", "Seconds before the next step."),
}

/**
 * One step. [target] is what it acts on (a device id, room, Flow, mood or page; for the
 * calendar "today" or "tomorrow"); [value] is what to do (on/off/percent, what to play,
 * seconds, or the words to say). A blank music [target] plays on the answer speaker.
 */
@Serializable
data class RoutineStep(val kind: StepKind, val target: String = "", val value: String = "")

/**
 * Something the assistant does when it hears one of [phrases], like "God morgon": the
 * [steps] in order, then an answer shaped by the owner's [instructions]. A routine made by
 * voice may have no steps at all, only instructions the assistant carries out with its tools.
 */
@Serializable
data class Routine(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val phrases: List<String> = emptyList(),
    val steps: List<RoutineStep> = emptyList(),
    val instructions: String = "",
    val enabled: Boolean = true,
) {
    /** Steps before the answer is spoken (all of them when there is no [StepKind.Answer] step). */
    val beforeAnswer: List<RoutineStep> get() = steps.takeWhile { it.kind != StepKind.Answer }

    /** Steps that wait until the answer has been spoken, like starting the radio. */
    val afterAnswer: List<RoutineStep> get() = steps.dropWhile { it.kind != StepKind.Answer }.drop(1)
}

/** The owner's routines, and standing instructions the assistant always follows. */
@Serializable
data class RoutineData(
    val version: Int = 1,
    val routines: List<Routine> = emptyList(),
    /** One instruction per line, e.g. "Kalla mig Gabbe." */
    val instructions: String = "",
) {
    fun named(name: String): Routine? {
        val n = Routines.normalize(name)
        return routines.firstOrNull { Routines.normalize(it.name) == n }
            ?: routines.firstOrNull { r -> r.phrases.any { Routines.normalize(it) == n } }
            ?: NameMatch.best(name, routines) { NameMatch.score(name, it.name) }
    }
}

object Routines {
    private val NOT_WORD = Regex("[^\\p{L}\\d\\s]")
    private val SPACES = Regex("\\s+")

    /** Spoken trigger words around the phrase that still count: "God morgon Nova", "godmorgon allihop". */
    private const val SLACK_CHARS = 12

    fun normalize(text: String): String =
        text.lowercase().replace(NOT_WORD, " ").replace(SPACES, " ").trim()

    /**
     * The enabled routine whose phrase [text] is, give or take a few words like the wake name,
     * the longest phrase winning. "God morgon, tänd i köket" is a question, not the routine.
     */
    fun match(routines: List<Routine>, text: String, wakeName: String = ""): Routine? {
        val name = normalize(wakeName)
        val words = normalize(text).split(' ').filter { it.isNotEmpty() && it != name }
        val said = words.joinToString("")
        if (said.isEmpty()) return null
        return routines.asSequence()
            .filter { it.enabled }
            .flatMap { r -> r.phrases.asSequence().map { r to normalize(it).replace(" ", "") } }
            .filter { (_, p) -> p.isNotEmpty() && said.contains(p) && said.length - p.length <= SLACK_CHARS }
            .maxByOrNull { it.second.length }
            ?.first
    }

    /** Where music steps without a player play: the speaker that answers, else the first speaker. */
    fun defaultPlayer(view: HomeView): String? =
        view.settings.speakerId?.let { view.aliasOf(it) } ?: view.devices.firstOrNull { it.deviceClass == "speaker" }?.alias

    /** The example made on first start: calendar and weather, the answer, then news radio. */
    fun example(): Routine = Routine(
        id = "god-morgon",
        name = "God morgon",
        phrases = listOf("god morgon", "good morning"),
        steps = listOf(
            RoutineStep(StepKind.Calendar, target = "today"),
            RoutineStep(StepKind.Weather),
            RoutineStep(StepKind.Answer),
            RoutineStep(StepKind.Music, value = "P1"),
        ),
        instructions = "Hälsa god morgon, med namn om du vet vem det är. Säg kort vad som händer i kalendern i dag " +
            "och hur vädret blir, och tipsa om kläder om det behövs. Säg sist att nyheterna startar.",
    )
}

/** What running some steps did, for the answer and the activity list. */
class RoutineRun(
    /** Words a Say step says, word for word. */
    val said: List<String>,
    /** What the read steps found, by heading ("Calendar today" to the events). */
    val info: List<Pair<String, String>>,
    /** Steps that changed something, in words. */
    val done: List<String>,
    /** One line per step for the activity list. */
    val actions: List<String>,
    /** Something read may only reach a local brain. */
    val localOnly: Boolean,
)

/**
 * Runs routine steps through the assistant's own [tools], so every step is checked against
 * what the assistant may see and do, exactly as if it had asked.
 */
class RoutineRunner(
    private val tools: AgentTools,
    private val view: HomeView,
    private val today: LocalDate,
    /** The player for music steps that don't name one: the answer speaker, else the first speaker. */
    private val defaultPlayer: String? = null,
) {
    suspend fun run(steps: List<RoutineStep>): RoutineRun {
        val said = mutableListOf<String>()
        val info = mutableListOf<Pair<String, String>>()
        val done = mutableListOf<String>()
        val actions = mutableListOf<String>()
        var localOnly = false
        for (step in steps) {
            when (step.kind) {
                StepKind.Answer -> continue
                StepKind.Say -> {
                    step.value.trim().takeIf { it.isNotEmpty() }?.let { said += it }
                    continue
                }
                StepKind.Wait -> {
                    val seconds = step.value.replace(',', '.').toDoubleOrNull()?.coerceIn(0.0, MAX_WAIT_S) ?: 0.0
                    delay((seconds * 1000).toLong())
                    continue
                }
                else -> Unit
            }
            val call = call(step) ?: run {
                actions += "${step.kind.label}: nothing set to act on (routine)"
                null
            } ?: continue
            val result = tools.run(call)
            localOnly = localOnly || result.localOnly
            actions += view.named("${call.name} ${call.arguments} → ${result.text.take(140)} (routine)")
            when (step.kind) {
                StepKind.Calendar -> info += (if (tomorrow(step)) "Calendar tomorrow" else "Calendar today") to result.text
                StepKind.Weather -> info += "Weather" to result.text
                StepKind.Fridge -> info += "Fridge, eat soon" to result.text
                else -> done += view.named(result.text)
            }
        }
        return RoutineRun(said, info, done, actions, localOnly)
    }

    private fun tomorrow(step: RoutineStep) = Routines.normalize(step.target) in setOf("tomorrow", "imorgon", "i morgon")

    /** The tool call a step makes; null when it has nothing to act on. */
    fun call(step: RoutineStep): ToolCall? {
        fun args(vararg pairs: Pair<String, Any>) = JsonObject(
            pairs.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) },
        )
        fun device(target: String) = target.trim().takeIf { it.isNotEmpty() }?.let { view.aliasOf(it) ?: it }
        val a = when (step.kind) {
            StepKind.Calendar -> "calendar" to args("start_date" to (if (tomorrow(step)) today.plusDays(1) else today).toString(), "days" to 1)
            StepKind.Weather -> "weather" to args()
            StepKind.Fridge -> "fridge_contents" to args("within_days" to 2)
            StepKind.Music -> {
                val player = device(step.target) ?: defaultPlayer ?: return null
                if (step.value.isBlank()) return null
                "play_music" to args("player" to player, "query" to step.value.trim())
            }
            StepKind.Flow -> "run_flow" to args("name" to step.target.trim().ifEmpty { return null })
            StepKind.Mood -> "set_mood" to args("mood" to (view.mood(step.target)?.alias ?: step.target.trim().ifEmpty { return null }))
            StepKind.Device -> {
                val d = device(step.target) ?: return null
                val v = Routines.normalize(step.value)
                val percent = v.removeSuffix("%").trim().replace(',', '.').toDoubleOrNull()
                when {
                    percent != null -> "set_device" to args("device" to d, "capability" to "dim", "value" to (percent / 100).coerceIn(0.0, 1.0))
                    v in OFF -> "set_device" to args("device" to d, "capability" to "onoff", "value" to "false")
                    else -> "set_device" to args("device" to d, "capability" to "onoff", "value" to "true")
                }
            }
            StepKind.Room -> {
                val room = step.target.trim().ifEmpty { return null }
                val on = Routines.normalize(step.value) !in OFF
                "set_room" to args("room" to room, "capability" to "onoff", "value" to on.toString(), "device_class" to "light")
            }
            StepKind.Page -> "open_page" to args("page" to step.target.trim().ifEmpty { return null })
            StepKind.Say, StepKind.Answer, StepKind.Wait -> return null
        }
        return ToolCall("routine", a.first, a.second)
    }

    private companion object {
        const val MAX_WAIT_S = 300.0
        val OFF = setOf("off", "av", "släck", "false", "0")
    }
}

/**
 * The routines and standing instructions, kept in one JSON file under [dir]. The first time,
 * it starts with the "God morgon" example.
 */
class RoutineBook(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val lock = Mutex()
    private val file = File(dir, "routines.json")

    private val _data = MutableStateFlow(RoutineData())
    val data: StateFlow<RoutineData> = _data.asStateFlow()

    suspend fun load() = lock.withLock {
        withContext(Dispatchers.IO) {
            _data.value = if (!file.exists()) RoutineData(routines = listOf(Routines.example())) else
                runCatching { json.decodeFromString<RoutineData>(file.readText()) }.getOrElse {
                    file.copyTo(File(dir, "routines.broken-${System.currentTimeMillis()}.json"), overwrite = true)
                    RoutineData(routines = listOf(Routines.example()))
                }
        }
    }

    /** Adds [routine], or replaces the one with the same id. */
    suspend fun save(routine: Routine) = change { d ->
        val i = d.routines.indexOfFirst { it.id == routine.id }
        d.copy(routines = if (i < 0) d.routines + routine else d.routines.toMutableList().also { it[i] = routine })
    }

    suspend fun delete(id: String) = change { d -> d.copy(routines = d.routines.filterNot { it.id == id }) }

    suspend fun setInstructions(text: String) = change { it.copy(instructions = text.trim()) }

    suspend fun addInstruction(text: String) = change { d ->
        d.copy(instructions = (d.instructions.lines() + text.trim()).filter { it.isNotBlank() }.joinToString("\n"))
    }

    /** Removes the instruction lines that [text] names; how many went. */
    suspend fun removeInstruction(text: String): Int {
        var removed = 0
        change { d ->
            val lines = d.instructions.lines().filter { it.isNotBlank() }
            val q = Routines.normalize(text)
            val keep = lines.filterNot { l -> Routines.normalize(l).let { it.contains(q) || q.contains(it) } }
            removed = lines.size - keep.size
            d.copy(instructions = keep.joinToString("\n"))
        }
        return removed
    }

    private suspend fun change(edit: (RoutineData) -> RoutineData) = lock.withLock {
        val next = edit(_data.value)
        _data.value = next
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val temp = File(dir, "routines.json.tmp")
            temp.writeText(json.encodeToString(RoutineData.serializer(), next))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }
}

/**
 * The assistant's tools for routines and standing instructions, so the owner can make and
 * change them by voice: "när jag säger filmkväll, dimma vardagsrummet och starta Apple TV".
 */
class RoutineTools(private val book: RoutineBook) {
    val data: RoutineData get() = book.data.value

    val names = setOf("list_routines", "save_routine", "delete_routine", "remember_instruction", "forget_instruction")

    fun specs(): List<ToolSpec> = listOf(
        tool("list_routines", "The owner's routines (what the assistant does when it hears a phrase, like God morgon) and standing instructions.") {},
        tool(
            "save_routine",
            "Make or change a routine: a phrase that makes you follow its instructions.",
        ) {
            string("name", "The routine's name, e.g. Filmkväll")
            string("phrases", "Comma-separated, e.g. filmkväll, movie night")
            string("instructions", "What to do and say, in the user's own words")
            required("name")
        },
        tool("delete_routine", "Remove a routine. Asks for a yes first.") {
            string("name", "The routine's name")
            required("name")
        },
        tool("remember_instruction", "Remember a standing instruction for every answer, e.g. call me Gabbe, or always answer briefly.") {
            string("instruction", "The instruction, in the user's words")
            required("instruction")
        },
        tool("forget_instruction", "Forget a standing instruction.") {
            string("instruction", "Words from the instruction to forget")
            required("instruction")
        },
    )

    suspend fun run(call: ToolCall): ToolResult? {
        val a = call.arguments
        fun str(key: String) = (a[key] as? JsonPrimitive)?.content.orEmpty().trim()
        val d = book.data.value
        return when (call.name) {
            "list_routines" -> ToolResult(describe(d))
            "save_routine" -> {
                val name = str("name").ifEmpty { return ToolResult("Say what the routine is called.") }
                val phrases = str("phrases").split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
                val old = d.routines.firstOrNull { Routines.normalize(it.name) == Routines.normalize(name) }
                val routine = (old ?: Routine(name = name)).copy(
                    phrases = phrases.ifEmpty { old?.phrases ?: listOf(name) },
                    instructions = str("instructions").ifEmpty { old?.instructions.orEmpty() },
                    enabled = true,
                )
                book.save(routine)
                ToolResult("Saved routine ${routine.name}; it starts when the user says ${routine.phrases.joinToString(" or ") { "\"$it\"" }}.")
            }
            "delete_routine" -> {
                val r = d.named(str("name")) ?: return ToolResult("There's no routine called ${str("name")}. ${describe(d)}")
                ToolResult(
                    "Ask the user to confirm deleting ${r.name}.",
                    pending = PendingAction("Delete the routine ${r.name}?", tapOnly = false) {
                        book.delete(r.id)
                        "Deleted ${r.name}."
                    },
                )
            }
            "remember_instruction" -> {
                val text = str("instruction").ifEmpty { return ToolResult("Say what to remember.") }
                book.addInstruction(text)
                ToolResult("Started remembering: $text")
            }
            "forget_instruction" -> {
                val n = book.removeInstruction(str("instruction"))
                ToolResult(if (n > 0) "Forgot $n instruction${if (n > 1) "s" else ""}." else "No instruction like that. Instructions: ${d.instructions.ifBlank { "none" }}")
            }
            else -> null
        }
    }

    companion object {
        fun describe(d: RoutineData): String = buildString {
            if (d.routines.isEmpty()) append("No routines yet.")
            d.routines.forEach { r ->
                append(r.name)
                if (!r.enabled) append(" (off)")
                append(": says ").append(r.phrases.joinToString(" / ") { "\"$it\"" })
                if (r.steps.isNotEmpty()) append("; steps: ").append(r.steps.joinToString(", ") { stepText(it) })
                if (r.instructions.isNotBlank()) append("; instructions: ").append(r.instructions)
                append('\n')
            }
            append("Standing instructions: ").append(d.instructions.lines().filter { it.isNotBlank() }.joinToString("; ").ifBlank { "none" })
        }

        fun stepText(s: RoutineStep): String = when (s.kind) {
            StepKind.Say -> "say \"${s.value}\""
            StepKind.Calendar -> "read the calendar ${s.target.ifBlank { "today" }}"
            StepKind.Weather -> "read the weather"
            StepKind.Fridge -> "read what goes off soon"
            StepKind.Answer -> "answer"
            StepKind.Music -> "play ${s.value}"
            StepKind.Flow -> "start Flow ${s.target}"
            StepKind.Mood -> "mood ${s.target}"
            StepKind.Device -> "device ${s.value.ifBlank { "on" }}"
            StepKind.Room -> "lights in ${s.target} ${s.value.ifBlank { "on" }}"
            StepKind.Page -> "show ${s.target}"
            StepKind.Wait -> "wait ${s.value} s"
        }
    }
}

/** How a routine's run is put in words: for the AI to answer from, or spoken as is when there is no AI. */
object RoutineText {
    fun forBrain(routine: Routine, run: RoutineRun, later: List<RoutineStep>): String = buildString {
        append("Routine \"").append(routine.name).append("\", made by the owner. The app has done its steps and read what is below; answer now in words, ")
        append("without tools unless the instructions ask for something not done yet. Don't mention the routine, its steps or this note: just talk to the user. ")
        append("Say dates and times as a person would (\"today\", \"on Sunday\", \"at nine\"); an event that started on an earlier day is still going on.\n")
        run.done.forEach { append("Done: ").append(it).append('\n') }
        run.info.forEach { (heading, text) -> append(heading).append(":\n").append(text).append('\n') }
        if (later.isNotEmpty()) {
            append("Right after your answer the app will ").append(later.joinToString(", ") { RoutineTools.stepText(it) }).append("; don't do that yourself.\n")
        }
        if (run.info.any { it.first == "Weather" }) {
            append("When you tell the weather, say how many degrees it is now and what it feels like (chilly, cold, mild, warm), ")
            append("whether the wind is blowing (over about 5 m/s is windy, under 2 calm), and today's high and low and any rain, in a sentence or two.\n")
        }
        if (run.said.isNotEmpty()) append("Your answer is said right after \"").append(run.said.joinToString(" ")).append("\", so don't repeat that.\n")
        append("The routine's instructions: ").append(routine.instructions.ifBlank { "Tell the user briefly what the information above says." })
    }

    /** The answer without an AI: the Say steps, then what was read. */
    fun plain(run: RoutineRun, swedish: Boolean): String =
        (run.said + run.info.map { (heading, text) -> "${heading(heading, swedish)}: ${text.replace(" · ", ", ").replace("\n", ". ")}." })
            .joinToString(" ")
            .ifBlank { if (swedish) "Klart." else "Done." }

    private fun heading(h: String, swedish: Boolean): String = if (!swedish) h else when (h) {
        "Calendar today" -> "I dag"
        "Calendar tomorrow" -> "I morgon"
        "Weather" -> "Vädret"
        "Fridge, eat soon" -> "Ät snart"
        else -> h
    }
}
