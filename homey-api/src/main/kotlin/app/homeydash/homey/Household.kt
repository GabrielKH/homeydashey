package app.homeydash.homey

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** A Homey user and whether Homey thinks they are home or asleep. */
data class HomeyUser(
    val id: String,
    val name: String,
    val present: Boolean?,
    val asleep: Boolean?,
    /** A picture Homey has for them (often on Athom's cloud), or null. */
    val avatar: String? = null,
    val role: String? = null,
    val enabled: Boolean = true,
)

/**
 * One of Homey's own wake-up alarms (väckarklockor). Homey itself makes no sound; Homey Flows
 * react when it goes off ("When Väckning goes off, open the blinds"). No [days] means it rings once.
 */
data class HomeyAlarm(
    val id: String,
    val name: String,
    val time: LocalTime,
    val enabled: Boolean,
    val days: Set<DayOfWeek>,
) {
    val repeats: Boolean get() = days.isNotEmpty()
}

/** Something about a person that Homey just changed. */
enum class PresenceKind(val relation: String) {
    Arrived("comes home"),
    Left("leaves home"),
    FellAsleep("goes to sleep"),
    WokeUp("wakes up"),
}

/** [user] did [kind] at [at] (epoch ms); [after] is how long the state before had lasted, when the tablet saw it start. */
data class PresenceChange(val user: HomeyUser, val kind: PresenceKind, val at: Long, val after: Long?)

data class HouseholdState(
    val users: List<HomeyUser> = emptyList(),
    val alarms: List<HomeyAlarm> = emptyList(),
    /** When each user (by id) last went home, away, to sleep or woke up, as far as the tablet saw. */
    val since: Map<String, Long> = emptyMap(),
    /** Repeating alarms switched off for their next ring only (by id), and when to switch them back on. */
    val skipped: Map<String, Long> = emptyMap(),
    /** False when the API key may not read users (it needs "View users"). */
    val usersAllowed: Boolean = true,
    /** False when the API key may not read alarms (it needs "View alarms"). */
    val alarmsAllowed: Boolean = true,
    val loaded: Boolean = false,
) {
    /** The people to show: Homey's users that are switched on. */
    val people: List<HomeyUser> get() = users.filter { it.enabled }
}

/** Reading and describing Homey's users and alarms; nothing here talks to the Homey. */
object Household {
    private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
    val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    /** Parses `GET /api/manager/users/user`. */
    fun parseUsers(json: JsonElement): List<HomeyUser> =
        values(json).mapNotNull(::parseUser).sortedBy { it.name.lowercase() }

    fun parseUser(u: JsonObject): HomeyUser? {
        val id = u.str("id") ?: return null
        val name = u.str("name")?.takeIf { it.isNotBlank() } ?: return null
        return HomeyUser(
            id = id,
            name = name,
            present = u.bool("present"),
            asleep = u.bool("asleep"),
            avatar = u.str("avatar")?.takeIf { it.isNotBlank() },
            role = u.str("role"),
            enabled = u.bool("enabled") ?: true,
        )
    }

    /** Parses `GET /api/manager/alarms/alarm`. */
    fun parseAlarms(json: JsonElement): List<HomeyAlarm> =
        values(json).mapNotNull(::parseAlarm).sortedWith(compareBy({ it.time }, { it.name.lowercase() }))

    fun parseAlarm(a: JsonObject): HomeyAlarm? {
        val id = a.str("id") ?: return null
        val time = a.str("time")?.let(::parseTime) ?: return null
        val repetition = a["repetition"] as? JsonObject
        val days = DayOfWeek.entries.filter { (repetition?.get(it.name.lowercase()) as? JsonPrimitive)?.booleanOrNull == true }.toSet()
        return HomeyAlarm(id, a.str("name").orEmpty().ifBlank { "Alarm" }, time, a.bool("enabled") ?: true, days)
    }

    /** The body Homey wants to create or change an alarm. */
    fun body(name: String, time: LocalTime, days: Set<DayOfWeek>, enabled: Boolean): JsonObject = buildJsonObject {
        put("name", name)
        put("time", time.format(HHMM))
        put("enabled", enabled)
        putJsonObject("repetition") { DayOfWeek.entries.forEach { put(it.name.lowercase(), it in days) } }
    }

    /** When [alarm] rings next after [now], or null when it is off. */
    fun nextRing(alarm: HomeyAlarm, now: ZonedDateTime): ZonedDateTime? {
        if (!alarm.enabled) return null
        for (offset in 0..7L) {
            val day = now.toLocalDate().plusDays(offset)
            val at = day.atTime(alarm.time).atZone(now.zone)
            if (!at.isAfter(now)) continue
            if (alarm.days.isEmpty() || day.dayOfWeek in alarm.days) return at
        }
        return null
    }

    /** When [alarm] would ring next if it were on: for skipping just that ring. */
    fun nextRingIfOn(alarm: HomeyAlarm, now: ZonedDateTime): ZonedDateTime? = nextRing(alarm.copy(enabled = true), now)

    /** The alarm that rings first after [now], with when. */
    fun next(alarms: List<HomeyAlarm>, now: ZonedDateTime): Pair<HomeyAlarm, ZonedDateTime>? =
        alarms.mapNotNull { a -> nextRing(a, now)?.let { a to it } }.minByOrNull { it.second }

    /** "weekdays", "every day", "once", "Mon, Wed, Fri". */
    fun daysText(days: Set<DayOfWeek>): String = when (days) {
        emptySet<DayOfWeek>() -> "once"
        DayOfWeek.entries.toSet() -> "every day"
        WEEKDAYS -> "weekdays"
        WEEKEND -> "weekends"
        else -> DayOfWeek.entries.filter { it in days }.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
    }

    /** "today 06:30", "tomorrow 06:30", "Monday 06:30". */
    fun whenText(at: ZonedDateTime, now: ZonedDateTime): String {
        val day = when (at.toLocalDate()) {
            now.toLocalDate() -> "today"
            now.toLocalDate().plusDays(1) -> "tomorrow"
            else -> at.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        }
        return "$day ${at.format(HHMM)}"
    }

    fun timeText(time: LocalTime): String = time.format(HHMM)

    /**
     * A time as people and models write it: "06:15", "6.15", "615", "7", "7am", "7:30 pm".
     * Null when it isn't one.
     */
    fun parseTime(text: String): LocalTime? {
        val t = text.trim().lowercase().replace(" ", "")
        val m = TIME.matchEntire(t) ?: return null
        val digits = m.groupValues[1]
        val minutesPart = m.groupValues[2]
        var (h, min) = when {
            minutesPart.isNotEmpty() -> digits.toInt() to minutesPart.toInt()
            digits.length <= 2 -> digits.toInt() to 0
            else -> digits.dropLast(2).toInt() to digits.takeLast(2).toInt()
        }
        when (m.groupValues[3]) {
            "pm" -> if (h < 12) h += 12
            "am" -> if (h == 12) h = 0
        }
        if (h == 24 && min == 0) h = 0
        if (h !in 0..23 || min !in 0..59) return null
        return LocalTime.of(h, min)
    }

    private val TIME = Regex("""(\d{1,4})(?:[:.](\d{2}))?(am|pm)?""")

    /**
     * Days as people and models say them, in English or Swedish: "weekdays", "vardagar",
     * "every day", "helger", "mon,wed", "måndag och fredag", "once". Empty for once; null when
     * the words aren't days at all.
     */
    fun parseDays(text: String): Set<DayOfWeek>? {
        val t = text.trim().lowercase()
        if (t.isEmpty() || ONCE.matches(t)) return emptySet()
        if (EVERY_DAY.matches(t)) return DayOfWeek.entries.toSet()
        if (WEEKDAY_WORDS.matches(t)) return WEEKDAYS
        if (WEEKEND_WORDS.matches(t)) return WEEKEND
        // "mon-fri", "måndag–fredag".
        RANGE.matchEntire(t)?.let { r ->
            val from = dayOf(r.groupValues[1]) ?: return null
            val to = dayOf(r.groupValues[2]) ?: return null
            return generateSequence(from) { it.plus(1) }.takeWhile { it != to }.toSet() + to
        }
        val words = t.split(Regex("""[\s,;/&+]+|\boch\b|\band\b""")).filter { it.isNotBlank() }
        val days = words.map { dayOf(it) ?: return null }.toSet()
        return days.ifEmpty { null }
    }

    private fun dayOf(word: String): DayOfWeek? {
        val w = word.trim().trimEnd('.', 's').removeSuffix("ar").removeSuffix("en")
        if (w.length < 2) return null
        return DAY_NAMES.entries.firstOrNull { (_, names) -> names.any { it.startsWith(w) || w.startsWith(it) } }?.key
    }

    private val DAY_NAMES = mapOf(
        DayOfWeek.MONDAY to listOf("mon", "mån", "man"),
        DayOfWeek.TUESDAY to listOf("tue", "tis"),
        DayOfWeek.WEDNESDAY to listOf("wed", "ons"),
        DayOfWeek.THURSDAY to listOf("thu", "tor"),
        DayOfWeek.FRIDAY to listOf("fri", "fre"),
        DayOfWeek.SATURDAY to listOf("sat", "lör", "lor"),
        DayOfWeek.SUNDAY to listOf("sun", "sön", "son"),
    )
    private val ONCE = Regex("""once|one time|en gång|engång|bara en gång|imorgon|i morgon|tomorrow|today|idag|i dag|next|nästa|never|aldrig|no repeat|none""")
    private val EVERY_DAY = Regex("""(every ?day|everyday|daily|all days|each day|varje dag|dagligen|alla dagar)""")
    private val WEEKDAY_WORDS = Regex("""(weekdays?|work ?days?|vardag(ar|arna)?|veckodag(ar)?|mon(day)?s? ?(-|–|to|till) ?fri(day)?s?|måndag(ar)? ?(-|–|till) ?fredag(ar)?|mån ?(-|–) ?fre)""")
    private val WEEKEND_WORDS = Regex("""(weekends?|helg(er|en|erna)?|lördag(ar)? och söndag(ar)?|saturdays? and sundays?)""")
    private val RANGE = Regex("""(\p{L}+)\s*(?:-|–|to|till)\s*(\p{L}+)""")

    /** What changed for each person between two readings of the users. */
    fun changes(old: List<HomeyUser>, new: List<HomeyUser>): List<Pair<HomeyUser, PresenceKind>> {
        val before = old.associateBy { it.id }
        return new.flatMap { u ->
            val o = before[u.id] ?: return@flatMap emptyList()
            buildList {
                if (o.present == false && u.present == true) add(u to PresenceKind.Arrived)
                if (o.present == true && u.present == false) add(u to PresenceKind.Left)
                if (o.asleep == false && u.asleep == true) add(u to PresenceKind.FellAsleep)
                if (o.asleep == true && u.asleep == false) add(u to PresenceKind.WokeUp)
            }
        }
    }

    /** "home", "away", "asleep", or "unknown". */
    fun status(u: HomeyUser): String = when {
        u.asleep == true -> "asleep"
        u.present == true -> "home"
        u.present == false -> "away"
        else -> "unknown"
    }

    private fun values(json: JsonElement): List<JsonObject> = when (json) {
        is JsonObject -> json.values.filterIsInstance<JsonObject>()
        is JsonArray -> json.filterIsInstance<JsonObject>()
        else -> emptyList()
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
}

/**
 * Homey's users and wake-up alarms, kept current by realtime events (forwarded by
 * [HomeyStore]) with a slow poll behind them. Presence and alarms change on Homey and in Homey
 * Flows, so Homey is always where they're read from and written to; the tablet only adds
 * [HouseholdState.skipped], for "no wake-up tomorrow" on an alarm that repeats.
 */
class HouseholdStore(
    private val client: HomeyClient,
    private val scope: CoroutineScope,
    /** Where skipped alarms are kept between restarts; null keeps them in memory only. */
    private val dir: File? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val _state = MutableStateFlow(HouseholdState())
    val state: StateFlow<HouseholdState> = _state.asStateFlow()

    private val _changes = MutableSharedFlow<PresenceChange>(extraBufferCapacity = 32)
    /** People coming home, leaving, falling asleep and waking up, as they happen. */
    val changes: SharedFlow<PresenceChange> = _changes.asSharedFlow()

    private val _alarmSaved = MutableSharedFlow<HomeyAlarm>(extraBufferCapacity = 8)
    /** Alarms set or changed from the tablet (by hand or by the assistant). */
    val alarmSaved: SharedFlow<HomeyAlarm> = _alarmSaved.asSharedFlow()

    private var job: Job? = null
    private val skipsLock = Mutex()
    private val skipsFile get() = dir?.let { File(it, "alarm-skips.json") }

    fun start() {
        if (job != null) return
        job = scope.launch {
            loadSkips()
            reload()
            while (true) {
                delay(POLL_MS)
                // Realtime events carry presence; this only catches what a dropped socket missed.
                reload()
                restoreSkipped()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun reload() {
        val (users, alarms) = coroutineScope {
            val users = async { runCatching { Household.parseUsers(client.users()) } }
            val alarms = async { runCatching { Household.parseAlarms(client.alarms()) } }
            users.await() to alarms.await()
        }
        users.getOrNull()?.let { setUsers(it) }
        _state.update { s ->
            s.copy(
                alarms = alarms.getOrNull() ?: s.alarms,
                usersAllowed = users.exceptionOrNull().let { it !is HomeyHttpException || (it.code != 401 && it.code != 403) },
                alarmsAllowed = alarms.exceptionOrNull().let { it !is HomeyHttpException || (it.code != 401 && it.code != 403) },
                loaded = true,
            )
        }
    }

    /** A realtime event about users or alarms. */
    fun onEvent(event: HomeyEvent) {
        when (event) {
            is HomeyEvent.UserChanged -> Household.parseUser(event.user)?.let { u ->
                val users = _state.value.users
                setUsers(if (users.any { it.id == u.id }) users.map { if (it.id == u.id) u else it } else (users + u).sortedBy { it.name.lowercase() })
            }
            is HomeyEvent.UserDeleted -> _state.update { s -> s.copy(users = s.users.filterNot { it.id == event.userId }) }
            is HomeyEvent.AlarmChanged -> Household.parseAlarm(event.alarm)?.let { a -> putAlarm(a) }
            is HomeyEvent.AlarmDeleted -> _state.update { s -> s.copy(alarms = s.alarms.filterNot { it.id == event.alarmId }) }
            else -> Unit
        }
    }

    private fun setUsers(users: List<HomeyUser>) {
        val now = clock()
        val before = _state.value
        val changed = if (before.users.isEmpty()) emptyList() else Household.changes(before.users, users)
        val since = before.since.toMutableMap()
        changed.forEach { (u, kind) ->
            val after = since[u.id]?.let { now - it }
            since[u.id] = now
            _changes.tryEmit(PresenceChange(u, kind, now, after))
        }
        _state.update { it.copy(users = users, since = since) }
    }

    private fun putAlarm(a: HomeyAlarm) = _state.update { s ->
        val alarms = if (s.alarms.any { it.id == a.id }) s.alarms.map { if (it.id == a.id) a else it } else s.alarms + a
        s.copy(alarms = alarms.sortedWith(compareBy({ it.time }, { it.name.lowercase() })))
    }

    /** Marks [userId] home or away in Homey, so Flows that depend on it run. */
    suspend fun setPresent(userId: String, present: Boolean) {
        client.setPresent(userId, present)
        _state.value.users.firstOrNull { it.id == userId }?.let { u -> onEvent(HomeyEvent.UserChanged(userJson(u.copy(present = present)))) }
    }

    /** Marks [userId] asleep or awake in Homey. */
    suspend fun setAsleep(userId: String, asleep: Boolean) {
        client.setAsleep(userId, asleep)
        _state.value.users.firstOrNull { it.id == userId }?.let { u -> onEvent(HomeyEvent.UserChanged(userJson(u.copy(asleep = asleep)))) }
    }

    /** Creates an alarm ([id] null) or changes one; returns it as Homey now has it. */
    suspend fun saveAlarm(id: String?, name: String, time: java.time.LocalTime, days: Set<DayOfWeek>, enabled: Boolean = true): HomeyAlarm {
        val body = Household.body(name.trim().ifBlank { "Alarm" }, time, days, enabled)
        val answer = if (id == null) client.createAlarm(body) else client.updateAlarm(id, body)
        val saved = (answer as? JsonObject)?.let(Household::parseAlarm)
            ?: HomeyAlarm(id ?: "", name, time, enabled, days).also { reload() }
        if (saved.id.isNotEmpty()) putAlarm(saved)
        clearSkip(saved.id)
        _alarmSaved.tryEmit(saved)
        return saved
    }

    suspend fun deleteAlarm(id: String) {
        client.deleteAlarm(id)
        clearSkip(id)
        _state.update { s -> s.copy(alarms = s.alarms.filterNot { it.id == id }) }
    }

    /** Switches an alarm on or off; switching it on also ends a skip. */
    suspend fun setEnabled(id: String, enabled: Boolean) {
        val a = _state.value.alarms.firstOrNull { it.id == id } ?: return
        val body = Household.body(a.name, a.time, a.days, enabled)
        val answer = client.updateAlarm(id, body)
        putAlarm((answer as? JsonObject)?.let(Household::parseAlarm) ?: a.copy(enabled = enabled))
        clearSkip(id)
    }

    /**
     * No ring next time: an alarm that rings once is switched off; one that repeats is switched
     * off until just after its next ring, then on again by the tablet. Returns when it would have rung.
     */
    suspend fun skipNext(id: String): ZonedDateTime? {
        val a = _state.value.alarms.firstOrNull { it.id == id } ?: return null
        val now = Instant.ofEpochMilli(clock()).atZone(zone())
        val ring = Household.nextRingIfOn(a, now) ?: return null
        client.updateAlarm(id, Household.body(a.name, a.time, a.days, false))
        putAlarm(a.copy(enabled = false))
        if (a.repeats) {
            skipsLock.withLock { _state.update { it.copy(skipped = it.skipped + (id to ring.toInstant().toEpochMilli() + SKIP_GRACE_MS)) } }
            saveSkips()
        }
        return ring
    }

    /** Puts back alarms whose skipped ring has passed. */
    suspend fun restoreSkipped() {
        val now = clock()
        val due = _state.value.skipped.filterValues { it <= now }.keys
        for (id in due) {
            if (_state.value.alarms.any { it.id == id }) runCatching { setEnabled(id, true) } else clearSkip(id)
        }
    }

    private suspend fun clearSkip(id: String) {
        if (id !in _state.value.skipped) return
        skipsLock.withLock { _state.update { it.copy(skipped = it.skipped - id) } }
        saveSkips()
    }

    private suspend fun loadSkips() {
        val file = skipsFile ?: return
        val skipped = withContext(Dispatchers.IO) {
            runCatching {
                if (!file.exists()) emptyMap() else HomeyJson.parseToJsonElement(file.readText()).jsonObject
                    .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { k to it } }.toMap()
            }.getOrDefault(emptyMap())
        }
        _state.update { it.copy(skipped = skipped) }
    }

    private suspend fun saveSkips() {
        val file = skipsFile ?: return
        val skipped = _state.value.skipped
        withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(buildJsonObject { skipped.forEach { (k, v) -> put(k, v) } }.toString())
            }
        }
    }

    private fun userJson(u: HomeyUser) = buildJsonObject {
        put("id", u.id)
        put("name", u.name)
        u.present?.let { put("present", it) }
        u.asleep?.let { put("asleep", it) }
        u.avatar?.let { put("avatar", it) }
        u.role?.let { put("role", it) }
        put("enabled", u.enabled)
    }

    private companion object {
        const val POLL_MS = 2 * 60_000L
        /** A skipped alarm comes back this long after the ring it skipped. */
        const val SKIP_GRACE_MS = 5 * 60_000L
    }
}
