package app.homeydash.notes

import app.homeydash.agent.Asker
import app.homeydash.agent.Memory
import app.homeydash.agent.NoteTools
import app.homeydash.agent.ToolCall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId

class NoteBoardTest {
    private var today = LocalDate.of(2026, 10, 22)
    private var now = 1_000_000L
    private val dir = Files.createTempDirectory("notes").toFile()
    private val board = NoteBoard(dir, clock = { now }, date = { today }).also { runBlocking { it.load() } }
    private val tools = NoteTools(board)
    private val gabriel = Asker("p-gabriel", "Gabriel")
    private val people = mapOf("p-gabriel" to "Gabriel", "p-alex" to "Alex")

    private fun call(name: String, vararg args: Pair<String, String>): String = runBlocking {
        tools.run(ToolCall("1", name, JsonObject(args.associate { it.first to JsonPrimitive(it.second) })), gabriel, "Nova", people)!!.text
    }

    @Test
    fun aPostItForSomeoneGoesUpWithWhoWroteIt() {
        call("write_note", "text" to "Middagen står i kylen", "for" to "alex", "until" to "tomorrow")
        val note = board.current().single()
        assertEquals("Middagen står i kylen", note.text)
        assertEquals("Alex", note.forWhom)
        assertEquals("Gabriel", note.by)
        assertEquals(today.plusDays(1), note.untilDate())
        assertTrue(note.dark)
    }

    @Test
    fun postItsPastTheirDayAreTakenDown() = runBlocking {
        board.write(PostIt(text = "Idag bara", until = today.toString()))
        board.write(PostIt(text = "Stays"))
        today = today.plusDays(1)
        assertEquals(listOf("Stays"), board.current().map { it.text })
        board.dropExpired()
        assertEquals(listOf("Stays"), board.notes.value.map { it.text })
    }

    @Test
    fun aPostItIsFoundByItsWordsAndTakenDown() {
        call("write_note", "text" to "Köp blommor till mormor")
        call("write_note", "text" to "Wifi för gäster")
        val reply = call("change_note", "note" to "blommorna", "action" to "remove")
        assertTrue(reply, reply.startsWith("Took down"))
        assertEquals(listOf("Wifi för gäster"), board.current().map { it.text })
    }

    @Test
    fun myTodoIsTheAskersAndIsTickedOffByTheirWords() {
        call("add_todo", "text" to "boka bilservice", "who" to "mine", "due" to "fredag")
        val todo = board.todos.value.single()
        assertEquals("Boka bilservice", todo.text)
        assertEquals("Gabriel", todo.who)
        assertEquals(LocalDate.of(2026, 10, 23), todo.dueDate())
        val reply = call("change_todo", "item" to "bilservicen")
        assertTrue(reply, reply.startsWith("Ticked off"))
        val done = board.todos.value.single()
        assertNotNull(done.done)
        assertEquals("p-gabriel", done.doneById)
    }

    @Test
    fun theSameTodoTwiceIsKeptOnce() {
        call("add_todo", "text" to "Vattna blommorna")
        val reply = call("add_todo", "text" to "vattna blommorna")
        assertTrue(reply, reply.contains("already"))
        assertEquals(1, board.todos.value.size)
    }

    @Test
    fun aRepeatingTodoComesBackInsteadOfStayingDone() {
        call("add_todo", "text" to "Ta ut soporna", "repeat" to "weekly")
        val id = board.todos.value.single().id
        runBlocking { board.tick(id) }
        val again = board.todo(id)!!
        assertNull(again.done)
        assertEquals(today.plusDays(7), again.dueDate())
    }

    @Test
    fun theListPutsWhatIsDueFirstAndLeavesOutOthersTodos() {
        call("add_todo", "text" to "Lämna paketet", "who" to "Alex")
        call("add_todo", "text" to "Byt glödlampan")
        call("add_todo", "text" to "Boka tandläkaren", "who" to "Gabriel", "due" to "today")
        val reply = call("notes", "kind" to "todos", "who" to "mine")
        assertTrue(reply, reply.indexOf("Boka tandläkaren") in 0 until reply.indexOf("Byt glödlampan"))
        assertTrue(reply, "paketet" !in reply)
    }

    @Test
    fun ticksAndNotesBecomeMemoryFacts() {
        val at = java.time.ZonedDateTime.of(2026, 10, 22, 19, 0, 0, 0, ZoneId.of("Europe/Stockholm"))
        val facts = Memory.postIt(PostIt(text = "Middagen står i kylen", by = "Gabriel", byId = "p-gabriel", forWhom = "Alex"), at)
        assertEquals(listOf("leaves post-its for", "wrote on a post-it for Alex"), facts.map { it.relation })
        assertTrue(facts.all { it.who == "p-gabriel" })
        val done = Memory.todoDone(Todo(text = "Ta ut soporna", due = "2026-10-21"), gabriel, at)!!
        assertEquals("gets done, late,", done.relation)
        assertEquals("Ta ut soporna", done.obj)
        assertEquals(app.homeydash.agent.FactKind.Habit, done.kind)
    }
}
