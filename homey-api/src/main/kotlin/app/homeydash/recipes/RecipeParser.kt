package app.homeydash.recipes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/**
 * What was read from a web page: the recipe (without an id yet), plus the site's own
 * categories and keywords, offered as suggestions when the recipe is saved.
 */
data class ParsedRecipe(
    val recipe: Recipe,
    val siteCategories: List<String> = emptyList(),
    /** How the recipe was found: "json-ld", "microdata" or "page". */
    val via: String,
)

/**
 * Reads a recipe from any web page, the way Recipe Keeper and other recipe apps do:
 *
 * 1. schema.org `Recipe` in JSON-LD (`<script type="application/ld+json">`), which nearly
 *    every recipe site publishes for Google's recipe results (ICA, Arla, Köket, Coop,
 *    BBC Good Food, Allrecipes, WordPress recipe plugins…).
 * 2. schema.org `Recipe` microdata (`itemprop` attributes), used by older sites.
 * 3. The page itself: its title and photo, and the lists under headings such as
 *    "Ingredienser" and "Gör så här".
 */
object RecipeParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(html: String, url: String?): ParsedRecipe? {
        val doc = Jsoup.parse(html, url.orEmpty())
        return fromJsonLd(doc, url) ?: fromMicrodata(doc, url) ?: fromPage(doc, url)
    }

    // ---- JSON-LD ----

    private fun fromJsonLd(doc: Document, url: String?): ParsedRecipe? {
        val roots = doc.select("script[type*=ld+json]").mapNotNull { script ->
            val text = script.data().trim()
                .removePrefix("<!--").removeSuffix("-->")
                .removePrefix("//<![CDATA[").removeSuffix("//]]>").trim()
            runCatching { json.parseToJsonElement(text) }.getOrNull()
        }
        if (roots.isEmpty()) return null
        // Yoast and others link graph nodes by "@id": index them to follow those links.
        val ids = HashMap<String, JsonObject>()
        roots.forEach { indexIds(it, ids, 0) }
        val node = roots.firstNotNullOfOrNull { findRecipe(it, 0) } ?: return null
        return ParsedRecipe(recipeFromLd(node, ids, url, doc), siteCategories(node), via = "json-ld")
    }

    private fun indexIds(e: JsonElement, ids: MutableMap<String, JsonObject>, depth: Int) {
        if (depth > 8) return
        when (e) {
            is JsonObject -> {
                e.str("@id")?.let { if (e.size > 1) ids[it] = e }
                e.values.forEach { indexIds(it, ids, depth + 1) }
            }
            is JsonArray -> e.forEach { indexIds(it, ids, depth + 1) }
            else -> Unit
        }
    }

    private fun isRecipe(o: JsonObject): Boolean {
        val types = when (val t = o["@type"]) {
            is JsonPrimitive -> listOfNotNull(t.contentOrNull)
            is JsonArray -> t.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }
        return types.any { it.substringAfterLast('/').substringAfterLast(':').equals("Recipe", ignoreCase = true) }
    }

    private fun findRecipe(e: JsonElement, depth: Int): JsonObject? {
        if (depth > 8) return null
        return when (e) {
            is JsonObject -> if (isRecipe(e)) e else e.values.firstNotNullOfOrNull { findRecipe(it, depth + 1) }
            is JsonArray -> e.firstNotNullOfOrNull { findRecipe(it, depth + 1) }
            else -> null
        }
    }

    private fun recipeFromLd(o: JsonObject, ids: Map<String, JsonObject>, url: String?, doc: Document): Recipe {
        fun resolve(e: JsonElement?): JsonElement? =
            (e as? JsonObject)?.let { obj -> obj.str("@id")?.takeIf { obj.size == 1 }?.let { ids[it] } } ?: e

        val title = o.str("name")?.let(::html) ?: doc.title()
        val ingredients = (o["recipeIngredient"] ?: o["ingredients"]).strings().map(::html).filter { it.isNotBlank() }.map { Ingredient(it) }
        val steps = mutableListOf<Step>()
        instructions(resolve(o["recipeInstructions"]), null, steps, ids, 0)
        // Some sites glue all steps into one string ("…tin.Snap the chocolate…").
        if (steps.size == 1 && steps[0].text.length > 250) {
            val parts = steps[0].text.split(Regex("""(?<=[a-zåäö)][.!?])(?=[A-ZÅÄÖ])""")).map(Text::clean).filter { it.isNotBlank() }
            if (parts.size > 1) {
                val section = steps[0].section
                steps.clear()
                parts.forEach { steps += Step(it, section) }
            }
        }
        val nutrition = (resolve(o["nutrition"]) as? JsonObject)?.let { n ->
            NUTRIENTS.mapNotNull { (key, label) -> n.str(key)?.let { label to Text.clean(it) } }.toMap()
        }.orEmpty()
        return Recipe(
            id = "",
            title = Text.clean(title),
            description = o.str("description")?.let(::html).orEmpty(),
            imageUrl = image(resolve(o["image"]), ids)?.let { absolute(it, url) } ?: ogImage(doc, url),
            sourceUrl = url,
            sourceName = name(resolve(o["publisher"])) ?: siteName(doc) ?: name(resolve(o["author"])) ?: host(url),
            servings = servings(o["recipeYield"] ?: o["yield"]),
            prepMinutes = Text.isoMinutes(o.str("prepTime")),
            cookMinutes = Text.isoMinutes(o.str("cookTime")),
            totalMinutes = Text.isoMinutes(o.str("totalTime")),
            ingredients = ingredients,
            steps = steps,
            nutrition = nutrition,
            keywords = (splitList(o["keywords"]) + splitList(o["recipeCuisine"])).distinct().take(20),
        )
    }

    private fun siteCategories(o: JsonObject): List<String> =
        (splitList(o["recipeCategory"]) + splitList(o["recipeCuisine"])).map { it.replaceFirstChar(Char::uppercaseChar) }.distinct().take(8)

    /** Steps from a string, a list of strings, HowToStep, HowToSection or ItemList, keeping section names. */
    private fun instructions(e: JsonElement?, section: String?, out: MutableList<Step>, ids: Map<String, JsonObject>, depth: Int) {
        if (e == null || depth > 6) return
        when (e) {
            is JsonPrimitive -> e.contentOrNull?.let { text -> splitSteps(text).forEach { out += Step(it, section) } }
            is JsonArray -> e.forEach { instructions(it, section, out, ids, depth + 1) }
            is JsonObject -> {
                val obj = e.str("@id")?.takeIf { e.size == 1 }?.let { ids[it] } ?: e
                val type = obj.str("@type").orEmpty()
                val children = obj["itemListElement"]
                when {
                    type.contains("HowToSection", ignoreCase = true) || (children != null && type.contains("ItemList", ignoreCase = true)) ->
                        instructions(children, obj.str("name")?.let(::html)?.takeIf { it.isNotBlank() && !GENERIC_SECTION.matches(it) } ?: section, out, ids, depth + 1)
                    children != null -> instructions(children, section, out, ids, depth + 1)
                    else -> {
                        val text = obj.str("text") ?: obj.str("name") ?: obj.str("description")
                        text?.let { t -> splitSteps(t).forEach { out += Step(it, section) } }
                    }
                }
            }
        }
    }

    /** One instruction string can hold several steps, as paragraphs, list items or lines. */
    private fun splitSteps(text: String): List<String> {
        val parts = if (text.contains('<')) {
            val body = Jsoup.parseBodyFragment(text).body()
            val blocks = body.select("li, p")
            if (blocks.isNotEmpty()) blocks.map { it.text() } else listOf(body.text())
        } else {
            text.split(Regex("""\r?\n+""")).map { Jsoup.parse(it).text() }
        }
        return parts.map { Text.stripNumbering(Text.clean(it)) }.filter { it.isNotBlank() }
    }

    private fun image(e: JsonElement?, ids: Map<String, JsonObject>): String? = when (e) {
        is JsonPrimitive -> e.contentOrNull?.takeIf { it.isNotBlank() }
        is JsonObject -> {
            val o = e.str("@id")?.takeIf { e.size == 1 }?.let { ids[it] } ?: e
            o.str("url") ?: o.str("contentUrl") ?: (o["@id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("http") && looksLikeImage(it) }
        }
        // Sites list several sizes; the widest is best for the big photo.
        is JsonArray -> e.maxByOrNull { el -> ((el as? JsonObject)?.str("width")?.toIntOrNull() ?: 0) }
            ?.takeIf { el -> (el as? JsonObject)?.str("width") != null }
            ?.let { image(it, ids) }
            ?: e.firstNotNullOfOrNull { image(it, ids) }
        else -> null
    }

    private fun looksLikeImage(url: String) = Regex("""\.(jpe?g|png|webp|avif)(\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url)

    private fun name(e: JsonElement?): String? = when (e) {
        is JsonPrimitive -> e.contentOrNull?.let(Text::clean)?.takeIf { it.isNotBlank() }
        is JsonObject -> e.str("name")?.let(Text::clean)?.takeIf { it.isNotBlank() }
        is JsonArray -> e.firstNotNullOfOrNull { name(it) }
        else -> null
    }

    /** "4 portioner" over a bare "4" when a site lists both. */
    private fun servings(e: JsonElement?): String? {
        val all = e.strings().map(Text::clean).filter { it.isNotBlank() }
        return all.firstOrNull { it.any(Char::isLetter) } ?: all.firstOrNull()
    }

    private fun splitList(e: JsonElement?): List<String> =
        e.strings().flatMap { html(it).split(',') }.map(Text::clean).filter { it.isNotBlank() && it.length <= 40 }

    /** Section names that say nothing, like Arla's "Första instruktionen" and "Sista instruktionen". */
    private val GENERIC_SECTION = Regex(
        """^(första|nästa|sista|first|next|last|final)?\s*(instruktion(en|er|erna)?|instructions?|steps?|steg(et)?|method|directions|gör så här)\s*\d*$""",
        RegexOption.IGNORE_CASE,
    )

    // ---- Microdata ----

    private fun fromMicrodata(doc: Document, url: String?): ParsedRecipe? {
        val root = doc.select("[itemtype~=(?i)schema.org/Recipe]").firstOrNull() ?: return null
        fun props(name: String) = root.select("[itemprop=$name]")
        fun value(el: Element): String = when {
            el.hasAttr("content") -> el.attr("content")
            el.hasAttr("datetime") -> el.attr("datetime")
            el.tagName() == "img" -> el.absUrl("src")
            el.tagName() == "meta" -> el.attr("content")
            else -> el.text()
        }
        fun first(name: String) = props(name).firstOrNull()?.let(::value)?.let(Text::clean)?.takeIf { it.isNotBlank() }

        val ingredients = (props("recipeIngredient") + props("ingredients")).map { Text.clean(value(it)) }.filter { it.isNotBlank() }.map { Ingredient(it) }
        val steps = props("recipeInstructions").flatMap { el ->
            val items = el.select("li, p, [itemprop=text]")
            (if (items.isNotEmpty()) items.map { it.text() } else listOf(el.text())).map { Text.stripNumbering(Text.clean(it)) }
        }.filter { it.isNotBlank() }.distinct().map { Step(it) }
        if (ingredients.isEmpty() && steps.isEmpty()) return null
        val imageEl = props("image").firstOrNull()
        val recipe = Recipe(
            id = "",
            title = first("name") ?: doc.title(),
            description = first("description").orEmpty(),
            imageUrl = imageEl?.let { if (it.tagName() == "img") it.absUrl("src") else absolute(value(it), url) } ?: ogImage(doc, url),
            sourceUrl = url,
            sourceName = siteName(doc) ?: host(url),
            servings = first("recipeYield"),
            prepMinutes = Text.isoMinutes(first("prepTime")),
            cookMinutes = Text.isoMinutes(first("cookTime")),
            totalMinutes = Text.isoMinutes(first("totalTime")),
            ingredients = ingredients,
            steps = steps,
        )
        val categories = props("recipeCategory").map { Text.clean(value(it)) }.filter { it.isNotBlank() }.distinct()
        return ParsedRecipe(recipe, categories, via = "microdata")
    }

    // ---- The page itself ----

    private val INGREDIENT_HEADINGS = Regex("""^(ingredienser|ingredients?|du behöver|det här behöver du|ingredientes|zutaten)\b""", RegexOption.IGNORE_CASE)
    private val STEP_HEADINGS = Regex(
        """^(gör så här|så här gör du|instruktioner|tillagning|tillvägagångssätt|gör så|method|directions|instructions|preparation|steps|how to make( it)?|zubereitung)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** For blogs without structured data: the list after an "Ingredients" heading and the one after a "Method" heading. */
    private fun fromPage(doc: Document, url: String?): ParsedRecipe? {
        val headings = doc.select("h1, h2, h3, h4, h5, strong, b, p > span")
        fun listAfter(pattern: Regex): List<String> {
            val heading = headings.firstOrNull { pattern.containsMatchIn(Text.clean(it.text())) && it.text().length < 60 } ?: return emptyList()
            // The heading itself may sit inside a <p> or <strong>; start from its block.
            var el: Element? = heading
            while (el != null && el.nextElementSibling() == null && el.parent() != null && el.parent() != doc.body()) el = el.parent()
            var next = el?.nextElementSibling()
            var hops = 0
            while (next != null && hops < 6) {
                val items = next.select("li")
                if (items.isNotEmpty()) return items.map { Text.clean(it.text()) }.filter { it.isNotBlank() }
                if (next.tagName().matches(Regex("h[1-4]"))) break
                next = next.nextElementSibling()
                hops++
            }
            return emptyList()
        }
        val ingredients = listAfter(INGREDIENT_HEADINGS)
        val steps = listAfter(STEP_HEADINGS).map(Text::stripNumbering)
        if (ingredients.isEmpty() && steps.isEmpty()) return null
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1")?.text() ?: doc.title()
        val recipe = Recipe(
            id = "",
            title = Text.clean(title),
            description = doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.let(Text::clean).orEmpty(),
            imageUrl = ogImage(doc, url),
            sourceUrl = url,
            sourceName = siteName(doc) ?: host(url),
            ingredients = ingredients.map { Ingredient(it) },
            steps = steps.map { Step(it) },
        )
        return ParsedRecipe(recipe, via = "page")
    }

    // ---- Helpers ----

    private fun ogImage(doc: Document, url: String?): String? =
        doc.selectFirst("meta[property=og:image], meta[name=og:image], meta[name=twitter:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }?.let { absolute(it, url) }

    private fun siteName(doc: Document): String? =
        doc.selectFirst("meta[property=og:site_name]")?.attr("content")?.let(Text::clean)?.takeIf { it.isNotBlank() }

    fun host(url: String?): String? = runCatching { URI(url!!).host?.removePrefix("www.") }.getOrNull()

    private fun absolute(link: String, base: String?): String = runCatching {
        if (base == null) link else URI(base).resolve(link.trim().replace(" ", "%20")).toString()
    }.getOrDefault(link)

    /** Text with HTML tags and entities (`&amp;`, `&#188;`) turned into plain text. */
    private fun html(s: String): String = if (s.contains('<') || s.contains('&')) Text.clean(Jsoup.parse(s).text()) else Text.clean(s)

    private fun JsonObject.str(key: String): String? = when (val v = this[key]) {
        is JsonPrimitive -> v.contentOrNull
        is JsonArray -> (v.firstOrNull() as? JsonPrimitive)?.contentOrNull
        else -> null
    }

    private fun JsonElement?.strings(): List<String> = when (this) {
        is JsonPrimitive -> listOfNotNull(contentOrNull)
        is JsonArray -> flatMap { it.strings() }
        is JsonObject -> listOfNotNull((this["name"] as? JsonPrimitive)?.contentOrNull ?: (this["text"] as? JsonPrimitive)?.contentOrNull)
        else -> emptyList()
    }

    private val NUTRIENTS = listOf(
        "calories" to "Calories", "proteinContent" to "Protein", "fatContent" to "Fat", "saturatedFatContent" to "Saturated fat",
        "carbohydrateContent" to "Carbohydrate", "sugarContent" to "Sugar", "fiberContent" to "Fibre", "sodiumContent" to "Sodium",
    )
}
