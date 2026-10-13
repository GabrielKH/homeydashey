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
    private var reader: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    /**
     * Starts recording. [onLevel] gets the loudness (0..1) as it goes; [onDone] gets the
     * question as a WAV file, or null when nothing was said. Callbacks come on the main thread.
     */
    fun record(gain: Float, onLevel: (Float) -> Unit, onDone: (ByteArray?) -> Unit, onError: (String) -> Unit) {
        stop()
        val gen = generation
        reader = thread(name = "question-mic") { read(gen, gain, onLevel, onDone, onError) }
    }

    @SuppressLint("MissingPermission") // The assistant only listens with the microphone permission.
    private fun read(gen: Int, gain: Float, onLevel: (Float) -> Unit, onDone: (ByteArray?) -> Unit, onError: (String) -> Unit) {
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
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                post { onError("The microphone isn't available.") }
                return
            }
            runCatching { record.setPreferredMicrophoneFieldDimension(-1f) }
            record.startRecording()
            val buf = ShortArray(RATE / 20)
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
                val state = endpointer.feed(buf, n)
                val level = endpointer.level
                post { onLevel(level) }
                when (state) {
                    Endpointer.State.Done -> {
                        val bytes = pcm.toByteArray()
                        val samples = ShortArray(bytes.size / 2) { i -> ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }
                        val wav = VoiceFx.writeWav(Pcm(RATE, samples))
                        post { onLevel(0f); onDone(wav) }
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
