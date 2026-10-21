package app.homeydash.ui.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.homeydash.agent.Ticker
import app.homeydash.agent.TickerPhase
import kotlin.math.abs
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.homeydash.ui.components.RoundButton
import app.homeydash.ui.components.Sym
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.homeydash.agent.AgentSettings
import app.homeydash.agent.FollowUp
import app.homeydash.assistant.Assistant
import app.homeydash.assistant.AssistantState
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.windowGlass
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme

/**
 * The chosen face over the whole screen while someone talks to the assistant, with what
 * was heard or is being said underneath. Tapping anywhere ends the conversation.
 * Drawn over everything on the dashboard, sidebar included. When the assistant opens a
 * page, or the minus is tapped, it shrinks to a small glass island at the top (or the side
 * window, if chosen) instead, which can be moved and tapped to grow again; the page under it
 * stays usable.
 */
@Composable
fun VoiceOverlay(assistant: Assistant, settings: AgentSettings) {
    val g = GlassTheme.colors
    val compact = assistant.compact
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = assistant.voiceActive && !compact,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(260)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(g.wallBase.copy(alpha = if (g.isDark) 0.78f else 0.82f))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { assistant.stop() },
                contentAlignment = Alignment.Center,
            ) {
                assistant.scene?.let { AnswerScene(it.kind, Modifier.fillMaxSize()) }
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                ) {
                    VoiceFace(
                        settings.face,
                        assistant.state,
                        levels = { VoiceLevels(assistant.level, assistant.low, assistant.mid, assistant.high) },
                        modifier = Modifier.fillMaxWidth(0.8f).fillMaxHeight(0.5f),
                        classicRed = settings.classicRed,
                    )
                    Text(
                        caption(assistant),
                        style = MaterialTheme.typography.headlineSmall,
                        color = g.fg,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 820.dp),
                    )
                    if (assistant.state == AssistantState.Thinking || assistant.state == AssistantState.Speaking) {
                        StepList(assistant.steps(), Modifier.widthIn(max = 620.dp), max = 5, style = MaterialTheme.typography.bodyLarge, icon = 20.dp)
                    }
                    Confirm(assistant, big = true)
                    Text(
                        if (settings.followUp == FollowUp.Always) "Say \"stop\" or tap anywhere to end" else "Tap anywhere to stop",
                        style = MaterialTheme.typography.bodyMedium,
                        color = g.muted,
                    )
                }
                RoundButton(
                    Sym.remove, { assistant.compact = true }, size = 48.dp, filledIcon = false,
                    modifier = Modifier.align(Alignment.TopEnd).padding(24.dp),
                )
            }
        }
        val side = settings.view == app.homeydash.agent.AssistantView.Side
        CompactVoice(assistant, settings, visible = assistant.voiceActive && compact && !side)
        SideVoice(assistant, settings, visible = assistant.voiceActive && compact && side)
    }
}

/** Where the island sits along the top; it snaps to the spot nearest to where it was dropped. */
private enum class Spot(val bias: Float) { Left(-1f), Middle(0f), Right(1f) }

private val TickerWidth = 300.dp
private val IslandEdge = 20.dp

/**
 * The minimised assistant: a small glass island at the top with the face inside and three
 * small lines under it. Tap it to grow back to full screen, flick it up to stop, or press
 * and hold to lift it and drop it on the left, in the middle or on the right.
 */
@Composable
private fun BoxScope.CompactVoice(assistant: Assistant, settings: AgentSettings, visible: Boolean) {
    var spot by rememberSaveable { mutableStateOf(Spot.Middle) }
    var lifted by remember { mutableStateOf(false) }
    var area by remember { mutableIntStateOf(0) }
    var island by remember { mutableIntStateOf(0) }
    val x = remember { Animatable(Float.NaN) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val bias by animateFloatAsState(spot.bias, spring(dampingRatio = 0.85f, stiffness = 380f), label = "spot")
    val lift by animateFloatAsState(if (lifted) 1.08f else 1f, label = "lift")
    fun leftOf(s: Spot): Float {
        val edge = with(density) { IslandEdge.toPx() }
        return when (s) {
            Spot.Left -> edge
            Spot.Middle -> (area - island) / 2f
            Spot.Right -> area - island - edge
        }
    }
    Box(Modifier.matchParentSize().onSizeChanged { area = it.width })
    // Keep it on its spot when the screen or the island changes size.
    LaunchedEffect(area, island) { if (area > 0 && island > 0 && !lifted) x.snapTo(leftOf(spot)) }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.85f),
        exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.85f),
        modifier = Modifier.align(Alignment.TopStart),
    ) {
        Column(
            Modifier
                .offset {
                    val column = TickerWidth.toPx()
                    val left = if (x.value.isNaN()) leftOf(spot) else x.value
                    IntOffset((left - (column - island) * (bias + 1f) / 2f).roundToInt(), 0)
                }
                .padding(top = 12.dp)
                .width(TickerWidth),
            horizontalAlignment = BiasAlignment.Horizontal(bias),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Island(
                assistant,
                settings,
                Modifier
                    .graphicsLayer { scaleX = lift; scaleY = lift }
                    .onSizeChanged { island = it.width }
                    .pointerInput(Unit) {
                        val flick = 36.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val held = awaitLongPressOrCancellation(down.id)
                            if (held == null) {
                                // A tap opens it; a quick flick up ends the conversation.
                                var last = currentEvent.changes.firstOrNull { it.id == down.id }
                                while (last != null && last.pressed) {
                                    last = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                }
                                if (last == null || last.isConsumed) return@awaitEachGesture
                                val moved = last.position - down.position
                                when {
                                    moved.y < -flick && abs(moved.y) > abs(moved.x) -> assistant.stop()
                                    moved.getDistance() < viewConfiguration.touchSlop -> assistant.compact = false
                                }
                                return@awaitEachGesture
                            }
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            lifted = true
                            var left = if (x.value.isNaN()) leftOf(spot) else x.value
                            var dx = 0f
                            var dy = 0f
                            drag(held.id) { change ->
                                val d = change.positionChange()
                                dx += d.x
                                dy += d.y
                                change.consume()
                                left += d.x
                                scope.launch { x.snapTo(left) }
                            }
                            lifted = false
                            if (dy < -flick * 2 && abs(dy) > abs(dx)) {
                                scope.launch { x.animateTo(leftOf(spot)) }
                                assistant.stop()
                                return@awaitEachGesture
                            }
                            val share = (left + island / 2f) / area.coerceAtLeast(1)
                            spot = when {
                                share < 0.36f -> Spot.Left
                                share > 0.64f -> Spot.Right
                                else -> Spot.Middle
                            }
                            scope.launch { x.animateTo(leftOf(spot), spring(dampingRatio = 0.8f, stiffness = 420f)) }
                        }
                    },
            )
            val ticker = Ticker.of(
                phase = when (assistant.state) {
                    AssistantState.Listening -> TickerPhase.Listening
                    AssistantState.Thinking -> TickerPhase.Thinking
                    AssistantState.Speaking -> TickerPhase.Speaking
                    AssistantState.Idle -> TickerPhase.Idle
                },
                heard = assistant.lines.lastOrNull { it.fromUser }?.text,
                partial = assistant.partial,
                steps = assistant.steps(),
                followUp = assistant.conversing || settings.followUp != FollowUp.Off,
            )
            TickerLines(
                ticker,
                rollKey = if (assistant.state == AssistantState.Listening) "listening" else ticker.prev + "\n" + ticker.now,
                align = when (spot) {
                    Spot.Left -> TextAlign.Start
                    Spot.Middle -> TextAlign.Center
                    Spot.Right -> TextAlign.End
                },
            )
        }
    }
    // Back on its spot whenever it is shown again.
    LaunchedEffect(visible) { if (!visible && area > 0 && island > 0) x.snapTo(leftOf(spot)) }
}

/** The glass island itself: the chosen face, a spinner while it works, and Yes and No when asked. */
@Composable
private fun Island(assistant: Assistant, settings: AgentSettings, modifier: Modifier) {
    val g = GlassTheme.colors
    Row(
        modifier
            .height(34.dp)
            .widthIn(min = 150.dp)
            .clip(PillShape)
            .background(g.wallBase.copy(alpha = if (g.isDark) 0.55f else 0.6f), PillShape)
            .glass(PillShape)
            .animateContentSize()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        VoiceFace(
            settings.face,
            assistant.state,
            levels = { VoiceLevels(assistant.level, assistant.low, assistant.mid, assistant.high) },
            modifier = Modifier.width(96.dp).height(24.dp),
            classicRed = settings.classicRed,
        )
        if (assistant.state == AssistantState.Thinking) {
            CircularProgressIndicator(Modifier.size(12.dp), color = g.fg, strokeWidth = 1.5.dp)
        }
        if (assistant.pending != null) {
            Text(
                "Yes",
                style = MaterialTheme.typography.labelMedium,
                color = g.wallBase,
                modifier = Modifier.pressable({ assistant.confirm(true) }).background(g.fg, PillShape).padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Text(
                "No",
                style = MaterialTheme.typography.labelMedium,
                color = g.fg,
                modifier = Modifier.pressable({ assistant.confirm(false) }).glass(PillShape).padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * Three small lines straight on the screen: what it just did, faded, what it is doing now,
 * and what comes next. They roll up a line whenever [rollKey] changes.
 */
@Composable
private fun TickerLines(ticker: Ticker, rollKey: String, align: TextAlign) {
    val g = GlassTheme.colors
    val glow = Shadow(color = g.wallBase.copy(alpha = 0.95f), blurRadius = 10f)
    val small = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, lineHeight = 15.sp, shadow = glow, textAlign = align)
    val now = small.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
    AnimatedContent(
        targetState = ticker,
        contentKey = { rollKey },
        transitionSpec = {
            (slideInVertically(tween(320)) { it / 3 } + fadeIn(tween(320))) togetherWith
                (slideOutVertically(tween(320)) { -it / 3 } + fadeOut(tween(200)))
        },
        label = "ticker",
        modifier = Modifier.fillMaxWidth(),
    ) { t ->
        Column(Modifier.fillMaxWidth()) {
            Text(t.prev, style = small, color = g.fg.copy(alpha = 0.45f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            Text(t.now, style = now, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            Text(
                if (t.next.isEmpty()) "" else "Next: ${t.next}",
                style = small,
                color = g.fg.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Where the side window sits; it snaps to the corner nearest to where it was dropped. */
private enum class Corner { TopStart, TopEnd, BottomStart, BottomEnd }

/**
 * The side window: the face and what was said in a small card, starting on the left. It can
 * be dragged to another corner and tapped to grow to full screen.
 */
@Composable
private fun BoxScope.SideVoice(assistant: Assistant, settings: AgentSettings, visible: Boolean) {
    val g = GlassTheme.colors
    var corner by rememberSaveable { mutableStateOf(Corner.TopStart) }
    val drag = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()
    var area by remember { mutableStateOf(IntSize.Zero) }
    var window by remember { mutableStateOf(IntSize.Zero) }
    Box(Modifier.matchParentSize().onSizeChanged { area = it })
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.85f),
        exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.85f),
        modifier = Modifier
            .align(
                when (corner) {
                    Corner.TopStart -> Alignment.TopStart
                    Corner.TopEnd -> Alignment.TopEnd
                    Corner.BottomStart -> Alignment.BottomStart
                    // Above the orb and the timers.
                    Corner.BottomEnd -> Alignment.BottomEnd
                },
            )
            .padding(
                start = 20.dp, end = 20.dp, top = 20.dp,
                bottom = if (corner == Corner.BottomEnd) 110.dp else 20.dp,
            )
            .offset { IntOffset(drag.value.x.roundToInt(), drag.value.y.roundToInt()) }
            .onSizeChanged { window = it }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, amount ->
                        change.consume()
                        scope.launch { drag.snapTo(drag.value + amount) }
                    },
                    onDragEnd = {
                        // The window's centre, measured from the top left of the screen.
                        val cornerX = if (corner == Corner.TopStart || corner == Corner.BottomStart) 0f else area.width - window.width.toFloat()
                        val cornerY = if (corner == Corner.TopStart || corner == Corner.TopEnd) 0f else area.height - window.height.toFloat()
                        val cx = cornerX + drag.value.x + window.width / 2f
                        val cy = cornerY + drag.value.y + window.height / 2f
                        val left = cx < area.width / 2f
                        val top = cy < area.height / 2f
                        corner = when {
                            top && left -> Corner.TopStart
                            top -> Corner.TopEnd
                            left -> Corner.BottomStart
                            else -> Corner.BottomEnd
                        }
                        scope.launch { drag.snapTo(Offset.Zero) }
                    },
                )
            },
    ) {
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .windowGlass(RoundedCornerShape(28.dp))
                .pressable({ assistant.compact = false }),
        ) {
            Box {
                assistant.scene?.let { AnswerScene(it.kind, Modifier.matchParentSize(), intensity = 0.8f) }
                Row(
                    Modifier.padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    VoiceFace(
                        settings.face,
                        assistant.state,
                        levels = { VoiceLevels(assistant.level, assistant.low, assistant.mid, assistant.high) },
                        modifier = Modifier.size(64.dp),
                        classicRed = settings.classicRed,
                    )
                    Text(
                        caption(assistant),
                        style = MaterialTheme.typography.bodyLarge,
                        color = g.fg,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).widthIn(min = 160.dp),
                    )
                    RoundButton(Sym.close, { assistant.stop() }, size = 40.dp, filledIcon = false)
                }
            }
            val working = assistant.state == AssistantState.Thinking || assistant.state == AssistantState.Speaking
            assistant.steps().lastOrNull()?.takeIf { working }?.let { step ->
                StepList(listOf(step), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).widthIn(max = 340.dp))
            }
            if (assistant.pending != null) {
                Box(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) { Confirm(assistant, big = false) }
            }
        }
    }
    // Back in a corner whenever it is shown again.
    LaunchedEffect(visible) { if (!visible) drag.snapTo(Offset.Zero) }
}

/** Yes and No for a step that needs saying yes to, and a note when it needs a tap. */
@Composable
private fun Confirm(assistant: Assistant, big: Boolean) {
    val g = GlassTheme.colors
    val p = assistant.pending ?: return
    val pad = if (big) 30.dp else 22.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Yes",
                style = MaterialTheme.typography.labelLarge,
                color = g.wallBase,
                modifier = Modifier.pressable({ assistant.confirm(true) }).background(g.fg, PillShape).padding(horizontal = pad, vertical = 14.dp),
            )
            Text(
                "No",
                style = MaterialTheme.typography.labelLarge,
                color = g.fg,
                modifier = Modifier.pressable({ assistant.confirm(false) }).glass(PillShape).padding(horizontal = pad, vertical = 14.dp),
            )
        }
        if (p.tapOnly) {
            Text("This one needs a tap.", style = MaterialTheme.typography.bodyMedium, color = g.muted)
        }
    }
}

private fun caption(assistant: Assistant): String = when (assistant.state) {
    AssistantState.Listening -> assistant.partial.ifBlank { if (assistant.conversing) "I'm listening…" else "Listening…" }
    AssistantState.Thinking -> assistant.lines.lastOrNull { it.fromUser }?.text ?: "Thinking…"
    AssistantState.Speaking -> assistant.lines.lastOrNull { !it.fromUser }?.text.orEmpty()
    AssistantState.Idle -> assistant.lines.lastOrNull()?.text.orEmpty()
}
