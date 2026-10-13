package app.homeydash.ui.assistant

import androidx.compose.animation.AnimatedVisibility
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
 * Drawn over everything on the dashboard, sidebar included.
 */
@Composable
fun VoiceOverlay(assistant: Assistant, settings: AgentSettings) {
    val g = GlassTheme.colors
    AnimatedVisibility(
        visible = assistant.voiceActive,
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(320)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(g.wallBase.copy(alpha = if (g.isDark) 0.78f else 0.82f))
                .clickable(remember { MutableInteractionSource() }, indication = null) { assistant.stop() },
            contentAlignment = Alignment.Center,
        ) {
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
                assistant.pending?.let { p ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Yes",
                            style = MaterialTheme.typography.labelLarge,
                            color = g.wallBase,
                            modifier = Modifier.pressable({ assistant.confirm(true) }).background(g.fg, PillShape).padding(horizontal = 30.dp, vertical = 14.dp),
                        )
                        Text(
                            "No",
                            style = MaterialTheme.typography.labelLarge,
                            color = g.fg,
                            modifier = Modifier.pressable({ assistant.confirm(false) }).glass(PillShape).padding(horizontal = 30.dp, vertical = 14.dp),
                        )
                    }
                    if (p.tapOnly) {
                        Text("This one needs a tap.", style = MaterialTheme.typography.bodyMedium, color = g.muted)
                    }
                }
                Text(
                    if (settings.followUp == FollowUp.Always) "Say \"stop\" or tap anywhere to end" else "Tap anywhere to stop",
                    style = MaterialTheme.typography.bodyMedium,
                    color = g.muted,
                )
            }
        }
    }
}

private fun caption(assistant: Assistant): String = when (assistant.state) {
    AssistantState.Listening -> assistant.partial.ifBlank { if (assistant.conversing) "I'm listening…" else "Listening…" }
    AssistantState.Thinking -> assistant.lines.lastOrNull { it.fromUser }?.text ?: "Thinking…"
    AssistantState.Speaking -> assistant.lines.lastOrNull { !it.fromUser }?.text.orEmpty()
    AssistantState.Idle -> assistant.lines.lastOrNull()?.text.orEmpty()
}
