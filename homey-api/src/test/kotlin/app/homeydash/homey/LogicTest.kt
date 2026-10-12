package app.homeydash.homey

import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LogicTest {
    private val zones: Map<String, Zone> = HomeyJson.decodeFromString(Fixtures.zones)
    private val devices: Map<String, Device> = HomeyJson.decodeFromString(Fixtures.devices)

    @Test fun `addresses get a scheme and a plain-IP fallback`() {
        assertEquals(listOf("http://192.168.1.20"), HomeyAddress.candidates(" 192.168.1.20/ "))
        assertEquals(
            listOf("https://192-168-1-20.homey.homeylocal.com", "http://192.168.1.20"),
            HomeyAddress.candidates("192-168-1-20.homey.homeylocal.com"),
        )
        assertEquals(listOf("http://homey.local:80"), HomeyAddress.candidates("http://homey.local:80"))
        assertEquals(emptyList<String>(), HomeyAddress.candidates("  "))
    }

    @Test fun `devices get the right card`() {
        assertEquals(CardKind.Light, DashboardLayout.cardKind(devices.getValue("d-lamp")))
        assertEquals(CardKind.Switch, DashboardLayout.cardKind(devices.getValue("d-plug")))
        assertEquals(CardKind.Sensor, DashboardLayout.cardKind(devices.getValue("d-temp")))
        assertEquals(CardKind.Other, DashboardLayout.cardKind(devices.getValue("d-lost")))
        val plugLamp = devices.getValue("d-plug").copy(virtualClass = "light")
        assertEquals(CardKind.Light, DashboardLayout.cardKind(plugLamp))
    }

    @Test fun `sections follow the zone tree and skip empty zones`() {
        val sections = DashboardLayout.sections(zones, devices)
        assertEquals(listOf("Kitchen", "Living room", "Other"), sections.map { it.zone.name })
        assertEquals(listOf("Coffee machine", "Kitchen lamp"), sections[0].devices.map { it.name })
        assertEquals(1, sections[0].depth)
    }

    @Test fun `socket events become typed events`() {
        val capability = HomeyRealtime.parseEvent(
            "homey:device:d-lamp", "capability",
            buildJsonObject { put("capabilityId", "onoff"); put("value", false) },
        )
        assertEquals(HomeyEvent.CapabilityChanged("d-lamp", "onoff", JsonPrimitive(false)), capability)

        val deleted = HomeyRealtime.parseEvent(HomeyRealtime.DEVICES_URI, "device.delete", buildJsonObject { put("id", "d-lamp") })
        assertEquals(HomeyEvent.DeviceDeleted("d-lamp"), deleted)

        assertNull(HomeyRealtime.parseEvent(HomeyRealtime.DEVICES_URI, "device.unknown", JsonPrimitive(1)))
    }

    @Test fun `capability updates replace only the changed device`() {
        val http = OkHttpClient()
        val store = HomeyStore(
            HomeyClient("http://127.0.0.1", "k", http),
            HomeyRealtime("http://127.0.0.1", "k", "id", http),
            TestScope(),
        )
        devices.values.forEach { store.apply(HomeyEvent.DeviceChanged(it)) }

        store.apply(HomeyEvent.CapabilityChanged("d-lamp", "onoff", JsonPrimitive(false)))

        val after = store.snapshot.value.devices
        assertEquals(false, after.getValue("d-lamp").capability("onoff")?.booleanValue)
        assertSame(devices.getValue("d-plug"), after.getValue("d-plug"))
    }
}
