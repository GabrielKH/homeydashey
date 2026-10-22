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
    /** Why [call] wasn't run, for the AI; a [soft] one runs when the AI asks for it again. */
    data class Refusal(val text: String, val soft: Boolean = false)

    /** What to tell the AI instead of running [call] for [question], or null when it may run. */
    fun refuse(call: ToolCall, question: String, view: HomeView, tools: Collection<String> = emptyList()): Refusal? {
        val q = question.trim().replace(LEAD, "")
        val folded = NameMatch.normalize(q)
        if (call.name in RECIPE_ACTIONS && WHICH_RECIPES.containsMatchIn(folded)) return Refusal(RECIPES_ONLY)
        if ("goodnight" in tools && GOODNIGHT.containsMatchIn(folded) && (call.name in Agent.SETS || call.name == "set_presence")) return Refusal(USE_GOODNIGHT)
        if (call.name == "remove_from_fridge") {
            val item = str(call.arguments, "item")
            val said = NameMatch.tokens(q)
            val named = NameMatch.tokens(item).any { w -> said.any { NameMatch.sameWord(w, it) || (w.length >= 4 && it.contains(w)) } }
            return if (named || item.isBlank()) null else Refusal(
                "Not done: the user said \"$question\", which doesn't name $item. If they meant something else, find it with fridge_contents " +
                    "and take that out; if $item is right after all, call it again.",
                soft = true,
            )
        }
        if (call.name !in Agent.SETS) return null
        if (STATUS.containsMatchIn(q) && !ASKS_TO_CHANGE.containsMatchIn(q)) return Refusal(STATUS_ONLY)
        if (call.name == "set_room" && allRooms(call.arguments) && turnsOn(call.arguments) && !ALL.containsMatchIn(q)) return Refusal(WHICH)
        if (call.name in DEVICE_CALLS) {
            val wanted = APPLIANCES.firstOrNull { it.words.containsMatchIn(NameMatch.normalize(q)) } ?: return null
            val target = view.device(str(call.arguments, "device"))
            if (target != null && !wanted.isIn(target) && view.devices.none { wanted.isIn(it) }) {
                return Refusal("There is no ${wanted.name} in the home, so nothing was switched; ${target.name} is something else. Tell the user there's no ${wanted.name} you can reach.")
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
     * [call] as the question meant it, or null when it stands as it is: the day said in words
     * instead of a date the AI worked out (it put Saturday a week late), no "family" calendar
     * unless it was named, a pause sent to play_music, a song "set" on a speaker, two minutes
     * more for a running timer, the fridge page for "visa kylen", and "vi måste…" for everyone.
     */
    fun rewrite(call: ToolCall, question: String, tools: Collection<String>, view: HomeView? = null): ToolCall? {
        val a = call.arguments
        val folded = NameMatch.normalize(question)
        fun with(vararg pairs: Pair<String, String?>): JsonObject {
            val m = a.toMutableMap()
            for ((k, v) in pairs) if (v == null) m.remove(k) else m[k] = JsonPrimitive(v)
            return JsonObject(m)
        }
        when (call.name) {
            "calendar", "add_event" -> {
                val key = if (call.name == "calendar") "start_date" else "date"
                val day = DAY.find(question)?.value?.trim()
                val dropFamily = call.name == "calendar" && NameMatch.normalize(str(a, "calendar")) in setOf("family", "familj", "familjen") && !FAMILY.containsMatchIn(folded)
                val newDay = day?.takeIf { it != str(a, key) }
                if (newDay == null && !dropFamily) return null
                var args = a
                if (newDay != null) args = JsonObject(args + (key to JsonPrimitive(newDay)))
                if (dropFamily) args = JsonObject(args - "calendar")
                return call.copy(arguments = args)
            }
            "play_music" -> {
                val cap = str(a, "capability")
                if (cap.isBlank() || !a.containsKey("value") || "set_device" !in tools) return null
                val value = when (str(a, "value").lowercase()) { "pause", "stop", "off" -> "false"; "play", "on" -> "true"; else -> str(a, "value") }
                val device = str(a, "player").ifBlank { str(a, "device") }
                return call.copy(name = "set_device", arguments = JsonObject(mapOf("device" to JsonPrimitive(device), "capability" to JsonPrimitive(cap), "value" to JsonPrimitive(value))))
            }
            "set_device", "set_room" -> {
                if (str(a, "capability") != "speaker_playing" || "play_music" !in tools) return null
                val v = str(a, "value")
                if (v.isBlank()) return null
                if (v.lowercase() !in SWITCH_WORDS) {
                    val player = if (call.name == "set_device") str(a, "device") else view?.let { speakerIn(it, str(a, "room")) } ?: return null
                    return call.copy(name = "play_music", arguments = JsonObject(mapOf("player" to JsonPrimitive(player), "query" to JsonPrimitive(v))))
                }
                // "sätt på lite morgonjazz i vardagsrummet" as speaker_playing true: what to play is in the question.
                if (v.lowercase() !in setOf("true", "on", "play", "1", "yes") || view == null) return null
                val player = if (call.name == "set_device") view.device(str(a, "device"))?.name else speakerIn(view, str(a, "room"))
                if (player == null) return null
                val query = musicAsked(question, view, player) ?: return null
                return call.copy(name = "play_music", arguments = JsonObject(mapOf("player" to JsonPrimitive(player), "query" to JsonPrimitive(query))))
            }
            "timer" -> {
                if (str(a, "action").lowercase() !in setOf("start", "set") || !MORE_TIME.containsMatchIn(folded)) return null
                return call.copy(arguments = with("action" to "add"))
            }
            "fridge_contents" -> {
                if ("open_page" !in tools || !SHOW_FRIDGE.containsMatchIn(folded)) return null
                return call.copy(name = "open_page", arguments = JsonObject(mapOf("page" to JsonPrimitive("fridge"))))
            }
            "add_todo" -> {
                val who = NameMatch.normalize(str(a, "who"))
                if (who.isBlank() || !WE.containsMatchIn(folded)) return null
                return call.copy(arguments = with("who" to null))
            }
        }
        return null
    }

    /** The one speaker in [room], by name. */
    private fun speakerIn(view: HomeView, room: String): String? {
        val id = view.room(room) ?: return null
        return view.devices.filter { d -> view.inRoom(d, id) && d.capabilities.any { it.id == "speaker_playing" } }.singleOrNull()?.name
    }

    /** What [question] asks to play once the asking words, rooms and the speaker's name are gone; null when nothing is left. */
    private fun musicAsked(question: String, view: HomeView, player: String): String? {
        val place = (view.rooms.values + player).flatMap { NameMatch.normalize(it).split(' ') }.filter { it.length > 2 }
        val rest = question.trim().split(Regex("""\s+""")).map { it.trim(',', '.', '?', '!', '"') }.filter { w ->
            val n = NameMatch.normalize(w)
            n.isNotBlank() && n !in PLAY_WORDS && place.none { NameMatch.sameWord(n, it) || n.startsWith(it) }
        }
        return rest.joinToString(" ").ifBlank { null }
    }

    private val PLAY_WORDS = setOf(
        "satt", "pa", "spela", "lite", "nagon", "nagot", "i", "pa", "kan", "du", "snalla", "starta", "musik", "musiken", "lat", "oss", "hora", "nova", "tars",
        "put", "on", "some", "play", "the", "in", "a", "music", "please", "can", "you", "start", "let's", "lets", "us", "hear", "turn", "sonos", "hogtalaren", "speaker",
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
        if (ENDEARMENT.containsMatchIn(t) || ERRAND.containsMatchIn(t)) return true
        val asker = settings.asker?.name?.let(NameMatch::normalize)
        // Called by name with a comma ("Alex, har du…", "…ikväll, Alex?"), so "spela Alex och Sigge" isn't.
        return settings.people.values.filter { it.isNotBlank() && NameMatch.normalize(it) != asker }.any { name ->
            val n = Regex.escape(name.trim())
            Regex("""^\s*$n\s*,|,\s*$n\s*[?.!]*\s*$""", RegexOption.IGNORE_CASE).containsMatchIn(question)
        }
    }

    // Errands only a person can run ("kan du hämta barnen idag?"), so they are to someone in the room.
    private val ERRAND = Regex("""^\s*(och |men )?(kan|kommer|ska|orkar) du (hamta|kopa|handla|diska|ga ut med|ga och|lamna|skjutsa|mata)|^\s*(and |but )?(can|could|will|would) you (pick up|buy|grab|walk|take out the|take the kids|feed|do the dishes|drop off|go and)""")

    private val ENDEARMENT = Regex("""(^| )(alskling|alsklingen|gubben|gumman|hjartat|sotnos|raring|honey|darling|babe|sweetie|sweetheart)( |$)""")

    private val DAY = Regex(
        """(?<![\p{L}])((nästa|next)\s+)?(måndag|tisdag|onsdag|torsdag|fredag|lördag|söndag|monday|tuesday|wednesday|thursday|friday|saturday|sunday)|""" +
            """i övermorgon|övermorgon|day after tomorrow|i morgon|imorgon|tomorrow|i dag|idag|today|ikväll|tonight""",
        RegexOption.IGNORE_CASE,
    )
    private val FAMILY = Regex("""famil""")
    private val SWITCH_WORDS = setOf("true", "false", "on", "off", "pause", "play", "stop", "1", "0", "yes", "no")
    /** "Två minuter till på pastan", "two more minutes", "lägg till två minuter", "förläng". */
    private val MORE_TIME = Regex("""(minut\w* till|min till|minuter? extra|lagg till|forlang|more minutes?|minutes? more|extra minutes?|add \w+ minutes?|extend)""")
    private val SHOW_FRIDGE = Regex("""^\s*(visa|oppna|ta fram|show|open|pull up)( mig| me)? (kylen|kylskapet|frysen|skafferiet|the fridge|the freezer|the pantry|fridge|freezer)\s*$""")
    /** "Vi måste…", "we need to…": for everyone, not only the one who said it. */
    private val WE = Regex("""^\s*(vi (maste|behover|ska)|we (need|have|must|should))""")
    private val RECIPE_ACTIONS = setOf("open_recipe", "recipe_step", "recipe_timers")
    /** "Vad kan jag laga med kyckling?", "har vi något recept med…", "what can I cook with…": names, not cooking. */
    private val WHICH_RECIPES = Regex("""^\s*(vad kan (jag|vi) (laga|gora|koka)|har vi (nagot|nagra) recept|finns det (nagot|nagra) recept|what can (i|we) (cook|make)|do we have (a|any) recipes?|any recipes)""")
    private val GOODNIGHT = Regex("""(^| )(godnatt|god natt|good night|goodnight|nu gar vi och lagger oss|vi gar och lagger oss)( |$)""")

    const val RECIPES_ONLY = "Not done: the user only asked which recipes there are. Tell them the names from find_recipes; don't open one or start anything."
    const val USE_GOODNIGHT = "Not done: for goodnight, call the goodnight tool alone; it asks the user first and then turns things off itself."
    const val STATUS_ONLY = "The user only asked how it is right now, so nothing was changed. Answer from get_device or the device list."
    const val WHICH = "The user didn't say all of them, so nothing was switched on. Ask which lamp or room they mean."
}
