package app.homeydash.homey

import app.homeydash.agent.NameMatch
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

    /**
     * Arguments the user has to fill in. The card's own `device` argument is implied;
     * another device argument (such as a Sonos group coordinator) is kept.
     */
    val inputArgs: List<FlowCardArg> get() = args.orEmpty().filter { !(it.type == "device" && it.name == "device") }
}

@Serializable
data class FlowCardArg(
    val name: String,
    val type: String = "text",
    val title: String? = null,
    val placeholder: String? = null,
    /** Range of a `range` or `number` argument. */
    val min: Double? = null,
    val max: Double? = null,
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

enum class RemoteKey { Up, Down, Left, Right, Select, Back, Home, PlayPause, VolumeUp, VolumeDown, Mute, Power, ChannelUp, ChannelDown }

/** A speaker app's grouping cards, such as Sonos "Group with" and "Ungroup Sonos speaker". */
data class SpeakerGrouping(
    /** Joins this speaker to the group of the speaker given in [joinArg]. */
    val join: FlowCardAction?,
    val joinArg: FlowCardArg?,
    val leave: FlowCardAction?,
    val groupVolume: FlowCardAction?,
    val groupVolumeArg: FlowCardArg?,
)

/** A source of music an app offers on a speaker, e.g. Sonos favourites or playlists. */
data class MusicSource(
    val title: String,
    val card: FlowCardAction,
    val arg: FlowCardArg,
    /** Other cards of the same kind (a twin's, the app's own), tried in turn when this one can't answer. */
    val fallbacks: List<MusicSource> = emptyList(),
) {
    /** This source and its fallbacks, in the order to try them. */
    val all: List<MusicSource> get() = listOf(copy(fallbacks = emptyList())) + fallbacks

    /** A search of a whole streaming catalogue (Spotify) rather than a list of the home's own picks. */
    val catalogue: Boolean get() = title == FlowCards.SPOTIFY
}

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

    /**
     * Other devices that are the same thing from another app, of the same kind: the same name anywhere (a hidden
     * copy often stays in the home's top zone), or a name containing the other one in the same room.
     */
    /** `uri.substringBeforeLast(':') == app`, without making a new string for every pair of devices. */
    private fun sameApp(uri: String, app: String): Boolean {
        val i = uri.lastIndexOf(':')
        return (if (i < 0) uri.length else i) == app.length && uri.startsWith(app)
    }

    fun twinsOf(devices: Map<String, Device>, deviceId: String): List<String> {
        val d = devices[deviceId] ?: return emptyList()
        val app = d.driverUri?.substringBeforeLast(':') ?: return emptyList()
        val name = normalized(d.name)
        return devices.values.filter { o ->
            // A copy Homey can't reach right now only fails; the speaker itself still works.
            if (o.id == d.id || !o.available || o.homeyClass != d.homeyClass || o.driverUri?.let { sameApp(it, app) } == true) return@filter false
            val other = normalized(o.name)
            other == name || (o.zone == d.zone && other.length >= 3 && name.length >= 3 && (other.contains(name) || name.contains(other)))
        }.map { it.id }
    }

    /** [NameMatch.normalize], remembered: [twinsOf] compares every pair of devices on each update. */
    private fun normalized(name: String): String = NAMES[name] ?: run {
        if (NAMES.size > 4096) NAMES.clear()
        NameMatch.normalize(name).also { NAMES[name] = it }
    }

    /** A device's cards, and those of its [twinsOf] that it doesn't have itself (albums on a hidden Sonos LocalAPI copy). */
    fun withTwins(devices: Map<String, Device>, cards: Map<String, List<FlowCardAction>>, deviceId: String): List<FlowCardAction> {
        val own = cards[deviceId].orEmpty()
        return own + twinsOf(devices, deviceId).flatMap { twin -> cards[twin].orEmpty().filter { c -> own.none { it.cardId == c.cardId } } }
    }

    fun byDevice(cards: List<FlowCardAction>): Map<String, List<FlowCardAction>> =
        cards.mapNotNull { c -> c.deviceId?.let { it to c } }.groupBy({ it.first }, { it.second })

    /**
     * Maps a device's argument-free cards to remote keys by their card ids
     * (`navigate_up`, `press_select`, `press_menu`, `volume_down`, …), which stay
     * English whatever language the Homey uses.
     */
    fun remoteKeys(cards: List<FlowCardAction>): Map<RemoteKey, FlowCardAction> {
        val result = cardKeys(cards)
        // Only TV boxes count as remotes: speakers also have play/pause and volume cards,
        // but no way to move around a menu.
        return if (navigation(result.keys) >= 3) result else emptyMap()
    }

    /** Every argument-free card that sends a remote key, whether or not the device is a TV. */
    fun cardKeys(cards: List<FlowCardAction>): Map<RemoteKey, FlowCardAction> {
        val result = linkedMapOf<RemoteKey, FlowCardAction>()
        cards.filter { it.inputArgs.isEmpty() }.forEach { card ->
            val key = remoteKey(card.cardId) ?: return@forEach
            result.putIfAbsent(key, card)
        }
        return result
    }

    internal fun navigation(keys: Set<RemoteKey>) =
        listOf(RemoteKey.Up, RemoteKey.Down, RemoteKey.Left, RemoteKey.Right, RemoteKey.Select).count { it in keys }

    /**
     * The words of a card id, from snake_case, kebab-case or camelCase. Remembered, as the same ids are
     * read again on every device update; they come from the Homey, so there are only so many.
     */
    internal fun tokens(cardId: String): Set<String> = TOKENS[cardId] ?: run {
        if (TOKENS.size > 4096) TOKENS.clear()
        cardId.replace(CAMEL, "$1_$2").lowercase().split('_', '-', '.', ' ').filter { it.isNotEmpty() }.toSet()
            .also { TOKENS[cardId] = it }
    }

    internal fun remoteKey(cardId: String): RemoteKey? {
        val tokens = tokens(cardId)
        fun has(vararg words: String) = words.any { it in tokens }
        return when {
            has("channel") && has("up", "next", "increase") -> RemoteKey.ChannelUp
            has("channel") && has("down", "previous", "prev", "decrease") -> RemoteKey.ChannelDown
            has("channel", "input", "source") -> null
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

    /** Grouping cards of a speaker, or null when its app has none. */
    fun grouping(cards: List<FlowCardAction>): SpeakerGrouping? {
        var join: FlowCardAction? = null
        var joinArg: FlowCardArg? = null
        var leave: FlowCardAction? = null
        var volume: FlowCardAction? = null
        var volumeArg: FlowCardArg? = null
        cards.forEach { card ->
            val t = tokens(card.cardId)
            if (t.any { it in GROUP_NOISE }) return@forEach
            val args = card.inputArgs
            when {
                ("ungroup" in t || ("leave" in t && "group" in t) || "standalone" in t) && args.isEmpty() -> if (leave == null) leave = card
                "group" in t && "volume" in t && t.none { it in RELATIVE } && args.size == 1 &&
                    args[0].type in NUMBER_TYPES -> if (volume == null) { volume = card; volumeArg = args[0] }
                ("group" in t || "join" in t) && "volume" !in t && args.size == 1 &&
                    args[0].type in PICK_TYPES -> if (join == null) { join = card; joinArg = args[0] }
            }
        }
        return if (join == null && leave == null) null else SpeakerGrouping(join, joinArg, leave, volume, volumeArg)
    }

    private val GROUP_NOISE = setOf("tv", "autoplay", "membership", "changed")
    private val CAMEL = Regex("([a-z0-9])([A-Z])")
    private val TOKENS = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()
    private val NAMES = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val RELATIVE = setOf("adjust", "relative")
    private val NUMBER_TYPES = setOf("range", "number")
    private val PICK_TYPES = setOf("device", "autocomplete", "dropdown")

    /** A card that opens an app chosen from a list, like the Apple TV's "Launch App". */
    fun appLauncher(cards: List<FlowCardAction>): MusicSource? = cards.firstNotNullOfOrNull { card ->
        val id = card.cardId.lowercase()
        val arg = card.inputArgs.singleOrNull()?.takeIf { it.type == "autocomplete" || it.type == "dropdown" }
        if (arg != null && "app" in id && ("launch" in id || "open" in id || "start" in id)) MusicSource(card.title ?: "Apps", card, arg) else null
    }

    /** Cards that play something picked from a list: favourites, playlists, albums, radio. */
    fun musicSources(cards: List<FlowCardAction>): List<MusicSource> = cards.mapNotNull { card ->
        val id = card.cardId.lowercase()
        val arg = card.inputArgs.firstOrNull { it.type == "autocomplete" }
            ?: card.inputArgs.firstOrNull { it.type == "dropdown" } ?: return@mapNotNull null
        // Other arguments must have a safe default (a play mode, a "shuffle" box), never a volume.
        if (card.inputArgs.any { it !== arg && it.type != "dropdown" && it.type != "checkbox" }) return@mapNotNull null
        val kind = MUSIC_WORDS.firstOrNull { it in id } ?: return@mapNotNull null
        MusicSource(MUSIC_TITLES.getValue(kind), card, arg)
    }.sortedBy { MUSIC_WORDS.indexOfFirst { w -> w in it.card.cardId.lowercase() } }
        // One tab per kind: the device's own card first, then a twin's, then the app's own.
        .groupBy { it.title }.values.map { same -> same.first().copy(fallbacks = same.drop(1)) }

    const val SPOTIFY = "Spotify"
    private val MUSIC_WORDS = listOf("favorite", "favourite", "album", "playlist", "radio", "station", "artist", "library", "spotify")
    private val MUSIC_TITLES = mapOf(
        "favorite" to "Favourites", "favourite" to "Favourites", "album" to "Albums", "playlist" to "Playlists",
        "radio" to "Radio", "station" to "Radio", "artist" to "Artists", "library" to "Library", "spotify" to SPOTIFY,
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

    /** The arguments to run a music source with: the picked item, and the defaults of the rest. */
    fun musicArgs(source: MusicSource, item: AutocompleteItem): JsonObject = JsonObject(
        source.card.inputArgs.mapNotNull { arg ->
            when {
                arg === source.arg -> arg.name to argValue(arg, item)
                arg.type == "dropdown" -> arg.values?.firstOrNull()?.let { arg.name to JsonPrimitive(it.id) }
                arg.type == "checkbox" -> arg.name to JsonPrimitive(false)
                else -> null
            }
        }.toMap()
    )
}
