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

    @Test fun `the new topics come with their own questions`() {
        assertTrue("weather" in topics("behöver jag paraply imorgon?"))
        assertTrue("goodnight" in topics("god natt"))
        assertTrue("moods" in topics("gör det mysigt i vardagsrummet"))
    }

    @Test fun `the closest worked examples come with a question, the home's own first`() {
        val found = Examples.find("spela P1 i köket", names = listOf("Kök", "Taklampa"))
        assertTrue(found.isNotEmpty())
        assertTrue(found.first().calls.first().startsWith("play_music"))
        assertTrue(Examples.find("xyzzy").isEmpty())
        val learned = listOf(Examples.Example("spela lugnt i köket", listOf("play_music {\"player\":\"Kök\",\"query\":\"lugnt\"}")))
        assertEquals("spela lugnt i köket", Examples.find("spela lugnt i köket", learned).first().say)
        assertTrue(Examples.note(found).startsWith("\nHow similar requests were done"))
    }

    @Test fun `a turn that went well is kept as an example, and forgotten when it was wrong`() = runTest {
        var clock = 1_000L
        val book = KnowHowBook(folder.root) { clock }
        book.load()
        book.learnExample("släck i tvättstugan", listOf("set_room {\"room\":\"Tvättstuga\"}"))
        book.learnExample("släck i tvättstugan", listOf("set_room {\"room\":\"Tvättstuga\",\"value\":\"false\"}"))
        assertEquals(1, book.data.value.examples.size)
        assertEquals(1, KnowHowBook(folder.root).apply { load() }.data.value.examples.size)
        clock = 5_000L
        assertFalse(book.forgetLastExample(since = 2_000L))
        assertTrue(book.forgetLastExample(since = 500L))
        assertTrue(book.data.value.examples.isEmpty())
    }

    @Test fun `no built-in example is a skill test sentence, so the tests still measure something`() {
        val dir = java.io.File(javaClass.classLoader.getResource("evals/cases")!!.toURI())
        val tested = dir.listFiles()!!.flatMap { f ->
            Regex("\"say\"\\s*:\\s*\\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL).findAll(f.readText()).flatMap { m ->
                Regex("\"([^\"]+)\"").findAll(m.groupValues[1]).map { NameMatch.normalize(it.groupValues[1]) }
            }.toList()
        }.toSet()
        assertTrue(tested.size > 50)
        val same = Examples.builtIn.filter { NameMatch.normalize(it.say) in tested }
        assertTrue("Examples copied from the tests: ${same.map { it.say }}", same.isEmpty())
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

    @Test fun `words taught by voice are kept in the book and forgotten by voice`() = runTest {
        val book = KnowHowBook(folder.root)
        val tools = KnowHowTools(book)
        fun call(name: String, vararg a: Pair<String, String>) = ToolCall("t", name, kotlinx.serialization.json.JsonObject(a.associate { it.first to JsonPrimitive(it.second) }))
        assertEquals("Remembered: sovdags now means goodnight.", tools.run(call("teach_words", "words" to "sovdags", "means" to "goodnight"))?.text)
        assertTrue(tools.run(call("teach_words", "words" to "almanackan", "means" to "calendar"))!!.text.contains("calendar"))
        tools.run(call("teach_words", "words" to "tvättstugan", "means" to "laundry room"))
        val data = KnowHowBook(folder.root).apply { load() }.data.value
        assertEquals(listOf("sovdags"), data.cues["goodnight"])
        assertEquals(listOf("almanackan"), data.cues["needs_calendar"])
        assertEquals(listOf(listOf("tvättstugan", "laundry room")), data.translations)
        assertEquals("Forgot sovdags.", tools.run(call("forget_words", "words" to "sovdags"))?.text)
        org.junit.Assert.assertNull(book.data.value.cues["goodnight"])
    }

    @Test fun `memory facts the question names come without a cue word`() {
        val data = MemoryData(facts = listOf(
            Fact(subject = "Elise", relation = "is", obj = "Gabriel's sister", kind = FactKind.Said),
            Fact(subject = "Gabriel", relation = "plays", obj = "P1 on Kök", kind = FactKind.Habit),
        ))
        assertEquals("Elise", Memory.matching(data, "ring Elise", null, true, 0).single().subject)
        assertTrue(Memory.matching(data, "spela P1", null, true, 0).isEmpty())
    }

    private val house: Map<String, Device> = HomeyJson.decodeFromString(
        """
        {
          "d-heat": {"id":"d-heat","name":"Element Hall","zone":"z-hall","class":"heater","capabilities":["target_temperature","measure_temperature"],
            "capabilitiesObj":{"target_temperature":{"id":"target_temperature","type":"number","value":20,"min":5,"max":30,"setable":true},
                               "measure_temperature":{"id":"measure_temperature","type":"number","value":19.5,"setable":false}}},
          "d-blind": {"id":"d-blind","name":"Persienn","zone":"z-kitchen","class":"blinds","capabilities":["windowcoverings_state"],
            "capabilitiesObj":{"windowcoverings_state":{"id":"windowcoverings_state","type":"enum","value":"idle","setable":true,"values":[{"id":"up"},{"id":"idle"},{"id":"down"}]}}},
          "d-wash": {"id":"d-wash","name":"Tvättmaskin","zone":"z-hall","class":"other","driverUri":"homey:app:com.miele:washer","capabilities":["onoff","measure_power"],
            "capabilitiesObj":{"onoff":{"id":"onoff","type":"boolean","value":true,"setable":true},
                               "measure_power":{"id":"measure_power","type":"number","value":1200,"setable":false}}},
          "d-lamp": {"id":"d-lamp","name":"Taklampa","zone":"z-kitchen","class":"light","capabilities":["onoff"],
            "capabilitiesObj":{"onoff":{"id":"onoff","type":"boolean","value":true,"setable":true}}}
        }
        """.trimIndent(),
    )

    private fun houseView(only: Set<String> = house.keys) =
        HomeView.build(zones, house.filterKeys { it in only }, emptyMap(), emptySet(), AgentSettings(), true)

    @Test fun `Homey's devices make their own topics, with their names and capabilities`() {
        val v = houseView()
        val graph = KnowHow.graph(KnowHowData(), view = v)
        val homey = graph.filter { it.fromHomey }.map { it.id }.toSet()
        assertEquals(setOf("homey_climate", "homey_covers", "app_miele", "homey_energy"), homey)
        fun top(q: String) = KnowHow.find(graph, q, v).map { it.skill.id }
        assertEquals("homey_climate", top("gör det varmare i hallen").first())
        assertEquals("homey_climate", top("hur är det med Element Hall").first())
        assertEquals("homey_covers", top("dra ner persiennerna").first())
        assertTrue(top("hur mycket ström drar tvättmaskinen?").containsAll(listOf("homey_energy", "app_miele")))
        // Lights stay with every question's rules: no topic of their own.
        assertTrue(top("tänd taklampan").isEmpty())
        val climate = graph.first { it.id == "homey_climate" }
        assertTrue(climate.rules.any { "Element Hall (Hall): target_temperature" in it })
        assertTrue(climate.rules.any { it.startsWith("target_temperature is") })
        // get_device is named by these topics but stays an everyday tool in the fixed part.
        val agent = Agent(ChatClient(OkHttpClient()))
        val s = AgentSettings()
        val all = AgentTools(v, Home(), Dash()).specs() + Agent.GUIDE_SPEC
        val core = all.filter { it.name !in (graph.flatMap { it.tools }.toSet() - Agent.CORE_TOOLS) }
        assertTrue(agent.fixed(s, v, core, graph).contains("get_device(device)"))
        assertTrue(agent.fixed(s, v, core, graph).contains("homey_climate (heating"))
    }

    @Test fun `a new kind of device is noticed once, and goes into the memory graph`() = runTest {
        val book = KnowHowBook(folder.root)
        val memory = MemoryBook(folder.newFolder("mem"))
        val server = MockWebServer()
        repeat(2) { server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Okej."}}]}""")) }
        server.start()
        val s = AgentSettings(local = Connection(Provider.Ollama, server.url("/v1").toString(), "", "m"), mode = BrainMode.Local)
        val agent = Agent(ChatClient(OkHttpClient()), memory = memory, knowHow = book)
        var only = setOf("d-lamp", "d-blind")
        agent.ask("hej", s, { HomeView.build(zones, house.filterKeys { k -> k in only }, emptyMap(), emptySet(), s, it) }, { AgentTools(it, Home(), Dash()) })
        assertTrue(memory.data.value.facts.isEmpty())
        assertTrue("homey_covers" in book.data.value.seen)
        only = only + "d-heat"
        agent.ask("hej igen", s, { HomeView.build(zones, house.filterKeys { k -> k in only }, emptyMap(), emptySet(), s, it) }, { AgentTools(it, Home(), Dash()) })
        val fact = memory.data.value.facts.single()
        assertEquals("Heating and climate", fact.subject)
        assertTrue(fact.obj.contains("Element Hall"))
        server.shutdown()
    }

    @Test fun `the know-how graph joins topics to their tools, devices and the facts that name them`() {
        val v = houseView()
        val skills = KnowHow.graph(KnowHowData(), view = v)
        val facts = listOf(
            Fact(id = "f1", whoName = "Gabriel", subject = "Gabriel", relation = "starts", obj = "Tvättmaskin on Sundays", kind = FactKind.Habit, used = 3),
            Fact(id = "f2", subject = "the house", relation = "likes", obj = "timers for pasta"),
            Fact(id = "f3", subject = "Gabriel", relation = "likes", obj = "blue"),
        )
        val g = KnowHowGraph.build(skills, mapOf("homey_climate" to 4), v, facts)
        fun at(key: String) = g.nodes.indexOfFirst { it.key == key }.also { assertTrue("no $key", it >= 0) }
        fun joined(a: String, b: String) = at(b) in g.neighbours(at(a))
        assertTrue(joined("t:homey_climate", "d:d-heat"))
        assertTrue(joined("t:homey_climate", "tool:get_device"))
        // A tool shared by topics ties them together.
        assertTrue(joined("t:homey_covers", "tool:get_device"))
        assertTrue(joined("t:recipes", "t:timers"))
        // Facts hang off their person and join the device they name, or the topic their words bring.
        assertTrue(joined("f:f1", "d:d-wash"))
        assertTrue(joined("who:gabriel", "f:f1"))
        assertTrue(joined("f:f2", "t:timers"))
        assertFalse(g.nodes.any { it.key == "f:f3" })
        assertEquals(KnowHowGraph.Source.Homey, g.nodes[at("t:homey_climate")].source)
        assertTrue(KnowHowGraph.weight(g.nodes[at("t:homey_climate")]) > KnowHowGraph.weight(g.nodes[at("t:homey_covers")]))
        val pos = KnowHowGraph.layout(g)
        assertEquals(g.nodes.size * 2, pos.size)
        assertTrue(pos.all { it in -1.001f..1.001f })
    }
}
