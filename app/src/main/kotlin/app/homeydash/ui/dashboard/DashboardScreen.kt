package app.homeydash.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.homeydash.homey.ConnectionState
import app.homeydash.homey.DashboardLayout
import app.homeydash.homey.HomeyInfo
import app.homeydash.homey.HomeyStore
import app.homeydash.homey.ZoneSection
import app.homeydash.ui.theme.DashboardTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/** Callback cards use to change a device: (deviceId, capabilityId, value). */
typealias SetCapability = (String, String, JsonElement) -> Unit

@Composable
fun DashboardScreen(store: HomeyStore, info: HomeyInfo, onForget: () -> Unit) {
    val snapshot by store.snapshot.collectAsStateWithLifecycle()
    val connection by store.connection.collectAsStateWithLifecycle()
    val sections = remember(snapshot.zones, snapshot.devices) {
        DashboardLayout.sections(snapshot.zones, snapshot.devices)
    }
    val scope = rememberCoroutineScope()
    val setCapability: SetCapability = remember(store) {
        { deviceId, capabilityId, value -> scope.launch { store.setCapability(deviceId, capabilityId, value) } }
    }
    val gridState = rememberLazyGridState()
    // Index of each zone's header in the grid, for jumping from the rail.
    val headerIndex = remember(sections) {
        var index = 0
        sections.associate { section -> (section.zone.id to index).also { index += 1 + section.devices.size } }
    }

    Row(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        ZoneRail(
            title = info.homeyModelName ?: "Homey",
            sections = sections,
            connection = connection,
            onZone = { zoneId -> headerIndex[zoneId]?.let { scope.launch { gridState.animateScrollToItem(it) } } },
            onForget = onForget,
        )
        Box(Modifier.fillMaxSize()) {
            when {
                !snapshot.loaded && snapshot.error == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 230.dp),
                    contentPadding = PaddingValues(start = 8.dp, end = 24.dp, top = 24.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    sections.forEach { section ->
                        item(key = "zone:${section.zone.id}", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                            Text(
                                section.zone.name,
                                style = MaterialTheme.typography.titleLarge,
                                color = DashboardTheme.style.onCard,
                                modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                            )
                        }
                        items(section.devices, key = { it.id }, contentType = { DashboardLayout.cardKind(it) }) { device ->
                            DeviceCard(device, setCapability)
                        }
                    }
                }
            }
            snapshot.error?.let { message ->
                ErrorBanner(message, onDismiss = store::clearError, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun ZoneRail(
    title: String,
    sections: List<ZoneSection>,
    connection: ConnectionState,
    onZone: (String) -> Unit,
    onForget: () -> Unit,
) {
    val style = DashboardTheme.style
    Column(
        Modifier
            .width(240.dp)
            .fillMaxHeight()
            .padding(start = 24.dp, top = 24.dp, bottom = 16.dp, end = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = style.onCard)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)) {
            val live = connection == ConnectionState.Connected
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (live) style.online else style.offline),
            )
            Text(
                when (connection) {
                    ConnectionState.Connected -> "Live"
                    ConnectionState.Connecting -> "Connecting…"
                    ConnectionState.Disconnected -> "Reconnecting…"
                },
                color = style.onCardMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            items(sections, key = { it.zone.id }) { section ->
                Text(
                    section.zone.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = style.onCard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onZone(section.zone.id) }
                        .padding(start = 12.dp + 12.dp * (section.depth - 1).coerceAtLeast(0), top = 12.dp, bottom = 12.dp, end = 12.dp),
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        TextButton(onClick = onForget) { Text("Change Homey") }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val style = DashboardTheme.style
    Row(
        modifier
            .padding(24.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(style.cardColor)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = style.offline, modifier = Modifier.weight(1f, fill = false))
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}
