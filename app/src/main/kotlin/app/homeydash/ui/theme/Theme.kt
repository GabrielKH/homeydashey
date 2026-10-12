package app.homeydash.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Everything the dashboard draws its surfaces with. The glass design swaps
 * these values (and [DashboardCard]'s drawing) without touching the screens.
 */
@Immutable
data class DashboardStyle(
    val background: Brush,
    val cardShape: Shape,
    val cardColor: Color,
    val cardActiveColor: Color,
    val cardBorder: Color,
    val cardBorderWidth: Dp,
    val onCard: Color,
    val onCardMuted: Color,
    val accent: Color,
    val online: Color,
    val offline: Color,
)

private val LightStyle = DashboardStyle(
    background = Brush.verticalGradient(listOf(Color(0xFFE9EEF5), Color(0xFFD9E1EC))),
    cardShape = RoundedCornerShape(24.dp),
    cardColor = Color(0xFFFFFFFF),
    cardActiveColor = Color(0xFFFFF4D6),
    cardBorder = Color(0x1F000000),
    cardBorderWidth = 1.dp,
    onCard = Color(0xFF111114),
    onCardMuted = Color(0xFF55555C),
    accent = Color(0xFFE59A00),
    online = Color(0xFF1E9E4A),
    offline = Color(0xFFC62828),
)

private val DarkStyle = DashboardStyle(
    background = Brush.verticalGradient(listOf(Color(0xFF15171C), Color(0xFF0B0C0F))),
    cardShape = RoundedCornerShape(24.dp),
    cardColor = Color(0xFF23252B),
    cardActiveColor = Color(0xFF3D3420),
    cardBorder = Color(0x33FFFFFF),
    cardBorderWidth = 1.dp,
    onCard = Color(0xFFF5F5F7),
    onCardMuted = Color(0xFFB0B0B8),
    accent = Color(0xFFFFC14D),
    online = Color(0xFF4CD07D),
    offline = Color(0xFFFF6B6B),
)

val LocalDashboardStyle = staticCompositionLocalOf { LightStyle }

// Large type throughout: the tablet is read from across the kitchen on a matte screen.
private val DashboardTypography = Typography(
    headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 18.sp),
    bodyMedium = TextStyle(fontSize = 16.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun HomeyDashTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val style = if (darkTheme) DarkStyle else LightStyle
    val colors = if (darkTheme) {
        darkColorScheme(primary = style.accent, surface = style.cardColor, onSurface = style.onCard)
    } else {
        lightColorScheme(primary = style.accent, surface = style.cardColor, onSurface = style.onCard)
    }
    CompositionLocalProvider(LocalDashboardStyle provides style) {
        MaterialTheme(colorScheme = colors, typography = DashboardTypography, content = content)
    }
}

object DashboardTheme {
    val style: DashboardStyle
        @Composable get() = LocalDashboardStyle.current
}
