package app.homeydash.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.ZonedDateTime

/**
 * A kitchen timer started by voice, by hand or by a recipe. [group] is the recipe that
 * started it, so its timers show together. A paused timer keeps what was left in
 * [pausedLeftMs]; a timer that has run out stays, ringing since [ringingSince], until someone
 * stops it or adds time.
 */
@Serializable
data class KitchenTimer(
    val id: Long,
    val label: String?,
    val seconds: Int,
    val endsAt: Long,
    val group: String? = null,
    val pausedLeftMs: Long? = null,
    val ringingSince: Long? = null,
) {
    val paused: Boolean get() = pausedLeftMs != null
    val ringing: Boolean get() = ringingSince != null

    fun leftMs(now: Long): Long = pausedLeftMs ?: (endsAt - now).coerceAtLeast(0)

    /** How much of it is left, 1 at the start and 0 when it rings; for the ring around it. */
    fun progress(now: Long): Float = if (seconds <= 0) 0f else (leftMs(now) / (seconds * 1000f)).coerceIn(0f, 1f)

    fun name(fallback: String = "Timer"): String = label ?: fallback
}

/** The timers in the order the island shows them: ringing first, then the one that rings next. */
data class TimerGroup(val name: String?, val timers: List<KitchenTimer>)

/**
 * The kitchen timers on the tablet, kept in a small file so an update or restart in the
 * middle of cooking doesn't lose them. Plain logic with a clock, so it's tested without
 * Android; the assistant rings what [due] hands it.
 */
class TimerBoard(private val file: File?, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _timers = MutableStateFlow<List<KitchenTimer>>(emptyList())
    val timers: StateFlow<List<KitchenTimer>> = _timers.asStateFlow()
    private var nextId = 1L

    /**
     * Reads the timers saved before the app last stopped. One that ran out meanwhile rings
     * now if it did so in the last [STALE_MS]; older ones are dropped, since nobody is waiting.
     */
    suspend fun load() {
        val f = file ?: return
        val saved = withContext(Dispatchers.IO) {
            runCatching { if (f.exists()) json.decodeFromString(ListSerializer(KitchenTimer.serializer()), f.readText()) else emptyList() }
                .getOrDefault(emptyList())
        }
        val now = clock()
        _timers.value = saved.filter { it.paused || it.endsAt > now - STALE_MS }.map { if (it.ringing) it.copy(ringingSince = null) else it }
        nextId = (_timers.value.maxOfOrNull { it.id } ?: 0) + 1
    }

    fun start(seconds: Int, label: String?, group: String? = null): KitchenTimer {
        val timer = KitchenTimer(nextId++, label?.trim()?.takeIf { it.isNotEmpty() }?.replaceFirstChar(Char::uppercase), seconds, clock() + seconds * 1000L, group?.takeIf { it.isNotBlank() })
        change { it + timer }
        return timer
    }

    /**
     * The timer [name] means, matched loosely ("pastan" is Pasta, "ugnen" is Ugn), also by its
     * recipe. Without a name: the ringing one, else the only one, else the newest.
     */
    fun find(name: String?): KitchenTimer? {
        val all = _timers.value
        if (name.isNullOrBlank()) return all.firstOrNull { it.ringing } ?: all.lastOrNull()
        val q = NameMatch.tokens(name).filterNot { it in TIMER_WORDS }
        if (q.isEmpty()) return all.firstOrNull { it.ringing } ?: all.lastOrNull()
        fun score(t: KitchenTimer): Int {
            val words = NameMatch.tokens(t.label.orEmpty()) + NameMatch.tokens(t.group.orEmpty())
            return q.count { w -> words.any { NameMatch.sameWord(w, it) || it.startsWith(w) || w.startsWith(it) && it.length >= 3 } }
        }
        return all.map { it to score(it) }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    /** Stops one timer (and its ringing); returns it, or null when none matched. */
    fun cancel(name: String?): KitchenTimer? = find(name)?.also { t -> change { list -> list.filterNot { it.id == t.id } } }

    fun stop(id: Long) = change { list -> list.filterNot { it.id == id } }

    /** Stops every timer, or only those of the recipe [group]; returns how many. */
    fun cancelAll(group: String? = null): Int {
        val gone = _timers.value.filter { group.isNullOrBlank() || it.group != null && NameMatch.score(group, it.group) > 0.5 }
        change { list -> list.filterNot { t -> gone.any { it.id == t.id } } }
        return gone.size
    }

    fun rename(name: String?, newLabel: String): KitchenTimer? {
        val label = newLabel.trim().takeIf { it.isNotEmpty() } ?: return null
        // Answering "do you want to name it?" names the newest timer that has no name yet.
        val t = (if (name.isNullOrBlank()) _timers.value.lastOrNull { it.label == null } else null) ?: find(name) ?: return null
        return replace(t.id) { it.copy(label = label.replaceFirstChar(Char::uppercase)) }
    }

    fun renameId(id: Long, newLabel: String) = replace(id) { it.copy(label = newLabel.trim().takeIf { l -> l.isNotEmpty() }) }

    /**
     * Adds [seconds] (or takes away, when negative); never below one second. On a ringing
     * timer it starts again with that long left, like a snooze.
     */
    fun add(name: String?, seconds: Int): KitchenTimer? = find(name)?.let { addTo(it.id, seconds) }

    fun addTo(id: Long, seconds: Int): KitchenTimer? = replace(id) { t ->
        val now = clock()
        when {
            t.ringing -> t.copy(endsAt = now + seconds.coerceAtLeast(1) * 1000L, seconds = seconds.coerceAtLeast(1), ringingSince = null)
            t.paused -> t.copy(pausedLeftMs = (t.pausedLeftMs!! + seconds * 1000L).coerceAtLeast(1000), seconds = (t.seconds + seconds).coerceAtLeast(1))
            else -> t.copy(endsAt = maxOf(t.endsAt + seconds * 1000L, now + 1000), seconds = (t.seconds + seconds).coerceAtLeast(1))
        }
    }

    fun pause(name: String?, pause: Boolean): KitchenTimer? = find(name)?.let { pauseId(it.id, pause) }

    fun pauseId(id: Long, pause: Boolean): KitchenTimer? = replace(id) { t ->
        val now = clock()
        when {
            t.ringing -> t
            pause && !t.paused -> t.copy(pausedLeftMs = (t.endsAt - now).coerceAtLeast(1000))
            !pause && t.paused -> t.copy(endsAt = now + t.pausedLeftMs!!, pausedLeftMs = null)
            else -> t
        }
    }

    /** Timers that just ran out; they are marked ringing, so each is handed over once. */
    fun due(): List<KitchenTimer> {
        val now = clock()
        val done = _timers.value.filter { !it.paused && !it.ringing && it.endsAt <= now }
        if (done.isEmpty()) return emptyList()
        change { list -> list.map { t -> if (done.any { it.id == t.id }) t.copy(ringingSince = now) else t } }
        return done
    }

    /** Ringing timers nobody stopped within [RING_FOR_MS]; they are taken away. */
    fun expired(): List<KitchenTimer> {
        val now = clock()
        val old = _timers.value.filter { it.ringingSince != null && now - it.ringingSince >= RING_FOR_MS }
        if (old.isNotEmpty()) change { list -> list.filterNot { t -> old.any { it.id == t.id } } }
        return old
    }

    /** Ringing first, then by what rings next; paused ones last. */
    fun ordered(now: Long = clock()): List<KitchenTimer> = Timers.ordered(_timers.value, now)

    fun describe(): List<String> = Timers.describe(_timers.value, clock())

    private fun replace(id: Long, edit: (KitchenTimer) -> KitchenTimer): KitchenTimer? {
        var out: KitchenTimer? = null
        change { list -> list.map { if (it.id == id) edit(it).also { e -> out = e } else it } }
        return out
    }

    private fun change(edit: (List<KitchenTimer>) -> List<KitchenTimer>) {
        _timers.update(edit)
        save()
    }

    private fun save() {
        val f = file ?: return
        val list = _timers.value
        runCatching {
            f.parentFile?.mkdirs()
            val temp = File(f.parentFile, f.name + ".tmp")
            temp.writeText(json.encodeToString(ListSerializer(KitchenTimer.serializer()), list))
            if (!temp.renameTo(f)) { f.delete(); temp.renameTo(f) }
        }
    }

    companion object {
        /** A timer that ran out longer ago than this while the app was closed is dropped. */
        const val STALE_MS = 10 * 60_000L
        /** How long a timer keeps ringing when nobody stops it. */
        const val RING_FOR_MS = 5 * 60_000L
        /** How often it beeps again while ringing. */
        const val RING_EVERY_MS = 30_000L
        private val TIMER_WORDS = setOf("timer", "timern", "timers", "timerna", "klocka", "klockan", "nedrakning")
    }
}

/** Timer words shared by the island, the assistant and the memory graph. */
object Timers {
    fun ordered(timers: List<KitchenTimer>, now: Long): List<KitchenTimer> =
        timers.sortedWith(compareBy<KitchenTimer>({ !it.ringing }, { it.paused }, { it.leftMs(now) }))

    /** Recipe timers together under the recipe, the others last; each group in [ordered] order. */
    fun groups(timers: List<KitchenTimer>, now: Long): List<TimerGroup> {
        val sorted = ordered(timers, now)
        val named = sorted.filter { it.group != null }.groupBy { it.group }.map { (g, ts) -> TimerGroup(g, ts) }
        val rest = sorted.filter { it.group == null }
        return named + listOfNotNull(rest.takeIf { it.isNotEmpty() }?.let { TimerGroup(null, it) })
    }

    /** "9:05", or "1:02:30" past an hour. */
    fun clock(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** One line per timer for the assistant: "Pasta: 6 min 10 s left (Kanelbullar)". */
    fun describe(timers: List<KitchenTimer>, now: Long): List<String> = ordered(timers, now).mapIndexed { i, t ->
        val left = (t.leftMs(now) + 999) / 1000
        val state = when {
            t.ringing -> "ringing now"
            t.paused -> "paused with ${left / 60} min ${left % 60} s left"
            else -> "${left / 60} min ${left % 60} s left"
        }
        "${t.label ?: "Timer ${i + 1} (no name)"}: $state" + (t.group?.let { " (from the recipe $it)" } ?: "")
    }

    /** That [asker] named a timer [label] and set it for [seconds], counted like a habit. */
    fun habit(label: String, seconds: Int, asker: Asker?, at: ZonedDateTime): Fact = Fact(
        who = asker?.id ?: Memory.HOUSE,
        whoName = asker?.name.orEmpty(),
        subject = asker?.name ?: "Someone in the house",
        relation = "sets the timer",
        obj = "${label.trim().replaceFirstChar(Char::uppercase)} for ${minutesText(seconds)}",
        kind = FactKind.Habit,
        parts = mapOf(Memory.partOfDay(at) to 1),
    )

    /**
     * How long [asker] usually sets the timer called [label], from the habits in [facts]: the
     * length used most often, theirs before the house's. Null when it was never set.
     */
    fun usualSeconds(facts: List<Fact>, label: String, asker: String?): Int? {
        val q = NameMatch.tokens(label).filterNot { it == "timer" || it == "timern" }
        if (q.isEmpty()) return null
        val hits = facts.filter { f ->
            f.kind == FactKind.Habit && f.relation == "sets the timer" && (f.who == asker || f.who == Memory.HOUSE)
        }.mapNotNull { f ->
            val m = HABIT.matchEntire(f.obj) ?: return@mapNotNull null
            val name = NameMatch.tokens(m.groupValues[1])
            if (q.none { w -> name.any { NameMatch.sameWord(w, it) || w.startsWith(it) && it.length >= 3 } }) return@mapNotNull null
            val seconds = (m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null) * 60
            Triple(f, seconds.toInt(), if (f.who == asker && asker != null) 1 else 0)
        }
        return hits.maxWithOrNull(compareBy({ it.third }, { it.first.count }, { it.first.last }))?.second?.takeIf { it > 0 }
    }

    fun minutesText(seconds: Int): String {
        val m = seconds / 60.0
        return if (m == Math.floor(m)) "${m.toInt()} minutes" else "${"%.1f".format(java.util.Locale.ROOT, m)} minutes"
    }

    private val HABIT = Regex("""(.+) for ([\d.,]+) minutes""")
}
