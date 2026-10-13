package app.homeydash.homey

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** A Homey Flow that can be started from the dashboard. */
data class HomeyFlow(val id: String, val name: String, val advanced: Boolean)

/** What Goodnight changes on one device. */
data class GoodnightStep(val device: Device, val capability: String)

/** Goodnight: turn off everything that's on and pause what's playing, except what the user keeps. */
object Goodnight {

    /** What Goodnight would do now: switch off what's on, pause speakers that are playing. */
    fun steps(devices: Collection<Device>, keep: Set<String>): List<GoodnightStep> =
        devices.filter { it.available && it.id !in keep }.mapNotNull { device ->
            val onoff = device.capability("onoff")
            val playing = device.capability("speaker_playing")
            when {
                onoff != null && onoff.setable && onoff.booleanValue == true -> GoodnightStep(device, "onoff")
                playing != null && playing.setable && playing.booleanValue == true -> GoodnightStep(device, "speaker_playing")
                else -> null
            }
        }.sortedBy { it.device.name.lowercase() }

    /** Devices Goodnight could ever change, for choosing exceptions. */
    fun candidates(devices: Collection<Device>): List<Device> =
        devices.filter { d -> d.capability("onoff")?.setable == true || d.capability("speaker_playing")?.setable == true }
            .sortedBy { it.name.lowercase() }

    /** Parses `GET /api/manager/flow/flow` or `/advancedflow`: flows that can be started, by name. */
    fun parseFlows(json: JsonElement, advanced: Boolean): List<HomeyFlow> {
        val items = (json as? JsonObject)?.values ?: return emptyList()
        return items.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val triggerable = (obj["triggerable"] as? JsonPrimitive)?.booleanOrNull
            if (triggerable == false) return@mapNotNull null
            HomeyFlow(id, name, advanced)
        }.sortedBy { it.name.lowercase() }
    }
}
