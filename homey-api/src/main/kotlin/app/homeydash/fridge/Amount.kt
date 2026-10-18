package app.homeydash.fridge

import java.math.BigDecimal
import java.math.RoundingMode

/** A pack size: how much and in what unit. */
data class Amount(val value: BigDecimal, val unit: String) {
    override fun toString(): String = value.stripTrailingZeros().toPlainString().replace('.', ',') + " " + unit
}

/**
 * Pack sizes in the unit that fits the food: drinks, milk and juice in litres (or dl, cl, ml),
 * eggs by the piece, everything else in grams and kilos. "1000ml" becomes "1 l", "0.5kg"
 * "500 g", a bare "1,5" for milk "1,5 l".
 */
object Amounts {
    val MASS = listOf("g", "kg")
    val VOLUME = listOf("ml", "cl", "dl", "l")
    const val PIECES = "st"
    val UNITS = MASS + VOLUME + PIECES

    private val LIQUID = setOf("milk", "juice", "drinks")
    private val PATTERN = Regex("""(\d+(?:[.,]\d+)?)\s*(kg|g|gr|gram|ml|cl|dl|l|liter|litre|lit|st|pcs|pack|x)?\b""", RegexOption.IGNORE_CASE)

    /** The unit a bare number means for a [category] key. */
    fun unitFor(category: String): String = when (category) {
        in LIQUID -> "l"
        "eggs" -> PIECES
        else -> "g"
    }

    fun isLiquid(category: String) = category in LIQUID

    /** Reads "500 g", "1,5L", "6 st"; null when there is no number. "2 x 125 g" reads as 250 g. */
    fun parse(text: String?, category: String? = null): Amount? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        Regex("""(\d+)\s*[x×]\s*(\d+(?:[.,]\d+)?)\s*(kg|g|ml|cl|dl|l)\b""", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            val total = BigDecimal(m.groupValues[1]) * number(m.groupValues[2])
            return Amount(total, unit(m.groupValues[3])!!)
        }
        val m = PATTERN.find(t) ?: return null
        val n = number(m.groupValues[1])
        val u = unit(m.groupValues[2]) ?: category?.let(::unitFor) ?: return null
        return Amount(n, u)
    }

    /**
     * [text] tidied into the right unit for [category], or [text] as it was when it can't be
     * read. Drinks given in grams are taken as millilitres (near enough for milk and juice).
     */
    fun normalize(text: String?, category: String): String? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val a = parse(t, category) ?: return t
        val fixed = if (isLiquid(category) && a.unit in MASS) Amount(if (a.unit == "kg") a.value * THOUSAND else a.value, "ml") else a
        return tidy(fixed).toString()
    }

    /** 1000 ml is 1 l and 1500 g is 1,5 kg; 0,5 l stays, 0,25 kg becomes 250 g. */
    fun tidy(a: Amount): Amount = when (a.unit) {
        "ml" -> if (a.value >= THOUSAND) Amount(a.value.divide(THOUSAND), "l") else a
        "g" -> if (a.value >= THOUSAND) Amount(a.value.divide(THOUSAND), "kg") else a
        "kg" -> if (a.value < BigDecimal.ONE) Amount(a.value * THOUSAND, "g") else a
        else -> a
    }.let { Amount(it.value.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros(), it.unit) }

    private val THOUSAND = BigDecimal(1000)

    private fun number(s: String) = BigDecimal(s.replace(',', '.'))

    private fun unit(s: String): String? = when (s.lowercase()) {
        "g", "gr", "gram" -> "g"
        "kg" -> "kg"
        "ml" -> "ml"
        "cl" -> "cl"
        "dl" -> "dl"
        "l", "liter", "litre", "lit" -> "l"
        "st", "pcs", "pack", "x" -> PIECES
        else -> null
    }
}
