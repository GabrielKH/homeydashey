package app.homeydash.ui.assistant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.homeydash.agent.FactKind
import app.homeydash.agent.MemoryGraph
import app.homeydash.ui.theme.GlassColors
import app.homeydash.ui.theme.GlassTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.sqrt

/** Each category's colour, on dots, lines and chips. */
internal fun kindColor(kind: FactKind, g: GlassColors): Color = when (kind) {
    FactKind.Habit -> g.on
    FactKind.Learned -> g.cool
    FactKind.Said -> if (g.isDark) Color(0xFF4FD1C5) else Color(0xFF14998C)
    FactKind.About -> if (g.isDark) Color(0xFFF48FB1) else Color(0xFFC2416F)
}

/**
 * The memory graph, drawn: people and the house as big dots, the things their facts are about
 * around them, each fact a line coloured by its category. A dot grows with how often its facts
 * were used and is faded when they never were. Pinch to zoom, drag to move, tap a dot to pick
 * it ([onSelect] with its key, or null on empty space).
 */
@Composable
internal fun MemoryGraphView(graph: MemoryGraph.Graph, selected: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    val g = GlassTheme.colors
    val dots = remember(graph, g) {
        graph.nodes.map { n -> GraphDot(n.key, n.label, n.hub, MemoryGraph.weight(n), if (n.hub) g.fg else kindColor(n.kind, g), faded = !n.hub && n.used == 0) }
    }
    val lines = remember(graph, g) {
        graph.edges.map { e ->
            val kind = e.kind
            GraphLine(
                e.a, e.b,
                if (kind == null) g.muted.copy(alpha = 0.35f) else kindColor(kind, g).copy(alpha = 0.75f),
                if (kind == null) 1f else (1.2f + ln(e.count.toFloat()) * 1.1f).coerceAtMost(6f),
            )
        }
    }
    GraphCanvas(dots, lines, graph, { MemoryGraph.layout(graph) }, selected, onSelect, modifier)
}

/** A dot of a drawn graph: big and bold when [hub], [weight] its size, [faded] when never used. */
internal data class GraphDot(val key: String, val label: String, val hub: Boolean, val weight: Float, val color: Color, val faded: Boolean = false)

/** A line of a drawn graph between dots [a] and [b], [width] in dp. */
internal data class GraphLine(val a: Int, val b: Int, val color: Color, val width: Float)

/**
 * Draws a graph of [dots] and [lines], placed by [layout] off the main thread whenever [layoutKey]
 * changes. Pinch to zoom, drag to move, tap a dot to pick it; the picked dot's neighbours stay
 * bright and the rest fade.
 */
@Composable
internal fun GraphCanvas(
    dots: List<GraphDot>,
    lines: List<GraphLine>,
    layoutKey: Any,
    layout: () -> FloatArray,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = GlassTheme.colors
    // Placing a few hundred dots takes a moment, so it's done off the main thread.
    val pos by produceState<FloatArray?>(null, layoutKey) { value = withContext(Dispatchers.Default) { layout() } }
    var scale by remember(layoutKey) { mutableFloatStateOf(1f) }
    var offset by remember(layoutKey) { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 12.sp, color = g.fg)
    val hubStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = g.fg)
    val labels = remember(dots, g) {
        dots.map { d ->
            measurer.measure(d.label, if (d.hub) hubStyle else labelStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = 360))
        }
    }
    val sel = dots.indexOfFirst { it.key == selected }
    val near = remember(lines, sel) {
        if (sel < 0) emptySet() else lines.mapNotNullTo(HashSet()) { e -> if (e.a == sel) e.b else if (e.b == sel) e.a else null } + sel
    }

    Canvas(
        modifier
            .pointerInput(layoutKey) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(0.5f, 6f)
                    val z = next / scale
                    val center = Offset(size.width / 2f, size.height / 2f)
                    offset = (centroid - center) * (1 - z) + offset * z + pan
                    scale = next
                }
            }
            .pointerInput(dots, pos) {
                detectTapGestures { tap ->
                    val p = pos ?: return@detectTapGestures
                    if (p.size < dots.size * 2) return@detectTapGestures
                    val base = minOf(size.width, size.height) / 2f * 0.86f
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val unit = 7.dp.toPx()
                    var best = -1
                    var bestD = Float.MAX_VALUE
                    dots.forEachIndexed { i, d ->
                        val at = center + Offset(p[i * 2], p[i * 2 + 1]) * (base * scale) + offset
                        val dist = (at - tap).getDistance()
                        if (dist < unit * d.weight + 16.dp.toPx() && dist < bestD) { best = i; bestD = dist }
                    }
                    onSelect(if (best < 0) null else dots[best].key)
                }
            },
    ) {
        val p = pos ?: return@Canvas
        // The dots changed and their new places aren't worked out yet.
        if (p.size < dots.size * 2) return@Canvas
        val base = minOf(size.width, size.height) / 2f * 0.86f
        val center = Offset(size.width / 2f, size.height / 2f)
        fun at(i: Int) = center + Offset(p[i * 2], p[i * 2 + 1]) * (base * scale) + offset
        val unit = 7.dp.toPx()
        val dim = sel >= 0

        for (e in lines) {
            val focus = !dim || (e.a == sel || e.b == sel)
            drawLine(e.color.copy(alpha = e.color.alpha * if (focus) 1f else 0.15f), at(e.a), at(e.b), strokeWidth = e.width * 1.dp.toPx())
        }
        val showAll = dots.size <= 50 || scale >= 1.8f
        dots.forEachIndexed { i, d ->
            val c = at(i)
            val r = unit * d.weight * sqrt(scale).coerceAtMost(1.6f)
            val focus = !dim || i in near
            drawCircle(d.color.copy(alpha = (if (d.faded) 0.4f else 1f) * (if (focus) 1f else 0.25f)), r, c)
            if (i == sel) drawCircle(d.color.copy(alpha = 0.6f), r + 6.dp.toPx(), c, style = Stroke(2.dp.toPx()))
            val label = labels[i]
            if (d.hub || showAll || i in near || d.weight >= 1.9f) {
                drawText(label, topLeft = c + Offset(-label.size.width / 2f, r + 3.dp.toPx()), alpha = if (focus) (if (d.faded) 0.7f else 1f) else 0.3f)
            }
        }
    }
}
