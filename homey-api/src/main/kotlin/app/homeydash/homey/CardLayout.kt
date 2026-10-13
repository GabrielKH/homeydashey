package app.homeydash.homey

/** A titled run of cards in a room: a group the user made, or an automatic one like "Lights". */
data class CardGroup(val title: String, val custom: Boolean, val devices: List<Device>)

/**
 * How the user arranged a room's cards on this tablet: the order of cards in each
 * room, and the groups they put cards into. Cards nobody arranged keep Homey's order
 * and their automatic group.
 */
data class CardArrangement(
    /** Device ids in order, by zone id. */
    val order: Map<String, List<String>> = emptyMap(),
    /** A group title by device id. */
    val groups: Map<String, String> = emptyMap(),
)

object CardLayout {

    /** A section's cards grouped and ordered: the user's groups first, then the automatic ones. */
    fun groups(section: ZoneSection, arrangement: CardArrangement): List<CardGroup> {
        val order = arrangement.order[section.zone.id].orEmpty()
        val position = order.withIndex().associate { (i, id) -> id to i }
        val sorted = section.devices.withIndex()
            .sortedWith(compareBy({ position[it.value.id] ?: (order.size + it.index) }, { it.index }))
            .map { it.value }
        val custom = sorted.filter { it.id in arrangement.groups }.groupBy { arrangement.groups.getValue(it.id) }
        val automatic = sorted.filter { it.id !in arrangement.groups }.groupBy(DashboardLayout::group)
        return custom.map { (title, devices) -> CardGroup(title, true, devices) } +
            DeviceGroup.entries.mapNotNull { group -> automatic[group]?.let { CardGroup(group.title, false, it) } }
    }

    /** The section's new card order after moving [deviceId] [delta] places within its group. */
    fun move(section: ZoneSection, arrangement: CardArrangement, deviceId: String, delta: Int): List<String> {
        val groups = groups(section, arrangement)
        val reordered = groups.map { group ->
            val ids = group.devices.map { it.id }.toMutableList()
            val from = ids.indexOf(deviceId)
            val to = from + delta
            if (from >= 0 && to in ids.indices) {
                ids.removeAt(from)
                ids.add(to, deviceId)
            }
            ids
        }
        return reordered.flatten()
    }

    /**
     * The section's new card order with [deviceId] dropped at [targetId]'s place, or null
     * when the two cards are in different groups (a card is dragged only within its group).
     */
    fun moveTo(section: ZoneSection, arrangement: CardArrangement, deviceId: String, targetId: String): List<String>? {
        if (deviceId == targetId) return null
        val groups = groups(section, arrangement).map { group -> group.devices.map { it.id } }
        val group = groups.firstOrNull { deviceId in it } ?: return null
        if (targetId !in group) return null
        val ids = group.toMutableList()
        val to = ids.indexOf(targetId)
        ids.remove(deviceId)
        ids.add(to, deviceId)
        return groups.flatMap { if (it === group) ids else it }
    }

    /** Where a card sits in its group, to know whether it can move up or down. */
    fun position(section: ZoneSection, arrangement: CardArrangement, deviceId: String): Pair<Int, Int>? {
        val group = groups(section, arrangement).firstOrNull { g -> g.devices.any { it.id == deviceId } } ?: return null
        return group.devices.indexOfFirst { it.id == deviceId } to group.devices.size
    }

    /** Group titles the user already made in a section, for the card menu. */
    fun customTitles(section: ZoneSection, arrangement: CardArrangement): List<String> =
        section.devices.mapNotNull { arrangement.groups[it.id] }.distinct()
}
