package app.homeydash.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTest {
    private fun usage(json: String) = ChatClient.usageOf(Json.parseToJsonElement(json) as JsonObject)

    @Test fun `token counts are read from OpenAI and llama-cpp answers`() {
        assertEquals(
            ChatClient.Usage(3120, 3000, 41),
            usage("""{"usage":{"prompt_tokens":3120,"completion_tokens":41,"prompt_tokens_details":{"cached_tokens":3000}}}"""),
        )
        assertEquals(ChatClient.Usage(3120, 3000, 41), usage("""{"timings":{"prompt_n":120,"cache_n":3000,"predicted_n":41}}"""))
        assertNull(usage("""{"choices":[]}"""))
    }

    @Test fun `a streamed answer is timed and counted`() {
        val a = ChatClient.StreamedAnswer()
        a.read("""data: {"choices":[{"delta":{"content":"Hej"}}]}""") {}
        a.read("""data: {"choices":[{"delta":{"content":" där."}}]}""") {}
        a.read("""data: {"choices":[],"usage":{"prompt_tokens":500,"completion_tokens":3}}""") {}
        val st = a.message().stats!!
        assertNotNull(st.firstMs)
        assertEquals(500, st.promptTokens)
        assertEquals(3, st.outputTokens)
        assertTrue(st.counted)
    }

    @Test fun `speeds read like a person would say them`() {
        val st = ChatStats(totalMs = 2000, firstMs = 1000, promptTokens = 3120, cachedTokens = 3000, outputTokens = 41, counted = true)
        assertEquals(40.0, st.tokensPerSecond!!, 0.01)
        assertEquals("first word 1.0 s · 41 tokens at 40 tok/s in 2.0 s · read 3 120 tokens (3 000 cached) at 120 tok/s", st.text())
        val speed = Speed(hearMs = 400, rounds = listOf(st), voiceMs = 1300, replyMs = 1700)
        assertEquals("Heard 0.4 s · AI first word 1.0 s, 40 tok/s · voice 1.3 s · reply 1.7 s after you stopped", speed.short())
    }

    @Test fun `a server that refuses stream options is asked again without them`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Unknown field stream_options"}}"""))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"OK"}}],"usage":{"prompt_tokens":5,"completion_tokens":1}}"""))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"OK"}}]}"""))
        server.start()
        val c = Connection(Provider.Ollama, server.url("/v1").toString(), "", "m")
        val chat = ChatClient(OkHttpClient())
        val m = chat.stream(c, listOf(ChatMessage.user("Hi")), emptyList()) {}
        assertEquals("OK", m.content)
        assertEquals(5, m.stats?.promptTokens)
        assertTrue(server.takeRequest().body.readUtf8().contains("stream_options"))
        assertTrue(!server.takeRequest().body.readUtf8().contains("stream_options"))
        chat.stream(c, listOf(ChatMessage.user("Hi")), emptyList()) {}
        assertTrue(!server.takeRequest().body.readUtf8().contains("stream_options"))
        server.shutdown()
    }
}
