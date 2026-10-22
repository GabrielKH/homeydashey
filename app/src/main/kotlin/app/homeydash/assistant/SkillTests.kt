package app.homeydash.assistant

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.homeydash.agent.AgentLog
import app.homeydash.agent.Connection
import app.homeydash.agent.GitHubLog
import app.homeydash.agent.evals.EvalOptions
import app.homeydash.agent.evals.EvalReport
import app.homeydash.agent.evals.EvalResult
import app.homeydash.agent.evals.EvalRun
import app.homeydash.agent.evals.SkillCases
import app.homeydash.agent.evals.SkillEvals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * Settings → Skill tests: asks the assistant every skill test with the brains set up on this
 * tablet (the local AI on the computer too, which the cloud can't reach), in a practice house,
 * and sends the report to the "Nova skill tests" issue on GitHub. Nothing in the real home is
 * touched and nothing is remembered; the assistant's own conversation is left alone.
 */
@Stable
class SkillTests(private val services: AssistantServices, private val http: OkHttpClient, private val scope: CoroutineScope) {
    /** How much to ask. */
    enum class Size(val label: String, val detail: String) {
        Quick("Quick", "The most important cases, one way of asking each, once, in Swedish and English."),
        Full("Full", "Every case, every way of asking, three tries each. Takes an hour or more on the local AI."),
    }

    /** Which brains to test. */
    enum class Which(val label: String) { Local("Local AI"), Internet("Internet AI"), Both("Both") }

    var running by mutableStateOf(false)
        private set
    var done by mutableIntStateOf(0)
        private set
    var total by mutableIntStateOf(0)
        private set
    /** The brain being tested and the last case, in words. */
    var now by mutableStateOf<String?>(null)
        private set
    /** The last run of each brain, local first. */
    var last by mutableStateOf<List<EvalRun>>(emptyList())
        private set
    /** Sent to GitHub, or what went wrong. */
    var status by mutableStateOf<String?>(null)
        private set

    private val dir = File(services.appContext.filesDir, "evals")
    private var job: Job? = null
    @Volatile private var stopping = false

    init {
        scope.launch(Dispatchers.IO) {
            last = listOf("local", "internet").mapNotNull { name ->
                File(dir, "last-$name.json").takeIf { it.exists() }?.let { runCatching { EvalRun.parse(it.readText()) }.getOrNull() }
            }
        }
    }

    /** How many questions [size] asks each brain. */
    fun count(size: Size): Int = runCatching {
        val (house, files) = SkillCases.load()
        SkillEvals(services.chat, http, dir, house).plan(files, options(size)).size
    }.getOrDefault(0)

    fun brains(which: Which): List<Connection> {
        val s = services.settings.value
        val local = s.local?.takeIf { it.usable }
        val internet = s.internet?.takeIf { it.usable }
        return when (which) {
            Which.Local -> listOfNotNull(local)
            Which.Internet -> listOfNotNull(internet)
            Which.Both -> listOfNotNull(local, internet)
        }
    }

    private fun options(size: Size) = when (size) {
        Size.Quick -> EvalOptions(trials = 1, allPhrasings = false, only = setOf("smoke"), base = services.settings.value)
        Size.Full -> EvalOptions(trials = 3, base = services.settings.value)
    }

    fun start(size: Size, which: Which) {
        if (running) return
        val brains = brains(which).ifEmpty { status = "Set up the ${which.label.lowercase()} under AI and servers first."; return }
        stopping = false
        running = true
        status = null
        job = scope.launch {
            try {
                val runs = withContext(Dispatchers.IO) {
                    val (house, files) = SkillCases.load()
                    val evals = SkillEvals(services.chat, http, File(services.appContext.cacheDir, "evals"), house)
                    val options = options(size)
                    val planned = evals.plan(files, options)
                    brains.map { brain ->
                        done = 0
                        total = planned.size
                        now = brain.provider.label
                        val run = evals.run(brain, planned, options, stop = { stopping }) { d, _, r ->
                            done = d
                            now = "${brain.provider.label}: ${r.case} " + if (r.passed) "passed" else "failed"
                        }
                        val name = if (brain.local) "local" else "internet"
                        val previous = File(dir, "last-$name.json").takeIf { it.exists() }?.let { runCatching { EvalRun.parse(it.readText()) }.getOrNull() }
                        dir.mkdirs()
                        File(dir, "last-$name.json").writeText(EvalRun.write(run))
                        File(dir, "report-$name.md").writeText(EvalReport.markdown(run, previous, android.os.Build.MODEL))
                        run to previous
                    }
                }
                last = runs.map { it.first }.sortedBy { !it.local }
                send(runs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "The tests stopped: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                running = false
                now = null
            }
        }
    }

    fun stop() {
        stopping = true
    }

    /** Failed runs of the last tests, worst skills first, for the screen. */
    fun failures(run: EvalRun): List<EvalResult> = run.results.filter { !it.passed }

    private suspend fun send(runs: List<Pair<EvalRun, EvalRun?>>) {
        val s = services.settings.value
        if (s.logToken.isBlank()) {
            status = "Done. Add a GitHub token under Logs for Claude to send the reports."
            return
        }
        val github = GitHubLog(http, title = SkillEvals.ISSUE_TITLE, about = SkillEvals.ISSUE_ABOUT)
        status = try {
            for ((run, previous) in runs) github.post(s.logToken, s.logRepo.trim().ifBlank { AgentLog.DEFAULT_REPO }, EvalReport.markdown(run, previous, android.os.Build.MODEL))
            "Done, and sent to the \"${SkillEvals.ISSUE_TITLE}\" issue on GitHub."
        } catch (e: java.io.IOException) {
            "Done, but ${e.message?.replaceFirstChar(Char::lowercase) ?: "it couldn't be sent to GitHub."}"
        }
    }
}
