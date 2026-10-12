package app.homeydash.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.homeydash.homey.CardKind
import app.homeydash.homey.Capability
import app.homeydash.homey.DashboardLayout
import app.homeydash.homey.Device
import app.homeydash.ui.theme.DashboardTheme
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * The surface every device card sits on. It reads only [DashboardTheme.style],
 * so the glass design can restyle every card from here.
 */
@Composable
fun DashboardCard(
    active: Boolean,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = DashboardTheme.style
    val color = if (active) style.cardActiveColor else style.cardColor
    val border = BorderStroke(style.cardBorderWidth, style.cardBorder)
    val inner: @Composable () -> Unit = {
        Column(
            Modifier
                .padding(20.dp)
                .heightIn(min = 112.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
    val cardModifier = modifier
        .fillMaxWidth()
        .alpha(if (enabled) 1f else 0.5f)
    if (onClick != null && enabled) {
        Surface(onClick = onClick, shape = style.cardShape, color = color, border = border, modifier = cardModifier) { inner() }
    } else {
        Surface(shape = style.cardShape, color = color, border = border, modifier = cardModifier) { inner() }
    }
}

@Composable
fun DeviceCard(device: Device, setCapability: SetCapability) {
    val onoff = device.capability("onoff")
    val isOn = onoff?.booleanValue == true
    val toggle = onoff?.takeIf { it.setable }?.let { { setCapability(device.id, "onoff", JsonPrimitive(!isOn)) } }

    when (DashboardLayout.cardKind(device)) {
        CardKind.Light, CardKind.Switch -> DashboardCard(active = isOn, enabled = device.available, onClick = toggle) {
            CardTitle(device, if (isOn) "On" else "Off")
            device.capability("dim")?.takeIf { it.setable }?.let { dim ->
                LevelSlider(dim) { setCapability(device.id, "dim", JsonPrimitive(it)) }
            }
            device.capability("measure_power")?.let { ValueLine(it) }
        }

        CardKind.Thermostat -> DashboardCard(active = false, enabled = device.available, onClick = null) {
            val target = device.capability("target_temperature")
            CardTitle(device, device.capability("measure_temperature")?.let { "Now ${formatValue(it)}" })
            target?.let { cap ->
                Stepper(
                    label = formatValue(cap),
                    onMinus = { stepTo(cap, -1)?.let { setCapability(device.id, cap.id, JsonPrimitive(it)) } },
                    onPlus = { stepTo(cap, +1)?.let { setCapability(device.id, cap.id, JsonPrimitive(it)) } },
                )
            }
        }

        CardKind.Blinds -> DashboardCard(active = false, enabled = device.available, onClick = null) {
            val position = device.capability("windowcoverings_set")
            CardTitle(device, position?.let { formatValue(it) })
            if (position?.setable == true) {
                LevelSlider(position) { setCapability(device.id, position.id, JsonPrimitive(it)) }
            } else if (device.capability("windowcoverings_state")?.setable == true) {
                ButtonRow(
                    "Up" to { setCapability(device.id, "windowcoverings_state", JsonPrimitive("up")) },
                    "Stop" to { setCapability(device.id, "windowcoverings_state", JsonPrimitive("idle")) },
                    "Down" to { setCapability(device.id, "windowcoverings_state", JsonPrimitive("down")) },
                )
            }
        }

        CardKind.Lock -> {
            val locked = device.capability("locked")?.booleanValue == true
            DashboardCard(
                active = !locked,
                enabled = device.available,
                onClick = { setCapability(device.id, "locked", JsonPrimitive(!locked)) },
            ) {
                CardTitle(device, if (locked) "Locked" else "Unlocked")
            }
        }

        CardKind.Media -> {
            val playing = device.capability("speaker_playing")?.booleanValue == true
            DashboardCard(active = playing || isOn, enabled = device.available, onClick = null) {
                val track = device.capability("speaker_track")?.stringValue
                val artist = device.capability("speaker_artist")?.stringValue
                CardTitle(device, listOfNotNull(track, artist).joinToString(" · ").ifEmpty { if (playing) "Playing" else "Paused" })
                val controls = buildList<Pair<String, () -> Unit>> {
                    if (device.capability("speaker_prev")?.setable == true) {
                        add("Prev" to { setCapability(device.id, "speaker_prev", JsonPrimitive(true)) })
                    }
                    if (device.capability("speaker_playing")?.setable == true) {
                        add((if (playing) "Pause" else "Play") to { setCapability(device.id, "speaker_playing", JsonPrimitive(!playing)) })
                    }
                    if (device.capability("speaker_next")?.setable == true) {
                        add("Next" to { setCapability(device.id, "speaker_next", JsonPrimitive(true)) })
                    }
                }
                if (controls.isNotEmpty()) ButtonRow(*controls.toTypedArray())
                device.capability("volume_set")?.takeIf { it.setable }?.let { volume ->
                    LevelSlider(volume) { setCapability(device.id, volume.id, JsonPrimitive(it)) }
                }
            }
        }

        CardKind.Button -> DashboardCard(
            active = false,
            enabled = device.available,
            onClick = { setCapability(device.id, "button", JsonPrimitive(true)) },
        ) {
            CardTitle(device, "Tap to press")
        }

        CardKind.Sensor, CardKind.Other -> DashboardCard(active = false, enabled = device.available, onClick = toggle) {
            CardTitle(device, null)
            device.capabilities
                .mapNotNull { device.capability(it) }
                .filter { it.getable && it.value !is JsonNull }
                .take(3)
                .forEach { ValueLine(it) }
        }
    }
}

@Composable
private fun CardTitle(device: Device, status: String?) {
    val style = DashboardTheme.style
    Text(
        device.name,
        style = MaterialTheme.typography.titleMedium,
        color = style.onCard,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    val line = if (!device.available) "Unavailable" else status
    line?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = style.onCardMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ValueLine(capability: Capability) {
    val style = DashboardTheme.style
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(capability.title ?: capability.id, color = style.onCardMuted, style = MaterialTheme.typography.bodyMedium)
        Text(formatValue(capability), color = style.onCard, style = MaterialTheme.typography.bodyLarge)
    }
}

/** A slider for a number capability; sends to Homey only when the finger lifts. */
@Composable
private fun LevelSlider(capability: Capability, onSet: (Double) -> Unit) {
    val min = (capability.min ?: 0.0).toFloat()
    val max = (capability.max ?: 1.0).toFloat()
    val remote = (capability.numberValue ?: min.toDouble()).toFloat().coerceIn(min, max)
    var local by remember(remote) { mutableFloatStateOf(remote) }
    Slider(
        value = local,
        onValueChange = { local = it },
        onValueChangeFinished = { onSet(local.toDouble()) },
        valueRange = min..max,
    )
}

@Composable
private fun Stepper(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(onClick = onMinus) { Text("−") }
        Text(label, style = MaterialTheme.typography.titleLarge, color = DashboardTheme.style.onCard)
        FilledTonalButton(onClick = onPlus) { Text("+") }
    }
}

@Composable
private fun ButtonRow(vararg buttons: Pair<String, () -> Unit>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        buttons.forEach { (label, onClick) ->
            FilledTonalButton(onClick = onClick, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
        }
    }
}
