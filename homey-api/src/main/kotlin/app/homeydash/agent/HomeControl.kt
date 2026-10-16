package app.homeydash.agent

import app.homeydash.homey.AutocompleteItem
import app.homeydash.homey.Device
import app.homeydash.homey.FlowCardAction
import app.homeydash.homey.FlowCardArg
import app.homeydash.homey.FlowCards
import app.homeydash.homey.HomeyFlow
import app.homeydash.homey.HomeyStore
import app.homeydash.homey.Mood
import app.homeydash.homey.MusicSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate

/** A Homey user, for "who is home". */
data class HomeyPerson(val name: String, val present: Boolean?, val asleep: Boolean?)

/** A Homey Logic variable. */
data class LogicVariable(val name: String, val type: String, val value: JsonElement)

/** What the assistant may do to the Homey. Everything goes through the Homey Web API. */
interface HomeControl {
    suspend fun setCapability(deviceId: String, capability: String, value: JsonElement)
    suspend fun setMood(mood: Mood)
    suspend fun flows(): List<HomeyFlow>
    suspend fun triggerFlow(flow: HomeyFlow)
    suspend fun people(): List<HomeyPerson>
    suspend fun variables(): List<LogicVariable>
    /** Plays the best match for [query] from the player's favourites, playlists or radio; returns its name. */
    suspend fun playMusic(deviceId: String, query: String): String?

    /**
     * The Flow "Then" cards the device's app offers: TV channels, apps, Sonos favourites,
     * playlists and radio, remote keys. Everything an app can do that isn't a capability.
     */
    fun deviceActions(deviceId: String): List<FlowCardAction> = emptyList()

    /** What an autocomplete argument offers for [query], such as channels or playlists. */
    suspend fun choices(card: FlowCardAction, arg: FlowCardArg, query: String): List<AutocompleteItem> = emptyList()

    /**
     * What Homey itself now says [capability] is, asked fresh rather than from what the
     * dashboard last heard; null when it can't tell.
     */
    suspend fun readBack(deviceId: String, capability: String): JsonElement? = null

    /** Runs a Flow card; throws when Homey refuses. */
    suspend fun runAction(card: FlowCardAction, args: JsonObject) {}

    /** The device as Homey last reported it, all capabilities included; null when unknown. */
    fun device(deviceId: String): Device? = null
}

/** One calendar event as the assistant sees it. */
data class AgentEvent(val title: String, val start: String, val end: String?, val allDay: Boolean, val location: String?)

/** What the assistant may do to the dashboard on the tablet. */
interface DashboardControl {
    /** Opens `overview`, `moods`, `media`, `calendar`, `recipes`, `fridge`, or a room by id. */
    fun openPage(page: String, roomId: String? = null): Boolean
    fun openDevice(deviceId: String): Boolean
    /** What Goodnight would switch off now, in words. */
    fun goodnightPreview(): String
    fun goodnight()
    suspend fun weather(): String?
    suspend fun calendar(from: LocalDate, days: Int, withLocations: Boolean): List<AgentEvent>
    fun startTimer(seconds: Int, label: String?)
    fun cancelTimer(label: String?): Boolean
    fun timers(): List<String>
    fun setTheme(mode: String): Boolean
}

/** [HomeControl] on top of the dashboard's [HomeyStore], so changes show on the cards at once. */
class StoreHomeControl(
    private val store: HomeyStore,
    private val cards: () -> Map<String, List<FlowCardAction>>,
) : HomeControl {
    override suspend fun setCapability(deviceId: String, capability: String, value: JsonElement) =
        store.setCapability(deviceId, capability, value, rethrow = true)

    override suspend fun readBack(deviceId: String, capability: String): JsonElement? {
        // Gives the device a moment to answer Homey before asking.
        kotlinx.coroutines.delay(READ_BACK_MS)
        return store.readBack(deviceId, capability)
    }

    override suspend fun setMood(mood: Mood) = store.setMood(mood)

    override suspend fun flows(): List<HomeyFlow> = store.flows()

    override suspend fun triggerFlow(flow: HomeyFlow) = store.triggerFlow(flow)

    override suspend fun people(): List<HomeyPerson> = parsePeople(store.users())

    override suspend fun variables(): List<LogicVariable> = parseVariables(store.logicVariables())

    override suspend fun playMusic(deviceId: String, query: String): String? {
        // Looks through every source (favourites, playlists, radio…) and plays the best match
        // of all, so "P3" finds the radio station even when a favourite also says "P3 Dokumentär".
        val sources = FlowCards.musicSources(deviceActions(deviceId)).flatMap { it.all }
        // "Play something", "välj själv": a favourite or playlist picked at random.
        if (query.isBlank() || ANYTHING.matches(NameMatch.normalize(query))) {
            for (source in sources) {
                val items = runCatching { choices(source.card, source.arg, "") }.getOrDefault(emptyList())
                if (items.isEmpty()) continue
                val item = items.random()
                runAction(source.card, FlowCards.musicArgs(source, item))
                return item.name
            }
            return null
        }
        // The home's own favourites, playlists and radio first; a catalogue like Spotify when
        // none of them is clearly what was asked for.
        val (catalogues, own) = sources.partition { it.catalogue }
        suspend fun look(list: List<MusicSource>): List<Pair<MusicSource, AutocompleteItem>> = list.flatMap { source ->
            runCatching { choices(source.card, source.arg, query) }.getOrDefault(emptyList()).map { source to it }
        }
        // The artist is often only in the description ("Abbey Road" · "The Beatles").
        fun score(item: AutocompleteItem): Double =
            maxOf(NameMatch.score(query, item.name), NameMatch.score(query, listOfNotNull(item.name, item.description).joinToString(" ")))
        val found = look(own)
        val ownBest: Pair<MusicSource, AutocompleteItem>? = NameMatch.best(query, found) { score(it.second) }
        var best: Pair<MusicSource, AutocompleteItem>? = ownBest?.takeIf { catalogues.isEmpty() || score(it.second) >= OWN_IS_ENOUGH }
        if (best == null && catalogues.isNotEmpty()) {
            val hits = look(catalogues)
            best = NameMatch.best(query, hits) { score(it.second) } ?: hits.firstOrNull()
        }
        best = best ?: ownBest ?: found.firstOrNull { it.first.arg.type == "autocomplete" } ?: return null
        runAction(best.first.card, FlowCards.musicArgs(best.first, best.second))
        // "Dancing Queen (Song · ABBA)", so the answer says what was found.
        return if (best.first.catalogue) listOfNotNull(best.second.name, best.second.description?.let { "($it)" }).joinToString(" ") else best.second.name
    }

    /**
     * The device's cards, and those of its twins: the same speaker added by a second app
     * (Sonos and Sonos LocalAPI), often hidden, whose albums and playlists count too.
     */
    override fun deviceActions(deviceId: String): List<FlowCardAction> =
        FlowCards.withTwins(store.snapshot.value.devices, cards(), deviceId)

    override suspend fun choices(card: FlowCardAction, arg: FlowCardArg, query: String): List<AutocompleteItem> =
        if (arg.type == "dropdown") FlowCards.dropdownItems(arg) else store.autocomplete(card, arg.name, query)

    override suspend fun runAction(card: FlowCardAction, args: JsonObject) = store.runAction(card, args, rethrow = true)

    override fun device(deviceId: String): Device? = store.snapshot.value.devices[deviceId]

    companion object {
        /** How well one of the home's own picks must match before a catalogue isn't searched. */
        private const val OWN_IS_ENOUGH = 0.9

        private val ANYTHING = Regex(
            "(anything|something|whatever|surprise me|random|you choose|any music|some music|music|" +
                "vad som helst|nagot|nat|valj sjalv|du valjer|valfritt|valfri|lite musik|musik|nagon musik|overraska mig)",
        )

        /** Parses `GET /api/manager/users/user`. */
        fun parsePeople(json: JsonElement): List<HomeyPerson> =
            values(json).mapNotNull { u ->
                val name = (u["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                HomeyPerson(name, (u["present"] as? JsonPrimitive)?.booleanOrNull, (u["asleep"] as? JsonPrimitive)?.booleanOrNull)
            }.sortedBy { it.name.lowercase() }

        /** Parses `GET /api/manager/logic/variable`. */
        fun parseVariables(json: JsonElement): List<LogicVariable> =
            values(json).mapNotNull { v ->
                val name = (v["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                LogicVariable(name, (v["type"] as? JsonPrimitive)?.contentOrNull ?: "string", v["value"] ?: JsonNull)
            }.sortedBy { it.name.lowercase() }

        private fun values(json: JsonElement): List<JsonObject> = when (json) {
            is JsonObject -> json.values.filterIsInstance<JsonObject>()
            is JsonArray -> json.filterIsInstance<JsonObject>()
            else -> emptyList()
        }
    }
}

private const val READ_BACK_MS = 1200L
