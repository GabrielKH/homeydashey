package app.homeydash.homey

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Homey Insights: the history Homey keeps of a device's readings, for the 24-hour line in a
 * device's sheet. Reading it needs the API key's Insights permission; without it there is
 * simply no line.
 */
object Insights {
    /** The log id Homey gives a device capability's history. */
    fun deviceUri(deviceId: String) = "homey:device:$deviceId"

    /** Which readings get a line: numbers that change over the day. */
    fun hasLine(capability: Capability): Boolean =
        capability.numberValue != null && (capability.id.startsWith("measure_") || capability.id.startsWith("meter_")) &&
            capability.id != "measure_battery"

    /**
     * The values of a `.../entry` answer, oldest first. Homey answers `{"values": [{"t": …, "v": 21.3}, …]}`;
     * booleans count as 1 and 0, and empty points are left out.
     */
    fun parseValues(json: JsonElement): List<Double> {
        val values = (json as? JsonObject)?.get("values") as? JsonArray ?: (json as? JsonArray) ?: return emptyList()
        return values.mapNotNull { point ->
            val v = (point as? JsonObject)?.get("v") as? JsonPrimitive ?: return@mapNotNull null
            v.doubleOrNull ?: v.booleanOrNull?.let { if (it) 1.0 else 0.0 }
        }
    }
}
