package app.homeydash.assistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import app.homeydash.agent.Endpointer
import app.homeydash.agent.Pcm
import app.homeydash.agent.VoiceFx
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * Records one question from the microphone until the speaker stops, for Whisper or OpenAI to
 * turn into text. Nothing is kept: the recording only lives in memory until it is sent.
 */
class SpeechRecorder {
    @Volatile private var generation = 0
    /** The number of the pause going on now, or -1 while someone is talking (see [Endpointer.pauses]). */
    @Volatile var pausedAt = -1
        private set
    private var reader: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    /**
     * Starts recording. [onLevel] gets the loudness (0..1) as it goes; [onPause] gets the
     * question so far each time the speaker pauses, with the pause's number and whether a
     * held "ööhh" came just before it, while recording goes on; [onDone] gets the whole
     * question as a WAV file once the quiet has lasted, or null when nothing was said.
     * [start] is sound already heard of the question, put before what the microphone hears.
     * Callbacks come on the main thread.
     */
    fun record(
        gain: Float,
        start: ShortArray? = null,
        onLevel: (Float) -> Unit,
        onPause: (wav: ByteArray, pause: Int, held: Boolean) -> Unit,
        onDone: (ByteArray?) -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
        val gen = generation
        reader = thread(name = "question-mic") { read(gen, gain, start, onLevel, onPause, onDone, onError) }
    }

    @SuppressLint("MissingPermission") // The assistant only listens with the microphone permission.
    private fun read(
        gen: Int,
        gain: Float,
        start: ShortArray?,
        onLevel: (Float) -> Unit,
        onPause: (ByteArray, Int, Boolean) -> Unit,
        onDone: (ByteArray?) -> Unit,
        onError: (String) -> Unit,
    ) {
        fun post(block: () -> Unit) = main.post { if (gen == generation) block() }
        val size = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE / 5 * 2)
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        } catch (e: Exception) {
            post { onError("The microphone isn't available: ${e.message}") }
            return
        }
        val pcm = ByteArrayOutputStream()
        val endpointer = Endpointer(RATE)
        pausedAt = -1
        fun wav(): ByteArray {
            val bytes = pcm.toByteArray()
            val samples = ShortArray(bytes.size / 2) { i -> ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }
            return VoiceFx.writeWav(Pcm(RATE, samples))
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
                endpointer.feed(part.toShortArray(), part.size)
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
                val state = endpointer.feed(buf, n)
                val level = endpointer.level
                post { onLevel(level) }
                if (state == Endpointer.State.Paused && endpointer.pauses != before) {
                    val pause = endpointer.pauses
                    val held = endpointer.held
                    val sofar = wav()
                    pausedAt = pause
                    post { onPause(sofar, pause, held) }
                } else if (state == Endpointer.State.Speaking) {
                    pausedAt = -1
                }
                when (state) {
                    Endpointer.State.Done -> {
                        val all = wav()
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

    private companion object {
        const val RATE = 16000
    }
}
