package app.homeydash.data

import app.homeydash.homey.HomeyApp
import app.homeydash.homey.HomeyStore
import app.homeydash.modules.AppModules
import app.homeydash.modules.ModuleChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The dashboard's modules on this tablet: which are turned on, where each gets its data, and
 * which Homey apps are installed. Every change is told to [onChoice] so the assistant can
 * remember it.
 */
class Modules(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val onChoice: suspend (module: String, on: Boolean, source: String) -> Unit,
) {
    val choices: StateFlow<Map<String, ModuleChoice>> = settings.modules
        .map { AppModules.decode(it) }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val _apps = MutableStateFlow<Map<String, HomeyApp>?>(null)
    /** Homey's installed apps, or null while unknown or when the API key may not list them. */
    val apps: StateFlow<Map<String, HomeyApp>?> = _apps.asStateFlow()

    private val _appsAllowed = MutableStateFlow(true)
    /** False when the API key lacks "View apps"; the apps are then guessed from the devices. */
    val appsAllowed: StateFlow<Boolean> = _appsAllowed.asStateFlow()

    private var store: HomeyStore? = null
    private var watching: Job? = null

    fun set(id: String, choice: ModuleChoice) {
        val module = AppModules.byId(id) ?: return
        val all = choices.value + (id to choice)
        scope.launch {
            settings.setModules(AppModules.encode(all))
            runCatching { onChoice(module.name, choice.on, module.source(choice.source).label) }
        }
    }

    /** Follows a new Homey: its apps now and every few minutes, as apps get installed. */
    fun attach(store: HomeyStore) {
        this.store = store
        watching?.cancel()
        watching = scope.launch {
            while (true) {
                refreshApps()
                delay(APPS_EVERY_MS)
            }
        }
    }

    fun detach() {
        watching?.cancel()
        store = null
        _apps.value = null
    }

    /** Asks Homey again, after installing an app. */
    fun refresh() {
        scope.launch { refreshApps() }
    }

    private suspend fun refreshApps() {
        val store = store ?: return
        runCatching { store.apps() }
            .onSuccess { apps ->
                _appsAllowed.value = apps != null
                _apps.value = apps
            }
    }

    private companion object {
        const val APPS_EVERY_MS = 10 * 60 * 1000L
    }
}
