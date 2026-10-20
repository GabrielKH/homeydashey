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
        var menu: AutocompleteItem? = null
        items.forEach { item ->
            val id = (item.raw["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val names = listOfNotNull(id, item.name)
            names.firstNotNullOfOrNull { keyName(it) }?.let { result.putIfAbsent(it, item) }
            if (play == null && names.any { name -> keyWords(name) == "play" }) play = item
            if (menu == null && names.any { name -> keyWords(name) == "menu" }) menu = item
        }
        // Some TVs only have separate play and pause keys; play is the useful one.
        play?.let { result.putIfAbsent(RemoteKey.PlayPause, it) }
        // The Apple TV calls its Back button "Menu".
        menu?.let { result.putIfAbsent(RemoteKey.Back, it) }
        return result
    }

    /** Buttons from Homey's standard capabilities. */
    fun capabilityKeys(device: Device): Map<RemoteKey, RemoteAction> = capabilityKeys(device.capabilities)

    fun capabilityKeys(capabilities: Collection<String>): Map<RemoteKey, RemoteAction> {
        val caps = capabilities.toSet()
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
    ): Map<RemoteKey, RemoteAction> = actions(device.capabilities, cards, keyCard, keyItems)

    fun actions(
        capabilities: Collection<String>,
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
        capabilityKeys(capabilities).forEach { (key, action) -> result.putIfAbsent(key, action) }
        return result
    }

    /**
     * Every button of a device's remote, as the remote sheet and the assistant press them,
     * loading a "send key" card's list with [choices] when the app gives it as a search.
     * Power is the app's own power key when it has one (what the TV's remote sends), else onoff.
     */
    suspend fun buttons(
        capabilities: Collection<String>,
        cards: List<FlowCardAction>,
        choices: suspend (FlowCardAction, FlowCardArg) -> List<AutocompleteItem>,
    ): Map<RemoteKey, RemoteAction> {
        val keyCard = keyCard(cards)
        val items = when (keyCard?.arg?.type) {
            "dropdown" -> FlowCards.dropdownItems(keyCard.arg)
            "autocomplete" -> choices(keyCard.card, keyCard.arg)
            else -> emptyList()
        }
        return actions(capabilities, cards, keyCard, keyItems(items))
    }

    /**
     * The TV in the same room that should take the volume keys, like a Siri Remote
     * that turns the TV's volume. Null when the box has no TV beside it with volume.
     */
    fun volumeTv(device: Device, others: List<Pair<Device, List<FlowCardAction>>>): Pair<Device, Map<RemoteKey, RemoteAction>>? {
        if (device.effectiveClass == "tv" || device.deviceClass == "tv") return null
        return others.asSequence()
            .filter { (other, _) -> other.id != device.id && other.zone == device.zone && (other.effectiveClass == "tv" || other.deviceClass == "tv") }
            .map { (other, cards) ->
                val keyCard = keyCard(cards)
                val items = keyCard?.arg?.takeIf { it.type == "dropdown" }?.let { keyItems(FlowCards.dropdownItems(it)) }.orEmpty()
                other to actions(other, cards, keyCard, items).filterKeys { it in VOLUME_KEYS }
            }
            .firstOrNull { (_, keys) -> RemoteKey.VolumeUp in keys || RemoteKey.VolumeDown in keys }
    }

    val VOLUME_KEYS = setOf(RemoteKey.VolumeUp, RemoteKey.VolumeDown, RemoteKey.Mute)

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
