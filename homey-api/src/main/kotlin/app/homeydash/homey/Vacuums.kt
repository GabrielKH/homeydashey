package app.homeydash.homey

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class VacuumState { Cleaning, Paused, Returning, Docked, Charging, Idle, Error, Offline }

/** One thing a vacuum button does: set a capability or run one of its app's Flow cards. */
sealed interface VacuumCommand {
    data class Set(val capability: String, val value: JsonElement) : VacuumCommand
    data class Card(val card: FlowCardAction, val args: JsonObject = JsonObject(emptyMap())) : VacuumCommand
}

/** A button on the vacuum page, such as "Empty dustbin" or "Locate". */
data class VacuumButton(val title: String, val command: VacuumCommand)

/** A Flow card with one list or on/off argument, such as "Load map" or "Start routine". */
data class VacuumChoiceCard(val title: String, val card: FlowCardAction, val arg: FlowCardArg)

/** A room the robot can be sent to. [raw] is the room card's autocomplete entry, when the rooms came from a card. */
data class VacuumRoom(val id: String, val name: String, val raw: JsonObject? = null)

/** How the robot is sent to chosen rooms. */
sealed interface RoomCleaning {
    /** Most rooms at once; null when there's no limit. */
    val maxRooms: Int?

    /** Cards with one room list per room ("Clean two rooms": room1, room2), by number of rooms. */
    data class Cards(val byCount: Map<Int, FlowCardAction>) : RoomCleaning {
        override val maxRooms: Int get() = byCount.keys.max()
    }

    /** A card taking room names as text, like "Clean one or more rooms: Kitchen, Hall". */
    data class Text(val card: FlowCardAction, val arg: FlowCardArg) : RoomCleaning {
        override val maxRooms: Int? get() = null
    }

    /** A room picker capability: picking a room sends the robot there, one room at a time. */
    data class Picker(val capability: String) : RoomCleaning {
        override val maxRooms: Int get() = 1
    }
}

/**
 * A part that wears out: filter, brushes, mop, sensors. [leftFraction] or [leftHours] come
 * from the device when its app reports them; otherwise the dashboard estimates wear from
 * cleaning hours against [lifetimeHours].
 */
data class VacuumPart(
    val key: String,
    val name: String,
    /** The capability that reports how much is left, when the app has one. */
    val capability: String? = null,
    val leftFraction: Double? = null,
    val leftHours: Double? = null,
    /** Typical life in cleaning hours, for the estimate. */
    val lifetimeHours: Double,
    /** Resets the robot's own counter after a replacement. */
    val reset: VacuumCommand? = null,
)

data class VacuumControls(
    val state: VacuumState,
    /** What the robot itself says it is doing, e.g. "Returning to dock", when it reports that. */
    val statusText: String?,
    val battery: Double?,
    val charging: Boolean,
    val start: VacuumCommand?,
    val pause: VacuumCommand?,
    val resume: VacuumCommand?,
    val stop: VacuumCommand?,
    val dock: VacuumCommand?,
    /** Lists like suction power, mop intensity and mode, from setable enum capabilities. */
    val pickers: List<Capability>,
    val mapPicker: Capability?,
    val toggles: List<Capability>,
    val buttons: List<VacuumButton>,
    val choiceCards: List<VacuumChoiceCard>,
    val roomCleaning: RoomCleaning?,
    /** Rooms known without asking the Homey, from a room picker capability. */
    val pickerRooms: List<VacuumRoom>,
    /** Readings such as cleaning time and area. */
    val readings: List<Capability>,
    /** Alarms that are on: stuck, bin full, water tank empty. */
    val alarms: List<Capability>,
    val map: DeviceImage?,
    val parts: List<VacuumPart>,
)

/**
 * Builds a robot vacuum's controls from whatever its Homey app exposes: Homey's standard
 * `vacuumcleaner_state`, app-specific capabilities (Roborock's `clean_full`, `pause_clean`,
 * `suction_power`, room pickers), its map image and its app's Flow cards. Nothing here is
 * specific to one brand; ids are matched by their words.
 */
object Vacuums {

    fun isVacuum(device: Device): Boolean =
        device.effectiveClass == "vacuumcleaner" || device.deviceClass == "vacuumcleaner" || "vacuumcleaner_state" in device.capabilities

    fun controls(device: Device, cards: List<FlowCardAction>): VacuumControls {
        val caps = device.capabilities.mapNotNull { device.capability(it) }
        val components = uiComponents(device)
        val used = mutableSetOf<String>()
        val usedCards = mutableSetOf<String>()

        val stdState = caps.firstOrNull { it.id == "vacuumcleaner_state" }
        val setBooleans = caps.filter { it.type == "boolean" && it.setable }
        // Rules in order of preference: the first rule any capability matches wins.
        fun boolCap(vararg rules: (Set<String>) -> Boolean) = rules.firstNotNullOfOrNull { rule -> setBooleans.firstOrNull { rule(words(it.id)) } }
        fun card(rule: (Set<String>) -> Boolean) = cards.firstOrNull { it.inputArgs.isEmpty() && rule(FlowCards.tokens(it.cardId)) }

        val startCap = boolCap({ "start" in it && "delay" !in it }, { "clean" in it && it.any { w -> w in WHOLE_HOME } })
        val pauseCap = boolCap({ "pause" in it })
        val stopCap = boolCap({ "stop" in it && "dock" !in it })
        val dockCap = boolCap({ "dock" in it && ("cancel" in it || "stop" in it) }, { it.any { w -> w in DOCK_WORDS } && "state" !in it })
        val onoff = caps.firstOrNull { it.id == "onoff" && it.setable }
        val startCard = card { (("start" in it || "clean" in it) && it.any { w -> w in WHOLE_HOME }) || it == setOf("start") || it == setOf("start", "cleaning") || it == setOf("start", "clean") }
        val pauseCard = card { "pause" in it }
        val resumeCard = card { "resume" in it || "continue" in it }
        val stopCard = card { "stop" in it && "dock" !in it }
        val dockCard = card { it.any { w -> w in DOCK_WORDS } && "dnd" !in it }

        fun set(cap: Capability?, value: JsonElement) = cap?.let { used += it.id; VacuumCommand.Set(it.id, value) }
        fun run(c: FlowCardAction?) = c?.let { usedCards += it.id; VacuumCommand.Card(it) }

        val stdSetable = stdState?.takeIf { it.setable }
        val start = set(stdSetable, JsonPrimitive("cleaning")) ?: set(startCap, JsonPrimitive(true)) ?: run(startCard) ?: set(onoff, JsonPrimitive(true))
        val pause = set(pauseCap, JsonPrimitive(true)) ?: run(pauseCard)
        val resume = run(resumeCard) ?: set(pauseCap, JsonPrimitive(false)) ?: start
        val stop = set(stopCap, JsonPrimitive(true)) ?: run(stopCard) ?: set(stdSetable, JsonPrimitive("stopped"))
            ?: startCap?.let { set(it, JsonPrimitive(false)) } ?: onoff?.let { set(it, JsonPrimitive(false)) }
        val dock = set(stdSetable, JsonPrimitive("docked")) ?: set(dockCap, JsonPrimitive(true)) ?: run(dockCard)
        // The plain "docked" capability is usually also a button that sends the robot home; keep it out of the list.
        setBooleans.filter { words(it.id).any { w -> w in DOCK_WORDS } }.forEach { used += it.id }
        listOfNotNull(pauseCard, resumeCard, stopCard, dockCard, startCard).forEach { usedCards += it.id }

        // Rooms and maps.
        val roomPicker = caps.firstOrNull { it.type == "enum" && it.setable && words(it.id).any { w -> w in ROOM_WORDS } }
        val mapPicker = caps.firstOrNull { it.type == "enum" && it.setable && "map" in words(it.id) }
        listOfNotNull(roomPicker, mapPicker).forEach { used += it.id }
        val roomCleaning = roomCleaning(cards, roomPicker)
        when (roomCleaning) {
            is RoomCleaning.Cards -> roomCleaning.byCount.values.forEach { usedCards += it.id }
            is RoomCleaning.Text -> usedCards += roomCleaning.card.id
            else -> Unit
        }
        val pickerRooms = roomPicker?.values.orEmpty().map { VacuumRoom(it.id, it.title ?: it.id) }

        // Parts that wear out.
        val parts = parts(caps, cards, setBooleans, used, usedCards)

        val pickers = caps.filter { it.type == "enum" && it.setable && it.id !in used && it.id != "vacuumcleaner_state" && !it.values.isNullOrEmpty() }
            .sortedBy { c -> PICKER_ORDER.indexOfFirst { w -> w in words(c.id) }.let { if (it < 0) PICKER_ORDER.size else it } }
        pickers.forEach { used += it.id }

        val toggles = mutableListOf<Capability>()
        val buttons = mutableListOf<VacuumButton>()
        setBooleans.filter { it.id !in used && it.id != "onoff" }.forEach { c ->
            val component = components[c.id]
            val isButton = component == "button" || c.id.startsWith("button.") ||
                (component == null && (words(c.id).any { it in ACTION_WORDS } || !c.getable))
            if (isButton) buttons += VacuumButton(title(c), VacuumCommand.Set(c.id, JsonPrimitive(true))) else toggles += c
            used += c.id
        }
        // The app's own Flow cards that the capabilities don't already cover.
        val capWords = caps.map { words(it.id) - SET_BUTTON }.toSet()
        val choiceCards = mutableListOf<VacuumChoiceCard>()
        cards.filter { it.id !in usedCards }.forEach { c ->
            val w = FlowCards.tokens(c.cardId)
            if (w.any { it in CARD_NOISE }) return@forEach
            if ((w - SET) in capWords) return@forEach
            if (mapPicker != null && "map" in w) return@forEach
            val args = c.inputArgs
            when {
                args.isEmpty() -> buttons += VacuumButton(c.title ?: readable(c.cardId), VacuumCommand.Card(c))
                args.size == 1 && args[0].type in CHOICE_TYPES ->
                    choiceCards += VacuumChoiceCard(c.title ?: readable(c.cardId), c, args[0])
            }
        }

        val battery = caps.firstOrNull { it.id == "measure_battery" }?.numberValue
        val charging = charging(caps)
        val alarms = alarms(caps)
        val (state, statusText) = state(device, caps, alarms, charging)

        val readings = caps.filter {
            it.getable && !it.setable && it.type == "number" && it.numberValue != null &&
                it.id != "measure_battery" && it.id !in POSITION && parts.none { p -> p.capability == it.id }
        } + caps.filter { it.getable && !it.setable && it.type == "boolean" && !it.id.startsWith("alarm_") && it.id !in STATE_BOOLEANS && it.booleanValue != null }

        return VacuumControls(
            state = state,
            statusText = statusText,
            battery = battery,
            charging = charging || state == VacuumState.Charging,
            start = start,
            pause = pause,
            resume = resume,
            stop = stop,
            dock = dock,
            pickers = pickers,
            mapPicker = mapPicker,
            toggles = toggles,
            buttons = buttons,
            choiceCards = choiceCards,
            roomCleaning = roomCleaning,
            pickerRooms = pickerRooms,
            readings = readings,
            alarms = alarms,
            map = mapImage(device),
            parts = parts,
        )
    }

    /** The robot's map: an image named "map", else any camera image. */
    fun mapImage(device: Device): DeviceImage? =
        device.images.firstOrNull { img -> img.imageObj?.url != null && listOfNotNull(img.id, img.title).any { "map" in it.lowercase() } }
            ?: device.images.firstOrNull { it.type == "camera" && it.imageObj?.url != null }

    /** Just what the robot is doing, as [controls] works it out, without building its controls. */
    fun state(device: Device): VacuumState {
        val caps = device.capabilities.mapNotNull { device.capability(it) }
        return state(device, caps, alarms(caps), charging(caps)).first
    }

    private fun charging(caps: List<Capability>): Boolean =
        caps.firstOrNull { it.id == "battery_charging_state" || "charging" in words(it.id) }?.let { c ->
            c.booleanValue ?: c.stringValue?.lowercase()?.let { "charg" in it && "dis" !in it && "not" !in it }
        } == true

    private fun alarms(caps: List<Capability>) = caps.filter { it.id.startsWith("alarm_") && it.booleanValue == true }

    private fun state(device: Device, caps: List<Capability>, alarms: List<Capability>, charging: Boolean): Pair<VacuumState, String?> {
        if (!device.available) return VacuumState.Offline to null
        val error = alarms.any { words(it.id).any { w -> w in ERROR_WORDS } }
        caps.firstOrNull { it.id == "vacuumcleaner_state" }?.stringValue?.let { value ->
            val s = when (value) {
                "cleaning", "spot_cleaning" -> VacuumState.Cleaning
                "docked" -> if (charging) VacuumState.Charging else VacuumState.Docked
                "charging" -> VacuumState.Charging
                else -> VacuumState.Idle
            }
            return (if (error) VacuumState.Error else s) to null
        }
        // An app's own status text, e.g. Roborock "operational_state" or another app's "status".
        val text = caps.firstOrNull { c ->
            c.getable && (c.type == "string" || c.type == "enum") && !c.setable && words(c.id).let { w -> w.any { it in STATUS_WORDS } && w.none { it in BATTERY_WORDS } }
        }?.let { c -> c.values?.firstOrNull { it.id == c.stringValue }?.title ?: c.stringValue }?.takeIf { it.isNotBlank() }
        val fromText = text?.lowercase()?.let { t ->
            when {
                "error" in t || "fault" in t || "stuck" in t -> VacuumState.Error
                "pause" in t -> VacuumState.Paused
                "return" in t || "back to" in t || "homing" in t || "going home" in t || "to dock" in t -> VacuumState.Returning
                "charg" in t && "full" !in t -> VacuumState.Charging
                "clean" in t || "sweep" in t || "mop" in t || "vacuum" in t -> VacuumState.Cleaning
                "dock" in t || "full" in t -> VacuumState.Docked
                else -> null
            }
        }
        fun bool(vararg names: String) = caps.firstOrNull { c -> c.type == "boolean" && words(c.id).let { w -> names.any { it in w } } }?.booleanValue
        val state = when {
            error -> VacuumState.Error
            fromText != null -> fromText
            bool("pause", "paused") == true -> VacuumState.Paused
            bool("cleaning") == true || caps.firstOrNull { c -> c.type == "boolean" && "clean" in words(c.id) && words(c.id).any { it in WHOLE_HOME } }?.booleanValue == true -> VacuumState.Cleaning
            bool("returning") == true -> VacuumState.Returning
            bool("dock", "docked") == true -> if (charging) VacuumState.Charging else VacuumState.Docked
            charging -> VacuumState.Charging
            device.capability("onoff")?.booleanValue == true -> VacuumState.Cleaning
            else -> VacuumState.Idle
        }
        return state to text
    }

    private fun roomCleaning(cards: List<FlowCardAction>, picker: Capability?): RoomCleaning? {
        val byCount = sortedMapOf<Int, FlowCardAction>()
        var text: RoomCleaning.Text? = null
        cards.forEach { c ->
            val args = c.inputArgs
            if (args.isEmpty() || args.any { it.type !in ROOM_CARD_TYPES }) return@forEach
            val roomArgs = args.filter { isRoomArg(it) }
            if (roomArgs.isEmpty() || args.any { it !in roomArgs && it.type !in NUMBER_TYPES }) return@forEach
            when {
                roomArgs.all { it.type == "autocomplete" } -> byCount.putIfAbsent(roomArgs.size, c)
                roomArgs.size == 1 && roomArgs[0].type == "text" && text == null -> text = RoomCleaning.Text(c, roomArgs[0])
            }
        }
        return when {
            text != null -> text
            byCount.isNotEmpty() -> RoomCleaning.Cards(byCount)
            picker != null -> RoomCleaning.Picker(picker.id)
            else -> null
        }
    }

    private fun isRoomArg(arg: FlowCardArg): Boolean =
        (FlowCards.tokens(arg.name) + FlowCards.tokens(arg.title ?: "")).any { w -> w.trimEnd { it.isDigit() } in ROOM_WORDS }

    /** Arguments for running [cleaning] on [rooms]: room entries or names, and 1 for counts like "repeat". */
    fun roomArgs(cleaning: RoomCleaning, rooms: List<VacuumRoom>): VacuumCommand? = when (cleaning) {
        is RoomCleaning.Picker -> rooms.singleOrNull()?.let { VacuumCommand.Set(cleaning.capability, JsonPrimitive(it.id)) }
        is RoomCleaning.Text -> {
            val args = defaults(cleaning.card) + (cleaning.arg.name to JsonPrimitive(rooms.joinToString(", ") { it.name }))
            VacuumCommand.Card(cleaning.card, JsonObject(args))
        }
        is RoomCleaning.Cards -> cleaning.byCount[rooms.size]?.let { card ->
            if (rooms.any { it.raw == null }) return@let null
            val roomArgs = card.inputArgs.filter { isRoomArg(it) }
            val args = defaults(card) + roomArgs.zip(rooms).associate { (arg, room) -> arg.name to room.raw!! }
            VacuumCommand.Card(card, JsonObject(args))
        }
    }

    /**
     * A room list to ask the Homey for: a room card's autocomplete argument. Needed when the
     * rooms are sent as card entries, or when no room picker capability names them.
     */
    fun roomListArg(cleaning: RoomCleaning?, cards: List<FlowCardAction>): Pair<FlowCardAction, FlowCardArg>? {
        val preferred = (cleaning as? RoomCleaning.Cards)?.byCount?.values.orEmpty()
        return (preferred + cards).firstNotNullOfOrNull { card ->
            card.inputArgs.firstOrNull { it.type == "autocomplete" && isRoomArg(it) }?.let { card to it }
        }
    }

    /**
     * A card that drives the robot to a room or spot without cleaning ("Go to room", "Move to
     * kitchen"), with its room argument when it takes one; null when the app has none.
     */
    fun goToCard(cards: List<FlowCardAction>): Pair<FlowCardAction, FlowCardArg?>? = cards.firstNotNullOfOrNull { c ->
        val w = FlowCards.tokens(c.cardId) + FlowCards.tokens(c.title.orEmpty())
        val goes = "goto" in w || "navigate" in w || ("go" in w && "to" in w) || ("move" in w && "to" in w)
        if (!goes || "clean" in w || w.any { it in DOCK_WORDS }) return@firstNotNullOfOrNull null
        val roomArg = c.inputArgs.firstOrNull { isRoomArg(it) && it.type in ROOM_ARG_TYPES }
        // Coordinates or anything else the user can't say aren't for a voice command.
        if (c.inputArgs.any { it != roomArg }) null else c to roomArg
    }

    fun roomsFrom(items: List<AutocompleteItem>): List<VacuumRoom> = items.map { item ->
        val id = (item.raw["id"] as? JsonPrimitive)?.contentOrNull ?: item.name
        VacuumRoom(id, item.name, item.raw)
    }

    private fun defaults(card: FlowCardAction): Map<String, JsonElement> =
        card.inputArgs.filter { it.type == "number" || it.type == "range" }.associate { it.name to JsonPrimitive((it.min ?: 1.0).coerceAtLeast(1.0).toInt()) }

    private fun parts(
        caps: List<Capability>,
        cards: List<FlowCardAction>,
        setBooleans: List<Capability>,
        used: MutableSet<String>,
        usedCards: MutableSet<String>,
    ): List<VacuumPart> {
        val result = linkedMapOf<String, VacuumPart>()
        // Parts the app reports itself: "Brush life remaining" in hours or percent.
        caps.filter { it.type == "number" && it.getable && !it.setable && it.id != "measure_battery" }.forEach { c ->
            val w = words(c.id) + words(c.title ?: "")
            val key = partKey(w) ?: return@forEach
            if (w.none { it in LIFE_WORDS } && c.units != "%") return@forEach
            val value = c.numberValue
            val percent = c.units == "%" || "percent" in w
            val life = lifetime(key)
            result[key] = VacuumPart(
                key = key,
                name = PART_NAMES[key] ?: readable(c.id),
                capability = c.id,
                leftFraction = value?.let { if (percent) (if ((c.max ?: 100.0) <= 1.0) it else it / 100.0) else it / life }?.coerceIn(0.0, 1.0),
                leftHours = value?.takeIf { !percent },
                lifetimeHours = life,
            )
        }
        // Reset buttons and a "reset consumable" card name the parts the robot counts.
        setBooleans.filter { "reset" in words(it.id) }.forEach { c ->
            val key = partKey(words(c.id) + words(c.title ?: "")) ?: return@forEach
            used += c.id
            val reset = VacuumCommand.Set(c.id, JsonPrimitive(true))
            result[key] = result[key]?.copy(reset = reset) ?: VacuumPart(key, PART_NAMES[key] ?: key, lifetimeHours = lifetime(key), reset = reset)
        }
        cards.forEach { card ->
            val w = FlowCards.tokens(card.cardId)
            if ("reset" !in w) return@forEach
            val arg = card.inputArgs.singleOrNull()
            if (arg != null && arg.type == "dropdown") {
                usedCards += card.id
                arg.values.orEmpty().forEach { v ->
                    val key = partKey(FlowCards.tokens(v.id) + FlowCards.tokens(v.label ?: "")) ?: return@forEach
                    val reset = VacuumCommand.Card(card, JsonObject(mapOf(arg.name to JsonPrimitive(v.id))))
                    result[key] = result[key]?.copy(reset = result[key]?.reset ?: reset)
                        ?: VacuumPart(key, v.label ?: PART_NAMES[key] ?: key, lifetimeHours = lifetime(key), reset = reset)
                }
            } else if (card.inputArgs.isEmpty()) {
                val key = partKey(w) ?: return@forEach
                usedCards += card.id
                val reset = VacuumCommand.Card(card)
                result[key] = result[key]?.copy(reset = result[key]?.reset ?: reset)
                    ?: VacuumPart(key, PART_NAMES[key] ?: key, lifetimeHours = lifetime(key), reset = reset)
            }
        }
        return result.values.sortedBy { PART_ORDER.indexOf(it.key).let { i -> if (i < 0) PART_ORDER.size else i } }
    }

    private fun partKey(w: Set<String>): String? = when {
        "side" in w && "brush" in w -> "side_brush"
        "brush" in w || "roller" in w -> "main_brush"
        "filter" in w || "mesh" in w || "hepa" in w -> "filter"
        "mop" in w || "pad" in w || "cloth" in w -> "mop"
        "sensor" in w || "sensors" in w -> "sensor"
        "bag" in w || "dustbag" in w -> "dust_bag"
        "strainer" in w -> "strainer"
        else -> null
    }

    /** Typical life in cleaning hours (Roborock's published defaults; others are similar). */
    fun lifetime(key: String): Double = when (key) {
        "main_brush" -> 300.0
        "side_brush" -> 200.0
        "filter" -> 150.0
        "sensor" -> 30.0
        "mop" -> 180.0
        "dust_bag" -> 120.0
        else -> 200.0
    }

    /** `ui.components` as capability id → component (`toggle`, `button`, `picker`, …). */
    private fun uiComponents(device: Device): Map<String, String> {
        val list = device.ui?.get("components") as? JsonArray ?: return emptyMap()
        return list.mapNotNull { it as? JsonObject }.flatMap { c ->
            val id = (c["id"] as? JsonPrimitive)?.contentOrNull ?: return@flatMap emptyList()
            (c["capabilities"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let { cap -> cap to id } }
        }.toMap()
    }

    // tokens() already splits at dots.
    private fun words(id: String): Set<String> = FlowCards.tokens(id)

    private fun title(c: Capability) = c.title?.takeIf { it.isNotBlank() } ?: readable(c.id)

    /** `empty_dustbin` → "Empty dustbin". */
    fun readable(id: String): String =
        FlowCards.tokens(id.substringAfter("button.")).joinToString(" ").replaceFirstChar { it.uppercase() }
            .ifEmpty { id }

    private val WHOLE_HOME = setOf("full", "all", "whole", "house", "home", "everything", "global", "auto")
    private val DOCK_WORDS = setOf("dock", "return", "charge", "base", "gohome", "homing")
    private val ROOM_WORDS = setOf("room", "rooms", "segment", "segments", "area", "areas")
    private val STATUS_WORDS = setOf("state", "status", "operational", "activity")
    private val BATTERY_WORDS = setOf("battery", "charging", "charge", "power")
    private val ERROR_WORDS = setOf("problem", "error", "stuck", "fault", "generic")
    private val LIFE_WORDS = setOf("left", "life", "remaining", "hours", "percent", "consumable", "health")
    private val ACTION_WORDS = setOf(
        "locate", "find", "empty", "wash", "spot", "refresh", "reset", "clean", "start", "stop", "go", "beep", "identify", "evacuate", "self",
    )
    private val CARD_NOISE = setOf("raw", "payload", "dnd")
    private val SET = setOf("set")
    private val SET_BUTTON = setOf("set", "button")
    private val CHOICE_TYPES = setOf("dropdown", "autocomplete", "checkbox")
    private val POSITION = setOf("position_x", "position_y")
    private val ROOM_CARD_TYPES = setOf("autocomplete", "text", "number", "range")
    private val NUMBER_TYPES = setOf("number", "range")
    private val ROOM_ARG_TYPES = setOf("autocomplete", "text", "dropdown")
    private val STATE_BOOLEANS = setOf("is_cleaning", "dock", "pause_clean", "clean_full", "onoff")
    private val PICKER_ORDER = listOf("suction", "fan", "power", "vacuum", "water", "scrub", "mop", "mode", "route", "brush")
    private val PART_ORDER = listOf("filter", "main_brush", "side_brush", "mop", "sensor", "dust_bag", "strainer")
    private val PART_NAMES = mapOf(
        "main_brush" to "Main brush", "side_brush" to "Side brush", "filter" to "Filter", "mop" to "Mop",
        "sensor" to "Sensors", "dust_bag" to "Dust bag", "strainer" to "Strainer",
    )
}
