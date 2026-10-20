package app.homeydash.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import app.homeydash.BuildConfig
import app.homeydash.updates.BuildInfo
import app.homeydash.updates.Change
import app.homeydash.updates.ChangeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.LocalDate

/**
 * Checks GitHub for a newer test build and installs it over the running app.
 * CI publishes the APK, a `version.txt` with its build number and a `changes.json` with what the
 * build contains to the `dev` release; the app carries its own `changes.json`, so the update's
 * notes are the commits the newer build has and this one doesn't.
 *
 * The download runs in the app's own scope, so it survives the screen being rebuilt (a rotation,
 * the theme following the time of day). Android's installer is opened from the screen itself once
 * the download is done ([State.ReadyToInstall]): started from the visible activity, inside its
 * own task, Android neither blocks it as a background launch nor as leaving the pinned kiosk.
 */
class AppUpdater(
    private val context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    /** Told once per update what it brought, for the assistant's memory. */
    private val onUpdated: suspend (version: String, day: String, changes: List<String>) -> Unit,
) {

    sealed interface State {
        data object UpToDate : State
        data class Available(val build: Int) : State
        data class Downloading(
            val build: Int,
            val progress: Float,
            val doneBytes: Long = 0,
            val totalBytes: Long = 0,
            val bytesPerSecond: Long = 0,
        ) : State
        /** Downloaded; the screen should open Android's installer for it now. */
        data class ReadyToInstall(val build: Int) : State
        data class Failed(val build: Int, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.UpToDate)
    val state: StateFlow<State> = _state.asStateFlow()

    val currentBuild: Int get() = BuildConfig.VERSION_CODE
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** What this build contains, newest first; empty in a build made outside CI. */
    val installedChanges: List<Change> by lazy {
        ChangeLog.changes(ChangeLog.parse(runCatching { context.assets.open("changes.json").bufferedReader().use { it.readText() } }.getOrNull()))
    }

    private val _latest = MutableStateFlow<BuildInfo?>(null)
    /** The newest build on GitHub, from its `changes.json`, once checked. */
    val latest: StateFlow<BuildInfo?> = _latest.asStateFlow()

    private val _lastChecked = MutableStateFlow<Long?>(null)
    /** When GitHub was last asked, in epoch millis. */
    val lastChecked: StateFlow<Long?> = _lastChecked.asStateFlow()

    private val _whatsNew = MutableStateFlow<List<Change>>(emptyList())
    /** What the update just installed brought; shown once, until [dismissWhatsNew]. */
    val whatsNew: StateFlow<List<Change>> = _whatsNew.asStateFlow()

    /** What the newest build has that this one doesn't, newest first. */
    fun pending(latest: BuildInfo?): List<Change> = ChangeLog.since(ChangeLog.changes(latest), installedChanges)

    private var downloading: Job? = null

    /** Compares this build's changes with the ones the app had last time it ran; once, at start. */
    suspend fun findWhatsNew() {
        val mine = installedChanges
        if (mine.isEmpty()) return
        val seen = settings.seenChanges.first()
        // The first time there's nothing to compare with, so the newest few are shown.
        val fresh = if (seen.isEmpty()) mine.take(FIRST_TIME) else mine.filter { it.sha !in seen }
        if (fresh.isEmpty()) return
        _whatsNew.value = fresh
        runCatching { onUpdated(currentVersion, LocalDate.now().toString(), fresh.map { it.title }) }
    }

    fun dismissWhatsNew() {
        _whatsNew.value = emptyList()
        scope.launch { settings.setSeenChanges(installedChanges.mapTo(HashSet()) { it.sha }) }
    }

    /** Set while Android's "install unknown apps" screen is open, so the install carries on after it. */
    private var awaitingPermission = false

    private val dir: File get() = File(context.cacheDir, "updates")
    private fun apk(build: Int) = File(dir, "homey-dashboard-$build.apk")

    suspend fun check() {
        if (_state.value is State.Downloading || _state.value is State.ReadyToInstall) return
        val (info, latest) = withContext(Dispatchers.IO) {
            val info = ChangeLog.parse(fetch("changes.json"))
            // Builds from before the change list only published their number.
            info to (info?.build?.takeIf { it > 0 } ?: fetch("version.txt")?.trim()?.toIntOrNull())
        }
        if (latest == null) return
        _lastChecked.value = System.currentTimeMillis()
        if (info != null) _latest.value = info
        if (_state.value is State.Downloading || _state.value is State.ReadyToInstall) return
        _state.value = if (latest > currentBuild) State.Available(latest) else State.UpToDate
    }

    /**
     * Downloads the new APK, then moves to [State.ReadyToInstall]. [activity] is the visible screen,
     * used to ask once for permission to install updates.
     */
    fun install(activity: Context) {
        val build = when (val s = _state.value) {
            is State.Available -> s.build
            is State.Failed -> s.build
            else -> return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            // Android asks once whether this app may install updates; the install goes on when we're back.
            awaitingPermission = true
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            return
        }
        _state.value = State.Downloading(build, 0f, totalBytes = _latest.value?.apkBytes ?: 0)
        downloading = scope.launch {
            _state.value = try {
                withContext(Dispatchers.IO) { download(build) }
                State.ReadyToInstall(build)
            } catch (e: CancellationException) {
                State.Available(build)
            } catch (e: Exception) {
                State.Failed(build, e.message ?: "Download failed")
            }
        }
    }

    /** Stops a download; the part already fetched is thrown away. */
    fun cancel() {
        val build = (_state.value as? State.Downloading)?.build ?: return
        downloading?.cancel()
        downloading = null
        _state.value = State.Available(build)
    }

    /** Called when the screen comes back; finishes an install that was waiting on the permission. */
    fun onResume(activity: Context) {
        if (!awaitingPermission) return
        awaitingPermission = false
        if (context.packageManager.canRequestPackageInstalls()) install(activity)
    }

    /** Opens Android's installer for the downloaded build. [activity] must be the visible screen. */
    fun openInstaller(activity: Context) {
        val build = (_state.value as? State.ReadyToInstall)?.build ?: return
        // Back to "Install", so a dismissed installer can be opened again without downloading again.
        _state.value = State.Available(build)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk(build))
        val opened = runCatching {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
        opened.exceptionOrNull()?.let { _state.value = State.Failed(build, it.message ?: "Couldn't open the installer") }
    }

    private fun fetch(name: String): String? = runCatching {
        http.newCall(Request.Builder().url(BASE + name).build()).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }.getOrNull()

    private suspend fun download(build: Int) {
        dir.mkdirs()
        val file = apk(build)
        // Older downloads only take space; this one is kept if it's already complete.
        dir.listFiles()?.filter { it != file }?.forEach { it.delete() }
        if (file.length() > 0) return
        val part = File(dir, file.name + ".part")
        val call = http.newCall(Request.Builder().url(BASE + "homey-dashboard.apk").build())
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) error("GitHub answered ${response.code}")
                val body = response.body ?: error("Empty download")
                val total = body.contentLength().takeIf { it > 0 }
                val started = System.nanoTime()
                var done = 0L
                var shown = -1
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            // The screen shows whole percents, so only those are sent to it.
                            val progress = if (total != null) (done.toFloat() / total).coerceIn(0f, 1f) else continue
                            val percent = (progress * 100).toInt()
                            if (percent != shown) {
                                shown = percent
                                val seconds = (System.nanoTime() - started) / 1e9
                                val speed = if (seconds > 0.2) (done / seconds).toLong() else 0L
                                _state.value = State.Downloading(build, progress, done, total, speed)
                            }
                        }
                    }
                }
                if (total != null && done != total) error("Download cut off")
            }
        } catch (e: CancellationException) {
            call.cancel()
            part.delete()
            throw e
        }
        if (!part.renameTo(file)) error("Couldn't save the download")
    }

    private companion object {
        /** How many of the newest changes are shown the first time, with nothing to compare. */
        const val FIRST_TIME = 4
        const val BASE = "https://github.com/GabrielKH/homeydashey/releases/download/dev/"
    }
}
