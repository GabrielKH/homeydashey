package app.homeydash.agent

import app.homeydash.homey.FlowCardAction
import app.homeydash.homey.FlowCardArg
import app.homeydash.homey.FlowCards
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A speaker's Flow card that can say the assistant's answer: either one that plays an
 * audio URL (Sonos "Play (url) at volume") or one that speaks a text itself ("Say").
 */
data class AnnounceCard(val card: FlowCardAction, val arg: FlowCardArg, val kind: Kind, val volumeArg: FlowCardArg?) {
    enum class Kind { Url, Text }

    /** Arguments for the card: [content] is the URL or the text, [volume] 0–100. */
    fun args(content: String, volume: Int): JsonObject {
        val map = linkedMapOf<String, JsonPrimitive>(arg.name to JsonPrimitive(content))
        volumeArg?.let { v ->
            val max = v.max ?: 100.0
            val scaled = if (max <= 1.0) volume / 100.0 else volume * max / 100.0
            map[v.name] = JsonPrimitive(scaled.coerceIn(v.min ?: 0.0, max))
        }
        return JsonObject(map)
    }
}

object Announce {
    private val URL_WORDS = setOf("url", "uri", "stream", "clip", "mp3", "file", "link")
    private val TEXT_WORDS = setOf("say", "speak", "tts", "announce", "announcement", "speech")

    /** The best card on a speaker for answers; a URL card wins, since the tablet then picks the voice. */
    fun find(cards: List<FlowCardAction>): AnnounceCard? {
        val found = cards.mapNotNull { card ->
            val t = FlowCards.tokens(card.cardId)
            val args = card.inputArgs
            val volume = args.firstOrNull { it.type == "range" || it.type == "number" }
                ?.takeIf { "volume" in FlowCards.tokens(it.name) || "vol" in FlowCards.tokens(it.name) || args.size == 2 }
            val rest = args - setOfNotNull(volume)
            val text = rest.singleOrNull()?.takeIf { it.type == "text" } ?: return@mapNotNull null
            val argWords = FlowCards.tokens(text.name)
            when {
                t.any { it in URL_WORDS } || argWords.any { it in URL_WORDS } -> AnnounceCard(card, text, AnnounceCard.Kind.Url, volume)
                t.any { it in TEXT_WORDS } -> AnnounceCard(card, text, AnnounceCard.Kind.Text, volume)
                else -> null
            }
        }
        return found.firstOrNull { it.kind == AnnounceCard.Kind.Url } ?: found.firstOrNull()
    }
}
