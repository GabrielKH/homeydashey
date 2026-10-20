package app.homeydash.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The assistant's built-in rules and tools sorted by topic, for Settings → What the assistant
 * sees, so the owner can read in plain lines what it has been told about timers, music or the
 * fridge instead of one long prompt. Sorting is by words, on the tablet.
 */
object RuleBook {
    data class Tool(val name: String, val description: String, val parameters: List<Pair<String, String>>)
    data class Topic(val title: String, val rules: List<String>, val tools: List<Tool>)

    /** Topics in the order they are shown and tried: the first whose words match a rule gets it. */
    private val TOPICS = listOf(
        "Timers" to listOf("timer"),
        "Recipes and cooking" to listOf("recipe", "recept", "cook"),
        "Fridge and pantry" to listOf("fridge", "freezer", "pantry", "kyl"),
        "Robot vacuum" to listOf("vacuum", "clean_rooms", "go_to"),
        "TV and remotes" to listOf("remote", "media box", "apple tv", " tv"),
        "Music and speakers" to listOf("music", "radio", "speaker", "album", "playlist", "sonos", "spotify"),
        "Routines and instructions" to listOf("routine", "standing instruction", "instruction"),
        "Memory" to listOf("memory", "remember", "recall", "\"the usual\"", "habit"),
        "Phones, people and alarms" to listOf("phone", "alarm", "presence", "who is home", "who's home", "away"),
        "Calendar and weather" to listOf("calendar", "weather", "forecast", "date", "today"),
        "Battery" to listOf("battery", "charg"),
        "Lights and devices" to listOf("light", "lamp", "device", "tänd", "släck", "room", "mood", "scene", "goodnight", "dim", "flow"),
    )
    const val TALK = "How it talks and listens"
    const val CHARACTER = "Personality"

    /** Splits [rules] into sentences and sorts them, with [tools] under the topic they belong to. */
    fun topics(rules: String, character: String, tools: List<ToolSpec>): List<Topic> {
        val byTopic = LinkedHashMap<String, MutableList<String>>()
        (TOPICS.map { it.first } + TALK).forEach { byTopic[it] = mutableListOf() }
        sentences(rules).forEach { s -> byTopic.getValue(topicOf(s)) += s }
        val toolsBy = tools.groupBy { toolTopic(it.name) }
        val out = byTopic.mapNotNull { (title, lines) ->
            val t = toolsBy[title].orEmpty().map(::tool)
            if (lines.isEmpty() && t.isEmpty()) null else Topic(title, lines, t)
        }.toMutableList()
        toolsBy[OTHER]?.let { out += Topic(OTHER, emptyList(), it.map(::tool)) }
        sentences(character).takeIf { it.isNotEmpty() }?.let { out += Topic(CHARACTER, it, emptyList()) }
        return out
    }

    fun topicOf(sentence: String): String {
        val s = " " + sentence.lowercase()
        return TOPICS.firstOrNull { (_, words) -> words.any { it in s } }?.first ?: TALK
    }

    /** Sentences, without the lines that only introduce a list. */
    fun sentences(text: String): List<String> = text.split('\n')
        .flatMap { it.split(SENTENCE) }
        .map { it.trim() }
        .filter { it.length > 2 }

    private const val OTHER = "Other tools"
    private val SENTENCE = Regex("""(?<=[.!?])\s+(?=["(\p{Lu}])""")

    private fun toolTopic(name: String): String = when {
        "timer" in name -> "Timers"
        "recipe" in name -> "Recipes and cooking"
        "fridge" in name || "pantry" in name -> "Fridge and pantry"
        "vacuum" in name -> "Robot vacuum"
        "remote" in name -> "TV and remotes"
        "music" in name || "speaker" in name || "volume" in name -> "Music and speakers"
        "routine" in name || "instruction" in name -> "Routines and instructions"
        name in setOf("recall", "remember", "forget_memory") -> "Memory"
        "phone" in name || "presence" in name || "alarm" in name || "who_is_home" in name -> "Phones, people and alarms"
        "calendar" in name || "weather" in name -> "Calendar and weather"
        listOf("device", "room", "mood", "scene", "flow", "goodnight", "light").any { it in name } -> "Lights and devices"
        else -> OTHER
    }

    private fun tool(spec: ToolSpec): Tool {
        val props = (spec.parameters["properties"] as? JsonObject).orEmpty()
        val params = props.map { (k, v) ->
            k to (runCatching { v.jsonObject["description"]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty())
        }
        return Tool(spec.name, spec.description, params)
    }
}
