package app.homeydash.ui.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.homeydash.agent.AgentSettings
import app.homeydash.agent.AgentTools
import app.homeydash.agent.KnowHow
import app.homeydash.agent.KnowHowBook
import app.homeydash.agent.RuleBook
import app.homeydash.agent.Skill
import app.homeydash.assistant.AssistantServices
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.RoundButton
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.pressable
import app.homeydash.ui.dashboard.SettingsBox
import app.homeydash.ui.theme.GlassTheme
import kotlinx.coroutines.launch

/** The know-how drawn as a graph beside the memory's, or topic by topic. */
private enum class KnowHowView(val label: String) { Graph("Graph"), Topics("Topics") }

/**
 * Settings → What the assistant sees → Know-how: the rules every question gets, then each
 * topic of the know-how graph with what brings it into a question, its rules (the owner's
 * own can be added and removed here), the words it learned and its tools. A new topic can be
 * made here too, or by voice ("lär dig att…" goes to teach).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KnowHowTopics(services: AssistantServices, settings: AgentSettings, view: app.homeydash.agent.HomeView, tools: AgentTools) {
    val g = GlassTheme.colors
    val scope = rememberCoroutineScope()
    val data by services.knowHow.data.collectAsState()
    val skills = remember(data, settings.batteryReminders, view) { KnowHow.graph(data, settings.batteryReminders, view) }
    val specs = remember(tools) { tools.specs() }
    SwitchRow(
        "Only the know-how a question needs",
        "Each question gets the rules and tools of the topics it is about, found on the tablet in no time. The rest of the prompt stays short " +
            "and the same, so the local AI answers faster and follows the rules better. Off sends everything with every question.",
        settings.knowHow,
    ) { on -> services.settingsStore.update { it.copy(knowHow = on) } }
    var tab by remember { mutableStateOf(KnowHowView.Graph) }
    Segmented(KnowHowView.entries, tab, { it.label }) { tab = it }
    if (tab == KnowHowView.Graph) {
        val memory by services.memory.data.collectAsState()
        KnowHowGraphTab(skills, data, view, memory.facts, specs)
        return
    }
    SettingsBox("Every question") {
        val core = remember(settings) { services.agent.core(settings) }
        RuleBook.sentences(core).forEach { Bullet(it) }
        val always = specs.filter { s -> skills.none { s.name in it.tools } }
        if (always.isNotEmpty()) Text("Tools: " + always.joinToString { it.name }, style = MaterialTheme.typography.bodyMedium, color = g.muted)
    }
    skills.forEach { skill ->
        key(skill.id) { TopicBox(services, skill, data.extra[skill.id].orEmpty(), data.learned[skill.id].orEmpty(), data.used[skill.id] ?: 0, specs) }
    }
    SettingsBox("New topic") {
        var round by remember { mutableIntStateOf(0) }
        key(round) {
            var title by remember { mutableStateOf("") }
            var words by remember { mutableStateOf("") }
            var rule by remember { mutableStateOf("") }
            Hint(
                "Something new the assistant should know how to do, like watering the plants. It comes with a question when one of its words is said.",
            )
            Field("Name", "", hint = "Plant watering") { title = it }
            Field("Words", "", hint = "vattna, blommor, plants") { words = it }
            Field("Rule", "", hint = "The monstera gets water on Sundays") { rule = it }
            Text(
                "Add topic",
                style = MaterialTheme.typography.labelLarge,
                color = if (title.isNotBlank() && rule.isNotBlank()) g.fg else g.muted,
                modifier = Modifier.glass(PillShape).pressable({
                    if (title.isBlank() || rule.isBlank()) return@pressable
                    scope.launch {
                        services.knowHow.saveTopic(
                            Skill(
                                id = KnowHowBook.idFor(title),
                                title = title.trim(),
                                about = rule.trim().take(60),
                                words = words.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() },
                                rules = listOf(rule.trim()),
                                own = true,
                            ),
                        )
                        round++
                    }
                }).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopicBox(services: AssistantServices, skill: Skill, mine: List<String>, learned: List<String>, used: Int, specs: List<app.homeydash.agent.ToolSpec>) {
    val g = GlassTheme.colors
    val scope = rememberCoroutineScope()
    SettingsBox(skill.title + if (skill.own) " (yours)" else if (skill.fromHomey) " (from Homey)" else "") {
        Hint(
            buildString {
                append("Comes with a question about ").append(skill.about.ifBlank { skill.title.lowercase() })
                if (skill.classes.isNotEmpty()) append(", or naming a device of its kind")
                append(". ")
                append(if (used == 0) "Not used yet." else "Used $used time${if (used == 1) "" else "s"}.")
            },
        )
        val built = skill.words.filterNot { it in learned }
        if (built.isNotEmpty()) Text("Words: " + built.joinToString { it.trimEnd('*') }, style = MaterialTheme.typography.bodySmall, color = g.muted)
        if (learned.isNotEmpty()) {
            Text("Learned words (tap to remove)", style = MaterialTheme.typography.labelMedium, color = g.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                learned.forEach { w ->
                    Text(
                        w,
                        style = MaterialTheme.typography.labelLarge,
                        color = g.fg,
                        modifier = Modifier.glass(PillShape).pressable({ scope.launch { services.knowHow.unlearn(skill.id, w) } })
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }
        skill.rules.forEach { rule ->
            if (rule in mine) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("•", style = MaterialTheme.typography.bodyMedium, color = g.ok)
                    Text(rule, style = MaterialTheme.typography.bodyMedium, color = g.fg, modifier = Modifier.weight(1f))
                    RoundButton(Sym.close, { scope.launch { services.knowHow.removeRule(skill.id, rule) } }, size = 32.dp, filledIcon = false, contentDescription = "Remove")
                }
            } else {
                Bullet(rule)
            }
        }
        specs.filter { it.name in skill.tools }.forEach { tool ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Tool: " + tool.name, style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace), color = g.fg)
                Text(tool.description, style = MaterialTheme.typography.bodyMedium, color = g.muted)
            }
        }
        var round by remember { mutableIntStateOf(0) }
        key(round) {
            var rule by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { Field("Your rule", "", hint = "Added to this topic") { rule = it } }
                RoundButton(Sym.add, {
                    if (rule.isNotBlank()) scope.launch {
                        services.knowHow.addRule(skill.id, rule)
                        round++
                    }
                }, size = 40.dp, filledIcon = false, contentDescription = "Add rule")
            }
        }
        if (skill.own) {
            Text(
                "Delete this topic",
                style = MaterialTheme.typography.labelLarge,
                color = g.danger,
                modifier = Modifier.pressable({ scope.launch { services.knowHow.deleteTopic(skill.id) } }).padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Bullet(text: String) {
    val g = GlassTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium, color = g.muted)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = g.fg)
    }
}
