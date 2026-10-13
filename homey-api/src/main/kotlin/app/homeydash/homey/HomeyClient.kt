package app.homeydash.homey

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class HomeyHttpException(val code: Int, message: String) : IOException(message)

/**
 * Talks to the Homey Web API over the local network with a local API key
 * (Homey Pro 2023 and later: Settings → API Keys in the Homey web app).
 */
class HomeyClient(
    baseUrl: String,
    private val token: String,
    private val http: OkHttpClient,
) {
    val baseUrl: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    /**
     * Unauthenticated; also confirms the address really is a Homey. Like the official
     * homey-api library, this reads the Homey id from the `X-Homey-ID` header of the ping.
     */
    suspend fun systemInfo(): HomeyInfo = withContext(Dispatchers.IO) {
        val request = request("api/manager/system/ping", authenticated = false).get().build()
        http.newCall(request).execute().use { response ->
            val id = response.header("X-Homey-ID")
                ?: throw HomeyConnectException("${baseUrl.host} answered, but it doesn't look like a Homey.")
            HomeyInfo(homeyId = id, homeyVersion = response.header("X-Homey-Version"))
        }
    }

    suspend fun zones(): Map<String, Zone> = get("api/manager/zones/zone")

    suspend fun devices(): Map<String, Device> = get("api/manager/devices/device")

    suspend fun device(id: String): Device = get("api/manager/devices/device/$id")

    /** The API key's user, whose `properties` hold per-user app settings. */
    suspend fun userMe(): JsonObject = get("api/manager/users/user/me")

    suspend fun moods(): Map<String, Mood> = get("api/manager/moods/mood")

    /** Homey users with whether they are home; for the assistant, behind its "Who is home" switch. */
    suspend fun users(): JsonElement = HomeyJson.parseToJsonElement(execute(request("api/manager/users/user").get().build()))

    /** Logic variables; for the assistant, behind its "Homey variables" switch. */
    suspend fun logicVariables(): JsonElement = HomeyJson.parseToJsonElement(execute(request("api/manager/logic/variable").get().build()))

    /** Where the Homey is (Settings → Location), or null when the key may not read it. */
    suspend fun location(): Pair<Double, Double>? {
        for (path in listOf("api/manager/geolocation/option/location", "api/manager/geolocation/state")) {
            val json = try {
                HomeyJson.parseToJsonElement(execute(request(path).get().build()))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            Weather.findCoordinates(json)?.let { return it }
        }
        return null
    }

    suspend fun setMood(id: String) {
        execute(request("api/manager/moods/mood/$id/set").post("{}".toRequestBody(JSON_TYPE)).build())
    }

    suspend fun flowCardActions(): List<FlowCardAction> =
        FlowCards.parseActions(HomeyJson.parseToJsonElement(execute(request("api/manager/flow/flowcardaction").get().build())))

    /** Flows that can be started (with a "This Flow is started" card), advanced ones included. */
    suspend fun flows(): List<HomeyFlow> {
        val basic = Goodnight.parseFlows(HomeyJson.parseToJsonElement(execute(request("api/manager/flow/flow").get().build())), advanced = false)
        val advanced = try {
            Goodnight.parseFlows(HomeyJson.parseToJsonElement(execute(request("api/manager/flow/advancedflow").get().build())), advanced = true)
        } catch (e: HomeyHttpException) {
            emptyList()
        }
        return (basic + advanced).sortedBy { it.name.lowercase() }
    }

    suspend fun triggerFlow(flow: HomeyFlow) {
        val kind = if (flow.advanced) "advancedflow" else "flow"
        execute(request("api/manager/flow/$kind/${flow.id}/trigger").post("{}".toRequestBody(JSON_TYPE)).build())
    }

    /** Runs a Flow "Then" card, e.g. an Apple TV remote key; [args] are the card's arguments. */
    suspend fun runAction(card: FlowCardAction, args: JsonObject = JsonObject(emptyMap())) {
        val body = buildJsonObject { put("args", args) }.toString()
        execute(flowCardRequest(card, "run").post(body.toRequestBody(JSON_TYPE)).build())
    }

    /** The choices an autocomplete argument offers, such as Sonos favourites. */
    suspend fun autocomplete(card: FlowCardAction, arg: String, query: String = ""): List<AutocompleteItem> {
        val builder = flowCardRequest(card, "autocomplete")
        val url = builder.build().url.newBuilder().addQueryParameter("name", arg).addQueryParameter("query", query).build()
        return FlowCards.parseAutocomplete(HomeyJson.parseToJsonElement(execute(builder.url(url).get().build())))
    }

    private fun flowCardRequest(card: FlowCardAction, action: String): Request.Builder {
        val url = baseUrl.newBuilder()
            .addPathSegments("api/manager/flow/flowcardaction")
            .addPathSegment(card.routeUri)
            .addPathSegment(card.id)
            .addPathSegment(action)
            .build()
        return Request.Builder().url(url).header("Authorization", "Bearer $token")
    }

    /**
     * Raw bytes of an image: a Homey path such as an `ImageObj.url` (sent with the key),
     * or a full URL an app gave, such as Sonos album art (sent without it).
     */
    suspend fun image(path: String): ByteArray = withContext(Dispatchers.IO) {
        val url = baseUrl.resolve(path) ?: throw IOException("Bad image path $path")
        val sameHost = url.host == baseUrl.host
        val request = Request.Builder().url(url).apply { if (sameHost) header("Authorization", "Bearer $token") }.get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HomeyHttpException(response.code, "Homey answered ${response.code} for $path")
            response.body?.bytes() ?: ByteArray(0)
        }
    }

    suspend fun setCapability(deviceId: String, capabilityId: String, value: JsonElement) {
        val body = buildJsonObject { put("value", value) }.toString()
        execute(
            request("api/manager/devices/device/$deviceId/capability/$capabilityId")
                .put(body.toRequestBody(JSON_TYPE))
                .build(),
        )
    }

    private suspend inline fun <reified T> get(path: String, authenticated: Boolean = true): T {
        val text = execute(request(path, authenticated).get().build())
        return HomeyJson.decodeFromString(text)
    }

    private fun request(path: String, authenticated: Boolean = true): Request.Builder {
        val url = baseUrl.newBuilder().addPathSegments(path).build()
        return Request.Builder().url(url).apply {
            if (authenticated) header("Authorization", "Bearer $token")
        }
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw HomeyHttpException(response.code, "Homey answered ${response.code} for ${request.url.encodedPath}: ${body.take(200)}")
            }
            body
        }
    }

    private companion object {
        val JSON_TYPE = "application/json".toMediaType()
    }
}

val HomeyJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}
