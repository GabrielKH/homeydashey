package app.homeydash.agent

import app.homeydash.homey.RemoteKey

/** Remote buttons as the assistant names them: "down x3, ok", in English or Swedish. */
object RemoteKeys {
    const val NAMES = "up, down, left, right, ok, back, home, play, volume up, volume down, mute, power, channel up, channel down"
    private const val MAX_PRESSES = 30

    private val WORDS: Map<String, RemoteKey> = mapOf(
        "up" to RemoteKey.Up, "upp" to RemoteKey.Up,
        "down" to RemoteKey.Down, "ner" to RemoteKey.Down, "ned" to RemoteKey.Down,
        "left" to RemoteKey.Left, "vanster" to RemoteKey.Left,
        "right" to RemoteKey.Right, "hoger" to RemoteKey.Right,
        "ok" to RemoteKey.Select, "select" to RemoteKey.Select, "enter" to RemoteKey.Select, "center" to RemoteKey.Select, "valj" to RemoteKey.Select,
        "back" to RemoteKey.Back, "tillbaka" to RemoteKey.Back, "menu" to RemoteKey.Back, "meny" to RemoteKey.Back,
        "home" to RemoteKey.Home, "hem" to RemoteKey.Home,
        "play" to RemoteKey.PlayPause, "pause" to RemoteKey.PlayPause, "playpause" to RemoteKey.PlayPause, "spela" to RemoteKey.PlayPause, "paus" to RemoteKey.PlayPause, "pausa" to RemoteKey.PlayPause,
        "volumeup" to RemoteKey.VolumeUp, "volup" to RemoteKey.VolumeUp, "louder" to RemoteKey.VolumeUp, "hojvolym" to RemoteKey.VolumeUp, "volymupp" to RemoteKey.VolumeUp, "hogre" to RemoteKey.VolumeUp,
        "volumedown" to RemoteKey.VolumeDown, "voldown" to RemoteKey.VolumeDown, "quieter" to RemoteKey.VolumeDown, "sankvolym" to RemoteKey.VolumeDown, "volymner" to RemoteKey.VolumeDown, "lagre" to RemoteKey.VolumeDown,
        "mute" to RemoteKey.Mute, "tyst" to RemoteKey.Mute, "ljudav" to RemoteKey.Mute,
        "power" to RemoteKey.Power, "onoff" to RemoteKey.Power, "standby" to RemoteKey.Power, "stromknapp" to RemoteKey.Power,
        "channelup" to RemoteKey.ChannelUp, "chup" to RemoteKey.ChannelUp, "nextchannel" to RemoteKey.ChannelUp, "kanalupp" to RemoteKey.ChannelUp,
        "channeldown" to RemoteKey.ChannelDown, "chdown" to RemoteKey.ChannelDown, "previouschannel" to RemoteKey.ChannelDown, "kanalner" to RemoteKey.ChannelDown,
    )

    private val REPEAT = Regex("""^(.*?)\s*(?:x\s*(\d+)|(\d+)\s*(?:x|times|ganger|gang)?)$""")
    private val LEADING = Regex("""^(\d+)\s*(?:x|times|ganger|gang)?\s+(.+)$""")

    /** The presses in order, or null when a button isn't known or there are none. */
    fun parse(keys: String): List<RemoteKey>? {
        val result = mutableListOf<RemoteKey>()
        for (raw in keys.split(',', ';', '\n', '+')) {
            var part = NameMatch.normalize(raw).trim()
            if (part.isEmpty()) continue
            var times = 1
            REPEAT.find(part)?.let { m ->
                part = m.groupValues[1].trim()
                times = (m.groupValues[2].ifEmpty { m.groupValues[3] }).toIntOrNull() ?: 1
            } ?: LEADING.find(part)?.let { m ->
                times = m.groupValues[1].toIntOrNull() ?: 1
                part = m.groupValues[2].trim()
            }
            val key = WORDS[part.replace(" ", "").replace("_", "").replace("-", "")] ?: return null
            repeat(times.coerceIn(1, MAX_PRESSES)) { result += key }
            if (result.size >= MAX_PRESSES) break
        }
        return result.take(MAX_PRESSES).ifEmpty { null }
    }

    fun name(key: RemoteKey): String = when (key) {
        RemoteKey.Select -> "ok"
        RemoteKey.PlayPause -> "play/pause"
        RemoteKey.VolumeUp -> "volume up"
        RemoteKey.VolumeDown -> "volume down"
        RemoteKey.ChannelUp -> "channel up"
        RemoteKey.ChannelDown -> "channel down"
        else -> key.name.lowercase()
    }
}
