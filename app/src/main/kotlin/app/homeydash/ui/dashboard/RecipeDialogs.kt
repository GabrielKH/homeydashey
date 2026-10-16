package app.homeydash.ui.dashboard

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.homeydash.data.Kitchen
import app.homeydash.recipes.ParsedRecipe
import app.homeydash.recipes.Recipe
import app.homeydash.recipes.RecipeText
import app.homeydash.recipes.Text as RecipeTextUtil
import app.homeydash.ui.components.DialogHeader
import app.homeydash.ui.components.GlassDialog
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.SectionLabel
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val LINK = Regex("""https?://\S+""")

/** The first web link in [text]; browsers share "Page title https://…". */
private fun linkIn(text: String): String? = LINK.find(text)?.value?.trimEnd('.', ',', ')', '"', '\'')

private fun clipboardLink(context: Context): String? = runCatching {
    val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
    clip?.getItemAt(0)?.coerceToText(context)?.toString()?.let(::linkIn)
}.getOrNull()

/**
 * Adding a recipe from a link, like Recipe Keeper: paste (or share) the address, the
 * dashboard reads the recipe from the page, and you pick its categories before saving.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddRecipeDialog(
    kitchen: Kitchen,
    initialUrl: String,
    onEdit: (Recipe) -> Unit,
    onSaved: (Recipe) -> Unit,
    onDismiss: () -> Unit,
) {
    val g = GlassTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(linkIn(initialUrl) ?: initialUrl.ifBlank { clipboardLink(context).orEmpty() }) }
    var busy by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var parsed by remember { mutableStateOf<ParsedRecipe?>(null) }
    var photo by remember { mutableStateOf<Pair<ByteArray, ImageBitmap>?>(null) }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    val known = remember { kitchen.book.tags() }

    fun fetch() {
        val link = linkIn(url.trim()) ?: linkIn("https://" + url.trim().removePrefix("//"))?.takeIf { url.contains('.') }
        if (link == null) {
            error = "Paste the address of a recipe page, e.g. https://www.ica.se/recept/…"
            return
        }
        busy = true
        error = null
        parsed = null
        photo = null
        scope.launch {
            try {
                val result = kitchen.fetch(link)
                if (result == null) {
                    error = "Couldn't find a recipe on that page. You can still write it in by hand."
                } else {
                    parsed = result
                    // Categories you already use that the site also names are picked for you.
                    val siteWords = (result.siteCategories + result.recipe.keywords).map(RecipeTextUtil::fold).toSet()
                    chosen = known.filter { RecipeTextUtil.fold(it) in siteWords }.toSet()
                    result.recipe.imageUrl?.let { photo = kitchen.downloadPhoto(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Couldn't open the page: ${e.message ?: e::class.simpleName}"
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(Unit) { if (initialUrl.isNotBlank()) fetch() }

    fun withTags(r: Recipe): Recipe {
        val courses = kitchen.book.courses().map(RecipeTextUtil::fold).toSet()
        val (c, rest) = chosen.partition { RecipeTextUtil.fold(it) in courses }
        return r.copy(courses = c, categories = rest)
    }

    GlassDialog(onDismiss = onDismiss, width = 720.dp) {
        DialogHeader("Add a recipe", "From any recipe site", onDismiss)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassField(
                    value = url,
                    onValue = { url = it },
                    placeholder = "https://…",
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Uri,
                    onGo = ::fetch,
                    leading = { SymIcon(Sym.link, size = 20.dp, tint = g.muted) },
                )
                if (url.isBlank()) PillButton(Sym.add, "Paste") { clipboardLink(context)?.let { url = it } }
                PillButton(Sym.download, if (busy) "Reading…" else "Get recipe", primary = parsed == null, enabled = !busy) { fetch() }
            }
            if (busy) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = g.fg, strokeWidth = 2.dp)
                    Text("Reading the page…", style = MaterialTheme.typography.bodyLarge, color = g.muted)
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = g.danger) }
            parsed?.let { p ->
                val r = p.recipe
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(150.dp, 112.dp).clip(RoundedCornerShape(16.dp)).background(g.onSoft), contentAlignment = Alignment.Center) {
                        val bitmap = photo?.second
                        if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else SymIcon(Sym.bowl, size = 36.dp, tint = g.muted)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(r.title, style = MaterialTheme.typography.titleLarge, color = g.fg)
                        Text(
                            listOfNotNull(
                                r.sourceName,
                                "${r.ingredients.size} ingredients",
                                "${r.steps.size} steps",
                                r.minutes?.let(RecipeTextUtil::duration),
                                r.servings,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodyLarge,
                            color = g.muted,
                        )
                        if (r.ingredients.isEmpty() || r.steps.isEmpty()) {
                            Text("Some of it is missing on the page; tap Edit first to fill it in.", style = MaterialTheme.typography.bodyMedium, color = g.danger)
                        }
                    }
                }
                SectionLabel("Categories")
                TagPicker(known + p.siteCategories, chosen) { chosen = it }
            }
        }
        if (parsed == null && !busy) {
            Text(
                "Write one by hand instead",
                style = MaterialTheme.typography.labelLarge,
                color = g.muted,
                modifier = Modifier.pressable({ onEdit(Recipe(id = "", title = "", sourceUrl = linkIn(url))) }).padding(vertical = 6.dp),
            )
        }
        parsed?.let { p ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.align(Alignment.End)) {
                PillButton(Sym.edit, "Edit first") { onEdit(withTags(p.recipe)) }
                PillButton(Sym.check, if (saving) "Saving…" else "Save recipe", primary = true, big = true, enabled = !saving) {
                    saving = true
                    scope.launch {
                        try {
                            onSaved(kitchen.book.save(withTags(p.recipe), photo?.first))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = "Couldn't save: ${e.message}"
                            saving = false
                        }
                    }
                }
            }
        }
    }
}

/** Writing or changing a recipe: everything Recipe Keeper keeps, as plain text fields. */
@Composable
internal fun EditRecipeDialog(
    kitchen: Kitchen,
    recipe: Recipe,
    onSaved: (Recipe) -> Unit,
    onDeleted: () -> Unit,
    onDismiss: () -> Unit,
) {
    val g = GlassTheme.colors
    val scope = rememberCoroutineScope()
    val isNew = recipe.id.isBlank()
    var title by remember { mutableStateOf(recipe.title) }
    var servings by remember { mutableStateOf(recipe.servings.orEmpty()) }
    var prep by remember { mutableStateOf(recipe.prepMinutes?.toString().orEmpty()) }
    var cook by remember { mutableStateOf(recipe.cookMinutes?.toString().orEmpty()) }
    var ingredients by remember { mutableStateOf(RecipeText.ingredients(recipe.ingredients)) }
    var steps by remember { mutableStateOf(RecipeText.steps(recipe.steps)) }
    var notes by remember { mutableStateOf(recipe.notes) }
    var source by remember { mutableStateOf(recipe.sourceUrl.orEmpty()) }
    var chosen by remember { mutableStateOf(recipe.tags.toSet()) }
    var photoBytes by remember { mutableStateOf<ByteArray?>(null) }
    var photoPreview by remember { mutableStateOf<ImageBitmap?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val known = remember { kitchen.book.tags() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bytes = kitchen.readPhoto(uri) ?: return@launch
            photoBytes = bytes
            photoPreview = kitchen.previewPhoto(bytes)
        }
    }

    GlassDialog(onDismiss = onDismiss, width = 860.dp) {
        DialogHeader(if (isNew) "New recipe" else "Edit recipe", recipe.title.takeIf { !isNew && it.isNotBlank() }, onDismiss)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    Modifier.size(180.dp, 135.dp).clip(RoundedCornerShape(16.dp)).pressable({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }),
                    contentAlignment = Alignment.Center,
                ) {
                    val preview = photoPreview
                    if (preview != null) Image(preview, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else RecipePhoto(kitchen, recipe, maxSide = 600, modifier = Modifier.fillMaxSize())
                    Text(
                        "Change photo",
                        style = MaterialTheme.typography.labelMedium,
                        color = g.fg,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).background(g.wallBase.copy(alpha = 0.8f), PillShape).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassField(title, { title = it }, "Name, e.g. Mormors köttbullar")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassField(servings, { servings = it }, "Servings", Modifier.weight(1f))
                        GlassField(prep, { prep = it.filter(Char::isDigit) }, "Prep min", Modifier.weight(1f), keyboardType = KeyboardType.Number)
                        GlassField(cook, { cook = it.filter(Char::isDigit) }, "Cook min", Modifier.weight(1f), keyboardType = KeyboardType.Number)
                    }
                }
            }
            SectionLabel("Categories")
            TagPicker(known, chosen) { chosen = it }
            SectionLabel("Ingredients · one per line · \"# Sauce\" starts a section")
            GlassField(ingredients, { ingredients = it }, "500 g köttfärs\n1 ägg\n…", singleLine = false, minLines = 5)
            SectionLabel("Steps · one per line")
            GlassField(steps, { steps = it }, "Blanda färsen med…\nRulla bullar…", singleLine = false, minLines = 5)
            SectionLabel("Notes")
            GlassField(notes, { notes = it }, "Tips, changes, what to serve with it", singleLine = false, minLines = 2)
            GlassField(source, { source = it }, "Link to the original (optional)", keyboardType = KeyboardType.Uri)
            error?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = g.danger) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!isNew) {
                PillButton(Sym.trash, if (confirmDelete) "Tap again to delete" else "Delete") {
                    if (!confirmDelete) confirmDelete = true
                    else scope.launch {
                        kitchen.book.delete(recipe.id)
                        onDeleted()
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            PillButton(Sym.check, if (saving) "Saving…" else "Save", primary = true, big = true, enabled = !saving && title.isNotBlank()) {
                saving = true
                val courses = kitchen.book.courses().map(RecipeTextUtil::fold).toSet()
                val collections = recipe.collections.toSet()
                val updated = recipe.copy(
                    title = RecipeTextUtil.clean(title),
                    servings = servings.trim().ifBlank { null },
                    prepMinutes = prep.toIntOrNull()?.takeIf { it > 0 },
                    cookMinutes = cook.toIntOrNull()?.takeIf { it > 0 },
                    // A total the site gave goes stale when the times change by hand.
                    totalMinutes = recipe.totalMinutes.takeIf { prep == recipe.prepMinutes?.toString().orEmpty() && cook == recipe.cookMinutes?.toString().orEmpty() },
                    ingredients = RecipeText.parseIngredients(ingredients),
                    steps = RecipeText.parseSteps(steps),
                    notes = notes.trim(),
                    sourceUrl = source.trim().ifBlank { null },
                    sourceName = recipe.sourceName ?: app.homeydash.recipes.RecipeParser.host(source.trim().ifBlank { null }),
                    collections = chosen.filter { it in collections },
                    courses = chosen.filter { it !in collections && RecipeTextUtil.fold(it) in courses },
                    categories = chosen.filter { it !in collections && RecipeTextUtil.fold(it) !in courses },
                )
                scope.launch {
                    try {
                        onSaved(kitchen.book.save(updated, photoBytes))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = "Couldn't save: ${e.message}"
                        saving = false
                    }
                }
            }
        }
    }
}

/** Categories as chips to switch on and off, with a field to make a new one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagPicker(all: List<String>, chosen: Set<String>, onChosen: (Set<String>) -> Unit) {
    val g = GlassTheme.colors
    var adding by remember { mutableStateOf("") }
    val shown = remember(all, chosen) { (all + chosen).distinctBy { RecipeTextUtil.fold(it) } }
    val suggestions = shown.ifEmpty { app.homeydash.recipes.DefaultCourses }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        suggestions.forEach { tag ->
            val on = tag in chosen
            FilterChip(tag, on) { onChosen(if (on) chosen - tag else chosen + tag) }
        }
        Row(Modifier.width(220.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassField(
                adding,
                { adding = it },
                "New category",
                Modifier.weight(1f),
                onGo = {
                    val t = RecipeTextUtil.clean(adding)
                    if (t.isNotEmpty()) onChosen(chosen + t)
                    adding = ""
                },
                leading = { SymIcon(Sym.add, size = 18.dp, tint = g.muted) },
            )
        }
    }
}

@Composable
internal fun GlassField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    onGo: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val g = GlassTheme.colors
    val shape = if (singleLine) PillShape else RoundedCornerShape(18.dp)
    Row(
        modifier.fillMaxWidth().glass(shape).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading?.invoke()
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = g.muted)
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = singleLine,
                minLines = minLines,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = g.fg),
                cursorBrush = SolidColor(g.fg),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = if (onGo != null) ImeAction.Go else ImeAction.Default),
                keyboardActions = KeyboardActions(onGo = { onGo?.invoke() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
