package app.homeydash.agent.evals

import app.homeydash.agent.AgentCalendar
import app.homeydash.agent.AgentEvent
import app.homeydash.agent.CalendarBook
import app.homeydash.agent.DashboardControl
import app.homeydash.agent.HomeControl
import app.homeydash.agent.HomeyPerson
import app.homeydash.agent.LogicVariable
import app.homeydash.agent.NameMatch
import app.homeydash.agent.NewEvent
import app.homeydash.agent.TimerBoard
import app.homeydash.fridge.FoodRules
import app.homeydash.fridge.Fridge
import app.homeydash.fridge.FridgeItem
import app.homeydash.homey.AutocompleteItem
import app.homeydash.homey.Capability
import app.homeydash.homey.Device
import app.homeydash.homey.FlowCardAction
import app.homeydash.homey.FlowCardArg
import app.homeydash.homey.HomeyFlow
import app.homeydash.homey.Mood
import app.homeydash.homey.Zone
import app.homeydash.recipes.Cooking
import app.homeydash.recipes.Ingredient
import app.homeydash.recipes.Recipe
import app.homeydash.recipes.RecipeBook
import app.homeydash.recipes.Step
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime

/** The practice house the skill tests run in, as written in `evals/house.json`. */
@Serializable
data class HouseFile(
    val zones: List<Zone> = emptyList(),
    val devices: List<SimDevice> = emptyList(),
    val moods: List<SimMood> = emptyList(),
    val flows: List<String> = emptyList(),
    val people: List<SimPerson> = emptyList(),
    /** The speakers' favourites and radio stations, as Sonos would list them. */
    val music: List<String> = emptyList(),
    /** What a Spotify search finds, for the speakers that can search it. */
    val spotify: List<SimSpotify> = emptyList(),
    val weather: String? = null,
    val calendars: List<SimCalendar> = emptyList(),
    val events: List<SimEvent> = emptyList(),
    val recipes: List<SimRecipe> = emptyList(),
    val fridge: List<SimFood> = emptyList(),
)

/** A device by its capabilities' values; their types and ranges are Homey's usual ones ([CAPS]). */
@Serializable
data class SimDevice(
    val id: String,
    val name: String,
    val zone: String,
    @SerialName("class") val deviceClass: String,
    val caps: Map<String, JsonElement> = emptyMap(),
)

/** A Homey mood and what it sets, by device id. */
@Serializable
data class SimMood(val id: String, val name: String, val zone: String? = null, val sets: Map<String, Map<String, JsonElement>> = emptyMap())

@Serializable
data class SimPerson(val id: String, val name: String, val home: Boolean = true, val asleep: Boolean = false)

/** A calendar on the tablet; [owner] is a person's id, for "my calendar". */
@Serializable
data class SimCalendar(val id: String, val name: String, val account: String, val owner: String = "")

/** [day] is a word [CalendarBook.day] knows: today, tomorrow, friday… */
@Serializable
data class SimEvent(val title: String, val day: String, val start: String? = null, val end: String? = null, val calendar: String = "", val location: String? = null)

@Serializable
data class SimRecipe(val title: String, val ingredients: List<String> = emptyList(), val steps: List<String> = emptyList(), val categories: List<String> = emptyList())

/** Something on Spotify: a song, album, artist, playlist, podcast or episode, [by] someone; [mine] when in the owner's library. */
@Serializable
data class SimSpotify(val name: String, val kind: String, val by: String? = null, val mine: Boolean = false)

/** Something in the fridge, bought [bought] days ago, with the date on its pack [date] days from today. */
@Serializable
data class SimFood(val name: String, val bought: Int = 0, val date: Int? = null, val place: String? = null, val quantity: Int = 1)

/**
 * A whole home for one test: devices that change when set, moods, Flows, speakers, timers,
 * the calendar, recipes and the fridge, all in memory and in [dir]. Nothing reaches the real
 * Homey. What it was asked to do besides setting capabilities is kept in [did], in words like
 * `flow:Städläge`, so a test can check it.
 */
class SimHouse(private val file: HouseFile, private val dir: File, http: OkHttpClient, private val today: () -> LocalDate = { LocalDate.now() }) : HomeControl {
    val zones: Map<String, Zone> = file.zones.associateBy { it.id }
    var devices: Map<String, Device> = file.devices.associate { it.id to device(it) }
        private set
    val moods: Map<String, Mood> = file.moods.associate { it.id to Mood(it.id, it.name, it.zone) }

    /** What was done besides capabilities: `flow:…`, `mood:…`, `play:<device>:…`, `page:…`, `theme:…`, `goodnight`, `event:…`. */
    val did = mutableListOf<String>()
    /** Devices whose capability calls fail, as when a lamp is unplugged. */
    var failing: Set<String> = emptySet()
    val added = mutableListOf<NewEvent>()

    val timers = TimerBoard(null)
    val recipes = RecipeBook(File(dir, "recipes"), http)
    val cooking = Cooking(recipes)
    val fridge = Fridge(File(dir, "fridge"), products = emptyList(), date = today)
    val notes = app.homeydash.notes.NoteBoard(File(dir, "notes"), date = today)

    /** Fills the recipe book and the fridge; once, before the first question. */
    suspend fun open() {
        recipes.load()
        file.recipes.forEachIndexed { i, r ->
            recipes.save(
                Recipe(
                    id = "r${i + 1}", title = r.title,
                    ingredients = r.ingredients.map { Ingredient(it) }, steps = r.steps.map { Step(it) }, categories = r.categories,
                ),
            )
        }
        fridge.load()
        notes.load()
        file.fridge.forEach { f ->
            val category = FoodRules.classify(f.name)
            fridge.add(
                FridgeItem(
                    name = f.name, category = category.key,
                    place = f.place?.let { p -> app.homeydash.fridge.Place.entries.firstOrNull { it.name.equals(p, ignoreCase = true) } } ?: category.place,
                    quantity = f.quantity, bought = today().minusDays(f.bought.toLong()).toString(),
                    date = f.date?.let { today().plusDays(it.toLong()).toString() },
                ),
            )
        }
    }

    /** Sets capabilities before a test, as `given` says. */
    fun given(values: Map<String, Map<String, JsonElement>>) {
        for ((id, caps) in values) for ((cap, value) in caps) change(id, cap, value)
    }

    /** Every device's capability values, to compare before and after a question. */
    fun state(): Map<String, Map<String, JsonElement>> =
        devices.mapValues { (_, d) -> d.capabilitiesObj.mapValues { it.value.value } }

    private fun change(id: String, capability: String, value: JsonElement) {
        val d = devices[id] ?: return
        var next = d.withCapabilityValue(capability, value)
        // As Homey does: a light dimmed to 0 is off, and dimming an off light turns it on.
        if (capability == "dim" && next.capability("onoff") != null) {
            val level = (value as? JsonPrimitive)?.content?.toDoubleOrNull()
            if (level != null) next = next.withCapabilityValue("onoff", JsonPrimitive(level > 0.0))
        }
        devices = devices + (id to next)
    }

    /**
     * [text] without the home's own words: recipe steps and ingredients, food, device, room and
     * mood names, which stay Swedish in an English answer ("Step 3 of 5: Gör vitsåsen…").
     */
    fun withoutHouseWords(text: String): String {
        val words = file.recipes.flatMap { it.steps + it.ingredients + it.title } + file.fridge.map { it.name } +
            file.devices.map { it.name } + file.zones.map { it.name } + file.moods.map { it.name } + file.flows +
            file.music + file.spotify.flatMap { listOfNotNull(it.name, it.by) }
        return words.filter { it.isNotBlank() }.sortedByDescending { it.length }
            .fold(text) { t, w -> t.replace(w, " ", ignoreCase = true) }
    }

    override suspend fun setCapability(deviceId: String, capability: String, value: JsonElement) {
        if (deviceId in failing) throw IOException("The device is unavailable: it didn't answer Homey.")
        val d = devices[deviceId] ?: throw IOException("No such device.")
        if (d.capability(capability)?.setable != true) throw IOException("$capability can't be set on ${d.name}.")
        change(deviceId, capability, value)
    }

    override suspend fun readBack(deviceId: String, capability: String): JsonElement? = devices[deviceId]?.capability(capability)?.value

    override suspend fun readDevice(deviceId: String): Device? = devices[deviceId]

    override fun device(deviceId: String): Device? = devices[deviceId]

    override suspend fun setMood(mood: Mood) {
        did += "mood:${mood.name}"
        file.moods.firstOrNull { it.id == mood.id }?.sets?.let(::given)
    }

    override suspend fun flows(): List<HomeyFlow> = file.flows.mapIndexed { i, name -> HomeyFlow("f${i + 1}", name, false) }

    override suspend fun triggerFlow(flow: HomeyFlow) {
        did += "flow:${flow.name}"
    }

    override suspend fun people(): List<HomeyPerson> = file.people.map { HomeyPerson(it.name, it.home, it.asleep, it.id) }

    override suspend fun variables(): List<LogicVariable> = emptyList()

    // Speakers get a Sonos-like favourites card, so the music tools treat them as players.
    override fun deviceActions(deviceId: String): List<FlowCardAction> =
        if (devices[deviceId]?.capabilitiesObj?.containsKey("speaker_playing") == true) {
            listOf(FlowCardAction("homey:device:$deviceId:play_favorite", title = "Play favourite", args = listOf(FlowCardArg("favorite", "autocomplete"))))
        } else emptyList()

    override suspend fun choices(card: FlowCardAction, arg: FlowCardArg, query: String): List<AutocompleteItem> =
        file.music.filter { query.isBlank() || NameMatch.score(query, it) > 0.5 }
            .map { AutocompleteItem(it, null, null, JsonObject(mapOf("name" to JsonPrimitive(it)))) }

    override suspend fun runAction(card: FlowCardAction, args: JsonObject) {
        val id = card.id.split(':').getOrNull(2).orEmpty()
        play(id, (args["favorite"] as? JsonObject)?.get("name")?.let { (it as? JsonPrimitive)?.content } ?: "")
    }

    override suspend fun playMusic(deviceId: String, query: String): String? = playMusic(deviceId, query, null)

    /**
     * As the real home does: the speakers' own favourites when one matches well and no other
     * kind was asked for, else Spotify, only that [kind] when one is given. What Spotify played
     * is kept in [did] as `spotify:<kind>:<name>` too.
     */
    override suspend fun playMusic(deviceId: String, query: String, kind: String?): String? {
        val wanted = kind?.lowercase()?.trim()?.let { KINDS[it] ?: it }?.takeIf { it in SPOTIFY_KINDS }
        if (query.isBlank() || ANYTHING.matches(NameMatch.normalize(query))) {
            val pick = file.music.firstOrNull() ?: return null
            play(deviceId, pick)
            return pick
        }
        val own = file.music.map { it to NameMatch.score(query, it) }.maxByOrNull { it.second }
        if (wanted == null && own != null && own.second >= OWN_IS_ENOUGH) return own.first.also { play(deviceId, it) }
        val found = file.spotify.filter { wanted == null || it.kind == wanted || (wanted == "podcast" && it.kind == "episode") }
            .map { it to maxOf(NameMatch.score(query, it.name), it.by?.let { b -> NameMatch.score(query, "${it.name} $b") } ?: 0.0, if (it.kind == "artist") NameMatch.score(query, it.name) else 0.0) }
            .filter { it.second > 0.5 }.maxByOrNull { it.second }?.first
        if (found != null) {
            did += "spotify:${found.kind}:${found.name}"
            play(deviceId, found.name)
            return found.name + (found.by?.let { " by $it" }.orEmpty())
        }
        val loose = own?.takeIf { wanted == null && it.second > 0.5 }?.first ?: return null
        play(deviceId, loose)
        return loose
    }

    private fun play(deviceId: String, name: String) {
        did += "play:$deviceId:$name"
        change(deviceId, "speaker_playing", JsonPrimitive(true))
    }

    /** The dashboard on the tablet: pages, the theme, Goodnight, the weather, the calendar and the timers. */
    val dashboard = object : DashboardControl {
        override fun openPage(page: String, roomId: String?): Boolean {
            did += "page:${roomId ?: page}"
            return true
        }

        override fun openDevice(deviceId: String): Boolean {
            did += "device:$deviceId"
            return true
        }

        override fun goodnightPreview(): String {
            val lights = devices.values.count { it.capability("onoff")?.booleanValue == true }
            val playing = devices.values.count { it.capability("speaker_playing")?.booleanValue == true }
            return "turn off $lights things that are on" + if (playing > 0) " and pause $playing speakers." else "."
        }

        override fun goodnight() {
            did += "goodnight"
            for (d in devices.values) {
                if (d.capability("onoff")?.booleanValue == true) change(d.id, "onoff", JsonPrimitive(false))
                if (d.capability("speaker_playing")?.booleanValue == true) change(d.id, "speaker_playing", JsonPrimitive(false))
            }
        }

        override suspend fun weather(): String? = file.weather

        override suspend fun calendar(from: LocalDate, days: Int, withLocations: Boolean): List<AgentEvent> = events(null, from, days, withLocations)

        override suspend fun calendarIn(ids: Set<String>, from: LocalDate, days: Int, withLocations: Boolean): List<AgentEvent> = events(ids, from, days, withLocations)

        override suspend fun writableCalendars(): List<AgentCalendar> = file.calendars.map { AgentCalendar(it.id, it.name, it.account, it.owner) }

        override suspend fun addEvent(event: NewEvent) {
            added += event
            did += "event:${event.title}"
        }

        override val timers: TimerBoard get() = this@SimHouse.timers

        override fun setTheme(mode: String): Boolean {
            if (mode !in setOf("light", "dark", "auto")) return false
            did += "theme:$mode"
            return true
        }
    }

    private fun events(ids: Set<String>?, from: LocalDate, days: Int, withLocations: Boolean): List<AgentEvent> {
        val until = from.plusDays(days.toLong())
        val fixed = file.events.mapNotNull { e -> CalendarBook.day(e.day, today())?.let { it to e } }
        val made = added.map { it.date to SimEvent(it.title, it.date.toString(), it.time?.toString(), null, it.calendarId, it.location) }
        return (fixed + made)
            .filter { (day, e) -> !day.isBefore(from) && day.isBefore(until) && (ids == null || e.calendar in ids) }
            .sortedBy { (day, e) -> day.toString() + (e.start ?: "") }
            .map { (day, e) ->
                val allDay = e.start == null
                AgentEvent(e.title, if (allDay) "$day" else "$day ${e.start}", e.end?.let { "$day $it" }, allDay, e.location.takeIf { withLocations })
            }
    }

    companion object {
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        private val json = Json { ignoreUnknownKeys = true; allowComments = true; allowTrailingComma = true }

        fun parse(text: String): HouseFile = json.decodeFromString(HouseFile.serializer(), text)

        private const val OWN_IS_ENOUGH = 0.9
        /** play_music's kinds, as [SimSpotify.kind] has them. */
        private val KINDS = mapOf("track" to "song", "show" to "podcast")
        private val SPOTIFY_KINDS = setOf("song", "album", "artist", "playlist", "podcast", "episode")
        private val ANYTHING = Regex("(anything|something|whatever|random|music|nagot|nat|valj sjalv|lite musik|musik)")

        /** Homey's usual type, range and whether it can be set, for the capabilities the house uses. */
        private val CAPS = mapOf(
            "onoff" to Capability("onoff", type = "boolean", title = "Turned on", setable = true),
            "dim" to Capability("dim", type = "number", title = "Brightness", min = 0.0, max = 1.0, step = 0.01, setable = true),
            "light_temperature" to Capability("light_temperature", type = "number", title = "Colour temperature", min = 0.0, max = 1.0, setable = true),
            "light_hue" to Capability("light_hue", type = "number", title = "Hue", min = 0.0, max = 1.0, setable = true),
            "light_saturation" to Capability("light_saturation", type = "number", title = "Saturation", min = 0.0, max = 1.0, setable = true),
            "target_temperature" to Capability("target_temperature", type = "number", title = "Target temperature", units = "°C", min = 5.0, max = 30.0, step = 0.5, setable = true),
            "measure_temperature" to Capability("measure_temperature", type = "number", title = "Temperature", units = "°C"),
            "measure_power" to Capability("measure_power", type = "number", title = "Power", units = "W"),
            "volume_set" to Capability("volume_set", type = "number", title = "Volume", min = 0.0, max = 1.0, step = 0.01, setable = true),
            "volume_mute" to Capability("volume_mute", type = "boolean", title = "Mute", setable = true),
            "speaker_playing" to Capability("speaker_playing", type = "boolean", title = "Playing", setable = true),
            "windowcoverings_set" to Capability("windowcoverings_set", type = "number", title = "Position", min = 0.0, max = 1.0, setable = true),
            "locked" to Capability("locked", type = "boolean", title = "Locked", setable = true),
            "alarm_contact" to Capability("alarm_contact", type = "boolean", title = "Open"),
        )

        fun device(d: SimDevice): Device {
            val caps = d.caps.mapValues { (id, value) -> (CAPS[id] ?: Capability(id, type = typeOf(value), setable = true)).copy(value = value) }
            return Device(d.id, d.name, d.zone, d.deviceClass, capabilities = caps.keys.toList(), capabilitiesObj = caps)
        }

        private fun typeOf(value: JsonElement): String = when {
            (value as? JsonPrimitive)?.booleanOrNull != null -> "boolean"
            (value as? JsonPrimitive)?.doubleOrNull != null -> "number"
            else -> "string"
        }

        /** "09:00" as a time, for checks; null when it isn't one. */
        fun time(text: String?): LocalTime? = text?.let { runCatching { LocalTime.parse(it.padStart(5, '0')) }.getOrNull() }
    }
}
