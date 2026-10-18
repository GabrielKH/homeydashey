package app.homeydash.data

import android.content.Context
import app.homeydash.homey.HomeySnapshot
import app.homeydash.homey.VacuumPart
import app.homeydash.homey.VacuumState
import app.homeydash.homey.Vacuums
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import app.homeydash.homey.HomeyJson

/** Cleaning hours the dashboard has counted for one robot, and when each part was last replaced. */
data class VacuumHours(
    val hours: Double = 0.0,
    /** Counted hours at each part's last replacement, by part key. */
    val replacedAt: Map<String, Double> = emptyMap(),
    /** When counting started (epoch ms). */
    val since: Long = System.currentTimeMillis(),
)

/** How worn a part is: from the robot when its app reports it, else estimated from counted hours. */
data class PartWear(val leftFraction: Double, val leftHours: Double?, val estimated: Boolean)

/**
 * Counts how long each robot vacuum cleans, so parts whose wear the Homey app doesn't
 * report (Roborock's filter and brushes) can be estimated from their typical life.
 * Everything it counts comes from the vacuum's state in Homey.
 */
class VacuumUsage(context: Context) {
    private val prefs = context.getSharedPreferences("vacuum_usage", Context.MODE_PRIVATE)
    private val _usage = MutableStateFlow(load())
    val usage: StateFlow<Map<String, VacuumHours>> = _usage.asStateFlow()

    fun wear(deviceId: String, part: VacuumPart): PartWear? {
        part.leftFraction?.let { return PartWear(it, part.leftHours, estimated = false) }
        val counted = _usage.value[deviceId] ?: return null
        val used = counted.hours - (counted.replacedAt[part.key] ?: 0.0)
        val left = (part.lifetimeHours - used).coerceAtLeast(0.0)
        return PartWear(left / part.lifetimeHours, left, estimated = true)
    }

    fun replaced(deviceId: String, partKey: String) {
        update(deviceId) { it.copy(replacedAt = it.replacedAt + (partKey to it.hours)) }
    }

    /**
     * Adds the time each vacuum spends cleaning. Runs while the dashboard is open, which
     * on a kiosk tablet is all the time; gaps longer than [MAX_STEP_MS] aren't counted.
     */
    suspend fun track(snapshot: StateFlow<HomeySnapshot>) {
        var last = System.currentTimeMillis()
        while (true) {
            delay(TICK_MS)
            val now = System.currentTimeMillis()
            val step = (now - last).coerceAtMost(MAX_STEP_MS)
            last = now
            snapshot.value.devices.values.filter(Vacuums::isVacuum).forEach { device ->
                val state = Vacuums.state(device)
                if (device.id !in _usage.value) update(device.id) { it }
                if (state == VacuumState.Cleaning) update(device.id) { it.copy(hours = it.hours + step / 3_600_000.0) }
            }
        }
    }

    private fun update(deviceId: String, change: (VacuumHours) -> VacuumHours) {
        _usage.update { all -> all + (deviceId to change(all[deviceId] ?: VacuumHours())) }
        save(deviceId, _usage.value.getValue(deviceId))
    }

    private fun save(deviceId: String, hours: VacuumHours) {
        val json = buildJsonObject {
            put("hours", hours.hours)
            put("since", hours.since)
            put("replacedAt", JsonObject(hours.replacedAt.mapValues { JsonPrimitive(it.value) }))
        }
        prefs.edit().putString(deviceId, json.toString()).apply()
    }

    private fun load(): Map<String, VacuumHours> = prefs.all.mapNotNull { (id, value) ->
        val obj = runCatching { HomeyJson.parseToJsonElement(value as String).jsonObject }.getOrNull() ?: return@mapNotNull null
        id to VacuumHours(
            hours = (obj["hours"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            replacedAt = (obj["replacedAt"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { k to it } }.toMap(),
            since = (obj["since"] as? JsonPrimitive)?.longOrNull ?: System.currentTimeMillis(),
        )
    }.toMap()

    companion object {
        private const val TICK_MS = 30_000L
        private const val MAX_STEP_MS = 90_000L

        @Volatile private var instance: VacuumUsage? = null

        /** One counter for the whole app, so the tracker and the cards share it. */
        fun get(context: Context): VacuumUsage =
            instance ?: synchronized(this) { instance ?: VacuumUsage(context.applicationContext).also { instance = it } }
    }
}
