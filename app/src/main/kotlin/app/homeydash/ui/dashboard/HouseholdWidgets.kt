package app.homeydash.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.homeydash.homey.HomeyAlarm
import app.homeydash.homey.HomeyHttpException
import app.homeydash.homey.HomeyUser
import app.homeydash.homey.Household
import app.homeydash.homey.HouseholdState
import app.homeydash.homey.HouseholdStore
import app.homeydash.ui.components.DialogHeader
import app.homeydash.ui.components.GlassDialog
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.RoundButton
import app.homeydash.ui.components.SectionLabel
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.TileShape
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.onBrush
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/** Now, ticking each minute, for "rings tomorrow 06:30" and "home since". */
@Composable
private fun rememberNow(): ZonedDateTime {
    val now by produceState(ZonedDateTime.now()) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            value = ZonedDateTime.now()
        }
    }
    return now
}

/**
 * Who's home: each Homey user as a round badge, green when home, dimmed when away, with a
 * moon when asleep; under them the next wake-up alarm. Tapping opens [HouseholdDialog].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhoIsHomeCard(household: HouseholdStore, onOpen: () -> Unit) {
    val g = GlassTheme.colors
    val state by household.state.collectAsStateWithLifecycle()
    val now = rememberNow()
    val people = state.people
    val home = people.count { it.present == true }
    val asleep = people.count { it.asleep == true }
    Column(
        Modifier.fillMaxWidth().pressable(onOpen).glass(TileShape).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SymIcon(Sym.people, filled = true, size = 20.dp, tint = g.fg)
            Column(Modifier.weight(1f)) {
                Text("Who's home", style = MaterialTheme.typography.titleMedium, color = g.fg)
                Text(
                    when {
                        !state.usersAllowed -> "The API key can't see users"
                        people.isEmpty() -> if (state.loaded) "No Homey users" else "Loading…"
                        home == 0 -> "Nobody is home"
                        home == people.size -> "Everyone is home" + if (asleep > 0) " · $asleep asleep" else ""
                        else -> "$home of ${people.size} home" + if (asleep > 0) " · $asleep asleep" else ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = g.muted,
                    maxLines = 1,
                )
            }
        }
        if (people.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                people.forEach { PersonBadge(it, state.since[it.id], now) }
            }
        }
        NextAlarmLine(state, now)
    }
}

@Composable
private fun PersonBadge(user: HomeyUser, since: Long?, now: ZonedDateTime) {
    val g = GlassTheme.colors
    val home = user.present == true
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.width(68.dp)) {
        Box {
            Avatar(user, size = 52)
            if (user.asleep == true) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(22.dp).background(g.wallBase, CircleShape).padding(3.dp),
                    contentAlignment = Alignment.Center,
                ) { SymIcon(Sym.moon, filled = true, size = 14.dp, tint = g.cool) }
            } else if (home) {
                Box(Modifier.align(Alignment.BottomEnd).size(16.dp).background(g.wallBase, CircleShape).padding(3.dp).background(g.ok, CircleShape))
            }
        }
        Text(firstName(user.name), style = MaterialTheme.typography.labelLarge, color = if (home) g.fg else g.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            statusText(user, since, now, short = true),
            style = MaterialTheme.typography.bodySmall,
            color = g.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A round badge with the person's initials, bright when they're home. */
@Composable
private fun Avatar(user: HomeyUser, size: Int) {
    val g = GlassTheme.colors
    val home = user.present == true
    val tint = avatarColor(user.name)
    Box(
        Modifier
            .size(size.dp)
            .background(if (home) tint else tint.copy(alpha = 0.22f), CircleShape)
            .border(2.dp, if (home) g.ok else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials(user.name),
            fontSize = (size * 0.36f).sp,
            fontWeight = FontWeight.SemiBold,
            color = if (home) Color.White else g.muted,
        )
    }
}

@Composable
private fun NextAlarmLine(state: HouseholdState, now: ZonedDateTime) {
    val g = GlassTheme.colors
    if (!state.alarmsAllowed) return
    val next = Household.next(state.alarms, now)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SymIcon(Sym.alarm, filled = next != null, size = 18.dp, tint = if (next != null) g.fg else g.muted)
        Text(
            if (next == null) "No wake-up alarm set" else "${next.first.name} · ${Household.whenText(next.second, now)}",
            style = MaterialTheme.typography.bodyLarge,
            color = if (next != null) g.fg else g.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Who's home, with buttons to mark people home, away or asleep, and Homey's wake-up alarms to edit. */
@Composable
fun HouseholdDialog(household: HouseholdStore, onDismiss: () -> Unit) {
    val g = GlassTheme.colors
    val state by household.state.collectAsStateWithLifecycle()
    val now = rememberNow()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    // The alarm being edited: null for none, an alarm with an empty id for a new one.
    var editing by remember { mutableStateOf<HomeyAlarm?>(null) }
    fun attempt(what: String, block: suspend () -> Unit) {
        scope.launch {
            error = null
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = explain(what, e)
            }
        }
    }

    GlassDialog(onDismiss = onDismiss, width = 920.dp) {
        DialogHeader(
            title = "Home and wake-up",
            subtitle = "Straight from Homey, so Flows that use presence and alarms keep working",
            onClose = onDismiss,
            leading = {
                Box(Modifier.size(52.dp).background(onBrush(), CircleShape), contentAlignment = Alignment.Center) {
                    SymIcon(Sym.people, filled = true, size = 26.dp, tint = g.onInk)
                }
            },
        )
        error?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = g.danger) }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f).heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Who's home")
                when {
                    !state.usersAllowed -> Hint("Give the Homey API key the \"View users\" permission to see who's home.")
                    state.people.isEmpty() -> Hint(if (state.loaded) "No Homey users yet." else "Loading…")
                    else -> state.people.forEach { user ->
                        PersonRow(
                            user, state.since[user.id], now,
                            onHome = { attempt("mark ${user.name} home") { household.setPresent(user.id, true) } },
                            onAway = { attempt("mark ${user.name} away") { household.setPresent(user.id, false) } },
                            onAsleep = { asleep -> attempt("mark ${user.name} ${if (asleep) "asleep" else "awake"}") { household.setAsleep(user.id, asleep) } },
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Wake-up alarms")
                val edit = editing
                when {
                    !state.alarmsAllowed -> Hint("Give the Homey API key the \"View alarms\" permission to see Homey's alarms, and \"Alarms\" to change them.")
                    edit != null -> AlarmEditor(
                        edit,
                        onSave = { name, time, days ->
                            attempt("save the alarm") {
                                household.saveAlarm(edit.id.ifEmpty { null }, name, time, days, true)
                                editing = null
                            }
                        },
                        onDelete = if (edit.id.isEmpty()) null else ({ attempt("delete the alarm") { household.deleteAlarm(edit.id); editing = null } }),
                        onCancel = { editing = null },
                    )
                    else -> {
                        if (state.alarms.isEmpty()) Hint("No alarms yet. Homey Flows can start when one goes off, like opening the blinds or playing the radio.")
                        state.alarms.forEach { alarm ->
                            AlarmRow(
                                alarm, now,
                                skippedUntil = state.skipped[alarm.id],
                                onToggle = { on -> attempt("switch ${alarm.name} ${if (on) "on" else "off"}") { household.setEnabled(alarm.id, on) } },
                                onSkip = { attempt("skip ${alarm.name}") { household.skipNext(alarm.id) } },
                                onEdit = { editing = alarm },
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth().pressable({ editing = HomeyAlarm("", "Väckning", LocalTime.of(7, 0), true, Household.WEEKDAYS) })
                                .glass(PillShape).padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SymIcon(Sym.add, size = 20.dp, tint = g.fg)
                            Text("New alarm", style = MaterialTheme.typography.titleSmall, color = g.fg, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonRow(user: HomeyUser, since: Long?, now: ZonedDateTime, onHome: () -> Unit, onAway: () -> Unit, onAsleep: (Boolean) -> Unit) {
    val g = GlassTheme.colors
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(user, size = 44)
            Column(Modifier.weight(1f)) {
                Text(user.name, style = MaterialTheme.typography.titleSmall, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(statusText(user, since, now, short = false), style = MaterialTheme.typography.bodyMedium, color = g.muted, maxLines = 1)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(Sym.home, "Home", user.present == true && user.asleep != true, onHome)
            Choice(Sym.directionsWalk, "Away", user.present == false, onAway)
            Choice(Sym.moon, if (user.asleep == true) "Wake" else "Asleep", user.asleep == true) { onAsleep(user.asleep != true) }
        }
    }
}

@Composable
private fun Choice(icon: app.homeydash.ui.components.Ic, label: String, selected: Boolean, onClick: () -> Unit) {
    val g = GlassTheme.colors
    Row(
        Modifier.pressable(onClick)
            .then(if (selected) Modifier.background(onBrush(), PillShape) else Modifier.glass(PillShape))
            .padding(start = 10.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SymIcon(icon, filled = selected, size = 16.dp, tint = if (selected) g.onInk else g.fg)
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) g.onInk else g.fg, maxLines = 1)
    }
}

@Composable
private fun AlarmRow(
    alarm: HomeyAlarm,
    now: ZonedDateTime,
    skippedUntil: Long?,
    onToggle: (Boolean) -> Unit,
    onSkip: () -> Unit,
    onEdit: () -> Unit,
) {
    val g = GlassTheme.colors
    val ring = Household.nextRing(alarm, now)
    val skipped = skippedUntil != null
    Column(
        Modifier.fillMaxWidth().pressable(onEdit).glass(RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                Household.timeText(alarm.time),
                style = MaterialTheme.typography.headlineMedium,
                color = if (alarm.enabled || skipped) g.fg else g.muted,
            )
            Column(Modifier.weight(1f)) {
                Text(alarm.name, style = MaterialTheme.typography.titleSmall, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    Household.daysText(alarm.days).replaceFirstChar(Char::uppercase) + " · " + when {
                        skipped -> "skips its next ring"
                        ring != null -> "rings ${Household.whenText(ring, now)}"
                        else -> "off"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = g.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Toggle(alarm.enabled || skipped, onClick = { onToggle(!(alarm.enabled || skipped)) })
        }
        if (alarm.enabled && alarm.repeats && !skipped) {
            Text(
                "Skip the next one",
                style = MaterialTheme.typography.labelLarge,
                color = g.muted,
                modifier = Modifier.pressable(onSkip).padding(vertical = 4.dp),
            )
        }
    }
}

/** Time with steppers, a name, and the days it repeats; no days means once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlarmEditor(
    alarm: HomeyAlarm,
    onSave: (String, LocalTime, Set<DayOfWeek>) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    val g = GlassTheme.colors
    var time by remember(alarm.id) { mutableStateOf(alarm.time) }
    var name by remember(alarm.id) { mutableStateOf(alarm.name) }
    var days by remember(alarm.id) { mutableStateOf(alarm.days) }
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Stepper(time.hour.toString().padStart(2, '0'), onUp = { time = time.plusHours(1) }, onDown = { time = time.minusHours(1) })
            Text(":", style = MaterialTheme.typography.displaySmall, color = g.fg, modifier = Modifier.padding(horizontal = 8.dp))
            Stepper(
                time.minute.toString().padStart(2, '0'),
                onUp = { time = time.withMinute((time.minute / 5 * 5 + 5) % 60) },
                onDown = { time = time.withMinute(((time.minute + 4) / 5 * 5 - 5 + 60) % 60) },
            )
        }
        Row(
            Modifier.fillMaxWidth().glass(PillShape).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (name.isEmpty()) Text("Name, e.g. Väckning Anna", style = MaterialTheme.typography.bodyLarge, color = g.muted)
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = g.fg),
                    cursorBrush = SolidColor(g.fg),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DayOfWeek.entries.forEach { d ->
                val on = d in days
                Box(
                    Modifier.size(40.dp).pressable({ days = if (on) days - d else days + d })
                        .then(if (on) Modifier.background(onBrush(), CircleShape) else Modifier.glass(CircleShape)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(d.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelLarge, color = if (on) g.onInk else g.fg)
                }
            }
        }
        Text(
            if (days.isEmpty()) "Rings once, at the next ${Household.timeText(time)}." else "Repeats ${Household.daysText(days)}.",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).pressable({ onSave(name, time, days) }).background(onBrush(), PillShape).padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
            ) { Text("Save", style = MaterialTheme.typography.titleSmall, color = g.onInk) }
            Text("Cancel", style = MaterialTheme.typography.titleSmall, color = g.fg, modifier = Modifier.pressable(onCancel).padding(12.dp))
            onDelete?.let { RoundButton(Sym.trash, it, size = 44.dp, filledIcon = false) }
        }
    }
}

@Composable
private fun Stepper(value: String, onUp: () -> Unit, onDown: () -> Unit) {
    val g = GlassTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RoundButton(Sym.keyboardArrowUp, onUp, size = 40.dp, filledIcon = false)
        Text(value, style = MaterialTheme.typography.displaySmall, color = g.fg)
        RoundButton(Sym.keyboardArrowDown, onDown, size = 40.dp, filledIcon = false)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = GlassTheme.colors.muted)
}

/**
 * Goodnight's part: tomorrow's alarm with a switch to skip it, and whether everyone home is
 * marked asleep in Homey. [markAsleep] is Goodnight's choice; it does the marking when pressed.
 */
@Composable
fun GoodnightWakeUp(household: HouseholdStore, markAsleep: Boolean, onMarkAsleep: (Boolean) -> Unit) {
    val g = GlassTheme.colors
    val state by household.state.collectAsStateWithLifecycle()
    val now = rememberNow()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val home = state.people.filter { it.present == true }
    if (home.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().pressable({ onMarkAsleep(!markAsleep) }).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SymIcon(Sym.moon, filled = markAsleep, size = 22.dp, tint = if (markAsleep) g.fg else g.muted)
            Column(Modifier.weight(1f)) {
                Text("Mark everyone home as asleep", style = MaterialTheme.typography.titleSmall, color = g.fg)
                Text(home.joinToString { firstName(it.name) } + " · for Homey Flows that wait for everyone asleep", style = MaterialTheme.typography.bodyMedium, color = g.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Toggle(markAsleep)
        }
    }
    if (!state.alarmsAllowed) return
    // The alarms that would ring before noon tomorrow, skipped ones included.
    val tomorrow = now.toLocalDate().plusDays(1)
    val morning = state.alarms.mapNotNull { a ->
        val skipped = a.id in state.skipped
        Household.nextRing(if (skipped) a.copy(enabled = true) else a, now)?.takeIf { it.toLocalDate() == tomorrow && it.hour < 12 }?.let { Triple(a, it, skipped) }
    }
    if (morning.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SymIcon(Sym.alarm, size = 22.dp, tint = g.muted)
            Text("No wake-up alarm tomorrow morning", style = MaterialTheme.typography.bodyLarge, color = g.muted)
        }
    }
    morning.forEach { (alarm, ring, skipped) ->
        Row(
            Modifier.fillMaxWidth().pressable({
                scope.launch {
                    error = null
                    try {
                        if (skipped) household.setEnabled(alarm.id, true) else household.skipNext(alarm.id)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = explain("change ${alarm.name}", e)
                    }
                }
            }).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SymIcon(Sym.alarm, filled = !skipped, size = 22.dp, tint = if (skipped) g.muted else g.fg)
            Column(Modifier.weight(1f)) {
                Text("${alarm.name} · ${Household.timeText(ring.toLocalTime())}", style = MaterialTheme.typography.titleSmall, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        skipped -> "No wake-up tomorrow"
                        alarm.repeats -> "Rings tomorrow · tap to skip just tomorrow"
                        else -> "Rings tomorrow · tap to switch it off"
                    }, style = MaterialTheme.typography.bodyMedium, color = g.muted)
            }
            Toggle(!skipped)
        }
    }
    error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = g.danger) }
}

/** Marks everyone who is home as asleep in Homey; a refusal is reported in the banner by [onError]. */
suspend fun markEveryoneAsleep(household: HouseholdStore, onError: (String) -> Unit) {
    for (u in household.state.value.people.filter { it.present == true && it.asleep != true }) {
        try {
            household.setAsleep(u.id, true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(explain("mark ${u.name} asleep", e))
            return
        }
    }
}

private fun explain(what: String, e: Exception): String = when {
    e is HomeyHttpException && (e.code == 401 || e.code == 403) ->
        "Homey wouldn't let the tablet $what. Give the API key the \"Presence\" and \"Alarms\" permissions in the Homey web app (Settings → API Keys)."
    else -> "Couldn't $what: ${e.message ?: e::class.simpleName}"
}

private fun statusText(user: HomeyUser, since: Long?, now: ZonedDateTime, short: Boolean): String {
    val word = when {
        user.asleep == true -> "Asleep"
        user.present == true -> "Home"
        user.present == false -> "Away"
        else -> "Unknown"
    }
    val at = since?.let { Instant.ofEpochMilli(it).atZone(now.zone) } ?: return word
    val time = Household.whenText(at, now).removePrefix("today ")
    if (short) return if (at.toLocalDate() == now.toLocalDate()) "$word $time" else word
    return "$word since $time"
}

private fun firstName(name: String) = name.trim().split(' ').first()

private fun initials(name: String): String {
    val parts = name.trim().split(' ').filter { it.isNotEmpty() }
    return (parts.firstOrNull()?.take(1).orEmpty() + if (parts.size > 1) parts.last().take(1) else "").uppercase()
}

/** A steady colour per person, from a few soft ones. */
private fun avatarColor(name: String): Color = AVATAR_COLORS[(name.lowercase().hashCode() and 0x7fffffff) % AVATAR_COLORS.size]

private val AVATAR_COLORS = listOf(
    Color(0xFF5B8DEF), Color(0xFFE5795C), Color(0xFF4DB6AC), Color(0xFFB27BD8), Color(0xFFE0A33C), Color(0xFF6CA85C), Color(0xFFD9668F),
)
