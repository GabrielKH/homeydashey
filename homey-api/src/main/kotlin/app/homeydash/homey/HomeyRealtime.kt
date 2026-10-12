package app.homeydash.homey

import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Manager
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

enum class ConnectionState { Connecting, Connected, Disconnected }

sealed interface HomeyEvent {
    data class CapabilityChanged(val deviceId: String, val capabilityId: String, val value: JsonElement) : HomeyEvent
    data class DeviceChanged(val device: Device) : HomeyEvent
    data class DeviceDeleted(val deviceId: String) : HomeyEvent
    data class ZoneChanged(val zone: Zone) : HomeyEvent
    data class ZoneDeleted(val zoneId: String) : HomeyEvent

    /** The socket came back after a drop; updates may have been missed, so reload. */
    data object Reconnected : HomeyEvent
}

/**
 * Homey's realtime API over Socket.IO, as specified at
 * https://api.developer.homey.app/http-and-socket.io/socket.io-specification.md
 *
 * 1. Connect to the base URL (websocket transport only).
 * 2. Emit `handshakeClient {token, homeyId}`; the answer names the namespace (usually `/api`).
 * 3. In that namespace, emit `subscribe <uri>` and listen on the event named after the uri.
 */
class HomeyRealtime(
    private val baseUrl: String,
    private val token: String,
    private val homeyId: String,
    private val http: OkHttpClient,
) {
    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<HomeyEvent>(extraBufferCapacity = 512)
    val events: SharedFlow<HomeyEvent> = _events.asSharedFlow()

    private val subscriptions = ConcurrentHashMap.newKeySet<String>()
    private var manager: Manager? = null
    private var root: Socket? = null
    private var api: Socket? = null
    private var connectedOnce = false

    fun connect() {
        if (root != null) return
        val options = IO.Options.builder()
            .setTransports(arrayOf(WebSocket.NAME))
            .setReconnection(true)
            .setReconnectionDelay(1_000)
            .setReconnectionDelayMax(10_000)
            .build()
        options.callFactory = http
        options.webSocketFactory = http

        val manager = Manager(URI.create(baseUrl), options).also { manager = it }
        val root = manager.socket("/").also { root = it }
        _state.value = ConnectionState.Connecting

        root.on(Socket.EVENT_CONNECT) { handshake(root) }
        root.on(Socket.EVENT_DISCONNECT) { _state.value = ConnectionState.Disconnected }
        root.on(Socket.EVENT_CONNECT_ERROR) { _state.value = ConnectionState.Disconnected }
        root.connect()
    }

    fun subscribe(uri: String) {
        if (subscriptions.add(uri)) api?.takeIf { it.connected() }?.let { listen(it, uri) }
    }

    fun close() {
        api?.disconnect()
        root?.disconnect()
        api = null
        root = null
        manager = null
        _state.value = ConnectionState.Disconnected
    }

    private fun handshake(root: Socket) {
        val payload = JSONObject().put("token", token).put("homeyId", homeyId)
        root.emit("handshakeClient", payload, Ack { args ->
            val error = args.getOrNull(0)
            val result = args.getOrNull(1) as? JSONObject
            if (error != null && error != JSONObject.NULL || result == null) {
                _state.value = ConnectionState.Disconnected
                return@Ack
            }
            openNamespace(result.optString("namespace", "/api"))
        })
    }

    private fun openNamespace(namespace: String) {
        api?.let { it.off(); it.disconnect() }
        val socket = manager!!.socket(namespace).also { api = it }
        socket.on(Socket.EVENT_CONNECT) {
            subscriptions.forEach { listen(socket, it) }
            _state.value = ConnectionState.Connected
            if (connectedOnce) _events.tryEmit(HomeyEvent.Reconnected)
            connectedOnce = true
        }
        socket.on(Socket.EVENT_DISCONNECT) { _state.value = ConnectionState.Disconnected }
        socket.connect()
    }

    private fun listen(socket: Socket, uri: String) {
        socket.off(uri)
        socket.on(uri) { args ->
            val event = args.getOrNull(0) as? String ?: return@on
            val data = args.getOrNull(1)?.let { HomeyJson.parseToJsonElement(it.toString()) } ?: JsonNull
            parseEvent(uri, event, data)?.let { _events.tryEmit(it) }
        }
        socket.emit("subscribe", uri, Ack { })
    }

    companion object {
        const val DEVICES_URI = "homey:manager:devices"
        const val ZONES_URI = "homey:manager:zones"
        fun deviceUri(deviceId: String) = "homey:device:$deviceId"

        /** Pure mapping from a raw socket event to a [HomeyEvent]; null for events we ignore. */
        fun parseEvent(uri: String, event: String, data: JsonElement): HomeyEvent? = runCatching {
            when {
                uri.startsWith("homey:device:") && event == "capability" -> {
                    val obj = data as JsonObject
                    HomeyEvent.CapabilityChanged(
                        deviceId = uri.removePrefix("homey:device:"),
                        capabilityId = obj.getValue("capabilityId").jsonPrimitive.content,
                        value = obj["value"] ?: JsonNull,
                    )
                }
                uri == DEVICES_URI && (event == "device.create" || event == "device.update") ->
                    HomeyEvent.DeviceChanged(HomeyJson.decodeFromJsonElement(Device.serializer(), data))
                uri == DEVICES_URI && event == "device.delete" ->
                    HomeyEvent.DeviceDeleted((data as JsonObject).getValue("id").jsonPrimitive.content)
                uri == ZONES_URI && (event == "zone.create" || event == "zone.update") ->
                    HomeyEvent.ZoneChanged(HomeyJson.decodeFromJsonElement(Zone.serializer(), data))
                uri == ZONES_URI && event == "zone.delete" ->
                    HomeyEvent.ZoneDeleted((data as JsonObject).getValue("id").jsonPrimitive.content)
                else -> null
            }
        }.getOrNull()
    }
}
