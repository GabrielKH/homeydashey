package app.homeydash.agent

import kotlinx.coroutines.Dispatchers
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

    suspend fun complete(connection: Connection, messages: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage = withContext(Dispatchers.IO) {
        val body = requestBody(connection.model, messages, tools).toString()
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

    /** Checks a connection with a tiny question; returns null when it works, else what went wrong. */
    suspend fun test(connection: Connection): String? = try {
        complete(connection, listOf(ChatMessage.user("Reply with the word OK.")), emptyList())
        null
    } catch (e: IOException) {
        e.message ?: e.toString()
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val Lenient = Json { ignoreUnknownKeys = true; isLenient = true }

        fun requestBody(model: String, messages: List<ChatMessage>, tools: List<ToolSpec>): JsonObject = buildJsonObject {
            put("model", model)
            put("temperature", 0.3)
            put("messages", buildJsonArray { messages.forEach { add(encode(it)) } })
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
            return ChatMessage("assistant", content, calls)
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
