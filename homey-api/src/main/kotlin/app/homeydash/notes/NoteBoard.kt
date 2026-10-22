package app.homeydash.notes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** A post-it's paper colour. */
@Serializable
enum class NoteColor(val en: String, val sv: String) {
    Yellow("yellow", "gul"),
    Pink("pink", "rosa"),
    Mint("green", "grön"),
    Blue("blue", "blå"),
    Orange("orange", "orange"),
    Lilac("purple", "lila");

    companion object {
        fun find(text: String): NoteColor? {
            val t = text.trim().lowercase()
            if (t.isEmpty()) return null
            return entries.firstOrNull { it.name.lowercase() == t || it.en == t || t.startsWith(it.sv) } ?: when (t) {
                "mint", "gröna", "green" -> Mint
                "lilac", "violet", "lavender" -> Lilac
                "red", "röd", "rosa" -> Pink
                "gula", "gult" -> Yellow
                "blåa", "blått" -> Blue
                else -> null
            }
        }
    }
}

/**
 * One post-it on the kitchen screen. [by] is who wrote it (a person's name, or the assistant's
 * character when it was asked to), [byId] that person's id; [forWhom] who it is meant for, if
 * anyone. It goes away by itself after [until]. [dark] puts it on the dark screen too.
 */
@Serializable
data class PostIt(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val color: NoteColor = NoteColor.Yellow,
    val by: String = "",
    val byId: String? = null,
    val forWhom: String? = null,
    val created: Long = 0,
    val until: String? = null,
    val dark: Boolean = true,
) {
    fun untilDate(): LocalDate? = until?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}

/**
 * One thing to do. [who] is the person it is for, or null for everyone; [due] a day; [done]
 * when it was ticked off. A todo that [repeatDays] is set on comes back that many days after
 * its due day instead of staying ticked.
 */
@Serializable
data class Todo(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val who: String? = null,
    val due: String? = null,
    val done: Long? = null,
    val doneBy: String? = null,
    val doneById: String? = null,
    val created: Long = 0,
    val by: String = "",
    val byId: String? = null,
    val repeatDays: Int? = null,
) {
    fun dueDate(): LocalDate? = due?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}

@Serializable
private data class BoardFile(
    val version: Int = 1,
    val notes: List<PostIt> = emptyList(),
    val todos: List<Todo> = emptyList(),
)

/**
 * The household's post-its and to-do list: one JSON file under [dir]. The Notes page, the
 * dark screen and the assistant all read and change the same board.
 */
class NoteBoard(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val date: () -> LocalDate = { LocalDate.now() },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val lock = Mutex()
    private val file = File(dir, "notes.json")

    private val _notes = MutableStateFlow<List<PostIt>>(emptyList())
    val notes: StateFlow<List<PostIt>> = _notes.asStateFlow()

    private val _todos = MutableStateFlow<List<Todo>>(emptyList())
    val todos: StateFlow<List<Todo>> = _todos.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Hears every post-it written and every to-do added or ticked off, for the assistant's memory. */
    var onWritten: (suspend (PostIt) -> Unit)? = null
    var onTodoAdded: (suspend (Todo) -> Unit)? = null
    var onTodoDone: (suspend (Todo) -> Unit)? = null

    suspend fun load() {
        lock.withLock {
            withContext(Dispatchers.IO) {
                val data = runCatching { json.decodeFromString<BoardFile>(file.readText()) }.getOrElse {
                    if (file.exists()) file.copyTo(File(dir, "notes.broken-${clock()}.json"), overwrite = true)
                    BoardFile()
                }
                _notes.value = data.notes
                _todos.value = data.todos
            }
            _loaded.value = true
        }
        dropExpired()
    }

    fun today(): LocalDate = date()

    /** Post-its still up, newest first. */
    fun current(): List<PostIt> = _notes.value.filter { n -> n.untilDate()?.let { !it.isBefore(today()) } ?: true }.sortedByDescending { it.created }

    /** To-dos to do first at the top: overdue and due soonest, then undated, then the done ones. */
    fun ordered(todos: List<Todo> = _todos.value): List<Todo> = order(todos)

    fun note(id: String): PostIt? = _notes.value.firstOrNull { it.id == id }

    fun todo(id: String): Todo? = _todos.value.firstOrNull { it.id == id }

    suspend fun write(note: PostIt): PostIt {
        val saved = note.copy(text = note.text.trim(), created = note.created.takeIf { it > 0 } ?: clock())
        change { b -> b.copy(notes = b.notes.filterNot { it.id == saved.id } + saved) }
        onWritten?.let { runCatching { it(saved) } }
        return saved
    }

    suspend fun saveNote(note: PostIt) = change { b -> b.copy(notes = b.notes.map { if (it.id == note.id) note.copy(text = note.text.trim()) else it }) }

    suspend fun removeNote(id: String) = change { b -> b.copy(notes = b.notes.filterNot { it.id == id }) }

    suspend fun add(todo: Todo): Todo {
        val saved = todo.copy(text = todo.text.trim(), created = todo.created.takeIf { it > 0 } ?: clock())
        change { b -> b.copy(todos = b.todos.filterNot { it.id == saved.id } + saved) }
        onTodoAdded?.let { runCatching { it(saved) } }
        return saved
    }

    suspend fun saveTodo(todo: Todo) = change { b -> b.copy(todos = b.todos.map { if (it.id == todo.id) todo.copy(text = todo.text.trim()) else it }) }

    /**
     * Ticks [id] off (or back on when [done] is false). A repeating one moves to its next due
     * day and stays open; the result is the to-do as it is now.
     */
    suspend fun tick(id: String, done: Boolean = true, by: String? = null, byId: String? = null): Todo? {
        val todo = todo(id) ?: return null
        val now = when {
            !done -> todo.copy(done = null, doneBy = null, doneById = null)
            todo.repeatDays != null && todo.repeatDays > 0 -> {
                val from = maxOf(todo.dueDate() ?: today(), today())
                todo.copy(due = from.plusDays(todo.repeatDays.toLong()).toString(), done = null, doneBy = null, doneById = null)
            }
            else -> todo.copy(done = clock(), doneBy = by, doneById = byId)
        }
        change { b -> b.copy(todos = b.todos.map { if (it.id == id) now else it }) }
        if (done) onTodoDone?.let { runCatching { it(todo.copy(done = clock(), doneBy = by, doneById = byId)) } }
        return now
    }

    suspend fun removeTodo(id: String) = change { b -> b.copy(todos = b.todos.filterNot { it.id == id }) }

    /** Takes away every ticked-off to-do; returns how many went. */
    suspend fun clearDone(): Int {
        val gone = _todos.value.count { it.done != null }
        if (gone > 0) change { b -> b.copy(todos = b.todos.filter { it.done == null }) }
        return gone
    }

    /** Post-its past their day, and to-dos ticked off more than a week ago, are tidied away. */
    suspend fun dropExpired() {
        val today = today()
        val weekAgo = clock() - DONE_KEPT_MS
        val stale = _notes.value.any { n -> n.untilDate()?.isBefore(today) == true } || _todos.value.any { (it.done ?: Long.MAX_VALUE) < weekAgo }
        if (stale) change { b ->
            b.copy(
                notes = b.notes.filterNot { n -> n.untilDate()?.isBefore(today) == true },
                todos = b.todos.filterNot { (it.done ?: Long.MAX_VALUE) < weekAgo },
            )
        }
    }

    private suspend fun change(edit: (BoardFile) -> BoardFile) = lock.withLock {
        val next = edit(BoardFile(notes = _notes.value, todos = _todos.value))
        _notes.value = next.notes
        _todos.value = next.todos
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val temp = File(dir, "notes.json.tmp")
            temp.writeText(json.encodeToString(BoardFile.serializer(), next))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    companion object {
        const val DONE_KEPT_MS = 7 * 24 * 3600_000L

        fun order(todos: List<Todo>): List<Todo> = todos.sortedWith(
            compareBy<Todo>({ it.done != null }, { it.dueDate() ?: LocalDate.MAX }, { -(it.done ?: 0) }, { it.created }),
        )
    }
}
