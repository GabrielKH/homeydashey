package app.homeydash.fridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

@Serializable
private class FridgeFile(
    val version: Int = 1,
    val items: List<FridgeItem> = emptyList(),
    /** Every product saved from a scan, by [ScanCode.key]. */
    val known: Map<String, KnownProduct> = emptyMap(),
)

/** Where a scanned product's details came from. */
enum class Source { Known, Database, Ai, Unknown }

data class Identified(val item: FridgeItem, val source: Source, val code: ScanCode, val problem: String? = null)

/**
 * What is in the fridge, freezer and pantry: one JSON file under [dir]. It also learns each
 * barcode it is shown, so the second pack of the same yoghurt is added without a lookup.
 */
class Fridge(
    private val dir: File,
    /** Product databases, asked in order until one knows the barcode. */
    private val products: List<ProductSource>,
    /** The internet AI, for barcodes no database knows or can't place; null when none is set up. */
    private val research: ProductResearch? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val date: () -> LocalDate = { LocalDate.now() },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val lock = Mutex()
    private val file = File(dir, "fridge.json")

    private val _items = MutableStateFlow<List<FridgeItem>>(emptyList())
    val items: StateFlow<List<FridgeItem>> = _items.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private var known: Map<String, KnownProduct> = emptyMap()

    suspend fun load() = lock.withLock {
        withContext(Dispatchers.IO) {
            val data = runCatching { json.decodeFromString<FridgeFile>(file.readText()) }.getOrElse {
                if (file.exists()) file.copyTo(File(dir, "fridge.broken-${clock()}.json"), overwrite = true)
                FridgeFile()
            }
            _items.value = data.items
            known = data.known
        }
        _loaded.value = true
    }

    fun today(): LocalDate = date()

    fun get(id: String): FridgeItem? = _items.value.firstOrNull { it.id == id }

    fun estimate(item: FridgeItem): Estimate = FoodRules.estimate(item, today())

    /** Everything, what to eat first at the top. */
    fun sorted(place: Place? = null): List<Pair<FridgeItem, Estimate>> =
        _items.value.filter { place == null || it.place == place }
            .map { it to estimate(it) }
            .sortedWith(compareBy<Pair<FridgeItem, Estimate>> { it.second.date }.thenBy { it.first.name.lowercase() })

    /** Things to eat within [days] days, or already past their date. */
    fun soon(days: Int = 2): List<Pair<FridgeItem, Estimate>> = sorted().filter { it.first.place != Place.Freezer && it.second.daysLeft <= days }

    fun knownProduct(key: String): KnownProduct? = known[key]

    /**
     * Turns a scanned code into an item to confirm: from what was saved for this code before,
     * else the product databases, else the internet AI, which also says how long the product
     * keeps; what it finds is saved, so it is asked once per product. A date in the code (GS1
     * 2D codes) is filled in.
     */
    suspend fun identify(code: ScanCode): Identified {
        val base = FridgeItem(
            name = "",
            code = code.key,
            bought = today().toString(),
            date = code.date?.toString(),
            dateKind = code.dateKind,
            amount = code.amount,
        )
        known[code.key]?.let { return Identified(fromKnown(base, it, code), Source.Known, code) }
        val gtin = code.gtin ?: return Identified(base, Source.Unknown, code)
        var problem: String? = null
        var product: Product? = null
        for (source in products) {
            product = try {
                source.find(gtin)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = "Couldn't look it up: ${e.message ?: e::class.simpleName}"
                null
            }
            if (product != null) break
        }
        val category = product?.let { FoodRules.classify(it.name, it.tags) }
        if (research != null && (product == null || category == FoodRules.other)) {
            val found = try {
                research.research(gtin, product)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (product == null) problem = "The internet AI couldn't look it up: ${e.message ?: e::class.simpleName}"
                null
            }
            if (found != null) {
                val kind = found.category?.let { FoodRules.byKey(it) }?.takeIf { it != FoodRules.other }
                    ?: category?.takeIf { it != FoodRules.other }
                    ?: FoodRules.classify(found.product.name, product?.tags.orEmpty())
                val learned = KnownProduct(
                    name = product?.name ?: found.product.name,
                    brand = product?.brand ?: found.product.brand,
                    category = kind.key,
                    amount = product?.amount ?: found.product.amount,
                    place = kind.place,
                    keepsDays = found.keepsDays,
                    openedDays = found.openedDays,
                    useBy = found.useBy,
                )
                remember(code.key) { learned }
                return Identified(fromKnown(base, learned, code), Source.Ai, code)
            }
        }
        if (product == null || category == null) {
            return Identified(base, Source.Unknown, code, problem = problem ?: "No product database knows this barcode.")
        }
        return Identified(
            base.copy(name = product.name, brand = product.brand, category = category.key, place = category.place, amount = code.amount ?: product.amount),
            Source.Database, code,
        )
    }

    private fun fromKnown(base: FridgeItem, k: KnownProduct, code: ScanCode) = base.copy(
        name = k.name,
        brand = k.brand,
        category = k.category,
        place = k.place ?: FoodRules.byKey(k.category).place,
        amount = code.amount ?: k.amount,
        dateKind = base.dateKind ?: k.useBy?.let { if (it) DateKind.UseBy else DateKind.BestBefore },
        keeps = k.typicalDays(),
        keepsOpened = k.openedDays,
    )

    /**
     * Adds [item], or adds to the count of the same thing bought the same day (scanning three
     * yoghurts makes one line of three). Remembers a scanned product's name and category.
     */
    suspend fun add(item: FridgeItem): FridgeItem {
        val clean = item.copy(name = item.name.trim().ifBlank { FoodRules.byKey(item.category).en }, quantity = item.quantity.coerceAtLeast(1))
        var result = clean
        update { list ->
            val same = list.firstOrNull {
                it.code != null && it.code == clean.code && it.bought == clean.bought && it.date == clean.date &&
                    it.place == clean.place && it.opened == null && clean.opened == null && it.name == clean.name
            }
            if (same != null) {
                result = same.copy(quantity = same.quantity + clean.quantity)
                list.map { if (it.id == same.id) result else it }
            } else {
                result = clean.copy(id = UUID.randomUUID().toString(), added = clock(), frozen = clean.frozen ?: if (clean.place == Place.Freezer) clean.bought else null)
                list + result
            }
        }
        learn(result)
        return result
    }

    /** Saves changes to an item already in the fridge. */
    suspend fun save(item: FridgeItem) {
        update { list -> list.map { if (it.id == item.id) item else it } }
        learn(item)
    }

    suspend fun remove(id: String) = update { list -> list.filterNot { it.id == id } }

    /** One of them is used up; the last one takes the line away. Returns what is left (0 when gone). */
    suspend fun useOne(id: String): Int {
        var left = 0
        update { list ->
            list.mapNotNull {
                if (it.id != id) it else {
                    left = it.quantity - 1
                    if (left > 0) it.copy(quantity = left) else null
                }
            }
        }
        return left
    }

    suspend fun markOpened(id: String, opened: Boolean = true) = edit(id) { it.copy(opened = if (opened) today().toString() else null) }

    /** Moves an item; into the freezer stops the clock, out of it starts the thawed clock. */
    suspend fun move(id: String, to: Place) = edit(id) {
        when {
            it.place == to -> it
            to == Place.Freezer -> it.copy(place = to, frozen = today().toString(), thawed = null)
            it.place == Place.Freezer -> it.copy(place = to, thawed = today().toString())
            else -> it.copy(place = to)
        }
    }

    suspend fun edit(id: String, change: (FridgeItem) -> FridgeItem) = update { list -> list.map { if (it.id == id) change(it) else it } }

    /** One line for the assistant's instructions; null when nothing is tracked. */
    fun describe(): String? {
        val all = _items.value
        if (all.isEmpty()) return null
        val first = soon(2).take(6)
        return buildString {
            append("${all.size} things are tracked in the fridge, freezer and pantry.")
            if (first.isNotEmpty()) {
                append(" Use first: ")
                append(first.joinToString("; ") { (item, e) -> "${item.name} (${e.label()}${if (item.opened != null) ", opened" else ""})" })
                append('.')
            }
            append(" Use fridge_contents for the full list.\n")
        }
    }

    /**
     * Remembers a saved product's name and kind by its code, keeping what the AI found, and
     * learns how long it keeps from the days between buying it and the date on the pack.
     */
    private suspend fun learn(item: FridgeItem) {
        val key = item.code ?: return
        if (item.name.isBlank()) return
        remember(key) { old ->
            val days = item.printedDate()?.let { printed -> item.boughtDate()?.let { ChronoUnit.DAYS.between(it, printed).toInt() } }
                ?.takeIf { it in 0..3650 && item.thawed == null }
            val seen = old?.seen.orEmpty()
            val keeps = old?.keeps.orEmpty().let { if (days != null && item.id !in seen) (it + days).takeLast(KEEP_PACKS) else it }
            KnownProduct(
                name = item.name.trim(),
                brand = item.brand,
                category = item.category,
                amount = item.amount,
                place = item.place,
                keeps = keeps,
                keepsDays = old?.keepsDays,
                openedDays = old?.openedDays,
                useBy = item.dateKind?.let { it == DateKind.UseBy } ?: old?.useBy,
                seen = if (days != null && item.id.isNotEmpty() && item.id !in seen) (seen + item.id).takeLast(KEEP_PACKS) else seen,
            )
        }
    }

    private suspend fun remember(key: String, change: (KnownProduct?) -> KnownProduct) = lock.withLock {
        val product = change(known[key])
        if (known[key] == product) return@withLock
        known = known + (key to product)
        write()
    }

    private suspend fun update(change: (List<FridgeItem>) -> List<FridgeItem>) = lock.withLock {
        _items.value = change(_items.value)
        write()
    }

    private suspend fun write() = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val tmp = File(dir, "fridge.json.tmp")
        tmp.writeText(json.encodeToString(FridgeFile.serializer(), FridgeFile(items = _items.value, known = known)))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private companion object {
        /** How many packs' dates to learn from; the middle one counts. */
        const val KEEP_PACKS = 6
    }
}
