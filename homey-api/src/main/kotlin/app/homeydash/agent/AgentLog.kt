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

/**
 * What the assistant heard, thought and did, kept on the tablet until it is sent. Lines
 * only the local AI may see are kept out of what is sent, and so are keys and tokens.
 */
class AgentLog(private val file: File?, private val zone: ZoneId = ZoneId.systemDefault()) {

    @Serializable
    data class Entry(val at: Long, val kind: String, val text: String, val localOnly: Boolean = false)

    private val entries = ArrayDeque<Entry>()
    private var loaded = false
    /** Lines in the file, which can run past [MAX_ENTRIES] until it is rewritten. */
    private var fileLines = 0

    @Synchronized
    fun add(kind: String, text: String, localOnly: Boolean = false, at: Long = System.currentTimeMillis()) {
        load()
        val entry = Entry(at, kind, text.take(MAX_TEXT), localOnly)
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
            if (e.kind == "question" || e.kind == "routine") append("\n---\n")
            val text = if (e.localOnly) LOCAL_ONLY else redact(e.text)
            append("`").append(time.format(Instant.ofEpochMilli(e.at))).append("` **").append(e.kind).append("**")
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
        private val json = Json { ignoreUnknownKeys = true }

        /** API keys and tokens, in case one ever turns up in what a tool or model said. */
        private val KEYS = Regex("""\b(sk-[A-Za-z0-9_\-]{12,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AIza[0-9A-Za-z_\-]{20,}|Bearer\s+[A-Za-z0-9._\-]{12,})""")

        fun redact(text: String): String = KEYS.replace(text, "[key]")
    }
}

/** Posts the assistant's log as comments on one GitHub issue, made the first time. */
class GitHubLog(private val http: OkHttpClient, private val api: String = "https://api.github.com") {
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
            o["title"]?.jsonPrimitive?.contentOrNull == AgentLog.ISSUE_TITLE && "pull_request" !in o
        }?.let { return it.jsonObject.getValue("number").jsonPrimitive.int }
        val made = call(
            token, "POST", "/repos/$repo/issues",
            buildJsonObject {
                put("title", AgentLog.ISSUE_TITLE)
                put("body", "What the dashboard's assistant heard, thought and did, sent from the tablet. Lines only the local AI may see are left out.")
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
                throw IOException("Couldn't send the logs: $why")
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
