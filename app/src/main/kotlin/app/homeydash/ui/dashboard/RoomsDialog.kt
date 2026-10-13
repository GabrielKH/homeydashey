package app.homeydash.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.homeydash.homey.DashboardLayout
import app.homeydash.homey.Zone
import app.homeydash.ui.components.Bubble
import app.homeydash.ui.components.DialogHeader
import app.homeydash.ui.components.GlassDialog
import app.homeydash.ui.components.RoundButton
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.zoneIcon
import app.homeydash.ui.theme.GlassTheme

/** Every room with a switch to show or hide it and arrows to move it among its neighbours. */
@Composable
fun RoomsDialog(
    zones: Map<String, Zone>,
    hiddenRooms: Set<String>,
    order: List<String>,
    onHide: (String, Boolean) -> Unit,
    onOrder: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val g = GlassTheme.colors
    val tree = remember(zones, order) { DashboardLayout.zoneTree(zones, order) }
    GlassDialog(onDismiss = onDismiss, width = 620.dp) {
        DialogHeader(title = "Rooms", subtitle = "Hide rooms or change their order", onClose = onDismiss)
        LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(tree, key = { it.first.id }) { (zone, depth) ->
                val isHome = zone.parent == null || zone.parent !in zones
                val hiddenAbove = DashboardLayout.parents(zones, zone.id).any { it.id in hiddenRooms }
                val shown = zone.id !in hiddenRooms && !hiddenAbove
                val siblings = remember(zones, order, zone.id) { DashboardLayout.siblings(zones, order, zone.id) }
                val index = siblings.indexOfFirst { it.id == zone.id }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp * depth.coerceAtMost(3), top = 4.dp, bottom = 4.dp)
                        .graphicsLayer { alpha = if (shown) 1f else 0.5f },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Bubble(zoneIcon(zone), on = false, size = 40.dp)
                    Text(
                        zone.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = g.fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    RoundButton(Sym.keyboardArrowUp, { onOrder(DashboardLayout.move(zones, order, zone.id, -1)) }, size = 38.dp, filledIcon = false, enabled = index > 0)
                    RoundButton(Sym.keyboardArrowDown, { onOrder(DashboardLayout.move(zones, order, zone.id, +1)) }, size = 38.dp, filledIcon = false, enabled = index in 0 until siblings.lastIndex)
                    if (isHome || hiddenAbove) {
                        Box(Modifier.width(52.dp))
                    } else {
                        Toggle(shown, Modifier.padding(start = 4.dp)) { onHide(zone.id, shown) }
                    }
                }
            }
        }
        Text(
            "Hiding a floor hides the rooms on it too. Hidden rooms only disappear from this tablet.",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
    }
}
