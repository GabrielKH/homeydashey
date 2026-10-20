package app.homeydash.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.homeydash.agent.MicSensitivity
import app.homeydash.agent.TalkOver
import app.homeydash.agent.WakeKeywords
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/** The offline voice models, downloaded once (about 50 MB) into the app's own storage. */
class VoiceModels(context: Context, http: OkHttpClient, private val scope: CoroutineScope) {
    sealed interface State {
        data object Missing : State
        data class Downloading(val progress: Float) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val dir = File(context.filesDir, "vosk")
    private val http = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
    private var job: Job? = null

    val speech: File get() = File(dir, SPEECH)
    val speaker: File get() = File(dir, SPEAKER)

    private val _state = MutableStateFlow<State>(if (installed()) State.Ready else State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Words the wake model knows, or null when it doesn't list them. */
    val vocabulary: Set<String>? by lazy {
        File(speech, "graph/words.txt").takeIf { it.exists() }
            ?.useLines { lines -> lines.map { it.substringBefore(' ') }.filter { it.isNotEmpty() }.toSet() }
    }

    private fun installed() = File(speech, DONE).exists() && File(speaker, DONE).exists()

    fun download() {
        if (job?.isActive == true || installed()) return
        job = scope.launch {
            _state.value = State.Downloading(0f)
            _state.value = try {
                withContext(Dispatchers.IO) {
                    fetch(SPEECH_URL, SPEECH, 0f, 0.75f)
                    fetch(SPEAKER_URL, SPEAKER, 0.75f, 0.25f)
                }
                State.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                State.Failed(e.message ?: "Download failed.")
            }
        }
    }

    private fun fetch(url: String, name: String, start: Float, share: Float) {
        val target = File(dir, name)
        if (File(target, DONE).exists()) return
        dir.mkdirs()
        target.deleteRecursively()
        val zip = File(dir, "$name.zip")
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Couldn't download the voice model (HTTP ${response.code}).")
            val body = response.body ?: throw IOException("Empty download.")
            val total = body.contentLength().takeIf { it > 0 }
            var read = 0L
            body.byteStream().use { input ->
                zip.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        if (total != null) _state.value = State.Downloading(start + share * read / total)
                    }
                }
            }
        }
        unzip(zip, target)
        zip.delete()
        File(target, DONE).writeText("ok")
    }

    /** Unpacks [zip] into [target], dropping the zip's single top folder. */
    private fun unzip(zip: File, target: File) {
        val root = target.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val entry = z.nextEntry ?: break
                val path = entry.name.substringAfter('/', "")
                if (path.isEmpty()) continue
                val out = File(root, path).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) continue
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { z.copyTo(it) }
                }
            }
        }
    }

    private companion object {
        const val DONE = ".installed"
        const val SPEECH = "speech-small-en-us-0.15"
        const val SPEAKER = "speaker-0.4"
        const val SPEECH_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        const val SPEAKER_URL = "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip"
    }
}

/** The wake name as the spotter heard it, with the sound of it for a voiceprint and a fingerprint. */
class WakeHit(
    /** Which of the names it was, as given to [WakeListener.start]. */
    val name: String,
    /** A greeting came first ("hey Nova"): two words are harder to hear by mistake than one. */
    val greeting: Boolean,
    /** The sound of the name with a little room either side, at least a second; null when too little was kept. */
    val sound: ShortArray?,
    /** Where in the listening (in samples) the name ended, for [WakeListener.afterName]. */
    val nameEnd: Long,
)

/**
 * Listens on the tablet's microphone for the assistant's name, entirely offline: Silero VAD
 * (sherpa-onnx) lets only speech through, and a keyword spotter (a small zipformer transducer
 * that knows nothing but the names, see [WakeKeywords]) says when a name was said, within a
 * couple of tenths of a second of it. Ordinary talk is never written down. The microphone is
 * one stream into a ring buffer, so what was said right after the name is still there for
 * the question ([afterName]) and the sound of the name itself for a voiceprint ([WakeHit.sound]).
 */
class WakeListener(context: Context, private val models: VoiceModels, private val scope: CoroutineScope) {
    private val assets = context.applicationContext.assets
    private var model: Model? = null
    private var speakerModel: SpeakerModel? = null
    private var spotter: KeywordSpotter? = null
    private var vad: Vad? = null
    private val tokens: Set<String> by lazy { WakeKeywords.tokensOf(assets.open("ear/tokens.txt").bufferedReader().readText()) }
    @Volatile private var generation = 0
    /** The thread reading the microphone; it closes its own recorder and recogniser when it ends. */
    private var reader: Thread? = null
    /** What the microphone last heard, kept when the reader stops, for [afterName]. */
    @Volatile private var lastHeard: Recent? = null
    @Volatile private var lastHit: Long = -1

    /** True while People is learning someone's voice; the dashboard leaves the mic alone. */
    var enrolling by mutableStateOf(false)

    /** While the assistant itself says one of the names, a hit is its own voice and is dropped. */
    @Volatile var muted = false

    /**
     * Starts listening for [names] (one per character in use). [onHeard] gets each spotted
     * name, on the main thread. Returns a token for [stop], so one user can't stop another's
     * listening. [sensitivity] (0–100) sets the microphone gain. With [onTalk] (while the
     * assistant talks), someone talking over it for a moment is handed over too, with the
     * last of what was heard.
     */
    fun start(
        names: List<String>,
        onHeard: (WakeHit) -> Unit,
        onError: (String) -> Unit,
        sensitivity: Int = MicSensitivity.DEFAULT,
        echo: Boolean = false,
        onTalk: ((sound: ShortArray) -> Unit)? = null,
        talkEasy: Boolean = false,
    ): Int {
        stop()
        val gen = generation
        scope.launch(Dispatchers.Main) {
            val loaded = withContext(Dispatchers.IO) { runCatching { loadEar() } }
            if (gen != generation) return@launch
            loaded.exceptionOrNull()?.let { onError("Couldn't load the wake-word spotter: ${it.message}"); return@launch }
            val kws = spotter ?: return@launch
            val keywords = WakeKeywords.keywords(names, tokens)
            if (keywords.isEmpty()) {
                onError("None of the names can be listened for.")
                return@launch
            }
            val stream = try {
                kws.createStream(WakeKeywords.stream(keywords))
            } catch (e: Exception) {
                onError("Couldn't listen for the wake name: ${e.message}")
                return@launch
            }
            val gain = MicSensitivity.gain(sensitivity)
            val main = Handler(Looper.getMainLooper())
            reader = thread(name = "wake-mic") {
                val talk: ((ShortArray) -> Unit)? = onTalk?.let { handler -> { sound -> main.post { if (gen == generation) handler(sound) } } }
                spot(kws, stream, names, gain, isLive = { gen == generation }, echo = echo, onTalk = talk, talkEasy = talkEasy) { hit, failure ->
                    main.post {
                        if (gen != generation) return@post
                        if (failure != null) onError(failure) else if (hit != null) onHeard(hit)
                    }
                }
            }
        }
        return gen
    }

    /**
     * Hears one question offline with the dictation model (Vosk, English only, downloaded
     * under Voice): the fallback when Google's speech service can't be reached. [onText] gets
     * what was said, or "" when nothing was said in time. Returns a token for [stop].
     */
    fun dictate(onText: (String) -> Unit, onError: (String) -> Unit): Int {
        stop()
        val gen = generation
        scope.launch(Dispatchers.Main) {
            val loaded = withContext(Dispatchers.IO) { runCatching { load() } }
            if (gen != generation) return@launch
            loaded.exceptionOrNull()?.let { onError("Couldn't load the offline speech model: ${it.message}"); return@launch }
            val rec = try {
                Recognizer(model, RATE.toFloat())
            } catch (e: Exception) {
                onError("Couldn't listen offline: ${e.message}")
                return@launch
            }
            val main = Handler(Looper.getMainLooper())
            main.postDelayed({ if (gen == generation) { stop(); onText("") } }, DICTATION_MS)
            reader = thread(name = "dictation-mic") {
                read(rec, 1f, isLive = { gen == generation }) { result, failure ->
                    main.post {
                        if (gen != generation) return@post
                        if (failure != null) {
                            stop()
                            onError(failure)
                            return@post
                        }
                        val text = runCatching { Json.parseToJsonElement(result.orEmpty()).jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                            .getOrNull().orEmpty().trim()
                        if (text.isNotEmpty()) {
                            stop()
                            onText(text)
                        }
                    }
                }
            }
        }
        return gen
    }

    /** Opens the microphone for the room: the voice-recognition input (beam-formed where there is one), or the call input with echo cancelling. */
    @SuppressLint("MissingPermission") // WakeWatch only starts with the microphone permission.
    private fun open(echo: Boolean): Pair<AudioRecord, android.media.audiofx.AcousticEchoCanceler?>? {
        val size = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE / 5 * 2)
        val source = if (echo) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val record = runCatching { AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size) }.getOrNull() ?: return null
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        // Far-field (-1 is the widest): hear the room, not just someone right in front of the tablet.
        runCatching { record.setPreferredMicrophoneFieldDimension(-1f) }
        val canceller = if (echo && android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
            runCatching { android.media.audiofx.AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
        } else {
            null
        }
        return record to canceller
    }

    /**
     * Reads the microphone until [isLive] turns false, keeping the last [RECENT_SECONDS] in a
     * ring. Each 32 ms window goes to the voice-activity model; only speech (with a little
     * before and after it) goes on to the spotter, so the TV's hum and the dishwasher never
     * reach a model that could take them for a name.
     */
    private fun spot(
        kws: KeywordSpotter,
        stream: OnlineStream,
        names: List<String>,
        gain: Float,
        isLive: () -> Boolean,
        echo: Boolean,
        onTalk: ((ShortArray) -> Unit)?,
        talkEasy: Boolean,
        emit: (WakeHit?, String?) -> Unit,
    ) {
        val (record, canceller) = open(echo) ?: run {
            stream.release()
            emit(null, "The microphone isn't available.")
            return
        }
        val gate = vad
        val ring = ShortArray(RATE * RECENT_SECONDS)
        var fed = 0L
        try {
            record.startRecording()
            val buf = ShortArray(WINDOW)
            val window = FloatArray(WINDOW)
            // Windows just before speech started, played into the spotter when it does.
            val before = ArrayDeque<FloatArray>()
            var speaking = false
            var hangover = 0
            var fedToSpotter = 0L
            val talk = onTalk?.let { TalkOver(RATE, talkEasy) }
            while (isLive()) {
                var got = 0
                while (got < WINDOW && isLive()) {
                    val n = record.read(buf, got, WINDOW - got)
                    if (n < 0) {
                        if (isLive()) emit(null, "The microphone stopped.")
                        return
                    }
                    got += n
                }
                if (got < WINDOW) break
                if (gain != 1f) {
                    for (i in 0 until WINDOW) buf[i] = (buf[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
                }
                for (i in 0 until WINDOW) ring[((fed + i) % ring.size).toInt()] = buf[i]
                fed += WINDOW
                if (talk != null && talk.feed(buf, WINDOW)) {
                    val keep = minOf(fed, RATE * TalkOver.SOUND_MS / 1000L).toInt()
                    onTalk?.invoke(ShortArray(keep) { ring[((fed - keep + it) % ring.size).toInt()] })
                }
                for (i in 0 until WINDOW) window[i] = buf[i] / 32768f
                val speech = gate == null || gate.compute(window) >= SPEECH
                if (speech) {
                    if (!speaking) {
                        speaking = true
                        for (w in before) { stream.acceptWaveform(w, RATE); fedToSpotter += WINDOW }
                        before.clear()
                    }
                    hangover = HANGOVER_WINDOWS
                } else if (speaking) {
                    if (--hangover <= 0) speaking = false
                }
                if (!speaking) {
                    before.addLast(window.copyOf())
                    while (before.size > BEFORE_WINDOWS) before.removeFirst()
                    continue
                }
                stream.acceptWaveform(window.copyOf(), RATE)
                fedToSpotter += WINDOW
                while (kws.isReady(stream)) kws.decode(stream)
                val result = kws.getResult(stream)
                if (result.keyword.isEmpty()) continue
                kws.reset(stream)
                val tag = WakeKeywords.tag(result.keyword) ?: continue
                val name = names.getOrNull(tag.name) ?: continue
                if (muted) continue
                // The spotter speaks up within a chunk or two of the name's last sound.
                val end = fed - RATE / 10
                val stamps = result.timestamps
                val length: Long = if (stamps.size >= 2) ((stamps.last() - stamps.first()) * RATE).toLong() + RATE / 5 else (RATE / 2).toLong()
                lastHit = end
                emit(WakeHit(name, tag.greeting, Recent.of(ring, fed).cut(end - length - RATE * 15 / 100, end + RATE * 15 / 100), end), null)
            }
        } finally {
            lastHeard = Recent.of(ring, fed)
            canceller?.release()
            runCatching { record.stop() }
            record.release()
            stream.release()
        }
    }

    /**
     * Reads the microphone into [rec] (the dictation model) until [isLive] turns false.
     */
    private fun read(rec: Recognizer, gain: Float, isLive: () -> Boolean, emit: (String?, String?) -> Unit) {
        val (record, canceller) = open(false) ?: run {
            rec.close()
            emit(null, "The microphone isn't available.")
            return
        }
        try {
            record.startRecording()
            val buf = ShortArray(RATE / 10)
            while (isLive()) {
                val n = record.read(buf, 0, buf.size)
                if (n < 0) {
                    if (isLive()) emit(null, "The microphone stopped.")
                    break
                }
                if (n == 0) continue
                if (gain != 1f) {
                    for (i in 0 until n) buf[i] = (buf[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
                }
                if (rec.acceptWaveForm(buf, n)) emit(rec.result, null)
            }
        } finally {
            canceller?.release()
            runCatching { record.stop() }
            record.release()
            rec.close()
        }
    }

    /** Frees the microphone, if [token] (from [start]) is still the one listening, or always without one. */
    fun stop(token: Int? = null) {
        if (token != null && token != generation) return
        generation++
        // The reader sees the new generation within a window and lets go of the microphone;
        // wait for it so whoever listens next gets the mic to themselves.
        val r = reader
        reader = null
        if (r != null && r !== Thread.currentThread()) runCatching { r.join(500) }
    }

    /**
     * What was said after the last spotted name, up to when listening stopped: the start of
     * the question, so "Hey Nova, tänd lampan" in one breath keeps "tänd lampan". Null when
     * nothing worth keeping came after the name.
     */
    fun afterName(): ShortArray? {
        val heard = lastHeard ?: return null
        val from = lastHit
        if (from < 0) return null
        lastHit = -1
        return heard.cut(from, heard.end)
    }

    /** Stops and lets go of the models' memory (when voice activation is turned off). */
    fun release() {
        stop()
        model?.close()
        model = null
        speakerModel?.close()
        speakerModel = null
        spotter?.release()
        spotter = null
        vad?.release()
        vad = null
    }

    /** The spotter and the voice-activity model, from the APK's assets; once. */
    private fun loadEar() {
        tokens.size
        if (spotter == null) {
            spotter = KeywordSpotter(
                assetManager = assets,
                config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = "ear/encoder.int8.onnx",
                            decoder = "ear/decoder.int8.onnx",
                            joiner = "ear/joiner.int8.onnx",
                        ),
                        tokens = "ear/tokens.txt",
                        numThreads = 1,
                        provider = "cpu",
                    ),
                    maxActivePaths = 4,
                    keywordsFile = "ear/keywords.txt",
                    keywordsScore = WakeKeywords.BOOST,
                    keywordsThreshold = WakeKeywords.THRESHOLD,
                    numTrailingBlanks = 1,
                ),
            )
        }
        if (vad == null) {
            vad = runCatching {
                Vad(
                    assetManager = assets,
                    config = VadModelConfig(
                        sileroVadModelConfig = SileroVadModelConfig(
                            model = "ear/silero_vad.onnx",
                            threshold = SPEECH,
                            minSilenceDuration = 0.25f,
                            minSpeechDuration = 0.1f,
                            windowSize = WINDOW,
                            maxSpeechDuration = 20f,
                        ),
                        sampleRate = RATE,
                        numThreads = 1,
                        provider = "cpu",
                    ),
                )
            }.getOrNull()
        }
    }

    private fun load() {
        if (model == null) model = Model(models.speech.absolutePath)
        if (speakerModel == null) speakerModel = SpeakerModel(models.speaker.absolutePath)
    }

    /** The recent microphone sound, ending [end] samples after listening started. */
    private class Recent(val samples: ShortArray, val end: Long) {
        /** The sound from sample [from] to [to] after listening started, as far as it is still kept; null when under a quarter second. */
        fun cut(from: Long, to: Long): ShortArray? {
            val start = end - samples.size
            val a = maxOf(from, start)
            val b = minOf(to, end)
            if (b - a < RATE / 4) return null
            return ShortArray((b - a).toInt()) { samples[(a - start + it).toInt()] }
        }

        companion object {
            /** A copy of [ring] in time order, [fed] samples having gone into it. */
            fun of(ring: ShortArray, fed: Long): Recent {
                val size = minOf(fed, ring.size.toLong()).toInt()
                val first = fed - size
                return Recent(ShortArray(size) { ring[((first + it) % ring.size).toInt()] }, fed)
            }
        }
    }

    private companion object {
        const val RATE = 16000
        const val DICTATION_MS = 9_000L
        const val RECENT_SECONDS = 6
        /** Silero VAD's window: 32 ms. */
        const val WINDOW = 512
        /** The voice-activity model's bar for speech. */
        const val SPEECH = 0.5f
        /** Windows of sound before speech starts that go to the spotter too (about 0.3 s). */
        const val BEFORE_WINDOWS = 10
        /** Windows after speech seems to end that still go to the spotter (about 0.3 s). */
        const val HANGOVER_WINDOWS = 10
    }
}
