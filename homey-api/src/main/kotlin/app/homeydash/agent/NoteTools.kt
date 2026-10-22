package app.homeydash.agent

import app.homeydash.notes.NoteBoard
import app.homeydash.notes.NoteColor
import app.homeydash.notes.PostIt
import app.homeydash.notes.Todo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The assistant's post-its and to-do list: reading them out, writing post-its for the house
 * (they also light up the dark screen), and adding, ticking off and changing to-dos.
 */
class NoteTools(
    private val board: NoteBoard,
    /** Shows the Notes page on the dashboard. */
    private val show: () -> Unit = {},
) {
    fun specs(): List<ToolSpec> = listOf(
        tool("notes", "Read the post-its on the kitchen screen and the to-do list, to-dos due first.") {
            string("kind", "Optional: postits, todos, or both (default)")
            string("who", "Optional: only to-dos for this person (a name, or mine)")
            string("done", "Optional: true to include ticked-off to-dos")
        },
        tool(
            "write_note",
            "Put a post-it on the kitchen screen, e.g. a message for someone or a reminder for the house. " +
                "Write the text short, the way it should read on the note.",
        ) {
            string("text", "What the post-it says")
            string("color", "Optional: yellow, pink, green, blue, orange or purple")
            string("for", "Optional: who it is for, a name")
            string("until", "Optional: the last day it should stay up, as said (\"fredag\", \"tomorrow\") or yyyy-mm-dd")
            required("text")
        },
        tool("change_note", "Change or take down a post-it.") {
            string("note", "Words from the post-it, as listed by notes")
            string("action", "edit (default) or remove")
            string("text", "New text, for edit")
            string("color", "New colour, for edit")
            string("until", "New last day, for edit; \"none\" keeps it up")
            required("note")
        },
        tool("add_todo", "Add something to the to-do list.") {
            string("text", "The thing to do, short, e.g. Book car service")
            string("who", "Optional: who should do it, a name or mine; leave out for everyone")
            string("due", "Optional: the day it is due, as said (\"fredag\", \"tomorrow\") or yyyy-mm-dd")
            string("repeat", "Optional: daily, weekly or monthly, for something that comes back")
            required("text")
        },
        tool("change_todo", "Tick off, un-tick, change or remove a to-do; or clear all the ticked-off ones.") {
            string("item", "Words from the to-do, as listed by notes; \"done\" with action clear")
            string("action", "done (default), undo, edit, remove, or clear (takes away every ticked-off to-do)")
            string("text", "New text, for edit")
            string("who", "New person, for edit; \"everyone\" for anyone")
            string("due", "New due day, for edit; \"none\" for no day")
            required("item")
        },
    )

    val names: Set<String> = setOf("notes", "write_note", "change_note", "add_todo", "change_todo")

    /** Runs a notes tool for [asker] (null when the tablet doesn't know who is talking), written by [character]. */
    suspend fun run(call: ToolCall, asker: Asker?, character: String, people: Map<String, String> = emptyMap()): ToolResult? {
        val a = call.arguments
        return when (call.name) {
            "notes" -> list(a.str("kind"), person(a.str("who"), asker, people), a.str("done") == "true")
            "write_note" -> write(a, asker, character, people)
            "change_note" -> changeNote(a)
            "add_todo" -> addTodo(a, asker, character, people)
            "change_todo" -> changeTodo(a, asker, people)
            else -> null
        }
    }

    private fun list(kind: String, who: String?, withDone: Boolean): ToolResult {
        val k = kind.trim().lowercase()
        val wantNotes = !k.startsWith("todo") && k != "to-dos"
        val wantTodos = !k.startsWith("post") && !k.startsWith("note") && !k.startsWith("lapp")
        val today = board.today()
        return ok(buildString {
            append("Today is ").append(today).append('.')
            if (wantNotes) {
                val notes = board.current()
                append("\nPost-its: ")
                if (notes.isEmpty()) append("none.") else notes.take(20).forEach { append("\n- ").append(noteLine(it, today)) }
            }
            if (wantTodos) {
                val todos = board.ordered().filter { withDone || it.done == null }
                    .filter { who == null || it.who == null || NameMatch.sameWord(NameMatch.normalize(it.who), NameMatch.normalize(who)) }
                append("\nTo-dos").append(who?.let { " for $it" }.orEmpty()).append(": ")
                if (todos.isEmpty()) append("nothing to do.") else todos.take(30).forEach { append("\n- ").append(todoLine(it, today)) }
                val done = board.todos.value.count { it.done != null }
                if (!withDone && done > 0) append("\n($done ticked off.)")
            }
        })
    }

    private suspend fun write(a: JsonObject, asker: Asker?, character: String, people: Map<String, String>): ToolResult {
        val text = a.str("text").trim()
        if (text.isBlank()) return ok("Say what the post-it should say.")
        val until = when (val u = day(a.str("until"))) {
            is Day.Bad -> return ok(u.why)
            is Day.Some -> u.date
            Day.None -> null
        }
        val forWhom = a.str("for").trim().takeIf { it.isNotEmpty() }?.let { person(it, asker, people) ?: it.replaceFirstChar { c -> c.titlecase(Locale.ROOT) } }
        val note = board.write(
            PostIt(
                text = text,
                color = NoteColor.find(a.str("color")) ?: nextColor(),
                by = asker?.name ?: character,
                byId = asker?.id,
                forWhom = forWhom,
                until = until?.toString(),
            ),
        )
        show()
        return ok("Put up a ${note.color.en} post-it: \"${note.text}\"" + (forWhom?.let { " for $it" } ?: "") + (until?.let { ", up until ${day(it)}" } ?: "") + ".")
    }

    private suspend fun changeNote(a: JsonObject): ToolResult {
        val note = findNote(a.str("note")) ?: return noNote(a.str("note"))
        if (a.str("action").trim().lowercase() in REMOVE) {
            board.removeNote(note.id)
            show()
            return ok("Took down the post-it \"${note.text}\".")
        }
        var changed = note
        a.str("text").trim().takeIf { it.isNotEmpty() }?.let { changed = changed.copy(text = it) }
        NoteColor.find(a.str("color"))?.let { changed = changed.copy(color = it) }
        if (a.str("until").isNotBlank()) {
            changed = when (val u = day(a.str("until"))) {
                is Day.Bad -> return ok(u.why)
                is Day.Some -> changed.copy(until = u.date.toString())
                Day.None -> changed.copy(until = null)
            }
        }
        if (changed == note) return ok("Nothing to change; say the new text, colour or last day.")
        board.saveNote(changed)
        show()
        return ok("Changed the post-it: " + noteLine(changed, board.today()))
    }

    private suspend fun addTodo(a: JsonObject, asker: Asker?, character: String, people: Map<String, String>): ToolResult {
        val text = a.str("text").trim()
        if (text.isBlank()) return ok("Say what to add.")
        val due = when (val d = day(a.str("due"))) {
            is Day.Bad -> return ok(d.why)
            is Day.Some -> d.date
            Day.None -> null
        }
        val repeat = repeatDays(a.str("repeat"))
        val who = person(a.str("who"), asker, people)
        // The same thing said twice shouldn't be on the list twice.
        board.todos.value.firstOrNull { it.done == null && NameMatch.normalize(it.text) == NameMatch.normalize(text) }?.let {
            return ok("\"${it.text}\" is on the to-do list already: " + todoLine(it, board.today()))
        }
        val todo = board.add(
            Todo(
                text = text.replaceFirstChar { it.titlecase(Locale.ROOT) },
                who = who,
                due = (due ?: repeat?.let { board.today() })?.toString(),
                by = asker?.name ?: character,
                byId = asker?.id,
                repeatDays = repeat,
            ),
        )
        show()
        return ok("Added to the to-do list: " + todoLine(todo, board.today()))
    }

    private suspend fun changeTodo(a: JsonObject, asker: Asker?, people: Map<String, String>): ToolResult {
        val action = a.str("action").trim().lowercase().ifEmpty { "done" }
        if (action == "clear" || action == "clear_done") {
            val gone = board.clearDone()
            show()
            return ok(if (gone == 0) "There were no ticked-off to-dos." else "Cleared $gone ticked-off to-do${if (gone == 1) "" else "s"}.")
        }
        val todo = findTodo(a.str("item"), open = action != "undo") ?: return noTodo(a.str("item"))
        val result = when (action) {
            in REMOVE -> {
                board.removeTodo(todo.id)
                "Removed \"${todo.text}\" from the to-do list."
            }
            "undo", "not done", "open" -> {
                board.tick(todo.id, done = false)
                "\"${todo.text}\" is back on the list."
            }
            "edit", "change" -> {
                var changed = todo
                a.str("text").trim().takeIf { it.isNotEmpty() }?.let { changed = changed.copy(text = it) }
                a.str("who").trim().takeIf { it.isNotEmpty() }?.let { w ->
                    changed = changed.copy(who = if (w.lowercase() in EVERYONE) null else person(w, asker, people) ?: w)
                }
                if (a.str("due").isNotBlank()) changed = when (val d = day(a.str("due"))) {
                    is Day.Bad -> return ok(d.why)
                    is Day.Some -> changed.copy(due = d.date.toString())
                    Day.None -> changed.copy(due = null)
                }
                if (changed == todo) return ok("Nothing to change; say the new text, person or day.")
                board.saveTodo(changed)
                "Changed: " + todoLine(changed, board.today())
            }
            else -> {
                if (todo.done != null) return ok("\"${todo.text}\" is ticked off already.")
                val now = board.tick(todo.id, done = true, by = asker?.name, byId = asker?.id)
                if (now?.done == null && now?.due != null) "Ticked off \"${todo.text}\"; it comes back ${day(now.dueDate())}."
                else "Ticked off \"${todo.text}\"." + left()
            }
        }
        show()
        return ok(result)
    }

    private fun left(): String {
        val open = board.todos.value.count { it.done == null }
        return when (open) {
            0 -> " The to-do list is empty now."
            1 -> " 1 thing left."
            else -> " $open things left."
        }
    }

    private fun noteLine(n: PostIt, today: LocalDate): String = buildString {
        append('"').append(n.text).append('"')
        append(" (").append(n.color.en)
        if (n.by.isNotBlank()) append(", by ").append(n.by)
        n.forWhom?.let { append(", for ").append(it) }
        n.untilDate()?.let { append(", up until ").append(relative(it, today)) }
        append(')')
    }

    private fun todoLine(t: Todo, today: LocalDate): String = buildString {
        append(t.text)
        append(" · ").append(t.who ?: "anyone")
        t.dueDate()?.let { d ->
            append(" · ")
            append(if (d.isBefore(today) && t.done == null) "overdue since ${relative(d, today)}" else "due ${relative(d, today)}")
        }
        t.repeatDays?.let { append(" · every ").append(repeatName(it)) }
        if (t.done != null) append(" · done").append(t.doneBy?.let { " by $it" }.orEmpty())
    }

    private fun relative(d: LocalDate, today: LocalDate): String = when (ChronoUnit.DAYS.between(today, d)) {
        0L -> "today"
        1L -> "tomorrow"
        -1L -> "yesterday"
        else -> day(d)
    }

    private fun day(d: LocalDate?): String = d?.format(DAY) ?: "?"

    private sealed interface Day {
        data object None : Day
        data class Some(val date: LocalDate) : Day
        data class Bad(val why: String) : Day
    }

    private fun day(text: String): Day {
        val t = text.trim()
        if (t.isEmpty() || t.lowercase() in NO_DAY) return Day.None
        return CalendarBook.day(t, board.today())?.let { Day.Some(it) }
            ?: Day.Bad("I couldn't tell which day \"$t\" is; say a weekday, tomorrow, or yyyy-mm-dd.")
    }

    /** A name said for a person: "mine"/"me" is the asker; a known person's name is written as the tablet knows it. */
    private fun person(said: String, asker: Asker?, people: Map<String, String>): String? {
        val s = said.trim()
        if (s.isEmpty() || s.lowercase() in EVERYONE) return null
        if (s.lowercase() in MINE) return asker?.name
        return people.values.firstOrNull { NameMatch.sameWord(NameMatch.normalize(it), NameMatch.normalize(s)) } ?: s.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    private fun repeatDays(text: String): Int? = when (text.trim().lowercase()) {
        "daily", "every day", "varje dag", "dagligen" -> 1
        "weekly", "every week", "varje vecka", "veckovis" -> 7
        "fortnightly", "every other week", "varannan vecka" -> 14
        "monthly", "every month", "varje månad", "månadsvis" -> 30
        else -> text.trim().toIntOrNull()?.takeIf { it in 1..365 }
    }

    private fun repeatName(days: Int): String = when (days) {
        1 -> "day"
        7 -> "week"
        14 -> "two weeks"
        30 -> "month"
        else -> "$days days"
    }

    /** Each new post-it a different colour from the one before, so the wall looks lively. */
    private fun nextColor(): NoteColor {
        val last = board.current().firstOrNull()?.color
        val order = listOf(NoteColor.Yellow, NoteColor.Pink, NoteColor.Mint, NoteColor.Blue, NoteColor.Orange, NoteColor.Lilac)
        return if (last == null) NoteColor.Yellow else order[(order.indexOf(last) + 1) % order.size]
    }

    private fun findNote(said: String): PostIt? {
        val notes = board.current()
        notes.firstOrNull { it.id == said }?.let { return it }
        if (said.trim().lowercase() in LAST && notes.isNotEmpty()) return notes.first()
        return NameMatch.best(said, notes) { words(said, it.text + " " + (it.forWhom ?: "")) }
    }

    private fun findTodo(said: String, open: Boolean): Todo? {
        val todos = board.ordered().filter { if (open) it.done == null else it.done != null }.ifEmpty { board.ordered() }
        todos.firstOrNull { it.id == said }?.let { return it }
        return NameMatch.best(said, todos) { words(said, it.text) }
    }

    /** How much of what was said is found in [text]: 1 when every word is in it. */
    private fun words(said: String, text: String): Double {
        // "lappen om blommorna" is the post-it with "blommor" on it: the words for a note don't count.
        val q = NameMatch.tokens(said).filter { it !in NOTE_WORDS }
        if (q.isEmpty()) return 0.0
        val t = NameMatch.tokens(text)
        val joined = t.joinToString("")
        val hits = q.count { w -> t.any { NameMatch.sameWord(it, w) } || (w.length >= 4 && joined.contains(w)) }
        return hits.toDouble() / q.size
    }

    private val NOTE_WORDS = setOf(
        "lapp", "lappen", "lappar", "lapparna", "postit", "post", "it", "om", "med", "pa", "dar", "det", "star",
        "note", "notes", "the", "about", "with", "on", "that", "says",
    )

    private fun noNote(said: String): ToolResult {
        val notes = board.current()
        return ok(if (notes.isEmpty()) "There are no post-its up." else "No post-it says \"$said\". Up now: " + notes.take(10).joinToString { "\"${it.text}\"" } +
            ". If one is plainly what the user meant (also in the other language: \"flowers\" for \"blommor\"), call again with its words; ask only when unsure.")
    }

    private fun noTodo(said: String): ToolResult {
        val open = board.ordered().filter { it.done == null }
        return ok(if (open.isEmpty()) "The to-do list is empty." else "\"$said\" isn't clear. On the list: " + open.take(12).joinToString { it.text } + ". Ask which one.")
    }

    private fun ok(text: String) = ToolResult(text)

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private companion object {
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
        val REMOVE = setOf("remove", "delete", "take down", "ta bort", "radera")
        val EVERYONE = setOf("everyone", "anyone", "all", "alla", "everybody", "house", "huset", "familjen", "family")
        val MINE = setOf("mine", "me", "my", "i", "min", "mig", "jag", "mitt", "mina")
        val NO_DAY = setOf("none", "no", "never", "ingen", "inget", "aldrig")
        val LAST = setOf("last", "latest", "newest", "senaste", "sista", "den senaste", "the last one")
    }
}
