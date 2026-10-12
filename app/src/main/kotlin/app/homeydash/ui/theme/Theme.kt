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

val Manrope = FontFamily(
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_medium, FontWeight.Normal),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)

// Large type throughout: the tablet is read from across the kitchen on a matte screen.
private val GlassTypography = Typography(
    displayLarge = TextStyle(fontFamily = Manrope, fontSize = 72.sp, lineHeight = 74.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp),
    headlineLarge = TextStyle(fontFamily = Manrope, fontSize = 38.sp, lineHeight = 44.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = Manrope, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontFamily = Manrope, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.ExtraBold),
    titleMedium = TextStyle(fontFamily = Manrope, fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.ExtraBold),
    titleSmall = TextStyle(fontFamily = Manrope, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontFamily = Manrope, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontFamily = Manrope, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelLarge = TextStyle(fontFamily = Manrope, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontFamily = Manrope, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp),
)

@Composable
fun HomeyDashTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val glass = if (dark) Dark else Light
    val colors = if (dark) {
        darkColorScheme(primary = glass.on, onPrimary = glass.onInk, surface = glass.wallBase, onSurface = glass.fg, background = glass.wallBase)
    } else {
        lightColorScheme(primary = glass.on, onPrimary = glass.onInk, surface = glass.wallBase, onSurface = glass.fg, background = glass.wallBase)
    }
    CompositionLocalProvider(LocalGlass provides glass) {
        MaterialTheme(colorScheme = colors, typography = GlassTypography, content = content)
    }
}

object GlassTheme {
    val colors: GlassColors
        @Composable get() = LocalGlass.current
}
