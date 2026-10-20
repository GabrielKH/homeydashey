package app.homeydash.agent

import app.homeydash.homey.Device
import app.homeydash.homey.HomeyFlow
import app.homeydash.homey.HomeyJson
import app.homeydash.homey.Mood
import app.homeydash.homey.RemoteKey
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class ActivityTest {
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
        override val timers = TimerBoard(null)
        override fun setTheme(mode: String) = true
    }

    private class Recorder : AgentListener {
        val steps = mutableListOf<AgentStep>()
        val traces = mutableListOf<Triple<String, String, Boolean>>()
        override fun step(step: AgentStep) { steps += step }
        override fun trace(kind: String, text: String, localOnly: Boolean) { traces += Triple(kind, text, localOnly) }
    }

    private fun toolCall(id: String, name: String, args: String) =
        """{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"$id","type":"function","function":{"name":"$name","arguments":"${args.replace("\"", "\\\"")}"}}]}}]}"""

    @Test fun `shows each step live, logs the turn, and does the same change only once`() = runTest {
        val server = MockWebServer()
        val dim = """{"device":"light_1","capability":"dim","value":"0.4"}"""
        server.enqueue(MockResponse().setBody(toolCall("a", "set_device", dim)))
        server.enqueue(MockResponse().setBody(toolCall("b", "set_device", dim)))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Dimmed to 40%."}}]}"""))
        server.start()
        val home = FakeHome()
        val settings = AgentSettings(local = Connection(Provider.Ollama, server.url("/v1").toString(), "", "m"), mode = BrainMode.Local)
        val agent = Agent(ChatClient(OkHttpClient()))
        val heard = Recorder()
        agent.listener = heard
        val reply = agent.ask("make it cosy by the stove", settings, { view(settings, it) }, { AgentTools(it, home, FakeDashboard()) })
        assertEquals("Dimmed to 40%.", reply.text)
        assertEquals(1, home.sets.size)

        val dimming = heard.steps.filter { it.text == "Dimming Kitchen lamp to 40%" }
        assertEquals(listOf(StepState.Running, StepState.Done), dimming.map { it.state })
        assertEquals(3, heard.steps.count { it.text == "Thinking" && it.state == StepState.Done })
        val kinds = heard.traces.map { it.first }
        assertEquals("question", kinds.first())
        assertEquals("answer", kinds.last())
        assertTrue(kinds.containsAll(listOf("brain", "model calls", "tool", "result", "guard")))
        server.shutdown()
    }

    @Test fun `a failed tool shows as failed with what went wrong`() = runTest {
        val heard = Recorder()
        val tools = AgentTools(view(), FakeHome(), FakeDashboard()).apply { listener = heard }
        tools.run(ToolCall("x", "set_device", JsonObject(mapOf("device" to JsonPrimitive("garage"), "capability" to JsonPrimitive("onoff"), "value" to JsonPrimitive("true")))))
        val last = heard.steps.last()
        assertEquals(StepState.Failed, last.state)
        assertEquals("Turning on garage", last.text)
        assertTrue(last.detail!!.contains("garage"))
    }

    @Test fun `reads remote buttons in English and Swedish`() {
        assertEquals(listOf(RemoteKey.Down, RemoteKey.Down, RemoteKey.Down, RemoteKey.Select), RemoteKeys.parse("down x3, ok"))
        assertEquals(listOf(RemoteKey.Right, RemoteKey.Right, RemoteKey.Back), RemoteKeys.parse("2 höger; tillbaka"))
        assertEquals(listOf(RemoteKey.VolumeUp, RemoteKey.ChannelDown), RemoteKeys.parse("Volume up, channel_down"))
        assertEquals(listOf(RemoteKey.PlayPause), RemoteKeys.parse("play"))
        assertNull(RemoteKeys.parse("dance"))
        assertNull(RemoteKeys.parse(""))
    }

    @Test fun `presses remote buttons from the TV's capabilities`() = runTest {
        val home = FakeHome()
        val tools = AgentTools(view(), home, FakeDashboard())
        val alias = view().devices.first { it.name == "TV" }.alias
        val result = tools.run(ToolCall("r", "press_remote", JsonObject(mapOf("device" to JsonPrimitive(alias), "keys" to JsonPrimitive("volume up x2, power")))))
        assertTrue(result.text, result.text.startsWith("Done: pressed volume up, volume up, power on TV"))
        assertEquals(
            listOf(Triple("d-tv", "volume_up", JsonPrimitive(true)), Triple("d-tv", "volume_up", JsonPrimitive(true)), Triple("d-tv", "onoff", JsonPrimitive(false))),
            home.sets,
        )
        val missing = tools.run(ToolCall("r2", "press_remote", JsonObject(mapOf("device" to JsonPrimitive(alias), "keys" to JsonPrimitive("up")))))
        assertTrue(missing.text, missing.text.startsWith("TV has no button for up"))
        assertTrue(StepText.failed(missing.text))
    }

    @Test fun `the log keeps local-only lines and keys on the tablet`() {
        val file = File.createTempFile("agentlog", ".jsonl").apply { delete() }
        val log = AgentLog(file, ZoneId.of("UTC"))
        log.add("question", "vad händer idag", at = 0)
        log.add("result", "Dentist 09:00", localOnly = true, at = 1000)
        log.add("model", "my key is sk-abcdefghijklmnopqrstuv", at = 2000)
        val again = AgentLog(file, ZoneId.of("UTC"))
        val entries = again.unsent()
        assertEquals(3, entries.size)
        val md = again.markdown(entries)
        assertTrue(md.contains("vad händer idag"))
        assertFalse(md.contains("Dentist"))
        assertTrue(md.contains(AgentLog.LOCAL_ONLY))
        assertFalse(md.contains("sk-abc"))
        again.sent(2)
        assertEquals(listOf("model"), AgentLog(file, ZoneId.of("UTC")).unsent().map { it.kind })
        file.delete()
    }

    @Test fun `posts the log on one issue, made the first time`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""[{"number":3,"title":"Something else"},{"number":4,"title":"Assistant logs","pull_request":{}}]"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"number":7}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":1}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":2}"""))
        server.start()
        val gh = GitHubLog(OkHttpClient(), server.url("/").toString())
        gh.post("tok", "me/repo", "first")
        gh.post("tok", "me/repo", "second")
        val list = server.takeRequest()
        assertEquals("/repos/me/repo/issues?state=open&per_page=100", list.path)
        assertEquals("Bearer tok", list.getHeader("Authorization"))
        assertEquals("/repos/me/repo/issues", server.takeRequest().path)
        assertEquals("/repos/me/repo/issues/7/comments", server.takeRequest().path)
        val second = server.takeRequest()
        assertEquals("/repos/me/repo/issues/7/comments", second.path)
        assertTrue(second.body.readUtf8().contains("second"))
        server.shutdown()
    }
}
