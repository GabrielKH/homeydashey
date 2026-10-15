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
     * Starts listening for [name]. [onHeard] gets the phrase, its Vosk voice vector (null when
     * the phrase was too short to tell) and the sound of the name being said, for a voiceprint
     * (see [VoicePrints]). Callbacks come on the main thread. Returns a token
     * for [stop], so one user can't stop another's listening. [sensitivity] (0–100) sets the
     * microphone gain and how sure the match must be.
     */
    fun start(
        name: String,
        onHeard: (text: String, voice: FloatArray?, sound: ShortArray?) -> Unit,
        onError: (String) -> Unit,
        sensitivity: Int = MicSensitivity.DEFAULT,
    ): Int {
        stop()
        val gen = generation
        scope.launch(Dispatchers.Main) {
            val loaded = withContext(Dispatchers.IO) { runCatching { load(); models.vocabulary } }
            if (gen != generation) return@launch
            loaded.exceptionOrNull()?.let { onError("Couldn't load the voice model: ${it.message}"); return@launch }
            val rec = try {
                Recognizer(model, RATE.toFloat(), WakeName.grammar(name, loaded.getOrNull())).apply {
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
                read(rec, gain, isLive = { gen == generation }) { result, failure, recent ->
                    main.post {
                        if (gen != generation) return@post
                        if (failure != null) {
                            onError(failure)
                        } else {
                            parse(result, name, minConfidence, recent)?.let { onHeard(it.text, it.voice, it.sound) }
                        }
                    }
                }
            }
        }
        return gen
    }

    /**
     * Hears one question offline with the wake model, in English only: the fallback when
     * Google's speech service can't be reached. [onText] gets what was said, or "" when
     * nothing was said in time. Returns a token for [stop].
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
                read(rec, 1f, isLive = { gen == generation }) { result, failure, _ ->
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

    /**
     * Reads the microphone into [rec] until [isLive] turns false. Uses the voice recognition
     * input, which on tablets with several microphones is the tuned (beam-formed) mix, asks
     * for a wide pickup so the whole kitchen is heard, and applies [gain].
     */
    @SuppressLint("MissingPermission") // WakeWatch only starts with the microphone permission.
    private fun read(rec: Recognizer, gain: Float, isLive: () -> Boolean, emit: (String?, String?, Recent?) -> Unit) {
        val size = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE / 5 * 2)
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        } catch (e: Exception) {
            rec.close()
            emit(null, "The microphone isn't available: ${e.message}", null)
            return
        }
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                emit(null, "The microphone isn't available.", null)
                return
            }
            // Far-field (-1 is the widest): hear the room, not just someone right in front of the tablet.
            runCatching { record.setPreferredMicrophoneFieldDimension(-1f) }
            record.startRecording()
            val buf = ShortArray(RATE / 10)
            // The last few seconds the recogniser heard, to cut the wake name out of.
            val ring = ShortArray(RATE * RECENT_SECONDS)
            var fed = 0L
            while (isLive()) {
                val n = record.read(buf, 0, buf.size)
                if (n < 0) {
                    if (isLive()) emit(null, "The microphone stopped.", null)
                    break
                }
                if (n == 0) continue
                if (gain != 1f) {
                    for (i in 0 until n) buf[i] = (buf[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
                }
                for (i in 0 until n) ring[((fed + i) % ring.size).toInt()] = buf[i]
                fed += n
                if (rec.acceptWaveForm(buf, n)) emit(rec.result, null, Recent.of(ring, fed))
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

    /** The recent microphone sound, ending [end] samples after listening started. */
    private class Recent(val samples: ShortArray, val end: Long) {
        /** The sound from [from] to [to] seconds after listening started, as far as it is still kept. */
        fun cut(from: Double, to: Double): ShortArray? {
            val start = end - samples.size
            val a = maxOf((from * RATE).toLong(), start)
            val b = minOf((to * RATE).toLong(), end)
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

    private class Heard(val text: String, val voice: FloatArray?, val sound: ShortArray?)

    private fun parse(hypothesis: String?, name: String, minConfidence: Double, recent: Recent?): Heard? {
        val json = runCatching { Json.parseToJsonElement(hypothesis.orEmpty()).jsonObject }.getOrNull() ?: return null
        val text = json["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val results = (json["result"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val words = results.map {
            WakeName.Word(it["word"]?.jsonPrimitive?.contentOrNull.orEmpty(), it["conf"]?.jsonPrimitive?.doubleOrNull ?: 1.0)
        }.ifEmpty { text.split(' ').map { WakeName.Word(it, 1.0) } }
        // Grammar mode guesses the closest phrase for any sound; unsure guesses don't count.
        if (!WakeName.spot(words, name, minConfidence)) return null
        val voice = json["spk"]?.jsonArray?.mapNotNull { it.jsonPrimitive.floatOrNull }?.toFloatArray()?.takeIf { it.isNotEmpty() }
        return Heard(text, voice, recent?.let { soundOfName(results, name, it) })
    }

    /**
     * The sound of the wake phrase ("hey nova"), with a little room either side, cut from what
     * the microphone heard. At least a second, so the voiceprint has enough to go on.
     */
    private fun soundOfName(results: List<JsonObject>, name: String, recent: Recent): ShortArray? {
        val own = WakeName.clean(name).split(' ')
        val words = results.map {
            Triple(WakeName.clean(it["word"]?.jsonPrimitive?.contentOrNull.orEmpty()), it["start"]?.jsonPrimitive?.doubleOrNull, it["end"]?.jsonPrimitive?.doubleOrNull)
        }
        val at = (0..words.size - own.size).firstOrNull { i -> words.subList(i, i + own.size).map { it.first } == own } ?: return null
        val greeting = words.getOrNull(at - 1)?.takeIf { it.first in GREETINGS }
        var from = (greeting ?: words[at]).second ?: return null
        var to = words[at + own.size - 1].third ?: return null
        from -= 0.15
        to += 0.15
        if (to - from < 1.0) {
            val grow = (1.0 - (to - from)) / 2
            from -= grow
            to += grow
        }
        return recent.cut(from, to)
    }

    private companion object {
        const val RATE = 16000
        const val DICTATION_MS = 9_000L
        const val RECENT_SECONDS = 4
        val GREETINGS = setOf("hey", "hi", "okay")
    }
}
