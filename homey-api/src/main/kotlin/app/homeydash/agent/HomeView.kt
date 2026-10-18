package app.homeydash.agent

import app.homeydash.homey.Capability
import app.homeydash.homey.DashboardLayout
import app.homeydash.homey.Device
import app.homeydash.homey.Mood
import app.homeydash.homey.Zone
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** A capability as the assistant sees it. */
data class AgentCapability(
    val id: String,
    val title: String?,
    val type: String,
    val value: JsonElement,
    val units: String?,
    val min: Double?,
    val max: Double?,
    val setable: Boolean,
    val choices: List<String>?,
)

/**
 * A device as the assistant sees it: a short [alias] instead of the Homey id, and only
 * the capabilities it may read. [secret] says which switch let it in, if any.
 */
data class AgentDevice(
    val alias: String,
    val id: String,
    val name: String,
    val roomId: String?,
    val room: String?,
    val deviceClass: String,
    val capabilities: List<AgentCapability>,
    val secret: Secret?,
    /** Hidden on the dashboard or in the Homey app; room-wide changes leave it alone. */
    val hiddenOnDashboard: Boolean = false,
    /** The app it comes from, in words ("apple tv", "sonos"), so "the Apple TV" finds a TV named "Vardagsrum". */
    val kind: String? = null,
    /** Homey can't reach it right now (unplugged, out of range); changing it does nothing. */
    val unreachable: Boolean = false,
    /** Other names the owner gave it ("Frame", "spelkonsolen"). */
    val nicknames: List<String> = emptyList(),
    /** The card group the owner put it in ("Taklampor"). */
    val group: String? = null,
) {
    fun capability(id: String): AgentCapability? = capabilities.firstOrNull { it.id == id }
}

data class AgentMood(val alias: String, val mood: Mood, val room: String?)

/**
 * Everything the assistant may know about the home for one question, built fresh from
 * the Homey each time. A brain on the internet ([local] = false) gets a smaller view
 * when switches are set to "Local AI only".
 */
class HomeView(
    val devices: List<AgentDevice>,
    val rooms: Map<String, String>,
    val moods: List<AgentMood>,
    val local: Boolean,
    val settings: AgentSettings,
    /** Each room's parent room, so a room includes the rooms inside it. */
    private val parents: Map<String, String?> = emptyMap(),
    /** Phones from Homey's Find My app; only the find_phone tool uses them, never the device list. */
    val phones: List<AgentDevice> = emptyList(),
) {
    private val byAlias = devices.associateBy { it.alias }

    /** Whether [d] is in [roomId] or in a room inside it ("Soffhörnan" in "Vardagsrum"). */
    fun inRoom(d: AgentDevice, roomId: String): Boolean {
        var zone = d.roomId
        var steps = 0
        while (zone != null && steps++ < 10) {
            if (zone == roomId) return true
            zone = parents[zone]
        }
        return false
    }

    /**
     * [text] with device and mood aliases ("light_3", or "light 3" as a model may write it)
     * swapped for their names, since answers are read and spoken to people.
     */
    fun named(text: String): String = ALIAS.replace(text) { m ->
        val alias = "${m.groupValues[1].lowercase()}_${m.groupValues[2]}"
        byAlias[alias]?.name ?: moods.firstOrNull { it.alias == alias }?.mood?.name ?: m.value
    }

    /**
     * A device by its name as listed ("Nattlampa Gabriel", with its room when several share
     * the name: "Taklampa, Sovrum"), by its alias, or by its name said loosely ("lampan i
     * köket"), which small models and speech recognition often give instead. When an alias
     * comes with a name that is another device's ("light_7 Nattlampa Gabriel"), the name
     * wins: small models mix up the numbers far more than the names. Null when nothing
     * clearly matches, also when a name is shared and no room says which.
     */
    fun device(alias: String): AgentDevice? {
        val q = alias.trim().trim('"').trim()
        byAlias[q.lowercase()]?.let { return it }
        ALIAS.find(q)?.takeIf { it.range.first == 0 }?.let { m ->
            val rest = q.substring(m.range.last + 1).trim(' ', '"', ':', '-', '(', ')').trim()
            val said = byAlias["${m.groupValues[1].lowercase()}_${m.groupValues[2]}"]
            return if (rest.isEmpty()) said else named(rest, devices) ?: said
        }
        ROOMED.matchEntire(q)?.let { m ->
            val name = m.groupValues[1].trim()
            val roomName = (m.groups[2] ?: m.groups[3])!!.value.trim()
            val roomId = room(roomName)
            val inRoom = devices.filter { roomId != null && it.roomId == roomId }
            if (inRoom.isNotEmpty()) named(name, inRoom)?.let { return it }
        }
        return named(q, devices)
    }

    private fun named(q: String, among: List<AgentDevice>): AgentDevice? {
        val exact = among.filter { it.name.equals(q, ignoreCase = true) }
        if (exact.size == 1) return exact.single()
        if (exact.size > 1) return null
        among.filter { d -> d.nicknames.any { it.equals(q, ignoreCase = true) } }.singleOrNull()?.let { return it }
        val norm = NameMatch.normalize(q)
        val same = among.filter { NameMatch.normalize(it.name) == norm }
        if (same.size == 1) return same.single()
        if (same.size > 1) return null
        return NameMatch.best(q, among) { score(q, it) }
    }

    /** Devices whose names come closest to [query], for suggesting what was meant. */
    fun similarDevices(query: String, limit: Int = 5): List<AgentDevice> =
        NameMatch.rank(query, devices) { score(query, it) }.take(limit).map { it.first }

    /**
     * How well [query] names [d]: by its name, or else by its app ("the Apple TV") or its type
     * ("the TV", "högtalaren"). The name counts most, so "taklampan" still means the lamp
     * called Taklampa and not every lamp.
     */
    private fun score(query: String, d: AgentDevice): Double {
        val byName = (listOf(d.name) + d.nicknames).maxOf { NameMatch.score(query, it, d.room) }
        val byKind = d.kind?.let { NameMatch.score(query, it, d.room) * KIND_WEIGHT } ?: 0.0
        val byClass = CLASS_WORDS[d.deviceClass].orEmpty().maxOfOrNull { NameMatch.score(query, it, d.room) * CLASS_WEIGHT } ?: 0.0
        return maxOf(byName, byKind, byClass)
    }

    /** The owner's card group [name] best names, by its exact title or loosely ("taklamporna"). */
    fun group(name: String): String? {
        val titles = devices.mapNotNull { it.group }.distinct()
        titles.firstOrNull { it.equals(name.trim(), ignoreCase = true) }?.let { return it }
        return NameMatch.best(name, titles) { NameMatch.score(name, it) }?.takeIf { NameMatch.score(name, it) >= 0.95 }
    }

    fun aliasOf(deviceId: String): String? = devices.firstOrNull { it.id == deviceId }?.alias

    fun mood(alias: String): AgentMood? =
        moods.firstOrNull { it.alias == alias.trim().lowercase() }
            ?: moods.firstOrNull { it.mood.name.equals(alias.trim(), ignoreCase = true) }
            ?: NameMatch.best(alias, moods) { NameMatch.score(alias, it.mood.name, it.room) }

    /** A room id by its name, loosely: "köket" finds "Kök". */
    fun room(name: String): String? =
        rooms.entries.firstOrNull { it.value.equals(name.trim(), ignoreCase = true) }?.key
            ?: rooms.keys.firstOrNull { it == name.trim() }
            ?: NameMatch.best(name, rooms.entries.toList()) { NameMatch.score(name, it.value) }?.key

    fun allows(secret: Secret) = settings.allows(secret, local)

    /** One line per device, short enough for a small local model's prompt. */
    fun describe(): String = buildString {
        devices.groupBy { it.room ?: "No room" }.toSortedMap().forEach { (room, list) ->
            append("## ").append(room).append('\n')
            list.forEach { d ->
                append(d.alias).append(" \"").append(d.name).append("\" ").append(d.deviceClass)
                d.kind?.let { append(" (").append(it).append(')') }
                if (d.nicknames.isNotEmpty()) append(" also called ").append(d.nicknames.joinToString { "\"$it\"" })
                d.group?.let { append(" group \"").append(it).append('"') }
                if (d.unreachable) append(" UNREACHABLE")
                append(':')
                d.capabilities.forEach { c -> append(' ').append(describe(c)) }
                append('\n')
            }
        }
        if (moods.isNotEmpty()) {
            append("## Moods\n")
            moods.forEach { append(it.alias).append(" \"").append(it.mood.name).append('"').append(it.room?.let { r -> " ($r)" } ?: "").append('\n') }
        }
    }

    /**
     * The home without its live values: what each device is and what can be set. It only
     * changes when devices do, so it sits in the fixed start of the prompt that a local model
     * keeps worked through between questions; what is on right now is in [states].
     */
    fun catalog(): String = buildString {
        devices.groupBy { it.room ?: "No room" }.toSortedMap().forEach { (room, list) ->
            append("## ").append(room).append('\n')
            list.forEach { d ->
                append(d.alias).append(" \"").append(d.name).append("\" ").append(d.deviceClass)
                d.kind?.let { append(" (").append(it).append(')') }
                if (d.nicknames.isNotEmpty()) append(" also called ").append(d.nicknames.joinToString { "\"$it\"" })
                d.group?.let { append(" group \"").append(it).append('"') }
                append(':')
                d.capabilities.mapNotNull(::catalog).forEach { append(' ').append(it) }
                append('\n')
            }
        }
        if (moods.isNotEmpty()) {
            append("## Moods\n")
            moods.forEach { append(it.alias).append(" \"").append(it.mood.name).append('"').append(it.room?.let { r -> " ($r)" } ?: "").append('\n') }
        }
    }

    /**
     * What is on, playing or worth knowing right now, one short line per device; devices
     * that are off or idle are left out. Small, so each question only carries this.
     */
    fun states(): String = buildString {
        devices.forEach { d ->
            val parts = live(d)
            if (parts.isEmpty()) return@forEach
            append(d.alias).append(" \"").append(d.name).append("\":")
            parts.forEach { append(' ').append(it) }
            append('\n')
        }
    }

    /** [states] by device alias, to tell what changed between two notes. */
    fun stateLines(): Map<String, String> =
        states().lines().filter { it.isNotBlank() }.associateBy { it.substringBefore(' ') }

    private fun live(d: AgentDevice): List<String> {
        fun on(id: String) = (d.capability(id)?.value as? JsonPrimitive)?.content == "true"
        val idle = when {
            d.capability("onoff") != null -> !on("onoff")
            d.capability("speaker_playing") != null -> !on("speaker_playing")
            else -> false
        }
        val parts = mutableListOf<String>()
        if (d.unreachable) parts += "UNREACHABLE"
        d.capabilities.forEach { c ->
            val value = c.value
            when {
                value == JsonNull -> Unit
                c.id == "onoff" -> if (on("onoff")) parts += "on"
                c.id.startsWith("alarm_") -> if (on(c.id)) parts += c.id
                c.id in READINGS -> parts += state(c)
                c.id.startsWith("measure_") || c.id.startsWith("meter_") -> Unit
                idle -> Unit
                c.type == "boolean" -> if (on(c.id)) parts += c.id
                else -> parts += state(c)
            }
        }
        return parts
    }

    companion object {
        /** Readings worth having with every question; the rest are a get_device away. */
        private val READINGS = setOf("measure_temperature", "measure_humidity", "target_temperature", "locked", "homealarm_state", "windowcoverings_state", "windowcoverings_set", "vacuumcleaner_state")

        /** Readings worth listing in the catalog; get_device reads the rest (power, battery, other sensors). */
        private val CATALOG_READINGS = setOf("measure_temperature", "measure_humidity", "measure_co2", "alarm_motion", "alarm_contact", "alarm_water", "alarm_smoke")

        /**
         * A capability in the catalog, as short as it can be, since every token here is read
         * on each question: a bare id is settable true|false or 0..1, a range or choices are
         * shown only when they differ, and readings are marked (read). Null when it's left out.
         */
        internal fun catalog(c: AgentCapability): String? {
            if (!c.setable) return if (c.id in CATALOG_READINGS) "${c.id} (read)" else null
            return when {
                c.choices != null -> "${c.id} [${c.choices.joinToString("|")}]"
                c.type == "number" && c.min != null && c.max != null && !(c.min == 0.0 && c.max == 1.0) -> "${c.id} [${num(c.min)}..${num(c.max)}]"
                else -> c.id
            }
        }

        private fun state(c: AgentCapability): String = buildString {
            append(c.id).append('=').append(format(c.value))
            c.units?.takeIf { it.isNotBlank() }?.let { append(it) }
        }

        /** Capabilities that say nothing useful to an assistant. */
        private val NOISE = setOf("energy", "measure_signal_strength", "rssi", "button.identify", "button.reset_meter")

        fun build(
            zones: Map<String, Zone>,
            devices: Map<String, Device>,
            moods: Map<String, Mood>,
            hidden: Set<String>,
            settings: AgentSettings,
            local: Boolean,
        ): HomeView {
            val roomNames = zones.mapValues { it.value.name }
            val counters = mutableMapOf<String, Int>()
            val all = devices.values
                // The assistant sees devices hidden on the dashboard too, unless the owner chose otherwise.
                .filter { it.id !in settings.hiddenDevices && (settings.seeHiddenDevices || (!it.hidden && it.id !in hidden)) }
                // Sorted by id, so aliases stay the same while the devices do.
                .sortedBy { it.id }
                .mapNotNull { device ->
                    val secret = secretOf(device)
                    if (secret != null && !settings.allows(secret, local)) return@mapNotNull null
                    val phone = secret == Secret.Phones
                    val prefix = if (phone) "phone" else aliasPrefix(device.effectiveClass)
                    val n = (counters[prefix] ?: 0) + 1
                    counters[prefix] = n
                    AgentDevice(
                        alias = "${prefix}_$n",
                        id = device.id,
                        name = device.name,
                        roomId = device.zone,
                        room = device.zone?.let { roomNames[it] },
                        // A lamp on a smart plug is a light, as on the dashboard.
                        deviceClass = if (DashboardLayout.isSwitchableLight(device)) "light" else device.effectiveClass,
                        capabilities = device.capabilities
                            .filter { !phone || !Phones.isCoordinates(it) }
                            .mapNotNull { id -> device.capability(id)?.let(::visibleCapability) },
                        secret = secret,
                        hiddenOnDashboard = device.hidden || device.id in hidden,
                        kind = kindOf(device),
                        unreachable = !device.available,
                        nicknames = device.nicknames,
                        group = device.userGroup,
                    )
                }
                .sortedWith(compareBy({ it.room ?: "" }, { it.name.lowercase() }))
            // Phones stay out of the device list: only find_phone may use them, and only for their owners.
            val (phones, shown) = all.partition { it.secret == Secret.Phones }
            val agentMoods = moods.values.sortedBy { it.id }.mapIndexed { i, mood -> AgentMood("mood_${i + 1}", mood, mood.zone?.let { roomNames[it] }) }
                .sortedBy { it.mood.name.lowercase() }
            val parents = zones.mapValues { it.value.parent }
            val direct = shown.mapNotNull { it.roomId }.toSet() + moods.values.mapNotNull { it.zone }
            // A room with lamps only in the rooms inside it is still a room people name.
            val usedRooms = direct.flatMap { generateSequence(it) { z -> parents[z] }.take(10).toList() }.toSet()
            return HomeView(shown, roomNames.filterKeys { it in usedRooms }, agentMoods, local, settings, parents, phones)
        }

        private const val KIND_WEIGHT = 0.9
        private const val CLASS_WEIGHT = 0.6

        /** What people call each kind of device, in English and Swedish. */
        private val CLASS_WORDS = mapOf(
            "tv" to listOf("tv", "teve", "television", "tvn"),
            "speaker" to listOf("speaker", "högtalare", "högtalaren", "music", "musik"),
            "amplifier" to listOf("amplifier", "förstärkare"),
            "light" to listOf("lamp", "lampa", "light", "ljus"),
            "socket" to listOf("plug", "uttag", "kontakt"),
            "thermostat" to listOf("thermostat", "termostat"),
            "heater" to listOf("heater", "element", "värme"),
            "heatpump" to listOf("heat pump", "värmepump"),
            "airconditioning" to listOf("air conditioning", "ac", "luftkonditionering"),
            "vacuumcleaner" to listOf("vacuum", "robot", "dammsugare", "robotdammsugare"),
            "windowcoverings" to listOf("blinds", "curtains", "gardin", "persienn", "rullgardin"),
            "fan" to listOf("fan", "fläkt"),
            "camera" to listOf("camera", "kamera"),
            "doorbell" to listOf("doorbell", "ringklocka", "dörrklocka"),
            "lock" to listOf("lock", "lås"),
            "kettle" to listOf("kettle", "vattenkokare"),
            "coffeemachine" to listOf("coffee machine", "kaffebryggare", "kaffemaskin"),
        )

        private val APP_NOISE = setOf("com", "nl", "be", "org", "io", "net", "se", "de", "no", "dk", "fi", "eu", "uk", "co", "dev", "app", "apps", "homey", "athom", "github")

        /** The app a device comes from, in words: `homey:app:com.apple.tv` is "apple tv". */
        fun kindOf(device: Device): String? {
            val app = device.driverUri?.removePrefix("homey:app:")?.substringBefore(':') ?: return null
            if (app.startsWith("homey:")) return null
            return app.split('.', '-', '_').filter { it.length > 1 && it !in APP_NOISE }.joinToString(" ").ifBlank { null }
        }

        /** The switch that guards a device, or null for an ordinary one. */
        fun secretOf(device: Device): Secret? {
            val cls = device.effectiveClass
            val caps = device.capabilities
            return when {
                Phones.isPhone(device) -> Secret.Phones
                cls == "lock" || "locked" in caps -> Secret.Locks
                cls == "homealarm" || caps.any { it.startsWith("homealarm_state") } -> Secret.Alarm
                cls == "camera" || cls == "doorbell" -> Secret.Cameras
                else -> null
            }
        }

        private fun visibleCapability(c: Capability): AgentCapability? {
            if (c.id in NOISE || (!c.getable && !c.setable)) return null
            val value = (c.value as? JsonPrimitive)?.takeIf { it.isString && it.content.length > 120 }
                ?.let { JsonPrimitive(it.content.take(120) + "…") } ?: c.value
            return AgentCapability(c.id, c.title, c.type, value, c.units, c.min, c.max, c.setable, c.values?.map { it.id })
        }

        /** "Taklampa, Sovrum" or "Taklampa (Sovrum)": a name with the room that tells it apart. */
        private val ROOMED = Regex("""^(.+?)\s*(?:\((.+)\)|,\s*(.+))$""")
        private val ALIAS = Regex("""\b(light|socket|player|climate|sensor|blind|vacuum|lock|alarm|camera|fan|device|mood|phone)[_ ](\d+)\b""", RegexOption.IGNORE_CASE)

        private fun aliasPrefix(cls: String): String = when (cls) {
            "light" -> "light"
            "socket" -> "socket"
            "speaker", "tv", "amplifier" -> "player"
            "thermostat", "heater", "heatpump", "airconditioning" -> "climate"
            "sensor" -> "sensor"
            "windowcoverings", "blinds", "curtain", "sunshade" -> "blind"
            "vacuumcleaner" -> "vacuum"
            "lock" -> "lock"
            "homealarm" -> "alarm"
            "camera", "doorbell" -> "camera"
            "fan" -> "fan"
            else -> "device"
        }

        internal fun describe(c: AgentCapability): String = buildString {
            append(c.id).append('=').append(format(c.value))
            c.units?.takeIf { it.isNotBlank() && c.value != JsonNull }?.let { append(it) }
            if (c.setable) {
                append(" [set")
                when {
                    c.choices != null -> append(": ").append(c.choices.joinToString("|"))
                    c.type == "number" && c.min != null && c.max != null -> append(' ').append(num(c.min)).append("..").append(num(c.max))
                    c.type == "boolean" -> append(" true|false")
                }
                append(']')
            }
        }

        private fun format(value: JsonElement): String = when {
            value == JsonNull -> "?"
            value is JsonPrimitive && value.isString -> "\"${value.content}\""
            value is JsonPrimitive -> value.content.toDoubleOrNull()?.let(::num) ?: value.content
            else -> value.toString().take(80)
        }

        private fun num(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, d).trimEnd('0').trimEnd('.')
    }
}
