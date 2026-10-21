package app.homeydash.agent

import app.homeydash.homey.HomeyFlow
import app.homeydash.homey.Mood
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class CalendarBookTest {
    private val today = LocalDate.of(2026, 10, 21)

    private val calendars = listOf(
        AgentCalendar("1", "gabriel.khalaf@gmail.com", "gabriel.khalaf@gmail.com", "gabriel.khalaf@gmail.com"),
        AgentCalendar("2", "Familj", "gabriel.khalaf@gmail.com", "abc@group.calendar.google.com"),
        AgentCalendar("3", "anna@example.com", "anna@example.com", "anna@example.com"),
        AgentCalendar("4", "Holidays in Sweden", "gabriel.khalaf@gmail.com"),
    )

    private val people = mapOf("u-gabriel" to "Gabriel", "u-anna" to "Anna")
    private val emails = mapOf("u-gabriel" to "Gabriel.Khalaf@gmail.com", "u-anna" to "anna@example.com")
    private val gabriel = Asker("u-gabriel", "Gabriel")

    private fun settings(asker: Asker? = gabriel, family: String = CalendarBook.GUESS) = AgentSettings(
        secrets = mapOf(Secret.Calendar to Access.On),
        asker = asker,
        people = people,
        calendarAccounts = emails,
        familyCalendar = family,
    )

    private fun picked(p: CalendarBook.Pick) = (p as? CalendarBook.Pick.Found)?.calendar?.id

    @Test
    fun mineIsTheAskersAccountAndFamilyIsFoundByName() {
        assertEquals("1", picked(CalendarBook.pick("mine", calendars, settings())))
        assertEquals("1", picked(CalendarBook.pick("min kalender", calendars, settings())))
        assertEquals("2", picked(CalendarBook.pick("family", calendars, settings())))
        assertEquals("2", picked(CalendarBook.pick("familjekalendern", calendars, settings())))
        assertEquals("3", picked(CalendarBook.pick("Annas kalender", calendars, settings())))
        // A chosen family calendar wins over the name guess.
        assertEquals("4", picked(CalendarBook.pick("family", calendars, settings(family = "4"))))
    }

    @Test
    fun unsaidCalendarIsTheUsualThenTheFamilys() {
        assertEquals("2", picked(CalendarBook.pick("", calendars, settings())))
        assertEquals("1", picked(CalendarBook.pick("", calendars, settings(), usual = "1")))
    }

    @Test
    fun anUnknownVoiceHasNoOwnCalendar() {
        val p = CalendarBook.pick("mine", calendars, settings(asker = null))
        assertTrue(p is CalendarBook.Pick.Problem && p.text.contains("don't know who is asking"))
    }

    @Test
    fun theUsualTimeLengthAndCalendarAreLearned() {
        val at = ZonedDateTime.of(2026, 10, 21, 9, 0, 0, 0, ZoneId.of("Europe/Stockholm"))
        val padel = NewEvent("2", "padel", today.plusDays(6), LocalTime.of(18, 0), 90)
        val habit = CalendarBook.habit(padel, "the family calendar", gabriel, at)
        assertEquals("Padel at 18:00 for 90 minutes, in the family calendar", habit.obj)
        val usual = CalendarBook.usual(listOf(habit), "Padel med Erik", "u-gabriel")
        assertNotNull(usual)
        assertEquals(LocalTime.of(18, 0), usual!!.time)
        assertEquals(90, usual.minutes)
        assertEquals("2", usual.calendarId)
        assertNull(CalendarBook.usual(listOf(habit), "Tandläkare", "u-gabriel"))
    }

    @Test
    fun timesAreRead() {
        assertEquals(LocalTime.of(14, 0), CalendarBook.time("14:00").getOrNull())
        assertEquals(LocalTime.of(9, 30), CalendarBook.time("9.30").getOrNull())
        assertNull(CalendarBook.time("all day").getOrThrow())
        assertTrue(CalendarBook.time("two o'clock").isFailure)
    }

    private class Home : HomeControl {
        override suspend fun setCapability(deviceId: String, capability: String, value: JsonElement) {}
        override suspend fun setMood(mood: Mood) {}
        override suspend fun flows() = emptyList<HomeyFlow>()
        override suspend fun triggerFlow(flow: HomeyFlow) {}
        override suspend fun people() = emptyList<HomeyPerson>()
        override suspend fun variables() = emptyList<LogicVariable>()
        override suspend fun playMusic(deviceId: String, query: String): String? = null
    }

    private inner class Dash : DashboardControl {
        val added = mutableListOf<NewEvent>()
        override fun openPage(page: String, roomId: String?) = true
        override fun openDevice(deviceId: String) = true
        override fun goodnightPreview() = ""
        override fun goodnight() {}
        override suspend fun weather(): String? = null
        override suspend fun calendar(from: LocalDate, days: Int, withLocations: Boolean) = emptyList<AgentEvent>()
        override suspend fun writableCalendars() = calendars
        override suspend fun addEvent(event: NewEvent) { added += event }
        override val timers = TimerBoard(null)
        override fun setTheme(mode: String) = true
    }

    private fun call(vararg args: Pair<String, Any>) = ToolCall(
        "c1",
        "add_event",
        JsonObject(args.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) }),
    )

    @Test
    fun addEventAsksForAYesThenAddsToTheAskersCalendar() = runTest {
        val dash = Dash()
        val view = HomeView.build(emptyMap(), emptyMap(), emptyMap(), emptySet(), settings(), true)
        val tools = AgentTools(view, Home(), dash, today = { today })
        val r = tools.run(call("title" to "Tandläkare", "date" to "2026-10-23", "time" to "14:00", "in_calendar" to "min"))
        assertTrue(r.text, r.text.contains("Gabriel's calendar") && r.text.contains("14:00"))
        assertTrue(dash.added.isEmpty())
        assertEquals("Added to Gabriel's calendar.", r.pending!!.run())
        assertEquals(NewEvent("1", "Tandläkare", LocalDate.of(2026, 10, 23), LocalTime.of(14, 0), 60), dash.added.single())
    }

    @Test
    fun guestsCantAddEvents() = runTest {
        val view = HomeView.build(emptyMap(), emptyMap(), emptyMap(), emptySet(), settings().forRole(Role.Guest), true)
        val tools = AgentTools(view, Home(), Dash(), today = { today })
        assertTrue(tools.specs().none { it.name == "add_event" })
    }
}
