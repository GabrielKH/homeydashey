package app.homeydash.ui.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.homeydash.agent.AgentSettings
import app.homeydash.agent.AgentTools
import app.homeydash.agent.HomeView
import app.homeydash.agent.Routine
import app.homeydash.assistant.AssistantServices
import app.homeydash.homey.Device
import app.homeydash.homey.Mood
import app.homeydash.homey.Zone
import app.homeydash.ui.assistant.AssistantNav
import app.homeydash.ui.assistant.AssistantPage
import app.homeydash.ui.components.AppDialog
import app.homeydash.ui.components.Compact
import app.homeydash.ui.components.Ic
import app.homeydash.ui.components.PanelShape
import app.homeydash.ui.components.RoundButton
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.pressable
import app.homeydash.ui.components.screenSizeDp
import app.homeydash.ui.theme.GlassTheme

/** A category in the settings side menu: its page, icon and name. */
private class Category(val id: String, val icon: Ic, val label: String)

private val GROUPS = listOf(
    "Dashboard" to listOf(
        Category("appearance", Sym.palette, "Appearance"),
        Category("screen", Sym.brightness, "Screen"),
        Category("homey", Sym.home, "Homey and rooms"),
        Category("modules", Sym.apps, "Modules"),
    ),
    "Assistant" to listOf(
        Category("main", Sym.face, "Assistant"),
        Category("voice", Sym.speaker, "Voice and look"),
        Category("listen", Sym.mic, "Listening"),
        Category("brain", Sym.autoAwesome, "AI and servers"),
        Category("routines", Sym.flow, "Routines"),
        Category("memory", Sym.lightbulb, "Memory"),
        Category("privacy", Sym.shieldLock, "Privacy and security"),
        Category("spotify", Sym.musicNote, "Spotify on Sonos"),
    ),
    "App" to listOf(
        Category("app", Sym.download, "App and updates"),
        Category("logs", Sym.send, "Logs for Claude"),
    ),
)

/** The dashboard's own pages; every other section is an assistant page. */
val DASHBOARD_SECTIONS = setOf("appearance", "screen", "homey", "modules", "app")

/** How big the wide settings page draws everything compared with the dashboard. */
private const val WindowScale = 0.8f

/** Screens at least this wide get settings as one page with a side menu, like a tablet's own settings. */
val WIDE_SETTINGS = 840.dp

/**
 * All settings on one wide page: the categories in a side menu like the dashboard's, which
 * shrinks to icons, and the chosen page beside it. The dashboard's pages come from [dashboard];
 * the assistant's are the same pages its own popup shows.
 */
@Composable
fun SettingsWindow(
    section: String,
    onSection: (String) -> Unit,
    compact: Boolean,
    onCompact: (Boolean) -> Unit,
    dashboard: @Composable (String) -> Unit,
    services: AssistantServices,
    settings: AgentSettings,
    zones: Map<String, Zone>,
    devices: Map<String, Device>,
    hidden: Set<String>,
    preview: (local: Boolean) -> Pair<HomeView, AgentTools>,
    onFaces: () -> Unit,
    moods: Map<String, Mood>,
    flows: suspend () -> List<String>,
    onRunRoutine: (Routine) -> Unit,
    onDismiss: () -> Unit,
) {
    val g = GlassTheme.colors
    val nav = remember { AssistantNav(if (section in DASHBOARD_SECTIONS) "main" else section) }
    val onDashboard = section in DASHBOARD_SECTIONS
    // The assistant's pages open their own sub-pages (a routine, the phones); the menu follows.
    LaunchedEffect(nav.page) { if (!onDashboard && nav.page != section) onSection(nav.page) }
    val parent = if (onDashboard) null else AssistantNav.parent(section)
    val back: () -> Unit = { if (parent != null) nav.page = parent else onDismiss() }
    val pick: (String) -> Unit = { id ->
        if (id !in DASHBOARD_SECTIONS) nav.page = id
        onSection(id)
    }
    // A little smaller than the popups, with more room between things.
    AppDialog(onDismiss) { Compact(WindowScale) {
        BackHandler(onBack = back)
        val screen = screenSizeDp()
        Row(
            // The whole screen, with the same margin as the dashboard around it.
            Modifier.width(screen.width - 32.dp).height(screen.height - 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Menu(selected = parent ?: section, compact = compact, onCompact = onCompact, onPick = pick)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(g.wallBase, PanelShape)
                    .glass()
                    .padding(vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (parent != null) RoundButton(Sym.arrowBack, back, size = 44.dp, filledIcon = false)
                    Text(
                        if (onDashboard) GROUPS.flatMap { it.second }.first { it.id == section }.label else nav.title(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = g.fg,
                        modifier = Modifier.weight(1f),
                    )
                    if (section == "main") {
                        Toggle(settings.enabled, onClick = { services.settingsStore.update { it.copy(enabled = !it.enabled) } })
                    }
                    RoundButton(Sym.close, onDismiss, size = 44.dp, filledIcon = false)
                }
                // Each page starts at its top; only the content scrolls.
                key(section) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (onDashboard) {
                            dashboard(section)
                        } else {
                            AssistantPage(nav, services, settings, zones, devices, hidden, preview, onFaces, moods, flows, onRunRoutine, menu = false)
                        }
                    }
                }
            }
        }
    } }
}

/** The categories, drawn like the dashboard's side menu: plain icons, the chosen one on glass. */
@Composable
private fun Menu(selected: String, compact: Boolean, onCompact: (Boolean) -> Unit, onPick: (String) -> Unit) {
    val g = GlassTheme.colors
    val width by animateDpAsState(if (compact) 84.dp else 248.dp, label = "settingsMenu")
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(g.wallBase, PanelShape)
            .glass()
            .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = 18.dp),
        horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        if (compact) {
            Box(Modifier.padding(bottom = 16.dp).size(48.dp).pressable({ onCompact(false) }).glass(CircleShape), contentAlignment = Alignment.Center) {
                SymIcon(Sym.settings, filled = true, size = 24.dp)
            }
        } else {
            Row(Modifier.padding(start = 10.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                SymIcon(Sym.settings, filled = true, size = 28.dp)
                Text(
                    "Settings",
                    style = MaterialTheme.typography.titleLarge,
                    color = g.fg,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
                RoundButton(Sym.keyboardArrowLeft, { onCompact(true) }, size = 36.dp, filledIcon = false, contentDescription = "Shrink menu")
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            // Each group in a box of its own, its name above it.
            GROUPS.forEachIndexed { i, (name, categories) ->
                if (!compact) {
                    Text(
                        name.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = g.muted,
                        modifier = Modifier.padding(start = 12.dp, top = if (i == 0) 0.dp else 20.dp, bottom = 8.dp),
                    )
                }
                val box = RoundedCornerShape(20.dp)
                Column(
                    Modifier
                        .padding(top = if (compact && i > 0) 12.dp else 0.dp)
                        .then(if (compact) Modifier else Modifier.fillMaxWidth())
                        .background(g.glassBottom, box)
                        .border(1.dp, g.glassLine, box)
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
                ) {
                    categories.forEach { c -> MenuItem(c, c.id == selected, compact) { onPick(c.id) } }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(category: Category, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    val g = GlassTheme.colors
    if (compact) {
        Box(
            Modifier
                .size(52.dp)
                .pressable(onClick)
                .then(if (selected) Modifier.glass(RoundedCornerShape(16.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            SymIcon(category.icon, filled = selected, size = 24.dp, tint = if (selected) g.fg else g.muted)
        }
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick)
            .then(if (selected) Modifier.glass(RoundedCornerShape(16.dp)) else Modifier)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SymIcon(category.icon, filled = selected, size = 23.dp, tint = if (selected) g.fg else g.muted)
        Text(
            category.label,
            style = MaterialTheme.typography.labelLarge,
            color = g.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
    }
}

/**
 * A titled group of settings in a glass box of its own, like a tablet's settings: the name
 * above, the rows inside with room between them.
 */
@Composable
fun SettingsBox(title: String?, content: @Composable ColumnScope.() -> Unit) {
    val g = GlassTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = g.muted, modifier = Modifier.padding(start = 14.dp, top = 4.dp))
        }
        Column(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}
