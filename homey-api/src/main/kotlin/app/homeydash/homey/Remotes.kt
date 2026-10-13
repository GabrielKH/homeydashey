package app.homeydash.homey

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What one remote button does. */
sealed interface RemoteAction {
    /** Runs a Flow card, with the key it sends when the card takes one. */
    data class Card(val card: FlowCardAction, val args: JsonObject = JsonObject(emptyMap())) : RemoteAction

    /** Presses a button capability (`volume_up`), or flips a boolean one (`onoff`, `volume_mute`) when [toggle]. */
    data class Capability(val capability: String, val toggle: Boolean) : RemoteAction
}

/** A card that sends any key picked from its list, like a Samsung, LG or Android TV app's "Send key". */
data class KeyCard(val card: FlowCardAction, val arg: FlowCardArg)

/**
 * Remotes for TVs and media boxes. Keys come from the device app's own key cards
 * (Apple TV), from a "send key" card with a list of keys (Samsung and other TVs),
 * and from Homey's standard TV capabilities (volume, channel, mute, power).
 */
object Remotes {

    private val TV_CLASSES = setOf("tv", "settopbox")
    private val KEY_CARD_WORDS = setOf("key", "keys", "button", "command", "remote", "keycode", "ircc")
    private val KEY_CARD_NOISE = setOf("app", "apps", "launch", "application", "macro")

    /** The device's "send key" card, if its app has one. */
    fun keyCard(cards: List<FlowCardAction>): KeyCard? = cards.firstNotNullOfOrNull { card ->
        val arg = card.inputArgs.singleOrNull()?.takeIf { it.type == "dropdown" || it.type == "autocomplete" } ?: return@firstNotNullOfOrNull null
        val words = FlowCards.tokens(card.cardId) + FlowCards.tokens(arg.name)
        if (words.any { it in KEY_CARD_WORDS } && words.none { it in KEY_CARD_NOISE }) KeyCard(card, arg) else null
    }

    /** Which entries of a key card's list send which remote keys. */
    fun keyItems(items: List<AutocompleteItem>): Map<RemoteKey, AutocompleteItem> {
        val result = linkedMapOf<RemoteKey, AutocompleteItem>()
        var play: AutocompleteItem? = null
        items.forEach { item ->
            val id = (item.raw["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val names = listOfNotNull(id, item.name)
            names.firstNotNullOfOrNull { keyName(it) }?.let { result.putIfAbsent(it, item) }
            if (play == null && names.any { name -> keyWords(name) == "play" }) play = item
        }
        // Some TVs only have separate play and pause keys; play is the useful one.
        play?.let { result.putIfAbsent(RemoteKey.PlayPause, it) }
        return result
    }

    /** Buttons from Homey's standard capabilities. */
    fun capabilityKeys(device: Device): Map<RemoteKey, RemoteAction> {
        val caps = device.capabilities.toSet()
        val result = linkedMapOf<RemoteKey, RemoteAction>()
        fun button(key: RemoteKey, id: String) {
            if (id in caps) result[key] = RemoteAction.Capability(id, toggle = false)
        }
        button(RemoteKey.VolumeUp, "volume_up")
        button(RemoteKey.VolumeDown, "volume_down")
        button(RemoteKey.ChannelUp, "channel_up")
        button(RemoteKey.ChannelDown, "channel_down")
        if ("volume_mute" in caps) result[RemoteKey.Mute] = RemoteAction.Capability("volume_mute", toggle = true)
        if ("onoff" in caps) result[RemoteKey.Power] = RemoteAction.Capability("onoff", toggle = true)
        return result
    }

    /** Every key the remote has: the app's key cards first, then its key list, then capabilities. */
    fun actions(
        device: Device,
        cards: List<FlowCardAction>,
        keyCard: KeyCard?,
        keyItems: Map<RemoteKey, AutocompleteItem>,
    ): Map<RemoteKey, RemoteAction> {
        val result = linkedMapOf<RemoteKey, RemoteAction>()
        FlowCards.cardKeys(cards).forEach { (key, card) -> result[key] = RemoteAction.Card(card) }
        if (keyCard != null) {
            keyItems.forEach { (key, item) ->
                val args = JsonObject(mapOf(keyCard.arg.name to FlowCards.argValue(keyCard.arg, item)))
                result.putIfAbsent(key, RemoteAction.Card(keyCard.card, args))
            }
        }
        capabilityKeys(device).forEach { (key, action) -> result.putIfAbsent(key, action) }
        return result
    }

    /** Whether a device gets a Remote button: TVs and media boxes, never plain speakers. */
    fun hasRemote(device: Device, cards: List<FlowCardAction>): Boolean {
        if (FlowCards.remoteKeys(cards).isNotEmpty()) return true
        val isTv = device.effectiveClass in TV_CLASSES || device.deviceClass in TV_CLASSES
        val keyCard = keyCard(cards)
        if (keyCard != null) {
            if (isTv || keyCard.arg.type == "autocomplete") return true
            if (FlowCards.navigation(keyItems(FlowCards.dropdownItems(keyCard.arg)).keys) >= 3) return true
        }
        return isTv && (capabilityKeys(device).keys - RemoteKey.Power).isNotEmpty() ||
            isTv && FlowCards.cardKeys(cards).isNotEmpty()
    }

    private val KEY_PREFIX = setOf("key", "keycode", "dpad", "button", "btn", "arrow", "cursor", "nav", "navigate", "press")

    /** The key's words joined, without prefixes such as `KEY_` or `KEYCODE_DPAD_`. */
    private fun keyWords(name: String): String =
        FlowCards.tokens(name.replace('/', '_')).filter { it !in KEY_PREFIX }.joinToString("")

    /** Reads a key name like `KEY_VOLUP`, `KEYCODE_DPAD_UP`, `Volume up` or `ENTER`. */
    internal fun keyName(name: String): RemoteKey? = when (keyWords(name)) {
        "up" -> RemoteKey.Up
        "down" -> RemoteKey.Down
        "left" -> RemoteKey.Left
        "right" -> RemoteKey.Right
        "enter", "ok", "select", "center", "centre", "confirm", "okenter", "enterok" -> RemoteKey.Select
        "back", "return", "goback", "backreturn", "returnback" -> RemoteKey.Back
        "home", "smarthub" -> RemoteKey.Home
        "power", "powertoggle", "poweronoff", "onoff", "standby" -> RemoteKey.Power
        "playpause", "pauseplay", "playpausetoggle", "mediaplaypause" -> RemoteKey.PlayPause
        "volup", "volumeup", "volplus", "volumeplus" -> RemoteKey.VolumeUp
        "voldown", "volumedown", "volmin", "volminus", "volumeminus" -> RemoteKey.VolumeDown
        "mute", "volumemute", "mutetoggle" -> RemoteKey.Mute
        "chup", "channelup", "chplus", "channelplus" -> RemoteKey.ChannelUp
        "chdown", "channeldown", "chminus", "channelminus" -> RemoteKey.ChannelDown
        else -> null
    }
}
