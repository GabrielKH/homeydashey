package app.homeydash.agent

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class SpokenDatesTest {
    private val today = LocalDate.of(2026, 10, 18)

    @Test fun `days are said as today, tomorrow or by name`() {
        assertEquals("Today", SpokenDates.day(today, today))
        assertEquals("Tomorrow", SpokenDates.day(today.plusDays(1), today))
        assertEquals("Tuesday 20 October", SpokenDates.day(today.plusDays(2), today))
    }

    @Test fun `a trip that started on Friday keeps its end day`() {
        val (from, until) = SpokenDates.event(LocalDateTime.of(2026, 10, 16, 9, 0), LocalDateTime.of(2026, 10, 19, 10, 0), false, today)
        assertEquals("Friday 16 October 09:00", from)
        assertEquals("Tomorrow 10:00", until)
        assertEquals("Today 09:00" to "10:00", SpokenDates.event(LocalDateTime.of(2026, 10, 18, 9, 0), LocalDateTime.of(2026, 10, 18, 10, 0), false, today))
    }

    @Test fun `all-day events end the day before the calendar's end`() {
        assertEquals("Today (all day)" to null, SpokenDates.event(today.atStartOfDay(), today.plusDays(1).atStartOfDay(), true, today))
        assertEquals("Today to Tuesday 20 October (all day)" to null, SpokenDates.event(today.atStartOfDay(), today.plusDays(3).atStartOfDay(), true, today))
    }

    @Test fun `written dates are spoken in words`() {
        assertEquals("Fredag 16 oktober kl 09", SpokenDates.speakable("Fredag 2026-10-16 kl 09", swedish = true, year = 2026))
        assertEquals("On 16 October 2025.", SpokenDates.speakable("On 2025-10-16.", swedish = false, year = 2026))
        assertEquals("Code 2026-13-40", SpokenDates.speakable("Code 2026-13-40", swedish = false, year = 2026))
    }
}
