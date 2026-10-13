package app.homeydash.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.homeydash.homey.CardArrangement
import app.homeydash.homey.CardLayout
import app.homeydash.homey.DashboardLayout
import app.homeydash.homey.Device
import app.homeydash.homey.Zone
import app.homeydash.homey.ZoneSection
import app.homeydash.ui.components.DialogHeader
import app.homeydash.ui.components.GlassDialog
import app.homeydash.ui.components.Ic
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.onBrush
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a long press on a card opens: the card's own extras (lamp, unlock, remote),
 * moving it within its group, putting it in a group, and hiding it on this tablet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardMenu(
    device: Device,
    section: ZoneSection?,
    arrangement: CardArrangement,
    actions: DashboardActions,
    onOrder: (zoneId: String, order: List<String>) -> Unit,
    onGroup: (title: String?) -> Unit,
    onHide: () -> Unit,
    pinned: Boolean,
    onPin: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val g = GlassTheme.colors
    val position = section?.let { CardLayout.position(it, arrangement, device.id) }
    val customTitles = section?.let { CardLayout.customTitles(it, arrangement) }.orEmpty()
    val current = arrangement.groups[device.id]
    var newGroup by remember { mutableStateOf<String?>(null) }

    GlassDialog(onDismiss = onDismiss, width = 520.dp) {
        DialogHeader(title = device.name, subtitle = section?.zone?.name, onClose = onDismiss)

        val locked = device.capability("locked")
        val extras = buildList<MenuExtra> {
            if (hasLightControls(device) && device.available) add(MenuExtra(Sym.sliders, "Light controls") { actions.open(Sheet.Light(device.id)) })
            if (locked?.setable == true && locked?.booleanValue == true && device.available) {
                add(MenuExtra(Sym.lockOpen, "Unlock") { actions.set(device.id, "locked", JsonPrimitive(false)); onDismiss() })
            }
            if (actions.hasRemote(device)) add(MenuExtra(Sym.remote, "Remote") { actions.open(Sheet.Remote(device.id)) })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MenuChip(if (pinned) Sym.pushPinSlash else Sym.pushPin, if (pinned) "Remove from overview" else "Add to overview", selected = pinned) {
                onPin(!pinned)
                onDismiss()
            }
            extras.forEach { extra -> MenuChip(extra.icon, extra.label, onClick = extra.onClick) }
        }
        Text("Tip: hold a card and drag it to move it.", style = MaterialTheme.typography.bodyMedium, color = g.muted)

        if (section != null && position != null && position.second > 1) {
            MenuLabel("Order")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MenuChip(Sym.keyboardArrowLeft, "Move earlier", enabled = position.first > 0) {
                    onOrder(section.zone.id, CardLayout.move(section, arrangement, device.id, -1))
                }
                MenuChip(Sym.keyboardArrowRight, "Move later", enabled = position.first < position.second - 1) {
                    onOrder(section.zone.id, CardLayout.move(section, arrangement, device.id, +1))
                }
            }
        }

        MenuLabel("Group")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MenuChip(null, DashboardLayout.group(device).title, selected = current == null) { onGroup(null) }
            customTitles.forEach { title -> MenuChip(null, title, selected = current == title) { onGroup(title) } }
            if (newGroup == null) MenuChip(Sym.add, "New group") { newGroup = "" }
        }
        newGroup?.let { text ->
            Row(
                Modifier.fillMaxWidth().glass(PillShape).padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (text.isEmpty()) Text("Group name, e.g. Ceiling", style = MaterialTheme.typography.bodyLarge, color = g.muted)
                    BasicTextField(
                        value = text,
                        onValueChange = { newGroup = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = g.fg),
                        cursorBrush = SolidColor(g.fg),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) { onGroup(text.trim()); newGroup = null } }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                MenuChip(Sym.check, "Add", enabled = text.isNotBlank(), primary = true) { onGroup(text.trim()); newGroup = null }
            }
        }

        Row(
            Modifier.fillMaxWidth().pressable({ onHide(); onDismiss() }).glass(PillShape).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SymIcon(Sym.visibilityOff, size = 20.dp, tint = g.danger)
            Column(Modifier.weight(1f)) {
                Text("Hide on this tablet", style = MaterialTheme.typography.labelLarge, color = g.danger)
                Text("Settings → Choose devices to show brings it back.", style = MaterialTheme.typography.bodyMedium, color = g.muted)
            }
        }
    }
}

/** A long press on a room card on the overview: move it, hide it, or change its background. */
@Composable
fun RoomMenu(
    zone: Zone,
    zones: Map<String, Zone>,
    order: List<String>,
    onOrder: (List<String>) -> Unit,
    onHide: () -> Unit,
    onBackground: () -> Unit,
    onDismiss: () -> Unit,
) {
    val g = GlassTheme.colors
    val siblings = remember(zones, order, zone.id) { DashboardLayout.siblings(zones, order, zone.id) }
    val index = siblings.indexOfFirst { it.id == zone.id }
    GlassDialog(onDismiss = onDismiss, width = 480.dp) {
        DialogHeader(title = zone.name, subtitle = "Room", onClose = onDismiss)
        if (siblings.size > 1) {
            MenuLabel("Order")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MenuChip(Sym.keyboardArrowLeft, "Move earlier", enabled = index > 0) { onOrder(DashboardLayout.move(zones, order, zone.id, -1)) }
                MenuChip(Sym.keyboardArrowRight, "Move later", enabled = index in 0 until siblings.lastIndex) { onOrder(DashboardLayout.move(zones, order, zone.id, +1)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MenuChip(Sym.image, "Background", onClick = onBackground)
            MenuChip(Sym.visibilityOff, "Hide room", color = g.danger) { onHide(); onDismiss() }
        }
        Text("Settings → Hide and sort rooms brings hidden rooms back.", style = MaterialTheme.typography.bodyMedium, color = g.muted)
    }
}

private class MenuExtra(val icon: Ic, val label: String, val onClick: () -> Unit)

@Composable
private fun MenuLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = GlassTheme.colors.muted)
}

@Composable
private fun MenuChip(
    icon: Ic?,
    label: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    primary: Boolean = false,
    color: Color? = null,
    onClick: () -> Unit,
) {
    val g = GlassTheme.colors
    val filled = selected || primary
    val ink = when {
        filled -> g.onInk
        color != null -> color
        else -> g.fg
    }
    Row(
        Modifier
            .widthIn(min = 0.dp)
            .pressable(onClick.takeIf { enabled })
            .then(if (filled) Modifier.background(onBrush(), PillShape) else Modifier.glass(PillShape))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let { SymIcon(it, filled = filled, size = 18.dp, tint = if (enabled) ink else g.muted) }
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) ink else g.muted, maxLines = 1)
    }
}
