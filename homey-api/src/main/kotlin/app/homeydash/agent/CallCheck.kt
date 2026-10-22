package app.homeydash.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Calls the question plainly didn't ask for, which the local AI makes often enough to show in
 * the skill tests: switching the hall lamp on when asked whether it is on, every lamp in the
 * home on for "tänd lampan", and the coffee maker for a dishwasher the home doesn't have. Each
 * is answered with what to do instead, so nothing is changed and the AI can answer or ask.
 */
internal object CallCheck {
    /** What to tell the AI instead of running [call] for [question], or null when it may run. */
    fun refuse(call: ToolCall, question: String, view: HomeView): String? {
        if (call.name !in Agent.SETS) return null
        val q = question.trim().replace(LEAD, "")
        if (STATUS.containsMatchIn(q) && !ASKS_TO_CHANGE.containsMatchIn(q)) return STATUS_ONLY
        if (call.name == "set_room" && allRooms(call.arguments) && turnsOn(call.arguments) && !ALL.containsMatchIn(q)) return WHICH
        if (call.name in DEVICE_CALLS) {
            val wanted = APPLIANCES.firstOrNull { it.words.containsMatchIn(NameMatch.normalize(q)) } ?: return null
            val target = view.device(str(call.arguments, "device"))
            if (target != null && !wanted.isIn(target) && view.devices.none { wanted.isIn(it) }) {
                return "There is no ${wanted.name} in the home, so nothing was switched; ${target.name} is something else. Tell the user there's no ${wanted.name} you can reach."
            }
        }
        return null
    }

    private class Appliance(val name: String, val words: Regex, val homeyClass: String) {
        fun isIn(d: AgentDevice): Boolean =
            d.deviceClass == homeyClass || (listOf(d.name) + d.nicknames).any { words.containsMatchIn(NameMatch.normalize(it)) }
    }

    private fun str(a: JsonObject, key: String) = (a[key] as? JsonPrimitive)?.content.orEmpty()

    private fun allRooms(a: JsonObject) = NameMatch.normalize(str(a, "room")) in setOf("all", "alla", "allt", "everything", "hela huset", "home", "hemma", "huset")

    /** On, or a level above nothing. */
    private fun turnsOn(a: JsonObject): Boolean {
        val v = str(a, "value").lowercase()
        return v == "true" || v == "on" || (v.toDoubleOrNull() ?: 0.0) > 0.0
    }

    private const val B = """(?<![\p{L}\d_])"""
    private const val E = """(?![\p{L}\d_])"""
    private val LEAD = Regex("""^\s*((och|men|så|okej|ok|and|but|so|okay)[\s,]+)+""", RegexOption.IGNORE_CASE)
    /** "Är lampan i hallen tänd?", "lyser hallampan?", "is the hall light on?" */
    private val STATUS = Regex("""^\s*(är|lyser|står|brinner|is|are)$E""", RegexOption.IGNORE_CASE)
    /** "Är det möjligt att tända…", "is it possible to turn on…": a request after all. */
    private val ASKS_TO_CHANGE = Regex(
        """$B(tända|släcka|sätta|stänga|starta|dimma|möjligt|kan du|snäll|snälla|turn|switch|possible|could you|can you|please)$E""",
        RegexOption.IGNORE_CASE,
    )
    private val ALL = Regex(
        """$B(allt|alla|överallt|hela|samtliga|all|everything|everywhere|every|whole)$E""",
        RegexOption.IGNORE_CASE,
    )
    private val DEVICE_CALLS = setOf("set_device", "run_device_action")
    private val APPLIANCES = listOf(
        Appliance("dishwasher", Regex("""${B}(diskmaskin\w*|disken|dishwasher)$E"""), "dishwasher"),
        Appliance("washing machine", Regex("""${B}(tvattmaskin\w*|tvatten|washing machine|washer)$E"""), "washer"),
        Appliance("tumble dryer", Regex("""${B}(torktumlar\w*|dryer)$E"""), "dryer"),
        Appliance("oven", Regex("""${B}(ugn\w*|oven)$E"""), "oven"),
    )

    /**
     * open_page "recipes" for "öppna lasagnen": the recipe itself is opened instead, by what
     * is left of the question once the asking words are gone. Null when nothing names one.
     */
    fun recipeInstead(call: ToolCall, question: String, tools: Collection<String>): ToolCall? {
        if (call.name != "open_page" || "open_recipe" !in tools) return null
        if (NameMatch.normalize(str(call.arguments, "page")) !in setOf("recipes", "recipe", "recept", "recepten")) return null
        val rest = NameMatch.normalize(question).split(' ').filter { it.isNotBlank() && it !in OPEN_WORDS }.joinToString(" ")
        if (rest.isBlank()) return null
        return call.copy(name = "open_recipe", arguments = JsonObject(mapOf("recipe" to JsonPrimitive(rest))))
    }

    private val OPEN_WORDS = setOf(
        "oppna", "visa", "ta", "fram", "upp", "kan", "du", "snalla", "mig", "for", "pa", "till", "av", "med", "receptet", "recept", "recepten",
        "nova", "tars", "open", "show", "pull", "bring", "up", "me", "the", "a", "please", "can", "you", "for", "of", "recipe", "recipes",
    )

    /**
     * Talk heard while the microphone stayed open that is plainly to someone else: a pet name
     * ("älskling", "honey") or another person in the home by name, and not the assistant's.
     */
    fun toSomeoneElse(question: String, settings: AgentSettings): Boolean {
        val t = NameMatch.normalize(question)
        val mine = listOf(settings.wakeName, "nova", "tars").map(NameMatch::normalize).filter { it.isNotBlank() }
        if (mine.any { Regex("""(^| )${Regex.escape(it)}( |$)""").containsMatchIn(t) }) return false
        if (ENDEARMENT.containsMatchIn(t)) return true
        val asker = settings.asker?.name?.let(NameMatch::normalize)
        // Called by name with a comma ("Alex, har du…", "…ikväll, Alex?"), so "spela Alex och Sigge" isn't.
        return settings.people.values.filter { it.isNotBlank() && NameMatch.normalize(it) != asker }.any { name ->
            val n = Regex.escape(name.trim())
            Regex("""^\s*$n\s*,|,\s*$n\s*[?.!]*\s*$""", RegexOption.IGNORE_CASE).containsMatchIn(question)
        }
    }

    private val ENDEARMENT = Regex("""(^| )(alskling|alsklingen|gubben|gumman|hjartat|sotnos|raring|honey|darling|babe|sweetie|sweetheart)( |$)""")

    const val STATUS_ONLY = "The user only asked how it is right now, so nothing was changed. Answer from get_device or the device list."
    const val WHICH = "The user didn't say all of them, so nothing was switched on. Ask which lamp or room they mean."
}
