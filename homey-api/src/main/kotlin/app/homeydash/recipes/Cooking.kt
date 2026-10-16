package app.homeydash.recipes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The recipe open on the tablet and where the cook is in it. [step] counts from 0. */
data class CookingState(
    val recipeId: String,
    val step: Int = 0,
    /** Ingredient lines ticked off, by index. */
    val checked: Set<Int> = emptySet(),
    /** The big step-by-step view is showing, rather than the whole recipe. */
    val cookMode: Boolean = false,
    /** Last time the cook moved along, to forget a recipe left open overnight. */
    val touched: Long = 0,
)

/** A time mentioned in a step, which can start a kitchen timer. */
data class StepTimer(val label: String, val seconds: Int)

/**
 * Which recipe is open and which step the cook is on. The recipe screen and the voice
 * assistant share it, so "next step" said aloud moves the screen too, and the assistant
 * always knows what is on the screen.
 */
class Cooking(private val book: RecipeBook, private val clock: () -> Long = System::currentTimeMillis) {
    private val _state = MutableStateFlow<CookingState?>(null)
    val state: StateFlow<CookingState?> = _state.asStateFlow()

    /** The open recipe, unless it was left untouched for [FORGET_AFTER_MS]. */
    fun current(): Pair<Recipe, CookingState>? {
        val s = _state.value ?: return null
        if (clock() - s.touched > FORGET_AFTER_MS) return null
        val r = book.get(s.recipeId) ?: return null
        return r to s
    }

    fun open(recipeId: String, cookMode: Boolean = false) {
        val s = _state.value
        // Opening the recipe that is already open keeps the place in it.
        _state.value = if (s != null && s.recipeId == recipeId && clock() - s.touched <= FORGET_AFTER_MS) {
            s.copy(cookMode = cookMode || s.cookMode, touched = clock())
        } else {
            CookingState(recipeId, cookMode = cookMode, touched = clock())
        }
    }

    fun close() {
        _state.value = null
    }

    fun setCookMode(on: Boolean) = change { it.copy(cookMode = on) }

    /** Goes to step [index] (from 0), kept within the recipe. */
    fun goTo(index: Int) = change { s ->
        val count = book.get(s.recipeId)?.steps?.size ?: 0
        s.copy(step = index.coerceIn(0, (count - 1).coerceAtLeast(0)))
    }

    fun next() = _state.value?.let { goTo(it.step + 1) }

    fun previous() = _state.value?.let { goTo(it.step - 1) }

    fun toggleIngredient(index: Int) = change { s -> s.copy(checked = if (index in s.checked) s.checked - index else s.checked + index) }

    private fun change(f: (CookingState) -> CookingState) {
        _state.value = _state.value?.let { f(it).copy(touched = clock()) }
    }

    /**
     * The open recipe in words for the assistant's instructions: the whole recipe, with the
     * step the cook is on marked, so it can answer "what's next?" or "how much flour?"
     * without a tool call. Null when no recipe is open.
     */
    fun describe(): String? {
        val (r, s) = current() ?: return null
        return buildString {
            append("A recipe is open on the tablet: \"").append(r.title).append('"')
            r.servings?.let { append(", ").append(it) }
            r.minutes?.let { append(", ").append(Text.duration(it)) }
            append(".\n")
            if (r.steps.isNotEmpty()) {
                append("The cook is on step ").append(s.step + 1).append(" of ").append(r.steps.size)
                append(if (s.cookMode) " (the big step view is showing)" else "").append(".\n")
            }
            append("Ingredients:\n")
            var section: String? = null
            r.ingredients.forEachIndexed { i, ing ->
                if (ing.section != section) {
                    section = ing.section
                    section?.let { append("  ").append(it).append(":\n") }
                }
                append("- ").append(ing.text.take(160))
                if (i in s.checked) append(" (ticked off)")
                append('\n')
            }
            append("Steps:\n")
            r.steps.forEachIndexed { i, step ->
                append(i + 1).append(". ")
                step.section?.let { append('[').append(it).append("] ") }
                append(step.text.take(MAX_STEP_CHARS))
                if (i == s.step) append("   <-- CURRENT STEP")
                append('\n')
            }
            if (r.notes.isNotBlank()) append("Notes: ").append(r.notes.take(600)).append('\n')
        }.take(MAX_CHARS)
    }

    companion object {
        const val FORGET_AFTER_MS = 8 * 60 * 60 * 1000L
        private const val MAX_STEP_CHARS = 400
        private const val MAX_CHARS = 9000

        private val TIME = Regex(
            """(\d+(?:[.,]\d+)?)(?:\s*(?:-|–|to|till|à)\s*(\d+(?:[.,]\d+)?))?\s*""" +
                """(h|hrs?|hours?|tim(?:me|mar)?|min(?:s|ute|utes|uter|ut)?|m|sek(?:under)?|sec(?:s|onds?)?)\b""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Times in a step, such as "bake for 25-30 minutes" or "låt koka 1 timme", as timers.
         * A range takes its upper end, since a timer that rings early is worse than one that
         * rings when it's surely done.
         */
        fun timers(step: String): List<StepTimer> = TIME.findAll(step).mapNotNull { m ->
            val (a, b, unit) = m.destructured
            val n = (b.ifEmpty { a }).replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
            val u = unit.lowercase()
            val seconds = when {
                u.startsWith("h") || u.startsWith("tim") -> n * 3600
                u.startsWith("s") -> n
                else -> n * 60
            }.toInt()
            if (seconds < 30 || seconds > 12 * 3600) return@mapNotNull null
            StepTimer(Text.clean(m.value), seconds)
        }.distinctBy { it.seconds }.toList()
    }
}
