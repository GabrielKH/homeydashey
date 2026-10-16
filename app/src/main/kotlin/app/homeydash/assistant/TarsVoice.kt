package app.homeydash.assistant

import android.content.Context
import app.homeydash.agent.Pcm
import app.homeydash.agent.PiperModel
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
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
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * TARS's voice, spoken on the tablet itself: the Piper voice the TARS-AI project trained on the
 * film, downloaded once from Hugging Face (about 60 MB) and run with sherpa-onnx. Swedish answers
 * are read with Swedish pronunciation in the same voice, so TARS speaks Swedish with his accent.
 */
class TarsVoice(context: Context, http: OkHttpClient, private val scope: CoroutineScope) {
    sealed interface State {
        data object Missing : State
        data class Downloading(val progress: Float, val what: String) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val dir = File(context.filesDir, "tars")
    private val english = File(dir, "TARS-en.onnx")
    private val swedish = File(dir, "TARS-sv.onnx")
    private val config = File(dir, "TARS.onnx.json")
    private val tokens = File(dir, "tokens.txt")
    private val espeak = File(dir, "espeak-ng-data")
    private val http = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
    private var job: Job? = null
    private val engines = java.util.concurrent.ConcurrentHashMap<Boolean, OfflineTts>()

    private fun ready() = english.exists() && swedish.exists() && tokens.exists() && File(espeak, "phontab").exists()

    private val _state = MutableStateFlow<State>(if (ready()) State.Ready else State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    fun download() {
        if (job?.isActive == true || ready()) return
        job = scope.launch {
            _state.value = State.Downloading(0f, "TARS's voice")
            _state.value = try {
                withContext(Dispatchers.IO) { fetchAll() }
                State.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                State.Failed(e.message ?: "Download failed.")
            }
        }
    }

    private fun fetchAll() {
        dir.mkdirs()
        val raw = File(dir, "TARS.onnx.part")
        fetch(MODEL_URLS, raw, "TARS's voice")
        fetch(CONFIG_URLS, config, "TARS's voice settings")
        if (!File(espeak, "phontab").exists()) {
            val archive = File(dir, "espeak-ng-data.tar.bz2")
            fetch(listOf(ESPEAK_URL), archive, "pronunciation rules")
            untar(archive, dir)
            archive.delete()
        }
        val cfg = Json.parseToJsonElement(config.readText()).jsonObject
        tokens.writeText(PiperModel.tokens(cfg))
        // One copy reads English, the other Swedish; sherpa takes the language from the model file.
        raw.copyTo(english, overwrite = true)
        english.appendBytes(PiperModel.metadataBytes(PiperModel.metadata(cfg)))
        if (!raw.renameTo(swedish)) throw IOException("Couldn't save TARS's voice.")
        swedish.appendBytes(PiperModel.metadataBytes(PiperModel.metadata(cfg, espeakVoice = "sv")))
    }

    /** Downloads the first of [urls] that answers into [file]. */
    private fun fetch(urls: List<String>, file: File, what: String) {
        var last: Exception? = null
        for (url in urls) {
            try {
                download(url, file, what)
                return
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("Couldn't download $what.")
    }

    private fun download(url: String, file: File, what: String) {
        val part = File(file.path + ".dl")
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Couldn't download $what (HTTP ${response.code}).")
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
                        if (total != null) _state.value = State.Downloading(read.toFloat() / total, what)
                    }
                }
            }
            if (total != null && read != total) throw IOException("The download of $what was cut off.")
        }
        if (file.exists()) file.delete()
        if (!part.renameTo(file)) throw IOException("Couldn't save $what.")
    }

    private fun untar(archive: File, into: File) {
        val root = into.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val out = File(into, entry.name)
                // Never outside the folder, whatever the archive says.
                if (!out.canonicalPath.startsWith(root)) continue
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it) }
                }
            }
        }
    }

    /** [text] in TARS's voice, or null before the voice is downloaded. */
    suspend fun speak(text: String, inSwedish: Boolean): Pcm? = withContext(Dispatchers.Default) {
        if (_state.value != State.Ready || text.isBlank()) return@withContext null
        runCatching { synth(text, inSwedish) }.getOrNull()
    }

    /** Whether his voice is on the tablet. */
    val isReady: Boolean get() = _state.value == State.Ready

    /**
     * Loads the voice ahead of the first answer (reading the 60 MB model takes a moment), so
     * TARS starts talking as soon as the text is there.
     */
    fun warm(inSwedish: Boolean = false) {
        if (!isReady || engines.containsKey(inSwedish)) return
        scope.launch(Dispatchers.Default) { runCatching { engine(inSwedish) } }
    }

    @Volatile private var neutral: Int? = null

    @Synchronized
    private fun engine(inSwedish: Boolean): OfflineTts =
        engines.getOrPut(inSwedish) {
            val model = if (inSwedish) swedish else english
            OfflineTts(
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(model = model.path, tokens = tokens.path, dataDir = espeak.path),
                        numThreads = 4,
                    ),
                ),
            )
        }

    @Synchronized
    private fun synth(text: String, inSwedish: Boolean): Pcm {
        val tts = engine(inSwedish)
        // The calm mood of TARS's eight sounds most like the film.
        val sid = neutral ?: PiperModel.speakerId(Json.parseToJsonElement(config.readText()).jsonObject, "neutral").also { neutral = it }
        val audio = tts.generate(text, sid = sid, speed = 1f)
        val samples = ShortArray(audio.samples.size) { i -> (audio.samples[i].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
        return Pcm(audio.sampleRate, samples)
    }

    private companion object {
        const val MODEL_URLS_BASE = "https://huggingface.co/olivierdion007/TARS-AI/resolve/main/TARS.onnx"
        const val GITHUB_BASE = "https://github.com/TARS-AI-Community/TARS-AI/raw/refs/heads/V3/src/character/TARS/voice/TARS.onnx"
        val MODEL_URLS = listOf(MODEL_URLS_BASE, GITHUB_BASE)
        val CONFIG_URLS = listOf("$MODEL_URLS_BASE.json", "$GITHUB_BASE.json")
        const val ESPEAK_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.tar.bz2"
    }
}
