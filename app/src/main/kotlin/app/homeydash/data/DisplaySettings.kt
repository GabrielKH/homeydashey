package app.homeydash.data

import android.net.Uri
import androidx.compose.runtime.Stable
import app.homeydash.homey.CardArrangement
import app.homeydash.ui.theme.Accent
import app.homeydash.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How this tablet shows the dashboard: theme, accent, backgrounds and devices left out. */
@Stable
class DisplaySettings(
    private val settings: SettingsRepository,
    val photos: BackgroundPhotos,
    private val scope: CoroutineScope,
) {
    val themeMode: StateFlow<ThemeMode> = settings.themeMode.stateIn(scope, SharingStarted.Eagerly, ThemeMode.System)
    val accent: StateFlow<Accent> = settings.accent.stateIn(scope, SharingStarted.Eagerly, Accent.Lavender)
    val accentOpacity: StateFlow<Float> = settings.accentOpacity.stateIn(scope, SharingStarted.Eagerly, 1f)
    val hiddenDevices: StateFlow<Set<String>> = settings.hiddenDevices.stateIn(scope, SharingStarted.Eagerly, emptySet())
    val compactSidebar: StateFlow<Boolean> = settings.compactSidebar.stateIn(scope, SharingStarted.Eagerly, false)
    val hiddenRooms: StateFlow<Set<String>> = settings.hiddenRooms.stateIn(scope, SharingStarted.Eagerly, emptySet())
    val roomOrder: StateFlow<List<String>> = settings.roomOrder.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val backgrounds: StateFlow<Map<String, String>> = settings.backgrounds.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    val cardArrangement: StateFlow<CardArrangement> = settings.cardArrangement.stateIn(scope, SharingStarted.Eagerly, CardArrangement())
    val uiScale: StateFlow<Float> = settings.uiScale.stateIn(scope, SharingStarted.Eagerly, 0f)
    val calendarView: StateFlow<String> = settings.calendarView.stateIn(scope, SharingStarted.Eagerly, "week")

    val weatherDays: StateFlow<Int> = settings.weatherDays.stateIn(scope, SharingStarted.Eagerly, 3)
    val palette: StateFlow<String> = settings.palette.stateIn(scope, SharingStarted.Eagerly, "aurora")
    val flat: StateFlow<Boolean> = settings.flat.stateIn(scope, SharingStarted.Eagerly, false)
    val kiosk: StateFlow<Boolean> = settings.kiosk.stateIn(scope, SharingStarted.Eagerly, false)
    /** The saved kiosk setting, without a default before it loads. */
    val kioskSaved: Flow<Boolean> = settings.kiosk
    val dimAfter: StateFlow<Int> = settings.dimAfter.stateIn(scope, SharingStarted.Eagerly, 0)
    val wakeOnProximity: StateFlow<Boolean> = settings.wakeOnProximity.stateIn(scope, SharingStarted.Eagerly, true)

    fun setWeatherDays(days: Int) {
        scope.launch { settings.setWeatherDays(days) }
    }

    fun setPalette(key: String) {
        scope.launch { settings.setPalette(key) }
    }

    fun setFlat(flat: Boolean) {
        scope.launch { settings.setFlat(flat) }
    }

    fun setKiosk(on: Boolean) {
        scope.launch { settings.setKiosk(on) }
    }

    fun setDimAfter(minutes: Int) {
        scope.launch { settings.setDimAfter(minutes) }
    }

    fun setWakeOnProximity(on: Boolean) {
        scope.launch { settings.setWakeOnProximity(on) }
    }

    fun setCardOrder(zoneId: String, order: List<String>) {
        scope.launch { settings.setCardOrder(zoneId, order) }
    }

    fun setCardGroup(deviceId: String, title: String?) {
        scope.launch { settings.setCardGroup(deviceId, title) }
    }

    fun setUiScale(scale: Float) {
        scope.launch { settings.setUiScale(scale) }
    }

    fun setCalendarView(view: String) {
        scope.launch { settings.setCalendarView(view) }
    }

    val remoteApps: StateFlow<Map<String, List<String>>> = settings.remoteApps.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** null goes back to the default: streaming apps first, then the rest. */
    fun setRemoteApps(deviceId: String, apps: List<String>?) {
        scope.launch { settings.setRemoteApps(deviceId, apps) }
    }

    fun setThemeMode(mode: ThemeMode) {
        scope.launch { settings.setThemeMode(mode) }
    }

    fun setAccent(accent: Accent) {
        scope.launch { settings.setAccent(accent) }
    }

    fun setAccentOpacity(opacity: Float) {
        scope.launch { settings.setAccentOpacity(opacity) }
    }

    fun setCompactSidebar(compact: Boolean) {
        scope.launch { settings.setCompactSidebar(compact) }
    }

    fun setRoomHidden(zoneId: String, hidden: Boolean) {
        scope.launch { settings.setRoomHidden(zoneId, hidden) }
    }

    fun setRoomOrder(order: List<String>) {
        scope.launch { settings.setRoomOrder(order) }
    }

    fun setHidden(deviceId: String, hidden: Boolean) {
        scope.launch { settings.setHidden(deviceId, hidden) }
    }

    /** [spec] is a [app.homeydash.ui.components.BackgroundSpec] string, or null for the default. */
    fun setBackground(key: String, spec: String?) {
        scope.launch {
            if (spec == null || !spec.startsWith("photo:")) photos.delete(key)
            settings.setBackground(key, spec)
        }
    }

    fun importPhoto(key: String, uri: Uri, onError: (String) -> Unit) {
        scope.launch {
            runCatching { photos.import(key, uri) }
                .onSuccess { version -> settings.setBackground(key, "photo:$version") }
                .onFailure { onError(it.message ?: "Couldn't use that photo") }
        }
    }
}
