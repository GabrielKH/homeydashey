package app.homeydash.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import java.util.concurrent.TimeUnit

/** Answers a general question from the internet. Only the question is sent, nothing about the home. */
fun interface WebLookup {
    suspend fun answer(question: String, language: Language): String
}

/**
 * Asks the internet AI a question with its own web search turned on: Google Search for
 * Gemini, web search for OpenAI. Used by the local AI for things it doesn't know.
 */
class WebSearch(http: OkHttpClient) {
    private val http = http.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    /** A lookup through [connection], or null when it isn't set up. */
    fun lookup(connection: Connection?): WebLookup? {
        val c = connection?.takeIf { it.usable && !it.local } ?: return null
        return WebLookup { question, language -> ask(c, question, language) }
    }

    private suspend fun ask(c: Connection, question: String, language: Language): String = withContext(Dispatchers.IO) {
        val prompt = prompt(question, language)
        val request = when (c.provider) {
            Provider.Gemini -> Request.Builder()
                .url(geminiBase(c.baseUrl) + "/models/" + c.model.removePrefix("models/") + ":generateContent")
                .header("x-goog-api-key", c.apiKey)
                .post(geminiBody(prompt).toString().toRequestBody(JSON))
            else -> Request.Builder()
                .url(c.baseUrl.trimEnd('/') + "/responses")
                .header("Authorization", "Bearer ${c.apiKey}")
                .post(openAiBody(c.model, prompt).toString().toRequestBody(JSON))
        }.build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw BrainException("${c.provider.label} search answered ${response.code}: ${text.take(200)}")
            val answer = if (c.provider == Provider.Gemini) parseGemini(text) else parseOpenAi(text)
            answer.ifBlank { throw BrainException("${c.provider.label} found nothing.") }
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val Lenient = Json { ignoreUnknownKeys = true; isLenient = true }

        fun prompt(question: String, language: Language): String = buildString {
            append("Search the web if it helps, then answer in at most three short sentences that will be read aloud: no markdown, lists, links or sources. ")
            append(
                when (language) {
                    Language.Swedish -> "Answer in Swedish. "
                    Language.English -> "Answer in English. "
                    Language.Auto -> "Answer in the language of the question. "
                },
            )
            append("The asker lives in Sweden.\n\nQuestion: ").append(question.trim())
        }

        /** The OpenAI-compatible Gemini address ends in /openai; the native API is one level up. */
        fun geminiBase(baseUrl: String): String = baseUrl.trimEnd('/').removeSuffix("/openai")

        fun geminiBody(prompt: String): JsonObject = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", prompt) }) })
                })
            })
            put("tools", buildJsonArray { add(buildJsonObject { put("google_search", buildJsonObject {}) }) })
        }

        fun openAiBody(model: String, prompt: String): JsonObject = buildJsonObject {
            put("model", model)
            put("input", prompt)
            put("tools", buildJsonArray { add(buildJsonObject { put("type", "web_search") }) })
        }

        fun parseGemini(text: String): String {
            val root = Lenient.parseToJsonElement(text) as? JsonObject ?: return ""
            val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return ""
            val parts = ((candidate["content"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
            return parts.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }.joinToString("").trim()
        }

        fun parseOpenAi(text: String): String {
            val root = Lenient.parseToJsonElement(text) as? JsonObject ?: return ""
            return (root["output"] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "message" }
                .flatMap { (it["content"] as? JsonArray).orEmpty() }
                .mapNotNull { part -> (part as? JsonObject)?.takeIf { (it["type"] as? JsonPrimitive)?.contentOrNull == "output_text" } }
                .mapNotNull { (it["text"] as? JsonPrimitive)?.contentOrNull }
                .joinToString(" ")
                .trim()
        }
    }
}
