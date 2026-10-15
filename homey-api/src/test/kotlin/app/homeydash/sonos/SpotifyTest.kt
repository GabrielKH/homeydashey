package app.homeydash.sonos

import app.homeydash.homey.Device
import app.homeydash.homey.HomeySnapshot
import app.homeydash.homey.Zone
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class SpotifyTest {

    private val searchJson = """
        {
          "tracks": {"items": [
            {"id": "t1", "name": "Dancing Queen", "artists": [{"name": "ABBA"}], "album": {"images": [
              {"url": "https://i.scdn.co/640", "width": 640}, {"url": "https://i.scdn.co/300", "width": 300}, {"url": "https://i.scdn.co/64", "width": 64}]}}
          ]},
          "albums": {"items": [{"id": "a1", "name": "Arrival", "artists": [{"name": "ABBA"}], "images": []}]},
          "artists": {"items": [{"id": "r1", "name": "ABBA", "images": [{"url": "https://i.scdn.co/abba", "width": 320}]}]},
          "playlists": {"items": [null, {"id": "p1", "name": "ABBA Gold", "owner": {"display_name": "Spotify"}, "images": null}]}
        }
    """.trimIndent()

    @Test fun `reads the kind asked for out of what was said`() {
        assertEquals(SpotifyHit.Kind.Album to "Arrival ABBA", SpotifySearch.parse("spela albumet Arrival av ABBA"))
        assertEquals(SpotifyHit.Kind.Track to "Dancing Queen", SpotifySearch.parse("the song Dancing Queen"))
        assertEquals(SpotifyHit.Kind.Artist to "Kent", SpotifySearch.parse("låtar med Kent"))
        assertEquals(SpotifyHit.Kind.Playlist to "Lugna favoriter", SpotifySearch.parse("spellistan Lugna favoriter på Spotify"))
        assertEquals(null to "Dancing Queen ABBA", SpotifySearch.parse("Dancing Queen by ABBA"))
    }

    @Test fun `reads search results, skipping empty playlists`() {
        val hits = SpotifySearch.parseSearch(Json.parseToJsonElement(searchJson))
        assertEquals(listOf("Dancing Queen", "Arrival", "ABBA", "ABBA Gold"), hits.map { it.name })
        assertEquals("Song · ABBA", hits[0].description)
        assertEquals("https://i.scdn.co/300", hits[0].art)
        assertEquals("Playlist · Spotify", hits[3].description)
        assertNull(hits[1].art)
    }

    @Test fun `offers the artist first when only the artist is named`() {
        val hits = SpotifySearch.parseSearch(Json.parseToJsonElement(searchJson))
        assertEquals(SpotifyHit.Kind.Artist, SpotifySearch.order("abba", hits, null).first().kind)
        assertEquals(SpotifyHit.Kind.Track, SpotifySearch.order("dancing queen", hits, null).first().kind)
        assertEquals(listOf("Arrival"), SpotifySearch.order("arrival", hits, SpotifyHit.Kind.Album).map { it.name })
    }

    @Test fun `makes the share link items Sonos plays from Spotify`() {
        val track = SpotifyOnSonos.item(SpotifyHit(SpotifyHit.Kind.Track, "t1", "Rock & Roll"))
        assertEquals("spotify%3atrack%3at1", track.uri)
        assertTrue(track.meta.contains("""<item id="00032020spotify%3atrack%3at1""""))
        assertTrue(track.meta.contains("<dc:title>Rock &amp; Roll</dc:title>"))
        assertTrue(track.meta.contains("SA_RINCON2311_X_#Svc2311-0-Token"))

        val album = SpotifyOnSonos.item(SpotifyHit(SpotifyHit.Kind.Album, "a1", "Arrival"), 3079)
        assertEquals("x-rincon-cpcontainer:1004206cspotify%3aalbum%3aa1", album.uri)
        assertTrue(album.meta.contains("object.container.album.musicAlbum") && album.meta.contains("SA_RINCON3079_X_#Svc3079-0-Token"))
        assertFalse(SonosClient.isStream(album))
    }

    @Test fun `finds the household's Spotify number in its favourites`() {
        val radio = SonosItem("P3", "x-sonosapi-stream:s24896?sid=254", "<DIDL-Lite><desc>SA_RINCON65031_</desc></DIDL-Lite>")
        val list = SonosItem("Mix", "x-rincon-cpcontainer:1006206cspotify%3aplaylist%3ax", "<desc>SA_RINCON3079_X_#Svc3079-0-Token</desc>")
        assertEquals(3079, SpotifyOnSonos.serviceIn(listOf(radio, list)))
        assertNull(SpotifyOnSonos.serviceIn(listOf(radio)))
    }

    @Test fun `searches Spotify and plays an artist's songs on the speaker's group`() = runTest {
        val spotifyServer = MockWebServer()
        spotifyServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/token" -> MockResponse().setBody("""{"access_token": "tok", "expires_in": 3600}""")
                request.path!!.startsWith("/v1/search") && request.requestUrl!!.queryParameter("q")!!.startsWith("artist:") ->
                    MockResponse().setBody(
                        """{"tracks": {"items": [
                            {"id": "t1", "name": "Dancing Queen", "artists": [{"name": "ABBA"}]},
                            {"id": "t2", "name": "Waterloo", "artists": [{"name": "ABBA"}]},
                            {"id": "t3", "name": "Tribute", "artists": [{"name": "ABBA Tribute Band"}]}]}}""",
                    )
                request.path!!.startsWith("/v1/search") -> MockResponse().setBody(searchJson)
                else -> MockResponse().setResponseCode(404)
            }
        }
        spotifyServer.start()

        // Asking for hostName starts the server, so the dispatcher is set on a started one.
        val sonosServer = MockWebServer().apply { start() }
        val host = sonosServer.hostName
        val actions = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val topology = """<ZoneGroupState><ZoneGroups><ZoneGroup Coordinator="RINCON_K" ID="RINCON_K:1">""" +
            """<ZoneGroupMember UUID="RINCON_K" Location="http://$host:1400/xml/device_description.xml" ZoneName="Kök"/>""" +
            """</ZoneGroup></ZoneGroups></ZoneGroupState>"""
        sonosServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val action = request.getHeader("SOAPACTION")!!.substringAfter('#').trimEnd('"')
                val body = request.body.readUtf8()
                actions += action to body
                val inner = when (action) {
                    "GetZoneGroupState" -> "<ZoneGroupState>${esc(topology)}</ZoneGroupState>"
                    "Browse" -> "<Result></Result>"
                    else -> ""
                }
                return MockResponse().setBody(
                    """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:${action}Response xmlns:u="urn:x">$inner</u:${action}Response></s:Body></s:Envelope>""",
                )
            }
        }

        val base = spotifyServer.url("/").toString().trimEnd('/')
        val search = SpotifySearch(OkHttpClient(), { SpotifyApp("id", "secret") }, accounts = base, api = base)
        val cards = SonosCards(SonosClient(OkHttpClient(), sonosServer.port), { listOf(host) }, search)
        val snapshot = HomeySnapshot(
            zones = mapOf("z" to Zone("z", "Kök")),
            devices = mapOf("k" to Device("k", "Kök", zone = "z", deviceClass = "speaker")),
        )
        val card = cards.cards(snapshot).getValue("k").single { it.cardId == SonosCards.SPOTIFY }
        assertEquals(emptyList<Any>(), cards.autocomplete(card, "item", ""))

        val found = cards.autocomplete(card, "item", "abba")
        assertEquals("ABBA", found.first().name)
        cards.run(card, JsonObject(mapOf("item" to found.first().raw)))

        val token = spotifyServer.takeRequest()
        assertTrue(token.getHeader("Authorization")!!.startsWith("Basic "))
        assertEquals("Bearer tok", spotifyServer.takeRequest().getHeader("Authorization"))
        val queued = actions.filter { it.first == "AddURIToQueue" }.map { it.second }
        // Only ABBA's own songs, not the tribute band's.
        assertEquals(2, queued.size)
        assertTrue(queued[0].contains("<EnqueuedURI>spotify%3atrack%3at1</EnqueuedURI>"))
        assertEquals(listOf("RemoveAllTracksFromQueue", "AddURIToQueue", "AddURIToQueue", "SetAVTransportURI", "Play"), actions.map { it.first }.takeLast(5))
        spotifyServer.shutdown()
        sonosServer.shutdown()
    }

    private fun esc(xml: String) = xml.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
