package app.homeydash.homey

import org.junit.Assert.assertEquals
import org.junit.Test

class CardLayoutTest {
    private fun light(id: String) = Device(id, id, zone = "k", deviceClass = "light", capabilities = listOf("onoff"))
    private fun sensor(id: String) = Device(id, id, zone = "k", deviceClass = "sensor", capabilities = listOf("measure_temperature"))
    private val section = ZoneSection(Zone("k", "Kitchen"), 1, listOf(light("a"), light("b"), sensor("t"), light("c")))

    @Test fun `automatic groups keep homey order`() {
        val groups = CardLayout.groups(section, CardArrangement())
        assertEquals(listOf("Lights", "Sensors"), groups.map { it.title })
        assertEquals(listOf("a", "b", "c"), groups[0].devices.map { it.id })
    }

    @Test fun `custom groups come first and cards move within their group`() {
        val arrangement = CardArrangement(groups = mapOf("c" to "Ceiling", "t" to "Ceiling"))
        val groups = CardLayout.groups(section, arrangement)
        assertEquals(listOf("Ceiling", "Lights"), groups.map { it.title })
        assertEquals(listOf("t", "c"), groups[0].devices.map { it.id })

        val order = CardLayout.move(section, arrangement, "c", -1)
        assertEquals(listOf("c", "t", "a", "b"), order)
        val moved = CardLayout.groups(section, arrangement.copy(order = mapOf("k" to order)))
        assertEquals(listOf("c", "t"), moved[0].devices.map { it.id })
        assertEquals(0 to 2, CardLayout.position(section, arrangement.copy(order = mapOf("k" to order)), "c"))
    }
}
