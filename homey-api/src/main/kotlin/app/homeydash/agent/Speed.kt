package app.homeydash.agent

import java.util.Locale

/**
 * How fast one answer from the AI came: [firstMs] from sending until the first word (the time
 * it spent reading the prompt, plus the network), [totalMs] until the last. Token counts come
 * from the server when it says ([counted]), otherwise they are guessed from the text.
 */
data class ChatStats(
    val totalMs: Long,
    val firstMs: Long? = null,
    /** Tokens of the prompt it read; null when the server doesn't say. */
    val promptTokens: Int? = null,
    /** Of those, how many it already had worked through from last time (prefix cache). */
    val cachedTokens: Int? = null,
    val outputTokens: Int = 0,
    val counted: Boolean = false,
) {
    /** Tokens written per second, after the first one; null when too few to tell. */
    val tokensPerSecond: Double?
        get() {
            val writing = totalMs - (firstMs ?: return null)
            if (outputTokens < 2 || writing <= 0) return null
            return (outputTokens - 1) * 1000.0 / writing
        }

    /** Prompt tokens read per second before the first word, when the server says how many were new. */
    val readPerSecond: Double?
        get() {
            val read = (promptTokens ?: return null) - (cachedTokens ?: 0)
            val ms = firstMs ?: return null
            if (read <= 0 || ms <= 0) return null
            return read * 1000.0 / ms
        }

    /** "first word 0.8 s · 41 tokens at 38 tok/s · read 3 120 tokens (3 000 cached)". */
    fun text(): String = buildList {
        firstMs?.let { add("first word ${Speed.seconds(it)}") }
        val tps = tokensPerSecond
        add(
            (if (counted) "" else "~") + "$outputTokens tokens" +
                (tps?.let { " at ${Speed.number(it)} tok/s" } ?: "") + " in ${Speed.seconds(totalMs)}",
        )
        promptTokens?.let { p ->
            add(
                "read ${Speed.number(p.toDouble())} tokens" +
                    (cachedTokens?.let { " (${Speed.number(it.toDouble())} cached)" } ?: "") +
                    (readPerSecond?.let { " at ${Speed.number(it)} tok/s" } ?: ""),
            )
        }
    }.joinToString(" · ")
}

/**
 * The time each part of one spoken question took, from when the person stopped talking to when
 * the answer was heard: speech to text, each round of the AI, and the voice. Times are in
 * milliseconds; a part that didn't happen (typed, or answered without the AI) is null.
 */
data class Speed(
    /** From the end of speech (the pause the recorder noticed) until the words were written. */
    val hearMs: Long? = null,
    /** The quiet the recorder waits for before it counts a pause, already gone when [hearMs] starts. */
    val pauseMs: Long? = null,
    /** Which AI answered, like "vMLX Qwen3.5-9B". */
    val brain: String? = null,
    val rounds: List<ChatStats> = emptyList(),
    /** From the question being sent to the AI until its first sentence was ready to be spoken. */
    val sentenceMs: Long? = null,
    /** From the question being sent to the AI until the first sound of the answer. */
    val voiceMs: Long? = null,
    /** That first sound was a "hmm" while it thought, not the answer itself. */
    val filler: Boolean = false,
    /** From the end of speech until the first sound of the answer (what it feels like). */
    val replyMs: Long? = null,
) {
    /** One line for under the answer, like "Heard 0.4 s · AI first word 0.9 s, 42 tok/s · voice 1.3 s · reply 1.8 s after you stopped". */
    fun short(): String = buildList {
        hearMs?.let { add("heard ${seconds(it)}") }
        rounds.firstOrNull()?.let { r ->
            add("AI first word " + (r.firstMs?.let(::seconds) ?: "?") + (r.tokensPerSecond?.let { ", ${number(it)} tok/s" } ?: ""))
        }
        if (rounds.size > 1) add("${rounds.size} AI rounds ${seconds(rounds.sumOf { it.totalMs })}")
        voiceMs?.let { add("voice ${seconds(it)}" + if (filler) " (a hmm)" else "") }
        replyMs?.let { add("reply ${seconds(it)} after you stopped") }
    }.joinToString(" · ").replaceFirstChar { it.uppercase() }

    /** Everything, for the logs. */
    fun long(): String = buildString {
        hearMs?.let { append("speech to text ").append(seconds(it)) }
        pauseMs?.let { append(" (after ").append(seconds(it)).append(" of quiet to notice the pause)") }
        if (hearMs != null) append("\n")
        brain?.let { append("AI: ").append(it).append("\n") }
        rounds.forEachIndexed { i, r -> append("round ").append(i + 1).append(": ").append(r.text()).append("\n") }
        if (rounds.size > 1) append("all AI rounds ").append(seconds(rounds.sumOf { it.totalMs })).append("\n")
        sentenceMs?.let { append("first sentence ready to speak ").append(seconds(it)).append(" after asking the AI\n") }
        voiceMs?.let { append("first sound ").append(seconds(it)).append(" after asking the AI").append(if (filler) " (a thinking sound)" else "").append("\n") }
        replyMs?.let { append("answer heard ").append(seconds(it)).append(" after the end of speech").append(pauseMs?.let { p -> " (+${seconds(p)} pause)" } ?: "").append("\n") }
    }.trimEnd()

    companion object {
        fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.1f s", ms / 1000.0)

        fun number(v: Double): String = when {
            v >= 1000 -> String.format(Locale.ROOT, "%,d", v.toLong()).replace(',', ' ')
            v >= 10 -> String.format(Locale.ROOT, "%.0f", v)
            else -> String.format(Locale.ROOT, "%.1f", v)
        }
    }
}
