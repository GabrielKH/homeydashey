package app.homeydash.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.homeydash.homey.CardKind
import app.homeydash.homey.Capability
import app.homeydash.homey.DashboardLayout
import app.homeydash.homey.Device
import app.homeydash.ui.components.Bubble
import app.homeydash.ui.components.RoundButton
import app.homeydash.ui.components.Ic
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.TileShape
import app.homeydash.ui.components.deviceIcon
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

val TileHeight = 148.dp

/** How many grid columns a device's tile takes. */
fun tileSpan(device: Device): Int = when (DashboardLayout.cardKind(device)) {
    CardKind.Thermostat, CardKind.Media -> 2
    else -> 1
}

@Composable
fun DeviceTile(device: Device, actions: DashboardActions, modifier: Modifier = Modifier) {
    val tileModifier = modifier.alpha(if (device.available) 1f else 0.55f)
    // Press and hold on the card itself (not its buttons) opens its menu: hide, move, group.
    val holdForMenu = Modifier.pressable(null, onLongClick = { actions.hold(Sheet.CardMenu(device.id)) })
    when (DashboardLayout.cardKind(device)) {
        CardKind.Light, CardKind.Switch -> LightTile(device, actions, tileModifier)
        CardKind.Thermostat -> ThermostatTile(device, actions, tileModifier.then(holdForMenu))
        CardKind.Blinds -> BlindsTile(device, actions, tileModifier)
        CardKind.Lock -> LockTile(device, actions, tileModifier)
        CardKind.Media -> MediaPlayerCard(device, actions, tileModifier.then(holdForMenu), large = false)
        CardKind.Button -> ButtonTile(device, actions, tileModifier)
        CardKind.Sensor, CardKind.Other -> SensorTile(device, actions, tileModifier)
    }
}

/**
 * A tile whose background fills from the left to show a level. Dragging sideways
 * changes the level (sent when the finger lifts); a tap runs [onTap].
 */
@Composable
private fun LevelTile(
    level: Float,
    fillColor: Color,
    onTap: (() -> Unit)?,
    onLevel: ((Float) -> Unit)?,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    content: @Composable ColumnScope.(shownLevel: Float, dragging: Boolean) -> Unit,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val currentLevel by rememberUpdatedState(level)
    val currentOnLevel by rememberUpdatedState(onLevel)
    val shown = drag ?: level
    val animated by animateFloatAsState(shown, label = "level")
    val gesture = if (onLevel != null) {
        Modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { drag = currentLevel.coerceAtLeast(0.01f) },
                onDragEnd = { drag?.let { currentOnLevel?.invoke(it) }; drag = null },
                onDragCancel = { drag = null },
                onHorizontalDrag = { change, dx ->
                    change.consume()
                    drag = ((drag ?: currentLevel) + dx / size.width).coerceIn(0.01f, 1f)
                },
            )
        }
    } else {
        Modifier
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(TileHeight)
            .pressable(onTap, onLongPress)
            .glass(TileShape)
            .then(gesture),
    ) {
        val edge = if (animated in 0.005f..0.995f) 2.dp else 0.dp
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(if (drag != null) shown else animated)
                    .background(Brush.horizontalGradient(listOf(fillColor.copy(alpha = fillColor.alpha * 0.55f), fillColor))),
            )
            Box(Modifier.fillMaxHeight().width(edge).background(fillColor.copy(alpha = 0.9f)))
        }
        Column(Modifier.fillMaxSize().padding(16.dp)) { content(shown, drag != null) }
    }
}

@Composable
private fun LightTile(device: Device, actions: DashboardActions, modifier: Modifier) {
    val g = GlassTheme.colors
    val onoff = device.capability("onoff")
    val dim = device.capability("dim")?.takeIf { it.setable }
    val isOn = onoff?.booleanValue == true
    val dimLevel = dim?.let(::fraction)
    val level = when {
        !isOn -> 0f
        dimLevel != null -> dimLevel
        else -> 1f
    }
    val toggle = onoff?.takeIf { it.setable }?.let { { actions.set(device.id, "onoff", JsonPrimitive(!isOn)) } }
    val setLevel = dim?.let { cap ->
        { f: Float ->
            actions.set(device.id, cap.id, JsonPrimitive(valueAt(cap, f)))
            if (!isOn && onoff?.setable == true) actions.set(device.id, "onoff", JsonPrimitive(true))
        }
    }
    val colour = lightColor(device)
    val openSheet = { actions.open(Sheet.Light(device.id)) }.takeIf { hasLightControls(device) && device.available }
    LevelTile(
        level = level,
        fillColor = colour?.copy(alpha = if (g.isDark) 0.34f else 0.5f) ?: g.onSoft.copy(alpha = if (g.isDark) 0.30f else 0.42f),
        onTap = toggle?.takeIf { device.available },
        onLevel = setLevel?.takeIf { device.available },
        modifier = modifier,
        onLongPress = { actions.hold(Sheet.CardMenu(device.id)) },
    ) { shown, dragging ->
        val lit = isOn || dragging
        Row(verticalAlignment = Alignment.Top) {
            Bubble(deviceIcon(device), on = lit, onColor = colour)
            Spacer(Modifier.weight(1f))
            device.capability("measure_power")?.numberValue?.takeIf { lit && it > 0.5 && openSheet == null }?.let {
                Text(formatValue(device.capability("measure_power")!!), style = MaterialTheme.typography.bodyMedium, color = g.muted)
            }
            openSheet?.let { RoundButton(Sym.sliders, it, size = 38.dp, filledIcon = false, contentDescription = "Adjust ${device.name}") }
        }
        Spacer(Modifier.weight(1f))
        TileTitle(device.name)
        TileStatus(
            when {
                !device.available -> "Unavailable"
                dragging || (isOn && dimLevel != null) -> "On · ${percent(shown)}"
                isOn -> "On"
                else -> "Off"
            },
            strong = lit,
        )
    }
}

@Composable
private fun BlindsTile(device: Device, actions: DashboardActions, modifier: Modifier) {
    val g = GlassTheme.colors
    val position = device.capability("windowcoverings_set")
    val state = device.capability("windowcoverings_state")?.takeIf { it.setable }
    val level = position?.let(::fraction) ?: 0f
    val setLevel = position?.takeIf { it.setable }?.let { cap -> { f: Float -> actions.set(device.id, cap.id, JsonPrimitive(valueAt(cap, f))) } }
    val tap = position?.takeIf { it.setable }?.let { cap ->
        { actions.set(device.id, cap.id, JsonPrimitive(valueAt(cap, if (level > 0.5f) 0f else 1f))) }
    }
    LevelTile(
        level = level,
        fillColor = g.cool.copy(alpha = 0.28f),
        onTap = tap?.takeIf { device.available },
        onLevel = setLevel?.takeIf { device.available },
        modifier = modifier,
        onLongPress = { actions.hold(Sheet.CardMenu(device.id)) },
    ) { shown, _ ->
        Row(verticalAlignment = Alignment.Top) {
            Bubble(deviceIcon(device), on = shown > 0.02f, onColor = g.cool)
            Spacer(Modifier.weight(1f))
            if (state != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RoundButton(Sym.keyboardArrowUp, { actions.set(device.id, state.id, JsonPrimitive("up")) }, size = 36.dp, filledIcon = false)
                    RoundButton(Sym.pause, { actions.set(device.id, state.id, JsonPrimitive("idle")) }, size = 36.dp)
                    RoundButton(Sym.keyboardArrowDown, { actions.set(device.id, state.id, JsonPrimitive("down")) }, size = 36.dp, filledIcon = false)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        TileTitle(device.name)
        TileStatus(
            when {
                !device.available -> "Unavailable"
                position != null && shown <= 0.01f -> "Closed"
                position != null && shown >= 0.99f -> "Open"
                position != null -> "${percent(shown)} open"
                else -> device.capability("windowcoverings_state")?.let(::formatValue) ?: ""
            },
            strong = shown > 0.02f,
        )
    }
}

@Composable
private fun ThermostatTile(device: Device, actions: DashboardActions, modifier: Modifier) {
    val g = GlassTheme.colors
    val target = device.capability("target_temperature")
    val now = device.capability("measure_temperature")?.numberValue
    // Taps are collected and sent once they pause, so five quick taps are one request.
    var pending by remember(target?.numberValue) { mutableStateOf<Double?>(null) }
    LaunchedEffect(pending) {
        val value = pending ?: return@LaunchedEffect
        delay(700)
        if (target != null) actions.set(device.id, target.id, JsonPrimitive(value))
    }
    val shownTarget = pending ?: target?.numberValue
    val heating = now != null && shownTarget != null && shownTarget > now + 0.2
    Row(
        modifier
            .fillMaxWidth()
            .height(TileHeight)
            .glass(TileShape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Bubble(deviceIcon(device), on = heating, onColor = g.onDeep, tint = g.cool)
            Spacer(Modifier.weight(1f))
            TileTitle(device.name)
            TileStatus(
                listOfNotNull(
                    now?.let { "Now ${shortTemperature(it)}" },
                    device.capability("thermostat_mode")?.let(::formatValue),
                    if (heating) "heating" else null,
                ).joinToString(" · ").ifEmpty { "–" },
                strong = false,
            )
        }
        if (target != null && target.setable && device.available) {
            RoundButton(Sym.remove, { stepFrom(target, shownTarget, -1)?.let { pending = it } }, filledIcon = false)
        }
        Text(
            shownTarget?.let { shortTemperature(it) } ?: "–",
            style = MaterialTheme.typography.headlineLarge,
            color = g.fg,
        )
        if (target != null && target.setable && device.available) {
            RoundButton(Sym.add, { stepFrom(target, shownTarget, +1)?.let { pending = it } }, filledIcon = false)
        }
    }
}

private fun stepFrom(capability: Capability, current: Double?, direction: Int): Double? =
    stepTo(capability.copy(value = current?.let { JsonPrimitive(it) } ?: capability.value, step = capability.step ?: 0.5), direction)

@Composable
private fun LockTile(device: Device, actions: DashboardActions, modifier: Modifier) {
    val g = GlassTheme.colors
    val locked = device.capability("locked")?.booleanValue == true
    val setable = device.capability("locked")?.setable == true && device.available
    // Locking is a tap; unlocking is in the menu a long press opens.
    TileBox(
        modifier,
        onClick = if (setable && !locked) ({ actions.set(device.id, "locked", JsonPrimitive(true)) }) else null,
        onLongClick = { actions.hold(Sheet.CardMenu(device.id)) },
    ) {
        Bubble(if (locked) Sym.lock else Sym.lockOpen, on = true, onColor = if (locked) g.ok else g.danger)
        Spacer(Modifier.weight(1f))
        TileTitle(device.name)
        TileStatus(
            when {
                !device.available -> "Unavailable"
                locked && setable -> "Locked · hold to unlock"
                locked -> "Locked"
                setable -> "Unlocked · tap to lock"
                else -> "Unlocked"
            },
            strong = !locked,
        )
    }
}

@Composable
private fun ButtonTile(device: Device, actions: DashboardActions, modifier: Modifier) {
    TileBox(modifier, onClick = { actions.set(device.id, "button", JsonPrimitive(true)) }.takeIf { device.available }, onLongClick = { actions.hold(Sheet.CardMenu(device.id)) }) {
        Bubble(deviceIcon(device), on = false)
        Spacer(Modifier.weight(1f))
        TileTitle(device.name)
        TileStatus(if (device.available) "Tap to press" else "Unavailable", strong = false)
    }
}

/** Sensors and anything else: the most useful reading large, alarms in red. */
@Composable
private fun SensorTile(device: Device, actions: DashboardActions, modifier: Modifier) {
    val g = GlassTheme.colors
    val readings = device.capabilities
        .mapNotNull { device.capability(it) }
        .filter { it.getable && it.value !is JsonNull && it.id != "alarm_battery" && it.id != "measure_battery" }
    val alarm = readings.firstOrNull { it.id.startsWith("alarm_") && it.booleanValue == true }
    val primary = PRIMARY_READINGS.firstNotNullOfOrNull { id -> readings.firstOrNull { it.id == id } }
        ?: readings.firstOrNull { it.id.startsWith("measure_") }
    val contact = readings.firstOrNull { it.id == "alarm_contact" }
    val motion = readings.firstOrNull { it.id == "alarm_motion" }
    val onoff = device.capability("onoff")?.takeIf { it.setable }
    val isOn = onoff?.booleanValue == true
    TileBox(
        modifier,
        onClick = onoff?.let { cap -> { actions.set(device.id, cap.id, JsonPrimitive(!isOn)) } }?.takeIf { device.available },
        onLongClick = { actions.hold(Sheet.CardMenu(device.id)) },
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Bubble(
                deviceIcon(device),
                on = alarm != null || isOn,
                onColor = if (alarm != null) g.danger else null,
                tint = if (primary?.id == "measure_temperature") g.cool else null,
            )
            Spacer(Modifier.weight(1f))
            if (primary != null) {
                Text(formatValue(primary), style = MaterialTheme.typography.headlineMedium, color = g.fg, maxLines = 1)
            }
        }
        Spacer(Modifier.weight(1f))
        TileTitle(device.name)
        val status = when {
            !device.available -> "Unavailable"
            contact != null -> if (contact.booleanValue == true) "Open" else "Closed"
            motion != null -> if (motion.booleanValue == true) "Motion" else "No motion"
            alarm != null -> alarm.title ?: "Alert"
            else -> readings.filter { it != primary && it.numberValue != null }.take(2).joinToString(" · ") { formatValue(it) }
                .ifEmpty { if (onoff != null) (if (isOn) "On" else "Off") else "" }
        }
        TileStatus(status, strong = alarm != null, color = if (alarm != null) g.danger else null)
    }
}

private val PRIMARY_READINGS = listOf(
    "measure_temperature", "measure_co2", "measure_humidity", "measure_power", "measure_luminance", "measure_pm25", "meter_power",
)

/** Plain tile body used by tiles without a level. */
@Composable
private fun TileBox(
    modifier: Modifier,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .height(TileHeight)
            .pressable(onClick, onLongClick)
            .glass(TileShape)
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun TileTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = GlassTheme.colors.fg,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun TileStatus(text: String, strong: Boolean, color: Color? = null) {
    if (text.isEmpty()) return
    val g = GlassTheme.colors
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color ?: if (strong) g.fg else g.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp),
    )
}
