package app.homeydash.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.homeydash.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class SavedHomey(val address: String, val apiKey: String)

private val Context.dataStore by preferencesDataStore(name = "homey")

/** Remembers the Homey address and API key so they are entered only once, and the theme. */
class SettingsRepository(private val context: Context) {

    val saved: Flow<SavedHomey?> = context.dataStore.data.map { prefs ->
        val address = prefs[ADDRESS]
        val key = prefs[API_KEY]
        if (address.isNullOrBlank() || key.isNullOrBlank()) null else SavedHomey(address, key)
    }

    suspend fun save(homey: SavedHomey) {
        context.dataStore.edit {
            it[ADDRESS] = homey.address
            it[API_KEY] = homey.apiKey
        }
    }

    /** Forgets the Homey; display preferences such as the theme are kept. */
    suspend fun clear() {
        context.dataStore.edit {
            it.remove(ADDRESS)
            it.remove(API_KEY)
        }
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        prefs[THEME]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.System
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[THEME] = mode.name }
    }

    /** Devices hidden from the dashboard on this tablet only. */
    val hiddenDevices: Flow<Set<String>> = context.dataStore.data.map { it[HIDDEN].orEmpty() }

    suspend fun setHidden(deviceId: String, hidden: Boolean) {
        context.dataStore.edit {
            val current = it[HIDDEN].orEmpty()
            it[HIDDEN] = if (hidden) current + deviceId else current - deviceId
        }
    }

    private companion object {
        val ADDRESS = stringPreferencesKey("address")
        val API_KEY = stringPreferencesKey("api_key")
        val THEME = stringPreferencesKey("theme")
        val HIDDEN = stringSetPreferencesKey("hidden_devices")
    }
}
