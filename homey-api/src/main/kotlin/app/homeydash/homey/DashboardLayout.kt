package app.homeydash.homey

/** Which card a device gets on the dashboard. */
enum class CardKind { Light, Switch, Thermostat, Blinds, Lock, Media, Vacuum, Button, Sensor, Other }

/** How a room's tiles are grouped, in display order. */
enum class DeviceGroup(val title: String) {
    Lights("Lights"),
    Media("Media"),
    Climate("Climate"),
    Openings("Blinds and locks"),
    Sensors("Sensors"),
    Other("Other"),
}

data class ZoneSection(
    val zone: Zone,
    /** 0 for the home itself, 1 for a floor or room directly under it, and so on. */
    val depth: Int,
    val devices: List<Device>,
)

/** What a room card on the overview shows. */
data class RoomSummary(
    val zone: Zone,
    val depth: Int,
    val devices: List<Device>,
    /** Lights and switched lamps in the room that can be turned on and off. */
    val lights: List<Device>,
    val lightsOn: Int,
    val temperature: Double?,
    val humidity: Double?,
    /** A speaker or TV in the room that is playing, if any. */
    val playing: Device?,
)

object DashboardLayout {
    private val MEDIA_CLASSES = setOf("speaker", "tv", "amplifier", "mediaplayer", "settopbox", "remote")
    private val MEDIA_CAPS = listOf("speaker_", "volume_", "channel_")

    fun cardKind(device: Device): CardKind {
        val caps = device.capabilities.toSet()
        val cls = device.effectiveClass
        return when {
            Vacuums.isVacuum(device) -> CardKind.Vacuum
            cls == "thermostat" || "target_temperature" in caps -> CardKind.Thermostat
            cls == "light" -> CardKind.Light
            // A dimmer Homey didn't call a light; not a TV or console that happens to have a brightness.
            cls != "socket" && cls !in MEDIA_CLASSES && "dim" in caps && caps.none { c -> MEDIA_CAPS.any { c.startsWith(it) } } -> CardKind.Light
            cls == "speaker" || cls == "tv" || "speaker_playing" in caps -> CardKind.Media
            cls == "windowcoverings" || cls == "blinds" || cls == "curtain" || "windowcoverings_set" in caps -> CardKind.Blinds
            "locked" in caps -> CardKind.Lock
            "onoff" in caps -> CardKind.Switch
            cls == "button" || "button" in caps -> CardKind.Button
            caps.any { it.startsWith("measure_") || it.startsWith("alarm_") || it.startsWith("meter_") } -> CardKind.Sensor
            else -> CardKind.Other
        }
    }

    fun group(device: Device): DeviceGroup = when (cardKind(device)) {
        CardKind.Light, CardKind.Switch -> DeviceGroup.Lights
        CardKind.Media -> DeviceGroup.Media
        CardKind.Thermostat -> DeviceGroup.Climate
        CardKind.Blinds, CardKind.Lock -> DeviceGroup.Openings
        CardKind.Sensor -> DeviceGroup.Sensors
        CardKind.Vacuum, CardKind.Button, CardKind.Other -> DeviceGroup.Other
    }

    /**
     * Devices the dashboard shows: everything except those hidden in the Homey app
     * and the extra ids in [hidden] (hidden per user in Homey, or on the tablet).
     */
    fun visible(devices: Map<String, Device>, hidden: Set<String> = emptySet()): Map<String, Device> =
        devices.filterValues { !it.hidden && it.id !in hidden }

    /** A light, or a socket the user marked as a light, that can be switched. */
    fun isSwitchableLight(device: Device): Boolean =
        device.capability("onoff")?.setable == true &&
            (device.effectiveClass == "light" || cardKind(device) == CardKind.Light)

    /**
     * Zones in tree order (parents before children, siblings in [order] then by name),
     * each with its [visible] devices sorted by name. Zones without visible devices are
     * left out, and so are [hiddenZones] with everything under them.
     */
    fun sections(
        zones: Map<String, Zone>,
        devices: Map<String, Device>,
        hidden: Set<String> = emptySet(),
        hiddenZones: Set<String> = emptySet(),
        order: List<String> = emptyList(),
    ): List<ZoneSection> {
        val shown = visible(devices, hidden)
        val byZone = shown.values.groupBy { it.zone }
        val result = mutableListOf<ZoneSection>()

        walk(zones, order, hiddenZones) { zone, depth ->
            byZone[zone.id]?.takeIf { it.isNotEmpty() }?.let { list ->
                result += ZoneSection(zone, depth, list.sortedBy { it.name.lowercase() })
            }
        }

        val orphans = shown.values.filter { it.zone == null || it.zone !in zones }
        if (orphans.isNotEmpty()) {
            result += ZoneSection(Zone(id = OTHER_ZONE_ID, name = "Other"), 0, orphans.sortedBy { it.name.lowercase() })
        }
        return result
    }

    fun rooms(sections: List<ZoneSection>): List<RoomSummary> = sections.map { section ->
        val lights = section.devices.filter(::isSwitchableLight)
        RoomSummary(
            zone = section.zone,
            depth = section.depth,
            devices = section.devices,
            lights = lights,
            lightsOn = lights.count { it.capability("onoff")?.booleanValue == true },
            temperature = roomTemperature(section.devices),
            humidity = section.devices.firstNotNullOfOrNull { it.capability("measure_humidity")?.numberValue },
            playing = section.devices.firstOrNull { it.capability("speaker_playing")?.booleanValue == true },
        )
    }

    /**
     * The room's air temperature. Readings in °F are converted; room sensors and thermostats
     * win over other devices, and readings no room has (a boiler, a fridge, an oven) are left out.
     */
    fun roomTemperature(devices: List<Device>): Double? {
        val readings = devices.mapNotNull { device ->
            val cap = device.capability("measure_temperature") ?: return@mapNotNull null
            val raw = cap.numberValue ?: return@mapNotNull null
            val celsius = if (cap.units?.contains("F") == true) (raw - 32) * 5 / 9 else raw
            device to celsius
        }.filter { (_, c) -> c in 5.0..35.0 }
        val preferred = readings.filter { (d, _) -> d.effectiveClass in setOf("sensor", "thermostat", "heater") }.ifEmpty { readings }
        return preferred.map { it.second }.sorted().let { if (it.isEmpty()) null else it[(it.size - 1) / 2] }
    }

    /** A zone and the zones under it, for showing a floor together with its rooms. */
    fun subtree(zones: Map<String, Zone>, rootId: String): Set<String> {
        val children = zones.values.groupBy { it.parent }
        val result = mutableSetOf<String>()
        fun visit(id: String) {
            if (!result.add(id)) return
            children[id].orEmpty().forEach { visit(it.id) }
        }
        visit(rootId)
        return result
    }

    /** Ancestors of a zone, nearest first, e.g. Kitchen → Ground floor → Home. */
    fun parents(zones: Map<String, Zone>, zoneId: String): List<Zone> {
        val result = mutableListOf<Zone>()
        var parent = zones[zoneId]?.parent
        while (parent != null && result.none { it.id == parent }) {
            val zone = zones[parent] ?: break
            result += zone
            parent = zone.parent
        }
        return result
    }

    /** Media players, the playing ones first. */
    fun mediaPlayers(devices: Map<String, Device>, hidden: Set<String> = emptySet()): List<Device> =
        visible(devices, hidden).values
            .filter { cardKind(it) == CardKind.Media }
            .sortedWith(compareByDescending<Device> { it.capability("speaker_playing")?.booleanValue == true }.thenBy { it.name.lowercase() })

    /** Moods sorted by name; [zoneId] limits them to moods saved for that zone. */
    fun moods(moods: Map<String, Mood>, zoneId: String? = null): List<Mood> =
        moods.values
            .filter { zoneId == null || it.zone == zoneId }
            .sortedBy { it.name.lowercase() }

    /** Every zone in display order with its depth, hidden ones included; for the room settings. */
    fun zoneTree(zones: Map<String, Zone>, order: List<String> = emptyList()): List<Pair<Zone, Int>> {
        val result = mutableListOf<Pair<Zone, Int>>()
        walk(zones, order) { zone, depth -> result += zone to depth }
        return result
    }

    /** Siblings of [zoneId] in display order, the zone itself included. */
    fun siblings(zones: Map<String, Zone>, order: List<String>, zoneId: String): List<Zone> {
        val parent = zones[zoneId]?.parent?.takeIf { it in zones }
        return zones.values.filter { (it.parent?.takeIf { p -> p in zones }) == parent }.sortedWith(siblingOrder(order))
    }

    /**
     * A new room order with [zoneId] moved one place up ([delta] -1) or down (+1)
     * among its siblings.
     */
    fun move(zones: Map<String, Zone>, order: List<String>, zoneId: String, delta: Int): List<String> {
        val ranked = zoneTree(zones, order).map { it.first.id }.toMutableList()
        val siblings = siblings(zones, order, zoneId)
        val index = siblings.indexOfFirst { it.id == zoneId }
        val other = siblings.getOrNull(index + delta) ?: return ranked
        val a = ranked.indexOf(zoneId)
        val b = ranked.indexOf(other.id)
        ranked[a] = other.id
        ranked[b] = zoneId
        return ranked
    }

    /**
     * A new room order with [zoneId] dropped at [targetId]'s place among its siblings,
     * or null when the two rooms aren't siblings.
     */
    fun moveTo(zones: Map<String, Zone>, order: List<String>, zoneId: String, targetId: String): List<String>? {
        if (zoneId == targetId) return null
        val siblings = siblings(zones, order, zoneId).map { it.id }.toMutableList()
        if (targetId !in siblings) return null
        val to = siblings.indexOf(targetId)
        siblings.remove(zoneId)
        siblings.add(to, zoneId)
        val ranked = zoneTree(zones, order).map { it.first.id }.toMutableList()
        // The siblings keep the places they had in the full order, now in their new sequence.
        val slots = ranked.withIndex().filter { it.value in siblings }.map { it.index }
        slots.zip(siblings).forEach { (slot, id) -> ranked[slot] = id }
        return ranked
    }

    /** Whether a player is playing right now. */
    fun isPlaying(device: Device): Boolean = device.capability("speaker_playing")?.booleanValue == true

    /**
     * The player the media page shows big: the one picked by hand, else the one that
     * started playing last, else any that is playing, else the one that played last.
     */
    fun featuredPlayer(players: List<Device>, chosen: String?, last: String?): Device? {
        players.firstOrNull { it.id == chosen }?.let { return it }
        val playing = players.filter(::isPlaying)
        return playing.firstOrNull { it.id == last }
            ?: playing.firstOrNull()
            ?: players.firstOrNull { it.id == last }
            ?: players.firstOrNull()
    }

    private fun siblingOrder(order: List<String>): Comparator<Zone> {
        val rank = order.withIndex().associate { it.value to it.index }
        return compareBy<Zone>({ rank[it.id] ?: Int.MAX_VALUE }, { it.name.lowercase() })
    }

    private fun walk(zones: Map<String, Zone>, order: List<String> = emptyList(), skip: Set<String> = emptySet(), visit: (Zone, Int) -> Unit) {
        val children = zones.values.groupBy { it.parent?.takeIf { p -> p in zones } }
        val sorted = siblingOrder(order)
        fun go(zone: Zone, depth: Int) {
            if (zone.id in skip) return
            visit(zone, depth)
            children[zone.id].orEmpty().sortedWith(sorted).forEach { go(it, depth + 1) }
        }
        children[null].orEmpty().sortedWith(sorted).forEach { go(it, 0) }
    }

    const val OTHER_ZONE_ID = ""
}
