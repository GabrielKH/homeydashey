package app.homeydash.ui.dashboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.homeydash.homey.AutocompleteItem
import app.homeydash.homey.ConnectionState
import app.homeydash.homey.FlowCardAction
import app.homeydash.homey.FlowCards
import app.homeydash.homey.MusicSource
import app.homeydash.homey.RemoteKey
import app.homeydash.homey.SpeakerGrouping
import app.homeydash.homey.DashboardLayout
import app.homeydash.homey.HomeyInfo
import app.homeydash.homey.HomeyStore
import app.homeydash.homey.Mood
import app.homeydash.homey.RoomSummary
import app.homeydash.ui.components.Backdrop
import app.homeydash.ui.components.HOME_BACKGROUND
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.backgroundFor
import app.homeydash.ui.components.RoundButton
import app.homeydash.ui.components.Ic
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.onBrush
import app.homeydash.ui.components.pressable
import app.homeydash.ui.components.zoneIcon
import app.homeydash.ui.theme.GlassTheme
import app.homeydash.data.AppUpdater
import app.homeydash.data.Blur
import app.homeydash.data.CalendarRepository
import app.homeydash.data.WeatherRepository
import app.homeydash.data.DisplaySettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Album art, and a tiny blurred copy that is stretched behind the player. */
class AlbumArtImages(val sharp: ImageBitmap, val blurred: ImageBitmap)

/** A panel opened over the dashboard. */
sealed interface Sheet {
    data class Light(val deviceId: String) : Sheet
    data class Remote(val deviceId: String) : Sheet
    /** Music for a room ([zoneId]), or straight to one speaker ([deviceId]). */
    data class Music(val zoneId: String?, val deviceId: String? = null) : Sheet
    data class Background(val key: String, val title: String) : Sheet
    data class Group(val deviceId: String) : Sheet
    data object Rooms : Sheet
    data object Weather : Sheet
    data object Calendars : Sheet
}

/** Everything a card can do to the Homey. One instance per dashboard. */
@Stable
class DashboardActions(private val store: HomeyStore, private val scope: CoroutineScope) {
    var sheet by mutableStateOf<Sheet?>(null)

    /** Flow cards per device, kept in step with the store by the dashboard. */
    var deviceCards by mutableStateOf<Map<String, List<FlowCardAction>>>(emptyMap())
        internal set

    fun open(sheet: Sheet) {
        this.sheet = sheet
    }

    fun close() {
        sheet = null
    }

    fun cards(deviceId: String): List<FlowCardAction> = deviceCards[deviceId].orEmpty()

    fun remoteKeys(deviceId: String): Map<RemoteKey, FlowCardAction> = FlowCards.remoteKeys(cards(deviceId))

    fun musicSources(deviceId: String): List<MusicSource> = FlowCards.musicSources(cards(deviceId))

    fun grouping(deviceId: String): SpeakerGrouping? = FlowCards.grouping(cards(deviceId))

    fun run(card: FlowCardAction, args: JsonObject = JsonObject(emptyMap())) {
        scope.launch { store.runAction(card, args) }
    }

    suspend fun autocomplete(card: FlowCardAction, arg: String, query: String): List<AutocompleteItem> =
        try {
            store.autocomplete(card, arg, query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            store.reportError("Couldn't load the list: ${e.message}")
            emptyList()
        }

    fun reportError(message: String) = store.reportError(message)

    /** The mood tapped last; Homey doesn't report an active mood, so the dashboard remembers it. */
    var lastMood by mutableStateOf<String?>(null)
        private set

    private val images = LruCache<String, ImageBitmap>(12)

    fun set(deviceId: String, capabilityId: String, value: JsonElement) {
        scope.launch { store.setCapability(deviceId, capabilityId, value) }
    }

    fun setAll(deviceIds: List<String>, capabilityId: String, value: JsonElement) {
        scope.launch { store.setAll(deviceIds, capabilityId, value) }
    }

    fun setMood(mood: Mood) {
        lastMood = mood.id
        scope.launch { store.setMood(mood) }
    }

    private val arts = LruCache<String, AlbumArtImages>(6)

    /** Album art with a softly blurred copy for the player's background. */
    suspend fun loadArt(path: String, cacheKey: String): AlbumArtImages? {
        arts.get(cacheKey)?.let { return it }
        return runCatching {
            val bytes = store.image(path)
            withContext(Dispatchers.Default) {
                val bitmap = decodeScaled(bytes, maxSide = 1024) ?: return@withContext null
                val small = Bitmap.createScaledBitmap(bitmap, 48, (48f * bitmap.height / bitmap.width).toInt().coerceAtLeast(1), true)
                AlbumArtImages(bitmap.asImageBitmap(), Blur.box(small, radius = 3, passes = 3).asImageBitmap())
            }
        }.getOrNull()?.also { arts.put(cacheKey, it) }
    }

    /** Album art and other Homey images, decoded off the main thread and kept for a few tracks. */
    suspend fun loadImage(path: String, cacheKey: String): ImageBitmap? {
        images.get(cacheKey)?.let { return it }
        return runCatching {
            val bytes = store.image(path)
            withContext(Dispatchers.Default) { decodeScaled(bytes, maxSide = 512)?.asImageBitmap() }
        }.getOrNull()?.also { images.put(cacheKey, it) }
    }
}

/** Decodes an image no larger than about [maxSide], so a grid of album covers stays light. */
private fun decodeScaled(bytes: ByteArray, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

private const val OVERVIEW = "overview"
private const val MOODS = "moods"
private const val MEDIA = "media"
private const val ROOM = "room:"

@Composable
fun DashboardScreen(
    store: HomeyStore,
    info: HomeyInfo,
    display: DisplaySettings,
    updater: AppUpdater,
    weather: WeatherRepository,
    calendar: CalendarRepository,
    onForget: () -> Unit,
) {
    val update by updater.state.collectAsStateWithLifecycle()
    val weatherState by weather.state.collectAsStateWithLifecycle()
    val weatherSource by weather.source.collectAsStateWithLifecycle()
    val weatherPlace by weather.place.collectAsStateWithLifecycle()
    val hasCalendar by calendar.hasPermission.collectAsStateWithLifecycle()
    val events by calendar.events.collectAsStateWithLifecycle()
    val calendars by calendar.calendars.collectAsStateWithLifecycle()
    val chosenCalendars by calendar.selected.collectAsStateWithLifecycle()
    val themeMode by display.themeMode.collectAsStateWithLifecycle()
    val hiddenOnTablet by display.hiddenDevices.collectAsStateWithLifecycle()
    val snapshot by store.snapshot.collectAsStateWithLifecycle()
    val connection by store.connection.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val actions = remember(store) { DashboardActions(store, scope) }
    SideEffect { actions.deviceCards = snapshot.deviceCards }
    val accent by display.accent.collectAsStateWithLifecycle()
    val accentOpacity by display.accentOpacity.collectAsStateWithLifecycle()
    val backgrounds by display.backgrounds.collectAsStateWithLifecycle()

    val hidden = remember(snapshot.hiddenByUser, hiddenOnTablet) { snapshot.hiddenByUser + hiddenOnTablet }
    val hiddenRooms by display.hiddenRooms.collectAsStateWithLifecycle()
    val compactSidebar by display.compactSidebar.collectAsStateWithLifecycle()
    val roomOrder by display.roomOrder.collectAsStateWithLifecycle()
    val sections = remember(snapshot.zones, snapshot.devices, hidden, hiddenRooms, roomOrder) {
        DashboardLayout.sections(snapshot.zones, snapshot.devices, hidden, hiddenRooms, roomOrder)
    }
    val rooms = remember(sections) { DashboardLayout.rooms(sections) }
    val players = remember(snapshot.devices, hidden) { DashboardLayout.mediaPlayers(snapshot.devices, hidden) }
    val moods = remember(snapshot.moods) { DashboardLayout.moods(snapshot.moods) }
    val homeName = remember(snapshot.zones) { snapshot.zones.values.firstOrNull { it.parent == null }?.name ?: "Home" }
    val visibleDevices = remember(snapshot.devices, hidden) { DashboardLayout.visible(snapshot.devices, hidden).values }
    val speakers = remember(visibleDevices, actions.deviceCards) { musicSpeakers(visibleDevices, actions) }
    val groupable = remember(visibleDevices, actions.deviceCards) { visibleDevices.filter { actions.grouping(it.id) != null }.sortedBy { it.name.lowercase() } }

    var destination by rememberSaveable { mutableStateOf(OVERVIEW) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showDevices by rememberSaveable { mutableStateOf(false) }

    val roomId = destination.takeIf { it.startsWith(ROOM) }?.removePrefix(ROOM)
    val (backgroundKey, backgroundSpec) = backgroundFor(backgrounds, roomId)
    Crossfade(targetState = backgroundKey to backgroundSpec, animationSpec = tween(400), label = "backdrop") { (key, spec) ->
        Backdrop(key, spec, display.photos)
    }

    Row(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Sidebar(
            homeName = homeName,
            connection = connection,
            rooms = rooms,
            hasMoods = moods.isNotEmpty(),
            hasMedia = players.isNotEmpty(),
            destination = destination,
            onNavigate = { destination = it },
            onSettings = { showSettings = true },
            compact = compactSidebar,
            onCompact = display::setCompactSidebar,
        )
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (!snapshot.loaded) {
                if (snapshot.error == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = GlassTheme.colors.fg)
                }
            } else {
                AnimatedContent(
                    targetState = destination,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "page",
                ) { target ->
                    when {
                        target == MOODS -> MoodsScreen(moods, snapshot.zones, actions)
                        target == MEDIA -> MediaScreen(players, snapshot.zones, actions, hasMusic = speakers.isNotEmpty())
                        target.startsWith(ROOM) -> {
                            val id = target.removePrefix(ROOM)
                            val room = rooms.firstOrNull { it.zone.id == id }
                            if (room == null) {
                                // The room was deleted or emptied; go back home.
                                LaunchedEffect(id) { destination = OVERVIEW }
                            } else {
                                RoomScreen(room, sections, snapshot.zones, snapshot.moods, actions, hasMusic = speakers.isNotEmpty())
                            }
                        }
                        else -> OverviewScreen(
                            rooms = rooms,
                            moods = moods,
                            player = players.firstOrNull(),
                            zones = snapshot.zones,
                            connection = connection,
                            actions = actions,
                            hasMusic = speakers.isNotEmpty(),
                            onRoom = { destination = ROOM + it },
                            widgets = {
                                WeatherCard(weatherState) { actions.open(Sheet.Weather) }
                                CalendarCard(
                                    hasPermission = hasCalendar,
                                    events = events,
                                    onPermissionResult = calendar::permissionChanged,
                                    onChooseCalendars = { actions.open(Sheet.Calendars) },
                                )
                            },
                        )
                    }
                }
            }
            UpdateBanner(update, onInstall = { scope.launch { updater.install() } }, modifier = Modifier.align(Alignment.TopEnd))
            snapshot.error?.let { message ->
                ErrorBanner(message, onDismiss = store::clearError, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            info = info,
            themeMode = themeMode,
            onThemeMode = display::setThemeMode,
            accent = accent,
            onAccent = display::setAccent,
            accentOpacity = accentOpacity,
            onAccentOpacity = display::setAccentOpacity,
            onHomeBackground = { showSettings = false; actions.open(Sheet.Background(HOME_BACKGROUND, homeName)) },
            onChooseDevices = { showSettings = false; showDevices = true },
            onChooseRooms = { showSettings = false; actions.open(Sheet.Rooms) },
            onWeather = { showSettings = false; actions.open(Sheet.Weather) },
            onCalendars = { showSettings = false; actions.open(Sheet.Calendars) },
            version = updater.currentVersion,
            update = update,
            onCheckUpdate = { scope.launch { updater.check() } },
            onInstallUpdate = { scope.launch { updater.install() } },
            onForget = { showSettings = false; onForget() },
            onDismiss = { showSettings = false },
        )
    }
    if (showDevices) {
        DevicePickerDialog(
            zones = snapshot.zones,
            devices = snapshot.devices,
            hiddenByUser = snapshot.hiddenByUser,
            hiddenOnTablet = hiddenOnTablet,
            onSetHidden = display::setHidden,
            onDismiss = { showDevices = false },
        )
    }
    when (val sheet = actions.sheet) {
        null -> Unit
        is Sheet.Light -> snapshot.devices[sheet.deviceId]?.let { LightSheet(it, actions, actions::close) } ?: SideEffect { actions.close() }
        is Sheet.Remote -> snapshot.devices[sheet.deviceId]?.let { RemoteSheet(it, actions, actions::close) } ?: SideEffect { actions.close() }
        is Sheet.Music -> MusicSheet(sheet.zoneId, sheet.deviceId, speakers, snapshot.zones, actions, actions::close)
        is Sheet.Group -> snapshot.devices[sheet.deviceId]?.let { GroupSheet(it, groupable, snapshot.zones, actions, actions::close) } ?: SideEffect { actions.close() }
        Sheet.Rooms -> RoomsDialog(
            zones = snapshot.zones,
            hiddenRooms = hiddenRooms,
            order = roomOrder,
            onHide = display::setRoomHidden,
            onOrder = display::setRoomOrder,
            onDismiss = actions::close,
        )
        Sheet.Weather -> WeatherDialog(
            source = weatherSource,
            place = weatherPlace,
            hasLocationPermission = weather::hasLocationPermission,
            search = { weather.search(it) },
            onChoose = { source, place -> weather.choose(source, place); actions.close() },
            onDismiss = actions::close,
        )
        Sheet.Calendars -> CalendarsDialog(
            calendars = calendars,
            selected = chosenCalendars,
            onSelected = calendar::setSelected,
            onDismiss = actions::close,
        )
        is Sheet.Background -> BackgroundDialog(
            key = sheet.key,
            title = sheet.title,
            current = backgrounds[sheet.key],
            display = display,
            onError = actions::reportError,
            onDismiss = actions::close,
        )
    }
}

@Composable
private fun Sidebar(
    homeName: String,
    connection: ConnectionState,
    rooms: List<RoomSummary>,
    hasMoods: Boolean,
    hasMedia: Boolean,
    destination: String,
    onNavigate: (String) -> Unit,
    onSettings: () -> Unit,
    compact: Boolean,
    onCompact: (Boolean) -> Unit,
) {
    val g = GlassTheme.colors
    val width by animateDpAsState(if (compact) 76.dp else 232.dp, label = "sidebar")
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .glass()
            .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = 18.dp),
        horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        if (compact) {
            // The home icon doubles as the live indicator and the button that opens the menu again.
            Box(Modifier.padding(bottom = 16.dp).pressable({ onCompact(false) }), contentAlignment = Alignment.TopEnd) {
                Box(Modifier.size(48.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
                    SymIcon(Sym.home, filled = true, size = 24.dp)
                }
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (connection == ConnectionState.Connected) g.ok else g.danger))
            }
        } else {
            Row(Modifier.padding(start = 10.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                SymIcon(Sym.home, filled = true, size = 28.dp)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(homeName, style = MaterialTheme.typography.titleLarge, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val live = connection == ConnectionState.Connected
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (live) g.ok else g.danger))
                        Text(
                            when (connection) {
                                ConnectionState.Connected -> "Live"
                                ConnectionState.Connecting -> "Connecting…"
                                ConnectionState.Disconnected -> "Reconnecting…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = g.muted,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                RoundButton(Sym.keyboardArrowLeft, { onCompact(true) }, size = 36.dp, filledIcon = false, contentDescription = "Shrink menu")
            }
        }
        NavItem(Sym.spaceDashboard, "Overview", destination == OVERVIEW, compact = compact) { onNavigate(OVERVIEW) }
        if (hasMoods) NavItem(Sym.autoAwesome, "Moods", destination == MOODS, compact = compact) { onNavigate(MOODS) }
        if (hasMedia) NavItem(Sym.playCircle, "Media", destination == MEDIA, compact = compact) { onNavigate(MEDIA) }
        if (compact) {
            Box(Modifier.padding(vertical = 10.dp).size(width = 32.dp, height = 1.dp).background(g.glassLine))
        } else {
            Text(
                "ROOMS",
                style = MaterialTheme.typography.labelMedium,
                color = g.muted,
                modifier = Modifier.padding(start = 12.dp, top = 18.dp, bottom = 6.dp),
            )
        }
        LazyColumn(Modifier.weight(1f), horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start) {
            items(rooms, key = { it.zone.id }) { room ->
                NavItem(
                    icon = zoneIcon(room.zone),
                    label = room.zone.name,
                    selected = destination == ROOM + room.zone.id,
                    indent = if (compact) 0 else (room.depth - 1).coerceIn(0, 2),
                    badge = room.lightsOn.takeIf { it > 0 }?.toString(),
                    compact = compact,
                ) { onNavigate(ROOM + room.zone.id) }
            }
        }
        Spacer(Modifier.size(8.dp))
        NavItem(Sym.settings, "Settings", selected = false, compact = compact, onClick = onSettings)
    }
}

@Composable
private fun NavItem(
    icon: Ic,
    label: String,
    selected: Boolean,
    indent: Int = 0,
    badge: String? = null,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val g = GlassTheme.colors
    if (compact) {
        // Icons only; a dot shows a room with lights on.
        Box(
            Modifier
                .padding(bottom = 4.dp)
                .size(52.dp)
                .pressable(onClick)
                .then(if (selected) Modifier.glass(RoundedCornerShape(16.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            SymIcon(icon, filled = selected, size = 24.dp, tint = if (selected) g.fg else g.muted)
            if (badge != null) {
                Box(Modifier.align(Alignment.TopEnd).padding(9.dp).size(8.dp).background(onBrush(), CircleShape))
            }
        }
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp * indent, bottom = 2.dp)
            .pressable(onClick)
            .then(if (selected) Modifier.glass(RoundedCornerShape(16.dp)) else Modifier)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SymIcon(icon, filled = selected, size = 23.dp, tint = if (selected) g.fg else g.muted)
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = g.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        badge?.let {
            Box(Modifier.background(g.onSoft, PillShape).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = g.fg)
            }
        }
    }
}

/** A small pill offering a newer build; it shows download progress once tapped. */
@Composable
private fun UpdateBanner(state: AppUpdater.State, onInstall: () -> Unit, modifier: Modifier = Modifier) {
    val g = GlassTheme.colors
    val text = when (state) {
        AppUpdater.State.UpToDate -> return
        is AppUpdater.State.Available -> "New version ready · Install"
        is AppUpdater.State.Downloading -> "Downloading update… ${(state.progress * 100).toInt()}%"
        is AppUpdater.State.Failed -> "Update failed · Try again"
    }
    Row(
        modifier
            .padding(4.dp)
            .pressable(onInstall.takeIf { state !is AppUpdater.State.Downloading })
            .background(g.wallBase, PillShape)
            .glass(PillShape)
            .padding(start = 12.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SymIcon(Sym.check, filled = true, size = 18.dp, tint = if (state is AppUpdater.State.Failed) g.danger else g.ok)
        Text(text, style = MaterialTheme.typography.labelLarge, color = g.fg)
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val g = GlassTheme.colors
    Row(
        modifier
            .padding(16.dp)
            .background(g.wallBase, RoundedCornerShape(20.dp))
            .glass(RoundedCornerShape(20.dp))
            .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SymIcon(Sym.error, filled = true, size = 22.dp, tint = g.danger)
        Text(message, color = g.fg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
        RoundButton(Sym.close, onDismiss, size = 40.dp, filledIcon = false)
    }
}
