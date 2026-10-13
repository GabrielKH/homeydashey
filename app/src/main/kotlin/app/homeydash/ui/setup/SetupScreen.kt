package app.homeydash.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import app.homeydash.data.FoundHomey
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.onBrush
import app.homeydash.ui.components.pressable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import app.homeydash.ui.theme.GlassTheme
import kotlinx.coroutines.launch

/** First launch: ask for the Homey's address and a local API key, once. */
@Composable
fun SetupScreen(
    onConnect: suspend (address: String, apiKey: String) -> Unit,
    discover: () -> Flow<FoundHomey>,
) {
    var address by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val g = GlassTheme.colors
    val found = remember { mutableStateListOf<FoundHomey>() }
    var searching by remember { mutableStateOf(true) }
    var searchRound by remember { mutableIntStateOf(0) }
    LaunchedEffect(searchRound) {
        searching = true
        // The subnet scan takes a few seconds; mDNS answers keep coming while the screen is open.
        launch { delay(12_000); searching = false }
        discover().collect { homey ->
            if (found.none { it.homeyId == homey.homeyId }) found += homey
            if (address.isBlank() && found.size == 1) address = homey.address
        }
    }

    fun submit() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                onConnect(address, apiKey)
            } catch (e: Exception) {
                error = e.message ?: "Couldn't connect."
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = 560.dp).glass().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Connect to your Homey", style = MaterialTheme.typography.headlineMedium, color = g.fg)
            Text(
                "In the Homey web app, open Settings → API Keys and create a key that can view zones and " +
                    "devices, control devices, view and set moods, and view and run Flows (for the Apple TV remote and Sonos music). Homeys on this Wi-Fi show up " +
                    "below; otherwise the address is in Settings → General, for example 192.168.1.20.",
                color = g.muted,
                style = MaterialTheme.typography.bodyLarge,
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Homeys on this Wi-Fi", style = MaterialTheme.typography.labelLarge, color = g.fg, modifier = Modifier.weight(1f))
                    if (searching) {
                        CircularProgressIndicator(color = g.fg, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Text(
                            "Search again",
                            style = MaterialTheme.typography.labelLarge,
                            color = g.fg,
                            modifier = Modifier.pressable({ found.clear(); searchRound++ }),
                        )
                    }
                }
                if (found.isEmpty() && !searching) {
                    Text("None found. Type the address below instead.", style = MaterialTheme.typography.bodyMedium, color = g.muted)
                }
                found.forEach { homey ->
                    val selected = address.trim() == homey.address
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .pressable({ address = homey.address })
                            .then(if (selected) Modifier.background(onBrush(), PillShape) else Modifier.glass(PillShape))
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val ink = if (selected) g.onInk else g.fg
                        SymIcon(Sym.home, filled = selected, size = 20.dp, tint = ink)
                        Text("Homey at ${homey.address}", style = MaterialTheme.typography.labelLarge, color = ink, modifier = Modifier.weight(1f))
                        if (selected) SymIcon(Sym.check, filled = true, size = 18.dp, tint = ink)
                    }
                }
            }
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Homey address") },
                placeholder = { Text("192.168.1.20") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = g.danger) }
            Button(onClick = { submit() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                if (busy) CircularProgressIndicator(color = g.onInk, strokeWidth = 2.dp) else Text("Connect")
            }
        }
    }
}
