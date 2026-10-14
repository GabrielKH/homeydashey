package app.homeydash.agent

import app.homeydash.homey.Capability
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
) {
    private val byAlias = devices.associateBy { it.alias }

    /**
     * [text] with device and mood aliases ("light_3", or "light 3" as a model may write it)
     * swapped for their names, since answers are read and spoken to people.
     */
    fun named(text: String): String = ALIAS.replace(text) { m ->
        val alias = "${m.groupValues[1].lowercase()}_${m.groupValues[2]}"
        byAlias[alias]?.name ?: moods.firstOrNull { it.alias == alias }?.mood?.name ?: m.value
    }

    /**
     * A device by its alias, or by its name said loosely ("lampan i köket"), which small
     * models and speech recognition often give instead. Null when nothing clearly matches.
     */
    fun device(alias: String): AgentDevice? {
        val q = alias.trim()
        byAlias[q.lowercase()]?.let { return it }
        devices.firstOrNull { it.name.equals(q, ignoreCase = true) }?.let { return it }
        val norm = NameMatch.normalize(q)
        devices.filter { NameMatch.normalize(it.name) == norm }.singleOrNull()?.let { return it }
        return NameMatch.best(q, devices) { score(q, it) }
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
        val byName = NameMatch.score(query, d.name, d.room)
        val byKind = d.kind?.let { NameMatch.score(query, it, d.room) * KIND_WEIGHT } ?: 0.0
        val byClass = CLASS_WORDS[d.deviceClass].orEmpty().maxOfOrNull { NameMatch.score(query, it, d.room) * CLASS_WEIGHT } ?: 0.0
        return maxOf(byName, byKind, byClass)
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

    companion object {
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
            val shown = devices.values
                // The assistant sees devices hidden on the dashboard too, unless the owner chose otherwise.
                .filter { it.id !in settings.hiddenDevices && (settings.seeHiddenDevices || (!it.hidden && it.id !in hidden)) }
                // Sorted by id, so aliases stay the same while the devices do.
                .sortedBy { it.id }
                .mapNotNull { device ->
                    val secret = secretOf(device)
                    if (secret != null && !settings.allows(secret, local)) return@mapNotNull null
                    val prefix = aliasPrefix(device.effectiveClass)
                    val n = (counters[prefix] ?: 0) + 1
                    counters[prefix] = n
                    AgentDevice(
                        alias = "${prefix}_$n",
                        id = device.id,
                        name = device.name,
                        roomId = device.zone,
                        room = device.zone?.let { roomNames[it] },
                        deviceClass = device.effectiveClass,
                        capabilities = device.capabilities.mapNotNull { id -> device.capability(id)?.let(::visibleCapability) },
                        secret = secret,
                        hiddenOnDashboard = device.hidden || device.id in hidden,
                        kind = kindOf(device),
                    )
                }
                .sortedWith(compareBy({ it.room ?: "" }, { it.name.lowercase() }))
            val agentMoods = moods.values.sortedBy { it.id }.mapIndexed { i, mood -> AgentMood("mood_${i + 1}", mood, mood.zone?.let { roomNames[it] }) }
                .sortedBy { it.mood.name.lowercase() }
            val usedRooms = shown.mapNotNull { it.roomId }.toSet() + moods.values.mapNotNull { it.zone }
            return HomeView(shown, roomNames.filterKeys { it in usedRooms }, agentMoods, local, settings)
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

        private val ALIAS = Regex("""\b(light|socket|player|climate|sensor|blind|vacuum|lock|alarm|camera|fan|device|mood)[_ ](\d+)\b""", RegexOption.IGNORE_CASE)

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
