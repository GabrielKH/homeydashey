package app.homeydash.fridge

import java.net.URLDecoder
import java.time.LocalDate
import java.time.YearMonth

/**
 * What a scanned code says. [key] is what the app learns the product by: the GTIN for
 * normal packs, the store's item number for an in-store label, or the text of any other QR.
 */
data class ScanCode(
    val raw: String,
    val key: String,
    /** The product number to look up in Open Food Facts; null for in-store labels and plain QR codes. */
    val gtin: String? = null,
    /** A label printed in the store (weighed meat, fish, deli, cheese): no database knows it. */
    val inStore: Boolean = false,
    val date: LocalDate? = null,
    val dateKind: DateKind? = null,
    val batch: String? = null,
    /** Net weight from a GS1 code, e.g. "0.532 kg". */
    val amount: String? = null,
)

/**
 * Reads barcodes and QR codes from groceries: EAN-13/8 and UPC, in-store labels (EAN-13
 * starting 20-29), GS1 Data Matrix / GS1-128 element strings (with the FNC1 separator or
 * in "(01)…(17)…" form) and GS1 Digital Link QR codes (https://…/01/<gtin>/17/<date>).
 * The 2D codes can carry the best-before or use-by date.
 */
object ScanCodes {
    private const val GS = '\u001d'

    fun parse(text: String): ScanCode? {
        val raw = text.trim().removePrefix("]d2").removePrefix("]C1").removePrefix("]Q3").removePrefix("]e0").trim()
        if (raw.isEmpty()) return null
        if (raw.all { it.isDigit() } && raw.length in setOf(8, 12, 13, 14)) return linear(raw)
        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            digitalLink(raw)?.let { return it }
            return ScanCode(raw, key = "qr:$raw")
        }
        if (raw.startsWith("(")) bracketed(raw)?.let { return it }
        if (raw.contains(GS) || (raw.startsWith("01") && raw.length >= 16 && raw.take(16).all { it.isDigit() })) {
            elements(raw)?.let { return it }
        }
        return ScanCode(raw, key = "qr:$raw")
    }

    /** A plain product barcode. */
    private fun linear(digits: String): ScanCode? {
        val ean = when (digits.length) {
            12 -> "0$digits"
            14 -> if (digits.startsWith("0")) digits.drop(1) else digits
            else -> digits
        }
        if (!checkDigitOk(ean)) return null
        // GS1 prefixes 20-29 are for the store's own use: weighed and priced at the counter.
        if (ean.length == 13 && ean[0] == '2') return ScanCode(digits, key = "store:" + ean.take(7), inStore = true)
        return ScanCode(digits, key = ean, gtin = ean)
    }

    fun checkDigitOk(code: String): Boolean {
        if (code.length < 8 || !code.all { it.isDigit() }) return false
        val body = code.dropLast(1)
        val sum = body.reversed().mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 3 else 1 }.sum()
        return (10 - sum % 10) % 10 == code.last() - '0'
    }

    /** "(01)07310865004703(17)261020(10)AB12" */
    private fun bracketed(raw: String): ScanCode? {
        val pairs = Regex("""\((\d{2,4})\)([^(]*)""").findAll(raw).map { it.groupValues[1] to it.groupValues[2].trim() }.toList()
        if (pairs.isEmpty()) return null
        return fromAis(raw, pairs.toMap())
    }

    /** GS1 element string, as read from a GS1 Data Matrix or GS1-128. */
    private fun elements(raw: String): ScanCode? {
        val ais = linkedMapOf<String, String>()
        var i = 0
        val s = raw.trimStart(GS)
        while (i < s.length) {
            if (s[i] == GS) { i++; continue }
            val ai = aiAt(s, i) ?: break
            i += ai.length
            val fixed = FIXED[ai] ?: fixedFour(ai)
            val value = if (fixed != null) {
                if (i + fixed > s.length) break
                s.substring(i, i + fixed).also { i += fixed }
            } else {
                val end = s.indexOf(GS, i).let { if (it < 0) s.length else it }
                s.substring(i, end).also { i = end }
            }
            ais[ai] = value
        }
        if (ais.isEmpty() || (ais["01"] == null && ais["02"] == null)) return null
        return fromAis(raw, ais)
    }

    /** https://id.gs1.se/01/07310865004703/10/AB12?17=261020 */
    private fun digitalLink(url: String): ScanCode? {
        val noScheme = url.substringAfter("://")
        val path = noScheme.substringAfter('/', "").substringBefore('?').substringBefore('#')
        val parts = path.split('/').filter { it.isNotEmpty() }
        val start = parts.indexOfFirst { it == "01" || it == "gtin" }
        if (start < 0 || start + 1 >= parts.size) return null
        val ais = linkedMapOf<String, String>()
        var i = start
        while (i + 1 < parts.size) {
            val ai = if (parts[i] == "gtin") "01" else parts[i]
            ais[ai] = URLDecoder.decode(parts[i + 1], "UTF-8")
            i += 2
        }
        noScheme.substringAfter('?', "").split('&').forEach { pair ->
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            if (k.isNotEmpty() && k.all { it.isDigit() } && v.isNotEmpty()) ais[k] = URLDecoder.decode(v, "UTF-8")
        }
        if (ais["01"]?.all { it.isDigit() } != true) return null
        return fromAis(url, ais)
    }

    private fun fromAis(raw: String, ais: Map<String, String>): ScanCode? {
        val gtin14 = (ais["01"] ?: ais["02"])?.takeIf { it.all { c -> c.isDigit() } } ?: return null
        val gtin = if (gtin14.length == 14 && gtin14.startsWith("0")) gtin14.drop(1) else gtin14
        val inStore = gtin.length == 13 && gtin[0] == '2'
        val (date, kind) = when {
            ais["17"] != null -> gs1Date(ais["17"]!!) to DateKind.UseBy
            ais["15"] != null -> gs1Date(ais["15"]!!) to DateKind.BestBefore
            ais["16"] != null -> gs1Date(ais["16"]!!) to DateKind.BestBefore
            else -> null to null
        }
        val weight = ais.entries.firstOrNull { it.key.length == 4 && it.key.startsWith("310") }?.let { (ai, v) ->
            val decimals = ai.last() - '0'
            v.toLongOrNull()?.let { n -> java.math.BigDecimal.valueOf(n, decimals).stripTrailingZeros().toPlainString() + " kg" }
        }
        return ScanCode(
            raw = raw,
            key = if (inStore) "store:" + gtin.take(7) else gtin,
            gtin = gtin.takeUnless { inStore },
            inStore = inStore,
            date = date,
            dateKind = date?.let { kind },
            batch = ais["10"],
            amount = weight,
        )
    }

    /** YYMMDD; a day of 00 means the last day of the month. */
    fun gs1Date(s: String): LocalDate? {
        if (s.length != 6 || !s.all { it.isDigit() }) return null
        val year = 2000 + s.take(2).toInt()
        val month = s.substring(2, 4).toInt()
        val day = s.substring(4, 6).toInt()
        if (month !in 1..12) return null
        val ym = YearMonth.of(year, month)
        return runCatching { if (day == 0) ym.atEndOfMonth() else ym.atDay(day) }.getOrNull()
    }

    /** The application identifier starting at [i]: two digits for most, three or four for some. */
    private fun aiAt(s: String, i: Int): String? {
        if (i + 2 > s.length) return null
        val two = s.substring(i, i + 2)
        if (!two.all { it.isDigit() }) return null
        val three = s.substring(i, minOf(i + 3, s.length))
        val four = s.substring(i, minOf(i + 4, s.length))
        return when {
            two in TWO_DIGIT -> two
            two in setOf("24", "25", "40", "41", "42") -> three.takeIf { it.length == 3 }
            two[0] in "3789" -> four.takeIf { it.length == 4 }
            else -> null
        }
    }

    private fun fixedFour(ai: String): Int? = when {
        ai.length == 4 && ai[0] == '3' && ai[1] in "1234567" -> 6
        ai.length == 3 && ai.startsWith("41") -> 13
        else -> null
    }

    private val TWO_DIGIT = setOf("00", "01", "02", "10", "11", "12", "13", "15", "16", "17", "20", "21", "22", "30", "37", "90", "91", "92", "93", "94", "95", "96", "97", "98", "99")
    private val FIXED = mapOf("00" to 18, "01" to 14, "02" to 14, "11" to 6, "12" to 6, "13" to 6, "15" to 6, "16" to 6, "17" to 6, "20" to 2)
}
