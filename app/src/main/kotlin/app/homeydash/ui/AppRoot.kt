package app.homeydash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.homeydash.AppContainer
import app.homeydash.SessionState
import app.homeydash.ui.components.Bubble
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.Wallpaper
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.pressable
import app.homeydash.ui.dashboard.DashboardScreen
import app.homeydash.ui.setup.SetupScreen
import app.homeydash.ui.theme.GlassTheme

@Composable
fun AppRoot(container: AppContainer) {
    val session by container.session.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        // The dashboard draws its own backdrop, which can differ per room.
        if (session !is SessionState.Ready) Wallpaper()
        when (val state = session) {
            SessionState.Starting -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = GlassTheme.colors.fg)
            SessionState.NeedsSetup -> SetupScreen(onConnect = container::connect, discover = container.discovery::discover)
            is SessionState.Unreachable -> Unreachable(state, onRetry = container::retry, onForget = container::forget)
            is SessionState.Ready -> DashboardScreen(
                store = state.store,
                info = state.info,
                display = container.display,
                updater = container.updater,
                weather = container.weather,
                calendar = container.calendar,
                onForget = container::forget,
            )
        }
    }
}

@Composable
private fun Unreachable(state: SessionState.Unreachable, onRetry: () -> Unit, onForget: () -> Unit) {
    val g = GlassTheme.colors
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(48.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).glass().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Bubble(Sym.wifiOff, on = true, onColor = g.danger, size = 64.dp)
            Text("Can't reach your Homey", style = MaterialTheme.typography.headlineMedium, color = g.fg)
            Text(state.message, textAlign = TextAlign.Center, color = g.muted, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Use a different Homey",
                    style = MaterialTheme.typography.labelLarge,
                    color = g.fg,
                    modifier = Modifier.pressable(onForget).glass(PillShape).padding(horizontal = 20.dp, vertical = 14.dp),
                )
                Text(
                    "Try again",
                    style = MaterialTheme.typography.labelLarge,
                    color = g.wallBase,
                    modifier = Modifier.pressable(onRetry).background(g.fg, PillShape).padding(horizontal = 24.dp, vertical = 14.dp),
                )
            }
        }
    }
}
