package app.homeydash.agent

import app.homeydash.homey.Device
import app.homeydash.homey.FlowCardAction
import app.homeydash.homey.FlowCardArg
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class VacuumToolTest {
    private val zones: Map<String, Zone> = HomeyJson.decodeFromString("""{"z-home":{"id":"z-home","name":"Home"}}""")
    private val devices: Map<String, Device> = HomeyJson.decodeFromString(
        """
        {"rr": {"id": "rr", "name": "Robbie", "zone": "z-home", "class": "vacuumcleaner",
          "capabilities": ["measure_battery", "clean_full", "pause_clean", "cancel_and_dock", "clean_room_pick"],
          "capabilitiesObj": {
            "measure_battery": {"id": "measure_battery", "type": "number", "value": 87, "units": "%"},
            "clean_full": {"id": "clean_full", "type": "boolean", "value": false, "setable": true},
            "pause_clean": {"id": "pause_clean", "type": "boolean", "value": false, "setable": true},
            "cancel_and_dock": {"id": "cancel_and_dock", "type": "boolean", "value": false, "setable": true},
            "clean_room_pick": {"id": "clean_room_pick", "type": "enum", "setable": true,
               "values": [{"id": "16", "title": "Kitchen"}, {"id": "17", "title": "Hall"}]}
          }}}
        """,
    )

    private fun cards(json: String): List<FlowCardAction> =
        FlowCards.parseActions(HomeyJson.parseToJsonElement(json)).let { FlowCards.byDevice(it)["rr"].orEmpty() }

    private val textCard = """
        {"homey:device:rr:clean_room": {"id": "homey:device:rr:clean_room", "title": "Clean one or more rooms",
          "args": [{"name": "rooms", "type": "text", "title": "rooms"}, {"name": "repeat", "type": "number", "min": 1}]}}
    """

    private class Home(val cards: List<FlowCardAction>, val device: Device?) : HomeControl {
        val sets = mutableListOf<Pair<String, JsonElement>>()
        val ran = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun setCapability(deviceId: String, capability: String, value: JsonElement) { sets += capability to value }
        override suspend fun setMood(mood: Mood) {}
        override suspend fun flows() = emptyList<HomeyFlow>()
        override suspend fun triggerFlow(flow: HomeyFlow) {}
        override suspend fun people() = emptyList<HomeyPerson>()
        override suspend fun variables() = emptyList<LogicVariable>()
        override suspend fun playMusic(deviceId: String, query: String): String? = null
        override fun deviceActions(deviceId: String) = cards
        override suspend fun choices(card: FlowCardAction, arg: FlowCardArg, query: String) =
            FlowCards.parseAutocomplete(HomeyJson.parseToJsonElement("""[{"id":16,"name":"Kök"},{"id":17,"name":"Hall"}]"""))
        override suspend fun runAction(card: FlowCardAction, args: JsonObject) { ran += card.cardId to args }
        override fun device(deviceId: String) = device
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

    private fun tools(home: Home) =
        AgentTools(HomeView.build(zones, devices, emptyMap(), emptySet(), AgentSettings(), true), home, Dash())

    private suspend fun AgentTools.vacuum(vararg args: Pair<String, String>) =
        run(ToolCall("c1", "vacuum", JsonObject(args.associate { (k, v) -> k to JsonPrimitive(v) }))).text

    @Test fun `start, dock and clean rooms through the app's own controls`() = runTest {
        val home = Home(cards(textCard), devices.getValue("rr"))
        val t = tools(home)
        assertTrue(t.specs().any { it.name == "vacuum" })
        assertEquals("Robbie started cleaning.", t.vacuum("action" to "start"))
        assertEquals("clean_full" to JsonPrimitive(true), home.sets.last())
        assertEquals("Robbie is going back to its dock.", t.vacuum("action" to "dock"))
        assertEquals("cancel_and_dock" to JsonPrimitive(true), home.sets.last())
        assertEquals("Robbie is cleaning Kitchen.", t.vacuum("action" to "clean_rooms", "rooms" to "kitchen"))
        assertEquals("clean_room" to JsonObject(mapOf("repeat" to JsonPrimitive(1), "rooms" to JsonPrimitive("Kitchen"))), home.ran.last())
    }

    @Test fun `go to a room uses a go-to card when the app has one, else cleans just that room`() = runTest {
        val goTo = """
            {"homey:device:rr:go_to_room": {"id": "homey:device:rr:go_to_room", "title": "Go to room",
              "args": [{"name": "room", "type": "autocomplete", "title": "Room"}]}}
        """
        val home = Home(cards(goTo), devices.getValue("rr"))
        assertEquals("Robbie is driving to Kök.", tools(home).vacuum("action" to "go_to", "rooms" to "Kök"))
        assertEquals("go_to_room", home.ran.single().first)

        val plain = Home(cards(textCard), devices.getValue("rr"))
        val said = tools(plain).vacuum("action" to "go_to", "rooms" to "Kitchen")
        assertTrue(said, said.contains("no plain go-to") && said.contains("cleaning Kitchen"))
        assertEquals("clean_room", plain.ran.single().first)
    }

    @Test fun `an unknown room lists the robot's rooms`() = runTest {
        val said = tools(Home(cards(textCard), devices.getValue("rr"))).vacuum("action" to "clean_rooms", "rooms" to "Garage")
        assertTrue(said, said.contains("no room \"Garage\"") && said.contains("Kitchen, Hall"))
    }
}
