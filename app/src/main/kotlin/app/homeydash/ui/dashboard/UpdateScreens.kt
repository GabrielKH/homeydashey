package app.homeydash.ui.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.homeydash.data.AppUpdater
import app.homeydash.data.Modules
import app.homeydash.homey.Device
import app.homeydash.modules.AppModules
import app.homeydash.modules.ModuleKind
import app.homeydash.modules.ModuleStatus
import app.homeydash.updates.Change
import app.homeydash.ui.components.GlassDialog
import app.homeydash.ui.components.Ic
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.RoundButton
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.onBrush
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CardShape = RoundedCornerShape(20.dp)

/**
 * Settings → App and updates: the version on the tablet, the newer one with what it brings and a
 * download bar, what earlier updates brought, and every feature with whether it's on.
 * [modules] and [devices] fill the features; without them (the small settings) it's left out.
 */
@Composable
internal fun AppSettings(
    updater: AppUpdater,
    update: AppUpdater.State,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    modules: Modules? = null,
    devices: Map<String, Device> = emptyMap(),
) {
    val latest by updater.latest.collectAsStateWithLifecycle()
    val lastChecked by updater.lastChecked.collectAsStateWithLifecycle()
    val pending = remember(latest) { updater.pending(latest) }
    val installed = updater.installedChanges
    val newer = update !is AppUpdater.State.UpToDate
    val newVersion = latest?.version?.takeIf { it.isNotEmpty() } ?: buildOf(update)?.let { "0.2.$it" }

    VersionCard(updater.currentVersion, newVersion, latest?.apkBytes ?: 0, lastChecked, update, onCheckUpdate, onInstallUpdate, updater::cancel)

    // Before an update: what it brings. Otherwise: what this version brought.
    val shown = if (newer && pending.isNotEmpty()) pending.take(8) else installed.take(6)
    if (shown.isNotEmpty()) {
        SettingsBox(if (newer && pending.isNotEmpty()) "What's new in ${newVersion ?: "the update"}" else "In this version") {
            if (newer && pending.isNotEmpty()) {
                Text(
                    if (pending.size == 1) "1 change since your version" else "${pending.size} changes since your version",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlassTheme.colors.muted,
                )
            }
            ChangeGrid(shown, columns = 2)
        }
    }

    val earlier = remember(installed, shown) { installed.filter { it !in shown }.groupBy { it.day }.entries.take(8).toList() }
    if (earlier.isNotEmpty()) {
        SettingsBox("Earlier updates") {
            earlier.forEach { (day, changes) -> EarlierDay(day, changes) }
        }
    }

    if (modules != null) Features(modules, devices)
}

@Composable
private fun VersionCard(
    version: String,
    newVersion: String?,
    apkBytes: Long,
    lastChecked: Long?,
    update: AppUpdater.State,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    val g = GlassTheme.colors
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(19.dp)).background(onBrush()), contentAlignment = Alignment.Center) {
                SymIcon(Sym.home, filled = true, size = 34.dp, tint = g.onInk)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Homey Dashboard", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = g.fg)
                    StatusChip(update)
                }
                Text(
                    when (update) {
                        AppUpdater.State.UpToDate -> "You have the newest version, $version."
                        is AppUpdater.State.Failed -> "The update didn't finish: ${update.message}"
                        else -> buildString {
                            append("You have $version. ")
                            append(if (newVersion != null) "Version $newVersion is ready" else "A newer version is ready")
                            if (apkBytes > 0) append(" (${megabytes(apkBytes)} MB)")
                            append('.')
                        }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = g.muted,
                )
                Text(
                    "Checks for updates every 3 hours" + (lastChecked?.let { " · last checked ${clock(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = g.muted,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (update) {
                    is AppUpdater.State.Available -> PrimaryButton(Sym.download, "Download and install", onInstall)
                    is AppUpdater.State.Failed -> PrimaryButton(Sym.refresh, "Try again", onInstall)
                    is AppUpdater.State.Downloading -> PlainButton("Cancel", onCancel, danger = true)
                    else -> {}
                }
                if (update is AppUpdater.State.UpToDate || update is AppUpdater.State.Available) {
                    PlainButton("Check again", onCheck)
                }
            }
        }
        if (update is AppUpdater.State.Downloading) DownloadProgress(update)
        if (update is AppUpdater.State.ReadyToInstall) {
            Text("Downloaded. Android asks you to confirm the install.", style = MaterialTheme.typography.bodyLarge, color = g.fg)
        }
    }
}

@Composable
private fun StatusChip(update: AppUpdater.State) {
    val g = GlassTheme.colors
    val (text, tint) = when (update) {
        AppUpdater.State.UpToDate -> "Up to date" to g.ok
        is AppUpdater.State.Available -> "New version ready" to g.on
        is AppUpdater.State.Downloading -> "Downloading" to g.on
        is AppUpdater.State.ReadyToInstall -> "Ready to install" to g.ok
        is AppUpdater.State.Failed -> "Update failed" to g.danger
    }
    Box(Modifier.background(tint.copy(alpha = 0.2f), PillShape).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (g.isDark) tint else g.fg)
    }
}

/** The download bar with its numbers: how far, how much, how fast and how long is left. */
@Composable
internal fun DownloadProgress(state: AppUpdater.State.Downloading) {
    val g = GlassTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProgressBar(state.progress, height = 16.dp)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = g.fg)
            Text(
                buildString {
                    if (state.totalBytes > 0) append("${megabytes(state.doneBytes)} of ${megabytes(state.totalBytes)} MB")
                    if (state.bytesPerSecond > 0) append(" · ${megabytes(state.bytesPerSecond)} MB/s")
                },
                style = MaterialTheme.typography.bodyLarge,
                color = g.muted,
                modifier = Modifier.weight(1f),
            )
            timeLeft(state)?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = g.muted) }
        }
    }
}

/** A rounded bar in the warm "on" colours, with a light sweeping across while it fills. */
@Composable
internal fun ProgressBar(progress: Float, height: Dp, modifier: Modifier = Modifier) {
    val g = GlassTheme.colors
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), tween(300), label = "progress")
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    val fill = onBrush()
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(PillShape)
            .background(g.fg.copy(alpha = if (g.isDark) 0.12f else 0.09f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(shown)
                .clip(PillShape)
                .background(fill)
                .drawWithContent {
                    drawContent()
                    val w = size.width * 0.35f
                    val x = size.width * sweep - w / 2
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent), startX = x, endX = x + w),
                        topLeft = Offset(x, 0f),
                        size = Size(w, size.height),
                    )
                },
        )
    }
}

/** Changes as small cards, [columns] to a row. */
@Composable
private fun ChangeGrid(changes: List<Change>, columns: Int, big: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        changes.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { ChangeCard(it, big, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ChangeCard(change: Change, big: Boolean, modifier: Modifier = Modifier) {
    val g = GlassTheme.colors
    val (icon, tint) = changeLook(change)
    Row(
        modifier
            .background(g.fg.copy(alpha = if (g.isDark) 0.06f else 0.04f), CardShape)
            .padding(horizontal = 14.dp, vertical = if (big) 16.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(icon, tint, if (big) 46.dp else 40.dp)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                change.title,
                style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = g.fg,
            )
            if (change.detail.isNotEmpty()) {
                Text(change.detail, style = MaterialTheme.typography.bodyMedium, color = g.muted)
            }
        }
    }
}

@Composable
private fun IconTile(icon: Ic, tint: Color, size: Dp) {
    Box(Modifier.size(size).background(tint.copy(alpha = 0.2f), RoundedCornerShape(size * 0.32f)), contentAlignment = Alignment.Center) {
        SymIcon(icon, filled = true, size = size * 0.52f, tint = tint)
    }
}

/** One day of earlier changes; tapped, it shows them all. */
@Composable
private fun EarlierDay(day: String, changes: List<Change>) {
    val g = GlassTheme.colors
    var open by rememberSaveable(day) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().pressable({ open = !open }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(dayLabel(day), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = g.fg, modifier = Modifier.width(110.dp))
            Text(
                changes.joinToString(" · ") { it.title },
                style = MaterialTheme.typography.bodyLarge,
                color = g.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(if (changes.size == 1) "1 change" else "${changes.size} changes", style = MaterialTheme.typography.bodyMedium, color = g.muted)
            SymIcon(if (open) Sym.keyboardArrowUp else Sym.keyboardArrowDown, size = 18.dp, tint = g.muted)
        }
        if (open) ChangeGrid(changes, columns = 2)
    }
}

/** A feature on the "What the app can do" grid. */
private data class Feature(val icon: Ic, val tint: Color, val name: String, val text: String, val status: String, val statusTint: Color?)

/** Everything the app does: the parts always there, then each module with whether it's on. */
@Composable
private fun Features(modules: Modules, devices: Map<String, Device>) {
    val g = GlassTheme.colors
    val choices by modules.choices.collectAsStateWithLifecycle()
    val apps by modules.apps.collectAsStateWithLifecycle()
    val states = remember(choices, apps, devices) { AppModules.states(choices, apps, devices.values) }
    val features = buildList {
        BUILT_IN.forEach { (icon, tint, name, text) -> add(Feature(icon, tint, name, text, "Built in", null)) }
        states.forEach { state ->
            val (icon, tint) = moduleLook(state.module.id)
            add(Feature(icon, tint, state.module.name, state.module.summary, state.status.label, statusColor(state.status)))
        }
    }
    val on = BUILT_IN.size + states.count { it.status == ModuleStatus.On }
    val ready = features.size - states.count { it.module.kind == ModuleKind.Planned }
    SettingsBox("What the app can do") {
        Text("$ready features · $on working on this tablet", style = MaterialTheme.typography.bodyMedium, color = g.muted)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            features.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { FeatureTile(it, Modifier.weight(1f)) }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun FeatureTile(feature: Feature, modifier: Modifier = Modifier) {
    val g = GlassTheme.colors
    Column(
        modifier.background(g.fg.copy(alpha = if (g.isDark) 0.06f else 0.04f), CardShape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(feature.icon, feature.tint, 38.dp)
            Spacer(Modifier.weight(1f))
            val tint = feature.statusTint ?: g.ok
            Box(Modifier.background(tint.copy(alpha = 0.18f), PillShape).padding(horizontal = 9.dp, vertical = 3.dp)) {
                Text(feature.status, style = MaterialTheme.typography.labelMedium, color = if (g.isDark) tint else g.fg, maxLines = 1)
            }
        }
        Text(feature.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = g.fg)
        Text(feature.text, style = MaterialTheme.typography.bodyMedium, color = g.muted)
    }
}

/** The parts of the app that aren't modules: they're always on. */
private val BUILT_IN = listOf(
    BuiltIn(Sym.home, Color(0xFFE9A23B), "Rooms and devices", "Every Homey zone and device, laid out by itself."),
    BuiltIn(Sym.face, Color(0xFF8B7BFF), "Voice assistant", "Nova or TARS for each person, woken by any name they chose."),
    BuiltIn(Sym.timer, Color(0xFFE08A3C), "Kitchen timers", "Several at once beside the page's chips, grouped by recipe, by voice too."),
    BuiltIn(Sym.flow, Color(0xFF1A9A52), "Routines", "God morgon and your own flows for the assistant."),
    BuiltIn(Sym.lightbulb, Color(0xFF3A8DDE), "Memory", "Habits and preferences for each person and the house."),
    BuiltIn(Sym.search, Color(0xFF8B7BFF), "Know-how", "Each question gets only the rules and tools it needs; teach it new topics."),
    BuiltIn(Sym.emojiObjects, Color(0xFFD0457A), "Moods and light looks", "Cozy and party looks for each room."),
)

private data class BuiltIn(val icon: Ic, val tint: Color, val name: String, val text: String)

/** An icon and colour for a change, guessed from the words in it. */
private fun changeLook(change: Change): Pair<Ic, Color> {
    val words = (change.title + " " + change.detail).lowercase(Locale.ROOT)
    return CHANGE_LOOKS.firstOrNull { (keys) -> keys.any { it in words } }?.let { it.second to it.third }
        ?: (Sym.autoAwesome to Color(0xFF8B7BFF))
}

private val CHANGE_LOOKS: List<Triple<List<String>, Ic, Color>> = listOf(
    Triple(listOf("timer"), Sym.timer, Color(0xFFE08A3C)),
    Triple(listOf("memory", "remember"), Sym.lightbulb, Color(0xFF3A8DDE)),
    Triple(listOf("module"), Sym.apps, Color(0xFF1A9A52)),
    Triple(listOf("phone", "find my"), Sym.phone, Color(0xFF6E7BF2)),
    Triple(listOf("fridge", "scan", "barcode"), Sym.fridge, Color(0xFF3BA3C7)),
    Triple(listOf("recipe", "cooking"), Sym.chefHat, Color(0xFFE08A3C)),
    Triple(listOf("vacuum"), Sym.vacuum, Color(0xFF8A63D2)),
    Triple(listOf("sonos", "spotify", "music", "radio"), Sym.musicNotes, Color(0xFF1DB954)),
    Triple(listOf("weather"), Sym.wxCloudSun, Color(0xFF3A8DDE)),
    Triple(listOf("calendar"), Sym.calendar, Color(0xFFE5534B)),
    Triple(listOf("who's home", "alarm", "person", "people"), Sym.people, Color(0xFF2BA79B)),
    Triple(listOf("routine", "morgon", "goodnight"), Sym.flow, Color(0xFF1A9A52)),
    Triple(listOf("privacy", "security"), Sym.shieldLock, Color(0xFF55657A)),
    Triple(listOf("voice", "nova", "tars", "wake", "speak", "say", "assistant", "ai "), Sym.waveform, Color(0xFF8B7BFF)),
    Triple(listOf("lamp", "light", "släck"), Sym.emojiObjects, Color(0xFFE9A23B)),
    Triple(listOf("update", "install"), Sym.download, Color(0xFFE9A23B)),
    Triple(listOf("setting"), Sym.settings, Color(0xFF55657A)),
)

/**
 * The sheet that opens when an update is started from the dashboard: what it brings and the
 * download bar. It can be hidden; the pill in the corner keeps showing the progress.
 */
@Composable
internal fun UpdateSheet(updater: AppUpdater, update: AppUpdater.State, onInstall: () -> Unit, onHide: () -> Unit) {
    val g = GlassTheme.colors
    val latest by updater.latest.collectAsStateWithLifecycle()
    val pending = remember(latest) { updater.pending(latest) }
    val newVersion = latest?.version?.takeIf { it.isNotEmpty() } ?: buildOf(update)?.let { "0.2.$it" } ?: "the newest version"
    GlassDialog(onDismiss = onHide, width = 640.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(17.dp)).background(onBrush()), contentAlignment = Alignment.Center) {
                SymIcon(Sym.download, filled = true, size = 30.dp, tint = g.onInk)
            }
            Column(Modifier.weight(1f)) {
                Text("Updating to $newVersion", style = MaterialTheme.typography.headlineMedium, color = g.fg)
                Text(
                    "From ${updater.currentVersion}" + if (pending.isNotEmpty()) " · ${pending.size} new ${if (pending.size == 1) "thing" else "things"}" else "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = g.muted,
                )
            }
            RoundButton(Sym.close, onHide, size = 44.dp, filledIcon = false, contentDescription = "Hide")
        }
        when (update) {
            is AppUpdater.State.Downloading -> DownloadProgress(update)
            is AppUpdater.State.ReadyToInstall -> Text("Downloaded. Opening the installer…", style = MaterialTheme.typography.bodyLarge, color = g.fg)
            is AppUpdater.State.Failed -> Text("The update didn't finish: ${update.message}", style = MaterialTheme.typography.bodyLarge, color = g.danger)
            else -> {}
        }
        if (pending.isNotEmpty()) {
            Column(
                Modifier.weight(1f, fill = false).fillMaxWidth().background(g.fg.copy(alpha = if (g.isDark) 0.06f else 0.04f), CardShape)
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("WHAT'S NEW", style = MaterialTheme.typography.labelMedium, color = g.muted)
                pending.take(6).forEach { change ->
                    val (icon, tint) = changeLook(change)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconTile(icon, tint, 32.dp)
                        Text(
                            change.title + if (change.detail.isNotEmpty()) " · " + change.detail else "",
                            style = MaterialTheme.typography.bodyLarge,
                            color = g.fg,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (pending.size > 6) Text("and ${pending.size - 6} more in Settings › App and updates", style = MaterialTheme.typography.bodyMedium, color = g.muted)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Android asks you to confirm the install when the download is done.", style = MaterialTheme.typography.bodyMedium, color = g.muted, modifier = Modifier.weight(1f))
            when (update) {
                is AppUpdater.State.Downloading -> PlainButton("Cancel", updater::cancel, danger = true)
                is AppUpdater.State.Available -> PrimaryButton(Sym.download, "Install", onInstall)
                is AppUpdater.State.Failed -> PrimaryButton(Sym.refresh, "Try again", onInstall)
                else -> {}
            }
        }
    }
}

/** Shown once after an update: what it brought. */
@Composable
internal fun WhatsNewSheet(version: String, changes: List<Change>, onAllFeatures: () -> Unit, onDismiss: () -> Unit) {
    val g = GlassTheme.colors
    GlassDialog(onDismiss = onDismiss, width = 820.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(56.dp).background(g.ok.copy(alpha = 0.2f), PillShape), contentAlignment = Alignment.Center) {
                SymIcon(Sym.check, filled = true, size = 30.dp, tint = g.ok)
            }
            Column(Modifier.weight(1f)) {
                Text("Updated to $version", style = MaterialTheme.typography.headlineMedium, color = g.fg)
                Text("Here's what's new", style = MaterialTheme.typography.bodyLarge, color = g.muted)
            }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            ChangeGrid(changes.take(8), columns = 2, big = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("All of it is in Settings › App and updates.", style = MaterialTheme.typography.bodyMedium, color = g.muted, modifier = Modifier.weight(1f))
            PlainButton("All features", onAllFeatures)
            PrimaryButton(null, "Got it", onDismiss)
        }
    }
}

@Composable
private fun PrimaryButton(icon: Ic?, text: String, onClick: () -> Unit) {
    val g = GlassTheme.colors
    Row(
        Modifier.pressable(onClick).background(onBrush(), PillShape).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let { SymIcon(it, filled = true, size = 20.dp, tint = g.onInk) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = g.onInk)
    }
}

@Composable
private fun PlainButton(text: String, onClick: () -> Unit, danger: Boolean = false) {
    val g = GlassTheme.colors
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (danger) g.danger else g.fg,
        modifier = Modifier.pressable(onClick).glass(PillShape).padding(horizontal = 18.dp, vertical = 11.dp),
    )
}

private fun buildOf(state: AppUpdater.State): Int? = when (state) {
    is AppUpdater.State.Available -> state.build
    is AppUpdater.State.Downloading -> state.build
    is AppUpdater.State.ReadyToInstall -> state.build
    is AppUpdater.State.Failed -> state.build
    AppUpdater.State.UpToDate -> null
}

private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / 1_000_000.0)

private fun timeLeft(state: AppUpdater.State.Downloading): String? {
    if (state.bytesPerSecond <= 0 || state.totalBytes <= 0) return null
    val seconds = ((state.totalBytes - state.doneBytes) / state.bytesPerSecond).coerceAtLeast(1)
    return when {
        seconds < 60 -> "About $seconds ${if (seconds == 1L) "second" else "seconds"} left"
        else -> (seconds / 60).let { "About $it ${if (it == 1L) "minute" else "minutes"} left" }
    }
}

private fun clock(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

private fun dayLabel(day: String): String {
    val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return day
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
    }
}
