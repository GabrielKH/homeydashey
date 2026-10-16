package app.homeydash.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.homeydash.ui.components.AppDialog
import app.homeydash.ui.components.dialogSize
import app.homeydash.homey.Device
import app.homeydash.homey.Zone
import app.homeydash.ui.components.Bubble
import app.homeydash.ui.components.PanelShape
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.deviceIcon
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme

/** Every device with a switch for whether it shows on this tablet. */
@Composable
fun DevicePickerDialog(
    zones: Map<String, Zone>,
    devices: Map<String, Device>,
    hiddenByUser: Set<String>,
    hiddenOnTablet: Set<String>,
    onSetHidden: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val g = GlassTheme.colors
    val rows = remember(zones, devices) {
        devices.values.sortedWith(compareBy<Device>({ it.zone?.let(zones::get)?.name?.lowercase() ?: "~" }, { it.name.lowercase() }))
    }
    AppDialog(onDismiss) {
        Column(
            Modifier
                .dialogSize(620.dp)
                .background(g.wallBase, PanelShape)
                .glass()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Devices on the dashboard", style = MaterialTheme.typography.headlineMedium, color = g.fg)
            Text(
                "Switch off anything you don't want on this tablet. Devices hidden in the Homey app are already left out.",
                style = MaterialTheme.typography.bodyMedium,
                color = g.muted,
            )
            LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(rows, key = { it.id }) { device ->
                    val inHomey = device.hidden || device.id in hiddenByUser
                    val shown = !inHomey && device.id !in hiddenOnTablet
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .pressable({ onSetHidden(device.id, shown) }.takeIf { !inHomey })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Bubble(deviceIcon(device), on = false, size = 40.dp)
                        Column(Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.titleSmall, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(device.zone?.let(zones::get)?.name, "Hidden in Homey".takeIf { inHomey }).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = g.muted,
                                maxLines = 1,
                            )
                        }
                        if (!inHomey) Toggle(shown)
                    }
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Text(
                    "Done",
                    style = MaterialTheme.typography.labelLarge,
                    color = g.wallBase,
                    modifier = Modifier.pressable(onDismiss).background(g.fg, PillShape).padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** iOS-style switch; without [onClick] it is display only and the whole row is the tap target. */
@Composable
fun Toggle(on: Boolean, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val g = GlassTheme.colors
    Box(
        modifier
            .pressable(onClick)
            .width(52.dp)
            .then(if (on) Modifier.background(g.ok, PillShape) else Modifier.glass(PillShape))
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(26.dp).background(if (on) Color.White else g.muted, CircleShape))
    }
}
