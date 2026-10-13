package app.homeydash.homey

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Flow "Then" card. Apps expose controls that have no capability this way,
 * for example the Apple TV app's remote keys and the Sonos app's "Play favorite".
 */
@Serializable
data class FlowCardAction(
    /** e.g. `homey:device:<deviceId>:navigate_up`. */
    val id: String,
    val ownerUri: String? = null,
    val uri: String? = null,
    val title: String? = null,
    val args: List<FlowCardArg>? = null,
) {
    /** The part of [id] that names the card, e.g. `navigate_up`. */
    val cardId: String get() = id.substringAfterLast(':')

    /** The `:uri` the API routes on: the first three parts of [id]. */
    val routeUri: String get() = id.split(':').take(3).joinToString(":")

    /** The device the card belongs to, when it is a device card. */
    val deviceId: String?
        get() = (ownerUri ?: uri)?.takeIf { it.startsWith("homey:device:") }?.removePrefix("homey:device:")
            ?: id.takeIf { it.startsWith("homey:device:") }?.removePrefix("homey:device:")?.substringBefore(':')

    /** Arguments the user has to fill in; a `device` argument is implied by device cards. */
    val inputArgs: List<FlowCardArg> get() = args.orEmpty().filter { it.type != "device" }
}

@Serializable
data class FlowCardArg(
    val name: String,
    val type: String = "text",
    val title: String? = null,
    val placeholder: String? = null,
    /** Choices of a `dropdown` argument. */
    val values: List<FlowDropdownValue>? = null,
)

@Serializable
data class FlowDropdownValue(val id: String, val label: String? = null)

/** One entry an `autocomplete` argument offers, such as a Sonos favourite or an Apple TV app. */
data class AutocompleteItem(
    val name: String,
    val description: String?,
    /** An image URL or Homey image path, when the app gives one. */
    val image: String?,
    /** The whole entry; it is sent back as the argument's value. */
    val raw: JsonObject,
)

enum class RemoteKey { Up, Down, Left, Right, Select, Back, Home, PlayPause, VolumeUp, VolumeDown, Mute, Power }

/** A source of music an app offers on a speaker, e.g. Sonos favourites or playlists. */
data class MusicSource(val title: String, val card: FlowCardAction, val arg: FlowCardArg)

object FlowCards {

    /** Parses `GET /api/manager/flow/flowcardaction`, which is a map by id (or a list on some versions). */
    fun parseActions(json: JsonElement): List<FlowCardAction> {
        val items = when (json) {
            is JsonObject -> json.values
            is JsonArray -> json
            else -> emptyList()
        }
        return items.mapNotNull { runCatching { HomeyJson.decodeFromJsonElement(FlowCardAction.serializer(), it) }.getOrNull() }
    }

    fun byDevice(cards: List<FlowCardAction>): Map<String, List<FlowCardAction>> =
        cards.filter { it.deviceId != null }.groupBy { it.deviceId!! }

    /**
     * Maps a device's argument-free cards to remote keys by their card ids
     * (`navigate_up`, `press_select`, `press_menu`, `volume_down`, …), which stay
     * English whatever language the Homey uses.
     */
    fun remoteKeys(cards: List<FlowCardAction>): Map<RemoteKey, FlowCardAction> {
        val result = mutableMapOf<RemoteKey, FlowCardAction>()
        cards.filter { it.inputArgs.isEmpty() }.forEach { card ->
            val key = remoteKey(card.cardId) ?: return@forEach
            result.putIfAbsent(key, card)
        }
        return result
    }

    internal fun remoteKey(cardId: String): RemoteKey? {
        val tokens = cardId.lowercase().split('_', '-', '.', ' ').filter { it.isNotEmpty() }.toSet()
        fun has(vararg words: String) = words.any { it in tokens }
        return when {
            has("volume") && has("up", "increase") -> RemoteKey.VolumeUp
            has("volume") && has("down", "decrease") -> RemoteKey.VolumeDown
            has("mute") && has("toggle") -> RemoteKey.Mute
            has("volume", "mute", "unmute", "relative") -> null
            has("play") && (has("pause") || has("toggle")) -> RemoteKey.PlayPause
            has("app", "account", "launch", "track", "chapter", "next", "previous", "seek") -> null
            has("up") -> RemoteKey.Up
            has("down") -> RemoteKey.Down
            has("left") -> RemoteKey.Left
            has("right") -> RemoteKey.Right
            has("select", "ok", "enter", "center", "centre") -> RemoteKey.Select
            has("menu", "back", "return") -> RemoteKey.Back
            has("home") -> RemoteKey.Home
            has("toggle") && has("on", "off", "onoff", "power") -> RemoteKey.Power
            else -> null
        }
    }

    /** A card that opens an app chosen from a list, like the Apple TV's "Launch App". */
    fun appLauncher(cards: List<FlowCardAction>): MusicSource? = cards.firstNotNullOfOrNull { card ->
        val id = card.cardId.lowercase()
        val arg = card.inputArgs.singleOrNull()?.takeIf { it.type == "autocomplete" || it.type == "dropdown" }
        if (arg != null && "app" in id && ("launch" in id || "open" in id || "start" in id)) MusicSource(card.title ?: "Apps", card, arg) else null
    }

    /** Cards that play something picked from a list: favourites, playlists, albums, radio. */
    fun musicSources(cards: List<FlowCardAction>): List<MusicSource> = cards.mapNotNull { card ->
        val id = card.cardId.lowercase()
        val arg = card.inputArgs.firstOrNull { it.type == "autocomplete" || it.type == "dropdown" } ?: return@mapNotNull null
        if (card.inputArgs.size > 1) return@mapNotNull null
        val kind = MUSIC_WORDS.firstOrNull { it in id } ?: return@mapNotNull null
        MusicSource(MUSIC_TITLES.getValue(kind), card, arg)
    }.sortedBy { MUSIC_WORDS.indexOfFirst { w -> w in it.card.cardId.lowercase() } }

    private val MUSIC_WORDS = listOf("favorite", "favourite", "album", "playlist", "radio", "station", "artist", "library")
    private val MUSIC_TITLES = mapOf(
        "favorite" to "Favourites", "favourite" to "Favourites", "album" to "Albums", "playlist" to "Playlists",
        "radio" to "Radio", "station" to "Radio", "artist" to "Artists", "library" to "Library",
    )

    fun parseAutocomplete(json: JsonElement): List<AutocompleteItem> {
        val items = (json as? JsonArray) ?: ((json as? JsonObject)?.get("results") as? JsonArray) ?: return emptyList()
        return items.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.string("name") ?: obj.string("title") ?: return@mapNotNull null
            val image = obj.string("image") ?: obj.string("icon") ?: obj.string("albumArtUri") ?: obj.string("albumArtURI")
            AutocompleteItem(name, obj.string("description"), image, obj)
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

    /** A dropdown argument's choices as list entries, so dropdowns and autocompletes look alike. */
    fun dropdownItems(arg: FlowCardArg): List<AutocompleteItem> = arg.values.orEmpty().map {
        AutocompleteItem(it.label ?: it.id, null, null, JsonObject(mapOf("id" to JsonPrimitive(it.id))))
    }

    /** The value to send for [item]: dropdowns take the bare id, autocompletes the whole entry. */
    fun argValue(arg: FlowCardArg, item: AutocompleteItem): JsonElement =
        if (arg.type == "dropdown") item.raw.getValue("id").jsonPrimitive else item.raw
}
