package app.homeydash.sonos

import app.homeydash.agent.NameMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Something found on Spotify: a song, an album, a playlist or an artist. */
data class SpotifyHit(val kind: Kind, val id: String, val name: String, val by: String? = null, val art: String? = null) {
    enum class Kind(val label: String) { Track("Song"), Album("Album"), Playlist("Playlist"), Artist("Artist") }

    /** "Song · ABBA", shown under the name. */
    val description: String get() = listOfNotNull(kind.label, by).joinToString(" · ")
}

/** A Spotify developer app's client ID and secret. */
data class SpotifyApp(val clientId: String, val secret: String)

/**
 * Searches Spotify's catalogue with the Web API. Only finding things goes through Spotify:
 * the speakers play them with the Spotify account already added in the Sonos app.
 * [app] is read on every call, so new keys work at once.
 */
class SpotifySearch(
    private val http: OkHttpClient,
    private val app: () -> SpotifyApp?,
    private val market: String = "SE",
    private val accounts: String = "https://accounts.spotify.com",
    private val api: String = "https://api.spotify.com",
) {
    private class Token(val forApp: SpotifyApp, val value: String, val until: Long)

    @Volatile private var token: Token? = null

    val configured: Boolean get() = app() != null

    /**
     * What Spotify finds for [text], in the order to offer it. "Album …", "låten …",
     * "spellistan …" and "artisten …" look for that kind only.
     */
    suspend fun search(text: String): List<SpotifyHit> {
        val (kind, query) = parse(text)
        if (query.isBlank()) return emptyList()
        val kinds = kind?.let(::listOf) ?: SpotifyHit.Kind.entries
        val json = get(
            "/v1/search",
            "q" to query, "type" to kinds.joinToString(",") { it.name.lowercase() },
            // Apps in development mode get at most 10 a kind.
            "limit" to (if (kind == null) "6" else "10"), "market" to market,
        )
        return order(query, parseSearch(json), kind)
    }

    /** Popular songs by the artist called [name], to play when someone asks for the artist. */
    suspend fun songsBy(name: String): List<SpotifyHit> {
        val json = get("/v1/search", "q" to "artist:\"$name\"", "type" to "track", "limit" to "10", "market" to market)
        val songs = parseSearch(json)
        // The search also finds songs where the name is only part of the artist's name.
        return songs.filter { s -> s.by.orEmpty().split(", ").any { NameMatch.normalize(it) == NameMatch.normalize(name) } }.ifEmpty { songs }
    }

    private suspend fun get(path: String, vararg params: Pair<String, String>): JsonElement {
        val url = (api + path).toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${token()}").build()
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 401) token = null
                if (!response.isSuccessful) throw IOException(problem(response.code, body))
                Json.parseToJsonElement(body)
            }
        }
    }

    /** An access token for the app itself (no user sign-in is needed to search), kept until it runs out. */
    private suspend fun token(): String {
        val keys = app() ?: throw IOException("Add a Spotify client ID and secret in Settings → Assistant → Spotify")
        token?.takeIf { it.forApp == keys && it.until > System.currentTimeMillis() }?.let { return it.value }
        val request = Request.Builder()
            .url("$accounts/api/token")
            .header("Authorization", Credentials.basic(keys.clientId.trim(), keys.secret.trim()))
            .post(FormBody.Builder().add("grant_type", "client_credentials").build())
            .build()
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException(if (response.code in 400..401) "Spotify didn't accept the client ID and secret" else problem(response.code, body))
                }
                val json = Json.parseToJsonElement(body) as JsonObject
                val value = json.str("access_token") ?: throw IOException("Spotify sent no access token")
                val seconds = (json["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600
                Token(keys, value, System.currentTimeMillis() + (seconds - 60) * 1000).also { token = it }.value
            }
        }
    }

    companion object {
        private val KIND_WORDS = listOf(
            SpotifyHit.Kind.Artist to "(songs by|music by|something by|låtar (med|av)|musik (med|av)|nå(go)?t (med|av)|the artist|the band|artisten|artist|bandet)",
            SpotifyHit.Kind.Album to "(the album|albumet|album|skivan|plattan)",
            SpotifyHit.Kind.Playlist to "(the playlist|playlist|spellistan|spellista)",
            SpotifyHit.Kind.Track to "(the song|song|the track|track|låten|låt|singeln)",
        )
        private val LEAD = Regex("^(play|spela( upp)?|put on|sätt på|starta)\\s+", RegexOption.IGNORE_CASE)
        private val TAIL = Regex("\\s+(on|from|på|från|i) spotify$", RegexOption.IGNORE_CASE)
        private val BY = Regex("\\s+(by|med|av)\\s+", RegexOption.IGNORE_CASE)

        /** The kind asked for, if any, and what to search for: "spela albumet Arrival av ABBA" → Album, "Arrival ABBA". */
        fun parse(text: String): Pair<SpotifyHit.Kind?, String> {
            var q = text.trim().replace(LEAD, "").replace(TAIL, "").trim()
            var kind: SpotifyHit.Kind? = null
            for ((k, words) in KIND_WORDS) {
                val at = Regex("(^|\\s)$words(\\s|$)", RegexOption.IGNORE_CASE)
                if (at.containsMatchIn(q)) {
                    kind = k
                    q = q.replace(at, " ").trim()
                    break
                }
            }
            q = q.replace(BY, " ").replace(Regex("\\s+"), " ").trim()
            return kind to q
        }

        /**
         * Hits in the order to offer them: songs, albums, artists, playlists, but the artist
         * first when the search is just an artist's name ("Kent").
         */
        fun order(query: String, hits: List<SpotifyHit>, kind: SpotifyHit.Kind?): List<SpotifyHit> {
            if (kind != null) return hits.filter { it.kind == kind }
            val q = NameMatch.normalize(query)
            val artistAsked = hits.firstOrNull { it.kind == SpotifyHit.Kind.Artist }?.let { NameMatch.normalize(it.name) == q } == true
            val rank = if (artistAsked) {
                listOf(SpotifyHit.Kind.Artist, SpotifyHit.Kind.Track, SpotifyHit.Kind.Album, SpotifyHit.Kind.Playlist)
            } else {
                listOf(SpotifyHit.Kind.Track, SpotifyHit.Kind.Album, SpotifyHit.Kind.Artist, SpotifyHit.Kind.Playlist)
            }
            return hits.sortedBy { rank.indexOf(it.kind) }
        }

        /** Parses `GET /v1/search`; playlists can come back as nulls, which are skipped. */
        fun parseSearch(json: JsonElement): List<SpotifyHit> {
            val root = json as? JsonObject ?: return emptyList()
            fun items(key: String) = ((root[key] as? JsonObject)?.get("items") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            val tracks = items("tracks").mapNotNull { t ->
                hit(SpotifyHit.Kind.Track, t, artists(t), image((t["album"] as? JsonObject)?.get("images")))
            }
            val albums = items("albums").mapNotNull { a -> hit(SpotifyHit.Kind.Album, a, artists(a), image(a["images"])) }
            val artists = items("artists").mapNotNull { a -> hit(SpotifyHit.Kind.Artist, a, null, image(a["images"])) }
            val playlists = items("playlists").mapNotNull { p ->
                hit(SpotifyHit.Kind.Playlist, p, (p["owner"] as? JsonObject)?.str("display_name"), image(p["images"]))
            }
            return tracks + albums + artists + playlists
        }

        private fun hit(kind: SpotifyHit.Kind, o: JsonObject, by: String?, art: String?): SpotifyHit? {
            val id = o.str("id") ?: return null
            val name = o.str("name") ?: return null
            return SpotifyHit(kind, id, name, by, art)
        }

        private fun artists(o: JsonObject): String? =
            (o["artists"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("name") }?.joinToString(", ")?.ifBlank { null }

        /** The picture closest to 300 pixels wide, big enough for a tile without a large download. */
        private fun image(images: JsonElement?): String? =
            (images as? JsonArray)?.filterIsInstance<JsonObject>()
                ?.minByOrNull { kotlin.math.abs(((it["width"] as? JsonPrimitive)?.intOrNull ?: 300) - 300) }
                ?.str("url")

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

        private fun problem(code: Int, body: String): String {
            val message = runCatching {
                val e = (Json.parseToJsonElement(body) as JsonObject)["error"]
                (e as? JsonObject)?.str("message") ?: (e as? JsonPrimitive)?.contentOrNull
            }.getOrNull()
            return when {
                code == 403 && message?.contains("premium", ignoreCase = true) == true -> "Spotify needs the developer app's owner to have Premium"
                code == 429 -> "Spotify asks to wait a moment before searching again"
                else -> "Spotify said no ($code${message?.let { ": $it" } ?: ""})"
            }
        }
    }
}

/**
 * How Sonos plays something from Spotify: the same "share link" items the Sonos app makes
 * when you paste a Spotify link, tied to the Spotify account already added to Sonos.
 */
object SpotifyOnSonos {
    /** Sonos's number for Spotify in Europe; accounts in the Americas use 3079. */
    const val EUROPE = 2311

    private const val DIDL =
        """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """ +
            """xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">"""

    /** The item to queue for [hit] (not an artist: those are played as their songs). [service] is from [serviceIn]. */
    fun item(hit: SpotifyHit, service: Int = EUROPE): SonosItem {
        val encoded = "spotify%3a${hit.kind.name.lowercase()}%3a${hit.id}"
        val (key, prefix, upnpClass) = when (hit.kind) {
            SpotifyHit.Kind.Track -> Triple("00032020", "", "object.item.audioItem.musicTrack")
            SpotifyHit.Kind.Album -> Triple("1004206c", "x-rincon-cpcontainer:1004206c", "object.container.album.musicAlbum")
            SpotifyHit.Kind.Playlist -> Triple("1006206c", "x-rincon-cpcontainer:1006206c", "object.container.playlistContainer")
            SpotifyHit.Kind.Artist -> throw IllegalArgumentException("An artist is played as its songs")
        }
        val meta = DIDL +
            """<item id="$key$encoded" parentID="" restricted="true"><dc:title>${escape(hit.name)}</dc:title>""" +
            """<upnp:class>$upnpClass</upnp:class>""" +
            """<desc id="cdudn" nameSpace="urn:schemas-rinconnetworks-com:metadata-1-0/">SA_RINCON${service}_X_#Svc$service-0-Token</desc></item></DIDL-Lite>"""
        return SonosItem(hit.name, prefix + encoded, meta, hit.description, hit.art)
    }

    /** Sonos's number for the household's Spotify account, read from a Spotify favourite or playlist. */
    fun serviceIn(items: List<SonosItem>): Int? = items.asSequence()
        .filter { it.uri.contains("spotify", ignoreCase = true) || it.meta.contains("spotify", ignoreCase = true) }
        .mapNotNull { SERVICE.find(it.meta)?.groupValues?.get(1)?.toIntOrNull() }
        .firstOrNull()

    private val SERVICE = Regex("SA_RINCON(\\d+)_")

    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
