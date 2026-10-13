package app.homeydash.ui.dashboard

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import app.homeydash.ui.components.pressable

/**
 * Rearranging cards the iOS way. Holding a card makes the cards jiggle; dragging it
 * then moves it, and the others make room. Letting go without dragging opens the
 * card's menu instead. While rearranging, a tap on a card opens its menu and Done
 * on the page ends it.
 */
@Stable
class Rearrange {
    var editing by mutableStateOf(false)
        private set

    /** The menu of the card being held, opened if the hold ends without a drag. */
    private var held: Sheet? = null
    private var holding = false
    private var holdStartedEditing = false

    /** A card was held; [menu] opens if it's let go without a drag (null: stay rearranging). */
    fun hold(menu: Sheet?) {
        held = menu
        holding = true
        if (!editing) {
            editing = true
            holdStartedEditing = true
        }
    }

    fun done() {
        editing = false
        held = null
        holding = false
        holdStartedEditing = false
    }

    /** The finger lifted. Returns the menu to open when it was a hold without a drag. */
    internal fun release(dragged: Boolean): Sheet? {
        val menu = held
        val wasHolding = holding
        held = null
        holding = false
        val started = holdStartedEditing
        holdStartedEditing = false
        if (!wasHolding || dragged || menu == null) return null
        if (started) editing = false
        return menu
    }
}

/**
 * Dragging one card of a [LazyGridState]'s grid. [canMove] says whether the card with
 * the first key may take the place of the card with the second; [onMove] makes that
 * change in the grid's items right away, so the other cards slide aside; [onDrop]
 * saves the order when the finger lifts.
 */
@Stable
class GridDrag(val grid: LazyGridState) {
    var key by mutableStateOf<Any?>(null)
        private set
    private var startOffset = Offset.Zero
    private var delta by mutableStateOf(Offset.Zero)

    /** Scroll speed while the dragged card is held at the top or bottom edge. */
    internal var edgeScroll = 0f
        private set

    var draggable: (Any) -> Boolean = { false }

    /** Cards the grid itself starts rearranging on a long press, for cards with no hold of their own. */
    var holdable: (Any) -> Boolean = { false }

    internal fun holdableAt(position: Offset): Boolean =
        grid.layoutInfo.visibleItemsInfo.firstOrNull { it.contains(position) }?.let { holdable(it.key) } == true
    var canMove: (Any, Any) -> Boolean = { _, _ -> false }
    var onMove: (Any, Any) -> Unit = { _, _ -> }
    var onDrop: () -> Unit = {}

    private fun info(key: Any?): LazyGridItemInfo? = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    private fun LazyGridItemInfo.contains(point: Offset) =
        point.x >= offset.x && point.x <= offset.x + size.width && point.y >= offset.y && point.y <= offset.y + size.height

    internal fun start(position: Offset): Boolean {
        val hit = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.contains(position) } ?: return false
        if (!draggable(hit.key)) return false
        key = hit.key
        startOffset = Offset(hit.offset.x.toFloat(), hit.offset.y.toFloat())
        delta = Offset.Zero
        return true
    }

    internal fun drag(by: Offset) {
        delta += by
        retarget()
    }

    /** Moves the card into the place under its centre, if it may go there. */
    internal fun retarget() {
        val dragged = key ?: return
        val current = info(dragged) ?: return
        val topLeft = startOffset + delta
        val center = topLeft + Offset(current.size.width / 2f, current.size.height / 2f)
        val target = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key != dragged && it.contains(center) }
        if (target != null && canMove(dragged, target.key)) onMove(dragged, target.key)
        val edge = 90f
        val viewportEnd = grid.layoutInfo.viewportEndOffset.toFloat()
        edgeScroll = when {
            topLeft.y + current.size.height > viewportEnd - edge -> 14f
            topLeft.y < edge && grid.canScrollBackward -> -14f
            else -> 0f
        }
    }

    internal fun end() {
        if (key != null) onDrop()
        key = null
        delta = Offset.Zero
        edgeScroll = 0f
    }

    /** How far the dragged card is drawn from its place in the grid, under the finger. */
    fun offsetOf(itemKey: Any): Offset {
        if (itemKey != key) return Offset.Zero
        val current = info(itemKey) ?: return Offset.Zero
        return startOffset + delta - Offset(current.offset.x.toFloat(), current.offset.y.toFloat())
    }
}

@Composable
fun rememberGridDrag(grid: LazyGridState): GridDrag {
    val drag = remember(grid) { GridDrag(grid) }
    // Keeps scrolling while the card is held at an edge.
    LaunchedEffect(drag, drag.key) {
        if (drag.key == null) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val speed = drag.edgeScroll
            if (speed != 0f) {
                drag.grid.dispatchRawDelta(speed)
                drag.retarget()
            }
        }
    }
    return drag
}

/**
 * The grid's side of rearranging: watches every touch before the cards see it, picks
 * up a card once the finger moves while rearranging, and swallows the touch from then
 * on so sliders and buttons under it don't react.
 */
fun Modifier.rearrangeable(drag: GridDrag, rearrange: Rearrange, onMenu: (Sheet) -> Unit, onHold: () -> Unit = {}): Modifier =
    pointerInput(drag, rearrange) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var dragging = false
            var gaveUp = false
            var moved = Offset.Zero
            var ended = false
            var selfHeld = false
            if (!rearrange.editing && drag.holdableAt(down.position)) {
                // Cards like the weather have no hold of their own: a still press starts rearranging.
                val timedOut = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            ended = true
                            break
                        }
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                    }
                } == null
                if (timedOut) {
                    selfHeld = true
                    rearrange.hold(null)
                    onHold()
                }
            }
            while (!ended) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    // The card under a hold isn't tapped when the finger lifts.
                    if (selfHeld) change.consume()
                    break
                }
                val step = change.position - change.previousPosition
                if (!rearrange.editing || gaveUp) continue
                if (!dragging) {
                    moved += step
                    if (moved.getDistance() > viewConfiguration.touchSlop) {
                        dragging = drag.start(down.position)
                        if (dragging) drag.drag(moved) else gaveUp = true
                    }
                } else {
                    drag.drag(step)
                }
                if (dragging || selfHeld) change.consume()
            }
            if (dragging) drag.end()
            rearrange.release(dragged = dragging)?.let(onMenu)
        }
    }

/**
 * One card in a rearrangeable grid: follows the finger while dragged, slides when
 * others move, jiggles while rearranging, and while rearranging a tap opens [menu]
 * instead of working the card.
 */
@Composable
fun LazyGridItemScope.RearrangeableItem(
    key: Any,
    drag: GridDrag,
    rearrange: Rearrange,
    menu: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val dragging = drag.key == key
    val itemModifier = if (dragging) {
        Modifier
            .zIndex(1f)
            .graphicsLayer {
                val offset = drag.offsetOf(key)
                translationX = offset.x
                translationY = offset.y
                scaleX = 1.05f
                scaleY = 1.05f
                shadowElevation = 24f
            }
    } else {
        Modifier.animateItem()
    }
    Box(modifier.then(itemModifier).then(if (rearrange.editing && !dragging) Modifier.jiggle(key) else Modifier)) {
        content()
        if (rearrange.editing) {
            // Covers the card so its own buttons and sliders rest while rearranging.
            Box(Modifier.matchParentSize().pressable(menu))
        }
    }
}

@Composable
private fun Modifier.jiggle(key: Any): Modifier {
    val transition = rememberInfiniteTransition(label = "jiggle")
    // Each card starts at its own point of the wiggle, so they don't move in step.
    val phase = remember(key) { (key.hashCode() and 0xff) / 255f }
    val angle = transition.animateFloat(
        initialValue = -0.9f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(150 + (phase * 60).toInt()), RepeatMode.Reverse),
        label = "angle",
    )
    return graphicsLayer { rotationZ = angle.value }
}
