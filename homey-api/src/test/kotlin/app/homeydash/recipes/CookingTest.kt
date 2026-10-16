package app.homeydash.recipes

import app.homeydash.agent.CookingCommand
import app.homeydash.agent.RecipeTools
import app.homeydash.agent.ToolCall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CookingTest {
    private var now = 1_000_000L
    private val book = RecipeBook(Files.createTempDirectory("book").toFile(), OkHttpClient(), clock = { now })
    private val cooking = Cooking(book) { now }
    private var shown = 0
    private val tools = RecipeTools(book, cooking) { shown++ }

    private val lasagne = runBlocking {
        book.load()
        book.save(
            Recipe(
                id = "", title = "Lasagne", categories = listOf("Pasta"),
                ingredients = listOf(Ingredient("500 g färs"), Ingredient("Lasagneplattor")),
                steps = listOf(Step("Bryn färsen."), Step("Koka såsen i 20 minuter."), Step("Varva och grädda 40 min.")),
            ),
        )
    }

    private fun call(name: String, vararg args: Pair<String, String>) =
        ToolCall("1", name, JsonObject(args.associate { it.first to JsonPrimitive(it.second) }))

    @Test
    fun openingARecipeTellsTheAssistantWhereTheCookIs() {
        assertNull(cooking.describe())
        val opened = tools.run(call("open_recipe", "recipe" to "lasagne"))!!.text
        assertTrue(opened, opened.contains("Opened Lasagne"))
        assertEquals(1, shown)
        tools.run(call("recipe_step", "action" to "next"))
        val described = cooking.describe()!!
        assertTrue(described.contains("\"Lasagne\""))
        assertTrue(described.contains("step 2 of 3"))
        assertTrue(described.contains("2. Koka såsen i 20 minuter.   <-- CURRENT STEP"))
    }

    @Test
    fun nextStepByVoiceWithoutTheAi() {
        assertNull("nothing open, so it's left to the AI", tools.quick("nästa steg"))
        cooking.open(lasagne.id)
        assertEquals("Steg 2 av 3: Koka såsen i 20 minuter. Sedan: Varva och grädda 40 min.", tools.quick("Nästa steg"))
        assertTrue(cooking.state.value!!.cookMode)
        assertEquals("Step 3 of 3: Varva och grädda 40 min. That's the last step.", tools.quick("what's next?"))
        assertEquals("That was the last step. Enjoy your meal!", tools.quick("next"))
        assertEquals("Steg 2 av 3: Koka såsen i 20 minuter. Sedan: Varva och grädda 40 min.", tools.quick("gå tillbaka"))
        assertEquals("Step 1 of 3: Bryn färsen. After that: Koka såsen i 20 minuter.", tools.quick("step one"))
        assertNull(tools.quick("turn on the kitchen light"))
    }

    @Test
    fun agentToolsPassRecipeCallsOn() = runBlocking {
        assertNotNull(tools.specs().firstOrNull { it.name == "recipe_step" })
        assertEquals("find_recipes", tools.specs().first().name)
        val found = tools.run(call("find_recipes", "category" to "pasta"))!!.text
        assertTrue(found, found.startsWith("Lasagne · Pasta"))
    }

    @Test
    fun aRecipeLeftOpenOvernightIsForgotten() {
        cooking.open(lasagne.id)
        now += Cooking.FORGET_AFTER_MS + 1
        assertNull(cooking.describe())
        assertNull(tools.quick("next"))
    }

    @Test
    fun understandsStepCommands() {
        assertEquals(CookingCommand("next", swedish = true), CookingCommand.parse("Okej, vad är nästa steg?"))
        assertEquals(CookingCommand("previous"), CookingCommand.parse("go back"))
        assertEquals(CookingCommand("go_to", 4, swedish = true), CookingCommand.parse("gå till steg fyra"))
        assertEquals(CookingCommand("current"), CookingCommand.parse("What step am I on?"))
        assertNull(CookingCommand.parse("how much flour do I need"))
    }

    @Test
    fun findsTimersInSteps() {
        assertEquals(listOf(StepTimer("15-20 minuter", 1200)), Cooking.timers("Grädda i 200 grader i 15-20 minuter."))
        assertEquals(listOf(3600), Cooking.timers("Låt sjuda 1 timme under lock.").map { it.seconds })
        assertEquals(listOf(90 * 60), Cooking.timers("Bake for 1.5 hours").map { it.seconds })
        assertTrue(Cooking.timers("Tillsätt 2 msk olja och 200 ml grädde.").isEmpty())
    }
}
