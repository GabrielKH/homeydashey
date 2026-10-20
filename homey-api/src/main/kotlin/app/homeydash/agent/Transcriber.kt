package app.homeydash.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/** Who turns a recorded question into text, in the order they're tried. */
enum class Hearer(val label: String) {
    Whisper("Whisper on your computer"),
    OpenAI("OpenAI"),
}

/** What was heard, and by whom. */
data class Heard(val text: String, val by: Hearer)

/**
 * Turns a recorded question into text with Whisper on the user's own computer (whisper.cpp's
 * server) and/or OpenAI, instead of Android's built-in speech recognition.
 */
class Transcriber(http: OkHttpClient, private val openAiUrl: String = "https://api.openai.com/v1") {
    private val localHttp = http.newBuilder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val cloudHttp = http.newBuilder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    /**
     * Tries each hearer that [settings] turns on, in order. [language] is "sv", "en" or null to
     * let the model tell; [hint] lists words it should expect (names of rooms and devices).
     */
    suspend fun transcribe(settings: AgentSettings, wav: ByteArray, language: String?, hint: String): Heard {
        val errors = mutableListOf<String>()
        for (hearer in settings.hearers()) {
            try {
                val text = when (hearer) {
                    Hearer.Whisper -> whisper(settings.whisperUrlOrGuess(), wav, language, hint)
                    Hearer.OpenAI -> openAi(settings.openAiSpeechKeyOrBrain(), wav, language, hint)
                }
                return Heard(cleanUp(text), hearer)
            } catch (e: IOException) {
                errors += "${hearer.label}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IOException(errors.joinToString("; ").ifEmpty { "No speech recognition is set up." })
    }

    /** Sends half a second of silence to [hearer]; null when it answered, else what went wrong. */
    suspend fun test(settings: AgentSettings, hearer: Hearer): String? {
        val silence = VoiceFx.writeWav(Pcm(16000, ShortArray(8000)))
        return try {
            when (hearer) {
                Hearer.Whisper -> whisper(settings.whisperUrlOrGuess(), silence, null, "")
                Hearer.OpenAI -> openAi(settings.openAiSpeechKeyOrBrain(), silence, null, "")
            }
            null
        } catch (e: IOException) {
            e.message ?: e.javaClass.simpleName
        }
    }

    private suspend fun whisper(baseUrl: String, wav: ByteArray, language: String?, hint: String): String {
        if (baseUrl.isBlank()) throw IOException("no address set")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "question.wav", wav.toRequestBody(WAV))
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            // No retries at higher temperatures: each one decodes the whole question again.
            .addFormDataPart("temperature_inc", "0")
            .addFormDataPart("language", language ?: "auto")
            .apply { if (hint.isNotBlank()) addFormDataPart("prompt", hint) }
            .build()
        return post(localHttp, Request.Builder().url(baseUrl.trimEnd('/') + "/inference").post(body).build())
    }

    private suspend fun openAi(key: String, wav: ByteArray, language: String?, hint: String): String {
        if (key.isBlank()) throw IOException("no API key")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "question.wav", wav.toRequestBody(WAV))
            .addFormDataPart("model", OPENAI_MODEL)
            .addFormDataPart("response_format", "json")
            .apply {
                language?.let { addFormDataPart("language", it) }
                if (hint.isNotBlank()) addFormDataPart("prompt", hint)
            }
            .build()
        val request = Request.Builder().url("$openAiUrl/audio/transcriptions")
            .header("Authorization", "Bearer $key").post(body).build()
        return post(cloudHttp, request)
    }

    private suspend fun post(http: OkHttpClient, request: Request): String = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} ${text.take(160)}")
            parseText(text) ?: throw IOException("no text in the answer")
        }
    }

    companion object {
        const val OPENAI_MODEL = "gpt-4o-mini-transcribe"
        /** whisper.cpp's server listens here unless told otherwise. */
        const val WHISPER_PORT = 8080
        private val WAV = "audio/wav".toMediaType()
        private val Lenient = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parseText(body: String): String? =
            runCatching { Lenient.parseToJsonElement(body).jsonObject["text"]?.jsonPrimitive?.contentOrNull }.getOrNull()

        /**
         * Whisper writes what it thinks it heard in silence ("Tack för att du tittade!",
         * "[BLANK_AUDIO]") and sometimes brackets. Those mean nothing was said.
         */
        fun cleanUp(text: String): String {
            val t = text.replace(Regex("""\[[^]]*]|\([^)]*\)"""), " ").replace(Regex("\\s+"), " ").trim()
            val plain = NameMatch.normalize(t)
            return if (plain.isEmpty() || HALLUCINATIONS.any { plain == it }) "" else t
        }

        private val HALLUCINATIONS = setOf(
            "tack for att du tittade", "tack for att ni tittade", "tack for tittandet", "textning",
            "thank you", "thanks for watching", "thank you for watching", "you", "bye",
        )

        /** Where Whisper probably runs: the Ollama computer's address on [WHISPER_PORT]. */
        fun guessUrl(local: Connection?): String {
            val host = local?.baseUrl?.let { runCatching { URI(it).host }.getOrNull() } ?: return ""
            return "http://$host:$WHISPER_PORT"
        }
    }
}

/**
 * Hears when a question starts and pauses from the microphone's loudness. A pause isn't the
 * end yet: the recording so far is checked ([TurnEnd]) and the question is sent the moment it
 * reads as finished, while a "so I want…" keeps it listening until [patienceMs] of quiet.
 * Feed it 16-bit frames in order.
 */
class Endpointer(
    private val sampleRate: Int,
    private val silenceMs: Int = 700,
    private val noSpeechMs: Int = 7000,
    private val maxMs: Int = 30000,
    private val patienceMs: Int = 3000,
) {
    enum class State { Waiting, Speaking, Paused, Done, NoSpeech }

    var state = State.Waiting
        private set
    /** Loudness of the last frame, 0..1, for the face. */
    var level = 0f
        private set
    /** Counts the pauses, so a check of an older pause can tell it is out of date. */
    var pauses = 0
        private set
    /**
     * The sound just before the pause was long and even: a drawn-out "ööhh" or "mmm" rather
     * than words, whose loudness rises and falls with every syllable.
     */
    var held = false
        private set
    /** How long each pause that turned out to be a breath lasted, in ms, for the person's pause habit. */
    val pauseLengths = ArrayList<Int>()
    /** How far into the recording the last speech ended, in ms, once paused; the question so far is up to here. */
    var speechEndMs = 0.0
        private set
    /** The quiet that ends the question after the pause going on now; [waitFor] sets it per pause. */
    private var patienceNow = patienceMs

    private var elapsedMs = 0.0
    private var speechMs = 0.0
    private var quietMs = 0.0
    private var floor = -1.0
    /** Loudness of each frame of the sound going on now. */
    private val run = ArrayList<Double>()
    private var runMs = 0.0

    /**
     * After the pause numbered [pause] (see [pauses]) has been judged, how much quiet in all
     * ends the question: a short wait when what was said reads as finished, a long one when
     * more seems to be coming. Ignored once a later pause has begun.
     */
    fun waitFor(pause: Int, ms: Long) {
        if (pause == pauses && state == State.Paused) patienceNow = ms.toInt().coerceAtLeast(0)
    }

    /**
     * [speech] is a voice-activity model's word on these samples when there is one; without it
     * the loudness against the room's floor decides.
     */
    fun feed(buf: ShortArray, n: Int, speech: Boolean? = null): State {
        if (n <= 0 || state == State.Done || state == State.NoSpeech) return state
        var sum = 0.0
        for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
        val rms = sqrt(sum / n)
        val ms = n * 1000.0 / sampleRate
        elapsedMs += ms
        level = (rms / 3000.0).toFloat().coerceIn(0f, 1f)
        // The room's own noise: follows quiet moments quickly and loud ones slowly.
        floor = when {
            floor < 0 -> minOf(rms, MIN_SPEECH_RMS)
            rms < floor -> floor * 0.7 + rms * 0.3
            else -> floor * 0.995 + rms * 0.005
        }
        val loud = speech ?: (rms > maxOf(floor * 2.5, MIN_SPEECH_RMS))
        if (loud) {
            if (quietMs > GAP_MS) { run.clear(); runMs = 0.0 }
            run += rms
            runMs += ms
        }
        when (state) {
            State.Waiting -> {
                speechMs = if (loud) speechMs + ms else (speechMs - ms).coerceAtLeast(0.0)
                if (speechMs >= START_MS) state = State.Speaking
                else if (elapsedMs >= noSpeechMs) state = State.NoSpeech
            }
            State.Speaking -> {
                quietMs = if (loud) 0.0 else quietMs + ms
                if (quietMs >= silenceMs) {
                    held = isHeld()
                    pauses++
                    speechEndMs = elapsedMs - quietMs
                    patienceNow = patienceMs
                    state = State.Paused
                }
            }
            State.Paused -> {
                if (loud) {
                    // Talking again: the pause was a breath, not the end.
                    speechMs += ms
                    if (speechMs >= START_MS) {
                        pauseLengths += quietMs.toInt()
                        state = State.Speaking
                        quietMs = 0.0
                    }
                } else {
                    speechMs = 0.0
                    quietMs += ms
                    if (quietMs >= patienceNow) state = State.Done
                }
            }
            else -> {}
        }
        if (elapsedMs >= maxMs && state != State.NoSpeech) state = if (state == State.Waiting) State.NoSpeech else State.Done
        return state
    }

    /** The last sound lasted a while and its loudness hardly moved. */
    private fun isHeld(): Boolean {
        if (runMs < HELD_MS || run.size < 4) return false
        val tail = run.takeLast(maxOf(4, (HELD_MS / (runMs / run.size)).toInt()))
        val mean = tail.average()
        if (mean <= 0) return false
        val sd = sqrt(tail.sumOf { (it - mean) * (it - mean) } / tail.size)
        return sd / mean < HELD_EVENNESS
    }

    private companion object {
        const val START_MS = 120.0
        const val MIN_SPEECH_RMS = 350.0
        /** Quiet longer than this starts a new sound. */
        const val GAP_MS = 150.0
        const val HELD_MS = 450.0
        /** Words swing far more than this from syllable to syllable; a held "öhh" doesn't. */
        const val HELD_EVENNESS = 0.2
    }
}
