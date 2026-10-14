package app.homeydash.recipes

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.util.zip.ZipFile

/** A recipe from a Recipe Keeper export, and the path of its photo inside the export (e.g. "images/<id>_0.jpg"). */
data class KeeperRecipe(val recipe: Recipe, val photo: String?)

/**
 * Reads Recipe Keeper's export: a .zip with `recipes.html` and an `images/` folder.
 * Each recipe is a `div.recipe-details` marked up with schema.org-style `itemprop`s
 * (`recipeId`, `recipeIsFavourite`, `recipeRating`, `name`, `recipeCourse`,
 * `recipeCategory`, `recipeCollection`, `recipeSource`, `recipeYield`, `prepTime`,
 * `cookTime`, `recipeIngredients`, `recipeDirections`, `recipeNotes`, `recipeNut…`).
 */
object RecipeKeeperImport {
    fun parseHtml(html: String): List<KeeperRecipe> =
        Jsoup.parse(html).select("div.recipe-details").mapNotNull(::parseRecipe)

    /** Reads the export zip; the photos stay in the zip until [photo] reads them. */
    fun parseZip(zip: ZipFile): List<KeeperRecipe> {
        val entry = zip.entries().asSequence().firstOrNull { !it.isDirectory && it.name.substringAfterLast('/').equals("recipes.html", ignoreCase = true) }
            ?: throw IllegalArgumentException("This zip has no recipes.html. Export from Recipe Keeper with Settings → Export, then pick the .zip.")
        val base = entry.name.substringBeforeLast('/', "")
        val html = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        return parseHtml(html).map { r -> r.copy(photo = r.photo?.let { if (base.isEmpty()) it else "$base/$it" }) }
    }

    /** The photo's bytes from the export, or null when it isn't there. */
    fun photo(zip: ZipFile, path: String): ByteArray? {
        val clean = path.removePrefix("./")
        if (clean.contains("..")) return null
        val entry = zip.getEntry(clean) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    fun parseZip(file: File): Pair<ZipFile, List<KeeperRecipe>> {
        val zip = ZipFile(file)
        return try {
            zip to parseZip(zip)
        } catch (e: Exception) {
            zip.close()
            throw e
        }
    }

    private fun parseRecipe(div: Element): KeeperRecipe? {
        fun meta(name: String) = div.select("meta[itemprop=$name]").map { it.attr("content").trim() }.filter { it.isNotEmpty() }
        fun text(name: String) = div.selectFirst("[itemprop=$name]")?.text()?.let(Text::clean)?.takeIf { it.isNotEmpty() }
        fun paragraphs(name: String): List<String> =
            div.selectFirst("[itemprop=$name]")?.select("p")?.map { Text.clean(it.text()) }?.filter { it.isNotEmpty() }.orEmpty()

        val title = text("name") ?: return null
        val source = div.selectFirst("[itemprop=recipeSource]")
        val sourceUrl = source?.selectFirst("a[href]")?.attr("href")?.takeIf { it.startsWith("http") }
            ?: source?.text()?.trim()?.takeIf { it.startsWith("http") }
        val sourceText = source?.text()?.let(Text::clean)?.takeIf { it.isNotEmpty() && !it.startsWith("http") }
        val nutrition = div.select("meta[itemprop^=recipeNut]").associate { m ->
            m.attr("itemprop").removePrefix("recipeNut").replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().replaceFirstChar(Char::uppercaseChar) to m.attr("content").trim()
        }.filterValues { it.isNotEmpty() && it != "0" }
        val photo = (div.selectFirst("img.recipe-photo") ?: div.selectFirst("img[itemprop=photo0]"))?.attr("src")?.takeIf { it.isNotBlank() }
        val recipe = Recipe(
            id = "",
            title = title,
            externalId = meta("recipeId").firstOrNull(),
            favourite = meta("recipeIsFavourite").firstOrNull().equals("true", ignoreCase = true),
            rating = meta("recipeRating").firstOrNull()?.toIntOrNull()?.coerceIn(0, 5) ?: 0,
            courses = div.select("[itemprop=recipeCourse]").map { Text.clean(it.text()) }.filter { it.isNotEmpty() }.distinct(),
            categories = meta("recipeCategory").distinct(),
            collections = meta("recipeCollection").distinct(),
            sourceUrl = sourceUrl,
            sourceName = sourceText ?: RecipeParser.host(sourceUrl),
            servings = text("recipeYield"),
            prepMinutes = Text.isoMinutes(meta("prepTime").firstOrNull()),
            cookMinutes = Text.isoMinutes(meta("cookTime").firstOrNull()),
            ingredients = sectioned(paragraphs("recipeIngredients")).map { (section, line) -> Ingredient(line, section) },
            steps = sectioned(paragraphs("recipeDirections").map(Text::stripNumbering)).map { (section, line) -> Step(line, section) },
            notes = paragraphs("recipeNotes").joinToString("\n"),
            nutrition = nutrition,
        )
        return KeeperRecipe(recipe, photo)
    }

    /**
     * Recipe Keeper has no sections, so people write a heading line such as "Sauce:" or
     * "# Sauce". Those lines become the section of the lines under them.
     */
    private fun sectioned(lines: List<String>): List<Pair<String?, String>> {
        var section: String? = null
        val out = mutableListOf<Pair<String?, String>>()
        for (line in lines) {
            val heading = Regex("""^#+\s*(.+)$""").find(line)?.groupValues?.get(1)
                ?: line.takeIf { it.endsWith(':') && it.length <= 40 && !it.any(Char::isDigit) }?.removeSuffix(":")
            if (heading != null) section = Text.clean(heading) else out += section to line
        }
        return out
    }
}
