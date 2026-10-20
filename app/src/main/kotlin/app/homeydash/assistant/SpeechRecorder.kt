package app.homeydash.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import app.homeydash.agent.Endpointer
import app.homeydash.agent.Pcm
import app.homeydash.agent.VoiceFx
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * Records one question from the microphone until the speaker stops, for Whisper or OpenAI to
 * turn into text. Nothing is kept: the recording only lives in memory until it is sent.
 * Silero VAD (sherpa-onnx, in the APK) says which moments are speech, so a fan, the TV or the
 * dishwasher neither keeps the question going nor starts one; without it the loudness does.
 */
class SpeechRecorder(context: Context) {
    private val assets = context.applicationContext.assets
    @Volatile private var generation = 0
    /** The number of the pause going on now, or -1 while someone is talking (see [Endpointer.pauses]). */
    @Volatile var pausedAt = -1
        private set
    private var reader: Thread? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var endpointer: Endpointer? = null
    private var vad: Vad? = null
    /** How long each pause that turned out to be a breath lasted in the last question, in ms. */
    @Volatile var pauseLengths: List<Int> = emptyList()
        private set

    /**
     * After the pause numbered [pause] was judged (see [Endpointer.waitFor]): how much quiet in
     * all ends the question, from a short wait when it read as finished to a long one when more
     * seemed to be coming.
     */
    fun waitFor(pause: Int, ms: Long) {
        endpointer?.waitFor(pause, ms)
    }

    /**
     * Starts recording. [onLevel] gets the loudness (0..1) as it goes; [onPause] gets the
     * question so far each time the speaker pauses, with the pause's number, whether a held
     * "ööhh" came just before it and the speech up to the pause as samples (for the turn
     * judge), while recording goes on; [onDone] gets the whole question as a WAV file once
     * the quiet has lasted, or null when nothing was said. [start] is sound already heard of
     * the question, put before what the microphone hears. Callbacks come on the main thread.
     */
    fun record(
        gain: Float,
        start: ShortArray? = null,
        onLevel: (Float) -> Unit,
        onPause: (wav: ByteArray, pause: Int, held: Boolean, turn: ShortArray) -> Unit,
        onDone: (ByteArray?) -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
        val gen = generation
        reader = thread(name = "question-mic") { read(gen, gain, start, onLevel, onPause, onDone, onError) }
    }

    /** The voice-activity model, loaded once; null when it can't be (the loudness decides then). */
    @Synchronized
    private fun gate(): Vad? {
        vad?.let { return it }
        vad = runCatching {
            Vad(
                assetManager = assets,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = "ear/silero_vad.onnx",
                        threshold = SPEECH,
                        minSilenceDuration = 0.25f,
                        minSpeechDuration = 0.1f,
                        windowSize = WINDOW,
                        maxSpeechDuration = 30f,
                    ),
                    sampleRate = RATE,
                    numThreads = 1,
                    provider = "cpu",
                ),
            )
        }.getOrNull()
        return vad
    }

    @SuppressLint("MissingPermission") // The assistant only listens with the microphone permission.
    private fun read(
        gen: Int,
        gain: Float,
        start: ShortArray?,
        onLevel: (Float) -> Unit,
        onPause: (ByteArray, Int, Boolean, ShortArray) -> Unit,
        onDone: (ByteArray?) -> Unit,
        onError: (String) -> Unit,
    ) {
        fun post(block: () -> Unit) = main.post { if (gen == generation) block() }
        val gate = gate()
        val size = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE / 5 * 2)
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        } catch (e: Exception) {
            post { onError("The microphone isn't available: ${e.message}") }
            return
        }
        val pcm = ByteArrayOutputStream()
        val endpointer = Endpointer(RATE, silenceMs = PAUSE_MS)
        this.endpointer = endpointer
        pausedAt = -1
        pauseLengths = emptyList()
        fun samples(): ShortArray {
            val bytes = pcm.toByteArray()
            return ShortArray(bytes.size / 2) { i -> ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }
        }
        fun wav(): ByteArray = VoiceFx.writeWav(Pcm(RATE, samples()))
        /** The speech up to the pause, at most the last [TURN_SECONDS], for the turn judge. */
        fun turn(): ShortArray {
            val all = samples()
            val end = (endpointer.speechEndMs / 1000.0 * RATE).toInt().coerceIn(0, all.size)
            val from = maxOf(0, end - RATE * TURN_SECONDS)
            return all.copyOfRange(from, end)
        }
        // The voice-activity model takes 32 ms windows; the microphone gives bigger pieces.
        val window = FloatArray(WINDOW)
        var filled = 0
        var speechProb = 0f
        fun speech(buf: ShortArray, n: Int): Boolean? {
            val g = gate ?: return null
            var top = 0f
            for (i in 0 until n) {
                window[filled++] = buf[i] / 32768f
                if (filled == WINDOW) {
                    speechProb = g.compute(window)
                    filled = 0
                }
                if (speechProb > top) top = speechProb
            }
            return top >= SPEECH
        }
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                post { onError("The microphone isn't available.") }
                return
            }
            runCatching { record.setPreferredMicrophoneFieldDimension(-1f) }
            record.startRecording()
            val buf = ShortArray(RATE / 20)
            // What was already said goes first, so the question is heard from its start.
            start?.toList()?.chunked(buf.size)?.forEach { part ->
                part.forEach { v ->
                    pcm.write(v.toInt() and 0xff)
                    pcm.write((v.toInt() shr 8) and 0xff)
                }
                val p = part.toShortArray()
                endpointer.feed(p, part.size, speech(p, part.size))
            }
            while (gen == generation) {
                val n = record.read(buf, 0, buf.size)
                if (n < 0) {
                    post { onError("The microphone stopped.") }
                    return
                }
                if (n == 0) continue
                if (gain != 1f) {
                    for (i in 0 until n) buf[i] = (buf[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
                }
                for (i in 0 until n) {
                    pcm.write(buf[i].toInt() and 0xff)
                    pcm.write((buf[i].toInt() shr 8) and 0xff)
                }
                val before = endpointer.pauses
                val state = endpointer.feed(buf, n, speech(buf, n))
                val level = endpointer.level
                post { onLevel(level) }
                if (state == Endpointer.State.Paused && endpointer.pauses != before) {
                    val pause = endpointer.pauses
                    val held = endpointer.held
                    val sofar = wav()
                    val spoken = turn()
                    pausedAt = pause
                    post { onPause(sofar, pause, held, spoken) }
                } else if (state == Endpointer.State.Speaking) {
                    pausedAt = -1
                }
                when (state) {
                    Endpointer.State.Done -> {
                        val all = wav()
                        pauseLengths = endpointer.pauseLengths.toList()
                        post { onLevel(0f); onDone(all) }
                        return
                    }
                    Endpointer.State.NoSpeech -> {
                        post { onLevel(0f); onDone(null) }
                        return
                    }
                    else -> {}
                }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    /** Stops recording without a result, and frees the microphone. */
    fun stop() {
        generation++
        val r = reader
        reader = null
        if (r != null && r !== Thread.currentThread()) runCatching { r.join(500) }
    }

    companion object {
        const val RATE = 16000
        /** The quiet that counts as a pause worth judging; the judge then says how much more ends the question. */
        const val PAUSE_MS = 300
        /** Silero VAD's window: 32 ms. */
        private const val WINDOW = 512
        private const val SPEECH = 0.5f
        /** How much of the question the turn judge hears. */
        private const val TURN_SECONDS = 10
    }
}
