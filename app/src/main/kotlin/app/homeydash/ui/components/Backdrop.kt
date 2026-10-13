package app.homeydash.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import app.homeydash.data.BackgroundPhotos
import app.homeydash.ui.theme.GlassTheme

/** Ready-made wallpapers, each with a dark and a light version. */
enum class WallPreset(val label: String, val dark: WallPalette, val light: WallPalette) {
    Aurora(
        "Aurora",
        WallPalette(Color(0xFF0B1220), Color(0xFF1E6F7A), Color(0xFF5B3FA8), Color(0xFF2A8F5F)),
        WallPalette(Color(0xFFEFF5F6), Color(0xFF9EE6E0), Color(0xFFCDBDFF), Color(0xFFB5EBC8)),
    ),
    Dusk(
        "Dusk",
        WallPalette(Color(0xFF140E1E), Color(0xFF7A3B5C), Color(0xFFB0603A), Color(0xFF3A2D7A)),
        WallPalette(Color(0xFFF7EFF0), Color(0xFFFFC2B0), Color(0xFFF5B8D2), Color(0xFFC9C2FF)),
    ),
    Ocean(
        "Ocean",
        WallPalette(Color(0xFF07121F), Color(0xFF0F4C81), Color(0xFF1C7C9C), Color(0xFF233A8F)),
        WallPalette(Color(0xFFEEF4FA), Color(0xFFA6CCF5), Color(0xFF9FE0EE), Color(0xFFB9C3F7)),
    ),
    Forest(
        "Forest",
        WallPalette(Color(0xFF0B140F), Color(0xFF2F5D3A), Color(0xFF5E7A2E), Color(0xFF1D5E57)),
        WallPalette(Color(0xFFF1F5EE), Color(0xFFBFDDB0), Color(0xFFDDE7A6), Color(0xFFA9DCCF)),
    ),
    Rose(
        "Rose",
        WallPalette(Color(0xFF1A0D14), Color(0xFF8A2F55), Color(0xFF5E2B7A), Color(0xFFA0485A)),
        WallPalette(Color(0xFFFBF0F3), Color(0xFFFFC1D4), Color(0xFFE3C4FF), Color(0xFFFFD0C2)),
    ),
    Graphite(
        "Graphite",
        WallPalette(Color(0xFF0E0F12), Color(0xFF2C3038), Color(0xFF3A3F4A), Color(0xFF22262E)),
        WallPalette(Color(0xFFF2F3F5), Color(0xFFD9DCE2), Color(0xFFE6E8EC), Color(0xFFCDD1D8)),
    ),
}

/** A saved background: `preset:<name>` or `photo:<version>`; null is the classic wallpaper. */
object BackgroundSpec {
    /** The classic wallpaper chosen for a room on purpose, rather than following the home. */
    const val CLASSIC = "classic"
    fun preset(preset: WallPreset) = "preset:${preset.name}"
    fun presetOf(spec: String?): WallPreset? =
        spec?.takeIf { it.startsWith("preset:") }?.removePrefix("preset:")?.let { name -> WallPreset.entries.firstOrNull { it.name == name } }
    fun photoVersion(spec: String?): String? = spec?.takeIf { it.startsWith("photo:") }?.removePrefix("photo:")
}

/** Home and each room can have their own background; a room without one uses the home's. */
fun backgroundFor(backgrounds: Map<String, String>, zoneId: String?): Pair<String, String?> {
    val own = zoneId?.let { backgrounds[it] }
    return if (own != null) zoneId to own else HOME_BACKGROUND to backgrounds[HOME_BACKGROUND]
}

const val HOME_BACKGROUND = "home"

/** Draws a background: a preset wallpaper, or a softened photo under a scrim so text stays readable. */
@Composable
fun Backdrop(key: String, spec: String?, photos: BackgroundPhotos, modifier: Modifier = Modifier) {
    val g = GlassTheme.colors
    val version = BackgroundSpec.photoVersion(spec)
    Box(modifier.fillMaxSize()) {
        val preset = BackgroundSpec.presetOf(spec)
        Wallpaper(palette = preset?.let { if (g.isDark) it.dark else it.light })
        if (version != null) {
            val photo by produceState<ImageBitmap?>(null, key, version) {
                value = photos.load(key, version)?.asImageBitmap()
            }
            photo?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            val scrim = remember(g.isDark) {
                val base = if (g.isDark) Color.Black else Color.White
                Brush.verticalGradient(listOf(base.copy(alpha = 0.42f), base.copy(alpha = 0.22f), base.copy(alpha = 0.48f)))
            }
            Box(Modifier.fillMaxSize().background(scrim))
        }
    }
}
