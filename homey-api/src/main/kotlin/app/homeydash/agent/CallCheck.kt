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
    /** [say] is what to tell the user (English, Swedish) if the AI still claims it was done. */
    data class Refusal(val text: String, val soft: Boolean = false, val say: Pair<String, String>? = null)

    /** What to tell the AI instead of running [call] for [question], or null when it may run. */
    fun refuse(call: ToolCall, question: String, view: HomeView, tools: Collection<String> = emptyList(), before: String = ""): Refusal? {
        val q = question.trim().replace(LEAD, "")
        val folded = NameMatch.normalize(q)
        // What the user may be agreeing to ("ja" after "Ska jag spela något?") counts as asked too.
        val asked = if (before.isBlank()) folded else NameMatch.normalize("$q $before")
        if (call.name in RECIPE_ACTIONS && WHICH_RECIPES.containsMatchIn(folded)) return Refusal(RECIPES_ONLY)
        if ("goodnight" in tools && GOODNIGHT.containsMatchIn(folded) && (call.name in Agent.SETS || call.name == "set_presence" || call.name == "set_mood")) return Refusal(USE_GOODNIGHT)
        if (call.name == "goodnight" && !GOODNIGHT.containsMatchIn(asked)) return Refusal(NOT_GOODNIGHT)
        if (call.name == "open_page" && !SHOW.containsMatchIn(asked)) return Refusal(NO_PAGE)
        if (call.name == "open_recipe" && !SHOW.containsMatchIn(asked) && !COOK.containsMatchIn(asked)) return Refusal(NO_PAGE)
        // "Vad har jag att göra?", "vad måste vi äta upp först?": a question changes no list.
        if (call.name in LIST_CHANGES && QUESTION.containsMatchIn(folded) && !ASKS_TO_CHANGE.containsMatchIn(q)) return Refusal(JUST_ASKED)
        if (call.name == "open_device" && !SHOW.containsMatchIn(asked)) return Refusal(NO_PAGE)
        // "Kör städläget": a mode or flow, never the lights instead (also not after the flow wasn't found).
        if (call.name in setOf("set_scene", "set_room", "set_device") && MODE_WORD.containsMatchIn(folded) && !LIGHT_LEVEL.containsMatchIn(folded) && !ON_OFF.containsMatchIn(folded)) return Refusal(FLOW_NOT_LOOK)
        // A look nobody asked for ("berätta ett skämt", "sätt på morgonjazz"): the lights stay as they are.
        if (call.name == "set_scene" && !LIGHT_LEVEL.containsMatchIn(asked) && !LIGHT_WORDS.containsMatchIn(asked)) return Refusal(NO_LIGHTS)
        if (call.name == "set_scene" && MOOD_WORD.containsMatchIn(folded) && view.moods.isNotEmpty()) return Refusal(
            "Not done: the user asked for a mood. The home's moods: " + view.moods.joinToString { it.mood.name + (it.room?.let { r -> " ($r)" }.orEmpty()) } +
                ". Call set_mood with the one they mean (\"dinner\" is Middag).",
        )
        if (call.name == "play_music" && !MUSIC.containsMatchIn(asked)) return Refusal(NO_MUSIC)
        if (call.name == "set_mood") {
            val mood = view.mood(str(call.arguments, "mood"))?.mood?.name ?: str(call.arguments, "mood")
            if (!sharesWord(asked, mood)) return Refusal(
                "Not done: the user didn't name the mood $mood. For a feeling (cozy, bright) use set_scene with a look; if $mood is what they meant, call it again.",
                soft = true,
            )
        }
        if (call.name == "remove_from_fridge" || call.name == "update_fridge_item") {
            val item = str(call.arguments, "item")
            val said = NameMatch.tokens(q).flatMap { Translate.variants(NameMatch.normalize(it)) }
            // "kycklingen" is Kycklingfilé: the same start counts as well as the same word.
            val named = NameMatch.tokens(item).flatMap { Translate.variants(NameMatch.normalize(it)) }.any { w ->
                said.any { NameMatch.sameWord(w, it) || (w.length >= 4 && it.contains(w)) || (w.length >= 6 && it.length >= 6 && w.take(6) == it.take(6)) }
            }
            return if (named || item.isBlank()) null else Refusal(
                "Not done: the user said \"$question\", which doesn't name $item. Only change what they named; if it isn't in the list, " +
                    "say so and ask, and don't change anything else.",
            )
        }
        // "Släck i köket och sätt på lugn musik i vardagsrummet": the living room was only named for the music.
        if (call.name in setOf("set_device", "set_room") && str(call.arguments, "capability") in setOf("onoff", "dim")) {
            val room = if (call.name == "set_room") str(call.arguments, "room") else view.device(str(call.arguments, "device"))?.takeIf { it.deviceClass == "light" }?.room
            if (room != null && onlyForMusic(folded, room)) return Refusal(NO_LIGHTS)
        }
        if (call.name !in Agent.SETS) return null
        // "How warm is it outside?" then the heating turned up because it offered to: a question changes nothing.
        if (QUESTION.containsMatchIn(folded) && !ASKS_TO_CHANGE.containsMatchIn(q) && !ON_OFF.containsMatchIn(asked)) return Refusal(STATUS_ONLY)
        // "Vi är klara, stäng receptet": the kitchen lights aren't part of it.
        if (call.name in setOf("set_device", "set_room") && KITCHEN_TOPIC.containsMatchIn(folded) && !ON_OFF.containsMatchIn(asked.replace(CLOSE_THING, "")) && !LIGHT_LEVEL.containsMatchIn(asked) &&
            !LIGHT_WORDS.containsMatchIn(asked) && !MUSIC.containsMatchIn(asked) && !VOLUME.containsMatchIn(asked)) return Refusal(NO_LIGHTS)
        // "Stäng av musiken i köket": the music, not the kitchen lamps.
        if (call.name in setOf("set_device", "set_room") && musicAsked(asked) && !LIGHT_WORDS.containsMatchIn(asked) && !LIGHT_LEVEL.containsMatchIn(asked) &&
            str(call.arguments, "capability") in setOf("onoff", "dim") && (call.name == "set_room" || view.device(str(call.arguments, "device"))?.deviceClass == "light")) return Refusal(NO_LIGHTS)
        if (STATUS.containsMatchIn(q) && !ASKS_TO_CHANGE.containsMatchIn(q)) return Refusal(STATUS_ONLY)
        if (call.name == "set_room" && allRooms(call.arguments) && turnsOn(call.arguments) && !ALL.containsMatchIn(q)) return Refusal(WHICH, say = WHICH_SAY)
        if (call.name == "set_scene" && allRooms(call.arguments) && !ALL.containsMatchIn(q)) return Refusal(WHICH, say = WHICH_SAY)
        if (call.name in setOf("set_device", "set_room")) {
            unasked(str(call.arguments, "capability"), asked)?.let { return Refusal(it) }
            // "Tänd lampan" with several lamps and none named: which one is the user's to say.
            if (call.name in setOf("set_device", "set_room") && GENERIC_LAMP.matches(folded) && view.devices.count { it.deviceClass == "light" } > 1) return Refusal(WHICH, say = WHICH_SAY)
            // "Dimma soffbordslampan" sent to the floor lamp: the lamp the user named is the one.
            if (call.name == "set_device") {
                val target = view.device(str(call.arguments, "device"))
                val said = Translate.phrases(folded)
                // Room words don't name a device ("bedroom lamp" isn't "Persienner sovrum"): only the rest of its name does.
                val rooms = view.rooms.values.flatMap { NameMatch.normalize(it).split(' ') }.toSet()
                fun own(name: String) = NameMatch.normalize(name.substringBefore(',')).split(' ').filter { it !in rooms && it.length >= 6 }.joinToString(" ")
                val named = view.devices.filter { d -> own(d.name).isNotBlank() && sharesWord(said, own(d.name)) }
                val inNamedRoom = target?.room?.let { names(question, it, view) } == true && named.none { it.room == target.room }
                if (target != null && named.isNotEmpty() && named.none { it.alias == target.alias } && !sharesWord(said, target.name) && !inNamedRoom) {
                    return Refusal("Not done: the user named ${named.joinToString(" or ") { it.name }}, not ${target.name}. Use the one they named.")
                }
            }
        }
        if (call.name in DEVICE_CALLS) {
            val wanted = APPLIANCES.firstOrNull { it.words.containsMatchIn(NameMatch.normalize(q)) } ?: return null
            val target = view.device(str(call.arguments, "device"))
            if (target != null && !wanted.isIn(target) && view.devices.none { wanted.isIn(it) }) {
                return Refusal(
                    "There is no ${wanted.name} in the home, so nothing was switched; ${target.name} is something else. Tell the user there's no ${wanted.name} you can reach.",
                    say = "There's no ${wanted.name} I can reach, so nothing was changed." to "Jag hittar ingen sådan apparat här, så inget ändrades.",
                )
            }
        }
        return null
    }

    /** Why setting [cap] wasn't asked for in [folded], or null when it was (or may have been). */
    private fun unasked(cap: String, folded: String): String? {
        val c = cap.lowercase()
        if (c.startsWith("measure_") || c.startsWith("alarm_") || c.startsWith("meter_")) return "Not done: $cap can only be read, not set."
        if (c.startsWith("volume") && !VOLUME.containsMatchIn(folded)) return "Not done: the user didn't ask about the volume; leave it as it is."
        if (c == "target_temperature" && !HEAT.containsMatchIn(folded)) return "Not done: the user didn't ask about the temperature; leave it as it is."
        if (c.startsWith("windowcoverings") && !BLINDS.containsMatchIn(folded)) return "Not done: the user didn't ask about the blinds; leave them as they are."
        if (c in LIGHT_LOOKS && !LIGHT_LEVEL.containsMatchIn(folded)) {
            if (VOLUME.containsMatchIn(folded)) return "Not done: the user asked about the volume, not the lights; leave the lights as they are."
            if (c != "dim" && ON_OFF.containsMatchIn(folded)) return "Not done: the user only asked to switch on or off; onoff alone does that."
        }
        return null
    }

    /** Whether [question] names [target] (a device alias, room or player), in either language. */
    fun names(question: String, target: String, view: HomeView): Boolean {
        if (target.isBlank()) return false
        val name = view.rooms[target] ?: view.named(target)
        val folded = NameMatch.normalize(question)
        if (sharesWord(folded, name)) return true
        // Short names ("Kök", "Hall"): a word that starts with one, in either language ("köket", "kitchen").
        val said = folded.split(' ').flatMap { Translate.variants(it) }
        return NameMatch.normalize(name).split(' ').filter { it.length >= 3 }.any { n -> said.any { it.startsWith(n) } }
    }

    /** A word of [folded] (four letters or more) that starts a word of [name] or the other way round: "film" and "Filmkväll". */
    private fun sharesWord(folded: String, name: String): Boolean {
        val said = folded.split(' ').filter { it.length >= 4 }.flatMap { Translate.variants(it) }.filter { it.length >= 3 }
        val named = NameMatch.normalize(name).split(' ').filter { it.length >= 4 }
        return said.any { w -> named.any { n -> n.startsWith(w.take(4)) && (w.startsWith(n.take(w.length.coerceAtMost(n.length))) || n.startsWith(w)) } }
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
        // "God natt" answered with the lights one by one: the goodnight tool does it, and asks first.
        if ("goodnight" in tools && GOODNIGHT.containsMatchIn(folded) && (call.name in Agent.SETS || call.name in setOf("set_presence", "set_mood"))) {
            return call.copy(name = "goodnight", arguments = JsonObject(emptyMap()))
        }
        when (call.name) {
            "add_event" -> if (DONE_TODO.containsMatchIn(folded) && "change_todo" in tools && !DAY.containsMatchIn(question) && !CLOCK.containsMatchIn(folded)) {
                // "Jag har bokat bilservicen" is the to-do done, not a new event.
                return call.copy(name = "change_todo", arguments = JsonObject(mapOf("item" to JsonPrimitive(str(a, "title")), "action" to JsonPrimitive("done"))))
            }
            "run_device_action" -> if ("play_music" in tools && NameMatch.normalize(str(a, "action")) in setOf("radio", "play", "station", "favourite", "favorite", "play favorite", "play_favorite", "playlist", "spela") && str(a, "value").isNotBlank()) {
                return call.copy(name = "play_music", arguments = JsonObject(mapOf("player" to JsonPrimitive(str(a, "device")), "query" to JsonPrimitive(str(a, "value")))))
            }
            "find_recipes" -> if (INGREDIENTS.containsMatchIn(folded) && "read_recipe" in tools) {
                return call.copy(name = "read_recipe", arguments = JsonObject(mapOf("recipe" to JsonPrimitive(str(a, "query").ifBlank { dish(folded) }))))
            }
            "update_fridge_item" -> if (ADD.containsMatchIn(folded) && "add_to_fridge" in tools) {
                // "Lägg till ägg i kylen" is something new in the fridge, not a move.
                val place = if (Regex("""(frysen|freezer)""").containsMatchIn(folded)) "freezer" else if (Regex("""(skafferi|pantry)""").containsMatchIn(folded)) "pantry" else "fridge"
                return call.copy(name = "add_to_fridge", arguments = JsonObject(mapOf("name" to JsonPrimitive(str(a, "item").ifBlank { str(a, "name") }), "place" to JsonPrimitive(place))))
            }
            "read_recipe", "open_recipe" -> {
                // The recipe sent as "name" or "title": the tools call it recipe.
                val key = "recipe"
                val given = listOf("name", "title", "dish", "query", "recipe").filter { it != key }.map { str(a, it) }.firstOrNull { it.isNotBlank() }
                if (str(a, key).isBlank() && given != null) return call.copy(arguments = JsonObject(mapOf(key to JsonPrimitive(given))))
                if (call.name == "read_recipe" && str(a, key).isBlank() && INGREDIENTS.containsMatchIn(folded)) return call.copy(arguments = JsonObject(mapOf(key to JsonPrimitive(dish(folded)))))
                if (call.name == "read_recipe" && INGREDIENTS.containsMatchIn(folded) && Regex("""(^| )(need|behover|for|till|ingredients|ingredienser)( |$)""").containsMatchIn(NameMatch.normalize(str(a, "recipe")))) {
                    // "need for the pancakes" as the recipe: the dish is what follows "for".
                    return call.copy(arguments = with("recipe" to dish(folded)))
                }
            }
            "remove_from_fridge" -> if (str(a, "item").isBlank() && str(a, "name").isBlank()) {
                // "Mjölken är slut" with no item: what is finished.
                val item = GONE.find(folded)?.let { m -> m.groupValues.drop(1).firstOrNull { it.isNotBlank() } }?.replace(Regex("""^(the|our|my|all the) """), "")?.trim()
                if (!item.isNullOrBlank()) return call.copy(arguments = with("item" to item))
            }
            "run_flow" -> {
                // "Kör städläget" run as "Rengöring": the flow is the one the user named.
                val said = MODE_NAME.find(question)?.value
                if (said != null && !sharesWord(folded, str(a, "name"))) return call.copy(arguments = with("name" to said))
            }
            "change_todo" -> if (str(a, "item").isBlank() && str(a, "action").lowercase() !in setOf("clear")) {
                // "Bocka av bilservice" with no item: the words after the tick-off.
                val item = (listOf("todo", "text", "name", "title", "task").map { str(a, it) }.firstOrNull { it.isNotBlank() }
                    ?: TICK.find(folded)?.groupValues?.get(2)?.takeUnless { it.trim() in setOf("it", "den", "det", "") }
                    ?: DID.find(folded)?.groupValues?.get(4))?.trim()?.trim('?', '.', '!')
                val action = str(a, "action").lowercase().takeIf { it in setOf("done", "undo", "edit", "remove", "clear") } ?: "done"
                if (!item.isNullOrBlank()) return call.copy(arguments = JsonObject(mapOf("item" to JsonPrimitive(item), "action" to JsonPrimitive(action))))
            }
            "change_note" -> if (str(a, "action").isBlank() && REMOVE.containsMatchIn(folded) && str(a, "text").isBlank()) {
                // "Ta bort lappen om blommorna" sent without an action: it is taken down, not edited.
                val note = str(a, "note").ifBlank { listOf("text", "item", "name", "title").map { str(a, it) }.firstOrNull { it.isNotBlank() } ?: ABOUT.find(folded)?.groupValues?.get(2).orEmpty() }
                if (note.isNotBlank()) return call.copy(arguments = JsonObject(mapOf("note" to JsonPrimitive(note), "action" to JsonPrimitive("remove"))))
            } else if (str(a, "note").isBlank()) {
                val note = listOf("text", "item", "name", "title").map { str(a, it) }.firstOrNull { it.isNotBlank() && str(a, "action") != "edit" } ?: ABOUT.find(folded)?.groupValues?.get(2)
                if (!note.isNullOrBlank()) return call.copy(arguments = with("note" to note))
            }
            "add_to_fridge" -> if (a.entries.any { (k, v) -> k in DATE_KEYS && (v as? JsonPrimitive)?.content?.matches(ISO_DAY) == false }) {
                // "bought": "now" isn't a date; left out, the tool takes today.
                return call.copy(arguments = JsonObject(a.filterNot { (k, v) -> k in DATE_KEYS && (v as? JsonPrimitive)?.content?.matches(ISO_DAY) == false }))
            } else if (MOVE.containsMatchIn(folded) && !BOUGHT.containsMatchIn(folded) && !ADD.containsMatchIn(folded) && "update_fridge_item" in tools) {
                return call.copy(name = "update_fridge_item", arguments = JsonObject(mapOf("item" to JsonPrimitive(str(a, "name")), "place" to JsonPrimitive(str(a, "place").ifBlank { "freezer" }))))
            }
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
                // A pause sent as play_music's kind or as an empty query: the speaker is paused.
                val kind = NameMatch.normalize(str(a, "kind"))
                if ("set_device" in tools && (kind in setOf("pause", "stop", "paus") || (str(a, "query").isBlank() && PAUSE.containsMatchIn(folded)))) {
                    // No speaker given: the one in the room the user named.
                    val player = str(a, "player").ifBlank { str(a, "device") }.ifBlank {
                        view?.let { v -> v.rooms.values.firstOrNull { names(question, it, v) }?.let { speakerIn(v, it) } }.orEmpty()
                    }
                    if (player.isBlank()) return null
                    return call.copy(name = "set_device", arguments = JsonObject(mapOf("device" to JsonPrimitive(player), "capability" to JsonPrimitive("speaker_playing"), "value" to JsonPrimitive("false"))))
                }
                // "morning jazz living room": the room is where to play, not what.
                val query = str(a, "query")
                val bare = withoutPlace(query, view)
                if (bare != query && bare.isNotBlank() && str(a, "capability").isBlank()) return call.copy(arguments = with("query" to bare))
                val cap = str(a, "capability")
                if (cap.isBlank() || !a.containsKey("value") || "set_device" !in tools) return null
                val value = when (str(a, "value").lowercase()) { "pause", "stop", "off" -> "false"; "play", "on" -> "true"; else -> str(a, "value") }
                val device = str(a, "player").ifBlank { str(a, "device") }
                return call.copy(name = "set_device", arguments = JsonObject(mapOf("device" to JsonPrimitive(device), "capability" to JsonPrimitive(cap), "value" to JsonPrimitive(value))))
            }
            "set_device", "set_room" -> {
                // "Turn off all the lights" room by room: the whole house in one call, so none is missed.
                if (call.name == "set_room" && !allRooms(a) && ALL.containsMatchIn(folded) && !turnsOn(a) && str(a, "capability") in setOf("onoff", "dim")) {
                    return call.copy(arguments = with("room" to "all"))
                }
                // "pause" for speaker_playing: it is a switch.
                if (str(a, "capability") == "speaker_playing" && str(a, "value").lowercase() in setOf("pause", "stop", "off", "paus")) return call.copy(arguments = with("value" to "false"))
                if (str(a, "capability") == "speaker_playing" && str(a, "value").lowercase() in setOf("play", "on", "resume")) return call.copy(arguments = with("value" to "true"))
                // "light", "on" or "power" for a switch: it is called onoff.
                if (NameMatch.normalize(str(a, "capability")) in SWITCH_NAMES) return call.copy(arguments = with("capability" to "onoff", "device_class" to str(a, "device_class").takeUnless { it == "dim" || it.isBlank() }))
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
                val query = musicAsked(question, view, player)?.let { withoutPlace(it, view).ifBlank { it } } ?: return null
                return call.copy(name = "play_music", arguments = JsonObject(mapOf("player" to JsonPrimitive(player), "query" to JsonPrimitive(query))))
            }
            "timer" -> {
                if (str(a, "action").lowercase() !in setOf("start", "set") || !MORE_TIME.containsMatchIn(folded)) return null
                return call.copy(arguments = with("action" to "add"))
            }
            "fridge_contents", "get_device" -> {
                // The fridge looked up as a device ("Fridge"): it is the fridge tool.
                if (call.name == "get_device" && "fridge_contents" in tools && NameMatch.normalize(str(a, "device")) in FRIDGE_NAMES && !INGREDIENTS.containsMatchIn(folded)) {
                    return call.copy(name = "fridge_contents", arguments = JsonObject(emptyMap()))
                }
                // A query the user never said ("soonest to go off") finds nothing; "all" is no place.
                if (call.name == "fridge_contents") {
                    val query = NameMatch.normalize(str(a, "query"))
                    val said = Translate.let { folded.split(' ').flatMap { w -> it.variants(w) } }
                    val madeUp = query.isNotBlank() && query.split(' ').any { w -> w.length > 2 && said.none { NameMatch.sameWord(it, w) } }
                    val noPlace = str(a, "place").isNotBlank() && NameMatch.normalize(str(a, "place")) !in PLACES
                    // "Vad går ut snart?": what's within a few days, past dates first, not the whole list.
                    if (!a.containsKey("within_days") && SOON.containsMatchIn(folded) && !INGREDIENTS.containsMatchIn(folded) && !madeUp && !noPlace) {
                        return call.copy(arguments = with("within_days" to "3"))
                    }
                    val allDays = a.containsKey("within_days") && !SOON.containsMatchIn(folded) && !INGREDIENTS.containsMatchIn(folded)
                    if (madeUp || noPlace || allDays) {
                        val m = a.toMutableMap()
                        if (madeUp) m.remove("query")
                        if (noPlace) m.remove("place")
                        if (allDays) m.remove("within_days")
                        return call.copy(arguments = JsonObject(m))
                    }
                }
                // "Vad behöver jag till pannkakorna?" is the recipe's, not the fridge's.
                if (INGREDIENTS.containsMatchIn(folded) && "read_recipe" in tools) {
                    return call.copy(name = "read_recipe", arguments = JsonObject(mapOf("recipe" to JsonPrimitive(dish(folded)))))
                }
                if (WHICH_RECIPES.containsMatchIn(folded) && "find_recipes" in tools) {
                    return call.copy(name = "find_recipes", arguments = JsonObject(mapOf("query" to JsonPrimitive(WITH.find(folded)?.groupValues?.get(2).orEmpty()))))
                }
                if (call.name == "get_device") return null
                if ("open_page" in tools && SHOW_FRIDGE.containsMatchIn(folded)) {
                    return call.copy(name = "open_page", arguments = JsonObject(mapOf("page" to JsonPrimitive("fridge"))))
                }
                // A model filling in every field sends within_days 0, which leaves out all but what's past its date.
                if (a.containsKey("within_days") && !SOON.containsMatchIn(folded)) return call.copy(arguments = with("within_days" to null))
                return null
            }
            "update_fridge_item" -> {
                if ("remove_from_fridge" !in tools) return null
                if (str(a, "quantity").toDoubleOrNull() != 0.0 && !FINISHED.containsMatchIn(folded)) return null
                return call.copy(name = "remove_from_fridge", arguments = JsonObject(mapOf("item" to JsonPrimitive(str(a, "item")))))
            }
            "set_scene" -> {
                // "Dags för film" with a mood called Filmkväll: the owner's own mood, not a made-up look.
                val v = view ?: return null
                if ("set_mood" !in tools) return null
                val mood = v.moods.singleOrNull { sharesWord(folded, it.mood.name) } ?: return null
                return call.copy(name = "set_mood", arguments = JsonObject(mapOf("mood" to JsonPrimitive(mood.mood.name))))
            }
            "add_todo" -> {
                val who = NameMatch.normalize(str(a, "who"))
                if (who.isBlank()) return null
                // "Vi måste…", or the asker filled in for a to-do that names nobody: it's everyone's.
                val asker = view?.settings?.asker?.name?.let(NameMatch::normalize)
                val forAsker = who == asker && !ME.containsMatchIn(folded) && !Regex("""(^| )${Regex.escape(who)}( |$)""").containsMatchIn(folded)
                if (!WE.containsMatchIn(folded) && !forAsker) return null
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
     * A call to a tool that doesn't exist, as the tool meant: "player_1 {device, capability,
     * value}" is set_device, "update_fridge" is update_fridge_item. Null when it is a real
     * tool or nothing plainly fits.
     */
    fun renamed(call: ToolCall, tools: Collection<String>): ToolCall? {
        if (call.name in tools || tools.isEmpty()) return null
        val a = call.arguments
        // A name close to a tool first ("get_fridge_contents"; "update_todo" and "edit_note" are the change_ tools)…
        fun near(n: String) = tools.filter { it.startsWith(n) || (n.startsWith(it) && it.length >= 5) }
            .ifEmpty { tools.filter { it.length >= 5 && n.removePrefix("get_").removePrefix("read_").removePrefix("list_") == it } }
        val n = call.name.lowercase()
        near(n).ifEmpty { near(n.replace(Regex("""^(update|edit|modify|mark|complete|tick)_"""), "change_")) }.singleOrNull()?.let { name ->
            // …without the device-style fields it was given on the way ("capability": "contents").
            val kept = if (name in setOf("set_device", "set_room", "get_device")) a else JsonObject(a - setOf("capability", "value", "device"))
            return call.copy(name = name, arguments = kept)
        }
        // A device's name as the tool ("player_1" with a capability): set it.
        if (a.containsKey("capability")) {
            val name = if (a.containsKey("device") || !a.containsKey("room")) "set_device" else "set_room"
            if (name !in tools) return null
            return call.copy(name = name, arguments = if (name == "set_device" && !a.containsKey("device")) JsonObject(a + ("device" to JsonPrimitive(call.name))) else a)
        }
        return null
    }

    /** [room] is named in [folded] only in parts about music, none about the lights. */
    private fun onlyForMusic(folded: String, room: String): Boolean {
        val r = NameMatch.normalize(room).takeIf { it.length >= 3 } ?: return false
        val stem = r.take(maxOf(3, r.length - 1))
        val parts = folded.split(CLAUSE).filter { p -> p.split(' ').any { w -> w.startsWith(stem) } }
        return parts.isNotEmpty() && parts.all { MUSIC_THING.containsMatchIn(it) && !LIGHT_WORDS.containsMatchIn(it) && !LIGHT_LEVEL.containsMatchIn(it) }
    }
    /** About music: something to listen to, or a music verb other than "sätt på" ("sätt på i vardagsrummet" is the lamps). */
    private fun musicAsked(folded: String) = MUSIC_THING.containsMatchIn(folded) || MUSIC.containsMatchIn(folded.replace(TURN_ON, " "))
    private val TURN_ON = Regex("""satt(a)? pa|starta|kor""")
    private val CLAUSE = Regex(""" (och|and|samt|men|but) |[,;.]""")
    private val MUSIC_THING get() = Cues.regex("music_thing")

    /**
     * The tool a question plainly needs, for when the AI answered without any: today's plans
     * from the calendar, not the weather; what's in the fridge from the fridge. Null when it
     * may well be answered in words, or the tool isn't there.
     */
    fun shouldCall(question: String, tools: Collection<String>, answer: String? = null): String? {
        val f = NameMatch.normalize(question)
        MUST_CALL.firstOrNull { (tool, cue) -> tool in tools && Cues.regex(cue).containsMatchIn(f) }?.let { return it.first }
        // "Är lampan i hallen tänd?" is answered from the note of what's on; only "there's no lamp there" needs a look.
        if ("get_device" in tools && answer != null && Cues.regex("needs_get_device").containsMatchIn(f) &&
            Cues.regex("denies_device").containsMatchIn(NameMatch.normalize(answer))
        ) return "get_device"
        return null
    }

    private val GOODNIGHT get() = Cues.regex("goodnight")
    /** Which tool each kind of question needs; the words are cues in the brain ([Cues]). */
    private val MUST_CALL = listOf(
        "read_recipe" to "needs_read_recipe",
        "calendar" to "needs_calendar",
        "fridge_contents" to "needs_fridge_contents",
        "add_to_fridge" to "needs_add_to_fridge",
        "remove_from_fridge" to "needs_remove_from_fridge",
        "write_note" to "needs_write_note",
        "goodnight" to "goodnight",
        "run_flow" to "needs_run_flow",
        "open_page" to "needs_open_page",
        "open_recipe" to "needs_open_recipe",
        "update_fridge_item" to "needs_update_fridge_item",
        "timer" to "needs_timer",
        "change_todo" to "needs_change_todo",
        "add_todo" to "needs_add_todo",
        "notes" to "needs_notes",
        "close_recipe" to "needs_close_recipe",
    )

    /** [query] without the room it was to play in ("morning jazz living room", "jazz i köket"). */
    private fun withoutPlace(query: String, view: HomeView?): String {
        var q = query.trim()
        val places = (view?.rooms?.values.orEmpty() + ROOM_WORDS).sortedByDescending { it.length }
        for (p in places) q = q.replace(Regex("""\s+((i|in|on|pa|the)\s+)*(the\s+)?${Regex.escape(p)}\w*\s*$""", RegexOption.IGNORE_CASE), "")
        return q.replace(Regex("""^(the|some|lite)\s+""", RegexOption.IGNORE_CASE), "").trim()
    }

    /** The dish a question about ingredients is about: what follows its last "till", "för", "i" or "for" ("pannkakorna"). */
    private fun dish(folded: String): String {
        // The greedy start finds the last one: "what do i need for the pancakes" is "the pancakes", not "need for…".
        val m = Regex("""^.*(^| )(till|for|i|in|of|to make|att gora) (.+)$""").find(folded) ?: return after(folded, INGREDIENTS)
        return m.groupValues[3].trim('?', '.', '!', ' ').replace(Regex("""^(the|a|an|en|ett)\s+"""), "").trim()
    }

    /** What follows [pattern] in [folded], with the article left off: "pannkakorna". */
    private fun after(folded: String, pattern: Regex): String =
        folded.substring((pattern.find(folded)?.range?.last ?: -1) + 1).trim().replace(Regex("""^(the|a|an|en|ett)\s+"""), "").trim('?', '.', '!', ' ')

    private val LIST_CHANGES = setOf("add_todo", "change_todo", "write_note", "change_note", "add_to_fridge", "update_fridge_item", "remove_from_fridge")
    private val QUESTION get() = Cues.regex("question")
    private val COOK get() = Cues.regex("cook")
    private val MOOD_WORD get() = Cues.regex("mood_word")
    private val BLINDS get() = Cues.regex("blinds")
    private val DONE_TODO get() = Cues.regex("done_todo")
    private val CLOCK = Regex("""(klockan|kl |\d{1,2}[:.]\d{2}|at \d|o clock|pm|am)""")
    private val ABOUT = Regex("""(om|about|med|with|that says|som det star) (.+?)$""")
    private val MOVE get() = Cues.regex("move")
    private val ADD get() = Cues.regex("add")
    private val TICK = Regex("""(bocka av|kryssa av|stryk|tick off|check off|cross off|mark) (.+?)( som klar| as done| done)?$""")
    private val MODE_WORD get() = Cues.regex("mode_word")
    private val LIGHT_WORDS get() = Cues.regex("light_words")
    private val CLOSE_THING = Regex("""(stang|stanga|stang av|close|turn off|switch off|slack) (the )?\S*(recept|recipe|timer)\S*""")
    private val KITCHEN_TOPIC get() = Cues.regex("kitchen_topic")
    private val GONE = Regex("""^(.+?) (ar slut|tog slut|is finished|is gone|has run out|ran out)|(?:druckit upp|atit upp|used up|finished) (.+)$""")
    private val DID = Regex("""^\s*(jag|vi|i|we) (har )?(?:bokat|fixat|gjort|ringt|betalat|bytt|skickat|booked|fixed|did|called|paid|changed|sent) (the |a )?(.+?)( and| och| tick| bocka|,|$)""")
    private val MODE_NAME = Regex("""[\p{L}]*(läge|läget|lägen|mode)(?![\p{L}])""", RegexOption.IGNORE_CASE)
    private val REMOVE get() = Cues.regex("remove")
    private val DATE_KEYS = setOf("bought", "date", "expires", "best_before", "eat_by", "opened_on")
    private val ISO_DAY = Regex("""\d{4}-\d{2}-\d{2}""")
    const val NO_LIGHTS = "Not done: the user didn't ask about the lights; leave them as they are and answer what they asked."
    const val FLOW_NOT_LOOK = "Not done: the user named a mode or flow, not a light look. If list_flows has one by that name, run it with run_flow; otherwise say there is none. Don't change the lights instead."
    private val BOUGHT get() = Cues.regex("bought")
    private val FINISHED get() = Cues.regex("finished")
    private val FRIDGE_NAMES = setOf("fridge", "freezer", "kyl", "kylen", "kylskap", "kylskapet", "frys", "frysen", "pantry", "skafferi", "skafferiet")
    private val PLACES = setOf("fridge", "freezer", "pantry", "kyl", "kylen", "kylskap", "kylskapet", "frys", "frysen", "skafferi", "skafferiet")
    const val JUST_ASKED = "Not done: the user only asked a question; nothing on the lists was changed. Answer it from what you looked up."
    private val ROOM_WORDS = listOf("living room", "kitchen", "bedroom", "hallway", "hall", "bathroom", "office", "dining room", "kids room")
    private val SWITCH_NAMES = setOf("light", "lights", "on", "power", "switch", "lamp", "state", "onoff_light")
    private val PAUSE get() = Cues.regex("pause")
    private val INGREDIENTS get() = Cues.regex("ingredients")
    private val WITH = Regex("""(med|with|av|using) (.+?)[?.!]*$""")
    private val SOON get() = Cues.regex("soon")
    private val ME = Regex("""(^| )(jag|mig|min|mitt|mina|me|my|mine|myself)( |$)|(^| )i (need|have|must|should|want|ll|will|gotta)( |$)""")

    /**
     * open_page "recipes" for "öppna lasagnen": the recipe itself is opened instead, by what
     * is left of the question once the asking words are gone. Null when nothing names one.
     */
    fun recipeInstead(call: ToolCall, question: String, tools: Collection<String>): ToolCall? {
        if (call.name != "open_page" || "open_recipe" !in tools) return null
        val page = NameMatch.normalize(str(call.arguments, "page"))
        val folded = NameMatch.normalize(question)
        // Another page the question doesn't name ("öppna lasagnen" as the fridge page) is the recipe too.
        val other = PAGE_WORDS.entries.firstOrNull { (k, _) -> page in k }?.value
        if (page !in setOf("recipes", "recipe", "recept", "recepten") && (other == null || other.containsMatchIn(folded))) return null
        // "Vad behöver jag till pannkakorna?" as a page: the recipe's ingredients, read out.
        if (INGREDIENTS.containsMatchIn(folded) && "read_recipe" in tools) return call.copy(name = "read_recipe", arguments = JsonObject(mapOf("recipe" to JsonPrimitive(dish(folded)))))
        // Nothing asked to be shown or cooked ("berätta ett skämt"): no recipe made up from the words.
        if (!SHOW.containsMatchIn(folded) && !COOK.containsMatchIn(folded)) return null
        val rest = folded.split(' ').filter { it.isNotBlank() && it !in OPEN_WORDS }.joinToString(" ")
        if (rest.isBlank()) return null
        return call.copy(name = "open_recipe", arguments = JsonObject(mapOf("recipe" to JsonPrimitive(rest))))
    }

    private val PAGE_WORDS = mapOf(
        setOf("fridge", "kylen", "kylskap", "kylskapet", "freezer", "frysen") to Regex("""(kyl|frys|fridge|freezer|skafferi|pantry)"""),
        setOf("calendar", "kalender") to Regex("""(kalender|calendar)"""),
        setOf("media", "music", "musik") to Regex("""(musik|media|music|spotify|sonos)"""),
        setOf("notes", "note", "todo", "todos", "lappar", "lapparna") to Regex("""(lapp|note|post|todo|to-do|att gora)"""),
        setOf("overview", "home", "oversikt") to Regex("""(oversikt|overview|home|hem|start)"""),
        setOf("moods") to Regex("""(stamning|mood)"""),
    )

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

    private val ENDEARMENT get() = Cues.regex("endearment")

    private val DAY = Regex(
        """(?<![\p{L}])((nästa|next)\s+)?(måndag|tisdag|onsdag|torsdag|fredag|lördag|söndag|monday|tuesday|wednesday|thursday|friday|saturday|sunday)|""" +
            """i övermorgon|övermorgon|day after tomorrow|i morgon|imorgon|tomorrow|i dag|idag|today|ikväll|tonight""",
        RegexOption.IGNORE_CASE,
    )
    private val FAMILY = Regex("""famil""")
    private val SWITCH_WORDS = setOf("true", "false", "on", "off", "pause", "play", "stop", "1", "0", "yes", "no")
    /** "Två minuter till på pastan", "two more minutes", "lägg till två minuter", "förläng". */
    private val MORE_TIME get() = Cues.regex("more_time")
    private val SHOW_FRIDGE get() = Cues.regex("show_fridge")
    /** "Vi måste…", "we need to…": for everyone, not only the one who said it. */
    private val WE = Regex("""^\s*(vi (maste|behover|ska)|we (need|have|must|should))""")
    const val NOT_GOODNIGHT = "Not done: the user didn't say goodnight. Do just what they asked with set_room or set_device."
    const val NO_PAGE = "Not done: the user didn't ask to see a page; answer them in words."
    const val NO_MUSIC = "Not done: the user didn't ask for music; leave the speakers as they are."
    private val RECIPE_ACTIONS = setOf("open_recipe", "recipe_step", "recipe_timers")
    /** "Vad kan jag laga med kyckling?", "har vi något recept med…", "what can I cook with…": names, not cooking. */
    private val WHICH_RECIPES get() = Cues.regex("which_recipes")
    private val SHOW get() = Cues.regex("show")
    private val MUSIC get() = Cues.regex("music")
    private val VOLUME get() = Cues.regex("volume")
    private val HEAT get() = Cues.regex("heat")
    private val LIGHT_LEVEL get() = Cues.regex("light_level")
    private val ON_OFF get() = Cues.regex("on_off")
    private val LIGHT_LOOKS = setOf("dim", "light_temperature", "light_hue", "light_saturation", "light_mode")
    /** "Tänd lampan", "turn on the light": one lamp, none named. */
    private val GENERIC_LAMP = Regex("""\s*(nova |tars )?(kan du |could you |can you )?(tand|tanda|turn on|switch on|slack|slacka|turn off|switch off) (lampan|ljuset|the light|the lamp|light|lamp)( (pa|tack|please))?\s*[?.!]?\s*""")

    const val RECIPES_ONLY = "Not done: the user only asked which recipes there are. Tell them the names from find_recipes; don't open one or start anything."
    const val USE_GOODNIGHT = "Not done: for goodnight, call the goodnight tool alone; it asks the user first and then turns things off itself."
    const val STATUS_ONLY = "The user only asked how it is right now, so nothing was changed. Answer from get_device or the device list."
    val WHICH_SAY = "Which lamp or room do you mean?" to "Vilken lampa eller vilket rum menar du?"
    const val WHICH = "The user didn't say all of them, so nothing was switched on. Ask which lamp or room they mean."
}
