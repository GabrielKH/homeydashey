package app.homeydash.ui.assistant

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.homeydash.ui.components.AppDialog
import app.homeydash.ui.components.Ic
import app.homeydash.agent.TARS_WAKE_NAME
import app.homeydash.agent.KnownPerson
import androidx.compose.runtime.key
import app.homeydash.agent.Persona
import app.homeydash.agent.VoiceStyle
import androidx.activity.compose.BackHandler
import app.homeydash.ui.components.dialogSize
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.homeydash.agent.Access
import app.homeydash.agent.AgentSettings
import app.homeydash.agent.Role
import app.homeydash.agent.Secret
import app.homeydash.assistant.AssistantServices
import app.homeydash.ui.components.PanelShape
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.RoundButton
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.pressable
import app.homeydash.ui.dashboard.Toggle
import app.homeydash.ui.theme.GlassTheme
import kotlinx.coroutines.launch

private const val SAMPLES = 8

/** Settings → Assistant → People: who the tablet knows by face or voice, and what each may do. */
@Composable
fun FacesDialog(services: AssistantServices, settings: AgentSettings, onDismiss: () -> Unit, onClose: () -> Unit = onDismiss) {
    val g = GlassTheme.colors
    val faces = services.faces
    val people by faces.people.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    // Who is being taught, and the name they say ("TARS" or Nova's name).
    var teaching by remember { mutableStateOf<Pair<String, String>?>(null) }
    AppDialog(onDismiss) { app.homeydash.ui.components.Compact {
        Column(
            Modifier
                .dialogSize(560.dp, tall = true)
                .background(g.wallBase, PanelShape)
                .glass()
                .padding(vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val back: () -> Unit = {
                when {
                    teaching != null -> teaching = null
                    adding -> adding = false
                    else -> onDismiss()
                }
                Unit
            }
            BackHandler(onBack = back)
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundButton(Sym.arrowBack, back, size = 44.dp, filledIcon = false)
                Text("People", style = MaterialTheme.typography.headlineMedium, color = g.fg, modifier = Modifier.weight(1f))
                RoundButton(Sym.close, onClose, size = 44.dp, filledIcon = false)
            }
            // Only the content scrolls; the title and close button stay put.
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "The tablet knows the people you add here by the front camera, and by their voice when they say the wake name, entirely on the tablet. " +
                        "Only a numeric fingerprint of each face and voice is kept, encrypted; no pictures or recordings. It is for convenience, not a lock: a photo or a recording can fool it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = g.muted,
                )
                if (settings.access(Secret.Faces) == Access.Off) {
                    Text(
                        "Turn on \"Face and voice recognition\" under Security for the assistant to use it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = g.danger,
                    )
                }
                val learning = people.firstOrNull { it.id == teaching?.first }
                if (learning != null) {
                    key(teaching) { VoiceEnrol(services, settings, learning, teaching!!.second) { teaching = null } }
                } else if (adding) {
                    Enrol(services, onVoiceOnly = { id -> adding = false; teaching = id to settings.wakeName }) { adding = false }
                } else {
                    people.forEach { p ->
                        PersonCard(
                            services = services,
                            person = p,
                            novaName = settings.nameOf(Persona.Nova),
                            settings = settings,
                            onTeach = { name -> teaching = p.id to name },
                            onRole = { faces.setRole(p.id, it) },
                            onRemove = {
                                faces.remove(p.id)
                                scope.launch { services.memory.forgetPerson(p.id) }
                            },
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().pressable({ adding = true }).glass(PillShape).padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SymIcon(Sym.add, size = 20.dp)
                        Text("Add a person", style = MaterialTheme.typography.labelLarge, color = g.fg, modifier = Modifier.padding(start = 10.dp))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Strangers are guests", style = MaterialTheme.typography.labelLarge, color = g.fg)
                            Text("Someone the tablet doesn't know only gets lights, music, climate, timers and weather.", style = MaterialTheme.typography.bodyMedium, color = g.muted)
                        }
                        Toggle(settings.strangersAreGuests, onClick = { services.settingsStore.update { it.copy(strangersAreGuests = !it.strangersAreGuests) } })
                    }
                }
            }
        }
    } }
}

/**
 * One person: who they are, and side by side what the tablet knows them by: their face, and
 * their voice saying each character's name.
 */
@Composable
private fun PersonCard(
    services: AssistantServices,
    person: KnownPerson,
    novaName: String,
    settings: AgentSettings,
    onTeach: (String) -> Unit,
    onRole: (Role) -> Unit,
    onRemove: () -> Unit,
) {
    val g = GlassTheme.colors
    Column(
        Modifier.fillMaxWidth().glass(androidx.compose.foundation.shape.RoundedCornerShape(22.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(48.dp).background(g.on.copy(alpha = 0.18f), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                Text(person.name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = g.fg)
            }
            Column(Modifier.weight(1f)) {
                Text(person.name, style = MaterialTheme.typography.titleLarge, color = g.fg)
                Text(person.role.label, style = MaterialTheme.typography.bodyMedium, color = g.muted)
            }
            Text("Remove", style = MaterialTheme.typography.labelLarge, color = g.danger, modifier = Modifier.pressable(onRemove).padding(8.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KnownBy(Sym.face, "Face", if (person.samples.isNotEmpty()) "Recognised" else "Not learned", person.samples.isNotEmpty(), null, Modifier.weight(1f))
            // "Hey TARS" and "Hey Nova" sound different, so each is taught on its own.
            listOf(novaName, TARS_WAKE_NAME).forEach { name ->
                val n = person.printsFor(name).size
                KnownBy(
                    Sym.mic,
                    "Hey $name",
                    if (n > 0) "Taught" else "Not taught",
                    n > 0,
                    (if (n > 0) "Teach more" else "Teach") to { onTeach(name) },
                    Modifier.weight(1f),
                )
            }
        }
        RoleChoice(person.role, onRole)
        PersonCharacter(services, settings, person)
        AboutPerson(services, person.id, person.name)
    }
}

/**
 * The character and voice this person talks with: TARS for one, Nova for another. Picking one
 * shows and says what it is like, and is remembered about them.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PersonCharacter(services: AssistantServices, settings: AgentSettings, person: KnownPerson) {
    val g = GlassTheme.colors
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val character = settings.characterOf(person)
    fun pick(p: Persona?, voice: VoiceStyle?) {
        services.faces.setCharacter(person.id, p, voice)
        val chosen = person.copy(character = p, voiceStyle = voice)
        val talking = settings.talkingAs(settings.characterOf(chosen), chosen)
        if (talking.voice == VoiceStyle.Tars) services.tars.download()
        if (talking.voice == VoiceStyle.Alma) services.alma.download()
        if (settings.access(Secret.Memory) != Access.Off) scope.launch {
            services.memory.learn(listOf(app.homeydash.agent.Memory.characterChoice(app.homeydash.agent.Asker(person.id, person.name), p, voice)))
        }
        introduce(services, scope, talking)
    }
    Text("Talks with", style = MaterialTheme.typography.labelLarge, color = g.fg)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("The house's (${settings.persona.label})", person.character == null) { if (person.character != null) pick(null, null) }
        Persona.entries.forEach { p -> Chip(p.label, person.character == p) { if (person.character != p) pick(p, null) } }
    }
    Text(character.detail, style = MaterialTheme.typography.bodyMedium, color = g.muted)
    if (person.character != null) {
        Text("Voice", style = MaterialTheme.typography.labelLarge, color = g.fg)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val usual = settings.talkingAs(character).voice
            Chip("Usual (${usual.label})", person.voiceStyle == null) { if (person.voiceStyle != null) pick(person.character, null) }
            VoiceStyle.entries.filter { it != usual }.forEach { v -> Chip(v.label, person.voiceStyle == v) { if (person.voiceStyle != v) pick(person.character, v) } }
        }
        Text(
            "Saying \"Hey ${settings.nameOf(character)}\" wakes ${character.label} for anyone it knows; ${person.name}'s own voice pick is used when ${person.name} talks with it.",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
    }
}

/** A tile saying whether the tablet knows someone by one thing, with a button to teach it. */
@Composable
private fun KnownBy(icon: Ic, title: String, status: String, known: Boolean, action: Pair<String, () -> Unit>?, modifier: Modifier) {
    val g = GlassTheme.colors
    Column(
        modifier.glass(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SymIcon(icon, size = 20.dp)
            Text(title, style = MaterialTheme.typography.labelLarge, color = g.fg, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).background(if (known) g.ok else g.muted.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape))
            Text(status, style = MaterialTheme.typography.bodyMedium, color = if (known) g.fg else g.muted)
        }
        action?.let { (label, onClick) ->
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = g.fg,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().pressable(onClick).glass(PillShape).padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun RoleChoice(role: Role, onPick: (Role) -> Unit) {
    val g = GlassTheme.colors
    Row(Modifier.fillMaxWidth().glass(PillShape).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Role.entries.forEach { r ->
            val on = r == role
            Text(
                r.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (on) g.wallBase else g.fg,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).pressable({ onPick(r) }).then(if (on) Modifier.background(g.fg, PillShape) else Modifier).padding(vertical = 10.dp),
            )
        }
    }
    Text(role.detail, style = MaterialTheme.typography.bodyMedium, color = g.muted)
}

/** Adding someone: their name and role, then a few seconds looking at the tablet. */
@Composable
private fun Enrol(services: AssistantServices, onVoiceOnly: (String) -> Unit, onDone: () -> Unit) {
    val g = GlassTheme.colors
    val faces = services.faces
    val owner = LocalLifecycleOwner.current
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(Role.Family) }
    var capturing by remember { mutableStateOf(false) }
    var face by remember { mutableStateOf<ImageBitmap?>(null) }
    var noFace by remember { mutableStateOf(false) }
    val samples = remember { mutableStateListOf<FloatArray>() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) capturing = true
    }

    if (capturing) {
        DisposableEffect(Unit) {
            val wasRunning = faces.running
            faces.start(owner)
            faces.beginEnrolment { sample ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (sample == null) {
                        noFace = true
                    } else if (samples.size < SAMPLES) {
                        noFace = false
                        samples += sample.embedding
                        face = sample.crop.asImageBitmap()
                    }
                }
            }
            onDispose {
                faces.endEnrolment()
                if (!wasRunning) faces.stop()
            }
        }
        LaunchedEffect(samples.size >= SAMPLES) {
            if (samples.size >= SAMPLES) {
                faces.add(name, role, samples.toList())
                onDone()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().glass(PillShape).padding(horizontal = 18.dp, vertical = 14.dp)) {
            Box(Modifier.weight(1f)) {
                if (name.isEmpty()) Text("First name", style = MaterialTheme.typography.bodyLarge, color = g.muted)
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    enabled = !capturing,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = g.fg),
                    cursorBrush = SolidColor(g.fg),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        RoleChoice(role) { role = it }
        if (capturing) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(96.dp).clip(CircleShape).glass(CircleShape), contentAlignment = Alignment.Center) {
                    face?.let { Image(it, null, Modifier.size(96.dp)) } ?: SymIcon(Sym.face, size = 40.dp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (noFace && samples.isEmpty()) "Look at the tablet's front camera…" else "Hold still, turning your head a little…",
                        style = MaterialTheme.typography.titleMedium,
                        color = g.fg,
                    )
                    LinearProgressIndicator(progress = { samples.size / SAMPLES.toFloat() }, modifier = Modifier.fillMaxWidth(), color = g.on, trackColor = g.glassLine)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Cancel",
                style = MaterialTheme.typography.labelLarge,
                color = g.fg,
                modifier = Modifier.pressable(onDone).glass(PillShape).padding(horizontal = 22.dp, vertical = 12.dp),
            )
            if (!capturing) {
                Text(
                    "Start",
                    style = MaterialTheme.typography.labelLarge,
                    color = g.wallBase,
                    modifier = Modifier
                        .pressable({
                            if (name.isNotBlank()) {
                                if (faces.hasPermission()) capturing = true else permission.launch(Manifest.permission.CAMERA)
                            }
                        })
                        .background(if (name.isBlank()) g.muted else g.fg, PillShape)
                        .padding(horizontal = 26.dp, vertical = 12.dp),
                )
                Text(
                    "Voice only",
                    style = MaterialTheme.typography.labelLarge,
                    color = g.fg,
                    modifier = Modifier
                        .pressable({ if (name.isNotBlank()) onVoiceOnly(faces.add(name, role, emptyList())) })
                        .glass(PillShape)
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
        }
    }
}
