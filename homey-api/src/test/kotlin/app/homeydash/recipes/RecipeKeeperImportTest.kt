package app.homeydash.recipes

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RecipeKeeperImportTest {
    /** Laid out the way Recipe Keeper's export writes recipes.html. */
    private val html = """
        <!DOCTYPE html><html><body>
        <div class="recipe-details">
            <meta content="aaaa-1111" itemprop="recipeId">
            <meta content="True" itemprop="recipeIsFavourite">
            <meta content="4" itemprop="recipeRating">
            <table><tr><td><img src="images/aaaa-1111_0.jpg" class="recipe-photo"/></td>
            <td>
                <h2 itemprop="name">Laxpudding</h2>
                <div>Courses: <span itemprop="recipeCourse">Middag</span></div>
                <div>Categories: <span>Fisk, Husman</span>
                    <meta content="Fisk" itemprop="recipeCategory"><meta content="Husman" itemprop="recipeCategory"></div>
                <div>Collections: <span>Mormor</span><meta content="Mormor" itemprop="recipeCollection"></div>
                <div>Source: <span itemprop="recipeSource"><a href="https://www.ica.se/recept/laxpudding/">https://www.ica.se/recept/laxpudding/</a></span></div>
                <div>Serving size: <span itemprop="recipeYield">4 portioner</span></div>
                <div>Preparation time: <span>20 mins</span><meta content="PT20M" itemprop="prepTime"></div>
                <div>Cooking time: <span>45 mins</span><meta content="PT45M" itemprop="cookTime"></div>
            </td></tr>
            <tr><td><div class="recipe-ingredients" itemprop="recipeIngredients">
                <p>1 kg potatis</p><p></p><p>Äggstanning:</p><p>4 ägg</p><p>5 dl mjölk</p>
            </div></td>
            <td><div itemprop="recipeDirections">
                <p>1. Koka potatisen.</p><p></p><p></p><p>2. Varva potatis och lax.</p><p>3. Grädda i 45 minuter.</p>
            </div></td></tr></table>
            <div class="recipe-notes" itemprop="recipeNotes"><p>Servera med smält smör.</p></div>
            <div>Calories: 410<meta content="410" itemprop="recipeNutCalories"></div>
            <div>Total Fat: 0<meta content="0" itemprop="recipeNutTotalFat"></div>
        </div>
        <div class="recipe-details">
            <meta content="bbbb-2222" itemprop="recipeId">
            <meta content="False" itemprop="recipeIsFavourite">
            <h2 itemprop="name">Gröt</h2>
            <span itemprop="recipeSource"></span>
            <div itemprop="recipeIngredients"><p>Havregryn</p></div>
            <div itemprop="recipeDirections"><p>Koka.</p></div>
            <div itemprop="recipeNotes"></div>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun readsEveryField() {
        val list = RecipeKeeperImport.parseHtml(html)
        assertEquals(2, list.size)
        val (r, photo) = list[0]
        assertEquals("Laxpudding", r.title)
        assertEquals("aaaa-1111", r.externalId)
        assertTrue(r.favourite)
        assertEquals(4, r.rating)
        assertEquals(listOf("Middag"), r.courses)
        assertEquals(listOf("Fisk", "Husman"), r.categories)
        assertEquals(listOf("Mormor"), r.collections)
        assertEquals("https://www.ica.se/recept/laxpudding/", r.sourceUrl)
        assertEquals("ica.se", r.sourceName)
        assertEquals("4 portioner", r.servings)
        assertEquals(65, r.minutes)
        assertEquals(listOf("1 kg potatis", "4 ägg", "5 dl mjölk"), r.ingredients.map { it.text })
        assertEquals(listOf(null, "Äggstanning", "Äggstanning"), r.ingredients.map { it.section })
        assertEquals(listOf("Koka potatisen.", "Varva potatis och lax.", "Grädda i 45 minuter."), r.steps.map { it.text })
        assertEquals("Servera med smält smör.", r.notes)
        assertEquals(mapOf("Calories" to "410"), r.nutrition)
        assertEquals("images/aaaa-1111_0.jpg", photo)
        assertEquals(null, list[1].photo)
        assertEquals(null, list[1].recipe.sourceUrl)
    }

    @Test
    fun importsTheZipAndUpdatesOnReimport() = runTest {
        val dir = Files.createTempDirectory("book").toFile()
        val zipFile = File(dir, "export.zip")
        ZipOutputStream(zipFile.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("RecipeKeeper/recipes.html")); z.write(html.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("RecipeKeeper/images/aaaa-1111_0.jpg")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry()
        }
        val book = RecipeBook(File(dir, "book"), OkHttpClient())
        book.load()
        val (zip, recipes) = RecipeKeeperImport.parseZip(zipFile)
        val first = zip.use { z -> book.importKeeper(recipes) { RecipeKeeperImport.photo(z, it) } }
        assertEquals(ImportResult(added = 2, updated = 0, photos = 1), first)
        val lax = book.recipes.value.first { it.title == "Laxpudding" }
        assertTrue(book.imageFile(lax)!!.exists())

        val (zip2, again) = RecipeKeeperImport.parseZip(zipFile)
        val second = zip2.use { z -> book.importKeeper(again.map { it.copy(recipe = it.recipe.copy(title = it.recipe.title + "!")) }) { RecipeKeeperImport.photo(z, it) } }
        assertEquals(0, second.added)
        assertEquals(2, second.updated)
        assertEquals(2, book.recipes.value.size)
        assertEquals(lax.id, book.recipes.value.first { it.title == "Laxpudding!" }.id)

        // It survives a restart.
        val reopened = RecipeBook(File(dir, "book"), OkHttpClient()).also { it.load() }
        assertEquals(setOf("Laxpudding!", "Gröt!"), reopened.recipes.value.map { it.title }.toSet())
        assertEquals(listOf("Fisk", "Husman", "Middag", "Mormor").sorted(), reopened.tags().sorted())
    }
}
