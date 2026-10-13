package app.homeydash.agent

import app.homeydash.homey.Device
import app.homeydash.homey.FlowCardAction
import app.homeydash.homey.HomeyFlow
import app.homeydash.homey.HomeyJson
import app.homeydash.homey.Mood
import app.homeydash.homey.Zone
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AgentTest {
    private val zones: Map<String, Zone> = HomeyJson.decodeFromString(
        """{"z-home":{"id":"z-home","name":"Home"},"z-kitchen":{"id":"z-kitchen","name":"Kitchen","parent":"z-home"},"z-hall":{"id":"z-hall","name":"Hall","parent":"z-home"}}""",
    )
    private val devices: Map<String, Device> = HomeyJson.decodeFromString(
        """
        {
          "d-lamp": {"id":"d-lamp","name":"Kitchen lamp","zone":"z-kitchen","class":"light","capabilities":["onoff","dim"],
            "capabilitiesObj":{"onoff":{"id":"onoff","type":"boolean","value":true,"setable":true},
                               "dim":{"id":"dim","type":"number","value":0.4,"min":0,"max":1,"setable":true}}},
          "d-spot": {"id":"d-spot","name":"Spots","zone":"z-kitchen","class":"light","capabilities":["onoff"],
            "capabilitiesObj":{"onoff":{"id":"onoff","type":"boolean","value":true,"setable":true}}},
          "d-lock": {"id":"d-lock","name":"Front door","zone":"z-hall","class":"lock","capabilities":["locked"],
            "capabilitiesObj":{"locked":{"id":"locked","type":"boolean","value":true,"setable":true}}},
          "d-alarm": {"id":"d-alarm","name":"Alarm","zone":"z-hall","class":"homealarm","capabilities":["homealarm_state"],
            "capabilitiesObj":{"homealarm_state":{"id":"homealarm_state","type":"enum","value":"armed","setable":true,
              "values":[{"id":"armed"},{"id":"disarmed"},{"id":"partially_armed"}]}}},
          "d-cam": {"id":"d-cam","name":"Door camera","zone":"z-hall","class":"doorbell","capabilities":["alarm_motion"],
            "capabilitiesObj":{"alarm_motion":{"id":"alarm_motion","type":"boolean","value":false}}},
          "d-hidden": {"id":"d-hidden","name":"Secret plug","zone":"z-kitchen","class":"socket","hidden":true,"capabilities":["onoff"],
            "capabilitiesObj":{"onoff":{"id":"onoff","type":"boolean","value":false,"setable":true}}}
        }
        """.trimIndent(),
    )
    private val moods = mapOf("m1" to Mood("m1", "Dinner", "z-kitchen"))

    private fun view(settings: AgentSettings = AgentSettings(), local: Boolean = true, hidden: Set<String> = emptySet()) =
        HomeView.build(zones, devices, moods, hidden, settings, local)

    private class FakeHome : HomeControl {
        val sets = mutableListOf<Triple<String, String, JsonElement>>()
        val started = mutableListOf<String>()
        override suspend fun setCapability(deviceId: String, capability: String, value: JsonElement) { sets += Triple(deviceId, capability, value) }
        override suspend fun setMood(mood: Mood) {}
        override suspend fun flows() = listOf(HomeyFlow("f1", "Movie night", false), HomeyFlow("f2", "Unlock front door", false))
        override suspend fun triggerFlow(flow: HomeyFlow) { started += flow.name }
        override suspend fun people() = listOf(HomeyPerson("Gabriel", true, false))
        override suspend fun variables() = listOf(LogicVariable("Alarm code", "string", JsonPrimitive("1234")))
        override suspend fun playMusic(deviceId: String, query: String): String? = null
    }

    private class FakeDashboard : DashboardControl {
        var page: String? = null
        var goodnights = 0
        override fun openPage(page: String, roomId: String?): Boolean { this.page = roomId ?: page; return true }
        override fun openDevice(deviceId: String) = true
        override fun goodnightPreview() = "turn off 2 lights."
        override fun goodnight() { goodnights++ }
        override suspend fun weather() = "Sunny, 18 °C"
        override suspend fun calendar(from: LocalDate, days: Int, withLocations: Boolean) =
            listOf(AgentEvent("Dentist", "09:00", "10:00", false, if (withLocations) "Main street 1" else null))
        override fun startTimer(seconds: Int, label: String?) {}
        override fun cancelTimer(label: String?) = false
        override fun timers() = emptyList<String>()
        override fun setTheme(mode: String) = true
    }

    private fun call(name: String, vararg args: Pair<String, Any>) =
        ToolCall("c1", name, JsonObject(args.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) }))

    @Test fun `secret devices and hidden devices stay out by default`() {
        val v = view()
        assertEquals(setOf("Kitchen lamp", "Spots"), v.devices.map { it.name }.toSet())
        assertFalse(v.describe().contains("Front door"))
        assertFalse(v.describe().contains("Secret plug"))
        assertEquals(setOf("z-kitchen"), v.rooms.keys)
    }

    @Test fun `local only switches reach only the local brain`() {
        val settings = AgentSettings(secrets = mapOf(Secret.Locks to Access.LocalOnly, Secret.Cameras to Access.On))
        assertTrue(view(settings, local = true).devices.any { it.name == "Front door" })
        assertFalse(view(settings, local = false).devices.any { it.name == "Front door" })
        assertTrue(view(settings, local = false).devices.any { it.name == "Door camera" })
    }

    @Test fun `aliases are stable and devices can be hidden from the assistant only`() {
        val a = view().devices.associate { it.name to it.alias }
        assertEquals("light_1", a["Kitchen lamp"])
        assertEquals("light_2", a["Spots"])
        assertNull(view(AgentSettings(hiddenDevices = setOf("d-spot"))).devices.firstOrNull { it.name == "Spots" })
        assertTrue(view().describe().contains("""light_1 "Kitchen lamp" light: onoff=true [set true|false] dim=0.4 [set 0..1]"""))
    }

    @Test fun `set_device changes an allowed device and converts values`() = runTest {
        val home = FakeHome()
        val tools = AgentTools(view(), home, FakeDashboard())
        tools.run(call("set_device", "device" to "light_1", "capability" to "dim", "value" to "60%"))
        tools.run(call("set_device", "device" to "Spots", "capability" to "onoff", "value" to "off"))
        assertEquals(Triple("d-lamp", "dim", JsonPrimitive(0.6)), home.sets[0])
        assertEquals(Triple("d-spot", "onoff", JsonPrimitive(false)), home.sets[1])
    }

    @Test fun `unknown and hidden devices are refused without touching Homey`() = runTest {
        val home = FakeHome()
        val tools = AgentTools(view(), home, FakeDashboard())
        val r = tools.run(call("set_device", "device" to "lock_1", "capability" to "locked", "value" to "false"))
        assertTrue(r.text.contains("can't see"))
        tools.run(call("set_device", "device" to "Secret plug", "capability" to "onoff", "value" to "true"))
        assertTrue(home.sets.isEmpty())
    }

    @Test fun `unlocking needs the switch and a tap, and the alarm can't be disarmed`() = runTest {
        val home = FakeHome()
        val noUnlock = AgentSettings(secrets = mapOf(Secret.Locks to Access.On, Secret.Alarm to Access.On))
        val tools = AgentTools(view(noUnlock), home, FakeDashboard())
        val lock = view(noUnlock).devices.first { it.name == "Front door" }.alias
        val alarm = view(noUnlock).devices.first { it.name == "Alarm" }.alias
        assertNull(tools.run(call("set_device", "device" to lock, "capability" to "locked", "value" to "false")).pending)
        assertTrue(tools.run(call("set_device", "device" to alarm, "capability" to "homealarm_state", "value" to "disarmed")).text.contains("never"))
        tools.run(call("set_device", "device" to lock, "capability" to "locked", "value" to "true"))
        assertEquals(listOf(Triple("d-lock", "locked", JsonPrimitive(true) as JsonElement)), home.sets.toList())

        val withUnlock = noUnlock.copy(secrets = noUnlock.secrets + (Secret.Unlock to Access.On))
        val pending = AgentTools(view(withUnlock), home, FakeDashboard()).run(call("set_device", "device" to lock, "capability" to "locked", "value" to "false")).pending
        assertNotNull(pending)
        assertTrue(pending!!.tapOnly)
        assertEquals(1, home.sets.size)
        pending.run()
        assertEquals(Triple("d-lock", "locked", JsonPrimitive(false) as JsonElement), home.sets.last())
    }

    @Test fun `set_room skips secret devices and other rooms`() = runTest {
        val home = FakeHome()
        AgentTools(view(), home, FakeDashboard()).run(call("set_room", "room" to "kitchen", "capability" to "onoff", "value" to "false"))
        assertEquals(setOf("d-lamp", "d-spot"), home.sets.map { it.first }.toSet())
    }

    @Test fun `sensitive flows and goodnight wait for a yes`() = runTest {
        val home = FakeHome()
        val dash = FakeDashboard()
        val tools = AgentTools(view(), home, dash)
        tools.run(call("run_flow", "name" to "movie night"))
        assertEquals(listOf("Movie night"), home.started)
        val unlock = tools.run(call("run_flow", "name" to "Unlock front door"))
        assertNull(unlock.pending)
        assertEquals(listOf("Movie night"), home.started)
        val gn = tools.run(call("goodnight"))
        assertEquals(0, dash.goodnights)
        gn.pending!!.run()
        assertEquals(1, dash.goodnights)
    }

    @Test fun `calendar and presence tools exist only when switched on`() = runTest {
        assertFalse(AgentTools(view(), FakeHome(), FakeDashboard()).specs().any { it.name == "calendar" || it.name == "who_is_home" })
        val settings = AgentSettings(secrets = mapOf(Secret.Calendar to Access.LocalOnly, Secret.Presence to Access.On))
        val local = AgentTools(view(settings, local = true), FakeHome(), FakeDashboard())
        assertTrue(local.specs().any { it.name == "calendar" })
        val r = local.run(call("calendar"))
        assertTrue(r.localOnly)
        assertFalse(r.text.contains("Main street"))
        assertFalse(AgentTools(view(settings, local = false), FakeHome(), FakeDashboard()).specs().any { it.name == "calendar" })
        assertTrue(AgentTools(view(settings, local = false), FakeHome(), FakeDashboard()).specs().any { it.name == "who_is_home" })
    }

    @Test fun `chat responses parse tool calls from OpenAI, Gemini and Ollama`() {
        val openai = ChatClient.parseResponse(
            """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"x","type":"function","function":{"name":"set_device","arguments":"{\"device\":\"light_1\"}"}}]}}]}""",
        )
        assertEquals("light_1", openai.toolCalls.single().arguments["device"]!!.jsonPrimitive.content)
        val ollama = ChatClient.parseResponse(
            """{"choices":[{"message":{"role":"assistant","content":"<think>hmm</think>Klart!","tool_calls":[{"function":{"name":"weather","arguments":{}}}]}}]}""",
        )
        assertEquals("Klart!", ollama.content)
        assertEquals("call_0", ollama.toolCalls.single().id)
    }

    @Test fun `request body sends tools and tool results in OpenAI format`() {
        val body = ChatClient.requestBody(
            "m",
            listOf(ChatMessage("assistant", null, listOf(ToolCall("c1", "weather", JsonObject(emptyMap())))), ChatMessage.tool("c1", "Sunny")),
            AgentTools(view(), FakeHome(), FakeDashboard()).specs(),
        )
        val messages = body["messages"]!!.jsonArray
        assertEquals("{}", messages[0].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        assertEquals("c1", messages[1].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        assertTrue((body["tools"] as JsonArray).size > 5)
    }

    @Test fun `smart mode falls back to the internet when the PC is off and hides local-only turns from it`() = runTest {
        val server = MockWebServer().apply { start() }
        val internet = Connection(Provider.OpenAI, baseUrl = server.url("/v1").toString(), apiKey = "k", model = "m")
        // Port 9 on localhost: nothing listens, so the "PC" is off.
        val local = Connection(Provider.Ollama, baseUrl = "http://127.0.0.1:9/v1", model = "q")
        val settings = AgentSettings(local = local, internet = internet, secrets = mapOf(Secret.Calendar to Access.LocalOnly))
        val agent = Agent(ChatClient(OkHttpClient()))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Hej!"}}]}"""))
        val reply = agent.ask("Hej", settings, { view(settings, it) }, { AgentTools(it, FakeHome(), FakeDashboard()) })
        assertEquals("Hej!", reply.text)
        assertEquals(Provider.OpenAI, reply.brain!!.provider)
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("Kitchen lamp"))
        assertFalse(sent.contains("\"calendar\""))
        server.shutdown()
    }

    @Test fun `a spoken yes runs the pending action, but never a tap-only one`() = runTest {
        val server = MockWebServer().apply { start() }
        val internet = Connection(Provider.OpenAI, baseUrl = server.url("/v1").toString(), apiKey = "k", model = "m")
        val settings = AgentSettings(internet = internet, mode = BrainMode.Internet)
        val dash = FakeDashboard()
        val agent = Agent(ChatClient(OkHttpClient()))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"g","function":{"name":"goodnight","arguments":"{}"}}]}}]}"""))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Ska jag säga godnatt?"}}]}"""))
        val ask = agent.ask("godnatt", settings, { view(settings, it) }, { AgentTools(it, FakeHome(), dash) })
        assertNotNull(ask.pending)
        assertEquals(0, dash.goodnights)
        val yes = agent.ask("Ja", settings, { view(settings, it) }, { AgentTools(it, FakeHome(), dash) })
        assertEquals("Goodnight.", yes.text)
        assertEquals(1, dash.goodnights)
        assertNull(agent.pending)
        server.shutdown()
    }

    @Test fun `yes and no in Swedish and English`() {
        assertEquals(true, Confirmation.parse("Ja tack"))
        assertEquals(true, Confirmation.parse("yes please."))
        assertEquals(false, Confirmation.parse("Nej"))
        assertEquals(false, Confirmation.parse("no"))
        assertNull(Confirmation.parse("tänd lampan i köket"))
        assertNull(Confirmation.parse("japanese food recipes for dinner tonight please"))
    }

    @Test fun `announce prefers a play-URL card with volume`() {
        val cards = HomeyJson.decodeFromString<List<FlowCardAction>>(
            """
            [
              {"id":"homey:device:s1:say_text","title":"Say","args":[{"name":"device","type":"device"},{"name":"text","type":"text"}]},
              {"id":"homey:device:s1:play_url_volume","title":"Play (url) at volume","args":[{"name":"device","type":"device"},{"name":"url","type":"text"},{"name":"volume","type":"range","min":0,"max":1}]},
              {"id":"homey:device:s1:play_favorite","args":[{"name":"favorite","type":"autocomplete"}]}
            ]
            """.trimIndent(),
        )
        val card = Announce.find(cards)!!
        assertEquals(AnnounceCard.Kind.Url, card.kind)
        assertEquals("url", card.arg.name)
        assertEquals(0.4, card.args("http://x/a.wav", 40)["volume"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(AnnounceCard.Kind.Text, Announce.find(cards.take(1))!!.kind)
        assertNull(Announce.find(cards.drop(2)))
    }

    @Test fun `people and variables parse`() {
        val people = StoreHomeControl.parsePeople(HomeyJson.parseToJsonElement("""{"u1":{"name":"Gabriel","present":true,"asleep":false},"u2":{"name":"Anna"}}"""))
        assertEquals(listOf("Anna", "Gabriel"), people.map { it.name })
        assertEquals(true, people[1].present)
        val vars = StoreHomeControl.parseVariables(HomeyJson.parseToJsonElement("""{"v":{"name":"Code","type":"string","value":"1"}}"""))
        assertEquals("Code", vars.single().name)
    }

    @Test fun `roles narrow the switches and guests lose Flows and Goodnight`() = runTest {
        val all = AgentSettings(secrets = Secret.entries.associateWith { Access.On })
        assertEquals(Access.Off, all.forRole(Role.Family).access(Secret.Unlock))
        assertEquals(Access.On, all.forRole(Role.Family).access(Secret.Calendar))
        val guest = all.forRole(Role.Guest)
        assertTrue(guest.secrets.isEmpty())
        val tools = AgentTools(view(guest), FakeHome(), FakeDashboard())
        val names = tools.specs().map { it.name }
        assertFalse("goodnight" in names || "run_flow" in names || "calendar" in names)
        assertTrue("set_device" in names)
        assertEquals("Guests can't do that.", tools.run(call("goodnight")).text)
    }

    @Test fun `who is at the tablet only reaches brains the switch allows`() {
        val agent = Agent(ChatClient(OkHttpClient()))
        val s = AgentSettings(secrets = mapOf(Secret.Faces to Access.LocalOnly))
        val ctx = AskContext(person = "Gabriel")
        assertTrue(agent.systemPrompt(s, view(s, local = true), ctx).contains("Gabriel"))
        assertFalse(agent.systemPrompt(s, view(s, local = false), ctx).contains("Gabriel"))
    }

    @Test fun `faces match the closest enrolled person above the threshold`() {
        val gabriel = KnownPerson("1", "Gabriel", Role.Owner, listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0.9f, 0.1f, 0f)))
        val anna = KnownPerson("2", "Anna", Role.Family, listOf(floatArrayOf(0f, 1f, 0f)))
        assertEquals("Gabriel", FaceMatcher.match(floatArrayOf(0.95f, 0.05f, 0.01f), listOf(gabriel, anna))?.name)
        assertEquals("Anna", FaceMatcher.match(floatArrayOf(0.1f, 0.99f, 0f), listOf(gabriel, anna))?.name)
        assertNull(FaceMatcher.match(floatArrayOf(0f, 0f, 1f), listOf(gabriel, anna)))
    }

    @Test fun `wake name phrases, grammar and vocabulary check`() {
        assertTrue(WakeName.heard("Hey Nova", "Nova"))
        assertTrue(WakeName.heard("nova", " nova! "))
        assertFalse(WakeName.heard("hey there", "Nova"))
        assertEquals("[\"hey nova\",\"hi nova\",\"okay nova\",\"nova\",\"[unk]\"]", WakeName.grammar("Nova"))
        assertEquals(listOf("jarvizz"), WakeName.unknownWords("Jarvizz", setOf("hey", "nova")))
        assertTrue(WakeName.phrases("  ").isEmpty())
    }

    @Test fun `voices match by speaker vector`() {
        val g = KnownPerson("1", "Gabriel", Role.Owner, emptyList(), voice = listOf(floatArrayOf(1f, 0.2f, 0f)))
        assertEquals("Gabriel", FaceMatcher.matchVoice(floatArrayOf(0.9f, 0.3f, 0.1f), listOf(g))?.name)
        assertNull(FaceMatcher.matchVoice(floatArrayOf(0f, 0f, 1f), listOf(g)))
        assertNull(FaceMatcher.match(floatArrayOf(1f, 0.2f, 0f), listOf(g)))
    }

    @Test fun `ask_internet sends only general questions and stops after private results`() = runTest {
        val asked = mutableListOf<String>()
        val web = WebLookup { q, _ -> asked += q; "Open until 20." }
        val settings = AgentSettings(secrets = mapOf(Secret.Calendar to Access.LocalOnly))
        assertFalse(AgentTools(view(settings), FakeHome(), FakeDashboard()).specs().any { it.name == "ask_internet" })
        val tools = AgentTools(view(settings), FakeHome(), FakeDashboard(), web = web)
        assertTrue(tools.specs().any { it.name == "ask_internet" })
        assertEquals("Open until 20.", tools.run(call("ask_internet", "question" to "When does ICA Maxi close today?")).text)
        assertTrue(tools.run(call("ask_internet", "question" to "Is light_1 a good lamp?")).text.startsWith("Leave"))
        tools.run(call("calendar"))
        assertTrue(tools.privateContext)
        assertTrue(tools.run(call("ask_internet", "question" to "Who is the dentist?")).text.contains("private"))
        assertEquals(listOf("When does ICA Maxi close today?"), asked)
    }

    @Test fun `web search is off in local only mode`() {
        val gemini = Connection(Provider.Gemini, apiKey = "k")
        assertEquals(gemini, AgentSettings(internet = gemini, mode = BrainMode.Smart).webSearch())
        assertNull(AgentSettings(internet = gemini, mode = BrainMode.Local).webSearch())
        assertNull(AgentSettings(internet = Connection(Provider.Gemini), mode = BrainMode.Smart).webSearch())
    }

    @Test fun `gemini web search uses the native API with google search`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"It closes "},{"text":"at 20."}]}}]}"""))
        server.start()
        val c = Connection(Provider.Gemini, baseUrl = server.url("/v1beta/openai").toString(), apiKey = "key", model = "gemini-2.5-flash")
        val answer = WebSearch(OkHttpClient()).lookup(c)!!.answer("When does ICA close?", Language.Auto)
        assertEquals("It closes at 20.", answer)
        val request = server.takeRequest()
        assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", request.path)
        assertEquals("key", request.getHeader("x-goog-api-key"))
        assertTrue(request.body.readUtf8().contains("google_search"))
        server.shutdown()
    }

    @Test fun `openai web search reads the responses output`() {
        val body = """{"output":[{"type":"web_search_call"},{"type":"message","content":[{"type":"output_text","text":"Sunny tomorrow."}]}]}"""
        assertEquals("Sunny tomorrow.", WebSearch.parseOpenAi(body))
        assertTrue(WebSearch.openAiBody("gpt-4.1-mini", "q").toString().contains("web_search"))
    }
}
