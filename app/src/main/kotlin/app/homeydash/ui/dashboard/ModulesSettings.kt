package app.homeydash.ui.dashboard

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.homeydash.ui.assistant.Hint
import app.homeydash.data.Modules
import app.homeydash.homey.Device
import app.homeydash.modules.AppModules
import app.homeydash.modules.HomeyAppRef
import app.homeydash.modules.ModuleKind
import app.homeydash.modules.ModuleSource
import app.homeydash.modules.ModuleState
import app.homeydash.modules.ModuleStatus
import app.homeydash.modules.SourceKind
import app.homeydash.ui.components.Bubble
import app.homeydash.ui.components.Ic
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.RoundButton
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.TileShape
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme

/** Each module's icon and colour on the modules page. */
private val LOOKS: Map<String, Pair<Ic, Color>> = mapOf(
    "calendar" to (Sym.calendar to Color(0xFFE5534B)),
    "weather" to (Sym.wxCloudSun to Color(0xFF3A8DDE)),
    "music" to (Sym.musicNotes to Color(0xFF1DB954)),
    "phones" to (Sym.phone to Color(0xFF6E7BF2)),
    "vacuum" to (Sym.vacuum to Color(0xFF8A63D2)),
    "tv" to (Sym.tv to Color(0xFF55657A)),
    "home" to (Sym.people to Color(0xFF2BA79B)),
    "fridge" to (Sym.fridge to Color(0xFF3BA3C7)),
    "recipes" to (Sym.chefHat to Color(0xFFE08A3C)),
    "energy" to (Sym.bolt to Color(0xFFC9A227)),
    "departures" to (Sym.clock to Color(0xFFD0457A)),
    "doorbell" to (Sym.doorbell to Color(0xFF5A6B80)),
)

internal fun moduleLook(id: String) = LOOKS[id] ?: (Sym.apps to Color(0xFF55657A))

/** Amber for "needs something from you"; the theme has green and red only. */
private val Attention = Color(0xFFE9A23B)

@Composable
internal fun statusColor(status: ModuleStatus): Color {
    val g = GlassTheme.colors
    return when (status) {
        ModuleStatus.On -> g.ok
        ModuleStatus.NeedsApp -> Attention
        else -> g.muted
    }
}

/**
 * The modules page in settings, like Homey's apps: every part of the dashboard as a tile, and
 * a page per module with its source (built in or a Homey app), the Homey apps it needs, where
 * it shows and what the assistant does with it.
 */
@Composable
internal fun ModulesSettings(modules: Modules, devices: Map<String, Device>, homeyCalendarError: String?) {
    val choices by modules.choices.collectAsStateWithLifecycle()
    val apps by modules.apps.collectAsStateWithLifecycle()
    val appsAllowed by modules.appsAllowed.collectAsStateWithLifecycle()
    val states = remember(choices, apps, devices) { AppModules.states(choices, apps, devices.values) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val state = states.firstOrNull { it.module.id == open }
    if (state == null) {
        ModuleList(states, appsAllowed) { open = it }
    } else {
        BackHandler { open = null }
        ModulePage(
            state = state,
            appsAllowed = appsAllowed,
            error = homeyCalendarError.takeIf { state.module.id == "calendar" && state.source.kind == SourceKind.HomeyApp },
            onBack = { open = null },
            onChoice = { modules.set(state.module.id, it) },
            onCheckAgain = modules::refresh,
        )
    }
}

@Composable
private fun ModuleList(states: List<ModuleState>, appsAllowed: Boolean, onOpen: (String) -> Unit) {
    val g = GlassTheme.colors
    Text(
        "Turn parts of the dashboard on and off, and choose whether each uses the app's own connection or a Homey app.",
        style = MaterialTheme.typography.bodyLarge,
        color = g.muted,
        modifier = Modifier.padding(start = 4.dp),
    )
    if (!appsAllowed) PermissionNote()
    val groups = listOf(
        "Needs setup" to states.filter { it.status == ModuleStatus.NeedsApp },
        "In use" to states.filter { it.status == ModuleStatus.On },
        "Not in use" to states.filter { it.status == ModuleStatus.Off || it.status == ModuleStatus.NoDevices },
        "Coming later" to states.filter { it.status == ModuleStatus.Planned },
    )
    groups.filter { it.second.isNotEmpty() }.forEach { (title, list) ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = g.muted, modifier = Modifier.padding(start = 14.dp, top = 8.dp))
            list.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { ModuleTile(it, Modifier.weight(1f).fillMaxHeight()) { onOpen(it.module.id) } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ModuleTile(state: ModuleState, modifier: Modifier, onClick: () -> Unit) {
    val g = GlassTheme.colors
    val (icon, tint) = moduleLook(state.module.id)
    Row(
        modifier.pressable(onClick).glass(TileShape).padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Bubble(icon, on = state.status != ModuleStatus.Planned, onColor = tint, size = 48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(state.module.name, style = MaterialTheme.typography.titleSmall, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tileLine(state), style = MaterialTheme.typography.bodyMedium, color = g.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            StatusLine(state.status)
        }
    }
}

/** What the tile says under the name: where the data comes from, or what's missing. */
private fun tileLine(state: ModuleState): String = when {
    state.status == ModuleStatus.NeedsApp -> "Needs the ${state.apps.firstOrNull { !it.second }?.first?.name ?: "Homey"} app on Homey"
    state.module.kind == ModuleKind.Devices && state.devices.isNotEmpty() ->
        state.devices.size.let { if (it == 1) "1 device in Homey" else "$it devices in Homey" }
    else -> state.source.label
}

@Composable
private fun StatusLine(status: ModuleStatus) {
    val color = statusColor(status)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(status.label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
private fun ModulePage(
    state: ModuleState,
    appsAllowed: Boolean,
    error: String?,
    onBack: () -> Unit,
    onChoice: (app.homeydash.modules.ModuleChoice) -> Unit,
    onCheckAgain: () -> Unit,
) {
    val g = GlassTheme.colors
    val module = state.module
    val (icon, tint) = moduleLook(module.id)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        RoundButton(Sym.arrowBack, onBack, size = 44.dp, filledIcon = false, contentDescription = "All modules")
        Bubble(icon, on = true, onColor = tint, size = 56.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(module.name, style = MaterialTheme.typography.titleLarge, color = g.fg)
            Text(module.summary, style = MaterialTheme.typography.bodyMedium, color = g.muted)
        }
        if (module.kind == ModuleKind.Feature) {
            Toggle(state.choice.on, onClick = { onChoice(state.choice.copy(on = !state.choice.on)) })
        } else {
            StatusLine(state.status)
        }
    }
    state.apps.firstOrNull { !it.second }?.first?.let { missing ->
        if (state.status == ModuleStatus.NeedsApp) MissingApp(missing, onCheckAgain)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val left: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingsBox("Where it comes from") {
                    module.sources.forEach { source ->
                        SourceRow(
                            source = source,
                            selected = source.id == state.source.id,
                            installed = state.installed(source),
                            choosable = module.kind == ModuleKind.Feature && module.sources.size > 1,
                            planned = module.kind == ModuleKind.Planned,
                            onClick = { onChoice(state.choice.copy(source = source.id)) },
                        )
                    }
                    if (error != null) {
                        Text("Couldn't read the calendar from Homey: $error", style = MaterialTheme.typography.bodyMedium, color = g.danger)
                    }
                }
                NeedsBox(state, appsAllowed)
            }
        }
        val right: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (module.kind == ModuleKind.Devices) DevicesBox(state.devices)
                SettingsBox("Shows on") {
                    module.showsOn.forEach { Bullet(it) }
                }
                SettingsBox("Assistant") {
                    Text(module.assistant, style = MaterialTheme.typography.bodyLarge, color = g.fg)
                    if (module.kind != ModuleKind.Planned) {
                        Hint(
                            "The assistant remembers what you choose here, so it knows where this comes from.",
                        )
                    }
                }
            }
        }
        if (maxWidth > 720.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.weight(3f)) { left() }
                Box(Modifier.weight(2f)) { right() }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { left(); right() }
        }
    }
}

@Composable
private fun SourceRow(source: ModuleSource, selected: Boolean, installed: Boolean, choosable: Boolean, planned: Boolean, onClick: () -> Unit) {
    val g = GlassTheme.colors
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick.takeIf { choosable })
            .then(if (selected && choosable) Modifier.glass(shape) else Modifier.border(1.dp, g.glassLine, shape))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (choosable) {
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .size(24.dp)
                    .then(if (selected) Modifier.background(g.fg, CircleShape) else Modifier.border(2.dp, g.muted, CircleShape)),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) SymIcon(Sym.check, filled = true, size = 14.dp, tint = g.wallBase)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(source.label, style = MaterialTheme.typography.labelLarge, color = g.fg)
            Text(source.detail, style = MaterialTheme.typography.bodyMedium, color = g.muted)
            val (text, color) = when {
                planned -> "Not built yet" to g.muted
                source.kind == SourceKind.BuiltIn -> "Works on this tablet" to g.ok
                source.kind == SourceKind.Homey -> "Comes with Homey" to g.ok
                installed -> "Installed on Homey" to g.ok
                else -> "Not installed on Homey" to Attention
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

@Composable
private fun NeedsBox(state: ModuleState, appsAllowed: Boolean) {
    val g = GlassTheme.colors
    val context = LocalContext.current
    SettingsBox("Needs in Homey") {
        if (state.apps.isEmpty()) {
            Text(
                if (state.source.kind == SourceKind.Homey) "Nothing extra. Homey has this built in." else "Nothing. This works on the tablet by itself.",
                style = MaterialTheme.typography.bodyLarge,
                color = g.fg,
            )
        }
        state.apps.forEach { (app, installed) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("${app.name} app", style = MaterialTheme.typography.labelLarge, color = g.fg)
                    Text(if (installed) "Installed" else "Not installed", style = MaterialTheme.typography.bodyMedium, color = if (installed) g.ok else Attention)
                }
                if (!installed) StoreButton(app) { openStore(context, app) }
            }
        }
        if (!appsAllowed && state.apps.isNotEmpty()) {
            Hint(
                "Your API key can't list Homey's apps, so they are worked out from your devices. Give the key the \"View apps\" permission in Homey for a full list.",
            )
        }
    }
}

@Composable
private fun DevicesBox(devices: List<Device>) {
    val g = GlassTheme.colors
    SettingsBox("Devices in Homey") {
        if (devices.isEmpty()) {
            Text("None yet. Add them in the Homey app and they show up here.", style = MaterialTheme.typography.bodyLarge, color = g.muted)
        }
        devices.take(MAX_DEVICES).forEach { Bullet(it.name) }
        if (devices.size > MAX_DEVICES) {
            Text("and ${devices.size - MAX_DEVICES} more", style = MaterialTheme.typography.bodyMedium, color = g.muted)
        }
    }
}

private const val MAX_DEVICES = 8

@Composable
private fun MissingApp(app: HomeyAppRef, onCheckAgain: () -> Unit) {
    val g = GlassTheme.colors
    val context = LocalContext.current
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Attention.copy(alpha = 0.12f), shape)
            .border(1.dp, Attention.copy(alpha = 0.45f), shape)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Install ${app.name} on Homey", style = MaterialTheme.typography.titleSmall, color = g.fg)
            Hint(
                "Install it from the Homey App Store and add your devices there. This module turns on by itself when it finds them.",
            )
        }
        Text(
            "Check again",
            style = MaterialTheme.typography.labelLarge,
            color = g.fg,
            modifier = Modifier.pressable(onCheckAgain).glass(PillShape).padding(horizontal = 18.dp, vertical = 12.dp),
        )
        StoreButton(app) { openStore(context, app) }
    }
}

@Composable
private fun StoreButton(app: HomeyAppRef, onClick: () -> Unit) {
    val g = GlassTheme.colors
    Text(
        "Get it on Homey",
        style = MaterialTheme.typography.labelLarge,
        color = g.wallBase,
        modifier = Modifier.pressable(onClick).background(g.fg, PillShape).padding(horizontal = 18.dp, vertical = 12.dp),
    )
}

private fun openStore(context: android.content.Context, app: HomeyAppRef) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.storeUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun Bullet(text: String) {
    val g = GlassTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(6.dp).background(g.muted, CircleShape))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = g.fg)
    }
}

@Composable
private fun PermissionNote() {
    val g = GlassTheme.colors
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SymIcon(Sym.key, size = 22.dp, tint = g.muted)
        Hint(
            "Your API key can't list Homey's apps, so they are worked out from your devices. Add the \"View apps\" permission to the key in Homey for a full list.",
            modifier = Modifier.weight(1f),
        )
    }
}
