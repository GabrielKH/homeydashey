package app.homeydash.ui.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.homeydash.homey.AutocompleteItem
import app.homeydash.homey.DashboardLayout
import app.homeydash.homey.Device
import app.homeydash.homey.FlowCards
import app.homeydash.homey.MusicSource
import app.homeydash.homey.Zone
import app.homeydash.ui.components.DialogHeader
import app.homeydash.ui.components.GlassDialog
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.onBrush
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme
import kotlinx.coroutines.delay
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Speakers whose apps can play something picked from a list (Sonos favourites, playlists…). */
fun musicSpeakers(devices: Collection<Device>, actions: DashboardActions): List<Device> =
    devices.filter { actions.musicSources(it.id).isNotEmpty() }.sortedBy { it.name.lowercase() }

/**
 * Pick an album, playlist or favourite and a speaker to play it on. Opens from
 * any room; the room's own speaker is picked first.
 */
@Composable
fun MusicSheet(
    zoneId: String?,
    preferredDevice: String?,
    speakers: List<Device>,
    zones: Map<String, Zone>,
    actions: DashboardActions,
    onDismiss: () -> Unit,
) {
    val g = GlassTheme.colors
    val inRoom = remember(zones, zoneId) { zoneId?.let { DashboardLayout.subtree(zones, it) }.orEmpty() }
    var targetId by rememberSaveable {
        mutableStateOf(
            preferredDevice?.takeIf { id -> speakers.any { it.id == id } }
                ?: speakers.firstOrNull { it.zone in inRoom }?.id
                ?: speakers.firstOrNull { it.capability("speaker_playing")?.booleanValue == true }?.id
                ?: speakers.firstOrNull()?.id,
        )
    }
    val target = speakers.firstOrNull { it.id == targetId }

    GlassDialog(onDismiss = onDismiss, width = 860.dp) {
        DialogHeader(
            title = "Play music",
            subtitle = target?.let { t -> "On ${listOfNotNull(t.name, t.zone?.let(zones::get)?.name).joinToString(" · ")}" },
            onClose = onDismiss,
        )
        if (target == null) {
            Text(
                "No speaker here can play favourites or playlists yet. Sonos speakers on the same Wi-Fi as the tablet show up here by themselves.",
                style = MaterialTheme.typography.bodyLarge,
                color = g.muted,
            )
            return@GlassDialog
        }
        if (speakers.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(speakers, key = { it.id }) { speaker ->
                    val on = speaker.id == target.id
                    Row(
                        Modifier
                            .pressable({ targetId = speaker.id })
                            .then(if (on) Modifier.background(onBrush(), PillShape) else Modifier.glass(PillShape))
                            .padding(start = 12.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val ink = if (on) g.onInk else g.fg
                        SymIcon(Sym.speakerHifi, filled = on, size = 18.dp, tint = ink)
                        Text(
                            listOfNotNull(speaker.zone?.let(zones::get)?.name, speaker.name).distinct().joinToString(" · "),
                            style = MaterialTheme.typography.labelLarge,
                            color = ink,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        val sources = actions.musicSources(target.id)
        // The tab is kept by its name, so picking another speaker stays on Spotify (or wherever you were).
        var tabTitle by rememberSaveable { mutableStateOf<String?>(null) }
        val tab = sources.indexOfFirst { it.title == tabTitle }.coerceAtLeast(0)
        val source = sources[tab]
        var query by remember(source.title) { mutableStateOf("") }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (sources.size > 1) Segmented(sources.map { it.title }, tab, { tabTitle = sources[it].title })
            if (source.arg.type == "autocomplete") SearchField(query, { query = it }, Modifier.weight(1f))
        }

        // Favourites and playlists belong to the whole household, the same on every speaker of an
        // app, so what was listed once shows again at once on another speaker instead of reloading.
        val listed = remember { mutableStateMapOf<String, List<Pair<MusicSource, AutocompleteItem>>>() }
        val listKey = "${source.card.ownerUri}|${source.title}|$query"
        // Each item remembers the card that listed it, since a tab can fall back to a twin's card;
        // a list from another speaker plays through this speaker's own card of that kind.
        fun here(list: List<Pair<MusicSource, AutocompleteItem>>?) = list?.map { (from, item) ->
            (source.all.firstOrNull { it.card.cardId == from.card.cardId && it.card.ownerUri == from.card.ownerUri } ?: source) to item
        }
        val results by produceState(here(listed[listKey]), target.id, source.card.id, query) {
            val known = here(listed[listKey])
            value = known
            if (known != null) return@produceState
            if (query.isNotEmpty()) delay(350)
            value = actions.musicItems(source, query).also { listed[listKey] = it }
        }
        // Spotify's library, before anything is typed, comes in groups: playlists, albums, podcasts, liked songs.
        val groups = if (source.catalogue && query.isBlank()) results.orEmpty().map { libraryGroup(it.second) }.distinct() else emptyList()
        var groupPicked by rememberSaveable { mutableStateOf<String?>(null) }
        val group = groupPicked?.takeIf { it in groups } ?: groups.firstOrNull()
        if (groups.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups) { name ->
                    val on = name == group
                    Text(
                        name,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) g.onInk else g.fg,
                        maxLines = 1,
                        modifier = Modifier
                            .pressable({ groupPicked = name })
                            .then(if (on) Modifier.background(onBrush(), PillShape) else Modifier.glass(PillShape))
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                }
            }
        }
        // Read once here: the grid builds its items later, when the list may already be reloading.
        val list = results?.let { all -> if (group == null) all else all.filter { libraryGroup(it.second) == group } }
        Box(Modifier.fillMaxWidth().height(440.dp), contentAlignment = Alignment.Center) {
            when {
                list == null -> CircularProgressIndicator(color = g.fg)
                list.isEmpty() -> Text(
                    when {
                        query.isNotBlank() -> "Nothing matches \"$query\"."
                        source.catalogue -> "Search for any song, album, artist, playlist or podcast."
                        else -> "Nothing in ${source.title.lowercase()} yet."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = g.muted,
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(list) { (from, item) ->
                        MusicItem(item, actions) {
                            actions.run(from.card, FlowCards.musicArgs(from, item))
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

/** The group a Spotify library item is shown under in Play music. */
private fun libraryGroup(item: AutocompleteItem): String = when (item.raw["kind"]?.jsonPrimitive?.contentOrNull) {
    "Playlist" -> "Playlists"
    "Album" -> "Albums"
    "Show" -> "Podcasts"
    "Episode" -> "Episodes"
    "Artist" -> "Artists"
    else -> "Liked songs"
}

@Composable
private fun MusicItem(item: AutocompleteItem, actions: DashboardActions, onClick: () -> Unit) {
    val g = GlassTheme.colors
    val art by produceState<ImageBitmap?>(null, item.image) {
        value = item.image?.let { actions.loadImage(it, it) }
    }
    val placeholder = remember(item.name) {
        val hue = (item.name.hashCode() and 0xFFFF) % 360f
        Brush.linearGradient(listOf(Color.hsv(hue, 0.45f, 0.85f), Color.hsv((hue + 40f) % 360f, 0.6f, 0.5f)), Offset.Zero, Offset.Infinite)
    }
    Column(Modifier.pressable(onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(placeholder),
            contentAlignment = Alignment.Center,
        ) {
            val image = art
            if (image != null) {
                Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                SymIcon(Sym.vinyl, filled = true, size = 44.dp, tint = Color.White.copy(alpha = 0.85f))
            }
        }
        Text(item.name, style = MaterialTheme.typography.titleSmall, color = g.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
        item.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = g.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun SearchField(value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    val g = GlassTheme.colors
    Row(
        modifier.glass(PillShape).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SymIcon(Sym.search, size = 20.dp, tint = g.muted)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Search", style = MaterialTheme.typography.bodyLarge, color = g.muted)
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = g.fg),
                cursorBrush = SolidColor(g.fg),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            SymIcon(Sym.close, size = 18.dp, tint = g.muted, modifier = Modifier.pressable({ onValue("") }))
        }
    }
}

