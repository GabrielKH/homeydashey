package app.homeydash.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import app.homeydash.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Checks GitHub for a newer test build and installs it over the running app.
 * CI publishes the APK and a `version.txt` with its build number to the `dev` release.
 */
class AppUpdater(private val context: Context, private val http: OkHttpClient) {

    sealed interface State {
        data object UpToDate : State
        data class Available(val build: Int) : State
        data class Downloading(val build: Int, val progress: Float) : State
        data class Failed(val build: Int, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.UpToDate)
    val state: StateFlow<State> = _state.asStateFlow()

    val currentBuild: Int get() = BuildConfig.VERSION_CODE
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    suspend fun check() {
        if (_state.value is State.Downloading) return
        val latest = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(BASE + "version.txt").build()).execute().use { response ->
                    if (!response.isSuccessful) null else response.body?.string()?.trim()?.toIntOrNull()
                }
            }.getOrNull()
        } ?: return
        _state.value = if (latest > currentBuild) State.Available(latest) else State.UpToDate
    }

    /** Downloads the new APK and hands it to Android's installer, which asks to confirm. */
    suspend fun install() {
        val build = when (val s = _state.value) {
            is State.Available -> s.build
            is State.Failed -> s.build
            else -> return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            // Android asks once whether this app may install updates.
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        _state.value = State.Downloading(build, 0f)
        val file = try {
            withContext(Dispatchers.IO) { download(build) }
        } catch (e: Exception) {
            _state.value = State.Failed(build, e.message ?: "Download failed")
            return
        }
        _state.value = State.Available(build)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun download(build: Int): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "homey-dashboard.apk")
        http.newCall(Request.Builder().url(BASE + "homey-dashboard.apk").build()).execute().use { response ->
            if (!response.isSuccessful) error("GitHub answered ${response.code}")
            val body = response.body ?: error("Empty download")
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total != null) _state.value = State.Downloading(build, (done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        return file
    }

    private companion object {
        const val BASE = "https://github.com/GabrielKH/homeydashey/releases/download/dev/"
    }
}
