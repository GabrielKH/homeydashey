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
            FAVOURITE -> client.favourites(speaker.host)
            PLAYLIST -> client.playlists(speaker.host)
            SPOTIFY -> return spotifyChoices(query)
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
        if (card.cardId != SPOTIFY) {
            client.play(current, leader.host, SonosItem(field("name"), field("uri"), field("meta")))
            return
        }

        val search = spotify ?: throw IOException("Spotify isn't set up")
        // Sonos's numbers for the household's Spotify account, from its Spotify favourites and playlists.
        if (spotifyService == null || spotifyAccount == null) {
            runCatching { client.favourites(speaker.host) + client.playlists(speaker.host) }.getOrNull()?.let { saved ->
                spotifyService = spotifyService ?: SpotifyOnSonos.serviceIn(saved)
                spotifyAccount = spotifyAccount ?: SpotifyOnSonos.accountIn(saved)
            }
        }
        val service = spotifyService ?: SpotifyOnSonos.EUROPE
        val account = spotifyAccount
        val kind = SpotifyHit.Kind.entries.firstOrNull { it.name == field("kind") } ?: throw IOException("Nothing was picked to play")
        val hits = if (kind == SpotifyHit.Kind.Artist) {
            search.songsBy(field("name")).ifEmpty { throw IOException("Spotify has no songs by ${field("name")}") }
        } else {
            listOf(SpotifyHit(kind, field("id"), field("name")))
        }
        try {
            client.play(current, leader.host, hits.map { SpotifyOnSonos.item(it, service, account) })
        } catch (e: IOException) {
            throw IOException("Sonos couldn't play that from Spotify. Is Spotify added in the Sonos app? (${e.message})", e)
        }
    }

    /** Spotify's hits for [query]; nothing until something is typed. */
    private suspend fun spotifyChoices(query: String): List<AutocompleteItem> {
        val search = spotify ?: return emptyList()
        if (query.isBlank()) return emptyList()
        return search.search(query).map { hit ->
            AutocompleteItem(
                hit.name, hit.description, hit.art,
                JsonObject(mapOf("name" to JsonPrimitive(hit.name), "kind" to JsonPrimitive(hit.kind.name), "id" to JsonPrimitive(hit.id))),
            )
        }
    }

    private fun speakerFor(card: FlowCardAction): SonosSpeaker {
        val deviceId = card.id.removePrefix("$OWNER:").substringBeforeLast(':')
        return rooms[deviceId] ?: throw IOException("Can't find that Sonos speaker on the Wi-Fi")
    }

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
        private const val FAVOURITE = "play_favorite"
        private const val PLAYLIST = "play_playlist"
        const val SPOTIFY = "play_spotify"

        /**
         * Which Sonos room each Homey speaker is: the same name, or for a device of a Sonos
         * app, a room named inside its name ("Sonos Kök") or else like its Homey zone.
         */
        fun match(devices: Collection<Device>, zones: Map<String, Zone>, speakers: List<SonosSpeaker>): Map<String, SonosSpeaker> {
            if (speakers.isEmpty()) return emptyMap()
            val byRoom = speakers.groupBy { NameMatch.normalize(it.room) }.mapValues { it.value.first() }
            return devices.mapNotNull { d ->
                val sonos = d.driverUri.orEmpty().contains("sonos", ignoreCase = true)
                if (!sonos && d.homeyClass != "speaker") return@mapNotNull null
                val name = NameMatch.normalize(d.name)
                val zone = d.zone?.let(zones::get)?.name?.let(NameMatch::normalize)
                val speaker = byRoom[name]
                    ?: (if (sonos) byRoom.entries.firstOrNull { (room, _) -> room.length >= 3 && Regex("(^| )${Regex.escape(room)}( |$)").containsMatchIn(name) }?.value else null)
                    ?: (if (sonos) zone?.let(byRoom::get) else null)
                    ?: return@mapNotNull null
                d.id to speaker
            }.toMap()
        }
    }
}
