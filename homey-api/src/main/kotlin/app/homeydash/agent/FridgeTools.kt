package app.homeydash.agent

import app.homeydash.fridge.DateKind
import app.homeydash.fridge.Estimate
import app.homeydash.fridge.FoodRules
import app.homeydash.fridge.Fridge
import app.homeydash.fridge.FridgeItem
import app.homeydash.fridge.Place
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The assistant's fridge tools: what's in the fridge, freezer and pantry and when it goes
 * off, and adding, changing and taking things out by voice.
 */
class FridgeTools(
    private val fridge: Fridge,
    /** Shows the fridge page on the dashboard. */
    private val show: () -> Unit = {},
) {
    fun specs(): List<ToolSpec> = listOf(
        tool(
            "fridge_contents",
            "What is in the fridge, freezer and pantry, soonest to go off first, with bought, opened and eat-by dates. " +
                "Use it for questions like what's in the fridge, do we have milk, what goes off soon, or what to cook from what we have.",
        ) {
            string("place", "Optional: fridge, freezer or pantry")
            string("query", "Optional words to look for, e.g. mjölk or chicken")
            number("within_days", "Optional: only things to eat within this many days (0 = today or already past)")
        },
        tool("add_to_fridge", "Add food to the fridge (or freezer or pantry). Its shelf life is worked out from what it is.") {
            string("name", "What it is, as the user said it, e.g. köttfärs or Arla mellanmjölk")
            number("quantity", "How many packs; default 1")
            string("place", "fridge, freezer or pantry; default by kind")
            string("category", "Optional kind, e.g. mince, poultry, milk, cheese, leftovers, vegetables")
            string("date", "Optional date printed on the pack, yyyy-mm-dd")
            string("date_kind", "Optional: best_before (bäst före) or use_by (sista förbrukningsdag)")
            string("bought", "Optional purchase day, yyyy-mm-dd; default today")
            required("name")
        },
        tool("update_fridge_item", "Change something in the fridge: mark it opened, move it to the freezer, set its date or how many there are.") {
            string("item", "The item's name as listed by fridge_contents")
            string("opened", "true when it has been opened")
            string("place", "Move it to fridge, freezer or pantry")
            string("date", "New date printed on the pack, yyyy-mm-dd")
            number("quantity", "New count")
            required("item")
        },
        tool("remove_from_fridge", "Take something out because it was eaten, used up or thrown away.") {
            string("item", "The item's name as listed by fridge_contents")
            number("quantity", "How many to take out; leave empty for all of them")
            required("item")
        },
    )

    val names: Set<String> = setOf("fridge_contents", "add_to_fridge", "update_fridge_item", "remove_from_fridge")

    suspend fun run(call: ToolCall): ToolResult? {
        val a = call.arguments
        return when (call.name) {
            "fridge_contents" -> contents(a.str("place"), a.str("query"), a.num("within_days"))
            "add_to_fridge" -> add(a)
            "update_fridge_item" -> update(a)
            "remove_from_fridge" -> remove(a.str("item"), a.num("quantity"))
            else -> null
        }
    }

    private fun contents(place: String, query: String, within: Double?): ToolResult {
        val all = fridge.sorted(place(place))
        if (all.isEmpty()) return ok(
            if (place.isBlank()) "Nothing is tracked in the fridge yet. Things are added on the Fridge page by scanning their barcodes, or by asking you."
            else "Nothing is tracked in the ${place.lowercase()}.",
        )
        var shown = all
        if (query.isNotBlank()) shown = shown.filter { (item, _) -> score(query, item) > 0 }
        within?.let { d -> shown = shown.filter { it.second.daysLeft <= d.toLong() } }
        if (shown.isEmpty()) return ok("Nothing matches. In there: " + all.take(20).joinToString { it.first.name } + ".")
        return ok("Today is ${fridge.today()}.\n" + shown.take(40).joinToString("\n") { (item, e) -> line(item, e) } +
            if (shown.size > 40) "\n…and ${shown.size - 40} more." else "")
    }

    private suspend fun add(a: JsonObject): ToolResult {
        val name = a.str("name").trim()
        if (name.isBlank()) return ok("Say what to add.")
        val category = a.str("category").takeIf { it.isNotBlank() }?.let { FoodRules.find(it) } ?: FoodRules.classify(name)
        val date = a.str("date").takeIf { it.isNotBlank() }?.let { parseDate(it) ?: return ok("The date must be yyyy-mm-dd.") }
        val bought = a.str("bought").takeIf { it.isNotBlank() }?.let { parseDate(it) ?: return ok("The bought date must be yyyy-mm-dd.") } ?: fridge.today()
        val item = fridge.add(
            FridgeItem(
                name = name.replaceFirstChar { it.titlecase(Locale.ROOT) },
                category = category.key,
                place = place(a.str("place")) ?: category.place,
                quantity = a.num("quantity")?.toInt()?.coerceIn(1, 99) ?: 1,
                bought = bought.toString(),
                date = date?.toString(),
                dateKind = when (a.str("date_kind").lowercase()) {
                    "use_by", "useby", "sista förbrukningsdag" -> DateKind.UseBy
                    "best_before", "bestbefore", "bäst före" -> DateKind.BestBefore
                    else -> null
                },
            ),
        )
        show()
        return ok("Added ${item.name} (${category.en}) to the ${item.place.en.lowercase()}. " + line(item, fridge.estimate(item)))
    }

    private suspend fun update(a: JsonObject): ToolResult {
        val item = find(a.str("item")) ?: return notFound(a.str("item"))
        var changed = item
        if (a.str("opened").equals("true", ignoreCase = true)) changed = changed.copy(opened = fridge.today().toString())
        if (a.str("opened").equals("false", ignoreCase = true)) changed = changed.copy(opened = null)
        a.str("date").takeIf { it.isNotBlank() }?.let { changed = changed.copy(date = (parseDate(it) ?: return ok("The date must be yyyy-mm-dd.")).toString()) }
        a.num("quantity")?.let { changed = changed.copy(quantity = it.toInt().coerceAtLeast(1)) }
        fridge.save(changed)
        place(a.str("place"))?.let { fridge.move(item.id, it) }
        val now = fridge.get(item.id) ?: changed
        show()
        return ok("Updated. " + line(now, fridge.estimate(now)))
    }

    private suspend fun remove(name: String, quantity: Double?): ToolResult {
        val item = find(name) ?: return notFound(name)
        val n = quantity?.toInt()
        if (n == null || n >= item.quantity) {
            fridge.remove(item.id)
            return ok("Took ${item.name} out.")
        }
        repeat(n.coerceAtLeast(1)) { fridge.useOne(item.id) }
        return ok("Took $n ${item.name} out; ${item.quantity - n} left.")
    }

    private fun line(item: FridgeItem, e: Estimate): String = buildString {
        append(item.name)
        item.brand?.let { append(" (").append(it).append(')') }
        if (item.quantity > 1) append(" ×").append(item.quantity)
        item.amount?.let { append(", ").append(it) }
        append(" · ").append(item.place.en.lowercase())
        append(" · ").append(item.categoryInfo.en)
        append(" · bought ").append(day(item.boughtDate()))
        item.openedDate()?.let { append(" · opened ").append(day(it)) }
        append(" · eat by ").append(day(e.date)).append(" (").append(e.label()).append(when (e.basis) {
            app.homeydash.fridge.Basis.Printed -> if (e.strict) ", use-by on pack" else ", best-before on pack"
            app.homeydash.fridge.Basis.Guessed -> ", estimated"
            app.homeydash.fridge.Basis.Opened -> ", since opening"
            app.homeydash.fridge.Basis.Frozen -> ", frozen quality"
            app.homeydash.fridge.Basis.Thawed -> ", since thawing"
        }).append(')')
        e.advice()?.let { append(". ").append(it) }
    }

    private fun day(d: LocalDate?): String = d?.format(DAY) ?: "?"

    private fun find(name: String): FridgeItem? {
        val items = fridge.items.value
        if (name.isBlank()) return null
        items.firstOrNull { it.id == name }?.let { return it }
        val ranked = NameMatch.rank(name, items) { score(name, it) }
        if (ranked.isEmpty()) return null
        // The same thing bought twice is fine to treat as one: take the one to eat first.
        val top = ranked.first().second
        val best = ranked.filter { it.second >= top - 0.01 }.map { it.first }
        return if (best.map { NameMatch.normalize(it.name) }.distinct().size == 1) best.minBy { fridge.estimate(it).date } else null
    }

    private fun score(query: String, item: FridgeItem): Double = maxOf(
        NameMatch.score(query, item.name),
        NameMatch.score(query, item.categoryInfo.sv) * 0.8,
        NameMatch.score(query, item.categoryInfo.en) * 0.8,
    )

    private fun notFound(name: String): ToolResult {
        val near = NameMatch.rank(name, fridge.items.value) { score(name, it) }.take(6).map { it.first.name }.distinct()
        return ok(
            if (near.isEmpty()) "There's no $name in the fridge list. Use fridge_contents to see what's there."
            else "\"$name\" could be: ${near.joinToString()}. Ask which one.",
        )
    }

    private fun place(text: String): Place? = when (text.trim().lowercase()) {
        "fridge", "kyl", "kylen", "kylskåp", "kylskåpet" -> Place.Fridge
        "freezer", "frys", "frysen" -> Place.Freezer
        "pantry", "skafferi", "skafferiet", "cupboard", "skåp" -> Place.Pantry
        else -> null
    }

    private fun parseDate(s: String): LocalDate? = runCatching { LocalDate.parse(s.trim().take(10)) }.getOrNull()

    private fun ok(text: String) = ToolResult(text)

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

    private companion object {
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    }
}
