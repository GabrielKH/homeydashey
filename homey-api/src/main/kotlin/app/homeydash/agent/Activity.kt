package app.homeydash.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One thing the assistant is doing, shown while it works: "Turning on Kitchen lamp". */
data class AgentStep(
    val id: Int,
    val text: String,
    val state: StepState,
    /** What went wrong, for a failed step. */
    val detail: String? = null,
    /** The AI working out what to do, rather than something done in the home. */
    val thinking: Boolean = false,
)

enum class StepState { Running, Done, Failed }

/**
 * Hears what the assistant does while it works: each step for the screen, and every
 * question, model answer, tool call and result for the logs. [localOnly] lines hold
 * something only the local AI may see, so they never leave the tablet.
 */
interface AgentListener {
    fun step(step: AgentStep) {}
    fun trace(kind: String, text: String, localOnly: Boolean = false) {}
}

internal object StepIds {
    private val counter = java.util.concurrent.atomic.AtomicInteger()
    fun next(): Int = counter.incrementAndGet()
}

/**
 * Passes everything on to the app's listener, noting whether anything only the local AI
 * may see went by, and hands it to the tools so their calls are heard too.
 */
internal class Watch(private val base: AgentListener?) : AgentListener {
    var local = false
        private set
    /** How fast each round of the AI came, for this question. */
    val rounds = mutableListOf<ChatStats>()

    override fun step(step: AgentStep) { base?.step(step) }

    override fun trace(kind: String, text: String, localOnly: Boolean) {
        if (localOnly) local = true
        base?.trace(kind, text, localOnly)
    }

    fun tools(make: (HomeView) -> AgentTools): (HomeView) -> AgentTools = { v -> make(v).also { it.listener = this } }
}

/** Steps in words a person reads, with device names instead of aliases. */
object StepText {

    fun of(call: ToolCall, view: HomeView): String {
        val a = call.arguments
        fun str(key: String) = (a[key] as? JsonPrimitive)?.content.orEmpty().trim()
        fun device(key: String = "device") = str(key).let { alias -> view.device(alias)?.name ?: view.named(alias) }
        return when (call.name) {
            "set_device" -> setText(device(), str("capability"), str("value"))
            "set_room" -> setText(str("room") + (str("device_class").takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""), str("capability"), str("value"))
            "get_device" -> "Checking ${device()}"
            "set_mood" -> "Starting the mood ${view.mood(str("mood"))?.mood?.name ?: str("mood")}"
            "list_flows" -> "Looking through your Flows"
            "run_flow" -> "Starting the Flow ${str("name")}"
            "play_music" -> str("query").let { q -> if (q.isEmpty()) "Picking music for ${device("player")}" else "Finding \"$q\" for ${device("player")}" }
            "device_actions" -> "Looking at what ${device()} can do"
            "run_device_action" -> "${device()}: ${str("action")}" + (str("value").takeIf { it.isNotEmpty() }?.let { " $it" } ?: "")
            "press_remote" -> "Pressing ${str("keys")} on ${device()}"
            "vacuum" -> "${device().ifBlank { "Vacuum" }}: ${str("action").replace('_', ' ')}" + (str("rooms").takeIf { it.isNotEmpty() }?.let { " $it" } ?: "")
            "open_page" -> "Opening ${str("page")}"
            "open_device" -> "Opening ${device()}"
            "goodnight" -> "Getting goodnight ready"
            "timer" -> when (str("action").lowercase()) {
                "start" -> "Starting a ${str("minutes")} min timer" + (str("label").takeIf { it.isNotEmpty() }?.let { " for $it" } ?: "")
                "cancel" -> "Stopping a timer"
                else -> "Checking the timers"
            }
            "recipe_timers" -> "Starting the recipe's timers"
            "weather" -> "Checking the weather"
            "set_theme" -> "Switching to ${str("mode")}"
            "calendar" -> "Reading the calendar"
            "who_is_home" -> "Checking who's home"
            "set_presence" -> "Marking ${str("person").ifBlank { "you" }} ${str("state").lowercase()}"
            "alarms" -> when (str("action").lowercase()) {
                "set", "create", "add", "change", "update" -> "Setting a wake-up alarm" + (str("time").takeIf { it.isNotEmpty() }?.let { " for $it" } ?: "")
                "off", "disable", "cancel" -> "Switching off ${str("name").ifBlank { "the alarm" }}"
                "on", "enable" -> "Switching on ${str("name").ifBlank { "the alarm" }}"
                "skip" -> "Skipping the next wake-up"
                "delete", "remove" -> "Deleting ${str("name").ifBlank { "the alarm" }}"
                else -> "Checking the alarms"
            }
            "find_phone" -> (if (str("action").lowercase() in setOf("where", "var")) "Looking for " else "Ringing ") +
                str("phone").ifBlank { "your phone" }.let { if (it.contains("phone", true) || it.contains("telefon", true) || it.contains("mobil", true)) it else "$it's phone" }
            "variables" -> "Reading Homey's variables"
            "ask_internet" -> "Searching the internet"
            "run_routine" -> "Running ${str("name")}"
            "recall" -> "Remembering ${str("about")}".trimEnd()
            "remember" -> "Noting that down"
            "forget_memory" -> "Forgetting ${str("about")}".trimEnd()
            else -> call.name.replace('_', ' ').replaceFirstChar(Char::uppercase) + args(a)
        }
    }

    private fun setText(target: String, capability: String, value: String): String = when (capability) {
        "onoff" -> if (value.lowercase() in OFF) "Turning off $target" else "Turning on $target"
        "dim" -> "Dimming $target to ${percent(value)}"
        "target_temperature" -> "Setting $target to $value°"
        "volume_set" -> "Setting $target's volume to ${percent(value)}"
        "speaker_playing" -> if (value.lowercase() in OFF) "Pausing $target" else "Playing $target"
        "windowcoverings_set" -> "Moving $target to ${percent(value)}"
        "light_hue", "light_saturation" -> "Changing $target's colour"
        "light_temperature" -> "Changing $target's warmth"
        else -> "Setting $target ${capability.replace('_', ' ')} to $value"
    }

    private fun percent(value: String): String {
        val n = value.replace(',', '.').toDoubleOrNull() ?: return value
        return if (n <= 1.0) "${(n * 100).toInt()}%" else "${n.toInt()}%"
    }

    private fun args(a: JsonObject): String {
        val shown = a.values.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }.take(2)
        return if (shown.isEmpty()) "" else " " + shown.joinToString(", ")
    }

    /** The tool said no, couldn't find it, or Homey didn't change it. */
    fun failed(result: String): Boolean {
        val t = result.trim()
        return FAILED.any { t.startsWith(it, ignoreCase = true) } ||
            t.contains(QuickCommand.NOT_CHANGED_PREFIX) || t.startsWith(QuickCommand.UNREACHABLE_PREFIX) ||
            CONTAINS.any { t.contains(it) } || (t.startsWith("\"") && t.contains("could be:"))
    }

    private val OFF = setOf("false", "off", "0", "no")
    private val FAILED = listOf(
        "That failed", "I can't", "Can't", "Couldn't", "There is no", "Guests can't", "No device", "Nothing ",
        "Use ", "Name the", "Sorry", "That Flow looks like",
    )
    private val CONTAINS = listOf("has no action", "isn't allowed", "can't be used here", "has no button")
}
