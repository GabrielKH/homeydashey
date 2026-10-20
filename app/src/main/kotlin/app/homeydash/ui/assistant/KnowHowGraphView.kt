package app.homeydash.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.homeydash.agent.Fact
import app.homeydash.agent.HomeView
import app.homeydash.agent.KnowHowData
import app.homeydash.agent.KnowHowGraph
import app.homeydash.agent.Memory
import app.homeydash.agent.Skill
import app.homeydash.agent.ToolSpec
import app.homeydash.ui.components.RoundButton
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.glass
import app.homeydash.ui.theme.GlassColors
import app.homeydash.ui.theme.GlassTheme

/** A know-how dot's colour: topics by where they come from, facts by their memory category. */
private fun dotColor(n: KnowHowGraph.Node, g: GlassColors): Color = when (n.type) {
    KnowHowGraph.Type.Topic -> when (n.source) {
        KnowHowGraph.Source.Homey -> g.ok
        KnowHowGraph.Source.Yours -> if (g.isDark) Color(0xFFFFD54F) else Color(0xFFB8860B)
        else -> if (g.isDark) Color(0xFFB39DDB) else Color(0xFF6A4FC2)
    }
    KnowHowGraph.Type.Tool -> g.muted
    KnowHowGraph.Type.Device -> if (g.isDark) Color(0xFF90A4AE) else Color(0xFF546E7A)
    KnowHowGraph.Type.Fact -> n.kind?.let { kindColor(it, g) } ?: g.cool
    KnowHowGraph.Type.Person -> g.fg
}

/**
 * Know-how → Graph: the topics as big dots, joined to their tools, the Homey devices they are
 * about and the topics they bring along, and, with memory on, the facts that name those devices
 * or bring those topics, hanging off their person. Tap a dot to see what it holds.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KnowHowGraphTab(skills: List<Skill>, data: KnowHowData, view: HomeView, facts: List<Fact>, specs: List<ToolSpec>) {
    val g = GlassTheme.colors
    var withMemory by remember { mutableStateOf(true) }
    val graph = remember(skills, data.used, view, facts, withMemory) {
        KnowHowGraph.build(skills, data.used, view, if (withMemory) facts else emptyList())
    }
    var selected by remember { mutableStateOf<String?>(null) }
    val dots = remember(graph, g) {
        graph.nodes.map { n ->
            val hub = n.type == KnowHowGraph.Type.Topic || n.type == KnowHowGraph.Type.Person
            GraphDot(n.key, n.label, hub, KnowHowGraph.weight(n), dotColor(n, g), faded = n.type == KnowHowGraph.Type.Topic && n.used == 0)
        }
    }
    val lines = remember(graph, g) {
        graph.edges.map { e ->
            when (e.type) {
                KnowHowGraph.Type.Tool -> GraphLine(e.a, e.b, g.muted.copy(alpha = 0.3f), 1f)
                KnowHowGraph.Type.Topic -> GraphLine(e.a, e.b, dotColor(graph.nodes[e.b], g).copy(alpha = 0.6f), 2f)
                else -> GraphLine(e.a, e.b, dotColor(graph.nodes[e.b], g).copy(alpha = 0.55f), 1.4f)
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("With memory", withMemory) { withMemory = !withMemory }
    }
    val count = { t: KnowHowGraph.Type -> graph.nodes.count { it.type == t } }
    Text(
        "${count(KnowHowGraph.Type.Topic)} topics · ${count(KnowHowGraph.Type.Tool)} tools · ${count(KnowHowGraph.Type.Device)} devices" +
            if (withMemory) " · ${count(KnowHowGraph.Type.Fact)} memory facts tied in" else "",
        style = MaterialTheme.typography.bodyMedium,
        color = g.muted,
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth > 760.dp
        val canvas = @Composable { m: Modifier ->
            Box(m.height(540.dp).glass(RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))) {
                GraphCanvas(dots, lines, graph, { KnowHowGraph.layout(graph) }, selected, { selected = it }, Modifier.fillMaxSize())
                Text(
                    "Purple = built in, green = from Homey, gold = yours. Grey = tools, slate = devices, other colours = memory facts. " +
                        "Bigger topic = used more often, faded = never used. Pinch to zoom, drag to move.",
                    style = MaterialTheme.typography.bodySmall,
                    color = g.muted,
                    modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
                )
            }
        }
        val detail = @Composable { m: Modifier -> KnowHowDetail(graph, skills, data, view, facts, specs, selected, { selected = it }, m) }
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                canvas(Modifier.weight(1f))
                detail(Modifier.width(320.dp))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                canvas(Modifier.fillMaxWidth())
                detail(Modifier.fillMaxWidth())
            }
        }
    }
}

/** Beside the graph: what the picked dot holds, or the most used topics when nothing is picked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KnowHowDetail(
    graph: KnowHowGraph.Graph,
    skills: List<Skill>,
    data: KnowHowData,
    view: HomeView,
    facts: List<Fact>,
    specs: List<ToolSpec>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier,
) {
    val g = GlassTheme.colors
    val i = graph.nodes.indexOfFirst { it.key == selected }
    Column(modifier.glass(RoundedCornerShape(22.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (i < 0) {
            Text("Most used topics", style = MaterialTheme.typography.titleMedium, color = g.fg)
            Text("Tap a dot to see what it holds.", style = MaterialTheme.typography.bodyMedium, color = g.muted)
            val top = graph.nodes.filter { it.type == KnowHowGraph.Type.Topic && it.used > 0 }.sortedByDescending { it.used }.take(6)
            if (top.isEmpty()) Text("No topic has come with a question yet.", style = MaterialTheme.typography.bodyMedium, color = g.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                top.forEach { n -> Chip("${n.label} ${n.used}", false) { onSelect(n.key) } }
            }
            return@Column
        }
        val node = graph.nodes[i]
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(14.dp).background(dotColor(node, g), CircleShape))
            Text(node.label, style = MaterialTheme.typography.titleMedium, color = g.fg, modifier = Modifier.weight(1f))
            RoundButton(Sym.close, { onSelect(null) }, size = 40.dp, filledIcon = false, contentDescription = "Close")
        }
        when (node.type) {
            KnowHowGraph.Type.Topic -> skills.firstOrNull { it.id == node.ref }?.let { s ->
                val learned = data.learned[s.id].orEmpty()
                GraphNote(
                    when (node.source) {
                        KnowHowGraph.Source.Homey -> "Made from what is in Homey"
                        KnowHowGraph.Source.Yours -> "Your own topic"
                        else -> "Built in"
                    } + " · " + if (node.used == 0) "not used yet" else "used ${node.used} time${if (node.used == 1) "" else "s"}",
                )
                if (s.about.isNotBlank()) GraphNote("About " + s.about + ".")
                val words = s.words.filterNot { it in learned }.map { it.trimEnd('*') }
                if (words.isNotEmpty()) GraphNote("Words: " + words.take(24).joinToString() + if (words.size > 24) "…" else "")
                if (learned.isNotEmpty()) GraphNote("Learned words: " + learned.joinToString())
                Label("Rules")
                s.rules.forEach { GraphText("• $it") }
            }
            KnowHowGraph.Type.Tool -> specs.firstOrNull { it.name == node.ref }?.let { GraphText(it.description) }
                ?: GraphNote("Handled by the assistant itself.")
            KnowHowGraph.Type.Device -> view.devices.firstOrNull { it.id == node.ref }?.let { d ->
                GraphNote(listOfNotNull(d.room, d.deviceClass, d.kind).joinToString(" · "))
            }
            KnowHowGraph.Type.Fact -> facts.firstOrNull { it.id == node.ref }?.let { f ->
                GraphText(Memory.line(f))
                GraphNote("${f.kind.label.replaceFirstChar { it.uppercase() }} · ${f.whoName.ifBlank { "Whole house" }} · used ${f.used} times. Edit or forget it under Memory.")
            }
            KnowHowGraph.Type.Person -> GraphNote("${graph.neighbours(i).size} of their memory facts touch the know-how.")
        }
        val near = graph.neighbours(i).map { graph.nodes[it] }.sortedBy { it.type.ordinal }
        if (near.isNotEmpty()) {
            Label("Connected to")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                near.take(40).forEach { n -> Chip(n.label, false) { onSelect(n.key) } }
            }
        }
    }
}

@Composable
private fun GraphNote(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = GlassTheme.colors.muted)

@Composable
private fun GraphText(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = GlassTheme.colors.fg)
