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
import java.util.concurrent.atomic.AtomicInteger

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
        assertEquals(SpotifyHit.Kind.Playlist to "Sommar", SpotifySearch.parse("spela min spellista Sommar"))
        assertEquals(SpotifyHit.Kind.Playlist to "chill", SpotifySearch.parse("playlist my own chill"))
    }

    @Test fun `knows Spotify's charts and podcasts by their everyday names`() {
        assertEquals(listOf("Top 50 – Sverige"), SpotifySearch.chartsFor("topplistan").map { it.name })
        assertEquals(listOf("Top 50 – Sverige"), SpotifySearch.chartsFor("top 50 sweden").map { it.name })
        assertEquals(listOf("Top 50 – Global"), SpotifySearch.chartsFor("global top 50").map { it.name })
        assertEquals(listOf("Top 50 – Global"), SpotifySearch.chartsFor("top 50", market = "US").map { it.name })
        assertEquals(listOf("Today's Top Hits"), SpotifySearch.chartsFor("today's top hits").map { it.name })
        assertEquals(emptyList<String>(), SpotifySearch.chartsFor("chill jazz").map { it.name })
        assertEquals(SpotifyHit.Kind.Show to "Alex och Sigge", SpotifySearch.parse("spela podden Alex och Sigge"))
        assertEquals(SpotifyHit.Kind.Show to "Sommar i P1", SpotifySearch.parse("senaste avsnittet av Sommar i P1"))
        assertEquals(SpotifyHit.Kind.Episode to "Naturen", SpotifySearch.parse("avsnittet Naturen"))
        val show = SpotifyOnSonos.item(SpotifyHit(SpotifyHit.Kind.Show, "s1", "Alex och Sigge"))
        assertEquals("x-rincon-cpcontainer:1006206cspotify%3ashow%3as1", show.uri)
        val episode = SpotifyOnSonos.item(SpotifyHit(SpotifyHit.Kind.Episode, "e1", "Avsnitt 1"))
        assertEquals("spotify%3aepisode%3ae1", episode.uri)
        val parsed = SpotifySearch.parseSearch(Json.parseToJsonElement("""{"shows": {"items": [{"id": "s1", "name": "Alex och Sigge", "images": []}]}}"""))
        assertEquals(SpotifyHit.Kind.Show, parsed.single().kind)
        assertEquals("Podcast", parsed.single().description)
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

    @Test fun `gives songs the Sonos app's own link when the account is known`() {
        val list = SonosItem("Mix", "x-rincon-cpcontainer:1006206cspotify%3aplaylist%3ax?sid=12&flags=8300&sn=3", "")
        val account = SpotifyOnSonos.accountIn(listOf(list))
        assertEquals(SpotifyOnSonos.Account("12", "3"), account)
        val track = SpotifyOnSonos.item(SpotifyHit(SpotifyHit.Kind.Track, "t1", "Waterloo"), account = account)
        assertEquals("x-sonos-spotify:spotify%3atrack%3at1?sid=12&flags=8224&sn=3", track.uri)
        val album = SpotifyOnSonos.item(SpotifyHit(SpotifyHit.Kind.Album, "a1", "Arrival"), account = account)
        assertEquals("x-rincon-cpcontainer:1004206cspotify%3aalbum%3aa1", album.uri)
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
        val stateChecks = AtomicInteger()
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
                    // Stopped at first, as Sonos sometimes is right after "Play", then playing.
                    "GetTransportInfo" -> "<CurrentTransportState>${if (stateChecks.incrementAndGet() <= 2) "STOPPED" else "PLAYING"}</CurrentTransportState>"
                    "GetPositionInfo" -> "<TrackURI>x-sonos-spotify:spotify%3atrack%3at1?sid=12&amp;flags=8224&amp;sn=3</TrackURI>"
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
        // It asked again when the group stayed stopped, and checked until it played.
        assertEquals(
            listOf("RemoveAllTracksFromQueue", "AddURIToQueue", "AddURIToQueue", "SetAVTransportURI", "Seek", "Play", "Play"),
            actions.map { it.first }.filterNot { it.startsWith("Get") || it == "Browse" },
        )
        assertEquals("GetPositionInfo", actions.last().first)
        // Straight to the first new song, not on with what the speaker was playing.
        assertTrue(actions.single { it.first == "Seek" }.second.contains("<Unit>TRACK_NR</Unit><Target>1</Target>"))
        spotifyServer.shutdown()
        sonosServer.shutdown()
    }

    @Test fun `finds your own playlists when signed in, plays one and adds it to the Sonos favourites`() = runTest {
        val spotifyServer = MockWebServer()
        val tokenForms = Collections.synchronizedList(mutableListOf<String>())
        spotifyServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/token" -> {
                    tokenForms += request.body.readUtf8()
                    MockResponse().setBody("""{"access_token": "tok", "expires_in": 3600, "refresh_token": "new"}""")
                }
                request.path!!.startsWith("/v1/me/playlists") -> {
                    assertEquals("Bearer tok", request.getHeader("Authorization"))
                    MockResponse().setBody(
                        """{"items": [
                            {"id": "p9", "name": "Sommar i köket", "owner": {"display_name": "Gabriel"}, "images": []},
                            {"id": "p8", "name": "Träning", "owner": {"display_name": "Gabriel"}, "images": []}], "next": null}""",
                    )
                }
                request.path!!.startsWith("/v1/search") -> MockResponse().setBody(searchJson)
                else -> MockResponse().setResponseCode(404)
            }
        }
        spotifyServer.start()
        val sonosServer = MockWebServer().apply { start() }
        val host = sonosServer.hostName
        val actions = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val topology = """<ZoneGroupState><ZoneGroups><ZoneGroup Coordinator="RINCON_K" ID="RINCON_K:1">""" +
            """<ZoneGroupMember UUID="RINCON_K" Location="http://$host:1400/xml/device_description.xml" ZoneName="Kök"/>""" +
            """</ZoneGroup></ZoneGroups></ZoneGroupState>"""
        sonosServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val action = request.getHeader("SOAPACTION")!!.substringAfter('#').trimEnd('"')
                actions += action to request.body.readUtf8()
                val inner = when (action) {
                    "GetZoneGroupState" -> "<ZoneGroupState>${esc(topology)}</ZoneGroupState>"
                    "Browse" -> "<Result></Result>"
                    "GetTransportInfo" -> "<CurrentTransportState>PLAYING</CurrentTransportState>"
                    "CreateObject" -> "<ObjectID>FV:2/40</ObjectID>"
                    else -> ""
                }
                return MockResponse().setBody(
                    """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:${action}Response xmlns:u="urn:x">$inner</u:${action}Response></s:Body></s:Envelope>""",
                )
            }
        }
        val base = spotifyServer.url("/").toString().trimEnd('/')
        var kept: String? = null
        val search = SpotifySearch(OkHttpClient(), { SpotifyApp("id", "secret") }, accounts = base, api = base, user = { "old" }, onUser = { kept = it })
        assertTrue(search.signedIn)
        // "min spellista" looks only among your own.
        assertEquals(listOf("Sommar i köket"), search.search("min spellista sommar").map { it.name })
        assertEquals("Your playlist · Gabriel", search.search("min spellista sommar").single().description)
        // Without "my", your own match comes before the catalogue's.
        assertEquals("Sommar i köket", search.search("playlist sommar").first().name)
        assertTrue(tokenForms.first().contains("grant_type=refresh_token") && tokenForms.first().contains("refresh_token=old"))
        assertEquals("new", kept)

        val cards = SonosCards(SonosClient(OkHttpClient(), sonosServer.port), { listOf(host) }, search)
        val snapshot = HomeySnapshot(
            zones = mapOf("z" to Zone("z", "Kök")),
            devices = mapOf("k" to Device("k", "Kök", zone = "z", deviceClass = "speaker")),
        )
        val all = cards.cards(snapshot).getValue("k")
        val play = all.single { it.cardId == SonosCards.SPOTIFY }
        val save = all.single { it.cardId == SonosCards.SAVE }
        // Adding to the favourites never counts as a way to play music.
        assertEquals(listOf(SonosCards.SPOTIFY, "play_favorite", "play_playlist").sorted(), app.homeydash.homey.FlowCards.musicSources(all).flatMap { it.all }.map { it.card.cardId }.sorted())
        assertEquals(emptyList<Any>(), cards.autocomplete(save, "item", ""))

        val mine = cards.autocomplete(play, "item", "min spellista sommar").single()
        cards.run(play, JsonObject(mapOf("item" to mine.raw)))
        assertTrue(actions.single { it.first == "AddURIToQueue" }.second.contains("x-rincon-cpcontainer:1006206cspotify%3aplaylist%3ap9"))

        // "Add it to my favourites": what was just played is offered first.
        val offered = cards.autocomplete(save, "item", "")
        assertEquals(listOf("Sommar i köket"), offered.map { it.name })
        cards.run(save, JsonObject(mapOf("item" to offered.single().raw)))
        val created = actions.single { it.first == "CreateObject" }.second
        assertTrue(created.contains("<ContainerID>FV:2</ContainerID>"))
        // The favourite, written once more inside the SOAP body.
        val elements = created.substringAfter("<Elements>").substringBefore("</Elements>")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
        assertTrue(elements.contains("<r:type>instantPlay</r:type>"))
        assertTrue(elements.contains("""<res protocolInfo="x-rincon-cpcontainer:*:*:*">x-rincon-cpcontainer:1006206cspotify%3aplaylist%3ap9</res>"""))
        assertTrue(elements.contains("<r:resMD>&lt;DIDL-Lite"))
        assertTrue(elements.contains("<dc:title>Sommar i köket</dc:title>"))
        spotifyServer.shutdown()
        sonosServer.shutdown()
    }

    private fun esc(xml: String) = xml.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
