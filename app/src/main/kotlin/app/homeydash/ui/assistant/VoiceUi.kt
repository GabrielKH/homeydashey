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
import android.os.SystemClock
import app.homeydash.agent.AgentSettings
import app.homeydash.agent.FaceMatcher
import app.homeydash.agent.KnownPerson
import app.homeydash.agent.Interrupt
import app.homeydash.agent.Memory
import app.homeydash.agent.MicSensitivity
import app.homeydash.agent.Pcm
import app.homeydash.agent.SoundPrint
import app.homeydash.agent.TalkOver
import app.homeydash.agent.VoiceFx
import app.homeydash.agent.VoiceId
import app.homeydash.agent.WakeJudge
import app.homeydash.agent.WakeSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
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
    val people by services.faces.people.collectAsState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val context = LocalContext.current
    val wake = services.wake
    // The spotter and the voice gate come with the app; the Vosk models are only for offline dictation.
    val enabled = settings.enabled && settings.wake && WakeName.clean(settings.wakeName).isNotEmpty()
    // While it thinks or talks the wake name still works, to cut in like with a person.
    val busy = assistant.state == AssistantState.Thinking || assistant.state == AssistantState.Speaking
    val speaking = assistant.state == AssistantState.Speaking
    val on = enabled && micGranted(context) && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && !wake.enrolling &&
        (busy || (assistant.state == AssistantState.Idle && !assistant.conversing))
    // The voiceprint model comes once wake by name is on; until then the older voice match is used.
    if (enabled) LaunchedEffect(Unit) { services.prints.download() }
    if (on) {
        // The house character's name, and another character's once someone picked it under People.
        val names = settings.wakeNames(people)
        LaunchedEffect(names, settings.onlyKnownVoices, settings.micSensitivity, settings.interrupt, people, busy, speaking) {
            // Give the speech recogniser or text-to-speech a moment to let go of the audio.
            delay(if (busy) 250 else 600)
            // Each character's name is taught on its own; a name nobody taught yet is matched
            // against everything taught, since a voice still sounds like itself.
            val all = people.map { p -> p.copy(print = names.flatMap { p.printsFor(it) }) }.filter { it.voice.isNotEmpty() || it.print.isNotEmpty() }
            fun knownFor(name: String) = people.map { it.forWake(name) }.filter { it.voice.isNotEmpty() || it.print.isNotEmpty() }
                .takeIf { k -> k.any { it.print.isNotEmpty() } } ?: all
            val known = all
            // How the people here say each name, from teaching their voices.
            val taught = names.map { n -> taughtFor(people, n) }
            var token: Int? = null
            var deciding = false
            // Talking over the answer works for voices it knows, so its own voice can't cut itself off.
            val withPrints = known.filter { it.print.isNotEmpty() }
            var checking = false
            val easy = settings.interrupt == Interrupt.Easy
            val need = if (easy) TalkOver.EASY_MARGIN else TalkOver.MARGIN
            val talked: (ShortArray) -> Unit = { sound ->
                // A thinking sound playing doesn't count: only the answer itself can be talked over.
                if (!checking && assistant.answering) {
                    checking = true
                    launch {
                        val print = services.prints.of(sound)
                        val best = print?.let { p -> withPrints.maxByOrNull { VoiceId.margin(p, it) } }
                        val margin = if (print != null && best != null) VoiceId.margin(print, best) else -1f
                        val person = best?.takeIf { margin >= need }
                        if (person != null && assistant.answering) {
                            services.trace("wake", "Someone talked over the answer; voiceprint matched someone taught.", localOnly = true)
                            wake.stop(token)
                            assistant.talkOver(person, sound)
                        } else if (print != null) {
                            services.trace("wake", "Sound over the answer, not clearly a known voice (%.2f, needs %.2f).".format(margin, need), localOnly = true)
                        }
                        checking = false
                    }
                }
            }
            val onTalk = talked.takeIf { speaking && settings.interrupt != Interrupt.Off && withPrints.isNotEmpty() }
            // A hit that ended undecided arms a lower bar for a moment, so "Nova? Nova!" gets through.
            var armedUntil = 0L
            token = wake.start(
                names,
                onHeard = { hit ->
                    if (!deciding) {
                        deciding = true
                        launch {
                            val began = SystemClock.elapsedRealtime()
                            val judged = judgeHit(assistant.roomMediaPlaying(), services, settings, hit, names, knownFor(hit.name), taught, armed = SystemClock.elapsedRealtime() < armedUntil)
                            val verdict = judged.verdict
                            val who = judged.who
                            val character = judged.character
                            val prior = judged.prior
                            if (verdict.undecided) armedUntil = SystemClock.elapsedRealtime() + ARMED_MS
                            services.trace(
                                "wake",
                                // Not through format(): what Whisper heard and the notes can hold a "%".
                                "\"${character.label}\" ${if (hit.spotted) "spotted" else "found as taught"} (prior ${"%.2f".format(prior)}): ${verdict.line()}, judge ${judged.share} % in ${SystemClock.elapsedRealtime() - began} ms. ${who.log}",
                                localOnly = false,
                            )
                            // Over its own voice, only someone it knows can cut in, so TARS saying
                            // "TARS" doesn't interrupt himself.
                            val selfHeard = speaking && known.isNotEmpty() && who.person == null
                            if (verdict.wake && selfHeard) services.trace("wake", "Ignored a wake while talking: no known voice.", localOnly = false)
                            // The other character answers people it recognises; with nobody taught it can't tell, so anyone.
                            val stranger = who.person == null && known.isNotEmpty() && (settings.onlyKnownVoices || character != settings.persona)
                            if (verdict.wake && stranger && !selfHeard) services.trace("wake", "Ignored \"${character.label}\": the voice isn't someone it knows.", localOnly = false)
                            // Another character answers those who picked it under People.
                            val notTheirs = !selfHeard && !stranger && who.person != null && !settings.answers(character, who.person)
                            if (verdict.wake && notTheirs) services.trace("wake", "Ignored \"${character.label}\": ${who.person?.name} hasn't picked ${character.label} under People.", localOnly = false)
                            if (verdict.wake && !selfHeard && !stranger && !notTheirs) {
                                wake.stop(token)
                                assistant.wake(who.person, character, hit.sound)
                            }
                            deciding = false
                        }
                    }
                },
                onError = {},
                sensitivity = settings.micSensitivity,
                echo = speaking,
                onTalk = onTalk,
                talkEasy = easy,
                taught = taught,
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

/** What the wake judge made of one hit, with who it heard and the room's prior. */
private class Judged(val verdict: WakeJudge.Verdict, val judge: WakeJudge, val who: Speaker, val character: app.homeydash.agent.Persona, val prior: Double) {
    /** The score as a share of what waking needs, for the meter. */
    val share: Int get() = WakeJudge.share(verdict, judge)
}

/** How the people who taught their voice say [name], for the listener; null when nobody did. */
private fun taughtFor(people: List<KnownPerson>, name: String): app.homeydash.assistant.Taught? {
    val each = people.map { it.saidFor(name) }.filter { it.size >= 2 }
    if (each.isEmpty()) return null
    // Each person's own repeats set their bar; the most forgiving one is used for all.
    val bar = each.mapNotNull { app.homeydash.agent.SaidName.bar(it) }.maxOrNull() ?: return null
    return app.homeydash.assistant.Taught(each.flatten(), bar)
}

/**
 * The wake judge on one hit: the room's prior from Homey, then the witnesses in cost order
 * (the spotter, how close it came to the name as taught, the voiceprint, past false wakes and,
 * only while still unsure, Whisper on the Mac), until the evidence crosses a line either way.
 */
private suspend fun judgeHit(
    mediaPlaying: Boolean,
    services: AssistantServices,
    settings: AgentSettings,
    hit: app.homeydash.assistant.WakeHit,
    names: List<String>,
    heardBy: List<KnownPerson>,
    taught: List<app.homeydash.assistant.Taught?>,
    armed: Boolean,
): Judged {
    val name = hit.name
    val print = hit.sound?.let { services.prints.of(it) }
    val who = whoSpoke(services, name, heardBy, null, print)
    val character = settings.characterNamed(name)
    val facts = services.memory.data.value.facts
    val prior = WakeJudge.prior(
        mediaPlaying = mediaPlaying,
        nobodyHome = false,
        faceKnown = services.faces.current()?.person != null,
        hourShare = Memory.wakeHourShare(facts, java.time.LocalTime.now().hour),
    )
    val falseWakes = Memory.falseWakes(facts)
    val t = taught.getOrNull(names.indexOf(name))
    val witnesses = listOf(
        WakeJudge.Witness { if (hit.spotted) WakeJudge.spotter(hit.greeting) else null },
        WakeJudge.Witness {
            when {
                hit.said != null -> WakeJudge.said(hit.said, hit.bar)
                t != null && hit.sound != null -> app.homeydash.agent.SaidName.find(t.shapes, app.homeydash.agent.SaidName.heard(hit.sound))?.let { WakeJudge.said(it.distance, t.bar) }
                else -> null
            }
        },
        WakeJudge.Witness { WakeJudge.voice(who.margin, who.person?.name) },
        WakeJudge.Witness {
            val sound = hit.sound
            if (sound == null || falseWakes.isEmpty()) null else WakeJudge.fingerprint(SoundPrint.closest(SoundPrint.of(sound), falseWakes))
        },
        WakeJudge.Witness {
            val sound = hit.sound ?: return@Witness null
            if (settings.hearers().isEmpty()) return@Witness null
            val text = runCatching {
                kotlinx.coroutines.runBlocking { services.transcriber.transcribe(settings, VoiceFx.writeWav(Pcm(16000, sound)), null, names.joinToString(", ")).text }
            }.getOrNull() ?: return@Witness null
            WakeJudge.whisper(text, names)
        },
    )
    val judge = WakeJudge(alpha = MicSensitivity.alpha(settings.micSensitivity))
    val verdict = withContext(Dispatchers.Default) { judge.judge(prior, witnesses, armed) }
    return Judged(verdict, judge, who, character, prior)
}

/** Who said the wake name, how far over the closest known voice's bar the voiceprint was (null without voiceprints), and why. */
private class Speaker(val person: KnownPerson?, val log: String, val margin: Float? = null)

/** How long a hit that ended undecided keeps the wake bar lowered for a repeat. */
private const val ARMED_MS = 4_000L

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
        if (person != null) return Speaker(person, "Voiceprint matched someone taught (%.2f over their bar).".format(best), best)
        return Speaker(null, "Voiceprint %.2f below the closest person's bar.".format(-best), best)
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
    val why = if (print == null) "no voiceprint (model not ready or too little sound)" else "nobody has voiceprints yet"
    return Speaker(null, "Voice not recognised: $why.")
}

/** How far over someone's bar a wake must be before the tablet learns from it. */
private const val LEARN_MARGIN = 0.1f

/** Settings → Assistant → Voice activation. */
@Composable
fun WakeSettings(services: AssistantServices, settings: AgentSettings, update: ((AgentSettings) -> AgentSettings) -> Unit) {
    val g = GlassTheme.colors
    val context = LocalContext.current
    val models by services.voiceModels.state.collectAsState()
    val people by services.faces.people.collectAsState()
    val others = settings.wakeNames(people).drop(1)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) update { it.copy(wake = true) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Wake by name", style = MaterialTheme.typography.labelLarge, color = g.fg)
            Text(
                "Say \"Hey ${settings.wakeName.trim().ifEmpty { "Nova" }}\" and the assistant starts listening, like \"Hey Siri\". " +
                    others.joinToString("") { "\"Hey $it\" wakes the character someone picked under People, for voices it knows. " } +
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
    Text(
        when {
            WakeName.clean(settings.wakeName).isEmpty() -> "Give the assistant a name."
            else -> "Use a name of two syllables or more that isn't said much at home. Any Swedish or English question works after it. " +
                "Teaching your voice under People also teaches it how you say the name, so it is found even when the spotter misses it."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = g.muted,
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
                // "Ja?" in the voice, once it's made; the hum until then.
                val swedish = settings.answers != app.homeydash.agent.Language.English
                val options = app.homeydash.agent.ThinkingSounds.answers(settings.persona, swedish)
                if (sound == WakeSound.Voice) services.output.prepareSounds(options, swedish, settings.voice)
                services.playWakeSound(sound, if (sound == WakeSound.Voice) services.output.prepared(options.first(), swedish, settings.voice) else null)
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
        "Higher hears the name from across the room but accepts more wakes by mistake, for example from the TV: " +
            "it sets both the microphone gain and how sure the wake judge must be (about 1 false wake in 200 spotted names at the lowest, 1 in 20 at the highest). " +
            "It listens through the tablet's voice input, which mixes its microphones where the tablet supports it, set for a wide room.",
        style = MaterialTheme.typography.bodyMedium,
        color = g.muted,
    )
    ListenTest(services, settings)
}

/** One thing said during the listening test, and what each part of the wake made of it. */
private class TestLine(val words: String, val spotted: String?, val said: String?, val share: Int, val verdict: String, val wake: Boolean)

/**
 * Settings → Voice activation → Test listening: shows live how loud the microphone hears the
 * room and when the voice gate counts it as speech, and for each thing said what was heard
 * (Whisper, when a computer hears), whether the spotter caught a name, how close it came to
 * the name as taught, and the judge's score as a share of what waking needs. Each line also
 * goes to the logs, so the sweet spot can be found from them.
 */
@Composable
private fun ListenTest(services: AssistantServices, settings: AgentSettings) {
    val g = GlassTheme.colors
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (open) "Stop the test" else "Test listening",
            style = MaterialTheme.typography.labelLarge,
            color = g.fg,
            modifier = Modifier.pressable({ open = !open }).glass(PillShape).padding(horizontal = 18.dp, vertical = 10.dp),
        )
        Text(
            if (open) "Say the name, other things, and let the TV play. Nothing wakes while testing." else "Meters for sound, the name and the wake judge, with the words it heard.",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
            modifier = Modifier.weight(1f),
        )
    }
    if (!open || !micGranted(context)) return
    val people by services.faces.people.collectAsState()
    var level by remember { mutableStateOf(0f) }
    var speaking by remember { mutableStateOf(false) }
    val lines = remember { mutableStateListOf<TestLine>() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val names = settings.wakeNames(people)
    DisposableEffect(names, people, settings.micSensitivity) {
        val wake = services.wake
        // The dashboard's listener lets go of the microphone while this runs.
        wake.enrolling = true
        val taught = names.map { taughtFor(people, it) }
        val hits = ArrayList<app.homeydash.assistant.WakeHit>()
        val token = wake.start(
            names,
            onHeard = { hits += it },
            onError = { lines.add(0, TestLine(it, null, null, 0, "", false)) },
            sensitivity = settings.micSensitivity,
            taught = taught,
            onSpeech = { sound ->
                val caught = hits.toList()
                hits.clear()
                scope.launch {
                    // The name it heard, or else the one this came closest to as taught, so the
                    // judge's score shows even when nothing caught it.
                    val hit = caught.lastOrNull() ?: run {
                        val shape = withContext(Dispatchers.Default) { app.homeydash.agent.SaidName.heard(sound) }
                        val best = names.indices.mapNotNull { i -> taught[i]?.let { t -> app.homeydash.agent.SaidName.find(t.shapes, shape)?.let { Triple(i, it.distance, t.bar) } } }
                            .minByOrNull { it.second / it.third }
                        app.homeydash.assistant.WakeHit(names[best?.first ?: 0], false, sound, 0L, spotted = false, said = best?.second, bar = best?.third)
                    }
                    val heardBy = people.map { it.forWake(hit.name) }.filter { it.voice.isNotEmpty() || it.print.isNotEmpty() }
                    val judged = judgeHit(false, services, settings, hit, names, heardBy, taught, armed = false)
                    val words = if (settings.hearers().isEmpty()) {
                        "(no computer set to hear words)"
                    } else {
                        runCatching { services.transcriber.transcribe(settings, VoiceFx.writeWav(Pcm(16000, sound)), null, names.joinToString(", ")).text }
                            .getOrElse { "(couldn't hear words: ${it.message})" }.ifBlank { "(no words)" }
                    }
                    // How close it came to the name as taught, as a share of the bar (under 100 % counts).
                    val share = if (hit.said != null && hit.bar != null) {
                        hit.said / hit.bar
                    } else {
                        taught.getOrNull(names.indexOf(hit.name))?.let { t ->
                            val heard = withContext(Dispatchers.Default) { app.homeydash.agent.SaidName.heard(hit.sound ?: sound) }
                            app.homeydash.agent.SaidName.find(t.shapes, heard)?.let { it.distance / t.bar }
                        }
                    }
                    val saidText = share?.let { "%.0f %% of the bar".format(it * 100) }
                    val line = TestLine(words, caught.lastOrNull()?.let { (if (it.spotted) "spotted " else "found as taught ") + it.name }, saidText, judged.share, judged.verdict.line(), judged.verdict.wake)
                    lines.add(0, line)
                    while (lines.size > 8) lines.removeAt(lines.lastIndex)
                    services.trace(
                        "wake-test",
                        "Heard \"$words\" (${"%.1f".format(sound.size / 16000f)} s): ${line.spotted ?: "no name caught"}${saidText?.let { ", said like taught $it" } ?: ""}, judge ${line.share} % (${judged.verdict.line()}). ${judged.who.log}",
                        localOnly = false,
                    )
                }
            },
            onLevel = { l, sp -> level = l; speaking = sp },
        )
        onDispose {
            wake.stop(token)
            wake.enrolling = false
        }
    }
    SoundMeter(level, speaking)
    if (lines.isEmpty()) Text("Waiting for speech…", style = MaterialTheme.typography.bodyMedium, color = g.muted)
    lines.forEach { line ->
        Column(Modifier.fillMaxWidth().glass(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("\"${line.words}\"", style = MaterialTheme.typography.labelLarge, color = g.fg)
            Text(
                listOfNotNull(line.spotted ?: "no name caught", line.said?.let { "said like taught: $it" }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = g.muted,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Judge", style = MaterialTheme.typography.bodyMedium, color = g.muted)
                LinearProgressIndicator(
                    progress = { line.share / 100f },
                    modifier = Modifier.weight(1f),
                    color = if (line.wake) g.on else g.muted,
                    trackColor = g.glassLine,
                )
                Text("${line.share} %" + if (line.wake) " · wakes" else "", style = MaterialTheme.typography.labelLarge, color = g.fg)
            }
            if (line.verdict.isNotEmpty()) Text(line.verdict, style = MaterialTheme.typography.bodySmall, color = g.muted)
        }
    }
}

/** The microphone's level, lit when the voice gate counts it as speech. */
@Composable
private fun SoundMeter(level: Float, speaking: Boolean) {
    val g = GlassTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Sound", style = MaterialTheme.typography.bodyMedium, color = g.muted)
        // Loudness on a log scale: a quiet room near the left, a raised voice near the right.
        val shown = ((20 * kotlin.math.log10(level.coerceAtLeast(1e-4f)) + 60f) / 60f).coerceIn(0f, 1f)
        LinearProgressIndicator(progress = { shown }, modifier = Modifier.weight(1f), color = if (speaking) g.on else g.muted, trackColor = g.glassLine)
        Text(if (speaking) "speech" else "quiet", style = MaterialTheme.typography.labelLarge, color = if (speaking) g.fg else g.muted)
    }
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
    val samples = remember { mutableStateListOf<FloatArray>() }
    val prints = remember { mutableStateListOf<FloatArray>() }
    val shapes = remember { mutableStateListOf<app.homeydash.agent.SaidName.Shape>() }
    var level by remember { mutableStateOf(0f) }
    var speaking by remember { mutableStateOf(false) }
    val jobs = remember { mutableListOf<kotlinx.coroutines.Job>() }
    var noSound by remember { mutableStateOf(0) }
    var failed by remember { mutableStateOf<String?>(null) }
    val printModel by services.prints.state.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var granted by remember { mutableStateOf(micGranted(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    // The voiceprint model is what learns the voice; the spotter that hears the name comes with the app.
    val ready = printModel == app.homeydash.assistant.VoicePrints.State.Ready && WakeName.clean(wakeName).isNotEmpty()
    val phrase = "Hey ${wakeName.trim()}"

    LaunchedEffect(Unit) {
        if (!granted) permission.launch(Manifest.permission.RECORD_AUDIO)
        services.prints.download()
    }
    if (ready && granted) {
        DisposableEffect(Unit) {
            val wake = services.wake
            wake.enrolling = true
            // Each thing said is one phrase, found by the voice gate alone: teaching doesn't need
            // the spotter to recognise the name first, so it works however the name is said.
            val token = wake.start(
                listOf(wakeName),
                onHeard = {},
                onError = { error = it },
                sensitivity = settings.micSensitivity,
                onSpeech = { sound ->
                    val seconds = sound.size / 16000f
                    when {
                        samples.size >= VOICE_SAMPLES -> Unit
                        seconds < 0.4f -> error = "That was very short; say the whole \"$phrase\"."
                        seconds > 3.5f -> error = "That was long; say just \"$phrase\", then pause."
                        else -> {
                            error = null
                            failed = null
                            samples += FloatArray(0)
                            jobs += scope.launch {
                                services.prints.of(sound)?.let { prints += it } ?: run { noSound++ }
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { app.homeydash.agent.SaidName.of(sound) }?.let { shapes += it }
                            }
                        }
                    }
                },
                onLevel = { l, sp -> level = l; speaking = sp },
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
                services.faces.addPrints(person.id, wakeName, prints.toList())
                // And how they say the name, so it is found even when the spotter misses it.
                services.faces.addSaid(person.id, wakeName, shapes.toList())
                val why = when {
                    printModel !is app.homeydash.assistant.VoicePrints.State.Ready -> "the voiceprint model isn't on the tablet ($printModel)"
                    noSound == samples.size -> "the sound of the name wasn't caught"
                    else -> services.prints.lastError ?: "unknown"
                }
                services.trace(
                    "wake",
                    "Taught \"Hey $wakeName\": ${samples.size} phrases, ${prints.size} voiceprints, ${shapes.size} ways of saying it" +
                        (app.homeydash.agent.SaidName.bar(shapes.toList())?.let { " (bar %.3f)".format(it) } ?: "") + if (prints.isEmpty()) " (none made: $why)." else ".",
                    localOnly = false,
                )
                if (prints.isEmpty()) {
                    // Nothing new was learned for this name; say why instead of closing as if it worked.
                    failed = "Couldn't make voiceprints for \"Hey $wakeName\": $why. Try again."
                    samples.clear()
                    shapes.clear()
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
                printModel is app.homeydash.assistant.VoicePrints.State.Downloading -> "Getting the voiceprint model first (about 26 MB, once)…"
                printModel is app.homeydash.assistant.VoicePrints.State.Failed -> (printModel as app.homeydash.assistant.VoicePrints.State.Failed).message
                WakeName.clean(wakeName).isEmpty() -> "Give the assistant a name under Voice activation first."
                else -> "${person.name}, say \"$phrase\" ${VOICE_WAYS[samples.size.coerceAtMost(VOICE_WAYS.lastIndex)]} (${samples.size + 1} of $VOICE_SAMPLES)."
            },
            style = MaterialTheme.typography.titleMedium,
            color = g.fg,
        )
        LinearProgressIndicator(progress = { samples.size / VOICE_SAMPLES.toFloat() }, modifier = Modifier.fillMaxWidth(), color = g.on, trackColor = g.glassLine)
        SoundMeter(level, speaking)
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
