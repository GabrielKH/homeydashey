package app.homeydash.ui.dashboard

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.homeydash.data.CalendarEvent
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.RoundButton
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.TileShape
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.onBrush
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

private val HourHeight = 64.dp

/**
 * The full calendar: a month of days with their events, a week in columns, or one day
 * on a timeline, with the chosen day's agenda alongside.
 */
@Composable
fun CalendarScreen(
    hasPermission: Boolean,
    /** Changes whenever the calendars sync, so the page loads again. */
    events: List<CalendarEvent>,
    load: suspend (from: LocalDate, until: LocalDate) -> List<CalendarEvent>,
    onPermissionResult: () -> Unit,
    onChooseCalendars: () -> Unit,
) {
    val g = GlassTheme.colors
    val zone = remember { ZoneId.systemDefault() }
    val today = rememberToday(zone)
    var view by rememberSaveable { mutableStateOf("month") }
    var selectedDay by rememberSaveable { mutableStateOf(LocalDate.now(zone).toEpochDay()) }
    val selected = LocalDate.ofEpochDay(selectedDay)
    val firstDay = remember { WeekFields.of(Locale.getDefault()).firstDayOfWeek }
    val weekStart = selected.minusDays(((selected.dayOfWeek.value - firstDay.value + 7) % 7).toLong())
    val month = YearMonth.from(selected)
    val monthStart = month.atDay(1).let { it.minusDays(((it.dayOfWeek.value - firstDay.value + 7) % 7).toLong()) }
    val (from, until) = when (view) {
        "day" -> selected to selected.plusDays(1)
        "week" -> weekStart to weekStart.plusDays(7)
        else -> monthStart to monthStart.plusDays(42)
    }
    val shown by produceState(emptyList<CalendarEvent>(), from, until, events, hasPermission) { value = load(from, until) }
    val upcoming by produceState(emptyList<CalendarEvent>(), selected, events, hasPermission) { value = load(selected, selected.plusDays(14)) }
    val byDay = remember(shown, zone) { groupByDay(shown, zone) }
    val upcomingByDay = remember(upcoming, zone) { groupByDay(upcoming, zone) }

    val monthFormat = remember { DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()) }
    val dayFormat = remember { DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()) }
    val shortFormat = remember { DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()) }
    val title = when (view) {
        "day" -> selected.format(dayFormat)
        "week" -> "${weekStart.format(shortFormat)} – ${weekStart.plusDays(6).format(shortFormat)}"
        else -> month.format(monthFormat)
    }.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    val step: (Int) -> Unit = { direction ->
        selectedDay = when (view) {
            "day" -> selected.plusDays(direction.toLong())
            "week" -> selected.plusWeeks(direction.toLong())
            else -> selected.plusMonths(direction.toLong())
        }.toEpochDay()
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(eyebrow = "Calendar", title = title) {
            if (hasPermission) {
                Segmented(
                    options = listOf("day" to "Day", "week" to "Week", "month" to "Month"),
                    selected = view,
                    onSelect = { view = it },
                    modifier = Modifier.width(260.dp),
                )
                RoundButton(Sym.keyboardArrowLeft, { step(-1) }, size = 42.dp, filledIcon = false, contentDescription = "Back")
                Text(
                    "Today",
                    style = MaterialTheme.typography.labelLarge,
                    color = g.fg,
                    modifier = Modifier.pressable({ selectedDay = today.toEpochDay() }).glass(PillShape).padding(horizontal = 16.dp, vertical = 11.dp),
                )
                RoundButton(Sym.keyboardArrowRight, { step(1) }, size = 42.dp, filledIcon = false, contentDescription = "Forward")
                IconChip(Sym.sliders, onChooseCalendars)
            }
        }
        if (!hasPermission) {
            Box(Modifier.widthIn(max = 420.dp)) {
                CalendarCard(
                    hasPermission = false,
                    events = emptyList(),
                    view = "week",
                    onView = {},
                    onPermissionResult = onPermissionResult,
                    onChooseCalendars = onChooseCalendars,
                )
            }
            return@Column
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().glass(TileShape).clip(TileShape)) {
                when (view) {
                    "day" -> DayTimeline(selected, today, byDay[selected].orEmpty(), zone)
                    "week" -> WeekColumns(weekStart, today, selected, byDay, zone, onSelect = { selectedDay = it.toEpochDay() })
                    else -> BigMonth(month, monthStart, today, selected, byDay, zone, onSelect = { selectedDay = it.toEpochDay() })
                }
            }
            Agenda(
                selected = selected,
                today = today,
                byDay = upcomingByDay,
                zone = zone,
                modifier = Modifier.width(330.dp).fillMaxHeight(),
            )
        }
    }
}

/** A row of choices in a glass pill, the selected one filled. */
@Composable
internal fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val g = GlassTheme.colors
    Row(modifier.glass(PillShape).padding(3.dp)) {
        options.forEach { (key, label) ->
            val on = selected == key
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (on) g.onInk else g.fg,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .pressable({ onSelect(key) })
                    .then(if (on) Modifier.background(onBrush(), PillShape) else Modifier)
                    .padding(vertical = 8.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- Month

@Composable
private fun BigMonth(
    month: YearMonth,
    start: LocalDate,
    today: LocalDate,
    selected: LocalDate,
    byDay: Map<LocalDate, List<CalendarEvent>>,
    zone: ZoneId,
    onSelect: (LocalDate) -> Unit,
) {
    val g = GlassTheme.colors
    val weeks = ((month.atEndOfMonth().toEpochDay() - start.toEpochDay()) / 7 + 1).toInt()
    val timeFormat = rememberTimeFormat()
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Row(Modifier.padding(bottom = 6.dp)) {
            (0L until 7L).forEach { i ->
                Text(
                    start.plusDays(i).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = g.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        repeat(weeks) { w ->
            Row(Modifier.weight(1f)) {
                (0 until 7).forEach { d ->
                    val day = start.plusDays((w * 7 + d).toLong())
                    MonthCell(
                        day = day,
                        inMonth = day.month == month.month,
                        isToday = day == today,
                        isSelected = day == selected,
                        events = byDay[day].orEmpty(),
                        zone = zone,
                        timeFormat = timeFormat,
                        onClick = { onSelect(day) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthCell(
    day: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    events: List<CalendarEvent>,
    zone: ZoneId,
    timeFormat: DateTimeFormatter,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val g = GlassTheme.colors
    BoxWithConstraints(
        modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(14.dp))
            .pressable(onClick)
            .then(if (isSelected) Modifier.background(g.fg.copy(alpha = 0.08f)) else Modifier)
            .padding(5.dp),
    ) {
        // As many events as fit under the date; the rest are counted.
        val fits = ((maxHeight - 32.dp) / 21.dp).toInt().coerceAtLeast(0)
        val shown = if (events.size > fits) (fits - 1).coerceAtLeast(0) else events.size
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(
                Modifier
                    .size(28.dp)
                    .then(
                        when {
                            isToday -> Modifier.background(onBrush(), CircleShape)
                            isSelected -> Modifier.border(1.5.dp, g.fg.copy(alpha = 0.6f), CircleShape)
                            else -> Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    day.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        isToday -> g.onInk
                        inMonth -> g.fg
                        else -> g.muted.copy(alpha = 0.5f)
                    },
                )
            }
            events.take(shown).forEach { event -> EventPill(event, zone, timeFormat) }
            if (shown < events.size) {
                Text(
                    "+${events.size - shown} more",
                    style = MaterialTheme.typography.labelMedium,
                    color = g.muted,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

/** All-day events as a coloured bar, timed ones as a dot, the time and the title. */
@Composable
private fun EventPill(event: CalendarEvent, zone: ZoneId, timeFormat: DateTimeFormatter) {
    val g = GlassTheme.colors
    val color = Color(event.color).copy(alpha = 1f)
    if (event.allDay) {
        Text(
            event.title,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().height(18.dp).background(color.copy(alpha = 0.85f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp),
        )
    } else {
        Row(Modifier.height(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(7.dp).background(color, CircleShape))
            Text(
                Instant.ofEpochMilli(event.begin).atZone(zone).format(timeFormat) + "  " + event.title,
                style = MaterialTheme.typography.labelMedium,
                color = g.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------- Week

@Composable
private fun WeekColumns(
    start: LocalDate,
    today: LocalDate,
    selected: LocalDate,
    byDay: Map<LocalDate, List<CalendarEvent>>,
    zone: ZoneId,
    onSelect: (LocalDate) -> Unit,
) {
    val g = GlassTheme.colors
    val timeFormat = rememberTimeFormat()
    Row(Modifier.fillMaxSize().padding(8.dp)) {
        (0L until 7L).forEach { i ->
            val day = start.plusDays(i)
            val isToday = day == today
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(2.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .pressable({ onSelect(day) })
                    .then(if (day == selected) Modifier.background(g.fg.copy(alpha = 0.07f)) else Modifier)
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isToday) g.fg else g.muted,
                    )
                    Box(
                        Modifier.padding(top = 2.dp).size(36.dp).then(if (isToday) Modifier.background(onBrush(), CircleShape) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, color = if (isToday) g.onInk else g.fg)
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    byDay[day].orEmpty().forEach { event -> EventBlock(event, zone, timeFormat) }
                }
            }
        }
    }
}

/** An event as a small card: a tinted panel with its colour on the left edge. */
@Composable
private fun EventBlock(event: CalendarEvent, zone: ZoneId, timeFormat: DateTimeFormatter, modifier: Modifier = Modifier) {
    val g = GlassTheme.colors
    val color = Color(event.color).copy(alpha = 1f)
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = if (g.isDark) 0.28f else 0.18f)),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(color))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                if (event.allDay) "All day" else Instant.ofEpochMilli(event.begin).atZone(zone).format(timeFormat),
                style = MaterialTheme.typography.labelMedium,
                color = g.muted,
                maxLines = 1,
            )
            Text(event.title, style = MaterialTheme.typography.labelLarge, color = g.fg, maxLines = 3, overflow = TextOverflow.Ellipsis)
            event.location?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = g.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

// ---------------------------------------------------------------- Day

/** One day on a timeline, scrolled to the morning, with a line at the current time. */
@Composable
private fun DayTimeline(day: LocalDate, today: LocalDate, events: List<CalendarEvent>, zone: ZoneId) {
    val g = GlassTheme.colors
    val timeFormat = rememberTimeFormat()
    val context = LocalContext.current
    val hourFormat = remember(context) { DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:00" else "h a", Locale.getDefault()) }
    val allDay = events.filter { it.allDay }
    val timed = remember(events, day) { layoutDay(events.filterNot { it.allDay }, day, zone) }
    val scroll = rememberScrollState()
    val hourPx = with(LocalDensity.current) { HourHeight.toPx() }
    LaunchedEffect(day) {
        val hour = if (day == today) (LocalDateTime.now().hour - 1).coerceAtLeast(0) else 7
        scroll.scrollTo((hour * hourPx).toInt())
    }
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (allDay.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                allDay.forEach { EventBlock(it, zone, timeFormat) }
            }
        }
        if (events.isEmpty()) {
            Text("Nothing planned.", style = MaterialTheme.typography.bodyLarge, color = g.muted)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
            val labelWidth = 64.dp
            val lane = maxWidth - labelWidth
            Box(Modifier.fillMaxWidth().height(HourHeight * 24)) {
                (0 until 24).forEach { hour ->
                    Row(Modifier.fillMaxWidth().offset(y = HourHeight * hour), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            LocalDateTime.of(day, java.time.LocalTime.of(hour, 0)).format(hourFormat),
                            style = MaterialTheme.typography.labelMedium,
                            color = g.muted,
                            modifier = Modifier.width(labelWidth),
                        )
                        Box(Modifier.weight(1f).height(1.dp).background(g.glassLine))
                    }
                }
                timed.forEach { placed ->
                    val top = HourHeight * (placed.startMinutes / 60f)
                    val height = (HourHeight * ((placed.endMinutes - placed.startMinutes) / 60f)).coerceAtLeast(26.dp)
                    val width = lane / placed.lanes
                    EventBlock(
                        placed.event,
                        zone,
                        timeFormat,
                        Modifier
                            .offset(x = labelWidth + width * placed.lane, y = top + 8.dp)
                            .width(width - 4.dp)
                            .height(height - 2.dp),
                    )
                }
                if (day == today) {
                    val now = LocalDateTime.now()
                    val y = HourHeight * ((now.hour * 60 + now.minute) / 60f) + 8.dp
                    Row(Modifier.offset(x = labelWidth - 5.dp, y = y - 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(g.danger, CircleShape))
                        Box(Modifier.width(lane).height(2.dp).background(g.danger))
                    }
                }
            }
        }
    }
}

private class PlacedEvent(val event: CalendarEvent, val startMinutes: Int, val endMinutes: Int, val lane: Int, var lanes: Int)

/** Side by side when events overlap, like a calendar app's day view. */
private fun layoutDay(events: List<CalendarEvent>, day: LocalDate, zone: ZoneId): List<PlacedEvent> {
    val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val result = mutableListOf<PlacedEvent>()
    var cluster = mutableListOf<PlacedEvent>()
    var clusterEnd = -1
    val laneEnds = mutableListOf<Int>()
    events
        .map { it to (((it.begin - dayStart) / 60_000L).toInt().coerceIn(0, 24 * 60)) }
        .sortedBy { it.second }
        .forEach { (event, start) ->
            val end = (((event.end - dayStart) / 60_000L).toInt().coerceIn(start + 15, 24 * 60))
            if (start >= clusterEnd) {
                cluster.forEach { it.lanes = laneEnds.size }
                cluster = mutableListOf()
                laneEnds.clear()
            }
            var lane = laneEnds.indexOfFirst { it <= start }
            if (lane < 0) {
                lane = laneEnds.size
                laneEnds += end
            } else {
                laneEnds[lane] = end
            }
            val placed = PlacedEvent(event, start, end, lane, 1)
            cluster += placed
            result += placed
            clusterEnd = maxOf(clusterEnd, end)
        }
    cluster.forEach { it.lanes = laneEnds.size }
    return result
}

// ---------------------------------------------------------------- Agenda

/** The chosen day's events, then what comes up over the next two weeks. */
@Composable
private fun Agenda(selected: LocalDate, today: LocalDate, byDay: Map<LocalDate, List<CalendarEvent>>, zone: ZoneId, modifier: Modifier) {
    val g = GlassTheme.colors
    val timeFormat = rememberTimeFormat()
    val dayFormat = remember { DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()) }
    fun label(day: LocalDate) = when (day) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(dayFormat).replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
    Column(
        modifier.glass(TileShape).padding(18.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(selected.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }, style = MaterialTheme.typography.bodyLarge, color = g.muted)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(selected.dayOfMonth.toString(), style = MaterialTheme.typography.displayMedium, color = g.fg, fontWeight = FontWeight.Light)
            Text(
                selected.month.getDisplayName(TextStyle.FULL, Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) },
                style = MaterialTheme.typography.titleLarge,
                color = g.fg,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        val first = byDay[selected].orEmpty()
        if (first.isEmpty()) Text("Nothing planned.", style = MaterialTheme.typography.bodyLarge, color = g.muted)
        first.forEach { AgendaRow(it, zone, timeFormat) }
        val later = byDay.filterKeys { it > selected }.filterValues { it.isNotEmpty() }
        if (later.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(g.glassLine))
            Text("COMING UP", style = MaterialTheme.typography.labelMedium, color = g.muted, modifier = Modifier.padding(top = 4.dp))
        }
        var count = 0
        later.forEach { (day, events) ->
            if (count >= 14) return@forEach
            Text(label(day).uppercase(), style = MaterialTheme.typography.labelMedium, color = g.muted, modifier = Modifier.padding(top = 4.dp))
            events.take(14 - count).forEach { AgendaRow(it, zone, timeFormat); count++ }
        }
    }
}

@Composable
private fun AgendaRow(event: CalendarEvent, zone: ZoneId, timeFormat: DateTimeFormatter) {
    val g = GlassTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(4.dp).height(36.dp).background(Color(event.color).copy(alpha = 1f), PillShape))
        Column(Modifier.weight(1f)) {
            Text(event.title, style = MaterialTheme.typography.titleSmall, color = g.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val time = if (event.allDay) {
                "All day"
            } else {
                "${Instant.ofEpochMilli(event.begin).atZone(zone).format(timeFormat)} – ${Instant.ofEpochMilli(event.end).atZone(zone).format(timeFormat)}"
            }
            Text(listOfNotNull(time, event.location).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = g.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- Helpers

private fun groupByDay(events: List<CalendarEvent>, zone: ZoneId): Map<LocalDate, List<CalendarEvent>> {
    val map = sortedMapOf<LocalDate, MutableList<CalendarEvent>>()
    events.forEach { event -> eventDays(event, zone).forEach { day -> map.getOrPut(day) { mutableListOf() } += event } }
    // All-day events first, then by start time.
    map.values.forEach { list -> list.sortWith(compareBy({ !it.allDay }, { it.begin })) }
    return map
}

@Composable
private fun rememberTimeFormat(): DateTimeFormatter {
    val context = LocalContext.current
    return remember(context) { DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a") }
}

/** Today's date, updated at midnight. */
@Composable
private fun rememberToday(zone: ZoneId): LocalDate {
    val today by produceState(LocalDate.now(zone)) {
        while (true) {
            delay(60_000L)
            value = LocalDate.now(zone)
        }
    }
    return today
}
