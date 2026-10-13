package app.homeydash.ui.dashboard

import android.Manifest
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.homeydash.data.CalendarEvent
import app.homeydash.data.CalendarInfo
import app.homeydash.data.WeatherSource
import app.homeydash.data.WeatherState
import app.homeydash.homey.DayWeather
import app.homeydash.homey.Place
import app.homeydash.homey.WeatherKind
import app.homeydash.ui.components.DialogHeader
import app.homeydash.ui.components.GlassDialog
import app.homeydash.ui.components.Ic
import app.homeydash.ui.components.PillShape
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
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.YearMonth
import java.time.temporal.WeekFields
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

// ---------------------------------------------------------------- Weather

private val WhiteSoft = Color.White.copy(alpha = 0.72f)
private val WhiteFaint = Color.White.copy(alpha = 0.22f)

fun weatherIcon(kind: WeatherKind, isDay: Boolean): Ic = when (kind) {
    WeatherKind.Clear -> if (isDay) Sym.sunny else Sym.wxMoon
    WeatherKind.PartlyCloudy -> if (isDay) Sym.wxCloudSun else Sym.wxCloudMoon
    WeatherKind.Cloudy -> Sym.wxCloud
    WeatherKind.Fog -> Sym.wxFog
    WeatherKind.Drizzle, WeatherKind.Rain -> Sym.wxRain
    WeatherKind.Snow -> Sym.wxSnow
    WeatherKind.Storm -> Sym.wxStorm
}

/** Sky colours like the iOS Weather app: they follow the condition and day or night. */
private fun sky(kind: WeatherKind, isDay: Boolean): List<Color> = when {
    kind == WeatherKind.Storm -> listOf(Color(0xFF2E2A4F), Color(0xFF5B4E7A))
    kind == WeatherKind.Snow -> if (isDay) listOf(Color(0xFF7F9CBF), Color(0xFFC9D8EA)) else listOf(Color(0xFF26324A), Color(0xFF4F607E))
    kind == WeatherKind.Rain || kind == WeatherKind.Drizzle ->
        if (isDay) listOf(Color(0xFF3F5770), Color(0xFF7491AD)) else listOf(Color(0xFF1B2433), Color(0xFF3A4A60))
    kind == WeatherKind.Fog -> if (isDay) listOf(Color(0xFF7D8A97), Color(0xFFB9C3CE)) else listOf(Color(0xFF2E343C), Color(0xFF555E69))
    kind == WeatherKind.Cloudy -> if (isDay) listOf(Color(0xFF61758D), Color(0xFFA2B3C6)) else listOf(Color(0xFF232A35), Color(0xFF475365))
    isDay -> listOf(Color(0xFF2F7DE1), Color(0xFF7DB9F5))
    else -> listOf(Color(0xFF0A1633), Color(0xFF2C3B6B))
}

private fun deg(value: Double) = "${value.roundToInt()}°"

/**
 * The overview's weather, a smaller take on the full forecast: now, the next hours and
 * [days] days ahead (chosen in the weather dialog). Tap for the full forecast.
 */
@Composable
fun WeatherCard(state: WeatherState, days: Int, onClick: () -> Unit) {
    val forecast = state.forecast
    val current = forecast?.current
    val colors = remember(current?.kind, current?.isDay) { sky(current?.kind ?: WeatherKind.Clear, current?.isDay ?: true) }
    Column(
        Modifier
            .fillMaxWidth()
            .pressable(onClick)
            .clip(TileShape)
            .background(Brush.verticalGradient(colors))
            .border(1.dp, Color.White.copy(alpha = 0.16f), TileShape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (forecast == null || current == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SymIcon(Sym.wxCloudSun, filled = true, size = 24.dp, tint = Color.White)
                Text(state.error ?: "Loading the weather…", style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }
            return@Column
        }
        val today = forecast.days.firstOrNull()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                deg(current.temperature),
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Normal),
                color = Color.White,
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SymIcon(Sym.mapPin, filled = true, size = 13.dp, tint = Color.White)
                    Text(state.placeName ?: "", style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    listOfNotNull(current.kind.label, today?.let { "H:${deg(it.max)} L:${deg(it.min)}" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = WhiteSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SymIcon(weatherIcon(current.kind, current.isDay), filled = true, size = 40.dp, tint = Color.White)
        }
        HourlyRow(state, count = 5, iconSize = 20.dp)
        val shownDays = forecast.days.take(days)
        if (shownDays.isNotEmpty()) {
            Divider()
            val low = shownDays.minOf { it.min }
            val high = shownDays.maxOf { it.max }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                shownDays.forEachIndexed { i, day ->
                    DayRow(day, isToday = i == 0, low = low, high = high, now = current.temperature.takeIf { i == 0 }, iconSize = 18.dp)
                }
            }
        }
    }
}

/** The whole forecast, as in the iOS Weather app: now, the next hours and the week. */
@Composable
fun WeatherDetails(state: WeatherState, modifier: Modifier = Modifier) {
    val forecast = state.forecast
    val current = forecast?.current
    val colors = remember(current?.kind, current?.isDay) { sky(current?.kind ?: WeatherKind.Clear, current?.isDay ?: true) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(TileShape)
            .background(Brush.verticalGradient(colors))
            .border(1.dp, Color.White.copy(alpha = 0.16f), TileShape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (forecast == null || current == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SymIcon(Sym.wxCloudSun, filled = true, size = 28.dp, tint = Color.White)
                Text(
                    state.error ?: "Loading the weather…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                )
            }
            return@Column
        }
        val today = forecast.days.firstOrNull()
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SymIcon(Sym.mapPin, filled = true, size = 15.dp, tint = Color.White)
                    Text(state.placeName ?: "", style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    deg(current.temperature),
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 68.sp, lineHeight = 72.sp, fontWeight = FontWeight.Normal),
                    color = Color.White,
                )
                Text(current.kind.label, style = MaterialTheme.typography.titleMedium, color = Color.White)
                today?.let {
                    Text("H:${deg(it.max)}  L:${deg(it.min)}", style = MaterialTheme.typography.bodyMedium, color = WhiteSoft)
                }
            }
            SymIcon(weatherIcon(current.kind, current.isDay), filled = true, size = 64.dp, tint = Color.White)
        }
        Divider()
        HourlyRow(state)
        Divider()
        val days = forecast.days.take(6)
        val low = days.minOfOrNull { it.min } ?: 0.0
        val high = days.maxOfOrNull { it.max } ?: 1.0
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            days.forEachIndexed { i, day ->
                DayRow(day, isToday = i == 0, low = low, high = high, now = current.temperature.takeIf { i == 0 })
            }
        }
        listOfNotNull(
            current.feelsLike?.let { "Feels like ${deg(it)}" },
            current.humidity?.let { "Humidity ${it.roundToInt()}%" },
            current.wind?.let { "Wind ${it.roundToInt()} m/s" },
        ).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = WhiteSoft)
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(WhiteFaint))
}

@Composable
private fun HourlyRow(state: WeatherState, count: Int = 6, iconSize: Dp = 24.dp) {
    val forecast = state.forecast ?: return
    val now = rememberHour()
    val context = LocalContext.current
    val is24 = remember(context) { DateFormat.is24HourFormat(context) }
    val start = forecast.hours.indexOfFirst { it.time >= now }.coerceAtLeast(0)
    val hours = forecast.hours.drop(start).take(count)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        hours.forEachIndexed { i, hour ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val label = if (i == 0) "Now" else hour.time.substringAfter('T').take(2).toIntOrNull()?.let { h ->
                    if (is24) "%02d".format(h) else "${if (h % 12 == 0) 12 else h % 12}${if (h < 12) "am" else "pm"}"
                } ?: ""
                Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White)
                SymIcon(weatherIcon(hour.kind, hour.isDay), filled = true, size = iconSize, tint = Color.White)
                Text(deg(hour.temperature), style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }
    }
}

/** "2026-10-13T14:00" for the current hour, refreshed every few minutes. */
@Composable
private fun rememberHour(): String {
    val value by produceState(hourKey()) {
        while (true) {
            delay(5 * 60 * 1000L)
            value = hourKey()
        }
    }
    return value
}

private fun hourKey() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))

@Composable
private fun DayRow(day: DayWeather, isToday: Boolean, low: Double, high: Double, now: Double?, iconSize: Dp = 22.dp) {
    val name = if (isToday) "Today" else runCatching {
        LocalDate.parse(day.date).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }.getOrDefault(day.date)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.width(52.dp))
        Column(Modifier.width(34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SymIcon(weatherIcon(day.kind, true), filled = true, size = iconSize, tint = Color.White)
            day.rainChance?.takeIf { it >= 30 }?.let {
                Text("$it%", style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp), color = Color(0xFF9FD8FF))
            }
        }
        Text(deg(day.min), style = MaterialTheme.typography.labelLarge, color = WhiteSoft, textAlign = TextAlign.End, modifier = Modifier.width(36.dp))
        RangeBar(day.min, day.max, low, high, now, Modifier.weight(1f))
        Text(deg(day.max), style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.width(36.dp))
    }
}

/** The day's low-to-high span on the week's scale, coloured cool to warm, with a dot for now. */
@Composable
private fun RangeBar(min: Double, max: Double, low: Double, high: Double, now: Double?, modifier: Modifier) {
    val span = (high - low).takeIf { it > 0.1 } ?: 1.0
    val from = ((min - low) / span).toFloat().coerceIn(0f, 1f)
    val to = ((max - low) / span).toFloat().coerceIn(from, 1f)
    BoxWithConstraints(modifier.height(6.dp).clip(PillShape).background(WhiteFaint)) {
        val gradient = Brush.horizontalGradient(listOf(tempColor(min), tempColor(max)))
        Box(
            Modifier
                .offset(x = maxWidth * from)
                .width(maxWidth * (to - from).coerceAtLeast(0.04f))
                .height(6.dp)
                .background(gradient, PillShape),
        )
        now?.let {
            val at = ((it - low) / span).toFloat().coerceIn(0f, 1f)
            Box(
                Modifier
                    .offset(x = (maxWidth - 6.dp) * at)
                    .size(6.dp)
                    .background(Color.White, CircleShape),
            )
        }
    }
}

private fun tempColor(t: Double): Color = when {
    t < 0 -> Color(0xFF8FD3FF)
    t < 10 -> Color(0xFF7FE0C4)
    t < 18 -> Color(0xFFD7E76E)
    t < 25 -> Color(0xFFFFC65C)
    else -> Color(0xFFFF8A5B)
}

/** Choose where the weather is for: the Homey, the tablet, the internet address, or a place from a search. */
@Composable
fun WeatherDialog(
    state: WeatherState,
    /** Forecast days on the overview card. */
    days: Int,
    onDays: (Int) -> Unit,
    source: WeatherSource,
    place: Place?,
    hasLocationPermission: () -> Boolean,
    search: suspend (String) -> List<Place>,
    onChoose: (WeatherSource, Place?) -> Unit,
    onDismiss: () -> Unit,
) {
    val g = GlassTheme.colors
    var query by remember { mutableStateOf("") }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onChoose(WeatherSource.Gps, null)
    }
    GlassDialog(onDismiss = onDismiss, width = 980.dp) {
        DialogHeader(title = "Weather", subtitle = state.placeName ?: "Forecast and location", onClose = onDismiss)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        WeatherDetails(state, Modifier.width(420.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("DAYS ON THE OVERVIEW", style = MaterialTheme.typography.labelMedium, color = g.muted)
        Row(Modifier.fillMaxWidth().glass(PillShape).padding(4.dp)) {
            listOf(0, 1, 3, 5, 7).forEach { n ->
                val selected = n == days
                Text(
                    if (n == 0) "None" else "$n",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) g.onInk else g.fg,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .pressable({ onDays(n) })
                        .then(if (selected) Modifier.background(onBrush(), PillShape) else Modifier)
                        .padding(vertical = 8.dp),
                )
            }
        }
        Text("LOCATION", style = MaterialTheme.typography.labelMedium, color = g.muted)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Triple(WeatherSource.Homey, Sym.home, "Where your Homey is, from its location setting"),
                Triple(WeatherSource.Gps, Sym.gps, "The tablet's own location"),
                Triple(WeatherSource.Ip, Sym.globe, "Roughly, from the internet connection"),
            ).forEach { (option, icon, detail) ->
                SourceRow(icon, option.label, detail, selected = source == option) {
                    if (option == WeatherSource.Gps && !hasLocationPermission()) {
                        askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    } else {
                        onChoose(option, null)
                    }
                }
            }
            SourceRow(
                Sym.mapPin,
                place?.name ?: "A place you choose",
                place?.detail ?: "Search below",
                selected = source == WeatherSource.Place,
                onClick = place?.let { { onChoose(WeatherSource.Place, it) } },
            )
        }
        Row(
            Modifier.fillMaxWidth().glass(PillShape).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SymIcon(Sym.search, size = 20.dp, tint = g.muted)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search for a town or city", style = MaterialTheme.typography.bodyLarge, color = g.muted)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = g.fg),
                    cursorBrush = SolidColor(g.fg),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        val results by produceState<List<Place>>(emptyList(), query) {
            if (query.length < 2) {
                value = emptyList()
                return@produceState
            }
            delay(300)
            value = search(query)
        }
        if (results.isNotEmpty()) {
            LazyColumn(
                Modifier.heightIn(max = 260.dp).fillMaxWidth().glass(RoundedCornerShape(18.dp)).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(results) { result ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .pressable({ onChoose(WeatherSource.Place, result); query = "" })
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(result.name, style = MaterialTheme.typography.titleSmall, color = g.fg)
                        result.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = g.muted) }
                    }
                }
            }
        }
        }
        }
    }
}

@Composable
private fun SourceRow(icon: Ic, title: String, detail: String, selected: Boolean, onClick: (() -> Unit)?) {
    val g = GlassTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick)
            .then(if (selected) Modifier.background(onBrush(), RoundedCornerShape(18.dp)) else Modifier.glass(RoundedCornerShape(18.dp)))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val ink = if (selected) g.onInk else g.fg
        SymIcon(icon, filled = selected, size = 22.dp, tint = ink)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = ink)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = if (selected) ink.copy(alpha = 0.75f) else g.muted)
        }
        if (selected) SymIcon(Sym.check, filled = true, size = 20.dp, tint = ink)
    }
}

// ---------------------------------------------------------------- Calendar

/** The tablet's calendars as Today, Week or Month, like the iOS Calendar widgets. */
@Composable
fun CalendarCard(
    hasPermission: Boolean,
    events: List<CalendarEvent>,
    view: String,
    onView: (String) -> Unit,
    onPermissionResult: () -> Unit,
    onChooseCalendars: () -> Unit,
) {
    val g = GlassTheme.colors
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onPermissionResult() }
    val zone = remember { ZoneId.systemDefault() }
    val today = LocalDate.now(zone)
    val timeFormat = remember(context) { DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a") }
    val dayFormat = remember { DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()) }
    // Each day an event touches, so a three-day trip shows on all three.
    val byDay = remember(events, zone) {
        val map = sortedMapOf<LocalDate, MutableList<CalendarEvent>>()
        events.forEach { event -> eventDays(event, zone).forEach { day -> map.getOrPut(day) { mutableListOf() } += event } }
        map
    }
    val now = System.currentTimeMillis()

    Column(
        Modifier
            .fillMaxWidth()
            .glass(TileShape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SymIcon(Sym.calendar, filled = true, size = 20.dp, tint = g.fg)
            if (hasPermission) {
                Row(Modifier.weight(1f).glass(PillShape).padding(3.dp)) {
                    listOf("today" to "Today", "week" to "Week", "month" to "Month").forEach { (key, label) ->
                        val selected = view == key
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) g.onInk else g.fg,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .pressable({ onView(key) })
                                .then(if (selected) Modifier.background(onBrush(), PillShape) else Modifier)
                                .padding(vertical = 6.dp),
                        )
                    }
                }
                SymIcon(Sym.sliders, size = 20.dp, tint = g.muted, modifier = Modifier.pressable(onChooseCalendars))
            } else {
                Text("Calendar", style = MaterialTheme.typography.titleMedium, color = g.fg, modifier = Modifier.weight(1f))
            }
        }
        if (!hasPermission) {
            Text(
                "Show the calendars on this tablet, such as your Google family calendar.",
                style = MaterialTheme.typography.bodyMedium,
                color = g.muted,
            )
            Text(
                "Show my calendars",
                style = MaterialTheme.typography.labelLarge,
                color = g.onInk,
                modifier = Modifier
                    .pressable({ ask.launch(Manifest.permission.READ_CALENDAR) })
                    .background(onBrush(), PillShape)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
            return@Column
        }
        val row: @Composable (CalendarEvent) -> Unit = { event -> EventRow(event, zone, timeFormat) }
        when (view) {
            "today" -> {
                val list = byDay[today].orEmpty().filter { it.end > now || it.allDay }
                Text(today.format(dayFormat).replaceFirstChar { it.titlecase(Locale.getDefault()) }, style = MaterialTheme.typography.titleSmall, color = g.fg)
                if (list.isEmpty()) Text("Nothing more today.", style = MaterialTheme.typography.bodyLarge, color = g.muted)
                list.forEach { row(it) }
            }
            "month" -> {
                var selected by remember { mutableStateOf(today) }
                var shownMonth by remember { mutableStateOf(YearMonth.from(today)) }
                MonthGrid(shownMonth, today, selected, byDay, onSelect = { selected = it }, onMonth = { shownMonth = shownMonth.plusMonths(it.toLong()) })
                val list = byDay[selected].orEmpty()
                Text(
                    when (selected) {
                        today -> "Today"
                        today.plusDays(1) -> "Tomorrow"
                        else -> selected.format(dayFormat).replaceFirstChar { it.titlecase(Locale.getDefault()) }
                    }.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = g.muted,
                )
                if (list.isEmpty()) Text("Nothing planned.", style = MaterialTheme.typography.bodyMedium, color = g.muted)
                list.take(6).forEach { row(it) }
            }
            else -> {
                val week = (0L until 7L).map { today.plusDays(it) }
                    .associateWith { day -> byDay[day].orEmpty().filter { day != today || it.end > now || it.allDay } }
                    .filterValues { it.isNotEmpty() }
                if (week.isEmpty()) {
                    Text("Nothing planned this week.", style = MaterialTheme.typography.bodyLarge, color = g.muted)
                }
                var shown = 0
                week.forEach { (date, dayEvents) ->
                    if (shown >= 10) return@forEach
                    Text(
                        when (date) {
                            today -> "Today"
                            today.plusDays(1) -> "Tomorrow"
                            else -> date.format(dayFormat).replaceFirstChar { it.titlecase(Locale.getDefault()) }
                        }.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = g.muted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    dayEvents.take(10 - shown).forEach { row(it); shown++ }
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: CalendarEvent, zone: ZoneId, timeFormat: DateTimeFormatter) {
    val g = GlassTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(4.dp).height(34.dp).background(Color(event.color).copy(alpha = 1f), PillShape))
        Column(Modifier.weight(1f)) {
            Text(event.title, style = MaterialTheme.typography.titleSmall, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val time = if (event.allDay) {
                "All day"
            } else {
                val start = Instant.ofEpochMilli(event.begin).atZone(zone)
                val end = Instant.ofEpochMilli(event.end).atZone(zone)
                "${start.format(timeFormat)} – ${end.format(timeFormat)}"
            }
            Text(
                listOfNotNull(time, event.location).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = g.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A month of days with a dot per event, Monday first (or the locale's first day). */
@Composable
private fun MonthGrid(
    month: YearMonth,
    today: LocalDate,
    selected: LocalDate,
    byDay: Map<LocalDate, List<CalendarEvent>>,
    onSelect: (LocalDate) -> Unit,
    onMonth: (Int) -> Unit,
) {
    val g = GlassTheme.colors
    val firstDay = remember { WeekFields.of(Locale.getDefault()).firstDayOfWeek }
    val monthFormat = remember { DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()) }
    val start = month.atDay(1).let { first -> first.minusDays(((first.dayOfWeek.value - firstDay.value + 7) % 7).toLong()) }
    val weeks = ((month.atEndOfMonth().toEpochDay() - start.toEpochDay()) / 7 + 1).toInt()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                month.format(monthFormat).replaceFirstChar { it.titlecase(Locale.getDefault()) },
                style = MaterialTheme.typography.titleSmall,
                color = g.fg,
                modifier = Modifier.weight(1f),
            )
            SymIcon(Sym.keyboardArrowLeft, size = 20.dp, tint = g.muted, modifier = Modifier.pressable({ onMonth(-1) }).padding(6.dp))
            SymIcon(Sym.keyboardArrowRight, size = 20.dp, tint = g.muted, modifier = Modifier.pressable({ onMonth(1) }).padding(6.dp))
        }
        Row {
            (0L until 7L).forEach { i ->
                Text(
                    firstDay.plus(i).getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelMedium,
                    color = g.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        repeat(weeks) { w ->
            Row {
                (0 until 7).forEach { d ->
                    val day = start.plusDays((w * 7 + d).toLong())
                    val inMonth = day.month == month.month
                    val isSelected = day == selected
                    val dayEvents = byDay[day].orEmpty()
                    Column(
                        Modifier
                            .weight(1f)
                            .pressable({ onSelect(day) })
                            .padding(vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .then(
                                    when {
                                        isSelected -> Modifier.background(onBrush(), CircleShape)
                                        day == today -> Modifier.border(1.5.dp, g.fg.copy(alpha = 0.6f), CircleShape)
                                        else -> Modifier
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                day.dayOfMonth.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = when {
                                    isSelected -> g.onInk
                                    inMonth -> g.fg
                                    else -> g.muted.copy(alpha = 0.5f)
                                },
                            )
                        }
                        Row(Modifier.height(6.dp).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            dayEvents.take(3).forEach { event ->
                                Box(Modifier.size(4.dp).background(Color(event.color).copy(alpha = 1f), CircleShape))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The local days an event covers; all-day events are stored at UTC midnight. */
private fun eventDays(event: CalendarEvent, zone: ZoneId): List<LocalDate> {
    val first: LocalDate
    val last: LocalDate
    if (event.allDay) {
        first = Instant.ofEpochMilli(event.begin).atZone(ZoneOffset.UTC).toLocalDate()
        last = Instant.ofEpochMilli(event.end - 1).atZone(ZoneOffset.UTC).toLocalDate()
    } else {
        first = Instant.ofEpochMilli(event.begin).atZone(zone).toLocalDate()
        last = Instant.ofEpochMilli(maxOf(event.begin, event.end - 1)).atZone(zone).toLocalDate()
    }
    val days = (last.toEpochDay() - first.toEpochDay()).coerceIn(0L, 41L)
    return (0..days).map { first.plusDays(it) }
}

/** Which of the tablet's calendars show on the dashboard. */
@Composable
fun CalendarsDialog(
    calendars: List<CalendarInfo>,
    selected: Set<String>,
    onSelected: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val g = GlassTheme.colors
    val effective = if (selected.isEmpty()) calendars.filter { it.visible }.map { it.id.toString() }.toSet() else selected
    GlassDialog(onDismiss = onDismiss, width = 560.dp) {
        DialogHeader(title = "Calendars", subtitle = "Choose what the overview shows", onClose = onDismiss)
        if (calendars.isEmpty()) {
            Text(
                "No calendars on this tablet yet. Add your Google account in Android Settings → Accounts and turn on calendar sync.",
                style = MaterialTheme.typography.bodyLarge,
                color = g.muted,
            )
            return@GlassDialog
        }
        LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(calendars, key = { it.id }) { calendar ->
                val id = calendar.id.toString()
                val on = id in effective
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressable({ onSelected(if (on) effective - id else effective + id) })
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(Modifier.size(14.dp).background(Color(calendar.color).copy(alpha = 1f), CircleShape))
                    Column(Modifier.weight(1f)) {
                        Text(calendar.name, style = MaterialTheme.typography.titleSmall, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(calendar.account, style = MaterialTheme.typography.bodyMedium, color = g.muted, maxLines = 1)
                    }
                    Toggle(on)
                }
            }
        }
        Text(
            "A family calendar shows here once it is synced in the Google Calendar app on this tablet.",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
    }
}
