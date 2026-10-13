package app.homeydash.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.homeydash.homey.AutocompleteItem
import app.homeydash.homey.Device
import app.homeydash.homey.FlowCards
import app.homeydash.homey.MusicSource
import app.homeydash.homey.RemoteKey
import app.homeydash.ui.components.DialogHeader
import app.homeydash.ui.components.GlassDialog
import app.homeydash.ui.components.Ic
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs

/**
 * A Siri Remote for an Apple TV (or any device whose app has remote cards):
 * a click-pad you tap or swipe, Back, Home, play/pause, volume and an app row.
 */
@Composable
fun RemoteSheet(device: Device, actions: DashboardActions, onDismiss: () -> Unit) {
    val g = GlassTheme.colors
    val keys = actions.remoteKeys(device.id)
    val launcher = remember(actions.deviceCards, device.id) { FlowCards.appLauncher(actions.cards(device.id)) }
    fun press(key: RemoteKey) {
        keys[key]?.let { actions.run(it) }
    }

    GlassDialog(onDismiss = onDismiss, width = 460.dp) {
        DialogHeader(title = device.name, subtitle = "Remote", onClose = onDismiss)
        if (keys.isEmpty()) {
            Text(
                "Homey doesn't offer remote buttons for this device. They come from the Apple TV app's Flow cards, so the API key needs the Flow permission.",
                style = MaterialTheme.typography.bodyLarge,
                color = g.muted,
            )
            return@GlassDialog
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            if (RemoteKey.Power in keys) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    KeyButton(Sym.power, RemoteKey.Power, keys.keys, size = 52.dp) { press(it) }
                }
            }
            ClickPad(keys.keys, ::press)
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
                KeyButton(Sym.backKey, RemoteKey.Back, keys.keys, label = "Back") { press(it) }
                KeyButton(Sym.tv, RemoteKey.Home, keys.keys, label = "Home") { press(it) }
                KeyButton(Sym.playPause, RemoteKey.PlayPause, keys.keys, label = "Play") { press(it) }
            }
            if (keys.keys.any { it == RemoteKey.VolumeUp || it == RemoteKey.VolumeDown || it == RemoteKey.Mute }) {
                Row(
                    Modifier.glass(PillShape).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KeyButton(Sym.remove, RemoteKey.VolumeDown, keys.keys, size = 56.dp, glass = false) { press(it) }
                    KeyButton(Sym.volumeOff, RemoteKey.Mute, keys.keys, size = 56.dp, glass = false) { press(it) }
                    KeyButton(Sym.add, RemoteKey.VolumeUp, keys.keys, size = 56.dp, glass = false) { press(it) }
                }
            }
        }
        launcher?.let { AppRow(it, actions) }
    }
}

/** The round pad: tap an edge for a direction, the middle for select, or swipe across it. */
@Composable
private fun ClickPad(available: Set<RemoteKey>, press: (RemoteKey) -> Unit) {
    val g = GlassTheme.colors
    val send by rememberUpdatedState(press)
    Box(
        Modifier
            .size(300.dp)
            .glass(CircleShape)
            .pointerInput(Unit) {
                var total = Offset.Zero
                detectDragGestures(
                    onDragStart = { total = Offset.Zero },
                    onDrag = { change, delta ->
                        change.consume()
                        total += delta
                        // Each 70px of swipe is one step, like the Siri Remote's touch surface.
                        val step = 70.dp.toPx()
                        when {
                            abs(total.x) > step && abs(total.x) > abs(total.y) -> {
                                send(if (total.x > 0) RemoteKey.Right else RemoteKey.Left)
                                total = Offset.Zero
                            }
                            abs(total.y) > step -> {
                                send(if (total.y > 0) RemoteKey.Down else RemoteKey.Up)
                                total = Offset.Zero
                            }
                        }
                    },
                )
            },
    ) {
        PadArrow(Sym.keyboardArrowUp, RemoteKey.Up, available, Modifier.align(Alignment.TopCenter), press)
        PadArrow(Sym.keyboardArrowDown, RemoteKey.Down, available, Modifier.align(Alignment.BottomCenter), press)
        PadArrow(Sym.keyboardArrowLeft, RemoteKey.Left, available, Modifier.align(Alignment.CenterStart), press)
        PadArrow(Sym.keyboardArrowRight, RemoteKey.Right, available, Modifier.align(Alignment.CenterEnd), press)
        Box(
            Modifier
                .align(Alignment.Center)
                .size(124.dp)
                .graphicsLayer { alpha = if (RemoteKey.Select in available) 1f else 0.4f }
                .pressable({ press(RemoteKey.Select) }.takeIf { RemoteKey.Select in available })
                .glass(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("OK", style = MaterialTheme.typography.titleLarge, color = g.fg)
        }
    }
}

@Composable
private fun PadArrow(icon: Ic, key: RemoteKey, available: Set<RemoteKey>, modifier: Modifier, press: (RemoteKey) -> Unit) {
    val enabled = key in available
    Box(
        modifier
            .size(88.dp)
            .pressable({ press(key) }.takeIf { enabled }),
        contentAlignment = Alignment.Center,
    ) {
        SymIcon(icon, size = 30.dp, tint = if (enabled) GlassTheme.colors.fg else GlassTheme.colors.muted.copy(alpha = 0.4f))
    }
}

@Composable
private fun KeyButton(
    icon: Ic,
    key: RemoteKey,
    available: Set<RemoteKey>,
    size: Dp = 72.dp,
    label: String? = null,
    glass: Boolean = true,
    press: (RemoteKey) -> Unit,
) {
    val g = GlassTheme.colors
    val enabled = key in available
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(size)
                .graphicsLayer { alpha = if (enabled) 1f else 0.35f }
                .pressable({ press(key) }.takeIf { enabled })
                .then(if (glass) Modifier.glass(CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            SymIcon(icon, filled = true, size = size * 0.42f, tint = if (key == RemoteKey.Power && enabled) g.danger else g.fg)
        }
        label?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = g.muted) }
    }
}

/** Apps the Apple TV can open, from its "Launch app" card. */
@Composable
private fun AppRow(source: MusicSource, actions: DashboardActions) {
    val g = GlassTheme.colors
    val card = source.card
    val arg = source.arg
    val apps by produceState<List<AutocompleteItem>?>(null, card.id) {
        value = if (arg.type == "dropdown") FlowCards.dropdownItems(arg) else actions.autocomplete(card, arg.name, "")
    }
    val list = apps ?: return
    if (list.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("APPS", style = MaterialTheme.typography.labelMedium, color = g.muted)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list) { app ->
                Text(
                    app.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = g.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .width(IntrinsicAppWidth)
                        .pressable({ actions.run(card, JsonObject(mapOf(arg.name to FlowCards.argValue(arg, app)))) })
                        .glass(PillShape)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }
        }
    }
}

private val IntrinsicAppWidth = 150.dp
