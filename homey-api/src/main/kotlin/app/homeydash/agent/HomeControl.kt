package app.homeydash.agent

import app.homeydash.homey.AutocompleteItem
import app.homeydash.homey.Device
import app.homeydash.homey.FlowCardAction
import app.homeydash.homey.FlowCardArg
import app.homeydash.homey.FlowCards
import app.homeydash.homey.HomeyAlarm
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** A Homey user, for "who is home"; [since] is when the tablet saw their state last change (epoch ms). */
data class HomeyPerson(val name: String, val present: Boolean?, val asleep: Boolean?, val id: String = "", val since: Long? = null)

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

    /** [playMusic], looking on Spotify for that [kind] only: playlist, album, song or artist. */
    suspend fun playMusic(deviceId: String, query: String, kind: String?): String? = playMusic(deviceId, query)

    /**
     * The Flow "Then" cards the device's app offers: TV channels, apps, Sonos favourites,
     * playlists and radio, remote keys. Everything an app can do that isn't a capability.
     */
    fun deviceActions(deviceId: String): List<FlowCardAction> = emptyList()

    /** Why some of [deviceId]'s actions (such as Spotify on Sonos) are missing; null when nothing is. */
    fun missingActions(deviceId: String): String? = null

    /** What an autocomplete argument offers for [query], such as channels or playlists. */
    suspend fun choices(card: FlowCardAction, arg: FlowCardArg, query: String): List<AutocompleteItem> = emptyList()

    /**
     * What Homey itself now says [capability] is, asked fresh rather than from what the
     * dashboard last heard; null when it can't tell.
     */
    suspend fun readBack(deviceId: String, capability: String): JsonElement? = null

    /** The whole device as Homey now reports it, asked fresh; null when it can't tell. */
    suspend fun readDevice(deviceId: String): Device? = null

    /** Runs a Flow card; throws when Homey refuses. */
    suspend fun runAction(card: FlowCardAction, args: JsonObject) {}

    /** The device as Homey last reported it, all capabilities included; null when unknown. */
    fun device(deviceId: String): Device? = null

    /** Marks a Homey user home or away ([present]) and asleep or awake ([asleep]); null leaves it. */
    suspend fun setPresence(userId: String, present: Boolean?, asleep: Boolean?) {
        throw UnsupportedOperationException("Presence can't be changed from here.")
    }

    /** Homey's wake-up alarms. */
    suspend fun alarms(): List<HomeyAlarm> = emptyList()

    /** Creates ([id] null) or changes a wake-up alarm; returns it as Homey has it. */
    suspend fun saveAlarm(id: String?, name: String, time: LocalTime, days: Set<DayOfWeek>, enabled: Boolean): HomeyAlarm =
        throw UnsupportedOperationException("Alarms can't be changed from here.")

    suspend fun deleteAlarm(id: String) {
        throw UnsupportedOperationException("Alarms can't be changed from here.")
    }

    /** Skips an alarm's next ring only; returns when it would have rung. */
    suspend fun skipAlarm(id: String): ZonedDateTime? = throw UnsupportedOperationException("Alarms can't be changed from here.")
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
    /** The kitchen timers on the tablet. */
    val timers: TimerBoard
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

    override suspend fun readDevice(deviceId: String): Device? {
        kotlinx.coroutines.delay(READ_BACK_MS)
        return store.readDevice(deviceId)
    }

    override suspend fun setMood(mood: Mood) = store.setMood(mood)

    override fun missingActions(deviceId: String): String? = store.missingCards(deviceId)

    override suspend fun flows(): List<HomeyFlow> = store.flows()

    override suspend fun triggerFlow(flow: HomeyFlow) = store.triggerFlow(flow)

    override suspend fun people(): List<HomeyPerson> {
        // The household keeps users current from Homey's events and knows since when.
        val h = store.household.state.value
        if (h.loaded && h.usersAllowed && h.users.isNotEmpty()) {
            return h.people.map { HomeyPerson(it.name, it.present, it.asleep, it.id, h.since[it.id]) }
        }
        return parsePeople(store.users())
    }

    override suspend fun setPresence(userId: String, present: Boolean?, asleep: Boolean?) {
        present?.let { store.household.setPresent(userId, it) }
        asleep?.let { store.household.setAsleep(userId, it) }
    }

    override suspend fun alarms(): List<HomeyAlarm> {
        val h = store.household.state.value
        if (!h.loaded) store.household.reload()
        if (!store.household.state.value.alarmsAllowed) throw java.io.IOException("The Homey API key may not read alarms. Give it the \"View alarms\" permission.")
        return store.household.state.value.alarms
    }

    override suspend fun saveAlarm(id: String?, name: String, time: LocalTime, days: Set<DayOfWeek>, enabled: Boolean): HomeyAlarm =
        store.household.saveAlarm(id, name, time, days, enabled)

    override suspend fun deleteAlarm(id: String) = store.household.deleteAlarm(id)

    override suspend fun skipAlarm(id: String): ZonedDateTime? = store.household.skipNext(id)

    override suspend fun variables(): List<LogicVariable> = parseVariables(store.logicVariables())

    override suspend fun playMusic(deviceId: String, query: String): String? = playMusic(deviceId, query, null)

    override suspend fun playMusic(deviceId: String, query: String, kind: String?): String? {
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
        // "radio P1", "kanalen P3": a station, which only the home's own radio has; Spotify
        // would find songs that merely look alike ("#1TAKE, Pt. 1" for P1).
        val asked = kind?.trim()?.lowercase()
        val radio = asked == "radio" || (asked.isNullOrEmpty() && (RADIO.containsMatchIn(NameMatch.normalize(query)) || STATION.matches(NameMatch.normalize(query))))
        @Suppress("NAME_SHADOWING") val query = if (radio) NameMatch.normalize(query).replace(RADIO, " ").trim().ifBlank { query } else query
        // Why a search failed (Spotify refusing the keys, say), so a miss can say so instead of "couldn't find".
        var trouble: Throwable? = null
        suspend fun look(list: List<MusicSource>): List<Pair<MusicSource, AutocompleteItem>> = list.flatMap { source ->
            runCatching { choices(source.card, source.arg, query) }.onFailure { trouble = it }.getOrDefault(emptyList()).map { source to it }
        }
        // The artist is often only in the description ("Abbey Road" · "The Beatles").
        fun score(item: AutocompleteItem): Double =
            maxOf(NameMatch.score(query, item.name), NameMatch.score(query, listOfNotNull(item.name, item.description).joinToString(" ")))
        val found = look(own)
        val ownBest: Pair<MusicSource, AutocompleteItem>? = NameMatch.best(query, found) { score(it.second) }
        var best: Pair<MusicSource, AutocompleteItem>? = ownBest?.takeIf { catalogues.isEmpty() || radio || score(it.second) >= OWN_IS_ENOUGH }
        if (best == null && catalogues.isNotEmpty() && !radio) {
            // "playlist party hits": Spotify then finds the playlist, not an artist called Party Hits.
            val word = kind?.let { KIND_WORDS[it.trim().lowercase()] }
            val hits = if (word == null) look(catalogues) else catalogues.flatMap { source ->
                runCatching { choices(source.card, source.arg, "$word $query") }.onFailure { trouble = it }.getOrDefault(emptyList()).map { source to it }
            }
            val hit = NameMatch.best(query, hits) { score(it.second) }
            // The home's own near match beats a catalogue's guess.
            best = listOfNotNull(ownBest, hit).maxByOrNull { score(it.second) } ?: hits.firstOrNull()
        }
        best = best ?: ownBest ?: found.firstOrNull { it.first.arg.type == "autocomplete" }
            ?: trouble?.let { throw java.io.IOException("Searching for \"$query\" failed: ${it.message}", it) }
            ?: return null
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

        /** play_music's kinds, as the words Spotify search knows them by. */
        private val KIND_WORDS = mapOf(
            "playlist" to "playlist", "album" to "album", "song" to "song", "track" to "song", "artist" to "artist",
        )

        /** Words that make a request a radio station. */
        private val RADIO = Regex("""\b(radio\w*|kanal\w*|station\w*|channel)\b""")
        /** Swedish Radio's stations said bare: "P1", "P4 Stockholm". */
        private val STATION = Regex("""p ?[1-4]( \w+)?""")

        private val ANYTHING = Regex(
            "(anything|something|whatever|surprise me|random|you choose|any music|some music|music|" +
                "vad som helst|nagot|nat|valj sjalv|du valjer|valfritt|valfri|lite musik|musik|nagon musik|overraska mig)",
        )

        /** Parses `GET /api/manager/users/user`. */
        fun parsePeople(json: JsonElement): List<HomeyPerson> =
            values(json).mapNotNull { u ->
                val name = (u["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                if ((u["enabled"] as? JsonPrimitive)?.booleanOrNull == false) return@mapNotNull null
                HomeyPerson(
                    name, (u["present"] as? JsonPrimitive)?.booleanOrNull, (u["asleep"] as? JsonPrimitive)?.booleanOrNull,
                    (u["id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                )
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
