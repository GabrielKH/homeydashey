package app.homeydash.agent

import app.homeydash.homey.FlowCardAction
import app.homeydash.homey.FlowCards
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.time.LocalDate

/**
 * Something the assistant wants to do that needs a yes first. [tapOnly] actions (like
 * unlocking a door) can only be confirmed by tapping on the tablet, never by voice.
 */
class PendingAction(val question: String, val tapOnly: Boolean, val run: suspend () -> String)

/** What one tool call did. [localOnly] results must never reach a brain on the internet. */
data class ToolResult(val text: String, val localOnly: Boolean = false, val pending: PendingAction? = null)

/**
 * The assistant's tools. Every call is checked against the [view]: a device, mood or
 * capability the assistant may not see is refused without touching the Homey.
 */
class AgentTools(
    private val view: HomeView,
    private val home: HomeControl,
    private val dashboard: DashboardControl,
    private val today: () -> LocalDate = { LocalDate.now() },
    /** Looks things up on the internet; null when no internet AI is set up. */
    private val web: WebLookup? = null,
    /** The recipes on the tablet and the one being cooked; null when the dashboard has none. */
    private val recipes: RecipeTools? = null,
    /** What is in the fridge, freezer and pantry; null when the dashboard has none. */
    private val fridge: FridgeTools? = null,
) {
    /**
     * Set once this conversation has seen something kept to the local AI; from then on
     * nothing is looked up on the internet, so it can't slip out inside a question.
     */
    var privateContext = false

    fun specs(): List<ToolSpec> = buildList {
        add(tool("set_device", "Change one device: switch, dim, colour, temperature, volume, play/pause, blinds. Use the device alias and a capability marked [set].") {
            string("device", "Device alias, e.g. light_3")
            string("capability", "Capability id, e.g. onoff, dim, target_temperature, volume_set, speaker_playing")
            string("value", "New value: true/false, a number within the range shown, or one of the listed choices")
            required("device", "capability", "value")
        })
        add(tool("set_room", "Change every device in a room, or in one of the owner's groups (shown as group \"…\"), that has the capability, e.g. all lights off in the kitchen.") {
            string("room", "Room or group name exactly as listed")
            string("capability", "Capability id, usually onoff or dim")
            string("value", "New value")
            string("device_class", "Optional: only this class, e.g. light")
            required("room", "capability", "value")
        })
        add(tool("get_device", "All details of one device, including ranges and choices.") {
            string("device", "Device alias")
            required("device")
        })
        if (view.moods.isNotEmpty()) add(tool("set_mood", "Turn on a Homey mood (scene).") {
            string("mood", "Mood alias, e.g. mood_2")
            required("mood")
        })
        if (!guest) add(tool("list_flows", "Names of the Homey Flows that can be started.") {})
        if (!guest) add(tool("run_flow", "Start a Homey Flow by its exact name.") {
            string("name", "Flow name")
            required("name")
        })
        add(tool("play_music", "Play a favourite, album, playlist or radio station on a player (e.g. a Sonos).") {
            string("player", "Player alias")
            string("query", "What to play: just the album, playlist or station name, e.g. \"Abbey Road\"")
            required("player", "query")
        })
        add(tool("device_actions", "What else a device's app can do in Homey: TV channels, open an app, Sonos favourites, playlists and radio, remote buttons.") {
            string("device", "Device alias")
            required("device")
        })
        add(tool("run_device_action", "Do one of a device's actions from device_actions, e.g. change channel, launch Netflix, play a Sonos favourite or playlist.") {
            string("device", "Device alias")
            string("action", "The action's id or title from device_actions")
            string("value", "What to pick or enter, e.g. a channel, app, playlist or station name; leave out when the action needs nothing")
            required("device", "action")
        })
        add(tool("open_page", "Show a page on the dashboard: overview, moods, media, calendar, recipes, fridge, or a room name.") {
            string("page", "overview, moods, media, calendar, recipes, fridge, or a room name")
            required("page")
        })
        add(tool("open_device", "Open a device's controls on the dashboard, e.g. a lamp's colour or a TV remote.") {
            string("device", "Device alias")
            required("device")
        })
        if (!guest) add(tool("goodnight", "Goodnight: turn off everything that is on and pause what plays, except the exceptions the owner chose. Asks for a yes first.") {})
        add(tool("timer", "Kitchen timers on the tablet: start, cancel or list.") {
            string("action", "start, cancel or list")
            number("minutes", "Length for start; may have decimals")
            string("label", "Optional name, e.g. pasta")
            required("action")
        })
        add(tool("weather", "Current weather and the next days' forecast.") {})
        if (!guest) add(tool("set_theme", "Switch the dashboard between light and dark.") {
            string("mode", "light, dark or auto")
            required("mode")
        })
        if (view.allows(Secret.Calendar)) add(tool("calendar", "Events from the family calendar.") {
            string("start_date", "First day, yyyy-mm-dd; default today")
            number("days", "How many days, 1-31; default 1")
        })
        if (view.allows(Secret.Presence)) add(tool("who_is_home", "Which Homey users are home or asleep.") {})
        if (view.allows(Secret.Variables)) add(tool("variables", "Homey Logic variables and their values.") {})
        if (web != null) add(tool(
            "ask_internet",
            "Look up something you don't know on the internet: news, sport, opening hours, facts, recipes, prices. " +
                "Send only a short general question. Never put anything about this home in it: no devices, rooms, people, calendar or tool results.",
        ) {
            string("question", "A self-contained general question")
            required("question")
        })
        recipes?.let { addAll(it.specs()) }
        fridge?.let { addAll(it.specs()) }
    }

    /** "Next step" and the like while a recipe is open, answered without the AI; null otherwise. */
    fun cookingQuick(text: String): String? = recipes?.quick(text)

    private val guest get() = view.settings.guest

    suspend fun run(call: ToolCall): ToolResult =
        (if (guest && call.name in GUEST_BLOCKED) ok("Guests can't do that.") else runAllowed(call))
            .also { if (it.localOnly) privateContext = true }

    private suspend fun runAllowed(call: ToolCall): ToolResult = try {
        val a = call.arguments
        when (call.name) {
            "set_device" -> setDevice(a.str("device"), a.str("capability"), a["value"])
            "set_room" -> setRoom(a.str("room"), a.str("capability"), a["value"], a.str("device_class"))
            "get_device" -> getDevice(a.str("device"))
            "set_mood" -> setMood(a.str("mood"))
            "list_flows" -> ToolResult(home.flows().joinToString("\n") { it.name }.ifBlank { "No Flows can be started." })
            "run_flow" -> runFlow(a.str("name"))
            "play_music" -> playMusic(a.str("player"), a.str("query"))
            "device_actions" -> deviceActions(a.str("device"))
            "run_device_action" -> runDeviceAction(a.str("device"), a.str("action"), a.str("value").ifBlank { null })
            "open_page" -> openPage(a.str("page"))
            "open_device" -> view.device(a.str("device"))?.let { d -> ok(if (dashboard.openDevice(d.id)) "Opened ${d.name}." else "Can't open ${d.name}.") } ?: unknown(a.str("device"))
            "goodnight" -> goodnight()
            "timer" -> timer(a.str("action"), a.num("minutes"), a.str("label"))
            "weather" -> ok(dashboard.weather() ?: "The weather isn't available right now.")
            "set_theme" -> ok(if (dashboard.setTheme(a.str("mode").lowercase())) "Theme set." else "Use light, dark or auto.")
            "calendar" -> calendar(a.str("start_date"), a.num("days"))
            "who_is_home" -> whoIsHome()
            "variables" -> variables()
            "ask_internet" -> askInternet(a.str("question"))
            else -> recipes?.run(call) ?: fridge?.run(call) ?: ok("There is no tool called ${call.name}.")
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        ok("That failed: ${e.message ?: e::class.simpleName}")
    }

    private fun localOnly(secret: Secret?) = secret != null && view.settings.access(secret) == Access.LocalOnly

    private suspend fun setDevice(alias: String, capabilityId: String, raw: JsonElement?): ToolResult {
        val device = view.device(alias) ?: return unknown(alias)
        val capability = capabilityOf(device, capabilityId)
            ?: return ok("${device.name} has no $capabilityId. It has: ${device.capabilities.filter { it.setable }.joinToString { it.id }}.")
        if (!capability.setable) return ok("$capabilityId on ${device.name} can only be read.")
        @Suppress("NAME_SHADOWING") val capabilityId = capability.id
        val value = coerce(capability, raw) ?: return ok("\"${raw?.let(::text)}\" isn't a valid value for $capabilityId on ${device.name}.")
        val lo = localOnly(device.secret)

        if (capabilityId == "locked" && (value as? JsonPrimitive)?.booleanOrNull == false) {
            if (!view.allows(Secret.Unlock)) return ToolResult("Unlocking isn't allowed for the assistant. The owner can turn on 'Unlock doors' in Settings.", lo)
            return ToolResult(
                "Ask the user to confirm by tapping Yes on the tablet; voice can't unlock doors.",
                lo,
                PendingAction("Unlock ${device.name}?", tapOnly = true) {
                    home.setCapability(device.id, capabilityId, value)
                    "${device.name} is unlocked."
                },
            )
        }
        if (capabilityId.startsWith("homealarm_state") && text(value) == "disarmed") {
            return ToolResult("The assistant may never disarm the alarm. Use the Homey app or the alarm's own keypad.", lo)
        }
        if (device.unreachable) {
            return ToolResult("Homey can't reach ${device.name} right now, so it can't be changed. Tell the user it may be unplugged, switched off at the wall or out of range.", lo)
        }
        home.setCapability(device.id, capabilityId, value)
        // Homey sometimes accepts a change the device never makes; check before saying it's done.
        val now = home.readBack(device.id, capabilityId)
        if (now != null && !same(now, value)) {
            return ToolResult(
                "Homey accepted it, but ${device.name} still reports $capabilityId = ${text(now)}, so it didn't change. " +
                    "Tell the user plainly; it may be out of range or switched off at the wall.",
                lo,
            )
        }
        return ToolResult("Done: ${device.name} $capabilityId = ${text(value)}.", lo)
    }

    private suspend fun setRoom(room: String, capabilityId: String, raw: JsonElement?, cls: String): ToolResult {
        // One of the owner's card groups ("Taklampor"), unless a room has exactly that name.
        val group = view.group(room)?.takeIf { g -> view.rooms.values.none { it.equals(room.trim(), ignoreCase = true) } || g.equals(room.trim(), ignoreCase = true) }
        val roomId = if (group != null) null else view.room(room) ?: return ok("There's no room or group called $room. Rooms: ${view.rooms.values.sorted().joinToString()}.")
        val place = group ?: view.rooms[roomId].orEmpty()
        val wantedClass = classOf(cls)
        val targets = view.devices.mapNotNull { d ->
            val inside = if (group != null) d.group == group else view.inRoom(d, roomId!!)
            if (!inside || d.secret != null || (wantedClass != null && d.deviceClass != wantedClass)) return@mapNotNull null
            // "Everything off in the kitchen" shouldn't reach a hidden fridge plug; name the class to include them.
            if (d.hiddenOnDashboard && wantedClass == null) return@mapNotNull null
            capabilityOf(d, capabilityId)?.takeIf { it.setable }?.let { d to it }
        }
        if (targets.isEmpty()) return ok("Nothing in $place has $capabilityId.")
        val (unreachable, reachable) = targets.partition { it.first.unreachable }
        val missed = if (unreachable.isEmpty()) "" else " Homey can't reach ${unreachable.joinToString { it.first.name }}, so they weren't changed."
        val sent = reachable.mapNotNull { (d, c) ->
            val value = coerce(c, raw) ?: return@mapNotNull null
            home.setCapability(d.id, c.id, value)
            Triple(d, c, value)
        }
        // Homey sometimes accepts a change a lamp never makes; ask again before saying it's done.
        val stuck = kotlinx.coroutines.coroutineScope {
            sent.map { (d, c, value) ->
                async { home.readBack(d.id, c.id)?.takeIf { !same(it, value) }?.let { d.name } }
            }.awaitAll().filterNotNull()
        }
        val changed = sent.map { it.first.name } - stuck.toSet()
        val notChanged = if (stuck.isEmpty()) "" else
            " Homey accepted it, but ${stuck.joinToString()} still report the old value, so they didn't change; tell the user plainly."
        if (changed.isEmpty() && stuck.isNotEmpty()) return ok("${QuickCommand.NOT_CHANGED_PREFIX} ${stuck.joinToString()} still report the old value, so nothing changed.$missed")
        return ok("Changed ${changed.size}: ${changed.joinToString()}.$notChanged$missed")
    }

    private fun getDevice(alias: String): ToolResult {
        val d = view.device(alias) ?: return unknown(alias)
        val json = buildJsonObject {
            put("alias", d.alias)
            put("name", d.name)
            d.room?.let { put("room", it) }
            put("class", d.deviceClass)
            putJsonArray("capabilities") {
                d.capabilities.forEach { c ->
                    add(buildJsonObject {
                        put("id", c.id)
                        c.title?.let { put("title", it) }
                        put("value", c.value)
                        c.units?.let { put("units", it) }
                        put("setable", c.setable)
                        c.min?.let { put("min", it) }
                        c.max?.let { put("max", it) }
                        c.choices?.let { ch -> putJsonArray("choices") { ch.forEach { add(JsonPrimitive(it)) } } }
                    })
                }
            }
        }
        return ToolResult(json.toString(), localOnly(d.secret))
    }

    private suspend fun setMood(alias: String): ToolResult {
        val mood = view.mood(alias) ?: return ok("There's no mood $alias.")
        home.setMood(mood.mood)
        return ok("Mood ${mood.mood.name} is on.")
    }

    private suspend fun runFlow(name: String): ToolResult {
        val flows = home.flows()
        val flow = flows.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
            ?: flows.firstOrNull { it.name.contains(name.trim(), ignoreCase = true) }
            ?: return ok("There's no Flow called $name.")
        if (SENSITIVE.containsMatchIn(flow.name)) {
            val unlock = UNLOCKING.containsMatchIn(flow.name)
            if (unlock && !view.allows(Secret.Unlock)) return ok("That Flow looks like it unlocks or disarms something, which the assistant isn't allowed to do.")
            return ToolResult(
                "Ask the user to confirm${if (unlock) " by tapping Yes on the tablet" else ""}.",
                pending = PendingAction("Start the Flow ${flow.name}?", tapOnly = unlock) {
                    home.triggerFlow(flow)
                    "Started ${flow.name}."
                },
            )
        }
        home.triggerFlow(flow)
        return ok("Started ${flow.name}.")
    }

    private suspend fun playMusic(alias: String, query: String): ToolResult {
        val d = view.device(alias) ?: return unknown(alias)
        val played = home.playMusic(d.id, query) ?: return ok("Couldn't find \"$query\" on ${d.name}.")
        return ok("Playing $played on ${d.name}.")
    }

    /** A device's Flow cards the assistant may use: never on guarded devices, nor anything that unlocks or disarms. */
    private fun actionsOf(d: AgentDevice): List<FlowCardAction>? {
        if (d.secret != null) return null
        if (guest && d.deviceClass !in GUEST_MEDIA) return null
        return home.deviceActions(d.id).filter { card -> !SENSITIVE.containsMatchIn(card.cardId) && !SENSITIVE.containsMatchIn(card.title.orEmpty()) }
    }

    private fun deviceActions(alias: String): ToolResult {
        val d = view.device(alias) ?: return unknown(alias)
        val cards = actionsOf(d) ?: return ok("${d.name}'s actions can't be used here.")
        if (cards.isEmpty()) return ok("${d.name} has no actions in Homey besides its capabilities.")
        return ok(cards.take(MAX_ACTIONS).joinToString("\n") { card ->
            val args = card.inputArgs.joinToString { arg ->
                when (arg.type) {
                    "dropdown" -> "${arg.name}: one of " + arg.values.orEmpty().take(12).joinToString("/") { it.label ?: it.id }
                    "autocomplete" -> "${arg.name}: a name to search for"
                    "range", "number" -> "${arg.name}: number ${arg.min ?: ""}..${arg.max ?: ""}"
                    else -> "${arg.name}: ${arg.type}"
                }
            }
            "${card.cardId} \"${card.title ?: card.cardId}\"" + if (args.isEmpty()) "" else " ($args)"
        })
    }

    private suspend fun runDeviceAction(alias: String, action: String, value: String?): ToolResult {
        val d = view.device(alias) ?: return unknown(alias)
        val cards = actionsOf(d) ?: return ok("${d.name}'s actions can't be used here.")
        val card = cards.firstOrNull { it.cardId.equals(action.trim(), ignoreCase = true) || it.title.equals(action.trim(), ignoreCase = true) }
            ?: NameMatch.best(action, cards) { NameMatch.score(action, it.title ?: it.cardId) }
            ?: return ok("${d.name} has no action \"$action\". Use device_actions to see what it has.")
        val title = card.title ?: card.cardId
        // One value per argument, separated by ";" when the card has several.
        val values = value?.split(';')?.map { it.trim() }.orEmpty()
        var picked: String? = null
        val args = buildMap<String, JsonElement> {
            card.inputArgs.forEachIndexed { i, arg ->
                val v = values.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: run {
                    // A play mode or a "shuffle" box left out takes its default, as in a Flow.
                    when (arg.type) {
                        "dropdown" -> arg.values?.firstOrNull()?.let { put(arg.name, JsonPrimitive(it.id)); return@forEachIndexed }
                        "checkbox" -> { put(arg.name, JsonPrimitive(false)); return@forEachIndexed }
                    }
                    return ok("\"$title\" needs ${arg.title ?: arg.name}.")
                }
                val element: JsonElement = when (arg.type) {
                    "autocomplete", "dropdown" -> {
                        val items = home.choices(card, arg, v)
                        val item = NameMatch.best(v, items) { NameMatch.score(v, it.name) }
                            ?: items.takeIf { arg.type == "autocomplete" }?.firstOrNull { NameMatch.score(v, it.name) > 0 }
                            ?: items.singleOrNull()
                            ?: return ok("Couldn't find \"$v\" for $title. " + if (items.isEmpty()) "Nothing matched." else "Some choices: " + items.take(10).joinToString { it.name } + ".")
                        picked = item.name
                        FlowCards.argValue(arg, item)
                    }
                    "range", "number" -> JsonPrimitive(v.replace(',', '.').toDoubleOrNull() ?: return ok("\"$title\" needs a number for ${arg.name}."))
                    "checkbox" -> JsonPrimitive(v.lowercase() in setOf("true", "yes", "ja", "on", "på", "1"))
                    "device" -> {
                        val other = view.device(v) ?: return unknown(v)
                        buildJsonObject { put("id", other.id); put("name", other.name) }
                    }
                    else -> JsonPrimitive(v)
                }
                put(arg.name, element)
            }
        }
        home.runAction(card, JsonObject(args))
        return ok("Done: $title" + (picked?.let { " ($it)" } ?: "") + " on ${d.name}.")
    }

    private fun openPage(page: String): ToolResult {
        val p = page.trim().lowercase()
        val fixed = mapOf(
            "overview" to "overview", "home" to "overview", "översikt" to "overview",
            "moods" to "moods", "media" to "media", "music" to "media", "musik" to "media",
            "calendar" to "calendar", "kalender" to "calendar",
            "recipes" to "recipes", "recipe" to "recipes", "recept" to "recipes",
            "fridge" to "fridge", "kylen" to "fridge", "kylskåp" to "fridge", "kylskåpet" to "fridge", "freezer" to "fridge", "frysen" to "fridge",
        )
        fixed[p]?.let { return ok(if (dashboard.openPage(it)) "Showing $it." else "Can't show $it.") }
        val roomId = view.room(page) ?: return ok("There's no page or room called $page.")
        return ok(if (dashboard.openPage("room", roomId)) "Showing ${view.rooms[roomId]}." else "Can't show that room.")
    }

    private fun goodnight(): ToolResult {
        val preview = dashboard.goodnightPreview()
        return ToolResult(
            "Goodnight would: $preview Ask the user to confirm.",
            pending = PendingAction("Goodnight? $preview", tapOnly = false) {
                dashboard.goodnight()
                "Goodnight."
            },
        )
    }

    private fun timer(action: String, minutes: Double?, label: String): ToolResult = when (action.lowercase()) {
        "start" -> {
            val seconds = ((minutes ?: 0.0) * 60).toInt()
            if (seconds <= 0 || seconds > 24 * 3600) ok("Say how many minutes.")
            else {
                dashboard.startTimer(seconds, label.ifBlank { null })
                ok("Timer started.")
            }
        }
        "cancel", "stop" -> ok(if (dashboard.cancelTimer(label.ifBlank { null })) "Timer cancelled." else "No timer like that is running.")
        else -> ok(dashboard.timers().joinToString("\n").ifBlank { "No timers are running." })
    }

    private suspend fun calendar(start: String, days: Double?): ToolResult {
        if (!view.allows(Secret.Calendar)) return ok("Reading the calendar is switched off.")
        val from = runCatching { LocalDate.parse(start.trim()) }.getOrNull() ?: today()
        val count = (days ?: 1.0).toInt().coerceIn(1, 31)
        val events = dashboard.calendar(from, count, withLocations = view.allows(Secret.CalendarDetails))
        val text = if (events.isEmpty()) "No events." else events.joinToString("\n") { e ->
            listOfNotNull(e.start + (e.end?.let { "–$it" } ?: ""), e.title, e.location).joinToString(" · ")
        }
        return ToolResult(text, localOnly(Secret.Calendar) || localOnly(Secret.CalendarDetails))
    }

    private suspend fun whoIsHome(): ToolResult {
        if (!view.allows(Secret.Presence)) return ok("Presence is switched off.")
        val text = home.people().joinToString("\n") { p ->
            val where = when (p.present) { true -> "home"; false -> "away"; null -> "unknown" }
            "${p.name}: $where${if (p.asleep == true) ", asleep" else ""}"
        }.ifBlank { "No Homey users." }
        return ToolResult(text, localOnly(Secret.Presence))
    }

    private suspend fun variables(): ToolResult {
        if (!view.allows(Secret.Variables)) return ok("Variables are switched off.")
        val text = home.variables().joinToString("\n") { "${it.name} = ${text(it.value)}" }.ifBlank { "No variables." }
        return ToolResult(text, localOnly(Secret.Variables))
    }

    private suspend fun askInternet(question: String): ToolResult {
        val lookup = web ?: return ok("No internet AI is set up.")
        if (question.isBlank()) return ok("Ask a question.")
        if (privateContext) {
            return ok("This conversation includes private things, so nothing is looked up on the internet now. Answer from what you know.")
        }
        if (mentionsHome(question)) return ok("Leave this home's devices, rooms and people out of internet questions; ask a general question instead.")
        return ok(lookup.answer(question, view.settings.language))
    }

    /** Whether a question names a device alias or a guarded device, which must stay home. */
    private fun mentionsHome(question: String): Boolean {
        val q = question.lowercase()
        if (ALIAS.containsMatchIn(q)) return true
        return view.devices.any { d -> d.secret != null && d.name.length > 2 && d.name.lowercase() in q }
    }

    private fun unknown(alias: String): ToolResult {
        val near = view.similarDevices(alias)
        return ok(
            if (near.isEmpty()) "I can't see a device called $alias. Use an alias from the list."
            else "\"$alias\" could be: " + near.joinToString { "${it.alias} \"${it.name}\"" + (it.room?.let { r -> " ($r)" } ?: "") } +
                ". Pick one, or ask the user which they mean.",
        )
    }

    private fun ok(text: String) = ToolResult(text)

    companion object {
        private val ALIAS = Regex("""\b(light|socket|player|climate|sensor|blind|vacuum|lock|alarm|camera|fan|device|mood)_\d+\b""")
        /** Guests may use the actions of TVs and speakers, nothing else's. */
        private val GUEST_MEDIA = setOf("tv", "speaker", "amplifier")
        private const val MAX_ACTIONS = 40

        private val GUEST_BLOCKED = setOf("list_flows", "run_flow", "goodnight", "set_theme", "calendar", "who_is_home", "variables")

        /** Flows whose names sound like locks, alarms or doors need a yes first. */
        private val SENSITIVE = Regex("""\b(un)?lock\w*|\blås\w*|\b(dis)?arm\w*|\b(av)?larm\w*|\balarm\w*|\bgarage\w*|\bdoor\w*|\bdörr\w*""", RegexOption.IGNORE_CASE)
        private val UNLOCKING = Regex("""\bunlock\w*|\blås\w* upp|\bupplås\w*|\bdisarm\w*|\bavlarm\w*|\böppna\w*|\bopen\b""", RegexOption.IGNORE_CASE)

        /** Turns what the model sent into a value the capability accepts, or null. */
        fun coerce(c: AgentCapability, raw: JsonElement?): JsonElement? {
            val p = raw as? JsonPrimitive ?: return null
            val s = p.contentOrNull?.trim() ?: return null
            return when (c.type) {
                "boolean" -> (p.booleanOrNull ?: when (s.lowercase()) {
                    "on", "1", "yes", "ja", "på", "tänd", "tänt", "tända", "turn_on" -> true
                    "off", "0", "no", "nej", "av", "släck", "släckt", "släckta", "turn_off" -> false
                    else -> null
                })?.let(::JsonPrimitive)
                "number" -> {
                    var n = p.doubleOrNull ?: s.removeSuffix("%").trim().replace(',', '.').toDoubleOrNull() ?: return null
                    // "60%" for a 0..1 capability such as dim.
                    if (c.max == 1.0 && n > 1.0 && n <= 100.0) n /= 100.0
                    c.min?.let { n = maxOf(n, it) }
                    c.max?.let { n = minOf(n, it) }
                    JsonPrimitive(n)
                }
                "enum" -> c.choices?.firstOrNull { it.equals(s, ignoreCase = true) }?.let(::JsonPrimitive)
                else -> JsonPrimitive(s)
            }
        }

        /** Everyday words models use for capabilities, mapped to Homey's ids. */
        private val CAPABILITY_WORDS = mapOf(
            "on" to "onoff", "off" to "onoff", "power" to "onoff", "switch" to "onoff", "state" to "onoff", "on_off" to "onoff",
            "brightness" to "dim", "level" to "dim", "dimmer" to "dim", "dimming" to "dim",
            "temperature" to "target_temperature", "temp" to "target_temperature", "setpoint" to "target_temperature",
            "volume" to "volume_set", "mute" to "volume_mute", "play" to "speaker_playing", "playing" to "speaker_playing",
            "color" to "light_hue", "colour" to "light_hue", "hue" to "light_hue", "saturation" to "light_saturation",
            "color_temperature" to "light_temperature", "colour_temperature" to "light_temperature",
            "position" to "windowcoverings_set", "blinds" to "windowcoverings_set",
        )

        /** The capability [id] names on [d], also when the model used an everyday word for it. */
        fun capabilityOf(d: AgentDevice, id: String): AgentCapability? {
            val key = id.trim().lowercase()
            d.capability(key)?.let { return it }
            CAPABILITY_WORDS[key]?.let { d.capability(it) }?.let { return it }
            // A sub-capability like onoff.1 when only that exists.
            return d.capabilities.filter { it.id.substringBefore('.') == key }.singleOrNull()
        }

        /** A device class from what the model sent: "lights" and "lamps" mean light. */
        private fun classOf(cls: String): String? {
            val c = cls.trim().lowercase().removeSuffix("s")
            return when {
                c.isBlank() -> null
                c == "lamp" || c == "lampa" || c == "lampor" || c == "ljus" -> "light"
                else -> c
            }
        }

        /** Whether Homey's [now] is the [wanted] value, allowing for a lamp rounding 30 % to 31 %. */
        internal fun same(now: JsonElement, wanted: JsonElement): Boolean {
            val a = now as? JsonPrimitive ?: return now == wanted
            val b = wanted as? JsonPrimitive ?: return false
            a.booleanOrNull?.let { return it == b.booleanOrNull }
            val x = a.doubleOrNull
            val y = b.doubleOrNull
            if (x != null && y != null) return kotlin.math.abs(x - y) <= 0.03 * maxOf(1.0, kotlin.math.abs(y))
            return a.contentOrNull == b.contentOrNull
        }

        private fun text(v: JsonElement): String = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()

        private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

        private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
    }
}

/** Builds a tool's JSON-schema parameters. */
internal class ParamsBuilder {
    val properties = linkedMapOf<String, JsonObject>()
    var required = emptyList<String>()

    fun string(name: String, description: String) {
        properties[name] = buildJsonObject { put("type", "string"); put("description", description) }
    }

    fun number(name: String, description: String) {
        properties[name] = buildJsonObject { put("type", "number"); put("description", description) }
    }

    fun required(vararg names: String) {
        required = names.toList()
    }
}

internal fun tool(name: String, description: String, params: ParamsBuilder.() -> Unit): ToolSpec {
    val b = ParamsBuilder().apply(params)
    return ToolSpec(name, description, buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { b.properties.forEach { (k, v) -> put(k, v) } }
        if (b.required.isNotEmpty()) put("required", buildJsonArray { b.required.forEach { add(JsonPrimitive(it)) } })
    })
}
