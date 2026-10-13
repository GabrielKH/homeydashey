package app.homeydash.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneDirection
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.homeydash.agent.MicSensitivity
import app.homeydash.agent.WakeName
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
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

/**
 * Listens on the tablet's microphone for the assistant's name, entirely offline (Vosk).
 * The recogniser only knows the wake phrases, so ordinary talk is ignored and never
 * written down. Each wake phrase also gives a voice "fingerprint" for telling people apart.
 */
class WakeListener(private val models: VoiceModels, private val scope: CoroutineScope) {
    private var model: Model? = null
    private var speakerModel: SpeakerModel? = null
    @Volatile private var generation = 0
    /** The thread reading the microphone; it closes its own recorder and recogniser when it ends. */
    private var reader: Thread? = null

    /** True while People is learning someone's voice; the dashboard leaves the mic alone. */
    var enrolling by mutableStateOf(false)

    /**
     * Starts listening for [name]. [onHeard] gets the phrase and its voice vector (null when
     * the phrase was too short to tell). Callbacks come on the main thread. Returns a token
     * for [stop], so one user can't stop another's listening. [sensitivity] (0–100) sets the
     * microphone gain and how sure the match must be.
     */
    fun start(
        name: String,
        onHeard: (text: String, voice: FloatArray?) -> Unit,
        onError: (String) -> Unit,
        sensitivity: Int = MicSensitivity.DEFAULT,
    ): Int {
        stop()
        val gen = generation
        scope.launch(Dispatchers.Main) {
            val loaded = withContext(Dispatchers.IO) { runCatching { load() } }
            if (gen != generation) return@launch
            loaded.exceptionOrNull()?.let { onError("Couldn't load the voice model: ${it.message}"); return@launch }
            val rec = try {
                Recognizer(model, RATE.toFloat(), WakeName.grammar(name)).apply {
                    setSpeakerModel(speakerModel)
                    setWords(true)
                }
            } catch (e: Exception) {
                onError("Couldn't listen for the wake name: ${e.message}")
                return@launch
            }
            val minConfidence = MicSensitivity.minConfidence(sensitivity)
            val gain = MicSensitivity.gain(sensitivity)
            val main = Handler(Looper.getMainLooper())
            reader = thread(name = "wake-mic") {
                read(rec, gain, isLive = { gen == generation }) { result, failure ->
                    main.post {
                        if (gen != generation) return@post
                        if (failure != null) {
                            onError(failure)
                        } else {
                            parse(result, name, minConfidence)?.let { onHeard(it.first, it.second) }
                        }
                    }
                }
            }
        }
        return gen
    }

    /**
     * Reads the microphone into [rec] until [isLive] turns false. Uses the voice recognition
     * input, which on tablets with several microphones is the tuned (beam-formed) mix, asks
     * for a wide pickup so the whole kitchen is heard, and applies [gain].
     */
    @SuppressLint("MissingPermission") // WakeWatch only starts with the microphone permission.
    private fun read(rec: Recognizer, gain: Float, isLive: () -> Boolean, emit: (String?, String?) -> Unit) {
        val size = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE / 5 * 2)
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        } catch (e: Exception) {
            rec.close()
            emit(null, "The microphone isn't available: ${e.message}")
            return
        }
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                emit(null, "The microphone isn't available.")
                return
            }
            // Far-field: hear the room, not just someone right in front of the tablet.
            runCatching { record.setPreferredMicrophoneFieldDimension(MicrophoneDirection.MIC_FIELD_DIMENSION_WIDE) }
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
            runCatching { record.stop() }
            record.release()
            rec.close()
        }
    }

    /** Frees the microphone, if [token] (from [start]) is still the one listening, or always without one. */
    fun stop(token: Int? = null) {
        if (token != null && token != generation) return
        generation++
        // The reader sees the new generation within a tenth of a second and lets go of the
        // microphone; wait for it so whoever listens next gets the mic to themselves.
        val r = reader
        reader = null
        if (r != null && r !== Thread.currentThread()) runCatching { r.join(500) }
    }

    /** Stops and lets go of the models' memory (when voice activation is turned off). */
    fun release() {
        stop()
        model?.close()
        model = null
        speakerModel?.close()
        speakerModel = null
    }

    private fun load() {
        if (model == null) model = Model(models.speech.absolutePath)
        if (speakerModel == null) speakerModel = SpeakerModel(models.speaker.absolutePath)
    }

    private fun parse(hypothesis: String?, name: String, minConfidence: Double): Pair<String, FloatArray?>? {
        val json = runCatching { Json.parseToJsonElement(hypothesis.orEmpty()).jsonObject }.getOrNull() ?: return null
        val text = json["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!WakeName.heard(text, name)) return null
        // Grammar mode guesses the closest phrase for any sound; skip unsure ones.
        val unsure = (json["result"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("conf")?.jsonPrimitive?.doubleOrNull }
            ?.any { it < minConfidence } ?: false
        if (unsure) return null
        val voice = json["spk"]?.jsonArray?.mapNotNull { it.jsonPrimitive.floatOrNull }?.toFloatArray()?.takeIf { it.isNotEmpty() }
        return text to voice
    }

    private companion object {
        const val RATE = 16000
    }
}
