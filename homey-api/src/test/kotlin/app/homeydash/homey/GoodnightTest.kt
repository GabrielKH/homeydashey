package app.homeydash.homey

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class GoodnightTest {
    private fun device(id: String, vararg caps: Pair<String, Boolean>) = Device(
        id, id, capabilities = caps.map { it.first },
        capabilitiesObj = caps.associate { (c, v) -> c to Capability(c, "boolean", value = JsonPrimitive(v), setable = true) },
    )

    @Test fun `turns off what is on and pauses what plays, except kept devices`() {
        val devices = listOf(
            device("lamp", "onoff" to true),
            device("off", "onoff" to false),
            device("sonos", "speaker_playing" to true),
            device("fridge", "onoff" to true),
        )
        val steps = Goodnight.steps(devices, keep = setOf("fridge"))
        assertEquals(listOf("lamp" to "onoff", "sonos" to "speaker_playing"), steps.map { it.device.id to it.capability })
    }

    @Test fun `flows that cannot be started are left out`() {
        val json = HomeyJson.parseToJsonElement(
            """{"a": {"id": "a", "name": "Natt", "triggerable": true}, "b": {"id": "b", "name": "Auto", "triggerable": false}, "c": {"id": "c", "name": "Bio"}}""",
        )
        assertEquals(listOf("Bio", "Natt"), Goodnight.parseFlows(json, advanced = false).map { it.name })
    }
}
