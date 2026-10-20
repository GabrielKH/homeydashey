package app.homeydash.agent

import app.homeydash.homey.Device
import app.homeydash.homey.HomeyFlow
import app.homeydash.homey.HomeyJson
import app.homeydash.homey.Mood
import app.homeydash.homey.Zone
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

class KnowHowTest {
    @get:Rule val folder = TemporaryFolder()

    private val zones: Map<String, Zone> = HomeyJson.decodeFromString(
        """{"z-kitchen":{"id":"z-kitchen","name":"Kök"},"z-living":{"id":"z-living","name":"Vardagsrum"},"z-hall":{"id":"z-hall","name":"Hall"}}""",
    )
    private val devices: Map<String, Device> = HomeyJson.decodeFromString(
        """
        {
          "d-lamp": {"id":"d-lamp","name":"Taklampa","zone":"z-kitchen","class":"light","capabilities":["onoff"],
            "capabilitiesObj":{"onoff":{"id":"onoff","type":"boolean","value":true,"setable":true}}},
          "d-tv": {"id":"d-tv","name":"Vardagsrum","zone":"z-living","class":"tv","capabilities":["onoff"],
            "capabilitiesObj":{"onoff":{"id":"onoff","type":"boolean","value":false,"setable":true}}},
          "d-robot": {"id":"d-robot","name":"Roborock","zone":"z-hall","class":"vacuumcleaner","capabilities":["onoff"],
            "capabilitiesObj":{"onoff":{"id":"onoff","type":"boolean","value":false,"setable":true}}}
        }
        """.trimIndent(),
    )

    private fun view(settings: AgentSettings = AgentSettings(), local: Boolean = true) = HomeView.build(zones, devices, emptyMap(), emptySet(), settings, local)

    private class Home : HomeControl {
        val sets = mutableListOf<Triple<String, String, JsonElement>>()
        override suspend fun setCapability(deviceId: String, capability: String, value: JsonElement) { sets += Triple(deviceId, capability, value) }
        override suspend fun setMood(mood: Mood) {}
        override suspend fun flows() = emptyList<HomeyFlow>()
        override suspend fun triggerFlow(flow: HomeyFlow) {}
        override suspend fun people() = emptyList<HomeyPerson>()
        override suspend fun variables() = emptyList<LogicVariable>()
        override suspend fun playMusic(deviceId: String, query: String): String? = null
        override suspend fun readBack(deviceId: String, capability: String): JsonElement? = null
    }

    private class Dash : DashboardControl {
        override fun openPage(page: String, roomId: String?) = true
        override fun openDevice(deviceId: String) = true
        override fun goodnightPreview() = ""
        override fun goodnight() {}
        override suspend fun weather() = "Sunny"
        override suspend fun calendar(from: LocalDate, days: Int, withLocations: Boolean) = emptyList<AgentEvent>()
        override val timers = TimerBoard(null)
        override fun setTheme(mode: String) = true
    }

    private val skills = KnowHow.builtIn()
    private fun topics(question: String, active: Set<String> = emptySet()) = KnowHow.find(skills, question, view(), active).map { it.skill.id }

    @Test fun `a question brings only the topics it is about`() {
        assertEquals("music", topics("spela P1 i köket").first())
        assertEquals("timers", topics("sätt en pastatimer på tio minuter").first())
        assertEquals("calendar", topics("vad händer nästa vecka?").first())
        assertEquals("fridge", topics("vad finns i kylen").first())
        assertEquals("vacuum", topics("dammsug köket").first())
        assertTrue(topics("Tänd taklampan").isEmpty())
        assertTrue(topics("släck i köket").isEmpty())
        // Never more than a few, so a long question doesn't bring everything.
        assertTrue(topics("spela musik, starta en timer, dammsug och vad händer imorgon i kalendern").size <= KnowHow.MAX_TOPICS + 1)
    }

    @Test fun `a named device brings its kind's topic, but a room name alone doesn't`() {
        assertEquals(listOf("vacuum"), topics("skicka Roborock till hallen"))
        // The Apple TV is called Vardagsrum: lights in the living room aren't about the TV.
        assertFalse("tv" in topics("tänd lamporna i vardagsrummet"))
    }

    @Test fun `what is going on brings its topic, and a recipe brings the timers along`() {
        val found = topics("och sen då?", active = setOf("recipes"))
        assertEquals(listOf("recipes", "timers"), found)
        assertEquals(listOf("timers"), KnowHow.find(skills, "tio minuter till", view(), already = setOf("music")).map { it.skill.id })
        assertTrue(KnowHow.find(skills, "spela P3", view(), already = setOf("music")).isEmpty())
    }

    @Test fun `the fixed prompt keeps only the core and the list of topics, the same for every question`() {
        val agent = Agent(ChatClient(OkHttpClient()))
        val s = AgentSettings()
        val v = view(s)
        val all = AgentTools(v, Home(), Dash()).specs() + Agent.GUIDE_SPEC
        val topical = skills.flatMap { it.tools }.toSet()
        val core = all.filter { it.name !in topical }
        val fixed = agent.fixed(s, v, core, skills)
        val before = agent.fixed(s.copy(knowHow = false), v, all)
        assertTrue(fixed.contains("Topics: music (songs, radio"))
        assertFalse(fixed.contains("play_music("))
        assertFalse(fixed.contains("For robot vacuums"))
        assertTrue(fixed.contains("guide(topic)"))
        assertTrue(fixed.contains("a bare \"sätt taklampan\" means on"))
        assertTrue(before.contains("For robot vacuums") && before.contains("play_music("))
        assertTrue("${fixed.length} vs ${before.length}", fixed.length < before.length * 0.75)
        // Everything it knows is still listed for Settings and the tests.
        assertTrue(agent.rules(s).contains("For robot vacuums"))
    }

    @Test fun `the note brings the topic's rules and tools, once per conversation`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Okej."}}]}"""))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Okej igen."}}]}"""))
        server.start()
        val s = AgentSettings(local = Connection(Provider.Ollama, server.url("/v1").toString(), "", "m"), mode = BrainMode.Local)
        val agent = Agent(ChatClient(OkHttpClient()))
        agent.ask("dammsug köket", s, { view(s, it) }, { AgentTools(it, Home(), Dash()) })
        val first = server.takeRequest().body.readUtf8()
        assertTrue(first.contains("Know-how for this question"))
        assertTrue(first.contains("[Robot vacuum]"))
        assertTrue(first.contains("vacuum(device?, action"))
        agent.ask("dammsug hallen också", s, { view(s, it) }, { AgentTools(it, Home(), Dash()) })
        val second = server.takeRequest().body.readUtf8()
        // Already in the conversation from the first note, so it isn't sent again.
        assertEquals(1, Regex("\\[Robot vacuum]").findAll(second).count())
        server.shutdown()
    }

    @Test fun `when the note missed a topic, guide hands it over and its words are learned`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"CALL guide {\"topic\": \"music\"}"}}]}"""))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Visst."}}]}"""))
        server.start()
        val book = KnowHowBook(folder.root)
        val s = AgentSettings(local = Connection(Provider.Ollama, server.url("/v1").toString(), "", "m"), mode = BrainMode.Local)
        val agent = Agent(ChatClient(OkHttpClient()), knowHow = book)
        agent.ask("sätt på lite jazzigt", s, { view(s, it) }, { AgentTools(it, Home(), Dash()) })
        server.takeRequest()
        val second = server.takeRequest().body.readUtf8()
        assertTrue(second.contains("[Music and speakers]"))
        assertTrue(second.contains("play_music(player"))
        assertEquals(listOf("jazzigt"), book.data.value.learned["music"])
        // Next time the question brings music by itself.
        assertEquals("music", KnowHow.find(agent.skills(s), "något jazzigt i köket", view()).first().skill.id)
        server.shutdown()
    }

    @Test fun `the owner's rules and topics join the graph`() = runTest {
        val book = KnowHowBook(folder.root)
        val tools = KnowHowTools(book)
        tools.run(ToolCall("t", "teach", kotlinx.serialization.json.JsonObject(mapOf("topic" to JsonPrimitive("musik"), "rule" to JsonPrimitive("På morgonen spelar vi P1 i köket")))))
        tools.run(ToolCall("t", "teach", kotlinx.serialization.json.JsonObject(mapOf(
            "topic" to JsonPrimitive("Plant watering"), "rule" to JsonPrimitive("Monsteran vattnas på söndagar"), "words" to JsonPrimitive("vattna, blommor"),
        ))))
        val graph = KnowHow.graph(book.data.value)
        assertTrue(graph.first { it.id == "music" }.rules.last() == "På morgonen spelar vi P1 i köket")
        assertEquals("plant_watering", KnowHow.find(graph, "när ska jag vattna blommorna?", null).first().skill.id)
        assertTrue(KnowHow.index(graph).contains("plant_watering"))
        val gone = tools.run(ToolCall("t", "forget_rule", kotlinx.serialization.json.JsonObject(mapOf("rule" to JsonPrimitive("morgonen P1")))))
        assertEquals("Forgot 1 rule.", gone?.text)
        val reloaded = KnowHowBook(folder.root).apply { load() }
        assertEquals(1, reloaded.data.value.own.size)
    }

    @Test fun `memory facts the question names come without a cue word`() {
        val data = MemoryData(facts = listOf(
            Fact(subject = "Elise", relation = "is", obj = "Gabriel's sister", kind = FactKind.Said),
            Fact(subject = "Gabriel", relation = "plays", obj = "P1 on Kök", kind = FactKind.Habit),
        ))
        assertEquals("Elise", Memory.matching(data, "ring Elise", null, true, 0).single().subject)
        assertTrue(Memory.matching(data, "spela P1", null, true, 0).isEmpty())
    }
}
