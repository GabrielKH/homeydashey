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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class MemoryTest {
    private val zones: Map<String, Zone> = HomeyJson.decodeFromString(
        """{"z-kitchen":{"id":"z-kitchen","name":"Kitchen"},"z-living":{"id":"z-living","name":"Living room"}}""",
    )
    private val devices: Map<String, Device> = HomeyJson.decodeFromString(
        """
        {
          "d-lamp": {"id":"d-lamp","name":"Kitchen lamp","zone":"z-kitchen","class":"light","capabilities":["onoff","dim"],
            "capabilitiesObj":{"onoff":{"id":"onoff","type":"boolean","value":true,"setable":true},
                               "dim":{"id":"dim","type":"number","value":0.4,"min":0,"max":1,"setable":true}}},
          "d-tv": {"id":"d-tv","name":"TV","zone":"z-living","class":"tv","capabilities":["onoff","volume_up","volume_down"],
            "capabilitiesObj":{"onoff":{"id":"onoff","type":"boolean","value":true,"setable":true},
                               "volume_up":{"id":"volume_up","type":"boolean","value":false,"setable":true},
                               "volume_down":{"id":"volume_down","type":"boolean","value":false,"setable":true}}}
        }
        """.trimIndent(),
    )

    private fun view(settings: AgentSettings = AgentSettings(), local: Boolean = true) =
        HomeView.build(zones, devices, emptyMap(), emptySet(), settings, local)

    private class FakeHome : HomeControl {
        val sets = mutableListOf<Triple<String, String, JsonElement>>()
        override suspend fun setCapability(deviceId: String, capability: String, value: JsonElement) { sets += Triple(deviceId, capability, value) }
        override suspend fun setMood(mood: Mood) {}
        override suspend fun flows() = emptyList<HomeyFlow>()
        override suspend fun triggerFlow(flow: HomeyFlow) {}
        override suspend fun people() = emptyList<HomeyPerson>()
        override suspend fun variables() = emptyList<LogicVariable>()
        override suspend fun playMusic(deviceId: String, query: String): String? = null
    }

    private class FakeDashboard : DashboardControl {
        override fun openPage(page: String, roomId: String?) = true
        override fun openDevice(deviceId: String) = true
        override fun goodnightPreview() = ""
        override fun goodnight() {}
        override suspend fun weather() = "Sunny"
        override suspend fun calendar(from: LocalDate, days: Int, withLocations: Boolean) = emptyList<AgentEvent>()
        override fun startTimer(seconds: Int, label: String?) {}
        override fun cancelTimer(label: String?) = false
        override fun timers() = emptyList<String>()
        override fun setTheme(mode: String) = true
    }

    private val gabriel = Asker("p-gabriel", "Gabriel")
    private val maja = Asker("p-maja", "Maja")
    private val day = 86_400_000L
    private val now = 100 * day

    private fun call(name: String, vararg args: Pair<String, String>) =
        ToolCall("c", name, JsonObject(args.associate { (k, v) -> k to JsonPrimitive(v) }))

    private fun fact(subject: String, relation: String, obj: String, who: Asker? = null, kind: FactKind = FactKind.Learned, localOnly: Boolean = false) =
        Fact(who = who?.id ?: Memory.HOUSE, whoName = who?.name.orEmpty(), subject = subject, relation = relation, obj = obj, kind = kind, last = now, localOnly = localOnly)

    private fun data(vararg facts: Fact) = MemoryData(facts = facts.toList())

    @Test fun `recall finds the few facts a question touches, and their neighbours`() {
        val d = data(
            fact("Gabriel", "plays", "P1 on Kök", gabriel, FactKind.Habit),
            fact("P1", "is", "the news radio"),
            fact("Elise", "lives in", "Göteborg"),
            fact("Dishwasher", "runs best", "at night"),
        )
        val found = Memory.recall(d, "spela radion som vanligt, P1", gabriel.id, local = true, now = now)
        assertTrue(found.any { it.obj == "P1 on Kök" })
        assertTrue(found.any { it.subject == "P1" })
        assertFalse(found.any { it.subject == "Elise" })
        assertTrue(Memory.recall(d, "vad vet du om fotboll", gabriel.id, true, now).isEmpty())
    }

    @Test fun `a person's facts are only recalled for that person, local ones only for a local AI`() {
        val d = data(
            fact("Gabriel", "likes", "warm light when cooking", gabriel),
            fact("Maja", "likes", "cold light", maja),
            fact("House", "keeps", "the spare key code", localOnly = true),
        )
        val forMaja = Memory.recall(d, "light", maja.id, local = true, now = now)
        assertEquals(listOf("cold light"), forMaja.map { it.obj })
        assertTrue(Memory.recall(d, "light", null, true, now).isEmpty())
        assertEquals(1, Memory.recall(d, "spare key", null, local = true, now = now).size)
        assertTrue(Memory.recall(d, "spare key", null, local = false, now = now).isEmpty())
    }

    @Test fun `repeated habits are counted, not stored twice, and new facts replace old ones`() {
        val morning = ZonedDateTime.of(2026, 10, 18, 7, 30, 0, 0, ZoneId.of("Europe/Stockholm"))
        val v = view()
        var d = MemoryData()
        repeat(3) { d = Memory.merge(d, Memory.habit(call("play_music", "player" to "Sonos", "query" to "P1"), v, gabriel, morning)!!, now) }
        d = Memory.merge(d, Memory.habit(call("play_music", "player" to "Sonos", "query" to "Spotify chill"), v, gabriel, morning.withHour(19))!!, now)
        assertEquals(2, d.facts.size)
        val p1 = d.facts.first { it.obj.startsWith("P1") }
        assertEquals(3, p1.count)
        assertEquals("Gabriel plays P1 on Sonos (3 times, mostly mornings)", Memory.line(p1))
        d = Memory.merge(d, fact("Elise", "lives in", "Stockholm"), now)
        d = Memory.merge(d, fact("Elise", "lives in", "Göteborg"), now)
        assertEquals("Göteborg", d.facts.single { it.subject == "Elise" }.obj)
        assertEquals(null, Memory.habit(call("get_device", "device" to "light_1"), v, gabriel, morning))
    }

    @Test fun `the weakest facts go when the memory is full`() {
        var d = MemoryData()
        repeat(Memory.MAX_FACTS + 10) { i -> d = Memory.merge(d, fact("Thing $i", "is", "number $i").copy(last = now - (Memory.MAX_FACTS + 10 - i) * day), now - (Memory.MAX_FACTS + 10 - i) * day) }
        d = Memory.merge(d, fact("Wifi", "is called", "Hemma", kind = FactKind.Said), now)
        assertEquals(Memory.MAX_FACTS, d.facts.size)
        assertTrue(d.facts.any { it.subject == "Wifi" })
        assertFalse(d.facts.any { it.subject == "Thing 0" })
    }

    @Test fun `the digest answer becomes facts, and bad lines are dropped`() {
        val text = """
            - me ; Gabriel ; likes ; dimmed warm light when cooking
            house ; Elise ; visits ; on 24 October
            Sure, here are the facts:
            me ; only three ; parts
            NONE
        """.trimIndent()
        val facts = Memory.parseDigest(text, gabriel, localOnly = false)
        assertEquals(2, facts.size)
        assertEquals(gabriel.id, facts[0].who)
        assertEquals(Memory.HOUSE, facts[1].who)
        // Nobody known: "me" facts go to the house.
        assertEquals(Memory.HOUSE, Memory.parseDigest("me ; Someone ; likes ; jazz", null, false).single().who)
    }

    @Test fun `the transcript has what was said, not notes, calls or nudges`() {
        val turn = listOf(
            ChatMessage.user("(From the app, not the user.)\nOn right now: lamp\n\nThe user said: spela P1 i köket"),
            ChatMessage("assistant", "CALL play_music {\"player\": \"player_1\"}"),
            ChatMessage.tool("x", "Playing P1"),
            ChatMessage.user("(From the app, not the user: answer it now.)"),
            ChatMessage("assistant", "P1 is on in the kitchen."),
        )
        assertEquals("User: spela P1 i köket\nAssistant: P1 is on in the kitchen.", Memory.transcriptOf(listOf(turn), Agent.QUESTION_LABEL))
    }

    @Test fun `memory tools remember, recall and forget for the person asking`() = runTest {
        val dir = Files.createTempDirectory("mem").toFile()
        val book = MemoryBook(dir) { now }
        val tools = MemoryTools(book) { now }
        tools.run(call("remember", "subject" to "Gabriel", "relation" to "likes", "object" to "tacos on Fridays"), gabriel, true)
        tools.run(call("remember", "subject" to "Wifi", "relation" to "is called", "object" to "Hemma", "for" to "house"), gabriel, true)
        assertEquals("Gabriel likes tacos on Fridays", tools.run(call("recall", "about" to "tacos"), gabriel, true)!!.text)
        assertEquals("No memory of that.", tools.run(call("recall", "about" to "tacos"), maja, true)!!.text)
        assertEquals("Wifi is called Hemma", tools.run(call("recall", "about" to "wifi"), maja, true)!!.text)
        assertEquals("Forgot 1 fact.", tools.run(call("forget_memory", "about" to "tacos"), gabriel, true)!!.text)
        // It lasts: a new book reads the same file.
        val again = MemoryBook(dir).apply { load() }
        assertEquals(listOf("Wifi"), again.data.value.facts.map { it.subject })
    }

    @Test fun `habits are learned from commands and a cue word brings them into the question`() = runTest {
        val server = MockWebServer()
        val on = """{"device":"light_1","capability":"onoff","value":"true"}"""
        server.enqueue(MockResponse().setBody(toolCall("a", "set_device", on)))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"On."}}]}"""))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Same as always."}}]}"""))
        server.start()
        val book = MemoryBook(Files.createTempDirectory("mem").toFile())
        val settings = AgentSettings(local = Connection(Provider.Ollama, server.url("/v1").toString(), "", "m"), mode = BrainMode.Local, asker = gabriel)
        val agent = Agent(ChatClient(OkHttpClient()), memory = book)
        agent.ask("could you light up the cooking area", settings, { view(settings, it) }, { AgentTools(it, FakeHome(), FakeDashboard(), memory = MemoryTools(book)) })
        assertEquals("Gabriel turns on Kitchen lamp", Memory.line(book.data.value.facts.single()))
        server.takeRequest()
        server.takeRequest()
        agent.ask("do the kitchen lamp like usual", settings, { view(settings, it) }, { AgentTools(it, FakeHome(), FakeDashboard(), memory = MemoryTools(book)) })
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("Remembered from earlier"))
        assertTrue(sent.contains("Gabriel turns on Kitchen lamp"))
        server.shutdown()
    }

    @Test fun `a finished conversation is squeezed into facts by the AI`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Nice, a weekend with Elise."}}]}"""))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"me ; Gabriel ; has a sister ; Elise\nhouse ; Elise ; visits ; Saturday"}}]}"""))
        server.start()
        val book = MemoryBook(Files.createTempDirectory("mem").toFile())
        val settings = AgentSettings(local = Connection(Provider.Ollama, server.url("/v1").toString(), "", "m"), mode = BrainMode.Local, asker = gabriel)
        val agent = Agent(ChatClient(OkHttpClient()), memory = book)
        agent.ask("min syster Elise kommer på lördag och stannar hela helgen", settings, { view(settings, it) }, { AgentTools(it, FakeHome(), FakeDashboard()) })
        assertEquals(0, agent.digest(settings)) // still going on
        agent.reset()
        assertEquals(2, agent.digest(settings))
        server.takeRequest()
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("min syster Elise"))
        assertFalse(sent.contains("On right now"))
        assertEquals(setOf(gabriel.id, Memory.HOUSE), book.data.value.facts.map { it.who }.toSet())
        assertEquals(0, agent.digest(settings))
        server.shutdown()
    }

    @Test fun `memory switched off leaves the prompt and tools as they were`() {
        val book = MemoryBook(Files.createTempDirectory("mem").toFile())
        val agent = Agent(ChatClient(OkHttpClient()), memory = book)
        assertTrue(agent.rules(AgentSettings()).contains("long memory"))
        val off = AgentSettings(secrets = mapOf(Secret.Memory to Access.Off))
        assertFalse(agent.rules(off).contains("long memory"))
        assertFalse(AgentTools(view(off), FakeHome(), FakeDashboard(), memory = MemoryTools(book)).specs().any { it.name == "recall" })
        assertTrue(AgentTools(view(), FakeHome(), FakeDashboard(), memory = MemoryTools(book)).specs().any { it.name == "recall" })
    }

    @Test fun `local AI only keeps the memory away from the internet AI`() {
        val book = MemoryBook(Files.createTempDirectory("mem").toFile())
        val agent = Agent(ChatClient(OkHttpClient()), memory = book)
        val s = AgentSettings()
        assertEquals(Access.LocalOnly, s.access(Secret.Memory))
        assertTrue(agent.rules(s, local = true).contains("long memory"))
        assertFalse(agent.rules(s, local = false).contains("long memory"))
        assertFalse(AgentTools(view(s, local = false), FakeHome(), FakeDashboard(), memory = MemoryTools(book)).specs().any { it.name == "recall" })
        val on = AgentSettings(secrets = mapOf(Secret.Memory to Access.On))
        assertTrue(AgentTools(view(on, local = false), FakeHome(), FakeDashboard(), memory = MemoryTools(book)).specs().any { it.name == "recall" })
    }

    @Test fun `what people write about themselves keeps every line and is never pushed out`() {
        var d = MemoryData()
        d = Memory.merge(d, fact("Maja", "likes", "sushi", kind = FactKind.About), now)
        d = Memory.merge(d, fact("Maja", "likes", "jazz", kind = FactKind.About), now)
        d = Memory.merge(d, fact("Maja", "birthday", "12 May", kind = FactKind.About), now)
        repeat(Memory.MAX_FACTS + 5) { i -> d = Memory.merge(d, fact("Thing $i", "is", "number $i", kind = FactKind.Said), now) }
        assertEquals(Memory.MAX_FACTS, d.facts.size)
        assertEquals(3, d.facts.count { it.kind == FactKind.About })
        // Shared lines answer anyone: "when is Maja's birthday?"
        assertEquals("12 May", Memory.recall(d, "Majas birthday", gabriel.id, true, now).first().obj)
    }

    private fun toolCall(id: String, name: String, args: String) =
        """{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"$id","type":"function","function":{"name":"$name","arguments":"${args.replace("\"", "\\\"")}"}}]}}]}"""

    @Test
    fun `a new character pick replaces the old one`() {
        val g = Asker("g", "Gabriel")
        var d = Memory.merge(MemoryData(), Memory.characterChoice(g, listOf(Persona.Tars)), 1)
        d = Memory.merge(d, Memory.characterChoice(g, listOf(Persona.Tars, Persona.Nova), mapOf(Persona.Nova to VoiceStyle.Warm)), 2)
        assertEquals(1, d.facts.size)
        assertEquals("TARS and Nova in the Warm voice", d.facts.single().obj)
        assertEquals("g", d.facts.single().who)
        val at = ZonedDateTime.of(2026, 10, 18, 8, 0, 0, 0, ZoneId.of("Europe/Stockholm"))
        d = Memory.merge(d, Memory.woke(g, Persona.Tars, at), 3)
        d = Memory.merge(d, Memory.woke(g, Persona.Tars, at), 4)
        assertEquals(2, d.facts.first { it.kind == FactKind.Habit }.count)
    }

    @Test fun `a module choice replaces the last one`() {
        var data = MemoryData()
        data = Memory.merge(data, Memory.moduleChoice("Calendar", on = true, source = "Built in: this tablet's accounts"), 1)
        data = Memory.merge(data, Memory.moduleChoice("Calendar", on = true, source = "Homey app: IcalCalendar"), 2)
        assertEquals(1, data.facts.size)
        assertEquals("Homey app: IcalCalendar", data.facts.single().obj)
        data = Memory.merge(data, Memory.moduleChoice("Calendar", on = false, source = "x"), 3)
        assertEquals(1, data.facts.size)
        assertTrue("turned off" in data.facts.single().obj)
    }

    @Test fun `using a fact counts it and logs the recall, newest first`() {
        val radio = fact("Gabriel", "plays", "P1 on Kök", gabriel, FactKind.Habit)
        var d = data(radio, fact("Elise", "lives in", "Göteborg"))
        d = Memory.used(d, "spela som vanligt", gabriel, RecallVia.Cue, listOf(radio), now)
        d = Memory.used(d, "fotboll", null, RecallVia.Tool, emptyList(), now + 1)
        assertEquals(1, d.facts.first { it.id == radio.id }.used)
        assertEquals(now, d.facts.first { it.id == radio.id }.lastUsed)
        assertEquals(0, d.facts.first { it.subject == "Elise" }.used)
        assertEquals(listOf("fotboll", "spela som vanligt"), d.recalls.map { it.question })
        assertEquals(listOf("Gabriel plays P1 on Kök"), d.recalls.last().lines)
        // Doing it again keeps the count of uses.
        d = Memory.merge(d, radio.copy(id = "other"), now + 2)
        assertEquals(2, d.facts.first { it.id == radio.id }.count)
        assertEquals(1, d.facts.first { it.id == radio.id }.used)
    }

    @Test fun `the graph hangs facts off their person and joins shared things`() {
        val d = listOf(
            fact("Gabriel", "plays", "P1", gabriel, FactKind.Habit),
            fact("Maja", "plays", "P1", maja, FactKind.Habit),
            fact("Someone in the house", "sets the lights", "cozy in Vardagsrum", kind = FactKind.Habit),
            fact("P1", "is", "the news radio"),
        )
        val g = MemoryGraph.build(d)
        val labels = g.nodes.map { it.label }
        assertEquals(listOf("Gabriel", "P1", "Maja", "Whole house", "cozy in Vardagsrum", "the news radio"), labels)
        val p1 = labels.indexOf("P1")
        assertEquals(setOf(labels.indexOf("Gabriel"), labels.indexOf("Maja"), labels.indexOf("the news radio"), labels.indexOf("Whole house")), g.neighbours(p1))
        val pos = MemoryGraph.layout(g)
        assertEquals(g.nodes.size * 2, pos.size)
        assertTrue(pos.all { it in -1.001f..1.001f })
        // The same memory draws the same way every time.
        assertTrue(pos.contentEquals(MemoryGraph.layout(g)))
    }

    @Test fun `editing a fact changes what it says`() = runTest {
        val book = MemoryBook(Files.createTempDirectory("mem").toFile()) { now }
        book.learn(listOf(fact("Maja", "likes", "cold light", maja)))
        val f = book.data.value.facts.single()
        book.edit(f.id, "blue light")
        assertEquals("blue light", book.data.value.facts.single().obj)
    }
}
