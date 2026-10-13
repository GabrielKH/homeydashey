package app.homeydash.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.homeydash.homey.Device
import app.homeydash.homey.FlowCards
import app.homeydash.homey.SpeakerGrouping
import app.homeydash.homey.Zone
import app.homeydash.ui.components.Bubble
import app.homeydash.ui.components.DialogHeader
import app.homeydash.ui.components.GlassDialog
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.deviceIcon
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.onBrush
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Sonos grouping for one speaker: add other speakers to it, take them out again,
 * and set the whole group's volume.
 */
@Composable
fun GroupSheet(
    device: Device,
    speakers: List<Device>,
    zones: Map<String, Zone>,
    actions: DashboardActions,
    onDismiss: () -> Unit,
) {
    val g = GlassTheme.colors
    val scope = rememberCoroutineScope()
    val own = actions.grouping(device.id)
    val others = speakers.filter { it.id != device.id }

    fun join(member: Device) {
        val grouping = actions.grouping(member.id) ?: return
        val card = grouping.join ?: return
        val arg = grouping.joinArg ?: return
        scope.launch {
            val value = coordinatorValue(grouping, device, member, actions)
            if (value == null) {
                actions.reportError("Couldn't find ${device.name} in ${member.name}'s group list")
            } else {
                actions.run(card, JsonObject(mapOf(arg.name to value)))
            }
        }
    }

    fun leave(member: Device) {
        actions.grouping(member.id)?.leave?.let { actions.run(it) }
    }

    GlassDialog(onDismiss = onDismiss, width = 620.dp) {
        DialogHeader(
            title = "Group speakers",
            subtitle = "Play ${device.name}'s music in more rooms",
            onClose = onDismiss,
            leading = { Bubble(Sym.speakerHifi, on = true, size = 52.dp) },
        )
        val volumeCard = own?.groupVolume
        val volumeArg = own?.groupVolumeArg
        if (volumeCard != null && volumeArg != null) {
            val card = volumeCard
            val arg = volumeArg
            var level by remember { mutableFloatStateOf(device.capability("volume_set")?.let(::fraction) ?: 0.3f) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Group volume", style = MaterialTheme.typography.labelLarge, color = g.fg)
                GlassSlider(
                    value = level,
                    onValue = {
                        level = it
                        val min = arg.min ?: 0.0
                        val max = arg.max ?: 1.0
                        actions.run(card, JsonObject(mapOf(arg.name to JsonPrimitive(min + (max - min) * it))))
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (others.isEmpty()) {
            Text("There are no other speakers to group with.", style = MaterialTheme.typography.bodyLarge, color = g.muted)
        } else {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(others, key = { it.id }) { member ->
                    val playing = member.capability("speaker_playing")?.booleanValue == true
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Bubble(deviceIcon(member), on = playing, size = 44.dp)
                        Column(Modifier.weight(1f)) {
                            Text(member.name, style = MaterialTheme.typography.titleSmall, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            member.zone?.let(zones::get)?.name?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = g.muted, maxLines = 1)
                            }
                        }
                        val grouping = actions.grouping(member.id)
                        if (grouping?.leave != null) SmallPill("Remove", primary = false) { leave(member) }
                        if (grouping?.join != null) SmallPill("Add", primary = true) { join(member) }
                    }
                }
            }
        }
        own?.leave?.let { card ->
            Text(
                "Take ${device.name} out of its group",
                style = MaterialTheme.typography.labelLarge,
                color = g.danger,
                modifier = Modifier.pressable({ actions.run(card) }).glass(PillShape).padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }
}

/** The value for [member]'s "Group with" argument that names [coordinator]. */
private suspend fun coordinatorValue(grouping: SpeakerGrouping, coordinator: Device, member: Device, actions: DashboardActions): JsonElement? {
    val card = grouping.join ?: return null
    val arg = grouping.joinArg ?: return null
    return when (arg.type) {
        "device" -> JsonObject(mapOf("id" to JsonPrimitive(coordinator.id), "name" to JsonPrimitive(coordinator.name)))
        "dropdown" -> FlowCards.dropdownItems(arg).firstOrNull { it.name.equals(coordinator.name, ignoreCase = true) }
            ?.let { FlowCards.argValue(arg, it) }
        else -> {
            val items = actions.autocomplete(card, arg.name, "")
            (items.firstOrNull { coordinator.id in it.raw.toString() }
                ?: items.firstOrNull { it.name.equals(coordinator.name, ignoreCase = true) }
                ?: items.firstOrNull { it.name.contains(coordinator.name, ignoreCase = true) })
                ?.let { FlowCards.argValue(arg, it) }
        }
    }.takeIf { member.id != coordinator.id }
}

@Composable
private fun SmallPill(label: String, primary: Boolean, onClick: () -> Unit) {
    val g = GlassTheme.colors
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (primary) g.onInk else g.fg,
        modifier = Modifier
            .pressable(onClick)
            .then(if (primary) Modifier.background(onBrush(), PillShape) else Modifier.glass(PillShape))
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}
