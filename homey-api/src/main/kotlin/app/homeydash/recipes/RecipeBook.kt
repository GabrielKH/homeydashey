package app.homeydash.recipes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.UUID

@Serializable
private class BookFile(val version: Int = 1, val recipes: List<Recipe> = emptyList())

/** How a Recipe Keeper import went. */
data class ImportResult(val added: Int, val updated: Int, val photos: Int)

/**
 * The recipes kept on the tablet: one JSON file plus a folder of photos, under [dir].
 * Photos pass through [shrink] before they are saved, so a 4000 px photo from a site
 * is stored at a size the tablet can show quickly.
 */
class RecipeBook(
    private val dir: File,
    private val http: OkHttpClient,
    private val shrink: (ByteArray) -> ByteArray = { it },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = false }
    private val lock = Mutex()
    private val file = File(dir, "recipes.json")
    val imageDir = File(dir, "images")

    private val _recipes = MutableStateFlow<List<Recipe>>(emptyList())
    /** Newest first. */
    val recipes: StateFlow<List<Recipe>> = _recipes.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    suspend fun load() = lock.withLock {
        withContext(Dispatchers.IO) {
            val list = runCatching { json.decodeFromString<BookFile>(file.readText()).recipes }.getOrElse {
                // A broken file is kept aside rather than overwritten by the next save.
                if (file.exists()) file.copyTo(File(dir, "recipes.broken-${clock()}.json"), overwrite = true)
                emptyList()
            }
            _recipes.value = list.sortedByDescending { it.added }
        }
        _loaded.value = true
    }

    fun get(id: String): Recipe? = _recipes.value.firstOrNull { it.id == id }

    /** Every course, category and collection in use, most used first. */
    fun tags(): List<String> = _recipes.value.flatMap { it.tags }.groupingBy { it }.eachCount()
        .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() }).map { it.key }

    fun categories(): List<String> = _recipes.value.flatMap { it.categories }.distinct().sortedBy { it.lowercase() }

    fun courses(): List<String> = (DefaultCourses + _recipes.value.flatMap { it.courses }).distinctBy { it.lowercase() }

    fun imageFile(recipe: Recipe): File? = recipe.image?.let { File(imageDir, it) }?.takeIf { it.exists() }

    /** Adds a new recipe (when [recipe] has no id) or replaces the one with the same id. Fetches its photo when it has none yet. */
    suspend fun save(recipe: Recipe, photo: ByteArray? = null): Recipe {
        var r = if (recipe.id.isBlank()) recipe.copy(id = UUID.randomUUID().toString(), added = clock()) else recipe
        val bytes = photo ?: if (r.image == null && r.imageUrl != null) runCatching { download(r.imageUrl!!) }.getOrNull() else null
        if (bytes != null) r = withContext(Dispatchers.IO) { runCatching { storePhoto(r, bytes) }.getOrDefault(r) }
        update { list -> if (list.any { it.id == r.id }) list.map { if (it.id == r.id) r else it } else listOf(r) + list }
        return r
    }

    suspend fun delete(id: String) {
        val r = get(id) ?: return
        update { list -> list.filterNot { it.id == id } }
        withContext(Dispatchers.IO) { imageFile(r)?.delete() }
    }

    suspend fun setFavourite(id: String, favourite: Boolean) = edit(id) { it.copy(favourite = favourite) }

    suspend fun markCooked(id: String) = edit(id) { it.copy(lastCooked = clock()) }

    suspend fun edit(id: String, change: (Recipe) -> Recipe) = update { list -> list.map { if (it.id == id) change(it) else it } }

    /** Renames a category on every recipe, or removes it when [to] is blank. */
    suspend fun renameTag(from: String, to: String) = update { list ->
        fun List<String>.swap() = mapNotNull { if (it == from) to.trim().ifBlank { null } else it }.distinct()
        list.map { it.copy(courses = it.courses.swap(), categories = it.categories.swap(), collections = it.collections.swap()) }
    }

    /**
     * Imports a Recipe Keeper export. A recipe imported before (same Recipe Keeper id) is
     * updated in place, keeping its photo when the export has none.
     */
    suspend fun importKeeper(recipes: List<KeeperRecipe>, photo: (String) -> ByteArray?): ImportResult {
        var added = 0
        var updated = 0
        var photos = 0
        val existing = _recipes.value.filter { it.externalId != null }.associateBy { it.externalId }
        val incoming = withContext(Dispatchers.IO) {
            recipes.map { k ->
                val old = k.recipe.externalId?.let { existing[it] }
                var r = if (old != null) {
                    updated++
                    k.recipe.copy(id = old.id, added = old.added, image = old.image, lastCooked = old.lastCooked)
                } else {
                    added++
                    k.recipe.copy(id = UUID.randomUUID().toString(), added = clock())
                }
                k.photo?.let { path -> runCatching { photo(path) }.getOrNull() }?.let { bytes ->
                    r = runCatching { storePhoto(r, bytes) }.getOrDefault(r)
                    photos++
                }
                r
            }
        }
        val byId = incoming.associateBy { it.id }
        update { list -> incoming.filter { r -> list.none { it.id == r.id } }.reversed() + list.map { byId[it.id] ?: it } }
        return ImportResult(added, updated, photos)
    }

    /** Fetches [url] and reads the recipe on it; [html] skips the fetch when the page was loaded another way (the in-app browser). */
    suspend fun fetch(url: String, html: String? = null): ParsedRecipe? {
        val page = html ?: withContext(Dispatchers.IO) { fetchBytes(url).toString(Charsets.UTF_8) }
        return withContext(Dispatchers.Default) { RecipeParser.parse(page, url) }
    }

    /** Downloads a photo from the web, for a recipe added by hand with a photo link. */
    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) { fetchBytes(url) }

    private fun fetchBytes(url: String): ByteArray {
        val request = Request.Builder().url(url)
            // Many recipe sites turn away clients that don't look like a browser.
            .header("User-Agent", BROWSER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,image/avif,image/webp,image/*,*/*;q=0.8")
            .header("Accept-Language", "sv-SE,sv;q=0.9,en;q=0.8")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("The site answered ${response.code}.")
            val body = response.body ?: throw IOException("The site sent nothing.")
            if (body.contentLength() > MAX_BYTES) throw IOException("That page is too big.")
            return body.bytes()
        }
    }

    private fun storePhoto(r: Recipe, bytes: ByteArray): Recipe {
        imageDir.mkdirs()
        val name = "${r.id}-${clock()}.jpg"
        File(imageDir, name).writeBytes(shrink(bytes))
        r.image?.let { File(imageDir, it).delete() }
        return r.copy(image = name)
    }

    private suspend fun update(change: (List<Recipe>) -> List<Recipe>) = lock.withLock {
        val next = change(_recipes.value)
        _recipes.value = next
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val tmp = File(dir, "recipes.json.tmp")
            tmp.writeText(json.encodeToString(BookFile.serializer(), BookFile(recipes = next)))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    companion object {
        const val BROWSER_AGENT = "Mozilla/5.0 (Linux; Android 15; NXTPAPER 11 Plus) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val MAX_BYTES = 30L * 1024 * 1024
    }
}
