package app.homeydash.ui.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.homeydash.agent.AgentSettings
import app.homeydash.agent.Memory
import app.homeydash.assistant.AssistantServices
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.RoundButton
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme
import kotlinx.coroutines.launch

/** Settings → Assistant → Memory: what it remembers, per person and for the house, and forgetting it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryPage(services: AssistantServices, settings: AgentSettings, update: ((AgentSettings) -> AgentSettings) -> Unit) {
    val g = GlassTheme.colors
    val scope = rememberCoroutineScope()
    val data by services.memory.data.collectAsState()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Remember conversations", settings.memory) { update { it.copy(memory = !it.memory) } }
    }
    Text(
        "${settings.persona.label} counts what each person asks for (music, lights, moods), and once a conversation has been quiet for 20 minutes " +
            "the AI squeezes it into a few short facts. None of it is in the prompt: only a question that needs it (\"som vanligt\", \"min favorit\", " +
            "\"igår\") gets the few facts it touches, so answers stay fast. A person's facts are only used for that person; " +
            "without voice or face recognition only the house's facts are used. Say \"kom ihåg att…\" or \"glöm…\" to change it by voice.",
        style = MaterialTheme.typography.bodyMedium,
        color = g.muted,
    )
    if (data.facts.isEmpty()) {
        Text("Nothing remembered yet.", style = MaterialTheme.typography.bodyMedium, color = g.muted)
        return
    }
    val now = System.currentTimeMillis()
    data.facts.groupBy { it.whoName.ifBlank { "Whole house" } }.toSortedMap(compareBy<String> { it == "Whole house" }.thenBy { it }).forEach { (who, facts) ->
        Label("$who (${facts.size})")
        facts.sortedByDescending { Memory.strength(it, now) }.forEach { f ->
            Row(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp)).padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(Memory.line(f), style = MaterialTheme.typography.bodyLarge, color = g.fg)
                    Text(
                        f.kind.label + if (f.localOnly) " · local AI only" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = g.muted,
                    )
                }
                RoundButton(Sym.close, { scope.launch { services.memory.delete(f.id) } }, size = 40.dp, filledIcon = false)
            }
        }
    }
    var sure by remember { mutableStateOf(false) }
    Text(
        if (sure) "Tap again to forget everything" else "Forget everything",
        style = MaterialTheme.typography.labelLarge,
        color = if (sure) g.danger else g.fg,
        modifier = Modifier.pressable({
            if (sure) scope.launch { services.memory.clear() }
            sure = !sure
        }).glass(PillShape).padding(horizontal = 18.dp, vertical = 10.dp),
    )
}
