package app.homeydash.ui.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.homeydash.agent.KitchenTimer
import app.homeydash.agent.Timers
import app.homeydash.assistant.Assistant
import app.homeydash.ui.components.PanelShape
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme
import kotlinx.coroutines.delay

/** Draws the running kitchen timers; every page header calls it first among its chips. */
val LocalTimerChip = compositionLocalOf<@Composable () -> Unit> { {} }

/**
 * The kitchen timers as one glass pill beside each page's chips (Goodnight, lights, music
 * on the overview), clear of the assistant's island at the top: the timer
 * that rings next with a "+2" for the others, which drops down a list grouped by recipe. A
 * ringing timer turns it red and pulsing until it is stopped or given more time.
 */
@Composable
fun TimerIsland(assistant: Assistant, modifier: Modifier = Modifier) {
    val board = assistant.timers
    val timers by board.timers.collectAsState()
    if (timers.isEmpty()) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(TICK_MS)
        }
    }
    var open by remember { mutableStateOf(false) }
    // Any touch inside the list keeps it open; left alone it closes by itself.
    var touched by remember { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(open, touched, renaming) {
        if (open && renaming == null) {
            delay(CLOSE_AFTER_MS)
            open = false
        }
    }
    val ordered = Timers.ordered(timers, now)
    val first = ordered.first()

    val drop = with(LocalDensity.current) { IntOffset(0, (PILL_HEIGHT + 8.dp).roundToPx()) }
    Box(modifier) {
        if (first.ringing) {
            RingingPill(
                first,
                onStop = { board.stop(first.id) },
                onAdd = { minutes -> board.addTo(first.id, minutes * 60) },
            )
        } else {
            IslandPill(
                first,
                now,
                others = ordered.size - 1,
                onClick = { open = true; touched++ },
                onStop = { board.stop(first.id) },
            )
        }
        // The list drops down over the cards below, lined up with the pill's right edge.
        if (open && (ordered.size > 1 || !first.ringing)) Popup(
            alignment = Alignment.TopEnd,
            offset = drop,
            onDismissRequest = { open = false; renaming = null },
            properties = PopupProperties(focusable = true),
        ) {
            TimerList(
                timers = timers,
                now = now,
                renaming = renaming,
                onTouch = { touched++ },
                onRename = { renaming = it; touched++ },
                onRenamed = { id, name -> assistant.renameTimer(id, name); renaming = null; touched++ },
                onAdd = { board.addTo(it.id, 60); touched++ },
                onPause = { board.pauseId(it.id, !it.paused); touched++ },
                onStop = { board.stop(it.id); touched++ },
                onStopGroup = { group -> board.cancelAll(group); touched++ },
                onStopAll = { board.cancelAll(null); open = false },
            )
        }
    }
}

@Composable
private fun IslandPill(timer: KitchenTimer, now: Long, others: Int, onClick: () -> Unit, onStop: () -> Unit) {
    val g = GlassTheme.colors
    Row(
        Modifier
            .height(PILL_HEIGHT)
            .pressable(onClick)
            .glass(PillShape)
            .animateContentSize()
            .padding(start = 7.dp, end = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TimerRing(timer.progress(now), if (timer.paused) g.muted else g.on, size = 30.dp)
        Text(
            timer.name(),
            style = MaterialTheme.typography.titleMedium,
            color = g.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp),
        )
        TimeText(timer, now, big = false)
        if (others > 0) {
            Box(
                Modifier.clip(PillShape).background(g.on).padding(horizontal = 10.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("+$others", style = MaterialTheme.typography.labelLarge, color = g.onInk, fontWeight = FontWeight.Bold)
            }
        } else {
            SmallButton(Sym.close, onStop)
        }
    }
}

@Composable
private fun RingingPill(timer: KitchenTimer, onStop: () -> Unit, onAdd: (Int) -> Unit) {
    val g = GlassTheme.colors
    val pulse by rememberInfiniteTransition(label = "ring").animateFloat(
        initialValue = 0.18f,
        targetValue = 0.42f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "pulse",
    )
    Row(
        Modifier
            .height(PILL_HEIGHT)
            .glass(PillShape)
            .drawBehind { drawRect(g.danger.copy(alpha = pulse)) }
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SymIcon(Sym.timer, filled = true, size = 22.dp, tint = g.fg)
        Text(
            (timer.label?.let { "$it is done" } ?: "Timer done"),
            style = MaterialTheme.typography.titleMedium,
            color = g.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp).padding(end = 4.dp),
        )
        TextButton("+1 min") { onAdd(1) }
        TextButton("+5 min") { onAdd(5) }
        TextButton("Stop", strong = true, onClick = onStop)
    }
}

@Composable
private fun TimerList(
    timers: List<KitchenTimer>,
    now: Long,
    renaming: Long?,
    onTouch: () -> Unit,
    onRename: (Long) -> Unit,
    onRenamed: (Long, String) -> Unit,
    onAdd: (KitchenTimer) -> Unit,
    onPause: (KitchenTimer) -> Unit,
    onStop: (KitchenTimer) -> Unit,
    onStopGroup: (String) -> Unit,
    onStopAll: () -> Unit,
) {
    val g = GlassTheme.colors
    val groups = Timers.groups(timers, now)
    Column(
        Modifier
            .width(460.dp)
            .heightIn(max = 520.dp)
            .glass(PanelShape)
            .pressable(onTouch)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        groups.forEach { group ->
            val recipe = group.name
            // A lone group of plain timers needs no heading.
            if (recipe != null || groups.size > 1) {
                Row(Modifier.fillMaxWidth().padding(start = 6.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (recipe ?: "Other timers").uppercase() + if (group.timers.size > 1) "  ·  ${group.timers.size}" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = g.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (recipe != null && group.timers.size > 1) {
                        Text(
                            "Stop all",
                            style = MaterialTheme.typography.labelLarge,
                            color = g.muted,
                            modifier = Modifier.clip(PillShape).pressable({ onStopGroup(recipe) }).padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            group.timers.forEach { t ->
                TimerRow(
                    t, now,
                    renaming = renaming == t.id,
                    onRename = { onRename(t.id) },
                    onRenamed = { onRenamed(t.id, it) },
                    onAdd = { onAdd(t) },
                    onPause = { onPause(t) },
                    onStop = { onStop(t) },
                )
            }
        }
        if (timers.size > 1) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                TextButton("Stop all timers", onClick = onStopAll)
            }
        }
    }
}

@Composable
private fun TimerRow(
    timer: KitchenTimer,
    now: Long,
    renaming: Boolean,
    onRename: () -> Unit,
    onRenamed: (String) -> Unit,
    onAdd: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
) {
    val g = GlassTheme.colors
    var text by remember(renaming) { mutableStateOf(timer.label.orEmpty()) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TimerRing(timer.progress(now), if (timer.ringing) g.danger else if (timer.paused) g.muted else g.on, size = 30.dp)
        Box(Modifier.weight(1f)) {
            if (renaming) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(color = g.fg),
                    cursorBrush = SolidColor(g.fg),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onRenamed(text) }),
                    modifier = Modifier.fillMaxWidth().glass(PillShape).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            } else {
                Row(Modifier.pressable(onRename), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        timer.label ?: "Timer",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (timer.label == null) g.muted else g.fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    SymIcon(Sym.edit, size = 14.dp, tint = g.muted)
                }
            }
        }
        TimeText(timer, now, big = false)
        if (renaming) {
            SmallButton(Sym.check, { onRenamed(text) }, primary = true)
        } else {
            SmallButton(Sym.add, onAdd)
            if (!timer.ringing) SmallButton(if (timer.paused) Sym.play else Sym.pause, onPause)
            SmallButton(Sym.close, onStop)
        }
    }
}

@Composable
private fun TimeText(timer: KitchenTimer, now: Long, big: Boolean) {
    val g = GlassTheme.colors
    val style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
    Text(
        if (timer.ringing) "0:00" else Timers.clock(timer.leftMs(now)),
        style = style.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
        color = if (timer.paused) g.muted else if (timer.ringing) g.danger else g.fg,
    )
}

/** The ring that drains as the time runs out. */
@Composable
private fun TimerRing(progress: Float, color: Color, size: Dp) {
    val g = GlassTheme.colors
    Canvas(Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.16f
        val inset = stroke / 2
        val arc = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(g.onSoft, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
        drawArc(color, -90f, 360f * progress, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun SmallButton(icon: app.homeydash.ui.components.Ic, onClick: () -> Unit, primary: Boolean = false) {
    val g = GlassTheme.colors
    Box(
        Modifier
            .size(36.dp)
            .pressable(onClick)
            .then(if (primary) Modifier.clip(CircleShape).background(g.on) else Modifier.glass(CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        SymIcon(icon, size = 16.dp, tint = if (primary) g.onInk else g.fg)
    }
}

@Composable
private fun TextButton(text: String, strong: Boolean = false, onClick: () -> Unit) {
    val g = GlassTheme.colors
    Box(
        Modifier
            .heightIn(min = 38.dp)
            .pressable(onClick)
            .then(if (strong) Modifier.clip(PillShape).background(g.danger) else Modifier.glass(PillShape))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (strong) Color.White else g.fg, fontWeight = FontWeight.Bold)
    }
}

private const val TICK_MS = 250L
/** As tall as the chips beside it in the overview's header. */
private val PILL_HEIGHT = 44.dp
private const val CLOSE_AFTER_MS = 8_000L
