package app.homeydash.modules

import app.homeydash.homey.Device
import app.homeydash.homey.HomeyApp
import app.homeydash.homey.appId

/** A Homey app a module can work through, with its page in the Homey App Store. */
data class HomeyAppRef(val id: String, val name: String) {
    val storeUrl: String get() = "https://homey.app/app/$id/"
}

/** Where a source's data comes from. */
enum class SourceKind {
    /** This app, on the tablet, with no Homey app needed. */
    BuiltIn,
    /** Homey itself (users, presence, energy), with no extra app. */
    Homey,
    /** A Homey app that has to be installed. */
    HomeyApp,
}

/** One way a module can get its data. */
data class ModuleSource(
    val id: String,
    val label: String,
    val detail: String,
    val kind: SourceKind,
    val app: HomeyAppRef? = null,
    /** Other Homey apps that do the same job, like Sonos (LocalAPI) for Sonos. */
    val alternatives: List<HomeyAppRef> = emptyList(),
) {
    /** Every Homey app that can serve this source, the usual one first. */
    val apps: List<HomeyAppRef> get() = listOfNotNull(app) + alternatives
}

enum class ModuleKind {
    /** A part of the app that can be turned on and off, with a choice of source. */
    Feature,
    /** Turns on by itself when Homey has devices for it. */
    Devices,
    /** Not built yet; shown so the owner sees what is coming. */
    Planned,
}

/**
 * A part of the dashboard, like Homey's apps: what it does, where its data can come from
 * (built in, or a Homey app), where it shows and what the assistant can do with it.
 */
data class AppModule(
    val id: String,
    val name: String,
    val summary: String,
    val kind: ModuleKind,
    val sources: List<ModuleSource>,
    val showsOn: List<String>,
    val assistant: String,
    /** Device classes that belong to a [ModuleKind.Devices] module, besides devices of its sources' apps. */
    val deviceClasses: Set<String> = emptySet(),
) {
    fun source(id: String?): ModuleSource = sources.firstOrNull { it.id == id } ?: sources.first()
}

/** What the owner chose for a module on this tablet. */
data class ModuleChoice(val on: Boolean = true, val source: String? = null)

enum class ModuleStatus(val label: String) {
    On("On"),
    NeedsApp("Needs a Homey app"),
    NoDevices("No devices yet"),
    Off("Off"),
    Planned("Coming later"),
}

/** A module's state on this tablet, for the modules page. */
data class ModuleState(
    val module: AppModule,
    val choice: ModuleChoice,
    val status: ModuleStatus,
    val source: ModuleSource,
    /** The Homey apps the chosen source needs and whether each is installed. */
    val apps: List<Pair<HomeyAppRef, Boolean>>,
    /** The module's devices in Homey, for [ModuleKind.Devices]. */
    val devices: List<Device>,
    /** Every installed Homey app, so each source can say whether its app is there. */
    val installed: Set<String> = emptySet(),
) {
    /**
     * Whether [source] has what it needs: no app, one of its apps installed, or (for a device
     * module) devices from any app, since another brand's vacuum or speaker app works just as well.
     */
    fun installed(source: ModuleSource): Boolean =
        source.app == null || source.apps.any { it.id in installed } ||
            (module.kind == ModuleKind.Devices && source.kind == SourceKind.HomeyApp && devices.isNotEmpty())
}

object AppModules {
    private val ICAL = HomeyAppRef("no.runely.calendar", "IcalCalendar")
    private val FIND_MY = HomeyAppRef("com.apple.findmy", "Apple Find My")
    private val SONOS = HomeyAppRef("com.sonos", "Sonos")
    private val SONOS_LOCAL = HomeyAppRef("nl.xiut.sonoslocal", "Sonos (LocalAPI)")
    private val ROBOROCK = HomeyAppRef("be.functor.roborock", "Roborock Cloud")
    private val APPLE_TV = HomeyAppRef("com.appletv.native", "Apple TV")
    private val TIBBER = HomeyAppRef("com.tibber", "Tibber")

    val all: List<AppModule> = listOf(
        AppModule(
            id = "calendar",
            name = "Calendar",
            summary = "Today's events on the overview, the calendar page, and answers from the assistant.",
            kind = ModuleKind.Feature,
            sources = listOf(
                ModuleSource(
                    "tablet", "Built in: this tablet's accounts",
                    "Reads the calendars synced to this tablet, like a Google account. Fastest, and works when Homey is offline.",
                    SourceKind.BuiltIn,
                ),
                ModuleSource(
                    "homey", "Homey app: IcalCalendar",
                    "Any calendar with an iCal link (Google's secret address, iCloud, Outlook, school). Homey keeps the events, so Flows can use them too.",
                    SourceKind.HomeyApp, ICAL,
                ),
            ),
            showsOn = listOf("Calendar card on the overview", "Calendar page", "Routines like Good morning"),
            assistant = "Answers \"what's on today?\" and reads the day in routines.",
        ),
        AppModule(
            id = "weather",
            name = "Weather",
            summary = "The forecast on the overview, and the weather in the assistant's answers.",
            kind = ModuleKind.Feature,
            sources = listOf(
                ModuleSource(
                    "openmeteo", "Built in: Open-Meteo",
                    "A free forecast for Homey's location, this tablet's, or a place you choose under Homey and rooms.",
                    SourceKind.BuiltIn,
                ),
            ),
            showsOn = listOf("Weather card on the overview", "Routines like Good morning"),
            assistant = "Answers weather questions and reads the forecast in routines.",
        ),
        AppModule(
            id = "music",
            name = "Music",
            summary = "The player card, Sonos favourites and playlists, and music by voice.",
            kind = ModuleKind.Devices,
            sources = listOf(
                ModuleSource(
                    "sonos", "Homey app: Sonos or Sonos (LocalAPI)",
                    "Brings the speakers into Homey. The dashboard then plays favourites, playlists and Spotify straight on the speakers.",
                    SourceKind.HomeyApp, SONOS, alternatives = listOf(SONOS_LOCAL),
                ),
            ),
            showsOn = listOf("Player card on the overview", "Media page", "Speaker cards in each room"),
            assistant = "Plays music, radio and playlists by voice, on the speaker in the room you ask from.",
            deviceClasses = setOf("speaker"),
        ),
        AppModule(
            id = "phones",
            name = "Find phones",
            summary = "Ring a lost phone and ask the assistant where it is.",
            kind = ModuleKind.Devices,
            sources = listOf(
                ModuleSource(
                    "findmy", "Homey app: Apple Find My",
                    "Adds the family's Apple devices to Homey, with their location and a play-sound action. " +
                        "The dashboard asks Find My for a fresh location only when someone asks, so you can turn off " +
                        "the app's automatic updates in Homey and save the phones' battery.",
                    SourceKind.HomeyApp, FIND_MY,
                ),
            ),
            showsOn = listOf("Phones under Assistant settings"),
            assistant = "\"Where is my phone?\" and \"ring my phone\", for the person asking.",
        ),
        AppModule(
            id = "vacuum",
            name = "Robot vacuum",
            summary = "The vacuum card with its map, cleaning by room, and part wear.",
            kind = ModuleKind.Devices,
            sources = listOf(
                ModuleSource(
                    "app", "Homey app: your vacuum's app",
                    "Any robot vacuum in Homey works. Roborock Cloud is the one tested here.",
                    SourceKind.HomeyApp, ROBOROCK,
                ),
            ),
            showsOn = listOf("Vacuum card and page"),
            assistant = "Starts, stops and sends the vacuum home by voice.",
            deviceClasses = setOf("vacuumcleaner"),
        ),
        AppModule(
            id = "tv",
            name = "TV and remote",
            summary = "A remote on the dashboard, what's playing, and \"pause the TV\" by voice.",
            kind = ModuleKind.Devices,
            sources = listOf(
                ModuleSource(
                    "appletv", "Homey app: Apple TV",
                    "Adds the Apple TV to Homey with its remote keys, apps and what's playing.",
                    SourceKind.HomeyApp, APPLE_TV,
                ),
            ),
            showsOn = listOf("Remote sheet", "Now playing in the room"),
            assistant = "Pauses, plays and turns the TV on and off by voice.",
            deviceClasses = setOf("tv"),
        ),
        AppModule(
            id = "home",
            name = "Who's home",
            summary = "Who is home or away, and who is asleep.",
            kind = ModuleKind.Feature,
            sources = listOf(
                ModuleSource(
                    "presence", "Homey: users and presence",
                    "Homey's own users, set home and away by the Homey app on each phone.",
                    SourceKind.Homey,
                ),
            ),
            showsOn = listOf("Who's home card on the overview"),
            assistant = "Knows who is home and learns when people usually come and go.",
        ),
        AppModule(
            id = "fridge",
            name = "Fridge",
            summary = "What's in the fridge, scanned with the tablet's camera, and when it goes off.",
            kind = ModuleKind.Feature,
            sources = listOf(
                ModuleSource(
                    "scanner", "Built in: camera scanning",
                    "Scan barcodes and dates with this tablet's camera. Products are looked up online.",
                    SourceKind.BuiltIn,
                ),
            ),
            showsOn = listOf("Fridge card on the overview", "Fridge page"),
            assistant = "Answers \"do we have milk?\" and learns how fast the house goes through things.",
        ),
        AppModule(
            id = "recipes",
            name = "Recipes",
            summary = "Your recipes on the tablet, with step-by-step cooking and timers.",
            kind = ModuleKind.Feature,
            sources = listOf(
                ModuleSource(
                    "book", "Built in: recipe book",
                    "Import from Recipe Keeper or paste a link to any recipe page.",
                    SourceKind.BuiltIn,
                ),
            ),
            showsOn = listOf("Recipes page"),
            assistant = "Finds recipes, reads the steps aloud and starts the timers.",
        ),
        AppModule(
            id = "energy",
            name = "Electricity price",
            summary = "Today's prices and the cheapest time to run the dishwasher.",
            kind = ModuleKind.Planned,
            sources = listOf(
                ModuleSource("homey", "Homey Energy", "Prices and use from Homey's own Energy page.", SourceKind.Homey),
                ModuleSource("tibber", "Homey app: Tibber", "Prices from your Tibber contract.", SourceKind.HomeyApp, TIBBER),
            ),
            showsOn = listOf("Price strip on the overview"),
            assistant = "\"When is electricity cheapest today?\"",
        ),
        AppModule(
            id = "departures",
            name = "Departures",
            summary = "The next buses and trains from the stops near home.",
            kind = ModuleKind.Planned,
            sources = listOf(ModuleSource("sl", "Built in: SL", "Live departures from SL's open data.", SourceKind.BuiltIn)),
            showsOn = listOf("Departures card on the overview"),
            assistant = "\"When does the next bus leave?\"",
        ),
        AppModule(
            id = "doorbell",
            name = "Doorbell and cameras",
            summary = "A pop-up with the camera when someone rings.",
            kind = ModuleKind.Planned,
            sources = listOf(ModuleSource("homey", "Homey camera and doorbell apps", "Any doorbell or camera that shows its picture in Homey.", SourceKind.HomeyApp)),
            showsOn = listOf("Doorbell pop-up", "Camera card"),
            assistant = "\"Who is at the door?\"",
        ),
    )

    fun byId(id: String): AppModule? = all.firstOrNull { it.id == id }

    /**
     * The Homey apps installed: the list Homey gave, or when the API key may not list apps
     * ([apps] null), the apps the devices come from.
     */
    fun installed(apps: Map<String, HomeyApp>?, devices: Collection<Device>): Set<String> =
        apps?.values?.filter { it.running }?.map { it.id }?.toSet()
            ?: devices.mapNotNull { it.appId }.toSet()

    /** The module's devices: those of its sources' apps, or of its device classes. */
    fun devicesOf(module: AppModule, devices: Collection<Device>): List<Device> {
        if (module.kind != ModuleKind.Devices) return emptyList()
        val apps = module.sources.mapNotNull { it.app?.id }.toSet()
        return devices.filter { d -> d.appId in apps || d.effectiveClass in module.deviceClasses }.sortedBy { it.name.lowercase() }
    }

    fun state(
        module: AppModule,
        choice: ModuleChoice,
        installed: Set<String>,
        devices: Collection<Device>,
        names: Map<String, String> = emptyMap(),
    ): ModuleState {
        val source = module.source(choice.source)
        val own = devicesOf(module, devices)
        val known = (module.sources.flatMap { it.apps }).associateBy { it.id }
        fun ref(id: String) = known[id] ?: HomeyAppRef(id, names[id] ?: id)
        // The apps actually doing the job: the source's apps that are installed, and for a device
        // module whichever apps its devices come from. Only when there are none is the usual app missing.
        val serving = (source.apps.map { it.id }.filter { it in installed } + own.mapNotNull { it.appId }.takeIf { module.kind == ModuleKind.Devices }.orEmpty())
            .distinct()
        val apps = if (serving.isNotEmpty()) serving.map { ref(it) to true } else listOfNotNull(source.app).map { it to false }
        val status = when (module.kind) {
            ModuleKind.Planned -> ModuleStatus.Planned
            ModuleKind.Devices -> when {
                own.isNotEmpty() -> ModuleStatus.On
                // Another app's vacuum or TV also counts, so only ask for the app when nothing is there.
                apps.any { !it.second } -> ModuleStatus.NeedsApp
                else -> ModuleStatus.NoDevices
            }
            ModuleKind.Feature -> when {
                !choice.on -> ModuleStatus.Off
                apps.any { !it.second } -> ModuleStatus.NeedsApp
                else -> ModuleStatus.On
            }
        }
        return ModuleState(module, choice, status, source, apps, own, installed)
    }

    /** Every module's state, in catalog order. */
    fun states(choices: Map<String, ModuleChoice>, apps: Map<String, HomeyApp>?, devices: Collection<Device>): List<ModuleState> {
        val installed = installed(apps, devices)
        val names = apps.orEmpty().mapValues { it.value.name }
        return all.map { state(it, choices[it.id] ?: ModuleChoice(), installed, devices, names) }
    }

    /** Whether a feature module is in use on this tablet; dashboard parts check this before showing. */
    fun isOn(choices: Map<String, ModuleChoice>, id: String): Boolean = choices[id]?.on ?: true

    /** Saved choices as text lines (`id|on|source`), for the settings store. */
    fun encode(choices: Map<String, ModuleChoice>): Set<String> =
        choices.map { (id, c) -> listOf(id, if (c.on) "1" else "0", c.source.orEmpty()).joinToString("|") }.toSet()

    fun decode(lines: Set<String>): Map<String, ModuleChoice> = lines.mapNotNull { line ->
        val parts = line.split('|')
        val id = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        id to ModuleChoice(on = parts.getOrNull(1) != "0", source = parts.getOrNull(2)?.ifEmpty { null })
    }.toMap()
}
