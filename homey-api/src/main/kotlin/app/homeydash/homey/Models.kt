package app.homeydash.homey

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Answer of the unauthenticated `GET /api/manager/system/local` endpoint. */
@Serializable
data class HomeyInfo(
    val homeyId: String,
    val homeyVersion: String? = null,
    val homeyModelId: String? = null,
    val homeyModelName: String? = null,
)

/** A room or area. Zones nest through [parent]; the top zone has no parent. */
@Serializable
data class Zone(
    val id: String,
    val name: String,
    val parent: String? = null,
    val icon: String? = null,
    val active: Boolean = false,
)

@Serializable
data class Device(
    val id: String,
    val name: String,
    val zone: String? = null,
    @SerialName("class") val deviceClass: String = "other",
    val virtualClass: String? = null,
    val capabilities: List<String> = emptyList(),
    val capabilitiesObj: Map<String, Capability> = emptyMap(),
    val available: Boolean = true,
    val ready: Boolean = true,
    val uiIndicator: String? = null,
    val iconObj: IconObj? = null,
) {
    /** Class used for picking a card: a socket switching a lamp reports `virtualClass = light`. */
    val effectiveClass: String get() = virtualClass ?: deviceClass

    fun capability(id: String): Capability? = capabilitiesObj[id]

    /** Returns a copy with one capability value replaced, as a realtime update does. */
    fun withCapabilityValue(capabilityId: String, value: JsonElement): Device {
        val current = capabilitiesObj[capabilityId] ?: return this
        return copy(capabilitiesObj = capabilitiesObj + (capabilityId to current.copy(value = value)))
    }
}

@Serializable
data class IconObj(
    val id: String? = null,
    val url: String? = null,
)

@Serializable
data class Capability(
    val id: String,
    /** `boolean`, `number`, `string` or `enum`. */
    val type: String = "string",
    val title: String? = null,
    val value: JsonElement = JsonNull,
    val units: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val decimals: Int? = null,
    val getable: Boolean = true,
    val setable: Boolean = false,
    val values: List<EnumValue>? = null,
) {
    val booleanValue: Boolean? get() = (value as? JsonPrimitive)?.booleanOrNull
    val numberValue: Double? get() = (value as? JsonPrimitive)?.doubleOrNull
    val stringValue: String? get() = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
data class EnumValue(
    val id: String,
    val title: String? = null,
)
