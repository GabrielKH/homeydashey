package app.homeydash.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.homeydash.homey.Device
import app.homeydash.homey.RoomSummary
import app.homeydash.homey.Zone
import app.homeydash.ui.components.Bubble
import app.homeydash.ui.components.DialogHeader
import app.homeydash.ui.components.GlassDialog
import app.homeydash.ui.components.Ic
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.deviceIcon
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.pressable
import app.homeydash.ui.components.zoneIcon
import app.homeydash.ui.theme.GlassTheme

/** One widget the overview can show, for the + Add card list. */
data class OverviewWidget(val key: String, val icon: Ic, val title: String, val detail: String)

/**
 * What's on the overview, from + Add card while the cards wiggle (Gabriel, 2026-10-22):
 * widgets, rooms and devices in three tabs, each with a switch that puts the card on the
 * overview or takes it off at once.
 */
@Composable
fun AddCardsDialog(
    widgets: List<OverviewWidget>,
    widgetOn: (String) -> Boolean,
    onWidget: (String, Boolean) -> Unit,
    rooms: List<RoomSummary>,
    roomOn: (String) -> Boolean,
    onRoom: (String, Boolean) -> Unit,
    devices: Collection<Device>,
    zones: Map<String, Zone>,
    pinned: Set<String>,
    onDevice: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val g = GlassTheme.colors
    var tab by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val sortedDevices = remember(devices, zones) {
        devices.sortedWith(compareBy<Device>({ it.zone?.let(zones::get)?.name?.lowercase() ?: "~" }, { it.name.lowercase() }))
    }
    GlassDialog(onDismiss = onDismiss, width = 560.dp) {
        DialogHeader(title = "On the overview", subtitle = "Switch a card on to add it, off to take it away", onClose = onDismiss)
        val counts = listOf(widgets.count { widgetOn(it.key) }, rooms.count { roomOn(it.zone.id) }, sortedDevices.count { it.id in pinned })
        Row(Modifier.fillMaxWidth().glass(PillShape).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Widgets", "Rooms", "Devices").forEachIndexed { i, label ->
                val selected = tab == i
                Text(
                    "$label · ${counts[i]}",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) g.wallBase else g.fg,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .pressable({ tab = i })
                        .then(if (selected) Modifier.background(g.fg, PillShape) else Modifier)
                        .padding(vertical = 10.dp),
                )
            }
        }
        when (tab) {
            0 -> CardList(widgets, key = { it.key }) { w ->
                PickRow(w.icon, w.title, w.detail, widgetOn(w.key)) { onWidget(w.key, !widgetOn(w.key)) }
            }
            1 -> CardList(rooms, key = { it.zone.id }) { room ->
                val on = roomOn(room.zone.id)
                PickRow(zoneIcon(room.zone), room.zone.name, if (room.lightsOn > 0) "${room.lightsOn} on" else "All off", on) { onRoom(room.zone.id, !on) }
            }
            else -> {
                Row(
                    Modifier.fillMaxWidth().glass(PillShape).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SymIcon(Sym.search, size = 18.dp, tint = g.muted)
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Search devices", style = MaterialTheme.typography.bodyLarge, color = g.muted)
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = g.fg),
                            cursorBrush = SolidColor(g.fg),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                val shown = sortedDevices.filter {
                    query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) ||
                        it.zone?.let(zones::get)?.name?.contains(query.trim(), ignoreCase = true) == true
                }
                CardList(shown, key = { it.id }) { device ->
                    val on = device.id in pinned
                    PickRow(deviceIcon(device), device.name, device.zone?.let(zones::get)?.name.orEmpty(), on) { onDevice(device.id, !on) }
                }
            }
        }
    }
}

@Composable
private fun <T> CardList(rows: List<T>, key: (T) -> Any, row: @Composable (T) -> Unit) {
    LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(rows, key = key) { row(it) }
    }
}

@Composable
private fun PickRow(icon: Ic, title: String, detail: String, on: Boolean, onToggle: () -> Unit) {
    val g = GlassTheme.colors
    Row(
        Modifier.fillMaxWidth().pressable(onToggle).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Bubble(icon, on = false, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = g.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Toggle(on)
    }
}
