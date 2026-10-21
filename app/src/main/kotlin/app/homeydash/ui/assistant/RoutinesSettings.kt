package app.homeydash.ui.assistant

import app.homeydash.ui.dashboard.SettingsBox
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import app.homeydash.agent.Routine
import app.homeydash.agent.RoutineStep
import app.homeydash.agent.StepKind
import app.homeydash.assistant.AssistantServices
import app.homeydash.homey.Device
import app.homeydash.homey.Mood
import app.homeydash.homey.Zone
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.RoundButton
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.pressable
import app.homeydash.ui.dashboard.Toggle
import app.homeydash.ui.theme.GlassTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Settings → Assistant → Routines: what the assistant does on "God morgon" and other phrases, and standing instructions. */
@Composable
internal fun RoutineList(services: AssistantServices, onEdit: (Routine) -> Unit, onRun: (Routine) -> Unit) {
    val g = GlassTheme.colors
    val scope = rememberCoroutineScope()
    val data by services.routines.data.collectAsState()
    Hint(
        "Say a routine's phrase, like \"God morgon\", and the assistant does its steps in order and answers the way its instructions say. " +
            "You can also make them by voice: \"när jag säger filmkväll, dimma vardagsrummet och starta Apple TV\".",
    )
    data.routines.forEach { r ->
        Row(
            Modifier.fillMaxWidth().pressable({ onEdit(r) }).glass(RoundedCornerShape(22.dp)).padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.labelLarge, color = g.fg)
                Text(
                    r.phrases.joinToString(" · ") { "\"$it\"" } + " · " + (if (r.steps.isEmpty()) "instructions only" else "${r.steps.count { it.kind != StepKind.Answer }} steps"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = g.muted,
                    maxLines = 1,
                )
            }
            RoundButton(Sym.playArrow, { onRun(r) }, size = 40.dp, filledIcon = false)
            Toggle(r.enabled, onClick = { scope.launch { services.routines.save(r.copy(enabled = !r.enabled)) } })
        }
    }
    NavRow(Sym.add, "New routine", { onEdit(Routine(name = "", phrases = emptyList(), steps = listOf(RoutineStep(StepKind.Answer)))) })

    SettingsBox("Standing instructions") {
        Hint(
            "Followed in every answer, one per line, e.g. \"Kalla mig Gabbe\" or \"Svara alltid kort\". Say \"kom ihåg att…\" to add one by voice.",
        )
        var text by remember(data.instructions.isEmpty()) { mutableStateOf(data.instructions) }
        LaunchedEffect(text) {
            if (text == data.instructions) return@LaunchedEffect
            delay(SAVE_AFTER_MS)
            services.routines.setInstructions(text)
        }
        TextBox(text, singleLine = false, hint = "One instruction per line") { text = it }
    }
}

/** Editing one routine; every change is saved as you go. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RoutineEditor(
    services: AssistantServices,
    start: Routine,
    zones: Map<String, Zone>,
    devices: Map<String, Device>,
    moods: Map<String, Mood>,
    flows: suspend () -> List<String>,
    onRun: (Routine) -> Unit,
    onDone: () -> Unit,
) {
    val g = GlassTheme.colors
    val scope = rememberCoroutineScope()
    var r by remember(start.id) { mutableStateOf(start) }
    var phrases by remember(start.id) { mutableStateOf(start.phrases.joinToString(", ")) }
    var flowNames by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) { flowNames = runCatching { flows() }.getOrDefault(emptyList()).sorted() }
    // A routine without phrases starts on its name.
    fun ready(x: Routine) = if (x.phrases.isEmpty()) x.copy(phrases = listOf(x.name.trim())) else x
    fun edit(change: (Routine) -> Routine) { r = change(r) }
    fun step(i: Int, change: (RoutineStep) -> RoutineStep) = edit { it.copy(steps = it.steps.toMutableList().also { s -> s[i] = change(s[i]) }) }
    // Saved a moment after each change; a new routine only once it has a name.
    LaunchedEffect(r) {
        if (r == start || r.name.isBlank()) return@LaunchedEffect
        delay(SAVE_AFTER_MS)
        services.routines.save(ready(r))
    }

    Label("Name")
    TextBox(r.name, hint = "e.g. God morgon") { v -> edit { it.copy(name = v) } }
    Label("Say any of these to start it")
    TextBox(phrases, hint = "god morgon, good morning") { v ->
        phrases = v
        edit { it.copy(phrases = v.split(',', ';').map(String::trim).filter(String::isNotEmpty)) }
    }
    Label("Instructions for the assistant")
    Hint(
        "How to answer and anything else to do, in your own words. The assistant can use all its tools here, " +
            "e.g. \"om det regnar, påminn om paraply\".",
    )
    TextBox(r.instructions, singleLine = false, hint = "Hälsa och säg vad som händer i dag") { v -> edit { it.copy(instructions = v) } }

    Label("Steps, in order")
    r.steps.forEachIndexed { i, s ->
        StepCard(
            step = s,
            zones = zones,
            devices = devices,
            moods = moods,
            flows = flowNames,
            onChange = { change -> step(i, change) },
            onUp = if (i > 0) ({ edit { it.copy(steps = it.steps.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) }) } }) else null,
            onDown = if (i < r.steps.lastIndex) ({ edit { it.copy(steps = it.steps.toMutableList().also { l -> l.add(i + 1, l.removeAt(i)) }) } }) else null,
            onRemove = { edit { it.copy(steps = it.steps.toMutableList().also { l -> l.removeAt(i) }) } },
        )
    }
    Text("Add a step", style = MaterialTheme.typography.labelLarge, color = g.fg)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StepKind.entries.filter { k -> k != StepKind.Answer || r.steps.none { it.kind == StepKind.Answer } }.forEach { k ->
            Chip(k.label, false) {
                val new = RoutineStep(k, target = if (k == StepKind.Calendar) "today" else "", value = if (k == StepKind.Device || k == StepKind.Room) "on" else "")
                edit { it.copy(steps = it.steps + new) }
            }
        }
    }

    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Run now", false) {
            scope.launch {
                if (r.name.isNotBlank()) services.routines.save(ready(r))
                onRun(ready(r))
            }
        }
        Chip("Done", false) {
            scope.launch {
                if (r.name.isNotBlank()) services.routines.save(ready(r))
                onDone()
            }
        }
        if (services.routines.data.value.routines.any { it.id == r.id }) Chip("Delete", false) {
            scope.launch {
                services.routines.delete(r.id)
                onDone()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepCard(
    step: RoutineStep,
    zones: Map<String, Zone>,
    devices: Map<String, Device>,
    moods: Map<String, Mood>,
    flows: List<String>,
    onChange: ((RoutineStep) -> RoutineStep) -> Unit,
    onUp: (() -> Unit)?,
    onDown: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    val g = GlassTheme.colors
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(step.kind.label, style = MaterialTheme.typography.labelLarge, color = g.fg)
                Text(step.kind.detail, style = MaterialTheme.typography.bodyMedium, color = g.muted)
            }
            onUp?.let { RoundButton(Sym.keyboardArrowUp, it, size = 36.dp, filledIcon = false) }
            onDown?.let { RoundButton(Sym.keyboardArrowDown, it, size = 36.dp, filledIcon = false) }
            RoundButton(Sym.trash, onRemove, size = 36.dp, filledIcon = false)
        }
        when (step.kind) {
            StepKind.Say -> TextBox(step.value, hint = "God morgon!") { v -> onChange { it.copy(value = v) } }
            StepKind.Calendar -> Segmented(listOf("today", "tomorrow"), step.target.ifBlank { "today" }, { if (it == "today") "Today" else "Tomorrow" }) { v ->
                onChange { it.copy(target = v) }
            }
            StepKind.Music -> {
                val speakers = devices.values.filter { it.deviceClass == "speaker" || it.deviceClass == "tv" }.sortedBy { it.name.lowercase() }
                Picker(listOf("" to "Answer speaker") + speakers.map { it.id to it.name }, step.target) { id -> onChange { it.copy(target = id) } }
                TextBox(step.value, hint = "What to play, e.g. P1 or a favourite") { v -> onChange { it.copy(value = v) } }
            }
            StepKind.Flow -> {
                if (flows.isNotEmpty()) Picker(flows.map { it to it }, step.target) { name -> onChange { it.copy(target = name) } }
                else TextBox(step.target, hint = "The Flow's name") { v -> onChange { it.copy(target = v) } }
            }
            StepKind.Mood -> Picker(moods.values.sortedBy { it.name.lowercase() }.map { it.name to it.name }, step.target) { name -> onChange { it.copy(target = name) } }
            StepKind.Device -> {
                val named = devices.values.filter { !it.hidden }.sortedBy { it.name.lowercase() }.map { d ->
                    d.id to (d.zone?.let { z -> zones[z]?.name }?.let { "${d.name} · $it" } ?: d.name)
                }
                Picker(named, step.target) { id -> onChange { it.copy(target = id) } }
                TextBox(step.value, hint = "on, off, or a dim level like 30 %") { v -> onChange { it.copy(value = v) } }
            }
            StepKind.Room -> {
                Picker(zones.values.sortedBy { it.name.lowercase() }.map { it.name to it.name }, step.target) { name -> onChange { it.copy(target = name) } }
                Segmented(listOf("on", "off"), if (step.value == "off") "off" else "on", { if (it == "on") "On" else "Off" }) { v -> onChange { it.copy(value = v) } }
            }
            StepKind.Page -> Picker(
                listOf("overview", "calendar", "media", "recipes", "fridge").map { it to it.replaceFirstChar(Char::uppercase) } +
                    zones.values.sortedBy { it.name.lowercase() }.map { it.name to it.name },
                step.target,
            ) { p -> onChange { it.copy(target = p) } }
            StepKind.Wait -> TextBox(step.value, hint = "Seconds, e.g. 5") { v -> onChange { it.copy(value = v.filter { c -> c.isDigit() || c == '.' || c == ',' }) } }
            StepKind.Weather, StepKind.Fridge, StepKind.Answer -> Unit
        }
    }
}

/** Picks one of [options] (id to label); a search box appears when there are many. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Picker(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val many = options.size > MAX_CHIPS
    if (many) TextBox(query, hint = "Search") { query = it }
    val shown = options
        .filter { query.isBlank() || it.second.contains(query.trim(), ignoreCase = true) }
        .let { list -> if (many) (list.filter { it.first == selected } + list.filter { it.first != selected }).take(MAX_CHIPS) else list }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { (id, label) -> Chip(label, id == selected) { onPick(id) } }
    }
}

/** A text field whose text lives with the caller, so reordered steps keep their own words. */
@Composable
internal fun TextBox(value: String, singleLine: Boolean = true, hint: String = "", onChange: (String) -> Unit) {
    val g = GlassTheme.colors
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = g.fg),
        cursorBrush = SolidColor(g.fg),
        modifier = Modifier
            .fillMaxWidth()
            .glass(if (singleLine) PillShape else RoundedCornerShape(22.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .then(if (singleLine) Modifier else Modifier.heightIn(min = 72.dp)),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = g.muted)
                inner()
            }
        },
    )
}

private const val SAVE_AFTER_MS = 400L
private const val MAX_CHIPS = 14
