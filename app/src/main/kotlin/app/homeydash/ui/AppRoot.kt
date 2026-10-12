package app.homeydash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.homeydash.AppContainer
import app.homeydash.SessionState
import app.homeydash.ui.dashboard.DashboardScreen
import app.homeydash.ui.setup.SetupScreen
import app.homeydash.ui.theme.DashboardTheme

@Composable
fun AppRoot(container: AppContainer) {
    val session by container.session.collectAsStateWithLifecycle()
    val style = DashboardTheme.style

    Box(
        Modifier
            .fillMaxSize()
            .background(style.background),
    ) {
        when (val state = session) {
            SessionState.Starting -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            SessionState.NeedsSetup -> SetupScreen(onConnect = container::connect)
            is SessionState.Unreachable -> Unreachable(state, onRetry = container::retry, onForget = container::forget)
            is SessionState.Ready -> DashboardScreen(state.store, state.info, onForget = container::forget)
        }
    }
}

@Composable
private fun Unreachable(state: SessionState.Unreachable, onRetry: () -> Unit, onForget: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Can't reach your Homey", style = MaterialTheme.typography.headlineMedium, color = DashboardTheme.style.onCard)
        Text(state.message, textAlign = TextAlign.Center, color = DashboardTheme.style.onCardMuted)
        Button(onClick = onRetry) { Text("Try again") }
        TextButton(onClick = onForget) { Text("Use a different Homey") }
    }
}
