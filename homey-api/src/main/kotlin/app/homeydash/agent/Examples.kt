package app.homeydash.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.ln

/**
 * Worked examples of how something said becomes tool calls, so the local AI copies a shape
 * instead of following a rule. The built-in ones (agent/examples.json) use <placeholders> for
 * names; the home's own ones are learned from turns that went cleanly and kept in the know-how
 * book ([KnowHowData.examples]). Only the few closest to the question go in its note.
 */
object Examples {
    /** One example: what was said, the calls it took (`name {json}`), and an optional note on why. */
    @Serializable
    data class Example(
        val say: String,
        val calls: List<String> = emptyList(),
        val answer: String? = null,
        val at: Long = 0,
    )

    @Serializable
    data class Data(val about: String = "", val examples: List<Example> = emptyList())

    /** At most this many examples go with one question. */
    const val SHOWN = 3
    /** Learned examples kept; the oldest go first. */
    const val KEEP = 150
    /** Below this, an example shares too little with the question to help. */
    private const val MIN_SCORE = 1.5

    private val json = Json { ignoreUnknownKeys = true }
    private val PLACEHOLDER = Regex("<[^>]*>")

    val builtIn: List<Example> by lazy {
        val text = Examples::class.java.classLoader?.getResourceAsStream("agent/examples.json")?.bufferedReader()?.use { it.readText() }
            ?: error("Missing agent/examples.json")
        json.decodeFromString<Data>(text).examples
    }

    private fun words(text: String): Set<String> =
        NameMatch.normalize(PLACEHOLDER.replace(text, " ")).split(' ').filter { it.length >= 2 }.toSet()

    /**
     * The [SHOWN] examples closest to [question], the home's own first when they score the same.
     * The home's [names] (rooms, devices) don't count: "i köket" says where, not how, so
     * "spela P1 i köket" finds the music example, not the one about the kitchen lamp.
     */
    fun find(question: String, learned: List<Example> = emptyList(), limit: Int = SHOWN, names: Collection<String> = emptyList()): List<Example> {
        val all = learned.reversed() + builtIn
        if (all.isEmpty()) return emptyList()
        val named = names.flatMap { words(it) }.filter { it.length >= 3 }.toSet()
        fun isName(w: String) = named.any { n -> w.startsWith(n) && w.length - n.length <= 3 }
        val asked = words(question).map { Translate.variants(it) }.filterNot { v -> v.any(::isName) }.flatten().toSet()
        if (asked.isEmpty()) return emptyList()
        val said = all.map { words(it.say) }
        // Rare words count more: "spela" says more than "i".
        val df = mutableMapOf<String, Int>()
        said.forEach { w -> w.forEach { df[it] = (df[it] ?: 0) + 1 } }
        val n = all.size.toDouble()
        return all.indices.map { i ->
            i to said[i].filter { it in asked }.sumOf { ln(1 + n / (df[it] ?: 1)) }
        }.filter { it.second >= MIN_SCORE }
            .sortedByDescending { it.second }
            .map { all[it.first] }
            .distinctBy { NameMatch.normalize(it.say) }
            .take(limit)
    }

    /** The examples as lines for the question's note; empty when there are none. */
    fun note(examples: List<Example>): String {
        if (examples.isEmpty()) return ""
        return "\nHow similar requests were done (names in <> stand for this home's real ones):\n" + examples.joinToString("\n") { e ->
            val calls = if (e.calls.isEmpty()) "no tool" else e.calls.joinToString("; ") { "CALL $it" }
            "«${e.say}» → $calls" + (e.answer?.let { " ($it)" } ?: "")
        }
    }

    /** [learned] with what was just said and done added, replacing an earlier one for the same words. */
    fun add(learned: List<Example>, say: String, calls: List<String>, at: Long): List<Example> {
        val key = NameMatch.normalize(say)
        if (key.isBlank() || calls.isEmpty()) return learned
        return (learned.filterNot { NameMatch.normalize(it.say) == key } + Example(say.trim(), calls, at = at)).takeLast(KEEP)
    }
}
