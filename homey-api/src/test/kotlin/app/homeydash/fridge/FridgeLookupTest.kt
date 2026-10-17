package app.homeydash.fridge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate

class FridgeLookupTest {
    private val today = LocalDate.of(2026, 10, 17)

    private fun date(text: String) = PackDate.find(text, today)

    @Test
    fun readsSwedishAndEnglishPackDates() {
        assertEquals(FoundDate(LocalDate.of(2026, 10, 20), DateKind.BestBefore, "20.10.2026"), date("Bäst före 20.10.2026"))
        assertEquals(DateKind.UseBy, date("Sista förbrukningsdag\n2026-10-22")?.kind)
        assertEquals(LocalDate.of(2026, 10, 22), date("Sista förbrukningsdag\n2026-10-22")?.date)
        assertEquals(LocalDate.of(2026, 10, 20), date("BF 201026 L2341")?.date)
        assertEquals(LocalDate.of(2026, 10, 20), date("Best before 20 OCT 26")?.date)
        assertEquals(LocalDate.of(2026, 10, 25), date("Use by 25/10")?.date)
        assertEquals(LocalDate.of(2027, 3, 31), date("Bäst före utgången av 00.03.2027")?.date)
        // A bare whole date counts, but no label means no kind.
        val bare = date("Packad 2026-10-15 2026-10-29")
        assertEquals(LocalDate.of(2026, 10, 15), bare?.date)
        assertNull(bare?.kind)
    }

    @Test
    fun leavesPricesWeightsAndBatchesAlone() {
        assertNull(date("Jfr pris 129,00 kr/kg 0,532 kg"))
        assertNull(date("Batch 241017 12.50"))
        assertNull(date("Packad 2019-01-01"))
    }

    @Test
    fun readsTheAiAnswerEvenWithTextAround() {
        val r = ProductResearch.parse("Here it is:\n```json\n{\"name\":\"Kvarg Vanilj\",\"brand\":\"Lindahls\",\"size\":\"500 g\",\"kind\":\"yoghurt\",\"keeps_days\":\"28\",\"opened_days\":4,\"use_by\":false}\n```")
        assertNotNull(r)
        assertEquals("Kvarg Vanilj", r!!.product.name)
        assertEquals("Lindahls", r.product.brand)
        assertEquals("yoghurt", r.category)
        assertEquals(28, r.keepsDays)
        assertEquals(4, r.openedDays)
        assertEquals(false, r.useBy)
        assertNull(ProductResearch.parse("{\"name\":null}"))
        assertNull(ProductResearch.parse("I couldn't find it."))
    }

    @Test
    fun readsUpcItemDb() {
        val p = UpcItemDb.parse("""{"code":"OK","total":1,"items":[{"ean":"0012000161155","title":"Pepsi Cola 12 oz","brand":"Pepsi","size":"12 oz"}]}""")
        assertEquals("Pepsi Cola 12 oz", p?.name)
        assertEquals("Pepsi", p?.brand)
        assertNull(UpcItemDb.parse("""{"code":"OK","total":0,"items":[]}"""))
    }

    @Test
    fun theAiFillsInWhatNoDatabaseKnowsOnceAndItIsRemembered() = runBlocking {
        var asked = 0
        val ai = ProductResearch { prompt ->
            asked++
            assertTrue(prompt.contains("735000123456"))
            """{"name":"Laxfilé","brand":"Fiskeriet","size":"250 g","kind":"fish","keeps_days":6,"opened_days":1,"use_by":true}"""
        }
        val dir = Files.createTempDirectory("fridge").toFile()
        val fridge = Fridge(dir, listOf(ProductSource { null }, ProductSource { null }), ai, date = { today }).also { it.load() }
        val code = ScanCodes.parse("7350001234561".let { it.dropLast(1) + check(it.dropLast(1)) })!!
        val found = fridge.identify(code)
        assertEquals(Source.Ai, found.source)
        assertEquals("Laxfilé", found.item.name)
        assertEquals("fish", found.item.category)
        assertEquals(6, found.item.keeps)
        assertEquals(DateKind.UseBy, found.item.dateKind)
        assertEquals(LocalDate.of(2026, 10, 23), fridge.estimate(found.item).date)
        assertEquals(Source.Known, fridge.identify(code).source)
        assertEquals(1, asked)
    }

    @Test
    fun learnsHowLongAProductKeepsFromItsPackDates() = runBlocking {
        val dir = Files.createTempDirectory("fridge").toFile()
        val fridge = Fridge(dir, emptyList(), date = { today }).also { it.load() }
        val code = ScanCodes.parse("7310865004703")!!
        val blank = fridge.identify(code).item
        // Three packs, dated 10, 12 and 30 days after buying: the middle one counts.
        for ((i, keeps) in listOf(10, 12, 30).withIndex()) {
            fridge.add(blank.copy(name = "Mjölk", category = "milk", bought = today.minusDays(i.toLong()).toString(), date = today.minusDays(i.toLong()).plusDays(keeps.toLong()).toString()))
        }
        val item = fridge.items.value.first()
        fridge.save(item.copy(note = "saved again"))
        assertEquals(listOf(10, 12, 30), fridge.knownProduct(code.key)?.keeps)
        val next = fridge.identify(code).item
        assertEquals(12, next.keeps)
        assertEquals(Basis.Product, fridge.estimate(next).basis)
        assertEquals(today.plusDays(12), fridge.estimate(next).date)
    }

    private fun check(digits: String): Int {
        val sum = digits.reversed().mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 3 else 1 }.sum()
        return (10 - sum % 10) % 10
    }
}
