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
import app.homeydash.agent.VoiceId
import app.homeydash.agent.WakeSound
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
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
    // The voiceprint model comes once wake by name is on; until then the older voice match is used.
    if (enabled) LaunchedEffect(Unit) { services.prints.download() }
    if (on) {
        LaunchedEffect(settings.wakeName, settings.onlyKnownVoices, settings.micSensitivity, people) {
            // Give the speech recogniser or text-to-speech a moment to let go of the audio.
            delay(600)
            // Each character's name is taught on its own; match against the name in use.
            val known = people.map { it.forWake(settings.wakeName) }.filter { it.voice.isNotEmpty() || it.print.isNotEmpty() }
            var token: Int? = null
            var deciding = false
            token = wake.start(
                settings.wakeName,
                onHeard = { _, vector, sound ->
                    if (!deciding) {
                        deciding = true
                        launch {
                            val print = sound?.let { services.prints.of(it) }
                            val who = whoSpoke(services, settings.wakeName, known, vector, print)
                            services.trace("wake", who.log, localOnly = true)
                            if (!settings.onlyKnownVoices || known.isEmpty() || who.person != null) {
                                wake.stop(token)
                                assistant.wake(who.person)
                            }
                            deciding = false
                        }
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

private class Speaker(val person: KnownPerson?, val log: String)

/**
 * Who said the wake name. The voiceprint decides for everyone who has voiceprints; the older
 * Vosk match only for people taught before voiceprints existed. A wake that clearly was someone
 * teaches the tablet a little more of their voice, so it keeps getting surer.
 */
private fun whoSpoke(services: AssistantServices, wakeName: String, known: List<KnownPerson>, vector: FloatArray?, print: FloatArray?): Speaker {
    val withPrints = known.filter { it.print.isNotEmpty() }
    if (print != null && withPrints.isNotEmpty()) {
        val person = VoiceId.match(print, withPrints)
        val best = withPrints.maxOf { VoiceId.margin(print, it) }
        if (person != null && VoiceId.margin(print, person) >= LEARN_MARGIN) services.faces.addPrints(person.id, wakeName, listOf(print))
        if (person != null) return Speaker(person, "Wake name heard; voiceprint matched someone taught (%.2f over their bar).".format(best))
    }
    val older = known.filter { it.print.isEmpty() && it.voice.isNotEmpty() }
    val person = vector?.let { FaceMatcher.matchVoice(it, older) }
    if (person != null && vector != null) {
        if (FaceMatcher.voiceScore(vector, person) >= FaceMatcher.VOICE_LEARN) {
            services.faces.addVoice(person.id, listOf(vector))
            // Their first voiceprints come from wakes the older match is sure of.
            print?.let { services.faces.addPrints(person.id, wakeName, listOf(it)) }
        }
        return Speaker(person, "Wake name heard; older voice match recognised someone taught.")
    }
    val why = when {
        print == null -> "no voiceprint (model not ready or too little sound)"
        withPrints.isEmpty() -> "nobody has voiceprints yet"
        else -> "voiceprint %.2f below the closest person's bar".format(-withPrints.maxOf { VoiceId.margin(print, it) })
    }
    return Speaker(null, "Wake name heard; voice not recognised: $why.")
}

/** How far over someone's bar a wake must be before the tablet learns from it. */
private const val LEARN_MARGIN = 0.1f

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
    NameField(settings.wakeName) { name ->
        update { it.copy(wakeName = name, novaName = if (it.persona == app.homeydash.agent.Persona.Nova && name.isNotBlank()) name.trim() else it.novaName) }
    }
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
    val printModel by services.prints.state.collectAsState()
    Text(
        when (val p = printModel) {
            app.homeydash.assistant.VoicePrints.State.Ready ->
                "Voices are told apart by voiceprints made on the tablet. Teach each voice under People (again, if it was taught before), " +
                    "and every clear wake teaches it a little more."
            is app.homeydash.assistant.VoicePrints.State.Downloading -> "Getting the voiceprint model (about 26 MB, once)… ${(p.progress * 100).roundToInt()}%"
            is app.homeydash.assistant.VoicePrints.State.Failed -> "Couldn't get the voiceprint model: ${p.message} The older voice match is used meanwhile."
            app.homeydash.assistant.VoicePrints.State.Missing -> "The voiceprint model comes next."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (printModel is app.homeydash.assistant.VoicePrints.State.Failed) g.danger else g.muted,
        modifier = if (printModel is app.homeydash.assistant.VoicePrints.State.Failed) Modifier.pressable({ services.prints.download() }) else Modifier,
    )
    Text("Sound when it hears its name", style = MaterialTheme.typography.labelLarge, color = g.fg)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        WakeSound.entries.forEach { sound ->
            Chip(sound.label, sound == settings.wakeSound) {
                update { it.copy(wakeSound = sound) }
                services.playWakeSound(sound)
            }
        }
    }
    Text(settings.wakeSound.detail, style = MaterialTheme.typography.bodyMedium, color = g.muted)
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
    // Picking TARS or Nova changes the name from outside the field.
    LaunchedEffect(value) { if (value != text) text = value }
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
fun VoiceEnrol(services: AssistantServices, settings: AgentSettings, person: KnownPerson, wakeName: String = settings.wakeName, onDone: () -> Unit) {
    val g = GlassTheme.colors
    val context = LocalContext.current
    val models by services.voiceModels.state.collectAsState()
    val samples = remember { mutableStateListOf<FloatArray>() }
    val prints = remember { mutableStateListOf<FloatArray>() }
    val jobs = remember { mutableListOf<kotlinx.coroutines.Job>() }
    var noSound by remember { mutableStateOf(0) }
    var failed by remember { mutableStateOf<String?>(null) }
    val printModel by services.prints.state.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var granted by remember { mutableStateOf(micGranted(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    // Wait for the voiceprint model too, unless it can't be had; then only the older voice match learns.
    val printsSettled = printModel == app.homeydash.assistant.VoicePrints.State.Ready || printModel is app.homeydash.assistant.VoicePrints.State.Failed
    val ready = models == VoiceModels.State.Ready && printsSettled && WakeName.clean(wakeName).isNotEmpty()
    val phrase = "Hey ${wakeName.trim()}"

    LaunchedEffect(Unit) {
        if (!granted) permission.launch(Manifest.permission.RECORD_AUDIO)
        if (models == VoiceModels.State.Missing) services.voiceModels.download()
        services.prints.download()
    }
    if (ready && granted) {
        DisposableEffect(Unit) {
            val wake = services.wake
            wake.enrolling = true
            val token = wake.start(
                wakeName,
                onHeard = { _, vector, sound ->
                    if (vector == null) error = "Didn't catch that; say it a little slower." else if (samples.size < VOICE_SAMPLES) {
                        error = null
                        failed = null
                        samples += vector
                        if (sound == null) noSound++ else jobs += scope.launch { services.prints.of(sound)?.let { prints += it } }
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
                // Voiceprints are worked out after each phrase; wait for all of them.
                jobs.toList().joinAll()
                // Teaching again adds to what the tablet already knows of the voice.
                services.faces.addVoice(person.id, samples.toList())
                services.faces.addPrints(person.id, wakeName, prints.toList())
                val why = when {
                    printModel !is app.homeydash.assistant.VoicePrints.State.Ready -> "the voiceprint model isn't on the tablet ($printModel)"
                    noSound == samples.size -> "the sound of the name wasn't caught"
                    else -> services.prints.lastError ?: "unknown"
                }
                services.trace(
                    "wake",
                    "Taught \"Hey $wakeName\": ${samples.size} phrases, ${prints.size} voiceprints" + if (prints.isEmpty()) " (none made: $why)." else ".",
                    localOnly = false,
                )
                if (prints.isEmpty()) {
                    // Nothing new was learned for this name; say why instead of closing as if it worked.
                    failed = "Couldn't make voiceprints for \"Hey $wakeName\": $why. Try again."
                    samples.clear()
                    jobs.clear()
                    noSound = 0
                } else {
                    onDone()
                }
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            when {
                !granted -> "The tablet needs the microphone to learn a voice."
                models is VoiceModels.State.Downloading -> "Getting the voice models first…"
                printModel is app.homeydash.assistant.VoicePrints.State.Downloading -> "Getting the voiceprint model first (about 26 MB, once)…"
                models is VoiceModels.State.Failed -> (models as VoiceModels.State.Failed).message
                WakeName.clean(wakeName).isEmpty() -> "Give the assistant a name under Voice activation first."
                else -> "${person.name}, say \"$phrase\" ${VOICE_WAYS[samples.size.coerceAtMost(VOICE_WAYS.lastIndex)]} (${samples.size + 1} of $VOICE_SAMPLES)."
            },
            style = MaterialTheme.typography.titleMedium,
            color = g.fg,
        )
        LinearProgressIndicator(progress = { samples.size / VOICE_SAMPLES.toFloat() }, modifier = Modifier.fillMaxWidth(), color = g.on, trackColor = g.glassLine)
        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = g.muted) }
        failed?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = g.danger) }
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
