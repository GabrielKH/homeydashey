package app.homeydash.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZonedDateTime

class TimersTest {
    private var now = 1_000_000L
    private fun board(file: File? = null) = TimerBoard(file) { now }

    @Test
    fun findsTimersByLooseNames() {
        val b = board()
        b.start(540, "Pasta")
        b.start(1200, "Ugn")
        b.start(300, "Steg 2 · 5 minuter", group = "Kanelbullar")
        assertEquals("Pasta", b.find("pastan")?.label)
        assertEquals("Ugn", b.find("ugnen")?.label)
        assertEquals("Steg 2 · 5 minuter", b.find("kanelbullarna")?.label)
        assertEquals("Ugn", b.find("timern för ugnen")?.label)
        assertNull(b.find("tvätten"))
        // Without a name: the newest.
        assertEquals("Steg 2 · 5 minuter", b.find(null)?.label)
    }

    @Test
    fun ringsOnceAndStopsRingingWhenSnoozed() {
        val b = board()
        val t = b.start(60, "Ägg")
        now += 59_000
        assertTrue(b.due().isEmpty())
        now += 1_000
        assertEquals(listOf(t.id), b.due().map { it.id })
        assertTrue(b.due().isEmpty())
        assertTrue(b.timers.value.single().ringing)
        // Without a name, the ringing one is meant.
        b.start(600, "Pasta")
        assertEquals("Ägg", b.find(null)?.label)
        // "Fem minuter till" on a ringing timer starts it again with five minutes.
        val snoozed = b.add("ägget", 300)!!
        assertFalse(snoozed.ringing)
        assertEquals(300_000L, snoozed.leftMs(now))
    }

    @Test
    fun ringingGivesUpAfterFiveMinutes() {
        val b = board()
        b.start(10, null)
        now += 10_000
        b.due()
        now += TimerBoard.RING_FOR_MS
        assertEquals(1, b.expired().size)
        assertTrue(b.timers.value.isEmpty())
    }

    @Test
    fun pausesAddsAndRenames() {
        val b = board()
        b.start(600, null)
        now += 100_000
        val paused = b.pause(null, true)!!
        now += 60_000
        assertEquals(500_000L, paused.leftMs(now))
        assertTrue(b.due().isEmpty())
        val resumed = b.pause(null, false)!!
        assertEquals(500_000L, resumed.leftMs(now))
        assertEquals(620_000L, b.add(null, 120)!!.leftMs(now))
        // Taking away more than is left leaves a second, not a timer in the past.
        assertEquals(1_000L, b.add(null, -3600)!!.leftMs(now))
        assertEquals("Pasta", b.rename(null, "pasta")!!.label)
    }

    @Test
    fun cancelsOneAGroupOrAll() {
        val b = board()
        b.start(60, "Pasta")
        b.start(60, "Steg 1", group = "Kanelbullar")
        b.start(60, "Steg 3", group = "Kanelbullar")
        assertEquals("Pasta", b.cancel("pastan")?.label)
        assertEquals(2, b.cancelAll("kanelbullar"))
        b.start(60, "Ugn")
        assertEquals(1, b.cancelAll(null))
        assertTrue(b.timers.value.isEmpty())
    }

    @Test
    fun ordersRingingThenSoonestAndGroupsByRecipe() {
        val b = board()
        b.start(900, "Tvätt")
        b.start(300, "Steg 1", group = "Bullar")
        b.start(120, "Pasta")
        b.start(5, "Ägg")
        now += 5_000
        b.due()
        assertEquals(listOf("Ägg", "Pasta", "Steg 1", "Tvätt"), b.ordered().map { it.label })
        val groups = Timers.groups(b.timers.value, now)
        assertEquals(listOf("Bullar", null), groups.map { it.name })
        assertEquals(listOf("Ägg", "Pasta", "Tvätt"), groups.last().timers.map { it.label })
        assertTrue(b.describe().first().startsWith("Ägg: ringing now"))
    }

    @Test
    fun keepsTimersThroughARestart() = runBlocking {
        val file = File(Files.createTempDirectory("timers").toFile(), "timers.json")
        val b = board(file)
        b.start(600, "Pasta")
        b.start(30, "Gammal")
        now += 60_000 + TimerBoard.STALE_MS
        val again = board(file)
        again.load()
        // The one that ran out long ago while the app was closed is gone; the one that just did stays to ring.
        assertEquals(listOf("Pasta"), again.timers.value.map { it.label })
        now -= TimerBoard.STALE_MS
        val third = board(file)
        third.load()
        assertEquals(listOf("Pasta", "Gammal"), third.timers.value.map { it.label })
        assertEquals(540_000L, third.timers.value.first().leftMs(now))
        assertEquals(1, third.due().size)
        assertTrue(third.start(60, "Ny").id > third.timers.value.first().id)
    }

    @Test
    fun learnsTheUsualLengthPerPerson() {
        val gabriel = Asker("p1", "Gabriel")
        val at = ZonedDateTime.parse("2026-10-20T18:00:00+02:00")
        var data = MemoryData()
        repeat(3) { data = Memory.merge(data, Timers.habit("pasta", 540, gabriel, at), 1L) }
        data = Memory.merge(data, Timers.habit("Pasta", 600, gabriel, at), 2L)
        data = Memory.merge(data, Timers.habit("Pasta", 720, null, at), 3L)
        data = Memory.merge(data, Timers.habit("Ägg", 450, gabriel, at), 4L)
        assertEquals("Gabriel sets the timer Pasta for 9 minutes", data.facts.first().let { "${it.subject} ${it.relation} ${it.obj}" })
        assertEquals(540, Timers.usualSeconds(data.facts, "pastatimer", "p1"))
        assertEquals(540, Timers.usualSeconds(data.facts, "pastan", "p1"))
        assertEquals(450, Timers.usualSeconds(data.facts, "ägg", "p1"))
        // Someone unknown gets the house's.
        assertEquals(720, Timers.usualSeconds(data.facts, "pasta", null))
        assertNull(Timers.usualSeconds(data.facts, "ris", "p1"))
    }

    @Test
    fun theToolAsksForANameAndUsesTheUsualLength() = runBlocking {
        val dir = Files.createTempDirectory("mem").toFile()
        val book = MemoryBook(dir)
        val asker = Asker("p1", "Gabriel")
        book.learn(listOf(Timers.habit("Pasta", 540, asker, ZonedDateTime.now())))
        val board = TimerBoard(null)
        val view = HomeView.build(emptyMap(), emptyMap(), emptyMap(), emptySet(), AgentSettings(asker = asker), true)
        val tools = AgentTools(view, NoHome, Dash(board), memory = MemoryTools(book))
        val plain = tools.run(ToolCall("1", "timer", args("action" to "start", "minutes" to "10"))).text
        assertTrue(plain, plain.contains("ask whether they want to give it a name"))
        val usual = tools.run(ToolCall("2", "timer", args("action" to "start", "label" to "pasta"))).text
        assertTrue(usual, usual.contains("9 minutes, the usual"))
        val renamed = tools.run(ToolCall("3", "timer", args("action" to "rename", "label" to "", "new_label" to "ägg"))).text
        assertTrue(renamed, renamed.contains("Ägg"))
        assertEquals(setOf("Ägg", "Pasta"), board.timers.value.map { it.label }.toSet())
    }

    private fun args(vararg pairs: Pair<String, String>) =
        kotlinx.serialization.json.JsonObject(pairs.associate { (k, v) -> k to kotlinx.serialization.json.JsonPrimitive(v) })

    private object NoHome : HomeControl {
        override suspend fun setCapability(deviceId: String, capability: String, value: kotlinx.serialization.json.JsonElement) {}
        override suspend fun setMood(mood: app.homeydash.homey.Mood) {}
        override suspend fun flows() = emptyList<app.homeydash.homey.HomeyFlow>()
        override suspend fun triggerFlow(flow: app.homeydash.homey.HomeyFlow) {}
        override suspend fun people() = emptyList<HomeyPerson>()
        override suspend fun variables() = emptyList<LogicVariable>()
        override suspend fun playMusic(deviceId: String, query: String): String? = null
    }

    private class Dash(override val timers: TimerBoard) : DashboardControl {
        override fun openPage(page: String, roomId: String?) = false
        override fun openDevice(deviceId: String) = false
        override fun goodnightPreview() = ""
        override fun goodnight() {}
        override suspend fun weather(): String? = null
        override suspend fun calendar(from: java.time.LocalDate, days: Int, withLocations: Boolean) = emptyList<AgentEvent>()
        override fun setTheme(mode: String) = false
    }
}
