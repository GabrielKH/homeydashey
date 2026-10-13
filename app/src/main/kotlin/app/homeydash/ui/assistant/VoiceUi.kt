package app.homeydash.ui.assistant

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import app.homeydash.agent.AgentSettings
import app.homeydash.agent.FaceMatcher
import app.homeydash.agent.KnownPerson
import app.homeydash.agent.WakeName
import app.homeydash.assistant.Assistant
import app.homeydash.assistant.AssistantServices
import app.homeydash.assistant.AssistantState
import app.homeydash.assistant.VoiceModels
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.pressable
import app.homeydash.ui.dashboard.Toggle
import app.homeydash.ui.theme.GlassTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val VOICE_SAMPLES = 8

/** Ways to say the wake name while teaching, so the tablet hears the voice more than one way. */
private val VOICE_WAYS = listOf(
    "normally", "a little quieter", "a little louder", "quickly",
    "from a few steps away", "the Swedish way (\"Hej\")", "while looking away from the tablet", "as you would while cooking",
)

private fun micGranted(context: android.content.Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/**
 * Listens for the wake name while the dashboard is on screen and the assistant is idle.
 * The microphone is let go whenever the assistant listens, speaks or the app is away.
 */
@Composable
fun WakeWatch(assistant: Assistant, services: AssistantServices, settings: AgentSettings) {
    val models by services.voiceModels.state.collectAsState()
    val people by services.faces.people.collectAsState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val context = LocalContext.current
    val wake = services.wake
    val enabled = settings.enabled && settings.wake && WakeName.clean(settings.wakeName).isNotEmpty() && models == VoiceModels.State.Ready
    val on = enabled && micGranted(context) && lifecycle.isAtLeast(Lifecycle.State.RESUMED) &&
        assistant.state == AssistantState.Idle && !assistant.conversing && !wake.enrolling
    if (on) {
        LaunchedEffect(settings.wakeName, settings.onlyKnownVoices, settings.micSensitivity, people) {
            // Give the speech recogniser or text-to-speech a moment to let go of the audio.
            delay(600)
            val voices = people.filter { it.voice.isNotEmpty() }
            var token: Int? = null
            token = wake.start(
                settings.wakeName,
                onHeard = { _, vector ->
                    val person = vector?.let { FaceMatcher.matchVoice(it, voices) }
                    // A wake that clearly was them teaches the tablet a little more of their voice.
                    if (vector != null && person != null && FaceMatcher.voiceScore(vector, person) >= FaceMatcher.VOICE_LEARN) {
                        services.faces.addVoice(person.id, listOf(vector))
                    }
                    if (!settings.onlyKnownVoices || voices.isEmpty() || person != null) {
                        wake.stop(token)
                        assistant.wake(person)
                    }
                },
                onError = {},
                sensitivity = settings.micSensitivity,
            )
            try {
                awaitCancellation()
            } finally {
                wake.stop(token)
            }
        }
    }
    if (!enabled) {
        DisposableEffect(Unit) {
            wake.release()
            onDispose {}
        }
    }
}

/** Settings → Assistant → Voice activation. */
@Composable
fun WakeSettings(services: AssistantServices, settings: AgentSettings, update: ((AgentSettings) -> AgentSettings) -> Unit) {
    val g = GlassTheme.colors
    val context = LocalContext.current
    val models by services.voiceModels.state.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) update { it.copy(wake = true) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Wake by name", style = MaterialTheme.typography.labelLarge, color = g.fg)
            Text(
                "Say \"Hey ${settings.wakeName.trim().ifEmpty { "Nova" }}\" and the assistant starts listening, like \"Hey Siri\". " +
                    "Heard on the tablet by a small offline model that only knows the name; nothing else is recorded or sent.",
                style = MaterialTheme.typography.bodyMedium,
                color = g.muted,
            )
        }
        Toggle(settings.wake, onClick = {
            when {
                settings.wake -> update { it.copy(wake = false) }
                micGranted(context) -> update { it.copy(wake = true) }
                else -> permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        })
    }
    if (!settings.wake) return
    when (val m = models) {
        VoiceModels.State.Missing, is VoiceModels.State.Failed -> {
            LaunchedEffect(Unit) { if (m == VoiceModels.State.Missing) services.voiceModels.download() }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Try again",
                    style = MaterialTheme.typography.labelLarge,
                    color = g.fg,
                    modifier = Modifier.pressable({ services.voiceModels.download() }).glass(PillShape).padding(horizontal = 18.dp, vertical = 10.dp),
                )
                Text(
                    (m as? VoiceModels.State.Failed)?.message ?: "Getting the voice models…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (m is VoiceModels.State.Failed) g.danger else g.muted,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        is VoiceModels.State.Downloading -> {
            Text("Getting the voice models (about 50 MB, once)…", style = MaterialTheme.typography.bodyMedium, color = g.muted)
            LinearProgressIndicator(progress = { m.progress }, modifier = Modifier.fillMaxWidth(), color = g.on, trackColor = g.glassLine)
        }
        VoiceModels.State.Ready -> Unit
    }
    NameField(settings.wakeName) { name -> update { it.copy(wakeName = name) } }
    val unknown = if (models == VoiceModels.State.Ready) {
        services.voiceModels.vocabulary?.let { WakeName.unknownWords(settings.wakeName, it) }.orEmpty()
    } else {
        emptyList()
    }
    Text(
        when {
            WakeName.clean(settings.wakeName).isEmpty() -> "Give the assistant a name."
            unknown.isNotEmpty() -> "The wake model doesn't know \"${unknown.joinToString(" ")}\". Pick a name that is an English word or common name, like Nova, Jarvis or Max."
            else -> "Use a name of two syllables or more that isn't said much at home. Any Swedish or English question works after it."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (unknown.isNotEmpty()) g.danger else g.muted,
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Only voices it knows", style = MaterialTheme.typography.labelLarge, color = g.fg)
            Text(
                "Only wake for people whose voice is taught under People. Who is speaking also sets what they may do, " +
                    "when \"Face and voice recognition\" is on.",
                style = MaterialTheme.typography.bodyMedium,
                color = g.muted,
            )
        }
        Toggle(settings.onlyKnownVoices, onClick = { update { it.copy(onlyKnownVoices = !it.onlyKnownVoices) } })
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Microphone sensitivity", style = MaterialTheme.typography.labelLarge, color = g.fg)
        app.homeydash.ui.dashboard.GlassSlider(
            settings.micSensitivity / 100f,
            { v -> update { it.copy(micSensitivity = (v * 100).roundToInt().coerceIn(0, 100)) } },
            Modifier.weight(1f),
        )
        Text("${settings.micSensitivity}%", style = MaterialTheme.typography.labelLarge, color = g.fg)
    }
    Text(
        "Higher hears the name from across the room but wakes more by mistake, for example from the TV. " +
            "It listens through the tablet's voice input, which mixes its microphones where the tablet supports it, set for a wide room.",
        style = MaterialTheme.typography.bodyMedium,
        color = g.muted,
    )
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit) {
    val g = GlassTheme.colors
    var text by remember { mutableStateOf(value) }
    Row(
        Modifier.fillMaxWidth().glass(PillShape).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Name", style = MaterialTheme.typography.labelLarge, color = g.muted, modifier = Modifier.width(90.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it; onChange(it) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = g.fg),
            cursorBrush = SolidColor(g.fg),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false),
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Teaching the tablet someone's voice: they say the wake name a few times and the
 * speaker model's fingerprint of each is kept (no recording).
 */
@Composable
fun VoiceEnrol(services: AssistantServices, settings: AgentSettings, person: KnownPerson, onDone: () -> Unit) {
    val g = GlassTheme.colors
    val context = LocalContext.current
    val models by services.voiceModels.state.collectAsState()
    val samples = remember { mutableStateListOf<FloatArray>() }
    var error by remember { mutableStateOf<String?>(null) }
    var granted by remember { mutableStateOf(micGranted(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val ready = models == VoiceModels.State.Ready && WakeName.clean(settings.wakeName).isNotEmpty()
    val phrase = "Hey ${settings.wakeName.trim()}"

    LaunchedEffect(Unit) {
        if (!granted) permission.launch(Manifest.permission.RECORD_AUDIO)
        if (models == VoiceModels.State.Missing) services.voiceModels.download()
    }
    if (ready && granted) {
        DisposableEffect(Unit) {
            val wake = services.wake
            wake.enrolling = true
            val token = wake.start(
                settings.wakeName,
                onHeard = { _, vector ->
                    if (vector == null) error = "Didn't catch that; say it a little slower." else if (samples.size < VOICE_SAMPLES) {
                        error = null
                        samples += vector
                    }
                },
                onError = { error = it },
                sensitivity = settings.micSensitivity,
            )
            onDispose {
                wake.stop(token)
                wake.enrolling = false
            }
        }
        LaunchedEffect(samples.size >= VOICE_SAMPLES) {
            if (samples.size >= VOICE_SAMPLES) {
                // Teaching again adds to what the tablet already knows of the voice.
                services.faces.addVoice(person.id, samples.toList())
                onDone()
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            when {
                !granted -> "The tablet needs the microphone to learn a voice."
                models is VoiceModels.State.Downloading -> "Getting the voice models first…"
                models is VoiceModels.State.Failed -> (models as VoiceModels.State.Failed).message
                WakeName.clean(settings.wakeName).isEmpty() -> "Give the assistant a name under Voice activation first."
                else -> "${person.name}, say \"$phrase\" ${VOICE_WAYS[samples.size.coerceAtMost(VOICE_WAYS.lastIndex)]} (${samples.size + 1} of $VOICE_SAMPLES)."
            },
            style = MaterialTheme.typography.titleMedium,
            color = g.fg,
        )
        LinearProgressIndicator(progress = { samples.size / VOICE_SAMPLES.toFloat() }, modifier = Modifier.fillMaxWidth(), color = g.on, trackColor = g.glassLine)
        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = g.muted) }
        Box {
            Text(
                "Cancel",
                style = MaterialTheme.typography.labelLarge,
                color = g.fg,
                modifier = Modifier.pressable(onDone).glass(PillShape).padding(horizontal = 22.dp, vertical = 12.dp),
            )
        }
    }
}
