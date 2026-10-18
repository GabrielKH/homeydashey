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
