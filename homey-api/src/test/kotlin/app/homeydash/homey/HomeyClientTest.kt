package app.homeydash.homey

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HomeyClientTest {
    private val server = MockWebServer()
    private lateinit var client: HomeyClient

    @Before fun setUp() {
        server.start()
        client = HomeyClient(server.url("/").toString(), "secret-key", OkHttpClient())
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `parses devices and sends the api key`() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.devices))
        val devices = client.devices()

        val request = server.takeRequest()
        assertEquals("/api/manager/devices/device", request.path)
        assertEquals("Bearer secret-key", request.getHeader("Authorization"))

        val lamp = devices.getValue("d-lamp")
        assertEquals(true, lamp.capability("onoff")?.booleanValue)
        assertEquals(0.4, lamp.capability("dim")?.numberValue)
        assertEquals("socket", devices.getValue("d-plug").effectiveClass)
        assertNull(devices.getValue("d-temp").capability("measure_humidity")?.numberValue)
    }

    @Test fun `system info comes from the ping headers without the key`() = runTest {
        server.enqueue(MockResponse().setBody("{}").addHeader("X-Homey-ID", "abc").addHeader("X-Homey-Version", "12.4.0"))
        val info = client.systemInfo()
        assertEquals("abc", info.homeyId)
        assertEquals("12.4.0", info.homeyVersion)
        val request = server.takeRequest()
        assertEquals("/api/manager/system/ping", request.path)
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun `a server that is not a Homey is rejected`() = runTest {
        server.enqueue(MockResponse().setBody("hello"))
        val error = runCatching { HomeyConnector(OkHttpClient()).connect(server.url("/").toString(), "key") }.exceptionOrNull()
        assertTrue(error is HomeyConnectException && "doesn't look like a Homey" in error.message!!)
    }

    @Test fun `sets a capability with a PUT`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        client.setCapability("d-lamp", "dim", JsonPrimitive(0.75))

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/manager/devices/device/d-lamp/capability/dim", request.path)
        assertEquals("""{"value":0.75}""", request.body.readUtf8())
    }

    @Test fun `sets a mood with a POST`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        client.setMood("m-cook")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/manager/moods/mood/m-cook/set", request.path)
    }

    @Test fun `fetches images with the key`() = runTest {
        server.enqueue(MockResponse().setBody("png"))
        assertEquals("png", String(client.image("/api/image/abc")))
        val request = server.takeRequest()
        assertEquals("/api/image/abc", request.path)
        assertEquals("Bearer secret-key", request.getHeader("Authorization"))
    }

    @Test fun `errors carry the status code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Invalid token"}"""))
        val error = runCatching { client.zones() }.exceptionOrNull()
        assertTrue(error is HomeyHttpException && error.code == 401)
    }

    @Test fun `connector rejects a bad key with a clear message`() = runTest {
        server.enqueue(MockResponse().addHeader("X-Homey-ID", "abc"))
        server.enqueue(MockResponse().setResponseCode(401))
        val error = runCatching { HomeyConnector(OkHttpClient()).connect(server.url("/").toString(), "nope") }.exceptionOrNull()
        assertTrue(error is HomeyConnectException && "API key" in error.message!!)
    }

    @Test fun `lists installed apps and runs an app's card with its result`() = runTest {
        server.enqueue(MockResponse().setBody("""{"com.apple.findmy": {"id": "com.apple.findmy", "name": "Find My", "enabled": true, "state": "running"}}"""))
        val apps = client.apps()
        assertEquals("/api/manager/apps/app", server.takeRequest().path)
        assertTrue(apps.getValue("com.apple.findmy").running)

        server.enqueue(MockResponse().setBody("""{"json": "[]"}"""))
        val result = client.runAppAction("no.runely.calendar", "get_calendars_metadata")
        val request = server.takeRequest()
        assertEquals("/api/manager/flow/flowcardaction/homey:app:no.runely.calendar/homey:app:no.runely.calendar:get_calendars_metadata/run", request.path)
        assertEquals("POST", request.method)
        assertEquals("[]", (result as kotlinx.serialization.json.JsonObject)["json"]!!.let { (it as JsonPrimitive).content })
    }
}
