package app.homeydash.homey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherTest {
    @Test fun `open-meteo forecast parses`() {
        val json = HomeyJson.parseToJsonElement(
            """
            {"current": {"temperature_2m": 12.4, "apparent_temperature": 10.1, "relative_humidity_2m": 80, "weather_code": 61, "is_day": 1, "wind_speed_10m": 4.2},
             "hourly": {"time": ["2026-10-13T12:00", "2026-10-13T13:00"], "temperature_2m": [12.0, 12.6], "weather_code": [61, 3],
                        "precipitation_probability": [70, null], "is_day": [1, 1]},
             "daily": {"time": ["2026-10-13", "2026-10-14"], "weather_code": [61, 0], "temperature_2m_max": [13.2, 15.0],
                       "temperature_2m_min": [7.1, 6.0], "precipitation_probability_max": [80, 5]}}
            """.trimIndent(),
        )
        val forecast = Weather.parseForecast(json)
        assertEquals(12.4, forecast.current.temperature, 0.001)
        assertEquals(WeatherKind.Rain, forecast.current.kind)
        assertTrue(forecast.current.isDay)
        assertEquals(listOf(70, null), forecast.hours.map { it.rainChance })
        assertEquals(WeatherKind.Cloudy, forecast.hours[1].kind)
        assertEquals(listOf(WeatherKind.Rain, WeatherKind.Clear), forecast.days.map { it.kind })
        assertEquals(6.0, forecast.days[1].min, 0.001)
    }

    @Test fun `places and ip location parse`() {
        val places = Weather.parsePlaces(
            HomeyJson.parseToJsonElement("""{"results": [{"name": "Stockholm", "latitude": 59.33, "longitude": 18.07, "country": "Sweden", "admin1": "Stockholm"}]}"""),
        )
        assertEquals("Stockholm", places.single().name)
        assertEquals("Stockholm, Sweden", places.single().detail)
        assertEquals(emptyList<Place>(), Weather.parsePlaces(HomeyJson.parseToJsonElement("""{"generationtime_ms": 1}""")))

        val ip = Weather.parseIpLocation(HomeyJson.parseToJsonElement("""{"success": true, "city": "Uppsala", "country": "Sweden", "latitude": 59.86, "longitude": 17.64}"""))
        assertEquals("Uppsala", ip?.name)
        assertNull(Weather.parseIpLocation(HomeyJson.parseToJsonElement("""{"success": false, "message": "x"}""")))
    }

    @Test fun `homey coordinates are found anywhere`() {
        assertEquals(59.3 to 18.1, Weather.findCoordinates(HomeyJson.parseToJsonElement("""{"value": {"latitude": 59.3, "longitude": 18.1, "accuracy": 10}}""")))
        assertNull(Weather.findCoordinates(HomeyJson.parseToJsonElement("""{"value": {"latitude": 0, "longitude": 0}}""")))
    }
}
