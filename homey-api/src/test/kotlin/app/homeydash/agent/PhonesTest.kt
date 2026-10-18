package app.homeydash.agent

import app.homeydash.homey.Device
import app.homeydash.homey.FlowCardAction
import app.homeydash.homey.FlowCards
import app.homeydash.homey.HomeyFlow
import app.homeydash.homey.HomeyJson
import app.homeydash.homey.Mood
import app.homeydash.homey.Zone
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PhonesTest {
    private val zones: Map<String, Zone> = HomeyJson.decodeFromString("""{"z-home":{"id":"z-home","name":"Home"}}""")

    private fun phone(id: String, name: String, home: Boolean) = """
        "$id": {"id": "$id", "name": "$name", "zone": "z-home", "class": "other", "driverUri": "homey:app:com.apple.findmy:device",
          "capabilities": ["measure_battery", "alarm_home", "measure_latitude", "measure_longitude"],
          "capabilitiesObj": {
            "measure_battery": {"id": "measure_battery", "type": "number", "value": 54, "units": "%"},
            "alarm_home": {"id": "alarm_home", "type": "boolean", "value": $home},
            "measure_latitude": {"id": "measure_latitude", "type": "number", "value": 59.3293},
            "measure_longitude": {"id": "measure_longitude", "type": "number", "value": 18.0686}
          }}
    """

    private val devices: Map<String, Device> = HomeyJson.decodeFromString(
        """
        {${phone("p1", "Gabriels iPhone", true)}, ${phone("p2", "Annas iPhone", false)},
         "l1": {"id": "l1", "name": "Taklampa", "zone": "z-home", "class": "light", "capabilities": ["onoff"],
           "capabilitiesObj": {"onoff": {"id": "onoff", "type": "boolean", "value": false, "setable": true}}}}
        """,
    )

    private val cards: Map<String, List<FlowCardAction>> = FlowCards.byDevice(
        FlowCards.parseActions(
            HomeyJson.parseToJsonElement(
                """
                {"homey:device:p1:play_sound": {"id": "homey:device:p1:play_sound", "title": "Play sound on device"},
                 "homey:device:p1:send_message": {"id": "homey:device:p1:send_message", "title": "Send message", "args": [{"name": "subject", "type": "text"}]},
                 "homey:device:p2:play_sound": {"id": "homey:device:p2:play_sound", "title": "Play sound on device"}}
                """,
            ),
        ),
    )

    private inner class Home : HomeControl {
        val rang = mutableListOf<String>()
        override suspend fun setCapability(deviceId: String, capability: String, value: JsonElement) {}
        override suspend fun setMood(mood: Mood) {}
        override suspend fun flows() = emptyList<HomeyFlow>()
        override suspend fun triggerFlow(flow: HomeyFlow) {}
        override suspend fun people() = emptyList<HomeyPerson>()
        override suspend fun variables() = emptyList<LogicVariable>()
        override suspend fun playMusic(deviceId: String, query: String): String? = null
        override fun deviceActions(deviceId: String) = cards[deviceId].orEmpty()
        override suspend fun runAction(card: FlowCardAction, args: JsonObject) { rang += card.id }
    }

    private class Dash : DashboardControl {
        override fun openPage(page: String, roomId: String?) = true
        override fun openDevice(deviceId: String) = true
        override fun goodnightPreview() = ""
        override fun goodnight() {}
        override suspend fun weather(): String? = null
        override suspend fun calendar(from: LocalDate, days: Int, withLocations: Boolean) = emptyList<AgentEvent>()
        override fun startTimer(seconds: Int, label: String?) {}
        override fun cancelTimer(label: String?) = false
        override fun timers() = emptyList<String>()
        override fun setTheme(mode: String) = true
    }

    private val people = mapOf("u-gabriel" to "Gabriel", "u-anna" to "Anna")
    private val gabriel = Asker("u-gabriel", "Gabriel")

    private fun settings(access: Access = Access.On, asker: Asker? = gabriel, sharing: PhoneSharing = PhoneSharing.Own) =
        AgentSettings(secrets = mapOf(Secret.Phones to access), asker = asker, people = people, phoneSharing = sharing)

    private fun view(s: AgentSettings, local: Boolean = true) = HomeView.build(zones, devices, emptyMap(), emptySet(), s, local)

    private suspend fun find(tools: AgentTools, phone: String = "", action: String = "") =
        tools.run(ToolCall("c1", "find_phone", JsonObject(mapOf("phone" to JsonPrimitive(phone), "action" to JsonPrimitive(action)))))

    @Test
    fun ringsTheAskersOwnPhoneAndSaysItIsHome() = runTest {
        val home = Home()
        val r = find(AgentTools(view(settings()), home, Dash()), "my phone")
        assertEquals(listOf("homey:device:p1:play_sound"), home.rang)
        assertTrue(r.text, r.text.contains("Ringing Gabriels iPhone") && r.text.contains("home") && r.text.contains("54 %"))
    }

    @Test
    fun someoneElsesPhoneIsRefusedUnlessSharingAllows() = runTest {
        val home = Home()
        val r = find(AgentTools(view(settings()), home, Dash()), "Anna")
        assertTrue(home.rang.isEmpty())
        assertTrue(r.text, r.text.contains("only Anna can find it"))

        val ringAny = find(AgentTools(view(settings(sharing = PhoneSharing.RingAny)), home, Dash()), "Annas telefon")
        assertEquals(listOf("homey:device:p2:play_sound"), home.rang)
        // Rung, but where it is stays Anna's to know.
        assertFalse(ringAny.text, ringAny.text.contains("away") || ringAny.text.contains("battery"))
    }

    @Test
    fun anUnknownVoiceCanRingNothing() = runTest {
        val home = Home()
        val r = find(AgentTools(view(settings(asker = null)), home, Dash()))
        assertTrue(home.rang.isEmpty())
        assertTrue(r.text, r.text.contains("don't know who is asking"))
    }

    @Test
    fun phonesStayOutOfTheDeviceListAndNeverShowCoordinates() {
        val v = view(settings())
        assertEquals(listOf("Taklampa"), v.devices.map { it.name })
        assertEquals(2, v.phones.size)
        assertTrue(v.phones.none { p -> p.capabilities.any { "lat" in it.id || "lon" in it.id } })
        assertFalse(v.catalog().contains("iPhone"))
    }

    @Test
    fun localOnlyKeepsPhonesFromTheInternetAi() = runTest {
        val s = settings(access = Access.LocalOnly)
        assertTrue(view(s, local = false).phones.isEmpty())
        assertFalse(AgentTools(view(s, local = false), Home(), Dash()).specs().any { it.name == "find_phone" })
        val r = find(AgentTools(view(s, local = true), Home(), Dash()))
        assertTrue(r.localOnly)
    }

    @Test
    fun offMeansNoTool() {
        assertFalse(AgentTools(view(settings(access = Access.Off)), Home(), Dash()).specs().any { it.name == "find_phone" })
        assertFalse(AgentTools(view(settings().forRole(Role.Guest)), Home(), Dash()).specs().any { it.name == "find_phone" })
    }

    @Test
    fun explicitOwnerBeatsTheName() {
        val s = settings().copy(phoneOwners = mapOf("p2" to "u-gabriel"))
        val anna = view(s).phones.first { it.id == "p2" }
        assertEquals(Phones.Right.All, Phones.rightTo(anna, s))
        assertTrue(Phones.named("Gabriel’s iPad", "Gabriel Khalaf"))
        assertFalse(Phones.named("Gabrielle iPhone", "Gabriel"))
    }
}
