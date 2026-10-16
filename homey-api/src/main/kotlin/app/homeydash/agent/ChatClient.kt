package app.homeydash.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A tool call the model asked for; [arguments] is already parsed. */
data class ToolCall(val id: String, val name: String, val arguments: JsonObject)

/** One message of a conversation, in the OpenAI chat format. */
data class ChatMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    /** Why the model stopped ("stop", "length", "tool_calls"), when the server says; only for the logs. */
    val finish: String? = null,
) {
    companion object {
        fun system(text: String) = ChatMessage("system", text)
        fun user(text: String) = ChatMessage("user", text)
        fun tool(id: String, result: String) = ChatMessage("tool", result, toolCallId = id)
    }
}

data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

/** The model refused or the service answered with an error (as opposed to being unreachable). */
class BrainException(message: String) : IOException(message)

/**
 * Talks to any service with OpenAI's chat-completions API: OpenAI itself, Gemini's
 * OpenAI-compatible endpoint, and Ollama's `/v1`. Tool calls come back parsed.
 */
class ChatClient(http: OkHttpClient) {
    private val localHttp = http.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private val cloudHttp = http.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    /**
     * [requireTool] asks for a tool call this time (`tool_choice: required`), after the model
     * promised to do something without calling one; a server that refuses it is asked again without.
     */
    suspend fun complete(connection: Connection, messages: List<ChatMessage>, tools: List<ToolSpec>, requireTool: Boolean = false): ChatMessage {
        if (requireTool && tools.isNotEmpty()) {
            try {
                return send(connection, messages, tools, requireTool = true)
            } catch (e: BrainException) {
                // Not every server knows tool_choice; fall through and ask the plain way.
            }
        }
        return send(connection, messages, tools, requireTool = false)
    }

    private suspend fun send(connection: Connection, messages: List<ChatMessage>, tools: List<ToolSpec>, requireTool: Boolean): ChatMessage = withContext(Dispatchers.IO) {
        val full = requestBody(connection.model, messages, tools, noThinking = connection.provider == Provider.Ollama)
        val body = (if (requireTool) JsonObject(full + ("tool_choice" to JsonPrimitive("required"))) else full).toString()
        val request = Request.Builder()
            .url(connection.baseUrl.trimEnd('/') + "/chat/completions")
            .apply { if (connection.apiKey.isNotBlank()) header("Authorization", "Bearer ${connection.apiKey}") }
            .post(body.toRequestBody(JSON))
            .build()
        val http = if (connection.local) localHttp else cloudHttp
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw BrainException("${connection.provider.label} answered ${response.code}: ${errorText(text)}")
            parseResponse(text)
        }
    }

    /**
     * Like [complete], but the answer comes word by word as the model writes it: [onText] gets
     * each new piece of text as it arrives (on a background thread), so speaking can start
     * before the answer is finished. Returns the whole answer, read the same way as [complete].
     * A server that ignores `stream` and answers in one go still works; [onText] then gets it all at once.
     */
    suspend fun stream(
        connection: Connection,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        requireTool: Boolean = false,
        onText: (String) -> Unit,
    ): ChatMessage {
        if (requireTool && tools.isNotEmpty()) {
            try {
                return sendStreaming(connection, messages, tools, requireTool = true, onText)
            } catch (e: BrainException) {
                // Not every server knows tool_choice; fall through and ask the plain way.
            }
        }
        return sendStreaming(connection, messages, tools, requireTool = false, onText)
    }

    private suspend fun sendStreaming(
        connection: Connection,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        requireTool: Boolean,
        onText: (String) -> Unit,
    ): ChatMessage = withContext(Dispatchers.IO) {
        val full = requestBody(connection.model, messages, tools, noThinking = connection.provider == Provider.Ollama)
        val body = JsonObject(full + ("stream" to JsonPrimitive(true)) + (if (requireTool) mapOf("tool_choice" to JsonPrimitive("required")) else emptyMap())).toString()
        val request = Request.Builder()
            .url(connection.baseUrl.trimEnd('/') + "/chat/completions")
            .apply { if (connection.apiKey.isNotBlank()) header("Authorization", "Bearer ${connection.apiKey}") }
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON))
            .build()
        val http = if (connection.local) localHttp else cloudHttp
        val call = http.newCall(request)
        // The read blocks; stopping the question (a wake word, a tap) closes the connection instead.
        val stop = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw BrainException("${connection.provider.label} answered ${response.code}: ${errorText(response.body?.string().orEmpty())}")
                val source = response.body?.source() ?: throw BrainException("${connection.provider.label} sent an empty answer")
                // Some servers stream without saying so; a reply in one go starts with "{".
                val streamed = response.header("Content-Type").orEmpty().contains("event-stream") ||
                    runCatching { source.peek().readUtf8Line()?.trimStart()?.startsWith("{") == false }.getOrDefault(false)
                if (!streamed) {
                    val answer = parseResponse(source.readUtf8())
                    answer.content?.takeIf { it.isNotEmpty() }?.let(onText)
                    return@withContext answer
                }
                val answer = StreamedAnswer()
                while (true) {
                    ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!answer.read(line, onText)) break
                }
                answer.message()
            }
        } finally {
            stop?.dispose()
        }
    }

    /**
     * Puts a streamed answer back together from its server-sent events (`data: {...}` lines
     * with a `delta` each, ending with `data: [DONE]`): the text, and tool calls whose
     * arguments arrive in pieces by their `index`.
     */
    internal class StreamedAnswer {
        private val text = StringBuilder()
        private class Call(var id: String = "", var name: String = "", val arguments: StringBuilder = StringBuilder())
        private val calls = sortedMapOf<Int, Call>()
        private var finish: String? = null

        /** Reads one line of the stream; false once the server says it's done. */
        fun read(line: String, onText: (String) -> Unit): Boolean {
            val data = line.trim().takeIf { it.startsWith("data:") }?.removePrefix("data:")?.trim() ?: return true
            if (data == "[DONE]") return false
            val root = runCatching { Lenient.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return true
            (root["error"] as? JsonObject)?.let { throw BrainException(findMessage(root) ?: "The AI stopped with an error") }
            val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return true
            (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull?.let { finish = it }
            val delta = choice["delta"] as? JsonObject ?: return true
            (delta["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                text.append(it)
                onText(it)
            }
            (delta["tool_calls"] as? JsonArray).orEmpty().forEach { el ->
                val obj = el as? JsonObject ?: return@forEach
                val index = (obj["index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: calls.size
                val call = calls.getOrPut(index) { Call() }
                (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { call.id = it }
                val fn = obj["function"] as? JsonObject ?: return@forEach
                (fn["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { call.name = it }
                when (val args = fn["arguments"]) {
                    is JsonPrimitive -> args.contentOrNull?.let { call.arguments.append(it) }
                    is JsonObject -> call.arguments.append(args.toString())
                    else -> {}
                }
            }
            return true
        }

        /** The whole answer, as [parseResponse] would read it from one reply. */
        fun message(): ChatMessage {
            val message = buildJsonObject {
                put("role", "assistant")
                put("content", text.toString())
                if (calls.isNotEmpty()) put("tool_calls", buildJsonArray {
                    calls.values.filter { it.name.isNotBlank() }.forEach { c ->
                        add(buildJsonObject {
                            put("id", c.id)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", c.name)
                                put("arguments", c.arguments.toString())
                            })
                        })
                    }
                })
            }
            val root = buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("message", message)
                        finish?.let { put("finish_reason", it) }
                    })
                })
            }
            return parseResponse(root.toString())
        }
    }

    private var warmed = 0L

    /**
     * Loads a local Ollama model into memory ahead of the question, so the answer doesn't
     * wait for it; called when the assistant starts listening. Other providers need nothing.
     */
    suspend fun warm(connection: Connection) = withContext(Dispatchers.IO) {
        if (connection.provider != Provider.Ollama) return@withContext
        val now = System.currentTimeMillis()
        if (now - warmed < WARM_EVERY_MS) return@withContext
        warmed = now
        val base = connection.baseUrl.trimEnd('/').removeSuffix("/v1")
        val body = buildJsonObject {
            put("model", connection.model)
            put("keep_alive", "2h")
        }.toString()
        val request = Request.Builder().url("$base/api/generate").post(body.toRequestBody(JSON)).build()
        runCatching { localHttp.newCall(request).execute().close() }.onFailure { warmed = 0L }
    }

    /**
     * Sends [messages] for a one-token answer that is thrown away, so a local model works
     * through them now and only has to read what comes after them when the real question
     * arrives (Ollama keeps the start of the last conversation it read). Failures don't matter.
     */
    suspend fun prefill(connection: Connection, messages: List<ChatMessage>, tools: List<ToolSpec>) = withContext(Dispatchers.IO) {
        val full = requestBody(connection.model, messages, tools, noThinking = connection.provider == Provider.Ollama)
        val body = JsonObject(full + ("max_tokens" to JsonPrimitive(1))).toString()
        val request = Request.Builder()
            .url(connection.baseUrl.trimEnd('/') + "/chat/completions")
            .apply { if (connection.apiKey.isNotBlank()) header("Authorization", "Bearer ${connection.apiKey}") }
            .post(body.toRequestBody(JSON))
            .build()
        runCatching { localHttp.newCall(request).execute().close() }
    }

    /** Checks a connection with a tiny question; returns null when it works, else what went wrong. */
    suspend fun test(connection: Connection): String? = try {
        complete(connection, listOf(ChatMessage.user("Reply with the word OK.")), emptyList())
        null
    } catch (e: IOException) {
        e.message ?: e.toString()
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val WARM_EVERY_MS = 5 * 60 * 1000L
        private val Lenient = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * [noThinking] turns off a local model's reasoning step, so spoken answers come quickly.
         * It's said four ways, since servers and models each listen to a different one:
         * `reasoning_effort`, Ollama's own `think`, the chat template's `enable_thinking`
         * (MLX servers such as vMLX, and Qwen 3.5), and Qwen's `/no_think` in the system prompt.
         */
        fun requestBody(model: String, messages: List<ChatMessage>, tools: List<ToolSpec>, noThinking: Boolean = false): JsonObject = buildJsonObject {
            put("model", model)
            put("temperature", 0.3)
            if (noThinking) {
                put("reasoning_effort", "none")
                put("think", false)
                put("chat_template_kwargs", buildJsonObject { put("enable_thinking", false) })
            }
            val sent = if (!noThinking) messages else messages.mapIndexed { i, m ->
                if (i == 0 && m.role == "system") m.copy(content = m.content.orEmpty() + "\n\n/no_think") else m
            }
            put("messages", buildJsonArray { sent.forEach { add(encode(it)) } })
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    tools.forEach { t ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", t.name)
                                put("description", t.description)
                                put("parameters", t.parameters)
                            })
                        })
                    }
                })
            }
        }

        private fun encode(m: ChatMessage): JsonObject = buildJsonObject {
            put("role", m.role)
            // OpenAI wants content present (null allowed) on assistant messages with tool calls.
            put("content", m.content?.let(::JsonPrimitive) ?: JsonPrimitive(""))
            m.toolCallId?.let { put("tool_call_id", it) }
            if (m.toolCalls.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    m.toolCalls.forEach { c ->
                        add(buildJsonObject {
                            put("id", c.id)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", c.name)
                                put("arguments", c.arguments.toString())
                            })
                        })
                    }
                })
            }
        }

        fun parseResponse(text: String): ChatMessage {
            val root = Lenient.parseToJsonElement(text) as? JsonObject ?: throw BrainException("Unexpected answer: ${text.take(200)}")
            val message = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
                ?: throw BrainException("No answer in: ${text.take(200)}")
            val content = (message["content"] as? JsonPrimitive)?.contentOrNull?.let(::stripThinking)
            val calls = (message["tool_calls"] as? JsonArray).orEmpty().mapIndexedNotNull { i, el ->
                val obj = el as? JsonObject ?: return@mapIndexedNotNull null
                val fn = obj["function"] as? JsonObject ?: return@mapIndexedNotNull null
                val name = (fn["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapIndexedNotNull null
                val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: "call_$i"
                ToolCall(id, name, parseArguments(fn["arguments"]))
            }
            val finish = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("finish_reason")?.let { (it as? JsonPrimitive)?.contentOrNull }
            if (calls.isEmpty() && content != null) {
                // Small local models sometimes write the tool call as text instead of calling it.
                val (written, rest) = callsInText(content)
                if (written.isNotEmpty()) return ChatMessage("assistant", rest, written, finish = finish)
            }
            return ChatMessage("assistant", content, calls, finish = finish)
        }

        private val TOOL_CALL_TAG = Regex("""<tool_call>\s*(\{[\s\S]*?\})\s*</tool_call>""")

        private val XML_FUNCTION = Regex("""<function=([\w.-]+)>([\s\S]*?)</function>""")
        private val XML_PARAMETER = Regex("""<parameter=([\w.-]+)>\s*([\s\S]*?)\s*</parameter>""")
        private val TOOL_CALL_BLOCK = Regex("""<tool_call>[\s\S]*?(</tool_call>|$)""")

        /**
         * Tool calls written into the answer text, and the text left over: Qwen's XML form
         * (<function=set_device><parameter=device>light_3</parameter></function>),
         * <tool_call>{json}</tool_call>, bare JSON, or `CALL name {json}` lines.
         */
        fun callsInText(content: String): Pair<List<ToolCall>, String> {
            val lines = callLines(content)
            if (lines.first.isNotEmpty()) return lines
            val xml = XML_FUNCTION.findAll(content).mapIndexed { i, m ->
                val args = XML_PARAMETER.findAll(m.groupValues[2]).associate { p -> p.groupValues[1] to JsonPrimitive(p.groupValues[2].trim()) }
                ToolCall("call_text_$i", m.groupValues[1], JsonObject(args))
            }.toList()
            if (xml.isNotEmpty()) {
                val rest = content.replace(TOOL_CALL_BLOCK, "").replace(XML_FUNCTION, "").trim()
                return xml to rest
            }
            return jsonCallsInText(content)
        }

        private val CALL_LINE = Regex("""(?m)^[ \t*`]*CALL[ \t]+([\w.-]+)[ \t]*""")

        private val CODE_FENCE = Regex("""(?m)^[ \t]*```\w*[ \t]*$""")

        /** `CALL name {json args}` lines ([ToolText.HOW]); the text left is everything else. */
        private fun callLines(content: String): Pair<List<ToolCall>, String> {
            val calls = mutableListOf<ToolCall>()
            val rest = StringBuilder()
            var from = 0
            for (m in CALL_LINE.findAll(content)) {
                if (m.range.first < from) continue
                rest.append(content, from, m.range.first)
                var end = m.range.last + 1
                val args = if (end < content.length && content[end] == '{') {
                    val close = closingBrace(content, end)
                    val obj = runCatching { Lenient.parseToJsonElement(content.substring(end, close)) as? JsonObject }.getOrNull()
                    end = close
                    obj
                } else null
                calls += ToolCall("call_text_${calls.size}", m.groupValues[1], args ?: JsonObject(emptyMap()))
                while (end < content.length && content[end] != '\n') end++
                from = end
            }
            if (calls.isEmpty()) return emptyList<ToolCall>() to content
            rest.append(content, from, content.length)
            return calls to rest.toString().replace(CODE_FENCE, "").trim()
        }

        /** Index just past the brace that closes the one at [start], or the end of [text]. */
        private fun closingBrace(text: String, start: Int): Int {
            var depth = 0
            var quoted = false
            var i = start
            while (i < text.length) {
                val c = text[i]
                when {
                    quoted && c == '\\' -> i++
                    c == '"' -> quoted = !quoted
                    !quoted && c == '{' -> depth++
                    !quoted && c == '}' -> if (--depth == 0) return i + 1
                }
                i++
            }
            return text.length
        }

        private fun jsonCallsInText(content: String): Pair<List<ToolCall>, String> {
            val tagged = TOOL_CALL_TAG.findAll(content).toList()
            val raws = if (tagged.isNotEmpty()) tagged.map { it.groupValues[1] } else listOf(content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
            val calls = raws.mapIndexedNotNull { i, raw ->
                val obj = runCatching { Lenient.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return@mapIndexedNotNull null
                val fn = (obj["function"] as? JsonObject) ?: obj
                val name = (fn["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapIndexedNotNull null
                ToolCall("call_text_$i", name, parseArguments(fn["arguments"] ?: fn["parameters"]))
            }
            if (calls.isEmpty()) return emptyList<ToolCall>() to content
            val rest = if (tagged.isNotEmpty()) content.replace(TOOL_CALL_TAG, "").trim() else ""
            return calls to rest
        }

        /** Arguments arrive as a JSON string (OpenAI, Gemini) or an object (some Ollama versions). */
        private fun parseArguments(raw: JsonElement?): JsonObject = when (raw) {
            is JsonObject -> raw
            is JsonPrimitive -> raw.contentOrNull?.takeIf { it.isNotBlank() }?.let { runCatching { Lenient.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                ?: JsonObject(emptyMap())
            else -> JsonObject(emptyMap())
        }

        /** Reasoning models served by Ollama may put their thinking in the answer. */
        fun stripThinking(text: String): String = text.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()

        private fun errorText(body: String): String {
            val parsed = runCatching { Lenient.parseToJsonElement(body) }.getOrNull()
            val message = when (parsed) {
                is JsonArray -> (parsed.firstOrNull() as? JsonObject)?.let(::findMessage)
                is JsonObject -> findMessage(parsed)
                else -> null
            }
            return (message ?: body).take(200)
        }

        private fun findMessage(obj: JsonObject): String? =
            ((obj["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
                ?: (obj["error"] as? JsonPrimitive)?.contentOrNull
    }
}
