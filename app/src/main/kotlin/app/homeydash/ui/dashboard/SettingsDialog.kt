package app.homeydash.ui.dashboard

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import app.homeydash.ui.components.dialogSize
import app.homeydash.ui.theme.Palette
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.homeydash.ui.components.AppDialog
import app.homeydash.ui.components.Compact
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.homeydash.ui.components.Bubble
import app.homeydash.ui.theme.Accent
import app.homeydash.data.AppUpdater
import app.homeydash.homey.HomeyInfo
import app.homeydash.ui.components.PanelShape
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.Ic
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme
import app.homeydash.ui.theme.ThemeMode

@Composable
fun SettingsDialog(
    info: HomeyInfo,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    accent: Accent,
    onAccent: (Accent) -> Unit,
    accentOpacity: Float,
    onAccentOpacity: (Float) -> Unit,
    onHomeBackground: () -> Unit,
    onChooseDevices: () -> Unit,
    onChooseRooms: () -> Unit,
    onWeather: () -> Unit,
    onCalendars: () -> Unit,
    /** 0 for automatic. */
    uiScale: Float,
    onUiScale: (Float) -> Unit,
    palette: Palette,
    onPalette: (Palette) -> Unit,
    flat: Boolean,
    onFlat: (Boolean) -> Unit,
    dimAfter: Int,
    onDimAfter: (Int) -> Unit,
    wakeOnProximity: Boolean,
    onWakeOnProximity: (Boolean) -> Unit,
    kiosk: Boolean,
    onKiosk: (Boolean) -> Unit,
    updater: AppUpdater,
    update: AppUpdater.State,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onForget: () -> Unit,
    onAssistant: () -> Unit,
    onDismiss: () -> Unit,
) {
    val g = GlassTheme.colors
    AppDialog(onDismiss) { Compact {
        Column(
            Modifier
                .dialogSize(560.dp, tall = true)
                .background(g.wallBase, PanelShape)
                .glass()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, color = g.fg)
            AppearanceSettings(
                themeMode, onThemeMode, palette, onPalette, flat, onFlat, uiScale, onUiScale,
                accent, onAccent, accentOpacity, onAccentOpacity, onHomeBackground,
            )
            ScreenSettings(dimAfter, onDimAfter, wakeOnProximity, onWakeOnProximity, kiosk, onKiosk)
            HomeySettings(info, onChooseDevices, onChooseRooms, onWeather, onCalendars)
            Text("Assistant", style = MaterialTheme.typography.labelMedium, color = g.muted)
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable(onAssistant)
                    .glass(PillShape)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SymIcon(Sym.autoAwesome, size = 20.dp)
                Text("Voice assistant and security", style = MaterialTheme.typography.labelLarge, color = g.fg, modifier = Modifier.weight(1f).padding(start = 10.dp))
                SymIcon(Sym.chevronRight, size = 20.dp, tint = g.muted)
            }
            AppSettings(updater, update, onCheckUpdate, onInstallUpdate)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ForgetButton(onForget)
                Row(
                    Modifier
                        .pressable(onDismiss)
                        .background(g.fg, PillShape)
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                ) {
                    Text("Done", style = MaterialTheme.typography.labelLarge, color = g.wallBase)
                }
            }
        }
    } }
}

/** Light or dark, the wall colours, glass or flat, the display size and the highlight colour. */
@Composable
internal fun AppearanceSettings(
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    palette: Palette,
    onPalette: (Palette) -> Unit,
    flat: Boolean,
    onFlat: (Boolean) -> Unit,
    uiScale: Float,
    onUiScale: (Float) -> Unit,
    accent: Accent,
    onAccent: (Accent) -> Unit,
    accentOpacity: Float,
    onAccentOpacity: (Float) -> Unit,
    onHomeBackground: () -> Unit,
) {
    val g = GlassTheme.colors
    SettingsBox("Look") {
        Row(
            Modifier.fillMaxWidth().glass(PillShape).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(
                Triple(ThemeMode.System, Sym.contrast, "Auto"),
                Triple(ThemeMode.Light, Sym.lightMode, "Light"),
                Triple(ThemeMode.Dark, Sym.darkMode, "Dark"),
            ).forEach { (mode, icon, label) ->
                val selected = mode == themeMode
                Row(
                    Modifier
                        .weight(1f)
                        .pressable({ onThemeMode(mode) })
                        .then(if (selected) Modifier.background(g.fg, PillShape) else Modifier)
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SymIcon(icon, filled = selected, size = 20.dp, tint = if (selected) g.wallBase else g.fg)
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) g.wallBase else g.fg,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        Text("Theme", style = MaterialTheme.typography.labelLarge, color = g.fg)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Palette.entries.forEach { option ->
                val selected = option == palette
                val walls = if (g.isDark) option.dark else option.light
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .pressable({ onPalette(option) })
                            .border(if (selected) 3.dp else 1.dp, if (selected) g.fg else g.glassLine, CircleShape)
                            .padding(if (selected) 5.dp else 1.dp)
                            .background(Brush.linearGradient(listOf(walls[1], walls[2], walls[3]), Offset.Zero, Offset.Infinite), CircleShape),
                    )
                    Text(option.label, style = MaterialTheme.typography.bodyMedium, color = if (selected) g.fg else g.muted)
                }
            }
        }
        Text("Style", style = MaterialTheme.typography.labelLarge, color = g.fg)
        Row(
            Modifier.fillMaxWidth().glass(PillShape).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(false to "Glass and gradients", true to "Flat colours").forEach { (value, label) ->
                val selected = value == flat
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) g.wallBase else g.fg,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .pressable({ onFlat(value) })
                        .then(if (selected) Modifier.background(g.fg, PillShape) else Modifier)
                        .padding(vertical = 12.dp),
                )
            }
        }
        Text("Display size", style = MaterialTheme.typography.labelLarge, color = g.fg)
        Row(
            Modifier.fillMaxWidth().glass(PillShape).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(0f to "Fit screen", 0.8f to "80%", 0.9f to "90%", 1f to "100%", 1.1f to "110%").forEach { (scale, label) ->
                val selected = kotlin.math.abs(scale - uiScale) < 0.01f
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) g.wallBase else g.fg,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .pressable({ onUiScale(scale) })
                        .then(if (selected) Modifier.background(g.fg, PillShape) else Modifier)
                        .padding(vertical = 12.dp),
                )
            }
        }
        Text(
            "Fit screen sizes cards and text to this tablet's screen. Press and hold a card to hide it, move it or put it in a group.",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
    }
    SettingsBox("Highlight") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Accent.entries.forEach { option ->
                val selected = option == accent
                Box(
                    Modifier
                        .size(48.dp)
                        .pressable({ onAccent(option) })
                        .border(if (selected) 3.dp else 0.dp, if (selected) g.fg else Color.Transparent, CircleShape)
                        .padding(if (selected) 5.dp else 0.dp)
                        .background(Brush.linearGradient(listOf(option.top, option.deep), Offset.Zero, Offset.Infinite), CircleShape),
                )
            }
        }
        Text(accent.label, style = MaterialTheme.typography.bodyMedium, color = g.muted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Opacity", style = MaterialTheme.typography.labelLarge, color = g.fg)
            GlassSlider(
                value = ((accentOpacity - 0.3f) / 0.7f).coerceIn(0f, 1f),
                onValue = { onAccentOpacity(0.3f + it * 0.7f) },
                modifier = Modifier.weight(1f),
            )
            Text(percent(accentOpacity), style = MaterialTheme.typography.labelLarge, color = g.fg)
        }
        // A preview of the highlight as it looks on lights and moods.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Bubble(Sym.lightbulb, on = true)
            Bubble(Sym.autoAwesome, on = true)
            Text("Lights that are on and the active mood use this.", style = MaterialTheme.typography.bodyMedium, color = g.muted)
        }
    }
    SettingsBox("Background") {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable(onHomeBackground)
                .glass(PillShape)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SymIcon(Sym.image, size = 20.dp)
            Text("Home background", style = MaterialTheme.typography.labelLarge, color = g.fg, modifier = Modifier.weight(1f).padding(start = 10.dp))
            SymIcon(Sym.chevronRight, size = 20.dp, tint = g.muted)
        }
        Text(
            "Each room can have its own background: open the room and tap the picture button.",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
    }
}

/** Which Homey this is, and the pages for devices, rooms, weather and calendars. */
@Composable
internal fun HomeySettings(
    info: HomeyInfo,
    onChooseDevices: () -> Unit,
    onChooseRooms: () -> Unit,
    onWeather: () -> Unit,
    onCalendars: () -> Unit,
) {
    val g = GlassTheme.colors
    SettingsBox("Homey") {
        Text(
            listOfNotNull(info.homeyModelName ?: "Homey", info.homeyVersion?.let { "version $it" }).joinToString(", "),
            style = MaterialTheme.typography.bodyLarge,
            color = g.fg,
        )
        Text(
            "Devices you hide in the Homey app stay off this dashboard. You can also hide devices on this tablet only.",
            style = MaterialTheme.typography.bodyMedium,
            color = g.muted,
        )
        // Two to a row, so the list of pages takes half the height.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NavPill(Sym.visibilityOff, "Devices to show", onChooseDevices, Modifier.weight(1f))
            NavPill(Sym.sliders, "Hide and sort rooms", onChooseRooms, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NavPill(Sym.mapPin, "Weather location", onWeather, Modifier.weight(1f))
            NavPill(Sym.calendar, "Calendars", onCalendars, Modifier.weight(1f))
        }
    }
}

@Composable
internal fun ForgetButton(onForget: () -> Unit) {
    val g = GlassTheme.colors
        Row(
            Modifier
                .pressable(onForget)
                .glass(PillShape)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SymIcon(Sym.logout, size = 20.dp, tint = g.danger)
            Text("Use a different Homey", style = MaterialTheme.typography.labelLarge, color = g.danger, modifier = Modifier.padding(start = 8.dp))
        }
}
/** A page to open, as a glass pill with its icon and a chevron; sized by [modifier]. */
@Composable
private fun NavPill(icon: app.homeydash.ui.components.Ic, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val g = GlassTheme.colors
    Row(
        modifier.pressable(onClick).glass(PillShape).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SymIcon(icon, size = 20.dp)
        Text(text, style = MaterialTheme.typography.labelLarge, color = g.fg, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 10.dp))
        SymIcon(Sym.chevronRight, size = 20.dp, tint = g.muted)
    }
}

/** Dimming when nobody is around, waking on the proximity sensor, and kiosk mode. */
@Composable
internal fun ScreenSettings(
    dimAfter: Int,
    onDimAfter: (Int) -> Unit,
    wakeOnProximity: Boolean,
    onWakeOnProximity: (Boolean) -> Unit,
    kiosk: Boolean,
    onKiosk: (Boolean) -> Unit,
) {
    val g = GlassTheme.colors
    val context = LocalContext.current
    val hasProximity = remember(context) {
        (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null
    }
    SettingsBox("Screen") {
        Text("Dim the screen after", style = MaterialTheme.typography.labelLarge, color = g.fg)
        Row(
            Modifier.fillMaxWidth().glass(PillShape).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(0 to "Never", 1 to "1 min", 5 to "5 min", 15 to "15 min", 30 to "30 min").forEach { (minutes, label) ->
                val selected = minutes == dimAfter
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) g.wallBase else g.fg,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .pressable({ onDimAfter(minutes) })
                        .then(if (selected) Modifier.background(g.fg, PillShape) else Modifier)
                        .padding(vertical = 12.dp),
                )
            }
        }
        SettingSwitch(
            title = "Wake when someone comes close",
            detail = if (hasProximity) "Uses the tablet's proximity sensor. A touch always wakes the screen." else "This tablet has no proximity sensor, so a touch wakes the screen.",
            on = wakeOnProximity && hasProximity,
            enabled = hasProximity && dimAfter > 0,
            onChange = onWakeOnProximity,
        )
    }
    SettingsBox("Kiosk") {
        SettingSwitch(
            title = "Kiosk mode",
            detail = "Pins the dashboard so Home and Back can't leave it (Android asks once). To leave, turn this off or hold Back and Overview.",
            on = kiosk,
            onChange = onKiosk,
        )
        if (kiosk) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable({ runCatching { context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })
                    .glass(PillShape)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SymIcon(Sym.home, size = 20.dp)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("Make it the home app", style = MaterialTheme.typography.labelLarge, color = g.fg)
                    Text("Starts the dashboard after a restart and on the Home button.", style = MaterialTheme.typography.bodyMedium, color = g.muted)
                }
                SymIcon(Sym.chevronRight, size = 20.dp, tint = g.muted)
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, detail: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val g = GlassTheme.colors
    Row(
        Modifier.fillMaxWidth().graphicsLayer { alpha = if (enabled) 1f else 0.5f },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = g.fg)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = g.muted)
        }
        Toggle(on, onClick = { onChange(!on) }.takeIf { enabled })
    }
}
