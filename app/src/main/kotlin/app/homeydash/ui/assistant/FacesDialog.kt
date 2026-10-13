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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.homeydash.ui.components.dialogSize
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

private const val SAMPLES = 8

/** Settings → Assistant → People: who the tablet knows by face or voice, and what each may do. */
@Composable
fun FacesDialog(services: AssistantServices, settings: AgentSettings, onDismiss: () -> Unit) {
    val g = GlassTheme.colors
    val faces = services.faces
    val people by faces.people.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var teaching by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .dialogSize(560.dp)
                .background(g.wallBase, PanelShape)
                .glass()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("People", style = MaterialTheme.typography.headlineMedium, color = g.fg, modifier = Modifier.weight(1f))
                RoundButton(Sym.close, onDismiss, size = 44.dp, filledIcon = false)
            }
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
            val learning = people.firstOrNull { it.id == teaching }
            if (learning != null) {
                VoiceEnrol(services, settings, learning) { teaching = null }
            } else if (adding) {
                Enrol(services, onVoiceOnly = { id -> adding = false; teaching = id }) { adding = false }
            } else {
                people.forEach { p ->
                    Column(Modifier.fillMaxWidth().glass(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SymIcon(Sym.face, size = 22.dp)
                            Text(p.name, style = MaterialTheme.typography.titleMedium, color = g.fg, modifier = Modifier.weight(1f).padding(start = 10.dp))
                            Text("Remove", style = MaterialTheme.typography.labelLarge, color = g.danger, modifier = Modifier.pressable({ faces.remove(p.id) }).padding(8.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                listOfNotNull("Face".takeIf { p.samples.isNotEmpty() }, "Voice".takeIf { p.voice.isNotEmpty() })
                                    .joinToString(" and ").ifEmpty { "Nothing learned yet" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = g.muted,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                if (p.voice.isEmpty()) "Teach voice" else "Teach voice again",
                                style = MaterialTheme.typography.labelLarge,
                                color = g.fg,
                                modifier = Modifier.pressable({ teaching = p.id }).glass(PillShape).padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                        RoleChoice(p.role) { faces.setRole(p.id, it) }
                    }
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
