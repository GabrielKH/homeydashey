package app.homeydash.agent.evals

import app.homeydash.agent.ChatClient
import app.homeydash.agent.Connection
import app.homeydash.agent.Provider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import kotlin.system.exitProcess

/**
 * Runs the skill tests against a real brain and writes `build/evals/report.md` and `run.json`:
 *
 *     ./gradlew :homey-api:evals -Peval.brain=gemini        (key in GEMINI_API_KEY)
 *     ./gradlew :homey-api:evals -Peval.brain=openai        (key in OPENAI_API_KEY)
 *     ./gradlew :homey-api:evals -Peval.brain=local -Peval.url=http://192.168.50.212:8000/v1 -Peval.model=qwen3.5-9b-4bit
 *
 * Also `-Peval.key=…`, `-Peval.trials=3`, `-Peval.quick=true` (first way of asking only),
 * `-Peval.only=timers,smoke` (skills, case ids or tags) and `-Peval.langs=sv`. Each setting can
 * also be an environment variable: EVAL_BRAIN, EVAL_URL and so on.
 */
fun main() {
    fun setting(name: String): String? =
        System.getProperty("eval.$name")?.takeIf { it.isNotBlank() } ?: System.getenv("EVAL_${name.uppercase()}")?.takeIf { it.isNotBlank() }

    val provider = when (setting("brain")?.lowercase() ?: "gemini") {
        "gemini", "google" -> Provider.Gemini
        "openai", "gpt" -> Provider.OpenAI
        "local", "ollama", "mlx", "qwen" -> Provider.Ollama
        else -> { System.err.println("eval.brain is gemini, openai or local."); exitProcess(2) }
    }
    val key = setting("key") ?: when (provider) {
        Provider.Gemini -> System.getenv("GEMINI_API_KEY")
        Provider.OpenAI -> System.getenv("OPENAI_API_KEY")
        Provider.Ollama -> null
    }.orEmpty()
    val brain = Connection(provider, setting("url") ?: provider.defaultUrl, key, setting("model") ?: provider.defaultModel)
    if (!brain.usable) { System.err.println("No key for ${provider.label}: set eval.key or ${if (provider == Provider.Gemini) "GEMINI_API_KEY" else "OPENAI_API_KEY"}."); exitProcess(2) }

    val quick = setting("quick") == "true"
    val options = EvalOptions(
        trials = setting("trials")?.toIntOrNull() ?: if (quick) 1 else 3,
        allPhrasings = !quick,
        languages = setting("langs")?.split(',')?.map { it.trim() }?.toSet() ?: setOf("sv", "en"),
        only = setting("only")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet(),
    )
    val out = File(setting("out") ?: "build/evals").apply { mkdirs() }
    val http = OkHttpClient()
    val (house, files) = SkillCases.load()
    val evals = SkillEvals(ChatClient(http), http, File(out, "work"), house)
    val planned = evals.plan(files, options)
    println("Asking ${brain.provider.label} (${brain.model}) ${planned.size} questions…")
    val run = runBlocking {
        evals.run(brain, planned, options, progress = { done, total, r ->
            println("[$done/$total] ${if (r.passed) "pass" else "FAIL"} ${r.case} \"${r.phrase}\" (${r.ms / 1000.0} s)" +
                if (r.passed) "" else "\n    " + r.problems.joinToString("\n    "))
        })
    }
    val previousFile = File(out, "last-${provider.name.lowercase()}.json")
    val previous = previousFile.takeIf { it.exists() }?.let { runCatching { EvalRun.parse(it.readText()) }.getOrNull() }
    val report = EvalReport.markdown(run, previous)
    File(out, "report.md").writeText(report)
    File(out, "run.json").writeText(EvalRun.write(run))
    previousFile.writeText(EvalRun.write(run))
    val all = EvalReport.total(run)
    println("\n${all.casesPassed}/${all.cases} cases passed every try, ${all.runsPassed}/${all.runs} runs. Report: ${File(out, "report.md").absolutePath}")
}
