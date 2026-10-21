package app.homeydash.sonos

import app.homeydash.agent.NameMatch
import app.homeydash.homey.AutocompleteItem
import app.homeydash.homey.CardSource
import app.homeydash.homey.Device
import app.homeydash.homey.FlowCardAction
import app.homeydash.homey.FlowCardArg
import app.homeydash.homey.HomeySnapshot
import app.homeydash.homey.Zone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/**
 * Gives every Sonos speaker in Homey "Play favourite" and "Play playlist" cards that the
 * app runs itself, straight on the speakers, whichever Homey app added them. With a
 * [spotify] search set up, they also get "Play from Spotify": any song, album, artist
 * or playlist, played with the Spotify account added in the Sonos app.
 * [find] returns addresses of Sonos speakers on the Wi-Fi; one that answers is enough.
 */
class SonosCards(
    private val client: SonosClient,
    private val find: suspend () -> List<String>,
    private val spotify: SpotifySearch? = null,
) : CardSource {

    @Volatile private var speakers: List<SonosSpeaker> = emptyList()
    @Volatile private var rooms: Map<String, SonosSpeaker> = emptyMap()
    @Volatile private var spotifyService: Int? = null
    @Volatile private var spotifyAccount: SpotifyOnSonos.Account? = null

    /**
     * Favourites and playlists belong to the whole Sonos household, the same on every speaker,
     * so they are read once and shared by all speakers for a while instead of on every pick.
     */
    @Volatile private var saved: Map<String, Pair<Long, List<SonosItem>>> = emptyMap()

    private suspend fun shared(cardId: String, host: String, read: suspend (String) -> List<SonosItem>): List<SonosItem> {
        val now = System.currentTimeMillis()
        saved[cardId]?.takeIf { now - it.first < SAVED_FOR_MS }?.let { return it.second }
        return read(host).also { saved = saved + (cardId to (now to it)) }
    }

    /** What each speaker last played from the app, newest first, so "add it to my favourites" knows what "it" is. */
    @Volatile private var played: Map<String, List<SonosItem>> = emptyMap()

    private fun remember(deviceId: String, item: SonosItem) {
        played = played + (deviceId to (listOf(item) + played[deviceId].orEmpty().filterNot { it.uri == item.uri }).take(RECENT))
    }

    /** What went wrong the last time the cards were made, for [missing]. */
    @Volatile private var trouble: String? = null

    /** Whether the cards last handed out had "Play from Spotify"; null before the first time. */
    @Volatile var spotifyShown: Boolean? = null
        private set

    override suspend fun cards(snapshot: HomeySnapshot): Map<String, List<FlowCardAction>> {
        speakers = household()
        rooms = match(snapshot.devices.values, snapshot.zones, speakers)
        val withSpotify = spotify?.configured == true
        spotifyShown = withSpotify
        trouble = when {
            speakers.isEmpty() -> "the app found no Sonos speaker on the Wi-Fi"
            !withSpotify -> "no Spotify keys in the assistant settings"
            else -> null
        }
        return rooms.mapValues { (deviceId, _) ->
            listOfNotNull(
                card(deviceId, FAVOURITE, "Play Sonos favourite"),
                card(deviceId, PLAYLIST, "Play Sonos playlist"),
                if (withSpotify) card(deviceId, SPOTIFY, "Play from Spotify") else null,
                card(deviceId, SAVE, "Add to Sonos favourites"),
            )
        }
    }

    override fun owns(card: FlowCardAction) = card.ownerUri == OWNER

    override fun missing(deviceId: String): String? = when {
        spotifyShown == null -> "the Sonos speakers haven't been looked for yet"
        trouble != null && (speakers.isEmpty() || deviceId in rooms) -> trouble
        deviceId !in rooms -> "it doesn't match a Sonos room; the rooms are ${speakers.map { it.room }.distinct().joinToString()}"
        else -> null
    }

    override suspend fun autocomplete(card: FlowCardAction, arg: String, query: String): List<AutocompleteItem> {
        val speaker = speakerFor(card)
        val items = when (card.cardId) {
            FAVOURITE -> shared(FAVOURITE, speaker.host, client::favourites)
            PLAYLIST -> shared(PLAYLIST, speaker.host, client::playlists)
            SPOTIFY -> return spotifyChoices(query)
            SAVE -> return saveChoices(card, query)
            else -> emptyList()
        }
        val q = query.trim()
        return items
            .filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) || NameMatch.score(q, it.title) > 0 }
            .map { item ->
                AutocompleteItem(
                    item.title, item.description, item.art,
                    JsonObject(
                        buildMap {
                            put("name", JsonPrimitive(item.title))
                            put("uri", JsonPrimitive(item.uri))
                            put("meta", JsonPrimitive(item.meta))
                        },
                    ),
                )
            }
    }

    override suspend fun run(card: FlowCardAction, args: JsonObject) {
        val speaker = speakerFor(card)
        val picked = args.values.firstOrNull() as? JsonObject ?: throw IOException("Nothing was picked to play")
        fun field(name: String) = picked[name]?.jsonPrimitive?.contentOrNull.orEmpty()
        // Groups change often, so ask who leads this speaker's group right now.
        val now = runCatching { client.speakers(speaker.host) }.getOrDefault(speakers).ifEmpty { speakers }
        val current = now.firstOrNull { it.uuid == speaker.uuid } ?: speaker
        val leader = now.firstOrNull { it.uuid == current.coordinator } ?: current
        val deviceId = deviceOf(card)
        if (card.cardId == SAVE) {
            val item = if (field("kind").isNotEmpty()) {
                val kind = SpotifyHit.Kind.entries.firstOrNull { it.name == field("kind") } ?: throw IOException("Nothing was picked to add")
                if (kind == SpotifyHit.Kind.Artist) throw IOException("An artist can't be a Sonos favourite here; pick one of their albums or a playlist")
                spotifyItem(speaker.host, SpotifyHit(kind, field("id"), field("name"), art = field("art").ifEmpty { null }, mine = field("mine") == "true"))
            } else {
                SonosItem(field("name"), field("uri"), field("meta"), field("description").ifEmpty { null }, field("art").ifEmpty { null })
            }
            if (shared(FAVOURITE, speaker.host, client::favourites).any { it.uri == item.uri || NameMatch.normalize(it.title) == NameMatch.normalize(item.title) }) {
                throw IOException("\"${item.title}\" is already a Sonos favourite")
            }
            try {
                client.addFavourite(speaker.host, item)
            } catch (e: IOException) {
                throw IOException("Sonos didn't add \"${item.title}\" to the favourites (${e.message})", e)
            }
            // Read again next time, so it can be played by name straight away.
            saved = saved - FAVOURITE
            return
        }
        if (card.cardId != SPOTIFY) {
            val item = SonosItem(field("name"), field("uri"), field("meta"), field("description").ifEmpty { null }, field("art").ifEmpty { null })
            client.play(current, leader.host, item)
            if (card.cardId == PLAYLIST) remember(deviceId, item)
            return
        }

        val search = spotify ?: throw IOException("Spotify isn't set up")
        val kind = SpotifyHit.Kind.entries.firstOrNull { it.name == field("kind") } ?: throw IOException("Nothing was picked to play")
        val asked = SpotifyHit(kind, field("id"), field("name"), art = field("art").ifEmpty { null }, mine = field("mine") == "true")
        val hits = when (kind) {
            SpotifyHit.Kind.Artist -> search.songsBy(field("name")).ifEmpty { throw IOException("Spotify has no songs by ${field("name")}") }
            // A podcast plays its newest episode, as "spela podden X" means; the whole show if Spotify lists none.
            SpotifyHit.Kind.Show -> listOf(runCatching { search.newestEpisode(asked.id) }.getOrNull() ?: asked)
            else -> listOf(asked)
        }
        val items = hits.map { spotifyItem(speaker.host, it) }
        try {
            client.play(current, leader.host, items)
        } catch (e: IOException) {
            throw IOException("Sonos couldn't play that from Spotify. Is Spotify added in the Sonos app? (${e.message})", e)
        }
        // An artist plays as a pile of songs, which isn't one thing to keep.
        // A podcast is kept as the show, so "save it" saves the podcast, not tonight's episode.
        if (kind != SpotifyHit.Kind.Artist) remember(deviceId, if (kind == SpotifyHit.Kind.Show) spotifyItem(speaker.host, asked) else items.single())
    }

    /** How the speakers play [hit], with Sonos's numbers for the household's Spotify account read from its favourites and playlists. */
    private suspend fun spotifyItem(host: String, hit: SpotifyHit): SonosItem {
        if (spotifyService == null || spotifyAccount == null) {
            runCatching { client.favourites(host) + client.playlists(host) }.getOrNull()?.let { saved ->
                spotifyService = spotifyService ?: SpotifyOnSonos.serviceIn(saved)
                spotifyAccount = spotifyAccount ?: SpotifyOnSonos.accountIn(saved)
            }
        }
        return SpotifyOnSonos.item(hit, spotifyService ?: SpotifyOnSonos.EUROPE, spotifyAccount)
    }

    /**
     * What can be added to the favourites: what this speaker (then any speaker) played last,
     * newest first, then what Spotify finds. Nothing typed offers only what was played.
     */
    private suspend fun saveChoices(card: FlowCardAction, query: String): List<AutocompleteItem> {
        val deviceId = deviceOf(card)
        val recent = (played[deviceId].orEmpty() + played.filterKeys { it != deviceId }.values.flatten()).distinctBy { it.uri }
        val q = query.trim()
        val matching = recent.filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) || NameMatch.score(q, it.title) >= 0.5 }
        val own = matching.map { item ->
            AutocompleteItem(
                item.title, item.description, item.art,
                JsonObject(
                    buildMap {
                        put("name", JsonPrimitive(item.title))
                        put("uri", JsonPrimitive(item.uri))
                        put("meta", JsonPrimitive(item.meta))
                        item.description?.let { put("description", JsonPrimitive(it)) }
                        item.art?.let { put("art", JsonPrimitive(it)) }
                    },
                ),
            )
        }
        if (q.isEmpty()) return own
        val found = runCatching { spotifyChoices(q) }.getOrDefault(emptyList()).filter { it.raw["kind"]?.jsonPrimitive?.contentOrNull != SpotifyHit.Kind.Artist.name }
        return own + found.filterNot { f -> own.any { it.name == f.name } }
    }

    /** Spotify's hits for [query]; before anything is typed, the signed-in account's own playlists (or nothing). */
    private suspend fun spotifyChoices(query: String): List<AutocompleteItem> {
        val search = spotify ?: return emptyList()
        if (query.isBlank() && !search.signedIn) return emptyList()
        return search.search(query).map { hit ->
            AutocompleteItem(
                hit.name, hit.description, hit.art,
                JsonObject(
                    buildMap {
                        put("name", JsonPrimitive(hit.name))
                        put("kind", JsonPrimitive(hit.kind.name))
                        put("id", JsonPrimitive(hit.id))
                        put("description", JsonPrimitive(hit.description))
                        hit.art?.let { put("art", JsonPrimitive(it)) }
                        if (hit.mine) put("mine", JsonPrimitive("true"))
                        // One of Spotify's own charts, asked for by its everyday name ("topplistan").
                        if (hit.by == "Spotify" && SpotifySearch.chartsFor(query).any { it.id == hit.id }) put("chart", JsonPrimitive("true"))
                    },
                ),
            )
        }
    }

    private fun deviceOf(card: FlowCardAction) = card.id.removePrefix("$OWNER:").substringBeforeLast(':')

    private fun speakerFor(card: FlowCardAction): SonosSpeaker =
        rooms[deviceOf(card)] ?: throw IOException("Can't find that Sonos speaker on the Wi-Fi")

    /** Every room, asked of the speakers we already know first, then of any that can be found. */
    private suspend fun household(): List<SonosSpeaker> {
        // Searching the Wi-Fi takes seconds, so it only runs when no known speaker answers.
        val known = speakers.map { it.host }.distinct()
        known.firstNotNullOfOrNull { ask(it) }?.let { return it }
        return (find().distinct() - known.toSet()).firstNotNullOfOrNull { ask(it) }.orEmpty()
    }

    private suspend fun ask(host: String): List<SonosSpeaker>? =
        runCatching { client.speakers(host) }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun card(deviceId: String, cardId: String, title: String) = FlowCardAction(
        id = "$OWNER:$deviceId:$cardId",
        ownerUri = OWNER,
        title = title,
        args = listOf(FlowCardArg("item", "autocomplete")),
    )

    companion object {
        const val OWNER = "homeydash:sonos"
        /** How long the household's favourites and playlists are kept before they're read again. */
        private const val SAVED_FOR_MS = 10 * 60 * 1000L
        private const val FAVOURITE = "play_favorite"
        private const val PLAYLIST = "play_playlist"
        const val SPOTIFY = "play_spotify"
        /** Adds something to the Sonos favourites; not a music source, so it never plays. */
        const val SAVE = "save_to_sonos"
        private const val RECENT = 8

        /**
         * Which Sonos room each Homey speaker is: the same name, or for a device of a Sonos
         * app, a room named inside its name ("Sonos Kök") or else like its Homey zone.
         */
        fun match(devices: Collection<Device>, zones: Map<String, Zone>, speakers: List<SonosSpeaker>): Map<String, SonosSpeaker> {
            if (speakers.isEmpty()) return emptyMap()
            // "Sonos Kök" in the Sonos app is the Homey speaker "Kök".
            val byRoom = speakers.groupBy { room(it.room) }.mapValues { it.value.first() }
            fun within(a: String, b: String) = a.length >= 3 && Regex("(^| )${Regex.escape(a)}( |$)").containsMatchIn(b)
            val candidates = devices.filter { d -> d.driverUri.orEmpty().contains("sonos", ignoreCase = true) || d.homeyClass == "speaker" }
            // The speaker's own id, MAC or address in what the Homey app stores, whatever the names are.
            val byId = candidates.mapNotNull { d -> speakers.firstOrNull { s -> identifies(d, s) }?.let { d.id to it } }.toMap()
            val found = byId + candidates.filter { it.id !in byId }.mapNotNull { d ->
                val sonos = d.driverUri.orEmpty().contains("sonos", ignoreCase = true)
                val name = room(d.name)
                val zone = d.zone?.let(zones::get)?.name?.let(::room)
                val speaker = byRoom[name]
                    ?: (if (sonos) byRoom.entries.firstOrNull { (r, _) -> within(r, name) || within(name, r) }?.value else null)
                    ?: (if (sonos) zone?.let(byRoom::get) else null)
                    ?: return@mapNotNull null
                d.id to speaker
            }.toMap()
            // One Sonos speaker left over and one Homey Sonos speaker (or copies of it) without a
            // room: they are the same one, named differently ("Beam" and "Sovrum").
            val leftRooms = speakers.distinctBy { it.room }.filter { s -> found.values.none { it.room == s.room } }
            val leftDevices = candidates.filter { it.id !in found && it.driverUri.orEmpty().contains("sonos", ignoreCase = true) }
            if (leftRooms.size == 1 && leftDevices.map { NameMatch.normalize(it.name) }.distinct().size == 1) {
                return found + leftDevices.associate { it.id to leftRooms.single() }
            }
            return found
        }

        /** Whether [d]'s data or settings name [s]: its RINCON id, its MAC (inside that id) or its IP. */
        fun identifies(d: Device, s: SonosSpeaker): Boolean {
            val values = buildList { listOfNotNull(d.data, d.settings).forEach { strings(it, this) } }.map { it.lowercase() }
            if (values.isEmpty()) return false
            val uuid = s.uuid.lowercase()
            val mac = Regex("^rincon_([0-9a-f]{12})").find(uuid)?.groupValues?.get(1)
            val host = s.host.lowercase()
            return values.any { v ->
                v.contains(uuid) || v == host || v.startsWith("$host:") || v.contains("//$host:") || v.contains("//$host/") ||
                    (mac != null && v.replace(":", "").replace("-", "").contains(mac))
            }
        }

        private fun strings(e: kotlinx.serialization.json.JsonElement, into: MutableList<String>) {
            when (e) {
                is JsonObject -> e.values.forEach { strings(it, into) }
                is kotlinx.serialization.json.JsonArray -> e.forEach { strings(it, into) }
                is JsonPrimitive -> if (e.isString) into += e.content
                else -> Unit
            }
        }

        private fun room(name: String) = NameMatch.normalize(name).removePrefix("sonos ").removeSuffix(" sonos").trim()
    }
}
