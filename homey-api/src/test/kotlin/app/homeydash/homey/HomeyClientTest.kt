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

    @Test fun `system info is fetched without the key`() = runTest {
        server.enqueue(MockResponse().setBody("""{"homeyId":"abc","homeyVersion":"12.4.0","homeyModelId":"homey5q"}"""))
        assertEquals("abc", client.systemInfo().homeyId)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `sets a capability with a PUT`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        client.setCapability("d-lamp", "dim", JsonPrimitive(0.75))

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/manager/devices/device/d-lamp/capability/dim", request.path)
        assertEquals("""{"value":0.75}""", request.body.readUtf8())
    }

    @Test fun `errors carry the status code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Invalid token"}"""))
        val error = runCatching { client.zones() }.exceptionOrNull()
        assertTrue(error is HomeyHttpException && error.code == 401)
    }

    @Test fun `connector rejects a bad key with a clear message`() = runTest {
        server.enqueue(MockResponse().setBody("""{"homeyId":"abc"}"""))
        server.enqueue(MockResponse().setResponseCode(401))
        val error = runCatching { HomeyConnector(OkHttpClient()).connect(server.url("/").toString(), "nope") }.exceptionOrNull()
        assertTrue(error is HomeyConnectException && "API key" in error.message!!)
    }
}
