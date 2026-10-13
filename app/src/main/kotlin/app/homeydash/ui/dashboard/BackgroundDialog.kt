package app.homeydash.ui.dashboard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.homeydash.data.DisplaySettings
import app.homeydash.ui.components.BackgroundSpec
import app.homeydash.ui.components.DialogHeader
import app.homeydash.ui.components.GlassDialog
import app.homeydash.ui.components.HOME_BACKGROUND
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.WallPreset
import app.homeydash.ui.components.Wallpaper
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme

private data class Choice(val label: String, val spec: String?, val preset: WallPreset?)

/** Choose a background for the home or one room: a preset, a photo, or (rooms) the home's. */
@Composable
fun BackgroundDialog(
    key: String,
    title: String,
    current: String?,
    display: DisplaySettings,
    onError: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val g = GlassTheme.colors
    val isHome = key == HOME_BACKGROUND
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            display.importPhoto(key, uri, onError)
            onDismiss()
        }
    }
    val choices = buildList {
        if (isHome) add(Choice("Classic", null, null)) else {
            add(Choice("Same as Home", null, null))
            add(Choice("Classic", BackgroundSpec.CLASSIC, null))
        }
        WallPreset.entries.forEach { add(Choice(it.label, BackgroundSpec.preset(it), it)) }
    }
    val isPhoto = BackgroundSpec.photoVersion(current) != null

    GlassDialog(onDismiss = onDismiss, width = 700.dp) {
        DialogHeader(title = "Background", subtitle = title, onClose = onDismiss)
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.height(360.dp),
        ) {
            items(choices) { choice ->
                Swatch(choice.label, selected = current == choice.spec && !isPhoto, onClick = {
                    display.setBackground(key, choice.spec)
                }) {
                    if (choice.spec == null && !isHome) {
                        Box(Modifier.fillMaxSize().glass(RoundedCornerShape(0.dp)), contentAlignment = Alignment.Center) {
                            SymIcon(Sym.home, size = 28.dp, tint = g.muted)
                        }
                    } else {
                        Wallpaper(palette = choice.preset?.let { if (g.isDark) it.dark else it.light })
                    }
                }
            }
            item {
                Swatch("Photo…", selected = isPhoto, onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Box(Modifier.fillMaxSize().glass(RoundedCornerShape(0.dp)), contentAlignment = Alignment.Center) {
                        SymIcon(Sym.image, filled = isPhoto, size = 30.dp, tint = g.fg)
                    }
                }
            }
        }
        Text(
            "Photos are softened so the cards stay easy to read.",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
    }
}

@Composable
private fun Swatch(label: String, selected: Boolean, onClick: () -> Unit, preview: @Composable () -> Unit) {
    val g = GlassTheme.colors
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.pressable(onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(shape)
                .border(if (selected) 3.dp else 1.dp, if (selected) g.fg else g.glassLine, shape),
        ) {
            preview()
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).border(0.dp, g.fg, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    SymIcon(Sym.check, filled = true, size = 22.dp, tint = g.fg)
                }
            }
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = g.fg, maxLines = 1)
    }
}
