package app.homeydash.ui.assistant

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.homeydash.agent.AgentSettings
import app.homeydash.assistant.Assistant
import app.homeydash.assistant.AssistantState
import app.homeydash.assistant.KitchenTimer
import app.homeydash.assistant.Line
import app.homeydash.ui.components.PanelShape
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.RoundButton
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.onBrush
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme
import kotlinx.coroutines.delay

/**
 * The assistant over the dashboard: a glass orb in the corner (tap to talk), running
 * kitchen timers above it, and the conversation panel when it is open.
 */
@Composable
fun BoxScope.AssistantLayer(
    assistant: Assistant,
    settings: AgentSettings,
    onChooseSpeaker: (String?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (!settings.enabled) return
    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) assistant.listen()
    }
    val talk = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            assistant.toggleListening()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        Modifier.align(Alignment.BottomEnd).padding(4.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AnimatedVisibility(
            visible = assistant.open && !assistant.voiceActive,
            enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.92f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)),
            exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.92f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)),
        ) {
            Panel(assistant, settings, talk, onChooseSpeaker, onOpenSettings)
        }
        assistant.timers.forEach { TimerChip(it) { assistant.cancelTimer(it.label) } }
        Orb(
            state = assistant.state,
            // Read while drawing, so the voice level doesn't redraw the whole panel 60 times a second.
            level = { assistant.level },
            onClick = talk,
            onLongClick = { assistant.open = !assistant.open },
        )
    }
}

/** Runs face recognition while the dashboard is on screen, when it is switched on and someone is enrolled. */
@Composable
fun FaceWatch(services: app.homeydash.assistant.AssistantServices, settings: AgentSettings) {
    val people by services.faces.people.collectAsState()
    val on = settings.enabled && settings.access(app.homeydash.agent.Secret.Faces) != app.homeydash.agent.Access.Off && people.any { it.samples.isNotEmpty() }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    if (on) {
        androidx.compose.runtime.DisposableEffect(owner) {
            services.faces.start(owner)
            onDispose { services.faces.stop() }
        }
    }
}

/** The round glass button: a sparkle at rest, a breathing waveform while listening. */
@Composable
private fun Orb(state: AssistantState, level: () -> Float, onClick: () -> Unit, onLongClick: () -> Unit) {
    val g = GlassTheme.colors
    val active = state != AssistantState.Idle
    val pulse = rememberInfiniteTransition(label = "orb")
    val breathe by pulse.animateFloat(1f, 1.08f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val spin by pulse.animateFloat(0f, 360f, infiniteRepeatable(tween(2400)), label = "spin")
    // The level is read here, so only the orb recomposes as the voice changes.
    val voice by animateFloatAsState(if (active) level() else 0f, spring(stiffness = 400f), label = "level")
    Box(
        Modifier
            .size(72.dp)
            .graphicsLayer {
                val scale = when (state) {
                    AssistantState.Listening -> 1f + voice * 0.18f
                    AssistantState.Thinking, AssistantState.Speaking -> breathe
                    AssistantState.Idle -> 1f
                }
                scaleX = scale
                scaleY = scale
            }
            .pressable(onClick, onLongClick)
            .drawBehind {
                if (active) {
                    // A soft halo that follows the voice.
                    drawCircle(g.on.copy(alpha = 0.25f + voice * 0.3f), radius = size.minDimension / 2 * (1.12f + voice * 0.25f))
                }
            }
            .then(if (active) Modifier.background(onBrush(), CircleShape) else Modifier.glass(CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        val icon = when (state) {
            AssistantState.Idle -> Sym.autoAwesome
            AssistantState.Listening -> Sym.mic
            AssistantState.Thinking -> Sym.autoAwesome
            AssistantState.Speaking -> Sym.waveform
        }
        SymIcon(
            icon,
            filled = active,
            size = 32.dp,
            tint = if (active) g.onInk else g.fg,
            modifier = if (state == AssistantState.Thinking) Modifier.graphicsLayer { rotationZ = spin } else Modifier,
        )
    }
}

@Composable
private fun Panel(
    assistant: Assistant,
    settings: AgentSettings,
    talk: () -> Unit,
    onChooseSpeaker: (String?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val g = GlassTheme.colors
    Column(
        Modifier
            .widthIn(max = 460.dp)
            .fillMaxWidth()
            .background(g.wallBase.copy(alpha = if (g.isDark) 0.9f else 0.92f), PanelShape)
            .glass()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(40.dp).background(onBrush(), CircleShape), contentAlignment = Alignment.Center) {
                SymIcon(Sym.autoAwesome, filled = true, size = 22.dp, tint = g.onInk)
            }
            Column(Modifier.weight(1f)) {
                Text("Assistant", style = MaterialTheme.typography.titleLarge, color = g.fg)
                val last = assistant.lines.lastOrNull { !it.fromUser && it.brain != null }
                Text(
                    last?.let { (if (it.local == true) "Local · " else "Internet · ") + it.brain } ?: modeText(settings),
                    style = MaterialTheme.typography.bodyMedium,
                    color = g.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RoundButton(Sym.settings, onOpenSettings, size = 40.dp, filledIcon = false)
            RoundButton(Sym.close, { assistant.stop(); assistant.open = false }, size = 40.dp, filledIcon = false)
        }
        SpeakerPicker(assistant, settings, onChooseSpeaker)

        val list = rememberLazyListState()
        val count = assistant.lines.size + (if (assistant.partial.isNotBlank()) 1 else 0)
        LaunchedEffect(count) { if (count > 0) list.animateScrollToItem(count - 1) }
        if (assistant.lines.isEmpty() && assistant.partial.isBlank()) {
            Text(
                "Tap the orb and speak, in Swedish or English. Try \"Släck i köket\", \"Sätt en timer på 10 minuter\" or \"What's the weather tomorrow?\"",
                style = MaterialTheme.typography.bodyLarge,
                color = g.muted,
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(assistant.lines) { _, line -> Bubble(line) }
                if (assistant.partial.isNotBlank()) item { Bubble(Line(true, assistant.partial), partial = true) }
            }
        }
        assistant.pending?.let { p ->
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(p.question, style = MaterialTheme.typography.titleMedium, color = g.fg)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Yes",
                        style = MaterialTheme.typography.labelLarge,
                        color = g.wallBase,
                        modifier = Modifier.pressable({ assistant.confirm(true) }).background(g.fg, PillShape).padding(horizontal = 26.dp, vertical = 12.dp),
                    )
                    Text(
                        "No",
                        style = MaterialTheme.typography.labelLarge,
                        color = g.fg,
                        modifier = Modifier.pressable({ assistant.confirm(false) }).glass(PillShape).padding(horizontal = 26.dp, vertical = 12.dp),
                    )
                }
            }
        }
        InputRow(assistant, talk)
    }
}

private fun modeText(settings: AgentSettings): String = when {
    settings.brains().isEmpty() -> "Not set up yet: open Settings"
    else -> settings.mode.label + " · " + settings.brains().joinToString(" then ") { it.provider.label }
}

@Composable
private fun SpeakerPicker(assistant: Assistant, settings: AgentSettings, onChoose: (String?) -> Unit) {
    val g = GlassTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val speakers = remember(expanded) { assistant.speakers() }
    val current = speakers.firstOrNull { it.first.id == settings.speakerId }?.first?.name ?: "This tablet"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.pressable({ expanded = !expanded }).glass(PillShape).padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SymIcon(Sym.speaker, size = 18.dp, tint = g.fg)
            Text("Answers on $current", style = MaterialTheme.typography.labelLarge, color = g.fg)
            SymIcon(Sym.caretUpDown, size = 16.dp, tint = g.muted)
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).padding(6.dp)) {
                (listOf<Pair<String?, String>>(null to "This tablet") + speakers.map { it.first.id to it.first.name }).forEach { (id, name) ->
                    val selected = id == settings.speakerId
                    Row(
                        Modifier.fillMaxWidth().pressable({ onChoose(id); expanded = false })
                            .then(if (selected) Modifier.background(g.fg.copy(alpha = 0.1f), RoundedCornerShape(12.dp)) else Modifier)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, style = MaterialTheme.typography.bodyLarge, color = g.fg, modifier = Modifier.weight(1f))
                        if (selected) SymIcon(Sym.check, size = 18.dp, tint = g.fg)
                    }
                }
                if (speakers.isEmpty()) {
                    Text(
                        "No speaker with a \"play URL\" or \"say\" Flow card was found. The API key needs Flow access.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = g.muted,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Bubble(line: Line, partial: Boolean = false) {
    val g = GlassTheme.colors
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (line.fromUser) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val shape = RoundedCornerShape(20.dp)
        Text(
            line.text,
            style = MaterialTheme.typography.bodyLarge.let { if (partial) it.copy(fontStyle = FontStyle.Italic) else it },
            color = when {
                line.error -> g.danger
                line.fromUser -> g.wallBase
                else -> g.fg
            },
            modifier = Modifier
                .widthIn(max = 340.dp)
                .then(if (line.fromUser) Modifier.background(g.fg.copy(alpha = if (partial) 0.55f else 0.9f), shape) else Modifier.glass(shape))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
        if (line.actions.isNotEmpty()) {
            Text(
                line.actions.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = g.muted,
                modifier = Modifier.widthIn(max = 340.dp).padding(horizontal = 6.dp),
            )
        }
        if (line.speechSettings) SpeechSettingsButton { app.homeydash.assistant.SpeechInput.openSettings(context) }
    }
}

/** Opens Android's speech settings, where the recognition languages are added. */
@Composable
fun SpeechSettingsButton(onClick: () -> Unit) {
    Text(
        "Speech settings",
        style = MaterialTheme.typography.labelLarge,
        color = GlassTheme.colors.fg,
        modifier = Modifier.pressable(onClick).glass(PillShape).padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

@Composable
private fun InputRow(assistant: Assistant, talk: () -> Unit) {
    val g = GlassTheme.colors
    var text by remember { mutableStateOf("") }
    val submit = {
        if (text.isNotBlank()) {
            assistant.send(text)
            text = ""
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).glass(PillShape).padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (text.isEmpty()) Text("Type a question", style = MaterialTheme.typography.bodyLarge, color = g.muted)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = g.fg),
                cursorBrush = SolidColor(g.fg),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (text.isNotBlank()) {
            RoundButton(Sym.send, submit, size = 46.dp, primary = true)
        } else {
            val busy = assistant.state != AssistantState.Idle
            RoundButton(if (busy) Sym.stop else Sym.mic, { if (busy) assistant.stop() else talk() }, size = 46.dp, primary = true)
        }
    }
}

@Composable
private fun TimerChip(timer: KitchenTimer, onCancel: () -> Unit) {
    val g = GlassTheme.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timer.id) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val left = ((timer.endsAt - now) / 1000).coerceAtLeast(0)
    val progress = (left.toFloat() / timer.seconds).coerceIn(0f, 1f)
    Row(
        Modifier
            .clip(PillShape)
            .glass(PillShape)
            .drawBehind {
                drawRect(
                    Brush.horizontalGradient(listOf(g.on.copy(alpha = 0.35f), g.on.copy(alpha = 0.15f))),
                    size = size.copy(width = size.width * progress),
                )
            }
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "${timer.label ?: "Timer"}  %d:%02d".format(left / 60, left % 60),
            style = MaterialTheme.typography.titleMedium,
            color = g.fg,
        )
        Box(Modifier.size(34.dp).alpha(0.8f).pressable(onCancel).glass(CircleShape), contentAlignment = Alignment.Center) {
            SymIcon(Sym.close, size = 16.dp, tint = g.fg)
        }
    }
}
