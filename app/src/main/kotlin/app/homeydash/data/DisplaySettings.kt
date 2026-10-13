package app.homeydash.data

import android.net.Uri
import androidx.compose.runtime.Stable
import app.homeydash.ui.theme.Accent
import app.homeydash.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
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
