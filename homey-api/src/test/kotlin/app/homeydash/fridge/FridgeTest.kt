package app.homeydash.fridge

import app.homeydash.agent.FridgeTools
import app.homeydash.agent.ToolCall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate

class FridgeTest {
    private var today = LocalDate.of(2026, 10, 14)
    private var lookups = 0
    private val products = ProductSource { gtin ->
        lookups++
        when (gtin) {
            "7310865004703" -> Product("Mellanmjölk 1,5%", "Arla", "1 l", listOf("en:dairies", "en:milks", "en:semi-skimmed-milks"))
            "7310090010203" -> Product("Kycklingfärs", "Kronfågel", "500 g", listOf("en:meats", "en:poultries"))
            else -> null
        }
    }
    private val dir = Files.createTempDirectory("fridge").toFile()
    private val fridge = Fridge(dir, listOf(products), date = { today }).also { runBlocking { it.load() } }

    @Test
    fun namesAreSortedIntoTheRightKind() {
        assertEquals("mince", FoodRules.classify("Kycklingfärs").key)
        assertEquals("mince", FoodRules.classify("Nötfärs 12%").key)
        assertEquals("poultry", FoodRules.classify("Kycklingfilé").key)
        assertEquals("cold_cuts", FoodRules.classify("Rostbiff skivad").key)
        assertEquals("cheese", FoodRules.classify("Prästost lagrad").key)
        assertEquals("soft_cheese", FoodRules.classify("Mozzarella").key)
        assertEquals("yoghurt", FoodRules.classify("Filmjölk 3%").key)
        assertEquals("milk", FoodRules.classify("Ekologisk mellanmjölk").key)
        assertEquals("fish", FoodRules.classify("Laxfilé").key)
        assertEquals("smoked_fish", FoodRules.classify("Kallrökt lax").key)
        assertEquals("fruit", FoodRules.classify("Vindruvor gröna").key)
        assertEquals("poultry", FoodRules.classify("Kalkonbröst").key)
        assertEquals("eggs", FoodRules.classify("Ägg 12-p").key)
        assertEquals("dips", FoodRules.classify("Bearnaisesås").key)
        assertEquals("other", FoodRules.classify("Diskmedel").key)
        // Open Food Facts' most specific category wins over words in the name.
        assertEquals("yoghurt", FoodRules.classify("Arla Ko", listOf("en:dairies", "en:fermented-milk-products", "en:yogurts")).key)
    }

    @Test
    fun mincePackWithoutDateKeepsTwoDaysAndIsStrict() {
        val item = FridgeItem(name = "Köttfärs", category = "mince", bought = "2026-10-14")
        val e = FoodRules.estimate(item, today)
        assertEquals(LocalDate.of(2026, 10, 16), e.date)
        assertEquals(Basis.Guessed, e.basis)
        assertTrue(e.strict)
        assertEquals(Urgency.Soon, e.urgency)
        val later = FoodRules.estimate(item, LocalDate.of(2026, 10, 17))
        assertEquals(Urgency.Gone, later.urgency)
        assertEquals("Expired", later.label())
        assertTrue(later.advice()!!.contains("Throw"))
    }

    @Test
    fun openingShortensTheDateButNeverLengthensIt() {
        val milk = FridgeItem(name = "Mjölk", category = "milk", bought = "2026-10-10", date = "2026-10-24")
        assertEquals(LocalDate.of(2026, 10, 24), FoodRules.estimate(milk, today).date)
        val opened = milk.copy(opened = "2026-10-14")
        val e = FoodRules.estimate(opened, today)
        assertEquals(LocalDate.of(2026, 10, 19), e.date)
        assertEquals(Basis.Opened, e.basis)
        // Opened the day before the printed date: the printed date still holds.
        val late = milk.copy(opened = "2026-10-23")
        assertEquals(LocalDate.of(2026, 10, 24), FoodRules.estimate(late, today).date)
        // Past a best-before date is a sniff test, not the bin.
        val old = FoodRules.estimate(milk, LocalDate.of(2026, 10, 26))
        assertFalse(old.strict)
        assertEquals("Past best before", old.label())
    }

    @Test
    fun freezingStopsTheClockAndThawingRestartsIt() = runBlocking {
        val item = fridge.add(FridgeItem(name = "Kycklingfilé", category = "poultry", bought = "2026-10-14"))
        fridge.move(item.id, Place.Freezer)
        val frozen = fridge.get(item.id)!!
        assertEquals(Basis.Frozen, fridge.estimate(frozen).basis)
        assertEquals(LocalDate.of(2027, 7, 11), fridge.estimate(frozen).date)
        today = LocalDate.of(2026, 12, 1)
        fridge.move(item.id, Place.Fridge)
        val thawed = fridge.get(item.id)!!
        assertEquals("2026-12-01", thawed.thawed)
        assertEquals(LocalDate.of(2026, 12, 2), fridge.estimate(thawed).date)
    }

    @Test
    fun scanningLooksUpOnceThenRemembers() = runBlocking {
        val code = ScanCodes.parse("7310865004703")!!
        val first = fridge.identify(code)
        assertEquals(Source.Database, first.source)
        assertEquals("Mellanmjölk 1,5%", first.item.name)
        assertEquals("milk", first.item.category)
        assertEquals("1 l", first.item.amount)
        // The user renames it before saving; the next scan uses that name without a lookup.
        fridge.add(first.item.copy(name = "Mjölk"))
        val again = fridge.identify(code)
        assertEquals(Source.Known, again.source)
        assertEquals("Mjölk", again.item.name)
        assertEquals(1, lookups)
        // Same thing the same day adds to the count.
        fridge.add(again.item)
        assertEquals(1, fridge.items.value.size)
        assertEquals(2, fridge.items.value.single().quantity)
        // It is all saved.
        val reread = Fridge(dir, listOf(products), date = { today }).also { it.load() }
        assertEquals(2, reread.items.value.single().quantity)
        assertEquals(Source.Known, reread.identify(code).source)
    }

    @Test
    fun inStoreLabelsAreLearnedByItemNumber() = runBlocking {
        // A weighed pack from the meat counter: prefix 2, item number, price.
        val code = ScanCodes.parse("2341234012990".let { it.dropLast(1) + checkDigit(it.dropLast(1)) })!!
        assertTrue(code.inStore)
        assertNull(code.gtin)
        val unknown = fridge.identify(code)
        assertEquals(Source.Unknown, unknown.source)
        assertEquals(0, lookups)
        fridge.add(unknown.item.copy(name = "Entrecote", category = "meat"))
        // Another pack, another price, same item number.
        val next = ScanCodes.parse("2341234025670".let { it.dropLast(1) + checkDigit(it.dropLast(1)) })!!
        val known = fridge.identify(next)
        assertEquals(Source.Known, known.source)
        assertEquals("Entrecote", known.item.name)
    }

    @Test
    fun unknownBarcodesComeBackBlankWithAReason() = runBlocking {
        val code = ScanCodes.parse("4006381333931")!!
        val r = fridge.identify(code)
        assertEquals(Source.Unknown, r.source)
        assertNotNull(r.problem)
    }

    @Test
    fun gs1DataMatrixCarriesTheUseByDateAndWeight() {
        val code = ScanCodes.parse("0107310865004703\u001d10AB12\u001d17261020\u001d3103000532")!!
        assertEquals("7310865004703", code.gtin)
        assertEquals(LocalDate.of(2026, 10, 20), code.date)
        assertEquals(DateKind.UseBy, code.dateKind)
        assertEquals("AB12", code.batch)
        assertEquals("0.532 kg", code.amount)
        // Fixed-length fields need no separator.
        val packed = ScanCodes.parse("010731086500470315261031")!!
        assertEquals(LocalDate.of(2026, 10, 31), packed.date)
        assertEquals(DateKind.BestBefore, packed.dateKind)
        val bracketed = ScanCodes.parse("(01)07310865004703(15)261100")!!
        assertEquals(LocalDate.of(2026, 11, 30), bracketed.date)
    }

    @Test
    fun gs1DigitalLinkQrCodes() {
        val code = ScanCodes.parse("https://id.gs1.se/01/07310865004703/10/LOT9?17=261105")!!
        assertEquals("7310865004703", code.gtin)
        assertEquals("LOT9", code.batch)
        assertEquals(LocalDate.of(2026, 11, 5), code.date)
        val other = ScanCodes.parse("https://example.com/menu")!!
        assertNull(other.gtin)
        assertEquals("qr:https://example.com/menu", other.key)
    }

    @Test
    fun badCheckDigitsAreRejected() {
        assertNull(ScanCodes.parse("7310865004704"))
        assertEquals("0012345678905", ScanCodes.parse("012345678905")!!.gtin)
    }

    @Test
    fun theAssistantCanListAddAndTakeOut() = runBlocking {
        val tools = FridgeTools(fridge)
        fun call(name: String, vararg args: Pair<String, Any>) = ToolCall("1", name, JsonObject(args.associate {
            it.first to (if (it.second is Number) JsonPrimitive(it.second as Number) else JsonPrimitive(it.second.toString()))
        }))
        assertTrue(tools.run(call("fridge_contents"))!!.text.contains("Nothing is tracked"))
        val added = tools.run(call("add_to_fridge", "name" to "köttfärs", "quantity" to 2))!!.text
        assertTrue(added, added.contains("Mince"))
        tools.run(call("add_to_fridge", "name" to "Prästost", "date" to "2026-12-01"))
        assertEquals(2, fridge.items.value.size)
        val soon = tools.run(call("fridge_contents", "within_days" to 2))!!.text
        assertTrue(soon, soon.contains("Köttfärs") && !soon.contains("Prästost"))
        val desc = fridge.describe()!!
        assertTrue(desc, desc.contains("Köttfärs"))
        tools.run(call("update_fridge_item", "item" to "osten", "opened" to "true"))
        assertEquals("2026-10-14", fridge.items.value.first { it.name == "Prästost" }.opened)
        val out = tools.run(call("remove_from_fridge", "item" to "färsen", "quantity" to 1))!!.text
        assertTrue(out, out.contains("1 left"))
        tools.run(call("remove_from_fridge", "item" to "köttfärs"))
        assertEquals(listOf("Prästost"), fridge.items.value.map { it.name })
    }

    @Test
    fun openFoodFactsAnswersAreRead() {
        val body = """{"code":"7310865004703","status":1,"product":{"product_name":"Mellanmjölk","product_name_sv":"Mellanmjölk 1,5 %","brands":"Arla Ko, Arla","quantity":"1 l","categories_tags":["en:dairies","en:milks"]}}"""
        val p = OpenFoodFacts.parse(body)!!
        assertEquals("Mellanmjölk 1,5 %", p.name)
        assertEquals("Arla Ko", p.brand)
        assertEquals(listOf("en:dairies", "en:milks"), p.tags)
        assertNull(OpenFoodFacts.parse("""{"status":0,"status_verbose":"product not found"}"""))
    }

    private fun checkDigit(body: String): Int {
        val sum = body.reversed().mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 3 else 1 }.sum()
        return (10 - sum % 10) % 10
    }
}
