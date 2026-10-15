package app.homeydash.assistant

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import app.homeydash.agent.Fbank
import app.homeydash.agent.VoiceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import java.util.concurrent.TimeUnit

/**
 * The voiceprint model (WeSpeaker ResNet34, about 26 MB, downloaded once): turns a second of
 * someone saying the wake name into 256 numbers that are much the same each time that person
 * speaks and clearly different for anyone else. Runs on the tablet; no sound leaves it.
 */
class VoicePrints(context: Context, http: OkHttpClient, private val scope: CoroutineScope) {
    sealed interface State {
        data object Missing : State
        data class Downloading(val progress: Float) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val file = File(File(context.filesDir, "vosk"), "wespeaker-resnet34.onnx")
    private val http = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
    private var job: Job? = null
    private var session: OrtSession? = null

    private val _state = MutableStateFlow<State>(if (file.exists()) State.Ready else State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    fun download() {
        if (job?.isActive == true || file.exists()) return
        job = scope.launch {
            _state.value = State.Downloading(0f)
            _state.value = try {
                withContext(Dispatchers.IO) { fetch() }
                State.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                State.Failed(e.message ?: "Download failed.")
            }
        }
    }

    private fun fetch() {
        file.parentFile?.mkdirs()
        val part = File(file.path + ".part")
        http.newCall(Request.Builder().url(URL).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Couldn't download the voiceprint model (HTTP ${response.code}).")
            val body = response.body ?: throw IOException("Empty download.")
            val total = body.contentLength().takeIf { it > 0 }
            var read = 0L
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        if (total != null) _state.value = State.Downloading(read.toFloat() / total)
                    }
                }
            }
            if (total != null && read != total) throw IOException("The download was cut off.")
        }
        if (!part.renameTo(file)) throw IOException("Couldn't save the voiceprint model.")
    }

    /** The voiceprint of 16 kHz [samples], or null without the model or enough sound. */
    suspend fun of(samples: ShortArray): FloatArray? = withContext(Dispatchers.Default) {
        if (_state.value != State.Ready || samples.size < Fbank.RATE / 4) return@withContext null
        runCatching { embed(samples) }.getOrNull()
    }

    @Synchronized
    private fun embed(samples: ShortArray): FloatArray? {
        val env = OrtEnvironment.getEnvironment()
        val s = session ?: env.createSession(file.path, OrtSession.SessionOptions()).also { session = it }
        val feats = Fbank.features(samples)
        if (feats.isEmpty()) return null
        val buffer = FloatBuffer.allocate(feats.size * Fbank.BANDS)
        feats.forEach { buffer.put(it) }
        buffer.rewind()
        return OnnxTensor.createTensor(env, buffer, longArrayOf(1, feats.size.toLong(), Fbank.BANDS.toLong())).use { input ->
            s.run(mapOf("feats" to input)).use { result ->
                ((result.get(0).value as? Array<*>)?.firstOrNull() as? FloatArray)?.let(VoiceId::normalize)
            }
        }
    }

    private companion object {
        const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_resnet34.onnx"
    }
}
