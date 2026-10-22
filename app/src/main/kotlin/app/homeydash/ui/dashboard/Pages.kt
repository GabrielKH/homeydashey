package app.homeydash.ui.dashboard

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.homeydash.homey.ConnectionState
import app.homeydash.homey.DashboardLayout
import app.homeydash.homey.Device
import app.homeydash.homey.CardArrangement
import app.homeydash.homey.CardLayout
import app.homeydash.homey.OverviewLanes
import app.homeydash.homey.LaneCard
import app.homeydash.homey.Mood
import app.homeydash.homey.RoomSummary
import app.homeydash.homey.Zone
import app.homeydash.homey.ZoneSection
import app.homeydash.ui.assistant.LocalTimerChip
import app.homeydash.ui.components.Bubble
import app.homeydash.ui.components.GlassChip
import app.homeydash.ui.components.HOME_BACKGROUND
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.Ic
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.TileShape
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.moodIcon
import app.homeydash.ui.components.onBrush
import app.homeydash.ui.components.pressable
import app.homeydash.ui.components.SectionLabel
import app.homeydash.ui.components.zoneIcon
import app.homeydash.ui.theme.GlassTheme
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val GridGap = 14.dp

// ---------------------------------------------------------------- Overview

@Composable
fun OverviewScreen(
    rooms: List<RoomSummary>,
    moods: List<Mood>,
    player: Device?,
    zones: Map<String, Zone>,
    connection: ConnectionState,
    actions: DashboardActions,
    hasMusic: Boolean,
    onRoom: (String) -> Unit,
    /** Devices pinned to the overview, each shown as its own card. */
    favourites: List<Device>,
    /** The saved order of every card on the overview, by key (lanes, see [OverviewLanes]). */
    order: List<String>,
    onOrder: (List<String>) -> Unit,
    /** Draws the weather, calendar, fridge or who's home card for its key. */
    widget: @Composable (String) -> Unit,
    /** Whether Homey has users to show in a who's home card. */
    hasHousehold: Boolean = false,
    /** Cards whose module is turned off under Modules (weather, calendar, fridge), and notes until asked for. */
    widgetsOff: Set<String> = emptySet(),
    /** Takes a pinned device off the overview. */
    onUnpin: (String) -> Unit = {},
    /** Hides a room's card. */
    onHideRoom: (String) -> Unit = {},
) {
    val g = GlassTheme.colors
    val lightsOn = remember(rooms) { rooms.sumOf { it.lightsOn } }
    val locks = remember(rooms) { rooms.flatMap { it.devices }.filter { it.capability("locked") != null } }
    val unlocked = remember(locks) { locks.count { it.capability("locked")?.booleanValue != true } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Two lanes on a small tablet, three on the 11", four on a big screen.
    val laneCount = (maxWidth / 380.dp).toInt().coerceIn(2, 4)
    val roomsById = remember(rooms) { rooms.associateBy { ROOM_KEY + it.zone.id } }
    val favouritesById = remember(favourites) { favourites.associateBy { FAV + it.id } }
    val hasPlayer = player != null
    val laneCards = remember(hasPlayer, hasHousehold, favouritesById, roomsById, widgetsOff) { buildList {
        if ("weather" !in widgetsOff) add(LaneCard("weather", LaneCard.Kind.Info, wide = true, height = 300))
        if ("calendar" !in widgetsOff) add(LaneCard("calendar", LaneCard.Kind.Info, wide = true, height = 380))
        if ("fridge" !in widgetsOff) add(LaneCard("fridge", LaneCard.Kind.Info, wide = true, height = 280))
        if ("notes" !in widgetsOff) add(LaneCard("notes", LaneCard.Kind.Info, wide = true, height = 300))
        if (hasHousehold) add(LaneCard("home", LaneCard.Kind.Info, wide = true, height = 200))
        if (hasPlayer) add(LaneCard("player", LaneCard.Kind.Media, wide = true, height = 310))
        favouritesById.forEach { (key, device) ->
            val wide = tileSpan(device) > 1
            add(LaneCard(key, LaneCard.Kind.Device, wide = wide, height = if (wide) 310 else 148))
        }
        roomsById.keys.forEach { add(LaneCard(it, LaneCard.Kind.Room, wide = false, height = 148)) }
    } }
    val wideKeys = remember(laneCards) { laneCards.filter { it.wide }.map { it.key }.toSet() }
    // The lanes while a card is dragged, until the saved layout catches up.
    var draft by remember { mutableStateOf<List<List<String>>?>(null) }
    LaunchedEffect(order) { draft = null }
    val placed = remember(order, laneCards, laneCount) { OverviewLanes.place(OverviewLanes.parse(order), laneCards, laneCount) }
    val lanes = draft ?: placed
    val autoArrange = { onOrder(OverviewLanes.flatten(OverviewLanes.auto(laneCards, laneCount))) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
            OverviewClock(Modifier.weight(1f))
            if (actions.rearrange.editing) {
                DoneChip(actions, Modifier.padding(bottom = 4.dp), onAutoArrange = autoArrange)
            } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                LocalTimerChip.current()
                if (connection != ConnectionState.Connected) {
                    GlassChip(Sym.wifiOff, "Reconnecting", g.danger)
                }
                GlassChip(Sym.lightbulb, if (lightsOn == 0) "All off" else "$lightsOn on", if (lightsOn > 0) g.on else g.muted, filled = lightsOn > 0)
                if (locks.isNotEmpty()) {
                    GlassChip(
                        if (unlocked == 0) Sym.lock else Sym.lockOpen,
                        if (unlocked == 0) "Locked" else "$unlocked unlocked",
                        if (unlocked == 0) g.ok else g.danger,
                    )
                }
                if (hasMusic) MusicChip { actions.open(Sheet.Music(zoneId = null)) }
                GlassChip(Sym.bedtime, "Goodnight", g.fg, onClick = { actions.open(Sheet.Goodnight) })
                IconChip(Sym.image) { actions.open(Sheet.Background(HOME_BACKGROUND, "Home")) }
            }
        }
        if (moods.isNotEmpty()) MoodRow(moods, actions)
        // The cards stack in a few invisible lanes; any card can go in any lane.
        val scroll = rememberScrollState()
        val drag = rememberLaneDrag(scroll)
        val haptics = LocalHapticFeedback.current
        SideEffect {
            drag.lanes = lanes
            drag.onMove = { draft = it }
            drag.onDrop = { draft?.let { onOrder(OverviewLanes.flatten(it)) } }
            // The weather, calendar and player have no hold of their own; the lanes start it.
            drag.holdable = ::isWidgetKey
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { drag.viewport = it }
                .rearrangeable(drag, actions.rearrange, onHold = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }),
        ) {
            LaneLayout(
                lanes = lanes,
                wide = { it in wideKeys },
                gap = GridGap,
                drag = drag,
                rearrange = actions.rearrange,
                menu = { key ->
                    val room = roomsById[key]
                    val device = favouritesById[key]
                    when {
                        room != null -> { { actions.open(Sheet.RoomMenu(room.zone.id)) } }
                        device != null -> { { actions.open(Sheet.CardMenu(device.id)) } }
                        else -> null
                    }
                },
                // The minus takes a pinned device off the overview, or hides a room.
                remove = { key ->
                    val room = roomsById[key]
                    val device = favouritesById[key]
                    when {
                        room != null -> { { onHideRoom(room.zone.id) } }
                        device != null -> { { onUnpin(device.id) } }
                        else -> null
                    }
                },
                modifier = Modifier.fillMaxWidth().verticalScroll(scroll).padding(bottom = 24.dp),
            ) { key ->
                val room = roomsById[key]
                val device = favouritesById[key]
                when {
                    room != null -> RoomCard(room, actions, onClick = { onRoom(room.zone.id) }, onLongClick = { actions.hold() })
                    device != null -> DeviceTile(device, actions)
                    key == "player" && player != null -> MediaPlayerCard(player, actions, subtitle = player.zone?.let { zones[it]?.name })
                    else -> Column { widget(key) }
                }
            }
        }
    }
    }
}

/** The greeting, time and date; on its own so the minute ticking redraws only this, not the cards. */
@Composable
private fun OverviewClock(modifier: Modifier) {
    val g = GlassTheme.colors
    val now = rememberNow()
    val context = LocalContext.current
    val timeFormat = remember(context) { DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm") }
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()) }
    Column(modifier) {
        Text(greeting(now.hour), style = MaterialTheme.typography.bodyLarge, color = g.muted)
        Text(now.format(timeFormat), style = MaterialTheme.typography.displayLarge, color = g.fg)
        Text(
            now.format(dateFormat).replaceFirstChar { it.titlecase(Locale.getDefault()) },
            style = MaterialTheme.typography.bodyLarge,
            color = g.muted,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun RoomCard(room: RoomSummary, actions: DashboardActions, onClick: () -> Unit, onLongClick: () -> Unit) {
    val g = GlassTheme.colors
    val anyOn = room.lightsOn > 0
    Column(
        Modifier
            .fillMaxWidth()
            .height(TileHeight)
            .pressable(onClick, onLongClick)
            .glass(TileShape)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Bubble(zoneIcon(room.zone), on = anyOn)
            Spacer(Modifier.weight(1f))
            if (room.lights.isNotEmpty()) {
                // Turns every light in the room off, or all on when none are lit.
                Box(
                    Modifier
                        .size(40.dp)
                        .pressable({ actions.setAll(room.lights.map { it.id }, "onoff", JsonPrimitive(!anyOn)) })
                        .then(if (anyOn) Modifier.background(onBrush(), CircleShape) else Modifier.glass(CircleShape)),
                    contentAlignment = Alignment.Center,
                ) {
                    SymIcon(Sym.lightbulb, filled = anyOn, size = 21.dp, tint = if (anyOn) g.onInk else g.fg)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        TileTitle(room.zone.name)
        val meta = listOfNotNull(
            room.temperature?.let { shortTemperature(it) },
            when {
                room.playing != null -> "Playing"
                room.lights.isEmpty() -> null
                anyOn -> if (room.lightsOn == 1) "1 light on" else "${room.lightsOn} lights on"
                else -> "All off"
            },
            room.humidity?.takeIf { room.lights.isEmpty() }?.let { "${it.toInt()}% humidity" },
        ).ifEmpty { listOf(deviceCount(room.devices.size)) }
        TileStatus(meta.joinToString("  ·  "), strong = anyOn)
    }
}

private fun deviceCount(n: Int) = if (n == 1) "1 device" else "$n devices"

private fun greeting(hour: Int) = when (hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    in 18..22 -> "Good evening"
    else -> "Good night"
}

/** The current time, updated on the minute. */
@Composable
private fun rememberNow(): LocalDateTime {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            val current = LocalDateTime.now()
            value = current
            delay(60_000L - current.second * 1_000L - current.nano / 1_000_000L + 50)
        }
    }
    return now
}

// ---------------------------------------------------------------- Moods

@Composable
fun MoodRow(moods: List<Mood>, actions: DashboardActions) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(moods, key = { it.id }) { mood -> MoodPill(mood, active = actions.lastMood == mood.id) { actions.setMood(mood) } }
    }
}

@Composable
private fun MoodPill(mood: Mood, active: Boolean, onClick: () -> Unit) {
    val g = GlassTheme.colors
    Row(
        Modifier
            .pressable(onClick)
            .then(if (active) Modifier.background(onBrush(), PillShape) else Modifier.glass(PillShape))
            .padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .then(if (active) Modifier.background(g.onInk.copy(alpha = 0.12f), CircleShape) else Modifier.glass(CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            SymIcon(moodIcon(mood), filled = true, size = 20.dp, tint = if (active) g.onInk else g.fg)
        }
        Text(mood.name, style = MaterialTheme.typography.labelLarge, color = if (active) g.onInk else g.fg, maxLines = 1)
    }
}

@Composable
fun MoodsScreen(moods: List<Mood>, zones: Map<String, Zone>, actions: DashboardActions) {
    val g = GlassTheme.colors
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(eyebrow = "Tap to set", title = "Moods")
        LazyVerticalGrid(
            columns = GridCells.Adaptive(196.dp),
            horizontalArrangement = Arrangement.spacedBy(GridGap),
            verticalArrangement = Arrangement.spacedBy(GridGap),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(moods, key = { it.id }) { mood ->
                val active = actions.lastMood == mood.id
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(TileHeight)
                        .pressable({ actions.setMood(mood) })
                        .glass(TileShape)
                        .padding(16.dp),
                ) {
                    Bubble(moodIcon(mood), on = active)
                    Spacer(Modifier.weight(1f))
                    TileTitle(mood.name)
                    TileStatus(mood.zone?.let { zones[it]?.name } ?: "Home", strong = active)
                }
            }
        }
        if (moods.isEmpty()) {
            Text("No moods yet. Create them in the Homey app under a room's Moods.", color = g.muted, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

// ---------------------------------------------------------------- Media

@Composable
fun MediaScreen(players: List<Device>, zones: Map<String, Zone>, actions: DashboardActions, hasMusic: Boolean, lastPlayer: String?) {
    val g = GlassTheme.colors
    // A player picked in the list stays until the page is left; otherwise the page follows what plays.
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val featured = DashboardLayout.featuredPlayer(players, chosen, lastPlayer)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val playing = players.count { DashboardLayout.isPlaying(it) }
        PageHeader(eyebrow = if (playing == 0) "Nothing playing" else "$playing playing", title = "Media") {
            if (hasMusic) MusicChip { actions.open(Sheet.Music(zoneId = null)) }
        }
        if (featured == null) {
            Text("No speakers or TVs on this Homey yet.", style = MaterialTheme.typography.bodyLarge, color = g.muted)
            return@Column
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GridGap)) {
            Crossfade(targetState = featured.id, animationSpec = tween(300), modifier = Modifier.weight(1f).fillMaxHeight(), label = "player") { id ->
                val device = players.firstOrNull { it.id == id } ?: featured
                MediaHero(device, actions, Modifier.fillMaxSize(), subtitle = device.zone?.let { zones[it]?.name })
            }
            if (players.size > 1) {
                Column(
                    Modifier.width(330.dp).fillMaxHeight().glass(TileShape).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SectionLabel("Players", Modifier.padding(start = 8.dp, top = 6.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(players, key = { it.id }) { player ->
                            PlayerRow(
                                device = player,
                                actions = actions,
                                subtitle = player.zone?.let { zones[it]?.name },
                                selected = player.id == featured.id,
                                onClick = { chosen = player.id },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Room

@Composable
fun RoomScreen(
    room: RoomSummary,
    sections: List<ZoneSection>,
    zones: Map<String, Zone>,
    moods: Map<String, Mood>,
    actions: DashboardActions,
    hasMusic: Boolean,
    arrangement: CardArrangement,
    onCardOrder: (zoneId: String, order: List<String>) -> Unit,
) {
    val g = GlassTheme.colors
    val remote = remember(room.devices, actions.deviceCards) { room.devices.firstOrNull { actions.hasRemote(it) } }
    val subtree = remember(zones, room.zone.id) { DashboardLayout.subtree(zones, room.zone.id) }
    val roomSections = remember(sections, subtree) { sections.filter { it.zone.id in subtree } }
    val roomMoods = remember(moods, room.zone.id) { DashboardLayout.moods(moods, room.zone.id) }
    val eyebrow = remember(zones, room.zone.id) { DashboardLayout.parents(zones, room.zone.id).firstOrNull()?.name ?: "Room" }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // The order while a card is dragged, until the saved order catches up.
        var pending by remember(room.zone.id) { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
        LaunchedEffect(arrangement) { pending = emptyMap() }
        val shown = if (pending.isEmpty()) arrangement else arrangement.copy(order = arrangement.order + pending)
        val grid = rememberLazyGridState()
        val drag = rememberGridDrag(grid)
        SideEffect {
            fun sectionOf(id: Any) = roomSections.firstOrNull { section -> section.devices.any { it.id == id } }
            fun current() = arrangement.copy(order = arrangement.order + pending)
            drag.draggable = { key -> sectionOf(key) != null }
            drag.canMove = { a, b -> sectionOf(a)?.let { CardLayout.moveTo(it, current(), a as String, b as String) } != null }
            drag.onMove = { a, b ->
                val section = sectionOf(a)
                val order = section?.let { CardLayout.moveTo(it, current(), a as String, b as String) }
                if (section != null && order != null) pending = pending + (section.zone.id to order)
            }
            drag.onDrop = { pending.forEach { (zoneId, order) -> onCardOrder(zoneId, order) } }
        }
        PageHeader(eyebrow = eyebrow, title = room.zone.name) {
            if (actions.rearrange.editing) {
                DoneChip(actions)
            } else {
                room.temperature?.let { GlassChip(Sym.deviceThermostat, shortTemperature(it), g.cool, filled = false) }
                room.humidity?.let { GlassChip(Sym.waterDrop, "${it.toInt()}%", g.cool, filled = false) }
                if (room.lights.size > 1) {
                    val anyOn = room.lightsOn > 0
                    GlassChip(
                        Sym.lightbulb,
                        if (anyOn) "All off" else "All on",
                        if (anyOn) g.on else g.muted,
                        filled = anyOn,
                        onClick = { actions.setAll(room.lights.map { it.id }, "onoff", JsonPrimitive(!anyOn)) },
                    )
                }
                remote?.let { GlassChip(Sym.remote, "Remote", g.fg, onClick = { actions.open(Sheet.Remote(it.id)) }) }
                if (hasMusic) MusicChip { actions.open(Sheet.Music(room.zone.id)) }
                IconChip(Sym.image) { actions.open(Sheet.Background(room.zone.id, room.zone.name)) }
            }
        }
        if (roomMoods.isNotEmpty()) MoodRow(roomMoods, actions)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(176.dp),
            horizontalArrangement = Arrangement.spacedBy(GridGap),
            verticalArrangement = Arrangement.spacedBy(GridGap),
            contentPadding = PaddingValues(bottom = 24.dp),
            state = grid,
            modifier = Modifier.weight(1f).rearrangeable(drag, actions.rearrange),
        ) {
            roomSections.forEach { section ->
                if (roomSections.size > 1) {
                    item(key = "zone:${section.zone.id}", span = { GridItemSpan(maxLineSpan) }, contentType = "zone") {
                        Text(
                            section.zone.name,
                            style = MaterialTheme.typography.titleLarge,
                            color = g.fg,
                            modifier = Modifier.padding(start = 4.dp, top = 10.dp),
                        )
                    }
                }
                groupedTiles(section, shown, actions, drag)
            }
        }
    }
}

private fun LazyGridScope.groupedTiles(section: ZoneSection, arrangement: CardArrangement, actions: DashboardActions, drag: GridDrag) {
    CardLayout.groups(section, arrangement).forEach { group ->
        item(key = "label:${section.zone.id}:${group.custom}:${group.title}", span = { GridItemSpan(maxLineSpan) }, contentType = "label") {
            SectionLabel(group.title)
        }
        items(
            group.devices,
            key = { it.id },
            span = { GridItemSpan(tileSpan(it).coerceAtMost(maxLineSpan)) },
            contentType = { DashboardLayout.cardKind(it) },
        ) { device ->
            RearrangeableItem(device.id, drag, actions.rearrange, menu = { actions.open(Sheet.CardMenu(device.id)) }) {
                DeviceTile(device, actions)
            }
        }
    }
}

@Composable
internal fun PageHeader(eyebrow: String, title: String, chips: @Composable () -> Unit = {}) {
    val g = GlassTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(eyebrow, style = MaterialTheme.typography.bodyLarge, color = g.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, style = MaterialTheme.typography.headlineLarge, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            LocalTimerChip.current()
            chips()
        }
    }
}

/** The overview's cards that aren't devices or rooms. */
private val WIDGETS = setOf("weather", "calendar", "player", "fridge", "home", "notes")

private fun isWidgetKey(key: Any): Boolean = key is String && key in WIDGETS

/** Overview grid keys for rooms. */
private const val ROOM_KEY = "room:"

/** Overview grid keys for pinned devices, apart from the room keys. */
private const val FAV = "fav:"

/** Ends rearranging. */
@Composable
private fun DoneChip(actions: DashboardActions, modifier: Modifier = Modifier, onAutoArrange: (() -> Unit)? = null) {
    val g = GlassTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Drag cards to move them, tap one to edit it", style = MaterialTheme.typography.bodyMedium, color = g.muted)
        onAutoArrange?.let { GlassChip(Sym.autoAwesome, "Auto arrange", g.fg, onClick = it) }
        Row(
            Modifier
                .pressable({ actions.rearrange.done() })
                .background(onBrush(), PillShape)
                .padding(start = 14.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SymIcon(Sym.check, filled = true, size = 18.dp, tint = g.onInk)
            Text("Done", style = MaterialTheme.typography.labelLarge, color = g.onInk)
        }
    }
}

@Composable
private fun MusicChip(onClick: () -> Unit) {
    GlassChip(Sym.musicNotes, "Music", GlassTheme.colors.fg, onClick = onClick)
}

/** A round glass header button with just an icon, e.g. the room's background picker. */
@Composable
internal fun IconChip(icon: Ic, onClick: () -> Unit) {
    Box(
        Modifier
            .size(42.dp)
            .pressable(onClick)
            .glass(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        SymIcon(icon, size = 20.dp)
    }
}
