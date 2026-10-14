package app.homeydash.sonos

import app.homeydash.homey.Device
import app.homeydash.homey.HomeySnapshot
import app.homeydash.homey.Zone
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SonosTest {

    private fun esc(xml: String) = xml.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun soap(action: String, inner: String) = MockResponse().setBody(
        """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:${action}Response xmlns:u="urn:x">$inner</u:${action}Response></s:Body></s:Envelope>""",
    )

    private val topology = """
        <ZoneGroupState><ZoneGroups>
          <ZoneGroup Coordinator="RINCON_K" ID="RINCON_K:1">
            <ZoneGroupMember UUID="RINCON_K" Location="http://10.0.0.5:1400/xml/device_description.xml" ZoneName="Kök"/>
            <ZoneGroupMember UUID="RINCON_V" Location="http://10.0.0.6:1400/xml/device_description.xml" ZoneName="Vardagsrum">
              <Satellite UUID="RINCON_SUB" Location="http://10.0.0.9:1400/xml/device_description.xml" ZoneName="Vardagsrum" Invisible="1"/>
            </ZoneGroupMember>
          </ZoneGroup>
          <ZoneGroup Coordinator="RINCON_S" ID="RINCON_S:2">
            <ZoneGroupMember UUID="RINCON_S" Location="http://10.0.0.7:1400/xml/device_description.xml" ZoneName="Sovrum"/>
            <ZoneGroupMember UUID="RINCON_S2" Location="http://10.0.0.8:1400/xml/device_description.xml" ZoneName="Sovrum" Invisible="1"/>
          </ZoneGroup>
        </ZoneGroups></ZoneGroupState>
    """.trimIndent()

    private val playlistMeta = """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"><item id="1006206cspotify%3aplaylist%3a37i9" restricted="true"><dc:title>Lugna favoriter</dc:title><upnp:class>object.container.playlistContainer</upnp:class><desc id="cdudn" nameSpace="urn:schemas-rinconnetworks-com:metadata-1-0/">SA_RINCON2311_X_#Svc2311-0-Token</desc></item></DIDL-Lite>"""
    private val radioMeta = """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"><item id="F00092020s24896" restricted="true"><dc:title>P3</dc:title><upnp:class>object.item.audioItem.audioBroadcast</upnp:class></item></DIDL-Lite>"""

    private val favourites = """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">""" +
        """<item id="FV:2/1" parentID="FV:2" restricted="false"><dc:title>Lugna favoriter</dc:title><upnp:class>object.itemobject.item.sonos-favorite</upnp:class><r:ordinal>0</r:ordinal>""" +
        """<res protocolInfo="x-rincon-cpcontainer:*:*:*">x-rincon-cpcontainer:1006206cspotify%3aplaylist%3a37i9?sid=12&amp;flags=8300&amp;sn=3</res>""" +
        """<upnp:albumArtURI>https://i.scdn.co/image/abc</upnp:albumArtURI><r:type>instantPlay</r:type><r:description>Spotify-spellista</r:description><r:resMD>${esc(playlistMeta)}</r:resMD></item>""" +
        """<item id="FV:2/2" parentID="FV:2" restricted="false"><dc:title>P3</dc:title><upnp:class>object.itemobject.item.sonos-favorite</upnp:class>""" +
        """<res protocolInfo="x-sonosapi-stream:*:*:*">x-sonosapi-stream:s24896?sid=254&amp;flags=8224&amp;sn=0</res>""" +
        """<upnp:albumArtURI>/getaa?s=1&amp;u=x-sonosapi-stream%3as24896</upnp:albumArtURI><r:resMD>${esc(radioMeta)}</r:resMD></item></DIDL-Lite>"""

    @Test fun `reads the household without hidden halves of pairs`() {
        val speakers = SonosClient.parseTopology(topology)
        assertEquals(listOf("Kök", "Vardagsrum", "Sovrum"), speakers.map { it.room })
        assertEquals("10.0.0.6", speakers[1].host)
        assertEquals("RINCON_K", speakers[1].coordinator)
    }

    @Test fun `reads favourites with their metadata and cover art`() {
        val items = SonosClient.parseDidl(favourites, "http://10.0.0.5:1400")
        assertEquals(listOf("Lugna favoriter", "P3"), items.map { it.title })
        assertTrue(items[0].uri.startsWith("x-rincon-cpcontainer:1006206c") && items[0].uri.contains("&flags=8300"))
        assertTrue(items[0].meta.contains("SA_RINCON2311_X_#Svc2311-0-Token"))
        assertEquals("Spotify-spellista", items[0].description)
        assertEquals("http://10.0.0.5:1400/getaa?s=1&u=x-sonosapi-stream%3as24896", items[1].art)
        assertFalse(SonosClient.isStream(items[0]))
        assertTrue(SonosClient.isStream(items[1]))
    }

    @Test fun `matches Homey speakers to Sonos rooms`() {
        val speakers = SonosClient.parseTopology(topology)
        val zones = mapOf("z-k" to Zone("z-k", "Kök"), "z-s" to Zone("z-s", "Sovrum"), "z-v" to Zone("z-v", "Vardagsrum"))
        val devices = listOf(
            Device("a", "Kök", zone = "z-k", deviceClass = "speaker", driverUri = "homey:app:com.sonos:speaker"),
            Device("b", "Sonos", zone = "z-s", deviceClass = "speaker", driverUri = "homey:app:com.sonos:speaker"),
            Device("c", "Sonos Vardagsrum Arc", zone = "z-k", deviceClass = "speaker", driverUri = "homey:app:com.sonos:speaker"),
            Device("d", "Kök", zone = "z-k", deviceClass = "light"),
            Device("e", "Köksradio", zone = "z-k", deviceClass = "speaker", driverUri = "homey:app:com.other:radio"),
        )
        val matched = SonosCards.match(devices, zones, speakers).mapValues { it.value.room }
        assertEquals(mapOf("a" to "Kök", "b" to "Sovrum", "c" to "Vardagsrum"), matched)
    }

    @Test fun `plays a playlist through the queue on the group leader`() = runTest {
        val server = MockWebServer().apply { start() }
        val host = server.hostName
        val household = Regex("10\\.0\\.0\\.\\d").replace(topology, host)
        server.enqueue(soap("GetZoneGroupState", "<ZoneGroupState>${esc(household)}</ZoneGroupState>"))
        server.enqueue(soap("Browse", "<Result>${esc(favourites)}</Result><NumberReturned>2</NumberReturned>"))
        server.enqueue(soap("GetZoneGroupState", "<ZoneGroupState>${esc(household)}</ZoneGroupState>"))
        repeat(4) { server.enqueue(soap("Ok", "")) }

        val client = SonosClient(OkHttpClient(), server.port)
        val cards = SonosCards(client) { listOf(host) }
        val zones = mapOf("z-v" to Zone("z-v", "Vardagsrum"))
        val snapshot = HomeySnapshot(zones = zones, devices = mapOf("v" to Device("v", "Vardagsrum", zone = "z-v", deviceClass = "speaker")))
        val card = cards.cards(snapshot).getValue("v").first()
        assertEquals("play_favorite", card.cardId)
        assertTrue(cards.owns(card))

        val items = cards.autocomplete(card, "item", "lugna")
        assertEquals(listOf("Lugna favoriter"), items.map { it.name })
        cards.run(card, JsonObject(mapOf("item" to items.single().raw)))

        val sent = (1..7).map { server.takeRequest() }
        assertEquals("\"urn:schemas-upnp-org:service:ZoneGroupTopology:1#GetZoneGroupState\"", sent[0].getHeader("SOAPACTION"))
        assertTrue(sent[1].body.readUtf8().contains("<ObjectID>FV:2</ObjectID>"))
        assertEquals(
            listOf("RemoveAllTracksFromQueue", "AddURIToQueue", "SetAVTransportURI", "Play"),
            sent.drop(3).map { it.getHeader("SOAPACTION")!!.substringAfter('#').trimEnd('"') },
        )
        val add = sent[4].body.readUtf8()
        assertTrue(add.contains("x-rincon-cpcontainer:1006206cspotify%3aplaylist%3a37i9?sid=12&amp;flags=8300"))
        assertTrue(add.contains("SA_RINCON2311_X_#Svc2311-0-Token"))
        // Vardagsrum is grouped with Kök, so the queue that plays is Kök's.
        assertTrue(sent[5].body.readUtf8().contains("x-rincon-queue:RINCON_K#0"))
        server.shutdown()
    }
}
