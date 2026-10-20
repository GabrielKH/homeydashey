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
 * A Piper voice spoken on the tablet itself, downloaded once (about 60 MB) and run with
 * sherpa-onnx: TARS's voice ([tars]) or Alma's Swedish one ([alma]).
 */
class PiperVoice(context: Context, http: OkHttpClient, private val scope: CoroutineScope, private val spec: Spec) {
    /**
     * Which voice: where it is kept and fetched from. A [bilingual] voice also reads Swedish
     * with Swedish pronunciation, so TARS speaks Swedish with his accent; the others only speak
     * their own language. [speaker] picks one of a voice's several speakers; the noise and length
     * settings are the ones the voice's maker recommends, or sherpa's own when null.
     */
    class Spec(
        val folder: String,
        val file: String,
        val what: String,
        val modelUrls: List<String>,
        val configUrls: List<String>,
        val bilingual: Boolean,
        val speaker: String? = null,
        val noiseScale: Float? = null,
        val noiseScaleW: Float? = null,
        val lengthScale: Float? = null,
    )

    sealed interface State {
        data object Missing : State
        data class Downloading(val progress: Float, val what: String) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val dir = File(context.filesDir, spec.folder)
    private val english = File(dir, "${spec.file}-en.onnx")
    private val swedish = File(dir, "${spec.file}-sv.onnx")
    private val config = File(dir, "${spec.file}.onnx.json")
    private val tokens = File(dir, "tokens.txt")
    // The pronunciation rules are the same for every voice, so they are kept once, where TARS's were.
    private val espeak = File(File(context.filesDir, "tars"), "espeak-ng-data")
    private val http = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
    private var job: Job? = null
    private val engines = java.util.concurrent.ConcurrentHashMap<Boolean, OfflineTts>()

    private fun ready() = (english.exists() || !spec.bilingual) && swedish.exists() && tokens.exists() && File(espeak, "phontab").exists()

    private val _state = MutableStateFlow<State>(if (ready()) State.Ready else State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    fun download() {
        if (job?.isActive == true || ready()) return
        job = scope.launch {
            _state.value = State.Downloading(0f, spec.what)
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
        val raw = File(dir, "${spec.file}.onnx.part")
        fetch(spec.modelUrls, raw, spec.what)
        fetch(spec.configUrls, config, "${spec.what} settings")
        if (!File(espeak, "phontab").exists()) {
            espeak.parentFile?.mkdirs()
            val archive = File(espeak.parentFile, "espeak-ng-data.tar.bz2")
            fetch(listOf(ESPEAK_URL), archive, "pronunciation rules")
            untar(archive, espeak.parentFile!!)
            archive.delete()
        }
        val cfg = Json.parseToJsonElement(config.readText()).jsonObject
        tokens.writeText(PiperModel.tokens(cfg))
        // One copy reads English, the other Swedish; sherpa takes the language from the model file.
        if (spec.bilingual) {
            raw.copyTo(english, overwrite = true)
            english.appendBytes(PiperModel.metadataBytes(PiperModel.metadata(cfg)))
        }
        if (!raw.renameTo(swedish)) throw IOException("Couldn't save ${spec.what}.")
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

    /** [text] in this voice, or null before it is downloaded, or for English in a Swedish-only voice. */
    suspend fun speak(text: String, inSwedish: Boolean, speed: Float = 1f): Pcm? = withContext(Dispatchers.Default) {
        if (_state.value != State.Ready || text.isBlank() || (!inSwedish && !spec.bilingual)) return@withContext null
        runCatching { synth(text, inSwedish, speed) }.getOrNull()
    }

    /** Whether the voice is on the tablet. */
    val isReady: Boolean get() = _state.value == State.Ready

    /**
     * Loads the voice ahead of the first answer (reading the 60 MB model takes a moment), so
     * it starts talking as soon as the text is there.
     */
    fun warm(inSwedish: Boolean = !spec.bilingual) {
        if (!isReady || engines.containsKey(inSwedish)) return
        scope.launch(Dispatchers.Default) { runCatching { engine(inSwedish) } }
    }

    @Volatile private var speakerId: Int? = null

    @Synchronized
    private fun engine(inSwedish: Boolean): OfflineTts =
        engines.getOrPut(inSwedish) {
            val model = if (inSwedish) swedish else english
            OfflineTts(
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(model = model.path, tokens = tokens.path, dataDir = espeak.path).apply {
                            spec.noiseScale?.let { noiseScale = it }
                            spec.noiseScaleW?.let { noiseScaleW = it }
                            spec.lengthScale?.let { lengthScale = it }
                        },
                        numThreads = 4,
                    ),
                ),
            )
        }

    @Synchronized
    private fun synth(text: String, inSwedish: Boolean, speed: Float): Pcm {
        val tts = engine(inSwedish)
        val sid = spec.speaker?.let { name -> speakerId ?: PiperModel.speakerId(Json.parseToJsonElement(config.readText()).jsonObject, name).also { speakerId = it } } ?: 0
        val audio = tts.generate(text, sid = sid, speed = speed)
        val samples = ShortArray(audio.samples.size) { i -> (audio.samples[i].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
        return Pcm(audio.sampleRate, samples)
    }

    companion object {
        private const val TARS_HF = "https://huggingface.co/olivierdion007/TARS-AI/resolve/main/TARS.onnx"
        private const val TARS_GITHUB = "https://github.com/TARS-AI-Community/TARS-AI/raw/refs/heads/V3/src/character/TARS/voice/TARS.onnx"
        private const val ALMA_HF = "https://huggingface.co/yeagersthlm/piper-voice-sv-alma/resolve/main/sv_SE-alma-medium.onnx"
        // Piper's own Alma, before the retraining, in case the retrained one is gone.
        private const val ALMA_PIPER = "https://huggingface.co/rhasspy/piper-voices/resolve/main/sv/sv_SE/alma/medium/sv_SE-alma-medium.onnx"
        private const val ESPEAK_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.tar.bz2"

        /**
         * The voice the TARS-AI project trained on the film; its calm mood, of his eight, sounds
         * most like the film.
         */
        val TARS = Spec(
            folder = "tars", file = "TARS", what = "TARS's voice",
            modelUrls = listOf(TARS_HF, TARS_GITHUB), configUrls = listOf("$TARS_HF.json", "$TARS_GITHUB.json"),
            bilingual = true, speaker = "neutral",
        )

        /**
         * Alma, a Swedish woman's voice retrained 10 000 epochs on the NST corpus by Daniel Nylander
         * (CC BY 4.0), with his recommended settings.
         */
        val ALMA = Spec(
            folder = "alma", file = "alma", what = "Alma's voice",
            modelUrls = listOf(ALMA_HF, ALMA_PIPER), configUrls = listOf("$ALMA_HF.json", "$ALMA_PIPER.json"),
            bilingual = false, noiseScale = 0.8f, noiseScaleW = 0.6f, lengthScale = 0.9f,
        )
    }
}
