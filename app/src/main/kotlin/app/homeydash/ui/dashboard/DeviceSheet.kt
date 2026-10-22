package app.homeydash.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.homeydash.homey.Capability
import app.homeydash.homey.Device
import app.homeydash.homey.Insights
import app.homeydash.ui.components.Bubble
import app.homeydash.ui.components.DialogHeader
import app.homeydash.ui.components.GlassDialog
import app.homeydash.ui.components.Ic
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.deviceIcon
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** At most this many readings can be picked to show on a card. */
const val CARD_READINGS_MAX = 2

/**
 * A sensor's, smoke detector's or other device's own sheet (Gabriel, 2026-10-22): every
 * reading Homey has for it, alarms first and in red, a 24-hour line from Homey Insights for
 * the readings that change, and a tick to show up to two readings on the card itself.
 */
@Composable
fun DeviceSheet(
    device: Device,
    room: String?,
    actions: DashboardActions,
    onCard: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val g = GlassTheme.colors
    val readings = remember(device) { sheetReadings(device) }
    var picked by remember(device.id) { mutableStateOf(actions.cardReadings[device.id].orEmpty()) }
    val alarm = readings.any { it.id.startsWith("alarm_") && it.booleanValue == true }
    val onoff = device.capability("onoff")?.takeIf { it.setable }
    GlassDialog(onDismiss = onDismiss, width = 560.dp) {
        DialogHeader(
            title = device.name,
            subtitle = listOfNotNull(room, "Unavailable".takeIf { !device.available }).joinToString(" · ").ifEmpty { null },
            onClose = onDismiss,
            leading = { Bubble(deviceIcon(device), on = alarm || onoff?.booleanValue == true, onColor = if (alarm) g.danger else null) },
        )
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onoff != null) {
                val on = onoff.booleanValue == true
                ReadingRow(Sym.power, onoff.title ?: "Power", if (on) "On" else "Off", alarm = false) {
                    Toggle(on, onClick = { actions.set(device.id, onoff.id, JsonPrimitive(!on)) }.takeIf { device.available })
                }
            }
            readings.forEach { cap -> key(cap.id) {
                val ringing = cap.id.startsWith("alarm_") && cap.booleanValue == true
                val history by produceState(emptyList<Double>(), device.id, cap.id) {
                    if (Insights.hasLine(cap)) value = actions.history(device.id, cap.id)
                }
                ReadingRow(readingIcon(cap), cap.title ?: cap.id, formatValue(cap), alarm = ringing, history = history) {
                    val on = cap.id in picked
                    Box(
                        Modifier
                            .size(30.dp)
                            .pressable({
                                picked = if (on) picked - cap.id else (picked + cap.id).takeLast(CARD_READINGS_MAX)
                                onCard(picked)
                            })
                            .then(
                                if (on) Modifier.background(g.on, RoundedCornerShape(9.dp))
                                else Modifier.background(g.fg.copy(alpha = 0.08f), RoundedCornerShape(9.dp)),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (on) SymIcon(Sym.check, filled = true, size = 16.dp, tint = g.onInk)
                    }
                }
            } }
            if (readings.isEmpty()) Text("Homey has no readings for this device.", style = MaterialTheme.typography.bodyLarge, color = g.muted)
        }
        Text(
            "Tick up to $CARD_READINGS_MAX readings to show them on the card. The lines show the last 24 hours from Homey.",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
    }
}

@Composable
private fun ReadingRow(icon: Ic, title: String, value: String, alarm: Boolean, history: List<Double> = emptyList(), trailing: @Composable () -> Unit) {
    val g = GlassTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (alarm) g.danger.copy(alpha = 0.16f) else g.fg.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Bubble(icon, on = alarm, size = 36.dp, onColor = if (alarm) g.danger else null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (history.size > 1) Text("Last 24 hours", style = MaterialTheme.typography.bodySmall, color = g.muted)
        }
        if (history.size > 1) Sparkline(history, g.cool)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (alarm) g.danger else g.fg, maxLines = 1)
        trailing()
    }
}

/** A small line of [values], scaled to their own range. */
@Composable
private fun Sparkline(values: List<Double>, color: Color) {
    val min = values.min()
    val span = (values.max() - min).takeIf { it > 0 } ?: 1.0
    Canvas(Modifier.width(84.dp).height(28.dp)) {
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val point = Offset(i * step, size.height - ((v - min) / span).toFloat() * size.height)
            if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** What the sheet lists: every reading with a value, alarms that are on first, battery last. */
internal fun sheetReadings(device: Device): List<Capability> =
    device.capabilities
        .mapNotNull { device.capability(it) }
        .filter { it.getable && it.value !is JsonNull && it.id != "onoff" }
        .sortedWith(
            compareBy<Capability>(
                { if (it.id.startsWith("alarm_") && it.booleanValue == true) 0 else 1 },
                {
                    when {
                        it.id == "measure_battery" || it.id == "alarm_battery" -> 4
                        it.id.startsWith("alarm_") -> 1
                        it.id.startsWith("measure_") || it.id.startsWith("meter_") -> 2
                        else -> 3
                    }
                },
            ),
        )

private fun readingIcon(cap: Capability): Ic = when {
    cap.id == "alarm_smoke" || cap.id == "alarm_fire" || cap.id == "alarm_heat" -> Sym.localFireDepartment
    cap.id == "alarm_motion" || cap.id.contains("presence") -> Sym.motionSensorActive
    cap.id == "alarm_contact" -> Sym.sensorDoor
    cap.id == "alarm_water" || cap.id == "measure_humidity" -> Sym.waterDrop
    cap.id.contains("battery") -> Sym.batteryFull
    cap.id == "measure_temperature" -> Sym.deviceThermostat
    cap.id == "measure_co2" || cap.id == "measure_co" || cap.id.startsWith("measure_pm") -> Sym.co2
    cap.id == "measure_luminance" -> Sym.wbSunny
    cap.id.startsWith("measure_power") || cap.id.startsWith("meter_power") || cap.id.contains("voltage") || cap.id.contains("current") -> Sym.bolt
    else -> Sym.sensors
}
