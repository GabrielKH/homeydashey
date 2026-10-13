package app.homeydash.homey

/** A card on the overview, as the lane layout sees it. */
data class LaneCard(
    val key: String,
    val kind: Kind,
    /** Takes the lane's full width; otherwise two cards share a row. */
    val wide: Boolean,
    /** Roughly how tall it is, in dp, to balance the lanes. */
    val height: Int,
) {
    enum class Kind { Info, Media, Device, Room }
}

/**
 * The overview as a few invisible lanes (columns) that cards stack in. Narrow cards
 * pair up two to a row inside a lane; wide ones take a row of their own. The saved
 * layout is a list of keys with [BREAK] between lanes.
 */
object OverviewLanes {
    const val BREAK = "|"
    const val GAP = 14

    /** The saved lanes, or null when nothing in the lane format is saved yet. */
    fun parse(saved: List<String>): List<List<String>>? {
        if (BREAK !in saved) return null
        val lanes = mutableListOf(mutableListOf<String>())
        saved.forEach { if (it == BREAK) lanes += mutableListOf<String>() else lanes.last() += it }
        return lanes
    }

    fun flatten(lanes: List<List<String>>): List<String> =
        lanes.flatMapIndexed { i, lane -> if (i == 0) lane else listOf(BREAK) + lane }

    /** A lane's cards in rows: a wide card alone, narrow cards in pairs. */
    fun rows(keys: List<String>, wide: (String) -> Boolean): List<List<String>> {
        val rows = mutableListOf<MutableList<String>>()
        var open: MutableList<String>? = null
        keys.forEach { key ->
            if (wide(key)) {
                rows += mutableListOf(key)
                open = null
            } else {
                val row = open
                if (row != null) {
                    row += key
                    open = null
                } else {
                    val fresh = mutableListOf(key)
                    rows += fresh
                    open = fresh
                }
            }
        }
        return rows
    }

    fun height(keys: List<String>, cards: Map<String, LaneCard>): Int =
        rows(keys) { cards[it]?.wide == true }.sumOf { row -> row.maxOf { cards[it]?.height ?: 0 } + GAP }

    /**
     * The saved layout fitted to what is on the overview now and to [count] lanes:
     * extra lanes fold into the last one, cards that are gone drop out, and new cards
     * go to the shortest lane. With nothing saved it is [auto].
     */
    fun place(saved: List<List<String>>?, cards: List<LaneCard>, count: Int): List<List<String>> {
        if (saved == null) return auto(cards, count)
        val byKey = cards.associateBy { it.key }
        val lanes = List(count) { mutableListOf<String>() }
        val placed = mutableSetOf<String>()
        saved.forEachIndexed { i, lane ->
            lane.forEach { key -> if (key in byKey && placed.add(key)) lanes[minOf(i, count - 1)] += key }
        }
        cards.filter { it.key !in placed }.forEach { card -> lanes.minBy { height(it, byKey) } += card.key }
        return lanes
    }

    /**
     * A tidy layout: the weather and calendar together in the last lane, the player and
     * pinned devices leading the first, and the rooms, in their order, two to a row,
     * each pair going to the lane that is shortest so far so the lanes end evenly.
     */
    fun auto(cards: List<LaneCard>, count: Int): List<List<String>> {
        val byKey = cards.associateBy { it.key }
        val lanes = List(count) { mutableListOf<String>() }
        lanes[count - 1] += cards.filter { it.kind == LaneCard.Kind.Info }.map { it.key }
        lanes[0] += cards.filter { it.kind == LaneCard.Kind.Media }.map { it.key }
        lanes[0] += cards.filter { it.kind == LaneCard.Kind.Device }.map { it.key }
        cards.filter { it.kind == LaneCard.Kind.Room }.chunked(2).forEach { pair ->
            // Ties go to the leftmost lane, so rooms read left to right.
            val lane = lanes.withIndex().minWith(compareBy({ height(it.value, byKey) }, { it.index })).value
            lane += pair.map { it.key }
        }
        return lanes
    }

    /** [lanes] with [key] taken out and put in lane [lane] before [before] (or at its end). */
    fun move(lanes: List<List<String>>, key: String, lane: Int, before: String?): List<List<String>> {
        val result = lanes.map { it.filter { k -> k != key }.toMutableList() }
        val target = result.getOrNull(lane) ?: return lanes
        val at = before?.let { target.indexOf(it) }?.takeIf { it >= 0 } ?: target.size
        target.add(at, key)
        return result
    }
}
