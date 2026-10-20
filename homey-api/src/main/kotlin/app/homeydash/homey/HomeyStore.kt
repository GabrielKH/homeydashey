package app.homeydash.homey

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    /** Cards the app runs itself, e.g. Sonos favourites played straight on the speakers. */
    private val extraCards: List<CardSource> = emptyList(),
    /** Where the household keeps what only the tablet knows (skipped alarms); null keeps it in memory. */
    householdDir: java.io.File? = null,
) {
    /** Homey's users (who is home or asleep) and wake-up alarms. */
    val household = HouseholdStore(client, scope, householdDir)

    private val _snapshot = MutableStateFlow(HomeySnapshot())
    val snapshot: StateFlow<HomeySnapshot> = _snapshot.asStateFlow()
    val connection: StateFlow<ConnectionState> = realtime.state

    private var eventsJob: Job? = null

    /** What the owner set on this tablet per device: a class, other names, a card group. */
    data class OwnerNotes(
        val classes: Map<String, String> = emptyMap(),
        val nicknames: Map<String, List<String>> = emptyMap(),
        val groups: Map<String, String> = emptyMap(),
    )

    @Volatile private var notes = OwnerNotes()

    /** Applies the owner's notes to every device, now and as Homey sends updates. */
    fun setOwnerNotes(notes: OwnerNotes) {
        if (notes == this.notes) return
        this.notes = notes
        _snapshot.update { s -> s.copy(devices = s.devices.mapValues { noted(it.value) }) }
    }

    private fun noted(d: Device): Device {
        val cls = notes.classes[d.id]
        val names = notes.nicknames[d.id].orEmpty()
        val group = notes.groups[d.id]
        // Unchanged devices stay the same object, so cards that didn't change don't redraw.
        return if (cls == d.classOverride && names == d.nicknames && group == d.userGroup) d
        else d.copy(classOverride = cls, nicknames = names, userGroup = group)
    }

    fun start() {
        if (eventsJob != null) return
        eventsJob = scope.launch {
            // Homey sends a burst of events at once (energy meters, a room switching), and each
            // new snapshot redraws the dashboard. Events arriving close together become one update.
            val queue = Channel<HomeyEvent>(Channel.UNLIMITED)
            launch { realtime.events.collect { queue.send(it) } }
            while (true) {
                val first = queue.receive()
                delay(BATCH_MS)
                val batch = mutableListOf(first)
                while (true) batch += queue.tryReceive().getOrNull() ?: break
                applyAll(batch)
            }
        }
        realtime.subscribe(HomeyRealtime.DEVICES_URI)
        realtime.subscribe(HomeyRealtime.ZONES_URI)
        realtime.subscribe(HomeyRealtime.MOODS_URI)
        realtime.subscribe(HomeyRealtime.USERS_URI)
        realtime.subscribe(HomeyRealtime.ALARMS_URI)
        household.start()
        scope.launch { reload() }
        realtime.connect()
    }

    fun stop() {
        eventsJob?.cancel()
        eventsJob = null
        household.stop()
        realtime.close()
    }

    suspend fun reload() {
        try {
            // The five requests go out together, so a reconnect waits for one round trip instead of
            // five. Failures are still reported in the same order: zones first, then devices.
            val (zones, devices, moods, hiddenByUser, deviceCards) = coroutineScope {
                val zones = async { runCatching { client.zones() } }
                val devices = async { runCatching { client.devices() } }
                // Moods need their own permission on the API key; the dashboard works without them.
                val moods = async { runCatching { client.moods() }.getOrDefault(emptyMap()) }
                val hiddenByUser = async { runCatching { hiddenDeviceIds(client.userMe()) }.getOrDefault(emptySet()) }
                val deviceCards = async { runCatching { FlowCards.byDevice(client.flowCardActions()) }.getOrDefault(emptyMap()) }
                Loaded(zones.await().getOrThrow(), devices.await().getOrThrow(), moods.await(), hiddenByUser.await(), deviceCards.await())
            }
            devices.keys.forEach { realtime.subscribe(HomeyRealtime.deviceUri(it)) }
            _snapshot.value = HomeySnapshot(zones = zones, devices = devices.mapValues { noted(it.value) }, moods = moods, hiddenByUser = hiddenByUser, deviceCards = deviceCards, loaded = true)
            if (extraCards.isNotEmpty()) scope.launch { addExtraCards(deviceCards) }
        } catch (e: Exception) {
            _snapshot.update { it.copy(error = e.message ?: e.toString()) }
        }
    }

    private data class Loaded(
        val zones: Map<String, Zone>,
        val devices: Map<String, Device>,
        val moods: Map<String, Mood>,
        val hiddenByUser: Set<String>,
        val deviceCards: Map<String, List<FlowCardAction>>,
    )

    /** Why the app's own cards for [deviceId] are missing, if any source knows. */
    fun missingCards(deviceId: String): String? = extraCards.mapNotNull { it.missing(deviceId) }.joinToString(" ").ifEmpty { null }

    /** Adds each [CardSource]'s cards to Homey's own, skipping any a device already has from Homey. */
    private suspend fun addExtraCards(homeyCards: Map<String, List<FlowCardAction>>) {
        val snapshot = _snapshot.value
        val extra = extraCards.map { source -> runCatching { source.cards(snapshot) }.getOrDefault(emptyMap()) }
        val merged = homeyCards.toMutableMap()
        extra.forEach { cards ->
            cards.forEach { (deviceId, list) ->
                val own = merged[deviceId].orEmpty()
                merged[deviceId] = own + list.filter { c -> own.none { it.cardId == c.cardId } }
            }
        }
        _snapshot.update { it.copy(deviceCards = merged) }
    }

    /**
     * Sets a capability, showing the new value at once and rolling back if Homey refuses.
     * With [rethrow], the refusal is also thrown, so the assistant can say it failed.
     */
    suspend fun setCapability(deviceId: String, capabilityId: String, value: JsonElement, rethrow: Boolean = false) {
        val before = _snapshot.value.devices[deviceId]
        apply(HomeyEvent.CapabilityChanged(deviceId, capabilityId, value))
        try {
            client.setCapability(deviceId, capabilityId, value)
        } catch (e: Exception) {
            if (before != null) apply(HomeyEvent.DeviceChanged(before))
            _snapshot.update { it.copy(error = "Couldn't change ${before?.name ?: "device"}: ${e.message}") }
            if (rethrow) throw e
        }
    }

    /**
     * Asks Homey for [deviceId] again and shows what it says, so a change that was accepted
     * but didn't happen (a lamp out of reach) stops showing as done. Returns [capabilityId]'s value.
     */
    suspend fun readBack(deviceId: String, capabilityId: String): JsonElement? = runCatching {
        val device = client.device(deviceId)
        apply(HomeyEvent.DeviceChanged(device))
        device.capabilitiesObj[capabilityId]?.value
    }.getOrNull()

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

    /** Runs a Flow card; failures show in the error banner, and with [rethrow] are thrown too. */
    suspend fun runAction(card: FlowCardAction, args: JsonObject = JsonObject(emptyMap()), rethrow: Boolean = false) {
        try {
            val source = extraCards.firstOrNull { it.owns(card) }
            if (source != null) source.run(card, args) else client.runAction(card, args)
        } catch (e: Exception) {
            _snapshot.update { it.copy(error = "Couldn't run ${card.title ?: card.cardId}: ${e.message}") }
            if (rethrow) throw e
        }
    }

    suspend fun flows(): List<HomeyFlow> = client.flows()

    /** The apps installed on Homey, or null when the API key may not list them. */
    suspend fun apps(): Map<String, HomeyApp>? = try {
        client.apps()
    } catch (e: HomeyHttpException) {
        if (e.code == 401 || e.code == 403) null else throw e
    }

    suspend fun runAppAction(appId: String, cardId: String, args: JsonObject = JsonObject(emptyMap())): JsonElement =
        client.runAppAction(appId, cardId, args)

    suspend fun users(): JsonElement = client.users()

    suspend fun logicVariables(): JsonElement = client.logicVariables()

    /** Starts a Flow; failures show in the error banner. */
    suspend fun triggerFlow(flow: HomeyFlow) {
        try {
            client.triggerFlow(flow)
        } catch (e: Exception) {
            _snapshot.update { it.copy(error = "Couldn't start ${flow.name}: ${e.message}") }
        }
    }

    suspend fun autocomplete(card: FlowCardAction, arg: String, query: String = ""): List<AutocompleteItem> =
        extraCards.firstOrNull { it.owns(card) }?.autocomplete(card, arg, query) ?: client.autocomplete(card, arg, query)

    fun clearError() = _snapshot.update { it.copy(error = null) }

    /** Shows a message from the app itself in the error banner. */
    fun reportError(message: String) = _snapshot.update { it.copy(error = message) }

    /** Applies [events] in order; runs of capability changes become a single new snapshot. */
    internal fun applyAll(events: List<HomeyEvent>) {
        var i = 0
        while (i < events.size) {
            val event = events[i]
            if (event !is HomeyEvent.CapabilityChanged) {
                apply(event)
                i++
                continue
            }
            var end = i
            while (end < events.size && events[end] is HomeyEvent.CapabilityChanged) end++
            val run = events.subList(i, end).map { it as HomeyEvent.CapabilityChanged }
            _snapshot.update { snap ->
                var devices: MutableMap<String, Device>? = null
                for (change in run) {
                    val device = (devices ?: snap.devices)[change.deviceId] ?: continue
                    val updated = device.withCapabilityValue(change.capabilityId, change.value)
                    if (updated !== device) (devices ?: snap.devices.toMutableMap().also { devices = it })[device.id] = updated
                }
                devices?.let { snap.copy(devices = it) } ?: snap
            }
            i = end
        }
    }

    internal fun apply(event: HomeyEvent) {
        when (event) {
            is HomeyEvent.CapabilityChanged -> _snapshot.update { snap ->
                val device = snap.devices[event.deviceId] ?: return@update snap
                val updated = device.withCapabilityValue(event.capabilityId, event.value)
                if (updated === device) snap else snap.copy(devices = snap.devices + (device.id to updated))
            }
            is HomeyEvent.DeviceChanged -> {
                realtime.subscribe(HomeyRealtime.deviceUri(event.device.id))
                _snapshot.update { it.copy(devices = it.devices + (event.device.id to noted(event.device))) }
            }
            is HomeyEvent.DeviceDeleted -> _snapshot.update { it.copy(devices = it.devices - event.deviceId) }
            is HomeyEvent.ZoneChanged -> _snapshot.update { it.copy(zones = it.zones + (event.zone.id to event.zone)) }
            is HomeyEvent.ZoneDeleted -> _snapshot.update { it.copy(zones = it.zones - event.zoneId) }
            is HomeyEvent.MoodChanged -> _snapshot.update { it.copy(moods = it.moods + (event.mood.id to event.mood)) }
            is HomeyEvent.MoodDeleted -> _snapshot.update { it.copy(moods = it.moods - event.moodId) }
            is HomeyEvent.UserChanged, is HomeyEvent.UserDeleted, is HomeyEvent.AlarmChanged, is HomeyEvent.AlarmDeleted -> household.onEvent(event)
            HomeyEvent.Reconnected -> scope.launch {
                reload()
                household.reload()
            }
        }
    }

    private companion object {
        /** How long to gather realtime events before showing them; short enough to feel instant. */
        const val BATCH_MS = 120L
    }
}
