package app.homeydash.recipes

import kotlinx.serialization.Serializable

/** One ingredient line. [section] groups lines, e.g. "Sauce", like a heading in the recipe. */
@Serializable
data class Ingredient(val text: String, val section: String? = null)

/** One instruction step. [section] groups steps, e.g. "Dough". */
@Serializable
data class Step(val text: String, val section: String? = null)

/**
 * A recipe kept on the tablet. Courses, categories and collections work like Recipe Keeper's:
 * a recipe can be in any number of each, and the list can be filtered by them.
 */
@Serializable
data class Recipe(
    val id: String,
    val title: String,
    val description: String = "",
    /** File name of the photo in the book's image folder. */
    val image: String? = null,
    /** Where the photo came from, so it can be fetched again. */
    val imageUrl: String? = null,
    val sourceUrl: String? = null,
    /** The site or author, e.g. "ICA" or "Arla". */
    val sourceName: String? = null,
    val servings: String? = null,
    val prepMinutes: Int? = null,
    val cookMinutes: Int? = null,
    val totalMinutes: Int? = null,
    val ingredients: List<Ingredient> = emptyList(),
    val steps: List<Step> = emptyList(),
    val courses: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val collections: List<String> = emptyList(),
    val notes: String = "",
    /** Per serving, e.g. "Calories" to "330". */
    val nutrition: Map<String, String> = emptyMap(),
    val favourite: Boolean = false,
    /** 0 (none) to 5 stars. */
    val rating: Int = 0,
    val added: Long = 0,
    val lastCooked: Long? = null,
    /** The recipe's id in Recipe Keeper, so importing a newer export updates it instead of adding a copy. */
    val externalId: String? = null,
    /** Words from the site (cuisine, keywords) that help search. */
    val keywords: List<String> = emptyList(),
) {
    /** Total time, or prep plus cook when the site gave no total. */
    val minutes: Int?
        get() = totalMinutes ?: listOfNotNull(prepMinutes, cookMinutes).takeIf { it.isNotEmpty() }?.sum()

    /** Courses, categories and collections together, for the filter chips. */
    val tags: List<String>
        get() = (courses + categories + collections).distinct()

    fun matches(query: String): Boolean {
        val q = Text.fold(query)
        if (q.isBlank()) return true
        val haystack = Text.fold(
            listOf(title, description, sourceName.orEmpty(), notes).joinToString(" ") + " " +
                (tags + keywords + ingredients.map { it.text }).joinToString(" "),
        )
        return q.split(' ').filter { it.isNotBlank() }.all { it in haystack }
    }
}

/** The usual courses, offered first when choosing one; any other name works too. */
val DefaultCourses = listOf("Breakfast", "Lunch", "Dinner", "Main dish", "Side dish", "Starter", "Dessert", "Snack", "Baking", "Drink", "Sauce")

/** Small text helpers shared by the parsers. */
object Text {
    private val SPACE = Regex("\\s+")
    private val NUMBERING = Regex("""^\s*(?:(?:step|steg)\s*)?\d{1,2}\s*[.):]\s+""", RegexOption.IGNORE_CASE)

    /** Collapses runs of white space (including non-breaking spaces) into one space. */
    fun clean(s: String): String = s.replace(' ', ' ').replace(SPACE, " ").trim()

    /** A step without the "1." or "Step 1:" the site put in front; the app numbers steps itself. */
    fun stripNumbering(s: String): String = NUMBERING.replace(s, "")

    /** Lower case without accents, for searching: "Kåldolmar" matches "kaldolmar" and "kål". */
    fun fold(s: String): String {
        val lower = s.lowercase()
        val sb = StringBuilder(lower.length)
        for (c in lower) {
            sb.append(
                when (c) {
                    'å', 'ä', 'á', 'à', 'â' -> 'a'
                    'ö', 'ø', 'ó', 'ò', 'ô' -> 'o'
                    'é', 'è', 'ê', 'ë' -> 'e'
                    'ü', 'ú', 'ù' -> 'u'
                    'í', 'ì', 'ï' -> 'i'
                    'ç' -> 'c'
                    'ñ' -> 'n'
                    else -> c
                },
            )
        }
        return clean(sb.toString())
    }

    /** Minutes from an ISO 8601 duration such as "PT1H30M" or "P0DT0H20M"; null when unreadable or zero. */
    fun isoMinutes(s: String?): Int? {
        val m = Regex("""^P(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$""", RegexOption.IGNORE_CASE)
            .find(s?.trim().orEmpty()) ?: return plainMinutes(s)
        val (d, h, min, sec) = m.destructured
        val total = (d.toDoubleOrNull() ?: 0.0) * 1440 + (h.toDoubleOrNull() ?: 0.0) * 60 + (min.toDoubleOrNull() ?: 0.0) + (sec.toDoubleOrNull() ?: 0.0) / 60
        return total.toInt().takeIf { it > 0 }
    }

    /** "1 hour 20 mins", "45 min", "1 tim 30 min" as minutes. */
    fun plainMinutes(s: String?): Int? {
        val t = s?.lowercase()?.trim().orEmpty()
        if (t.isEmpty()) return null
        val hours = Regex("""(\d+(?:[.,]\d+)?)\s*(?:h|hr|hrs|hour|hours|tim|timme|timmar)\b""").find(t)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        val mins = Regex("""(\d+)\s*(?:m|min|mins|minute|minutes|minut|minuter)\b""").find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val total = (hours * 60 + mins).toInt()
        if (total > 0) return total
        return t.toIntOrNull()?.takeIf { it > 0 }
    }

    /** "1 h 20 min" for showing a length of time. */
    fun duration(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}

/**
 * Ingredients and steps as plain text for editing: one per line, and a line starting
 * with "#" names the section for the lines under it.
 */
object RecipeText {
    fun ingredients(list: List<Ingredient>): String = lines(list.map { it.section to it.text })

    fun steps(list: List<Step>): String = lines(list.map { it.section to it.text })

    fun parseIngredients(text: String): List<Ingredient> = parse(text).map { (s, t) -> Ingredient(t, s) }

    fun parseSteps(text: String): List<Step> = parse(text).map { (s, t) -> Step(Text.stripNumbering(t), s) }

    private fun lines(items: List<Pair<String?, String>>): String = buildString {
        var section: String? = null
        for ((s, t) in items) {
            if (s != section) {
                if (isNotEmpty()) append('\n')
                // A lone "#" ends a section, for lines after it that have none.
                if (s != null) append("# ").append(s).append('\n') else append("#\n")
                section = s
            }
            append(t).append('\n')
        }
    }.trimEnd()

    private fun parse(text: String): List<Pair<String?, String>> {
        var section: String? = null
        return text.lines().mapNotNull { raw ->
            val line = Text.clean(raw)
            when {
                line.isEmpty() -> null
                line.startsWith("#") -> {
                    section = line.trimStart('#').trim().ifEmpty { null }
                    null
                }
                else -> section to line
            }
        }
    }
}
