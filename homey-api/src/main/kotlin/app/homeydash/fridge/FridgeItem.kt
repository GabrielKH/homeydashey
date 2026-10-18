package app.homeydash.fridge

import kotlinx.serialization.Serializable
import java.time.LocalDate

/** What the date printed on a pack means. */
enum class DateKind {
    /** Bäst före: past it, look, smell and taste. */
    BestBefore,

    /** Sista förbrukningsdag: past it, throw it away. */
    UseBy,
}

/**
 * One thing in the fridge, freezer or pantry. Dates are ISO yyyy-mm-dd, so the file stays
 * readable and a phone in another time zone reads the same day.
 */
@Serializable
data class FridgeItem(
    val id: String = "",
    val name: String,
    val brand: String? = null,
    /** The barcode or QR key it was scanned with, to learn and look it up again. */
    val code: String? = null,
    /** A [FoodCategory.key]. */
    val category: String = FoodRules.other.key,
    val place: Place = Place.Fridge,
    val quantity: Int = 1,
    /** Pack size as printed, e.g. "500 g" or "1 l". */
    val amount: String? = null,
    val bought: String,
    /** The date printed on the pack, when known. */
    val date: String? = null,
    val dateKind: DateKind? = null,
    val opened: String? = null,
    /** Put in the freezer on this day. */
    val frozen: String? = null,
    /** Taken out of the freezer on this day. */
    val thawed: String? = null,
    val note: String? = null,
    /** How long this product keeps unopened, learned or looked up; null means the kind's usual time. */
    val keeps: Int? = null,
    /** How long this product keeps once opened, when known for this product. */
    val keepsOpened: Int? = null,
    /** When it was added, for sorting the newest first; epoch ms. */
    val added: Long = 0,
) {
    fun boughtDate(): LocalDate? = parse(bought)
    fun printedDate(): LocalDate? = parse(date)
    fun openedDate(): LocalDate? = parse(opened)
    fun frozenDate(): LocalDate? = parse(frozen)
    fun thawedDate(): LocalDate? = parse(thawed)

    val categoryInfo: FoodCategory get() = FoodRules.byKey(category)

    private fun parse(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}

/** What a barcode was saved as, so the next scan of it needs no lookup and no typing. */
@Serializable
data class KnownProduct(
    val name: String,
    val brand: String? = null,
    val category: String,
    val amount: String? = null,
    val place: Place? = null,
    /** Days from purchase to the date printed on the pack, from the last few packs saved with a date. */
    val keeps: List<Int> = emptyList(),
    /** What the internet AI said: days unopened and opened, and whether it is a use-by food. */
    val keepsDays: Int? = null,
    val openedDays: Int? = null,
    val useBy: Boolean? = null,
    /** Items whose dates are already in [keeps], so saving one again doesn't count it twice. */
    val seen: List<String> = emptyList(),
) {
    /** The best guess of how long it keeps unopened: what the packs showed, else what the AI found. */
    fun typicalDays(): Int? = keeps.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] } ?: keepsDays
}
