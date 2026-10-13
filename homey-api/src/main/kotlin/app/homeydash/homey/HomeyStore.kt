package app.homeydash.homey

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class HomeySnapshot(
    val zones: Map<String, Zone> = emptyMap(),
    val devices: Map<String, Device> = emptyMap(),
    /** Empty when there are none or the API key may not read moods. */
    val moods: Map<String, Mood> = emptyMap(),
    /** Devices hidden in the Homey app's user settings (on top of each device's own `hidden` flag). */
    val hiddenByUser: Set<String> = emptySet(),
    /** Flow "Then" cards per device id: remote keys, "Play favorite" and the like. Empty without Flow access. */
    val deviceCards: Map<String, List<FlowCardAction>> = emptyMap(),
    val loaded: Boolean = false,
    val error: String? = null,
)

/**
 * Keeps an in-memory mirror of the Homey: one full load over HTTP, then
 * kept current by realtime events. Device instances that did not change are
 * reused, so Compose can skip their cards.
 */
class HomeyStore(
    private val client: HomeyClient,
    private val realtime: HomeyRealtime,
    private val scope: CoroutineScope,
) {
    private val _snapshot = MutableStateFlow(HomeySnapshot())
    val snapshot: StateFlow<HomeySnapshot> = _snapshot.asStateFlow()
    val connection: StateFlow<ConnectionState> = realtime.state

    private var eventsJob: Job? = null

    fun start() {
        if (eventsJob != null) return
        eventsJob = scope.launch { realtime.events.collect(::apply) }
        realtime.subscribe(HomeyRealtime.DEVICES_URI)
        realtime.subscribe(HomeyRealtime.ZONES_URI)
        realtime.subscribe(HomeyRealtime.MOODS_URI)
        scope.launch { reload() }
        realtime.connect()
    }

    fun stop() {
        eventsJob?.cancel()
        eventsJob = null
        realtime.close()
    }

    suspend fun reload() {
        try {
            val zones = client.zones()
            val devices = client.devices()
            // Moods need their own permission on the API key; the dashboard works without them.
            val moods = runCatching { client.moods() }.getOrDefault(emptyMap())
            val hiddenByUser = runCatching { hiddenDeviceIds(client.userMe()) }.getOrDefault(emptySet())
            val deviceCards = runCatching { FlowCards.byDevice(client.flowCardActions()) }.getOrDefault(emptyMap())
            devices.keys.forEach { realtime.subscribe(HomeyRealtime.deviceUri(it)) }
            _snapshot.value = HomeySnapshot(zones = zones, devices = devices, moods = moods, hiddenByUser = hiddenByUser, deviceCards = deviceCards, loaded = true)
        } catch (e: Exception) {
            _snapshot.update { it.copy(error = e.message ?: e.toString()) }
        }
    }

    /** Sets a capability, showing the new value at once and rolling back if Homey refuses. */
    suspend fun setCapability(deviceId: String, capabilityId: String, value: JsonElement) {
        val before = _snapshot.value.devices[deviceId]
        apply(HomeyEvent.CapabilityChanged(deviceId, capabilityId, value))
        try {
            client.setCapability(deviceId, capabilityId, value)
        } catch (e: Exception) {
            if (before != null) apply(HomeyEvent.DeviceChanged(before))
            _snapshot.update { it.copy(error = "Couldn't change ${before?.name ?: "device"}: ${e.message}") }
        }
    }

    suspend fun setMood(mood: Mood) {
        try {
            client.setMood(mood.id)
        } catch (e: Exception) {
            _snapshot.update { it.copy(error = "Couldn't set ${mood.name}: ${e.message}") }
        }
    }

    /** Toggles several devices at once, e.g. every light in a room. */
    suspend fun setAll(deviceIds: Collection<String>, capabilityId: String, value: JsonElement) = coroutineScope {
        deviceIds.forEach { id -> launch { setCapability(id, capabilityId, value) } }
    }

    suspend fun image(path: String): ByteArray = client.image(path)

    /** The Homey's own location, for the weather. */
    suspend fun location(): Pair<Double, Double>? = client.location()

    /** Runs a Flow card; failures show in the error banner. */
    suspend fun runAction(card: FlowCardAction, args: JsonObject = JsonObject(emptyMap())) {
        try {
            client.runAction(card, args)
        } catch (e: Exception) {
            _snapshot.update { it.copy(error = "Couldn't run ${card.title ?: card.cardId}: ${e.message}") }
        }
    }

    suspend fun flows(): List<HomeyFlow> = client.flows()

    /** Starts a Flow; failures show in the error banner. */
    suspend fun triggerFlow(flow: HomeyFlow) {
        try {
            client.triggerFlow(flow)
        } catch (e: Exception) {
            _snapshot.update { it.copy(error = "Couldn't start ${flow.name}: ${e.message}") }
        }
    }

    suspend fun autocomplete(card: FlowCardAction, arg: String, query: String = ""): List<AutocompleteItem> =
        client.autocomplete(card, arg, query)

    fun clearError() = _snapshot.update { it.copy(error = null) }

    /** Shows a message from the app itself in the error banner. */
    fun reportError(message: String) = _snapshot.update { it.copy(error = message) }

    internal fun apply(event: HomeyEvent) {
        when (event) {
            is HomeyEvent.CapabilityChanged -> _snapshot.update { snap ->
                val device = snap.devices[event.deviceId] ?: return@update snap
                val updated = device.withCapabilityValue(event.capabilityId, event.value)
                if (updated == device) snap else snap.copy(devices = snap.devices + (device.id to updated))
            }
            is HomeyEvent.DeviceChanged -> {
                realtime.subscribe(HomeyRealtime.deviceUri(event.device.id))
                _snapshot.update { it.copy(devices = it.devices + (event.device.id to event.device)) }
            }
            is HomeyEvent.DeviceDeleted -> _snapshot.update { it.copy(devices = it.devices - event.deviceId) }
            is HomeyEvent.ZoneChanged -> _snapshot.update { it.copy(zones = it.zones + (event.zone.id to event.zone)) }
            is HomeyEvent.ZoneDeleted -> _snapshot.update { it.copy(zones = it.zones - event.zoneId) }
            is HomeyEvent.MoodChanged -> _snapshot.update { it.copy(moods = it.moods + (event.mood.id to event.mood)) }
            is HomeyEvent.MoodDeleted -> _snapshot.update { it.copy(moods = it.moods - event.moodId) }
            HomeyEvent.Reconnected -> scope.launch { reload() }
        }
    }
}
