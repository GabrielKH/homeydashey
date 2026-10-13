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
    val backgrounds: StateFlow<Map<String, String>> = settings.backgrounds.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun setThemeMode(mode: ThemeMode) {
        scope.launch { settings.setThemeMode(mode) }
    }

    fun setAccent(accent: Accent) {
        scope.launch { settings.setAccent(accent) }
    }

    fun setAccentOpacity(opacity: Float) {
        scope.launch { settings.setAccentOpacity(opacity) }
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
