package app.homeydash.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import app.homeydash.homey.Remotes
import app.homeydash.homey.TvApps
import app.homeydash.ui.components.DialogHeader
import app.homeydash.ui.components.GlassDialog
import app.homeydash.ui.components.Ic
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.RoundButton
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/**
 * A Siri Remote-style remote for a TV or media box (Apple TV, Samsung and others):
 * a click-pad you tap or swipe, Back, Home, play/pause, volume and channels on the
 * left, and the box's apps on the right, streaming services first with their logos.
 */
@Composable
fun RemoteSheet(
    device: Device,
    actions: DashboardActions,
    /** The other devices, to find a TV in the same room for the volume keys. */
    others: List<Device>,
    /** The apps chosen for this box, in order; null shows them all, streaming apps first. */
    chosenApps: List<String>?,
    onChooseApps: (List<String>?) -> Unit,
    onDismiss: () -> Unit,
) {
    val g = GlassTheme.colors
    val cards = actions.cards(device.id)
    val keyCard = remember(cards) { Remotes.keyCard(cards) }
    val keyList by produceState<List<AutocompleteItem>>(keyCard?.arg?.takeIf { it.type == "dropdown" }?.let(FlowCards::dropdownItems).orEmpty(), keyCard?.card?.id) {
        if (keyCard != null && keyCard.arg.type == "autocomplete") value = actions.autocomplete(keyCard.card, keyCard.arg.name, "")
    }
    val ownKeys = remember(device, cards, keyCard, keyList) { Remotes.actions(device, cards, keyCard, Remotes.keyItems(keyList)) }
    // Like a Siri Remote, the volume keys turn the TV's volume when the TV is in Homey too.
    val volumeTv = remember(device, others, actions.deviceCards) { Remotes.volumeTv(device, others.map { it to actions.cards(it.id) }) }
    val keys = remember(ownKeys, volumeTv) { if (volumeTv == null) ownKeys else ownKeys + volumeTv.second }
    val launcher = remember(cards) { FlowCards.appLauncher(cards) }
    fun press(key: RemoteKey) {
        val tv = volumeTv
        if (tv != null && key in Remotes.VOLUME_KEYS && key in tv.second) {
            tv.second[key]?.let { actions.press(tv.first, it) }
        } else {
            ownKeys[key]?.let { actions.press(device, it) }
        }
    }

    GlassDialog(onDismiss = onDismiss, width = if (launcher != null) 940.dp else 460.dp) {
        DialogHeader(title = device.name, subtitle = "Remote", onClose = onDismiss)
        if (keys.isEmpty() && launcher == null) {
            Text(
                "Homey doesn't offer remote buttons for this device yet. They come from the TV app's Flow cards, so the API key needs the Flow permission.",
                style = MaterialTheme.typography.bodyLarge,
                color = g.muted,
            )
            return@GlassDialog
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.width(400.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (RemoteKey.Power in keys) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        KeyButton(Sym.power, RemoteKey.Power, keys.keys, size = 52.dp) { press(it) }
                    }
                }
                if (keys.keys.any { it in PadKeys }) ClickPad(keys.keys, ::press)
                if (keys.keys.any { it == RemoteKey.Back || it == RemoteKey.Home || it == RemoteKey.PlayPause }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
                        KeyButton(Sym.backKey, RemoteKey.Back, keys.keys, label = "Back") { press(it) }
                        KeyButton(Sym.tv, RemoteKey.Home, keys.keys, label = "Home") { press(it) }
                        KeyButton(Sym.playPause, RemoteKey.PlayPause, keys.keys, label = "Play") { press(it) }
                    }
                }
                VolumeAndChannels(keys.keys, volumeTv?.first?.name, ::press)
            }
            if (launcher != null) {
                AppsPanel(launcher, actions, chosenApps, onChooseApps, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun VolumeAndChannels(keys: Set<RemoteKey>, volumeOn: String?, press: (RemoteKey) -> Unit) {
    val g = GlassTheme.colors
    val hasVolume = keys.any { it in Remotes.VOLUME_KEYS }
    val hasChannels = keys.any { it == RemoteKey.ChannelUp || it == RemoteKey.ChannelDown }
    if (!hasVolume && !hasChannels) return
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (hasVolume) {
                Row(
                    Modifier.glass(PillShape).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KeyButton(Sym.remove, RemoteKey.VolumeDown, keys, size = 56.dp, glass = false, press = press)
                    KeyButton(Sym.volumeOff, RemoteKey.Mute, keys, size = 56.dp, glass = false, press = press)
                    KeyButton(Sym.add, RemoteKey.VolumeUp, keys, size = 56.dp, glass = false, press = press)
                }
            }
            if (hasChannels) {
                Row(
                    Modifier.glass(PillShape).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KeyButton(Sym.keyboardArrowDown, RemoteKey.ChannelDown, keys, size = 56.dp, glass = false, press = press)
                    Text("CH", style = MaterialTheme.typography.labelLarge, color = g.muted)
                    KeyButton(Sym.keyboardArrowUp, RemoteKey.ChannelUp, keys, size = 56.dp, glass = false, press = press)
                }
            }
        }
        if (hasVolume && volumeOn != null) {
            Text("Volume on $volumeOn", style = MaterialTheme.typography.bodyMedium, color = g.muted)
        }
    }
}

private val PadKeys = setOf(RemoteKey.Up, RemoteKey.Down, RemoteKey.Left, RemoteKey.Right, RemoteKey.Select)

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

/** One app from the box's "Launch app" list. */
private data class TvApp(val id: String, val name: String, val item: AutocompleteItem)

private fun AutocompleteItem.appId(): String =
    (raw["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: description ?: name

/** The box's apps as tiles; the sliders button switches to choosing and ordering them. */
@Composable
private fun AppsPanel(
    source: MusicSource,
    actions: DashboardActions,
    chosen: List<String>?,
    onChoose: (List<String>?) -> Unit,
    modifier: Modifier,
) {
    val g = GlassTheme.colors
    val card = source.card
    val arg = source.arg
    val loaded by produceState<List<TvApp>?>(null, card.id) {
        val items = if (arg.type == "dropdown") FlowCards.dropdownItems(arg) else actions.autocomplete(card, arg.name, "")
        value = items.map { TvApp(it.appId(), it.name, it) }.distinctBy { it.id }
    }
    var editing by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (editing) "CHOOSE APPS" else "APPS", style = MaterialTheme.typography.labelMedium, color = g.muted, modifier = Modifier.weight(1f))
            if (editing && chosen != null) {
                Text(
                    "Reset",
                    style = MaterialTheme.typography.labelLarge,
                    color = g.fg,
                    modifier = Modifier.pressable({ onChoose(null) }).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            RoundButton(if (editing) Sym.check else Sym.sliders, { editing = !editing }, size = 38.dp, filledIcon = editing)
        }
        val apps = loaded
        when {
            apps == null -> Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = g.fg)
            }
            apps.isEmpty() -> Text("The box didn't list any apps.", style = MaterialTheme.typography.bodyLarge, color = g.muted)
            editing -> AppChooser(apps, chosen, onChoose)
            else -> {
                val shown = remember(apps, chosen) { TvApps.arrange(apps, { it.id }, { it.name }, chosen) }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.heightIn(max = 600.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(shown, key = { it.id }) { app ->
                        AppTile(
                            app.id,
                            app.name,
                            Modifier.pressable({ actions.run(card, JsonObject(mapOf(arg.name to FlowCards.argValue(arg, app.item)))) }),
                        )
                    }
                }
            }
        }
    }
}

/** Every app with a switch to show it and arrows to order the shown ones. */
@Composable
private fun AppChooser(apps: List<TvApp>, chosen: List<String>?, onChoose: (List<String>?) -> Unit) {
    val g = GlassTheme.colors
    val shown = remember(apps, chosen) { TvApps.arrange(apps, { it.id }, { it.name }, chosen) }
    val hidden = remember(apps, shown) { apps.filter { it !in shown }.sortedBy { it.name.lowercase() } }
    val order = shown.map { it.id }
    LazyColumn(Modifier.heightIn(max = 600.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(shown + hidden, key = { _, app -> app.id }) { index, app ->
            val on = index < shown.size
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).graphicsLayer { alpha = if (on) 1f else 0.55f },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AppIcon(app.id, app.name, Modifier.width(64.dp), logoSize = 22.dp)
                Text(app.name, style = MaterialTheme.typography.titleSmall, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (on) {
                    RoundButton(Sym.keyboardArrowUp, { onChoose(order.swap(index, index - 1)) }, size = 36.dp, filledIcon = false, enabled = index > 0)
                    RoundButton(Sym.keyboardArrowDown, { onChoose(order.swap(index, index + 1)) }, size = 36.dp, filledIcon = false, enabled = index < shown.lastIndex)
                }
                Toggle(on) { onChoose(if (on) order - app.id else order + app.id) }
            }
        }
    }
}

private fun List<String>.swap(i: Int, j: Int): List<String> =
    if (i !in indices || j !in indices) this else toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }
