package app.homeydash.agent

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * A calendar on the tablet the assistant may add events to: one synced from a Google (or
 * other) account. [account] is the account it belongs to, [owner] the email of whoever owns
 * it (the same as [name] for a Google account's own calendar).
 */
data class AgentCalendar(val id: String, val name: String, val account: String, val owner: String = "")

/** An event the assistant adds: a day, and a start time with a length, or all day when [time] is null. */
data class NewEvent(
    val calendarId: String,
    val title: String,
    val date: LocalDate,
    val time: LocalTime?,
    val minutes: Int,
    val location: String? = null,
)

/**
 * Which calendar is whose: each person's own is the one their account email (written under
 * People) owns, the family calendar is the one picked in Settings or the one called family.
 * Also the calendar habits counted for the memory, so "boka padel på tisdag" gets the usual
 * time, length and calendar.
 */
object CalendarBook {
    /** What [AgentSettings.familyCalendar] holds when it should be guessed by name. */
    const val GUESS = ""

    private val FAMILY_WORDS = setOf("family", "familj", "familjen", "familjens", "families", "hem", "home", "house", "huset", "shared", "delad", "gemensam")
    private val MINE = Regex("""^(mine|my|my own|my calendar|min|mitt|min egen|min kalender|egen|own|me|jag|mig)$""", RegexOption.IGNORE_CASE)
    private val FAMILY = Regex("""^(the )?(family|familj|familjen|familjens|familjekalender|familjekalendern|family calendar|house|huset|shared|alla|everyone)( calendar| kalender)?$""", RegexOption.IGNORE_CASE)
    private val TIME = Regex("""^(\d{1,2})[:.](\d{2})$""")
    private val HOUR = Regex("""^(\d{1,2})$""")
    private val ALL_DAY = Regex("""^(all[ -]?day|heldag|hela dagen|whole day)$""", RegexOption.IGNORE_CASE)

    /** The calendar of the person whose account email is [email]: the account's own calendar first. */
    fun ownedBy(calendars: List<AgentCalendar>, email: String): AgentCalendar? {
        val e = email.trim().lowercase().takeIf { '@' in it } ?: return null
        fun same(s: String) = s.trim().lowercase() == e
        return calendars.firstOrNull { same(it.name) && same(it.account) }
            ?: calendars.firstOrNull { same(it.owner) && same(it.account) }
            ?: calendars.firstOrNull { same(it.name) || same(it.owner) }
            ?: calendars.firstOrNull { same(it.account) }
    }

    /** The family calendar: [chosen] by id, or else one with family in its name. */
    fun family(calendars: List<AgentCalendar>, chosen: String): AgentCalendar? =
        calendars.firstOrNull { it.id == chosen && chosen != GUESS }
            ?: calendars.firstOrNull { c -> NameMatch.tokens(c.name).any { it in FAMILY_WORDS } }

    /** The person id a calendar belongs to, or [Memory.HOUSE] for the family's; null for anyone else's. */
    fun ownerOf(calendar: AgentCalendar, calendars: List<AgentCalendar>, s: AgentSettings): String? {
        if (family(calendars, s.familyCalendar)?.id == calendar.id) return Memory.HOUSE
        return s.calendarAccounts.entries.firstOrNull { (_, email) -> ownedBy(calendars, email)?.id == calendar.id }?.key
    }

    /** "Gabriel's calendar", "the family calendar", or the calendar's own name. */
    fun label(calendar: AgentCalendar, calendars: List<AgentCalendar>, s: AgentSettings): String =
        when (val owner = ownerOf(calendar, calendars, s)) {
            Memory.HOUSE -> "the family calendar"
            null -> "the calendar \"${calendar.name}\""
            else -> s.people[owner]?.let { "$it's calendar" } ?: "the calendar \"${calendar.name}\""
        }

    /** What picking a calendar came to: the calendar, or what to tell the user instead. */
    sealed interface Pick {
        data class Found(val calendar: AgentCalendar) : Pick
        data class Problem(val text: String) : Pick
    }

    /**
     * The calendar [asked] names: "mine" (the asker's own), "family", a person's name ("Anna",
     * "Annas kalender") or a calendar's own name. Blank picks [usual] (from the memory), then
     * the family calendar, then the asker's own.
     */
    fun pick(asked: String, calendars: List<AgentCalendar>, s: AgentSettings, usual: String? = null): Pick {
        if (calendars.isEmpty()) return Pick.Problem(NO_CALENDARS)
        val a = asked.trim().removeSuffix(".")
        fun own(): Pick {
            val asker = s.asker ?: return Pick.Problem("I don't know who is asking, so I can't tell which calendar is yours. Teach me your voice under People, or say whose calendar.")
            val email = s.calendarAccounts[asker.id]?.takeIf { it.isNotBlank() }
                ?: return Pick.Problem("${asker.name} has no calendar yet. Write ${asker.name}'s email under Settings → Assistant → People, and add that account to the tablet.")
            return ownedBy(calendars, email)?.let { Pick.Found(it) }
                ?: Pick.Problem("$email isn't on the tablet. Add that account under the tablet's Settings → Accounts, with calendar sync on.")
        }
        fun family(): Pick = family(calendars, s.familyCalendar)?.let { Pick.Found(it) }
            ?: Pick.Problem("There's no family calendar. Pick it under Settings → Assistant → People. The calendars are: ${names(calendars)}.")
        return when {
            a.isEmpty() -> usual?.let { u -> calendars.firstOrNull { it.id == u } }?.let { Pick.Found(it) }
                ?: family(calendars, s.familyCalendar)?.let { Pick.Found(it) }
                ?: own()
            MINE.matches(a) -> own()
            FAMILY.matches(a) -> family()
            else -> {
                // "Anna", "Annas kalender": by the person's name, else by the calendar's own.
                val words = a.replace(Regex("""\b(calendar|kalender|kalendern|'s|’s)\b""", RegexOption.IGNORE_CASE), " ").trim()
                val person = s.people.entries.firstOrNull { (_, name) -> Phones.named(words, name) || NameMatch.score(words, name) >= 0.95 }
                if (person != null) {
                    val email = s.calendarAccounts[person.key]?.takeIf { it.isNotBlank() }
                        ?: return Pick.Problem("${person.value} has no calendar yet. Write their email under Settings → Assistant → People.")
                    ownedBy(calendars, email)?.let { Pick.Found(it) }
                        ?: Pick.Problem("${person.value}'s account ($email) isn't on the tablet.")
                } else {
                    NameMatch.best(words, calendars) { NameMatch.score(words, it.name) }?.let { Pick.Found(it) }
                        ?: Pick.Problem("I can't find a calendar called \"$a\". The calendars are: ${names(calendars)}.")
                }
            }
        }
    }

    /** "18:00", "18.30", "6" → a time; "all day" and blank → null; a failure otherwise. */
    fun time(text: String): Result<LocalTime?> {
        val t = text.trim().lowercase()
        if (t.isEmpty() || ALL_DAY.matches(t)) return Result.success(null)
        TIME.matchEntire(t)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h in 0..23 && min in 0..59) return Result.success(LocalTime.of(h, min))
        }
        HOUR.matchEntire(t)?.let { m -> m.groupValues[1].toInt().takeIf { it in 0..23 }?.let { return Result.success(LocalTime.of(it, 0)) } }
        return Result.failure(IllegalArgumentException("Give the time as HH:mm, e.g. 18:00, or leave it out for all day."))
    }

    /** How the event reads when asking for a yes: "Padel, Tuesday 28 October at 18:00 for 90 minutes". */
    fun describe(e: NewEvent, today: LocalDate): String = buildString {
        append('"').append(e.title).append("\", ")
        append(SpokenDates.day(e.date, today))
        if (e.time == null) append(", all day")
        else append(" at ").append(e.time).append(" for ").append(lengthText(e.minutes))
        e.location?.let { append(", at ").append(it) }
    }

    fun lengthText(minutes: Int): String = when {
        minutes % 60 == 0 && minutes >= 60 -> if (minutes == 60) "1 hour" else "${minutes / 60} hours"
        else -> "$minutes minutes"
    }

    const val ADDS = "adds to the calendar"
    private val HABIT = Regex("""(.+?) (?:at (\d{2}:\d{2}) for (\d+) minutes|all day), in (.+)""")

    /** That [asker] put [e] in [calendarLabel], counted like a habit; [calendarId] lets the usual calendar be found again. */
    fun habit(e: NewEvent, calendarLabel: String, asker: Asker?, at: ZonedDateTime): Fact = Fact(
        who = asker?.id ?: Memory.HOUSE,
        whoName = asker?.name.orEmpty(),
        subject = asker?.name ?: "Someone in the house",
        relation = ADDS,
        obj = e.title.trim().replaceFirstChar(Char::uppercase) +
            (e.time?.let { " at $it for ${e.minutes} minutes" } ?: " all day") + ", in " + calendarLabel,
        kind = FactKind.Habit,
        parts = mapOf(Memory.partOfDay(at) to 1),
        data = e.calendarId,
    )

    /** The time, length and calendar [asker] usually gives an event called [title]; null when never added. */
    data class Usual(val time: LocalTime?, val minutes: Int?, val calendarId: String?)

    fun usual(facts: List<Fact>, title: String, asker: String?): Usual? {
        val q = NameMatch.tokens(title)
        if (q.isEmpty()) return null
        val hit = facts.filter { f ->
            f.kind == FactKind.Habit && f.relation == ADDS && (f.who == asker || f.who == Memory.HOUSE)
        }.mapNotNull { f ->
            val m = HABIT.matchEntire(f.obj) ?: return@mapNotNull null
            val name = NameMatch.tokens(m.groupValues[1])
            if (q.none { w -> name.any { NameMatch.sameWord(w, it) } }) return@mapNotNull null
            f to m
        }.maxWithOrNull(compareBy({ (f, _) -> if (f.who == asker && asker != null) 1 else 0 }, { it.first.count }, { it.first.last })) ?: return null
        val (f, m) = hit
        return Usual(
            time = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
            minutes = m.groupValues[3].toIntOrNull(),
            calendarId = f.data.takeIf { it.isNotEmpty() },
        )
    }

    private fun names(calendars: List<AgentCalendar>) = calendars.joinToString { "\"${it.name}\"" }

    const val NO_CALENDARS = "There are no calendars on the tablet to add to. Add a Google account under the tablet's Settings → Accounts with calendar sync on, and allow the dashboard to use the calendar."
    const val DEFAULT_MINUTES = 60
}
