package app.homeydash.homey

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlowCardsTest {
    private val json = """
        {
          "homey:device:tv1:navigate_up": {"id": "homey:device:tv1:navigate_up", "ownerUri": "homey:device:tv1", "title": "Navigera upp", "args": []},
          "homey:device:tv1:navigate_left": {"id": "homey:device:tv1:navigate_left", "ownerUri": "homey:device:tv1", "args": []},
          "homey:device:tv1:press_select": {"id": "homey:device:tv1:press_select", "ownerUri": "homey:device:tv1", "args": []},
          "homey:device:tv1:press_menu": {"id": "homey:device:tv1:press_menu", "ownerUri": "homey:device:tv1", "args": []},
          "homey:device:tv1:press_home": {"id": "homey:device:tv1:press_home", "ownerUri": "homey:device:tv1"},
          "homey:device:tv1:volume_down": {"id": "homey:device:tv1:volume_down", "ownerUri": "homey:device:tv1"},
          "homey:device:tv1:set_volume": {"id": "homey:device:tv1:set_volume", "ownerUri": "homey:device:tv1", "args": [{"name": "volume", "type": "range"}]},
          "homey:device:tv1:launch_app": {"id": "homey:device:tv1:launch_app", "ownerUri": "homey:device:tv1", "title": "Launch App",
             "args": [{"name": "app", "type": "autocomplete"}]},
          "homey:device:son1:play_favorite": {"id": "homey:device:son1:play_favorite", "ownerUri": "homey:device:son1", "title": "Play Favorite",
             "args": [{"name": "favorite", "type": "autocomplete"}]},
          "homey:device:son1:play_playlist": {"id": "homey:device:son1:play_playlist", "ownerUri": "homey:device:son1",
             "args": [{"name": "playlist", "type": "autocomplete"}]},
          "homey:device:son1:play_url": {"id": "homey:device:son1:play_url", "ownerUri": "homey:device:son1", "args": [{"name": "url", "type": "text"}]},
          "homey:manager:logic:x": {"id": "homey:manager:logic:x"}
        }
    """.trimIndent()
    private val cards = FlowCards.parseActions(HomeyJson.parseToJsonElement(json))
    private val byDevice = FlowCards.byDevice(cards)

    @Test fun `cards group by device`() {
        assertEquals(setOf("tv1", "son1"), byDevice.keys)
        assertEquals("homey:device:tv1", byDevice.getValue("tv1").first().routeUri)
    }

    @Test fun `apple tv cards become remote keys`() {
        val keys = FlowCards.remoteKeys(byDevice.getValue("tv1"))
        assertEquals("navigate_up", keys[RemoteKey.Up]?.cardId)
        assertEquals("navigate_left", keys[RemoteKey.Left]?.cardId)
        assertEquals("press_select", keys[RemoteKey.Select]?.cardId)
        assertEquals("press_menu", keys[RemoteKey.Back]?.cardId)
        assertEquals("press_home", keys[RemoteKey.Home]?.cardId)
        assertEquals("volume_down", keys[RemoteKey.VolumeDown]?.cardId)
        assertNull(keys[RemoteKey.Down])
        assertEquals("launch_app", FlowCards.appLauncher(byDevice.getValue("tv1"))?.card?.cardId)
    }

    @Test fun `sonos cards become music sources`() {
        val sources = FlowCards.musicSources(byDevice.getValue("son1"))
        assertEquals(listOf("Favourites", "Playlists"), sources.map { it.title })
        assertEquals(emptyList<MusicSource>(), FlowCards.musicSources(byDevice.getValue("tv1")))
    }

    @Test fun `runs and autocompletes cards on the right route`() = runTest {
        val server = MockWebServer().apply { start() }
        val client = HomeyClient(server.url("/").toString(), "k", OkHttpClient())
        val favorite = byDevice.getValue("son1").first { it.cardId == "play_favorite" }

        server.enqueue(MockResponse().setBody("""[{"name": "Kind of Blue", "description": "Miles Davis", "image": "https://x/y.jpg", "id": "fv:2"}]"""))
        val items = client.autocomplete(favorite, "favorite")
        assertEquals("Kind of Blue", items.single().name)
        assertEquals(
            "/api/manager/flow/flowcardaction/homey:device:son1/homey:device:son1:play_favorite/autocomplete?name=favorite&query=",
            server.takeRequest().path,
        )

        server.enqueue(MockResponse().setBody("{}"))
        client.runAction(favorite, JsonObject(mapOf("favorite" to items.single().raw)))
        val run = server.takeRequest()
        assertEquals("/api/manager/flow/flowcardaction/homey:device:son1/homey:device:son1:play_favorite/run", run.path)
        assertEquals("""{"args":{"favorite":{"name":"Kind of Blue","description":"Miles Davis","image":"https://x/y.jpg","id":"fv:2"}}}""", run.body.readUtf8())
        server.shutdown()
    }

    @Test fun `dropdown values send the bare id`() {
        val arg = FlowCardArg("mode", "dropdown", values = listOf(FlowDropdownValue("a", "Alpha")))
        val item = FlowCards.dropdownItems(arg).single()
        assertEquals("Alpha", item.name)
        assertEquals(JsonPrimitive("a"), FlowCards.argValue(arg, item))
    }
}
