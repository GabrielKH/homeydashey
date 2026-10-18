package app.homeydash.agent

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Dates and times as a person says them ("today", "tomorrow", "Tuesday 20 October"), for what
 * the assistant reads out: a voice reads "2026-10-20" as "2026 dash 10 dash 20".
 */
object SpokenDates {
    private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    fun day(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DAY)
    }

    /** [date] written as 2026-10-20, in words; left as it is when it isn't a date. */
    fun day(date: String, today: LocalDate): String =
        runCatching { day(LocalDate.parse(date.trim()), today) }.getOrDefault(date)

    /**
     * When an event starts and ends, in words. An event over several days keeps its end day
     * ("Friday 16 October 09:00" until "Sunday 18 October 10:00"), so a trip that started
     * earlier doesn't sound like it starts today. [end] of an all-day event is the day after
     * its last day, as calendars store it.
     */
    fun event(start: LocalDateTime, end: LocalDateTime, allDay: Boolean, today: LocalDate): Pair<String, String?> {
        if (allDay) {
            val last = end.toLocalDate().minusDays(1).coerceAtLeast(start.toLocalDate())
            val days = if (last == start.toLocalDate()) day(last, today) else "${day(start.toLocalDate(), today)} to ${day(last, today)}"
            return "$days (all day)" to null
        }
        val from = "${day(start.toLocalDate(), today)} ${start.format(TIME)}"
        val until = if (end.toLocalDate() == start.toLocalDate()) end.format(TIME) else "${day(end.toLocalDate(), today)} ${end.format(TIME)}"
        return from to until
    }

    private val ISO = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")
    private val EN_MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val SV_MONTHS = listOf("januari", "februari", "mars", "april", "maj", "juni", "juli", "augusti", "september", "oktober", "november", "december")

    /**
     * [text] with dates written as 2026-10-18 turned into "18 October" (the year only when it
     * isn't [year]), as a last guard before it is spoken.
     */
    fun speakable(text: String, swedish: Boolean, year: Int): String = ISO.replace(text) { m ->
        val (y, mo, d) = m.destructured
        val month = mo.toInt()
        if (month !in 1..12 || d.toInt() !in 1..31) return@replace m.value
        val name = (if (swedish) SV_MONTHS else EN_MONTHS)[month - 1]
        "${d.toInt()} $name" + if (y.toInt() != year) " ${y.toInt()}" else ""
    }
}
