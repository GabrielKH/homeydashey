package app.homeydash.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CalendarInfo(val id: Long, val name: String, val account: String, val color: Int, val visible: Boolean)

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
 * calendar) through Android's calendar provider; no sign-in of its own.
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

    init {
        scope.launch { settings.calendars.collect { refresh() } }
        scope.launch {
            while (true) {
                delay(5 * 60 * 1000L)
                refresh()
            }
        }
    }

    /** Call after the permission dialog closes. */
    fun permissionChanged() {
        _hasPermission.value = granted()
        refresh()
    }

    fun setSelected(ids: Set<String>) {
        scope.launch { settings.setCalendars(ids) }
    }

    fun refresh() {
        if (!granted()) return
        _hasPermission.value = true
        observe()
        job?.cancel()
        job = scope.launch {
            val chosen = settings.calendars.first()
            val (calendars, events) = withContext(Dispatchers.IO) {
                val calendars = runCatching { queryCalendars() }.getOrDefault(emptyList())
                val shown = if (chosen.isEmpty()) calendars.filter { it.visible }.map { it.id }.toSet() else chosen.mapNotNull { it.toLongOrNull() }.toSet()
                calendars to runCatching { queryEvents(shown) }.getOrDefault(emptyList())
            }
            _calendars.value = calendars
            _events.value = events
        }
    }

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
                )
            }
        }
        return result.sortedWith(compareBy({ it.account.lowercase() }, { it.name.lowercase() }))
    }

    private fun queryEvents(calendarIds: Set<Long>): List<CalendarEvent> {
        if (calendarIds.isEmpty()) return emptyList()
        // From the start of this month (for the month view) to two months ahead; a day either
        // side covers all-day events, which are stored at UTC midnight.
        val zone = ZoneId.systemDefault()
        val monthStart = LocalDate.now(zone).withDayOfMonth(1)
        val from = monthStart.atStartOfDay(zone).toInstant().toEpochMilli() - DAY
        val to = monthStart.plusMonths(2).atStartOfDay(zone).toInstant().toEpochMilli() + DAY
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
                if (calendarId !in calendarIds) continue
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
    }
}
