package app.homeydash.sonos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/** One Sonos room on the network: the speaker that answers for it and the group it plays in. */
data class SonosSpeaker(val uuid: String, val room: String, val host: String, val coordinator: String)

/** A Sonos favourite or saved playlist, with what the speaker needs to play it again. */
data class SonosItem(
    val title: String,
    val uri: String,
    val meta: String,
    val description: String? = null,
    val art: String? = null,
)

/**
 * Talks to Sonos speakers directly over their local UPnP control port (1400), the same
 * way the Sonos app does on the Wi-Fi. Favourites and saved playlists live on every
 * speaker, so any speaker can list them and any group can play them.
 */
class SonosClient(private val http: OkHttpClient, private val port: Int = PORT) {

    /** Every visible room, as one speaker (any of them) sees the household. */
    suspend fun speakers(host: String): List<SonosSpeaker> =
        parseTopology(soap(host, TOPOLOGY, "GetZoneGroupState", emptyList()).text("ZoneGroupState").orEmpty())

    suspend fun favourites(host: String): List<SonosItem> = browse(host, "FV:2")

    suspend fun playlists(host: String): List<SonosItem> = browse(host, "SQ:")

    private suspend fun browse(host: String, objectId: String): List<SonosItem> {
        val answer = soap(
            host, CONTENT, "Browse",
            listOf(
                "ObjectID" to objectId, "BrowseFlag" to "BrowseDirectChildren", "Filter" to "*",
                "StartingIndex" to "0", "RequestedCount" to "500", "SortCriteria" to "",
            ),
        )
        return parseDidl(answer.text("Result").orEmpty(), "http://$host:$port")
    }

    /**
     * Adds [item] to the household's Sonos favourites, the way the Sonos app's "Add to My
     * Sonos" does, so it shows up in every Sonos app and can be played by name. Returns the
     * new favourite's id when the speaker says it.
     */
    suspend fun addFavourite(host: String, item: SonosItem): String? {
        val answer = soap(host, CONTENT, "CreateObject", listOf("ContainerID" to "FV:2", "Elements" to favouriteDidl(item)))
        return answer.text("ObjectID")
    }

    /** Plays [item] on the group [speaker] is in: radio straight away, anything else through a fresh queue. */
    suspend fun play(speaker: SonosSpeaker, coordinatorHost: String, item: SonosItem) {
        play(speaker, coordinatorHost, listOf(item))
    }

    /** Plays [items] one after another on the group [speaker] is in, in a fresh queue (a stream plays on its own). */
    suspend fun play(speaker: SonosSpeaker, coordinatorHost: String, items: List<SonosItem>) {
        val first = items.firstOrNull() ?: return
        if (isStream(first)) {
            soap(coordinatorHost, TRANSPORT, "SetAVTransportURI", listOf("InstanceID" to "0", "CurrentURI" to first.uri, "CurrentURIMetaData" to first.meta))
        } else {
            soap(coordinatorHost, TRANSPORT, "RemoveAllTracksFromQueue", listOf("InstanceID" to "0"))
            var firstTrack: String? = null
            items.filterNot(::isStream).forEach { item ->
                val added = soap(
                    coordinatorHost, TRANSPORT, "AddURIToQueue",
                    listOf(
                        "InstanceID" to "0", "EnqueuedURI" to item.uri, "EnqueuedURIMetaData" to item.meta,
                        "DesiredFirstTrackNumberEnqueued" to "0", "EnqueueAsNext" to "0",
                    ),
                )
                if (firstTrack == null) firstTrack = added.text("FirstTrackNumberEnqueued")
            }
            soap(coordinatorHost, TRANSPORT, "SetAVTransportURI", listOf("InstanceID" to "0", "CurrentURI" to "x-rincon-queue:${speaker.coordinator}#0", "CurrentURIMetaData" to ""))
            // Jump to what was just added: a group already playing its queue (or a Spotify session)
            // otherwise carries on with the song it was on.
            runCatching { soap(coordinatorHost, TRANSPORT, "Seek", listOf("InstanceID" to "0", "Unit" to "TRACK_NR", "Target" to (firstTrack ?: "1"))) }
        }
        soap(coordinatorHost, TRANSPORT, "Play", listOf("InstanceID" to "0", "Speed" to "1"))
        ensurePlaying(coordinatorHost, first.takeUnless(::isStream)?.let(::songOf))
    }

    /**
     * Waits until the group really plays, and plays the song asked for: Sonos can take a
     * moment to load a Spotify song, and sometimes stays stopped after "Play" or carries on
     * with what it had. Tries "Play" (and the jump to the song) again, then says it failed.
     */
    private suspend fun ensurePlaying(host: String, song: String?) {
        var nudged = 0
        repeat(CHECKS) { check ->
            delay(CHECK_MS)
            val state = runCatching { soap(host, TRANSPORT, "GetTransportInfo", listOf("InstanceID" to "0")).text("CurrentTransportState") }.getOrNull()
            if (state == "TRANSITIONING" || state == null) return@repeat
            val right = song == null || runCatching {
                songOf(soap(host, TRANSPORT, "GetPositionInfo", listOf("InstanceID" to "0")).text("TrackURI").orEmpty()) == song
            }.getOrDefault(true)
            if (state == "PLAYING" && right) return
            // Stopped, paused, or on another song: nudge it, at most twice, a little apart.
            if (nudged < 2 && check >= 2 * nudged + 1) {
                nudged++
                if (!right) runCatching { soap(host, TRANSPORT, "Seek", listOf("InstanceID" to "0", "Unit" to "TRACK_NR", "Target" to "1")) }
                runCatching { soap(host, TRANSPORT, "Play", listOf("InstanceID" to "0", "Speed" to "1")) }
            }
        }
        throw IOException("Sonos loaded it but didn't start playing")
    }

    private suspend fun soap(host: String, service: Service, action: String, args: List<Pair<String, String>>): Element =
        withContext(Dispatchers.IO) {
            val body = buildString {
                append("""<?xml version="1.0" encoding="utf-8"?>""")
                append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""")
                append("""<u:$action xmlns:u="${service.type}">""")
                args.forEach { (name, value) -> append("<$name>").append(escape(value)).append("</$name>") }
                append("</u:$action></s:Body></s:Envelope>")
            }
            val request = Request.Builder()
                .url("http://$host:$port${service.path}")
                .header("SOAPACTION", "\"${service.type}#$action\"")
                .post(body.toRequestBody(XML))
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val code = runCatching { parse(text).text("errorCode") }.getOrNull()
                    throw IOException("Sonos said no to $action" + (code?.let { " (error $it)" } ?: " (${response.code})"))
                }
                parse(text)
            }
        }

    private class Service(val path: String, val type: String)

    companion object {
        private const val PORT = 1400
        private const val CHECKS = 10
        private const val CHECK_MS = 700L

        /** The Spotify song a link plays ("spotify:track:…"), or null for anything else. */
        fun songOf(item: SonosItem): String? = songOf(item.uri)

        fun songOf(uri: String): String? =
            SONG.find(uri)?.groupValues?.get(1)?.let { "spotify:track:$it" }

        private val SONG = Regex("spotify(?::|%3a)track(?::|%3a)([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
        private val XML = "text/xml; charset=\"utf-8\"".toMediaType()
        private val TOPOLOGY = Service("/ZoneGroupTopology/Control", "urn:schemas-upnp-org:service:ZoneGroupTopology:1")
        private val CONTENT = Service("/MediaServer/ContentDirectory/Control", "urn:schemas-upnp-org:service:ContentDirectory:1")
        private val TRANSPORT = Service("/MediaRenderer/AVTransport/Control", "urn:schemas-upnp-org:service:AVTransport:1")

        private const val DIDL =
            """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """ +
                """xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">"""

        /**
         * The favourite the Sonos app itself would make for [item]: its title, how to play it,
         * and the item's own metadata inside, which is what plays it later.
         */
        fun favouriteDidl(item: SonosItem): String {
            val scheme = item.uri.substringBefore(':', "x-rincon-cpcontainer")
            return DIDL + "<item><dc:title>${escape(item.title)}</dc:title><r:type>instantPlay</r:type>" +
                """<res protocolInfo="${escape(scheme)}:*:*:*">${escape(item.uri)}</res>""" +
                (item.art?.let { "<upnp:albumArtURI>${escape(it)}</upnp:albumArtURI>" } ?: "") +
                "<r:description>${escape(item.description ?: "Spotify")}</r:description>" +
                "<r:resMD>${escape(item.meta)}</r:resMD></item></DIDL-Lite>"
        }

        /** Radio and other endless streams can't go in a queue; they're set as the source instead. */
        private val STREAMS = listOf(
            "x-sonosapi-stream:", "x-sonosapi-radio:", "x-rincon-mp3radio:", "hls-radio:",
            "x-sonosapi-hls:", "x-rincon-stream:", "aac:",
        )

        fun isStream(item: SonosItem): Boolean =
            STREAMS.any { item.uri.startsWith(it) } || item.meta.contains("audioBroadcast")

        /** Parses `ZoneGroupState`: each group's coordinator and its visible members (not the second half of a stereo pair). */
        fun parseTopology(xml: String): List<SonosSpeaker> {
            if (xml.isBlank()) return emptyList()
            val root = parse(xml)
            return root.all("ZoneGroup").flatMap { group ->
                val coordinator = group.getAttribute("Coordinator")
                group.childElements("ZoneGroupMember")
                    .filter { it.getAttribute("Invisible") != "1" }
                    .mapNotNull { m ->
                        val host = runCatching { URI(m.getAttribute("Location")).host }.getOrNull() ?: return@mapNotNull null
                        SonosSpeaker(m.getAttribute("UUID"), m.getAttribute("ZoneName"), host, coordinator)
                    }
            }
        }

        /** Parses a DIDL-Lite browse result. [base] makes the speaker's own cover art paths whole. */
        fun parseDidl(xml: String, base: String): List<SonosItem> {
            if (xml.isBlank()) return emptyList()
            val root = parse(xml)
            return root.childElements().filter { it.localName == "item" || it.localName == "container" }.mapNotNull { e ->
                val title = e.text("title") ?: return@mapNotNull null
                val uri = e.text("res") ?: return@mapNotNull null
                // A favourite carries the original item's metadata; a saved playlist is its own.
                val meta = e.text("resMD") ?: ""
                val art = (e.text("albumArtURI") ?: e.text("albumArtURI", inside = meta))
                    ?.let { if (it.startsWith("/")) base + it else it }
                SonosItem(title, uri, meta, e.text("description"), art)
            }
        }

        private fun parse(xml: String): Element {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                isExpandEntityReferences = false
            }
            return factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        }

        private fun Element.all(name: String): List<Element> {
            val nodes = getElementsByTagNameNS("*", name)
            return (0 until nodes.length).map { nodes.item(it) as Element }
        }

        private fun Element.childElements(name: String? = null): List<Element> {
            val nodes = childNodes
            return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { name == null || it.localName == name }
        }

        private fun Element.text(name: String): String? = all(name).firstOrNull()?.textContent?.takeIf { it.isNotBlank() }

        private fun Element.text(name: String, inside: String): String? =
            if (inside.isBlank()) null else runCatching { parse(inside).text(name) }.getOrNull()

        private fun escape(value: String) = value
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
