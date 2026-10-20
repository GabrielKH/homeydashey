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
    // Placing a few hundred dots takes a moment, so it's done off the main thread.
    val pos by produceState<FloatArray?>(null, graph) { value = withContext(Dispatchers.Default) { MemoryGraph.layout(graph) } }
    var scale by remember(graph) { mutableFloatStateOf(1f) }
    var offset by remember(graph) { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 12.sp, color = g.fg)
    val hubStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = g.fg)
    val labels = remember(graph, g) {
        graph.nodes.map { n ->
            measurer.measure(n.label, if (n.hub) hubStyle else labelStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = 360))
        }
    }
    val sel = graph.nodes.indexOfFirst { it.key == selected }
    val near = remember(graph, sel) { if (sel < 0) emptySet() else graph.neighbours(sel) + sel }

    Canvas(
        modifier
            .pointerInput(graph) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(0.5f, 6f)
                    val z = next / scale
                    val center = Offset(size.width / 2f, size.height / 2f)
                    offset = (centroid - center) * (1 - z) + offset * z + pan
                    scale = next
                }
            }
            .pointerInput(graph, pos) {
                detectTapGestures { tap ->
                    val p = pos ?: return@detectTapGestures
                    val base = minOf(size.width, size.height) / 2f * 0.86f
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val unit = 7.dp.toPx()
                    var best = -1
                    var bestD = Float.MAX_VALUE
                    graph.nodes.forEachIndexed { i, n ->
                        val at = center + Offset(p[i * 2], p[i * 2 + 1]) * (base * scale) + offset
                        val d = (at - tap).getDistance()
                        if (d < unit * MemoryGraph.weight(n) + 16.dp.toPx() && d < bestD) { best = i; bestD = d }
                    }
                    onSelect(if (best < 0) null else graph.nodes[best].key)
                }
            },
    ) {
        val p = pos ?: return@Canvas
        val base = minOf(size.width, size.height) / 2f * 0.86f
        val center = Offset(size.width / 2f, size.height / 2f)
        fun at(i: Int) = center + Offset(p[i * 2], p[i * 2 + 1]) * (base * scale) + offset
        val unit = 7.dp.toPx()
        val dim = sel >= 0

        for (e in graph.edges) {
            val focus = !dim || (e.a == sel || e.b == sel)
            val kind = e.kind
            val color = if (kind == null) g.muted.copy(alpha = 0.35f) else kindColor(kind, g).copy(alpha = 0.75f)
            drawLine(
                color.copy(alpha = color.alpha * if (focus) 1f else 0.15f),
                at(e.a),
                at(e.b),
                strokeWidth = if (kind == null) 1.dp.toPx() else (1.2f + ln(e.count.toFloat()) * 1.1f).coerceAtMost(6f) * 1.dp.toPx(),
            )
        }
        val showAll = graph.nodes.size <= 50 || scale >= 1.8f
        graph.nodes.forEachIndexed { i, n ->
            val c = at(i)
            val r = unit * MemoryGraph.weight(n) * sqrt(scale).coerceAtMost(1.6f)
            val focus = !dim || i in near
            val faded = !n.hub && n.used == 0
            val fill = if (n.hub) g.fg else kindColor(n.kind, g)
            drawCircle(fill.copy(alpha = (if (faded) 0.4f else 1f) * (if (focus) 1f else 0.25f)), r, c)
            if (i == sel) drawCircle(fill.copy(alpha = 0.6f), r + 6.dp.toPx(), c, style = Stroke(2.dp.toPx()))
            val label = labels[i]
            if (n.hub || showAll || i in near || MemoryGraph.weight(n) >= 1.9f) {
                drawText(label, topLeft = c + Offset(-label.size.width / 2f, r + 3.dp.toPx()), alpha = if (focus) (if (faded) 0.7f else 1f) else 0.3f)
            }
        }
    }
}
