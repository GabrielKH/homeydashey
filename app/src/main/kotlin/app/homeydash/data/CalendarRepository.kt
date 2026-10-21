package app.homeydash.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import app.homeydash.agent.AgentCalendar
import app.homeydash.agent.NewEvent
import app.homeydash.homey.HomeyCalendarEvent
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CalendarInfo(
    val id: Long,
    val name: String,
    val account: String,
    val color: Int,
    /** Shown in the tablet's calendar app. */
    val visible: Boolean,
    /** The account syncs this calendar's events to the tablet. */
    val synced: Boolean,
    /** Events from the start of this month to two months ahead. */
    val events: Int = 0,
) {
    companion object {
        /**
         * The calendars shown when none are chosen: the ones the calendar app shows. Some
         * tablets mark none as shown (Google Calendar keeps its own list), so then every
         * calendar that has events.
         */
        fun defaults(calendars: List<CalendarInfo>): Set<Long> =
            calendars.filter { it.visible }.map { it.id }.toSet()
                .ifEmpty { calendars.filter { it.events > 0 }.map { it.id }.toSet() }

        fun shown(calendars: List<CalendarInfo>, chosen: Set<String>): Set<Long> =
            if (chosen.isEmpty()) defaults(calendars) else chosen.mapNotNull { it.toLongOrNull() }.toSet()
    }
}

/** One occurrence of an event. All-day events have [begin] and [end] at UTC midnight. */
data class CalendarEvent(
    val id: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int,
    val location: String?,
    val calendarId: Long,
)

/**
 * Reads the calendars synced to the tablet (for example a Google account's family
 * calendar) through Android's calendar provider; no sign-in of its own. When the Calendar
 * module's source is the IcalCalendar Homey app, the events come from Homey instead ([useHomey]).
 */
class CalendarRepository(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val _hasPermission = MutableStateFlow(granted())
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    private val _calendars = MutableStateFlow<List<CalendarInfo>>(emptyList())
    val calendars: StateFlow<List<CalendarInfo>> = _calendars.asStateFlow()

    private val _events = MutableStateFlow<List<CalendarEvent>>(emptyList())
    val events: StateFlow<List<CalendarEvent>> = _events.asStateFlow()

    /** Chosen calendar ids; empty means the ones the tablet's calendar app shows. */
    val selected: StateFlow<Set<String>> = settings.calendars.stateIn(scope, SharingStarted.Eagerly, emptySet())

    private var observing = false
    private var job: Job? = null

    /** Reads IcalCalendar's events on Homey, while the Calendar module uses it. */
    private var homey: (suspend () -> List<HomeyCalendarEvent>)? = null
    private var homeyEvents: List<CalendarEvent> = emptyList()
    private var homeyRead = 0L
    private val homeyNames = mutableMapOf<Long, String>()

    private val _homeyError = MutableStateFlow<String?>(null)
    /** Why Homey's calendar couldn't be read last time, for the module's page. */
    val homeyError: StateFlow<String?> = _homeyError.asStateFlow()

    init {
        // The settings store emits on every write to any setting; only a new choice of calendars matters here.
        scope.launch { settings.calendars.distinctUntilChanged().collect { refresh() } }
        scope.launch {
            while (true) {
                delay(5 * 60 * 1000L)
                refresh()
            }
        }
    }

    /** Call after the permission dialog closes. */
    fun permissionChanged() {
        _hasPermission.value = usable()
        refresh()
    }

    /** Takes the events from Homey's IcalCalendar app with [reader], or from the tablet again with null. */
    fun useHomey(reader: (suspend () -> List<HomeyCalendarEvent>)?) {
        if (reader == null && homey == null) return
        homey = reader
        homeyRead = 0L
        homeyEvents = emptyList()
        _homeyError.value = null
        _hasPermission.value = usable()
        refresh()
    }

    fun setSelected(ids: Set<String>) {
        scope.launch { settings.setCalendars(ids) }
    }

    fun refresh() {
        homey?.let { refreshHomey(it); return }
        if (!granted()) return
        _hasPermission.value = true
        observe()
        job?.cancel()
        job = scope.launch {
            val chosen = settings.calendars.first()
            val (calendars, events) = withContext(Dispatchers.IO) {
                val all = runCatching { queryEvents(null) }.getOrDefault(emptyList())
                val counts = all.groupingBy { it.calendarId }.eachCount()
                val calendars = runCatching { queryCalendars() }.getOrDefault(emptyList())
                    .map { it.copy(events = counts[it.id] ?: 0) }
                val shown = CalendarInfo.shown(calendars, chosen)
                calendars to all.filter { it.calendarId in shown }
            }
            _calendars.value = calendars
            _events.value = events
        }
    }

    private fun refreshHomey(reader: suspend () -> List<HomeyCalendarEvent>) {
        job?.cancel()
        job = scope.launch {
            // Homey runs a card per event, so read it now and then, not on every refresh.
            if (System.currentTimeMillis() - homeyRead > HOMEY_EVERY || homeyEvents.isEmpty()) {
                runCatching { reader() }
                    .onSuccess { list ->
                        homeyEvents = list.map { it.toEvent() }
                        homeyRead = System.currentTimeMillis()
                        _homeyError.value = null
                    }
                    .onFailure { _homeyError.value = it.message ?: it.toString() }
            }
            if (homey !== reader) return@launch
            val counts = homeyEvents.groupingBy { it.calendarId }.eachCount()
            val calendars = homeyEvents.distinctBy { it.calendarId }.map { e ->
                CalendarInfo(e.calendarId, homeyNames[e.calendarId] ?: "Calendar", "Homey", e.color, visible = true, synced = true, events = counts[e.calendarId] ?: 0)
            }.sortedBy { it.name.lowercase() }
            _calendars.value = calendars
            _events.value = homeyEvents.filter { it.calendarId in homeyShown(calendars, selected.value) }
        }
    }

    /** The chosen calendars when they are Homey's; otherwise all of them. */
    private fun homeyShown(calendars: List<CalendarInfo>, chosen: Set<String>): Set<Long> {
        val ids = calendars.map { it.id }.toSet()
        return chosen.mapNotNull { it.toLongOrNull() }.filter { it in ids }.toSet().ifEmpty { ids }
    }

    private fun HomeyCalendarEvent.toEvent(): CalendarEvent {
        val calendarId = HOMEY_IDS + (calendar.hashCode().toLong() and 0xFFFFFFFL)
        homeyNames[calendarId] = calendar
        // The tablet's all-day events sit at UTC midnight, and the pages read them that way.
        fun utcDay(millis: Long) = java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        return CalendarEvent(
            id = uid.hashCode().toLong(),
            title = title,
            begin = if (allDay) utcDay(begin) else begin,
            end = if (allDay) utcDay(end).coerceAtLeast(utcDay(begin) + DAY) else end,
            allDay = allDay,
            color = HOMEY_COLORS[(calendarId % HOMEY_COLORS.size).toInt()],
            location = location,
            calendarId = calendarId,
        )
    }

    private fun usable() = homey != null || granted()

    private fun granted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Updates as soon as the calendar provider syncs a change. */
    private fun observe() {
        if (observing) return
        observing = true
        context.contentResolver.registerContentObserver(
            CalendarContract.CONTENT_URI,
            true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = refresh()
            },
        )
    }

    private fun queryCalendars(): List<CalendarInfo> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.SYNC_EVENTS,
        )
        val result = mutableListOf<CalendarInfo>()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                result += CalendarInfo(
                    id = c.getLong(0),
                    name = c.getString(1) ?: "Calendar",
                    account = c.getString(2) ?: "",
                    color = c.getInt(3),
                    visible = c.getInt(4) == 1,
                    synced = c.getInt(5) == 1,
                )
            }
        }
        return result.sortedWith(compareBy({ it.account.lowercase() }, { it.name.lowercase() }))
    }

    /** Whether events may be added to the tablet's calendars. */
    fun canWrite() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /**
     * The tablet's calendars events can be added to (the person's own Google calendar, a shared
     * family calendar), whether shown on the dashboard or not; never holidays or birthdays.
     */
    suspend fun writable(): List<AgentCalendar> {
        if (!granted() || !canWrite()) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val projection = arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME,
                    CalendarContract.Calendars.OWNER_ACCOUNT,
                    CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                    CalendarContract.Calendars.SYNC_EVENTS,
                )
                val result = mutableListOf<AgentCalendar>()
                context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        if (c.getInt(4) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR || c.getInt(5) != 1) continue
                        result += AgentCalendar(
                            id = c.getLong(0).toString(),
                            name = c.getString(1) ?: "Calendar",
                            account = c.getString(2).orEmpty(),
                            owner = c.getString(3).orEmpty(),
                        )
                    }
                }
                result.sortedWith(compareBy({ it.account.lowercase() }, { it.name.lowercase() }))
            }.getOrDefault(emptyList())
        }
    }

    /** Puts [event] in its calendar; the account syncs it to Google (or wherever it lives) on its own. */
    suspend fun add(event: NewEvent) {
        check(canWrite()) { "The dashboard may not add to the calendar. Allow it under Settings → Assistant → People." }
        val zone = ZoneId.systemDefault()
        val time = event.time
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, event.calendarId.toLong())
            put(CalendarContract.Events.TITLE, event.title)
            event.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            if (time == null) {
                // All-day events sit at UTC midnight and end at the next one.
                val start = event.date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, start + DAY)
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            } else {
                val start = event.date.atTime(time).atZone(zone).toInstant().toEpochMilli()
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, start + event.minutes * 60_000L)
                put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            }
        }
        withContext(Dispatchers.IO) {
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: error("The calendar didn't take the event.")
        }
        refresh()
    }

    /** Events in the calendars [ids] only, whether the dashboard shows them or not. */
    suspend fun eventsIn(ids: Set<Long>, from: LocalDate, until: LocalDate): List<CalendarEvent> {
        if (!granted()) return emptyList()
        return withContext(Dispatchers.IO) { runCatching { queryEvents(ids, from, until) }.getOrDefault(emptyList()) }
    }

    /** Events from [from] up to [until] (exclusive) in the chosen calendars, for the calendar page. */
    suspend fun events(from: LocalDate, until: LocalDate): List<CalendarEvent> {
        if (homey != null) {
            val zone = ZoneId.systemDefault()
            val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
            val stop = until.atStartOfDay(zone).toInstant().toEpochMilli()
            val shown = homeyShown(_calendars.value, selected.value)
            return homeyEvents.filter { it.end > start && it.begin < stop && it.calendarId in shown }
        }
        if (!granted()) return emptyList()
        val chosen = settings.calendars.first()
        return withContext(Dispatchers.IO) {
            runCatching {
                val calendars = if (chosen.isEmpty()) _calendars.value.ifEmpty { queryCalendars() } else emptyList()
                queryEvents(CalendarInfo.shown(calendars, chosen), from, until)
            }.getOrDefault(emptyList())
        }
    }

    // From the start of this month (for the month view) to two months ahead; null is every calendar.
    private fun queryEvents(calendarIds: Set<Long>?): List<CalendarEvent> {
        val monthStart = LocalDate.now(ZoneId.systemDefault()).withDayOfMonth(1)
        return queryEvents(calendarIds, monthStart, monthStart.plusMonths(2))
    }

    private fun queryEvents(calendarIds: Set<Long>?, fromDay: LocalDate, untilDay: LocalDate): List<CalendarEvent> {
        if (calendarIds?.isEmpty() == true) return emptyList()
        // A day either side covers all-day events, which are stored at UTC midnight.
        val zone = ZoneId.systemDefault()
        val from = fromDay.atStartOfDay(zone).toInstant().toEpochMilli() - DAY
        val to = untilDay.atStartOfDay(zone).toInstant().toEpochMilli() + DAY
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, from)
        ContentUris.appendId(builder, to)
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_ID,
        )
        val result = mutableListOf<CalendarEvent>()
        context.contentResolver.query(builder.build(), projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val calendarId = c.getLong(7)
                if (calendarIds != null && calendarId !in calendarIds) continue
                val event = CalendarEvent(
                    id = c.getLong(0),
                    title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "(No title)",
                    begin = c.getLong(2),
                    end = c.getLong(3),
                    allDay = c.getInt(4) == 1,
                    color = c.getInt(5),
                    location = c.getString(6)?.takeIf { it.isNotBlank() },
                    calendarId = calendarId,
                )
                result += event
            }
        }
        return result
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
        const val HOMEY_EVERY = 15 * 60 * 1000L
        /** Homey's calendars get ids far from the tablet's, so a saved choice of one never picks the other. */
        const val HOMEY_IDS = 1L shl 40
        val HOMEY_COLORS = intArrayOf(0xFF4C9AFF.toInt(), 0xFFE5534B.toInt(), 0xFF2BA79B.toInt(), 0xFFE08A3C.toInt(), 0xFF8A63D2.toInt())
    }
}
