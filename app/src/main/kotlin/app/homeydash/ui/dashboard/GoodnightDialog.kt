package app.homeydash.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.homeydash.homey.Device
import app.homeydash.homey.Goodnight
import app.homeydash.homey.HomeyFlow
import app.homeydash.homey.Zone
import app.homeydash.ui.components.DialogHeader
import app.homeydash.ui.components.GlassDialog
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.SectionLabel
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.deviceIcon
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.onBrush
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme

/**
 * Goodnight: shows what is on right now and turns it all off in one go, except the
 * devices chosen to stay on. It can also start one of the Homey's Flows, for anything
 * else bedtime should do (lock up, arm the alarm, lower the heating).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoodnightDialog(
    devices: Collection<Device>,
    zones: Map<String, Zone>,
    keep: Set<String>,
    flow: HomeyFlow?,
    actions: DashboardActions,
    household: app.homeydash.homey.HouseholdStore,
    markAsleep: Boolean,
    onMarkAsleep: (Boolean) -> Unit,
    onKeep: (Set<String>) -> Unit,
    onFlow: (HomeyFlow?) -> Unit,
    onDismiss: () -> Unit,
) {
    val g = GlassTheme.colors
    val steps = remember(devices, keep) { Goodnight.steps(devices, keep) }
    val candidates = remember(devices) { Goodnight.candidates(devices) }
    var choosingFlow by remember { mutableStateOf(false) }
    val lights = steps.count { it.capability == "onoff" }
    val players = steps.size - lights

    GlassDialog(onDismiss = onDismiss, width = 920.dp) {
        DialogHeader(
            title = "Goodnight",
            subtitle = "Turns off everything that's on, except what you keep on",
            onClose = onDismiss,
            leading = {
                Box(Modifier.size(52.dp).background(onBrush(), CircleShape), contentAlignment = Alignment.Center) {
                    SymIcon(Sym.bedtime, filled = true, size = 26.dp, tint = g.onInk)
                }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            // Left: what will happen, the Flow, and the button.
            Column(Modifier.weight(1f).heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionLabel("Now on")
                if (steps.isEmpty()) {
                    Text("Everything is already off.", style = MaterialTheme.typography.bodyLarge, color = g.muted)
                } else {
                    Text(
                        listOfNotNull(
                            lights.takeIf { it > 0 }?.let { if (it == 1) "1 device turns off" else "$it devices turn off" },
                            players.takeIf { it > 0 }?.let { if (it == 1) "1 player pauses" else "$it players pause" },
                        ).joinToString(" and ") + ". Tap one to keep it on.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = g.fg,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        steps.forEach { step ->
                            DeviceChip(step.device, room = step.device.zone?.let { zones[it]?.name }) { onKeep(keep + step.device.id) }
                        }
                    }
                }

                SectionLabel("Tonight and tomorrow")
                GoodnightWakeUp(household, markAsleep, onMarkAsleep)

                SectionLabel("Also start a Flow")
                FlowChoice(flow, onClick = { choosingFlow = !choosingFlow }, onClear = { onFlow(null) })
                if (choosingFlow) {
                    FlowPicker(actions, selected = flow, onPick = { onFlow(it); choosingFlow = false })
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .pressable({ actions.goodnight(steps, flow, markAsleep); onDismiss() })
                        .background(onBrush(), PillShape)
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SymIcon(Sym.bedtime, filled = true, size = 22.dp, tint = g.onInk)
                    Text(
                        if (flow != null) "Goodnight · ${flow.name}" else "Goodnight",
                        style = MaterialTheme.typography.titleMedium,
                        color = g.onInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
            // Right: the devices that always stay on.
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Always keep on")
                Text(
                    if (keep.isEmpty()) "Nothing yet. Turn on what should stay on, like the fridge or a night light." else "${keep.size} kept on",
                    style = MaterialTheme.typography.bodyMedium,
                    color = g.muted,
                )
                LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(candidates, key = { it.id }) { device ->
                        val kept = device.id in keep
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pressable({ onKeep(if (kept) keep - device.id else keep + device.id) })
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            SymIcon(deviceIcon(device), filled = kept, size = 22.dp, tint = if (kept) g.fg else g.muted)
                            Column(Modifier.weight(1f)) {
                                Text(device.name, style = MaterialTheme.typography.titleSmall, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                device.zone?.let { zones[it]?.name }?.let {
                                    Text(it, style = MaterialTheme.typography.bodyMedium, color = g.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Toggle(kept)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceChip(device: Device, room: String?, onClick: () -> Unit) {
    val g = GlassTheme.colors
    Row(
        Modifier.pressable(onClick).glass(PillShape).padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SymIcon(deviceIcon(device), filled = true, size = 18.dp, tint = g.on)
        Text(device.name, style = MaterialTheme.typography.labelLarge, color = g.fg, maxLines = 1)
        room?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = g.muted, maxLines = 1) }
    }
}

@Composable
private fun FlowChoice(flow: HomeyFlow?, onClick: () -> Unit, onClear: () -> Unit) {
    val g = GlassTheme.colors
    Row(
        Modifier.fillMaxWidth().pressable(onClick).glass(RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SymIcon(Sym.flow, filled = flow != null, size = 22.dp, tint = if (flow != null) g.fg else g.muted)
        Column(Modifier.weight(1f)) {
            Text(flow?.name ?: "No Flow", style = MaterialTheme.typography.titleSmall, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (flow == null) "Choose a Flow from your Homey" else if (flow.advanced) "Advanced Flow" else "Flow",
                style = MaterialTheme.typography.bodyMedium,
                color = g.muted,
            )
        }
        if (flow != null) {
            SymIcon(Sym.close, size = 18.dp, tint = g.muted, modifier = Modifier.pressable(onClear).padding(4.dp))
        } else {
            SymIcon(Sym.keyboardArrowDown, size = 18.dp, tint = g.muted)
        }
    }
}

/** The Homey's Flows that can be started, with a search field. */
@Composable
private fun FlowPicker(actions: DashboardActions, selected: HomeyFlow?, onPick: (HomeyFlow) -> Unit) {
    val g = GlassTheme.colors
    val flows by produceState<List<HomeyFlow>?>(null) { value = actions.flows() }
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().glass(PillShape).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SymIcon(Sym.search, size = 18.dp, tint = g.muted)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search Flows", style = MaterialTheme.typography.bodyLarge, color = g.muted)
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
        val list = flows
        when {
            list == null -> Text("Loading Flows…", style = MaterialTheme.typography.bodyMedium, color = g.muted)
            list.isEmpty() -> Text(
                "No Flows found. The API key needs permission to read and start Flows.",
                style = MaterialTheme.typography.bodyMedium,
                color = g.muted,
            )
            else -> {
                val shown = list.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    items(shown, key = { (if (it.advanced) "a:" else "f:") + it.id }) { flow ->
                        val on = flow == selected
                        Row(
                            Modifier.fillMaxWidth().pressable({ onPick(flow) }).padding(horizontal = 6.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            SymIcon(Sym.flow, filled = on, size = 18.dp, tint = if (on) g.fg else g.muted)
                            Text(flow.name, style = MaterialTheme.typography.bodyLarge, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (flow.advanced) Text("Advanced", style = MaterialTheme.typography.bodySmall, color = g.muted)
                            if (on) SymIcon(Sym.check, filled = true, size = 18.dp, tint = g.fg)
                        }
                    }
                }
            }
        }
    }
}
