package app.homeydash.homey

import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun `devices hidden in Homey stay off the dashboard`() {
        val all = DashboardLayout.sections(zones, devices).flatMap { it.devices }.map { it.id }
        assertFalse("d-hidden" in all)
        assertTrue(devices.getValue("d-hidden").hidden)
    }

    @Test fun `room summaries count lights and read climate`() {
        val rooms = DashboardLayout.rooms(DashboardLayout.sections(zones, devices))
        val kitchen = rooms.first { it.zone.id == "z-kitchen" }
        assertEquals(listOf("d-lamp"), kitchen.lights.map { it.id })
        assertEquals(1, kitchen.lightsOn)
        assertEquals(21.5, rooms.first { it.zone.id == "z-living" }.temperature)
        assertEquals(listOf("Home"), DashboardLayout.parents(zones, "z-kitchen").map { it.name })
        assertEquals(setOf("z-home", "z-kitchen", "z-living", "z-attic"), DashboardLayout.subtree(zones, "z-home"))
    }

    @Test fun `moods parse and filter by zone`() {
        val moods: Map<String, Mood> = HomeyJson.decodeFromString(Fixtures.moods)
        assertEquals(listOf("Cooking", "Movie"), DashboardLayout.moods(moods).map { it.name })
        assertEquals(listOf("Cooking"), DashboardLayout.moods(moods, "z-kitchen").map { it.name })
        val changed = HomeyRealtime.parseEvent(
            HomeyRealtime.MOODS_URI, "mood.update",
            buildJsonObject { put("id", "m-cook"); put("name", "Cook"); put("zone", "z-kitchen") },
        )
        assertEquals(HomeyEvent.MoodChanged(Mood("m-cook", "Cook", "z-kitchen")), changed)
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
