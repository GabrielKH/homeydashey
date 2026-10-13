package app.homeydash.homey

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** A place for the weather: a name to show and where it is. */
data class Place(val name: String, val detail: String?, val latitude: Double, val longitude: Double)

/** Weather conditions grouped the way the card draws them, from WMO weather codes. */
enum class WeatherKind(val label: String) {
    Clear("Clear"),
    PartlyCloudy("Partly cloudy"),
    Cloudy("Cloudy"),
    Fog("Fog"),
    Drizzle("Drizzle"),
    Rain("Rain"),
    Snow("Snow"),
    Storm("Thunderstorm"),
    ;

    companion object {
        fun of(code: Int): WeatherKind = when (code) {
            0, 1 -> Clear
            2 -> PartlyCloudy
            3 -> Cloudy
            45, 48 -> Fog
            in 51..57 -> Drizzle
            in 61..67, in 80..82 -> Rain
            in 71..77, 85, 86 -> Snow
            in 95..99 -> Storm
            else -> Cloudy
        }
    }
}

data class CurrentWeather(
    val temperature: Double,
    val feelsLike: Double?,
    val humidity: Double?,
    val wind: Double?,
    val kind: WeatherKind,
    val isDay: Boolean,
)

/** [time] is local ISO time, e.g. `2026-10-13T14:00`. */
data class HourWeather(val time: String, val temperature: Double, val kind: WeatherKind, val isDay: Boolean, val rainChance: Int?)

/** [date] is a local ISO date, e.g. `2026-10-13`. */
data class DayWeather(val date: String, val kind: WeatherKind, val max: Double, val min: Double, val rainChance: Int?)

data class Forecast(val current: CurrentWeather, val hours: List<HourWeather>, val days: List<DayWeather>)

/**
 * Weather from Open-Meteo (free, no account), place search from its geocoding API,
 * and a rough location from the internet address (ipwho.is).
 */
class WeatherClient(private val http: OkHttpClient) {

    suspend fun forecast(latitude: Double, longitude: Double): Forecast {
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", latitude.toString())
            .addQueryParameter("longitude", longitude.toString())
            .addQueryParameter("current", "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,wind_speed_10m")
            .addQueryParameter("hourly", "temperature_2m,weather_code,precipitation_probability,is_day")
            .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("forecast_days", "7")
            .addQueryParameter("wind_speed_unit", "ms")
            .build()
        return Weather.parseForecast(getJson(url.toString()))
    }

    suspend fun search(query: String, language: String = "en"): List<Place> {
        if (query.isBlank()) return emptyList()
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", query.trim())
            .addQueryParameter("count", "8")
            .addQueryParameter("language", language)
            .addQueryParameter("format", "json")
            .build()
        return Weather.parsePlaces(getJson(url.toString()))
    }

    /** Where the internet connection is, to the nearest town. */
    suspend fun ipLocation(): Place? = Weather.parseIpLocation(getJson("https://ipwho.is/"))

    private suspend fun getJson(url: String): JsonElement = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw HomeyHttpException(response.code, "Weather service answered ${response.code}")
            HomeyJson.parseToJsonElement(response.body?.string().orEmpty())
        }
    }
}

object Weather {

    fun parseForecast(json: JsonElement): Forecast {
        val root = json as JsonObject
        val current = root["current"] as JsonObject
        val currentForecast = CurrentWeather(
            temperature = current.num("temperature_2m") ?: 0.0,
            feelsLike = current.num("apparent_temperature"),
            humidity = current.num("relative_humidity_2m"),
            wind = current.num("wind_speed_10m"),
            kind = WeatherKind.of(current.num("weather_code")?.toInt() ?: 3),
            isDay = (current.num("is_day") ?: 1.0) > 0.5,
        )

        val hourly = root["hourly"] as? JsonObject
        val hourTimes = hourly.strings("time")
        val hourTemps = hourly.nums("temperature_2m")
        val hourCodes = hourly.nums("weather_code")
        val hourRain = hourly.nums("precipitation_probability")
        val hourDay = hourly.nums("is_day")
        val hours = hourTimes.indices.mapNotNull { i ->
            HourWeather(
                time = hourTimes[i],
                temperature = hourTemps.getOrNull(i) ?: return@mapNotNull null,
                kind = WeatherKind.of(hourCodes.getOrNull(i)?.toInt() ?: 3),
                isDay = (hourDay.getOrNull(i) ?: 1.0) > 0.5,
                rainChance = hourRain.getOrNull(i)?.toInt(),
            )
        }

        val daily = root["daily"] as? JsonObject
        val dates = daily.strings("time")
        val codes = daily.nums("weather_code")
        val maxes = daily.nums("temperature_2m_max")
        val mins = daily.nums("temperature_2m_min")
        val rain = daily.nums("precipitation_probability_max")
        val days = dates.indices.mapNotNull { i ->
            DayWeather(
                date = dates[i],
                kind = WeatherKind.of(codes.getOrNull(i)?.toInt() ?: 3),
                max = maxes.getOrNull(i) ?: return@mapNotNull null,
                min = mins.getOrNull(i) ?: return@mapNotNull null,
                rainChance = rain.getOrNull(i)?.toInt(),
            )
        }
        return Forecast(currentForecast, hours, days)
    }

    fun parsePlaces(json: JsonElement): List<Place> {
        val results = (json as? JsonObject)?.get("results") as? JsonArray ?: return emptyList()
        return results.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            Place(
                name = obj.str("name") ?: return@mapNotNull null,
                detail = listOfNotNull(obj.str("admin1"), obj.str("country")).distinct().joinToString(", ").ifEmpty { null },
                latitude = obj.num("latitude") ?: return@mapNotNull null,
                longitude = obj.num("longitude") ?: return@mapNotNull null,
            )
        }
    }

    fun parseIpLocation(json: JsonElement): Place? {
        val obj = json as? JsonObject ?: return null
        if ((obj["success"] as? JsonPrimitive)?.contentOrNull == "false") return null
        return Place(
            name = obj.str("city") ?: return null,
            detail = obj.str("country"),
            latitude = obj.num("latitude") ?: return null,
            longitude = obj.num("longitude") ?: return null,
        )
    }

    /** Finds the first latitude/longitude pair anywhere in a Homey geolocation answer. */
    fun findCoordinates(json: JsonElement): Pair<Double, Double>? = when (json) {
        is JsonObject -> {
            val lat = json.num("latitude") ?: json.num("lat")
            val lon = json.num("longitude") ?: json.num("lng") ?: json.num("lon")
            if (lat != null && lon != null && !(lat == 0.0 && lon == 0.0)) lat to lon
            else json.values.firstNotNullOfOrNull { findCoordinates(it) }
        }
        is JsonArray -> json.firstNotNullOfOrNull { findCoordinates(it) }
        else -> null
    }

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject?.nums(key: String): List<Double?> =
        (this?.get(key) as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull ?: (it as? JsonPrimitive)?.intOrNull?.toDouble() }.orEmpty()
    private fun JsonObject?.strings(key: String): List<String> =
        (this?.get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
}
