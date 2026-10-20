package app.homeydash.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
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
import app.homeydash.ui.components.AppDialog
import androidx.activity.compose.BackHandler
import app.homeydash.ui.components.dialogSize
import app.homeydash.agent.Access
import app.homeydash.agent.AgentSettings
import app.homeydash.agent.Persona
import app.homeydash.agent.intro
import app.homeydash.agent.WakeSound
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
import app.homeydash.agent.Phones
import app.homeydash.agent.PhoneSharing
import app.homeydash.agent.SHARED_PHONE
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
import app.homeydash.ui.dashboard.SettingsBox
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
    /** The page to open on, so coming back from People lands where it left. */
    initialPage: String = "main",
    onPage: (String) -> Unit = {},
    /** Back from the main page: to the dashboard's Settings. */
    onBack: () -> Unit = onDismiss,
    moods: Map<String, app.homeydash.homey.Mood> = emptyMap(),
    flows: suspend () -> List<String> = { emptyList() },
    onRunRoutine: (app.homeydash.agent.Routine) -> Unit = {},
) {
    val g = GlassTheme.colors
    val update = services.settingsStore::update
    val nav = remember { AssistantNav(initialPage) }
    val page = nav.page
    LaunchedEffect(page) { onPage(page) }
    val back: () -> Unit = {
        when {
            page == "main" -> onBack()
            else -> nav.page = AssistantNav.parent(page) ?: "main"
        }
        Unit
    }
    AppDialog(onDismiss) { app.homeydash.ui.components.Compact {
        Column(
            Modifier
                .dialogSize(620.dp, tall = true)
                .background(g.wallBase, PanelShape)
                .glass()
                .padding(vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackHandler(onBack = back)
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundButton(Sym.arrowBack, back, size = 44.dp, filledIcon = false)
                Text(
                    nav.title(),
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AssistantPage(nav, services, settings, zones, devices, hidden, preview, onFaces, moods, flows, onRunRoutine)
            }
        }
    } }
}

/**
 * Which assistant settings page shows, and the routine being edited; shared by the popup and
 * the wide settings page, which shows the same pages beside its side menu.
 */
@androidx.compose.runtime.Stable
internal class AssistantNav(start: String) {
    var page by mutableStateOf(start)
    var editing by mutableStateOf<app.homeydash.agent.Routine?>(null)

    fun title(): String = when (page) {
        "devices" -> "Hidden from the assistant"
        "preview" -> "What the assistant sees"
        "phones" -> "Phones"
        "routines" -> "Routines"
        "routine" -> editing?.name?.ifBlank { null } ?: "New routine"
        in PAGES -> PAGES.getValue(page)
        else -> "Assistant"
    }

    companion object {
        /** The page a page opens from, for the pages that aren't in the menu. */
        fun parent(page: String): String? = when (page) {
            "routine" -> "routines"
            "devices", "preview", "phones" -> "privacy"
            else -> null
        }
    }
}

/** The content of [nav]'s page. [menu] shows the main page's links to the others, which the wide page has in its side menu. */
@Composable
internal fun AssistantPage(
    nav: AssistantNav,
    services: AssistantServices,
    settings: AgentSettings,
    zones: Map<String, Zone>,
    devices: Map<String, Device>,
    hidden: Set<String>,
    preview: (local: Boolean) -> Pair<HomeView, AgentTools>,
    onFaces: () -> Unit,
    moods: Map<String, app.homeydash.homey.Mood>,
    flows: suspend () -> List<String>,
    onRunRoutine: (app.homeydash.agent.Routine) -> Unit,
    menu: Boolean = true,
) {
    val update = services.settingsStore::update
    when (nav.page) {
        "devices" -> HiddenDevices(services, zones, devices, hidden, settings) { id, hide ->
            update { s -> s.copy(hiddenDevices = if (hide) s.hiddenDevices + id else s.hiddenDevices - id) }
        }
        "preview" -> Preview(services, settings, preview)
        "phones" -> PhonesPage(services, devices, settings, update)
        "routines" -> RoutineList(services, onEdit = { nav.editing = it; nav.page = "routine" }, onRun = onRunRoutine)
        "routine" -> nav.editing?.let { r ->
            androidx.compose.runtime.key(r.id) {
                RoutineEditor(services, r, zones, devices, moods, flows, onRun = onRunRoutine, onDone = { nav.page = "routines" })
            }
        }
        "voice" -> VoicePage(services, settings, update)
        "listen" -> ListenPage(services, settings, update)
        "brain" -> BrainPage(services, settings, update, preview)
        "privacy" -> PrivacyPage(settings, update, onDevices = { nav.page = "devices" }, onPreview = { nav.page = "preview" }, onFaces = onFaces, onPhones = { nav.page = "phones" })
        "spotify" -> Spotify(settings, update)
        "logs" -> Logs(services, settings, update)
        "memory" -> MemoryPage(services, settings, update)
        else -> Main(services, settings, update, onPage = { nav.page = it }, onRoutines = { nav.page = "routines" }, menu = menu)
    }
}

/** The settings pages behind the main page, with their titles. */
private val PAGES = mapOf(
    "voice" to "Voice and look",
    "listen" to "Listening",
    "brain" to "AI and servers",
    "privacy" to "Privacy and security",
    "spotify" to "Spotify on Sonos",
    "logs" to "Logs for Claude",
    "memory" to "Memory",
)

/** The main page: who the assistant is, its language, and the groups of settings. */
@Composable
private fun Main(
    services: AssistantServices,
    settings: AgentSettings,
    update: ((AgentSettings) -> AgentSettings) -> Unit,
    onPage: (String) -> Unit,
    onRoutines: () -> Unit,
    menu: Boolean = true,
) {
    val g = GlassTheme.colors
    Text(
        "Talk to the tablet (tap the orb in the corner) or type. The assistant controls the home through Homey and answers on the tablet or a speaker.",
        style = MaterialTheme.typography.bodyMedium,
        color = g.muted,
    )
    SettingsBox("Character") {
        Character(services, settings, update)
        Text(
            "This is the house's character. Each person can pick their own, and its voice, under People; saying any character's name wakes it.",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
    }

    SettingsBox("Language") {
        Segmented(Language.entries, settings.language, { it.label }) { l ->
            update { it.withLanguage(l) }
            if (l == Language.Swedish && settings.persona == Persona.Nova) services.alma.download()
        }
        Text(
            when {
                settings.persona == Persona.Tars -> "TARS understands the language you pick here but always answers in English."
                settings.language == Language.Swedish -> "Svenska gives Nova Alma's Swedish voice, made on the tablet. You can pick another voice under Voice and look."
                else -> "Svenska + English hears both and answers in the language you spoke."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
    }

    if (!menu) return
    SettingsBox("Settings") {
        // Two to a row, so the eight pages take four rows instead of eight.
        val pages = listOf(
            Triple(Sym.speaker, "Voice and look") { onPage("voice") },
            Triple(Sym.mic, "Listening") { onPage("listen") },
            Triple(Sym.autoAwesome, "AI and servers: ${settings.mode.label}") { onPage("brain") },
            Triple(Sym.flow, "Routines (${services.routines.data.value.routines.size})", onRoutines),
            Triple(Sym.lightbulb, "Memory: " + if (settings.access(Secret.Memory) != Access.Off) "${services.memory.data.value.facts.size} facts" else "off") { onPage("memory") },
            Triple(Sym.shieldLock, "Privacy and security") { onPage("privacy") },
            Triple(Sym.musicNote, "Spotify on Sonos") { onPage("spotify") },
            Triple(Sym.send, "Logs for Claude") { onPage("logs") },
        )
        pages.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (icon, text, open) -> NavRow(icon, text, open, Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Nova or TARS, in one tap: TARS brings his voice (downloaded to the tablet), his name as the
 * wake word, the "huuh?" when he hears it, and his dry humor.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Character(services: AssistantServices, settings: AgentSettings, update: ((AgentSettings) -> AgentSettings) -> Unit) {
    val g = GlassTheme.colors
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Persona.entries.forEach { p ->
            Chip(p.label, p == settings.persona) {
                update { it.asCharacter(p) }
                if (p == Persona.Tars) services.tars.download()
                if (p == Persona.Nova && settings.language == Language.Swedish) services.alma.download()
                if (p != settings.persona) introduce(services, scope, settings.asCharacter(p))
            }
        }
    }
    Text(settings.persona.detail, style = MaterialTheme.typography.bodyMedium, color = g.muted)
    Traits(settings, update)
    // TARS's humor is the house's, also when only someone under People talks with him.
    val people by services.faces.people.collectAsState()
    if (settings.persona == Persona.Tars || people.any { Persona.Tars in it.characters }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Humor ${settings.humor} %", style = MaterialTheme.typography.labelLarge, color = g.fg)
            GlassSlider(settings.humor / 100f, { v -> update { it.copy(humor = (v * 100).toInt()) } }, Modifier.weight(1f))
        }
        PiperVoiceState(
            services.tars,
            "TARS's voice is on the tablet. It's a fan-made copy of Bill Irwin's voice from the film (TARS-AI, non-commercial licence), for home use.",
            "TARS's voice isn't downloaded yet (about 70 MB). Until it is, the tablet's own voice speaks.",
        )
    }
}

/**
 * Each character's personality in its own words, as the assistant is told it: open one, change
 * the text, and the next question uses it. Empty, or back to the built-in, uses the app's own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Traits(settings: AgentSettings, update: ((AgentSettings) -> AgentSettings) -> Unit) {
    val g = GlassTheme.colors
    var editing by remember { mutableStateOf<Persona?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Persona.entries.forEach { p ->
            val own = !settings.traits[p].isNullOrBlank()
            Chip("Edit ${p.label}'s personality" + if (own) " (yours)" else "", editing == p) { editing = if (editing == p) null else p }
        }
    }
    val p = editing ?: return
    val builtIn = app.homeydash.agent.builtInTraits(p, settings.humor)
    // The text lives here while typing, so clearing it all doesn't snap back to the built-in.
    var text by remember(p) { mutableStateOf(settings.traits[p]?.takeIf { it.isNotBlank() } ?: builtIn) }
    TextBox(text, singleLine = false, hint = "How ${p.label} talks and behaves") { t ->
        text = t
        update { s -> s.copy(traits = if (t.isBlank() || t.trim() == builtIn.trim()) s.traits - p else s.traits + (p to t)) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (settings.traits[p].isNullOrBlank()) "This is the built-in personality. Change it and ${p.label} talks your way from the next question."
            else "Your own words for ${p.label}. " + if (p == Persona.Tars) "The humor slider still sets how funny he is." else "",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
            modifier = Modifier.weight(1f),
        )
        if (!settings.traits[p].isNullOrBlank()) Chip("Use the built-in", false) { text = builtIn; update { s -> s.copy(traits = s.traits - p) } }
    }
}

/** Who complains about the battery: the owner's own character under People, or the house's. */
@Composable
private fun batteryVoice(services: AssistantServices, settings: AgentSettings): String {
    val people by services.faces.people.collectAsState()
    return settings.characterOf(people.firstOrNull { it.role == app.homeydash.agent.Role.Owner }).label
}

/** The character in [s] says what it is like, in its own voice, as it is picked. */
internal fun introduce(services: AssistantServices, scope: kotlinx.coroutines.CoroutineScope, s: AgentSettings) {
    val swedish = s.answers != Language.English
    val text = s.persona.intro(s.nameOf(s.persona), swedish, s.humor)
    scope.launch { services.output.speak(text, if (swedish) Language.Swedish else Language.English, s.voice) }
}

/** Whether a voice made on the tablet (TARS's, Alma's) is there yet, with a way to fetch it. */
@Composable
private fun PiperVoiceState(voice: app.homeydash.assistant.PiperVoice, ready: String, missing: String) {
    val g = GlassTheme.colors
    val state by voice.state.collectAsState()
    val text = when (val s = state) {
        app.homeydash.assistant.PiperVoice.State.Ready -> ready
        app.homeydash.assistant.PiperVoice.State.Missing -> missing
        is app.homeydash.assistant.PiperVoice.State.Downloading -> "Downloading ${s.what}: ${(s.progress * 100).toInt()} %"
        is app.homeydash.assistant.PiperVoice.State.Failed -> "Couldn't get the voice: ${s.message}"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = g.muted, modifier = Modifier.weight(1f))
        if (state is app.homeydash.assistant.PiperVoice.State.Missing || state is app.homeydash.assistant.PiperVoice.State.Failed) {
            Chip("Download", false) { voice.download() }
        }
    }
}

/** How it sounds and looks, and where answers are played. */
@Composable
private fun VoicePage(services: AssistantServices, settings: AgentSettings, update: ((AgentSettings) -> AgentSettings) -> Unit) {
    val g = GlassTheme.colors
    SettingsBox("Answers") {
        SwitchRow("Speak answers", "Off shows answers as text only.", settings.speak) { on -> update { it.copy(speak = on) } }
        SwitchRow(
            "Talk like a person",
            "Starts saying the answer while the AI is still writing it, and says a short \"hmm\" when an answer takes a moment. " +
                "On the tablet's own speaker; a Sonos gets the whole answer at once.",
            settings.liveSpeech,
        ) { on -> update { it.copy(liveSpeech = on) } }
    }
    LookAndVoice(services, settings, update)
    SettingsBox("Speaker") {
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
    }
}

/** The wake word, its sound, how long it keeps listening, and who turns speech into text. */
@Composable
private fun ListenPage(services: AssistantServices, settings: AgentSettings, update: ((AgentSettings) -> AgentSettings) -> Unit) {
    val g = GlassTheme.colors
    SettingsBox("Voice activation") {
        WakeSettings(services, settings, update)
    }
    SettingsBox("Conversation") {
        Text("Keep listening after an answer", style = MaterialTheme.typography.labelLarge, color = g.fg)
        Segmented(FollowUp.entries, settings.followUp, { it.label }) { f -> update { it.copy(followUp = f) } }
        Text(
            settings.followUp.detail + " Say \"stop\", \"thanks\" or \"tack\" to end early.",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
        Text("Talk over the answer", style = MaterialTheme.typography.labelLarge, color = g.fg)
        Segmented(app.homeydash.agent.Interrupt.entries, settings.interrupt, { it.label }) { i -> update { it.copy(interrupt = i) } }
        Text(
            settings.interrupt.detail + if (settings.interrupt != app.homeydash.agent.Interrupt.Off) " It stops and listens to what you say, like a person would." else "",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
    }
    SettingsBox("Who hears your questions") {
        Hearing(services, settings, update)
        val context = androidx.compose.ui.platform.LocalContext.current
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "The tablet's speech recognition needs Swedish and English. If it says a language is missing, add them in Android's speech settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = g.muted,
                modifier = Modifier.weight(1f),
            )
            SpeechSettingsButton { app.homeydash.assistant.SpeechInput.openSettings(context) }
        }
    }
}

/** Which AI answers, and how to reach the local and the internet one. */
@Composable
private fun BrainPage(
    services: AssistantServices,
    settings: AgentSettings,
    update: ((AgentSettings) -> AgentSettings) -> Unit,
    preview: (local: Boolean) -> Pair<HomeView, AgentTools>,
) {
    val g = GlassTheme.colors
    SettingsBox("Which AI answers") {
        Segmented(BrainMode.entries, settings.mode, { it.label }) { m -> update { it.copy(mode = m) } }
        Text(
            when (settings.mode) {
                BrainMode.Smart -> "Your computer first. When it's off or can't answer, the internet AI takes over. " +
                    "For things it doesn't know, like news or opening hours, your computer asks the internet AI with web search, sending only the question."
                BrainMode.Local -> "Only the AI on your computer. Nothing leaves the house, except speech recognition when the tablet can't do it on its own."
                BrainMode.Internet -> "Only Gemini or OpenAI, which can search the web."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
    }
    Servers(services, settings, update)
    SettingsBox("Internet AI") {
        val internet = settings.internet ?: Connection(Provider.Gemini)
        Segmented(listOf(Provider.Gemini, Provider.OpenAI), internet.provider, { it.label }) { p ->
            update { it.copy(internet = if (p == internet.provider) internet else Connection(p)) }
        }
        ConnectionFields(services, internet) { c -> update { it.copy(internet = c) } }
    }
    SpeedTest(services, settings, preview)
}

/** Every server on your own computer in one place: the local AI, Whisper and the Warm voice's model. */
@Composable
private fun Servers(services: AssistantServices, settings: AgentSettings, update: ((AgentSettings) -> AgentSettings) -> Unit) {
    val g = GlassTheme.colors
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<Pair<app.homeydash.agent.Hearer, String>?>(null) }
    SettingsBox("Local AI on your computer") {
        val local = settings.local ?: Connection(Provider.Ollama)
        ConnectionFields(services, local, keyHint = "Only if the server asks for one") { c -> update { it.copy(local = c) } }
        SwitchRow(
            "Short tool list",
            "The local AI gets its tools as a few lines of text and writes its calls itself. A much shorter prompt, so faster answers, " +
                "and it works on servers like vMLX that lose tool calls on long prompts.",
            settings.compactTools,
        ) { on -> update { it.copy(compactTools = on) } }
    }
    SettingsBox("Whisper (hears your questions)") {
        Field("Address", settings.whisperUrl.ifBlank { settings.whisperUrlOrGuess() }, keyboard = KeyboardType.Uri) { url -> update { it.copy(whisperUrl = url) } }
        HearingTest(
            app.homeydash.agent.Hearer.Whisper,
            result,
            "On the Mac: \"brew install whisper-cpp\", download a model, then run whisper-server with --host 0.0.0.0 --port 8080. " +
                if (settings.useWhisper) "It's on under Listening." else "Turn it on under Listening.",
        ) { h ->
            result = h to "Testing…"
            scope.launch { result = h to (services.transcriber.test(services.settings.value, h)?.let { "Didn't work: $it" } ?: "Works.") }
        }
    }
    SettingsBox("Voice model (the Warm voice)") {
        Field("Address", settings.voiceServerUrl.ifBlank { settings.voiceServerUrlOrGuess() }, keyboard = KeyboardType.Uri) { url -> update { it.copy(voiceServerUrl = url) } }
        Field("Model", settings.voiceServerModel) { model -> update { it.copy(voiceServerModel = model) } }
        Text(
            "mlx-audio on the Mac speaks the Warm voice. Which recording each character copies is under Voice and look.",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
    }
}

/**
 * Times the AIs with the assistant's real prompt, so models and servers can be compared: how
 * long until the first word, how fast it writes, and whether the second time is faster because
 * the server kept the prompt worked through. The result also goes to the logs.
 */
@Composable
private fun SpeedTest(services: AssistantServices, settings: AgentSettings, preview: (local: Boolean) -> Pair<HomeView, AgentTools>) {
    val g = GlassTheme.colors
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    SettingsBox("Speed") {
        Text(
            "Asks each AI a short question with everything it normally reads, twice. \"First word\" is how long it read before answering; " +
                "if the second time isn't much faster, the server reads the whole prompt again for every question.",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
        Text(
            if (result == "Testing…") "Testing…" else "Speed test",
            style = MaterialTheme.typography.labelLarge,
            color = g.fg,
            modifier = Modifier
                .pressable({
                    if (result != "Testing…") {
                        result = "Testing…"
                        scope.launch {
                            result = try {
                                services.agent.speedTest(settings, view = { local -> preview(local).first }, tools = { v -> preview(v.local).second }).joinToString("\n\n")
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                "Didn't work: ${e.message}"
                            }
                        }
                    }
                })
                .glass(PillShape)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
        result?.takeIf { it != "Testing…" }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp), color = g.fg)
        }
    }
}

/** What the assistant may read and do, and who is who. */
@Composable
private fun PrivacyPage(
    settings: AgentSettings,
    update: ((AgentSettings) -> AgentSettings) -> Unit,
    onDevices: () -> Unit,
    onPreview: () -> Unit,
    onFaces: () -> Unit,
    onPhones: () -> Unit,
) {
    val g = GlassTheme.colors
    Text(
        "The assistant reads ordinary things: rooms, lights, climate, sensors, players, moods, Flow names, weather. " +
            "Everything below is off until you turn it on. \"Local AI only\" keeps it away from the internet AI. " +
            "Keys, codes and camera pictures are never sent.",
        style = MaterialTheme.typography.bodyMedium,
        color = g.muted,
    )
    SettingsBox("People and devices") {
        NavRow(Sym.face, "People: faces and voices", onFaces)
        NavRow(Sym.visibilityOff, "Hide devices from the assistant (${settings.hiddenDevices.size})", onDevices)
        NavRow(Sym.search, "What the assistant sees", onPreview)
    }
    SettingsBox("What the assistant may use") {
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
                // Like an iOS settings row: what it is on the left, a small Off / Local / On switch on the right.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(secret.title, style = MaterialTheme.typography.labelLarge, color = g.fg)
                        Text(secret.detail, style = MaterialTheme.typography.bodyMedium, color = g.muted)
                    }
                    Segmented(Access.entries, settings.access(secret), { it.label }, enabled = enabled, compact = true) { a ->
                        update { it.copy(secrets = it.secrets + (secret to a)) }
                    }
                }
                if (secret == Secret.Phones && settings.access(secret) != Access.Off) NavRow(Sym.phone, "Whose phone is whose", onPhones)
            }
        }
    }
}

/** Who may find which phone, and each phone's owner. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhonesPage(services: AssistantServices, devices: Map<String, Device>, settings: AgentSettings, update: ((AgentSettings) -> AgentSettings) -> Unit) {
    val g = GlassTheme.colors
    val people by services.faces.people.collectAsState()
    val phones = devices.values.filter { Phones.isPhone(it) }.sortedBy { it.name.lowercase() }
    Text(
        "Say \"${settings.nameOf(settings.persona)}, where's my phone?\" and it rings yours through Homey's Apple Find My app, even on silent. " +
            "It knows whose phone to ring by your voice or face, so teach it under People and switch on Face and voice recognition.",
        style = MaterialTheme.typography.bodyMedium,
        color = g.muted,
    )
    SettingsBox("Who may find a phone") {
        Segmented(PhoneSharing.entries, settings.phoneSharing, { it.label }) { p -> update { it.copy(phoneSharing = p) } }
        Text(settings.phoneSharing.detail, style = MaterialTheme.typography.bodyMedium, color = g.muted)
        if (settings.access(Secret.Faces) == Access.Off && settings.phoneSharing != PhoneSharing.Anyone) {
            Text(
                "Face and voice recognition is off, so it can't tell who is asking: only phones set to Shared can be rung.",
                style = MaterialTheme.typography.bodyMedium,
                color = g.danger,
            )
        }
    }
    SettingsBox("Phones") {
        if (phones.isEmpty()) {
            Text(
                "No phones yet. Install Apple Find My on your Homey (Homey Pro), sign in with each person's Apple ID and add their iPhones as devices.",
                style = MaterialTheme.typography.bodyMedium,
                color = g.muted,
            )
        }
        val names = people.associate { it.id to it.name }
        phones.forEach { phone ->
            val chosen = settings.phoneOwners[phone.id]
            val guessed = names.entries.firstOrNull { Phones.named(phone.name, it.value) }?.value
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(phone.name, style = MaterialTheme.typography.labelLarge, color = g.fg)
                Text(
                    when {
                        chosen == SHARED_PHONE -> "Shared: anyone at the tablet can ring and find it."
                        chosen != null -> "${names[chosen] ?: "Someone removed from People"}'s phone."
                        guessed != null -> "$guessed's phone, by its name."
                        else -> "Nobody's yet: pick whose it is."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = g.muted,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    fun pick(owner: String?) = update { s -> s.copy(phoneOwners = if (owner == null) s.phoneOwners - phone.id else s.phoneOwners + (phone.id to owner)) }
                    Chip("By its name", chosen == null) { pick(null) }
                    people.forEach { p -> Chip(p.name, chosen == p.id) { pick(p.id) } }
                    Chip("Shared", chosen == SHARED_PHONE) { pick(SHARED_PHONE) }
                }
            }
        }
    }
}

/** A Spotify developer app's keys, so any song can be searched for and played on the Sonos speakers. */
@Composable
private fun Spotify(settings: AgentSettings, update: ((AgentSettings) -> AgentSettings) -> Unit) {
    val g = GlassTheme.colors
    Text(
        "Play any song, album, artist or playlist on Sonos: say \"spela Dancing Queen i köket\", or use the Spotify tab under Play music. " +
            "Spotify only finds the music; the speakers play it with the Spotify account already added in the Sonos app.",
        style = MaterialTheme.typography.bodyMedium,
        color = g.muted,
    )
    SettingsBox("Spotify app") {
        Field("Client ID", settings.spotifyClientId) { id -> update { it.copy(spotifyClientId = id) } }
        Field("Client secret", settings.spotifySecret, secret = true) { secret -> update { it.copy(spotifySecret = secret) } }
        Text(
            "Make an app at developer.spotify.com/dashboard (any name, redirect URI http://127.0.0.1:8888/callback, Web API ticked) " +
                "with a Spotify Premium account, then copy its Client ID and Client secret here.",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
    }
}

/** Sends what the assistant heard, thought and did to a GitHub issue, to find out why it misbehaves. */
@Composable
private fun Logs(services: AssistantServices, settings: AgentSettings, update: ((AgentSettings) -> AgentSettings) -> Unit) {
    val g = GlassTheme.colors
    val scope = rememberCoroutineScope()
    SettingsBox(null) {
        SwitchRow(
            "Send logs",
            "Your questions, the AI's answers and every tool it used go to the \"Assistant logs\" issue on GitHub a minute and a half after you stop talking. " +
                "Anything set to Local AI only, keys and codes are left out.",
            settings.sendLogs,
        ) { on -> update { it.copy(sendLogs = on) } }
        if (settings.sendLogs) {
            Field("GitHub token", settings.logToken, secret = true) { token -> update { it.copy(logToken = token) } }
            Field("Repository", settings.logRepo) { repo -> update { it.copy(logRepo = repo) } }
            Text(
                "Make a fine-grained token on github.com (Settings, Developer settings, Personal access tokens) for this repository only, with Issues set to Read and write.",
                style = MaterialTheme.typography.bodyMedium,
                color = g.muted,
            )
            val status = services.logStatus
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Send now",
                    style = MaterialTheme.typography.labelLarge,
                    color = g.fg,
                    modifier = Modifier.pressable({ scope.launch { services.sendLog() } }).glass(PillShape).padding(horizontal = 18.dp, vertical = 10.dp),
                )
                Text(
                    status ?: "${services.log.unsent().size} lines waiting.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status?.startsWith("Couldn't") == true) g.danger else g.muted,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Whisper on the computer and/or OpenAI turn questions into text instead of Google. */
@Composable
private fun Hearing(services: AssistantServices, settings: AgentSettings, update: ((AgentSettings) -> AgentSettings) -> Unit) {
    val g = GlassTheme.colors
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<Pair<app.homeydash.agent.Hearer, String>?>(null) }
    Text(
        "With neither on, Android's speech recognition from Google hears your questions. With both on, Whisper is tried first and OpenAI when the computer doesn't answer.",
        style = MaterialTheme.typography.bodyMedium,
        color = g.muted,
    )
    SwitchRow(
        "Whisper on your computer",
        "Best Swedish, free, and nothing leaves the house. Runs next to Ollama.",
        settings.useWhisper,
    ) { on -> update { it.copy(useWhisper = on) } }
    if (settings.useWhisper) {
        Text(
            "Whisper's address and its test are under AI and servers, with the other servers on your computer.",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
    }
    SwitchRow(
        "OpenAI",
        "ChatGPT-level hearing in Swedish and English, about 3 öre a minute. Your question's audio is sent to OpenAI.",
        settings.useOpenAiSpeech,
    ) { on -> update { it.copy(useOpenAiSpeech = on) } }
    if (settings.useOpenAiSpeech) {
        val brainKey = settings.internet?.takeIf { it.provider == Provider.OpenAI && it.apiKey.isNotBlank() } != null
        Field("API key", settings.openAiSpeechKey, secret = true) { key -> update { it.copy(openAiSpeechKey = key) } }
        HearingTest(
            app.homeydash.agent.Hearer.OpenAI,
            result,
            if (brainKey) "Leave the key empty to use the internet AI's OpenAI key." else "Get a key at platform.openai.com.",
        ) { h ->
            result = h to "Testing…"
            scope.launch { result = h to (services.transcriber.test(services.settings.value, h)?.let { "Didn't work: $it" } ?: "Works.") }
        }
    }
}

@Composable
private fun HearingTest(
    hearer: app.homeydash.agent.Hearer,
    result: Pair<app.homeydash.agent.Hearer, String>?,
    hint: String,
    onTest: (app.homeydash.agent.Hearer) -> Unit,
) {
    val g = GlassTheme.colors
    val text = result?.takeIf { it.first == hearer }?.second
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Test",
            style = MaterialTheme.typography.labelLarge,
            color = g.fg,
            modifier = Modifier.pressable({ onTest(hearer) }).glass(PillShape).padding(horizontal = 18.dp, vertical = 10.dp),
        )
        Text(
            text ?: hint,
            style = MaterialTheme.typography.bodyMedium,
            color = if (text?.startsWith("Didn't") == true) g.danger else g.muted,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ConnectionFields(services: AssistantServices, connection: Connection, keyHint: String = "", onChange: (Connection) -> Unit) {
    val g = GlassTheme.colors
    val scope = rememberCoroutineScope()
    var result by remember(connection.provider) { mutableStateOf<String?>(null) }
    // The fields keep their own text while typing; switching provider starts them afresh.
    key(connection.provider) {
        Field("Address", connection.baseUrl, keyboard = KeyboardType.Uri) { onChange(connection.copy(baseUrl = it)) }
        Field("API key", connection.apiKey, secret = true, hint = keyHint) { onChange(connection.copy(apiKey = it)) }
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
                    scope.launch {
                        result = services.chat.speedTest(connection).fold(
                            { st -> "Works." + (st?.let { " " + it.text().replaceFirstChar(Char::uppercase) + "." } ?: "") },
                            { e -> "Didn't work: ${e.message ?: e}" },
                        )
                    }
                })
                .glass(PillShape)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
        Text(
            result ?: when (connection.provider) {
                Provider.Ollama -> "Any server on your computer that speaks the OpenAI format: oMLX, LM Studio or Ollama. Give its address with /v1 at the end and the model's name as the server shows it."
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
private fun HiddenDevices(services: AssistantServices, zones: Map<String, Zone>, devices: Map<String, Device>, hidden: Set<String>, settings: AgentSettings, onHide: (String, Boolean) -> Unit) {
    val g = GlassTheme.colors
    Text(
        "Turn off devices the assistant shouldn't know about. Locks, alarms and cameras are left out unless switched on under Security.",
        style = MaterialTheme.typography.bodyMedium,
        color = g.muted,
    )
    SwitchRow(
        "Also devices hidden on the dashboard",
        "The assistant can find and control devices you've hidden on the dashboard or in the Homey app.",
        settings.seeHiddenDevices,
    ) { on -> services.settingsStore.update { it.copy(seeHiddenDevices = on) } }
    devices.values.filter { settings.seeHiddenDevices || (!it.hidden && it.id !in hidden) }
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
    var raw by remember { mutableStateOf(false) }
    Segmented(listOf(false, true), raw, { if (it) "Everything, as it reads it" else "Built-in rules by topic" }) { raw = it }
    if (!raw) {
        val topics = remember(view, tools) {
            val who = services.agent.character(settings)
            val rules = services.agent.rules(settings, local).removeSuffix(who)
            app.homeydash.agent.RuleBook.topics(rules, who, tools.specs())
        }
        Text(
            "What the app has told the assistant, sorted by topic, and the tools it can use for each. " +
                "Your own routines and instructions come on top of these.",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
        topics.forEach { topic ->
            SettingsBox(topic.title) {
                topic.rules.forEach { rule ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("•", style = MaterialTheme.typography.bodyMedium, color = g.muted)
                        Text(rule, style = MaterialTheme.typography.bodyMedium, color = g.fg)
                    }
                }
                topic.tools.forEach { tool ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Tool: " + tool.name,
                            style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                            color = g.fg,
                        )
                        Text(tool.description, style = MaterialTheme.typography.bodyMedium, color = g.muted)
                        tool.parameters.forEach { (name, about) ->
                            Text(
                                "  $name" + if (about.isNotBlank()) ": $about" else "",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = g.muted,
                            )
                        }
                    }
                }
            }
        }
        return
    }
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
internal fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = GlassTheme.colors.muted, modifier = Modifier.padding(top = 4.dp))
}

@Composable
internal fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, enabled: Boolean = true, compact: Boolean = false, onPick: (T) -> Unit) {
    val g = GlassTheme.colors
    // Compact sits beside its title, sized to its words; otherwise it spans the page.
    Row((if (compact) Modifier.width(IntrinsicSize.Max) else Modifier.fillMaxWidth()).glass(PillShape).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
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
                    .padding(vertical = if (compact) 7.dp else 11.dp, horizontal = if (compact) 14.dp else 0.dp),
            )
        }
    }
}

@Composable
internal fun Field(label: String, value: String, secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, hint: String = "", onChange: (String) -> Unit) {
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
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty() && hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = g.muted, maxLines = 1)
                    inner()
                }
            },
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
internal fun NavRow(icon: app.homeydash.ui.components.Ic, text: String, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    val g = GlassTheme.colors
    Row(
        modifier.pressable(onClick).glass(PillShape).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SymIcon(icon, size = 20.dp)
        Text(text, style = MaterialTheme.typography.labelLarge, color = g.fg, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 10.dp))
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
    SettingsBox("Look") {
        Box(Modifier.fillMaxWidth().height(150.dp).glass(RoundedCornerShape(22.dp))) {
            VoiceFace(settings.face, previewState, levels = { VoiceLevels(0f, 0f, 0f, 0f) }, modifier = Modifier.fillMaxWidth().height(150.dp), classicRed = settings.classicRed, demo = true)
        }
        Segmented(listOf(AssistantState.Listening, AssistantState.Thinking, AssistantState.Speaking), previewState, { it.name }) { previewState = it }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FaceStyle.entries.forEach { f -> Chip(f.label, f == settings.face) { update { it.copy(face = f) } } }
            if (settings.face == FaceStyle.Scanner) Chip("Classic red", settings.classicRed) { update { it.copy(classicRed = !it.classicRed) } }
        }
        Text(settings.face.detail, style = MaterialTheme.typography.bodyMedium, color = g.muted)

        Text("Starts as", style = MaterialTheme.typography.labelLarge, color = g.fg)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            app.homeydash.agent.AssistantView.entries.forEach { v -> Chip(v.label, v == settings.view) { update { it.copy(view = v) } } }
        }
        Text(settings.view.detail, style = MaterialTheme.typography.bodyMedium, color = g.muted)
    }

    SettingsBox("Voice") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VoiceStyle.entries.forEach { v ->
                Chip(v.label, v == settings.voice) {
                    update { it.copy(voice = v) }
                    if (v == VoiceStyle.Tars) services.tars.download()
                    if (v == VoiceStyle.Alma) services.alma.download()
                }
            }
            Chip("Hear it", false) {
                scope.launch {
                    val tars = settings.persona == Persona.Tars
                    val sample = when {
                        tars -> "Humor setting at ${settings.humor} percent. Dinner music in the living room? I'll try not to pick anything with bagpipes."
                        settings.language == Language.English -> "Hi, I'm your kitchen assistant. Dinner music in the living room?"
                        else -> "Hej, jag är er köksassistent. Ska jag sätta på middagsmusik?"
                    }
                    services.output.speak(sample, settings.answers, settings.voice)
                }
            }
        }
        Text(
            settings.voice.detail + if (settings.voice.local) "" else " Made from the tablet's own voice with pitch, speed and a sound filter.",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val range = app.homeydash.agent.PACE_RANGE
            Text("Speaking speed ${settings.pace} %", style = MaterialTheme.typography.labelLarge, color = g.fg)
            GlassSlider(
                (settings.pace - range.first) / (range.last - range.first).toFloat(),
                { v -> update { it.copy(pace = (range.first + v * (range.last - range.first)).toInt().coerceIn(range)) } },
                Modifier.weight(1f),
            )
        }
    }
    SettingsBox("Battery reminders") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Battery reminders", settings.batteryReminders) { update { it.copy(batteryReminders = !it.batteryReminders) } }
        }
        Text(
            "Under ${app.homeydash.agent.BatteryNag.BELOW} % and not charging, ${batteryVoice(services, settings)} asks every 20 minutes to be charged, never from 21:00 to 07:00.",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
    }
    SettingsBox("Kitchen timers") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Ask for a name", settings.askTimerName) { update { it.copy(askTimerName = !it.askTimerName) } }
        }
        Text(
            "After a timer is started without a name, the assistant asks if you want to give it one. Named timers are remembered, so \"sätt en pastatimer\" uses your usual length.",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
    }
    if (settings.voice == VoiceStyle.Alma) {
        SettingsBox("Alma") {
            PiperVoiceState(
                services.alma,
                "Alma's voice is on the tablet, so Swedish answers need no computer and start straight away.",
                "Alma's voice isn't downloaded yet (about 63 MB). Until it is, the tablet's own voice speaks.",
            )
        }
    }
    if (settings.voice.local && settings.voice != VoiceStyle.Tars) {
        SettingsBox("Warm voice recordings") {
            app.homeydash.agent.Persona.entries.forEach { p ->
                Field("${p.label}'s voice", settings.warmVoices[p].orEmpty()) { voice ->
                    update { it.copy(warmVoices = if (voice.isBlank()) it.warmVoices - p else it.warmVoices + (p to voice)) }
                }
            }
            Text(
                "Chatterbox on the Mac copies a voice from a 15-20 second recording in the voices folder: type its name " +
                    "(\"anna\" for anna.wav) for each character, or leave it empty for the model's own voice. People can pick " +
                    "their own under People. Everything stays in the house. Tap Hear it to try it; if the computer doesn't " +
                    "answer, the tablet's voice speaks and it tries the computer again two minutes later. " +
                    "The voice server's address is under AI and servers.",
                style = MaterialTheme.typography.bodyMedium,
                color = g.muted,
            )
        }
    }
}

@Composable
internal fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
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
