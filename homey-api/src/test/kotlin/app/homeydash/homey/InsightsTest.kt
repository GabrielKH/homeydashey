package app.homeydash.homey

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsTest {
    @Test fun `entry values parse oldest first, skipping empty points`() {
        val json = HomeyJson.parseToJsonElement(
            """{"values": [{"t": "2026-10-22T10:00:00.000Z", "v": 21.5}, {"t": "2026-10-22T10:05:00.000Z", "v": null}, {"t": "2026-10-22T10:10:00.000Z", "v": 22}], "step": 300000}""",
        )
        assertEquals(listOf(21.5, 22.0), Insights.parseValues(json))
        assertEquals(listOf(1.0, 0.0), Insights.parseValues(HomeyJson.parseToJsonElement("""{"values": [{"v": true}, {"v": false}]}""")))
        assertEquals(emptyList<Double>(), Insights.parseValues(HomeyJson.parseToJsonElement("""{"error": "Missing Scopes"}""")))
    }

    @Test fun `only changing numbers get a line`() {
        assertTrue(Insights.hasLine(Capability("measure_temperature", type = "number", value = JsonPrimitive(21.0))))
        assertFalse(Insights.hasLine(Capability("measure_battery", type = "number", value = JsonPrimitive(80))))
        assertFalse(Insights.hasLine(Capability("alarm_smoke", type = "boolean", value = JsonPrimitive(false))))
    }
}
