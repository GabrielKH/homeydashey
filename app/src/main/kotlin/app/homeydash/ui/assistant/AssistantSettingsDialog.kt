package app.homeydash.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.homeydash.ui.components.dialogSize
import app.homeydash.agent.Access
import app.homeydash.agent.AgentSettings
import app.homeydash.agent.AgentTools
import app.homeydash.agent.AskContext
import app.homeydash.agent.BrainMode
import app.homeydash.agent.Connection
import app.homeydash.agent.FaceStyle
import app.homeydash.agent.FollowUp
import app.homeydash.agent.VoiceStyle
import app.homeydash.assistant.AssistantState
import app.homeydash.agent.HomeView
import app.homeydash.agent.Language
import app.homeydash.agent.Provider
import app.homeydash.agent.Secret
import app.homeydash.assistant.AssistantServices
import app.homeydash.homey.Device
import app.homeydash.homey.Zone
import app.homeydash.ui.components.PanelShape
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.RoundButton
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.pressable
import app.homeydash.ui.dashboard.GlassSlider
import app.homeydash.ui.dashboard.Toggle
import app.homeydash.ui.theme.GlassTheme
import kotlinx.coroutines.launch

/** Settings → Assistant: the AI connections, language, voice, and the security switches. */
@Composable
fun AssistantSettingsDialog(
    services: AssistantServices,
    settings: AgentSettings,
    zones: Map<String, Zone>,
    devices: Map<String, Device>,
    hidden: Set<String>,
    preview: (local: Boolean) -> Pair<HomeView, AgentTools>,
    onFaces: () -> Unit,
    onDismiss: () -> Unit,
) {
    val g = GlassTheme.colors
    val update = services.settingsStore::update
    var page by remember { mutableStateOf("main") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .dialogSize(620.dp)
                .background(g.wallBase, PanelShape)
                .glass()
                .padding(vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (page != "main") RoundButton(Sym.arrowBack, { page = "main" }, size = 44.dp, filledIcon = false)
                Text(
                    when (page) { "devices" -> "Hidden from the assistant"; "preview" -> "What the assistant sees"; else -> "Assistant" },
                    style = MaterialTheme.typography.headlineMedium,
                    color = g.fg,
                    modifier = Modifier.weight(1f),
                )
                if (page == "main") Toggle(settings.enabled, onClick = { update { it.copy(enabled = !it.enabled) } })
                RoundButton(Sym.close, onDismiss, size = 44.dp, filledIcon = false)
            }
            // Only the content scrolls; the title and close button stay put.
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (page) {
                    "devices" -> HiddenDevices(zones, devices, hidden, settings) { id, hide ->
                        update { s -> s.copy(hiddenDevices = if (hide) s.hiddenDevices + id else s.hiddenDevices - id) }
                    }
                    "preview" -> Preview(services, settings, preview)
                    else -> Main(services, settings, update, onDevices = { page = "devices" }, onPreview = { page = "preview" }, onFaces = onFaces)
                }
            }
        }
    }
}

@Composable
private fun Main(
    services: AssistantServices,
    settings: AgentSettings,
    update: ((AgentSettings) -> AgentSettings) -> Unit,
    onDevices: () -> Unit,
    onPreview: () -> Unit,
    onFaces: () -> Unit,
) {
    val g = GlassTheme.colors
    Text(
        "Talk to the tablet (tap the orb in the corner) or type. The assistant controls the home through Homey and answers on the tablet or a speaker.",
        style = MaterialTheme.typography.bodyMedium,
        color = g.muted,
    )
    Label("Which AI answers")
    Segmented(BrainMode.entries, settings.mode, { it.label }) { m -> update { it.copy(mode = m) } }
    Text(
        when (settings.mode) {
            BrainMode.Smart -> "Your computer first. When it's off or can't answer, the internet AI takes over. " +
                "For things it doesn't know, like news or opening hours, your computer asks the internet AI with web search, sending only the question."
            BrainMode.Local -> "Only Ollama on your computer. Nothing leaves the house, except speech recognition when the tablet can't do it on its own."
            BrainMode.Internet -> "Only Gemini or OpenAI, which can search the web."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = g.muted,
    )

    Label("Local AI: Ollama on your computer")
    val local = settings.local ?: Connection(Provider.Ollama)
    ConnectionFields(services, local, showKey = false) { c -> update { it.copy(local = c) } }

    Label("Internet AI")
    val internet = settings.internet ?: Connection(Provider.Gemini)
    Segmented(listOf(Provider.Gemini, Provider.OpenAI), internet.provider, { it.label }) { p ->
        update { it.copy(internet = if (p == internet.provider) internet else Connection(p)) }
    }
    ConnectionFields(services, internet, showKey = true) { c -> update { it.copy(internet = c) } }

    Label("Language")
    Segmented(Language.entries, settings.language, { it.label }) { l -> update { it.copy(language = l) } }
    Text(
        "Svenska + English hears both and answers in the language you spoke.",
        style = MaterialTheme.typography.bodyMedium,
        color = g.muted,
    )

    Label("Voice activation")
    WakeSettings(services, settings, update)
    Text("Keep listening after an answer", style = MaterialTheme.typography.labelLarge, color = g.fg)
    Segmented(FollowUp.entries, settings.followUp, { it.label }) { f -> update { it.copy(followUp = f) } }
    Text(
        settings.followUp.detail + " Say \"stop\", \"thanks\" or \"tack\" to end early.",
        style = MaterialTheme.typography.bodyMedium,
        color = g.muted,
    )

    Label("Voice")
    SwitchRow("Speak answers", "Off shows answers as text only.", settings.speak) { on -> update { it.copy(speak = on) } }
    LookAndVoice(services, settings, update)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Speaker volume", style = MaterialTheme.typography.labelLarge, color = g.fg)
        GlassSlider(settings.speakerVolume / 100f, { v -> update { it.copy(speakerVolume = (v * 100).toInt()) } }, Modifier.weight(1f))
        Text("${settings.speakerVolume}%", style = MaterialTheme.typography.labelLarge, color = g.fg)
    }
    Text(
        "Pick the speaker that answers in the assistant panel. Sonos speakers can't be used as microphones, so you speak to the tablet.",
        style = MaterialTheme.typography.bodyMedium,
        color = g.muted,
    )

    Label("Security")
    Text(
        "The assistant reads ordinary things: rooms, lights, climate, sensors, players, moods, Flow names, weather. " +
            "Everything below is off until you turn it on. \"Local AI only\" keeps it away from the internet AI. " +
            "Keys, codes and camera pictures are never sent.",
        style = MaterialTheme.typography.bodyMedium,
        color = g.muted,
    )
    Secret.entries.forEach { secret ->
        val enabled = when (secret) {
            Secret.Unlock -> settings.access(Secret.Locks) != Access.Off
            Secret.CalendarDetails -> settings.access(Secret.Calendar) != Access.Off
            else -> true
        }
        Column(
            Modifier.fillMaxWidth().graphicsLayer { alpha = if (enabled) 1f else 0.45f },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(secret.title, style = MaterialTheme.typography.labelLarge, color = g.fg)
            Text(secret.detail, style = MaterialTheme.typography.bodyMedium, color = g.muted)
            Segmented(Access.entries, settings.access(secret), { it.label }, enabled = enabled) { a ->
                update { it.copy(secrets = it.secrets + (secret to a)) }
            }
        }
    }
    NavRow(Sym.face, "People: faces and voices", onFaces)
    NavRow(Sym.visibilityOff, "Hide devices from the assistant (${settings.hiddenDevices.size})", onDevices)
    NavRow(Sym.search, "What the assistant sees", onPreview)
}

@Composable
private fun ConnectionFields(services: AssistantServices, connection: Connection, showKey: Boolean, onChange: (Connection) -> Unit) {
    val g = GlassTheme.colors
    val scope = rememberCoroutineScope()
    var result by remember(connection.provider) { mutableStateOf<String?>(null) }
    // The fields keep their own text while typing; switching provider starts them afresh.
    key(connection.provider) {
        Field("Address", connection.baseUrl, keyboard = KeyboardType.Uri) { onChange(connection.copy(baseUrl = it)) }
        if (showKey) Field("API key", connection.apiKey, secret = true) { onChange(connection.copy(apiKey = it)) }
        Field("Model", connection.model) { onChange(connection.copy(model = it)) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Test",
            style = MaterialTheme.typography.labelLarge,
            color = g.fg,
            modifier = Modifier
                .pressable({
                    result = "Testing…"
                    scope.launch { result = services.chat.test(connection)?.let { "Didn't work: $it" } ?: "Works." }
                })
                .glass(PillShape)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
        Text(
            result ?: when (connection.provider) {
                Provider.Ollama -> "On the computer: install Ollama, run \"ollama pull ${connection.model}\", and on a Mac run \"launchctl setenv OLLAMA_HOST 0.0.0.0\" then restart Ollama so the tablet can reach it."
                Provider.Gemini -> "Get a key at aistudio.google.com."
                Provider.OpenAI -> "Get a key at platform.openai.com."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (result?.startsWith("Didn't") == true) g.danger else g.muted,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HiddenDevices(zones: Map<String, Zone>, devices: Map<String, Device>, hidden: Set<String>, settings: AgentSettings, onHide: (String, Boolean) -> Unit) {
    val g = GlassTheme.colors
    Text(
        "Turn off devices the assistant shouldn't know about. Devices hidden on the dashboard, locks, alarms and cameras are already left out unless switched on under Security.",
        style = MaterialTheme.typography.bodyMedium,
        color = g.muted,
    )
    devices.values.filter { !it.hidden && it.id !in hidden }
        .groupBy { it.zone?.let { z -> zones[z]?.name } ?: "No room" }
        .toSortedMap()
        .forEach { (room, list) ->
            Label(room)
            list.sortedBy { it.name.lowercase() }.forEach { d ->
                val shown = d.id !in settings.hiddenDevices
                Row(
                    Modifier.fillMaxWidth().pressable({ onHide(d.id, shown) }).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.bodyLarge, color = g.fg)
                        HomeView.secretOf(d)?.let { Text("Needs the ${it.title} switch", style = MaterialTheme.typography.bodyMedium, color = g.muted) }
                    }
                    Toggle(shown)
                }
            }
        }
}

@Composable
private fun Preview(services: AssistantServices, settings: AgentSettings, preview: (Boolean) -> Pair<HomeView, AgentTools>) {
    val g = GlassTheme.colors
    var local by remember { mutableStateOf(false) }
    Segmented(listOf(false, true), local, { if (it) "Sent to the local AI" else "Sent to the internet AI" }) { local = it }
    val (view, tools) = remember(local, settings) { preview(local) }
    val prompt = remember(view) { services.agent.systemPrompt(settings, view, AskContext()) }
    Text(
        "This is everything the AI gets before your question, plus the answers of the tools it uses: " +
            tools.specs().joinToString { it.name } + ".",
        style = MaterialTheme.typography.bodyMedium,
        color = g.muted,
    )
    Box(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).horizontalScroll(rememberScrollState()).padding(14.dp)) {
        Text(prompt, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp), color = g.fg)
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = GlassTheme.colors.muted, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, enabled: Boolean = true, onPick: (T) -> Unit) {
    val g = GlassTheme.colors
    Row(Modifier.fillMaxWidth().glass(PillShape).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { option ->
            val on = option == selected
            Text(
                label(option),
                style = MaterialTheme.typography.labelLarge,
                color = if (on) g.wallBase else g.fg,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .pressable({ onPick(option) }.takeIf { enabled })
                    .then(if (on) Modifier.background(g.fg, PillShape) else Modifier)
                    .padding(vertical = 11.dp),
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String, secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    val g = GlassTheme.colors
    var text by remember { mutableStateOf(value) }
    var reveal by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().glass(PillShape).padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = g.muted, modifier = Modifier.width(90.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it; onChange(it) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = g.fg),
            cursorBrush = SolidColor(g.fg),
            visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        if (secret) RoundButton(Sym.visibilityOff, { reveal = !reveal }, size = 36.dp, filledIcon = reveal)
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val g = GlassTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = g.fg)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = g.muted)
        }
        Toggle(on, onClick = { onChange(!on) })
    }
}

@Composable
private fun NavRow(icon: app.homeydash.ui.components.Ic, text: String, onClick: () -> Unit) {
    val g = GlassTheme.colors
    Row(
        Modifier.fillMaxWidth().pressable(onClick).glass(PillShape).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SymIcon(icon, size = 20.dp)
        Text(text, style = MaterialTheme.typography.labelLarge, color = g.fg, modifier = Modifier.weight(1f).padding(start = 10.dp))
        SymIcon(Sym.chevronRight, size = 20.dp, tint = g.muted)
    }
}

/** How the assistant looks while it talks, and how it sounds. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookAndVoice(services: AssistantServices, settings: AgentSettings, update: ((AgentSettings) -> AgentSettings) -> Unit) {
    val g = GlassTheme.colors
    val scope = rememberCoroutineScope()
    var previewState by remember { mutableStateOf(AssistantState.Speaking) }
    Text("Look", style = MaterialTheme.typography.labelLarge, color = g.fg)
    Box(Modifier.fillMaxWidth().height(150.dp).glass(RoundedCornerShape(22.dp))) {
        VoiceFace(settings.face, previewState, levels = { VoiceLevels(0f, 0f, 0f, 0f) }, modifier = Modifier.fillMaxWidth().height(150.dp), classicRed = settings.classicRed, demo = true)
    }
    Segmented(listOf(AssistantState.Listening, AssistantState.Thinking, AssistantState.Speaking), previewState, { it.name }) { previewState = it }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FaceStyle.entries.forEach { f -> Chip(f.label, f == settings.face) { update { it.copy(face = f) } } }
        if (settings.face == FaceStyle.Scanner) Chip("Classic red", settings.classicRed) { update { it.copy(classicRed = !it.classicRed) } }
    }
    Text(settings.face.detail, style = MaterialTheme.typography.bodyMedium, color = g.muted)

    Text("Voice", style = MaterialTheme.typography.labelLarge, color = g.fg)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VoiceStyle.entries.forEach { v -> Chip(v.label, v == settings.voice) { update { it.copy(voice = v) } } }
        Chip("Hear it", false) {
            scope.launch {
                val sample = if (settings.language == Language.English) "Hi, I'm your kitchen assistant. Dinner music in the living room?"
                else "Hej, jag är er köksassistent. Ska jag sätta på middagsmusik?"
                services.output.speak(sample, settings.language, settings.voice)
            }
        }
    }
    Text(
        settings.voice.detail + " Made from the tablet's own voice with pitch, speed and a sound filter.",
        style = MaterialTheme.typography.bodyMedium,
        color = g.muted,
    )
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val g = GlassTheme.colors
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (on) g.wallBase else g.fg,
        modifier = Modifier
            .pressable(onClick)
            .then(if (on) Modifier.background(g.fg, PillShape) else Modifier.glass(PillShape))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
