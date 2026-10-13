package app.homeydash.ui.dashboard

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import app.homeydash.homey.DeviceGroup
import app.homeydash.homey.Mood
import app.homeydash.homey.RoomSummary
import app.homeydash.homey.Zone
import app.homeydash.homey.ZoneSection
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
    /** Weather and calendar cards for the right-hand column. */
    widgets: @Composable ColumnScope.() -> Unit,
) {
    val g = GlassTheme.colors
    val now = rememberNow()
    val context = LocalContext.current
    val timeFormat = remember(context) { DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm") }
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()) }
    val lightsOn = rooms.sumOf { it.lightsOn }
    val locks = rooms.flatMap { it.devices }.filter { it.capability("locked") != null }
    val unlocked = locks.count { it.capability("locked")?.booleanValue != true }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(greeting(now.hour), style = MaterialTheme.typography.bodyLarge, color = g.muted)
                Text(now.format(timeFormat), style = MaterialTheme.typography.displayLarge, color = g.fg)
                Text(
                    now.format(dateFormat).replaceFirstChar { it.titlecase(Locale.getDefault()) },
                    style = MaterialTheme.typography.bodyLarge,
                    color = g.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 4.dp)) {
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
                IconChip(Sym.image) { actions.open(Sheet.Background(HOME_BACKGROUND, "Home")) }
            }
        }
        if (moods.isNotEmpty()) MoodRow(moods, actions)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(GridGap)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(196.dp),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(GridGap),
                verticalArrangement = Arrangement.spacedBy(GridGap),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, contentType = "label") { SectionLabel("Rooms") }
                items(rooms, key = { it.zone.id }, contentType = { "room" }) { room ->
                    RoomCard(room, actions, onClick = { onRoom(room.zone.id) })
                }
            }
            Column(
                Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(GridGap),
            ) {
                widgets()
                if (player != null) {
                    SectionLabel("Now playing")
                    MediaPlayerCard(player, actions, subtitle = player.zone?.let { zones[it]?.name })
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun RoomCard(room: RoomSummary, actions: DashboardActions, onClick: () -> Unit) {
    val g = GlassTheme.colors
    val anyOn = room.lightsOn > 0
    Column(
        Modifier
            .fillMaxWidth()
            .height(TileHeight)
            .pressable(onClick)
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
fun MediaScreen(players: List<Device>, zones: Map<String, Zone>, actions: DashboardActions, hasMusic: Boolean) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val playing = players.count { it.capability("speaker_playing")?.booleanValue == true }
        PageHeader(eyebrow = if (playing == 0) "Nothing playing" else "$playing playing", title = "Media") {
            if (hasMusic) MusicChip { actions.open(Sheet.Music(zoneId = null)) }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(300.dp),
            horizontalArrangement = Arrangement.spacedBy(GridGap),
            verticalArrangement = Arrangement.spacedBy(GridGap),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(players, key = { it.id }) { player ->
                MediaPlayerCard(player, actions, large = true, subtitle = player.zone?.let { zones[it]?.name })
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
) {
    val g = GlassTheme.colors
    val remote = remember(room.devices, actions.deviceCards) { room.devices.firstOrNull { actions.remoteKeys(it.id).isNotEmpty() } }
    val subtree = remember(zones, room.zone.id) { DashboardLayout.subtree(zones, room.zone.id) }
    val roomSections = remember(sections, subtree) { sections.filter { it.zone.id in subtree } }
    val roomMoods = remember(moods, room.zone.id) { DashboardLayout.moods(moods, room.zone.id) }
    val eyebrow = remember(zones, room.zone.id) { DashboardLayout.parents(zones, room.zone.id).firstOrNull()?.name ?: "Room" }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(eyebrow = eyebrow, title = room.zone.name) {
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
        if (roomMoods.isNotEmpty()) MoodRow(roomMoods, actions)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(188.dp),
            horizontalArrangement = Arrangement.spacedBy(GridGap),
            verticalArrangement = Arrangement.spacedBy(GridGap),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.weight(1f),
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
                groupedTiles(section, actions)
            }
        }
    }
}

private fun LazyGridScope.groupedTiles(section: ZoneSection, actions: DashboardActions) {
    val groups = section.devices.groupBy(DashboardLayout::group)
    DeviceGroup.entries.forEach { group ->
        val devices = groups[group] ?: return@forEach
        item(key = "label:${section.zone.id}:${group.name}", span = { GridItemSpan(maxLineSpan) }, contentType = "label") {
            SectionLabel(group.title)
        }
        items(
            devices,
            key = { it.id },
            span = { GridItemSpan(tileSpan(it).coerceAtMost(maxLineSpan)) },
            contentType = { DashboardLayout.cardKind(it) },
        ) { device ->
            DeviceTile(device, actions)
        }
    }
}

@Composable
private fun PageHeader(eyebrow: String, title: String, chips: @Composable () -> Unit = {}) {
    val g = GlassTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(eyebrow, style = MaterialTheme.typography.bodyLarge, color = g.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, style = MaterialTheme.typography.headlineLarge, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 4.dp)) { chips() }
    }
}

@Composable
private fun MusicChip(onClick: () -> Unit) {
    GlassChip(Sym.musicNotes, "Music", GlassTheme.colors.fg, onClick = onClick)
}

/** A round glass header button with just an icon, e.g. the room's background picker. */
@Composable
private fun IconChip(icon: Ic, onClick: () -> Unit) {
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
