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
import app.homeydash.agent.Agent
import app.homeydash.agent.Access
import app.homeydash.agent.AgentReply
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
    val agent = Agent(chat, routines = { routines.data.value })
    val input = SpeechInput(appContext)
    val output = SpeechOutput(appContext)
    val server = AnswerServer(appContext)
    val faces = FaceService(appContext)
    val web = WebSearch(http)
    val voiceModels = VoiceModels(appContext, http, scope)
    val wake = WakeListener(voiceModels, scope)
    /** Records questions for Whisper or OpenAI, when they hear instead of Google. */
    val recorder = SpeechRecorder()
    val transcriber = app.homeydash.agent.Transcriber(http)
    /** Called when the assistant starts listening or ringing, so a dimmed screen lights up. */
    var onActive: (() -> Unit)? = null
    /** The recipes and the one being cooked, so the assistant can follow along. */
    var kitchen: app.homeydash.data.Kitchen? = null

    init {
        scope.launch { routines.load() }
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
        get() = state == AssistantState.Listening || conversing || (lastByVoice && state != AssistantState.Idle)
    /**
     * The face is a small window instead of the whole screen, so the page it opened (a recipe,
     * a room) can be used while it keeps talking. It stays small, also for the next question
     * while cooking, until it is tapped open or the dashboard is back on the overview.
     */
    var compact by mutableStateOf(false)
    /** A small moving picture that goes with the answer, like rain while it tells the weather. */
    var scene by mutableStateOf<Scene?>(null)
    val lines = mutableStateListOf<Line>()
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
    private val settings: AgentSettings get() = services.settings.value
    private val home = StoreHomeControl(store) { store.snapshot.value.deviceCards }

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

    fun listen() {
        services.output.stop()
        job?.cancel()
        services.onActive?.invoke()
        warmUp()
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
            listenRecorded(language)
            return
        }
        if (!swedish && SystemClock.elapsedRealtime() < offlineUntil && listenOffline()) return
        services.input.start(language, preferOnDevice = local, listener = object : SpeechInput.Listener {
            override fun onLevel(level: Float) = meter(floatArrayOf(level, level, level * 0.8f, level * 0.6f))
            override fun onPartial(text: String) { partial = text }
            override fun onResult(text: String) = heard(text)
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
    private fun listenRecorded(language: app.homeydash.agent.Language) {
        recording = true
        val s = settings
        services.recorder.record(
            gain = app.homeydash.agent.MicSensitivity.gain(s.micSensitivity),
            onLevel = { l -> meter(floatArrayOf(l, l, l * 0.8f, l * 0.6f)) },
            onDone = done@{ wav ->
                recording = false
                if (wav == null) {
                    heard("")
                    return@done
                }
                partial = "…"
                val code = when (language) {
                    app.homeydash.agent.Language.Swedish -> "sv"
                    app.homeydash.agent.Language.English -> "en"
                    else -> null
                }
                job?.cancel()
                job = scope.launch {
                    val text = try {
                        services.transcriber.transcribe(s, wav, code, hint(s)).text
                    } catch (e: java.io.IOException) {
                        if (state != AssistantState.Listening) return@launch
                        hearersDownUntil = SystemClock.elapsedRealtime() + OFFLINE_FOR_MS
                        failed("Couldn't make out the question (${e.message}). Google's speech recognition hears the next few.")
                        return@launch
                    }
                    if (state == AssistantState.Listening) heard(text)
                }
            },
            onError = { message ->
                recording = false
                failed(message)
            },
        )
    }

    /** Names worth expecting in a question: the wake name, rooms and devices. */
    private fun hint(s: AgentSettings): String {
        val v = runCatching { view(s, true) }.getOrNull() ?: return s.wakeName
        return (listOf(s.wakeName) + v.rooms.values + v.devices.map { it.name }).distinct().joinToString(", ").take(HINT_CHARS)
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

    private fun heard(text: String) {
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
        val local = settings.local?.takeIf { it.usable && settings.mode != BrainMode.Internet } ?: return
        val followUp = conversing
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

    /** The wake name was heard; [person] is whoever the voice belongs to, if known. */
    fun wake(person: KnownPerson?) {
        voice = Seen(person, SystemClock.elapsedRealtime())
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            scope.launch { delay(300); tone.release() }
        }
        listen()
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
        // Rain on screen while it says it rains.
        if (WEATHER_WORDS.containsMatchIn(text)) scene = bridge.weatherScene(TOMORROW.containsMatchIn(text))
        job?.cancel()
        job = scope.launch {
            state = AssistantState.Thinking
            val reply = try {
                val (s, person) = forWhoIsHere(settings)
                services.agent.ask(
                    text.trim(), s,
                    view = { local -> view(s, local) },
                    tools = { v -> tools(v) },
                    context = askContext(s, person, followUp),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AgentReply("Something went wrong: ${e.message}", error = true)
            }
            show(reply)
        }
    }

    /** Runs [routine] now, as if its phrase was said (Settings → Assistant → Routines → Run). */
    fun runRoutine(routine: app.homeydash.agent.Routine) {
        lastByVoice = false
        conversing = false
        conversationUntil = 0L
        open = true
        lines += Line(true, routine.name)
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
    )

    private fun view(s: AgentSettings, local: Boolean): HomeView {
        val snap = store.snapshot.value
        return HomeView.build(snap.zones, snap.devices, snap.moods, bridge.hidden(), s, local)
    }

    private fun askContext(s: AgentSettings, person: String?, followUp: Boolean) = AskContext(
        speaker = speakerName(s), person = person, followUp = followUp,
        kitchen = services.kitchen?.cooking?.describe(), fridge = services.kitchen?.fridge?.describe(),
        weather = bridge.weatherNow(),
    )

    private fun speakerName(s: AgentSettings): String? = s.speakerId?.let { store.snapshot.value.devices[it]?.name }

    /**
     * The settings for whoever is at the tablet, known by the voice that said the wake name
     * or else by face: a known person gets their role, a stranger gets guest access if the
     * owner chose that.
     */
    private fun forWhoIsHere(s: AgentSettings): Pair<AgentSettings, String?> {
        if (s.access(Secret.Faces) == Access.Off) return s to null
        val byVoice = voice?.takeIf { it.person != null && SystemClock.elapsedRealtime() - it.at < 60_000 }
        val seen = byVoice ?: services.faces.current() ?: return s to null
        val person = seen.person
        return when {
            person != null -> s.forRole(person.role) to person.name
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
        lines += Line(false, reply.text, error = reply.error, brain = brain?.provider?.label, local = brain?.local, actions = reply.actions)
        if (settings.speak && !reply.error) say(reply.text)
        // A routine's last steps, like starting the radio, wait until the answer has been said.
        reply.then?.let { then ->
            val done = try { then() } catch (e: CancellationException) { throw e } catch (e: Exception) { listOf("That failed: ${e.message}") }
            val i = lines.lastIndex
            if (i >= 0 && !lines[i].fromUser) lines[i] = lines[i].copy(actions = lines[i].actions + done)
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

    /** Says [text] on the chosen speaker, or on the tablet when there is none or it fails. */
    private suspend fun say(text: String) {
        state = AssistantState.Speaking
        val s = settings
        val speaker = s.speakerId?.let { id -> speakers().firstOrNull { it.first.id == id } }
        if (speaker != null && sayOn(speaker.second, text, s)) return
        services.output.speak(text, s.language, s.voice, ::meter)
    }

    /** The voice's level and its low, middle and high parts, for the face. */
    private fun meter(frame: FloatArray) {
        level = frame[0]
        low = frame[1]
        mid = frame[2]
        high = frame[3]
    }

    private suspend fun sayOn(card: AnnounceCard, text: String, s: AgentSettings): Boolean = try {
        var spoken: Spoken? = null
        val content = when (card.kind) {
            AnnounceCard.Kind.Text -> text
            AnnounceCard.Kind.Url -> {
                val file = File(services.appContext.cacheDir, "answer.wav")
                spoken = services.output.render(text, s.language, s.voice, file)
                spoken?.let { services.server.publish(it.file) }
            }
        }
        if (content == null) false else {
            store.runAction(card.card, card.args(content, s.speakerVolume))
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
        val swedish = when (settings.language) {
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
        const val RELISTEN_GAP_MS = 300L
        const val OFFLINE_FOR_MS = 2 * 60 * 1000L
        const val SWEDISH_FOR_MS = 30 * 60 * 1000L
        const val HINT_CHARS = 400
        val WEATHER_WORDS = Regex("väder|vädr|regn|snö|soligt|solen|prognos|temperatur|grader ute|weather|rain|snow|sunny|forecast", RegexOption.IGNORE_CASE)
        val TOMORROW = Regex("tomorrow|imorgon|i morgon", RegexOption.IGNORE_CASE)
    }
}
