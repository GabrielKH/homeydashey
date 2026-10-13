package app.homeydash.assistant

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

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
    private var recognizer: Recognizer? = null
    private var service: SpeechService? = null
    private var generation = 0

    /** True while People is learning someone's voice; the dashboard leaves the mic alone. */
    var enrolling by mutableStateOf(false)

    /**
     * Starts listening for [name]. [onHeard] gets the phrase and its voice vector (null when
     * the phrase was too short to tell). Callbacks come on the main thread. Returns a token
     * for [stop], so one user can't stop another's listening.
     */
    fun start(name: String, onHeard: (text: String, voice: FloatArray?) -> Unit, onError: (String) -> Unit): Int {
        stop()
        val gen = generation
        scope.launch(Dispatchers.Main) {
            val loaded = withContext(Dispatchers.IO) { runCatching { load() } }
            if (gen != generation) return@launch
            loaded.exceptionOrNull()?.let { onError("Couldn't load the voice model: ${it.message}"); return@launch }
            try {
                val rec = Recognizer(model, RATE, WakeName.grammar(name)).apply {
                    setSpeakerModel(speakerModel)
                    setWords(true)
                }
                val svc = SpeechService(rec, RATE)
                recognizer = rec
                service = svc
                svc.startListening(object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) {}
                    override fun onResult(hypothesis: String?) = handle(hypothesis)
                    override fun onFinalResult(hypothesis: String?) = handle(hypothesis)
                    override fun onTimeout() {}
                    override fun onError(exception: Exception?) {
                        if (gen == generation) onError(exception?.message ?: "The microphone stopped.")
                    }

                    private fun handle(hypothesis: String?) {
                        if (gen != generation) return
                        val heard = parse(hypothesis, name) ?: return
                        onHeard(heard.first, heard.second)
                    }
                })
            } catch (e: Exception) {
                stop()
                onError("Couldn't listen for the wake name: ${e.message}")
            }
        }
        return gen
    }

    /** Frees the microphone, if [token] (from [start]) is still the one listening, or always without one. */
    fun stop(token: Int? = null) {
        if (token != null && token != generation) return
        generation++
        service?.let { runCatching { it.stop() }; runCatching { it.shutdown() } }
        service = null
        recognizer?.let { runCatching { it.close() } }
        recognizer = null
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

    private fun parse(hypothesis: String?, name: String): Pair<String, FloatArray?>? {
        val json = runCatching { Json.parseToJsonElement(hypothesis.orEmpty()).jsonObject }.getOrNull() ?: return null
        val text = json["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!WakeName.heard(text, name)) return null
        // Grammar mode guesses the closest phrase for any sound; skip unsure ones.
        val unsure = (json["result"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("conf")?.jsonPrimitive?.doubleOrNull }
            ?.any { it < MIN_CONFIDENCE } ?: false
        if (unsure) return null
        val voice = json["spk"]?.jsonArray?.mapNotNull { it.jsonPrimitive.floatOrNull }?.toFloatArray()?.takeIf { it.isNotEmpty() }
        return text to voice
    }

    private companion object {
        const val RATE = 16000f
        const val MIN_CONFIDENCE = 0.6
    }
}
