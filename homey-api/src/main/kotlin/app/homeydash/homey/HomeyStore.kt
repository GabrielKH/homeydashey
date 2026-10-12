package app.homeydash.homey

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

data class HomeySnapshot(
    val zones: Map<String, Zone> = emptyMap(),
    val devices: Map<String, Device> = emptyMap(),
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
            devices.keys.forEach { realtime.subscribe(HomeyRealtime.deviceUri(it)) }
            _snapshot.value = HomeySnapshot(zones = zones, devices = devices, loaded = true)
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

    fun clearError() = _snapshot.update { it.copy(error = null) }

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
            HomeyEvent.Reconnected -> scope.launch { reload() }
        }
    }
}
