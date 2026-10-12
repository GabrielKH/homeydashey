package app.homeydash.homey

/** Which card a device gets on the dashboard. */
enum class CardKind { Light, Switch, Thermostat, Blinds, Lock, Media, Button, Sensor, Other }

data class ZoneSection(
    val zone: Zone,
    /** 0 for the home itself, 1 for a floor or room directly under it, and so on. */
    val depth: Int,
    val devices: List<Device>,
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

    /**
     * Zones in tree order (parents before children, siblings by name), each with
     * its devices sorted by name. Zones without devices are left out.
     */
    fun sections(zones: Map<String, Zone>, devices: Map<String, Device>): List<ZoneSection> {
        val byZone = devices.values.groupBy { it.zone }
        val children = zones.values.groupBy { it.parent?.takeIf { p -> p in zones } }
        val result = mutableListOf<ZoneSection>()

        fun visit(zone: Zone, depth: Int) {
            byZone[zone.id]?.takeIf { it.isNotEmpty() }?.let { list ->
                result += ZoneSection(zone, depth, list.sortedBy { it.name.lowercase() })
            }
            children[zone.id].orEmpty().sortedBy { it.name.lowercase() }.forEach { visit(it, depth + 1) }
        }
        children[null].orEmpty().sortedBy { it.name.lowercase() }.forEach { visit(it, 0) }

        val orphans = devices.values.filter { it.zone == null || it.zone !in zones }
        if (orphans.isNotEmpty()) {
            result += ZoneSection(Zone(id = "", name = "Other"), 0, orphans.sortedBy { it.name.lowercase() })
        }
        return result
    }
}
