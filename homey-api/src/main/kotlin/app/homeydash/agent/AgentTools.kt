package app.homeydash.agent

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
        add(tool("set_room", "Change every device in a room that has the capability, e.g. all lights off in the kitchen.") {
            string("room", "Room name exactly as listed")
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
        add(tool("play_music", "Play a favourite, playlist or radio station on a player (e.g. a Sonos).") {
            string("player", "Player alias")
            string("query", "What to play, e.g. a playlist or station name")
            required("player", "query")
        })
        add(tool("open_page", "Show a page on the dashboard: overview, moods, media, calendar, or a room name.") {
            string("page", "overview, moods, media, calendar, or a room name")
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
    }

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
            else -> ok("There is no tool called ${call.name}.")
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
        home.setCapability(device.id, capabilityId, value)
        return ToolResult("Done: ${device.name} $capabilityId = ${text(value)}.", lo)
    }

    private suspend fun setRoom(room: String, capabilityId: String, raw: JsonElement?, cls: String): ToolResult {
        val roomId = view.room(room) ?: return ok("There's no room called $room. Rooms: ${view.rooms.values.sorted().joinToString()}.")
        val wantedClass = classOf(cls)
        val targets = view.devices.mapNotNull { d ->
            if (d.roomId != roomId || d.secret != null || (wantedClass != null && d.deviceClass != wantedClass)) return@mapNotNull null
            // "Everything off in the kitchen" shouldn't reach a hidden fridge plug; name the class to include them.
            if (d.hiddenOnDashboard && wantedClass == null) return@mapNotNull null
            capabilityOf(d, capabilityId)?.takeIf { it.setable }?.let { d to it }
        }
        if (targets.isEmpty()) return ok("Nothing in ${view.rooms[roomId]} has $capabilityId.")
        val changed = targets.mapNotNull { (d, c) ->
            val value = coerce(c, raw) ?: return@mapNotNull null
            home.setCapability(d.id, c.id, value)
            d.name
        }
        return ok("Changed ${changed.size}: ${changed.joinToString()}.")
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

    private fun openPage(page: String): ToolResult {
        val p = page.trim().lowercase()
        val fixed = mapOf(
            "overview" to "overview", "home" to "overview", "översikt" to "overview",
            "moods" to "moods", "media" to "media", "music" to "media", "musik" to "media",
            "calendar" to "calendar", "kalender" to "calendar",
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
                    "on", "1", "yes", "ja", "på" -> true
                    "off", "0", "no", "nej", "av" -> false
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

        private fun text(v: JsonElement): String = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()

        private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

        private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
    }
}

/** Builds a tool's JSON-schema parameters. */
private class ParamsBuilder {
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

private fun tool(name: String, description: String, params: ParamsBuilder.() -> Unit): ToolSpec {
    val b = ParamsBuilder().apply(params)
    return ToolSpec(name, description, buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { b.properties.forEach { (k, v) -> put(k, v) } }
        if (b.required.isNotEmpty()) put("required", buildJsonArray { b.required.forEach { add(JsonPrimitive(it)) } })
    })
}
