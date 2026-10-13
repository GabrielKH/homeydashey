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
import app.homeydash.agent.HomeView
import app.homeydash.agent.KnownPerson
import app.homeydash.agent.PendingAction
import app.homeydash.agent.Role
import app.homeydash.agent.Secret
import app.homeydash.agent.StoreHomeControl
import app.homeydash.agent.VoiceTrack
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
    val agent = Agent(chat)
    val input = SpeechInput(appContext)
    val output = SpeechOutput(appContext)
    val server = AnswerServer(appContext)
    val faces = FaceService(appContext)
    val web = WebSearch(http)
    val voiceModels = VoiceModels(appContext, http, scope)
    val wake = WakeListener(voiceModels, scope)
}

/** One line in the assistant's conversation. */
data class Line(val fromUser: Boolean, val text: String, val error: Boolean = false, val brain: String? = null, val local: Boolean? = null)

/** A kitchen timer started by voice or by hand. */
data class KitchenTimer(val id: Long, val label: String?, val endsAt: Long, val seconds: Int)

enum class AssistantState { Idle, Listening, Thinking, Speaking }

/** What the assistant needs from the dashboard on screen; built by the dashboard. */
interface DashboardBridge : DashboardControl {
    /** Visible, non-hidden devices for the assistant (the dashboard's own hidden list applied). */
    fun hidden(): Set<String>
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
    val lines = mutableStateListOf<Line>()
    var pending by mutableStateOf<PendingAction?>(null)
        private set
    val timers = mutableStateListOf<KitchenTimer>()

    private var job: Job? = null
    /** Who said the wake name last, and when; a known voice outranks the camera for a minute. */
    private var voice: Seen? = null
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
        open = true
        partial = ""
        state = AssistantState.Listening
        val local = settings.mode == BrainMode.Local
        services.input.start(settings.language, preferOnDevice = local, listener = object : SpeechInput.Listener {
            override fun onLevel(level: Float) = meter(floatArrayOf(level, level, level * 0.8f, level * 0.6f))
            override fun onPartial(text: String) { partial = text }
            override fun onResult(text: String) {
                level = 0f
                partial = ""
                if (text.isBlank()) state = AssistantState.Idle else send(text)
            }
            override fun onError(message: String) {
                level = 0f
                partial = ""
                state = AssistantState.Idle
                if (message.isNotBlank()) lines += Line(false, message, error = true)
            }
        })
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
        level = 0f
        if (state == AssistantState.Listening) state = AssistantState.Idle
    }

    /** Stops whatever the assistant is doing. */
    fun stop() {
        stopListening()
        job?.cancel()
        services.output.stop()
        state = AssistantState.Idle
    }

    fun send(text: String) {
        if (text.isBlank()) return
        open = true
        lines += Line(true, text.trim())
        job?.cancel()
        job = scope.launch {
            state = AssistantState.Thinking
            val reply = try {
                val (s, person) = forWhoIsHere(settings)
                services.agent.ask(
                    text.trim(), s,
                    view = { local -> view(s, local) },
                    tools = { v -> tools(v) },
                    context = AskContext(speaker = speakerName(s), person = person),
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

    private fun tools(v: HomeView) = AgentTools(v, home, bridge, web = services.web.lookup(v.settings.webSearch()))

    private fun view(s: AgentSettings, local: Boolean): HomeView {
        val snap = store.snapshot.value
        return HomeView.build(snap.zones, snap.devices, snap.moods, bridge.hidden(), s, local)
    }

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
        pending = services.agent.pending
        val brain: Connection? = reply.brain
        lines += Line(false, reply.text, error = reply.error, brain = brain?.provider?.label, local = brain?.local)
        if (settings.speak && !reply.error) say(reply.text)
        state = AssistantState.Idle
        // A yes/no question is answered by voice straight away.
        if (pending?.tapOnly == false && settings.speakerId == null) listen()
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
    }
}
