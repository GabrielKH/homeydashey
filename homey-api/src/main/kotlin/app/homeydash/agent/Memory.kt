package app.homeydash.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.math.ln

/** How a fact came to be known. */
@Serializable
enum class FactKind(val label: String) {
    /** The user asked for it to be remembered ("kom ihåg att…"). */
    Said("told"),
    /** Squeezed out of a finished conversation by the AI, while nobody was talking. */
    Learned("learned"),
    /** Counted by the app from what it was asked to do, without the AI. */
    Habit("habit"),
    /** Written by the person under People: birthday, likes, dislikes and the like. Never dropped. */
    About("about them"),
}

/**
 * One edge of the memory graph: [subject] [relation] [obj], like "Gabriel" "likes" "P1 in the
 * morning". [who] is the person it belongs to, by id, or [Memory.HOUSE] for the whole house; a
 * person's facts are only ever recalled for that person. [parts] counts the parts of the day a
 * habit happened in.
 */
@Serializable
data class Fact(
    val id: String = UUID.randomUUID().toString(),
    val who: String = Memory.HOUSE,
    val whoName: String = "",
    val subject: String,
    val relation: String,
    val obj: String,
    val kind: FactKind = FactKind.Learned,
    val count: Int = 1,
    val first: Long = 0,
    val last: Long = 0,
    val parts: Map<String, Int> = emptyMap(),
    /** Learned from something only a local AI may see; never recalled for an internet AI. */
    val localOnly: Boolean = false,
)

@Serializable
data class MemoryData(val version: Int = 1, val facts: List<Fact> = emptyList())

/**
 * The assistant's long memory, kept on the tablet and out of the prompt, after Graphify's idea:
 * a small graph of who and what, so a question only gets the few facts it touches instead of
 * every conversation ever had. Finding them is plain word matching on the tablet, which takes
 * well under a millisecond; nothing here asks the AI.
 */
object Memory {
    const val HOUSE = ""
    const val MAX_FACTS = 400
    const val RECALL_LIMIT = 4

    /** Words that say the answer needs something from before: habits, favourites, earlier talks. */
    val CUES = Regex(
        """(?<![\p{L}\d_])(som vanligt|som förra gången|som igår|brukar|vanliga|vanligaste|favorit\w*|förra gången|igår|i förrgår|kommer du ihåg|minns du|vet du vad jag|vad sa jag|vad gillar|""" +
            """usual\w*|like last time|like yesterday|favou?rite\w*|last time|yesterday|do you remember|what did i (say|tell)|what do i like)(?![\p{L}\d_])""",
        RegexOption.IGNORE_CASE,
    )

    /** Words too common to say what a fact is about. */
    private val STOP = setOf(
        "och", "att", "det", "som", "en", "ett", "är", "på", "i", "av", "för", "med", "till", "jag", "du", "vi", "han", "hon", "den", "de",
        "har", "kan", "ska", "vill", "min", "mitt", "mina", "din", "ditt", "vad", "hur", "när", "var", "om", "inte", "lite", "nu", "så",
        "the", "and", "a", "an", "to", "of", "in", "on", "is", "it", "i", "you", "my", "me", "we", "what", "how", "when", "do", "does",
        "can", "please", "some", "that", "this", "for", "with", "be", "are", "was", "at", "about",
        "vanligt", "brukar", "usual", "usually", "favorit", "favourite", "favorite", "kom", "ihåg", "remember", "minns", "spela", "play",
    )

    fun tokens(text: String): Set<String> =
        Routines.normalize(text).split(' ').filter { it.length >= 2 && it !in STOP }.toSet()

    /** Two words match when one starts the other ("lampan", "lampa"; "köket", "kök"). */
    private fun same(a: String, b: String): Boolean =
        a == b || (minOf(a.length, b.length) >= 3 && (a.startsWith(b) || b.startsWith(a)))

    private fun overlap(query: Set<String>, words: Set<String>): Int = query.count { q -> words.any { same(q, it) } }

    private fun node(text: String) = Routines.normalize(text)

    /** How much a fact counts on its own: said often and lately. Told facts start strong. */
    fun strength(f: Fact, now: Long): Double {
        val days = ((now - f.last).coerceAtLeast(0) / 86_400_000.0)
        val base = ln(1.0 + f.count) + when (f.kind) {
            FactKind.About -> 2.0
            FactKind.Said -> 1.5
            else -> 0.0
        }
        return base / (1.0 + days / 30.0)
    }

    /** The facts [asker] may hear: their own and the whole house's, never another person's. */
    fun visible(data: MemoryData, asker: String?, local: Boolean): List<Fact> =
        data.facts.filter { (it.who == HOUSE || it.who == asker) && (local || !it.localOnly) }

    /**
     * The few facts [query] touches, best first: facts whose words match, then their neighbours
     * in the graph (facts that share a person, thing or place with them) at half weight. A
     * blank query, or "everything", gives the strongest facts.
     */
    fun recall(data: MemoryData, query: String, asker: String?, local: Boolean, now: Long, limit: Int = RECALL_LIMIT): List<Fact> {
        val facts = visible(data, asker, local)
        if (facts.isEmpty()) return emptyList()
        val q = tokens(query)
        if (q.isEmpty() || q.any { it in EVERYTHING }) return facts.sortedByDescending { strength(it, now) }.take(limit)
        val score = HashMap<String, Double>()
        for (f in facts) {
            val hits = overlap(q, tokens("${f.subject} ${f.relation} ${f.obj}"))
            if (hits > 0) score[f.id] = hits * 2.0 + strength(f, now)
        }
        if (score.isEmpty()) return emptyList()
        // One step out in the graph: "what do I usually listen to" finds "P1" through "Gabriel plays".
        val hitNodes = facts.filter { it.id in score }.flatMap { listOf(node(it.subject), node(it.obj)) }.toSet()
        for (f in facts) {
            if (f.id in score) continue
            if (node(f.subject) in hitNodes || node(f.obj) in hitNodes) score[f.id] = 0.5 * (1.0 + strength(f, now))
        }
        val byId = facts.associateBy { it.id }
        return score.entries.sortedByDescending { it.value }.take(limit).map { byId.getValue(it.key) }
    }

    private val EVERYTHING = setOf("allt", "everything", "all", "alla")

    /** One fact as a short line for the AI: "Gabriel plays P1 on Kök (12 times, mostly mornings)". */
    fun line(f: Fact): String = buildString {
        append(f.subject).append(' ').append(f.relation).append(' ').append(f.obj)
        val extra = buildList {
            if (f.count > 1) add("${f.count} times")
            f.parts.maxByOrNull { it.value }?.takeIf { f.count >= 3 && it.value * 2 > f.count }?.let { add("mostly ${it.key}") }
        }
        if (extra.isNotEmpty()) append(" (").append(extra.joinToString(", ")).append(')')
    }

    fun partOfDay(t: ZonedDateTime): String = when (t.hour) {
        in 5..9 -> "mornings"
        in 10..16 -> "daytime"
        in 17..21 -> "evenings"
        else -> "nights"
    }

    /**
     * Adds [fact], or strengthens the one it repeats: the same person, subject and relation (and
     * for habits the same object too, so "plays P1" and "plays Spotify" are two habits). A newer
     * learned or told fact replaces the old object ("lives in Göteborg" after "lives in Stockholm").
     * The weakest facts go when there are more than [MAX_FACTS].
     */
    fun merge(data: MemoryData, fact: Fact, now: Long): MemoryData {
        // A person may like many things, and do many things often: those keep one fact per object.
        fun many(f: Fact) = f.kind == FactKind.Habit || f.kind == FactKind.About
        fun key(f: Fact) = listOf(f.who, node(f.subject), node(f.relation)) + if (many(f)) listOf(node(f.obj)) else emptyList()
        val k = key(fact)
        // Told and learned facts are one kind of fact; habits and what people wrote are kept apart.
        fun group(f: Fact) = if (f.kind == FactKind.Said) FactKind.Learned else f.kind
        val i = data.facts.indexOfFirst { group(it) == group(fact) && key(it) == k }
        val facts = data.facts.toMutableList()
        if (i < 0) {
            facts += fact.copy(first = fact.first.takeIf { it > 0 } ?: now, last = now)
        } else {
            val old = facts[i]
            facts[i] = old.copy(
                obj = fact.obj,
                count = old.count + 1,
                last = now,
                parts = (old.parts.keys + fact.parts.keys).associateWith { (old.parts[it] ?: 0) + (fact.parts[it] ?: 0) },
                kind = if (fact.kind == FactKind.Said) FactKind.Said else old.kind,
                whoName = fact.whoName.ifBlank { old.whoName },
                localOnly = old.localOnly || fact.localOnly,
            )
        }
        // What people wrote about themselves stays however much there is; the rest makes room.
        val (about, rest) = facts.partition { it.kind == FactKind.About }
        val kept = if (facts.size <= MAX_FACTS) facts else about + rest.sortedByDescending { strength(it, now) }.take((MAX_FACTS - about.size).coerceAtLeast(0))
        return data.copy(facts = kept)
    }

    /** Removes the facts [what] names (by words); how many went. */
    fun forget(data: MemoryData, what: String, asker: String?): Pair<MemoryData, Int> {
        val q = tokens(what)
        if (q.isEmpty()) return data to 0
        val gone = data.facts.filter { f ->
            (f.who == HOUSE || f.who == asker) && overlap(q, tokens("${f.subject} ${f.relation} ${f.obj}")) == q.size
        }.map { it.id }.toSet()
        return data.copy(facts = data.facts.filterNot { it.id in gone }) to gone.size
    }

    /**
     * The habit a tool call shows, counted without the AI: what was played where, which look or
     * mood, which Flow, which lamp. Null for calls that only look, or that changed nothing.
     */
    fun habit(call: ToolCall, view: HomeView, asker: Asker?, at: ZonedDateTime): Fact? {
        fun arg(k: String) = (call.arguments[k] as? JsonPrimitive)?.content.orEmpty().trim()
        fun name(alias: String) = view.named(alias).trim()
        val (relation, obj) = when (call.name) {
            "play_music" -> "plays" to listOf(arg("query").ifBlank { "music" }, "on " + name(arg("player"))).joinToString(" ")
            "set_scene" -> "sets the lights" to "${arg("look")} in ${arg("room")}"
            "set_mood" -> "turns on the mood" to name(arg("mood"))
            "run_flow" -> "starts the Flow" to arg("name")
            "set_room" -> "sets ${arg("capability")} ${arg("value")}" to "in ${arg("room")}" + arg("device_class").takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            "set_device" -> if (arg("capability") == "onoff") {
                "turns ${if (arg("value") == "true") "on" else "off"}" to name(arg("device"))
            } else return null
            else -> return null
        }
        if (obj.isBlank()) return null
        return Fact(
            who = asker?.id ?: HOUSE,
            whoName = asker?.name.orEmpty(),
            subject = asker?.name ?: "Someone in the house",
            relation = relation,
            obj = obj,
            kind = FactKind.Habit,
            parts = mapOf(partOfDay(at) to 1),
        )
    }

    /**
     * The character and voice [person] picked under People, as one fact a new pick replaces, so
     * the assistant can say who they talk with ("Gabriel talks with TARS").
     */
    fun characterChoice(person: Asker, character: Persona?, voice: VoiceStyle?): Fact = Fact(
        who = person.id,
        whoName = person.name,
        subject = person.name,
        relation = "talks with the character",
        obj = (character?.label ?: "the house's character") + (voice?.let { " in the ${it.label} voice" } ?: ""),
        kind = FactKind.Said,
    )

    /** That [asker] woke [character] by its name, counted like a habit with the part of the day. */
    fun woke(asker: Asker, character: Persona, at: ZonedDateTime): Fact = Fact(
        who = asker.id,
        whoName = asker.name,
        subject = asker.name,
        relation = "wakes",
        obj = character.label,
        kind = FactKind.Habit,
        parts = mapOf(partOfDay(at) to 1),
    )

    /**
     * A finished conversation as plain lines for [digestPrompt]: what the user said (after
     * [label] in a question's note) and what was answered, without notes, tool calls or the
     * app's own nudges. The newest part is kept when it's long.
     */
    fun transcriptOf(turns: List<List<ChatMessage>>, label: String): String {
        val lines = turns.flatMap { messages ->
            messages.mapNotNull { m ->
                val text = m.content.orEmpty()
                when {
                    m.role == "user" && (label in text || !text.trimStart().startsWith("(From the app")) ->
                        text.substringAfterLast(label).trim().takeIf { it.isNotEmpty() }?.let { "User: $it" }
                    m.role == "assistant" -> text.lines().filterNot { it.trimStart().startsWith("CALL ") }.joinToString(" ").trim()
                        .takeIf { it.isNotEmpty() && it != Agent.IGNORE }?.let { "Assistant: $it" }
                    else -> null
                }
            }
        }
        return lines.joinToString("\n").takeLast(MAX_TRANSCRIPT)
    }

    private const val MAX_TRANSCRIPT = 4000

    /**
     * What the AI is asked once a conversation is over, to squeeze it into a few facts. Only
     * user questions and spoken answers go in, never the notes, so it's a few hundred tokens.
     */
    fun digestPrompt(transcript: String, who: Asker?, known: List<Fact>): List<ChatMessage> {
        val person = who?.name ?: "an unknown person"
        val system = "You keep the long memory of a home voice assistant. From a finished conversation with $person, " +
            "write only facts worth remembering for later conversations: preferences, names, plans with a date, routines, " +
            "things about the people or the home. Skip small talk, jokes, one-off commands and anything already known. " +
            "Write at most 5 lines, each exactly: me|house ; subject ; relation ; object\n" +
            "\"me\" is about $person only, \"house\" is for everyone. Short words, in the language spoken. " +
            "Example: me ; $person ; likes ; dimmed warm light when cooking\n" +
            "If nothing is worth keeping, write NONE."
        val knownText = known.takeIf { it.isNotEmpty() }?.joinToString("\n", "Already known:\n", "\n\n") { "- " + line(it) }.orEmpty()
        return listOf(ChatMessage.system(system), ChatMessage.user(knownText + "Conversation:\n" + transcript))
    }

    /** The AI's [digestPrompt] answer as facts; lines that don't fit the shape are dropped. */
    fun parseDigest(text: String, who: Asker?, localOnly: Boolean): List<Fact> =
        text.lines().mapNotNull { raw ->
            val parts = raw.trim().trimStart('-', '*', ' ').split(';').map { it.trim().trim('"') }
            if (parts.size != 4 || parts.any { it.isEmpty() }) return@mapNotNull null
            val scope = parts[0].lowercase()
            if (scope != "me" && scope != "house") return@mapNotNull null
            // A fact about "me" with nobody known is kept for the house.
            val mine = scope == "me" && who != null
            Fact(
                who = if (mine) who!!.id else HOUSE,
                whoName = if (mine) who!!.name else "",
                subject = parts[1].take(60),
                relation = parts[2].take(40),
                obj = parts[3].take(120),
                kind = FactKind.Learned,
                localOnly = localOnly,
            )
        }.take(5)
}

/** The memory graph on disk (memory.json), like [RoutineBook]. */
class MemoryBook(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()
    private val file = File(dir, "memory.json")

    private val _data = MutableStateFlow(MemoryData())
    val data: StateFlow<MemoryData> = _data.asStateFlow()

    suspend fun load() = lock.withLock {
        withContext(Dispatchers.IO) {
            _data.value = if (!file.exists()) MemoryData() else
                runCatching { json.decodeFromString<MemoryData>(file.readText()) }.getOrElse {
                    file.copyTo(File(dir, "memory.broken-${System.currentTimeMillis()}.json"), overwrite = true)
                    MemoryData()
                }
        }
    }

    suspend fun learn(facts: List<Fact>) {
        if (facts.isEmpty()) return
        change { d -> facts.fold(d) { acc, f -> Memory.merge(acc, f, clock()) } }
    }

    suspend fun delete(id: String) = change { d -> d.copy(facts = d.facts.filterNot { it.id == id }) }

    suspend fun forget(what: String, asker: String?): Int {
        var n = 0
        change { d -> Memory.forget(d, what, asker).also { n = it.second }.first }
        return n
    }

    suspend fun clear() = change { MemoryData() }

    /** Everything that is only [personId]'s, when they are removed from People. */
    suspend fun forgetPerson(personId: String) = change { d -> d.copy(facts = d.facts.filterNot { it.who == personId }) }

    private suspend fun change(edit: (MemoryData) -> MemoryData) = lock.withLock {
        val next = edit(_data.value)
        _data.value = next
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val temp = File(dir, "memory.json.tmp")
            temp.writeText(json.encodeToString(MemoryData.serializer(), next))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }
}

/** The memory tools: [recall] is always there, remembering and forgetting only when asked for. */
class MemoryTools(private val book: MemoryBook, private val clock: () -> Long = System::currentTimeMillis) {
    val names = setOf("recall", "remember", "forget_memory")

    fun specs(): List<ToolSpec> = listOf(
        tool("recall", "Look up what you remember from earlier conversations and habits (\"the usual\", favourites, what they told you before).") {
            string("about", "A few words on what to look up, e.g. music in the morning")
            required("about")
        },
        tool("remember", "Remember a fact for later conversations, e.g. my sister is called Elise, or we eat tacos on Fridays.") {
            string("subject", "Who or what it is about")
            string("relation", "e.g. likes, is called, eats")
            string("object", "The rest of the fact")
            string("for", "me (only the person asking) or house (everyone)")
            required("subject", "relation", "object")
        },
        tool("forget_memory", "Forget remembered facts that match these words.") {
            string("about", "Words from the fact to forget")
            required("about")
        },
    )

    suspend fun run(call: ToolCall, asker: Asker?, local: Boolean): ToolResult? {
        fun str(k: String) = (call.arguments[k] as? JsonPrimitive)?.content.orEmpty().trim()
        return when (call.name) {
            "recall" -> {
                val found = Memory.recall(book.data.value, str("about"), asker?.id, local, clock())
                ToolResult(
                    if (found.isEmpty()) "No memory of that." else found.joinToString("\n") { Memory.line(it) },
                    localOnly = found.any { it.localOnly },
                )
            }
            "remember" -> {
                val mine = str("for").lowercase() != "house" && asker != null
                val fact = Fact(
                    who = if (mine) asker!!.id else Memory.HOUSE,
                    whoName = if (mine) asker!!.name else "",
                    subject = str("subject").ifEmpty { return ToolResult("Say who or what the fact is about.") },
                    relation = str("relation").ifEmpty { "is" },
                    obj = str("object").ifEmpty { return ToolResult("Say what to remember.") },
                    kind = FactKind.Said,
                )
                book.learn(listOf(fact))
                ToolResult("Remembered" + if (mine) " for ${asker!!.name}." else " for the whole house.")
            }
            "forget_memory" -> {
                val n = book.forget(str("about"), asker?.id)
                ToolResult(if (n == 0) "No remembered fact matched that." else "Forgot $n fact${if (n == 1) "" else "s"}.")
            }
            else -> null
        }
    }
}
