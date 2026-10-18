package app.homeydash.agent

import app.homeydash.homey.HomeyAlarm
import app.homeydash.homey.Household
import app.homeydash.homey.PresenceChange
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * What Homey's presence and wake-up alarms teach the memory graph, counted on the tablet
 * without the AI: "Anna comes home around 17:00 on weekdays", "Gabriel sets the wake-up alarm
 * 06:15 weekdays". Nova can then answer "när brukar Anna komma hem?" or "väck mig som vanligt".
 */
object HouseholdMemory {
    /** A state shorter than this was a phone's location flickering, not something someone did. */
    const val MIN_STATE_MS = 10 * 60_000L

    /** The person the tablet knows (under People) with [name], by first name. */
    fun personFor(name: String, people: Map<String, String>): Asker? {
        val first = name.trim().split(' ').firstOrNull().orEmpty()
        return people.entries.firstOrNull { (_, n) ->
            n.equals(name.trim(), ignoreCase = true) || n.trim().split(' ').firstOrNull().equals(first, ignoreCase = true)
        }?.let { Asker(it.key, it.value) }
    }

    /**
     * The habit one presence change shows. Who is home is shared by everyone who may ask
     * "who's home", so these are the house's facts, about the person by name. Null when the state
     * before lasted too short to mean anything.
     */
    fun presence(change: PresenceChange, zone: ZoneId, localOnly: Boolean): Fact? {
        if (change.after != null && change.after < MIN_STATE_MS) return null
        val t = Instant.ofEpochMilli(change.at).atZone(zone)
        return Fact(
            who = Memory.HOUSE,
            subject = change.user.name,
            relation = change.kind.relation,
            obj = "around ${halfHour(t)} on ${dayKind(t.dayOfWeek)}",
            kind = FactKind.Habit,
            first = change.at,
            parts = mapOf(Memory.partOfDay(t) to 1),
            localOnly = localOnly,
        )
    }

    /**
     * The habit an alarm set from the tablet shows: whose it is by its name ("Väckning Gabriel"),
     * else whoever asked for it, else the house's.
     */
    fun alarm(alarm: HomeyAlarm, people: Map<String, String>, asker: Asker?, at: ZonedDateTime, localOnly: Boolean): Fact {
        val owner = people.entries.firstOrNull { (_, n) -> alarm.name.contains(n.trim().split(' ').first(), ignoreCase = true) }
            ?.let { Asker(it.key, it.value) } ?: asker
        return Fact(
            who = owner?.id ?: Memory.HOUSE,
            whoName = owner?.name.orEmpty(),
            subject = owner?.name ?: "Someone in the house",
            relation = "sets the wake-up alarm",
            obj = "${Household.timeText(alarm.time)} ${Household.daysText(alarm.days)}",
            kind = FactKind.Habit,
            parts = mapOf(Memory.partOfDay(at) to 1),
            localOnly = localOnly,
        )
    }

    /** "07:30" for 07:20 to 07:44. */
    fun halfHour(t: ZonedDateTime): String {
        val m = ((t.hour * 60 + t.minute + 15) / 30 * 30) % (24 * 60)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    private fun dayKind(d: DayOfWeek) = if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) "weekends" else "weekdays"
}
