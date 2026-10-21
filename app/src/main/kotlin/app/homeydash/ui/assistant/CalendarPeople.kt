package app.homeydash.ui.assistant

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.homeydash.HomeyDashApp
import app.homeydash.agent.AgentCalendar
import app.homeydash.agent.AgentSettings
import app.homeydash.agent.CalendarBook
import app.homeydash.agent.KnownPerson
import app.homeydash.assistant.AssistantServices
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.pressable
import app.homeydash.ui.dashboard.openAppSettings
import app.homeydash.ui.dashboard.permanentlyRefused
import app.homeydash.ui.theme.GlassTheme

/** The tablet's calendars Nova and TARS may add to, read again when the dashboard comes back from Android's settings. */
private class Writable(val calendars: List<AgentCalendar>, val allowed: Boolean, val ask: () -> Unit, val addAccount: () -> Unit)

@Composable
private fun rememberWritable(): Writable {
    val context = LocalContext.current
    val repo = remember(context) { (context.applicationContext as HomeyDashApp).container.calendar }
    var round by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        round++
        onPauseOrDispose { }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        // Once refused for good, Android answers at once without asking, so open the app's settings instead.
        if (granted.values.any { !it } && permanentlyRefused(context, Manifest.permission.WRITE_CALENDAR)) openAppSettings(context)
        repo.permissionChanged()
        round++
    }
    val allowed = remember(round) { repo.canWrite() && repo.hasPermission.value }
    val calendars by produceState(emptyList<AgentCalendar>(), round) { value = repo.writable() }
    return Writable(
        calendars,
        allowed,
        ask = { ask.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) },
        addAccount = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ADD_ACCOUNT)
                        .putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
    )
}

/**
 * Someone's own calendar: the email of their account, which must be added to the tablet
 * (Android's Settings → Accounts) so its calendar syncs here. "Min kalender" is then theirs.
 */
@Composable
internal fun PersonCalendar(services: AssistantServices, person: KnownPerson) {
    val g = GlassTheme.colors
    val w = rememberWritable()
    Text("Own calendar", style = MaterialTheme.typography.labelLarge, color = g.fg)
    Field("Email", person.calendar, keyboard = androidx.compose.ui.text.input.KeyboardType.Email, hint = "name@gmail.com") { email ->
        services.faces.setCalendar(person.id, email)
    }
    val email = person.calendar.trim()
    val found = CalendarBook.ownedBy(w.calendars, email)
    when {
        email.isEmpty() -> Text(
            "The email of ${person.name}'s Google account. Nova and TARS add events to its calendar when ${person.name} says \"min kalender\".",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
        !w.allowed -> Action("The dashboard may not add to calendars yet.", "Allow", w.ask)
        found != null -> Status("\"${found.name}\" is on the tablet. Events ${person.name} adds go there.", ok = true)
        else -> Action("$email isn't on the tablet yet. Add the account with calendar sync on.", "Add account", w.addAccount)
    }
}

/** Which of the tablet's calendars is the family's: one picked, or the one called family. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FamilyCalendar(services: AssistantServices, settings: AgentSettings) {
    val g = GlassTheme.colors
    val w = rememberWritable()
    Text("Family calendar", style = MaterialTheme.typography.labelLarge, color = g.fg)
    Text(
        "Where Nova and TARS put events that aren't anyone's own (\"lägg in i familjekalendern\"). It must be a calendar the tablet can write to, " +
            "for example a Google calendar shared with everyone's accounts.",
        style = MaterialTheme.typography.bodyMedium,
        color = g.muted,
    )
    when {
        !w.allowed -> Action("The dashboard may not add to calendars yet.", "Allow", w.ask)
        w.calendars.isEmpty() -> Action("There are no calendars on the tablet to add to.", "Add account", w.addAccount)
        else -> {
            val current = CalendarBook.family(w.calendars, settings.familyCalendar)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                w.calendars.forEach { c ->
                    val label = if (c.name.equals(c.account, ignoreCase = true)) c.name else "${c.name} · ${c.account}"
                    Chip(label, c.id == current?.id) { services.settingsStore.update { it.copy(familyCalendar = c.id) } }
                }
            }
        }
    }
}

@Composable
private fun Status(text: String, ok: Boolean) {
    val g = GlassTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).background(if (ok) g.ok else g.muted.copy(alpha = 0.5f), CircleShape))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (ok) g.fg else g.muted)
    }
}

@Composable
private fun Action(text: String, button: String, onClick: () -> Unit) {
    val g = GlassTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = g.muted, modifier = Modifier.weight(1f))
        Text(
            button,
            style = MaterialTheme.typography.labelLarge,
            color = g.fg,
            modifier = Modifier.pressable(onClick).glass(PillShape).padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
