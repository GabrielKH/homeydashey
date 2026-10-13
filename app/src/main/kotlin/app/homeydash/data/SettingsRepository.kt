package app.homeydash.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.homeydash.ui.theme.Accent
import app.homeydash.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class SavedHomey(val address: String, val apiKey: String)

private val Context.dataStore by preferencesDataStore(name = "homey")

/** Remembers the Homey address and API key so they are entered only once, and the display preferences. */
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

    val accent: Flow<Accent> = context.dataStore.data.map { prefs ->
        prefs[ACCENT]?.let { name -> Accent.entries.firstOrNull { it.name == name } } ?: Accent.Lavender
    }

    suspend fun setAccent(accent: Accent) {
        context.dataStore.edit { it[ACCENT] = accent.name }
    }

    /** Where the weather is for: `homey`, `gps`, `ip` or `place`. */
    val weatherSource: Flow<String> = context.dataStore.data.map { it[WEATHER_SOURCE] ?: "homey" }

    /** A chosen place as name, detail, latitude and longitude separated by tabs. */
    val weatherPlace: Flow<String?> = context.dataStore.data.map { it[WEATHER_PLACE] }

    suspend fun setWeather(source: String, place: String?) {
        context.dataStore.edit {
            it[WEATHER_SOURCE] = source
            if (place != null) it[WEATHER_PLACE] = place
        }
    }

    /** Calendar ids shown on the dashboard; empty means every calendar the phone shows. */
    val calendars: Flow<Set<String>> = context.dataStore.data.map { it[CALENDARS].orEmpty() }

    suspend fun setCalendars(ids: Set<String>) {
        context.dataStore.edit { it[CALENDARS] = ids }
    }

    /** The side menu shrunk to icons only. */
    val compactSidebar: Flow<Boolean> = context.dataStore.data.map { it[COMPACT_SIDEBAR] ?: false }

    suspend fun setCompactSidebar(compact: Boolean) {
        context.dataStore.edit { it[COMPACT_SIDEBAR] = compact }
    }

    /** Rooms left off the dashboard on this tablet, by zone id. */
    val hiddenRooms: Flow<Set<String>> = context.dataStore.data.map { it[HIDDEN_ROOMS].orEmpty() }

    suspend fun setRoomHidden(zoneId: String, hidden: Boolean) {
        context.dataStore.edit {
            val current = it[HIDDEN_ROOMS].orEmpty()
            it[HIDDEN_ROOMS] = if (hidden) current + zoneId else current - zoneId
        }
    }

    /** Zone ids in the order the user arranged them. */
    val roomOrder: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[ROOM_ORDER]?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
    }

    suspend fun setRoomOrder(order: List<String>) {
        context.dataStore.edit { it[ROOM_ORDER] = order.joinToString(",") }
    }

    val accentOpacity: Flow<Float> = context.dataStore.data.map { it[ACCENT_OPACITY] ?: 1f }

    suspend fun setAccentOpacity(opacity: Float) {
        context.dataStore.edit { it[ACCENT_OPACITY] = opacity }
    }

    /** Backgrounds by key ("home" or a zone id), as [BackgroundSpec] strings. */
    val backgrounds: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        prefs.asMap().entries
            .filter { it.key.name.startsWith(BG_PREFIX) }
            .associate { it.key.name.removePrefix(BG_PREFIX) to it.value.toString() }
    }

    /** Sets a background; null goes back to the default (a room then uses the home's). */
    suspend fun setBackground(key: String, spec: String?) {
        context.dataStore.edit {
            val pref = stringPreferencesKey(BG_PREFIX + key)
            if (spec == null) it.remove(pref) else it[pref] = spec
        }
    }

    /** The apps each TV box's remote shows, in order, by device id; missing means the default. */
    val remoteApps: Flow<Map<String, List<String>>> = context.dataStore.data.map { prefs ->
        prefs.asMap().entries
            .filter { it.key.name.startsWith(APPS_PREFIX) }
            .associate { it.key.name.removePrefix(APPS_PREFIX) to it.value.toString().split('\n').filter { id -> id.isNotEmpty() } }
    }

    suspend fun setRemoteApps(deviceId: String, apps: List<String>?) {
        context.dataStore.edit {
            val pref = stringPreferencesKey(APPS_PREFIX + deviceId)
            if (apps == null) it.remove(pref) else it[pref] = apps.joinToString("\n")
        }
    }

    private companion object {
        const val BG_PREFIX = "bg_"
        const val APPS_PREFIX = "remote_apps_"
        val ACCENT = stringPreferencesKey("accent")
        val WEATHER_SOURCE = stringPreferencesKey("weather_source")
        val WEATHER_PLACE = stringPreferencesKey("weather_place")
        val CALENDARS = stringSetPreferencesKey("calendars")
        val COMPACT_SIDEBAR = booleanPreferencesKey("compact_sidebar")
        val HIDDEN_ROOMS = stringSetPreferencesKey("hidden_rooms")
        val ROOM_ORDER = stringPreferencesKey("room_order")
        val ACCENT_OPACITY = floatPreferencesKey("accent_opacity")

        val ADDRESS = stringPreferencesKey("address")
        val API_KEY = stringPreferencesKey("api_key")
        val THEME = stringPreferencesKey("theme")
        val HIDDEN = stringSetPreferencesKey("hidden_devices")
    }
}
