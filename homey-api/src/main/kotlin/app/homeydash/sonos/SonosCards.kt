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
 * app runs itself, straight on the speakers, whichever Homey app added them.
 * [find] returns addresses of Sonos speakers on the Wi-Fi; one that answers is enough.
 */
class SonosCards(
    private val client: SonosClient,
    private val find: suspend () -> List<String>,
) : CardSource {

    @Volatile private var speakers: List<SonosSpeaker> = emptyList()
    @Volatile private var rooms: Map<String, SonosSpeaker> = emptyMap()

    override suspend fun cards(snapshot: HomeySnapshot): Map<String, List<FlowCardAction>> {
        speakers = household()
        rooms = match(snapshot.devices.values, snapshot.zones, speakers)
        return rooms.mapValues { (deviceId, _) -> listOf(card(deviceId, FAVOURITE, "Play Sonos favourite"), card(deviceId, PLAYLIST, "Play Sonos playlist")) }
    }

    override fun owns(card: FlowCardAction) = card.ownerUri == OWNER

    override suspend fun autocomplete(card: FlowCardAction, arg: String, query: String): List<AutocompleteItem> {
        val speaker = speakerFor(card)
        val items = when (card.cardId) {
            FAVOURITE -> client.favourites(speaker.host)
            PLAYLIST -> client.playlists(speaker.host)
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
        val item = SonosItem(field("name"), field("uri"), field("meta"))
        // Groups change often, so ask who leads this speaker's group right now.
        val now = runCatching { client.speakers(speaker.host) }.getOrDefault(speakers).ifEmpty { speakers }
        val current = now.firstOrNull { it.uuid == speaker.uuid } ?: speaker
        val leader = now.firstOrNull { it.uuid == current.coordinator } ?: current
        client.play(current, leader.host, item)
    }

    private fun speakerFor(card: FlowCardAction): SonosSpeaker {
        val deviceId = card.id.removePrefix("$OWNER:").substringBeforeLast(':')
        return rooms[deviceId] ?: throw IOException("Can't find that Sonos speaker on the Wi-Fi")
    }

    /** Every room, asked of the speakers we already know first, then of any that can be found. */
    private suspend fun household(): List<SonosSpeaker> {
        val known = speakers.map { it.host }
        for (host in (known + find()).distinct()) {
            val found = runCatching { client.speakers(host) }.getOrNull()
            if (!found.isNullOrEmpty()) return found
        }
        return emptyList()
    }

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
