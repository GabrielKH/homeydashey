package app.homeydash.ui.assistant

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.homeydash.agent.AgentStep
import app.homeydash.agent.StepState
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.theme.GlassTheme

/**
 * What the assistant is doing, one line per step: a spinner while it runs, a tick when
 * it's done, and a warning with what went wrong when it isn't. The last [max] steps show.
 */
@Composable
fun StepList(
    steps: List<AgentStep>,
    modifier: Modifier = Modifier,
    max: Int = 6,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    icon: Dp = 16.dp,
) {
    if (steps.isEmpty()) return
    val g = GlassTheme.colors
    Column(modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val shown = steps.takeLast(max)
        if (steps.size > shown.size) {
            Text("${steps.size - shown.size} more before…", style = style, color = g.muted)
        }
        shown.forEach { step ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.padding(top = 2.dp).size(icon), contentAlignment = Alignment.Center) {
                    when (step.state) {
                        StepState.Running -> CircularProgressIndicator(Modifier.size(icon - 2.dp), color = g.fg, strokeWidth = 2.dp)
                        StepState.Done -> SymIcon(Sym.check, size = icon, tint = g.ok)
                        StepState.Failed -> SymIcon(Sym.error, size = icon, tint = g.danger)
                    }
                }
                Column {
                    Text(
                        step.text,
                        style = style,
                        color = if (step.state == StepState.Running) g.fg else g.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    step.detail?.takeIf { step.state == StepState.Failed }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = g.danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
