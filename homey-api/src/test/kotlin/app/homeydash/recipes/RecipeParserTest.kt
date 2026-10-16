package app.homeydash.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeParserTest {
    @Test
    fun readsJsonLdGraphWithLinkedImageAndSections() {
        val html = """
            <html><head><title>Kladdkaka | Bakbloggen</title>
            <meta property="og:site_name" content="Bakbloggen">
            <script type="application/ld+json">
            {"@context":"https://schema.org","@graph":[
              {"@type":"WebPage","@id":"https://example.se/kladdkaka/#webpage","name":"Kladdkaka"},
              {"@type":"ImageObject","@id":"https://example.se/#primaryimage","url":"https://example.se/img/kladdkaka.jpg","width":1200},
              {"@type":["Recipe"],"name":"Kladdkaka &amp; grädde","description":"<p>Världens <b>bästa</b>.</p>",
               "image":{"@id":"https://example.se/#primaryimage"},
               "author":{"@type":"Person","name":"Anna"},
               "recipeYield":["8","8 bitar"],
               "prepTime":"PT10M","cookTime":"PT0H20M","totalTime":"PT30M",
               "recipeCategory":"Dessert, Fika","recipeCuisine":["Svensk"],"keywords":"choklad, enkel",
               "recipeIngredient":["100 g smör","2 ägg","&#188; tsk salt"],
               "recipeInstructions":[
                 {"@type":"HowToSection","name":"Smet","itemListElement":[
                   {"@type":"HowToStep","text":"1. Smält smöret."},
                   {"@type":"HowToStep","text":"Rör ner ägg och socker."}]},
                 {"@type":"HowToSection","name":"Grädda","itemListElement":[
                   {"@type":"HowToStep","text":"Grädda i 200 grader i 15-20 minuter."}]}
               ],
               "nutrition":{"@type":"NutritionInformation","calories":"320 kcal"}}
            ]}
            </script></head><body></body></html>
        """.trimIndent()
        val parsed = RecipeParser.parse(html, "https://example.se/kladdkaka/")
        assertNotNull(parsed)
        val r = parsed!!.recipe
        assertEquals("json-ld", parsed.via)
        assertEquals("Kladdkaka & grädde", r.title)
        assertEquals("Världens bästa.", r.description)
        assertEquals("https://example.se/img/kladdkaka.jpg", r.imageUrl)
        assertEquals("8 bitar", r.servings)
        assertEquals(10, r.prepMinutes)
        assertEquals(20, r.cookMinutes)
        assertEquals(30, r.minutes)
        assertEquals(listOf("100 g smör", "2 ägg", "¼ tsk salt"), r.ingredients.map { it.text })
        assertEquals(listOf("Smält smöret.", "Rör ner ägg och socker.", "Grädda i 200 grader i 15-20 minuter."), r.steps.map { it.text })
        assertEquals(listOf("Smet", "Smet", "Grädda"), r.steps.map { it.section })
        assertEquals("Bakbloggen", r.sourceName)
        assertEquals(listOf("Dessert", "Fika", "Svensk"), parsed.siteCategories)
        assertEquals("320 kcal", r.nutrition["Calories"])
        assertTrue("choklad" in r.keywords)
    }

    @Test
    fun readsInstructionsGivenAsOneHtmlString() {
        val html = """
            <script type="application/ld+json">[{"@type":"Organization","name":"X"},
            {"@type":"Recipe","name":"Pasta","image":["https://x.se/a.jpg","https://x.se/b.jpg"],
             "recipeIngredient":["Pasta"],"recipeInstructions":"<ol><li>Koka vatten.</li><li>Koka pastan 10 min.</li></ol>"}]</script>
        """.trimIndent()
        val r = RecipeParser.parse(html, "https://x.se/pasta")!!.recipe
        assertEquals(listOf("Koka vatten.", "Koka pastan 10 min."), r.steps.map { it.text })
        assertEquals("https://x.se/a.jpg", r.imageUrl)
        assertEquals("x.se", r.sourceName)
    }

    @Test
    fun readsPlainTextInstructionsLineByLine() {
        val html = """<script type="application/ld+json">{"@type":"Recipe","name":"Gröt","recipeIngredient":["Havre"],
            "recipeInstructions":"Koka upp vatten.\nRör ner havre.\n\nSalta."}</script>"""
        val r = RecipeParser.parse(html, null)!!.recipe
        assertEquals(listOf("Koka upp vatten.", "Rör ner havre.", "Salta."), r.steps.map { it.text })
    }

    @Test
    fun picksTheWidestImageWhenSizesAreListed() {
        val html = """<script type="application/ld+json">{"@type":"Recipe","name":"Soppa","recipeIngredient":["Vatten"],
            "image":[{"@type":"ImageObject","url":"https://s.se/small.jpg","width":"300"},{"@type":"ImageObject","url":"https://s.se/big.jpg","width":"1600"}]}</script>"""
        assertEquals("https://s.se/big.jpg", RecipeParser.parse(html, null)!!.recipe.imageUrl)
    }

    @Test
    fun fallsBackToMicrodata() {
        val html = """
            <div itemscope itemtype="http://schema.org/Recipe">
              <h1 itemprop="name">Pannkakor</h1>
              <img itemprop="image" src="/p.jpg">
              <meta itemprop="totalTime" content="PT25M">
              <span itemprop="recipeYield">4 portioner</span>
              <ul><li itemprop="recipeIngredient">3 dl mjöl</li><li itemprop="recipeIngredient">6 dl mjölk</li></ul>
              <div itemprop="recipeInstructions"><p>Vispa.</p><p>Stek.</p></div>
            </div>
        """.trimIndent()
        val parsed = RecipeParser.parse(html, "https://mat.se/pannkakor")!!
        assertEquals("microdata", parsed.via)
        assertEquals("Pannkakor", parsed.recipe.title)
        assertEquals("https://mat.se/p.jpg", parsed.recipe.imageUrl)
        assertEquals(25, parsed.recipe.minutes)
        assertEquals(listOf("3 dl mjöl", "6 dl mjölk"), parsed.recipe.ingredients.map { it.text })
        assertEquals(listOf("Vispa.", "Stek."), parsed.recipe.steps.map { it.text })
    }

    @Test
    fun fallsBackToHeadingsOnPlainBlogs() {
        val html = """
            <html><head><meta property="og:title" content="Mormors köttbullar"><meta property="og:image" content="https://blogg.se/kb.jpg"></head>
            <body><article><h1>Mormors köttbullar</h1><p>Lite prat först.</p>
            <h3>Ingredienser</h3><ul><li>500 g färs</li><li>1 ägg</li></ul>
            <h3>Gör så här</h3><ol><li>Blanda allt.</li><li>Rulla bullar.</li><li>Stek i 10 minuter.</li></ol>
            </article></body></html>
        """.trimIndent()
        val parsed = RecipeParser.parse(html, "https://blogg.se/kb")!!
        assertEquals("page", parsed.via)
        assertEquals("Mormors köttbullar", parsed.recipe.title)
        assertEquals("https://blogg.se/kb.jpg", parsed.recipe.imageUrl)
        assertEquals(2, parsed.recipe.ingredients.size)
        assertEquals("Stek i 10 minuter.", parsed.recipe.steps.last().text)
    }

    @Test
    fun noRecipeOnAPageGivesNull() {
        assertNull(RecipeParser.parse("<html><body><h1>Om oss</h1><p>Hej</p></body></html>", "https://x.se"))
    }

    @Test
    fun readsDurations() {
        assertEquals(90, Text.isoMinutes("PT1H30M"))
        assertEquals(20, Text.isoMinutes("P0DT0H20M"))
        assertEquals(90, Text.isoMinutes("PT90M"))
        assertNull(Text.isoMinutes("PT0M"))
        assertEquals(80, Text.plainMinutes("1 hour 20 mins"))
        assertEquals(45, Text.plainMinutes("45 minuter"))
        assertEquals("1 h 5 min", Text.duration(65))
    }

    @Test
    fun searchIgnoresAccentsAndCase() {
        val r = Recipe(id = "1", title = "Kåldolmar", ingredients = listOf(Ingredient("Vitkål")), categories = listOf("Husman"))
        assertTrue(r.matches("kaldolmar"))
        assertTrue(r.matches("VITKÅL husman"))
        assertTrue(!r.matches("lasagne"))
    }

    @Test
    fun editsAsTextWithSections() {
        val list = listOf(Ingredient("1 kg potatis"), Ingredient("4 ägg", "Stanning"), Ingredient("5 dl mjölk", "Stanning"))
        val text = RecipeText.ingredients(list)
        assertEquals("1 kg potatis\n\n# Stanning\n4 ägg\n5 dl mjölk", text)
        assertEquals(list, RecipeText.parseIngredients(text))
        assertEquals(listOf(Step("Koka."), Step("Grädda.")), RecipeText.parseSteps("1. Koka.\n\n2) Grädda."))
    }
}
