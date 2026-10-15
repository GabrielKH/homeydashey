package app.homeydash.agent

import app.homeydash.homey.Device
import app.homeydash.homey.HomeyFlow
import app.homeydash.homey.HomeyJson
import app.homeydash.homey.Mood
import app.homeydash.homey.Zone
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class RoutinesTest {
    private val zones: Map<String, Zone> = HomeyJson.decodeFromString(
        """{"z-home":{"id":"z-home","name":"Home"},"z-kitchen":{"id":"z-kitchen","name":"Kitchen","parent":"z-home"}}""",
    )
    private val devices: Map<String, Device> = HomeyJson.decodeFromString(
        """
        {
          "d-lamp": {"id":"d-lamp","name":"Kitchen lamp","zone":"z-kitchen","class":"light","capabilities":["onoff","dim"],
            "capabilitiesObj":{"onoff":{"id":"onoff","type":"boolean","value":false,"setable":true},
                               "dim":{"id":"dim","type":"number","value":0.4,"min":0,"max":1,"setable":true}}},
          "d-sonos": {"id":"d-sonos","name":"Kök Sonos","zone":"z-kitchen","class":"speaker","capabilities":["speaker_playing"],
            "capabilitiesObj":{"speaker_playing":{"id":"speaker_playing","type":"boolean","value":false,"setable":true}}}
        }
        """.trimIndent(),
    )

    private fun view(settings: AgentSettings, local: Boolean = true) =
        HomeView.build(zones, devices, mapOf("m1" to Mood("m1", "Morgon", "z-kitchen")), emptySet(), settings, local)

    private class Home : HomeControl {
        val sets = mutableListOf<Triple<String, String, JsonElement>>()
        val played = mutableListOf<Pair<String, String>>()
        val started = mutableListOf<String>()
        override suspend fun setCapability(deviceId: String, capability: String, value: JsonElement) { sets += Triple(deviceId, capability, value) }
        override suspend fun setMood(mood: Mood) {}
        override suspend fun flows() = listOf(HomeyFlow("f1", "Kaffebryggare på", false))
        override suspend fun triggerFlow(flow: HomeyFlow) { started += flow.name }
        override suspend fun people() = emptyList<HomeyPerson>()
        override suspend fun variables() = emptyList<LogicVariable>()
        override suspend fun playMusic(deviceId: String, query: String): String? { played += deviceId to query; return "Sveriges Radio P1" }
    }

    private class Dash : DashboardControl {
        var asked: LocalDate? = null
        override fun openPage(page: String, roomId: String?) = true
        override fun openDevice(deviceId: String) = true
        override fun goodnightPreview() = ""
        override fun goodnight() {}
        override suspend fun weather() = "Sunny, 18 °C"
        override suspend fun calendar(from: LocalDate, days: Int, withLocations: Boolean): List<AgentEvent> {
            asked = from
            return listOf(AgentEvent("Tandläkaren", "09:00", "10:00", false, null))
        }
        override fun startTimer(seconds: Int, label: String?) {}
        override fun cancelTimer(label: String?) = false
        override fun timers() = emptyList<String>()
        override fun setTheme(mode: String) = true
    }

    private val monday = ZonedDateTime.of(2026, 10, 12, 7, 0, 0, 0, ZoneId.of("Europe/Stockholm"))

    @Test fun `phrases match with the wake name or a word around them, but not a whole question`() {
        val list = listOf(Routines.example())
        assertEquals("God morgon", Routines.match(list, "God morgon!", "Nova")?.name)
        assertEquals("God morgon", Routines.match(list, "Hej Nova, godmorgon", "Nova")?.name)
        assertEquals("God morgon", Routines.match(list, "Good morning everyone")?.name)
        assertNull(Routines.match(list, "god morgon, kan du tända lampan i köket"))
        assertNull(Routines.match(list, "tänd i köket"))
        assertNull(Routines.match(listOf(Routines.example().copy(enabled = false)), "god morgon"))
        val longer = Routine(name = "Lång", phrases = listOf("god morgon allihop"))
        assertEquals("Lång", Routines.match(list + longer, "god morgon allihop")?.name)
    }

    @Test fun `without an AI the steps run and what they read is spoken, the radio after the answer`() = runTest {
        val settings = AgentSettings(secrets = mapOf(Secret.Calendar to Access.On))
        val home = Home()
        val dash = Dash()
        val agent = Agent(ChatClient(OkHttpClient()), now = { monday }, routines = { RoutineData(routines = listOf(Routines.example())) })
        val reply = agent.ask("God morgon", settings, { view(settings, it) }, { AgentTools(it, home, dash) })
        assertFalse(reply.error)
        assertTrue(reply.text, reply.text.contains("Tandläkaren"))
        assertTrue(reply.text, reply.text.contains("Sunny"))
        assertEquals(LocalDate.of(2026, 10, 12), dash.asked)
        // The radio waits for the answer.
        assertTrue(home.played.isEmpty())
        val later = reply.then!!.invoke()
        assertEquals(listOf("d-sonos" to "P1"), home.played)
        assertTrue(later.single().startsWith("play_music"))
    }

    @Test fun `with an AI the routine's findings and instructions go with the question`() = runTest {
        val server = MockWebServer().apply { start() }
        val internet = Connection(Provider.OpenAI, baseUrl = server.url("/v1").toString(), apiKey = "k", model = "m")
        val settings = AgentSettings(internet = internet, mode = BrainMode.Internet, secrets = mapOf(Secret.Calendar to Access.On))
        val routine = Routines.example().copy(steps = listOf(RoutineStep(StepKind.Say, value = "Hej hej!")) + Routines.example().steps)
        val data = RoutineData(routines = listOf(routine), instructions = "Kalla mig Gabbe.")
        val agent = Agent(ChatClient(OkHttpClient()), now = { monday }, routines = { data })
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"God morgon Gabbe! Tandläkaren klockan nio."}}]}"""))
        val reply = agent.ask("god morgon", settings, { view(settings, it) }, { AgentTools(it, Home(), Dash()) })
        assertEquals("Hej hej! God morgon Gabbe! Tandläkaren klockan nio.", reply.text)
        assertNotNull(reply.then)
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("Routine \\\"God morgon\\\""))
        assertTrue(sent.contains("Tandläkaren"))
        assertTrue(sent.contains("Hälsa god morgon"))
        assertTrue(sent.contains("Kalla mig Gabbe."))
        assertTrue(sent.contains("play P1"))
        server.shutdown()
    }

    @Test fun `a calendar kept to the local AI never reaches the internet one through a routine`() = runTest {
        val server = MockWebServer().apply { start() }
        val internet = Connection(Provider.OpenAI, baseUrl = server.url("/v1").toString(), apiKey = "k", model = "m")
        // Port 9 on localhost: nothing listens, so the "PC" is off.
        val local = Connection(Provider.Ollama, baseUrl = "http://127.0.0.1:9/v1", model = "q")
        val settings = AgentSettings(local = local, internet = internet, secrets = mapOf(Secret.Calendar to Access.LocalOnly))
        val agent = Agent(ChatClient(OkHttpClient()), now = { monday }, routines = { RoutineData(routines = listOf(Routines.example())) })
        val reply = agent.ask("god morgon", settings, { view(settings, it) }, { AgentTools(it, Home(), Dash()) })
        assertEquals(0, server.requestCount)
        assertTrue(reply.text, reply.text.contains("Tandläkaren"))
        server.shutdown()
    }

    @Test fun `steps become the assistant's own tool calls`() {
        val settings = AgentSettings()
        val v = view(settings)
        val runner = RoutineRunner(AgentTools(v, Home(), Dash()), v, LocalDate.of(2026, 10, 12), "speaker_1")
        val dim = runner.call(RoutineStep(StepKind.Device, target = "d-lamp", value = "30 %"))!!
        assertEquals("set_device", dim.name)
        assertEquals(JsonPrimitive(v.aliasOf("d-lamp")), dim.arguments["device"])
        assertEquals(0.3, (dim.arguments["value"] as JsonPrimitive).content.toDouble(), 0.001)
        assertEquals("false", (runner.call(RoutineStep(StepKind.Room, target = "Kitchen", value = "av"))!!.arguments["value"] as JsonPrimitive).content)
        assertEquals("2026-10-13", (runner.call(RoutineStep(StepKind.Calendar, target = "imorgon"))!!.arguments["start_date"] as JsonPrimitive).content)
        assertEquals("mood_1", (runner.call(RoutineStep(StepKind.Mood, target = "Morgon"))!!.arguments["mood"] as JsonPrimitive).content)
        assertNull(runner.call(RoutineStep(StepKind.Flow)))
        assertEquals("speaker_1", (runner.call(RoutineStep(StepKind.Music, value = "P3"))!!.arguments["player"] as JsonPrimitive).content)
    }

    @Test fun `routines and instructions are made by voice and kept in a file`() = runTest {
        val dir = Files.createTempDirectory("routines").toFile()
        val book = RoutineBook(dir)
        book.load()
        assertEquals(listOf("God morgon"), book.data.value.routines.map { it.name })
        val tools = RoutineTools(book)
        fun call(name: String, vararg args: Pair<String, String>) = ToolCall("c", name, JsonObject(args.associate { it.first to JsonPrimitive(it.second) }))
        tools.run(call("save_routine", "name" to "Filmkväll", "phrases" to "filmkväll, movie night", "instructions" to "Dimma vardagsrummet till 20 %"))
        tools.run(call("remember_instruction", "instruction" to "Kalla mig Gabbe"))
        tools.run(call("remember_instruction", "instruction" to "Svara kort"))
        assertEquals("Forgot 1 instruction.", tools.run(call("forget_instruction", "instruction" to "gabbe"))!!.text)
        val again = RoutineBook(dir).apply { load() }.data.value
        assertEquals(listOf("filmkväll", "movie night"), again.named("Filmkväll")!!.phrases)
        assertEquals("Svara kort", again.instructions)
        assertEquals("Filmkväll", Routines.match(again.routines, "movie night")?.name)
        val delete = tools.run(call("delete_routine", "name" to "filmkväll"))!!
        assertNotNull(delete.pending)
        delete.pending!!.run()
        assertNull(book.data.value.routines.firstOrNull { it.name == "Filmkväll" })
        dir.deleteRecursively()
    }

    @Test fun `guests can't change routines but can run them`() {
        val v = view(AgentSettings(guest = true))
        val book = RoutineBook(Files.createTempDirectory("r").toFile())
        val names = AgentTools(v, Home(), Dash(), routines = RoutineTools(book)).specs().map { it.name }
        assertFalse("save_routine" in names)
    }

    @Test fun `after a promise without a tool call, a tool is required`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Jag tänder lampan!"}}]}"""))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Okej."}}]}"""))
        server.start()
        val settings = AgentSettings(local = Connection(Provider.Ollama, server.url("/v1").toString(), "", "m"), mode = BrainMode.Local)
        val agent = Agent(ChatClient(OkHttpClient()), now = { monday })
        agent.ask("kan du göra det mysigt", settings, { view(settings, it) }, { AgentTools(it, Home(), Dash()) })
        assertFalse(server.takeRequest().body.readUtf8().contains("tool_choice"))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"tool_choice\":\"required\""))
        server.shutdown()
    }
}
