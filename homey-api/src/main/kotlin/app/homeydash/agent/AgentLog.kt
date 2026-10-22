package app.homeydash.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How much the assistant's log keeps, from nothing to the whole prompt of every round. */
enum class LogLevel(val label: String, val detail: String) {
    Off("Off", "Nothing is kept."),
    Errors("Errors", "Only what went wrong, and answers marked as wrong."),
    Normal("Normal", "Each question, the tools it used and what Homey said, the answer and how long it took."),
    Detailed("Detailed", "Also what the AI wrote each round, the guards, the memory and know-how it used, and the wake word and listening decisions."),
    Verbose("Verbose", "Also the whole prompt sent to the AI each round. Goes back to Detailed after an hour."),
}

/** The parts of the assistant whose log lines can be kept at a level of their own. */
enum class LogArea(val label: String) {
    Wake("Wake word and voices"),
    Hearing("Listening and speech to text"),
    Brain("The AI"),
    Tools("Tools and Homey"),
    Memory("Memory and know-how"),
    Speech("Speaking"),
}

/**
 * What the assistant heard, thought and did, kept on the tablet until it is sent. Lines
 * only the local AI may see are kept out of what is sent, and so are keys and tokens.
 * Each question starts a numbered turn, so an answer marked as wrong ("det där blev fel")
 * points at its turn. [levels] says how much of each [LogArea] is kept.
 */
class AgentLog(private val file: File?, private val zone: ZoneId = ZoneId.systemDefault()) {

    @Serializable
    data class Entry(val at: Long, val kind: String, val text: String, val localOnly: Boolean = false, val turn: Int = 0)

    private val entries = ArrayDeque<Entry>()
    private var loaded = false
    /** Lines in the file, which can run past [MAX_ENTRIES] until it is rewritten. */
    private var fileLines = 0
    private var turn = 0
    /** The last questions by turn, to find the turn of an answer marked as wrong after it was sent. */
    private val questions = ArrayDeque<Pair<Int, String>>()

    /** How much of each area is kept; everything but the prompts, unless set. */
    @Volatile
    var levels: (LogArea) -> LogLevel = { LogLevel.Detailed }

    /** Whether lines of [kind] are kept at the levels set now. */
    fun keeps(kind: String, text: String = ""): Boolean {
        val (area, needs) = classify(kind, text)
        return levels(area) >= needs && levels(area) != LogLevel.Off
    }

    @Synchronized
    fun add(kind: String, text: String, localOnly: Boolean = false, at: Long = System.currentTimeMillis()) {
        load()
        if (!keeps(kind, text)) return
        if (kind in TURN_STARTS) {
            turn++
            questions.addLast(turn to text)
            while (questions.size > MAX_QUESTIONS) questions.removeFirst()
        }
        append(Entry(at, kind, text.take(if (kind == PROMPT) MAX_PROMPT else MAX_TEXT), localOnly, turn))
    }

    /**
     * Marks the answer to [question] as wrong (the newest turn that asked it, else the last
     * turn), with [text] saying how; kept at every level but Off.
     */
    @Synchronized
    fun flag(question: String?, text: String, at: Long = System.currentTimeMillis()) {
        load()
        if (!keeps(FLAG)) return
        val asked = question?.trim()?.let { q -> questions.lastOrNull { it.second.trim() == q }?.first }
        append(Entry(at, FLAG, text.take(MAX_TEXT), false, asked ?: turn))
    }

    private fun append(entry: Entry) {
        entries.addLast(entry)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        file?.let { f ->
            runCatching {
                f.parentFile?.mkdirs()
                // Appends, and rewrites the file only once it holds twice what is kept, instead of on every line once full.
                if (fileLines >= MAX_ENTRIES * 2) save() else {
                    f.appendText(json.encodeToString(Entry.serializer(), entry) + "\n")
                    fileLines++
                }
            }
        }
    }

    /** Everything not sent yet, oldest first. */
    @Synchronized
    fun unsent(): List<Entry> {
        load()
        return entries.toList()
    }

    /** Drops the first [count] entries, once they have been sent. */
    @Synchronized
    fun sent(count: Int) {
        load()
        repeat(count.coerceAtMost(entries.size)) { entries.removeFirst() }
        save()
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val lines = f.readLines()
            fileLines = lines.size
            lines.forEach { line ->
                runCatching { json.decodeFromString(Entry.serializer(), line) }.getOrNull()?.let(entries::addLast)
            }
        }
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        turn = entries.maxOfOrNull { it.turn } ?: 0
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(entries.joinToString("") { json.encodeToString(Entry.serializer(), it) + "\n" })
            fileLines = entries.size
        }
    }

    /** [entries] as Markdown for a GitHub comment, one question per section. */
    fun markdown(entries: List<Entry>, device: String? = null): String = buildString {
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)
        append("### Assistant log")
        device?.let { append(" · ").append(it) }
        append("\n")
        entries.forEach { e ->
            if (e.kind in TURN_STARTS) append("\n---\n_Turn ").append(e.turn).append("_\n\n")
            val text = if (e.localOnly) LOCAL_ONLY else redact(e.text)
            append("`").append(time.format(Instant.ofEpochMilli(e.at))).append("` **")
            if (e.kind == FLAG) append("⚑ marked wrong (turn ").append(e.turn).append(")") else append(e.kind)
            append("**")
            if (text.contains('\n') || text.length > 160) {
                append("\n```\n").append(text.replace("```", "ʼʼʼ")).append("\n```\n")
            } else {
                append(": ").append(text.replace("\n", " ")).append("  \n")
            }
        }
    }

    companion object {
        const val DEFAULT_REPO = "GabrielKH/homeydashey"
        const val ISSUE_TITLE = "Assistant logs"
        const val LOCAL_ONLY = "[local only, not sent]"
        private const val MAX_ENTRIES = 600
        private const val MAX_TEXT = 3000
        /** A whole prompt, at Verbose. */
        private const val MAX_PROMPT = 60_000
        private const val MAX_QUESTIONS = 50

        /** The whole prompt of one round, kept at Verbose. */
        const val PROMPT = "prompt"
        /** An answer marked as wrong. */
        const val FLAG = "flag"
        private val TURN_STARTS = setOf("question", "routine")

        /**
         * The area a line of [kind] belongs to and the level that keeps it; a tool's result is
         * kept at Errors too when the tool failed. Kinds not named here are the AI's, at Normal.
         */
        fun classify(kind: String, text: String = ""): Pair<LogArea, LogLevel> = when (kind) {
            FLAG -> LogArea.Brain to LogLevel.Errors
            "error" -> LogArea.Brain to LogLevel.Errors
            "question", "answer", "ignored", "routine", "brain", "timing", "speed test" -> LogArea.Brain to LogLevel.Normal
            "model", "model calls", "model stopped", "guard", "speed" -> LogArea.Brain to LogLevel.Detailed
            PROMPT -> LogArea.Brain to LogLevel.Verbose
            "tool", "phone" -> LogArea.Tools to LogLevel.Normal
            "result" -> LogArea.Tools to if (StepText.failed(text)) LogLevel.Errors else LogLevel.Normal
            "know-how", "memory" -> LogArea.Memory to LogLevel.Detailed
            "wake" -> LogArea.Wake to LogLevel.Detailed
            "listen" -> LogArea.Hearing to LogLevel.Detailed
            "voice" -> LogArea.Speech to LogLevel.Detailed
            "battery" -> LogArea.Speech to LogLevel.Normal
            else -> LogArea.Brain to LogLevel.Normal
        }
        private val json = Json { ignoreUnknownKeys = true }

        /** API keys and tokens, in case one ever turns up in what a tool or model said. */
        private val KEYS = Regex("""\b(sk-[A-Za-z0-9_\-]{12,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AIza[0-9A-Za-z_\-]{20,}|Bearer\s+[A-Za-z0-9._\-]{12,})""")

        fun redact(text: String): String = KEYS.replace(text, "[key]")
    }
}

/**
 * Posts as comments on one GitHub issue, made the first time: the assistant's log, or with
 * another [title] and [about] something else, like the skill test reports.
 */
class GitHubLog(
    private val http: OkHttpClient,
    private val api: String = "https://api.github.com",
    private val title: String = AgentLog.ISSUE_TITLE,
    private val about: String = "What the dashboard's assistant heard, thought and did, sent from the tablet. Lines only the local AI may see are left out.",
) {
    private var issue: Pair<String, Int>? = null

    suspend fun post(token: String, repo: String, body: String) = withContext(Dispatchers.IO) {
        val number = issue?.takeIf { it.first == repo }?.second ?: findOrCreate(token, repo).also { issue = repo to it }
        for (part in parts(body)) {
            call(token, "POST", "/repos/$repo/issues/$number/comments", buildJsonObject { put("body", part) })
        }
    }

    private fun findOrCreate(token: String, repo: String): Int {
        val open = call(token, "GET", "/repos/$repo/issues?state=open&per_page=100", null)
        (open as? JsonArray)?.firstOrNull { item ->
            val o = item.jsonObject
            o["title"]?.jsonPrimitive?.contentOrNull == title && "pull_request" !in o
        }?.let { return it.jsonObject.getValue("number").jsonPrimitive.int }
        val made = call(
            token, "POST", "/repos/$repo/issues",
            buildJsonObject {
                put("title", title)
                put("body", about)
            },
        )
        return made.jsonObject.getValue("number").jsonPrimitive.int
    }

    private fun call(token: String, method: String, path: String, body: JsonObject?): kotlinx.serialization.json.JsonElement {
        val request = Request.Builder()
            .url(api.trimEnd('/') + path)
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .method(method, body?.toString()?.toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val why = when (response.code) {
                    401 -> "the token isn't accepted"
                    403, 404 -> "the token can't write issues in that repository"
                    else -> "GitHub answered ${response.code}"
                }
                throw IOException("Couldn't send to GitHub: $why")
            }
            return Json.parseToJsonElement(text.ifBlank { "{}" })
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_COMMENT = 60_000

        /** GitHub comments hold at most 65 536 characters. */
        fun parts(body: String): List<String> {
            if (body.length <= MAX_COMMENT) return listOf(body)
            val result = mutableListOf<String>()
            var rest = body
            while (rest.length > MAX_COMMENT) {
                val cut = rest.lastIndexOf("\n---\n", MAX_COMMENT).takeIf { it > MAX_COMMENT / 2 } ?: MAX_COMMENT
                result += rest.substring(0, cut)
                rest = rest.substring(cut)
            }
            if (rest.isNotBlank()) result += rest
            return result
        }
    }
}
