package app.homeydash.agent

import app.homeydash.homey.Device
import app.homeydash.homey.FlowCardAction
import app.homeydash.homey.HomeyFlow
import app.homeydash.homey.HomeyJson
import app.homeydash.homey.Mood
import app.homeydash.homey.Zone
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import app.homeydash.homey.FlowCardArg
import app.homeydash.homey.AutocompleteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.sin

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
        /** What Homey says afterwards, per device; a device not listed changed as asked. */
        var after = mapOf<String, JsonElement>()
        override suspend fun readBack(deviceId: String, capability: String): JsonElement? = after[deviceId]
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

    @Test fun `secret devices stay out, dashboard-hidden ones only when the owner says`() {
        val v = view()
        assertEquals(setOf("Kitchen lamp", "Spots", "Secret plug"), v.devices.map { it.name }.toSet())
        assertFalse(v.describe().contains("Front door"))
        assertEquals(setOf("z-home", "z-kitchen"), v.rooms.keys)
        val strict = view(AgentSettings(seeHiddenDevices = false), hidden = setOf("d-spot"))
        assertEquals(setOf("Kitchen lamp"), strict.devices.map { it.name }.toSet())
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
        val tools = AgentTools(view(AgentSettings(seeHiddenDevices = false)), home, FakeDashboard())
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

    private val media: Map<String, Device> = HomeyJson.decodeFromString(
        """
        {
          "d-atv": {"id":"d-atv","name":"Vardagsrum","zone":"z-kitchen","class":"tv","driverUri":"homey:app:com.apple.tv","capabilities":["onoff"],
            "capabilitiesObj":{"onoff":{"id":"onoff","type":"boolean","value":false,"setable":true}}},
          "d-sonos": {"id":"d-sonos","name":"Kök","zone":"z-kitchen","class":"speaker","driverUri":"homey:app:com.sonos","capabilities":["speaker_playing"],
            "capabilitiesObj":{"speaker_playing":{"id":"speaker_playing","type":"boolean","value":false,"setable":true}}},
          "d-lamp": {"id":"d-lamp","name":"Taklampa","zone":"z-kitchen","class":"light","capabilities":["onoff"],
            "capabilitiesObj":{"onoff":{"id":"onoff","type":"boolean","value":false,"setable":true}}}
        }
        """.trimIndent(),
    )

    private class MediaHome : HomeControl {
        val ran = mutableListOf<Pair<String, JsonObject>>()
        private val channel = FlowCardAction(
            "homey:device:d-atv:change_channel", title = "Change channel",
            args = listOf(FlowCardArg("device", "device"), FlowCardArg("channel", "autocomplete")),
        )
        private val unlock = FlowCardAction("homey:device:d-atv:unlock_parental", title = "Unlock")
        override suspend fun setCapability(deviceId: String, capability: String, value: JsonElement) {}
        override suspend fun setMood(mood: Mood) {}
        override suspend fun flows() = emptyList<HomeyFlow>()
        override suspend fun triggerFlow(flow: HomeyFlow) {}
        override suspend fun people() = emptyList<HomeyPerson>()
        override suspend fun variables() = emptyList<LogicVariable>()
        override suspend fun playMusic(deviceId: String, query: String): String? = null
        override fun deviceActions(deviceId: String) = if (deviceId == "d-atv") listOf(channel, unlock) else emptyList()
        override suspend fun choices(card: FlowCardAction, arg: FlowCardArg, query: String) =
            listOf("SVT1", "SVT2", "TV4").map { AutocompleteItem(it, null, null, JsonObject(mapOf("name" to JsonPrimitive(it)))) }
        override suspend fun runAction(card: FlowCardAction, args: JsonObject) { ran += card.cardId to args }
    }

    @Test fun `devices are found by their app or type, not only their name`() = runTest {
        val v = HomeView.build(zones, media, emptyMap(), emptySet(), AgentSettings(), true)
        assertEquals("Vardagsrum", v.device("the Apple TV")?.name)
        assertEquals("Vardagsrum", v.device("tv:n")?.name)
        assertEquals("Kök", v.device("sonos")?.name)
        assertEquals("Taklampa", v.device("taklampan")?.name)
        assertTrue(v.describe().contains("\"Vardagsrum\" tv (apple tv)"))
        assertEquals("Vardagsrum", QuickCommand.parse("start the apple tv", v)?.device?.name)

        val home = MediaHome()
        val tools = AgentTools(v, home, FakeDashboard())
        val list = tools.run(call("device_actions", "device" to "apple tv")).text
        assertTrue(list.contains("change_channel"))
        assertFalse(list.contains("unlock"))
        val done = tools.run(call("run_device_action", "device" to "Vardagsrum", "action" to "byt kanal change channel", "value" to "tv4")).text
        assertEquals("Done: Change channel (TV4) on Vardagsrum.", done)
        assertEquals("TV4", home.ran.single().second["channel"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(tools.run(call("run_device_action", "device" to "Vardagsrum", "action" to "unlock_parental")).text.contains("no action"))
    }

    @Test fun `the endpointer hears a question start and stop`() {
        val e = Endpointer(16000, silenceMs = 500, noSpeechMs = 3000, patienceMs = 1500)
        val quiet = ShortArray(1600) { ((it % 7) - 3).toShort() }
        val loud = ShortArray(1600) { if (it % 2 == 0) 4000 else -4000 }
        repeat(5) { assertEquals(Endpointer.State.Waiting, e.feed(quiet, quiet.size)) }
        repeat(10) { e.feed(loud, loud.size) }
        assertEquals(Endpointer.State.Speaking, e.state)
        repeat(6) { e.feed(quiet, quiet.size) }
        assertEquals(Endpointer.State.Paused, e.state)
        assertEquals(1, e.pauses)
        // Talking again after the pause carries on the same question.
        repeat(3) { e.feed(loud, loud.size) }
        assertEquals(Endpointer.State.Speaking, e.state)
        repeat(6) { e.feed(quiet, quiet.size) }
        assertEquals(Endpointer.State.Paused, e.state)
        assertEquals(2, e.pauses)
        repeat(10) { e.feed(quiet, quiet.size) }
        assertEquals(Endpointer.State.Done, e.state)

        val silent = Endpointer(16000, noSpeechMs = 1000)
        repeat(11) { silent.feed(quiet, quiet.size) }
        assertEquals(Endpointer.State.NoSpeech, silent.state)
    }

    @Test fun `whisper's made-up words for silence count as nothing said`() {
        assertEquals("", Transcriber.cleanUp(" Tack för att du tittade! "))
        assertEquals("", Transcriber.cleanUp("[BLANK_AUDIO]"))
        assertEquals("Tänd taklampan.", Transcriber.cleanUp(" Tänd taklampan. "))
        assertEquals("http://192.168.1.2:8080", Transcriber.guessUrl(Connection(Provider.Ollama)))
    }

    @Test fun `questions are heard by whisper, then openai when whisper is off`() = runTest {
        val whisper = MockWebServer().apply { start() }
        val openai = MockWebServer().apply { start() }
        whisper.enqueue(MockResponse().setBody("""{"text":" Sätt på taklampan."}"""))
        openai.enqueue(MockResponse().setBody("""{"text":"Hej"}"""))
        val t = Transcriber(OkHttpClient(), openai.url("/v1").toString().trimEnd('/'))
        val both = AgentSettings(useWhisper = true, whisperUrl = whisper.url("/").toString(), useOpenAiSpeech = true, openAiSpeechKey = "k")
        val heard = t.transcribe(both, ByteArray(100), "sv", "taklampan")
        assertEquals(Heard("Sätt på taklampan.", Hearer.Whisper), heard)
        val sent = whisper.takeRequest().body.readUtf8()
        assertTrue(sent.contains("taklampan") && sent.contains("sv"))

        whisper.shutdown()
        val fallback = t.transcribe(both, ByteArray(100), null, "")
        assertEquals(Hearer.OpenAI, fallback.by)
        val request = openai.takeRequest()
        assertEquals("/v1/audio/transcriptions", request.path)
        assertEquals("Bearer k", request.getHeader("Authorization"))
        openai.shutdown()
    }

    @Test fun `voice fingerprints add up to a limit, newest kept`() {
        val first = List(FaceMatcher.MAX_VOICE) { floatArrayOf(1f, it.toFloat()) }
        val more = FaceMatcher.withVoice(first, listOf(floatArrayOf(0f, 2f)))
        assertEquals(FaceMatcher.MAX_VOICE, more.size)
        assertEquals(1f, more.last()[1], 1e-6f)
        val person = KnownPerson("p", "Gabriel", Role.Owner, emptyList(), voice = listOf(floatArrayOf(1f, 0f)))
        assertEquals(1f, FaceMatcher.voiceScore(floatArrayOf(2f, 0f), person), 1e-6f)
    }

    @Test fun `answers name devices instead of their aliases`() {
        val v = view()
        val d = v.devices.first()
        val spaced = d.alias.replace('_', ' ').replaceFirstChar { it.uppercase() }
        assertEquals("${d.name} är tänd.", v.named("${d.alias} är tänd."))
        assertEquals("${d.name} is on.", v.named("$spaced is on."))
        assertEquals("light_999 stays", v.named("light_999 stays"))
    }

    @Test fun `local requests turn thinking off every way Ollama understands`() {
        val body = ChatClient.requestBody("q", listOf(ChatMessage("system", "Be brief."), ChatMessage.user("Hej")), emptyList(), noThinking = true)
        assertEquals("none", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("false", body["chat_template_kwargs"]!!.jsonObject["enable_thinking"]!!.jsonPrimitive.content)
        assertEquals("false", body["think"]!!.jsonPrimitive.content)
        assertTrue(body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content.endsWith("/no_think"))
        assertNull(ChatClient.requestBody("m", listOf(ChatMessage("system", "x")), emptyList())["think"])
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
        assertEquals("[\"hey nova\",\"hi nova\",\"okay nova\",\"nova\",\"hello\",\"[unk]\"]", WakeName.grammar("Nova", setOf("hello", "nova")))
        // Decoys never include the name itself.
        assertEquals(1, Regex("\"max\"").findAll(WakeName.grammar("Max")).count())
        assertTrue(WakeName.grammar("Nova").contains("\"noah\""))
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

    @Test fun `voices round trip as wav, keep their length and are measured for the face`() {
        val rate = 16000
        // Half a second of a 200 Hz tone, then half a second of silence.
        val samples = ShortArray(rate) { i -> if (i < rate / 2) (sin(2 * Math.PI * 200 * i / rate) * 12000).toInt().toShort() else 0 }
        val pcm = Pcm(rate, samples)
        val back = VoiceFx.readWav(VoiceFx.writeWav(pcm))!!
        assertEquals(rate, back.sampleRate)
        assertEquals(samples.toList(), back.samples.toList())
        VoiceStyle.entries.forEach { style ->
            val out = VoiceFx.apply(style, pcm)
            assertEquals(samples.size, out.samples.size)
            assertTrue(out.samples.maxOf { kotlin.math.abs(it.toInt()) } <= 32767)
        }
        val track = VoiceFx.analyse(pcm)
        assertTrue(track.durationMs in 990..1030)
        assertTrue(track.at(200)[0] > 0.8f)
        assertEquals(0f, track.at(800)[0])
        assertEquals(0f, track.at(5000)[0])
        assertNull(VoiceFx.readWav(ByteArray(10)))
    }

    @Test fun `TARS's chat about his settings isn't taken for a promise`() {
        assertFalse(Agent.PROMISE.containsMatchIn("I can try, but I'm still a robot with a 90% honesty setting."))
        assertTrue(Agent.PROMISE.containsMatchIn("Setting the living room to Myskväll."))
        assertTrue(Agent.PROMISE.containsMatchIn("Dimming the lights now."))
        assertTrue(Agent.COMMAND.containsMatchIn("Can you make it cozy in the living room?"))
        val rules = Agent(ChatClient(OkHttpClient())).rules(AgentSettings(persona = Persona.Tars))
        assertTrue(rules.contains("call calendar with start_date"))
        assertTrue(rules.contains("leave the weather out unless"))
    }

    @Test fun `TARS always answers in English and jokes about other languages`() {
        val agent = Agent(ChatClient(OkHttpClient()))
        val rules = agent.rules(AgentSettings(persona = Persona.Tars, language = Language.Swedish))
        assertTrue(rules.contains("Always answer in English, even when the user speaks Swedish"))
        assertFalse(rules.contains("Always answer in Swedish"))
        assertTrue(rules.contains("clever, deadpan line"))
        assertEquals(Language.English, AgentSettings(persona = Persona.Tars, language = Language.Auto).answers)
        assertEquals(Language.Swedish, AgentSettings(language = Language.Swedish).answers)
    }

    @Test fun `the note gives the day, date and week number`() {
        val t = java.time.ZonedDateTime.of(2026, 10, 16, 16, 45, 0, 0, java.time.ZoneId.of("Europe/Stockholm"))
        assertEquals("Friday 16 October 2026 (2026-10-16), week 42, 16:45", nowLine(t))
        // New Year's Day 2027 is a Friday, still in 2026's week 53.
        assertTrue(nowLine(t.withYear(2027).withMonth(1).withDayOfMonth(1)).contains("week 53"))
    }

    @Test fun `TARS gets his personality and humor setting in the rules`() {
        val agent = Agent(ChatClient(OkHttpClient()))
        assertFalse(agent.rules(AgentSettings()).contains("TARS"))
        val tars = agent.rules(AgentSettings(persona = Persona.Tars, humor = 60))
        assertTrue(tars.contains("you are TARS"))
        assertTrue(tars.contains("humor setting is 60 %"))
        assertTrue(tars.contains("Most answers get a sarcastic aside"))
        assertTrue(tars.contains("noticeably sarcastic"))
        assertTrue(agent.rules(AgentSettings(persona = Persona.Tars, humor = 0)).contains("No jokes"))
    }

    @Test fun `TARS is one tap, and Nova undoes only what TARS changed`() {
        val tars = AgentSettings(voice = VoiceStyle.Warm).asCharacter(Persona.Tars)
        assertEquals(Persona.Tars, tars.persona)
        assertEquals(VoiceStyle.Tars, tars.voice)
        assertEquals(WakeSound.Huh, tars.wakeSound)
        assertEquals("TARS", tars.wakeName)
        val nova = tars.asCharacter(Persona.Nova)
        assertEquals(VoiceStyle.Natural, nova.voice)
        assertEquals(WakeSound.Hum, nova.wakeSound)
        assertEquals("Nova", nova.wakeName)
        assertEquals("Kim", AgentSettings(wakeName = "Kim").asCharacter(Persona.Nova).wakeName)
    }

    @Test fun `a Piper voice becomes sherpa's tokens and metadata`() {
        val cfg = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"audio":{"sample_rate":22050},"espeak":{"voice":"en"},"num_speakers":8,"speaker_id_map":{"anger":0,"neutral":5},""" +
                """"phoneme_id_map":{"_":[0],"^":[1],"\n":[9],"a":[14]}}""",
        ).jsonObject
        assertEquals("_ 0\n^ 1\na 14\n", PiperModel.tokens(cfg))
        assertEquals(5, PiperModel.speakerId(cfg, "neutral"))
        val meta = PiperModel.metadata(cfg, espeakVoice = "sv")
        assertEquals("sv", meta["voice"])
        assertEquals("Swedish", meta["language"])
        assertEquals("8", meta["n_speakers"])
        // Field 14 (0x72), an entry with key "a" (0x0A) and value "b" (0x12).
        assertEquals(listOf(0x72, 6, 0x0A, 1, 'a'.code, 0x12, 1, 'b'.code), PiperModel.metadataBytes(mapOf("a" to "b")).map { it.toInt() })
    }

    @Test fun `the note tells TARS how the tablet's battery is`() {
        val agent = Agent(ChatClient(OkHttpClient()))
        val view = HomeView.build(emptyMap(), emptyMap(), emptyMap(), emptySet(), AgentSettings(), local = true)
        assertTrue(agent.situation(AgentSettings(), view, AskContext(tabletBattery = "12 %, not charging")).contains("battery 12 %, not charging"))
        assertTrue(agent.rules(AgentSettings(persona = Persona.Tars)).contains("gloomy"))
    }

    @Test
    fun `thanks and stop end a conversation, questions don't`() {
        assertTrue(FollowUp.ends("Thanks!"))
        assertTrue(FollowUp.ends("Tack så mycket"))
        assertTrue(FollowUp.ends("ok stop"))
        assertTrue(FollowUp.ends("that's all, Nova", "Nova"))
        assertTrue(FollowUp.ends("Hej då"))
        assertFalse(FollowUp.ends("stop the music"))
        assertFalse(FollowUp.ends("thanks, turn off the lights"))
        assertFalse(FollowUp.ends(""))
        assertTrue(FollowUp.ends("Avsluta"))
        assertTrue(FollowUp.ends("tack då"))
        assertTrue(FollowUp.ends("okej tack Nova", "Nova"))
        assertTrue(FollowUp.ends("stopp nu"))
        assertTrue(FollowUp.ends("tack för hjälpen"))
        assertTrue(FollowUp.ends("det var allt tack"))
        assertFalse(FollowUp.ends("stoppa musiken"))
        assertFalse(FollowUp.ends("tack, släck lampan"))
        assertFalse(FollowUp.ends("då förstår jag"))
    }

    @Test
    fun `mic sensitivity boosts gain and lowers the bar`() {
        assertEquals(1f, MicSensitivity.gain(50), 1e-4f)
        assertEquals(4f, MicSensitivity.gain(100), 1e-4f)
        assertEquals(0.25f, MicSensitivity.gain(0), 1e-4f)
        assertEquals(0.675, MicSensitivity.minConfidence(50), 1e-9)
        assertTrue(MicSensitivity.minConfidence(100) < MicSensitivity.minConfidence(0))
        assertEquals(4f, MicSensitivity.gain(250), 1e-4f)
    }

    @Test fun `names match loosely in Swedish and English`() {
        assertTrue(NameMatch.score("lampan i köket", "Kök lampa", "Kök") > 1.0)
        assertTrue(NameMatch.score("kökslampan", "Kök lampa") >= 1.0)
        assertTrue(NameMatch.score("taklampan i hallen", "Taklampa", "Hall") > NameMatch.score("taklampan i hallen", "Taklampa", "Kök"))
        assertEquals(0.0, NameMatch.score("tv", "Kök lampa", "Kök"), 0.0)
        assertTrue(NameMatch.sameWord("vardagsrummet", "vardagsrum"))
        assertTrue(NameMatch.sameWord("lampa", "lamoa"))
        assertFalse(NameMatch.sameWord("hall", "bad"))
    }

    @Test fun `devices, rooms and capabilities are found from loose words`() = runTest {
        val v = view()
        assertEquals("Kitchen lamp", v.device("the kitchen lamp")?.name)
        assertEquals("Kitchen lamp", v.device("LIGHT_1")?.name)
        assertEquals("z-kitchen", v.room("the kitchen"))
        assertEquals("Dinner", v.mood("dinner mood")?.mood?.name)
        val home = FakeHome()
        val tools = AgentTools(v, home, FakeDashboard())
        tools.run(call("set_device", "device" to "kitchen lamp", "capability" to "brightness", "value" to "30"))
        assertEquals(Triple("d-lamp", "dim", JsonPrimitive(0.3)), home.sets.single())
        // Unclear names come back with suggestions instead of a guess.
        val r = tools.run(call("set_device", "device" to "kitchen spot lamp", "capability" to "onoff", "value" to "on"))
        assertTrue(r.text, r.text.contains("light_1") && r.text.contains("light_2"))
        assertEquals(1, home.sets.size)
        // Room-wide changes leave dashboard-hidden devices alone unless their class is named.
        home.sets.clear()
        tools.run(call("set_room", "room" to "kitchen", "capability" to "on", "value" to "false", "device_class" to "sockets"))
        assertEquals(listOf("d-hidden"), home.sets.map { it.first })
    }

    @Test fun `talk not meant for the assistant is ignored in follow-ups`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"[ignore]"}}]}"""))
        server.start()
        val agent = Agent(ChatClient(OkHttpClient()))
        val settings = AgentSettings(local = Connection(Provider.Ollama, server.url("/v1").toString(), "", "m"), mode = BrainMode.Local)
        val reply = agent.ask("ja men jag ringer dig sen", settings, { view(settings, it) }, { AgentTools(it, FakeHome(), FakeDashboard()) }, AskContext(followUp = true))
        assertTrue(reply.ignored)
        assertTrue(server.takeRequest().body.readUtf8().contains("reply with exactly [ignore]"))
        server.shutdown()
    }

    @Test fun `the rules and devices stay the same and the warm-up reads them once`() = runTest {
        val server = MockWebServer()
        repeat(3) { server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Okej."}}]}""")) }
        server.start()
        var minute = 0L
        val agent = Agent(ChatClient(OkHttpClient())) { ZonedDateTime.of(2026, 10, 14, 12, 0, 0, 0, ZoneId.of("UTC")).plusMinutes(minute) }
        val settings = AgentSettings(local = Connection(Provider.Ollama, server.url("/v1").toString(), "", "m"), mode = BrainMode.Local)
        fun sent(): List<JsonObject> = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["messages"]!!.jsonArray.map { it.jsonObject }
        fun JsonObject.text() = this["content"]!!.jsonPrimitive.content
        suspend fun warm() = agent.warm(settings, { view(settings, it) }, { AgentTools(it, FakeHome(), FakeDashboard()) }, AskContext(person = "Gabriel"))

        warm()
        val warm = sent()
        // Unchanged since last time: nothing is sent, so a question never waits behind it.
        warm()
        agent.ask("vad är klockan", settings, { view(settings, it) }, { AgentTools(it, FakeHome(), FakeDashboard()) }, AskContext(person = "Gabriel"))
        val first = sent()
        // The warm-up is the start of the question, word for word: the rules and the devices without their values.
        assertEquals(1, warm.size)
        assertEquals(warm[0], first[0])
        assertTrue(first[0].text().contains("""light_1 "Kitchen lamp" light: onoff dim
"""))
        assertFalse(first[0].text().contains("dim=0.4"))
        // What is on now comes with the question.
        assertTrue(first[1].text(), first[1].text().contains("""light_1 "Kitchen lamp": on dim=0.4"""))
        assertTrue(first[1].text().endsWith("The user said: vad är klockan"))
        minute = 7
        agent.ask("tack", settings, { view(settings, it) }, { AgentTools(it, FakeHome(), FakeDashboard()) })
        val second = sent()
        // The clock and the person live with the question, so the start doesn't change.
        assertEquals(first[0], second[0])
        assertFalse(second[0].text().contains("12:0") || second[0].text().contains("Gabriel"))
        assertEquals("vad är klockan", second[1].text())
        assertTrue(second.last().text().contains("12:07"))
        assertEquals(3, server.requestCount)
        server.shutdown()
    }

    @Test fun `a change that didn't happen isn't called done`() = runTest {
        val home = FakeHome()
        home.after = mapOf("d-lamp" to JsonPrimitive(true))
        val tools = AgentTools(view(), home, FakeDashboard())
        val r = tools.run(call("set_device", "device" to "light_1", "capability" to "onoff", "value" to "false"))
        assertTrue(r.text, r.text.startsWith(QuickCommand.NOT_CHANGED_PREFIX))
        // Without the AI too, and in Swedish.
        val reply = Agent(ChatClient(OkHttpClient())).ask("släck kitchen lamp", AgentSettings(), { view() }, { AgentTools(it, home, FakeDashboard()) })
        assertTrue(reply.text, reply.text.contains("svarar inte") && reply.error)
        // A lamp Homey can't reach isn't even tried.
        val offline = devices + ("d-lamp" to devices.getValue("d-lamp").copy(available = false))
        val v = HomeView.build(zones, offline, moods, emptySet(), AgentSettings(), true)
        assertTrue(v.describe().contains("UNREACHABLE"))
        home.sets.clear()
        val r2 = AgentTools(v, home, FakeDashboard()).run(call("set_device", "device" to "light_1", "capability" to "onoff", "value" to "false"))
        assertTrue(r2.text.startsWith(QuickCommand.UNREACHABLE_PREFIX))
        assertTrue(home.sets.isEmpty())
        // A whole room: the lamps that didn't change are named.
        home.after = mapOf("d-spot" to JsonPrimitive(true))
        val room = AgentTools(view(), home, FakeDashboard()).run(call("set_room", "room" to "kitchen", "capability" to "onoff", "value" to "false", "device_class" to "light"))
        assertTrue(room.text, room.text.startsWith("Changed 1: Kitchen lamp.") && room.text.contains("Spots still report"))
        assertTrue(AgentTools.same(JsonPrimitive(0.31), JsonPrimitive(0.3)))
        assertFalse(AgentTools.same(JsonPrimitive(0.0), JsonPrimitive(0.3)))
    }

    @Test fun `saying it's done without a tool is never passed on`() = runTest {
        val server = MockWebServer()
        repeat(2) { server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Taklampan är tänd."}}]}""")) }
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Ja, den är tänd."}}]}"""))
        server.start()
        val home = FakeHome()
        val settings = AgentSettings(local = Connection(Provider.Ollama, server.url("/v1").toString(), "", "m"), mode = BrainMode.Local)
        val agent = Agent(ChatClient(OkHttpClient()))
        val reply = agent.ask("kan du göra så att det blir ljust i taket tack, tänd", settings, { view(settings, it) }, { AgentTools(it, home, FakeDashboard()) })
        assertTrue(reply.text, reply.text.startsWith("Förlåt") && reply.error)
        assertEquals(2, server.requestCount)
        // Asking how things are is answered as is, without a nudge.
        assertEquals("Ja, den är tänd.", agent.ask("är taklampan tänd?", settings, { view(settings, it) }, { AgentTools(it, home, FakeDashboard()) }).text)
        assertEquals(3, server.requestCount)
        server.shutdown()
    }

    @Test fun `a failed tool call followed by "done" is never passed on`() = runTest {
        val server = MockWebServer()
        val bad = """{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"a","type":"function","function":{"name":"set_device","arguments":"{\"device\":\"light_1\",\"capability\":\"colour_of_sky\",\"value\":\"true\"}"}}]}}]}"""
        server.enqueue(MockResponse().setBody(bad))
        repeat(2) { server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Jag har tänt taklampan."}}]}""")) }
        server.start()
        val home = FakeHome()
        val settings = AgentSettings(local = Connection(Provider.Ollama, server.url("/v1").toString(), "", "m"), mode = BrainMode.Local)
        val reply = Agent(ChatClient(OkHttpClient())).ask("kan du tända lite mysbelysning tack", settings, { view(settings, it) }, { AgentTools(it, home, FakeDashboard()) })
        assertTrue(reply.text, reply.text.startsWith("Förlåt") && reply.error)
        assertTrue(home.sets.isEmpty())
        assertEquals(JsonPrimitive(true), AgentTools.coerce(view().device("light_1")!!.capability("onoff")!!, JsonPrimitive("tänd")))
        server.shutdown()
    }

    @Test fun `a whole room includes dimmers and the rooms inside it`() = runTest {
        val z: Map<String, Zone> = HomeyJson.decodeFromString(
            """{"z-home":{"id":"z-home","name":"Hem"},"z-living":{"id":"z-living","name":"Vardagsrum","parent":"z-home"},"z-sofa":{"id":"z-sofa","name":"Soffhörna","parent":"z-living"}}""",
        )
        val d: Map<String, Device> = HomeyJson.decodeFromString(
            """{"d-dim":{"id":"d-dim","name":"Golvlampa","zone":"z-sofa","class":"other","capabilities":["onoff","dim"],
                "capabilitiesObj":{"onoff":{"id":"onoff","type":"boolean","value":false,"setable":true},"dim":{"id":"dim","type":"number","value":0.5,"min":0,"max":1,"setable":true}}}}""",
        )
        val v = HomeView.build(z, d, emptyMap(), emptySet(), AgentSettings(), true)
        val a = QuickCommand.parse("Slå på alla lampor i vardagsrummet", v)!!
        assertEquals("z-living", a.roomId)
        val home = FakeHome()
        val r = AgentTools(v, home, FakeDashboard()).run(QuickCommand.call(a))
        assertTrue(r.text, r.text.startsWith("Changed 1"))
        assertEquals(Triple("d-dim", "onoff", JsonPrimitive(true)), home.sets.single())
    }

    @Test fun `the owner's class, other names and groups reach the assistant`() = runTest {
        val z: Map<String, Zone> = HomeyJson.decodeFromString("""{"z-living":{"id":"z-living","name":"Vardagsrum"}}""")
        val raw: Map<String, Device> = HomeyJson.decodeFromString(
            """{"d-frame":{"id":"d-frame","name":"Samsung QE55","zone":"z-living","class":"tv","capabilities":["onoff","dim"],
                  "capabilitiesObj":{"onoff":{"id":"onoff","type":"boolean","value":false,"setable":true},"dim":{"id":"dim","type":"number","value":0.5,"min":0,"max":1,"setable":true}}},
                "d-ps":{"id":"d-ps","name":"PS5","zone":"z-living","class":"socket","virtualClass":"light","capabilities":["onoff"],
                  "capabilitiesObj":{"onoff":{"id":"onoff","type":"boolean","value":false,"setable":true}}},
                "d-a":{"id":"d-a","name":"Spot 1","zone":"z-living","class":"light","capabilities":["onoff"],
                  "capabilitiesObj":{"onoff":{"id":"onoff","type":"boolean","value":false,"setable":true}}},
                "d-b":{"id":"d-b","name":"Spot 2","zone":"z-living","class":"light","capabilities":["onoff"],
                  "capabilitiesObj":{"onoff":{"id":"onoff","type":"boolean","value":false,"setable":true}}}}""",
        )
        // A TV with a brightness setting isn't a lamp, even before the owner says anything.
        assertFalse(app.homeydash.homey.DashboardLayout.isSwitchableLight(raw.getValue("d-frame")))
        // The owner says the plug Homey calls a light is something else, names the TV and groups the spots.
        val noted = raw.mapValues { (id, d) ->
            when (id) {
                "d-ps" -> d.copy(classOverride = "other")
                "d-frame" -> d.copy(nicknames = listOf("The Frame"))
                else -> d.copy(userGroup = "Taklampor")
            }
        }
        val v = HomeView.build(z, noted, emptyMap(), emptySet(), AgentSettings(), true)
        assertEquals(setOf("Spot 1", "Spot 2"), v.devices.filter { it.deviceClass == "light" }.map { it.name }.toSet())
        assertEquals("Samsung QE55", v.device("the frame")?.name)
        assertTrue(v.describe(), v.describe().contains("also called \"The Frame\"") && v.describe().contains("group \"Taklampor\""))
        val a = QuickCommand.parse("tänd taklamporna", v)!!
        assertEquals("Taklampor", a.group)
        val home = FakeHome()
        val r = AgentTools(v, home, FakeDashboard()).run(QuickCommand.call(a))
        assertTrue(r.text, r.text.startsWith("Changed 2"))
        assertEquals(setOf("d-a", "d-b"), home.sets.map { it.first }.toSet())
        assertEquals("Taklampor är tända.", QuickCommand.done(a, v))
    }

    @Test fun `tool calls written as text are still run`() {
        val (calls, rest) = ChatClient.callsInText("""Okej. <tool_call>{"name":"set_device","arguments":{"device":"light_1","capability":"onoff","value":"true"}}</tool_call>""")
        assertEquals("set_device", calls.single().name)
        assertEquals("light_1", (calls.single().arguments["device"] as JsonPrimitive).content)
        assertEquals("Okej.", rest)
        assertEquals("set_room", ChatClient.callsInText("""{"name":"set_room","arguments":"{\"room\":\"kök\"}"}""").first.single().name)
        assertTrue(ChatClient.callsInText("Lampan är tänd.").first.isEmpty())
        val xml = ChatClient.callsInText("Jag tänder den.\n<tool_call>\n<function=set_device>\n<parameter=device>\nlight_1\n</parameter>\n<parameter=value>\ntrue\n</parameter>\n</function>\n</tool_call>")
        assertEquals("set_device", xml.first.single().name)
        assertEquals("light_1", (xml.first.single().arguments["device"] as JsonPrimitive).content)
        assertEquals("Jag tänder den.", xml.second)
        val lines = ChatClient.callsInText("```\nCALL set_device {\"device\": \"light_1\", \"value\": \"{on}\"}\nCALL weather\n```\nKlart.")
        assertEquals(listOf("set_device", "weather"), lines.first.map { it.name })
        assertEquals("{on}", (lines.first[0].arguments["value"] as JsonPrimitive).content)
        assertEquals("Klart.", lines.second)
    }

    @Test fun `a promise without a tool call gets one nudge`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"I'm going to turn on the kitchen lamp."}}]}"""))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"a","type":"function","function":{"name":"set_device","arguments":"{\"device\":\"light_1\",\"capability\":\"onoff\",\"value\":\"true\"}"}}]}}]}"""))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Done."}}]}"""))
        server.start()
        val home = FakeHome()
        val settings = AgentSettings(local = Connection(Provider.Ollama, server.url("/v1").toString(), "", "m"), mode = BrainMode.Local)
        val reply = Agent(ChatClient(OkHttpClient())).ask("it's dark by the stove", settings, { view(settings, it) }, { AgentTools(it, home, FakeDashboard()) })
        assertEquals("Done.", reply.text)
        assertEquals(Triple("d-lamp", "onoff", JsonPrimitive(true)), home.sets.single())
        server.shutdown()
    }

    @Test fun `plain on, off and dim commands run without the AI`() = runTest {
        val v = view()
        val lamp = QuickCommand.parse("Tänd kitchen lamp!", v)!!
        assertEquals("Kitchen lamp", lamp.device?.name)
        assertEquals(true, lamp.on)
        assertTrue(lamp.swedish)
        assertEquals(0.3, QuickCommand.parse("sätt kitchen lamp till 30 %", v)!!.dim!!, 1e-9)
        assertEquals(false, QuickCommand.parse("turn the spots off", v)!!.on)
        val room = QuickCommand.parse("turn off the lights in the kitchen", v)!!
        assertNull(room.device)
        assertEquals("z-kitchen", room.roomId)
        assertNull(QuickCommand.parse("what's the weather", v))
        assertNull(QuickCommand.parse("turn on the garage door", v))
        // Speech often drops the "på": a bare "sätt" on a lamp turns it on.
        assertEquals(true, QuickCommand.parse("sätt kitchen lamp", v)!!.on)
        assertNull(QuickCommand.parse("sätt en timer", v))

        // Through the agent: no AI is set up at all, and it still works.
        val home = FakeHome()
        val reply = Agent(ChatClient(OkHttpClient())).ask("släck spots", AgentSettings(), { view(local = it) }, { AgentTools(it, home, FakeDashboard()) })
        assertEquals("Spots är släckt.", reply.text)
        assertEquals(Triple("d-spot", "onoff", JsonPrimitive(false)), home.sets.single())
    }

    @Test fun `next step while cooking is answered without the AI, and the open recipe is in the instructions`() = runTest {
        val book = app.homeydash.recipes.RecipeBook(java.nio.file.Files.createTempDirectory("book").toFile(), OkHttpClient())
        book.load()
        val cooking = app.homeydash.recipes.Cooking(book)
        val r = book.save(
            app.homeydash.recipes.Recipe(
                id = "", title = "Pannkakor",
                ingredients = listOf(app.homeydash.recipes.Ingredient("3 dl mjöl")),
                steps = listOf(app.homeydash.recipes.Step("Vispa smeten."), app.homeydash.recipes.Step("Stek i smör.")),
            ),
        )
        cooking.open(r.id)
        val recipes = RecipeTools(book, cooking) {}
        val settings = AgentSettings() // No brain is set up, so only the quick path can answer.
        val context = AskContext(kitchen = cooking.describe())
        val reply = Agent(ChatClient(OkHttpClient())).ask("nästa steg", settings, { view(settings, it) }, { AgentTools(it, FakeHome(), FakeDashboard(), recipes = recipes) }, context)
        assertEquals("Steg 2 av 2: Stek i smör. Det är sista steget.", reply.text)
        assertEquals(1, cooking.state.value!!.step)
        val prompt = Agent(ChatClient(OkHttpClient())).systemPrompt(settings, view(settings), AskContext(kitchen = cooking.describe()))
        assertTrue(prompt.contains("A recipe is open on the tablet: \"Pannkakor\""))
        assertTrue(prompt.contains("2. Stek i smör.   <-- CURRENT STEP"))
        assertTrue(AgentTools(view(), FakeHome(), FakeDashboard(), recipes = recipes).specs().any { it.name == "recipe_step" })
    }

    @Test fun `a speaker uses the album cards of its twin from another app`() {
        val devices = listOf(
            Device("son", "Kök", zone = "z-kitchen", deviceClass = "speaker", driverUri = "homey:app:com.sonos:speaker"),
            Device("local", "Kök", zone = "z-home", deviceClass = "speaker", hidden = true, driverUri = "homey:app:nl.xiut.sonoslocal:speaker"),
            Device("lamp", "Kök", zone = "z-kitchen", deviceClass = "light", driverUri = "homey:app:com.ikea:bulb"),
            Device("tv", "Vardagsrum", zone = "z-living", deviceClass = "speaker", driverUri = "homey:app:nl.xiut.sonoslocal:speaker"),
        ).associateBy { it.id }
        assertEquals(listOf("local"), app.homeydash.homey.FlowCards.twinsOf(devices, "son"))
        val away = devices + ("local" to devices.getValue("local").copy(available = false))
        assertEquals(emptyList<String>(), app.homeydash.homey.FlowCards.twinsOf(away, "son"))
        assertEquals(emptyList<String>(), app.homeydash.homey.FlowCards.twinsOf(devices, "tv"))

        val album = FlowCardAction(
            "homey:device:local:play_album", title = "Play album",
            args = listOf(
                FlowCardArg("mode", "dropdown", values = listOf(app.homeydash.homey.FlowDropdownValue("replace"), app.homeydash.homey.FlowDropdownValue("queue"))),
                FlowCardArg("album", "autocomplete"),
            ),
        )
        val withVolume = FlowCardAction("homey:device:local:play_album_volume", args = listOf(FlowCardArg("album", "autocomplete"), FlowCardArg("volume", "range")))
        val fav1 = FlowCardAction("homey:device:son:sonos_play_favorite", args = listOf(FlowCardArg("favorite", "autocomplete")))
        val fav2 = FlowCardAction("homeydash:sonos:son:play_favorite", args = listOf(FlowCardArg("item", "autocomplete")))
        val tabs = app.homeydash.homey.FlowCards.musicSources(listOf(fav1, album, fav2))
        assertEquals(listOf("Favourites", "Albums"), tabs.map { it.title })
        assertEquals(listOf("sonos_play_favorite", "play_favorite"), tabs[0].all.map { it.card.cardId })
        val sources = app.homeydash.homey.FlowCards.musicSources(listOf(album, withVolume))
        assertEquals(listOf("play_album"), sources.map { it.card.cardId })
        assertEquals("album", sources.single().arg.name)
        val item = AutocompleteItem("Abbey Road", null, null, buildJsonObject { put("id", "a1"); put("name", "Abbey Road") })
        val args = app.homeydash.homey.FlowCards.musicArgs(sources.single(), item)
        assertEquals("replace", args["mode"]!!.jsonPrimitive.content)
        assertEquals("a1", args["album"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test fun `a plant light is found by what it is, not only its exact name`() = runTest {
        val z: Map<String, Zone> = HomeyJson.decodeFromString(
            """{"z-home":{"id":"z-home","name":"Hem"},"z-office":{"id":"z-office","name":"Kontor","parent":"z-home"},"z-kok":{"id":"z-kok","name":"Kök","parent":"z-home"}}""",
        )
        fun light(id: String, name: String, zone: String) =
            """"$id":{"id":"$id","name":"$name","zone":"$zone","class":"light","capabilities":["onoff"],"capabilitiesObj":{"onoff":{"id":"onoff","type":"boolean","value":false,"setable":true}}}"""
        val raw: Map<String, Device> = HomeyJson.decodeFromString(
            "{" + listOf(
                light("d-plant", "Växt spotlight", "z-office"),
                light("d-roof", "Taklampa", "z-office"),
                light("d-desk", "Skrivbordslampa", "z-office"),
                light("d-kplant", "Växtljus", "z-kok"),
            ).joinToString(",") + "}",
        )
        val v = HomeView.build(z, raw, emptyMap(), emptySet(), AgentSettings(), true)
        assertEquals("Växt spotlight", v.device("växt lampan i kontoret")?.name)
        assertEquals("Växt spotlight", v.device("växtlampan i kontoret")?.name)
        assertEquals("Växtljus", v.device("växtlampan i köket")?.name)
        assertEquals("Taklampa", v.device("taklampan i kontoret")?.name)
        assertTrue(NameMatch.related("lampan", "spotlight"))
        assertFalse(NameMatch.related("lampan", "uttag"))
        val home = FakeHome()
        AgentTools(v, home, FakeDashboard()).run(call("set_device", "device" to "växt lampan i kontoret", "capability" to "onoff", "value" to "on"))
        assertEquals("d-plant", home.sets.single().first)
    }

    @Test fun `the weather card is in the note, so the weather is always known`() {
        val agent = Agent(ChatClient(OkHttpClient()))
        val note = agent.situation(AgentSettings(), view(), AskContext(weather = "Stockholm: Rain, 9 °C"))
        assertTrue(note.contains("Weather (the dashboard's weather card):\nStockholm: Rain, 9 °C"))
        assertTrue(agent.rules(AgentSettings()).contains("never ask the same question twice"))
    }
}
