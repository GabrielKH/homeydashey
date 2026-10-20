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
        override val timers = TimerBoard(null)
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
        assertTrue(t.vacuum("action" to "dock").startsWith("Sent Robbie home twice"))
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

    private fun robot(state: String, charging: Boolean = false): Device = HomeyJson.decodeFromString(
        """
        {"id": "rr", "name": "Robbie", "zone": "z-home", "class": "vacuumcleaner",
          "capabilities": ["vacuumcleaner_state", "battery_charging_state", "clean_full", "cancel_and_dock"],
          "capabilitiesObj": {
            "vacuumcleaner_state": {"id": "vacuumcleaner_state", "type": "enum", "value": "$state"},
            "battery_charging_state": {"id": "battery_charging_state", "type": "boolean", "value": $charging},
            "clean_full": {"id": "clean_full", "type": "boolean", "value": false, "setable": true},
            "cancel_and_dock": {"id": "cancel_and_dock", "type": "boolean", "value": false, "setable": true}
          }}
        """,
    )

    @Test fun `where says docked, charging, or the room it was last sent to`() = runTest {
        assertEquals("Robbie is in its dock.", tools(Home(cards(textCard), robot("docked"))).vacuum("action" to "where"))
        assertEquals("Robbie is in its dock, charging.", tools(Home(cards(textCard), robot("docked", charging = true))).vacuum("action" to "locate"))
        val goTo = """
            {"homey:device:rr:go_to_room": {"id": "homey:device:rr:go_to_room", "title": "Go to room",
              "args": [{"name": "room", "type": "autocomplete", "title": "Room"}]}}
        """
        val t = tools(Home(cards(goTo), robot("stopped")))
        t.vacuum("action" to "go_to", "rooms" to "Kök")
        val said = t.vacuum("action" to "where")
        assertTrue(said, said.contains("last sent to Kök") && said.contains("just now"))
    }

    @Test fun `rooms as a list and the vacuum's alias used as the tool name both work`() = runTest {
        val home = Home(cards(textCard), devices.getValue("rr"))
        val t = tools(home)
        val rooms = JsonObject(mapOf("action" to JsonPrimitive("clean_rooms"), "rooms" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("Kitchen"), JsonPrimitive("Hall")))))
        assertEquals("Robbie is cleaning Kitchen and Hall.", t.run(ToolCall("c1", "vacuum", rooms)).text)
        val alias = HomeView.build(zones, devices, emptyMap(), emptySet(), AgentSettings(), true).devices.single().alias
        val said = t.run(ToolCall("c2", alias, JsonObject(mapOf("action" to JsonPrimitive("dock"))))).text
        assertTrue(said, said.startsWith("Sent Robbie home twice"))
    }

    @Test fun `start then dock waits between the two, and dock is confirmed from Homey`() = runTest {
        var robot = robot("cleaning")
        val home = object : HomeControl by Home(cards(textCard), null) {
            val times = mutableListOf<Long>()
            override suspend fun setCapability(deviceId: String, capability: String, value: JsonElement) {
                times += testScheduler.currentTime
                if (capability == "cancel_and_dock") robot = robot("docked")
            }
            override fun device(deviceId: String) = robot
        }
        val t = AgentTools(HomeView.build(zones, mapOf("rr" to robot), emptyMap(), emptySet(), AgentSettings(), true), home, Dash())
        t.vacuum("action" to "start")
        assertEquals("Robbie is on its way back to its dock.", t.vacuum("action" to "dock"))
        assertTrue(home.times.toString(), home.times.size == 2 && home.times[1] - home.times[0] >= 3_000)
    }
}
