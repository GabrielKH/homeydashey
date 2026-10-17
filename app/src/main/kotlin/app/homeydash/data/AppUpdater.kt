package app.homeydash.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import app.homeydash.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Checks GitHub for a newer test build and installs it over the running app.
 * CI publishes the APK and a `version.txt` with its build number to the `dev` release.
 *
 * The download runs in the app's own scope, so it survives the screen being rebuilt (a rotation,
 * the theme following the time of day). Android's installer is opened from the screen itself once
 * the download is done ([State.ReadyToInstall]): started from the visible activity, inside its
 * own task, Android neither blocks it as a background launch nor as leaving the pinned kiosk.
 */
class AppUpdater(private val context: Context, private val http: OkHttpClient, private val scope: CoroutineScope) {

    sealed interface State {
        data object UpToDate : State
        data class Available(val build: Int) : State
        data class Downloading(val build: Int, val progress: Float) : State
        /** Downloaded; the screen should open Android's installer for it now. */
        data class ReadyToInstall(val build: Int) : State
        data class Failed(val build: Int, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.UpToDate)
    val state: StateFlow<State> = _state.asStateFlow()

    val currentBuild: Int get() = BuildConfig.VERSION_CODE
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Set while Android's "install unknown apps" screen is open, so the install carries on after it. */
    private var awaitingPermission = false

    private val dir: File get() = File(context.cacheDir, "updates")
    private fun apk(build: Int) = File(dir, "homey-dashboard-$build.apk")

    suspend fun check() {
        if (_state.value is State.Downloading || _state.value is State.ReadyToInstall) return
        val latest = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(BASE + "version.txt").build()).execute().use { response ->
                    if (!response.isSuccessful) null else response.body?.string()?.trim()?.toIntOrNull()
                }
            }.getOrNull()
        } ?: return
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
        _state.value = State.Downloading(build, 0f)
        scope.launch {
            _state.value = try {
                withContext(Dispatchers.IO) { download(build) }
                State.ReadyToInstall(build)
            } catch (e: Exception) {
                State.Failed(build, e.message ?: "Download failed")
            }
        }
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

    private fun download(build: Int) {
        dir.mkdirs()
        val file = apk(build)
        // Older downloads only take space; this one is kept if it's already complete.
        dir.listFiles()?.filter { it != file }?.forEach { it.delete() }
        if (file.length() > 0) return
        val part = File(dir, file.name + ".part")
        http.newCall(Request.Builder().url(BASE + "homey-dashboard.apk").build()).execute().use { response ->
            if (!response.isSuccessful) error("GitHub answered ${response.code}")
            val body = response.body ?: error("Empty download")
            val total = body.contentLength().takeIf { it > 0 }
            var done = 0L
            var shown = -1
            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        // The screen shows whole percents, so only those are sent to it.
                        val progress = if (total != null) (done.toFloat() / total).coerceIn(0f, 1f) else continue
                        val percent = (progress * 100).toInt()
                        if (percent != shown) {
                            shown = percent
                            _state.value = State.Downloading(build, progress)
                        }
                    }
                }
            }
            if (total != null && done != total) error("Download cut off")
        }
        if (!part.renameTo(file)) error("Couldn't save the download")
    }

    private companion object {
        const val BASE = "https://github.com/GabrielKH/homeydashey/releases/download/dev/"
    }
}
