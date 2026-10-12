package app.homeydash.ui.dashboard

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.homeydash.homey.HomeyInfo
import app.homeydash.ui.components.PanelShape
import app.homeydash.ui.components.PillShape
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
    onForget: () -> Unit,
    onDismiss: () -> Unit,
) {
    val g = GlassTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(460.dp)
                .background(g.wallBase, PanelShape)
                .glass()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, color = g.fg)
            Text("Appearance", style = MaterialTheme.typography.labelMedium, color = g.muted)
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
            Text("Homey", style = MaterialTheme.typography.labelMedium, color = g.muted)
            Text(
                listOfNotNull(info.homeyModelName ?: "Homey", info.homeyVersion?.let { "version $it" }).joinToString(", "),
                style = MaterialTheme.typography.bodyLarge,
                color = g.fg,
            )
            Text(
                "Devices you hide in the Homey app stay off this dashboard.",
                style = MaterialTheme.typography.bodyMedium,
                color = g.muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
    }
}
