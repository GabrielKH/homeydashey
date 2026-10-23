package app.homeydash.agent.evals

import app.homeydash.agent.AgentLog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A test run in numbers, per skill and in all, and as Markdown for GitHub. */
object EvalReport {

    data class Score(
        val skill: String,
        /** Cases, and of them how many passed every try of every way of asking (pass^k). */
        val cases: Int,
        val casesPassed: Int,
        val runs: Int,
        val runsPassed: Int,
        /** Runs that changed something nobody asked for. */
        val unasked: Int,
        val medianMs: Long?,
        val medianFirstWordMs: Long?,
    ) {
        val caseRate: Double get() = if (cases == 0) 0.0 else casesPassed.toDouble() / cases
        val runRate: Double get() = if (runs == 0) 0.0 else runsPassed.toDouble() / runs
    }

    fun score(skill: String, results: List<EvalResult>): Score {
        val byCase = results.groupBy { it.case }
        return Score(
            skill, byCase.size, byCase.count { (_, r) -> r.all { it.passed } }, results.size, results.count { it.passed },
            results.count { it.unasked }, median(results.map { it.ms }), median(results.mapNotNull { it.firstWordMs }),
        )
    }

    fun scores(run: EvalRun): List<Score> = run.results.groupBy { it.skill }.map { (skill, r) -> score(skill, r) }

    fun total(run: EvalRun): Score = score("all", run.results)

    /** One line for the screen, like "Timers 5/6 cases · 28/30 runs". */
    fun line(s: Score): String = "${s.casesPassed}/${s.cases} cases · ${s.runsPassed}/${s.runs} runs" +
        (if (s.unasked > 0) " · ${s.unasked} unasked changes" else "")

    /**
     * The run as Markdown: the totals, a table per skill with the change since [previous]
     * (the last run with the same brain), and every failure with what went wrong and the transcript.
     */
    fun markdown(run: EvalRun, previous: EvalRun? = null, device: String? = null, zone: ZoneId = ZoneId.systemDefault(), only: String? = null): String = buildString {
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
        val all = total(run)
        val before = previous?.let { scores(it).associateBy { s -> s.skill } }
        append("### Skill tests · ").append(run.brain).append(" `").append(run.model).append("`")
        append(if (run.local) " (local)" else " (internet)")
        append(" · ").append(time.format(Instant.ofEpochMilli(run.started)))
        device?.let { append(" · ").append(it) }
        if (run.answers.isNotBlank()) append(" · answers in ").append(run.answers)
        only?.let { append(" · only ").append(it) }
        append("\n\n")
        append("**${pct(all.caseRate)} of cases passed every try** (${all.casesPassed}/${all.cases}, pass^${run.trials}) · ")
        append("${pct(all.runRate)} of runs passed (${all.runsPassed}/${all.runs})")
        if (all.unasked > 0) append(" · **${all.unasked} runs changed something nobody asked for**")
        all.medianMs?.let { append(" · median ").append(seconds(it)).append(" per question") }
        all.medianFirstWordMs?.let { append(", first word ").append(seconds(it)) }
        if (run.stopped) append("\n\n_Stopped before the end._")
        append("\n\n| Skill | Cases passed | Runs passed | Unasked changes | Median time | Since last run |\n|---|---|---|---|---|---|\n")
        for (s in scores(run)) {
            val was = before?.get(s.skill)
            val delta = was?.let { w -> ((s.caseRate - w.caseRate) * 100).toInt() }?.let { d -> if (d == 0) "same" else if (d > 0) "+$d %" else "$d %" } ?: "–"
            append("| ").append(s.skill).append(" | ").append("${s.casesPassed}/${s.cases}").append(" | ")
                .append("${s.runsPassed}/${s.runs}").append(" | ").append(s.unasked).append(" | ")
                .append(s.medianMs?.let(::seconds) ?: "–").append(" | ").append(delta).append(" |\n")
        }
        val failed = run.results.filter { !it.passed }
        if (failed.isEmpty()) { append("\nEverything passed.\n"); return@buildString }
        append("\n#### What failed\n")
        for ((case, runs) in failed.groupBy { it.case }) {
            append("\n**").append(case).append("** · ").append(runs.size).append(" failed run").append(if (runs.size == 1) "" else "s").append("\n")
            for (r in runs.take(MAX_SHOWN)) {
                append("- \"").append(r.phrase).append("\" (").append(r.lang).append(", try ").append(r.trial).append(", ").append(seconds(r.ms)).append(")\n")
                r.problems.forEach { append("  - ").append(AgentLog.redact(it).replace("\n", " ")).append("\n") }
                if (r.calls.isNotEmpty()) append("  - Calls: `").append(AgentLog.redact(r.calls.joinToString("; ")).take(500).replace("`", "'")).append("`\n")
                append("  <details><summary>Transcript</summary>\n\n  ```\n")
                r.transcript.forEach { append("  ").append(AgentLog.redact(it).replace("```", "ʼʼʼ").replace("\n", "\n  ")).append("\n") }
                append("  ```\n  </details>\n")
            }
            if (runs.size > MAX_SHOWN) append("- …and ${runs.size - MAX_SHOWN} more like it.\n")
        }
    }

    private fun median(values: List<Long>): Long? = values.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }

    private fun pct(rate: Double) = "${(rate * 100).toInt()} %"

    private fun seconds(ms: Long) = String.format(java.util.Locale.ROOT, "%.1f s", ms / 1000.0)

    private const val MAX_SHOWN = 3
}
