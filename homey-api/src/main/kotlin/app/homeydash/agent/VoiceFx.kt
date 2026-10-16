package app.homeydash.agent

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** How the assistant looks while it listens, thinks and talks. */
enum class FaceStyle(val label: String, val detail: String) {
    Orb("Glass orb", "Your accent and wallpaper colours swirl inside a glass ball."),
    Ribbon("Ribbon", "Three see-through waves that rise with the voice."),
    Pills("Pills", "Five calm, rounded bars."),
    Scanner("Scanner", "KITT's voice box from Knight Rider, and its side-to-side scanner while listening."),
    Lens("Lens", "A single glowing eye, like HAL."),
    Rings("Rings", "Turning arcs around a bright core, like JARVIS."),
}

/** How the assistant sounds: the tablet's own voice, changed by pitch, speed and a filter. */
enum class VoiceStyle(val label: String, val detail: String, val pitch: Float, val rate: Float, val local: Boolean = false) {
    Natural("Natural", "The tablet's own voice, clearest from across the room.", 1f, 1f),
    Cruiser("Cruiser", "Lower, calm and a bit quick, with a metallic shimmer, like KITT.", 0.82f, 1.08f),
    Robot("Robot", "A buzzing 70s android voice.", 0.9f, 1f),
    Calm("Calm lens", "Slow, low and even, with a little echo, like HAL.", 0.78f, 0.9f),
    Warm(
        "Warm",
        "Soft, warm and a little playful, in the spirit of Samantha in the film Her. Spoken by a voice model on your own " +
            "computer (mlx-audio); while it isn't running, by the tablet's own voice, a touch lower and slower.",
        0.95f,
        0.94f,
        local = true,
    ),
    Tars(
        "TARS",
        "TARS from Interstellar: the voice the TARS-AI fan project trained on the film, spoken on the tablet " +
            "(downloaded once, about 60 MB). He reads Swedish with his own accent.",
        0.8f,
        0.98f,
        local = true,
    ),
}

/** 16-bit mono speech, as Android text-to-speech writes it. */
class Pcm(val sampleRate: Int, val samples: ShortArray) {
    val durationMs: Long get() = samples.size * 1000L / sampleRate.coerceAtLeast(1)
}

/** How loud the voice is, and its low, middle and high parts, for each 1/60 s of an answer. */
class VoiceTrack(val level: FloatArray, val low: FloatArray, val mid: FloatArray, val high: FloatArray, val frameMs: Float) {
    val durationMs: Long get() = (level.size * frameMs).toLong()

    /** The frame playing [ms] into the answer: level, low, mid, high (all 0..1). */
    fun at(ms: Long): FloatArray {
        val i = (ms / frameMs).toInt()
        if (i < 0 || i >= level.size) return FloatArray(4)
        return floatArrayOf(level[i], low[i], mid[i], high[i])
    }
}

/**
 * An answer cut into sentences, so a voice made on the tablet can start speaking the first
 * while the rest is still being made. Very short pieces ("Done.") ride along with the next.
 */
object Sentences {
    private val END = Regex("""(?<=[.!?…:;])\s+""")

    fun split(text: String, shortest: Int = 24): List<String> {
        val parts = text.trim().split(END).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var carry = ""
        for (p in parts) {
            val joined = if (carry.isEmpty()) p else "$carry $p"
            if (joined.length < shortest) carry = joined else { out += joined; carry = "" }
        }
        if (carry.isNotEmpty()) {
            if (out.isEmpty()) out += carry else out[out.lastIndex] = out.last() + " " + carry
        }
        return out
    }
}

/** Reads and writes WAV files, gives voices their character, and measures them for the face. */
object VoiceFx {
    const val FPS = 60

    fun readWav(bytes: ByteArray): Pcm? {
        if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var rate = 0
        var channels = 1
        var bits = 16
        var float = false
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    // Plain 16-bit PCM, or 32-bit float as some voice servers write.
                    float = when (buf.getShort(body).toInt()) {
                        1 -> false
                        3 -> true
                        else -> return null
                    }
                    channels = buf.getShort(body + 2).toInt()
                    rate = buf.getInt(body + 4)
                    bits = buf.getShort(body + 14).toInt()
                }
                "data" -> {
                    if (rate == 0 || channels < 1 || (if (float) bits != 32 else bits != 16)) return null
                    // Some engines write a placeholder size while streaming; use what is there.
                    val end = if (size <= 0 || body + size > bytes.size) bytes.size else body + size
                    val width = bits / 8
                    val frames = (end - body) / (width * channels)
                    val out = ShortArray(frames) { i ->
                        if (float) (buf.getFloat(body + i * width * channels).coerceIn(-1f, 1f) * 32767).toInt().toShort()
                        else buf.getShort(body + i * width * channels)
                    }
                    return Pcm(rate, out)
                }
            }
            if (size < 0) return null
            pos = body + size + (size and 1)
        }
        return null
    }

    fun writeWav(pcm: Pcm): ByteArray {
        val data = pcm.samples.size * 2
        val out = ByteArrayOutputStream(44 + data)
        val head = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        head.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        head.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(pcm.sampleRate).putInt(pcm.sampleRate * 2).putShort(2).putShort(16)
        head.put("data".toByteArray()).putInt(data)
        out.write(head.array())
        val body = ByteBuffer.allocate(data).order(ByteOrder.LITTLE_ENDIAN)
        pcm.samples.forEach { body.putShort(it) }
        out.write(body.array())
        return out.toByteArray()
    }

    /** Gives the voice its character; pitch and speed are set on the speech engine itself. */
    fun apply(style: VoiceStyle, pcm: Pcm): Pcm {
        if (style == VoiceStyle.Natural || style == VoiceStyle.Warm || style == VoiceStyle.Tars) return pcm
        val rate = pcm.sampleRate
        val x = FloatArray(pcm.samples.size) { pcm.samples[it] / 32768f }
        val y = when (style) {
            VoiceStyle.Natural, VoiceStyle.Warm, VoiceStyle.Tars -> x
            // A short comb filter: a faint metallic ring on top of the voice.
            VoiceStyle.Cruiser -> {
                val d = (rate * 0.0025).toInt().coerceAtLeast(1)
                FloatArray(x.size) { i -> x[i] + 0.4f * (if (i >= d) x[i - d] else 0f) }
            }
            // Ring modulation, half wet so the words stay clear.
            VoiceStyle.Robot -> FloatArray(x.size) { i ->
                val m = sin(2 * PI * 55.0 * i / rate).toFloat()
                0.35f * x[i] + 0.9f * x[i] * m
            }
            // Three feedback echoes, mixed low: a small, hard room.
            VoiceStyle.Calm -> {
                val wet = FloatArray(x.size)
                listOf(0.029, 0.037, 0.043).forEach { s ->
                    val d = (rate * s).toInt().coerceAtLeast(1)
                    val comb = FloatArray(x.size)
                    for (i in x.indices) comb[i] = x[i] + if (i >= d) 0.45f * comb[i - d] else 0f
                    for (i in x.indices) wet[i] += comb[i] / 3f
                }
                FloatArray(x.size) { i -> 0.8f * x[i] + 0.3f * wet[i] }
            }
        }
        return Pcm(rate, normalize(y, peakOf(x)))
    }

    private fun peakOf(x: FloatArray): Float = x.maxOfOrNull { abs(it) }?.coerceAtLeast(1e-4f) ?: 1f

    /** Back to 16-bit at the original loudness, without clipping. */
    private fun normalize(y: FloatArray, targetPeak: Float): ShortArray {
        val peak = peakOf(y)
        val gain = (targetPeak / peak).coerceAtMost(4f)
        return ShortArray(y.size) { i -> (y[i] * gain * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
    }

    /** Measures the voice, 60 times a second, for the face to follow. */
    fun analyse(pcm: Pcm): VoiceTrack {
        val rate = pcm.sampleRate
        val per = (rate / FPS).coerceAtLeast(1)
        val n = (pcm.samples.size + per - 1) / per
        val level = FloatArray(n)
        val low = FloatArray(n)
        val mid = FloatArray(n)
        val high = FloatArray(n)
        // One-pole low-passes at about 300 Hz and 2 kHz split the voice in three.
        val a1 = exp(-2 * PI * 300.0 / rate).toFloat()
        val a2 = exp(-2 * PI * 2000.0 / rate).toFloat()
        var lp1 = 0f
        var lp2 = 0f
        for (f in 0 until n) {
            var s = 0f
            var sl = 0f
            var sm = 0f
            var sh = 0f
            val start = f * per
            val end = minOf(start + per, pcm.samples.size)
            for (i in start until end) {
                val v = pcm.samples[i] / 32768f
                lp1 = (1 - a1) * v + a1 * lp1
                lp2 = (1 - a2) * v + a2 * lp2
                s += v * v
                sl += lp1 * lp1
                sm += (lp2 - lp1) * (lp2 - lp1)
                sh += (v - lp2) * (v - lp2)
            }
            val count = (end - start).coerceAtLeast(1)
            level[f] = sqrt(s / count)
            low[f] = sqrt(sl / count)
            mid[f] = sqrt(sm / count)
            high[f] = sqrt(sh / count)
        }
        listOf(level, low, mid, high).forEach(::scale)
        return VoiceTrack(level, low, mid, high, per * 1000f / rate)
    }

    /** 0..1 against the loud parts of this answer, eased so quiet syllables still show. */
    private fun scale(v: FloatArray) {
        val sorted = v.sorted()
        val ref = (sorted.getOrNull((sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)) ?: 0f).coerceAtLeast(1e-4f)
        for (i in v.indices) v[i] = sqrt((v[i] / ref).coerceIn(0f, 1f))
    }
}
