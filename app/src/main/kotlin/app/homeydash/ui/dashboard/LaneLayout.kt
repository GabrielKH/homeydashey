package app.homeydash.ui.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import app.homeydash.homey.OverviewLanes
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Dragging a card between the overview's lanes. Positions are kept in root
 * coordinates, so they stay right while the page scrolls.
 */
@Stable
class LaneDrag(val scroll: ScrollState) : DragSurface {
    var key by mutableStateOf<String?>(null)
        private set
    private var startTopLeft = Offset.Zero
    private var delta by mutableStateOf(Offset.Zero)

    internal var viewport: LayoutCoordinates? = null

    /** Each card's bounds on screen, by key. */
    // A plain map: it's written on every layout pass, scrolling included, and only read while
    // a card is picked up, so it needs no snapshot state.
    internal val cards = HashMap<String, Rect>()

    /** The lanes' left edges and width, in px from the lanes' own left edge. */
    internal var laneLefts: List<Float> = emptyList()
    internal var laneWidth = 0f

    /** The layout being shown; moves change it at once so quick drags build on each other. */
    var lanes: List<List<String>> = emptyList()
    var onMove: (List<List<String>>) -> Unit = {}
    var onDrop: () -> Unit = {}
    var holdable: (String) -> Boolean = { false }

    internal var edgeScroll = 0f
        private set

    private fun origin(): Offset = viewport?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero

    private fun hit(position: Offset): String? {
        val point = origin() + position
        val shown = lanes.flatten().toSet()
        return cards.entries.firstOrNull { it.key in shown && it.value.contains(point) }?.key
    }

    override fun holdableAt(position: Offset): Boolean = hit(position)?.let(holdable) == true

    override fun cardAt(position: Offset): Boolean = hit(position) != null

    override fun start(position: Offset): Boolean {
        val found = hit(position) ?: return false
        key = found
        startTopLeft = cards[found]?.topLeft ?: return false
        delta = Offset.Zero
        return true
    }

    override fun drag(by: Offset) {
        delta += by
        retarget()
    }

    /** Puts the card where its centre is: in the lane under it, before the card it is above or left of. */
    internal fun retarget() {
        val dragged = key ?: return
        val box = cards[dragged] ?: return
        val topLeft = startTopLeft + delta
        val center = topLeft + Offset(box.width / 2f, box.height / 2f)
        val left = origin().x
        val lane = laneLefts.indices.minByOrNull { i -> abs(left + laneLefts[i] + laneWidth / 2f - center.x) } ?: return
        val before = lanes.getOrNull(lane).orEmpty().filter { it != dragged }.firstOrNull { k ->
            val b = cards[k] ?: return@firstOrNull false
            center.y < b.top || (center.y <= b.bottom && center.x < b.center.x)
        }
        val moved = OverviewLanes.move(lanes, dragged, lane, before)
        if (moved != lanes) {
            lanes = moved
            onMove(moved)
        }
        val view = viewport?.takeIf { it.isAttached }
        if (view != null) {
            val top = topLeft.y - origin().y
            val edge = 90f
            edgeScroll = when {
                top + box.height > view.size.height - edge && scroll.canScrollForward -> 14f
                top < edge && scroll.canScrollBackward -> -14f
                else -> 0f
            }
        }
    }

    override fun end() {
        if (key != null) onDrop()
        key = null
        delta = Offset.Zero
        edgeScroll = 0f
    }

    fun offsetOf(itemKey: String): Offset {
        if (itemKey != key) return Offset.Zero
        val placed = cards[itemKey] ?: return Offset.Zero
        return startTopLeft + delta - placed.topLeft
    }
}

@Composable
fun rememberLaneDrag(scroll: ScrollState): LaneDrag {
    val drag = remember(scroll) { LaneDrag(scroll) }
    LaunchedEffect(drag, drag.key) {
        if (drag.key == null) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val speed = drag.edgeScroll
            if (speed != 0f) {
                drag.scroll.dispatchRawDelta(speed)
                drag.retarget()
            }
        }
    }
    return drag
}

/**
 * The overview's cards in [lanes]: equal columns, wide cards taking a lane's width,
 * narrow ones two to a row. Cards slide to their new place when the layout changes.
 * While rearranging, the lanes show faintly so it's clear where a card can go.
 */
@Composable
fun LaneLayout(
    lanes: List<List<String>>,
    wide: (String) -> Boolean,
    gap: Dp,
    drag: LaneDrag,
    rearrange: Rearrange,
    /** Opens a card's menu while rearranging; null for cards without one. */
    menu: (String) -> (() -> Unit)?,
    modifier: Modifier = Modifier,
    card: @Composable (String) -> Unit,
) {
    val g = GlassTheme.colors
    val keys = lanes.flatten()
    val editing = rearrange.editing
    Layout(
        modifier = modifier.drawBehind {
            if (editing) {
                drag.laneLefts.forEach { x ->
                    drawRoundRect(
                        color = g.fg.copy(alpha = 0.05f),
                        topLeft = Offset(x, 0f),
                        size = Size(drag.laneWidth, size.height),
                        cornerRadius = CornerRadius(24.dp.toPx()),
                    )
                }
            }
        },
        content = {
            keys.forEach { k ->
                key(k) { LaneItem(k, drag, rearrange, menu(k)) { card(k) } }
            }
        },
    ) { measurables, constraints ->
        val count = lanes.size.coerceAtLeast(1)
        val gapPx = gap.roundToPx()
        val width = constraints.maxWidth
        val laneWidth = ((width - gapPx * (count - 1)) / count).coerceAtLeast(0)
        val half = ((laneWidth - gapPx) / 2).coerceAtLeast(0)
        drag.laneWidth = laneWidth.toFloat()
        drag.laneLefts = List(count) { (it * (laneWidth + gapPx)).toFloat() }
        val byKey = keys.zip(measurables).toMap()
        val places = mutableListOf<Triple<Placeable, Int, Int>>()
        var tallest = 0
        lanes.forEachIndexed { i, lane ->
            val x0 = i * (laneWidth + gapPx)
            var y = 0
            OverviewLanes.rows(lane, wide).forEach { row ->
                val placed = row.mapIndexedNotNull { j, k ->
                    val w = if (wide(k)) laneWidth else half
                    byKey[k]?.measure(Constraints.fixedWidth(w))?.let { it to (x0 + j * (half + gapPx)) }
                }
                val rowHeight = placed.maxOfOrNull { it.first.height } ?: 0
                placed.forEach { (p, x) -> places += Triple(p, x, y) }
                y += rowHeight + gapPx
            }
            tallest = maxOf(tallest, y)
        }
        layout(width, tallest) {
            places.forEach { (p, x, y) -> p.place(x, y) }
        }
    }
}

private class Last(var position: Offset? = null)

@Composable
private fun LaneItem(key: String, drag: LaneDrag, rearrange: Rearrange, menu: (() -> Unit)?, content: @Composable () -> Unit) {
    val dragging = drag.key == key
    val scope = rememberCoroutineScope()
    val slide = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val last = remember { Last() }
    Box(
        Modifier
            .onGloballyPositioned { drag.cards[key] = Rect(it.positionInRoot(), it.size.toSize()) }
            .onPlaced { coords ->
                // Slides from where it was drawn to its new place.
                val position = coords.positionInParent()
                val previous = last.position
                last.position = position
                if (previous != null && previous != position && drag.key != key) {
                    scope.launch {
                        slide.snapTo(slide.value + (previous - position))
                        slide.animateTo(Offset.Zero, spring(stiffness = Spring.StiffnessMediumLow))
                    }
                }
            }
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                if (dragging) {
                    val offset = drag.offsetOf(key)
                    translationX = offset.x
                    translationY = offset.y
                    scaleX = 1.05f
                    scaleY = 1.05f
                    shadowElevation = 24f
                } else {
                    translationX = slide.value.x
                    translationY = slide.value.y
                }
            }
            .then(if (rearrange.editing && !dragging) Modifier.jiggle(key) else Modifier),
    ) {
        content()
        if (rearrange.editing) {
            // Covers the card so its own buttons rest while rearranging.
            Box(Modifier.matchParentSize().pressable(menu ?: {}))
        }
    }
}
