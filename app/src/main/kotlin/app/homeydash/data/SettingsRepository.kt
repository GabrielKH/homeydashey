package app.homeydash.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.homeydash.homey.CardArrangement
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

    /** How the user arranged cards: their order per room and the groups they made. */
    val cardArrangement: Flow<CardArrangement> = context.dataStore.data.map { prefs ->
        val order = prefs.asMap().entries
            .filter { it.key.name.startsWith(ORDER_PREFIX) }
            .associate { it.key.name.removePrefix(ORDER_PREFIX) to it.value.toString().split('\n').filter { id -> id.isNotEmpty() } }
        val groups = prefs[CARD_GROUPS].orEmpty().mapNotNull { entry ->
            val (id, title) = entry.split('\t', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            id to title
        }.toMap()
        CardArrangement(order, groups)
    }

    suspend fun setCardOrder(zoneId: String, order: List<String>) {
        context.dataStore.edit { it[stringPreferencesKey(ORDER_PREFIX + zoneId)] = order.joinToString("\n") }
    }

    /** Puts a card in a group of the user's, or back in its automatic group when [title] is null. */
    suspend fun setCardGroup(deviceId: String, title: String?) {
        context.dataStore.edit { prefs ->
            val others = prefs[CARD_GROUPS].orEmpty().filterNot { it.startsWith(deviceId + "\t") }.toSet()
            prefs[CARD_GROUPS] = if (title.isNullOrBlank()) others else others + (deviceId + "\t" + title.trim())
        }
    }

    /** What the owner says a device is, when Homey's class is wrong (e.g. a TV Homey calls a light). */
    val deviceClasses: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        prefs.asMap().entries
            .filter { it.key.name.startsWith(CLASS_PREFIX) }
            .associate { it.key.name.removePrefix(CLASS_PREFIX) to it.value.toString() }
    }

    suspend fun setDeviceClass(deviceId: String, cls: String?) {
        context.dataStore.edit {
            val pref = stringPreferencesKey(CLASS_PREFIX + deviceId)
            if (cls.isNullOrBlank()) it.remove(pref) else it[pref] = cls
        }
    }

    /** Other names per device, for the assistant. */
    val deviceNicknames: Flow<Map<String, List<String>>> = context.dataStore.data.map { prefs ->
        prefs.asMap().entries
            .filter { it.key.name.startsWith(NICKNAMES_PREFIX) }
            .associate { it.key.name.removePrefix(NICKNAMES_PREFIX) to it.value.toString().split('\n').filter { n -> n.isNotBlank() } }
    }

    suspend fun setDeviceNicknames(deviceId: String, names: List<String>) {
        context.dataStore.edit {
            val pref = stringPreferencesKey(NICKNAMES_PREFIX + deviceId)
            val clean = names.map { n -> n.trim() }.filter { n -> n.isNotEmpty() }.distinct()
            if (clean.isEmpty()) it.remove(pref) else it[pref] = clean.joinToString("\n")
        }
    }

    /** Display size: 0 for automatic (fit the screen), otherwise a scale such as 0.9. */
    val uiScale: Flow<Float> = context.dataStore.data.map { it[UI_SCALE] ?: 0f }

    suspend fun setUiScale(scale: Float) {
        context.dataStore.edit { it[UI_SCALE] = scale }
    }

    val calendarView: Flow<String> = context.dataStore.data.map { it[CALENDAR_VIEW] ?: "week" }

    suspend fun setCalendarView(view: String) {
        context.dataStore.edit { it[CALENDAR_VIEW] = view }
    }

    /** Forecast days on the overview's weather card. */
    val weatherDays: Flow<Int> = context.dataStore.data.map { it[WEATHER_DAYS] ?: 3 }

    suspend fun setWeatherDays(days: Int) {
        context.dataStore.edit { it[WEATHER_DAYS] = days }
    }

    val palette: Flow<String> = context.dataStore.data.map { it[PALETTE] ?: "aurora" }

    suspend fun setPalette(key: String) {
        context.dataStore.edit { it[PALETTE] = key }
    }

    /** Flat colours instead of glass and gradients. */
    val flat: Flow<Boolean> = context.dataStore.data.map { it[FLAT] ?: false }

    suspend fun setFlat(flat: Boolean) {
        context.dataStore.edit { it[FLAT] = flat }
    }

    val kiosk: Flow<Boolean> = context.dataStore.data.map { it[KIOSK] ?: false }

    suspend fun setKiosk(on: Boolean) {
        context.dataStore.edit { it[KIOSK] = on }
    }

    /** Minutes without a touch before the screen dims; 0 keeps it on. */
    val dimAfter: Flow<Int> = context.dataStore.data.map { it[DIM_AFTER] ?: 0 }

    suspend fun setDimAfter(minutes: Int) {
        context.dataStore.edit { it[DIM_AFTER] = minutes }
    }

    /** Wake the dimmed screen when someone comes close (proximity sensor). */
    val wakeOnProximity: Flow<Boolean> = context.dataStore.data.map { it[WAKE_PROXIMITY] ?: true }

    suspend fun setWakeOnProximity(on: Boolean) {
        context.dataStore.edit { it[WAKE_PROXIMITY] = on }
    }

    /** Devices pinned to the overview, in the order they show. */
    val overviewDevices: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[OVERVIEW_DEVICES]?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
    }

    suspend fun setOverviewDevices(ids: List<String>) {
        context.dataStore.edit { it[OVERVIEW_DEVICES] = ids.joinToString("\n") }
    }

    /** The overview's card order by key: weather, calendar, player, fav:<device>, room:<zone>. */
    val overviewOrder: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[OVERVIEW_ORDER]?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
    }

    suspend fun setOverviewOrder(keys: List<String>) {
        context.dataStore.edit { it[OVERVIEW_ORDER] = keys.joinToString("\n") }
    }

    /** The player that played last, so the media page opens on it. */
    val lastPlayer: Flow<String?> = context.dataStore.data.map { it[LAST_PLAYER] }

    suspend fun setLastPlayer(id: String) {
        context.dataStore.edit { if (it[LAST_PLAYER] != id) it[LAST_PLAYER] = id }
    }

    /** Devices Goodnight leaves alone. */
    val goodnightKeep: Flow<Set<String>> = context.dataStore.data.map { it[GOODNIGHT_KEEP].orEmpty() }

    suspend fun setGoodnightKeep(ids: Set<String>) {
        context.dataStore.edit { it[GOODNIGHT_KEEP] = ids }
    }

    /** The Flow Goodnight also starts, as id, "advanced" or "flow", and name separated by tabs. */
    val goodnightFlow: Flow<String?> = context.dataStore.data.map { it[GOODNIGHT_FLOW] }

    suspend fun setGoodnightFlow(flow: String?) {
        context.dataStore.edit { if (flow == null) it.remove(GOODNIGHT_FLOW) else it[GOODNIGHT_FLOW] = flow }
    }

    /** Whether Goodnight marks everyone home as asleep in Homey. */
    val goodnightAsleep: Flow<Boolean> = context.dataStore.data.map { it[GOODNIGHT_ASLEEP] ?: true }

    suspend fun setGoodnightAsleep(on: Boolean) {
        context.dataStore.edit { it[GOODNIGHT_ASLEEP] = on }
    }

    private companion object {
        val OVERVIEW_DEVICES = stringPreferencesKey("overview_devices")
        val LAST_PLAYER = stringPreferencesKey("last_player")
        val OVERVIEW_ORDER = stringPreferencesKey("overview_order")
        val GOODNIGHT_KEEP = stringSetPreferencesKey("goodnight_keep")
        val GOODNIGHT_FLOW = stringPreferencesKey("goodnight_flow")
        val GOODNIGHT_ASLEEP = booleanPreferencesKey("goodnight_asleep")
        val WEATHER_DAYS = intPreferencesKey("weather_days")
        val PALETTE = stringPreferencesKey("palette")
        val FLAT = booleanPreferencesKey("flat")
        val KIOSK = booleanPreferencesKey("kiosk")
        val DIM_AFTER = intPreferencesKey("dim_after")
        val WAKE_PROXIMITY = booleanPreferencesKey("wake_proximity")
        const val ORDER_PREFIX = "card_order_"
        val CARD_GROUPS = stringSetPreferencesKey("card_groups")
        val UI_SCALE = floatPreferencesKey("ui_scale")
        val CALENDAR_VIEW = stringPreferencesKey("calendar_view")
        const val BG_PREFIX = "bg_"
        const val APPS_PREFIX = "remote_apps_"
        const val CLASS_PREFIX = "device_class_"
        const val NICKNAMES_PREFIX = "device_nicknames_"
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
