package app.homeydash.ui.assistant

import app.homeydash.ui.dashboard.SettingsBox
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
import app.homeydash.agent.Access
import app.homeydash.agent.AgentSettings
import app.homeydash.agent.Fact
import app.homeydash.agent.FactKind
import app.homeydash.agent.Secret
import app.homeydash.agent.Memory
import app.homeydash.assistant.AssistantServices
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.RoundButton
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import app.homeydash.agent.MemoryData
import app.homeydash.agent.MemoryGraph
import app.homeydash.agent.RecallVia
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The three ways to look at the memory. */
private enum class MemoryView(val label: String) { Graph("Graph"), List("List"), Recent("Recently used") }

/**
 * Settings → Assistant → Memory: what it remembers, per person and for the house, how often each
 * fact is used, and forgetting it. Graph draws it like Graphify, List has every fact, and
 * Recently used shows what was handed to the AI for each question, to find a fact that keeps
 * leading answers astray.
 */
@Composable
internal fun MemoryPage(services: AssistantServices, settings: AgentSettings, update: ((AgentSettings) -> AgentSettings) -> Unit) {
    val g = GlassTheme.colors
    val scope = rememberCoroutineScope()
    val data by services.memory.data.collectAsState()
    SettingsBox("Memory") {
        Segmented(Access.entries, settings.access(Secret.Memory), { it.label }) { a -> update { it.copy(secrets = it.secrets + (Secret.Memory to a)) } }
        Text(
            "${settings.persona.label} counts what each person asks for (music, lights, moods), and once a conversation has been quiet for 20 minutes " +
                "the AI squeezes it into a few short facts. People can also write about themselves under People: birthday, likes, dislikes and more. \"Local AI only\" keeps all of it away from the internet AI, which then neither learns nor hears any of it. None of it is in the prompt: only a question that needs it (\"som vanligt\", \"min favorit\", " +
                "\"igår\") gets the few facts it touches, so answers stay fast. What someone said or keeps private is only used for them; " +
                "without voice or face recognition only the house's facts are used. Say \"kom ihåg att…\" or \"glöm…\" to change it by voice.",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
    }
    if (data.facts.isEmpty() && data.recalls.isEmpty()) {
        Text("Nothing remembered yet.", style = MaterialTheme.typography.bodyMedium, color = g.muted)
        return
    }
    var view by remember { mutableStateOf(MemoryView.Graph) }
    Segmented(MemoryView.entries, view, { it.label }) { view = it }
    when (view) {
        MemoryView.Graph -> GraphTab(services, data)
        MemoryView.List -> ListTab(services, data)
        MemoryView.Recent -> RecentTab(services, data)
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

/** A category's name on its chip. */
private fun kindName(kind: FactKind) = when (kind) {
    FactKind.Habit -> "Habits"
    FactKind.Learned -> "Learned"
    FactKind.Said -> "Told"
    FactKind.About -> "About them"
}

private fun whose(f: Fact) = f.whoName.ifBlank { MemoryGraph.HOUSE_LABEL }

/** "today 07:12", "yesterday 21:40", "14 Oct 08:30". */
private fun whenText(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val hm = "%02d:%02d".format(t.hour, t.minute)
    return when (t.toLocalDate()) {
        today -> "today $hm"
        today.minusDays(1) -> "yesterday $hm"
        else -> t.format(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH))
    }
}

private fun usedText(f: Fact) =
    if (f.used == 0) "never used" else "used ${f.used} time${if (f.used == 1) "" else "s"} · last ${whenText(f.lastUsed)}"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GraphTab(services: AssistantServices, data: MemoryData) {
    val g = GlassTheme.colors
    val people = remember(data.facts) { data.facts.map(::whose).distinct().sortedWith(compareBy<String> { it == MemoryGraph.HOUSE_LABEL }.thenBy { it }) }
    var person by remember { mutableStateOf<String?>(null) }
    var kinds by remember { mutableStateOf(FactKind.entries.toSet()) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Everyone", person == null) { person = null }
        people.forEach { p -> Chip(p, person == p) { person = if (person == p) null else p } }
        FactKind.entries.forEach { k ->
            val on = k in kinds
            Row(
                Modifier.pressable({ kinds = if (on) kinds - k else kinds + k })
                    .then(if (on) Modifier.background(kindColor(k, g).copy(alpha = 0.22f), PillShape) else Modifier.glass(PillShape))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(10.dp).background(kindColor(k, g).copy(alpha = if (on) 1f else 0.4f), CircleShape))
                Text("${kindName(k)} ${data.facts.count { it.kind == k }}", style = MaterialTheme.typography.labelLarge, color = if (on) g.fg else g.muted)
            }
        }
    }
    val facts = remember(data.facts, person, kinds) { data.facts.filter { it.kind in kinds && (person == null || whose(it) == person) } }
    val graph = remember(facts) { MemoryGraph.build(facts) }
    var selected by remember { mutableStateOf<String?>(null) }
    val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
    val usedThisWeek = data.recalls.filter { it.at >= weekAgo }.sumOf { it.facts.size }
    Text(
        "${facts.size} facts · used $usedThisWeek times this week · ${facts.count { it.used == 0 }} never used",
        style = MaterialTheme.typography.bodyMedium,
        color = g.muted,
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth > 760.dp
        val canvas = @Composable { m: Modifier ->
            Box(m.height(540.dp).glass(RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))) {
                if (graph.nodes.isEmpty()) {
                    Text("Nothing here with these filters.", style = MaterialTheme.typography.bodyMedium, color = g.muted, modifier = Modifier.align(Alignment.Center))
                } else {
                    MemoryGraphView(graph, selected, { selected = it }, Modifier.fillMaxSize())
                }
                Text(
                    "Bigger dot = used more often. Thicker line = said or done more often. Faded = never used. Pinch to zoom, drag to move.",
                    style = MaterialTheme.typography.bodySmall,
                    color = g.muted,
                    modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
                )
            }
        }
        val detail = @Composable { m: Modifier -> NodeDetail(services, data, graph, selected, { selected = it }, m) }
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

/** Beside the graph: the picked dot's facts, or the most used ones when nothing is picked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NodeDetail(services: AssistantServices, data: MemoryData, graph: MemoryGraph.Graph, selected: String?, onSelect: (String?) -> Unit, modifier: Modifier) {
    val g = GlassTheme.colors
    val i = graph.nodes.indexOfFirst { it.key == selected }
    val byId = remember(data.facts) { data.facts.associateBy { it.id } }
    Column(modifier.glass(RoundedCornerShape(22.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (i < 0) {
            Text("Most used", style = MaterialTheme.typography.titleMedium, color = g.fg)
            Text("Tap a dot to see what it holds.", style = MaterialTheme.typography.bodyMedium, color = g.muted)
            val top = graph.nodes.flatMap { it.facts }.distinct().mapNotNull { byId[it] }.filter { it.used > 0 }.sortedByDescending { it.used }.take(5)
            if (top.isEmpty()) Text("Nothing has been used yet.", style = MaterialTheme.typography.bodyMedium, color = g.muted)
            top.forEach { FactRow(services, it) }
            return@Column
        }
        val node = graph.nodes[i]
        val facts = node.facts.distinct().mapNotNull { byId[it] }.sortedByDescending { it.used }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(14.dp).background(if (node.hub) g.fg else kindColor(node.kind, g), CircleShape))
            Text(node.label, style = MaterialTheme.typography.titleMedium, color = g.fg, modifier = Modifier.weight(1f))
            RoundButton(Sym.close, { onSelect(null) }, size = 40.dp, filledIcon = false, contentDescription = "Close")
        }
        val last = facts.maxOfOrNull { it.lastUsed } ?: 0
        Text(
            "${facts.size} fact${if (facts.size == 1) "" else "s"} · used ${facts.sumOf { it.used }} times" + if (last > 0) " · last used ${whenText(last)}" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
        facts.forEach { FactRow(services, it) }
        val near = graph.neighbours(i).map { graph.nodes[it] }
        if (near.isNotEmpty()) {
            Label("Connected to")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                near.forEach { n -> Chip(n.label, false) { onSelect(n.key) } }
            }
        }
    }
}

/** One fact with its category, owner and use, and Edit and Forget. */
@Composable
private fun FactRow(services: AssistantServices, f: Fact) {
    val g = GlassTheme.colors
    val scope = rememberCoroutineScope()
    var editing by remember(f.id) { mutableStateOf(false) }
    var text by remember(f.id) { mutableStateOf(f.obj) }
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp)).padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).background(kindColor(f.kind, g), CircleShape))
            Text(Memory.line(f), style = MaterialTheme.typography.bodyLarge, color = g.fg, modifier = Modifier.weight(1f))
        }
        Text(
            "${f.kind.label.replaceFirstChar { it.uppercase() }} · ${whose(f)}${if (f.localOnly) " · local AI only" else ""} · ${usedText(f)}",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
        if (editing) {
            TextBox(text, hint = "What it says") { text = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (editing) "Save" else "Edit",
                style = MaterialTheme.typography.labelLarge,
                color = g.fg,
                modifier = Modifier.pressable({
                    if (editing && text.isNotBlank()) scope.launch { services.memory.edit(f.id, text) }
                    editing = !editing
                }).glass(PillShape).padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                "Forget",
                style = MaterialTheme.typography.labelLarge,
                color = g.danger,
                modifier = Modifier.pressable({ scope.launch { services.memory.delete(f.id) } }).glass(PillShape).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ListTab(services: AssistantServices, data: MemoryData) {
    val now = System.currentTimeMillis()
    data.facts.groupBy(::whose).toSortedMap(compareBy<String> { it == MemoryGraph.HOUSE_LABEL }.thenBy { it }).forEach { (who, facts) ->
        Label("$who (${facts.size})")
        facts.sortedByDescending { Memory.strength(it, now) }.forEach { FactRow(services, it) }
    }
}

/** Every time the memory was looked in, newest first, with what came back. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentTab(services: AssistantServices, data: MemoryData) {
    val g = GlassTheme.colors
    val scope = rememberCoroutineScope()
    Text(
        "Every time the assistant pulled something out of memory, newest first. If an answer went wrong, forget or fix the fact it used.",
        style = MaterialTheme.typography.bodyMedium,
        color = g.muted,
    )
    if (data.recalls.isEmpty()) {
        Text("Nothing has been looked up yet.", style = MaterialTheme.typography.bodyMedium, color = g.muted)
        return
    }
    val byId = remember(data.facts) { data.facts.associateBy { it.id } }
    data.recalls.forEach { r ->
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(whenText(r.at), style = MaterialTheme.typography.bodyMedium, color = g.muted)
                Text(
                    r.whoName.ifBlank { "House" } + ": " + if (r.via == RecallVia.Tool) "looked up \"${r.question}\"" else "\"${r.question}\"",
                    style = MaterialTheme.typography.bodyLarge,
                    color = g.fg,
                    modifier = Modifier.weight(1f),
                )
                Text(when (r.via) { RecallVia.Tool -> "recall tool"; RecallVia.Auto -> "named in the question"; RecallVia.Cue -> "cue word" }, style = MaterialTheme.typography.bodyMedium, color = g.muted)
            }
            if (r.facts.isEmpty()) Text("Nothing matched.", style = MaterialTheme.typography.bodyMedium, color = g.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                r.facts.forEachIndexed { n, id ->
                    val f = byId[id]
                    Row(
                        Modifier.background((if (f != null) kindColor(f.kind, g) else g.muted).copy(alpha = 0.18f), PillShape)
                            .padding(start = 12.dp, end = if (f != null) 4.dp else 12.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(9.dp).background(if (f != null) kindColor(f.kind, g) else g.muted, CircleShape))
                        Text(
                            if (f != null) Memory.line(f) else (r.lines.getOrNull(n) ?: "a fact") + " (forgotten)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (f != null) g.fg else g.muted,
                        )
                        if (f != null) RoundButton(Sym.close, { scope.launch { services.memory.delete(f.id) } }, size = 32.dp, filledIcon = false, contentDescription = "Forget")
                    }
                }
            }
        }
    }
}

/** Kinds of things people write about themselves, offered as chips; anything else can be typed. */
private val ABOUT_KINDS = listOf("Birthday", "Likes", "Dislikes", "Allergic to", "Favourite food", "Favourite music", "Works as", "Partner", "Children", "Pets", "Other")

/**
 * Under People: what [name] writes about themself, as many lines as they like, each kept as a
 * fact in the memory graph. Shared lines are known to everyone in the house ("when is Maja's
 * birthday?"); private ones only when the tablet knows it is them asking.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AboutPerson(services: AssistantServices, personId: String, name: String) {
    val g = GlassTheme.colors
    val scope = rememberCoroutineScope()
    val data by services.memory.data.collectAsState()
    val mine = data.facts.filter { it.kind == FactKind.About && it.whoName == name && (it.who == personId || it.subject == name) }
    Text("About $name", style = MaterialTheme.typography.labelLarge, color = g.fg)
    mine.forEach { f ->
        Row(
            Modifier.fillMaxWidth().glass(PillShape).padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("${f.relation}: ${f.obj}", style = MaterialTheme.typography.bodyLarge, color = g.fg, modifier = Modifier.weight(1f))
            if (f.who == personId) Text("private", style = MaterialTheme.typography.bodyMedium, color = g.muted)
            RoundButton(Sym.close, { scope.launch { services.memory.delete(f.id) } }, size = 36.dp, filledIcon = false)
        }
    }
    var kind by remember { mutableStateOf(ABOUT_KINDS[1]) }
    var text by remember { mutableStateOf("") }
    var onlyMe by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ABOUT_KINDS.forEach { k -> Chip(k, k == kind) { kind = k } }
    }
    TextBox(text, hint = when (kind) {
        "Birthday" -> "e.g. 12 May 1990"
        "Other" -> "Anything, e.g. supports AIK"
        else -> "e.g. sushi, jazz, long walks"
    }) { text = it }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Only when it's me asking", onlyMe) { onlyMe = !onlyMe }
        Text(
            "Add",
            style = MaterialTheme.typography.labelLarge,
            color = if (text.isBlank()) g.muted else g.fg,
            modifier = Modifier.pressable({
                val value = text.trim()
                if (value.isEmpty()) return@pressable
                val relation = if (kind == "Other") "is" else kind.lowercase()
                scope.launch {
                    services.memory.learn(listOf(Fact(
                        who = if (onlyMe) personId else Memory.HOUSE,
                        whoName = name,
                        subject = name,
                        relation = relation,
                        obj = value,
                        kind = FactKind.About,
                    )))
                }
                text = ""
            }).glass(PillShape).padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}
