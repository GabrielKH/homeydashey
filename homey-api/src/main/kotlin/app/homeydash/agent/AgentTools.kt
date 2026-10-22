package app.homeydash.agent

import app.homeydash.homey.FlowCardAction
import app.homeydash.homey.FlowCards
import app.homeydash.homey.RemoteAction
import app.homeydash.homey.RemoteKey
import app.homeydash.homey.Remotes
import app.homeydash.homey.VacuumCommand
import app.homeydash.homey.VacuumRoom
import app.homeydash.homey.VacuumState
import app.homeydash.homey.Vacuums
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
import app.homeydash.homey.HomeyAlarm
import app.homeydash.homey.Household
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Something the assistant wants to do that needs a yes first. [tapOnly] actions (like
 * unlocking a door) can only be confirmed by tapping on the tablet, never by voice.
 */
class PendingAction(val question: String, val tapOnly: Boolean, val run: suspend () -> String)

/** What one tool call did. [localOnly] results must never reach a brain on the internet. */
data class ToolResult(val text: String, val localOnly: Boolean = false, val pending: PendingAction? = null, val hunt: PhoneHunt? = null)

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
    /** The post-its and to-do list; null when the dashboard has none. */
    private val notes: NoteTools? = null,
    /** The owner's routines and standing instructions; null when the dashboard has none. */
    private val routines: RoutineTools? = null,
    /** What the assistant remembers from earlier conversations; null when it remembers nothing. */
    private val memory: MemoryTools? = null,
    /** Teaching the topics' know-how by voice; null when the dashboard keeps none. */
    private val knowHow: KnowHowTools? = null,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    /**
     * Set once this conversation has seen something kept to the local AI; from then on
     * nothing is looked up on the internet, so it can't slip out inside a question.
     */
    var privateContext = false

    /** Hears each tool call as it starts and ends, to show on screen and keep in the logs. */
    var listener: AgentListener? = null

    fun specs(): List<ToolSpec> = buildList {
        add(tool("set_device", "Change one capability of one device: switch, dim, colour, temperature, volume, play/pause, blinds.") {
            string("device", DEVICE)
            string("capability", "Capability id, e.g. onoff, dim")
            string("value", "true/false, a number, or a listed choice")
            required("device", "capability", "value")
        })
        add(tool("set_room", "Change a capability on every device in a room or group, e.g. all lights off in the kitchen. One call with room \"all\" does the whole house.") {
            string("room", "Room or group name, or all")
            string("capability", "Capability id, usually onoff or dim")
            string("value", "New value")
            string("device_class", "Optional, e.g. light")
            required("room", "capability", "value")
        })
        add(tool("get_device", "All details of one device, including ranges and choices.") {
            string("device", DEVICE)
            required("device")
        })
        if (view.moods.isNotEmpty()) add(tool("set_mood", "Turn on a Homey mood (scene) the owner made.") {
            string("mood", "Mood alias, e.g. mood_2")
            string("room", "The room the user means, when they said one")
            required("mood")
        })
        add(tool("set_scene", "Set the lights in a room to a look when no mood fits: cozy, party, romantic, relax, movie, focus, bright, night.") {
            string("room", "Room or group name, or all")
            string("look", "cozy, party, romantic, relax, movie, focus, bright or night")
            required("room", "look")
        })
        if (!guest) add(tool("list_flows", "Names of the Homey Flows that can be started.") {})
        if (!guest) add(tool("run_flow", "Start a Homey Flow by its exact name.") {
            string("name", "Flow name")
            required("name")
        })
        add(tool("play_music", "Play a song, album, artist, playlist, favourite or radio station on a player (e.g. a Sonos); finds anything on Spotify when set up.") {
            string("player", "Player alias")
            string("query", "What they asked for in their words: a title with the artist if said (\"Dancing Queen ABBA\"), or a genre, mood or chart (\"swedish top 50\", \"calm female vocals\"); empty only when they let you choose")
            string("kind", "what they asked for: song (a song or track), album, artist (only the artist named), playlist (a list or a mood), podcast (plays its newest episode), episode, radio for a station (\"P1\", \"radio P3\"), or omit for a favourite")
            required("player")
        })
        if (!guest) add(tool("save_favourite", "Add music to the Sonos favourites: what was just played, or a playlist, album or song by name.") {
            string("player", "Player alias")
            string("query", "Its name, e.g. the playlist play_music just played; empty for the last thing played on the player")
            required("player")
        })
        add(tool("device_actions", "What else a device's app can do in Homey: TV channels, open an app, Sonos favourites, playlists and radio, remote buttons.") {
            string("device", DEVICE)
            required("device")
        })
        add(tool("run_device_action", "Run an action from device_actions: channel, app, favourite, playlist.") {
            string("device", DEVICE)
            string("action", "Action id or title")
            string("value", "What to pick or enter, e.g. Netflix; omit if nothing")
            required("device", "action")
        })
        if (!guest && view.devices.any { it.deviceClass == "vacuumcleaner" && it.secret == null }) add(tool(
            "vacuum",
            "Control a robot vacuum: start, pause, resume, stop, dock (send home), clean_rooms (clean only the named rooms), " +
                "go_to (drive to a room, e.g. so the user can empty it there), rooms (list the rooms it knows), " +
                "or where (where it is: in its dock, cleaning, or the room it was last sent to).",
        ) {
            string("device", "Vacuum alias; may be left out when there is one")
            string("action", "start, pause, resume, stop, dock, clean_rooms, go_to, rooms or where")
            string("rooms", "For clean_rooms and go_to: room names, comma-separated, e.g. \"Kitchen, Hall\"")
            required("action")
        })
        add(tool("open_page", "Show a page on the dashboard: overview, moods, media, calendar, recipes, fridge, notes, or a room name.") {
            string("page", "overview, moods, media, calendar, recipes, fridge, notes, or a room name")
            required("page")
        })
        add(tool("open_device", "Open a device's controls on the dashboard, e.g. a lamp's colour or a TV remote.") {
            string("device", DEVICE)
            required("device")
        })
        add(tool("press_remote", "Press TV or media box remote buttons in order.") {
            string("device", DEVICE)
            string("keys", "Comma-separated: up, down, left, right, ok, back, home, play, volume up/down, mute, power, channel up/down; repeat with x, e.g. \"down x3, ok\"")
            required("device", "keys")
        })
        if (!guest) add(tool("goodnight", "Goodnight: turn off everything that is on and pause what plays, except the exceptions the owner chose. Asks for a yes first.") {})
        add(tool("timer", "Kitchen timers on the tablet: start, list, cancel one or all, rename, add or take away time, pause or resume, mute or unmute the spoken countdown.") {
            string("action", "start, list, cancel, cancel_all, rename, add, pause, resume, mute or unmute")
            number("minutes", "start: length (leave out for a named timer to use the usual length); add: minutes to add, negative to take away")
            string("label", "The timer's name, e.g. pasta; for rename the timer to rename (leave out for the newest)")
            string("new_label", "rename: the new name")
            string("several", "start: several timers at once, each a name and minutes, e.g. \"pasta 10, ris 12, ägg 7\"")
            required("action")
        })
        if (recipes != null) add(tool("recipe_timers", "Start a kitchen timer for every step of the open recipe that names a time, all at once.") {})
        add(tool("weather", "Current weather and the next days' forecast.") {})
        if (!guest) add(tool("set_theme", "Switch the dashboard between light and dark.") {
            string("mode", "light, dark or auto")
            required("mode")
        })
        if (view.allows(Secret.Calendar)) add(tool("calendar", "Events from the family calendar, or from one person's own calendar.") {
            string("start_date", "First day as the user said it: today, tomorrow, a weekday (\"lördag\", \"saturday\"), or yyyy-mm-dd; default today")
            number("days", "How many days, 1-31; default 1")
            string("calendar", "Leave out for every calendar on the dashboard, also for \"what's happening\"; \"mine\" only for the asker's own, \"family\" only for the family calendar, or a person's name")
        })
        if (!guest && view.allows(Secret.AddEvents)) add(tool(
            "add_event",
            "Add an event to a calendar: the asker's own (\"min kalender\"), the family calendar, or someone else's by name. Asks for a yes first.",
        ) {
            string("title", "What it is, short, e.g. Tandläkare or Padel with Erik")
            string("date", "The day as the user said it: today, tomorrow, a weekday (\"måndag\", \"next friday\"), or yyyy-mm-dd for a date")
            string("time", "Start, HH:mm 24-hour; \"all day\" for an all-day event; leave out when not said")
            number("minutes", "How long; leave out when not said")
            string("in_calendar", "\"mine\", \"family\", or a person's name; leave out when not said")
            string("location", "Where, only if said")
            required("title", "date")
        })
        if (view.allows(Secret.Presence)) add(tool("who_is_home", "Which Homey users are home or asleep, and since when.") {})
        if (!guest && view.allows(Secret.Presence)) add(tool(
            "set_presence",
            "Tell Homey someone is home, away, asleep or awake (\"jag går nu\", \"Anna sover\"); Homey Flows react to it.",
        ) {
            string("person", "Whose, as who_is_home names them; omit for the asker, or \"everyone\" for everyone home")
            string("state", "home, away, asleep or awake")
            required("state")
        })
        if (!guest && view.allows(Secret.WakeAlarms)) add(tool(
            "alarms",
            "Homey's wake-up alarms: list, set (\"väck mig 6:15\"), on, off, skip (no ring next time only, \"ingen väckning imorgon\") or delete.",
        ) {
            string("action", "list, set, on, off, skip or delete")
            string("time", "For set: HH:mm, 24-hour, e.g. 06:15")
            string("days", "For set: once (default), weekdays, weekends, every day, or days like \"mon, wed\"")
            string("name", "The alarm's name; omit for the asker's own")
            required("action")
        })
        if (view.phones.isNotEmpty()) add(tool(
            "find_phone",
            "Find a phone in the house: ring it (it plays a loud sound, even on silent) and say whether it is home and its battery. " +
                "Leave phone out for the asker's own phone.",
        ) {
            string("phone", "Whose phone, or its name, e.g. \"Anna\" or \"iPad\"; omit for the asker's own")
            string("action", "ring (default; also for find, hitta, locate) or where (only when asked whether it is home or its battery, no ring)")
        })
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
        notes?.let { addAll(it.specs()) }
        routines?.let { r ->
            if (r.data.routines.any { it.enabled }) add(tool("run_routine", "Run one of the owner's routines now, e.g. God morgon, and follow what it returns.") {
                string("name", "The routine's name")
                required("name")
            })
            if (!guest) addAll(r.specs())
        }
        memory?.takeIf { !guest && view.settings.allows(Secret.Memory, view.local) }?.let { addAll(it.specs()) }
        knowHow?.takeIf { !guest }?.let { addAll(it.specs()) }
    }

    /** "Next step" and the like while a recipe is open, answered without the AI; null otherwise. */
    fun cookingQuick(text: String, swedish: Boolean? = null): String? = recipes?.quick(text, swedish)

    private val guest get() = view.settings.guest

    suspend fun run(call: ToolCall): ToolResult {
        val l = listener
        val id = StepIds.next()
        val text = StepText.of(call, view)
        l?.step(AgentStep(id, text, StepState.Running))
        l?.trace("tool", "${call.name} ${call.arguments}")
        val result = try {
            (if (guest && call.name in GUEST_BLOCKED) ok("Guests can't do that.") else runAllowed(call))
                .also { if (it.localOnly) privateContext = true }
        } catch (e: kotlinx.coroutines.CancellationException) {
            l?.step(AgentStep(id, text, StepState.Failed, "Stopped"))
            throw e
        }
        val failed = StepText.failed(result.text)
        l?.step(AgentStep(id, text, if (failed) StepState.Failed else StepState.Done, if (failed) view.named(result.text).take(160) else null))
        l?.trace("result", result.text, result.localOnly)
        return result
    }

    private suspend fun runAllowed(call: ToolCall): ToolResult = try {
        val a = call.arguments
        when (call.name) {
            "set_device" -> setDevice(a.str("device"), a.str("capability"), a["value"])
            "set_room" -> setRoom(a.str("room"), a.str("capability"), a["value"], a.str("device_class"), lightsOnly = a.str(LIGHTS_ONLY) == "true")
            "get_device" -> getDevice(a.str("device"))
            "set_mood" -> setMood(a.str("mood"), a.str("room"))
            "set_scene" -> setScene(a.str("room"), a.str("look"))
            "list_flows" -> ToolResult(home.flows().joinToString("\n") { it.name }.ifBlank { "No Flows can be started." })
            "run_flow" -> runFlow(a.str("name"))
            "play_music" -> playMusic(a.str("player"), a.str("query"), a.str("kind").ifBlank { null })
            "save_favourite" -> saveFavourite(a.str("player"), a.str("query"))
            "device_actions" -> deviceActions(a.str("device"))
            "run_device_action" -> runDeviceAction(a.str("device"), a.str("action"), a.str("value").ifBlank { null })
            "open_page" -> openPage(a.str("page"))
            "open_device" -> view.device(a.str("device"))?.let { d -> ok(if (dashboard.openDevice(d.id)) "Opened ${d.name}." else "Can't open ${d.name}.") } ?: unknown(a.str("device"))
            "press_remote" -> pressRemote(a.str("device"), a.str("keys"))
            "vacuum" -> vacuum(a.str("device"), a.str("action"), a.list("rooms"))
            "goodnight" -> goodnight()
            // "several": false is a model filling in every field, not a list of timers called "false".
            "timer" -> timer(a.str("action"), a.num("minutes"), Timers.realName(a.str("label")), a.str("new_label"),
                a.str("several").takeUnless { it.trim().lowercase() in setOf("false", "true", "null", "none", "no") }.orEmpty())
            "recipe_timers" -> recipeTimers()
            "weather" -> ok(dashboard.weather() ?: "The weather isn't available right now.")
            "set_theme" -> ok(if (dashboard.setTheme(a.str("mode").lowercase())) "Theme set." else "Use light, dark or auto.")
            "calendar" -> calendar(a.str("start_date"), a.num("days"), a.str("calendar"))
            "add_event" -> addEvent(a.str("title"), a.str("date"), a.str("time"), a.num("minutes"), a.str("in_calendar"), a.str("location"), "time" in a)
            "who_is_home" -> whoIsHome()
            "set_presence" -> setPresence(a.str("person"), a.str("state"))
            "alarms" -> alarms(a.str("action"), a.str("time"), a.str("days"), a.str("name"))
            "find_phone" -> findPhone(a.str("phone"), a.str("action"))
            "variables" -> variables()
            "ask_internet" -> askInternet(a.str("question"))
            "run_routine" -> runRoutine(a.str("name"))
            // A small model sometimes calls the device instead of its tool ("vacuum_1 {action: go_to}").
            else -> view.device(call.name)?.takeIf { it.deviceClass == "vacuumcleaner" }?.let { d -> vacuum(d.alias, a.str("action"), a.list("rooms")) }
                ?: recipes?.run(call) ?: fridge?.run(call)
                ?: notes?.takeIf { call.name in it.names }?.run(call, view.settings.asker, view.settings.persona.label, view.settings.people)
                ?: routines?.takeIf { !guest && call.name in it.names }?.run(call)
                ?: memory?.takeIf { !guest && view.settings.allows(Secret.Memory, view.local) && call.name in it.names }?.run(call, view.settings.asker, view.local)
                ?: knowHow?.takeIf { !guest && call.name in it.names }?.run(call)
                ?: ok("There is no tool called ${call.name}.")
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
        // A TV's own power key is what its remote sends; switching onoff often fails or shows an error on the TV.
        if (capabilityId == "onoff" && device.deviceClass in TV_CLASSES) {
            val power = actionsOf(device)?.let { cards -> Remotes.buttons(device.capabilities.map { it.id }, cards) { c, a -> home.choices(c, a, "") } }?.get(RemoteKey.Power)
            if (power is RemoteAction.Card) return tvPower(device, power, (value as? JsonPrimitive)?.booleanOrNull == true, lo)
        }
        home.setCapability(device.id, capabilityId, value)
        // Homey sometimes accepts a change the device never makes; check before saying it's done.
        val now = stillReports(device, capabilityId, value, tries = if (device.deviceClass in TV_CLASSES) TV_TRIES else 2)
        if (now != null) {
            return ToolResult(
                "Homey accepted it, but ${device.name} still reports $capabilityId = ${text(now)}, so it didn't change. " +
                    "Tell the user plainly; it may be out of range or switched off at the wall.",
                lo,
            )
        }
        return ToolResult("Done: ${device.name} $capabilityId = ${text(value)}.", lo)
    }

    /**
     * Switches a TV with its own power key, only when it isn't already as asked (the key
     * flips it), then waits for Homey to show it on or off before saying so.
     */
    private suspend fun tvPower(d: AgentDevice, power: RemoteAction.Card, on: Boolean, lo: Boolean): ToolResult {
        val word = if (on) "on" else "off"
        val was = (home.readBack(d.id, "onoff") as? JsonPrimitive)?.booleanOrNull
        if (was == on) return ToolResult("Done: ${d.name} was already $word.", lo)
        home.runAction(power.card, power.args)
        val now = stillReports(d, "onoff", JsonPrimitive(on), TV_TRIES)
            ?: return ToolResult("Done: ${d.name} onoff = $on (Homey shows it $word).", lo)
        return ToolResult(
            "Pressed ${d.name}'s power key, but Homey still reports onoff = ${text(now)}, so it isn't $word yet. Tell the user plainly; it may need a moment or the TV may not be reachable.",
            lo,
        )
    }

    /**
     * Asks Homey up to [tries] times whether [capabilityId] now reads [value]: null when it does
     * or Homey can't tell, else the value it still reports. Slow devices like TVs get more tries.
     */
    private suspend fun stillReports(d: AgentDevice, capabilityId: String, value: JsonElement, tries: Int): JsonElement? {
        var now: JsonElement? = null
        repeat(tries) {
            val read = home.readBack(d.id, capabilityId) ?: return null
            if (same(read, value)) return null
            now = read
        }
        return now
    }

    /**
     * What Homey reports about [d] after an action, compared with [before]: the capabilities
     * that changed, or a note that nothing did yet, so the AI says "sent" rather than "done".
     */
    private suspend fun afterwards(d: AgentDevice, before: Map<String, JsonElement?>): String {
        val after = home.readDevice(d.id) ?: return ""
        val changed = after.capabilitiesObj.mapNotNull { (id, c) ->
            val was = before[id]
            val now = c.value ?: return@mapNotNull null
            if (was != null && same(was, now)) null else "$id ${text(now)}" + (was?.let { " (was ${text(it)})" } ?: "")
        }
        return if (changed.isEmpty()) " Homey shows no change on ${d.name} yet, so tell the user it was sent, not that it is done."
        else " Homey now reports: ${changed.take(6).joinToString()}."
    }

    private fun valuesOf(d: AgentDevice): Map<String, JsonElement?> =
        home.device(d.id)?.capabilitiesObj?.mapValues { it.value.value } ?: d.capabilities.associate { it.id to it.value }

    /** [lightsOnly]: the user said lights ("släck lamporna"), so only lights change, hidden ones still left alone. */
    private suspend fun setRoom(room: String, capabilityId: String, raw: JsonElement?, cls: String, lightsOnly: Boolean = false): ToolResult {
        // The whole house in one call, so "all the lights off" isn't a call per room for the AI to write.
        val everywhere = WHOLE_HOUSE.matches(room.trim()) && view.rooms.values.none { it.equals(room.trim(), ignoreCase = true) }
        // One of the owner's card groups ("Taklampor"), unless a room has exactly that name.
        val group = if (everywhere) null else view.group(room)?.takeIf { g -> view.rooms.values.none { it.equals(room.trim(), ignoreCase = true) } || g.equals(room.trim(), ignoreCase = true) }
        val roomId = if (everywhere || group != null) null else view.room(room) ?: return ok("There's no room or group called $room. Rooms: ${view.rooms.values.sorted().joinToString()}, or all for the whole house.")
        val place = if (everywhere) "the house" else group ?: view.rooms[roomId].orEmpty()
        val wantedClass = classOf(cls)
        val targets = view.devices.mapNotNull { d ->
            val inside = everywhere || if (group != null) d.group == group else view.inRoom(d, roomId!!)
            if (!inside || d.secret != null || (wantedClass != null && d.deviceClass != wantedClass)) return@mapNotNull null
            if (lightsOnly && d.deviceClass != "light") return@mapNotNull null
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

    private suspend fun setMood(alias: String, room: String = ""): ToolResult {
        val mood = view.mood(alias) ?: return ok("There's no mood $alias.")
        // A living-room mood isn't "cozy in the kitchen": it would light the wrong room.
        val asked = room.takeIf { it.isNotBlank() && !WHOLE_HOUSE.matches(it.trim()) }?.let { view.room(it) }
        if (asked != null && mood.room != null && view.room(mood.room) != asked) {
            return ok("${mood.mood.name} is a mood for ${mood.room}, not ${view.rooms[asked]}, so it wasn't turned on. Use set_scene for ${view.rooms[asked]} instead.")
        }
        home.setMood(mood.mood)
        return ok("Mood ${mood.mood.name} is on.")
    }

    /**
     * The lights of [room] set to a [look] ("make it cozy in the kitchen") from what each lamp
     * can do: warm and dim for cozy, a different bright colour per lamp for a party.
     */
    private suspend fun setScene(room: String, look: String): ToolResult {
        val style = SceneLook.of(look) ?: return ok("I don't know the look \"$look\". Looks: ${SceneLook.entries.joinToString { it.word }}.")
        val everywhere = WHOLE_HOUSE.matches(room.trim()) && view.rooms.values.none { it.equals(room.trim(), ignoreCase = true) }
        val group = if (everywhere) null else view.group(room)?.takeIf { g -> view.rooms.values.none { it.equals(room.trim(), ignoreCase = true) } || g.equals(room.trim(), ignoreCase = true) }
        val roomId = if (everywhere || group != null) null else view.room(room) ?: return ok("There's no room or group called $room. Rooms: ${view.rooms.values.sorted().joinToString()}, or all for the whole house.")
        val place = if (everywhere) "the house" else group ?: view.rooms[roomId].orEmpty()
        val lights = view.devices.filter { d ->
            val inside = everywhere || if (group != null) d.group == group else view.inRoom(d, roomId!!)
            inside && d.secret == null && d.deviceClass == "light" && !d.hiddenOnDashboard && !d.unreachable && d.capability("onoff")?.setable == true
        }
        if (lights.isEmpty()) return ok("There are no lights in $place to set.")
        val coloured = lights.filter { it.capability("light_hue")?.setable == true }
        lights.forEach { d ->
            suspend fun put(id: String, value: Any) {
                val c = d.capability(id)?.takeIf { it.setable } ?: return
                val v = coerce(c, when (value) { is Boolean -> JsonPrimitive(value); is Number -> JsonPrimitive(value); else -> JsonPrimitive(value.toString()) }) ?: return
                home.setCapability(d.id, c.id, v)
            }
            put("onoff", true)
            put("dim", style.dim)
            val hue = style.hue ?: if (style.colourful) (coloured.indexOf(d).coerceAtLeast(0) * GOLDEN + style.ordinal * 0.13) % 1.0 else null
            if (hue != null && d.capability("light_hue")?.setable == true) {
                if (d.capability("light_mode")?.setable == true) put("light_mode", "color")
                put("light_hue", hue)
                put("light_saturation", style.saturation)
            } else if (style.warmth != null && d.capability("light_temperature")?.setable == true) {
                if (d.capability("light_mode")?.setable == true) put("light_mode", "temperature")
                put("light_temperature", style.warmth)
            }
        }
        return ok("Changed ${lights.size}: ${lights.joinToString { it.name }} in $place are now ${style.word}.")
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

    /**
     * The player meant when the model names none ("play a chill playlist", "save it"): the one
     * music was last played on, else one playing now, else one that can search Spotify, in the
     * kitchen (where the tablet hangs) first.
     */
    private fun defaultPlayer(): AgentDevice? {
        val players = view.devices.filter { sources(it.id).isNotEmpty() }
        lastPlayer?.let { id -> players.firstOrNull { it.id == id } }?.let { return it }
        players.firstOrNull { it.capability("speaker_playing")?.value?.toString() == "true" }?.let { return it }
        val spotify = players.filter { p -> sources(p.id).any { it.catalogue } }.ifEmpty { players }
        return spotify.firstOrNull { NameMatch.normalize(it.room.orEmpty()) in KITCHEN } ?: spotify.firstOrNull()
    }

    private suspend fun playMusic(alias: String, query: String, kind: String? = null): ToolResult {
        val asked = (if (alias.isBlank()) defaultPlayer() else view.device(alias)) ?: return unknown(alias)
        val d = musicPlayer(asked) ?: return ok(
            "${asked.name} can't play music. These can: " +
                view.devices.filter { sources(it.id).isNotEmpty() }.joinToString { "${it.alias} (${it.name}${it.room?.let { r -> ", $r" }.orEmpty()})" } + ".",
        )
        val played = home.playMusic(d.id, query, kind)
            ?: translated(d.id, query)?.let { home.playMusic(d.id, it, kind) }
            ?: return ok("Couldn't find \"$query\" on ${d.name}." + musicChoices(d.id))
        lastPlayer = d.id
        // The room too, so "Vardagsrum" played on "Beam" isn't taken for the wrong speaker.
        val room = d.room?.takeIf { r -> !NameMatch.normalize(d.name).contains(NameMatch.normalize(r)) }
        return ok("Playing $played on ${d.name}${room?.let { " ($it)" }.orEmpty()}.")
    }

    /**
     * Adds what [query] names (or what was played last) to the household's Sonos favourites
     * through a Sonos speaker's "Add to Sonos favourites"; favourites belong to every speaker,
     * so any speaker that has the card will do, the one asked for first.
     */
    private suspend fun saveFavourite(alias: String, query: String): ToolResult {
        val asked = (if (alias.isBlank()) defaultPlayer() else view.device(alias)) ?: return unknown(alias)
        val first = listOfNotNull(musicPlayer(asked), asked) + view.devices
        val holder = first.firstOrNull { dev -> home.deviceActions(dev.id).any { it.cardId == app.homeydash.sonos.SonosCards.SAVE } }
            ?: return ok("No Sonos speaker was found on the Wi-Fi, so nothing can be added to the Sonos favourites." + (home.missingActions(asked.id)?.let { " ($it)" }.orEmpty()))
        val card = home.deviceActions(holder.id).first { it.cardId == app.homeydash.sonos.SonosCards.SAVE }
        val arg = card.inputArgs.first()
        val q = query.trim()
        val items = home.choices(card, arg, q)
        val item = (if (q.isEmpty()) items.firstOrNull() else NameMatch.best(q, items) { NameMatch.score(q, it.name) } ?: items.firstOrNull())
            ?: return ok(if (q.isEmpty()) "Nothing has been played from here yet; ask what to add." else "Couldn't find \"$q\" to add.")
        home.runAction(card, JsonObject(mapOf(arg.name to FlowCards.argValue(arg, item))))
        return ok("Added ${item.name}" + (item.description?.let { " ($it)" }.orEmpty()) + " to the Sonos favourites.")
    }

    private fun sources(deviceId: String) = FlowCards.musicSources(home.deviceActions(deviceId)).flatMap { it.all }

    /**
     * The player to use for [asked]: itself, or another speaker in the same room when [asked]
     * can't play music, or can't search Spotify and that one can (the same speaker added twice).
     */
    private fun musicPlayer(asked: AgentDevice): AgentDevice? {
        val own = sources(asked.id)
        if (own.any { it.catalogue }) return asked
        val roommates = view.devices.filter { it.id != asked.id && asked.roomId != null && it.roomId == asked.roomId }
        roommates.firstOrNull { o -> sources(o.id).any { it.catalogue } }?.let { return it }
        if (own.isNotEmpty()) return asked
        roommates.firstOrNull { sources(it.id).isNotEmpty() }?.let { return it }
        // Refused only when other players show what they can play; else the home is asked anyway.
        return asked.takeIf { view.devices.none { sources(it.id).isNotEmpty() } }
    }

    /** Some of what the player has, so a miss can be followed by a pick of the model's own. */
    /**
     * The one favourite [query] names in the other language, word by word: "morning jazz" is
     * Morgonjazz (it ends in "jazz", and "morgon" starts like "morning"). Null when none or
     * several do.
     */
    private suspend fun translated(deviceId: String, query: String): String? {
        val words = NameMatch.normalize(query).split(' ').filter { it.isNotBlank() }
        if (words.size < 2 || words.last().length < 4) return null
        val names = FlowCards.musicSources(home.deviceActions(deviceId)).flatMap { it.all }
            .flatMap { source -> runCatching { home.choices(source.card, source.arg, "") }.getOrDefault(emptyList()).map { it.name } }.distinct()
        return names.filter { name ->
            val joined = NameMatch.normalize(name).replace(" ", "")
            val rest = joined.removeSuffix(words.last())
            rest != joined && rest.length >= 3 && rest.take(3) == words.first().take(3)
        }.singleOrNull()
    }

    private suspend fun musicChoices(deviceId: String): String {
        val lists = mutableListOf<String>()
        for (source in FlowCards.musicSources(home.deviceActions(deviceId)).flatMap { it.all }) {
            if (lists.size == 2) break
            // A speaker and its twin both have "Favourites": list them once.
            if (lists.any { it.startsWith("${source.title}:") }) continue
            val items = runCatching { home.choices(source.card, source.arg, "") }.getOrDefault(emptyList())
            if (items.isNotEmpty()) lists += "${source.title}: " + items.take(12).joinToString { it.name } + "."
        }
        val names = lists.joinToString(" ")
        val noSpotify = FlowCards.musicSources(home.deviceActions(deviceId)).flatMap { it.all }.none { it.catalogue }
        return (if (names.isEmpty()) "" else " It has $names Pick one of these if it fits.") +
            (if (noSpotify) " Spotify search isn't set up on this speaker, so only these can be played." else "") +
            (if (noSpotify) home.missingActions(deviceId)?.let { " ($it)" }.orEmpty() else "")
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
            // "favourite" or "radio" for Sonos's "Play Sonos favorite", which the model guesses before looking.
            ?: FAVOURITE.takeIf { it.matches(action.trim()) }?.let { cards.firstOrNull { c -> c.cardId.contains("favorite", ignoreCase = true) } }
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
        val before = valuesOf(d)
        home.runAction(card, JsonObject(args))
        return ok("Done: $title" + (picked?.let { " ($it)" } ?: "") + " on ${d.name}." + afterwards(d, before))
    }

    /**
     * A robot vacuum, whatever its brand: the same buttons and room cleaning the vacuum page
     * finds in its Homey app's capabilities and Flow cards.
     */
    private suspend fun vacuum(alias: String, action: String, rooms: String): ToolResult {
        val vacuums = view.devices.filter { it.deviceClass == "vacuumcleaner" }
        val d = (if (alias.isBlank()) vacuums.singleOrNull() else view.device(alias))
            ?: return if (alias.isBlank()) ok("Which vacuum? " + vacuums.joinToString { "${it.alias} \"${it.name}\"" }) else unknown(alias)
        val cards = actionsOf(d) ?: return ok("${d.name} can't be used here.")
        val device = home.device(d.id) ?: return ok("Homey hasn't said anything about ${d.name} yet; try again in a moment.")
        if (!Vacuums.isVacuum(device)) return ok("${d.name} isn't a robot vacuum.")
        val c = Vacuums.controls(device, cards)
        suspend fun exec(cmd: VacuumCommand?, done: String): ToolResult {
            cmd ?: return ok("${d.name}'s Homey app has no way to $action it.")
            // "Stop" then "dock" in one breath: the robot drops the second if it comes too soon.
            val wait = (lastCommand[d.id] ?: 0L) + VACUUM_GAP_MS - System.currentTimeMillis()
            if (wait > 0) kotlinx.coroutines.delay(wait)
            when (cmd) {
                is VacuumCommand.Set -> home.setCapability(d.id, cmd.capability, cmd.value)
                is VacuumCommand.Card -> home.runAction(cmd.card, cmd.args)
            }
            lastCommand[d.id] = System.currentTimeMillis()
            return ok(done)
        }
        /** Sends it home, and once more if Homey doesn't show it heading there after a few seconds. */
        suspend fun dock(): ToolResult {
            val cmd = c.dock ?: return exec(null, "")
            exec(cmd, "")
            vacuumSent(d.id, DOCK)
            if (homeward(d.id)) return ok("${d.name} is on its way back to its dock.")
            exec(cmd, "")
            return ok(
                if (homeward(d.id)) "${d.name} is on its way back to its dock (it needed a second nudge)."
                else "Sent ${d.name} home twice, but Homey doesn't show it heading back yet; it may need a moment, or a look.",
            )
        }
        val verb = action.trim().lowercase().replace(' ', '_')
        return when (verb) {
            "start" -> exec(c.start, "${d.name} started cleaning.")
            "pause" -> exec(c.pause, "${d.name} paused.")
            "resume", "continue" -> exec(c.resume, "${d.name} carries on.")
            "stop" -> exec(c.stop, "${d.name} stopped.")
            "dock", "home", "return" -> dock()
            "where", "locate", "status", "position", "location" -> ok(whereIs(d.name, device))
            "rooms" -> vacuumRooms(c, cards).let { known ->
                ok(if (known.isEmpty()) "${d.name} doesn't list its rooms in Homey." else "${d.name}'s rooms: " + known.joinToString { it.name } + ".")
            }
            "clean_rooms", "clean", "go_to", "goto" -> {
                val wanted = rooms.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
                if (wanted.isEmpty()) return ok("Say which room.")
                val goTo = verb.startsWith("go")
                val goCard = if (goTo) Vacuums.goToCard(cards) else null
                val goArg = goCard?.second
                if (goCard != null && goArg != null && goArg.type != "text") {
                    val items = home.choices(goCard.first, goArg, wanted[0])
                    val item = NameMatch.best(wanted[0], items) { NameMatch.score(wanted[0], it.name) } ?: items.singleOrNull()
                    if (item != null) {
                        home.runAction(goCard.first, JsonObject(mapOf(goArg.name to FlowCards.argValue(goArg, item))))
                        vacuumSent(d.id, item.name)
                        return ok("${d.name} is driving to ${item.name}.")
                    }
                } else if (goCard != null && goArg != null) {
                    home.runAction(goCard.first, JsonObject(mapOf(goArg.name to JsonPrimitive(wanted[0]))))
                    vacuumSent(d.id, wanted[0])
                    return ok("${d.name} is driving to ${wanted[0]}.")
                }
                val cleaning = c.roomCleaning ?: return ok("${d.name}'s Homey app can't send it to a room; it can only start, stop and dock.")
                val known = vacuumRooms(c, cards)
                val picked = wanted.map { w ->
                    NameMatch.best(w, known) { NameMatch.score(w, it.name) }
                        ?: return ok("${d.name} has no room \"$w\"." + if (known.isEmpty()) "" else " Its rooms: " + known.joinToString { it.name } + ". Use one of these.")
                }.distinct()
                if (goTo && picked.size > 1) return ok("${d.name} can only drive to one room at a time.")
                val max = cleaning.maxRooms
                if (max != null && picked.size > max) return ok("${d.name} can take at most $max room(s) at once.")
                val names = picked.joinToString(" and ") { it.name }
                vacuumSent(d.id, picked.last().name)
                // Without a go-to card, cleaning just that room is how the robot gets there.
                exec(
                    Vacuums.roomArgs(cleaning, picked),
                    if (goTo) "${d.name} has no plain go-to in Homey, so it is cleaning $names, which takes it there. Tell the user, and that \"pause\" stops it when it arrives."
                    else "${d.name} is cleaning $names.",
                )
            }
            else -> ok("Use start, pause, resume, stop, dock, clean_rooms, go_to, rooms or where.")
        }
    }

    /** Waits up to [VACUUM_CHECK_MS] for Homey to show the robot returning, docked or charging. */
    private suspend fun homeward(deviceId: String): Boolean {
        var waited = 0L
        while (home.device(deviceId)?.let { Vacuums.state(it) } !in HOMEWARD) {
            if (waited >= VACUUM_CHECK_MS) return false
            kotlinx.coroutines.delay(VACUUM_POLL_MS)
            waited += VACUUM_POLL_MS
        }
        return true
    }

    /**
     * Where a vacuum is, as far as the app knows: in its dock when Homey says it's docked or
     * charging, else the room it reports (if its app has one) or the room it was last sent to.
     */
    private fun whereIs(name: String, device: app.homeydash.homey.Device): String {
        val state = Vacuums.state(device)
        val room = device.capabilities.mapNotNull { device.capability(it) }.firstOrNull { c ->
            !c.setable && (c.type == "string" || c.type == "enum") && FlowCards.tokens(c.id).any { it in ROOM_WORDS }
        }?.let { c -> c.values?.firstOrNull { it.id == c.stringValue }?.title ?: c.stringValue }?.takeIf { it.isNotBlank() }
        val sent = lastSent[device.id]
        val ago = sent?.let { (_, at) -> ((System.currentTimeMillis() - at) / 60_000).let { m -> if (m < 1) "just now" else if (m < 120) "$m min ago" else "${m / 60} h ago" } }
        return when (state) {
            VacuumState.Docked -> "$name is in its dock."
            VacuumState.Charging -> "$name is in its dock, charging."
            VacuumState.Returning -> "$name is on its way back to its dock."
            VacuumState.Offline -> "$name is offline in Homey, so where it is isn't known."
            else -> {
                val doing = when (state) {
                    VacuumState.Cleaning -> "cleaning"
                    VacuumState.Paused -> "paused"
                    VacuumState.Error -> "stuck or showing an error"
                    else -> "standing still"
                }
                when {
                    room != null -> "$name is $doing in $room."
                    sent != null && sent.first != DOCK -> "$name is $doing; it was last sent to ${sent.first} ($ago), so it's most likely there."
                    else -> "$name is $doing, away from its dock; its app doesn't tell Homey which room it's in."
                }
            }
        }
    }

    private suspend fun vacuumRooms(c: app.homeydash.homey.VacuumControls, cards: List<FlowCardAction>): List<VacuumRoom> {
        val listArg = Vacuums.roomListArg(c.roomCleaning, cards)
        val fromCard = listArg?.let { (card, arg) -> runCatching { Vacuums.roomsFrom(home.choices(card, arg, "")) }.getOrDefault(emptyList()) }.orEmpty()
        return fromCard.ifEmpty { c.pickerRooms }
    }

    /** Presses [keys] ("down x3, ok") on a remote, as the dashboard's remote sheet would. */
    private suspend fun pressRemote(alias: String, keys: String): ToolResult {
        val d = view.device(alias) ?: return unknown(alias)
        val cards = actionsOf(d) ?: return ok("${d.name}'s remote can't be used here.")
        val presses = RemoteKeys.parse(keys) ?: return ok("Name the buttons: ${RemoteKeys.NAMES}.")
        val buttons = Remotes.buttons(d.capabilities.map { it.id }, cards) { c, a -> home.choices(c, a, "") }
        val before = valuesOf(d)
        val missing = presses.distinct().filter { it !in buttons }
        if (missing.isNotEmpty()) {
            return ok("${d.name} has no button for ${missing.joinToString { RemoteKeys.name(it) }}" +
                (if (buttons.isEmpty()) ". Homey offers no remote buttons for it." else "; it has ${buttons.keys.joinToString { RemoteKeys.name(it) }}."))
        }
        for ((i, key) in presses.withIndex()) {
            if (i > 0) kotlinx.coroutines.delay(REMOTE_GAP_MS)
            when (val action = buttons.getValue(key)) {
                is RemoteAction.Card -> home.runAction(action.card, action.args)
                is RemoteAction.Capability -> {
                    val value = if (action.toggle) {
                        val now = home.readBack(d.id, action.capability) ?: d.capability(action.capability)?.value
                        (now as? JsonPrimitive)?.booleanOrNull != true
                    } else true
                    home.setCapability(d.id, action.capability, JsonPrimitive(value))
                }
            }
        }
        val pressed = "pressed ${presses.joinToString { RemoteKeys.name(it) }} on ${d.name}"
        // Power flips it: say what Homey shows once it has had time to switch.
        val wasOn = (before["onoff"] as? JsonPrimitive)?.booleanOrNull
        if (RemoteKey.Power in presses && wasOn != null) {
            val wanted = if (presses.count { it == RemoteKey.Power } % 2 == 1) !wasOn else wasOn
            val now = stillReports(d, "onoff", JsonPrimitive(wanted), TV_TRIES)
                ?: return ok("Done: $pressed; Homey shows it ${if (wanted) "on" else "off"}.")
            return ok("Pressed: $pressed, but Homey still reports onoff = ${text(now)}. Tell the user plainly; it may need a moment.")
        }
        return ok("Done: $pressed.")
    }

    private fun openPage(page: String): ToolResult {
        val p = page.trim().lowercase()
        val fixed = mapOf(
            "overview" to "overview", "home" to "overview", "översikt" to "overview",
            "moods" to "moods", "media" to "media", "music" to "media", "musik" to "media",
            "calendar" to "calendar", "kalender" to "calendar",
            "recipes" to "recipes", "recipe" to "recipes", "recept" to "recipes",
            "fridge" to "fridge", "kylen" to "fridge", "kylskåp" to "fridge", "kylskåpet" to "fridge", "freezer" to "fridge", "frysen" to "fridge",
            "notes" to "notes", "note" to "notes", "post-its" to "notes", "postits" to "notes", "lappar" to "notes", "lapparna" to "notes",
            "todo" to "notes", "todos" to "notes", "to-do" to "notes", "to do" to "notes", "att göra" to "notes", "anteckningar" to "notes",
        )
        fixed[p]?.let { return ok(if (dashboard.openPage(it)) "Showing $it." else "Can't show $it.") }
        val roomId = view.room(page) ?: return ok("There's no page or room called $page.")
        return ok(if (dashboard.openPage("room", roomId)) "Showing ${view.rooms[roomId]}." else "Can't show that room.")
    }

    /** Runs a routine's steps now (the answer step doesn't wait) and returns what to tell the user. */
    private suspend fun runRoutine(name: String): ToolResult {
        val data = routines?.data ?: return ok("There are no routines.")
        val routine = data.named(name)?.takeIf { it.enabled } ?: return ok("There's no routine called $name. ${RoutineTools.describe(data)}")
        val run = RoutineRunner(this, view, today(), Routines.defaultPlayer(view)).run(routine.steps)
        return ToolResult(RoutineText.forBrain(routine, run, emptyList()), localOnly = run.localOnly)
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

    /** A named timer counted for whoever asked, so "the usual" pasta timer is known next time. */
    private suspend fun learnTimer(t: KitchenTimer) {
        val s = view.settings
        if (t.label == null || t.group != null || s.guest || s.access(Secret.Memory) == Access.Off) return
        memory?.learnTimer(t.label, t.seconds, s.asker, clock())
    }

    private suspend fun timer(action: String, minutes: Double?, label: String, newLabel: String, several: String = ""): ToolResult {
        val board = dashboard.timers
        fun named(t: KitchenTimer) = t.label ?: "The timer"
        return when (action.lowercase().replace(' ', '_')) {
            "start", "set" -> {
                val many = Timers.parseSeveral(several)
                if (many.isNotEmpty()) {
                    val started = many.map { (name, secs) ->
                        val s = secs ?: name?.let { memory?.usualTimerSeconds(it, view.settings.asker?.id) }
                        if (s == null || s <= 0 || s > 24 * 3600) "${name ?: "a timer"} (no length: ask how many minutes)"
                        else board.start(s, name, owner = view.settings.asker?.id, character = view.settings.persona.name).also { learnTimer(it) }.let { "${it.label ?: "a timer"} for ${Timers.minutesText(s)}" }
                    }
                    return ok("Started: " + started.joinToString("; ") + ".")
                }
                val usual = if (minutes == null && label.isNotBlank()) memory?.usualTimerSeconds(label, view.settings.asker?.id) else null
                val seconds = usual ?: ((minutes ?: 0.0) * 60).toInt()
                if (seconds <= 0 || seconds > 24 * 3600) return ok("Ask how many minutes.")
                learnTimer(board.start(seconds, label.ifBlank { null }, owner = view.settings.asker?.id, character = view.settings.persona.name))
                val length = Timers.minutesText(seconds)
                when {
                    usual != null -> ok("Timer ${label.trim()} started for $length, the usual length. Say that it's the usual.")
                    label.isNotBlank() -> ok("Timer ${label.trim()} started for $length.")
                    view.settings.askTimerName -> ok(
                        "Timer started for $length. In the same short answer, ask whether they want to give it a name. " +
                            "If they answer with a name, call timer with action rename and new_label; if they say no, leave it.",
                    )
                    else -> ok("Timer started for $length.")
                }
            }
            "cancel", "stop", "remove", "delete" -> board.cancel(label)?.let { ok("${named(it)} is stopped.") }
                ?: ok(if (board.timers.value.isEmpty()) "No timers are running." else "No timer like that. Running: " + board.describe().joinToString("; "))
            "cancel_all", "stop_all" -> {
                val n = board.cancelAll(label.ifBlank { null })
                ok(if (n == 0) "No timers were running." else "Stopped $n timer${if (n == 1) "" else "s"}.")
            }
            "rename", "name" -> {
                if (newLabel.isBlank()) return ok("Ask what to call it.")
                board.rename(label, newLabel)?.let { learnTimer(it); ok("The timer is now called ${it.label}.") } ?: ok("No timers are running.")
            }
            "add", "extend", "snooze", "add_minutes", "add_time" -> {
                val seconds = ((minutes ?: 1.0) * 60).toInt()
                if (seconds == 0) return ok("Ask how many minutes.")
                // "all" from a model means the one running; with none running, "add a timer" means start one.
                val which = label.takeUnless { it.equals("all", ignoreCase = true) }.orEmpty()
                board.add(which, seconds)?.let { ok("${named(it)} now has ${Timers.clock(it.leftMs(System.currentTimeMillis()))} left.") }
                    ?: if (board.timers.value.isEmpty() && minutes != null) timer("start", minutes, which, newLabel) else ok("No timer like that is running.")
            }
            "mute", "quiet", "silence" -> {
                val n = board.mute(label.ifBlank { null })
                val s = view.settings
                if (n > 0 && !s.guest && s.access(Secret.Memory) != Access.Off) memory?.learn(Timers.mutedHabit(s.asker, clock()))
                ok(if (n == 0) "No timers are running." else "Muted: no more spoken countdown" + if (label.isBlank()) " for any timer." else " for that timer.")
            }
            "unmute" -> ok(if (board.mute(label.ifBlank { null }, mute = false) == 0) "No timers are running." else "The countdown is spoken again.")
            "pause" -> board.pause(label, true)?.let { ok("${named(it)} is paused.") } ?: ok("No timer like that is running.")
            "resume", "continue" -> board.pause(label, false)?.let { ok("${named(it)} is running again.") } ?: ok("No timer like that is running.")
            else -> ok(board.describe().joinToString("\n").ifBlank { "No timers are running." })
        }
    }

    private fun recipeTimers(): ToolResult {
        val timers = recipes?.stepTimers().orEmpty()
        if (timers.isEmpty()) return ok("No recipe is open, or its steps name no times.")
        timers.forEach { (step, t) -> dashboard.timers.start(t.seconds, "Steg $step · ${t.label}", recipes?.openName()) }
        return ok("Started: " + timers.joinToString { (step, t) -> "step $step ${t.label}" } + ".")
    }

    private suspend fun calendar(start: String, days: Double?, whose: String): ToolResult {
        if (!view.allows(Secret.Calendar)) return ok("Reading the calendar is switched off.")
        val from = CalendarBook.day(start, today()) ?: today()
        val count = (days ?: 1.0).toInt().coerceIn(1, 31)
        val details = view.allows(Secret.CalendarDetails)
        val events = if (whose.isBlank()) {
            dashboard.calendar(from, count, withLocations = details)
        } else {
            when (val pick = CalendarBook.pick(whose, dashboard.writableCalendars(), view.settings)) {
                is CalendarBook.Pick.Problem -> return ok(pick.text)
                is CalendarBook.Pick.Found -> dashboard.calendarIn(setOf(pick.calendar.id), from, count, withLocations = details)
            }
        }
        val text = if (events.isEmpty()) "No events." else events.joinToString("\n") { e ->
            listOfNotNull(e.start + (e.end?.let { " to $it" } ?: ""), e.title, e.location).joinToString(" · ")
        }
        return ToolResult(text, localOnly(Secret.Calendar) || localOnly(Secret.CalendarDetails))
    }

    /**
     * Puts an event in a calendar after a yes: the asker's own, the family's or someone's by
     * name. What isn't said (time, length, calendar) is what this asker usually gives an event
     * of that name, from the memory; the yes question says it all back so it can be corrected.
     */
    private suspend fun addEvent(title: String, date: String, time: String, minutes: Double?, whose: String, location: String, timeSaid: Boolean): ToolResult {
        if (!view.allows(Secret.AddEvents)) return ok("Adding calendar events is switched off under the assistant's Privacy and security settings.")
        val name = title.trim().ifEmpty { return ok("Nothing was added. Ask only what the event should be called, then call add_event again with it.") }
        val day = CalendarBook.day(date, today()) ?: return ok("Which day? Give a weekday, today, tomorrow or yyyy-mm-dd.")
        val s = view.settings
        val usual = memory?.takeIf { s.access(Secret.Memory) != Access.Off }?.usualEvent(name, s.asker?.id)
        val at = if (timeSaid && time.isNotBlank()) CalendarBook.time(time).getOrElse { return ok(it.message.orEmpty()) } else usual?.time
        val length = minutes?.toInt()?.takeIf { it in 5..(24 * 60) } ?: usual?.minutes ?: CalendarBook.DEFAULT_MINUTES
        val calendars = dashboard.writableCalendars()
        val calendar = when (val pick = CalendarBook.pick(whose, calendars, s, usual?.calendarId)) {
            is CalendarBook.Pick.Problem -> return ok(pick.text)
            is CalendarBook.Pick.Found -> pick.calendar
        }
        val event = NewEvent(calendar.id, name, day, at, length, location.trim().ifEmpty { null })
        val where = CalendarBook.label(calendar, calendars, s)
        val said = CalendarBook.describe(event, today())
        return ToolResult(
            "Not added yet: it waits for the user's yes. Ask only this, once, in the user's language, with the day in words: " +
                "add $said to $where? If they then change something, call add_event again with the change.",
            pending = PendingAction("Add $said to $where?", tapOnly = false) {
                dashboard.addEvent(event)
                if (s.access(Secret.Memory) != Access.Off) memory?.learn(CalendarBook.habit(event, where, s.asker, clock()))
                "Added to $where."
            },
        )
    }

    private suspend fun whoIsHome(): ToolResult {
        if (!view.allows(Secret.Presence)) return ok("Presence is switched off.")
        val now = clock()
        val text = home.people().joinToString("\n") { p ->
            val where = when (p.present) { true -> "home"; false -> "away"; null -> "unknown" }
            val since = p.since?.let { " since " + Household.whenText(java.time.Instant.ofEpochMilli(it).atZone(now.zone), now).removePrefix("today ") }.orEmpty()
            "${p.name}: $where${if (p.asleep == true) ", asleep" else ""}$since"
        }.ifBlank { "No Homey users." }
        return ToolResult(text, localOnly(Secret.Presence))
    }

    /** Marks someone home, away, asleep or awake in Homey; the asker when no one is named. */
    private suspend fun setPresence(person: String, state: String): ToolResult {
        if (!view.allows(Secret.Presence)) return ok("Presence is switched off.")
        val lo = localOnly(Secret.Presence)
        val s = state.trim().lowercase()
        val (present, asleep) = when {
            s in setOf("home", "hemma", "present", "back", "arrived") -> true to null
            s in setOf("away", "borta", "out", "left", "gone", "ute") -> false to false
            s in setOf("asleep", "sleep", "sleeping", "sover", "sova", "night") -> null to true
            s in setOf("awake", "wake", "up", "vaken", "uppe", "morning") -> null to false
            else -> return ok("Say home, away, asleep or awake.")
        }
        val people = home.people().filter { it.id.isNotEmpty() }
        if (people.isEmpty()) return ToolResult("No Homey users to mark.", lo)
        val asked = person.trim()
        val chosen = when {
            EVERYONE.matches(asked) -> people.filter { it.present == true }.ifEmpty { return ToolResult("Nobody is home.", lo) }
            asked.isEmpty() || OWN_PHONE.matches(asked) -> {
                val asker = view.settings.asker ?: return ToolResult("I don't know who is asking. Say whose, e.g. \"Anna\".", lo)
                listOf(people.firstOrNull { NameMatch.score(asker.name, it.name) >= 0.9 }
                    ?: return ToolResult("${asker.name} isn't a Homey user, so Homey can't mark them.", lo))
            }
            else -> listOf(NameMatch.best(asked, people) { NameMatch.score(asked, it.name) }
                ?: return ToolResult("There's no Homey user called $asked. Users: ${people.joinToString { it.name }}.", lo))
        }
        for (p in chosen) {
            try {
                home.setPresence(p.id, present, asleep)
            } catch (e: app.homeydash.homey.HomeyHttpException) {
                if (e.code == 401 || e.code == 403) return ToolResult("The Homey API key may not change presence. Give it the \"Presence\" permission in the Homey web app.", lo)
                throw e
            }
        }
        val word = when { asleep == true -> "asleep"; asleep == false && present == null -> "awake"; present == true -> "home"; else -> "away" }
        return ToolResult("Homey now has ${chosen.joinToString { it.name }} as $word.", lo)
    }

    /** Lists, sets and switches Homey's wake-up alarms; an unnamed alarm is the asker's own. */
    private suspend fun alarms(action: String, time: String, days: String, name: String): ToolResult {
        if (!view.allows(Secret.WakeAlarms)) return ok("Wake-up alarms are switched off for the assistant.")
        val lo = localOnly(Secret.WakeAlarms)
        val now = clock()
        val list = home.alarms()
        val asker = view.settings.asker
        fun describe(a: HomeyAlarm): String {
            val ring = Household.nextRing(a, now)
            return "${a.name}: ${Household.timeText(a.time)}, ${Household.daysText(a.days)}, " +
                (if (ring != null) "rings ${Household.whenText(ring, now)}" else "off")
        }
        // "mitt alarm": the one named after the asker.
        fun mine(): HomeyAlarm? = asker?.let { who -> list.firstOrNull { it.name.contains(who.name, ignoreCase = true) } }
        // The named one, else the asker's, else the only one there is.
        fun target(): HomeyAlarm? {
            val asked = name.trim()
            if (asked.isNotEmpty()) return NameMatch.best(asked, list) { NameMatch.score(asked, it.name) }
            return mine() ?: list.singleOrNull()
        }
        fun denied(e: Exception) = e is app.homeydash.homey.HomeyHttpException && (e.code == 401 || e.code == 403)
        val noRight = "The Homey API key may not change alarms. Give it the \"Alarms\" permission in the Homey web app."
        return try { when (action.trim().lowercase()) {
            "", "list", "status", "next" -> ToolResult(if (list.isEmpty()) "No wake-up alarms in Homey." else list.joinToString("\n", transform = ::describe), lo)
            "set", "create", "add", "change", "update" -> {
                val at = Household.parseTime(time) ?: return ok("Say the time as HH:mm, e.g. 06:15.")
                // Never someone else's: a new alarm unless it's named, or the asker's own.
                val existing = when {
                    name.isNotBlank() -> NameMatch.best(name.trim(), list) { NameMatch.score(name.trim(), it.name) }
                    asker != null -> mine()
                    else -> list.firstOrNull { it.name.equals(DEFAULT_ALARM, ignoreCase = true) }
                }
                val repeat = if (days.isBlank()) existing?.days ?: emptySet() else Household.parseDays(days) ?: return ok("Say the days as once, weekdays, weekends, every day or day names.")
                val alarmName = name.trim().ifBlank { existing?.name ?: asker?.let { "$DEFAULT_ALARM ${it.name}" } ?: DEFAULT_ALARM }
                val saved = home.saveAlarm(existing?.id, alarmName, at, repeat, true)
                ToolResult("Set: " + describe(saved) + ".", lo)
            }
            "on", "enable" -> {
                val a = target() ?: return ToolResult(pickOne(list), lo)
                val saved = home.saveAlarm(a.id, a.name, a.time, a.days, true)
                ToolResult("On: " + describe(saved) + ".", lo)
            }
            "off", "disable", "cancel" -> {
                val a = target() ?: return ToolResult(pickOne(list), lo)
                home.saveAlarm(a.id, a.name, a.time, a.days, false)
                ToolResult("${a.name} is off. It won't ring until switched on again.", lo)
            }
            "skip" -> {
                val a = target() ?: return ToolResult(pickOne(list), lo)
                val ring = home.skipAlarm(a.id) ?: return ToolResult("${a.name} isn't set to ring.", lo)
                ToolResult("${a.name} won't ring ${Household.whenText(ring, now)}." + if (a.repeats) " It rings as usual after that." else "", lo)
            }
            "delete", "remove" -> {
                val a = target() ?: return ToolResult(pickOne(list), lo)
                home.deleteAlarm(a.id)
                ToolResult("Deleted ${a.name}.", lo)
            }
            else -> ok("Use list, set, on, off, skip or delete.")
        } } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (denied(e)) ToolResult(noRight, lo) else throw e
        }
    }

    private fun pickOne(list: List<HomeyAlarm>) =
        if (list.isEmpty()) "There are no wake-up alarms in Homey." else "Which alarm? " + list.joinToString { "\"${it.name}\" (${Household.timeText(it.time)})" } + "."

    /** Rings a phone and says whether it's home, for its owner only unless sharing allows more; see [Phones]. */
    private suspend fun findPhone(whose: String, action: String): ToolResult {
        val s = view.settings
        val phones = view.phones
        if (phones.isEmpty()) return ok("No phones in Homey. Add them with Homey's Apple Find My app, and switch on Find phones.")
        fun owner(p: AgentDevice) = Phones.ownerOf(p, s)
        val asked = whose.trim().takeUnless { it.isEmpty() || OWN_PHONE.matches(it) }
        val chosen: List<AgentDevice> = if (asked == null) {
            val asker = s.asker
            val mine = phones.filter { asker != null && owner(it) == asker.id }
            when {
                mine.isNotEmpty() -> mine
                phones.size == 1 && Phones.rightTo(phones.single(), s) != Phones.Right.None -> phones
                asker == null -> return ok("I don't know who is asking, so I can't tell which phone is yours. Teach me your voice under People, or say whose phone.")
                else -> return ok("${asker.name} has no phone in Homey's Find My app. Pick it under Settings → Assistant → Privacy → Phones.")
            }
        } else {
            // "Anna", "Annas telefon", "the iPad": by its owner's name or by its own.
            val byOwner = phones.filter { p -> owner(p)?.let { s.people[it] }?.let { NameMatch.score(asked, it) >= 0.95 || Phones.named(asked, it) } == true }
            byOwner.ifEmpty { listOfNotNull(NameMatch.best(asked, phones) { NameMatch.score(asked, it.name) }) }
                .ifEmpty { return ok("I can't find a phone called \"$asked\". The phones are: " + phones.joinToString { "\"${it.name}\"" } + ".") }
        }
        val onlyWhere = action.trim().lowercase() in WHERE_WORDS
        val rung = mutableListOf<Pair<AgentDevice, FlowCardAction>>()
        val lines = chosen.map { p ->
            val right = Phones.rightTo(p, s)
            val ownerName = owner(p)?.let { s.people[it] }
            when {
                right == Phones.Right.None ->
                    "${p.name} is ${ownerName ?: "someone else"}'s phone; only ${ownerName ?: "its owner"} can find it."
                onlyWhere && right != Phones.Right.All -> "${p.name}: only ${ownerName ?: "its owner"} may hear where it is."
                onlyWhere -> "${p.name}: ${Phones.status(fresh(p))}."
                else -> {
                    val card = home.deviceActions(p.id).let(Phones::ringCard)
                        ?: return@map "${p.name} can't ring: its Homey app has no \"Play sound\" card."
                    if (!ring(card, s)) return@map "${p.name} didn't ring: Homey's Find My app didn't answer. Say so; don't say it rings."
                    rung += p to card
                    // Ringing never asks Apple where the phone is: that is what wakes its GPS and drains it.
                    "Ringing ${p.name}" + if (right == Phones.Right.All) " (last known: ${Phones.status(p)})." else "."
                }
            }
        }
        // Rung: the assistant asks later whether it was found and rings again when nobody answers.
        val hunt = rung.takeIf { it.isNotEmpty() }?.let { phones ->
            PhoneHunt(phones.map { it.first.name }) { phones.map { (_, card) -> ring(card, s) }.any { it } }
        }
        val tell = if (hunt != null) "\nTell the user it rings now and to say when they've found it; you'll ask again if they don't." else ""
        return ToolResult(lines.joinToString("\n") + tell, localOnly(Secret.Phones), hunt = hunt)
    }

    /** Plays the phone's sound; false when Homey or Apple didn't take it. */
    private suspend fun ring(card: FlowCardAction, s: AgentSettings): Boolean = try {
        home.runAction(card, Phones.ringArgs(card, s.nameOf(s.persona)))
        true
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /**
     * [phone] as Find My sees it right now: one "Update device data" asked only when someone
     * wants to know, so the app's own polling (which wakes the phone's GPS) can be turned off.
     */
    private suspend fun fresh(phone: AgentDevice): AgentDevice {
        val card = Phones.refreshCard(home.deviceActions(phone.id)) ?: return phone
        runCatching { home.runAction(card, JsonObject(emptyMap())) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        val caps = kotlinx.coroutines.coroutineScope {
            phone.capabilities.map { c -> async { home.readBack(phone.id, c.id)?.let { c.copy(value = it) } ?: c } }.awaitAll()
        }
        return phone.copy(capabilities = caps)
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
        return ok(lookup.answer(question, view.settings.answers))
    }

    /** Whether a question names a device alias or a guarded device, which must stay home. */
    private fun mentionsHome(question: String): Boolean {
        val q = question.lowercase()
        if (ALIAS.containsMatchIn(q)) return true
        return view.devices.any { d -> d.secret != null && d.name.length > 2 && d.name.lowercase() in q }
    }

    private fun unknown(alias: String): ToolResult {
        // Phones aren't in the device list: "Gabriels iPhone" must not turn into a lamp named Gabriel.
        view.phones.firstOrNull { NameMatch.score(alias, it.name) >= 0.8 }?.let { p ->
            return ok("\"${p.name}\" is a phone: use find_phone to ring it.")
        }
        val near = view.similarDevices(alias)
        return ok(
            if (near.isEmpty()) "I can't see a device called $alias. Use a name from the list."
            else "\"$alias\" could be: " + near.joinToString { "\"${it.name}" + (it.room?.let { r -> ", $r" } ?: "") + "\"" } +
                ". Pick one, or ask the user which they mean.",
        )
    }

    private fun ok(text: String) = ToolResult(text)

    companion object {
        /** Set by the agent, not the AI: set_room changes only lights (see Agent.lightsOnly). */
        const val LIGHTS_ONLY = "_lights_only"

        /** Spreads party colours evenly around the colour wheel. */
        private const val GOLDEN = 0.618034

        /** How a tool asks for a device: by name, since small models mix up alias numbers. */
        private const val DEVICE = "The device's name as listed, with its room after a comma when several share the name, e.g. \"Taklampa, Sovrum\""

        /** The player music was last played on, for a play_music or save_favourite that names none. */
        @Volatile private var lastPlayer: String? = null
        private val KITCHEN = setOf("kok", "koket", "kitchen")

        /** What a model calls a speaker's favourites when it guesses an action name. */
        private val FAVOURITE = Regex("""(?i)(play_?)?(favou?rites?|favorit(er)?|radio|station|kanal)""")

        /** "all", "the whole house", "hela huset": set_room for every room at once. */
        private val WHOLE_HOUSE = Regex("""(?i)(all|alla|everything|everywhere|allt|överallt|((the )?whole )?(house|home)|hela (huset|hemmet)|hemma|alla rum|all rooms)""")

        private val ALIAS = Regex("""\b(light|socket|player|climate|sensor|blind|vacuum|lock|alarm|camera|fan|device|mood|phone)_\d+\b""")

        /** "min", "my phone", "mobilen": the asker's own phone. */
        private val OWN_PHONE = Regex("""(?i)(my|mine|me|min|mitt|mig|jag|own|egen|eget)?\s*(phone|telefon(en)?|mobil(en)?|iphone(n)?|cell)?""")
        /** What an alarm the assistant makes is called, with the asker's name after it. */
        const val DEFAULT_ALARM = "Väckning"
        private val EVERYONE = Regex("""(?i)(everyone|everybody|all|alla|allihop|allihopa|hela familjen|the whole family)""")
        // Not "locate", "find" or "hitta": finding a phone means ringing it (the AI sent "locate" for "hitta min telefon").
        private val WHERE_WORDS = setOf("where", "var", "status", "home", "hemma", "battery", "batteri")
        /** Guests may use the actions of TVs and speakers, nothing else's. */
        private val GUEST_MEDIA = setOf("tv", "speaker", "amplifier")
        private const val MAX_ACTIONS = 40

        private const val REMOTE_GAP_MS = 350L
        private val TV_CLASSES = setOf("tv", "settopbox")
        /** Reads of a TV's state after switching it, about 1.5 s apart: TVs take a few seconds. */
        private const val TV_TRIES = 5
        private const val DOCK = "its dock"
        private const val VACUUM_GAP_MS = 4_000L
        private const val VACUUM_CHECK_MS = 6_000L
        private const val VACUUM_POLL_MS = 1_000L
        private val HOMEWARD = setOf(VacuumState.Returning, VacuumState.Docked, VacuumState.Charging)
        /** When each robot was last given a command (device id to time). */
        private val lastCommand = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private val ROOM_WORDS = setOf("room", "rum", "segment", "zone", "area", "location", "position")
        private val GUEST_BLOCKED = setOf("vacuum", "list_flows", "run_flow", "goodnight", "set_theme", "calendar", "add_event", "who_is_home", "variables", "find_phone", "set_presence", "alarms")

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

        /** A text or a list of texts as one comma-separated text: models send ["Kök"] as often as "Kök". */
        private fun JsonObject.list(key: String): String = when (val v = this[key]) {
            is kotlinx.serialization.json.JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(", ")
            else -> str(key)
        }

        /**
         * Where each vacuum was last sent by the assistant or its page (device id to place and
         * when), since most robots don't tell Homey which room they're in.
         */
        private val lastSent = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

        /** Remembers that [deviceId] was sent to [place], e.g. "Kök" or "its dock". */
        fun vacuumSent(deviceId: String, place: String) { lastSent[deviceId] = place to System.currentTimeMillis() }

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

/**
 * How lights look for a feeling, for rooms without a mood. [warmth] is Homey's light_temperature
 * (0 cold, 1 warm); [hue] a fixed colour, or a different one per lamp when [colourful].
 */
enum class SceneLook(val word: String, val dim: Double, val warmth: Double?, val hue: Double? = null, val saturation: Double = 0.0, val colourful: Boolean = false, val words: List<String>) {
    Cozy("cozy", 0.35, 0.95, words = listOf("cozy", "cosy", "mysig", "mysigt", "mys", "myskväll", "hygge", "warm", "varm", "varmt")),
    Party("party", 1.0, null, saturation = 1.0, colourful = true, words = listOf("party", "fest", "festligt", "disco", "disko", "colourful", "colorful", "färgglatt", "färgglad")),
    Romantic("romantic", 0.25, 1.0, hue = 0.97, saturation = 0.75, words = listOf("romantic", "romantiskt", "romantisk", "date", "dejt")),
    Relax("relax", 0.5, 0.8, words = listOf("relax", "relaxed", "calm", "lugnt", "lugn", "avslappnat", "chill", "soft", "mjukt")),
    Movie("movie", 0.12, 0.9, words = listOf("movie", "film", "filmkväll", "tv", "cinema", "bio")),
    Focus("focus", 1.0, 0.2, words = listOf("focus", "fokus", "work", "jobba", "arbete", "study", "plugga", "read", "läsa", "läsljus")),
    Bright("bright", 1.0, 0.5, words = listOf("bright", "ljust", "full", "max", "starkt", "clean", "städa", "cleaning")),
    Night("night", 0.05, 1.0, words = listOf("night", "natt", "nattljus", "sleep", "sova", "dimmed", "dämpat"));

    companion object {
        fun of(look: String): SceneLook? {
            val w = NameMatch.normalize(look)
            if (w.isEmpty()) return null
            return entries.firstOrNull { e -> e.words.any { NameMatch.normalize(it) == w } }
                ?: entries.firstOrNull { e -> e.words.any { w.split(' ').contains(NameMatch.normalize(it)) } }
        }
    }
}
