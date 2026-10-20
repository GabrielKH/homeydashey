package app.homeydash.ui.dashboard

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.homeydash.homey.Device
import app.homeydash.ui.components.Ic
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.TileShape
import app.homeydash.ui.components.deviceIcon
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive

private val Ink = Color.White
private val InkSoft = Color.White.copy(alpha = 0.68f)
private val InkFaint = Color.White.copy(alpha = 0.24f)

/**
 * Now-playing card in the style of Apple Music: the album art, blurred, fills the
 * card behind white text, with plain transport icons and thin bars. [large] stacks
 * it around big art for the Media page. Shows a remote button for TV boxes with remote
 * keys (Apple TV), and music and group buttons for speakers that can play
 * favourites and join groups (Sonos).
 */
@Composable
fun MediaPlayerCard(device: Device, actions: DashboardActions, modifier: Modifier = Modifier, large: Boolean = false, subtitle: String? = null) {
    val playing = device.capability("speaker_playing")?.booleanValue == true
    val track = device.capability("speaker_track")?.stringValue?.takeIf { it.isNotBlank() }
    val artist = device.capability("speaker_artist")?.stringValue?.takeIf { it.isNotBlank() }
    val album = device.capability("speaker_album")?.stringValue?.takeIf { it.isNotBlank() }
    val onoff = device.capability("onoff")
    val poweredOff = onoff?.booleanValue == false
    val art = rememberAlbumArt(device, actions, track)
    val hasRemote = actions.hasRemote(device)
    val hasMusic = actions.musicSources(device.id).isNotEmpty()
    val canGroup = actions.grouping(device.id) != null

    val source = listOfNotNull(subtitle, device.name).joinToString(" · ")
    val title = track ?: if (poweredOff) "Off" else if (playing) "Playing" else "Not playing"
    val byline = listOfNotNull(artist, album.takeIf { large }).joinToString(" · ").ifEmpty { null }

    Box(
        modifier
            .fillMaxWidth()
            .clip(TileShape),
    ) {
        PlayerBackground(art?.blurred, Modifier.matchParentSize())
        Column(
            Modifier
                .then(if (large) Modifier else Modifier.heightIn(min = TileHeight * 2 + 14.dp))
                .padding(20.dp),
            // In the grid the card is two tiles tall; spare height goes evenly above and below.
            verticalArrangement = Arrangement.spacedBy(if (large) 18.dp else 14.dp, Alignment.CenterVertically),
        ) {
            if (large) {
                AlbumArt(art?.sharp, device, Modifier.fillMaxWidth(), corner = 22.dp)
                TrackText(source, title, byline, large = true)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AlbumArt(art?.sharp, device, Modifier.size(92.dp), corner = 14.dp)
                    Box(Modifier.weight(1f)) { TrackText(source, title, byline, large = false) }
                }
            }
            Progress(device, playing)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val prev = device.capability("speaker_prev")?.setable == true
                val next = device.capability("speaker_next")?.setable == true
                val play = device.capability("speaker_playing")?.setable == true
                Transport(Sym.skipPrevious, 34.dp, prev) { actions.set(device.id, "speaker_prev", JsonPrimitive(true)) }
                Transport(if (playing) Sym.pause else Sym.playArrow, if (large) 60.dp else 52.dp, play) {
                    actions.set(device.id, "speaker_playing", JsonPrimitive(!playing))
                }
                Transport(Sym.skipNext, 34.dp, next) { actions.set(device.id, "speaker_next", JsonPrimitive(true)) }
            }
            device.capability("volume_set")?.takeIf { it.setable }?.let { volume ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val mute = device.capability("volume_mute")?.takeIf { it.setable }
                    val muted = mute?.booleanValue == true
                    SymIcon(
                        if (muted) Sym.volumeOff else Sym.volumeDown,
                        filled = true,
                        size = 20.dp,
                        tint = InkSoft,
                        modifier = Modifier.pressable(mute?.let { { actions.set(device.id, it.id, JsonPrimitive(!muted)) } }),
                    )
                    ThinSlider(
                        value = fraction(volume) ?: 0f,
                        onValue = { actions.set(device.id, volume.id, JsonPrimitive(valueAt(volume, it))) },
                        modifier = Modifier.weight(1f),
                    )
                    SymIcon(Sym.volumeUp, filled = true, size = 20.dp, tint = InkSoft)
                }
            }
            val power = onoff?.takeIf { it.setable }
            if (hasRemote || hasMusic || canGroup || power != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (hasRemote) PlayerPill(Sym.remote, "Remote") { actions.open(Sheet.Remote(device.id)) }
                    if (hasMusic) PlayerPill(Sym.musicNotes, "Music") { actions.open(Sheet.Music(device.zone, device.id)) }
                    if (canGroup) PlayerPill(Sym.speakerHifi, "Group") { actions.open(Sheet.Group(device.id)) }
                    Spacer(Modifier.weight(1f))
                    power?.let {
                        Box(
                            Modifier
                                .size(40.dp)
                                .pressable({ actions.power(device) })
                                .background(if (poweredOff) InkFaint else Ink.copy(alpha = 0.9f), PillShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            SymIcon(Sym.power, size = 20.dp, tint = if (poweredOff) Ink else Color(0xFF15161C))
                        }
                    }
                }
            }
        }
    }
}

/** The blurred art stretched over the card under a dark scrim; a deep gradient when there is no art. */
@Composable
private fun PlayerBackground(blurred: ImageBitmap?, modifier: Modifier) {
    val g = GlassTheme.colors
    val fallback = remember(g) { Brush.linearGradient(listOf(Color(0xFF2B2F52), g.onDeep.copy(alpha = 0.55f).compositeOverDark(), Color(0xFF14152A)), Offset.Zero, Offset.Infinite) }
    val scrim = remember { Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.18f), Color.Black.copy(alpha = 0.42f))) }
    Box(modifier.background(fallback)) {
        if (blurred != null) {
            Image(blurred, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(scrim).border(1.dp, Color.White.copy(alpha = 0.12f), TileShape))
    }
}

private fun Color.compositeOverDark(): Color {
    val base = Color(0xFF1B1D33)
    return Color(
        red = red * alpha + base.red * (1 - alpha),
        green = green * alpha + base.green * (1 - alpha),
        blue = blue * alpha + base.blue * (1 - alpha),
    )
}

@Composable
private fun Transport(icon: Ic, size: Dp, enabled: Boolean, onClick: () -> Unit) {
    SymIcon(
        icon,
        filled = true,
        size = size,
        tint = if (enabled) Ink else InkFaint,
        modifier = Modifier.pressable(onClick.takeIf { enabled }).padding(6.dp),
    )
}

@Composable
private fun PlayerPill(icon: Ic, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .pressable(onClick)
            .background(Color.White.copy(alpha = 0.16f), PillShape)
            .border(1.dp, Color.White.copy(alpha = 0.18f), PillShape)
            .padding(start = 12.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SymIcon(icon, filled = true, size = 18.dp, tint = Ink)
        Text(label, style = MaterialTheme.typography.labelLarge, color = Ink)
    }
}

@Composable
private fun TrackText(source: String, title: String, byline: String?, large: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(source.uppercase(), style = MaterialTheme.typography.labelMedium, color = InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            title,
            style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
            color = Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        byline?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun AlbumArt(art: ImageBitmap?, device: Device, modifier: Modifier, corner: Dp) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            SymIcon(deviceIcon(device), filled = true, size = 40.dp, tint = InkSoft)
        }
    }
}

/** Track position, ticking locally while playing between Homey's updates. */
@Composable
private fun Progress(device: Device, playing: Boolean) {
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
        Box(Modifier.fillMaxWidth().height(5.dp).clip(PillShape).background(InkFaint)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth((position / duration).toFloat().coerceIn(0f, 1f)).background(Ink, PillShape))
        }
        Row {
            Text(clock(position), style = MaterialTheme.typography.bodyMedium, color = InkSoft, modifier = Modifier.weight(1f))
            Text("-" + clock(duration - position), style = MaterialTheme.typography.bodyMedium, color = InkSoft)
        }
    }
}

private fun clock(seconds: Double): String {
    val s = seconds.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** iOS-style thin bar that thickens under the finger; the value is sent when it lifts. */
@Composable
fun ThinSlider(value: Float, onValue: (Float) -> Unit, modifier: Modifier = Modifier, fill: Color = Ink, track: Color = InkFaint) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val current by rememberUpdatedState(value)
    val send by rememberUpdatedState(onValue)
    val shown = drag ?: value
    val thickness by animateDpAsState(if (drag != null) 12.dp else 6.dp, label = "thickness")
    Box(
        modifier
            .height(32.dp)
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
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(thickness).clip(PillShape).background(track)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(shown).background(fill, PillShape))
        }
    }
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
private fun rememberAlbumArt(device: Device, actions: DashboardActions, track: String?): AlbumArtImages? {
    val image = device.images.firstOrNull { it.id.contains("album", ignoreCase = true) || it.id.contains("art", ignoreCase = true) }
        ?: device.images.firstOrNull()
    val url = image?.imageObj?.url
    val version = image?.imageObj?.lastUpdated
    val art by produceState<AlbumArtImages?>(null, url, version, track) {
        value = url?.let { actions.loadArt(it, "$it|$version|$track") }
    }
    return art
}

/**
 * The Media page's one big player: large album art beside the track, big transport
 * buttons, volume and the player's extras, all over the blurred art.
 */
@Composable
fun MediaHero(device: Device, actions: DashboardActions, modifier: Modifier = Modifier, subtitle: String? = null) {
    val playing = device.capability("speaker_playing")?.booleanValue == true
    val track = device.capability("speaker_track")?.stringValue?.takeIf { it.isNotBlank() }
    val artist = device.capability("speaker_artist")?.stringValue?.takeIf { it.isNotBlank() }
    val album = device.capability("speaker_album")?.stringValue?.takeIf { it.isNotBlank() }
    val onoff = device.capability("onoff")
    val poweredOff = onoff?.booleanValue == false
    val art = rememberAlbumArt(device, actions, track)
    val hasRemote = actions.hasRemote(device)
    val hasMusic = actions.musicSources(device.id).isNotEmpty()
    val canGroup = actions.grouping(device.id) != null

    Box(modifier.clip(TileShape)) {
        PlayerBackground(art?.blurred, Modifier.matchParentSize())
        Row(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(0.46f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                AlbumArt(art?.sharp, device, Modifier, corner = 26.dp)
            }
            Column(Modifier.weight(0.54f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        listOfNotNull(subtitle, device.name).joinToString(" · ").uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = InkSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (playing) PlayingBars(Ink)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        track ?: if (poweredOff) "Off" else if (playing) "Playing" else "Not playing",
                        style = MaterialTheme.typography.displaySmall,
                        color = Ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    artist?.let { Text(it, style = MaterialTheme.typography.titleLarge, color = InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    album?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Progress(device, playing)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val prev = device.capability("speaker_prev")?.setable == true
                    val next = device.capability("speaker_next")?.setable == true
                    val play = device.capability("speaker_playing")?.setable == true
                    Transport(Sym.skipPrevious, 46.dp, prev) { actions.set(device.id, "speaker_prev", JsonPrimitive(true)) }
                    Transport(if (playing) Sym.pause else Sym.playArrow, 84.dp, play) {
                        actions.set(device.id, "speaker_playing", JsonPrimitive(!playing))
                    }
                    Transport(Sym.skipNext, 46.dp, next) { actions.set(device.id, "speaker_next", JsonPrimitive(true)) }
                }
                device.capability("volume_set")?.takeIf { it.setable }?.let { volume ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        val mute = device.capability("volume_mute")?.takeIf { it.setable }
                        val muted = mute?.booleanValue == true
                        SymIcon(
                            if (muted) Sym.volumeOff else Sym.volumeDown,
                            filled = true,
                            size = 24.dp,
                            tint = InkSoft,
                            modifier = Modifier.pressable(mute?.let { { actions.set(device.id, it.id, JsonPrimitive(!muted)) } }),
                        )
                        ThinSlider(
                            value = fraction(volume) ?: 0f,
                            onValue = { actions.set(device.id, volume.id, JsonPrimitive(valueAt(volume, it))) },
                            modifier = Modifier.weight(1f),
                        )
                        SymIcon(Sym.volumeUp, filled = true, size = 24.dp, tint = InkSoft)
                    }
                }
                val power = onoff?.takeIf { it.setable }
                if (hasRemote || hasMusic || canGroup || power != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (hasRemote) PlayerPill(Sym.remote, "Remote") { actions.open(Sheet.Remote(device.id)) }
                        if (hasMusic) PlayerPill(Sym.musicNotes, "Music") { actions.open(Sheet.Music(device.zone, device.id)) }
                        if (canGroup) PlayerPill(Sym.speakerHifi, "Group") { actions.open(Sheet.Group(device.id)) }
                        Spacer(Modifier.weight(1f))
                        power?.let {
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .pressable({ actions.power(device) })
                                    .background(if (poweredOff) InkFaint else Ink.copy(alpha = 0.9f), PillShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                SymIcon(Sym.power, size = 22.dp, tint = if (poweredOff) Ink else Color(0xFF15161C))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A player in the Media page's list: its art, what it plays, and play or pause. */
@Composable
fun PlayerRow(device: Device, actions: DashboardActions, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    val g = GlassTheme.colors
    val playing = device.capability("speaker_playing")?.booleanValue == true
    val track = device.capability("speaker_track")?.stringValue?.takeIf { it.isNotBlank() }
    val artist = device.capability("speaker_artist")?.stringValue?.takeIf { it.isNotBlank() }
    val art = rememberAlbumArt(device, actions, track)
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick)
            .then(if (selected) Modifier.glass(shape) else Modifier)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)).glass(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val sharp = art?.sharp
            if (sharp != null) {
                Image(sharp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                SymIcon(deviceIcon(device), filled = true, size = 24.dp, tint = g.muted)
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(device.name, style = MaterialTheme.typography.titleSmall, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (playing) PlayingBars(g.fg)
            }
            Text(
                track?.let { listOfNotNull(it, artist).joinToString(" – ") } ?: "Not playing",
                style = MaterialTheme.typography.bodyMedium,
                color = if (track != null) g.fg.copy(alpha = 0.85f) else g.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = g.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (device.capability("speaker_playing")?.setable == true) {
            Box(
                Modifier
                    .size(40.dp)
                    .pressable({ actions.set(device.id, "speaker_playing", JsonPrimitive(!playing)) })
                    .glass(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                SymIcon(if (playing) Sym.pause else Sym.playArrow, filled = true, size = 18.dp, tint = g.fg)
            }
        }
    }
}

/** Three bouncing bars, the "now playing" sign. */
@Composable
fun PlayingBars(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "bars")
    val heights = listOf(0, 180, 360).map { delayMs ->
        transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(520, delayMillis = delayMs), RepeatMode.Reverse),
            label = "bar",
        )
    }
    Row(modifier.height(14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        heights.forEach { h ->
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .graphicsLayer {
                        scaleY = h.value
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .background(color, PillShape),
            )
        }
    }
}
