package app.homeydash

import android.content.Context
import app.homeydash.assistant.AssistantServices
import app.homeydash.data.AppUpdater
import app.homeydash.data.BackgroundPhotos
import app.homeydash.data.CalendarRepository
import app.homeydash.data.WeatherRepository
import app.homeydash.data.DisplaySettings
import app.homeydash.data.HomeyDiscovery
import app.homeydash.data.SonosFinder
import app.homeydash.data.Kitchen
import app.homeydash.data.Modules
import app.homeydash.homey.appId
import app.homeydash.data.SavedHomey
import app.homeydash.data.SettingsRepository
import app.homeydash.homey.HomeyConnectException
import app.homeydash.homey.HomeyConnector
import app.homeydash.homey.HomeyInfo
import app.homeydash.homey.HomeySession
import app.homeydash.homey.HomeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.homeydash.sonos.SonosCards
import app.homeydash.sonos.SonosClient
import app.homeydash.sonos.SpotifyApp
import app.homeydash.sonos.SpotifySearch
import java.util.Locale
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

sealed interface SessionState {
    data object Starting : SessionState
    data object NeedsSetup : SessionState
    data class Unreachable(val saved: SavedHomey, val message: String) : SessionState
    data class Ready(val store: HomeyStore, val info: HomeyInfo) : SessionState
}

/** Hand-wired dependencies; one instance lives for the whole process. */
class AppContainer(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val settings = SettingsRepository(context)
    private val householdDir = java.io.File(context.filesDir, "household")
    private val connector = HomeyConnector(http)

    val discovery = HomeyDiscovery(context, http)
    val updater = AppUpdater(context, http, scope, settings) { version, day, changes ->
        rememberForHouse(app.homeydash.agent.Memory.appUpdated(version, day, changes))
    }
    val calendar = CalendarRepository(context, settings, scope)

    val display = DisplaySettings(settings, BackgroundPhotos(context), scope)

    val assistant = AssistantServices(context, http, scope)

    /** Finds any song on Spotify for the Sonos speakers, once keys are added in the assistant's settings. */
    private val spotify = SpotifySearch(
        http,
        app = {
            assistant.settings.value.let { s ->
                if (s.spotifyClientId.isBlank() || s.spotifySecret.isBlank()) null else SpotifyApp(s.spotifyClientId, s.spotifySecret)
            }
        },
        market = Locale.getDefault().country.takeIf { it.length == 2 } ?: "SE",
    )
    private val sonos = SonosCards(SonosClient(http), SonosFinder(context, http)::find, spotify)

    /** Recipes, and the one being cooked; the voice assistant reads it too. */
    val kitchen = Kitchen(context, http, scope) { prompt ->
        assistant.web.raw(assistant.settings.value.webSearch(), prompt)
    }.also { k ->
        assistant.kitchen = k
        // Eaten and finished food goes into the assistant's memory, when memory is on.
        k.fridge.onUsed = { item, left ->
            if (assistant.settings.value.access(app.homeydash.agent.Secret.Memory) != app.homeydash.agent.Access.Off) {
                val lasted = item.boughtDate()?.takeIf { left == 0 }?.let { java.time.temporal.ChronoUnit.DAYS.between(it, java.time.LocalDate.now()).toInt() }
                assistant.memory.learn(app.homeydash.agent.Memory.finished(item.name, lasted, java.time.ZonedDateTime.now()))
            }
        }
    }

    /** The dashboard's modules; each choice goes into the assistant's memory, when memory is on. */
    val modules = Modules(settings, scope) { module, on, source ->
        rememberForHouse(app.homeydash.agent.Memory.moduleChoice(module, on, source))
    }

    private suspend fun rememberForHouse(fact: app.homeydash.agent.Fact) {
        if (assistant.settings.value.access(app.homeydash.agent.Secret.Memory) != app.homeydash.agent.Access.Off) {
            assistant.memory.learn(listOf(fact))
        }
    }

    private val _session = MutableStateFlow<SessionState>(SessionState.Starting)
    val session: StateFlow<SessionState> = _session.asStateFlow()

    val weather = WeatherRepository(context, http, settings, scope) {
        (_session.value as? SessionState.Ready)?.store?.location()
    }

    init {
        scope.launch { updater.findWhatsNew() }
        scope.launch {
            while (true) {
                updater.check()
                delay(UPDATE_CHECK_MS)
            }
        }
        // New Spotify keys add (or take away) the speakers' "Play from Spotify" without a restart.
        // The keys can also load after the speakers' cards were made, so compare with what they got.
        scope.launch {
            assistant.settings.map { it.spotifyClientId.isNotBlank() && it.spotifySecret.isNotBlank() }
                .distinctUntilChanged()
                .collect { keys ->
                    val shown = sonos.spotifyShown
                    if (shown != null && shown != keys) (_session.value as? SessionState.Ready)?.store?.reload()
                }
        }
        scope.launch {
            val saved = settings.saved.first()
            if (saved == null) _session.value = SessionState.NeedsSetup else open(saved)
        }
    }

    /** Called from the setup screen. Throws [HomeyConnectException] with a readable message. */
    suspend fun connect(address: String, apiKey: String) {
        val saved = SavedHomey(address.trim(), apiKey.trim())
        val homey = connector.connect(saved.address, saved.apiKey)
        settings.save(saved)
        start(homey.newStore(saved))
    }

    fun retry() {
        val state = _session.value as? SessionState.Unreachable ?: return
        _session.value = SessionState.Starting
        scope.launch { open(state.saved) }
    }

    fun forget() {
        (_session.value as? SessionState.Ready)?.store?.stop()
        learning?.cancel()
        following?.cancel()
        modules.detach()
        calendar.useHomey(null)
        _session.value = SessionState.NeedsSetup
        scope.launch { settings.clear() }
    }

    private suspend fun open(saved: SavedHomey) {
        try {
            start(connector.connect(saved.address, saved.apiKey).newStore(saved))
        } catch (e: HomeyConnectException) {
            _session.value = SessionState.Unreachable(saved, e.message.orEmpty())
        }
    }

    /** Teaches the assistant's memory from the current Homey's presence and alarms. */
    private var learning: kotlinx.coroutines.Job? = null

    /** Keeps the modules in step with the current Homey: its apps, the calendar's source, and what the assistant knows. */
    private var following: kotlinx.coroutines.Job? = null

    private fun start(ready: SessionState.Ready) {
        ready.store.start()
        learning?.cancel()
        learning = scope.launch { assistant.learnFrom(ready.store.household) }
        following?.cancel()
        modules.attach(ready.store)
        following = scope.launch { followModules(ready.store) }
        _session.value = ready
        // The weather may use the Homey's location, known only now.
        weather.refresh()
    }

    private suspend fun followModules(store: HomeyStore): Unit = kotlinx.coroutines.coroutineScope<Unit> {
        // The Calendar module reads from the tablet, or from the IcalCalendar app on Homey.
        launch {
            modules.choices.map { it["calendar"] ?: app.homeydash.modules.ModuleChoice() }
                .distinctUntilChanged()
                .collect { choice ->
                    val fromHomey = choice.on && choice.source == "homey"
                    calendar.useHomey(
                        if (!fromHomey) null else {
                            val reader = app.homeydash.homey.HomeyCalendar(run = { card, args ->
                                store.runAppAction(app.homeydash.homey.HomeyCalendar.APP_ID, card, args)
                            })
                            suspend { reader.events() }
                        },
                    )
                }
        }
        // A module that turns on by itself (its Homey app got devices) is told to the assistant once.
        launch {
            val told = mutableSetOf<String>()
            kotlinx.coroutines.flow.combine(store.snapshot, modules.apps, modules.choices) { snapshot, apps, choices ->
                if (!snapshot.loaded) emptyList() else app.homeydash.modules.AppModules.states(choices, apps, snapshot.devices.values)
            }.collect { states ->
                states.filter { it.module.kind == app.homeydash.modules.ModuleKind.Devices && it.status == app.homeydash.modules.ModuleStatus.On }
                    .forEach { state ->
                        val apps = state.devices.mapNotNull { d -> d.appId }.distinct()
                        val key = state.module.id + apps
                        if (told.add(key)) {
                            val names = apps.map { id -> modules.apps.value?.get(id)?.name ?: state.module.sources.firstOrNull { it.app?.id == id }?.app?.name ?: id }
                            runCatching { rememberForHouse(app.homeydash.agent.Memory.moduleChoice(state.module.name, true, "Homey: " + names.joinToString(" and ") + " (" + state.devices.size + " devices)")) }
                        }
                    }
            }
        }
    }

    private fun HomeySession.newStore(saved: SavedHomey) = SessionState.Ready(
        store = HomeyStore(client, newRealtime(saved.apiKey, http), scope, extraCards = listOf(sonos), householdDir = householdDir),
        info = info,
    )
}

private const val UPDATE_CHECK_MS = 3 * 60 * 60 * 1000L
