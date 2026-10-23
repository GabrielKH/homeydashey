package app.homeydash.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The words the assistant listens for, kept apart from the rules that use them. [CallCheck]'s
 * rules ("goodnight is the goodnight tool", "a question changes no list") stay in code; what
 * counts as saying goodnight, music or "it's finished" is data: the built-in words from
 * agent/cues.json, plus words the owner taught by voice (kept in the know-how book, see
 * [KnowHowData.cues]), so a new way of saying something needs no new app.
 */
object Cues {
    /**
     * One kind of thing said. [words] are alternatives (regex pieces for the built-in ones),
     * whole words when [edge] is "word", anywhere when "part"; a [pattern] is used as it is.
     */
    @Serializable
    data class Cue(
        val id: String,
        val about: String = "",
        val edge: String = "part",
        val words: List<String> = emptyList(),
        val pattern: String? = null,
    )

    @Serializable
    data class Data(
        val about: String = "",
        val cues: List<Cue> = emptyList(),
        val translations: List<List<String>> = emptyList(),
        val phrases: List<List<String>> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** The built-in words, read once from the app. */
    val builtIn: Data by lazy {
        val text = Cues::class.java.classLoader?.getResourceAsStream("agent/cues.json")?.bufferedReader()?.use { it.readText() }
            ?: error("Missing agent/cues.json")
        json.decodeFromString<Data>(text)
    }

    private val byId: Map<String, Cue> by lazy { builtIn.cues.associateBy { it.id } }

    @Volatile private var owner: Map<String, List<String>> = emptyMap()
    @Volatile private var ownerTranslations: List<List<String>> = emptyList()
    private val compiled = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    /** The cues the owner can add words to, for the teach_words tool: id and what it is. */
    fun kinds(): List<Pair<String, String>> = builtIn.cues.filterNot { it.id.startsWith("needs_") }.map { it.id to it.about } +
        builtIn.cues.filter { it.id.startsWith("needs_") }.map { it.id to it.about }

    fun exists(id: String): Boolean = id in byId

    /** Words the owner taught, per cue id, and their own word pairs; the agent sets these before each question. */
    fun useOwner(words: Map<String, List<String>>, translations: List<List<String>> = emptyList()) {
        if (words == owner && translations == ownerTranslations) return
        owner = words
        ownerTranslations = translations
        compiled.clear()
        Translate.reset()
    }

    fun translations(): List<List<String>> = builtIn.translations + ownerTranslations

    fun phrases(): List<List<String>> = builtIn.phrases

    /** What cue [id] matches in normalized text: the built-in words and the owner's. */
    fun regex(id: String): Regex = compiled.getOrPut(id) {
        val cue = byId[id] ?: error("No cue $id")
        val taught = owner[id].orEmpty().map { NameMatch.normalize(it) }.filter { it.isNotBlank() }.map(Regex::escape)
        val base = cue.pattern ?: when (cue.edge) {
            "word" -> """(^| )(${cue.words.joinToString("|")})( |$)"""
            else -> """(${cue.words.joinToString("|")})"""
        }
        if (taught.isEmpty()) Regex(base) else Regex("""(?:$base)|(?:(^| )(${taught.joinToString("|")})( |$))""")
    }
}
