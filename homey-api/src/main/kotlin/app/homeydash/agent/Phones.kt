package app.homeydash.agent

import app.homeydash.homey.Device
import app.homeydash.homey.FlowCardAction
import app.homeydash.homey.FlowCards
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Who is asking, when the tablet knows them by voice or face. */
data class Asker(val id: String, val name: String)

/** Whose phones the assistant may ring and find. */
enum class PhoneSharing(val label: String, val detail: String) {
    Own("Only your own", "It rings and finds only the phone of the person it recognises by voice or face."),
    RingAny("Ring anyone's", "Anyone it recognises can ring any phone, but it only says where your own phone is."),
    Anyone("Anyone", "Anyone at the tablet can ring and find every phone, also voices it doesn't know."),
}

/** A phone's owner when it belongs to the whole home, like a house iPad. */
const val SHARED_PHONE = "*"

/**
 * Phones and tablets from Homey's Apple Find My app: ring one so it can be found in the house,
 * and say whether it is home. Each phone belongs to a person, and only that person may find it
 * unless the owner chose otherwise ([PhoneSharing]).
 */
object Phones {
    /** Homey apps whose devices are phones to find: Apple Find My (`com.apple.findmy`). */
    private val APPS = listOf("findmy", "find.my", "icloud")

    fun isPhone(device: Device): Boolean {
        val app = device.driverUri?.removePrefix("homey:app:")?.substringBefore(':')?.lowercase() ?: return false
        return APPS.any { it in app }
    }

    /** The card that makes the phone play its sound ("Play sound on device"). */
    fun ringCard(cards: List<FlowCardAction>): FlowCardAction? = cards.firstOrNull { card ->
        val t = FlowCards.tokens(card.cardId)
        ("sound" in t || "ring" in t || "ping" in t) && "message" !in t &&
            card.inputArgs.all { it.type in RING_ARG_TYPES }
    }

    /**
     * The card that asks Apple for the phone's location once ("Update device data"), so the
     * Find My app's polling can stay off and the phone's GPS only wakes when someone asks.
     */
    fun refreshCard(cards: List<FlowCardAction>): FlowCardAction? = cards.firstOrNull { card ->
        val t = FlowCards.tokens(card.cardId)
        ("update" in t || "refresh" in t) && "interval" !in t && card.inputArgs.isEmpty()
    }

    /** The arguments to ring with: a short subject where the card asks for one, the defaults for the rest. */
    fun ringArgs(card: FlowCardAction, from: String): JsonObject = JsonObject(
        card.inputArgs.mapNotNull { arg ->
            when (arg.type) {
                "text" -> arg.name to JsonPrimitive(from)
                "dropdown" -> arg.values?.firstOrNull()?.let { arg.name to JsonPrimitive(it.id) }
                "checkbox" -> arg.name to JsonPrimitive(false)
                else -> null
            }
        }.toMap()
    )

    /** Capabilities that would tell where exactly a phone is; the assistant never reads them. */
    fun isCoordinates(capabilityId: String): Boolean = COORDINATES.containsMatchIn(capabilityId)

    /** The person id [phone] belongs to: the owner's choice in Settings, or a name in the phone's ("Gabriels iPhone"). */
    fun ownerOf(phone: AgentDevice, settings: AgentSettings): String? =
        settings.phoneOwners[phone.id] ?: settings.people.entries.firstOrNull { (_, name) -> named(phone.name, name) }?.key

    /** Whether [phoneName] has [person]'s first name in it: "Gabriels iPhone", "Gabriel’s iPad", "iPhone Gabriel". */
    fun named(phoneName: String, person: String): Boolean {
        val first = NameMatch.normalize(person).split(' ').firstOrNull()?.takeIf { it.length >= 2 } ?: return false
        return NameMatch.normalize(phoneName.replace("'", "").replace("’", "")).split(' ')
            .any { it == first || it == first + "s" }
    }

    /** What may be done with one phone for whoever asks. */
    enum class Right { None, Ring, All }

    fun rightTo(phone: AgentDevice, settings: AgentSettings): Right {
        val owner = ownerOf(phone, settings)
        val asker = settings.asker
        return when {
            settings.phoneSharing == PhoneSharing.Anyone -> Right.All
            owner == SHARED_PHONE -> Right.All
            asker == null -> Right.None
            owner == asker.id -> Right.All
            settings.phoneSharing == PhoneSharing.RingAny -> Right.Ring
            else -> Right.None
        }
    }

    /** "home, battery 54 %, charging": what a phone's capabilities say, never its coordinates. */
    fun status(phone: AgentDevice): String {
        val parts = mutableListOf<String>()
        phone.capabilities.forEach { c ->
            val id = c.id.lowercase()
            when {
                id == "measure_battery" -> (c.value as? JsonPrimitive)?.doubleOrNull?.let { parts += "battery ${it.toInt()} %" }
                "charging" in id -> bool(c.value)?.let { if (it) parts += "charging" }
                ("home" in id || "presence" in id || "present" in id) && "distance" !in id -> bool(c.value)?.let { parts.add(0, if (it) "home" else "away") }
                "distance" in id -> (c.value as? JsonPrimitive)?.doubleOrNull?.let { m ->
                    parts += "${distance(m, c.units)} from home"
                }
            }
        }
        if (phone.unreachable) parts += "Homey can't reach it right now"
        return parts.joinToString(", ").ifBlank { "nothing known about where it is" }
    }

    private fun distance(value: Double, units: String?): String {
        val metres = if (units?.trim()?.lowercase() == "km") value * 1000 else value
        return if (metres < 1000) "${metres.toInt()} m" else "%.1f km".format(java.util.Locale.ROOT, metres / 1000)
    }

    private fun bool(v: JsonElement): Boolean? = (v as? JsonPrimitive)?.booleanOrNull

    private val RING_ARG_TYPES = setOf("text", "dropdown", "checkbox")
    private val COORDINATES = Regex("""(?i)lat|lon|lng|location|address|coord|gps|place""")
}

/**
 * A phone that was rung, kept until someone says it was found (Gabriel, 2026-10-22): a ring that
 * wasn't heard, or an answer cut off, mustn't end the search. After [ASK_AFTER_MS] the assistant
 * asks whether it was found; no answer within [ANSWER_MS] rings it again, up to [MAX_RINGS] rings.
 */
class PhoneHunt(
    /** "Gabriels iPhone": what is being looked for. */
    val names: List<String>,
    private val now: () -> Long = System::currentTimeMillis,
    private val ringIt: suspend () -> Boolean,
) {
    var rings = 1
        private set
    /** "Did you find it?" was asked and waits for its answer. */
    var asked = false
        private set
    private var lastAt = now()

    /** The phones as said out loud: "Gabriels iPhone och Annas iPad". */
    fun name(swedish: Boolean) = names.joinToString(if (swedish) " och " else " and ")

    /** What the assistant does about the hunt now that nobody is talking. */
    enum class Next { Ask, RingAgain, GiveUp }

    fun next(): Next? {
        val quiet = now() - lastAt
        return when {
            !asked && quiet >= ASK_AFTER_MS -> Next.Ask.also { asked = true; lastAt = now() }
            asked && quiet >= ANSWER_MS -> if (rings < MAX_RINGS) Next.RingAgain else Next.GiveUp
            else -> null
        }
    }

    /** Rings again; false when Homey couldn't. The next question waits a full [ASK_AFTER_MS]. */
    suspend fun ringAgain(): Boolean {
        rings++
        asked = false
        lastAt = now()
        return ringIt()
    }

    /** What someone said during the hunt. */
    enum class Heard { Found, Again, Stop }

    companion object {
        const val ASK_AFTER_MS = 30_000L
        const val ANSWER_MS = 20_000L
        const val MAX_RINGS = 3

        private val FOUND = Regex(
            """(?i)(?<![\p{L}])(hitta(de|t)|fann|har den|har hittat|found|got it|i have it|i've got it|here it is|där är den|här är den)(?![\p{L}])""",
        )
        private val AGAIN = Regex(
            """(?i)^\W*(igen|en gång till|again|once more)\W*$|(?<![\p{L}])(ring(a|er)? (den|it|igen|telefonen|mobilen|the phone)|""" +
                """hör (den |ingenting )?inte|hörde (den )?inte|can'?t hear|didn'?t hear)(?![\p{L}])""",
        )
        private val STOP = Regex("""(?i)^\s*(sluta|stopp|avbryt|strunta i det|glöm det|stop|cancel|never ?mind|forget it)\b""")
        private val NOT_FOUND = Regex("""(?i)(?<![\p{L}])(inte|not|nej|no|nope|nä|still|fortfarande)(?![\p{L}])""")

        /**
         * What [text] says about the hunt, or null when it is about something else (the AI then
         * answers it and the hunt goes on). A plain yes or no counts only once [asked].
         */
        fun heard(text: String, asked: Boolean): Heard? {
            val t = text.trim()
            if (t.split(Regex("\\s+")).size > 10) return null
            return when {
                STOP.containsMatchIn(t) -> Heard.Stop
                AGAIN.containsMatchIn(t) -> Heard.Again
                FOUND.containsMatchIn(t) -> if (NOT_FOUND.containsMatchIn(t)) Heard.Again else Heard.Found
                asked -> when (Confirmation.parse(t)) {
                    true -> Heard.Found
                    false -> Heard.Again
                    null -> null
                }
                else -> null
            }
        }

        fun ringingLine(name: String, swedish: Boolean) =
            if (swedish) "Nu ringer $name. Säg till när du har hittat den." else "$name is ringing now. Tell me when you've found it."

        fun askLine(swedish: Boolean) = if (swedish) "Hittade du telefonen?" else "Did you find the phone?"

        fun againLine(name: String, swedish: Boolean) =
            if (swedish) "Jag ringer $name igen. Säg till när du har hittat den." else "I'm ringing $name again. Tell me when you've found it."

        fun foundLine(swedish: Boolean) = if (swedish) "Vad bra, då slutar jag leta." else "Great, I'll stop looking."

        fun stopLine(swedish: Boolean) = if (swedish) "Okej, jag slutar leta." else "Okay, I'll stop looking."

        fun failedLine(name: String, swedish: Boolean) =
            if (swedish) "Jag fick inte $name att ringa. Homey svarade inte." else "I couldn't make $name ring. Homey didn't answer."

        fun giveUpLine(swedish: Boolean) =
            if (swedish) "Jag har ringt tre gånger nu, så jag slutar. Säg till om jag ska ringa igen."
            else "I've rung it three times now, so I'll stop. Tell me if I should ring it again."
    }
}
