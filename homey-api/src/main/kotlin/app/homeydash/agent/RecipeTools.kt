package app.homeydash.agent

import app.homeydash.recipes.Cooking
import app.homeydash.recipes.Recipe
import app.homeydash.recipes.RecipeBook
import app.homeydash.recipes.Text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The assistant's recipe tools: find recipes, open one on the tablet, read it, and move
 * through its steps. The screen and the assistant share [cooking], so the assistant
 * always knows which recipe is up and which step the cook is on.
 */
class RecipeTools(
    private val book: RecipeBook,
    private val cooking: Cooking,
    /** Shows the recipe page on the dashboard. */
    private val show: () -> Unit,
) {
    fun specs(): List<ToolSpec> = listOf(
        tool("find_recipes", "Search the recipes saved on the tablet by name, ingredient or category. Leave both empty to list them.") {
            string("query", "Words to look for, e.g. lasagne or kyckling")
            string("category", "Optional category or course, e.g. Dessert")
        },
        tool("open_recipe", "Show a saved recipe on the tablet's screen and start cooking it from step 1 (or keep the place if it is already open).") {
            string("recipe", "The recipe's name as listed by find_recipes")
            string("cook_mode", "true to show the big step-by-step view")
            required("recipe")
        },
        tool("read_recipe", "The full recipe: ingredients, steps and notes. Without a name it reads the recipe open on the tablet.") {
            string("recipe", "Optional recipe name; default the open recipe")
        },
        tool(
            "recipe_step",
            "Move through the open recipe's steps on the screen and get the step to tell the user: next, previous, current, first, last, or go_to a step number.",
        ) {
            string("action", "next, previous, current, first, last or go_to")
            number("step", "Step number for go_to, counting from 1")
            required("action")
        },
        tool("close_recipe", "Close the recipe open on the tablet, e.g. when the cooking is done.") {},
    )

    val names: Set<String> = setOf("find_recipes", "open_recipe", "read_recipe", "recipe_step", "close_recipe")

    fun run(call: ToolCall): ToolResult? {
        val a = call.arguments
        return when (call.name) {
            "find_recipes" -> find(a.str("query"), a.str("category"))
            "open_recipe" -> open(a.str("recipe"), a.str("cook_mode").equals("true", ignoreCase = true))
            "read_recipe" -> read(a.str("recipe"))
            "recipe_step" -> step(a.str("action"), a.num("step"))
            "close_recipe" -> {
                cooking.close()
                ok("Closed the recipe.")
            }
            else -> null
        }
    }

    /** The open recipe's title, so its timers show together under it. */
    fun openName(): String? = cooking.current()?.first?.title

    /** Every time named in the open recipe's steps, with its step number (from 1). */
    fun stepTimers(): List<Pair<Int, app.homeydash.recipes.StepTimer>> {
        val (r, _) = cooking.current() ?: return emptyList()
        return r.steps.flatMapIndexed { i, step -> Cooking.timers(step.text).map { (i + 1) to it } }
    }

    /**
     * "Next step", "nästa steg", "what's next", "step 4" and the like while a recipe is open,
     * done straight away without the AI so the kitchen gets an instant answer. Null when
     * [text] isn't such a command or no recipe is open.
     */
    fun quick(text: String, answerSwedish: Boolean? = null): String? {
        if (cooking.current() == null) return null
        val command = CookingCommand.parse(text) ?: return null
        // [answerSwedish] is the language answers are given in, when that is set (TARS: English).
        val swedish = answerSwedish ?: (Confirmation.looksSwedish(text) || command.swedish)
        val before = cooking.state.value?.step
        when (command.action) {
            "next" -> cooking.next()
            "previous" -> cooking.previous()
            "first" -> cooking.goTo(0)
            "last" -> cooking.goTo(Int.MAX_VALUE)
            "go_to" -> cooking.goTo((command.step ?: 1) - 1)
        }
        if (command.action != "current") cooking.setCookMode(true)
        show()
        return spoken(swedish, stuckAtEnd = command.action == "next" && cooking.state.value?.step == before)
    }

    private fun find(query: String, category: String): ToolResult {
        val all = book.recipes.value
        if (all.isEmpty()) return ok("No recipes are saved yet. They can be added on the Recipes page, from a link or a Recipe Keeper export.")
        val hits = all.filter { r ->
            (category.isBlank() || r.tags.any { Text.fold(it) == Text.fold(category) || Text.fold(it).contains(Text.fold(category)) }) && r.matches(query)
        }
        if (hits.isEmpty()) return ok("No saved recipe matches. Saved recipes include: " + all.take(15).joinToString { it.title } + ".")
        return ok(hits.take(25).joinToString("\n") { r ->
            listOfNotNull(
                r.title,
                r.tags.takeIf { it.isNotEmpty() }?.joinToString(", "),
                r.minutes?.let(Text::duration),
                "favourite".takeIf { r.favourite },
            ).joinToString(" · ")
        } + if (hits.size > 25) "\n…and ${hits.size - 25} more." else "")
    }

    private fun open(name: String, cookMode: Boolean): ToolResult {
        val r = byName(name) ?: return notFound(name)
        cooking.open(r.id, cookMode)
        show()
        val s = cooking.state.value?.step ?: 0
        return ok(
            "Opened ${r.title} on the screen: ${r.ingredients.size} ingredients, ${r.steps.size} steps" +
                (r.minutes?.let { ", ${Text.duration(it)}" } ?: "") + ". " +
                (r.steps.getOrNull(s)?.let { "Step ${s + 1}: ${it.text}" } ?: ""),
        )
    }

    private fun read(name: String): ToolResult {
        val r = if (name.isBlank()) cooking.current()?.first ?: return ok("No recipe is open. Use find_recipes and open_recipe.")
        else byName(name) ?: return notFound(name)
        return ok(buildString {
            append(r.title)
            r.servings?.let { append(" (").append(it).append(')') }
            append("\nIngredients:\n")
            r.ingredients.forEach { append("- ").append(it.section?.let { s -> "[$s] " } ?: "").append(it.text).append('\n') }
            append("Steps:\n")
            r.steps.forEachIndexed { i, s -> append(i + 1).append(". ").append(s.text).append('\n') }
            if (r.notes.isNotBlank()) append("Notes: ").append(r.notes)
        }.take(8000))
    }

    private fun step(action: String, number: Double?): ToolResult {
        val (r, _) = cooking.current() ?: return ok("No recipe is open. Use open_recipe first.")
        if (r.steps.isEmpty()) return ok("${r.title} has no steps written down.")
        val act = action.trim().lowercase()
        val before = cooking.state.value?.step
        when (act) {
            "next", "nästa", "forward" -> cooking.next()
            "previous", "back", "prev", "föregående" -> cooking.previous()
            "first", "start", "restart" -> cooking.goTo(0)
            "last", "end" -> cooking.goTo(r.steps.lastIndex)
            "go_to", "goto", "go to" -> cooking.goTo(((number ?: 1.0).toInt()) - 1)
            "current", "repeat", "" -> Unit
            else -> return ok("Use next, previous, current, first, last or go_to.")
        }
        if (act != "current" && act != "repeat" && act.isNotEmpty()) cooking.setCookMode(true)
        show()
        return ok(spoken(swedish = false, stuckAtEnd = act in setOf("next", "nästa", "forward") && cooking.state.value?.step == before))
    }

    /** The step the cook is on, and what comes after it, ready to be said aloud. */
    private fun spoken(swedish: Boolean, stuckAtEnd: Boolean): String {
        val (r, s) = cooking.current() ?: return if (swedish) "Inget recept är öppet." else "No recipe is open."
        if (r.steps.isEmpty()) return if (swedish) "${r.title} har inga steg." else "${r.title} has no steps written down."
        val i = s.step
        val step = r.steps[i].text
        val last = i == r.steps.lastIndex
        if (stuckAtEnd) return if (swedish) "Det var sista steget. Smaklig måltid!" else "That was the last step. Enjoy your meal!"
        return buildString {
            if (swedish) {
                append("Steg ").append(i + 1).append(" av ").append(r.steps.size).append(": ").append(step)
                if (last) append(" Det är sista steget.")
            } else {
                append("Step ").append(i + 1).append(" of ").append(r.steps.size).append(": ").append(step)
                if (last) append(" That's the last step.")
            }
            r.steps.getOrNull(i + 1)?.let { next ->
                append(if (swedish) " Sedan: " else " After that: ").append(next.text.take(120))
                if (next.text.length > 120) append("…")
            }
        }
    }

    private fun byName(name: String): Recipe? {
        val all = book.recipes.value
        val q = Text.fold(name)
        if (q.isBlank()) return null
        return all.firstOrNull { it.id == name }
            ?: all.firstOrNull { Text.fold(it.title) == q }
            ?: all.firstOrNull { Text.fold(it.title).contains(q) }
            ?: all.filter { r -> q.split(' ').filter { it.length > 2 }.let { words -> words.isNotEmpty() && words.all { w -> Text.fold(r.title).contains(w) } } }.singleOrNull()
            ?: all.filter { it.matches(name) }.singleOrNull()
    }

    private fun notFound(name: String): ToolResult {
        val near = book.recipes.value.filter { r -> Text.fold(name).split(' ').any { w -> w.length > 2 && Text.fold(r.title).contains(w) } }.take(6)
        return ok(
            if (near.isEmpty()) "There's no saved recipe called $name. Use find_recipes to see what's saved."
            else "\"$name\" could be: ${near.joinToString { it.title }}. Ask which one.",
        )
    }

    private fun ok(text: String) = ToolResult(text)

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
}

/** A spoken "next step" style command, understood without the AI. */
data class CookingCommand(val action: String, val step: Int? = null, val swedish: Boolean = false) {
    companion object {
        private val NUMBERS = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
            "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6,
            "ett" to 1, "en" to 1, "två" to 2, "tre" to 3, "fyra" to 4, "fem" to 5, "sex" to 6, "sju" to 7, "åtta" to 8, "nio" to 9, "tio" to 10,
            "första" to 1, "andra" to 2, "tredje" to 3, "fjärde" to 4, "femte" to 5, "sjätte" to 6,
        )
        private val FILLER = Regex("""^(ok(ej|ay)?|hej|hey|nova|och|and|so|så|then|sen|da|då|men|but)[,.!]?\s+""", RegexOption.IGNORE_CASE)

        fun parse(text: String): CookingCommand? {
            var t = text.lowercase().trim().trimEnd('.', '!', '?').replace(Regex("""[,]"""), " ").replace(Regex("""\s+"""), " ")
            repeat(3) { t = FILLER.replace(t, "") }
            t = t.removeSuffix(" please").removeSuffix(" tack").trim()
            if (t.split(' ').size > 8) return null
            return when {
                t.matches(Regex("""(next|next step|go on|continue|done|okay next|what'?s next|what now|what do i do (now|next)|then what|what'?s the next step|go to the next step)""")) ->
                    CookingCommand("next")
                t.matches(Regex("""(nästa|nästa steg|fortsätt|klar|färdig|vad (är|blir) nästa( steg)?|vad gör jag (nu|sen|sedan)|och sen|vad händer sen|gå till nästa( steg)?|nästa tack)""")) ->
                    CookingCommand("next", swedish = true)
                t.matches(Regex("""(back|go back|previous|previous step|step back|one step back|go back a step)""")) -> CookingCommand("previous")
                t.matches(Regex("""(tillbaka|bakåt|föregående|föregående steg|förra steget|backa|gå tillbaka( ett steg)?|ett steg tillbaka)""")) ->
                    CookingCommand("previous", swedish = true)
                t.matches(Regex("""(repeat|repeat that|say that again|again|what step am i on|which step am i on|where am i|what was that|current step|read the step)""")) ->
                    CookingCommand("current")
                t.matches(Regex("""(upprepa|igen|säg igen|säg det igen|vilket steg är jag på|var är jag|vad sa du|vad var det|läs steget|nuvarande steg)""")) ->
                    CookingCommand("current", swedish = true)
                t.matches(Regex("""(start over|from the (start|beginning)|first step)""")) -> CookingCommand("first")
                t.matches(Regex("""(börja om|från början|första steget)""")) -> CookingCommand("first", swedish = true)
                else -> {
                    Regex("""^(?:go to |gå till |show |visa )?(step|steg) (\S+)$""").find(t)?.let { m ->
                        val n = m.groupValues[2].toIntOrNull() ?: NUMBERS[m.groupValues[2]] ?: return null
                        CookingCommand("go_to", n, swedish = m.groupValues[1] == "steg")
                    }
                }
            }
        }
    }
}
