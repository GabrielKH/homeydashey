package app.homeydash.assistant

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.homeydash.agent.TurnEnd
import app.homeydash.agent.Agent
import app.homeydash.agent.Access
import app.homeydash.agent.AgentListener
import app.homeydash.agent.AgentLog
import app.homeydash.agent.AgentReply
import app.homeydash.agent.AgentStep
import app.homeydash.agent.GitHubLog
import app.homeydash.agent.AgentSettings
import app.homeydash.agent.AgentTools
import app.homeydash.agent.Announce
import app.homeydash.agent.AnnounceCard
import app.homeydash.agent.AskContext
import app.homeydash.agent.BrainMode
import app.homeydash.agent.ChatClient
import app.homeydash.agent.Connection
import app.homeydash.agent.DashboardControl
import app.homeydash.agent.FollowUp
import app.homeydash.agent.HomeView
import app.homeydash.agent.KnownPerson
import app.homeydash.agent.PendingAction
import app.homeydash.agent.Role
import app.homeydash.agent.Secret
import app.homeydash.agent.StoreHomeControl
import app.homeydash.agent.VoiceTrack
import app.homeydash.agent.WakeName
import app.homeydash.agent.WebSearch
import app.homeydash.homey.Device
import app.homeydash.homey.HomeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File

/** Parts of the assistant that live as long as the app: settings, voice, and the conversation. */
class AssistantServices(context: Context, http: OkHttpClient, scope: CoroutineScope) {
    val appContext: Context = context.applicationContext
    val settingsStore = AssistantSettingsStore(appContext, scope)
    val settings: StateFlow<AgentSettings> = settingsStore.settings
    val chat = ChatClient(http)
    /** The owner's routines ("God morgon") and standing instructions for the assistant. */
    val routines = app.homeydash.agent.RoutineBook(File(appContext.filesDir, "assistant"))
    /** What the assistant remembers from earlier conversations, per person and for the house; kept out of the prompt. */
    val memory = app.homeydash.agent.MemoryBook(File(appContext.filesDir, "assistant"))
    val agent = Agent(chat, routines = { routines.data.value }, memory = memory)
    val input = SpeechInput(appContext)
    /** TARS's voice, downloaded to the tablet when TARS is chosen. */
    val tars = TarsVoice(appContext, http, scope)
    val output = SpeechOutput(appContext, http, tars, scope) { settings.value }
    val server = AnswerServer(appContext)
    val faces = FaceService(appContext)
    val web = WebSearch(http)
    val voiceModels = VoiceModels(appContext, http, scope)
    val wake = WakeListener(voiceModels, scope)
    /** Voiceprints, for telling who said the wake name. */
    val prints = VoicePrints(appContext, http, scope)
    /** Records questions for Whisper or OpenAI, when they hear instead of Google. */
    val recorder = SpeechRecorder()
    val transcriber = app.homeydash.agent.Transcriber(http)
    /** Called when the assistant starts listening or ringing, so a dimmed screen lights up. */
    var onActive: (() -> Unit)? = null
    /** Whether the assistant is listening, thinking, speaking or waiting for a follow-up; the screen doesn't dim meanwhile. */
    var busy: () -> Boolean = { false }
    /** The recipes and the one being cooked, so the assistant can follow along. */
    var kitchen: app.homeydash.data.Kitchen? = null

    /** What the assistant heard, thought and did, kept until it is sent (Settings → Assistant → Send logs). */
    val log = AgentLog(File(appContext.filesDir, "assistant/log.jsonl"))
    private val github = GitHubLog(http)
    private val logScope = scope
    private var upload: Job? = null
    /** When the log was last sent, and what went wrong if it wasn't; for the settings. */
    var logStatus by mutableStateOf<String?>(null)
        private set

    init {
        scope.launch { routines.load() }
        scope.launch { memory.load() }
    }

    fun trace(kind: String, text: String, localOnly: Boolean) {
        // Kept in order; a line appended to a small file.
        log.add(kind, text, localOnly)
        val s = settings.value
        if (!s.sendLogs || s.logToken.isBlank()) return
        // Sent a little after the conversation goes quiet, as one comment.
        upload?.cancel()
        upload = logScope.launch {
            delay(LOG_QUIET_MS)
            sendLog()
        }
    }

    private val wakeSounds = mutableMapOf<app.homeydash.agent.WakeSound, ShortArray>()

    /** Plays the sound for hearing the wake name and returns how long it lasts, in milliseconds. */
    fun playWakeSound(sound: app.homeydash.agent.WakeSound): Long {
        val pcm = synchronized(wakeSounds) { wakeSounds.getOrPut(sound) { app.homeydash.agent.WakeSounds.render(sound) } }
        if (pcm.isEmpty()) return 0
        val track = runCatching {
            android.media.AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(app.homeydash.agent.WakeSounds.RATE)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                .build()
                .also { it.write(pcm, 0, pcm.size); it.play() }
        }.getOrNull() ?: return 0
        val ms = pcm.size * 1000L / app.homeydash.agent.WakeSounds.RATE
        logScope.launch { delay(ms + 300); runCatching { track.release() } }
        return ms
    }

    private val sending = Mutex()

    /** Sends what hasn't been sent yet; returns what happened, in words. */
    suspend fun sendLog(): String {
        val s = settings.value
        val result = when {
            s.logToken.isBlank() -> "Add a GitHub token first."
            // One send at a time, and a new line cancelling the wait mustn't cut a send short: the
            // comment would be posted but the lines kept as unsent, so they went to GitHub twice.
            else -> sending.withLock {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
                    val entries = log.unsent()
                    if (entries.isEmpty()) return@withContext "Nothing new to send."
                    try {
                        github.post(s.logToken, s.logRepo.trim().ifBlank { AgentLog.DEFAULT_REPO }, log.markdown(entries, android.os.Build.MODEL))
                        log.sent(entries.size)
                        "Sent ${entries.size} lines at " + java.time.LocalTime.now().withNano(0).toString().take(5) + "."
                    } catch (e: java.io.IOException) {
                        e.message ?: "Couldn't send the logs."
                    }
                }
            }
        }
        logStatus = result
        return result
    }

    private companion object {
        const val LOG_QUIET_MS = 90_000L
    }
}

/** One line in the assistant's conversation. */
data class Line(
    val fromUser: Boolean,
    val text: String,
    val error: Boolean = false,
    val brain: String? = null,
    val local: Boolean? = null,
    /** Speech recognition lacks a language: the line offers a way to Android's speech settings. */
    val speechSettings: Boolean = false,
    /** What the assistant did for this answer and what Homey said, shown small under it. */
    val actions: List<String> = emptyList(),
    /** How long hearing, thinking and speaking took, like "Heard 0.4 s · AI first word 0.9 s, 42 tok/s". */
    val speed: String? = null,
    /** The same, as the steps shown while it worked. */
    val steps: List<AgentStep> = emptyList(),
)

/** What [Scene] the assistant shows with an answer. */
enum class SceneKind { Sun, Night, Clouds, Rain, Snow, Storm, Fog }

data class Scene(val kind: SceneKind)

/** A kitchen timer started by voice or by hand. */
data class KitchenTimer(val id: Long, val label: String?, val endsAt: Long, val seconds: Int)

enum class AssistantState { Idle, Listening, Thinking, Speaking }

/** What the assistant needs from the dashboard on screen; built by the dashboard. */
interface DashboardBridge : DashboardControl {
    /** Visible, non-hidden devices for the assistant (the dashboard's own hidden list applied). */
    fun hidden(): Set<String>

    /** The weather card's forecast in a few lines, for the assistant's note; null before it has loaded. */
    fun weatherNow(): String? = null

    /** The picture for the weather [tomorrow] or now. */
    fun weatherScene(tomorrow: Boolean): Scene? = null
}

/**
 * The assistant on the dashboard: listening, asking the brain, speaking the answer on
 * the tablet or a Homey speaker, kitchen timers, and confirmations.
 */
@Stable
class Assistant(
    private val services: AssistantServices,
    private val store: HomeyStore,
    private val scope: CoroutineScope,
) {
    lateinit var bridge: DashboardBridge

    var open by mutableStateOf(false)
    var state by mutableStateOf(AssistantState.Idle)
        private set
    var level by mutableFloatStateOf(0f)
        private set
    var low by mutableFloatStateOf(0f)
        private set
    var mid by mutableFloatStateOf(0f)
        private set
    var high by mutableFloatStateOf(0f)
        private set
    var partial by mutableStateOf("")
        private set
    /** A spoken conversation is going on: after each answer it listens again for a while. */
    var conversing by mutableStateOf(false)
        private set
    /** The last question was spoken, so the answer is followed by listening. */
    private var lastByVoice by mutableStateOf(false)

    /** Talking by voice right now: the face fills the screen. */
    val voiceActive: Boolean
        get() = waking || state == AssistantState.Listening || conversing || (lastByVoice && state != AssistantState.Idle)
    /** The wake name was just heard: the face shows while the wake sound plays. */
    private var waking by mutableStateOf(false)
    /**
     * The face is a small window instead of the whole screen, so the page it opened (a recipe,
     * a room) can be used while it keeps talking. It stays small, also for the next question
     * while cooking, until it is tapped open or the dashboard is back on the overview.
     */
    var compact by mutableStateOf(false)
    /** A small moving picture that goes with the answer, like rain while it tells the weather. */
    var scene by mutableStateOf<Scene?>(null)
    val lines = mutableStateListOf<Line>()
    /** What the assistant is doing for the current question, step by step, as it happens. */
    val activity = mutableStateListOf<AgentStep>()
    var pending by mutableStateOf<PendingAction?>(null)
        private set
    val timers = mutableStateListOf<KitchenTimer>()

    private var job: Job? = null
    /** Who said the wake name last, and when; a known voice outranks the camera for a minute. */
    private var voice: Seen? = null
    /** Until when questions are heard offline because Google's speech service didn't answer. */
    private var offlineUntil = 0L
    /** Until when Google hears questions because Whisper and OpenAI didn't answer. */
    private var hearersDownUntil = 0L
    /** A question is being recorded for Whisper or OpenAI (the recorder holds the microphone). */
    private var recording = false
    /** Until when questions are heard as Swedish, because the last one was (elapsed realtime). */
    private var swedishUntil = 0L
    private var toldOffline = false
    /** The offline model is hearing a question (it holds the microphone). */
    private var dictating = false
    /** When the conversation stops listening for a next question (elapsed realtime). */
    private var conversationUntil = 0L
    /**
     * The settings as the character who is talking: the one woken by its name, for the rest of
     * that conversation, or else the one the person at the tablet picked under People.
     */
    private val settings: AgentSettings get() {
        val s = services.settings.value
        val people = services.faces.people.value
        val w = woken?.takeIf { conversing || state != AssistantState.Idle || SystemClock.elapsedRealtime() - it.at < WOKEN_MS }
        if (w != null) return s.talkingAs(w.character, people.firstOrNull { it.id == w.personId })
        val here = services.faces.current()?.person?.takeIf { s.access(Secret.Faces) != Access.Off }?.let { p -> people.firstOrNull { it.id == p.id } }
        return s.talkingAs(s.characterOf(here), here)
    }

    /** Which character was woken by its name, and by whom when the voice was known. */
    private class Woken(val character: app.homeydash.agent.Persona, val personId: String?, val at: Long)
    private var woken: Woken? = null

    /** When the last spoken question ended (elapsed realtime) and how long writing it down took, for its [Timing]. */
    private var speechEnd: Long? = null
    private var hearMs: Long? = null
    private var pauseMs: Long? = null

    /** How long the question being answered takes, part by part (elapsed realtime). */
    private class Timing(val speechEnd: Long?, val hearMs: Long?, val pauseMs: Long?, val askedAt: Long) {
        @Volatile var sentenceAt = 0L
        @Volatile var soundAt = 0L
        @Volatile var filler = false

        fun sound(isFiller: Boolean = false) {
            if (soundAt == 0L) {
                soundAt = SystemClock.elapsedRealtime()
                filler = isFiller
            }
        }
    }
    private var timing: Timing? = null
    private val home = StoreHomeControl(store) { store.snapshot.value.deviceCards }

    init {
        services.busy = { voiceActive || state != AssistantState.Idle }
        services.agent.listener = object : AgentListener {
            override fun step(step: AgentStep) {
                val i = activity.indexOfFirst { it.id == step.id }
                if (i >= 0) activity[i] = step else activity += step
            }

            override fun trace(kind: String, text: String, localOnly: Boolean) = services.trace(kind, text, localOnly)
        }
        // Kept warm while nobody talks: a warm-up started by the wake word ran on the Mac at the
        // same time as Whisper (7 s of speech to text, 20 s to the first word after a quiet spell).
        scope.launch {
            while (true) {
                delay(KEEP_WARM_MS)
                if (state == AssistantState.Idle && !voiceActive) {
                    // A conversation that has gone quiet is squeezed into a few facts first, then the rules are read ahead again.
                    runCatching { services.agent.digest(settings) }.onFailure { if (it is CancellationException) throw it }
                    warmBrain(followUp = false)
                }
            }
        }
        // A low tablet complains every 20 minutes in the day, in the character's own voice.
        scope.launch {
            while (true) {
                delay(BATTERY_CHECK_MS)
                runCatching { nagAboutBattery() }
            }
        }
    }

    private var lastNagAt: Long? = null
    private var lastNag: String? = null

    private suspend fun nagAboutBattery() {
        // In the owner's own character and voice, whoever was last at the tablet.
        val base = services.settings.value
        val owner = services.faces.people.value.firstOrNull { it.role == Role.Owner }
        val s = base.talkingAs(base.characterOf(owner), owner)
        val bm = services.appContext.getSystemService(android.os.BatteryManager::class.java) ?: return
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        val charging = bm.isCharging
        // Charged up again: the next low battery starts its own reminders straight away.
        if (charging || (level ?: 100) >= app.homeydash.agent.BatteryNag.BELOW) lastNagAt = null
        val since = lastNagAt?.let { SystemClock.elapsedRealtime() - it }
        if (!app.homeydash.agent.BatteryNag.due(s.batteryReminders, level, charging, java.time.LocalTime.now(), since)) return
        // Never talks over someone or an answer; tries again next minute.
        if (state != AssistantState.Idle || voiceActive) return
        val swedish = when (s.answers) {
            app.homeydash.agent.Language.Swedish -> true
            app.homeydash.agent.Language.English -> false
            app.homeydash.agent.Language.Auto -> lines.lastOrNull { it.fromUser }?.let { app.homeydash.agent.Confirmation.looksSwedish(it.text) } ?: true
        }
        val text = app.homeydash.agent.BatteryNag.line(s.persona, swedish, level ?: return, lastNag)
        lastNagAt = SystemClock.elapsedRealtime()
        lastNag = text
        services.trace("battery", "Reminded to charge at $level %.", false)
        services.onActive?.invoke()
        open = true
        lines += Line(false, text)
        say(text, s)
        state = AssistantState.Idle
    }

    /** The steps worth showing: what it does in the home, and the AI only while it thinks. */
    fun steps(): List<AgentStep> = activity.filter { !it.thinking || it.state == app.homeydash.agent.StepState.Running }

    /** Speakers that can say answers: they have a play-URL or say-text Flow card. */
    fun speakers(): List<Pair<Device, AnnounceCard>> {
        val snap = store.snapshot.value
        val hidden = bridge.hidden()
        return snap.devices.values
            .filter { !it.hidden && it.id !in hidden }
            .mapNotNull { d -> Announce.find(snap.deviceCards[d.id].orEmpty())?.let { d to it } }
            .sortedBy { it.first.name.lowercase() }
    }

    fun toggleListening() {
        if (state == AssistantState.Listening) stopListening() else listen()
    }

    /** Listens for a question; [heard] is sound already heard of it (someone talked over the answer). */
    fun listen(heard: ShortArray? = null) {
        if (!open && !conversing) freshScreen()
        services.output.stop()
        // The wake name may have been listened for while it talked; the question gets the microphone.
        if (!services.wake.enrolling) services.wake.stop()
        job?.cancel()
        services.onActive?.invoke()
        warmUp()
        showAsChosen()
        waking = false
        open = true
        partial = ""
        scene = null
        state = AssistantState.Listening
        val local = settings.mode == BrainMode.Local
        // Once someone speaks Swedish, the next questions are heard as Swedish, in this conversation
        // and the next ones for a while: language detection on a short sentence often guesses
        // English. The offline recogniser only knows English, so it isn't used for Swedish.
        val language = if (settings.language == app.homeydash.agent.Language.Auto && SystemClock.elapsedRealtime() < swedishUntil) {
            app.homeydash.agent.Language.Swedish
        } else {
            settings.language
        }
        val swedish = language == app.homeydash.agent.Language.Swedish
        if (settings.hearers().isNotEmpty() && SystemClock.elapsedRealtime() >= hearersDownUntil) {
            listenRecorded(language, heard)
            return
        }
        if (!swedish && SystemClock.elapsedRealtime() < offlineUntil && listenOffline()) return
        services.input.start(language, preferOnDevice = local, listener = object : SpeechInput.Listener {
            override fun onLevel(level: Float) = meter(floatArrayOf(level, level, level * 0.8f, level * 0.6f))
            override fun onPartial(text: String) { partial = text }
            override fun onResult(text: String) {
                speechEnd = SystemClock.elapsedRealtime()
                hearMs = null
                pauseMs = null
                heard(text)
            }
            override fun onError(message: String) = failed(message)
            override fun onUnavailable(message: String) {
                // Google's speech service is down or unreachable: hear this (and the next few) offline.
                offlineUntil = SystemClock.elapsedRealtime() + OFFLINE_FOR_MS
                if (swedish || !listenOffline()) failed(message)
            }
        })
    }

    /**
     * Records the question and has Whisper on the user's computer and/or OpenAI write it down,
     * instead of Android's speech recognition. When neither answers, Google hears the next
     * questions for a couple of minutes.
     */
    private fun listenRecorded(language: app.homeydash.agent.Language, heard: ShortArray? = null) {
        recording = true
        val s = settings
        val code = when (language) {
            app.homeydash.agent.Language.Swedish -> "sv"
            app.homeydash.agent.Language.English -> "en"
            else -> null
        }
        val hint = hint(s)
        // What Whisper made of the question up to the latest pause, while recording goes on.
        var early: Early? = null
        services.recorder.record(
            gain = app.homeydash.agent.MicSensitivity.gain(s.micSensitivity),
            start = heard,
            onLevel = { l -> meter(floatArrayOf(l, l, l * 0.8f, l * 0.6f)) },
            onPause = { wav, pause, held ->
                // A pause: answer now if what was said reads as finished, else wait for the rest,
                // like a person would through an "öhh" or "and…".
                val noticed = SystemClock.elapsedRealtime()
                scope.launch {
                    val text = try {
                        services.transcriber.transcribe(s, wav, code, hint).text
                    } catch (e: java.io.IOException) {
                        return@launch
                    }
                    if (!recording || state != AssistantState.Listening || services.recorder.pausedAt != pause) return@launch
                    early = Early(pause, text, noticed)
                    TurnEnd.clean(text).takeIf { it.isNotEmpty() }?.let { partial = it }
                    if (TurnEnd.complete(text, s.wakeName, held)) {
                        recording = false
                        services.recorder.stop()
                        timeHeard(noticed, PAUSE_MS)
                        heard(text)
                    } else {
                        services.trace("listen", "Waiting for the rest: \"$text\"" + if (held) " (a held sound before the pause)" else "", true)
                    }
                }
            },
            onDone = done@{ wav ->
                recording = false
                if (wav == null) {
                    heard("")
                    return@done
                }
                // Nothing more was said after the last pause: what was heard then is the question.
                early?.let { (pause, text, noticed) ->
                    if (services.recorder.pausedAt == pause) {
                        // Heard at the pause; the wait since then for more is part of the time.
                        timeHeard(noticed, PAUSE_MS)
                        heard(text)
                        return@done
                    }
                }
                partial = "…"
                job?.cancel()
                val noticed = SystemClock.elapsedRealtime()
                job = scope.launch {
                    val text = try {
                        services.transcriber.transcribe(s, wav, code, hint).text
                    } catch (e: java.io.IOException) {
                        if (state != AssistantState.Listening) return@launch
                        hearersDownUntil = SystemClock.elapsedRealtime() + OFFLINE_FOR_MS
                        failed("Couldn't make out the question (${e.message}). Google's speech recognition hears the next few.")
                        return@launch
                    }
                    if (state == AssistantState.Listening) {
                        timeHeard(noticed, PAUSE_MS)
                        heard(text)
                    }
                }
            },
            onError = { message ->
                recording = false
                failed(message)
            },
        )
    }

    /** What Whisper made of the question at a pause, and when the pause was noticed. */
    private data class Early(val pause: Int, val text: String, val noticed: Long)

    /** The question ended at [noticed] (after [pause] ms of quiet) and has just been written down. */
    private fun timeHeard(noticed: Long, pause: Long?) {
        speechEnd = noticed
        hearMs = SystemClock.elapsedRealtime() - noticed
        pauseMs = pause
    }

    /** Names worth expecting in a question: the wake name, rooms and devices. */
    private fun hint(s: AgentSettings): String {
        val v = runCatching { view(s, true) }.getOrNull() ?: return FILLER_HINT + s.wakeName
        // The hesitation sounds up front make Whisper write them down, so a pause after
        // one can be told from the end of the question.
        return FILLER_HINT + (listOf(s.wakeName) + v.rooms.values + v.devices.map { it.name }).distinct().joinToString(", ").take(HINT_CHARS)
    }

    /** Listens with the offline model (English only) when it's downloaded; false when it isn't. */
    private fun listenOffline(): Boolean {
        if (services.voiceModels.state.value != VoiceModels.State.Ready) return false
        if (!toldOffline) {
            toldOffline = true
            lines += Line(false, "Google's speech service isn't answering, so I'm listening offline for now, in English only.")
        }
        dictating = true
        services.wake.dictate(
            onText = { text -> dictating = false; heard(text) },
            onError = { message -> dictating = false; failed(message) },
        )
        return true
    }

    private fun heard(said: String) {
        // "Öh" and "ehm" are for knowing when the question ends, not for the AI.
        val text = TurnEnd.clean(said)
        meter(FloatArray(4))
        partial = ""
        when {
            text.isBlank() -> listenAgainOrRest()
            // Just the name ("Hey Nova") during a conversation: still here, keep listening.
            WakeName.heard(text, settings.wakeName) -> { keepConversation(); listenAgainOrRest() }
            FollowUp.ends(text, settings.wakeName) -> endConversation()
            else -> {
                swedishUntil = if (app.homeydash.agent.Confirmation.looksSwedish(text)) SystemClock.elapsedRealtime() + SWEDISH_FOR_MS else 0L
                send(text, byVoice = true)
            }
        }
    }

    private fun failed(message: String) {
        meter(FloatArray(4))
        partial = ""
        if (message.isNotBlank()) {
            lines += Line(false, message, error = true, speechSettings = message == SpeechInput.MISSING_LANGUAGE)
            endConversation()
        } else {
            // Nothing was said: keep listening while the conversation lasts.
            listenAgainOrRest()
        }
    }

    /**
     * Loads a local model and has it read the rules and the home while the question is still
     * being said, so only the question itself is left to read when it arrives.
     */
    private fun warmUp() {
        // TARS's voice is loaded while the question is said, so he speaks without a pause.
        if (settings.speak) services.output.warm(settings.voice)
        warmBrain(conversing)
    }

    /** Reads the rules and devices ahead on the local AI; the agent skips it when nothing changed. */
    private fun warmBrain(followUp: Boolean) {
        val local = settings.local?.takeIf { it.usable && settings.mode != BrainMode.Internet } ?: return
        scope.launch {
            services.chat.warm(local)
            runCatching {
                val (s, person) = forWhoIsHere(settings)
                services.agent.warm(s, view = { l -> view(s, l) }, tools = { v -> tools(v) }, context = askContext(s, person, followUp))
            }
        }
    }

    /** Listens again if the conversation is still open, otherwise goes quiet. */
    private fun listenAgainOrRest() {
        state = AssistantState.Idle
        if (!conversing || SystemClock.elapsedRealtime() >= conversationUntil) {
            endConversation()
            return
        }
        job?.cancel()
        job = scope.launch {
            // Lets the recogniser release the microphone before it starts again.
            delay(RELISTEN_GAP_MS)
            if (conversing && state == AssistantState.Idle) listen()
        }
    }

    /** Keeps listening for the next question, for as long as the owner chose. */
    private fun keepConversation() {
        val follow = settings.followUp
        conversing = true
        conversationUntil = when (follow) {
            FollowUp.Always -> Long.MAX_VALUE
            // A yes/no question gets a short window even when follow-up is off.
            FollowUp.Off -> SystemClock.elapsedRealtime() + FollowUp.Short.ms
            else -> SystemClock.elapsedRealtime() + follow.ms
        }
    }

    private fun endConversation() {
        conversing = false
        conversationUntil = 0L
        if (state == AssistantState.Listening) services.input.stop()
        if (dictating) {
            dictating = false
            services.wake.stop()
        }
        if (recording) {
            recording = false
            services.recorder.stop()
        }
        state = AssistantState.Idle
    }

    /**
     * The wake name was heard; [person] is whoever the voice belongs to, if known, and
     * [character] the one whose name was said, which answers for this conversation.
     */
    fun wake(person: KnownPerson?, character: app.homeydash.agent.Persona = services.settings.value.characterOf(person)) {
        voice = Seen(person, SystemClock.elapsedRealtime())
        woken = Woken(character, person?.id, SystemClock.elapsedRealtime())
        rememberWoke(person, character)
        // Woken again after it was stopped: a new talk, so the last one's answers aren't still on screen.
        if (!conversing && state == AssistantState.Idle) freshScreen()
        // Said while it was thinking or talking: like a person, it stops and listens.
        if (state == AssistantState.Thinking || state == AssistantState.Speaking) {
            services.trace("wake", "Interrupted while ${state.name.lowercase()}.", false)
            services.output.stop()
            job?.cancel()
            pending = null
            scene = null
            state = AssistantState.Idle
        }
        if (settings.wakeSound == app.homeydash.agent.WakeSound.Off) {
            listen()
            return
        }
        // The face first, then the "mm?" as it appears, then the microphone, so the question
        // doesn't start with the sound.
        services.onActive?.invoke()
        showAsChosen()
        waking = true
        scope.launch {
            delay(WAKE_SOUND_AFTER_MS)
            if (!waking) return@launch
            val ms = services.playWakeSound(settings.wakeSound)
            delay(ms + 60)
            if (waking && state == AssistantState.Idle) listen() else waking = false
        }
    }

    /** Counts who wakes which character, for the memory, once the house has more than one in use. */
    private fun rememberWoke(person: KnownPerson?, character: app.homeydash.agent.Persona) {
        val s = services.settings.value
        if (person == null || s.access(Secret.Memory) == Access.Off || s.wakeNames(services.faces.people.value).size < 2) return
        scope.launch {
            runCatching { services.memory.learn(listOf(app.homeydash.agent.Memory.woke(app.homeydash.agent.Asker(person.id, person.name), character, java.time.ZonedDateTime.now()))) }
        }
    }

    /**
     * Someone it knows started talking while it spoke: like a person, it stops mid-sentence and
     * listens, keeping what they already said ([heard]) as the start of the question.
     */
    fun talkOver(person: KnownPerson, heard: ShortArray) {
        if (state != AssistantState.Speaking || !answering) return
        answering = false
        voice = Seen(person, SystemClock.elapsedRealtime())
        services.trace("wake", "Stopped talking: ${person.name} talked over the answer.", true)
        services.output.stop()
        job?.cancel()
        pending = null
        scene = null
        state = AssistantState.Idle
        keepConversation()
        listen(heard)
    }

    /** Clears what the last talk left on screen; the assistant still remembers it. */
    private fun freshScreen() {
        lines.clear()
        activity.clear()
        pending = null
        scene = null
    }

    /** Small from the start when the island or side window was chosen; never forces full screen. */
    private fun showAsChosen() {
        if (!voiceActive && settings.view != app.homeydash.agent.AssistantView.Full) compact = true
    }

    fun stopListening() {
        services.input.stop()
        if (dictating) {
            dictating = false
            services.wake.stop()
        }
        if (recording) {
            recording = false
            services.recorder.stop()
        }
        meter(FloatArray(4))
        conversing = false
        conversationUntil = 0L
        if (state == AssistantState.Listening) state = AssistantState.Idle
    }

    /** Stops whatever the assistant is doing, and ends the conversation. */
    fun stop() {
        waking = false
        stopListening()
        job?.cancel()
        services.output.stop()
        state = AssistantState.Idle
    }

    fun send(text: String, byVoice: Boolean = false) {
        if (text.isBlank()) return
        // Heard with the microphone left open after an answer: may be people talking, not a question.
        val followUp = byVoice && conversing
        lastByVoice = byVoice
        // A typed question ends a spoken conversation.
        if (!byVoice) {
            conversing = false
            conversationUntil = 0L
        }
        open = true
        lines += Line(true, text.trim())
        activity.clear()
        // Rain on screen while it says it rains.
        if (WEATHER_WORDS.containsMatchIn(text)) scene = bridge.weatherScene(TOMORROW.containsMatchIn(text))
        job?.cancel()
        answering = false
        val clock = Timing(speechEnd.takeIf { byVoice }, hearMs.takeIf { byVoice }, pauseMs.takeIf { byVoice }, SystemClock.elapsedRealtime())
        speechEnd = null
        hearMs = null
        pauseMs = null
        timing = clock
        job = scope.launch {
            state = AssistantState.Thinking
            // Talking like a person: the answer is spoken while the AI still writes it, and a
            // short "hmm" fills the silence when it takes a moment.
            val live = if (byVoice && talksLive(settings)) Channel<String>(Channel.UNLIMITED) else null
            val swedish = when (settings.answers) {
                app.homeydash.agent.Language.Swedish -> true
                app.homeydash.agent.Language.English -> false
                app.homeydash.agent.Language.Auto -> app.homeydash.agent.Confirmation.looksSwedish(text)
            }
            val begun = java.util.concurrent.atomic.AtomicBoolean(false)
            val speaking = live?.let { pieces ->
                launch {
                    services.output.speakLive(pieces, swedish, settings.voice, onStart = { clock.sound(isFiller = !begun.get()); state = AssistantState.Speaking }, onFrame = ::meter)
                }
            }
            // Not for follow-ups: those may turn out to be people talking to each other.
            val hmm = live?.takeIf { !followUp }?.let { pieces ->
                launch {
                    delay(app.homeydash.agent.ThinkingSounds.AFTER_MS)
                    // Only one made ahead of time: making it now would come too late.
                    val ready = app.homeydash.agent.ThinkingSounds.all(settings.persona, swedish).filter { services.output.hasSound(it, swedish, settings.voice) }
                    val sound = app.homeydash.agent.ThinkingSounds.pick(ready, lastHmm)
                    if (!begun.get() && sound != null) {
                        lastHmm = sound
                        services.trace("voice", "Said \"$sound\" while the answer took a moment.", false)
                        pieces.trySend(sound)
                    }
                }
            }
            val reply = try {
                val (s, person) = forWhoIsHere(settings)
                services.agent.ask(
                    text.trim(), s,
                    view = { local -> view(s, local) },
                    tools = { v -> tools(v) },
                    context = askContext(s, person, followUp),
                    speech = live?.let { pieces ->
                        { sentence: String ->
                            if (clock.sentenceAt == 0L) clock.sentenceAt = SystemClock.elapsedRealtime()
                            begun.set(true); answering = true; pieces.trySend(sentence); Unit
                        }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AgentReply("Something went wrong: ${e.message}", error = true)
            }
            hmm?.cancel()
            live?.close()
            // Whatever was handed over finishes playing before anything else is said.
            speaking?.join()
            show(reply)
            // The "hmm" sounds for next time, made in this voice once the answer is done, so making
            // them never holds up a question on the computer's voice server.
            if (live != null) services.output.prepareSounds(app.homeydash.agent.ThinkingSounds.all(settings.persona, swedish), swedish, settings.voice)
        }
    }

    /**
     * The answer itself is being said, not a thinking sound: only then can someone talk over it,
     * so the echo of a "hmm" never cancels the question it's thinking about.
     */
    @Volatile var answering = false
        private set

    /** The last thinking sound, so the next one is different. */
    private var lastHmm: String? = null

    /** Answers are spoken while they're written: on the tablet only, since a speaker gets one clip. */
    private fun talksLive(s: AgentSettings) = s.speak && s.liveSpeech && s.speakerId == null

    /** Runs [routine] now, as if its phrase was said (Settings → Assistant → Routines → Run). */
    fun runRoutine(routine: app.homeydash.agent.Routine) {
        lastByVoice = false
        conversing = false
        conversationUntil = 0L
        open = true
        lines += Line(true, routine.name)
        activity.clear()
        job?.cancel()
        job = scope.launch {
            state = AssistantState.Thinking
            val reply = try {
                val (s, person) = forWhoIsHere(settings)
                services.agent.runRoutine(
                    routine, routine.name, s,
                    view = { local -> view(s, local) },
                    tools = { v -> tools(v) },
                    context = askContext(s, person, followUp = false),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AgentReply("Something went wrong: ${e.message}", error = true)
            }
            show(reply)
        }
    }

    fun confirm(yes: Boolean) {
        job?.cancel()
        activity.clear()
        job = scope.launch {
            state = AssistantState.Thinking
            show(services.agent.confirm(yes))
        }
    }

    fun clear() {
        stop()
        lines.clear()
        services.agent.reset()
        pending = null
    }

    /** What a brain would get, for Settings → Assistant → What the assistant sees. */
    fun preview(local: Boolean): Pair<HomeView, AgentTools> {
        val v = view(settings, local)
        return v to tools(v)
    }

    private fun tools(v: HomeView) = AgentTools(
        v, home, bridge,
        web = services.web.lookup(v.settings.webSearch()),
        recipes = services.kitchen?.let { k -> app.homeydash.agent.RecipeTools(k.book, k.cooking) { bridge.openPage("recipes") } },
        fridge = services.kitchen?.let { k -> app.homeydash.agent.FridgeTools(k.fridge) { bridge.openPage("fridge") } },
        routines = app.homeydash.agent.RoutineTools(services.routines),
        memory = app.homeydash.agent.MemoryTools(services.memory),
    )

    private fun view(s: AgentSettings, local: Boolean): HomeView {
        val snap = store.snapshot.value
        return HomeView.build(snap.zones, snap.devices, snap.moods, bridge.hidden(), s, local)
    }

    private fun askContext(s: AgentSettings, person: String?, followUp: Boolean) = AskContext(
        speaker = speakerName(s), person = person, followUp = followUp,
        kitchen = services.kitchen?.cooking?.describe(), fridge = services.kitchen?.fridge?.describe(),
        weather = bridge.weatherNow(),
        tabletBattery = tabletBattery(),
    )

    /** The tablet's battery, like "14 %, not charging". */
    private fun tabletBattery(): String? {
        val bm = services.appContext.getSystemService(android.os.BatteryManager::class.java) ?: return null
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 } ?: return null
        return "$level %, " + if (bm.isCharging) "charging" else "not charging"
    }

    private fun speakerName(s: AgentSettings): String? = s.speakerId?.let { store.snapshot.value.devices[it]?.name }

    /**
     * The settings for whoever is at the tablet, known by the voice that said the wake name
     * or else by face: a known person gets their role, a stranger gets guest access if the
     * owner chose that.
     */
    private fun forWhoIsHere(settings: AgentSettings): Pair<AgentSettings, String?> {
        // Who has which phone is told by the people the tablet knows, whoever is asking.
        val s = settings.copy(people = services.faces.people.value.associate { it.id to it.name })
        if (s.access(Secret.Faces) == Access.Off) return s to null
        val byVoice = voice?.takeIf { it.person != null && SystemClock.elapsedRealtime() - it.at < 60_000 }
        val seen = byVoice ?: services.faces.current() ?: return s to null
        val person = seen.person
        return when {
            person != null -> s.forRole(person.role).copy(asker = app.homeydash.agent.Asker(person.id, person.name)) to person.name
            s.strangersAreGuests -> s.forRole(Role.Guest) to null
            else -> s to null
        }
    }

    private suspend fun show(reply: AgentReply) {
        if (reply.ignored) {
            // Not meant for the assistant: drop what was heard and keep listening quietly.
            if (lines.lastOrNull()?.fromUser == true) lines.removeAt(lines.lastIndex)
            listenAgainOrRest()
            return
        }
        pending = services.agent.pending
        val brain: Connection? = reply.brain
        lines += Line(false, reply.text, error = reply.error, brain = brain?.provider?.label, local = brain?.local, actions = reply.actions, steps = steps())
        val clock = timing?.also { timing = null }
        if (settings.speak && !reply.error && !reply.spoken) {
            if (clock != null && clock.sentenceAt == 0L) clock.sentenceAt = SystemClock.elapsedRealtime()
            say(reply.text) { clock?.sound() }
        }
        clock?.let { timed(it, reply) }
        // A routine's last steps, like starting the radio, wait until the answer has been said.
        reply.then?.let { then ->
            val done = try { then() } catch (e: CancellationException) { throw e } catch (e: Exception) { listOf("That failed: ${e.message}") }
            val i = lines.lastIndex
            if (i >= 0 && !lines[i].fromUser) lines[i] = lines[i].copy(actions = lines[i].actions + done, steps = steps())
        }
        state = AssistantState.Idle
        // A spoken question, or a yes/no question, is followed by listening for the next one.
        val yesNo = pending?.tapOnly == false
        if (yesNo || (lastByVoice && settings.followUp != FollowUp.Off)) {
            keepConversation()
            listen()
        } else {
            conversing = false
        }
    }

    /**
     * Shows under the answer how long each part took, and puts it in the logs: writing down
     * what was said, the AI's first word and speed, and when the voice started.
     */
    private fun timed(clock: Timing, reply: AgentReply) {
        fun since(at: Long) = at.takeIf { it != 0L }?.let { it - clock.askedAt }
        val speed = app.homeydash.agent.Speed(
            hearMs = clock.hearMs,
            pauseMs = clock.pauseMs,
            brain = reply.brain?.let { "${it.provider.label} ${it.model}" },
            rounds = reply.rounds,
            sentenceMs = since(clock.sentenceAt),
            voiceMs = since(clock.soundAt),
            filler = clock.filler,
            replyMs = clock.speechEnd?.let { end -> clock.soundAt.takeIf { it != 0L }?.let { it - end } },
        )
        val short = speed.short()
        if (short.isBlank()) return
        services.trace("timing", speed.long(), false)
        val i = lines.indexOfLast { !it.fromUser }
        if (i >= 0) lines[i] = lines[i].copy(speed = short)
    }

    /** Says [text] on the chosen speaker, or on the tablet when there is none or it fails; [onStart] when it starts. */
    private suspend fun say(text: String, s: AgentSettings = settings, onStart: () -> Unit = {}) {
        state = AssistantState.Speaking
        answering = true
        val speaker = s.speakerId?.let { id -> speakers().firstOrNull { it.first.id == id } }
        if (speaker != null && sayOn(speaker.second, text, s, onStart)) return
        services.output.speak(text, s.answers, s.voice, ::meter, onStart)
    }

    /** The voice's level and its low, middle and high parts, for the face. */
    private fun meter(frame: FloatArray) {
        level = frame[0]
        low = frame[1]
        mid = frame[2]
        high = frame[3]
    }

    private suspend fun sayOn(card: AnnounceCard, text: String, s: AgentSettings, onStart: () -> Unit = {}): Boolean = try {
        var spoken: Spoken? = null
        val content = when (card.kind) {
            AnnounceCard.Kind.Text -> text
            AnnounceCard.Kind.Url -> {
                val file = File(services.appContext.cacheDir, "answer.wav")
                spoken = services.output.render(text, s.answers, s.voice, file)
                spoken?.let { services.server.publish(it.file) }
            }
        }
        if (content == null) false else {
            store.runAction(card.card, card.args(content, s.speakerVolume))
            // The speaker starts a little after Homey has it, so this is about when it is heard.
            onStart()
            // Move along with the speaker; it starts after a short buffer.
            spoken?.let { follow(it.track, startAfterMs = SPEAKER_DELAY_MS) }
            true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** Plays [track] on the face by the clock, for answers that play on another speaker. */
    private suspend fun follow(track: VoiceTrack, startAfterMs: Long) {
        try {
            delay(startAfterMs)
            val start = SystemClock.elapsedRealtime()
            while (true) {
                val at = SystemClock.elapsedRealtime() - start
                if (at > track.durationMs) break
                meter(track.at(at))
                delay(16)
            }
        } finally {
            meter(FloatArray(4))
        }
    }

    // Kitchen timers

    fun startTimer(seconds: Int, label: String?) {
        val timer = KitchenTimer(System.nanoTime(), label, System.currentTimeMillis() + seconds * 1000L, seconds)
        timers += timer
        scope.launch {
            delay(seconds * 1000L)
            if (timers.remove(timer)) ring(timer)
        }
    }

    fun cancelTimer(label: String?): Boolean {
        val t = timers.lastOrNull { label == null || it.label.equals(label, ignoreCase = true) } ?: return false
        return timers.remove(t)
    }

    fun describeTimers(): List<String> = timers.map { t ->
        val left = ((t.endsAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        "${t.label ?: "Timer"}: ${left / 60} min ${left % 60} s left"
    }

    private suspend fun ring(timer: KitchenTimer) {
        services.onActive?.invoke()
        open = true
        val swedish = when (settings.answers) {
            app.homeydash.agent.Language.Swedish -> true
            app.homeydash.agent.Language.English -> false
            app.homeydash.agent.Language.Auto -> lines.lastOrNull { it.fromUser }?.let { app.homeydash.agent.Confirmation.looksSwedish(it.text) } ?: true
        }
        val name = timer.label?.replaceFirstChar(Char::uppercase)
        val text = when {
            swedish -> name?.let { "$it är klar." } ?: "Timern är klar."
            else -> name?.let { "$it is done." } ?: "The timer is done."
        }
        lines += Line(false, text)
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 90)
            repeat(3) { tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 400); delay(700) }
            tone.release()
        }
        say(text)
        state = AssistantState.Idle
    }

    private companion object {
        const val SPEAKER_DELAY_MS = 800L
        /** How often to check whether the local AI needs warming while it waits (see Agent.REWARM_AFTER_MS). */
        const val KEEP_WARM_MS = 2 * 60 * 1000L
        const val BATTERY_CHECK_MS = 60 * 1000L
        const val RELISTEN_GAP_MS = 300L
        /** Long enough for the face to fade in before the wake sound plays. */
        const val WAKE_SOUND_AFTER_MS = 200L
        /** How long a woken character stays after the wake when no conversation follows. */
        const val WOKEN_MS = 60_000L
        const val OFFLINE_FOR_MS = 2 * 60 * 1000L
        const val SWEDISH_FOR_MS = 30 * 60 * 1000L
        const val HINT_CHARS = 400
        const val FILLER_HINT = "Öh, ehm, hmm… "
        /** The quiet [app.homeydash.agent.Endpointer] waits for before it counts a pause. */
        const val PAUSE_MS = 700L
        val WEATHER_WORDS = Regex("väder|vädr|regn|snö|soligt|solen|prognos|temperatur|grader ute|weather|rain|snow|sunny|forecast", RegexOption.IGNORE_CASE)
        val TOMORROW = Regex("tomorrow|imorgon|i morgon", RegexOption.IGNORE_CASE)
    }
}
