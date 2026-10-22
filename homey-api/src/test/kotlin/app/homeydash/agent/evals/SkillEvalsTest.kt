package app.homeydash.agent.evals

import app.homeydash.agent.AgentSettings
import app.homeydash.agent.CallCheck
import app.homeydash.agent.AgentTools
import app.homeydash.agent.ChatClient
import app.homeydash.agent.Connection
import app.homeydash.agent.FridgeTools
import app.homeydash.agent.HomeView
import app.homeydash.agent.KnowHow
import app.homeydash.agent.KnowHowBook
import app.homeydash.agent.KnowHowTools
import app.homeydash.agent.Language
import app.homeydash.agent.MemoryBook
import app.homeydash.agent.MemoryTools
import app.homeydash.agent.Provider
import app.homeydash.agent.RecipeTools
import app.homeydash.agent.RoutineBook
import app.homeydash.agent.RoutineTools
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SkillEvalsTest {
    private val http = OkHttpClient()
    private val work: File = Files.createTempDirectory("evals").toFile()
    private val loaded = SkillCases.load()
    private val house = loaded.first
    private val files = loaded.second

    private fun case(id: String) = files.flatMap { f -> f.cases.map { f to it } }.first { it.second.id == id }

    private fun brain(server: MockWebServer) = Connection(Provider.OpenAI, server.url("/v1").toString(), apiKey = "k", model = "m")

    private fun says(content: String) = MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":${JsonPrimitive(content)}}}]}""")

    private fun calls(name: String, args: String) = MockResponse().setBody(
        """{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"c1","type":"function","function":{"name":"$name","arguments":${JsonPrimitive(args)}}}]}}]}""",
    )

    /** Every tool the assistant can have, with everything the practice house offers switched on. */
    private suspend fun toolNames(): Set<String> {
        val sim = SimHouse(house, File(work, "names"), http).also { it.open() }
        val evals = SkillEvals(ChatClient(http), http, work, house)
        val settings = evals.settingsFor(SkillCase("x"), Connection(Provider.OpenAI, apiKey = "k"), AgentSettings())
        val view = HomeView.build(sim.zones, sim.devices, sim.moods, emptySet(), settings, local = true)
        val books = File(work, "names-books")
        val tools = AgentTools(
            view, sim, sim.dashboard,
            recipes = RecipeTools(sim.recipes, sim.cooking) {}, fridge = FridgeTools(sim.fridge), notes = app.homeydash.agent.NoteTools(sim.notes),
            routines = RoutineTools(RoutineBook(books)), memory = MemoryTools(MemoryBook(books)), knowHow = KnowHowTools(KnowHowBook(books)),
        )
        return tools.specs().map { it.name }.toSet() + KnowHow.GUIDE
    }

    @Test fun `every case file reads and names only real devices, tools and calendars`() = runTest {
        val ids = files.flatMap { f -> f.cases.map { it.id } }
        assertEquals("case ids are unique", ids.size, ids.toSet().size)
        assertTrue("at least 40 cases (${ids.size})", ids.size >= 40)
        val devices = house.devices.map { it.id }.toSet()
        val tools = toolNames()
        val calendars = house.calendars.map { it.id }.toSet()
        for (f in files) for (c in f.cases) {
            assertTrue("${c.id} starts with its skill", c.id.startsWith(f.skill + "."))
            assertTrue("${c.id} is said some way", c.say.values.flatten().isNotEmpty())
            assertTrue("${c.id} only sv/en", c.say.keys.all { it in setOf("sv", "en") })
            val expects = listOf(c.expect) + c.then.map { it.expect }
            val named = c.given.devices.keys + c.given.failing + expects.flatMap { e ->
                e.state.keys + e.changed.flatMap { it.split('|') } + e.mayChange.filter { it != "*" } + e.up.keys + e.down.keys
            }
            for (d in named) assertTrue("${c.id} names $d, which the house hasn't", d in devices)
            for (e in expects) {
                for (t in e.tools.flatMap { it.split('|') } + e.toolsNever + e.args.keys) assertTrue("${c.id} names the tool $t, which doesn't exist", t in tools)
                e.event?.calendar?.let { assertTrue("${c.id} names the calendar $it", it in calendars) }
            }
            c.given.recipe?.let { r -> assertTrue("${c.id} opens $r", house.recipes.any { it.title == r }) }
        }
    }

    // Real time: the run's timeout must not jump ahead while the mock server answers.
    @Test fun `a right answer passes`() = runBlocking {
        val server = MockWebServer().apply { start() }
        server.enqueue(calls("set_room", """{"room":"Kök","capability":"onoff","value":"false"}"""))
        server.enqueue(says("Nu är det släckt i köket."))
        val (file, c) = case("lights.kitchen_off")
        val evals = SkillEvals(ChatClient(http), http, work, house)
        val r = evals.runOne(brain(server), Planned(file, c, "sv", "släck i köket", 1), EvalOptions())
        assertTrue(r.problems.joinToString(), r.passed)
        assertFalse(r.unasked)
        assertEquals(listOf("set_room"), r.calls.map { it.substringBefore(' ') })
        assertTrue(r.changes.any { it.startsWith("kitchen_ceiling onoff") })
        assertTrue(r.transcript.any { it.startsWith("question: ") })
        server.shutdown()
    }

    @Test fun `answers follow the home's language setting`() = runBlocking {
        val server = MockWebServer().apply { start() }
        repeat(2) {
            server.enqueue(calls("set_room", """{"room":"Kök","capability":"onoff","value":"false"}"""))
            server.enqueue(says("Done, the kitchen lights are off."))
        }
        val (file, c) = case("lights.kitchen_off")
        val evals = SkillEvals(ChatClient(http), http, work, house)
        val planned = Planned(file, c, "sv", "släck i köket", 1)
        // Svenska + English: a Swedish question wants a Swedish answer.
        val auto = evals.runOne(brain(server), planned, EvalOptions(base = AgentSettings(language = Language.Auto)))
        assertTrue(auto.problems.joinToString(), auto.problems.any { "answered in English" in it })
        // English only: the same English answer is right.
        val english = EvalOptions(base = AgentSettings(language = Language.English))
        val r = evals.runOne(brain(server), planned, english)
        assertTrue(r.problems.joinToString(), r.passed)
        assertEquals(Language.English, evals.settingsFor(c, brain(server), english.base).language)
        server.shutdown()
    }

    @Test fun `saying it is done without doing it fails`() = runBlocking {
        val server = MockWebServer().apply { start() }
        repeat(3) { server.enqueue(says("Jag har släckt i köket.")) }
        val (file, c) = case("lights.kitchen_off")
        val r = SkillEvals(ChatClient(http), http, work, house).runOne(brain(server), Planned(file, c, "sv", "släck i köket", 1), EvalOptions())
        assertFalse(r.passed)
        assertTrue(r.problems.joinToString(), r.problems.any { "kitchen_ceiling onoff" in it })
        server.shutdown()
    }

    @Test fun `changing what nobody asked for is caught`() = runBlocking {
        val server = MockWebServer().apply { start() }
        server.enqueue(calls("set_room", """{"room":"all","capability":"onoff","value":"false"}"""))
        server.enqueue(says("Allt är släckt."))
        server.enqueue(calls("set_device", """{"device":"Hallampa","capability":"onoff","value":"true"}"""))
        server.enqueue(says("Klart, det är släckt i köket."))
        val (file, c) = case("lights.kitchen_off")
        val evals = SkillEvals(ChatClient(http), http, work, house)
        // All lights off is more than the kitchen, but nothing else was on; a lamp switched on elsewhere is a real slip.
        val all = evals.runOne(brain(server), Planned(file, c, "sv", "släck i köket", 1), EvalOptions())
        assertTrue(all.problems.joinToString(), all.passed)
        val slip = evals.runOne(brain(server), Planned(file, c, "sv", "släck i köket", 1), EvalOptions())
        assertTrue(slip.unasked)
        assertFalse(slip.passed)
        server.shutdown()
    }

    @Test fun `goodnight waits for the yes in the next turn`() = runBlocking {
        val server = MockWebServer().apply { start() }
        server.enqueue(calls("goodnight", "{}"))
        server.enqueue(says("Ska jag säga godnatt och släcka allt?"))
        val (file, c) = case("goodnight.yes")
        val r = SkillEvals(ChatClient(http), http, work, house).runOne(brain(server), Planned(file, c, "sv", "godnatt", 1), EvalOptions())
        assertTrue(r.problems.joinToString(), r.passed)
        assertEquals(2, r.replies.size)
        assertEquals(2, server.requestCount)
        server.shutdown()
    }

    @Test fun `verbose logs get the whole prompt, and a wrong answer is marked without asking the AI`() = runBlocking {
        val server = MockWebServer().apply { start() }
        server.enqueue(says("Hej!"))
        val sim = SimHouse(house, File(work, "prompt"), http).also { it.open() }
        val evals = SkillEvals(ChatClient(http), http, work, house)
        val settings = evals.settingsFor(SkillCase("x"), brain(server), AgentSettings())
        val lines = mutableListOf<Pair<String, String>>()
        val agent = app.homeydash.agent.Agent(ChatClient(http))
        agent.listener = object : app.homeydash.agent.AgentListener {
            override fun trace(kind: String, text: String, localOnly: Boolean) { lines += kind to text }
            override fun wants(kind: String) = true
        }
        val view = { local: Boolean -> HomeView.build(sim.zones, sim.devices, sim.moods, emptySet(), settings, local) }
        agent.ask("hej", settings, view, { AgentTools(it, sim, sim.dashboard) })
        val prompt = lines.single { it.first == app.homeydash.agent.AgentLog.PROMPT }.second
        assertTrue(prompt, prompt.contains("[system]") && prompt.contains("hej"))
        val wrong = agent.ask("det där blev fel", settings, view, { AgentTools(it, sim, sim.dashboard) })
        assertEquals(1, server.requestCount)
        assertEquals(app.homeydash.agent.AgentLog.FLAG, lines.last().first)
        assertTrue(wrong.text, wrong.text.contains("Claude"))
        server.shutdown()
    }

    @Test fun `the practice house dims like Homey, and its own Swedish words don't count as the answer's language`() = runBlocking {
        val sim = SimHouse(house, File(work, "dim"), http).also { it.open() }
        sim.setCapability("kitchen_ceiling", "dim", JsonPrimitive(0.0))
        assertEquals(JsonPrimitive(false), sim.device("kitchen_ceiling")?.capability("onoff")?.value)
        sim.setCapability("kitchen_ceiling", "dim", JsonPrimitive(0.4))
        assertEquals(JsonPrimitive(true), sim.device("kitchen_ceiling")?.capability("onoff")?.value)
        val step = house.recipes.first { it.title == "Lasagne" }.steps[2]
        assertEquals("en", Lang.of(sim.withoutHouseWords("Step 3 of 5: $step After that, you are done.")))
    }

    @Test fun `the practice house finds Spotify albums, podcasts and songs by kind`() = runBlocking {
        val sim = SimHouse(house, File(work, "spotify"), http).also { it.open() }
        assertEquals("Abbey Road by The Beatles", sim.playMusic("kitchen_sonos", "Abbey Road", "album"))
        assertEquals("Värvet by Kristoffer Triumf", sim.playMusic("kitchen_sonos", "värvet", "show"))
        assertEquals("Dancing Queen by ABBA", sim.playMusic("kitchen_sonos", "Dancing Queen ABBA", null))
        assertEquals("Alex & Sigge by Alex Schulman och Sigge Eklund", sim.playMusic("kitchen_sonos", "Alex och Sigge", "episode"))
        assertEquals("P1", sim.playMusic("kitchen_sonos", "P1", "station"))
        assertNull(sim.playMusic("kitchen_sonos", "Abbey Road", "podcast"))
        assertTrue(sim.did.toString(), "spotify:podcast:Värvet" in sim.did && "play:kitchen_sonos:P1" in sim.did)
    }

    @Test fun `timer add with nothing running starts one, and an alias may carry its room`() = runBlocking {
        val sim = SimHouse(house, File(work, "tools"), http).also { it.open() }
        val settings = AgentSettings()
        val view = HomeView.build(sim.zones, sim.devices, sim.moods, emptySet(), settings, local = true)
        val tools = AgentTools(view, sim, sim.dashboard)
        fun args(vararg pairs: Pair<String, String>) = kotlinx.serialization.json.JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })
        tools.run(app.homeydash.agent.ToolCall("1", "timer", args("action" to "add", "minutes" to "10", "label" to "pastan")))
        assertEquals("Pastan", sim.timers.timers.value.single().label)
        tools.run(app.homeydash.agent.ToolCall("2", "timer", args("action" to "add_minutes", "minutes" to "2", "label" to "all")))
        assertEquals(12 * 60, sim.timers.timers.value.single().seconds)
        tools.run(app.homeydash.agent.ToolCall("3", "timer", kotlinx.serialization.json.JsonObject(mapOf(
            "action" to JsonPrimitive("start"), "minutes" to JsonPrimitive(5), "label" to kotlinx.serialization.json.JsonNull, "several" to JsonPrimitive(false),
        ))))
        assertEquals(2, sim.timers.timers.value.size)
        val cooking = AgentTools(view, sim, sim.dashboard, recipes = RecipeTools(sim.recipes, sim.cooking) {})
        cooking.run(app.homeydash.agent.ToolCall("4", "open_recipe", args("recipe" to "lasagnen")))
        assertEquals("Lasagne", sim.cooking.current()?.first?.title)
        assertEquals("Done, the lights are on.", ChatClient.stripThinking("<think>    Done, the lights are on."))
        val lamp = view.devices.first { it.name == house.devices.first { d -> d.id == "bedroom_lamp" }.name }
        assertEquals(lamp, view.device("${lamp.alias}, ${lamp.room}"))
    }

    @Test fun `calls the question didn't ask for are refused, and the recipe is opened instead of the list`() = runBlocking {
        val sim = SimHouse(house, File(work, "checks"), http).also { it.open() }
        val view = HomeView.build(sim.zones, sim.devices, sim.moods, emptySet(), AgentSettings(), local = true)
        fun call(name: String, vararg a: Pair<String, String>) =
            app.homeydash.agent.ToolCall("1", name, kotlinx.serialization.json.JsonObject(a.associate { it.first to JsonPrimitive(it.second) }))
        val hall = call("set_device", "device" to "Hallampa", "capability" to "onoff", "value" to "true")
        assertEquals(CallCheck.STATUS_ONLY, CallCheck.refuse(hall, "är lampan i hallen tänd?", view)?.text)
        assertEquals(CallCheck.STATUS_ONLY, CallCheck.refuse(hall, "is the hall light on?", view)?.text)
        assertNull(CallCheck.refuse(hall, "är du snäll och tänd i hallen", view))
        assertNull(CallCheck.refuse(hall, "tänd i hallen", view))
        val allOn = call("set_room", "room" to "all", "capability" to "onoff", "value" to "true")
        assertEquals(CallCheck.WHICH, CallCheck.refuse(allOn, "tänd lampan", view)?.text)
        assertNull(CallCheck.refuse(allOn, "tänd alla lampor", view))
        assertNull(CallCheck.refuse(call("set_room", "room" to "all", "capability" to "onoff", "value" to "false"), "släck", view))
        val coffee = call("set_device", "device" to "Kaffebryggare, Kök", "capability" to "onoff", "value" to "true")
        assertTrue(CallCheck.refuse(coffee, "sätt på diskmaskinen", view)?.text.orEmpty().contains("no dishwasher"))
        assertNull(CallCheck.refuse(coffee, "sätt på kaffebryggaren", view))
        val all = listOf("goodnight", "set_device", "play_music", "open_page", "open_recipe")
        assertEquals(CallCheck.USE_GOODNIGHT, CallCheck.refuse(allOn.copy(arguments = kotlinx.serialization.json.JsonObject(allOn.arguments + ("value" to JsonPrimitive("false")))), "god natt, vi går och lägger oss", view, all)?.text)
        assertEquals(CallCheck.RECIPES_ONLY, CallCheck.refuse(call("open_recipe", "recipe" to "Kycklinggryta"), "vad kan jag laga med kyckling?", view, all)?.text)
        assertTrue(CallCheck.refuse(call("remove_from_fridge", "item" to "Yoghurt"), "mjölken är slut", view, all)!!.soft)
        assertNull(CallCheck.refuse(call("remove_from_fridge", "item" to "Mjölk"), "mjölken är slut", view, all))
        fun arg(c: app.homeydash.agent.ToolCall?, k: String) = (c?.arguments?.get(k) as? JsonPrimitive)?.content
        val sat = CallCheck.rewrite(call("calendar", "start_date" to "2026-10-29", "calendar" to "family"), "what are we doing on saturday?", all)
        assertEquals("saturday", arg(sat, "start_date"))
        assertNull(arg(sat, "calendar"))
        assertEquals("family", arg(CallCheck.rewrite(call("calendar", "start_date" to "2026-10-29", "calendar" to "family"), "vad har familjen på lördag?", all), "calendar"))
        assertEquals("fredag", arg(CallCheck.rewrite(call("add_event", "title" to "Frisör", "date" to "2026-10-29"), "lägg in frisör på fredag klockan 14", all), "date"))
        val pause = CallCheck.rewrite(call("play_music", "player" to "Kök Sonos", "capability" to "speaker_playing", "value" to "pause"), "pausa musiken i köket", all)
        assertEquals("set_device", pause?.name)
        assertEquals("false", arg(pause, "value"))
        assertEquals("play_music", CallCheck.rewrite(call("set_device", "device" to "Kök Sonos", "capability" to "speaker_playing", "value" to "Rumours"), "sätt på Rumours i köket", all)?.name)
        assertNull(CallCheck.rewrite(call("set_device", "device" to "Kök Sonos", "capability" to "speaker_playing", "value" to "true"), "spela i köket", all))
        assertEquals("add", arg(CallCheck.rewrite(call("timer", "action" to "start", "minutes" to "2", "label" to "pastan"), "två minuter till på pastan", all), "action"))
        assertNull(CallCheck.rewrite(call("timer", "action" to "start", "minutes" to "10", "label" to "pasta"), "timer på tio minuter för pastan", all))
        assertEquals("open_page", CallCheck.rewrite(call("fridge_contents"), "visa kylen", all)?.name)
        assertNull(CallCheck.rewrite(call("fridge_contents"), "vad finns i kylen?", all))
        assertNull(arg(CallCheck.rewrite(call("add_todo", "text" to "Byta glödlampa", "who" to "Gabriel"), "vi måste byta glödlampan i hallen", all), "who"))
        val names = listOf("open_page", "open_recipe")
        assertEquals("lasagnen", CallCheck.recipeInstead(call("open_page", "page" to "recipes"), "öppna lasagnen", names)?.arguments?.get("recipe")?.let { (it as JsonPrimitive).content })
        assertNull(CallCheck.recipeInstead(call("open_page", "page" to "recipes"), "öppna recepten", names))
        val home = AgentSettings(people = mapOf("p-gabriel" to "Gabriel", "p-alex" to "Alex"), asker = app.homeydash.agent.Asker("p-gabriel", "Gabriel"))
        assertTrue(CallCheck.toSomeoneElse("vad ska vi äta ikväll, älskling?", home))
        assertTrue(CallCheck.toSomeoneElse("honey, did you feed the cat?", home))
        assertTrue(CallCheck.toSomeoneElse("Alex, har du sett nycklarna?", home))
        assertFalse(CallCheck.toSomeoneElse("spela Alex och Sigge", home))
        assertFalse(CallCheck.toSomeoneElse("Nova, älskling, tänd i köket", home))
        assertTrue(CallCheck.toSomeoneElse("kan du hämta barnen idag?", home))
        assertTrue(CallCheck.toSomeoneElse("could you pick up milk on the way home?", home))
        assertFalse(CallCheck.toSomeoneElse("kan du tända i köket?", home))
        val play = listOf("set_room", "set_device", "play_music")
        val jazz = CallCheck.rewrite(call("set_room", "room" to "Vardagsrum", "capability" to "speaker_playing", "value" to "true"), "sätt på lite morgonjazz i vardagsrummet", play, view)
        assertEquals("play_music", jazz?.name)
        assertEquals("Vardagsrum Sonos", arg(jazz, "player"))
        assertEquals("morgonjazz", arg(jazz, "query"))
        assertEquals("morgonjazz", arg(CallCheck.rewrite(call("set_device", "device" to "Vardagsrum Sonos", "capability" to "speaker_playing", "value" to "true"), "spela morgonjazz på vardagsrummets sonos", play, view), "query"))
        assertNull(CallCheck.rewrite(call("set_room", "room" to "Vardagsrum", "capability" to "speaker_playing", "value" to "true"), "sätt på musiken i vardagsrummet", play, view))
        assertTrue(app.homeydash.agent.Agent.inOtherLanguage("Jag har tänt lampan i köket.", swedish = false, view))
        assertFalse(app.homeydash.agent.Agent.inOtherLanguage("I turned on the light in Kök, it is on now.", swedish = false, view))
        assertFalse(app.homeydash.agent.Agent.inOtherLanguage("Klart.", swedish = false, view))
    }

    @Test fun `the plan picks phrasings, languages and tags`() {
        val evals = SkillEvals(ChatClient(http), http, work, house)
        val all = evals.plan(files, EvalOptions(trials = 3))
        val phrasings = files.sumOf { f -> f.cases.sumOf { c -> c.say.values.sumOf { it.size } } }
        assertEquals(phrasings * 3, all.size)
        val quick = evals.plan(files, EvalOptions(trials = 1, allPhrasings = false, languages = setOf("sv"), only = setOf("smoke")))
        assertTrue(quick.isNotEmpty())
        assertTrue(quick.all { "smoke" in it.case.tags && it.lang == "sv" })
        assertEquals(quick.size, quick.map { it.case.id }.toSet().size)
    }

    @Test fun `languages and sentences`() {
        assertEquals("sv", Lang.of("Nu är det släckt i köket."))
        assertEquals("en", Lang.of("The kitchen lights are off now."))
        assertNull(Lang.of("Okej."))
        assertEquals(2, Lang.sentences("Klart! Lamporna är släckta i köket."))
        assertTrue(Graders.same(JsonPrimitive(0.3), JsonPrimitive(0.31)))
        assertFalse(Graders.same(JsonPrimitive(0.3), JsonPrimitive(0.5)))
        assertTrue(Graders.same(JsonPrimitive(22), JsonPrimitive(22.0)))
        assertTrue(Graders.argMatches(JsonPrimitive(10), JsonPrimitive("10 min")))
        assertTrue(Graders.argMatches(JsonPrimitive("pasta|spaghetti"), JsonPrimitive("Pastan")))
    }

    @Test fun `the report sums up per skill and shows what failed`() {
        val pass = EvalResult("timers", "timers.pasta", "sv", "timer pasta", 1, true, ms = 2000)
        val fail = EvalResult("timers", "timers.pasta", "sv", "timer pasta", 2, false, listOf("Expected a timer pasta, 10 min; timers: none."), ms = 3000)
        val run = EvalRun("Ollama (your computer)", "qwen", true, 0, 1, trials = 2, results = listOf(pass, fail))
        val md = EvalReport.markdown(run)
        assertTrue(md, md.contains("| timers | 0/1 | 1/2 | 0 |"))
        assertTrue(md.contains("Expected a timer pasta"))
        assertEquals(run, EvalRun.parse(EvalRun.write(run)))
    }
}
