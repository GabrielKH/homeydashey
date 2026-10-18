package app.homeydash.agent

import app.homeydash.homey.HomeyAlarm
import app.homeydash.homey.HomeyEvent
import app.homeydash.homey.HomeyFlow
import app.homeydash.homey.HomeyJson
import app.homeydash.homey.HomeyRealtime
import app.homeydash.homey.HomeyUser
import app.homeydash.homey.Household
import app.homeydash.homey.Mood
import app.homeydash.homey.PresenceChange
import app.homeydash.homey.PresenceKind
import app.homeydash.homey.Zone
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class HouseholdTest {
    private val zone = ZoneId.of("Europe/Stockholm")
    // Sunday 18 October 2026, 21:30.
    private val now = ZonedDateTime.of(2026, 10, 18, 21, 30, 0, 0, zone)

    @Test
    fun readsUsersAndAlarmsAsHomeySendsThem() {
        val users = Household.parseUsers(HomeyJson.parseToJsonElement(
            """{"u1": {"id": "u1", "name": "Gabriel", "present": true, "asleep": false, "role": "owner", "enabled": true},
               "u2": {"id": "u2", "name": "Anna", "present": false, "asleep": null},
               "u3": {"id": "u3", "name": null}}""",
        ))
        assertEquals(listOf("Anna", "Gabriel"), users.map { it.name })
        assertEquals("away", Household.status(users[0]))

        val alarms = Household.parseAlarms(HomeyJson.parseToJsonElement(
            """{"a1": {"id": "a1", "name": "Väckning Gabriel", "time": "06:30", "enabled": true,
                 "repetition": {"monday": true, "tuesday": true, "wednesday": true, "thursday": true, "friday": true, "saturday": false, "sunday": false}},
               "a2": {"id": "a2", "name": "Helg", "time": "8:15", "enabled": false, "repetition": {}}}""",
        ))
        assertEquals(LocalTime.of(6, 30), alarms[0].time)
        assertEquals(Household.WEEKDAYS, alarms[0].days)
        assertEquals("weekdays", Household.daysText(alarms[0].days))
        assertEquals("once", Household.daysText(alarms[1].days))
    }

    @Test
    fun theBodySentToHomeyNamesEveryDay() {
        val body = Household.body("Väckning", LocalTime.of(6, 5), setOf(DayOfWeek.MONDAY), true)
        assertEquals("06:05", (body["time"] as JsonPrimitive).content)
        val rep = body["repetition"]!!.jsonObject
        assertEquals(7, rep.size)
        assertEquals("true", rep["monday"].toString())
        assertEquals("false", rep["sunday"].toString())
    }

    @Test
    fun nextRingSkipsDaysItDoesNotRepeatOn() {
        val weekdays = HomeyAlarm("a", "W", LocalTime.of(6, 30), true, Household.WEEKDAYS)
        assertEquals(ZonedDateTime.of(2026, 10, 19, 6, 30, 0, 0, zone), Household.nextRing(weekdays, now))
        val once = HomeyAlarm("b", "O", LocalTime.of(22, 0), true, emptySet())
        assertEquals(ZonedDateTime.of(2026, 10, 18, 22, 0, 0, 0, zone), Household.nextRing(once, now))
        assertNull(Household.nextRing(once.copy(enabled = false), now))
        assertEquals("tomorrow 06:30", Household.whenText(Household.nextRing(weekdays, now)!!, now))
        // Saturday only: next Saturday, by name.
        val sat = HomeyAlarm("c", "S", LocalTime.of(9, 0), true, setOf(DayOfWeek.SATURDAY))
        assertEquals("Saturday 09:00", Household.whenText(Household.nextRing(sat, now)!!, now))
    }

    @Test
    fun understandsTimesAndDaysTheWayModelsWriteThem() {
        assertEquals(LocalTime.of(6, 15), Household.parseTime("06:15"))
        assertEquals(LocalTime.of(6, 15), Household.parseTime("6.15"))
        assertEquals(LocalTime.of(6, 15), Household.parseTime("615"))
        assertEquals(LocalTime.of(7, 0), Household.parseTime("7"))
        assertEquals(LocalTime.of(19, 30), Household.parseTime("7:30 pm"))
        assertNull(Household.parseTime("halv sju"))
        assertNull(Household.parseTime("25:00"))

        assertEquals(Household.WEEKDAYS, Household.parseDays("vardagar"))
        assertEquals(Household.WEEKDAYS, Household.parseDays("mon-fri"))
        assertEquals(Household.WEEKEND, Household.parseDays("helger"))
        assertEquals(DayOfWeek.entries.toSet(), Household.parseDays("varje dag"))
        assertEquals(emptySet<DayOfWeek>(), Household.parseDays("once"))
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), Household.parseDays("mon, wed"))
        assertEquals(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), Household.parseDays("tisdag och torsdag"))
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY), Household.parseDays("måndag-onsdag"))
        assertNull(Household.parseDays("bananas"))
    }

    @Test
    fun seesPeopleComeAndGo() {
        val before = listOf(HomeyUser("u1", "Anna", present = false, asleep = false), HomeyUser("u2", "Bo", present = true, asleep = false))
        val after = listOf(HomeyUser("u1", "Anna", present = true, asleep = false), HomeyUser("u2", "Bo", present = true, asleep = true))
        assertEquals(
            listOf("Anna" to PresenceKind.Arrived, "Bo" to PresenceKind.FellAsleep),
            Household.changes(before, after).map { it.first.name to it.second },
        )
    }

    @Test
    fun realtimeUserAndAlarmEventsAreRead() {
        val user = HomeyRealtime.parseEvent(HomeyRealtime.USERS_URI, "user.update", HomeyJson.parseToJsonElement("""{"id": "u1", "name": "Anna", "present": true}"""))
        assertTrue(user is HomeyEvent.UserChanged)
        val gone = HomeyRealtime.parseEvent(HomeyRealtime.ALARMS_URI, "alarm.delete", HomeyJson.parseToJsonElement("""{"id": "a1"}"""))
        assertEquals(HomeyEvent.AlarmDeleted("a1"), gone)
    }

    @Test
    fun presenceBecomesAHouseHabitButFlickeringDoesNot() {
        val at = ZonedDateTime.of(2026, 10, 19, 17, 10, 0, 0, zone).toInstant().toEpochMilli()
        val anna = HomeyUser("u1", "Anna", true, false)
        val fact = HouseholdMemory.presence(PresenceChange(anna, PresenceKind.Arrived, at, after = 8 * 3600_000L), zone, localOnly = true)!!
        assertEquals(Memory.HOUSE, fact.who)
        assertEquals("Anna comes home around 17:00 on weekdays", "${fact.subject} ${fact.relation} ${fact.obj}")
        assertEquals(FactKind.Habit, fact.kind)
        assertTrue(fact.localOnly)
        assertNull(HouseholdMemory.presence(PresenceChange(anna, PresenceKind.Arrived, at, after = 2 * 60_000L), zone, localOnly = false))

        // Counted up as it repeats, and found again when asked.
        var data = MemoryData()
        repeat(3) { data = Memory.merge(data, fact, at) }
        val found = Memory.recall(data, "när brukar Anna komma hem", asker = "someone-else", local = true, now = at)
        assertEquals(3, found.single().count)
    }

    @Test
    fun anAlarmBelongsToThePersonItIsNamedAfter() {
        val alarm = HomeyAlarm("a1", "Väckning Gabriel", LocalTime.of(6, 15), true, Household.WEEKDAYS)
        val fact = HouseholdMemory.alarm(alarm, mapOf("p-g" to "Gabriel"), null, now, localOnly = false)
        assertEquals("p-g", fact.who)
        assertEquals("Gabriel sets the wake-up alarm 06:15 weekdays", "${fact.subject} ${fact.relation} ${fact.obj}")
    }

    // The assistant's tools.

    private class Home : HomeControl {
        val users = mutableListOf(
            HomeyPerson("Gabriel", true, false, "u-g"),
            HomeyPerson("Anna", true, false, "u-a"),
        )
        val alarms = mutableListOf<HomeyAlarm>()
        val skipped = mutableListOf<String>()
        override suspend fun setCapability(deviceId: String, capability: String, value: JsonElement) {}
        override suspend fun setMood(mood: Mood) {}
        override suspend fun flows() = emptyList<HomeyFlow>()
        override suspend fun triggerFlow(flow: HomeyFlow) {}
        override suspend fun people() = users.toList()
        override suspend fun variables() = emptyList<LogicVariable>()
        override suspend fun playMusic(deviceId: String, query: String): String? = null
        override suspend fun setPresence(userId: String, present: Boolean?, asleep: Boolean?) {
            val i = users.indexOfFirst { it.id == userId }
            users[i] = users[i].copy(present = present ?: users[i].present, asleep = asleep ?: users[i].asleep)
        }
        override suspend fun alarms() = alarms.toList()
        override suspend fun saveAlarm(id: String?, name: String, time: LocalTime, days: Set<DayOfWeek>, enabled: Boolean): HomeyAlarm {
            val a = HomeyAlarm(id ?: "a${alarms.size + 1}", name, time, enabled, days)
            alarms.removeAll { it.id == a.id }
            alarms += a
            return a
        }
        override suspend fun deleteAlarm(id: String) { alarms.removeAll { it.id == id } }
        override suspend fun skipAlarm(id: String): ZonedDateTime? {
            skipped += id
            return ZonedDateTime.of(2026, 10, 19, 6, 15, 0, 0, ZoneId.of("Europe/Stockholm"))
        }
    }

    private class Dash : DashboardControl {
        override fun openPage(page: String, roomId: String?) = true
        override fun openDevice(deviceId: String) = true
        override fun goodnightPreview() = ""
        override fun goodnight() {}
        override suspend fun weather(): String? = null
        override suspend fun calendar(from: LocalDate, days: Int, withLocations: Boolean) = emptyList<AgentEvent>()
        override fun startTimer(seconds: Int, label: String?) {}
        override fun cancelTimer(label: String?) = false
        override fun timers() = emptyList<String>()
        override fun setTheme(mode: String) = true
    }

    private val gabriel = Asker("p-g", "Gabriel")

    private fun tools(home: Home, asker: Asker? = gabriel, presence: Access = Access.On): AgentTools {
        val s = AgentSettings(secrets = mapOf(Secret.Presence to presence, Secret.WakeAlarms to Access.On), asker = asker)
        val view = HomeView.build(emptyMap<String, Zone>(), emptyMap(), emptyMap(), emptySet(), s, true)
        return AgentTools(view, home, Dash(), clock = { now })
    }

    private suspend fun AgentTools.call(name: String, vararg args: Pair<String, String>) =
        run(ToolCall("c", name, JsonObject(args.associate { it.first to JsonPrimitive(it.second) })))

    @Test
    fun wakeMeAtSetsTheAskersOwnAlarmAndChangesItNextTime() = runTest {
        val home = Home()
        val t = tools(home)
        val r = t.call("alarms", "action" to "set", "time" to "6:15", "days" to "vardagar")
        assertTrue(r.text, r.text.contains("Väckning Gabriel") && r.text.contains("rings tomorrow 06:15"))
        t.call("alarms", "action" to "set", "time" to "06:45")
        assertEquals(1, home.alarms.size)
        assertEquals(LocalTime.of(6, 45), home.alarms.single().time)
        assertEquals(Household.WEEKDAYS, home.alarms.single().days)
    }

    @Test
    fun neverChangesSomeoneElsesAlarmWithoutItsName() = runTest {
        val home = Home()
        home.alarms += HomeyAlarm("a-anna", "Väckning Anna", LocalTime.of(7, 0), true, Household.WEEKDAYS)
        tools(home).call("alarms", "action" to "set", "time" to "06:00")
        assertEquals(2, home.alarms.size)
        assertEquals(LocalTime.of(7, 0), home.alarms.first { it.id == "a-anna" }.time)
    }

    @Test
    fun skippingTomorrowLeavesTheAlarmForTheDaysAfter() = runTest {
        val home = Home()
        home.alarms += HomeyAlarm("a1", "Väckning Gabriel", LocalTime.of(6, 15), true, Household.WEEKDAYS)
        val r = tools(home).call("alarms", "action" to "skip")
        assertEquals(listOf("a1"), home.skipped)
        assertTrue(r.text, r.text.contains("won't ring tomorrow 06:15") && r.text.contains("as usual after that"))
    }

    @Test
    fun marksTheAskerAwayAndEveryoneAsleep() = runTest {
        val home = Home()
        val t = tools(home)
        t.call("set_presence", "state" to "away")
        assertEquals(false, home.users.first { it.name == "Gabriel" }.present)
        t.call("set_presence", "person" to "alla", "state" to "sover")
        assertEquals(true, home.users.first { it.name == "Anna" }.asleep)
        // Gabriel is away, so "everyone home" didn't include him.
        assertEquals(false, home.users.first { it.name == "Gabriel" }.asleep)
    }

    @Test
    fun presenceOffHidesTheTools() {
        val names = tools(Home(), presence = Access.Off).specs().map { it.name }
        assertTrue(names.none { it == "set_presence" || it == "who_is_home" })
        assertTrue("alarms" in names)
    }
}
