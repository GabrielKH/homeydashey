package app.homeydash.ui.dashboard

import app.homeydash.agent.AgentEvent
import app.homeydash.assistant.Assistant
import app.homeydash.assistant.DashboardBridge
import app.homeydash.assistant.Scene
import app.homeydash.assistant.SceneKind
import app.homeydash.data.CalendarRepository
import app.homeydash.data.DisplaySettings
import app.homeydash.data.WeatherRepository
import app.homeydash.homey.CardKind
import app.homeydash.homey.DashboardLayout
import app.homeydash.homey.Goodnight
import app.homeydash.homey.HomeyStore
import app.homeydash.homey.WeatherKind
import app.homeydash.ui.theme.ThemeMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Lets the assistant drive the dashboard: pages, device panels, Goodnight, weather, calendar, timers, theme. */
class AssistantBridge(
    private val assistant: Assistant,
    private val store: HomeyStore,
    private val actions: DashboardActions,
    private val display: DisplaySettings,
    private val weather: WeatherRepository,
    private val calendar: CalendarRepository,
    private val hiddenDevices: () -> Set<String>,
    private val navigate: (String) -> Unit,
) : DashboardBridge {

    override fun hidden(): Set<String> = hiddenDevices()

    override fun openPage(page: String, roomId: String?): Boolean {
        navigate(if (page == "room" && roomId != null) "room:$roomId" else page)
        // The page is what was asked for: the face makes room for it.
        assistant.compact = true
        return true
    }

    override fun openDevice(deviceId: String): Boolean {
        val device = store.snapshot.value.devices[deviceId] ?: return false
        assistant.compact = true
        when {
            actions.hasRemote(device) -> actions.open(Sheet.Remote(device.id))
            DashboardLayout.cardKind(device) == CardKind.Light -> actions.open(Sheet.Light(device.id))
            DashboardLayout.cardKind(device) == CardKind.Media -> actions.open(Sheet.Music(device.zone, device.id))
            device.zone != null -> navigate("room:${device.zone}")
            else -> return false
        }
        assistant.open = false
        return true
    }

    private fun steps() = Goodnight.steps(DashboardLayout.visible(store.snapshot.value.devices, hidden()).values, display.goodnightKeep.value)

    override fun goodnightPreview(): String {
        val steps = steps()
        val flow = display.goodnightFlow.value
        val off = steps.count { it.capability == "onoff" }
        val paused = steps.count { it.capability == "speaker_playing" }
        return listOfNotNull(
            "turn off $off device${if (off == 1) "" else "s"}".takeIf { off > 0 },
            "pause $paused player${if (paused == 1) "" else "s"}".takeIf { paused > 0 },
            flow?.let { "start the Flow ${it.name}" },
            "mark everyone home as asleep in Homey".takeIf { display.goodnightAsleep.value && store.household.state.value.people.any { it.present == true } },
        ).joinToString(", ").ifBlank { "nothing is on" } + "."
    }

    override fun goodnight() = actions.goodnight(steps(), display.goodnightFlow.value, markAsleep = display.goodnightAsleep.value)

    override suspend fun weather(): String? {
        return weatherNow()
    }

    override fun weatherScene(tomorrow: Boolean): Scene? {
        val f = weather.state.value.forecast ?: return null
        val c = f.current
        return Scene(sceneOf(if (tomorrow) f.days.getOrNull(1)?.kind ?: c.kind else c.kind, tomorrow || c.isDay))
    }

    override fun weatherNow(): String? {
        val f = weather.state.value.forecast ?: return null
        val c = f.current
        val now = "${weather.state.value.placeName ?: "Here"}: ${c.kind.label}, ${c.temperature.toInt()} °C" +
            (c.feelsLike?.let { ", feels like ${it.toInt()} °C" } ?: "") + (c.wind?.let { ", wind ${it.toInt()} m/s" } ?: "")
        val today = LocalDate.now()
        val days = f.days.take(4).joinToString("\n") { d ->
            "${app.homeydash.agent.SpokenDates.day(d.date, today)}: ${d.kind.label}, ${d.min.toInt()} to ${d.max.toInt()} °C" + (d.rainChance?.let { ", rain $it%" } ?: "")
        }
        return "$now\n$days"
    }

    override suspend fun calendar(from: LocalDate, days: Int, withLocations: Boolean): List<AgentEvent> {
        if (!calendar.hasPermission.value) return emptyList()
        // All-day events are stored at midnight UTC, timed ones in local time.
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        return calendar.events(from, from.plusDays(days.toLong())).map { e ->
            val at = if (e.allDay) ZoneOffset.UTC else zone
            val start = Instant.ofEpochMilli(e.begin).atZone(at).toLocalDateTime()
            val end = Instant.ofEpochMilli(e.end).atZone(at).toLocalDateTime()
            val (begins, ends) = app.homeydash.agent.SpokenDates.event(start, end, e.allDay, today)
            AgentEvent(e.title, begins, ends, e.allDay, e.location.takeIf { withLocations })
        }
    }

    override fun startTimer(seconds: Int, label: String?) = assistant.startTimer(seconds, label)

    override fun cancelTimer(label: String?) = assistant.cancelTimer(label)

    override fun timers(): List<String> = assistant.describeTimers()

    override fun setTheme(mode: String): Boolean {
        display.setThemeMode(
            when (mode) {
                "light" -> ThemeMode.Light
                "dark" -> ThemeMode.Dark
                "auto", "system" -> ThemeMode.System
                else -> return false
            },
        )
        return true
    }
}

private fun sceneOf(kind: WeatherKind, day: Boolean): SceneKind = when (kind) {
    WeatherKind.Clear -> if (day) SceneKind.Sun else SceneKind.Night
    WeatherKind.PartlyCloudy, WeatherKind.Cloudy -> SceneKind.Clouds
    WeatherKind.Fog -> SceneKind.Fog
    WeatherKind.Drizzle, WeatherKind.Rain -> SceneKind.Rain
    WeatherKind.Snow -> SceneKind.Snow
    WeatherKind.Storm -> SceneKind.Storm
}
