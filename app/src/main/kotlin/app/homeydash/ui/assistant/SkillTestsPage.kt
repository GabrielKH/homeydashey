package app.homeydash.ui.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.homeydash.agent.evals.EvalReport
import app.homeydash.agent.evals.EvalRun
import app.homeydash.assistant.AssistantServices
import app.homeydash.assistant.SkillTests
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.pressable
import app.homeydash.ui.dashboard.SettingsBox
import app.homeydash.ui.theme.GlassTheme

/** Settings → Skill tests: run every skill test against the brains set up here, and see how each skill did. */
@Composable
internal fun SkillTestsPage(services: AssistantServices) {
    val g = GlassTheme.colors
    val tests = services.skillTests
    var size by rememberSaveable { mutableStateOf(SkillTests.Size.Quick) }
    var which by rememberSaveable { mutableStateOf(SkillTests.Which.Local) }
    Hint(
        "Asks the assistant every skill many ways, in Swedish and English, in a practice house, and checks what it did and said. " +
            "Nothing in your home is switched and nothing is remembered. The report goes to the \"Nova skill tests\" issue on GitHub " +
            "with the token under Logs for Claude, so Claude can see what to make better. The local AI is busy meanwhile, and its first " +
            "real answer afterwards is slower while it reads your home again.",
        short = "Tests every skill in a practice house; your home isn't touched.",
    )
    SettingsBox(null) {
        Segmented(SkillTests.Size.entries, size, { it.label }, enabled = !tests.running) { size = it }
        val count = remember(size) { tests.count(size) }
        Hint("${size.detail} $count questions per brain.")
        Segmented(SkillTests.Which.entries, which, { it.label }, enabled = !tests.running) { which = it }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (tests.running) "Stop" else "Test all skills",
                style = MaterialTheme.typography.labelLarge,
                color = g.fg,
                modifier = Modifier.pressable({ if (tests.running) tests.stop() else tests.start(size, which) })
                    .glass(PillShape).padding(horizontal = 18.dp, vertical = 10.dp),
            )
            Text(
                when {
                    tests.running -> "${tests.done}/${tests.total} · ${tests.now.orEmpty()}"
                    else -> tests.status ?: if (tests.brains(which).isEmpty()) "Set up the ${which.label.lowercase()} under AI and servers first." else ""
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (tests.status?.startsWith("The tests stopped") == true) g.danger else g.muted,
                modifier = Modifier.weight(1f),
            )
        }
    }
    for (run in tests.last) LastRun(run)
}

/** How each skill did in [run], and the cases that failed. */
@Composable
private fun LastRun(run: EvalRun) {
    val g = GlassTheme.colors
    val all = EvalReport.total(run)
    val time = java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.ofEpochMilli(run.started))
    SettingsBox("${if (run.local) "Local AI" else "Internet AI"} · ${run.model} · $time") {
        Text(
            "${all.casesPassed} of ${all.cases} cases passed every try" + (if (run.stopped) " (stopped early)" else "") +
                (all.medianMs?.let { " · ${it / 100 / 10.0} s per question" } ?: ""),
            style = MaterialTheme.typography.titleMedium,
            color = g.fg,
        )
        for (s in EvalReport.scores(run)) {
            SettingsDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(s.skill.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.bodyLarge, color = g.fg, modifier = Modifier.weight(1f))
                Text(
                    EvalReport.line(s),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (s.casesPassed == s.cases && s.unasked == 0) g.ok else if (s.unasked > 0) g.danger else g.muted,
                )
            }
        }
        val failed = run.results.filter { !it.passed }.groupBy { it.case }
        if (failed.isNotEmpty()) {
            SettingsDivider()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label("What failed")
                failed.entries.take(MAX_FAILED).forEach { (case, runs) ->
                    val first = runs.first()
                    Text(
                        "$case · \"${first.phrase}\": ${first.problems.firstOrNull().orEmpty()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = g.muted,
                        maxLines = 3,
                    )
                }
                if (failed.size > MAX_FAILED) Text("…and ${failed.size - MAX_FAILED} more in the report on GitHub.", style = MaterialTheme.typography.bodyMedium, color = g.muted)
            }
        }
    }
}

private const val MAX_FAILED = 8
