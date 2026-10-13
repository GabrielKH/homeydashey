package app.homeydash.homey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverviewLanesTest {
    private val weather = LaneCard("weather", LaneCard.Kind.Info, wide = true, height = 300)
    private val calendar = LaneCard("calendar", LaneCard.Kind.Info, wide = true, height = 340)
    private val player = LaneCard("player", LaneCard.Kind.Media, wide = true, height = 310)
    private fun room(n: Int) = LaneCard("room:$n", LaneCard.Kind.Room, wide = false, height = 148)
    private val rooms = (1..8).map(::room)

    @Test fun `auto puts the day on the right, the player first and spreads the rooms`() {
        val lanes = OverviewLanes.auto(listOf(weather, calendar, player) + rooms, 3)
        assertEquals(listOf("weather", "calendar"), lanes[2].take(2))
        assertEquals("player", lanes[0].first())
        assertEquals(8, lanes.sumOf { lane -> lane.count { it.startsWith("room:") } })
        // The middle lane starts with the first rooms, in order.
        assertEquals(listOf("room:1", "room:2"), lanes[1].take(2))
        val byKey = (listOf(weather, calendar, player) + rooms).associateBy { it.key }
        // The room lanes end within a row of each other.
        val heights = lanes.take(2).map { OverviewLanes.height(it, byKey) }
        assert(heights.max() - heights.min() <= 162) { heights.toString() }
    }

    @Test fun `saved lanes are kept, folded into fewer lanes, and new cards fill the shortest`() {
        val saved = listOf(listOf("weather"), listOf("room:1", "gone"), listOf("calendar"))
        val lanes = OverviewLanes.place(saved, listOf(weather, calendar, room(1), room(2)), 2)
        assertEquals(listOf("weather", "room:2"), lanes[0])
        assertEquals(listOf("room:1", "calendar"), lanes[1])
    }

    @Test fun `saving round-trips and old saves are not lanes`() {
        val lanes = listOf(listOf("a", "b"), emptyList(), listOf("c"))
        assertEquals(lanes, OverviewLanes.parse(OverviewLanes.flatten(lanes)))
        assertNull(OverviewLanes.parse(listOf("weather", "room:1")))
    }

    @Test fun `narrow cards pair up and a card moves between lanes`() {
        assertEquals(listOf(listOf("a", "b"), listOf("W"), listOf("c")), OverviewLanes.rows(listOf("a", "b", "W", "c")) { it == "W" })
        val moved = OverviewLanes.move(listOf(listOf("a", "b"), listOf("c")), "a", 1, "c")
        assertEquals(listOf(listOf("b"), listOf("a", "c")), moved)
        assertEquals(listOf(listOf("b"), listOf("c", "a")), OverviewLanes.move(listOf(listOf("a", "b"), listOf("c")), "a", 1, null))
    }
}
