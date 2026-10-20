package app.homeydash.updates

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a build contains, as CI writes it to `changes.json`: its number, the APK's size and the
 * newest commits. Each commit's first line is already written for people ("Timer island: kitchen
 * timers at the top…"), so it is the update's notes as is.
 */
@Serializable
data class BuildInfo(
    val build: Int = 0,
    val version: String = "",
    val apkBytes: Long = 0,
    val changes: List<Commit> = emptyList(),
) {
    @Serializable
    data class Commit(val sha: String, val date: String = "", val subject: String)
}

/** One change people can notice, from one commit. */
data class Change(val sha: String, val day: String, val title: String, val detail: String)

object ChangeLog {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String?): BuildInfo? =
        text?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(BuildInfo.serializer(), it) }.getOrNull() }

    /** The build's changes, newest first, without build fixes and merges nobody would notice. */
    fun changes(info: BuildInfo?): List<Change> = info?.changes.orEmpty().mapNotNull { commit ->
        val subject = commit.subject.trim()
        if (subject.isEmpty() || NOISE.any { subject.startsWith(it, ignoreCase = true) }) return@mapNotNull null
        val (title, detail) = split(subject)
        Change(commit.sha, commit.date.take(10), title, detail)
    }

    /** "Timer island: kitchen timers at the top" → "Timer island" and "Kitchen timers at the top". */
    fun split(subject: String): Pair<String, String> {
        val colon = subject.indexOf(": ")
        if (colon !in 1..MAX_TITLE) return subject to ""
        val detail = subject.substring(colon + 2).trim()
        return subject.substring(0, colon).trim() to detail.replaceFirstChar { it.uppercaseChar() }
    }

    /** Changes in [newer] that [current] doesn't have, newest first: what an update brings. */
    fun since(newer: List<Change>, current: List<Change>): List<Change> {
        val have = current.mapTo(HashSet()) { it.sha }
        return newer.filter { it.sha !in have }
    }

    private const val MAX_TITLE = 40
    private val NOISE = listOf("Merge ", "Fix build", "Fix CI", "CI:", "Revert ", "WIP", "Initial commit")
}
