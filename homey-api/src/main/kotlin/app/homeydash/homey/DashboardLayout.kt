package app.homeydash.homey

/** Which card a device gets on the dashboard. */
enum class CardKind { Light, Switch, Thermostat, Blinds, Lock, Media, Button, Sensor, Other }

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

    fun cardKind(device: Device): CardKind {
        val caps = device.capabilities.toSet()
        val cls = device.effectiveClass
        return when {
            cls == "thermostat" || "target_temperature" in caps -> CardKind.Thermostat
            cls == "light" || (cls != "socket" && "dim" in caps) -> CardKind.Light
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
        CardKind.Button, CardKind.Other -> DeviceGroup.Other
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
     * Zones in tree order (parents before children, siblings by name), each with
     * its [visible] devices sorted by name. Zones without visible devices are left out.
     */
    fun sections(zones: Map<String, Zone>, devices: Map<String, Device>, hidden: Set<String> = emptySet()): List<ZoneSection> {
        val shown = visible(devices, hidden)
        val byZone = shown.values.groupBy { it.zone }
        val result = mutableListOf<ZoneSection>()

        walk(zones) { zone, depth ->
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
            temperature = section.devices.firstNotNullOfOrNull { it.capability("measure_temperature")?.numberValue },
            humidity = section.devices.firstNotNullOfOrNull { it.capability("measure_humidity")?.numberValue },
            playing = section.devices.firstOrNull { it.capability("speaker_playing")?.booleanValue == true },
        )
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

    private fun walk(zones: Map<String, Zone>, visit: (Zone, Int) -> Unit) {
        val children = zones.values.groupBy { it.parent?.takeIf { p -> p in zones } }
        fun go(zone: Zone, depth: Int) {
            visit(zone, depth)
            children[zone.id].orEmpty().sortedBy { it.name.lowercase() }.forEach { go(it, depth + 1) }
        }
        children[null].orEmpty().sortedBy { it.name.lowercase() }.forEach { go(it, 0) }
    }

    const val OTHER_ZONE_ID = ""
}
