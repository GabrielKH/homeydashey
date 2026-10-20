package app.homeydash.agent

import app.homeydash.homey.Device
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
import org.junit.Test
import java.time.LocalDate

class SpeechStreamTest {
    private fun spoken(vararg pieces: String, finish: Boolean = true): List<String> {
        val said = mutableListOf<String>()
        val s = SpeechStream({ said += it })
        pieces.forEach(s::feed)
        if (finish) s.finish()
        return said
    }

    @Test fun `a sentence is said as soon as it is finished`() {
        val said = mutableListOf<String>()
        val s = SpeechStream({ said += it })
        "Next week you have the dentist on Monday. ".chunked(5).forEach(s::feed)
        assertEquals(listOf("Next week you have the dentist on Monday."), said)
        s.feed("And on Friday")
        assertEquals(1, said.size)
        s.feed(" it's Lisa's birthday.")
        s.finish()
        assertEquals("And on Friday it's Lisa's birthday.", said.last())
    }

    @Test fun `a short first word waits for the next sentence`() {
        assertEquals(listOf("Okay. The kitchen is warm."), spoken("Okay. ", "The kitchen is warm."))
    }

    @Test fun `tool calls and thinking are never said`() {
        assertEquals(listOf("Let me check the calendar."), spoken("<think>they want dates</think>Let me check the calendar.\n", "CALL calendar {\"days\": 7}"))
        assertEquals(listOf("Sure"), spoken("Sure\n<tool_call>{\"name\":\"x\"}</tool_call>"))
        assertEquals(emptyList<String>(), spoken("CALL play_music {\"query\": \"jazz. Now.\"}"))
    }

    @Test fun `talk that wasn't meant for it stays quiet`() {
        assertEquals(emptyList<String>(), spoken("[ign", "ore]"))
        assertEquals(emptyList<String>(), spoken("[ignore]", finish = false))
    }

    @Test fun `aliases are said by their names`() {
        val said = mutableListOf<String>()
        val s = SpeechStream({ said += it }, { it.replace("light_1", "the kitchen lamp") })
        s.feed("light_1 is on and bright.")
        s.finish()
        assertEquals(listOf("the kitchen lamp is on and bright."), said)
    }

    @Test fun `a streamed answer is put back together with its tool call`() {
        val a = ChatClient.StreamedAnswer()
        val text = StringBuilder()
        listOf(
            """data: {"choices":[{"delta":{"role":"assistant","content":"Turning"}}]}""",
            "",
            """data: {"choices":[{"delta":{"content":" it on."}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"set_device","arguments":"{\"device\":"}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"light_1\"}"}}]}}]}""",
            """data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
        ).forEach { assertTrue(a.read(it) { p -> text.append(p) }) }
        assertFalse(a.read("data: [DONE]") {})
        val m = a.message()
        assertEquals("Turning it on.", text.toString())
        assertEquals("set_device", m.toolCalls.single().name)
        assertEquals("light_1", (m.toolCalls.single().arguments["device"] as JsonPrimitive).content)
        assertEquals("tool_calls", m.finish)
    }

    private val zones: Map<String, Zone> = HomeyJson.decodeFromString("""{"z":{"id":"z","name":"Kitchen"}}""")
    private val devices: Map<String, Device> = HomeyJson.decodeFromString(
        """{"d":{"id":"d","name":"Kitchen lamp","zone":"z","class":"light","capabilities":["onoff"],
           "capabilitiesObj":{"onoff":{"id":"onoff","type":"boolean","value":false,"setable":true}}}}""",
    )

    private class Home : HomeControl {
        val sets = mutableListOf<String>()
        override suspend fun setCapability(deviceId: String, capability: String, value: JsonElement) { sets += "$deviceId $capability $value" }
        override suspend fun setMood(mood: Mood) {}
        override suspend fun flows() = emptyList<app.homeydash.homey.HomeyFlow>()
        override suspend fun triggerFlow(flow: app.homeydash.homey.HomeyFlow) {}
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

    private fun events(vararg texts: String) = texts.joinToString("") { t ->
        "data: {\"choices\":[{\"delta\":{\"content\":${JsonPrimitive(t)}}}]}\n\n"
    } + "data: [DONE]\n\n"

    @Test fun `a question is spoken while the AI is still writing`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events("Mostly sunny today, ", "around 18 degrees. ", "Take a jacket tonight.")))
        server.start()
        val settings = AgentSettings(local = Connection(Provider.Ollama, server.url("/v1").toString(), "", "m"), mode = BrainMode.Local)
        val view = { l: Boolean -> HomeView.build(zones, devices, emptyMap(), emptySet(), settings, l) }
        val said = mutableListOf<String>()
        val reply = Agent(ChatClient(OkHttpClient())).ask("How is the weather?", settings, view, { AgentTools(it, Home(), Dash()) }, speech = { said += it })
        assertTrue(reply.spoken)
        assertEquals("Mostly sunny today, around 18 degrees. Take a jacket tonight.", reply.text)
        assertEquals(listOf("Mostly sunny today, around 18 degrees.", "Take a jacket tonight."), said)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"stream\":true"))
        server.shutdown()
    }

    @Test fun `a stream without its content type and a reply in one go both work`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(events("It's 18 degrees outside right now.")))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"It's 18 degrees outside right now."}}]}"""))
        server.start()
        val c = Connection(Provider.Ollama, server.url("/v1").toString(), "", "m")
        repeat(2) {
            val heard = StringBuilder()
            val m = ChatClient(OkHttpClient()).stream(c, listOf(ChatMessage.user("Temp?")), emptyList()) { heard.append(it) }
            assertEquals("It's 18 degrees outside right now.", m.content)
            assertEquals(m.content, heard.toString())
        }
        server.shutdown()
    }

    @Test fun `a change is only said once a tool did it`() = runTest {
        val server = MockWebServer()
        // First it promises without a call, then calls, then confirms.
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"The lamp is now on."}}]}"""))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"CALL set_device {\"device\": \"light_1\", \"capability\": \"onoff\", \"value\": true}"}}]}"""))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events("Done, the kitchen lamp is on.")))
        server.start()
        val settings = AgentSettings(local = Connection(Provider.Ollama, server.url("/v1").toString(), "", "m"), mode = BrainMode.Local)
        val view = { l: Boolean -> HomeView.build(zones, devices, emptyMap(), emptySet(), settings, l) }
        val said = mutableListOf<String>()
        val home = Home()
        // Not a plain on/off the quick path takes, so the AI answers it.
        val reply = Agent(ChatClient(OkHttpClient())).ask("make it cosy in here, turn the lamp on please", settings, view, { AgentTools(it, home, Dash()) }, speech = { said += it })
        assertEquals(reply.actions.toString(), listOf("d onoff true"), home.sets)
        assertEquals(listOf("Done, the kitchen lamp is on."), said)
        assertTrue(reply.spoken)
        // Streamed for the timing, but nothing was said before the tool did it.
        assertTrue(server.takeRequest().body.readUtf8().contains("\"stream\":true"))
        assertEquals(3, reply.rounds.size)
        server.shutdown()
    }

    @Test fun `a sentence from another voice is made to fit the playing one`() {
        val pcm = Pcm(24000, ShortArray(2400) { (it % 100).toShort() })
        val out = VoiceFx.resample(pcm, 16000)
        assertEquals(16000, out.sampleRate)
        assertEquals(1600, out.samples.size)
        assertEquals(pcm, VoiceFx.resample(pcm, 24000))
    }

    private fun chunk(amp: Int, rate: Int = 16000) = ShortArray(rate / 10) { i -> (amp * kotlin.math.sin(i * 0.3)).toInt().toShort() }

    @Test fun `someone talking over its voice is noticed, its own quiet echo isn't`() {
        val rate = 16000
        val t = TalkOver(rate)
        // Faint echo while it learns and after: nothing.
        repeat(25) { assertFalse(t.feed(chunk(300), rate / 10)) }
        // Someone talks clearly: noticed after about 0.9 s.
        val hits = (1..12).map { t.feed(chunk(4000), rate / 10) }
        assertEquals(1, hits.count { it })
        assertTrue(hits.indexOf(true) in 7..9)
    }

    @Test fun `a loud start of its own voice or a short sound doesn't stop it`() {
        val rate = 16000
        val t = TalkOver(rate)
        // Its "hmm" echoing loudly right at the start only teaches it the floor.
        repeat(8) { assertFalse(t.feed(chunk(3000), rate / 10)) }
        // A cough, a clap or a pot: short sounds never count.
        repeat(5) {
            repeat(5) { assertFalse(t.feed(chunk(9000), rate / 10)) }
            repeat(4) { assertFalse(t.feed(chunk(300), rate / 10)) }
        }
        // Quiet talk across the room only counts on Easy.
        val easy = TalkOver(rate, easy = true)
        val strict = TalkOver(rate)
        repeat(10) { easy.feed(chunk(200), rate / 10); strict.feed(chunk(200), rate / 10) }
        assertTrue((1..10).any { easy.feed(chunk(900), rate / 10) })
        assertFalse((1..10).any { strict.feed(chunk(900), rate / 10) })
    }

    @Test fun `thinking sounds don't repeat`() {
        val all = ThinkingSounds.all(Persona.Nova, swedish = true)
        val first = ThinkingSounds.pick(all, last = null, random = kotlin.random.Random(1))
        repeat(20) { assertTrue(ThinkingSounds.pick(all, first) != first) }
        assertEquals("Hmm…", ThinkingSounds.pick(listOf("Hmm…"), "Hmm…"))
        assertEquals(null, ThinkingSounds.pick(emptyList(), null))
    }

    @Test
    fun `what a round said is kept for the next round, and cleared after`() {
        val said = mutableListOf<String>()
        val out = SpeechStream({ said += it })
        out.feed("Jag tänder lamporna i vardagsrummet. ")
        out.feed("CALL set_room {}")
        out.finish()
        assertEquals("Jag tänder lamporna i vardagsrummet.", out.heardThisRound())
        out.next()
        assertEquals("", out.heardThisRound())
    }

    @Test
    fun `a hum is said as a word, never spelled out`() {
        assertEquals("ehm, det vet jag inte.", ThinkingSounds.sayable("Hmm, det vet jag inte.", true))
        assertEquals("um… let me see", ThinkingSounds.sayable("Hmmm… let me see", false))
        assertEquals("Hampus och Emma", ThinkingSounds.sayable("Hampus och Emma", true))
        for (p in Persona.entries) for (sv in listOf(true, false)) {
            (ThinkingSounds.all(p, sv) + ThinkingSounds.answers(p, sv)).forEach { assertEquals(it, ThinkingSounds.sayable(it, sv)) }
        }
    }
}
