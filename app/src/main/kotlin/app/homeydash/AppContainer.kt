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
    val updater = AppUpdater(context, http, scope)
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
    }.also { assistant.kitchen = it }

    private val _session = MutableStateFlow<SessionState>(SessionState.Starting)
    val session: StateFlow<SessionState> = _session.asStateFlow()

    val weather = WeatherRepository(context, http, settings, scope) {
        (_session.value as? SessionState.Ready)?.store?.location()
    }

    init {
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

    private fun start(ready: SessionState.Ready) {
        ready.store.start()
        learning?.cancel()
        learning = scope.launch { assistant.learnFrom(ready.store.household) }
        _session.value = ready
        // The weather may use the Homey's location, known only now.
        weather.refresh()
    }

    private fun HomeySession.newStore(saved: SavedHomey) = SessionState.Ready(
        store = HomeyStore(client, newRealtime(saved.apiKey, http), scope, extraCards = listOf(sonos), householdDir = householdDir),
        info = info,
    )
}

private const val UPDATE_CHECK_MS = 3 * 60 * 60 * 1000L
