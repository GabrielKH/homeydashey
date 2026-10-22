package app.homeydash.ui.dashboard

import android.app.DatePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.homeydash.notes.NoteBoard
import app.homeydash.notes.NoteColor
import app.homeydash.notes.PostIt
import app.homeydash.notes.Todo
import app.homeydash.ui.components.DialogHeader
import app.homeydash.ui.components.GlassDialog
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.SectionLabel
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.TileShape
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme
import app.homeydash.ui.theme.Handwriting
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

/**
 * The household's post-its and to-do list on one page: the post-its as a wall on the left,
 * the to-dos in a glass panel on the right. The assistant writes to the same [board], and the
 * post-its also light up the dark screen.
 */
@Composable
fun NotesScreen(board: NoteBoard, people: List<String>, onOverview: Boolean, onOnOverview: (Boolean) -> Unit) {
    val g = GlassTheme.colors
    val loaded by board.loaded.collectAsStateWithLifecycle()
    val allNotes by board.notes.collectAsStateWithLifecycle()
    val todos by board.todos.collectAsStateWithLifecycle()
    val today = rememberToday()
    var editingNote by remember { mutableStateOf<PostIt?>(null) }
    var editingTodo by remember { mutableStateOf<Todo?>(null) }

    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = g.fg) }
        return
    }
    val notes = remember(allNotes, today) { board.current() }
    val names = remember(people, todos) { (people + todos.mapNotNull { it.who }).distinct() }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(eyebrow = "Home", title = "Notes") {
            FilterChip("On the overview", onOverview, Sym.check.takeIf { onOverview }) { onOnOverview(!onOverview) }
            PillButton(Sym.add, "Post-it", primary = true) { editingNote = PostIt(id = "", text = "", color = nextColor(notes)) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // Side by side on the landscape tablet; stacked when the window is narrow.
            if (maxWidth >= 760.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    PostItWall(notes, today, Modifier.weight(1.45f).fillMaxHeight(), onOpen = { editingNote = it }, onNew = {
                        editingNote = PostIt(id = "", text = "", color = nextColor(notes))
                    })
                    TodoPanel(board, todos, names, today, Modifier.weight(1f).fillMaxHeight(), onOpen = { editingTodo = it })
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PostItWall(notes, today, Modifier.weight(1f).fillMaxWidth(), onOpen = { editingNote = it }, onNew = {
                        editingNote = PostIt(id = "", text = "", color = nextColor(notes))
                    })
                    TodoPanel(board, todos, names, today, Modifier.weight(1f).fillMaxWidth(), onOpen = { editingTodo = it })
                }
            }
        }
    }

    editingNote?.let { NoteDialog(board, it, names, onDone = { editingNote = null }) }
    editingTodo?.let { TodoDialog(board, it, names, onDone = { editingTodo = null }) }
}

// ---------------------------------------------------------------- Post-its

@Composable
private fun PostItWall(notes: List<PostIt>, today: LocalDate, modifier: Modifier, onOpen: (PostIt) -> Unit, onNew: () -> Unit) {
    val g = GlassTheme.colors
    if (notes.isEmpty()) {
        Column(
            modifier.glass(TileShape).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PostItCard(PostIt(text = "Hej! Write a note for the house.", color = NoteColor.Yellow), today, Modifier.width(220.dp).aspectRatio(1f))
            Text("No post-its yet", style = MaterialTheme.typography.headlineSmall, color = g.fg, modifier = Modifier.padding(top = 16.dp))
            Text(
                "Leave a message for someone, or ask the assistant: \"skriv en lapp till Anna att middagen står i kylen\". " +
                    "Post-its also glow on the dark screen.",
                style = MaterialTheme.typography.bodyLarge,
                color = g.muted,
                modifier = Modifier.width(460.dp),
            )
            PillButton(Sym.add, "New post-it", primary = true, big = true, onClick = onNew)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 200.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 96.dp),
        modifier = modifier,
    ) {
        items(notes, key = { it.id }) { note ->
            PostItCard(note, today, Modifier.aspectRatio(1f).pressable({ onOpen(note) }))
        }
        item(key = "new") {
            Box(
                Modifier
                    .aspectRatio(1f)
                    .pressable(onNew)
                    .border(BorderStroke(2.dp, g.muted.copy(alpha = 0.45f)), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SymIcon(Sym.add, size = 20.dp, tint = g.muted)
                    Text("New post-it", style = MaterialTheme.typography.labelLarge, color = g.muted)
                }
            }
        }
    }
}

/** The paper colours, bright enough to read on the dimmed screen. */
internal fun paper(c: NoteColor): Color = when (c) {
    NoteColor.Yellow -> Color(0xFFFFE873)
    NoteColor.Pink -> Color(0xFFFF9EC7)
    NoteColor.Mint -> Color(0xFF9EF0C8)
    NoteColor.Blue -> Color(0xFF9CD3FF)
    NoteColor.Orange -> Color(0xFFFFC27A)
    NoteColor.Lilac -> Color(0xFFCDB4FF)
}

private val INK = Color(0xFF2A2412)
private val PostItShape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomEnd = 22.dp, bottomStart = 4.dp)

/** A slight tilt that stays the same for each note, so the wall looks stuck up by hand. */
private fun tilt(note: PostIt): Float = ((abs(note.id.hashCode()) % 9) - 4) * 0.6f

/** One post-it: the text in handwriting, who wrote it and for whom at the foot. */
@Composable
internal fun PostItCard(note: PostIt, today: LocalDate, modifier: Modifier = Modifier, textSize: Int = 30, footer: Boolean = true, shadow: Boolean = true) {
    Column(
        modifier
            .graphicsLayer { rotationZ = tilt(note) }
            .then(if (shadow) Modifier.shadow(10.dp, PostItShape) else Modifier)
            .background(paper(note.color), PostItShape)
            .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp),
    ) {
        Text(
            note.text.ifBlank { "…" },
            fontFamily = Handwriting,
            fontWeight = FontWeight.Medium,
            fontSize = textSize.sp,
            lineHeight = (textSize * 1.05f).sp,
            color = INK,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (footer) {
            val left = listOfNotNull(note.by.takeIf { it.isNotBlank() }, note.forWhom?.let { "for $it" }).joinToString(" · ")
            val right = note.untilDate()?.let { "until " + dayWord(it, today) }.orEmpty()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(left, style = MaterialTheme.typography.labelMedium, color = INK.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (right.isNotEmpty()) Text(right, style = MaterialTheme.typography.labelMedium, color = INK.copy(alpha = 0.7f), maxLines = 1)
            }
        }
    }
}

private fun nextColor(notes: List<PostIt>): NoteColor {
    val last = notes.firstOrNull()?.color ?: return NoteColor.Yellow
    return NoteColor.entries[(last.ordinal + 1) % NoteColor.entries.size]
}

/** Writes a new post-it or changes one ([initial] has an id). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteDialog(board: NoteBoard, initial: PostIt, people: List<String>, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val isNew = initial.id.isBlank()
    val today = rememberToday()
    var note by remember(initial) { mutableStateOf(initial) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun save() = scope.launch {
        if (isNew) board.write(note.copy(id = java.util.UUID.randomUUID().toString())) else board.saveNote(note)
        onDone()
    }

    GlassDialog(onDismiss = onDone, width = 820.dp) {
        DialogHeader(if (isNew) "New post-it" else "Post-it", "Shows on the Notes page" + if (note.dark) " and on the dark screen" else "", onDone)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.weight(1f, fill = false)) {
            PostItCard(note, today, Modifier.width(240.dp).aspectRatio(1f).padding(4.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassField(note.text, { note = note.copy(text = it.take(MAX_TEXT)) }, "What should it say?", singleLine = false, minLines = 3)
                SectionLabel("Colour")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NoteColor.entries.forEach { c ->
                        Box(
                            Modifier
                                .size(44.dp)
                                .pressable({ note = note.copy(color = c) })
                                .background(paper(c), CircleShape)
                                .then(if (note.color == c) Modifier.border(3.dp, GlassTheme.colors.fg, CircleShape) else Modifier),
                        )
                    }
                }
                SectionLabel("For")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip("Everyone", note.forWhom == null) { note = note.copy(forWhom = null) }
                    people.forEach { p -> FilterChip(p, note.forWhom == p) { note = note.copy(forWhom = p) } }
                }
                SectionLabel("Stays up")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val until = note.untilDate()
                    val sunday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                    FilterChip("Until taken down", until == null) { note = note.copy(until = null) }
                    FilterChip("Today", until == today) { note = note.copy(until = today.toString()) }
                    FilterChip("Tomorrow", until == today.plusDays(1)) { note = note.copy(until = today.plusDays(1).toString()) }
                    FilterChip("This week", until == sunday && sunday != today && sunday != today.plusDays(1)) { note = note.copy(until = sunday.toString()) }
                    DayChip(until?.takeIf { it != today && it != today.plusDays(1) && it != sunday }) { note = note.copy(until = it.toString()) }
                }
                FilterChip("Glow on the dark screen", note.dark, Sym.bedtime) { note = note.copy(dark = !note.dark) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!isNew) PillButton(Sym.trash, if (confirmDelete) "Tap again to take down" else "Take down") {
                if (!confirmDelete) confirmDelete = true else scope.launch { board.removeNote(note.id); onDone() }
            }
            Spacer(Modifier.weight(1f))
            PillButton(Sym.check, if (isNew) "Put it up" else "Save", primary = true, enabled = note.text.isNotBlank()) { save() }
        }
    }
}

// ---------------------------------------------------------------- To-dos

@Composable
private fun TodoPanel(board: NoteBoard, todos: List<Todo>, people: List<String>, today: LocalDate, modifier: Modifier, onOpen: (Todo) -> Unit) {
    val g = GlassTheme.colors
    val scope = rememberCoroutineScope()
    // A person's name, TODAY, or null for everyone.
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var typed by rememberSaveable { mutableStateOf("") }
    val open = todos.count { it.done == null }
    val done = todos.size - open
    val shown = remember(todos, filter, today) {
        NoteBoard.order(todos).filter { t ->
            when (filter) {
                null -> true
                TODAY -> t.done == null && t.dueDate()?.let { !it.isAfter(today) } == true
                else -> t.who == null || t.who == filter
            }
        }
    }

    fun add() {
        val text = typed.trim()
        if (text.isEmpty()) return
        typed = ""
        scope.launch {
            board.add(Todo(text = text.replaceFirstChar { it.titlecase() }, who = filter?.takeIf { it != TODAY }, due = if (filter == TODAY) today.toString() else null))
        }
    }

    Column(modifier.glass(TileShape).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SymIcon(Sym.checkSquare, filled = true, size = 22.dp, tint = g.fg)
            Text("To do", style = MaterialTheme.typography.titleLarge, color = g.fg, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp).weight(1f))
            Text(
                when {
                    todos.isEmpty() -> ""
                    done == 0 -> "$open left"
                    else -> "$open left · $done done"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = g.muted,
            )
        }
        if (people.isNotEmpty() || todos.any { it.due != null }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip("Everyone", filter == null) { filter = null } }
                items(people) { p -> FilterChip(p, filter == p) { filter = if (filter == p) null else p } }
                item { FilterChip("Today", filter == TODAY) { filter = if (filter == TODAY) null else TODAY } }
            }
        }
        if (shown.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (todos.isEmpty()) "Nothing to do" else "All done here", style = MaterialTheme.typography.titleMedium, color = g.fg)
                Text(
                    "Type below, or say \"lägg till byt glödlampan i hallen på att göra-listan\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = g.muted,
                    modifier = Modifier.padding(top = 4.dp, start = 16.dp, end = 16.dp),
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.id }) { t ->
                    TodoRow(t, today, onToggle = { scope.launch { board.tick(t.id, done = t.done == null) } }, onOpen = { onOpen(t) })
                }
                if (done > 0) item(key = "clear") {
                    Text(
                        "Clear ticked-off",
                        style = MaterialTheme.typography.labelLarge,
                        color = g.muted,
                        modifier = Modifier.padding(top = 10.dp, start = 6.dp).pressable({ scope.launch { board.clearDone() } }),
                    )
                }
            }
        }
        GlassField(
            typed,
            { typed = it.take(MAX_TEXT) },
            when (filter) {
                null -> "Add a to-do"
                TODAY -> "Add a to-do for today"
                else -> "Add a to-do for $filter"
            },
            onGo = ::add,
            leading = { SymIcon(Sym.add, size = 20.dp, tint = g.muted, modifier = Modifier.pressable(::add)) },
        )
    }
}

@Composable
private fun TodoRow(t: Todo, today: LocalDate, onToggle: () -> Unit, onOpen: () -> Unit) {
    val g = GlassTheme.colors
    val isDone = t.done != null
    val due = t.dueDate()
    val late = !isDone && due != null && !due.isAfter(today)
    val accent = Color(0xFFFFB84D)
    Row(
        Modifier.fillMaxWidth().graphicsLayer { alpha = if (isDone) 0.5f else 1f }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The tick box is a big target; the text opens the to-do to change it.
        Box(Modifier.size(48.dp).pressable(onToggle), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(28.dp)
                    .then(
                        if (isDone) Modifier.background(g.ok, CircleShape)
                        else Modifier.border(2.dp, if (late) accent else g.muted, CircleShape),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isDone) SymIcon(Sym.check, filled = true, size = 16.dp, tint = Color.White)
            }
        }
        Column(Modifier.weight(1f).pressable(onOpen).padding(vertical = 6.dp)) {
            Text(
                t.text,
                style = MaterialTheme.typography.bodyLarge,
                color = g.fg,
                textDecoration = if (isDone) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                t.who ?: "Everyone",
                due?.let { if (late && it.isBefore(today)) "late, " + dayWord(it, today) else dayWord(it, today) },
                t.repeatDays?.let { repeatWord(it) },
                if (isDone) "done" + (t.doneBy?.let { " by $it" } ?: "") else null,
            ).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodyMedium, color = if (late) accent else g.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Adds a to-do or changes one ([initial] has an id). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodoDialog(board: NoteBoard, initial: Todo, people: List<String>, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val isNew = initial.id.isBlank()
    val today = rememberToday()
    var todo by remember(initial) { mutableStateOf(initial) }
    var confirmDelete by remember { mutableStateOf(false) }

    GlassDialog(onDismiss = onDone, width = 720.dp) {
        DialogHeader(if (isNew) "New to-do" else "To-do", todo.by.takeIf { it.isNotBlank() }?.let { "Added by $it" }, onDone)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassField(todo.text, { todo = todo.copy(text = it.take(MAX_TEXT)) }, "What needs doing?")
            SectionLabel("Who")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("Everyone", todo.who == null) { todo = todo.copy(who = null) }
                people.forEach { p -> FilterChip(p, todo.who == p) { todo = todo.copy(who = p) } }
            }
            SectionLabel("When")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val due = todo.dueDate()
                FilterChip("No day", due == null) { todo = todo.copy(due = null) }
                FilterChip("Today", due == today) { todo = todo.copy(due = today.toString()) }
                FilterChip("Tomorrow", due == today.plusDays(1)) { todo = todo.copy(due = today.plusDays(1).toString()) }
                DayChip(due?.takeIf { it != today && it != today.plusDays(1) }) { todo = todo.copy(due = it.toString()) }
            }
            SectionLabel("Repeats")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to "Once", 1 to "Every day", 7 to "Every week", 14 to "Every two weeks", 30 to "Every month").forEach { (days, label) ->
                    FilterChip(label, todo.repeatDays == days) {
                        todo = todo.copy(repeatDays = days, due = todo.due ?: if (days != null) today.toString() else null)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!isNew) PillButton(Sym.trash, if (confirmDelete) "Tap again to remove" else "Remove") {
                if (!confirmDelete) confirmDelete = true else scope.launch { board.removeTodo(todo.id); onDone() }
            }
            Spacer(Modifier.weight(1f))
            PillButton(Sym.check, if (isNew) "Add" else "Save", primary = true, enabled = todo.text.isNotBlank()) {
                scope.launch {
                    if (isNew) board.add(todo.copy(id = java.util.UUID.randomUUID().toString())) else board.saveTodo(todo)
                    onDone()
                }
            }
        }
    }
}

/** A chip that picks any day; shows the day once one is picked. */
@Composable
private fun DayChip(picked: LocalDate?, onPick: (LocalDate) -> Unit) {
    val context = LocalContext.current
    val today = rememberToday()
    FilterChip(picked?.let { shortDay(it) } ?: "Pick a day…", picked != null, Sym.calendar) {
        val d = picked ?: today
        DatePickerDialog(context, { _, y, m, day -> onPick(LocalDate.of(y, m + 1, day)) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
    }
}

private fun dayWord(d: LocalDate, today: LocalDate): String = when (ChronoUnit.DAYS.between(today, d)) {
    0L -> "today"
    1L -> "tomorrow"
    -1L -> "yesterday"
    in 2L..6L -> d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
    else -> shortDay(d)
}

private fun repeatWord(days: Int): String = when (days) {
    1 -> "every day"
    7 -> "every week"
    14 -> "every two weeks"
    30 -> "every month"
    else -> "every $days days"
}

private const val TODAY = "\u0000today"
private const val MAX_TEXT = 200

// ---------------------------------------------------------------- Overview card

/** The overview's Notes card: the newest post-its, small, and what is due first. */
@Composable
fun NotesCard(board: NoteBoard, onOpen: () -> Unit) {
    val g = GlassTheme.colors
    val notes by board.notes.collectAsStateWithLifecycle()
    val todos by board.todos.collectAsStateWithLifecycle()
    val today = rememberToday()
    val up = remember(notes, today) { board.current() }
    val open = remember(todos) { NoteBoard.order(todos).filter { it.done == null } }
    Column(
        Modifier.fillMaxWidth().pressable(onOpen).glass(TileShape).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SymIcon(Sym.sticker, filled = true, size = 20.dp, tint = g.fg)
            Column(Modifier.weight(1f)) {
                Text("Notes", style = MaterialTheme.typography.titleMedium, color = g.fg)
                Text(
                    listOf(
                        when (up.size) { 0 -> "No post-its"; 1 -> "1 post-it"; else -> "${up.size} post-its" },
                        when (open.size) { 0 -> "nothing to do"; 1 -> "1 to-do"; else -> "${open.size} to-dos" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = g.muted,
                    maxLines = 1,
                )
            }
        }
        if (up.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                up.take(2).forEach { n -> PostItCard(n, today, Modifier.weight(1f).height(84.dp), textSize = 20, footer = false, shadow = false) }
                if (up.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        open.take(3).forEach { t ->
            val due = t.dueDate()
            val late = due != null && !due.isAfter(today)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(18.dp).border(2.dp, if (late) Color(0xFFFFB84D) else g.muted, CircleShape))
                Text(t.text, style = MaterialTheme.typography.bodyLarge, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                due?.let { Text(dayWord(it, today), style = MaterialTheme.typography.labelMedium, color = if (late) Color(0xFFFFB84D) else g.muted, maxLines = 1) }
            }
        }
    }
}

// ---------------------------------------------------------------- Dark screen

/**
 * The dark screen's post-its: only these and a faint clock are lit, everything else is black.
 * They drift a few dp every couple of minutes so nothing sits in one place all night.
 */
@Composable
fun DarkScreenNotes(notes: List<PostIt>, clock: String, clockSize: Dp) {
    val today = LocalDate.now()
    val shift by produceState(0) {
        while (true) {
            delay(120_000)
            value = (value + 1) % 6
        }
    }
    val dx = listOf(0, 10, -8, 6, -12, 4)[shift].dp
    val dy = listOf(0, -6, 8, -10, 4, 10)[shift].dp
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 48.dp, vertical = 40.dp).offset(dx, dy),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Text(clock, color = Color.White.copy(alpha = 0.35f), fontSize = clockSize.value.sp, fontWeight = FontWeight.Light, fontFamily = app.homeydash.ui.theme.Inter)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val shown = notes.take(6)
            val perRow = if (shown.size <= 3) shown.size.coerceAtLeast(1) else 3
            val side = minOf((maxWidth - 40.dp * (perRow - 1)) / perRow, if (shown.size > 3) (maxHeight - 40.dp) / 2 else maxHeight, 300.dp)
            Column(verticalArrangement = Arrangement.spacedBy(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                shown.chunked(perRow).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                        row.forEach { n -> PostItCard(n, today, Modifier.size(side), textSize = (side.value / 7.5f).toInt().coerceIn(20, 40), shadow = false) }
                    }
                }
            }
        }
    }
}
