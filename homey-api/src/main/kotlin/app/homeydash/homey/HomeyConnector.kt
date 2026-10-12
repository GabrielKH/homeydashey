package app.homeydash.homey

import okhttp3.OkHttpClient

data class HomeySession(
    val client: HomeyClient,
    val info: HomeyInfo,
) {
    fun newRealtime(token: String, http: OkHttpClient) =
        HomeyRealtime(client.baseUrl.toString().trimEnd('/'), token, info.homeyId, http)
}

class HomeyConnectException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Finds a working address for the Homey and checks the API key against it. */
class HomeyConnector(private val http: OkHttpClient) {

    suspend fun connect(address: String, token: String): HomeySession {
        val candidates = HomeyAddress.candidates(address)
        if (candidates.isEmpty()) throw HomeyConnectException("Enter your Homey's address.")
        if (token.isBlank()) throw HomeyConnectException("Enter an API key.")

        var lastError: Throwable? = null
        for (url in candidates) {
            val client = HomeyClient(url, token.trim(), http)
            val info = try {
                client.systemInfo()
            } catch (e: Exception) {
                lastError = e
                continue
            }
            try {
                client.zones()
            } catch (e: HomeyHttpException) {
                if (e.code == 401 || e.code == 403) {
                    throw HomeyConnectException("Homey refused the API key. Check it has access to devices and zones.", e)
                }
                throw HomeyConnectException(e.message ?: "Homey answered with an error.", e)
            }
            return HomeySession(client, info)
        }
        throw HomeyConnectException("Couldn't reach a Homey at ${candidates.first()}.", lastError)
    }
}
