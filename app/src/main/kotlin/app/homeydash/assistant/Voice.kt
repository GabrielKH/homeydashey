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
import android.media.AudioFormat
import android.media.AudioTrack
import app.homeydash.agent.Pcm
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
        /** Google's speech service couldn't be reached; the listener may listen another way. */
        fun onUnavailable(message: String) = onError(message)
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
        listenWith(attempts, (working[key] ?: 0).coerceAtMost(attempts.size - 1), key, listener, retries = 0)
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

    private fun listenWith(attempts: List<Attempt>, index: Int, key: Pair<Language, Boolean>, listener: Listener, retries: Int) {
        stop()
        val a = attempts[index]
        val r = if (a.onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        // Callbacks from a recogniser that was already replaced are ignored.
        val current = { recognizer === r }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                working[key] = index
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                if (current()) listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                if (current()) listener.onLevel(0f)
            }
            override fun onError(error: Int) {
                if (!current()) return
                // The tablet's on-device recogniser drops the connection (error 11) when it can't
                // serve this language; Google's online one can. Use it from now on.
                if (a.onDevice && error in ON_DEVICE_GAVE_UP) {
                    val online = attempts.indexOfFirst { !it.onDevice }
                    if (online > index) {
                        working[key] = online
                        listenWith(attempts, online, key, listener, retries = 0)
                        return
                    }
                }
                // The recognition service hiccups (busy, restarting, dropped connection); try again quietly.
                if (error in TRANSIENT && retries < MAX_RETRIES) {
                    main.postDelayed({ if (current()) listenWith(attempts, index, key, listener, retries + 1) }, RETRY_DELAY_MS * (retries + 1))
                    return
                }
                val missingLanguage = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                if (missingLanguage) {
                    // Ask Android to fetch the pack for next time, where it can.
                    if (a.onDevice && Build.VERSION.SDK_INT >= 33) runCatching { r.triggerModelDownload(intent(a)) }
                    if (index + 1 < attempts.size) {
                        working[key] = index + 1
                        listenWith(attempts, index + 1, key, listener, retries = 0)
                        return
                    }
                }
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ""
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "The assistant needs the microphone permission."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs the network."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> MISSING_LANGUAGE
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The microphone is busy; another app may be using it."
                    SpeechRecognizer.ERROR_AUDIO -> "The microphone didn't record (error 3)."
                    SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                        "Google's speech service didn't answer (error $error). Check the tablet's internet."
                    else -> "Speech recognition failed (error $error)."
                }
                if (error in UNREACHABLE) listener.onUnavailable(message) else listener.onError(message)
            }
            override fun onResults(results: Bundle?) {
                if (current()) listener.onResult(best(results))
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (current()) listener.onPartial(best(partialResults))
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(intent(a))
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 350L
        private val UNREACHABLE = setOf(
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
        )
        private val ON_DEVICE_GAVE_UP = setOf(
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_CLIENT,
        )
        private val TRANSIENT = setOf(
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
            SpeechRecognizer.ERROR_AUDIO,
        )

        const val MISSING_LANGUAGE =
            "Speech recognition on this tablet has no Swedish or English yet. Tap \"Speech settings\" and add them there."

        /**
         * Opens where the tablet's speech recognition and its languages are set up: the
         * voice input screen, else the language and input screen, else Settings.
         */
        fun openSettings(context: Context) {
            val intents = listOf(
                Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS),
                Intent("com.android.settings.VOICE_INPUT_SETTINGS"),
                Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS),
                Intent(android.provider.Settings.ACTION_SETTINGS),
            )
            for (i in intents) {
                val ok = runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                if (ok) return
            }
        }
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

/**
 * Speaks answers with Android's text-to-speech, in one of the voice styles, on the tablet or into a file for a speaker.
 * The [VoiceStyle.local] voices come from a voice model on your own computer, set in [settings].
 */
class SpeechOutput(
    context: Context,
    http: okhttp3.OkHttpClient,
    private val tars: PiperVoice,
    private val alma: PiperVoice,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val settings: () -> app.homeydash.agent.AgentSettings,
) {
    private val http = http.newBuilder()
        .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val appContext = context.applicationContext
    private var ready = false

    /** [text] as it should be heard: dates in words, not "2026 dash 10 dash 18", and "taklampan" not "tacklampan". */
    private fun spoken(text: String, swedish: Boolean) = app.homeydash.agent.Pronounce.sayable(
        app.homeydash.agent.SpokenDates.speakable(app.homeydash.agent.ThinkingSounds.sayable(text, swedish), swedish, java.time.LocalDate.now().year),
        swedish,
    )

    /** The owner's speaking speed, as a factor of each voice's own. */
    private fun pace(): Float = settings().pace.coerceIn(app.homeydash.agent.PACE_RANGE) / 100f
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
        tts.setSpeechRate(voice.rate * pace())
    }

    /**
     * Speaks on the tablet and returns when it has finished. [onFrame] gets the voice's
     * level, low, mid and high (0..1) about 60 times a second while it plays.
     */
    suspend fun speak(said: String, language: Language, voice: VoiceStyle = VoiceStyle.Natural, onFrame: (FloatArray) -> Unit = {}, onStart: () -> Unit = {}) {
        if (said.isBlank()) return
        val text = spoken(said, swedish(said, language))
        // TARS's voice is made on the tablet sentence by sentence: he starts on the first one
        // while the rest is still being made, instead of after the whole answer.
        piper(voice)?.takeIf { it.isReady }?.let { p ->
            val swedish = swedish(text, language)
            if ((swedish || voice != VoiceStyle.Alma) && stream(channelOf(app.homeydash.agent.Sentences.split(text)), { p.speak(it, swedish, pace()) }, onFrame, onStart)) return
        }
        if (!ready) return
        val spoken = render(text, language, voice, File(appContext.cacheDir, "say.wav"))
        if (spoken == null) {
            plain(text, language, voice, onStart)
            return
        }
        play(spoken, onFrame, onStart)
    }

    /** The plain engine, when the voice can't be made into a file. */
    private suspend fun plain(text: String, language: Language, voice: VoiceStyle, onStart: () -> Unit = {}) {
        onStart()
        prepare(text, language, voice)
        suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            synchronized(done) { done[id] = { if (cont.isActive) cont.resume(Unit) } }
            cont.invokeOnCancellation { tts.stop() }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    private suspend fun play(spoken: Spoken, onFrame: (FloatArray) -> Unit, onStart: () -> Unit = {}) {
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
            onStart()
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
        track?.let { runCatching { it.pause(); it.flush() } }
        track = null
    }

    @Volatile private var track: AudioTrack? = null

    /** The voice made on the tablet itself for [voice]: TARS's or Alma's; null for the others. */
    private fun piper(voice: VoiceStyle): PiperVoice? = when (voice) {
        VoiceStyle.Tars -> tars
        VoiceStyle.Alma -> alma
        else -> null
    }

    /** Gets TARS's or Alma's voice ready before there is something to say. */
    fun warm(voice: VoiceStyle) {
        piper(voice)?.warm()
    }

    /**
     * Speaks an answer that is still being written: each piece from [pieces] is spoken as soon as
     * it arrives, the next one made while the one before plays, until [pieces] is closed.
     * [onStart] is called when the first sound is about to play. False when nothing was spoken.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun speakLive(
        pieces: kotlinx.coroutines.channels.ReceiveChannel<String>,
        swedish: Boolean,
        voice: VoiceStyle,
        onStart: () -> Unit,
        onFrame: (FloatArray) -> Unit,
        onSentence: (String) -> Unit = {},
    ): Boolean = coroutineScope {
        // Long pieces are cut into sentences, so the first one starts sooner.
        val sentences = produce(capacity = kotlinx.coroutines.channels.Channel.UNLIMITED) {
            for (piece in pieces) app.homeydash.agent.Sentences.split(spoken(piece, swedish)).forEach { send(it) }
        }
        val live = File(appContext.cacheDir, "live.wav")
        stream(sentences, { sentence -> phrase(sentence, swedish, voice) ?: pcmOf(sentence, swedish, voice, live) }, onFrame, onStart, onSentence)
    }

    /** Short sounds made ahead of time, like the "hmm" while it thinks, per voice and language. */
    private val phrases = java.util.concurrent.ConcurrentHashMap<String, Pcm>()

    private fun phraseKey(text: String, swedish: Boolean, voice: VoiceStyle) = "${voice.name}|${settings().pace}|$swedish|$text"

    private fun phrase(text: String, swedish: Boolean, voice: VoiceStyle): Pcm? = phrases[phraseKey(text, swedish, voice)]

    /** [text] in [voice] as made ahead of time by [prepareSounds]; null until then. */
    fun prepared(text: String, swedish: Boolean, voice: VoiceStyle): Pcm? = phrase(text, swedish, voice)

    /** Whether [text] is made ahead of time in [voice], so it can be said without waiting. */
    fun hasSound(text: String, swedish: Boolean, voice: VoiceStyle): Boolean = phrases.containsKey(phraseKey(text, swedish, voice))

    /** Makes [texts] in [voice] ahead of time, so saying them later is instant. */
    fun prepareSounds(texts: List<String>, swedish: Boolean, voice: VoiceStyle) {
        scope.launch(Dispatchers.Default) {
            val file = File(appContext.cacheDir, "phrase.wav")
            for (t in texts) {
                val key = phraseKey(t, swedish, voice)
                if (phrases.containsKey(key)) continue
                pcmOf(t, swedish, voice, file)?.let { phrases[key] = it }
            }
        }
    }

    /** [text] in [voice] as sound, from whichever voice can make it; null when none can. */
    private suspend fun pcmOf(text: String, swedish: Boolean, voice: VoiceStyle, scratch: File): Pcm? {
        piper(voice)?.takeIf { it.isReady }?.speak(text, swedish, pace())?.let { return it }
        if (voice.local) localPcm(text, swedish)?.let { return it }
        return tabletPcm(text, if (swedish) Language.Swedish else Language.English, voice, scratch)
    }

    /** The tablet's own text-to-speech in [voice], as sound; null when it can't. */
    private suspend fun tabletPcm(text: String, language: Language, voice: VoiceStyle, raw: File): Pcm? {
        if (!ready) return null
        val made = synchronized(tts) {
            prepare(text, language, voice)
            CompletableDeferred<Boolean>().also { result ->
                val id = UUID.randomUUID().toString()
                synchronized(done) { done[id] = { result.complete(raw.length() > 0) } }
                if (tts.synthesizeToFile(text, Bundle(), raw, id) != TextToSpeech.SUCCESS) {
                    synchronized(done) { done.remove(id) }
                    result.complete(false)
                }
            }
        }
        if (!made.await()) return null
        return withContext(Dispatchers.Default) { VoiceFx.readWav(raw.readBytes())?.let { VoiceFx.apply(voice, it) } }
    }

    private fun <T> channelOf(items: List<T>) = kotlinx.coroutines.channels.Channel<T>(kotlinx.coroutines.channels.Channel.UNLIMITED).also { c ->
        items.forEach { c.trySend(it) }
        c.close()
    }

    private fun swedish(text: String, language: Language) = when (language) {
        Language.Swedish -> true
        Language.English -> false
        Language.Auto -> Confirmation.looksSwedish(text)
    }

    /**
     * Plays [sentences] as they are made: the next one is made while the one before plays.
     * False when not even the first could be made, so another voice can take over.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun stream(
        sentences: kotlinx.coroutines.channels.ReceiveChannel<String>,
        make: suspend (String) -> Pcm?,
        onFrame: (FloatArray) -> Unit,
        onStart: () -> Unit = {},
        /** Each sentence as it starts to be heard, so the words on screen keep pace with the voice. */
        onSentence: (String) -> Unit = {},
    ): Boolean = coroutineScope {
        val made = produce(Dispatchers.Default, capacity = 1) { for (s in sentences) make(s)?.let { send(s to it) } }
        val firstMade = made.receiveCatching().getOrNull() ?: return@coroutineScope false
        val first = firstMade.second
        onStart()
        val rate = first.sampleRate
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, rate / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        // Where each sentence starts, in frames, with its levels for the face.
        val parts = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, VoiceTrack>>()
        var written = 0L
        val meter = launch {
            while (true) {
                val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                val part = parts.lastOrNull { it.first <= head }
                onFrame(part?.let { (start, levels) -> levels.at((head - start) * 1000 / rate) } ?: FloatArray(4))
                delay(16)
            }
        }
        try {
            t.play()
            var nextMade: Pair<String, Pcm>? = firstMade
            while (nextMade != null && track === t) {
                val next = nextMade.second
                // Written as the one before it ends (the buffer holds a fifth of a second).
                onSentence(nextMade.first)
                // A sentence from another voice (the computer's voice server went quiet) is made to fit.
                val pcm = VoiceFx.resample(next, rate)
                parts += written to VoiceFx.analyse(pcm)
                withContext(Dispatchers.IO) {
                    var offset = 0
                    while (offset < pcm.samples.size && track === t) {
                        ensureActive()
                        val n = t.write(pcm.samples, offset, pcm.samples.size - offset)
                        if (n <= 0) break
                        offset += n
                    }
                }
                written += pcm.samples.size
                nextMade = made.receiveCatching().getOrNull()
            }
            // Let the last of it play out.
            while (track === t && (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < written && t.playState == AudioTrack.PLAYSTATE_PLAYING) delay(20)
        } finally {
            meter.cancel()
            made.cancel()
            onFrame(FloatArray(4))
            runCatching { t.stop() }
            t.release()
            if (track === t) track = null
        }
        true
    }

    /** Writes the spoken answer in [voice] to [file] as a WAV, for the tablet or a speaker. */
    suspend fun render(text: String, language: Language, voice: VoiceStyle, file: File): Spoken? {
        if (text.isBlank()) return null
        val p = piper(voice)
        if (p != null) piperSpeaks(p, text, language, file)?.let { return it }
        else if (voice.local) local(text, language, file)?.let { return it }
        if (!ready) return null
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

    /** Until when the voice server is left alone after it didn't answer, so answers aren't held up. */
    @Volatile private var serverDownUntil = 0L

    /**
     * The answer from the voice model on your own computer (mlx-audio's OpenAI-style speech
     * endpoint), or null when it isn't set up or doesn't answer; the tablet's voice takes over.
     */
    private suspend fun local(text: String, language: Language, file: File): Spoken? {
        val swedish = when (language) {
            Language.Swedish -> true
            Language.English -> false
            Language.Auto -> Confirmation.looksSwedish(text)
        }
        val pcm = localPcm(text, swedish) ?: return null
        return withContext(Dispatchers.IO) {
            file.writeBytes(VoiceFx.writeWav(pcm))
            Spoken(file, VoiceFx.analyse(pcm))
        }
    }

    /** [text] from the voice model on your own computer, or null when it isn't set up or doesn't answer. */
    private suspend fun localPcm(text: String, swedish: Boolean): Pcm? {
        val s = settings()
        val url = s.voiceServerUrlOrGuess().trimEnd('/')
        if (url.isEmpty() || android.os.SystemClock.elapsedRealtime() < serverDownUntil) return null
        return withContext(Dispatchers.IO) {
            val made = runCatching {
                val body = kotlinx.serialization.json.buildJsonObject {
                    put("model", kotlinx.serialization.json.JsonPrimitive(s.voiceServerModel.trim().ifBlank { app.homeydash.agent.DEFAULT_VOICE_MODEL }))
                    put("input", kotlinx.serialization.json.JsonPrimitive(text))
                    s.voiceServerVoice.trim().takeIf { it.isNotEmpty() }?.let { put("voice", kotlinx.serialization.json.JsonPrimitive(it)) }
                    put("lang_code", kotlinx.serialization.json.JsonPrimitive(if (swedish) "sv" else "en"))
                    put("speed", kotlinx.serialization.json.JsonPrimitive(pace()))
                    put("response_format", kotlinx.serialization.json.JsonPrimitive("wav"))
                }.toString()
                val request = okhttp3.Request.Builder()
                    .url("$url/v1/audio/speech")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                val bytes = http.newCall(request).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null } ?: return@runCatching null
                VoiceFx.readWav(bytes)?.takeIf { it.samples.isNotEmpty() }
            }.getOrNull()
            if (made == null) serverDownUntil = android.os.SystemClock.elapsedRealtime() + SERVER_RETRY_MS
            made
        }
    }

    /** The answer in TARS's or Alma's voice, made on the tablet; null until it is downloaded (and for English in Alma's). */
    private suspend fun piperSpeaks(voice: PiperVoice, text: String, language: Language, file: File): Spoken? {
        val swedish = when (language) {
            Language.Swedish -> true
            Language.English -> false
            Language.Auto -> Confirmation.looksSwedish(text)
        }
        val pcm = voice.speak(text, swedish, pace()) ?: return null
        return withContext(Dispatchers.IO) {
            file.writeBytes(VoiceFx.writeWav(pcm))
            Spoken(file, VoiceFx.analyse(pcm))
        }
    }

    fun shutdown() = tts.shutdown()

    private companion object {
        val SWEDISH: Locale = Locale.forLanguageTag("sv-SE")
        const val SERVER_RETRY_MS = 120_000L
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
