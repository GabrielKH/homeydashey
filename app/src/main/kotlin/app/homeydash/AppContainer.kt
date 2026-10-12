package app.homeydash

import android.content.Context
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    private val connector = HomeyConnector(http)

    private val _session = MutableStateFlow<SessionState>(SessionState.Starting)
    val session: StateFlow<SessionState> = _session.asStateFlow()

    init {
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

    private fun start(ready: SessionState.Ready) {
        ready.store.start()
        _session.value = ready
    }

    private fun HomeySession.newStore(saved: SavedHomey) = SessionState.Ready(
        store = HomeyStore(client, newRealtime(saved.apiKey, http), scope),
        info = info,
    )
}
