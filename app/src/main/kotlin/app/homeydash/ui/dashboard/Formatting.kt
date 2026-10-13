package app.homeydash.ui.dashboard

import app.homeydash.homey.Capability
import java.util.Locale
import kotlin.math.round

/** Human-readable value of a capability, e.g. "21.5 °C", "40%", "On". */
fun formatValue(capability: Capability): String {
    capability.booleanValue?.let { on ->
        return when {
            capability.id.startsWith("alarm_") -> if (on) "Alert" else "OK"
            else -> if (on) "On" else "Off"
        }
    }
    capability.numberValue?.let { number ->
        // Homey reports dim, volume and positions as 0–1 with "%" as the unit.
        if (capability.units == "%" && (capability.max ?: 100.0) <= 1.0) {
            return "${round(number * 100).toInt()}%"
        }
        val decimals = capability.decimals ?: if (number % 1.0 == 0.0) 0 else 1
        val text = String.format(Locale.getDefault(), "%.${decimals}f", number)
        return capability.units?.let { units -> if (units == "%") "$text%" else "$text $units" } ?: text
    }
    capability.values?.let { options ->
        val id = capability.stringValue
        options.firstOrNull { it.id == id }?.title?.let { return it }
    }
    return capability.stringValue ?: "–"
}

/** Next value one step up or down, clamped to the capability's range. */
fun stepTo(capability: Capability, direction: Int): Double? {
    val current = capability.numberValue ?: return null
    val step = capability.step ?: 0.5
    val next = current + step * direction
    val min = capability.min ?: Double.NEGATIVE_INFINITY
    val max = capability.max ?: Double.POSITIVE_INFINITY
    return next.coerceIn(min, max)
}

/** A number capability's value as 0–1 of its range (dim, volume, blind position). */
fun fraction(capability: Capability): Float? {
    val value = capability.numberValue ?: return null
    val min = capability.min ?: 0.0
    val max = capability.max ?: 1.0
    if (max <= min) return null
    return ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)
}

/** The capability value for a 0–1 [fraction] of its range, snapped to its step. */
fun valueAt(capability: Capability, fraction: Float): Double {
    val min = capability.min ?: 0.0
    val max = capability.max ?: 1.0
    val raw = min + (max - min) * fraction.coerceIn(0f, 1f)
    val step = capability.step?.takeIf { it > 0 } ?: return raw
    return (round((raw - min) / step) * step + min).coerceIn(min, max)
}

fun percent(fraction: Float): String = "${round(fraction * 100).toInt()}%"

/** "21.5°" for temperatures on cards, where the unit is obvious. */
fun shortTemperature(value: Double): String = String.format(Locale.getDefault(), "%.1f°", value)
