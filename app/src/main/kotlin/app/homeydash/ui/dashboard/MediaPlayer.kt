package app.homeydash.ui.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.homeydash.homey.Device
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.RoundButton
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.TileShape
import app.homeydash.ui.components.deviceIcon
import app.homeydash.ui.components.glass
import app.homeydash.ui.theme.GlassTheme
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive

/**
 * Now-playing card for a speaker or TV: album art, track, progress, transport
 * controls and volume. [large] stacks it vertically with big art for the Media page.
 */
@Composable
fun MediaPlayerCard(device: Device, actions: DashboardActions, modifier: Modifier = Modifier, large: Boolean = false, subtitle: String? = null) {
    val g = GlassTheme.colors
    val playing = device.capability("speaker_playing")?.booleanValue == true
    val track = device.capability("speaker_track")?.stringValue?.takeIf { it.isNotBlank() }
    val artist = device.capability("speaker_artist")?.stringValue?.takeIf { it.isNotBlank() }
    val album = device.capability("speaker_album")?.stringValue?.takeIf { it.isNotBlank() }
    val onoff = device.capability("onoff")
    val poweredOff = onoff?.booleanValue == false
    val art = rememberAlbumArt(device, actions, track)

    val source = listOfNotNull(device.name, subtitle).joinToString(" · ")
    val title = track ?: if (poweredOff) "Off" else if (playing) "Playing" else "Nothing playing"
    val byline = listOfNotNull(artist, album.takeIf { large }).joinToString(" · ").ifEmpty { null }

    Column(
        modifier
            .fillMaxWidth()
            .then(if (large) Modifier else Modifier.height(TileHeight * 2 + 14.dp))
            .glass(TileShape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (large) {
            AlbumArt(art, device, Modifier.fillMaxWidth())
            TrackText(source, title, byline)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                AlbumArt(art, device, Modifier.size(104.dp))
                Box(Modifier.weight(1f)) { TrackText(source, title, byline) }
                onoff?.takeIf { it.setable }?.let {
                    RoundButton(Sym.powerSettingsNew, { actions.set(device.id, it.id, JsonPrimitive(poweredOff)) }, size = 40.dp, filledIcon = false)
                }
            }
        }
        Progress(device, playing)
        if (!large) Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val prev = device.capability("speaker_prev")?.setable == true
            val next = device.capability("speaker_next")?.setable == true
            val play = device.capability("speaker_playing")?.setable == true
            RoundButton(Sym.skipPrevious, { actions.set(device.id, "speaker_prev", JsonPrimitive(true)) }, enabled = prev, size = 52.dp)
            RoundButton(
                if (playing) Sym.pause else Sym.playArrow,
                { actions.set(device.id, "speaker_playing", JsonPrimitive(!playing)) },
                primary = true,
                enabled = play,
                size = 68.dp,
            )
            RoundButton(Sym.skipNext, { actions.set(device.id, "speaker_next", JsonPrimitive(true)) }, enabled = next, size = 52.dp)
        }
        device.capability("volume_set")?.takeIf { it.setable }?.let { volume ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val mute = device.capability("volume_mute")?.takeIf { it.setable }
                val muted = mute?.booleanValue == true
                Box(
                    Modifier.clip(PillShape).pointerInput(mute, muted) {
                        detectTapGestures { mute?.let { actions.set(device.id, it.id, JsonPrimitive(!muted)) } }
                    },
                ) {
                    SymIcon(if (muted) Sym.volumeOff else Sym.volumeDown, size = 22.dp, tint = g.muted)
                }
                GlassSlider(
                    value = fraction(volume) ?: 0f,
                    onValue = { actions.set(device.id, volume.id, JsonPrimitive(valueAt(volume, it))) },
                    modifier = Modifier.weight(1f),
                )
                SymIcon(Sym.volumeUp, size = 22.dp, tint = g.muted)
            }
        }
        if (large) {
            onoff?.takeIf { it.setable }?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (poweredOff) "Off" else "On", style = MaterialTheme.typography.bodyMedium, color = g.muted, modifier = Modifier.weight(1f))
                    RoundButton(Sym.powerSettingsNew, { actions.set(device.id, it.id, JsonPrimitive(poweredOff)) }, size = 44.dp, filledIcon = false)
                }
            }
        }
    }
}

@Composable
private fun TrackText(source: String, title: String, byline: String?) {
    val g = GlassTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(source.uppercase(), style = MaterialTheme.typography.labelMedium, color = g.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(title, style = MaterialTheme.typography.titleLarge, color = g.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
        byline?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = g.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun AlbumArt(art: ImageBitmap?, device: Device, modifier: Modifier) {
    val shape = RoundedCornerShape(20.dp)
    val placeholder = remember {
        Brush.linearGradient(listOf(Color(0xFFFF7A59), Color(0xFF8E5BFF), Color(0xFF2B3D8F)), Offset.Zero, Offset.Infinite)
    }
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(placeholder),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            SymIcon(deviceIcon(device), filled = true, size = 44.dp, tint = Color.White.copy(alpha = 0.85f))
        }
    }
}

/** Track position, ticking locally while playing between Homey's updates. */
@Composable
private fun Progress(device: Device, playing: Boolean) {
    val g = GlassTheme.colors
    val duration = device.capability("speaker_duration")?.numberValue?.takeIf { it > 0 } ?: return
    val reported = device.capability("speaker_position")?.numberValue ?: return
    val position by produceState(reported, reported, playing) {
        value = reported
        while (playing) {
            delay(1_000)
            value = (value + 1).coerceAtMost(duration)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(5.dp).clip(PillShape).background(g.glassLine)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth((position / duration).toFloat().coerceIn(0f, 1f)).background(g.fg, PillShape))
        }
        Row {
            Text(clock(position), style = MaterialTheme.typography.bodyMedium, color = g.muted, modifier = Modifier.weight(1f))
            Text("-" + clock(duration - position), style = MaterialTheme.typography.bodyMedium, color = g.muted)
        }
    }
}

private fun clock(seconds: Double): String {
    val s = seconds.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** A glass pill you drag or tap along; the value is sent when the finger lifts. */
@Composable
fun GlassSlider(value: Float, onValue: (Float) -> Unit, modifier: Modifier = Modifier) {
    val g = GlassTheme.colors
    var drag by remember { mutableStateOf<Float?>(null) }
    val current by rememberUpdatedState(value)
    val send by rememberUpdatedState(onValue)
    val shown = drag ?: value
    Box(
        modifier
            .height(34.dp)
            .glass(PillShape)
            .pointerInput(Unit) {
                detectTapGestures { offset -> send((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = current },
                    onDragEnd = { drag?.let(send); drag = null },
                    onDragCancel = { drag = null },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        drag = ((drag ?: current) + dx / size.width).coerceIn(0f, 1f)
                    },
                )
            },
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(shown)
                .background(Brush.horizontalGradient(listOf(g.glassTop, g.glassEdge.copy(alpha = if (g.isDark) 0.35f else 0.9f))), PillShape),
        )
    }
}

/** Loads the device's album art from the Homey, refetching when the track changes. */
@Composable
private fun rememberAlbumArt(device: Device, actions: DashboardActions, track: String?): ImageBitmap? {
    val image = device.images.firstOrNull { it.id.contains("album", ignoreCase = true) || it.id.contains("art", ignoreCase = true) }
        ?: device.images.firstOrNull()
    val url = image?.imageObj?.url
    val version = image?.imageObj?.lastUpdated
    val art by produceState<ImageBitmap?>(null, url, version, track) {
        value = url?.let { actions.loadImage(it, "$it|$version|$track") }
    }
    return art
}
