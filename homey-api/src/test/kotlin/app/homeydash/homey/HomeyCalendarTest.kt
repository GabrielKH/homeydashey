package app.homeydash.homey

import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeyCalendarTest {
    private val zone = ZoneId.of("Europe/Stockholm")

    @Test fun `reads the date formats IcalCalendar is set to`() {
        assertEquals(LocalDateTime.of(2026, 10, 20, 14, 30), HomeyCalendar.parseDateTime("26-10-20 14:30"))
        assertEquals(LocalDateTime.of(2026, 10, 20, 14, 30), HomeyCalendar.parseDateTime("20.10.26 14:30"))
        assertEquals(LocalDateTime.of(2026, 10, 20, 14, 30), HomeyCalendar.parseDateTime("10/20/26 14:30"))
        assertEquals(LocalDateTime.of(2026, 10, 20, 14, 30), HomeyCalendar.parseDateTime("20/10/2026 14:30"))
        assertEquals(LocalDateTime.of(2026, 10, 20, 9, 5), HomeyCalendar.parseDateTime("2026-10-20 09:05"))
        assertEquals(LocalDateTime.of(2026, 10, 20, 14, 0), HomeyCalendar.parseDateTime("20 oktober 2026 14:00"))
        assertEquals(LocalDateTime.of(2026, 10, 20, 14, 0), HomeyCalendar.parseDateTime("October 20, 2026 2:00 PM"))
        assertEquals(LocalDateTime.of(2026, 10, 20, 0, 0), HomeyCalendar.parseDateTime("26-10-20"))
        assertNull(HomeyCalendar.parseDateTime("soon"))
    }

    @Test fun `parses the metadata card's calendars`() {
        val json = """[{"calendarName":"Familj","events":[0,1,2]},{"calendarName":"Skola","events":[]}]"""
        val result = JsonObject(mapOf("json" to JsonPrimitive(json)))
        assertEquals(listOf("Familj" to listOf(0, 1, 2), "Skola" to emptyList()), HomeyCalendar.parseMetadata(result))
    }

    @Test fun `parses an event, all-day ones from midnight`() {
        val tokens = JsonObject(mapOf(
            "event_start" to JsonPrimitive("26-10-21 00:00"),
            "event_end" to JsonPrimitive("26-10-22 00:00"),
            "event_uid" to JsonPrimitive("u1"),
            "event_name" to JsonPrimitive("Studiedag"),
            "event_location" to JsonPrimitive(""),
            "event_fullday_event" to JsonPrimitive(true),
            "event_duration" to JsonPrimitive(1440),
        ))
        val e = HomeyCalendar.parseEvent(JsonObject(mapOf("returnTokens" to tokens)), "Skola", zone)!!
        assertEquals("Studiedag", e.title)
        assertTrue(e.allDay)
        assertNull(e.location)
        assertEquals(LocalDateTime.of(2026, 10, 21, 0, 0).atZone(zone).toInstant().toEpochMilli(), e.begin)
        assertEquals(24 * 60 * 60 * 1000L, e.end - e.begin)
    }

    @Test fun `runs the cards for each event and sorts them`() = runTest {
        val calendar = HomeyCalendar(run = { card, args ->
            when (card) {
                "get_calendars_metadata" -> JsonObject(mapOf("json" to JsonPrimitive("""[{"calendarName":"Familj","events":[0,1]}]""")))
                else -> {
                    assertEquals("Familj", args["calendar"]!!.jsonObject["name"]!!.jsonPrimitive.content)
                    val i = args["index"]!!.jsonPrimitive.int
                    JsonObject(mapOf(
                        "event_start" to JsonPrimitive(if (i == 0) "26-10-22 18:00" else "26-10-21 08:00"),
                        "event_name" to JsonPrimitive("Event $i"),
                        "event_duration" to JsonPrimitive(30),
                    ))
                }
            }
        }, zone = { zone })
        val events = calendar.events()
        assertEquals(listOf("Event 1", "Event 0"), events.map { it.title })
        assertEquals(30 * 60 * 1000L, events[0].end - events[0].begin)
    }
}
