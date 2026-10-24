package app.homeydash.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.homeydash.R

/** Light, dark, or follow the tablet's setting. */
enum class ThemeMode { System, Light, Dark }

/**
 * The glass palette. Glass is faked cheaply for the Helio G100: a wallpaper of
 * soft colour fields drawn once, and translucent tinted panels with a bright top
 * edge on top of it. Nothing is blurred live.
 */
@Immutable
data class GlassColors(
    val isDark: Boolean,
    val wallBase: Color,
    val wallA: Color,
    val wallB: Color,
    val wallC: Color,
    val glassTop: Color,
    val glassBottom: Color,
    /** Hairline around every panel. */
    val glassLine: Color,
    /** Specular highlight along a panel's top edge. */
    val glassEdge: Color,
    val fg: Color,
    val muted: Color,
    /** Warm "on" colour for lights and the active mood. */
    val on: Color,
    val onDeep: Color,
    /** Text and icons drawn on top of [on]. */
    val onInk: Color,
    val onSoft: Color,
    val cool: Color,
    val ok: Color,
    val danger: Color,
    /** Flat colours: solid panels and single-colour highlights instead of glass and gradients. */
    val flat: Boolean = false,
    /** The solid panel colour in [flat] style. */
    val flatPanel: Color = Color.Unspecified,
)

private val Light = GlassColors(
    isDark = false,
    wallBase = Color(0xFFEEF1F7),
    wallA = Color(0xFF9FC3FF),
    wallB = Color(0xFFFFC9A8),
    wallC = Color(0xFFD8C4FF),
    glassTop = Color(0x9EFFFFFF),
    glassBottom = Color(0x5CFFFFFF),
    glassLine = Color(0x1F1E233C),
    glassEdge = Color(0xF2FFFFFF),
    fg = Color(0xFF14161C),
    muted = Color(0xFF575C6C),
    on = Color(0xFFFFB02E),
    onDeep = Color(0xFFFF8A3D),
    onInk = Color(0xFF2A1800),
    onSoft = Color(0x47FFB02E),
    cool = Color(0xFF2F7BEF),
    ok = Color(0xFF1A9A52),
    danger = Color(0xFFDC3E43),
)

private val Dark = GlassColors(
    isDark = true,
    wallBase = Color(0xFF0D1020),
    wallA = Color(0xFF2B3D8F),
    wallB = Color(0xFF7A3B5C),
    wallC = Color(0xFF13606A),
    glassTop = Color(0x1FFFFFFF),
    glassBottom = Color(0x0DFFFFFF),
    glassLine = Color(0x1FFFFFFF),
    glassEdge = Color(0x52FFFFFF),
    fg = Color(0xFFF4F5FA),
    muted = Color(0xFFADB2C4),
    on = Color(0xFFFFC14D),
    onDeep = Color(0xFFFF8A3D),
    onInk = Color(0xFF2A1800),
    onSoft = Color(0x38FFC14D),
    cool = Color(0xFF6AA8FF),
    ok = Color(0xFF4CD07D),
    danger = Color(0xFFFF6B6B),
)

val LocalGlass = staticCompositionLocalOf { Light }

/**
 * Inter: the closest open typeface to Apple's SF Pro (which can't ship on Android),
 * drawn for screens, with tabular figures for the clock and readings.
 */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)

/** Caveat (OFL): a friendly handwriting for the post-its. */
val Handwriting = FontFamily(
    Font(R.font.caveat_medium, FontWeight.Medium),
    Font(R.font.caveat_bold, FontWeight.Bold),
)

// Large type throughout: the tablet is read from across the kitchen on a matte screen.
private val GlassTypography = Typography(
    displayLarge = TextStyle(fontFamily = Inter, fontSize = 80.sp, lineHeight = 82.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-3).sp),
    headlineLarge = TextStyle(fontFamily = Inter, fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontFamily = Inter, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    titleLarge = TextStyle(fontFamily = Inter, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = Inter, fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleSmall = TextStyle(fontFamily = Inter, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    labelLarge = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp),
)

/** The colours of the whole app: the wallpaper's colour fields and the panels' tint, light and dark. */
enum class Palette(
    val key: String,
    val label: String,
    val light: List<Color>,
    val dark: List<Color>,
    val lightPanel: Color,
    val darkPanel: Color,
) {
    Aurora("aurora", "Aurora", listOf(Color(0xFFEEF1F7), Color(0xFF9FC3FF), Color(0xFFFFC9A8), Color(0xFFD8C4FF)), listOf(Color(0xFF0D1020), Color(0xFF2B3D8F), Color(0xFF7A3B5C), Color(0xFF13606A)), Color(0xFFF7F8FC), Color(0xFF1B1F31)),
    Ocean("ocean", "Ocean", listOf(Color(0xFFEAF3FA), Color(0xFF8EC9FF), Color(0xFF9EE7E0), Color(0xFFB7C8FF)), listOf(Color(0xFF08131F), Color(0xFF0F3E6B), Color(0xFF0F5A5A), Color(0xFF1C2C6B)), Color(0xFFF4F9FD), Color(0xFF13202E)),
    Forest("forest", "Forest", listOf(Color(0xFFEEF3EC), Color(0xFFA9D8A2), Color(0xFFE8E1A8), Color(0xFF9FD1C0)), listOf(Color(0xFF0B140F), Color(0xFF1F4D33), Color(0xFF4A4A1F), Color(0xFF0F4A44)), Color(0xFFF6F9F4), Color(0xFF16211A)),
    Sunset("sunset", "Sunset", listOf(Color(0xFFFBF0EA), Color(0xFFFFC09E), Color(0xFFFF9EB5), Color(0xFFFFD9A0)), listOf(Color(0xFF1A0E14), Color(0xFF6B2A3F), Color(0xFF7A4220), Color(0xFF3D1F5C)), Color(0xFFFDF7F3), Color(0xFF261A20)),
    Lilac("lilac", "Lilac", listOf(Color(0xFFF3F0FA), Color(0xFFC9B8FF), Color(0xFFF0C2E6), Color(0xFFB8D0FF)), listOf(Color(0xFF120F20), Color(0xFF3D2B7A), Color(0xFF5C2B5C), Color(0xFF22306B)), Color(0xFFF9F7FD), Color(0xFF1E1A2E)),
    Graphite("graphite", "Graphite", listOf(Color(0xFFF2F2F4), Color(0xFFD9DCE3), Color(0xFFE8E8EC), Color(0xFFCED2DA)), listOf(Color(0xFF101113), Color(0xFF2A2D33), Color(0xFF22252B), Color(0xFF30343B)), Color(0xFFFAFAFB), Color(0xFF1C1E22)),
    ;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Aurora
    }
}

/** The highlight for things that are on: a two-colour gradient for lights, the active mood, toggles. */
enum class Accent(val label: String, val top: Color, val deep: Color, val ink: Color) {
    Lavender("Lavender", Color(0xFFB8C2FF), Color(0xFF8B7BFF), Color(0xFF14123A)),
    Sky("Sky", Color(0xFF9EDBFF), Color(0xFF4F8DFF), Color(0xFF06223D)),
    Aqua("Aqua", Color(0xFF8EF1F1), Color(0xFF8E9BFF), Color(0xFF07253A)),
    Mint("Mint", Color(0xFFB2F5D9), Color(0xFF3CCB9A), Color(0xFF05301F)),
    Lime("Lime", Color(0xFFE4F79A), Color(0xFF8BD45C), Color(0xFF1C2E05)),
    Rose("Rose", Color(0xFFFFC2D2), Color(0xFFFF7EA1), Color(0xFF3D0817)),
    Sunset("Sunset", Color(0xFFFFC98B), Color(0xFFFF7E9B), Color(0xFF3A0D12)),
    Warm("Warm white", Color(0xFFFFF4DE), Color(0xFFFFD39A), Color(0xFF3A2606)),
    Frost("Frost", Color(0xFFFFFFFF), Color(0xFFCBD3E1), Color(0xFF15181F)),
}

@Composable
fun HomeyDashTheme(
    mode: ThemeMode = ThemeMode.System,
    accent: Accent = Accent.Lavender,
    /** How solid the highlight is, 0.3–1. */
    accentOpacity: Float = 1f,
    palette: Palette = Palette.Aurora,
    flat: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val alpha = accentOpacity.coerceIn(0.3f, 1f)
    val walls = if (dark) palette.dark else palette.light
    val glass = (if (dark) Dark else Light).copy(
        wallBase = walls[0],
        wallA = walls[1],
        wallB = walls[2],
        wallC = walls[3],
        flat = flat,
        flatPanel = if (dark) palette.darkPanel else palette.lightPanel,
        on = accent.top.copy(alpha = alpha),
        onDeep = accent.deep.copy(alpha = alpha),
        // See-through highlights let the glass show, so their ink follows the theme.
        onInk = if (alpha < 0.65f) (if (dark) Color.White else Color(0xFF14161C)) else accent.ink,
        onSoft = accent.deep.copy(alpha = (if (dark) 0.30f else 0.36f) * alpha),
    )
    val colors = if (dark) {
        darkColorScheme(primary = glass.onDeep, onPrimary = Color.White, surface = glass.wallBase, onSurface = glass.fg, background = glass.wallBase)
    } else {
        lightColorScheme(primary = glass.onDeep, onPrimary = Color.White, surface = glass.wallBase, onSurface = glass.fg, background = glass.wallBase)
    }
    CompositionLocalProvider(LocalGlass provides glass) {
        MaterialTheme(colorScheme = colors, typography = GlassTypography, content = content)
    }
}

object GlassTheme {
    val colors: GlassColors
        @Composable get() = LocalGlass.current
}
