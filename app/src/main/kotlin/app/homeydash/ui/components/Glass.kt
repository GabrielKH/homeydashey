package app.homeydash.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.window.DialogProperties
import app.homeydash.ui.theme.GlassColors
import app.homeydash.ui.theme.GlassTheme

val PanelShape = RoundedCornerShape(26.dp)
val TileShape = RoundedCornerShape(24.dp)
val PillShape = RoundedCornerShape(percent = 50)

/** The colours of a wallpaper: a base and three soft fields. */
@Immutable
data class WallPalette(val base: Color, val a: Color, val b: Color, val c: Color)

/** Soft colour fields behind everything; drawn once, which is what makes the panels read as glass. */
@Composable
fun Wallpaper(modifier: Modifier = Modifier, palette: WallPalette? = null) {
    val g = GlassTheme.colors
    val p = palette ?: WallPalette(g.wallBase, g.wallA, g.wallB, g.wallC)
    Canvas(modifier.fillMaxSize()) {
        drawRect(p.base)
        fun blob(color: Color, x: Float, y: Float, r: Float) {
            val center = Offset(size.width * x, size.height * y)
            val radius = size.maxDimension * r
            drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center, radius), radius, center)
        }
        blob(p.a, 0.10f, 0.15f, 0.55f)
        blob(p.b, 0.90f, 0.20f, 0.50f)
        blob(p.c, 0.60f, 1.05f, 0.62f)
    }
}

/** Translucent panel: a diagonal tint, a hairline, and a bright top edge. */
fun Modifier.glass(shape: Shape = PanelShape): Modifier = composed {
    val g = GlassTheme.colors
    val fill = remember(g) { if (g.flat) SolidColor(g.flatPanel.copy(alpha = if (g.isDark) 0.92f else 0.9f)) else glassFill(g) }
    val edge = remember(g) { if (g.flat) SolidColor(g.glassLine) else Brush.verticalGradient(0f to g.glassEdge, 0.35f to g.glassLine, 1f to g.glassLine) }
    this
        .clip(shape)
        .background(fill)
        .border(1.dp, edge, shape)
}

/**
 * The assistant's side window look: the wall's own dark (or light) colour, a little
 * see-through, under the glass.
 */
fun Modifier.windowGlass(shape: Shape = PanelShape): Modifier = composed {
    val g = GlassTheme.colors
    this
        .clip(shape)
        .background(g.wallBase.copy(alpha = if (g.isDark) 0.88f else 0.9f), shape)
        .glass(shape)
}

private fun glassFill(g: GlassColors) = Brush.linearGradient(listOf(g.glassTop, g.glassBottom), Offset.Zero, Offset.Infinite)

/** The warm fill of something that is on: the active mood, a lit bubble. */
@Composable
fun onBrush(): Brush {
    val g = GlassTheme.colors
    return remember(g) { if (g.flat) SolidColor(g.on) else Brush.linearGradient(listOf(g.on, g.onDeep), Offset.Zero, Offset.Infinite) }
}

/**
 * Tap and long-press with a slight press-in, like iOS controls. Taps and long
 * presses also give a light haptic tick.
 */
fun Modifier.pressable(
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)? = null,
    role: Role = Role.Button,
    enabled: Boolean = true,
): Modifier = composed {
    if (onClick == null && onLongClick == null) return@composed this
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = 900f), label = "press")
    val haptics = LocalHapticFeedback.current
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
            onLongClick = onLongClick?.let { { haptics.performHapticFeedback(HapticFeedbackType.LongPress); it() } },
            onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick?.invoke() },
        )
}

/** A glass panel that can be tapped. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = PanelShape,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .pressable(onClick, onLongClick)
            .glass(shape),
        content = content,
    )
}

/** An icon in a circle: glass when off, warm and filled when on. */
@Composable
fun Bubble(
    icon: Ic,
    on: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    onColor: Color? = null,
    tint: Color? = null,
) {
    val g = GlassTheme.colors
    val bg = if (on) {
        if (onColor != null) Modifier.background(onColor, CircleShape) else Modifier.background(onBrush(), CircleShape)
    } else {
        Modifier.glass(CircleShape)
    }
    Box(modifier.size(size).then(bg), contentAlignment = Alignment.Center) {
        SymIcon(
            icon,
            filled = on,
            size = size * 0.5f,
            tint = when {
                on && onColor == null -> g.onInk
                on -> inkOn(onColor!!)
                else -> tint ?: g.fg
            },
        )
    }
}

/** Dark ink on light colours, white on dark ones. */
fun inkOn(color: Color): Color = if (color.luminance() > 0.55f) Color(0xFF1A1B22) else Color.White

/** Round glass button for media and steppers; [primary] is the solid play button. */
@Composable
fun RoundButton(
    icon: Ic,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    primary: Boolean = false,
    filledIcon: Boolean = true,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val g = GlassTheme.colors
    Box(
        modifier
            .size(size)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .pressable(onClick, enabled = enabled)
            .then(if (primary) Modifier.background(g.fg, CircleShape) else Modifier.glass(CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        SymIcon(icon, filled = filledIcon, size = size * if (primary) 0.55f else 0.48f, tint = if (primary) g.wallBase else g.fg)
    }
}

/** Small status pill for headers: an icon and a short value. */
@Composable
fun GlassChip(icon: Ic, text: String, tint: Color, modifier: Modifier = Modifier, filled: Boolean = true, onClick: (() -> Unit)? = null) {
    Row(
        modifier
            .pressable(onClick)
            .glass(PillShape)
            .padding(start = 12.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SymIcon(icon, filled = filled, size = 20.dp, tint = tint)
        Text(text, style = MaterialTheme.typography.labelLarge, color = GlassTheme.colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = GlassTheme.colors.muted,
        modifier = modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
    )
}

/** The screen's usable size in the dashboard's own dp, which the Display size setting scales. */
@Composable
fun screenSizeDp(): DpSize {
    val configuration = LocalConfiguration.current
    val ratio = LocalContext.current.resources.displayMetrics.density / LocalDensity.current.density
    return DpSize((configuration.screenWidthDp * ratio).dp, (configuration.screenHeightDp * ratio).dp)
}

/** Largest share of the screen's height a popup takes, so the dashboard still shows above and below it. */
const val DialogHeightShare = 0.8f

/**
 * A popup's size: [preferred] width when the screen has room for it, and never taller than
 * [DialogHeightShare] of the screen. [tall] popups (the settings) may use nearly the whole
 * height, so long pages need less scrolling.
 */
@Composable
fun Modifier.dialogSize(preferred: Dp, tall: Boolean = false): Modifier {
    val screen = screenSizeDp()
    val maxHeight = screen.height * (if (tall) TallDialogHeightShare else DialogHeightShare)
    // Inside [Compact] a dp is smaller, so the popup keeps its width on the screen.
    return this.width(minOf(preferred / LocalCompactScale.current, screen.width - 48.dp)).heightIn(max = maxHeight)
}

/**
 * How big the settings popups draw everything compared with the dashboard: a little smaller,
 * so long pages fit with less scrolling, while the dashboard stays easy to read across the kitchen.
 */
const val SettingsScale = 0.86f

private val LocalCompactScale = androidx.compose.runtime.staticCompositionLocalOf { 1f }

/**
 * [content] drawn at [scale] of its size: text, icons, spacing and corners together, so it looks
 * the same, only smaller. The owner's display size setting still applies on top.
 */
@Composable
fun Compact(scale: Float = SettingsScale, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        LocalDensity provides androidx.compose.ui.unit.Density(d.density * scale, d.fontScale),
        LocalCompactScale provides LocalCompactScale.current * scale,
        content = content,
    )
}

/** Largest share of the screen's height the settings popups take. */
const val TallDialogHeightShare = 0.94f

/**
 * Touches anywhere in the app, popups included. A popup is a window of its own, so the
 * activity never sees its touches; without this the screen dimmed while someone was busy in
 * Settings.
 */
object UserActivity {
    @Volatile var onTouch: () -> Unit = {}
}

/** A popup window whose touches count as using the tablet (see [UserActivity]). */
@Composable
fun AppDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        UserActivity.onTouch()
                    }
                }
            },
        ) { content() }
    }
}

/** A floating glass panel over the dimmed dashboard, for lamps, the remote and music. */
@Composable
fun GlassDialog(
    onDismiss: () -> Unit,
    width: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val g = GlassTheme.colors
    AppDialog(onDismiss) {
        Column(
            Modifier
                .dialogSize(width)
                .background(g.wallBase.copy(alpha = if (g.isDark) 0.94f else 0.96f), PanelShape)
                .glass()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            content = content,
        )
    }
}

/** Title row of a [GlassDialog], with a close button. */
@Composable
fun DialogHeader(title: String, subtitle: String?, onClose: () -> Unit, leading: (@Composable () -> Unit)? = null) {
    val g = GlassTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = g.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        RoundButton(Sym.close, onClose, size = 44.dp, filledIcon = false)
    }
}
