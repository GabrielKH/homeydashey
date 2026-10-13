package app.homeydash.ui.dashboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.homeydash.ui.components.DialogHeightShare
import app.homeydash.ui.components.screenSizeDp
import app.homeydash.data.PartWear
import app.homeydash.data.VacuumUsage
import app.homeydash.homey.AutocompleteItem
import app.homeydash.homey.Capability
import app.homeydash.homey.Device
import app.homeydash.homey.FlowCards
import app.homeydash.homey.RoomCleaning
import app.homeydash.homey.VacuumChoiceCard
import app.homeydash.homey.VacuumCommand
import app.homeydash.homey.VacuumControls
import app.homeydash.homey.VacuumPart
import app.homeydash.homey.VacuumRoom
import app.homeydash.homey.VacuumState
import app.homeydash.homey.Vacuums
import app.homeydash.ui.components.Bubble
import app.homeydash.ui.components.DialogHeader
import app.homeydash.ui.components.GlassDialog
import app.homeydash.ui.components.Ic
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.RoundButton
import app.homeydash.ui.components.SectionLabel
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.TileShape
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.onBrush
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/** Parts with this much life left or less get a warning on the card and the page. */
private const val WORN = 0.1

/** Runs a vacuum button: sets the capability or runs the app's Flow card. */
fun DashboardActions.runVacuum(device: Device, command: VacuumCommand) {
    when (command) {
        is VacuumCommand.Set -> set(device.id, command.capability, command.value)
        is VacuumCommand.Card -> run(command.card, command.args)
    }
}

/** Robot vacuum on the dashboard: its map, what it's doing, battery and the main buttons. Tap for everything else. */
@Composable
fun VacuumTile(device: Device, actions: DashboardActions, modifier: Modifier = Modifier) {
    val g = GlassTheme.colors
    val cards = actions.cards(device.id)
    val controls = remember(device, cards) { Vacuums.controls(device, cards) }
    val usage = VacuumUsage.get(LocalContext.current)
    val counted by usage.usage.collectAsState()
    val worn = remember(controls, counted) { controls.parts.filter { (usage.wear(device.id, it)?.leftFraction ?: 1.0) <= WORN } }
    val map = rememberVacuumMap(controls, actions, maxSide = 512)
    Row(
        modifier
            .fillMaxWidth()
            .height(TileHeight)
            .pressable({ actions.open(Sheet.Vacuum(device.id)) }, { actions.hold(Sheet.CardMenu(device.id)) })
            .glass(TileShape)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (map != null) {
            Image(
                map,
                contentDescription = "Map",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(TileHeight - 24.dp).clip(RoundedCornerShape(16.dp)).background(g.glassTop),
            )
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.Top) {
                if (map == null) {
                    Bubble(Sym.vacuum, on = controls.state == VacuumState.Cleaning)
                    Spacer(Modifier.width(10.dp))
                }
                Spacer(Modifier.weight(1f))
                BatteryText(controls)
            }
            Spacer(Modifier.weight(1f))
            TileTitle(device.name)
            val warning = controls.alarms.firstOrNull()?.let { it.title ?: Vacuums.readable(it.id) }
                ?: worn.firstOrNull()?.let { "Replace the ${it.name.lowercase()}" }
            TileStatus(warning ?: statusLine(controls), strong = warning != null || controls.state == VacuumState.Cleaning, color = if (warning != null) g.danger else null)
        }
        if (device.available) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                mainButton(controls)?.let { (icon, command, label) ->
                    RoundButton(icon, { actions.runVacuum(device, command) }, size = 52.dp, primary = true, contentDescription = label)
                }
                val dock = controls.dock.takeIf { controls.state !in setOf(VacuumState.Docked, VacuumState.Charging) }
                dock?.let { RoundButton(Sym.home, { actions.runVacuum(device, it) }, size = 44.dp, filledIcon = false, contentDescription = "Send home") }
            }
        }
    }
}

/** The big button: Start, Pause or Resume, depending on what the robot is doing. */
private fun mainButton(c: VacuumControls): Triple<Ic, VacuumCommand, String>? = when (c.state) {
    VacuumState.Cleaning, VacuumState.Returning -> (c.pause ?: c.stop)?.let { Triple(if (c.pause != null) Sym.pause else Sym.stop, it, if (c.pause != null) "Pause" else "Stop") }
    VacuumState.Paused -> c.resume?.let { Triple(Sym.play, it, "Resume") }
    VacuumState.Offline -> null
    else -> c.start?.let { Triple(Sym.play, it, "Start") }
}

private fun stateText(state: VacuumState) = when (state) {
    VacuumState.Cleaning -> "Cleaning"
    VacuumState.Paused -> "Paused"
    VacuumState.Returning -> "Returning to dock"
    VacuumState.Docked -> "Docked"
    VacuumState.Charging -> "Charging"
    VacuumState.Idle -> "Ready"
    VacuumState.Error -> "Needs attention"
    VacuumState.Offline -> "Unavailable"
}

private fun statusLine(c: VacuumControls): String =
    c.statusText?.takeIf { c.state != VacuumState.Offline } ?: stateText(c.state)

@Composable
private fun BatteryText(c: VacuumControls) {
    val g = GlassTheme.colors
    val level = c.battery ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SymIcon(Sym.batteryFull, filled = c.charging, size = 20.dp, tint = if (level < 20 && !c.charging) g.danger else g.fg)
        Text("${level.roundToInt()}%", style = MaterialTheme.typography.labelLarge, color = g.fg)
    }
}

/**
 * The vacuum's map image from its Homey app, refetched while the robot is out so its
 * path and position stay current.
 */
@Composable
private fun rememberVacuumMap(c: VacuumControls, actions: DashboardActions, maxSide: Int): ImageBitmap? {
    val obj = c.map?.imageObj ?: return null
    val url = obj.url ?: return null
    val active = c.state in setOf(VacuumState.Cleaning, VacuumState.Paused, VacuumState.Returning)
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(active) {
        while (active) {
            delay(15_000)
            tick++
        }
    }
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    val key = "$url|${obj.lastUpdated}|$tick|$maxSide"
    LaunchedEffect(key) { actions.loadImage(url, key, maxSide)?.let { bitmap = it } }
    return bitmap
}

/** Everything the vacuum's Homey app offers: map, rooms, modes, actions, parts and readings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VacuumSheet(device: Device, actions: DashboardActions, onDismiss: () -> Unit) {
    val g = GlassTheme.colors
    val context = LocalContext.current
    val cards = actions.cards(device.id)
    val controls = remember(device, cards) { Vacuums.controls(device, cards) }
    val usage = VacuumUsage.get(context)
    val counted by usage.usage.collectAsState()
    val screen = screenSizeDp()
    val width = (screen.width - 64.dp).coerceIn(480.dp, 1280.dp)
    // The panel's padding, header and gaps take about 140dp of its height.
    val height = (screen.height * DialogHeightShare - 140.dp).coerceAtLeast(300.dp)
    var choice by remember { mutableStateOf<VacuumChoiceCard?>(null) }

    GlassDialog(onDismiss = onDismiss, width = width) {
        DialogHeader(
            title = device.name,
            subtitle = statusLine(controls),
            onClose = onDismiss,
            leading = { Bubble(Sym.vacuum, on = controls.state == VacuumState.Cleaning) },
        )
        Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1.1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MapPanel(controls, actions, Modifier.weight(1f).fillMaxWidth())
                controls.mapPicker?.takeIf { (it.values?.size ?: 0) > 1 }?.let { picker ->
                    PickerRow(picker) { actions.set(device.id, picker.id, JsonPrimitive(it)) }
                }
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatusRow(controls)
                if (device.available) MainControls(device, controls, actions)
                RoomsSection(device, controls, cards, actions)
                if (controls.pickers.isNotEmpty() || controls.toggles.isNotEmpty()) {
                    SectionLabel("Settings")
                    controls.pickers.forEach { picker ->
                        Text(picker.title ?: Vacuums.readable(picker.id), style = MaterialTheme.typography.labelLarge, color = g.muted)
                        PickerRow(picker) { actions.set(device.id, picker.id, JsonPrimitive(it)) }
                    }
                    controls.toggles.forEach { toggle ->
                        LabelledChoice(toggle.title ?: Vacuums.readable(toggle.id)) {
                            Segmented(
                                listOf("on" to "On", "off" to "Off"),
                                if (toggle.booleanValue == true) "on" else "off",
                                { actions.set(device.id, toggle.id, JsonPrimitive(it == "on")) },
                                Modifier.width(180.dp),
                            )
                        }
                    }
                }
                if (controls.buttons.isNotEmpty() || controls.choiceCards.isNotEmpty()) {
                    SectionLabel("Actions")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        controls.buttons.forEach { button ->
                            Pill(buttonIcon(button.title), button.title) { actions.runVacuum(device, button.command) }
                        }
                        controls.choiceCards.filter { it.arg.type != "checkbox" }.forEach { card ->
                            Pill(Sym.playlist, card.title) { choice = card }
                        }
                    }
                    controls.choiceCards.filter { it.arg.type == "checkbox" }.forEach { card ->
                        LabelledChoice(card.title) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill(null, "On") { actions.run(card.card, kotlinx.serialization.json.JsonObject(mapOf(card.arg.name to JsonPrimitive(true)))) }
                                Pill(null, "Off") { actions.run(card.card, kotlinx.serialization.json.JsonObject(mapOf(card.arg.name to JsonPrimitive(false)))) }
                            }
                        }
                    }
                }
                if (controls.parts.isNotEmpty()) {
                    SectionLabel("Parts")
                    controls.parts.forEach { part -> PartRow(device, part, usage.wear(device.id, part), actions, usage, context) }
                    counted[device.id]?.takeIf { controls.parts.any { it.leftFraction == null } }?.let { hours ->
                        Text(
                            "Estimated from ${formatHours(hours.hours)} of cleaning counted since ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(hours.since))}. Tap Replaced after changing a part.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = g.muted,
                        )
                    }
                }
                if (controls.readings.isNotEmpty()) {
                    SectionLabel("Details")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        controls.readings.forEach { reading ->
                            Column(Modifier.glass(RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(reading.title ?: Vacuums.readable(reading.id), style = MaterialTheme.typography.labelMedium, color = g.muted)
                                Text(readingText(reading), style = MaterialTheme.typography.titleSmall, color = g.fg)
                            }
                        }
                    }
                }
            }
        }
    }
    choice?.let { card -> ChoiceDialog(card, actions, onDismiss = { choice = null }) }
}

@Composable
private fun MapPanel(c: VacuumControls, actions: DashboardActions, modifier: Modifier) {
    val g = GlassTheme.colors
    val map = rememberVacuumMap(c, actions, maxSide = 1600)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(modifier.clip(TileShape).glass(TileShape), contentAlignment = Alignment.Center) {
        if (map != null) {
            Image(
                map,
                contentDescription = "Map",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) }
                    .transformable(transform)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(32.dp)) {
                SymIcon(Sym.map, size = 56.dp, tint = g.muted)
                Text(
                    if (c.map == null) "This vacuum's Homey app doesn't share a map." else "Loading the map…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = g.muted,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusRow(c: VacuumControls) {
    val g = GlassTheme.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stateText(c.state), style = MaterialTheme.typography.headlineSmall, color = g.fg)
        c.battery?.let {
            Pill(Sym.batteryFull, "${it.roundToInt()}%" + if (c.charging) " · charging" else "", tint = if (it < 20 && !c.charging) g.danger else null)
        }
        c.alarms.forEach { alarm -> Pill(Sym.error, alarm.title ?: Vacuums.readable(alarm.id), tint = g.danger) }
    }
}

@Composable
private fun MainControls(device: Device, c: VacuumControls, actions: DashboardActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        mainButton(c)?.let { (icon, command, label) -> BigButton(icon, label, primary = true) { actions.runVacuum(device, command) } }
        if (c.state in setOf(VacuumState.Cleaning, VacuumState.Paused, VacuumState.Returning) && c.pause != null) {
            c.stop?.let { BigButton(Sym.stop, "Stop") { actions.runVacuum(device, it) } }
        }
        c.dock?.takeIf { c.state !in setOf(VacuumState.Docked, VacuumState.Charging) }?.let {
            BigButton(Sym.home, "Send home") { actions.runVacuum(device, it) }
        }
    }
}

@Composable
private fun BigButton(icon: Ic, label: String, primary: Boolean = false, onClick: () -> Unit) {
    val g = GlassTheme.colors
    Row(
        Modifier
            .pressable(onClick)
            .then(if (primary) Modifier.background(onBrush(), PillShape) else Modifier.glass(PillShape))
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SymIcon(icon, filled = true, size = 24.dp, tint = if (primary) g.onInk else g.fg)
        Text(label, style = MaterialTheme.typography.titleSmall, color = if (primary) g.onInk else g.fg)
    }
}

/** Room chips to pick from, then one button that sends the robot to them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoomsSection(device: Device, c: VacuumControls, cards: List<app.homeydash.homey.FlowCardAction>, actions: DashboardActions) {
    val g = GlassTheme.colors
    val cleaning = c.roomCleaning ?: return
    val listArg = remember(cleaning, cards) { Vacuums.roomListArg(cleaning, cards) }
    val needList = cleaning is RoomCleaning.Cards || c.pickerRooms.isEmpty()
    val rooms by produceState(c.pickerRooms, listArg?.first?.id, needList) {
        if (needList && listArg != null) {
            value = Vacuums.roomsFrom(actions.autocomplete(listArg.first, listArg.second.name, "")).ifEmpty { c.pickerRooms }
        }
    }
    if (rooms.isEmpty()) return
    var selected by remember(device.id) { mutableStateOf(listOf<VacuumRoom>()) }
    val max = cleaning.maxRooms
    SectionLabel("Rooms")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rooms.forEach { room ->
            val on = selected.any { it.id == room.id }
            Pill(null, room.name, selected = on) {
                selected = when {
                    on -> selected.filter { it.id != room.id }
                    max == 1 -> listOf(room)
                    max != null && selected.size >= max -> selected
                    else -> selected + room
                }
            }
        }
    }
    val command = remember(selected, cleaning) { selected.takeIf { it.isNotEmpty() }?.let { Vacuums.roomArgs(cleaning, it) } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val label = when (selected.size) {
            0 -> "Pick rooms to clean"
            1 -> "Clean ${selected.single().name}"
            else -> "Clean ${selected.size} rooms"
        }
        Box(Modifier.graphicsLayer { alpha = if (command != null && device.available) 1f else 0.45f }) {
            BigButton(Sym.broom, label, primary = command != null) {
                if (command != null && device.available) {
                    actions.runVacuum(device, command)
                    selected = emptyList()
                }
            }
        }
        if (max != null && max > 1) Text("Up to $max at a time", style = MaterialTheme.typography.bodyMedium, color = g.muted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickerRow(picker: Capability, onPick: (String) -> Unit) {
    val options = picker.values.orEmpty()
    if (options.size in 2..4) {
        Segmented(options.map { it.id to (it.title ?: it.id) }, picker.stringValue ?: "", onPick, Modifier.fillMaxWidth())
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { Pill(null, it.title ?: it.id, selected = picker.stringValue == it.id) { onPick(it.id) } }
        }
    }
}

@Composable
private fun LabelledChoice(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = GlassTheme.colors.fg, modifier = Modifier.weight(1f))
        content()
    }
}

@Composable
private fun Pill(icon: Ic?, text: String, selected: Boolean = false, tint: Color? = null, onClick: (() -> Unit)? = null) {
    val g = GlassTheme.colors
    Row(
        Modifier
            .pressable(onClick)
            .then(if (selected) Modifier.background(onBrush(), PillShape) else Modifier.glass(PillShape))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let { SymIcon(it, filled = selected || tint != null, size = 20.dp, tint = tint ?: if (selected) g.onInk else g.fg) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) g.onInk else tint ?: g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun buttonIcon(title: String): Ic {
    val t = title.lowercase()
    return when {
        "locate" in t || "find" in t -> Sym.locate
        "empty" in t || "dust" in t || "bin" in t -> Sym.trash
        "wash" in t || "mop" in t || "water" in t -> Sym.waterDrop
        "dry" in t -> Sym.air
        "refresh" in t || "repeat" in t -> Sym.refresh
        "spot" in t || "clean" in t -> Sym.broom
        else -> Sym.smartButton
    }
}

/** One wearing part: a bar of what's left, "Replaced" and "Buy". */
@Composable
private fun PartRow(device: Device, part: VacuumPart, wear: PartWear?, actions: DashboardActions, usage: VacuumUsage, context: Context) {
    val g = GlassTheme.colors
    val left = wear?.leftFraction
    val low = left != null && left <= WORN
    var confirm by remember(part.key) { mutableStateOf(false) }
    LaunchedEffect(confirm) {
        if (confirm) {
            delay(4_000)
            confirm = false
        }
    }
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SymIcon(if (low) Sym.error else Sym.filter, filled = low, size = 22.dp, tint = if (low) g.danger else g.fg)
            Column(Modifier.weight(1f)) {
                Text(part.name, style = MaterialTheme.typography.titleSmall, color = g.fg)
                Text(
                    when (wear) {
                        null -> "Counting starts with the next clean"
                        else -> listOfNotNull(
                            "${(wear.leftFraction * 100).roundToInt()}% left",
                            wear.leftHours?.let { "about ${formatHours(it)}" },
                            if (wear.estimated) "estimated" else null,
                        ).joinToString(" · ")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (low) g.danger else g.muted,
                )
            }
            Pill(if (confirm) Sym.check else null, if (confirm) "Tap to confirm" else "Replaced", selected = confirm) {
                if (confirm) {
                    usage.replaced(device.id, part.key)
                    part.reset?.let { actions.runVacuum(device, it) }
                    confirm = false
                } else {
                    confirm = true
                }
            }
            Pill(Sym.cart, "Buy") { openShop(context, device, part, actions) }
        }
        if (left != null) {
            Box(Modifier.fillMaxWidth().height(8.dp).glass(PillShape)) {
                Box(
                    Modifier
                        .fillMaxWidth(left.toFloat().coerceIn(0.02f, 1f))
                        .fillMaxHeight()
                        .background(if (low) g.danger else g.ok, PillShape),
                )
            }
        }
    }
}

/** Opens a shop search for the part in the browser, e.g. "roborock filter". */
private fun openShop(context: Context, device: Device, part: VacuumPart, actions: DashboardActions) {
    val app = device.driverUri?.removePrefix("homey:app:")?.substringBefore(':').orEmpty()
    val brand = app.split('.').lastOrNull { it.length > 2 && it !in setOf("homey", "app", "vacuum", "cloud", "local") }.orEmpty()
    val query = listOf(brand, part.name, "replacement").filter { it.isNotBlank() }.joinToString(" ")
    val url = "https://www.google.com/search?tbm=shop&q=" + URLEncoder.encode(query, "UTF-8")
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { actions.reportError("Couldn't open the browser: ${it.message}") }
}

private fun formatHours(hours: Double): String = when {
    hours < 1 -> "${(hours * 60).roundToInt()} min"
    hours < 10 -> String.format(java.util.Locale.getDefault(), "%.1f h", hours)
    else -> "${hours.roundToInt()} h"
}

private fun readingText(c: Capability): String = when {
    c.type == "boolean" -> if (c.booleanValue == true) "Yes" else "No"
    else -> formatValue(c)
}

/** The list a Flow card offers (a routine, a map), and runs the card with the one picked. */
@Composable
private fun ChoiceDialog(card: VacuumChoiceCard, actions: DashboardActions, onDismiss: () -> Unit) {
    val g = GlassTheme.colors
    val items by produceState<List<AutocompleteItem>?>(if (card.arg.type == "dropdown") FlowCards.dropdownItems(card.arg) else null, card.card.id) {
        if (card.arg.type == "autocomplete") value = actions.autocomplete(card.card, card.arg.name, "")
    }
    GlassDialog(onDismiss = onDismiss, width = 520.dp) {
        DialogHeader(title = card.title, subtitle = null, onClose = onDismiss)
        val list = items
        when {
            list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = g.fg) }
            list.isEmpty() -> Text("Nothing to choose from.", style = MaterialTheme.typography.bodyLarge, color = g.muted)
            else -> LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list) { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .pressable({
                                actions.run(card.card, kotlinx.serialization.json.JsonObject(mapOf(card.arg.name to FlowCards.argValue(card.arg, item))))
                                onDismiss()
                            })
                            .glass(RoundedCornerShape(16.dp))
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                    ) {
                        Column {
                            Text(item.name, style = MaterialTheme.typography.titleSmall, color = g.fg)
                            item.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = g.muted) }
                        }
                    }
                }
            }
        }
    }
}
