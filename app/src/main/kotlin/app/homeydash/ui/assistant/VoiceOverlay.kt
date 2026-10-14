package app.homeydash.ui.assistant

import androidx.compose.animation.AnimatedVisibility
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
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme

/**
 * The chosen face over the whole screen while someone talks to the assistant, with what
 * was heard or is being said underneath. Tapping anywhere ends the conversation.
 * Drawn over everything on the dashboard, sidebar included. When the assistant opens a
 * page, or the minus is tapped, it shrinks to a small window in a corner instead, which
 * can be dragged to another corner and tapped to grow again; the page under it stays usable.
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
        CompactVoice(assistant, settings, visible = assistant.voiceActive && compact)
    }
}

/** Where the small window sits; it snaps to the corner nearest to where it was dropped. */
private enum class Corner { TopStart, TopEnd, BottomStart, BottomEnd }

@Composable
private fun BoxScope.CompactVoice(assistant: Assistant, settings: AgentSettings, visible: Boolean) {
    val g = GlassTheme.colors
    var corner by rememberSaveable { mutableStateOf(Corner.TopEnd) }
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
                .clip(RoundedCornerShape(28.dp))
                .background(g.wallBase.copy(alpha = if (g.isDark) 0.88f else 0.9f), RoundedCornerShape(28.dp))
                .glass(RoundedCornerShape(28.dp))
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
