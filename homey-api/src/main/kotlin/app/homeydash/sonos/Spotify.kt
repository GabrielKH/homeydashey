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
data class SpotifyHit(
    val kind: Kind,
    val id: String,
    val name: String,
    val by: String? = null,
    val art: String? = null,
    /** In the signed-in account's library (its playlists, saved albums and podcasts, liked songs), not just found in the catalogue. */
    val mine: Boolean = false,
) {
    enum class Kind(val label: String, val type: String) {
        Track("Song", "track"), Album("Album", "album"), Playlist("Playlist", "playlist"), Artist("Artist", "artist"),
        /** A podcast; it plays its newest episode. */
        Show("Podcast", "show"),
        Episode("Episode", "episode"),
    }

    /** "Song · ABBA", or "Your playlist · Gabriel", "Liked song · ABBA", shown under the name. */
    val description: String get() = listOfNotNull(
        when {
            !mine -> kind.label
            kind == Kind.Track -> "Liked song"
            else -> "Your ${kind.label.lowercase()}"
        },
        by,
    ).joinToString(" · ")
}

/** A Spotify developer app's client ID and secret. */
data class SpotifyApp(val clientId: String, val secret: String)

/**
 * Searches Spotify's catalogue with the Web API. Only finding things goes through Spotify:
 * the speakers play them with the Spotify account already added in the Sonos app.
 * [app] is read on every call, so new keys work at once.
 *
 * Signed in ([user] gives the sign-in's refresh token), it also finds the account's own
 * playlists, private ones too, which the catalogue search doesn't find. Spotify may hand out
 * a new refresh token when the old one is used; [onUser] keeps it.
 */
class SpotifySearch(
    private val http: OkHttpClient,
    private val app: () -> SpotifyApp?,
    private val market: String = "SE",
    private val accounts: String = "https://accounts.spotify.com",
    private val api: String = "https://api.spotify.com",
    private val user: () -> String? = { null },
    private val onUser: (String) -> Unit = {},
) {
    private class Token(val forApp: SpotifyApp, val value: String, val until: Long, val refresh: String? = null)

    @Volatile private var token: Token? = null
    @Volatile private var userToken: Token? = null
    @Volatile private var own: Pair<Long, List<SpotifyHit>>? = null

    /** Why the account's own playlists couldn't be read the last time, for the log; null when they could. */
    @Volatile var ownTrouble: String? = null
        private set

    val configured: Boolean get() = app() != null

    val signedIn: Boolean get() = configured && !user().isNullOrBlank()

    /** The page where the account owner lets the app read their playlists; it comes back to [REDIRECT] with a code. */
    fun signInUrl(state: String): String? {
        val keys = app() ?: return null
        return "$accounts/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", keys.clientId.trim())
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", REDIRECT)
            .addQueryParameter("scope", SCOPES)
            .addQueryParameter("state", state)
            .addQueryParameter("show_dialog", "true")
            .build().toString()
    }

    /** Swaps the code from [signInUrl]'s page for a refresh token; returns it and the account's name. */
    suspend fun finishSignIn(code: String): Pair<String, String?> {
        val json = tokenCall(FormBody.Builder().add("grant_type", "authorization_code").add("code", code).add("redirect_uri", REDIRECT).build())
        val access = json.str("access_token") ?: throw IOException("Spotify sent no access token")
        val refresh = json.str("refresh_token") ?: throw IOException("Spotify sent no refresh token")
        val seconds = (json["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600
        userToken = app()?.let { Token(it, access, System.currentTimeMillis() + (seconds - 60) * 1000, refresh) }
        own = null
        val name = runCatching { (get("/v1/me", access = access) as? JsonObject)?.str("display_name") }.getOrNull()
        return refresh to name
    }

    /**
     * The signed-in account's library: its playlists (made or followed), saved albums, saved
     * podcasts and liked songs, read again after a few minutes. Empty when nobody signed in;
     * what Spotify refused is in [ownTrouble] (an older sign-in may only allow playlists).
     */
    suspend fun library(): List<SpotifyHit> {
        if (!signedIn) return emptyList()
        own?.takeIf { System.currentTimeMillis() - it.first < OWN_FOR_MS }?.let { return it.second }
        val access = try {
            userAccess()
        } catch (e: IOException) {
            ownTrouble = e.message
            return emptyList()
        }
        val troubles = mutableListOf<String>()
        suspend fun pages(path: String, read: (JsonObject) -> SpotifyHit?): List<SpotifyHit> = try {
            val all = mutableListOf<SpotifyHit>()
            for (page in 0 until OWN_PAGES) {
                val json = get(path, "limit" to "50", "offset" to (page * 50).toString(), access = access) as? JsonObject ?: break
                val items = (json["items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                all += items.mapNotNull(read).map { it.copy(mine = true) }
                if (items.size < 50 || (json["next"] as? JsonPrimitive)?.contentOrNull == null) break
            }
            all
        } catch (e: IOException) {
            troubles += e.message.orEmpty()
            emptyList()
        }
        fun inner(o: JsonObject, key: String) = o[key] as? JsonObject
        val playlists = pages("/v1/me/playlists") { p -> hit(SpotifyHit.Kind.Playlist, p, (p["owner"] as? JsonObject)?.str("display_name"), image(p["images"])) }
        val albums = pages("/v1/me/albums") { o -> inner(o, "album")?.let { a -> hit(SpotifyHit.Kind.Album, a, artists(a), image(a["images"])) } }
        val shows = pages("/v1/me/shows") { o -> inner(o, "show")?.let { sh -> hit(SpotifyHit.Kind.Show, sh, null, image(sh["images"])) } }
        val liked = pages("/v1/me/tracks") { o ->
            inner(o, "track")?.let { t -> hit(SpotifyHit.Kind.Track, t, artists(t), image((t["album"] as? JsonObject)?.get("images"))) }
        }
        ownTrouble = troubles.firstOrNull()?.let { "$it (sign in again to let the app read your albums, podcasts and liked songs)" }
        return (playlists + albums + shows + liked).also { own = System.currentTimeMillis() to it }
    }

    /** The library's [kind] (any kind when null) that [query] names, best first, with how well; all of it for an empty query. */
    suspend fun library(query: String, kind: SpotifyHit.Kind?): List<Pair<SpotifyHit, Double>> {
        val mine = library().filter { kind == null || it.kind == kind }
        if (query.isBlank()) return mine.map { it to 1.0 }
        val q = NameMatch.normalize(query)
        return mine.map { h ->
            val text = listOfNotNull(h.name, h.by).joinToString(" ")
            h to maxOf(NameMatch.score(query, h.name), NameMatch.score(query, text), if (q.length >= 3 && NameMatch.normalize(h.name).contains(q)) 0.7 else 0.0)
        }
            .filter { it.second >= 0.5 }
            .sortedByDescending { it.second }
    }

    /**
     * What Spotify finds for [text], in the order to offer it. "Album …", "låten …",
     * "spellistan …", "podden …" and "artisten …" look for that kind only. The account's
     * library comes first when it clearly has what was named, or when "my …" was said;
     * otherwise the whole catalogue comes first, so "the best hiphop right now" isn't
     * answered with a hiphop playlist of your own.
     */
    suspend fun search(text: String): List<SpotifyHit> {
        val (kind, query) = parse(text)
        val mineAsked = MINE.containsMatchIn(text) || LIKED.containsMatchIn(text)
        val mine = library(query, kind)
        // "min spellista Sommar", "mina poddar", "mina gillade låtar": only the library, when it has them.
        if (signedIn && mineAsked && mine.isNotEmpty()) return mine.map { it.first }
        if (query.isBlank()) return emptyList()
        // Podcasts are searched too (episodes only when asked for), so a show is found by its name.
        val kinds = kind?.let(::listOf)
            ?: listOf(SpotifyHit.Kind.Track, SpotifyHit.Kind.Album, SpotifyHit.Kind.Artist, SpotifyHit.Kind.Playlist, SpotifyHit.Kind.Show)
        val json = get(
            "/v1/search",
            "q" to query, "type" to kinds.joinToString(",") { it.type },
            // Apps in development mode get at most 10 a kind.
            "limit" to (if (kind == null) "6" else "10"), "market" to market,
        )
        val (strong, weak) = mine.partition { it.second >= OWN_IS_CLEAR }.let { (a, b) -> a.map { it.first } to b.map { it.first } }
        val found = order(query, parseSearch(json), kind).filterNot { hit -> mine.any { it.first.id == hit.id } }
        return strong + found + weak
    }

    /** The podcast [showId]'s newest episode, to play when someone asks for the podcast; null when Spotify lists none. */
    suspend fun newestEpisode(showId: String): SpotifyHit? {
        val json = get("/v1/shows/$showId/episodes", "limit" to "1", "market" to market) as? JsonObject ?: return null
        val episode = (json["items"] as? JsonArray)?.filterIsInstance<JsonObject>()?.firstOrNull() ?: return null
        return hit(SpotifyHit.Kind.Episode, episode, null, image(episode["images"]))
    }

    /** Popular songs by the artist called [name], to play when someone asks for the artist. */
    suspend fun songsBy(name: String): List<SpotifyHit> {
        val json = get("/v1/search", "q" to "artist:\"$name\"", "type" to "track", "limit" to "10", "market" to market)
        val songs = parseSearch(json)
        // The search also finds songs where the name is only part of the artist's name.
        return songs.filter { s -> s.by.orEmpty().split(", ").any { NameMatch.normalize(it) == NameMatch.normalize(name) } }.ifEmpty { songs }
    }

    private suspend fun get(path: String, vararg params: Pair<String, String>, access: String? = null): JsonElement {
        val url = (api + path).toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${access ?: token()}").build()
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 401) {
                    if (access == null) token = null else userToken = null
                }
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

    /** An access token for the signed-in account, from its refresh token, kept until it runs out. */
    private suspend fun userAccess(): String {
        val keys = app() ?: throw IOException("Add a Spotify client ID and secret in Settings → Assistant → Spotify")
        val refresh = user()?.takeIf { it.isNotBlank() } ?: throw IOException("Nobody is signed in to Spotify")
        userToken?.takeIf { it.forApp == keys && it.until > System.currentTimeMillis() }?.let { return it.value }
        val json = try {
            tokenCall(FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh).build())
        } catch (e: IOException) {
            throw IOException("Spotify sign-in no longer works; sign in again in Settings → Spotify on Sonos (${e.message})", e)
        }
        val value = json.str("access_token") ?: throw IOException("Spotify sent no access token")
        val seconds = (json["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600
        json.str("refresh_token")?.takeIf { it != refresh }?.let(onUser)
        return Token(keys, value, System.currentTimeMillis() + (seconds - 60) * 1000).also { userToken = it }.value
    }

    private suspend fun tokenCall(form: FormBody): JsonObject {
        val keys = app() ?: throw IOException("Add a Spotify client ID and secret in Settings → Assistant → Spotify")
        val request = Request.Builder()
            .url("$accounts/api/token")
            .header("Authorization", Credentials.basic(keys.clientId.trim(), keys.secret.trim()))
            .post(form)
            .build()
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException(problem(response.code, body))
                Json.parseToJsonElement(body) as? JsonObject ?: throw IOException("Spotify sent something unreadable")
            }
        }
    }

    companion object {
        /** Where Spotify sends the sign-in back to; the same address the settings ask to add to the developer app. */
        const val REDIRECT = "http://127.0.0.1:8888/callback"
        private const val SCOPES = "playlist-read-private playlist-read-collaborative user-library-read"
        private const val OWN_FOR_MS = 5 * 60 * 1000L
        private const val OWN_PAGES = 4

        /** "my", "min", "mina", "egna": the account's own playlists are meant. */
        private val MINE = Regex("(?<!\\S)(my|mine|own|min|mina|mitt|egna)(?!\\S)", RegexOption.IGNORE_CASE)

        /** How well a library item must match before it comes ahead of the whole catalogue. */
        private const val OWN_IS_CLEAR = 0.9

        /** "Liked songs", "gillade låtar": the account's liked songs. */
        private val LIKED = Regex("(?i)(liked songs|liked tracks|gillade (låtar|spår)|låtar jag gillat|mina låtar|my songs)")

        private val KIND_WORDS = listOf(
            SpotifyHit.Kind.Artist to "(songs by|music by|something by|låtar (med|av)|musik (med|av)|nå(go)?t (med|av)|the artist|the band|artisten|artist|bandet)",
            SpotifyHit.Kind.Album to "(the album|albumet|album|skivan|plattan)",
            SpotifyHit.Kind.Playlist to "(the playlist|playlist|spellistan|spellista)",
            SpotifyHit.Kind.Track to "(the song|song|the track|track|låten|låt|singeln)",
            // "Senaste avsnittet av X" is the podcast X, which plays its newest episode.
            SpotifyHit.Kind.Show to "((the )?(latest|newest|new) episode of|(det )?(senaste|nya|nyaste) avsnittet( av| med| i)?|the podcast|podcast|poddcasten|poddcast|podden|podd)",
            SpotifyHit.Kind.Episode to "(the episode|episode|avsnittet|avsnitt)",
        ).map { (kind, words) -> kind to Regex("(^|\\s)$words(\\s|$)", RegexOption.IGNORE_CASE) }
        private val SPACES = Regex("\\s+")
        private val FILLER = Regex("(?i)(?<!\\S)(the best|best|bästa|right now|just nu|at the moment|för tillfället|currently)(?!\\S)")
        private val LEAD = Regex("^(play|spela( upp)?|put on|sätt på|starta)\\s+", RegexOption.IGNORE_CASE)
        private val TAIL = Regex("\\s+(on|from|på|från|i) spotify$", RegexOption.IGNORE_CASE)
        private val BY = Regex("\\s+(by|med|av)\\s+", RegexOption.IGNORE_CASE)

        /** The kind asked for, if any, and what to search for: "spela albumet Arrival av ABBA" → Album, "Arrival ABBA". */
        fun parse(text: String): Pair<SpotifyHit.Kind?, String> {
            var q = text.trim().replace(LEAD, "").replace(TAIL, "").trim()
            var kind: SpotifyHit.Kind? = null
            for ((k, at) in KIND_WORDS) {
                if (at.containsMatchIn(q)) {
                    kind = k
                    q = q.replace(at, " ").trim()
                    break
                }
            }
            // "liked songs" are songs; "min spellista Sommar" looks for "Sommar".
            if (LIKED.containsMatchIn(q)) {
                kind = kind ?: SpotifyHit.Kind.Track
                q = q.replace(LIKED, " ").trim()
            }
            if (kind != null) q = q.replace(MINE, " ").trim()
            // Words that only say "something good" search badly: "the best hiphop right now" is hiphop.
            q = q.replace(FILLER, " ").trim()
            q = q.replace(BY, " ").replace(SPACES, " ").trim()
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
            val shows = items("shows").mapNotNull { sh -> hit(SpotifyHit.Kind.Show, sh, null, image(sh["images"])) }
            val episodes = items("episodes").mapNotNull { e -> hit(SpotifyHit.Kind.Episode, e, null, image(e["images"])) }
            return tracks + albums + artists + playlists + shows + episodes
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
    private val SID = Regex("[?&]sid=(\\d+)")
    private val SN = Regex("[?&]sn=(\\d+)")

    /** Sonos's number for Spotify in Europe; accounts in the Americas use 3079. */
    const val EUROPE = 2311

    private const val DIDL =
        """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """ +
            """xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">"""

    /** The household's Spotify account as Sonos numbers it in its own links ("sid=12&sn=3"). */
    data class Account(val sid: String, val sn: String)

    /**
     * The item to queue for [hit] (not an artist: those are played as their songs). [service] is
     * from [serviceIn]; with the [account] a song gets the same link the Sonos app gives it.
     */
    fun item(hit: SpotifyHit, service: Int = EUROPE, account: Account? = null): SonosItem {
        val encoded = "spotify%3a${hit.kind.name.lowercase()}%3a${hit.id}"
        val (key, prefix, upnpClass) = when (hit.kind) {
            SpotifyHit.Kind.Track, SpotifyHit.Kind.Episode -> Triple("00032020", "", "object.item.audioItem.musicTrack")
            // A podcast queues like a playlist, the way the Sonos app shares one.
            SpotifyHit.Kind.Show -> Triple("1006206c", "x-rincon-cpcontainer:1006206c", "object.container.playlistContainer")
            SpotifyHit.Kind.Album -> Triple("1004206c", "x-rincon-cpcontainer:1004206c", "object.container.album.musicAlbum")
            SpotifyHit.Kind.Playlist -> Triple("1006206c", "x-rincon-cpcontainer:1006206c", "object.container.playlistContainer")
            SpotifyHit.Kind.Artist -> throw IllegalArgumentException("An artist is played as its songs")
        }
        val meta = DIDL +
            """<item id="$key$encoded" parentID="" restricted="true"><dc:title>${escape(hit.name)}</dc:title>""" +
            """<upnp:class>$upnpClass</upnp:class>""" +
            """<desc id="cdudn" nameSpace="urn:schemas-rinconnetworks-com:metadata-1-0/">SA_RINCON${service}_X_#Svc$service-0-Token</desc></item></DIDL-Lite>"""
        val uri = if (hit.kind == SpotifyHit.Kind.Track && account != null) {
            "x-sonos-spotify:$encoded?sid=${account.sid}&flags=8224&sn=${account.sn}"
        } else {
            prefix + encoded
        }
        return SonosItem(hit.name, uri, meta, hit.description, hit.art)
    }

    /** The Spotify account in a Spotify favourite's link (`…spotify%3a…?sid=12&flags=8300&sn=3`). */
    fun accountIn(items: List<SonosItem>): Account? = items.asSequence()
        .filter { it.uri.contains("spotify", ignoreCase = true) }
        .mapNotNull { item ->
            val sid = SID.find(item.uri)?.groupValues?.get(1) ?: return@mapNotNull null
            val sn = SN.find(item.uri)?.groupValues?.get(1) ?: return@mapNotNull null
            Account(sid, sn)
        }
        .firstOrNull()

    /** Sonos's number for the household's Spotify account, read from a Spotify favourite or playlist. */
    fun serviceIn(items: List<SonosItem>): Int? = items.asSequence()
        .filter { it.uri.contains("spotify", ignoreCase = true) || it.meta.contains("spotify", ignoreCase = true) }
        .mapNotNull { SERVICE.find(it.meta)?.groupValues?.get(1)?.toIntOrNull() }
        .firstOrNull()

    private val SERVICE = Regex("SA_RINCON(\\d+)_")

    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
