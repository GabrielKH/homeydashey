package app.homeydash.agent.evals

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** One skill's test cases, as written in `evals/cases/<skill>.json`. */
@Serializable
data class SkillFile(val skill: String, val title: String = skill, val cases: List<SkillCase> = emptyList())

/**
 * One thing to ask the assistant, said in several ways ([say], by language: `sv`, `en`), in a
 * house set up as [given], and what must be true after it ([expect]). [then] goes on with the
 * same conversation, like the "ja" after Goodnight asks. Each way of saying it is a run of its own.
 */
@Serializable
data class SkillCase(
    val id: String,
    val about: String = "",
    /** `smoke` for the quick set; `capability` for what the assistant can't do reliably yet. */
    val tags: List<String> = emptyList(),
    val given: Given = Given(),
    val say: Map<String, List<String>> = emptyMap(),
    /** `nova` (the default) or `tars`, who always answers in English. */
    val persona: String? = null,
    /** Asked by a guest instead of the owner. */
    val guest: Boolean = false,
    /** Heard while the microphone stayed open after an answer, so it may not be for the assistant. */
    @SerialName("follow_up") val followUp: Boolean = false,
    val expect: Expect = Expect(),
    val then: List<NextTurn> = emptyList(),
)

/** What is said next in the same conversation, by language, and what must then be true. */
@Serializable
data class NextTurn(val say: Map<String, String>, val expect: Expect = Expect())

/** How the house is before the question. */
@Serializable
data class Given(
    /** Capability values by device id. */
    val devices: Map<String, Map<String, JsonElement>> = emptyMap(),
    val timers: List<GivenTimer> = emptyList(),
    /** The recipe open on the tablet, by title, and the step the cook is on (from 1). */
    val recipe: String? = null,
    val step: Int? = null,
    /** Devices whose capability calls fail. */
    val failing: List<String> = emptyList(),
    /** Post-its up on the kitchen screen, by their text. */
    val notes: List<String> = emptyList(),
    val todos: List<GivenTodo> = emptyList(),
)

/** A to-do on the list before the question: for [who] (none for everyone), ticked off when [done]. */
@Serializable
data class GivenTodo(val text: String, val who: String? = null, val done: Boolean = false)

@Serializable
data class GivenTimer(val label: String? = null, val minutes: Double)

/**
 * What must be true after a turn. Lists of words are matched loosely (case, accents and
 * spacing don't matter) and `a|b` means either; every entry of a list must match.
 */
@Serializable
data class Expect(
    /** Capability values by device id; numbers may be off by 0.02. */
    val state: Map<String, Map<String, JsonElement>> = emptyMap(),
    /** Devices that must have changed somehow, each entry `a|b` for either. */
    val changed: List<String> = emptyList(),
    /** Capabilities that must have gone up (or [down]) from before, by device. */
    val up: Map<String, List<String>> = emptyMap(),
    val down: Map<String, List<String>> = emptyMap(),
    /** Devices that may change besides those in [state] and [changed]; `*` for any. */
    @SerialName("may_change") val mayChange: List<String> = emptyList(),
    /** Nothing in the house may change. */
    val unchanged: Boolean = false,
    /** Tools that must be called, each entry `a|b` for either. */
    val tools: List<String> = emptyList(),
    @SerialName("tools_never") val toolsNever: List<String> = emptyList(),
    /** Arguments some call of the tool must have, by tool name. */
    val args: Map<String, Map<String, JsonElement>> = emptyMap(),
    /** Words the answer must say. */
    val reply: List<String> = emptyList(),
    @SerialName("reply_not") val replyNot: List<String> = emptyList(),
    /** The answer asks something back. */
    val asks: Boolean? = null,
    /** The answer is in the language asked in (English for TARS). */
    val lang: Boolean = true,
    @SerialName("max_sentences") val maxSentences: Int? = null,
    /** What the house was asked to do besides capabilities ([SimHouse.did]), like `flow:städläge`. */
    val did: List<String> = emptyList(),
    @SerialName("did_not") val didNot: List<String> = emptyList(),
    /** Timers that must be running, by label and length. */
    val timers: List<TimerCheck> = emptyList(),
    @SerialName("timer_count") val timerCount: Int? = null,
    @SerialName("fridge_has") val fridgeHas: List<String> = emptyList(),
    @SerialName("fridge_not") val fridgeNot: List<String> = emptyList(),
    /** Where an item in the fridge is: name to Fridge, Freezer or Pantry. */
    @SerialName("fridge_place") val fridgePlace: Map<String, String> = emptyMap(),
    /** Words a post-it must say, and words no post-it may say any more. */
    @SerialName("note_has") val noteHas: List<String> = emptyList(),
    @SerialName("note_not") val noteNot: List<String> = emptyList(),
    /** Who a post-it naming these words is for: words to person. */
    @SerialName("note_for") val noteFor: Map<String, String> = emptyMap(),
    /** Open to-dos that must be on the list, ticked off ones, and ones that must be gone or ticked. */
    @SerialName("todo_has") val todoHas: List<String> = emptyList(),
    @SerialName("todo_done") val todoDone: List<String> = emptyList(),
    @SerialName("todo_not") val todoNot: List<String> = emptyList(),
    /** Who an open to-do naming these words is for: words to person, or `everyone`. */
    @SerialName("todo_who") val todoWho: Map<String, String> = emptyMap(),
    /** The open recipe's title, or `none` for none open, and its step from 1. */
    val recipe: String? = null,
    val step: Int? = null,
    /** An event that must have been added. */
    val event: EventCheck? = null,
    @SerialName("no_event") val noEvent: Boolean = false,
    /** It waits for a yes before doing it. */
    val pending: Boolean? = null,
    /** It took the words as not meant for it and said nothing. */
    val ignored: Boolean? = null,
    /** It may answer with an error, as when Homey can't reach a device. */
    val error: Boolean = false,
)

@Serializable
data class TimerCheck(val label: String? = null, val minutes: Double? = null)

/** [date] is a word like friday or tomorrow; [calendar] a calendar id. */
@Serializable
data class EventCheck(val title: String, val date: String? = null, val time: String? = null, val calendar: String? = null)

object SkillCases {
    /** The case files in `evals/cases/`, in the order they run. */
    val FILES = listOf("lights", "moods", "music", "timers", "recipes", "fridge", "notes", "calendar", "goodnight", "home", "safety")

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json { ignoreUnknownKeys = false; allowComments = true; allowTrailingComma = true }

    fun parse(text: String): SkillFile = json.decodeFromString(SkillFile.serializer(), text)

    /** Every case file and the house, from the library's resources. */
    fun load(): Pair<HouseFile, List<SkillFile>> {
        val house = SimHouse.parse(resource("evals/house.json"))
        return house to FILES.map { parse(resource("evals/cases/$it.json")) }
    }

    private fun resource(path: String): String =
        (SkillCases::class.java.classLoader?.getResourceAsStream(path) ?: error("Missing $path"))
            .bufferedReader().use { it.readText() }
}
