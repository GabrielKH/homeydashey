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

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Must be called on the main thread. Stops by itself after a pause in speech. */
    fun start(language: Language, preferOnDevice: Boolean, listener: Listener) {
        stop()
        val onDevice = preferOnDevice && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = listener.onLevel(0f)
            override fun onError(error: Int) {
                listener.onError(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ""
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "The assistant needs the microphone permission."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs the network."
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                            "This language isn't installed for speech recognition. Add Swedish and English under Android Settings, then Speech."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The microphone is busy."
                        else -> "Speech recognition failed ($error)."
                    },
                )
            }
            override fun onResults(results: Bundle?) = listener.onResult(best(results))
            override fun onPartialResults(partialResults: Bundle?) = listener.onPartial(best(partialResults))
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(intent(language))
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun best(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun intent(language: Language) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (language == Language.English) "en-US" else "sv-SE")
        if (language == Language.Auto) {
            // Android 14+: hear Swedish and English in the same session.
            if (Build.VERSION.SDK_INT >= 34) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, arrayListOf("sv-SE", "en-US"))
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, arrayListOf("sv-SE", "en-US"))
            }
        }
    }
}

/** Speaks answers with Android's text-to-speech, on the tablet or into a file for a speaker. */
class SpeechOutput(context: Context) {
    private var ready = false
    private val done = mutableMapOf<String, () -> Unit>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> ready = status == TextToSpeech.SUCCESS }

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

    private fun prepare(text: String, language: Language) {
        val locale = when (language) {
            Language.Swedish -> SWEDISH
            Language.English -> Locale.UK
            Language.Auto -> if (Confirmation.looksSwedish(text)) SWEDISH else Locale.UK
        }
        if (tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) tts.setLanguage(Locale.getDefault())
    }

    /** Speaks on the tablet; returns when it has finished. */
    suspend fun speak(text: String, language: Language) {
        if (!ready || text.isBlank()) return
        prepare(text, language)
        suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            synchronized(done) { done[id] = { if (cont.isActive) cont.resume(Unit) } }
            cont.invokeOnCancellation { tts.stop() }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    fun stop() = tts.stop()

    /** Writes the spoken answer to a WAV file, for a speaker to play. */
    suspend fun toFile(text: String, language: Language, file: File): Boolean {
        if (!ready || text.isBlank()) return false
        prepare(text, language)
        return suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            synchronized(done) { done[id] = { if (cont.isActive) cont.resume(file.length() > 0) } }
            if (tts.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) {
                synchronized(done) { done.remove(id) }
                cont.resume(false)
            }
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
