package app.homeydash.assistant

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.homeydash.agent.Confirmation
import app.homeydash.agent.Language
import app.homeydash.agent.VoiceFx
import app.homeydash.agent.VoiceStyle
import app.homeydash.agent.VoiceTrack
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.concurrent.thread

/** Listens on the tablet's microphone with Android's speech recognizer, in Swedish, English or both. */
class SpeechInput(private val context: Context) {
    interface Listener {
        fun onLevel(level: Float)
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onError(message: String)
    }

    private var recognizer: SpeechRecognizer? = null
    /** The first way of listening that worked, per language and on-device choice, so failures aren't repeated. */
    private val working = mutableMapOf<Pair<Language, Boolean>, Int>()

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** One way of asking the recogniser to listen; later ones need less installed on the tablet. */
    private data class Attempt(val onDevice: Boolean, val locale: String?, val detect: Boolean)

    /**
     * Must be called on the main thread. Stops by itself after a pause in speech. If the
     * tablet lacks a language pack for one way of listening, it quietly tries simpler ones:
     * no language detection, the online recogniser, the other language, the tablet's own.
     */
    fun start(language: Language, preferOnDevice: Boolean, listener: Listener) {
        val attempts = attempts(language, preferOnDevice)
        val key = language to preferOnDevice
        listenWith(attempts, (working[key] ?: 0).coerceAtMost(attempts.size - 1), key, listener)
    }

    private fun attempts(language: Language, preferOnDevice: Boolean): List<Attempt> {
        val onDevice = preferOnDevice && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val primary = if (language == Language.English) "en-US" else "sv-SE"
        val other = if (language == Language.English) "sv-SE" else "en-US"
        val detect = language == Language.Auto && Build.VERSION.SDK_INT >= 34
        return buildList {
            if (onDevice) {
                if (detect) add(Attempt(true, primary, true))
                add(Attempt(true, primary, false))
            }
            if (detect) add(Attempt(false, primary, true))
            add(Attempt(false, primary, false))
            // Auto hears either language, so the other one is a fair stand-in.
            if (language == Language.Auto) add(Attempt(false, other, false))
            add(Attempt(false, null, false))
        }
    }

    private fun listenWith(attempts: List<Attempt>, index: Int, key: Pair<Language, Boolean>, listener: Listener) {
        stop()
        val a = attempts[index]
        val r = if (a.onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                working[key] = index
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = listener.onLevel(0f)
            override fun onError(error: Int) {
                val missingLanguage = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                if (missingLanguage && recognizer === r) {
                    // Ask Android to fetch the pack for next time, where it can.
                    if (a.onDevice && Build.VERSION.SDK_INT >= 33) runCatching { r.triggerModelDownload(intent(a)) }
                    if (index + 1 < attempts.size) {
                        working[key] = index + 1
                        listenWith(attempts, index + 1, key, listener)
                        return
                    }
                }
                listener.onError(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ""
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "The assistant needs the microphone permission."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs the network."
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                            "Speech recognition on this tablet has no Swedish or English. Install \"Speech Recognition & Synthesis from Google\" from the Play Store, then add the languages under Android Settings, then Speech."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The microphone is busy."
                        else -> "Speech recognition failed ($error)."
                    },
                )
            }
            override fun onResults(results: Bundle?) = listener.onResult(best(results))
            override fun onPartialResults(partialResults: Bundle?) = listener.onPartial(best(partialResults))
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(intent(a))
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun best(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun intent(a: Attempt) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        if (a.locale != null) putExtra(RecognizerIntent.EXTRA_LANGUAGE, a.locale)
        // Android 14+: hear Swedish and English in the same session.
        if (a.detect && Build.VERSION.SDK_INT >= 34) {
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, arrayListOf("sv-SE", "en-US"))
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, arrayListOf("sv-SE", "en-US"))
        }
    }
}

/** A spoken answer ready to play: the sound file and how loud it is moment by moment. */
class Spoken(val file: File, val track: VoiceTrack)

/** Speaks answers with Android's text-to-speech, in one of the voice styles, on the tablet or into a file for a speaker. */
class SpeechOutput(context: Context) {
    private val appContext = context.applicationContext
    private var ready = false
    private val done = mutableMapOf<String, () -> Unit>()
    private val tts: TextToSpeech = TextToSpeech(appContext) { status -> ready = status == TextToSpeech.SUCCESS }
    @Volatile private var player: MediaPlayer? = null

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                synchronized(done) { done.remove(utteranceId) }?.invoke()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                synchronized(done) { done.remove(utteranceId) }?.invoke()
            }
        })
    }

    private fun prepare(text: String, language: Language, voice: VoiceStyle) {
        val locale = when (language) {
            Language.Swedish -> SWEDISH
            Language.English -> Locale.UK
            Language.Auto -> if (Confirmation.looksSwedish(text)) SWEDISH else Locale.UK
        }
        if (tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) tts.setLanguage(Locale.getDefault())
        tts.setPitch(voice.pitch)
        tts.setSpeechRate(voice.rate)
    }

    /**
     * Speaks on the tablet and returns when it has finished. [onFrame] gets the voice's
     * level, low, mid and high (0..1) about 60 times a second while it plays.
     */
    suspend fun speak(text: String, language: Language, voice: VoiceStyle = VoiceStyle.Natural, onFrame: (FloatArray) -> Unit = {}) {
        if (!ready || text.isBlank()) return
        val spoken = render(text, language, voice, File(appContext.cacheDir, "say.wav"))
        if (spoken == null) {
            plain(text, language, voice)
            return
        }
        play(spoken, onFrame)
    }

    /** The plain engine, when the voice can't be made into a file. */
    private suspend fun plain(text: String, language: Language, voice: VoiceStyle) {
        prepare(text, language, voice)
        suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            synchronized(done) { done[id] = { if (cont.isActive) cont.resume(Unit) } }
            cont.invokeOnCancellation { tts.stop() }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    private suspend fun play(spoken: Spoken, onFrame: (FloatArray) -> Unit) {
        val finished = CompletableDeferred<Unit>()
        val p = MediaPlayer()
        player = p
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            p.setDataSource(spoken.file.path)
            p.setOnCompletionListener { finished.complete(Unit) }
            p.setOnErrorListener { _, _, _ -> finished.complete(Unit); true }
            p.prepare()
            p.start()
            while (!finished.isCompleted) {
                onFrame(spoken.track.at(runCatching { p.currentPosition.toLong() }.getOrDefault(0L)))
                delay(16)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing to play; the answer is still shown as text.
        } finally {
            onFrame(FloatArray(4))
            runCatching { p.stop() }
            p.release()
            if (player === p) player = null
        }
    }

    fun stop() {
        tts.stop()
        player?.let { runCatching { it.stop() } }
    }

    /** Writes the spoken answer in [voice] to [file] as a WAV, for the tablet or a speaker. */
    suspend fun render(text: String, language: Language, voice: VoiceStyle, file: File): Spoken? {
        if (!ready || text.isBlank()) return null
        val raw = File(file.parentFile, file.nameWithoutExtension + "-raw.wav")
        prepare(text, language, voice)
        val made = suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            synchronized(done) { done[id] = { if (cont.isActive) cont.resume(raw.length() > 0) } }
            if (tts.synthesizeToFile(text, Bundle(), raw, id) != TextToSpeech.SUCCESS) {
                synchronized(done) { done.remove(id) }
                cont.resume(false)
            }
        }
        if (!made) return null
        return withContext(Dispatchers.Default) {
            val pcm = VoiceFx.readWav(raw.readBytes()) ?: return@withContext null
            val shaped = VoiceFx.apply(voice, pcm)
            file.writeBytes(VoiceFx.writeWav(shaped))
            raw.delete()
            Spoken(file, VoiceFx.analyse(shaped))
        }
    }

    fun shutdown() = tts.shutdown()

    private companion object {
        val SWEDISH: Locale = Locale.forLanguageTag("sv-SE")
    }
}

/**
 * A tiny web server on the tablet that hands the latest spoken answer to a speaker
 * on the home network (a Sonos plays it through its Homey "play URL" card). The path
 * has a random part, so only the speaker that was told the URL can fetch it.
 */
class AnswerServer(private val context: Context) {
    private var server: ServerSocket? = null
    @Volatile private var file: File? = null
    @Volatile private var path: String = ""

    /** Publishes [audio] and returns the URL a speaker can fetch it from, or null without a network address. */
    fun publish(audio: File): String? {
        val address = lanAddress() ?: return null
        val socket = server ?: ServerSocket(0).also { s ->
            server = s
            thread(name = "answer-server", isDaemon = true) { serve(s) }
        }
        file = audio
        path = "/answer/${UUID.randomUUID()}.wav"
        return "http://$address:${socket.localPort}$path"
    }

    private fun serve(s: ServerSocket) {
        while (!s.isClosed) {
            val client = runCatching { s.accept() }.getOrNull() ?: continue
            thread(isDaemon = true) { runCatching { handle(client) }; runCatching { client.close() } }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        val reader = client.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0)
        val out = client.getOutputStream()
        val f = file
        if (parts.getOrNull(1) != path || f == null || !f.exists() || (method != "GET" && method != "HEAD")) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        out.write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: ${f.length()}\r\nConnection: close\r\n\r\n".toByteArray())
        if (method == "GET") f.inputStream().use { it.copyTo(out) }
        out.flush()
    }

    private fun lanAddress(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    fun close() {
        runCatching { server?.close() }
        server = null
    }
}
