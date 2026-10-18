package app.homeydash.fridge

import java.time.LocalDate
import java.time.YearMonth

/** A date read off a pack, and what kind of date the words next to it say it is. */
data class FoundDate(val date: LocalDate, val kind: DateKind?, val text: String)

/**
 * Finds the eat-by date in text read off a pack by the camera: "Bäst före 20.10.2026",
 * "BF 201026", "Sista förbrukningsdag 2026-10-20", "Best before 20 OCT 26", "Use by 20/10".
 * A date next to such words wins; a bare date counts only with its year, so prices,
 * weights and batch numbers are left alone. Dates far in the past or future are ignored.
 */
object PackDate {
    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "maj" to 5, "may" to 5, "jun" to 6, "jul" to 7,
        "aug" to 8, "sep" to 9, "okt" to 10, "oct" to 10, "nov" to 11, "dec" to 12,
    )

    private val USE_BY = Regex(
        """(sista\s*f[öo0]rbr\w*\s*(dag|datum)?|anv[äa]nd\s*senast|f[öo]rbrukas\s*senast|use\s*by|exp\w*|utg[åa]r)""",
        RegexOption.IGNORE_CASE,
    )
    private val BEST_BEFORE = Regex(
        """(b[äa]st\s*f[öo0]re(\s*(utg[åa]ngen\s*av|slutet\s*av))?|b\.?\s*f\.?(?=[\s:.]*\d)|best\s*before(\s*end)?|bbe?|mhd|holdbar\s*til)""",
        RegexOption.IGNORE_CASE,
    )

    private val NUMERIC = Regex("""(?<![\d.,])(\d{4})[-./ ](\d{1,2})[-./ ](\d{1,2})(?![\d,])|(?<![\d.,])(\d{1,2})\s?[-./]\s?(\d{1,2})\s?[-./]\s?(\d{2,4})(?![\d,])""")
    private val SHORT = Regex("""(?<![\d.,/-])(\d{1,2})\s?[./-]\s?(\d{1,2})(?![\d.,/-])""")
    private val COMPACT = Regex("""(?<!\d)(\d{6}|\d{8})(?!\d)""")
    private val WORDED = Regex("""(?<!\d)(\d{1,2})\.?\s*([a-zåäö]{3})[a-zåäö]*\.?\s*(\d{2,4})?(?![\d])""", RegexOption.IGNORE_CASE)

    /** The best date in [text] seen on [today], or null. */
    fun find(text: String, today: LocalDate): FoundDate? {
        val clean = text.replace('\n', ' ').replace(Regex("""\s+"""), " ")
        val labels = (USE_BY.findAll(clean).map { it.range.last to DateKind.UseBy } + BEST_BEFORE.findAll(clean).map { it.range.last to DateKind.BestBefore })
            .sortedBy { it.first }.toList()
        val candidates = mutableListOf<Candidate>()
        NUMERIC.findAll(clean).forEach { m ->
            val g = m.groupValues
            val date = if (g[1].isNotEmpty()) make(g[1].toInt(), g[2].toInt(), g[3].toInt(), today)
            else make(year(g[6]), g[5].toInt(), g[4].toInt(), today)
            date?.let { candidates += Candidate(it, m.range.first, m.value, hasYear = true) }
        }
        WORDED.findAll(clean).forEach { m ->
            val month = MONTHS[m.groupValues[2].lowercase().take(3)] ?: return@forEach
            val y = m.groupValues[3].takeIf { it.isNotEmpty() }
            val date = if (y != null) make(year(y), month, m.groupValues[1].toInt(), today) else nextOccurrence(month, m.groupValues[1].toInt(), today)
            date?.let { candidates += Candidate(it, m.range.first, m.value, hasYear = y != null) }
        }
        SHORT.findAll(clean).forEach { m ->
            if (candidates.any { m.range.first in it.at until it.at + it.text.length }) return@forEach
            nextOccurrence(m.groupValues[2].toInt(), m.groupValues[1].toInt(), today)?.let { candidates += Candidate(it, m.range.first, m.value, hasYear = false) }
        }
        COMPACT.findAll(clean).forEach { m ->
            val s = m.value
            val date = if (s.length == 6) make(year(s.substring(4)), s.substring(2, 4).toInt(), s.take(2).toInt(), today)
                ?: make(year(s.take(2)), s.substring(2, 4).toInt(), s.substring(4).toInt(), today)
            else make(s.take(4).toInt(), s.substring(4, 6).toInt(), s.substring(6).toInt(), today)
                ?: make(s.substring(4).toInt(), s.substring(2, 4).toInt(), s.take(2).toInt(), today)
            date?.let { candidates += Candidate(it, m.range.first, s, hasYear = true, compact = true) }
        }
        if (candidates.isEmpty()) return null

        // A date just after "bäst före" or the like, within a few words.
        val labelled = candidates.mapNotNull { c ->
            val label = labels.lastOrNull { it.first < c.at && c.at - it.first <= 24 } ?: return@mapNotNull null
            Triple(c, label.second, c.at - label.first)
        }.minByOrNull { it.third }
        if (labelled != null) return FoundDate(labelled.first.date, labelled.second, labelled.first.text)

        // Without a label only a whole date with its year, not a run of digits.
        val bare = candidates.filter { it.hasYear && !it.compact && it.date >= today.minusDays(14) }.minByOrNull { it.date } ?: return null
        return FoundDate(bare.date, null, bare.text)
    }

    private class Candidate(val date: LocalDate, val at: Int, val text: String, val hasYear: Boolean, val compact: Boolean = false)

    private fun year(s: String): Int = s.toInt().let { if (it < 100) 2000 + it else it }

    private fun make(year: Int, month: Int, day: Int, today: LocalDate): LocalDate? {
        if (month !in 1..12 || day !in 0..31) return null
        val d = runCatching { if (day == 0) YearMonth.of(year, month).atEndOfMonth() else LocalDate.of(year, month, day) }.getOrNull() ?: return null
        return d.takeIf { it >= today.minusDays(60) && it <= today.plusYears(4) }
    }

    /** "20.10" with no year: the next 20 October, or this year's if it passed only lately. */
    private fun nextOccurrence(month: Int, day: Int, today: LocalDate): LocalDate? {
        val thisYear = make(today.year, month, day, today) ?: return make(today.year + 1, month, day, today)
        return if (thisYear >= today.minusDays(30)) thisYear else make(today.year + 1, month, day, today)
    }
}
