package app.homeydash.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.homeydash.fridge.Fridge
import app.homeydash.fridge.OpenFoodFacts
import app.homeydash.recipes.Cooking
import app.homeydash.recipes.ImportResult
import app.homeydash.recipes.ParsedRecipe
import app.homeydash.recipes.Recipe
import app.homeydash.recipes.RecipeBook
import app.homeydash.recipes.RecipeKeeperImport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume

/**
 * The recipes on the tablet: the book, the recipe being cooked, importing from links and
 * Recipe Keeper exports, and the photos; and what is in the fridge. One instance lives as
 * long as the app.
 */
class Kitchen(context: Context, http: OkHttpClient, scope: CoroutineScope) {
    private val appContext = context.applicationContext

    val book = RecipeBook(File(appContext.filesDir, "recipes"), http, shrink = { shrinkPhoto(it) })
    val cooking = Cooking(book)

    /** A link shared to the app from a browser ("Share → Homey Dashboard"), waiting to be imported. */
    val sharedLink = MutableStateFlow<String?>(null)

    /** What is in the fridge, freezer and pantry; names for barcodes come from Open Food Facts. */
    val fridge = Fridge(File(appContext.filesDir, "fridge"), OpenFoodFacts(http))

    /** Set by the overview's fridge card to open the scanner as soon as the fridge page shows. */
    val scanRequest = MutableStateFlow(false)

    init {
        scope.launch { book.load() }
        scope.launch { fridge.load() }
    }

    /**
     * Reads the recipe at [url]. Some sites turn away anything that isn't a browser, or build
     * the page with JavaScript; then the page is loaded in a hidden browser and read from there,
     * like Recipe Keeper's own browser does.
     */
    suspend fun fetch(url: String): ParsedRecipe? {
        val direct = runCatching { book.fetch(url) }
        direct.getOrNull()?.takeIf { it.recipe.ingredients.isNotEmpty() || it.recipe.steps.isNotEmpty() }?.let { return it }
        val html = loadInBrowser(url) ?: direct.exceptionOrNull()?.let { throw it } ?: return direct.getOrNull()
        return book.fetch(url, html) ?: direct.getOrNull()
    }

    /** Imports a Recipe Keeper export (.zip, or a bare recipes.html) picked in the file picker. */
    suspend fun importKeeper(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val temp = File(appContext.cacheDir, "recipe-import.tmp")
        try {
            resolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                ?: throw IllegalArgumentException("Couldn't open that file.")
            val isZip = temp.inputStream().use { it.read() == 'P'.code && it.read() == 'K'.code }
            if (isZip) {
                val (zip, recipes) = RecipeKeeperImport.parseZip(temp)
                zip.use { z -> book.importKeeper(recipes) { RecipeKeeperImport.photo(z, it) } }
            } else {
                val recipes = RecipeKeeperImport.parseHtml(temp.readText())
                if (recipes.isEmpty()) throw IllegalArgumentException("No Recipe Keeper recipes in that file. Pick the .zip from Recipe Keeper's Export.")
                book.importKeeper(recipes) { null }
            }
        } finally {
            temp.delete()
        }
    }

    /** A photo picked from the tablet's gallery, for a recipe added by hand. */
    suspend fun readPhoto(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
    }

    private val photos = LruCache<String, ImageBitmap>(40)

    /** A recipe's photo, decoded no larger than about [maxSide] and kept for a while. */
    suspend fun photo(recipe: Recipe, maxSide: Int): ImageBitmap? {
        val name = recipe.image ?: return null
        val key = "$name@$maxSide"
        photos.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = book.imageFile(recipe) ?: return@withContext null
            decode(file.readBytes(), maxSide)?.asImageBitmap()
        }?.also { photos.put(key, it) }
    }

    /** Downloads a photo for the preview before saving; the bytes are saved with the recipe so it isn't fetched twice. */
    suspend fun downloadPhoto(url: String): Pair<ByteArray, ImageBitmap>? = runCatching {
        val bytes = book.download(url)
        val bitmap = withContext(Dispatchers.Default) { decode(bytes, 900) } ?: return@runCatching null
        bytes to bitmap.asImageBitmap()
    }.getOrNull()

    /** A picked photo, decoded for the editor's preview. */
    suspend fun previewPhoto(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) { decode(bytes, 900)?.asImageBitmap() }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun loadInBrowser(url: String): String? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(BROWSER_TIMEOUT_MS) {
            val web = WebView(appContext)
            try {
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                web.settings.loadsImagesAutomatically = false
                val finished = suspendCancellableCoroutine<Boolean> { cont ->
                    web.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, finishedUrl: String) {
                            if (cont.isActive) cont.resume(true)
                        }
                    }
                    web.loadUrl(url)
                }
                if (!finished) return@withTimeoutOrNull null
                // Pages that build themselves with JavaScript need a moment after "finished".
                delay(1500)
                val encoded = suspendCancellableCoroutine<String?> { cont ->
                    web.evaluateJavascript("document.documentElement.outerHTML") { cont.resume(it) }
                }
                encoded?.let { (Json.parseToJsonElement(it) as? JsonPrimitive)?.contentOrNull }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            } finally {
                web.stopLoading()
                web.destroy()
            }
        }
    }

    private companion object {
        const val BROWSER_TIMEOUT_MS = 25_000L
        const val MAX_SIDE = 1600

        /** Photos are kept at most [MAX_SIDE] px on the long side, as JPEG. */
        fun shrinkPhoto(bytes: ByteArray): ByteArray {
            val bitmap = decode(bytes, MAX_SIDE) ?: throw IllegalArgumentException("That isn't a photo the tablet can read.")
            val scale = MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
            val sized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
            return ByteArrayOutputStream().use { out ->
                sized.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            }
        }

        fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
