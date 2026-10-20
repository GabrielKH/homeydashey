package app.homeydash.ui.dashboard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.homeydash.data.Kitchen
import app.homeydash.recipes.Cooking
import app.homeydash.recipes.CookingState
import app.homeydash.recipes.Recipe
import app.homeydash.recipes.Text as RecipeTextUtil
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.RoundButton
import app.homeydash.ui.components.SectionLabel
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.Ic
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.TileShape
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.onBrush
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs

/** "Favourites" in the filter chips. */
private const val FAVOURITES = "♥"

/**
 * The kitchen's recipe book: a grid of recipes with photos, filtered by course and
 * category like Recipe Keeper; a recipe page; and a big step-by-step view for cooking.
 * The voice assistant shares the open recipe and step through [Kitchen.cooking].
 */
@Composable
fun RecipesScreen(kitchen: Kitchen, onTimer: (seconds: Int, label: String) -> Unit) {
    val recipes by kitchen.book.recipes.collectAsStateWithLifecycle()
    val loaded by kitchen.book.loaded.collectAsStateWithLifecycle()
    val cooking by kitchen.cooking.state.collectAsStateWithLifecycle()
    val shared by kitchen.sharedLink.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Recipe?>(null) }

    // A link shared from the browser opens the import straight away.
    LaunchedEffect(shared) {
        shared?.let {
            adding = it
            kitchen.sharedLink.value = null
        }
    }

    val open = cooking?.let { s -> recipes.firstOrNull { it.id == s.recipeId }?.let { it to s } }
    when {
        !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = GlassTheme.colors.fg) }
        open != null && open.second.cookMode -> CookView(kitchen, open.first, open.second, onTimer)
        open != null -> RecipeView(kitchen, open.first, open.second, onEdit = { editing = open.first })
        else -> RecipeList(kitchen, recipes, onAdd = { adding = "" }, onWrite = { editing = Recipe(id = "", title = "") })
    }

    adding?.let { url ->
        AddRecipeDialog(
            kitchen = kitchen,
            initialUrl = url,
            onEdit = { draft -> adding = null; editing = draft },
            onSaved = { saved -> adding = null; kitchen.cooking.open(saved.id) },
            onDismiss = { adding = null },
        )
    }
    editing?.let { recipe ->
        EditRecipeDialog(
            kitchen = kitchen,
            recipe = recipe,
            onSaved = { saved -> editing = null; kitchen.cooking.open(saved.id) },
            onDeleted = { editing = null; kitchen.cooking.close() },
            onDismiss = { editing = null },
        )
    }
}

// ---------------------------------------------------------------- List

@Composable
private fun RecipeList(kitchen: Kitchen, recipes: List<Recipe>, onAdd: () -> Unit, onWrite: () -> Unit) {
    val g = GlassTheme.colors
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val tags = remember(recipes) { kitchen.book.tags() }
    val shown = remember(recipes, query, filter) {
        recipes.filter { r ->
            when (filter) {
                null -> true
                FAVOURITES -> r.favourite
                else -> filter in r.tags
            } && r.matches(query)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            message = try {
                val result = kitchen.importKeeper(uri)
                buildString {
                    append("Imported from Recipe Keeper: ${result.added} new")
                    if (result.updated > 0) append(", ${result.updated} updated")
                    append(", ${result.photos} photos.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't import: ${e.message ?: e::class.simpleName}"
            }
            importing = false
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(eyebrow = "Kitchen", title = "Recipes") {
            PillButton(Sym.download, if (importing) "Importing…" else "Recipe Keeper", enabled = !importing) {
                picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "text/html", "application/octet-stream"))
            }
            PillButton(Sym.add, "Add recipe", primary = true, onClick = onAdd)
        }
        message?.let { text ->
            Row(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp)).padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text, style = MaterialTheme.typography.bodyLarge, color = g.fg, modifier = Modifier.weight(1f))
                RoundButton(Sym.close, { message = null }, size = 36.dp, filledIcon = false)
            }
        }
        if (recipes.isEmpty()) {
            EmptyBook(onAdd = onAdd, onImport = { picker.launch(arrayOf("application/zip", "text/html", "application/octet-stream")) }, onWrite = onWrite)
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RecipeSearch(query, { query = it }, Modifier.width(320.dp))
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip("All", filter == null) { filter = null } }
                if (recipes.any { it.favourite }) item { FilterChip("Favourites", filter == FAVOURITES, Sym.heart) { filter = if (filter == FAVOURITES) null else FAVOURITES } }
                items(tags, key = { it }) { tag -> FilterChip(tag, filter == tag) { filter = if (filter == tag) null else tag } }
            }
        }
        if (shown.isEmpty()) {
            Text("No recipe matches.", style = MaterialTheme.typography.bodyLarge, color = g.muted, modifier = Modifier.padding(8.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(250.dp),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            // Room for the assistant's orb in the corner.
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            items(shown, key = { it.id }) { r -> RecipeCard(kitchen, r) { kitchen.cooking.open(r.id) } }
        }
    }
}

@Composable
private fun EmptyBook(onAdd: () -> Unit, onImport: () -> Unit, onWrite: () -> Unit) {
    val g = GlassTheme.colors
    Column(
        Modifier.fillMaxWidth().glass().padding(36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SymIcon(Sym.chefHat, size = 48.dp, tint = g.muted)
        Text("No recipes yet", style = MaterialTheme.typography.headlineMedium, color = g.fg)
        Text(
            "Paste a link to a recipe on any site (ICA, Arla, Köket, a blog…) and the dashboard reads the ingredients, steps and photo. " +
                "You can also share a page to the dashboard from the browser, import your Recipe Keeper export (Recipe Keeper → Settings → Export, the .zip), or write one by hand.",
            style = MaterialTheme.typography.bodyLarge,
            color = g.muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(Sym.link, "Add from a link", primary = true, onClick = onAdd)
            PillButton(Sym.download, "Import Recipe Keeper", onClick = onImport)
            PillButton(Sym.edit, "Write one", onClick = onWrite)
        }
    }
}

@Composable
private fun RecipeCard(kitchen: Kitchen, r: Recipe, onOpen: () -> Unit) {
    val g = GlassTheme.colors
    Column(Modifier.pressable(onOpen).glass(TileShape)) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f)) {
            RecipePhoto(kitchen, r, maxSide = 640, modifier = Modifier.fillMaxSize())
            if (r.favourite) {
                Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(34.dp).background(g.wallBase.copy(alpha = 0.75f), CircleShape), contentAlignment = Alignment.Center) {
                    SymIcon(Sym.heart, filled = true, size = 18.dp, tint = g.danger)
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(r.title, style = MaterialTheme.typography.titleMedium, color = g.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                r.minutes?.let { Meta(Sym.clock, RecipeTextUtil.duration(it)) }
                r.tags.firstOrNull()?.let { Meta(Sym.tag, it) }
            }
        }
    }
}

@Composable
private fun Meta(icon: Ic, text: String) {
    val g = GlassTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        SymIcon(icon, size = 16.dp, tint = g.muted)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = g.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The recipe's photo, or a soft placeholder when it has none. */
@Composable
internal fun RecipePhoto(kitchen: Kitchen, r: Recipe, maxSide: Int, modifier: Modifier = Modifier) {
    val g = GlassTheme.colors
    val photo by produceState(initialValue = null as androidx.compose.ui.graphics.ImageBitmap?, r.image, maxSide) { value = kitchen.photo(r, maxSide) }
    Box(modifier.background(g.onSoft), contentAlignment = Alignment.Center) {
        val p = photo
        if (p != null) Image(p, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else SymIcon(Sym.bowl, size = 44.dp, tint = g.muted)
    }
}

// ---------------------------------------------------------------- One recipe

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipeView(kitchen: Kitchen, r: Recipe, state: CookingState, onEdit: () -> Unit) {
    val g = GlassTheme.colors
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundButton(Sym.arrowBack, { kitchen.cooking.close() }, size = 48.dp, filledIcon = false, contentDescription = "All recipes")
            Column(Modifier.weight(1f)) {
                Text(r.sourceName ?: "Recipe", style = MaterialTheme.typography.bodyLarge, color = g.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.title, style = MaterialTheme.typography.headlineLarge, color = g.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            RoundButton(Sym.heart, { scope.launch { kitchen.book.setFavourite(r.id, !r.favourite) } }, size = 48.dp, filledIcon = r.favourite, contentDescription = "Favourite")
            RoundButton(Sym.edit, onEdit, size = 48.dp, filledIcon = false, contentDescription = "Edit")
            if (r.steps.isNotEmpty()) {
                PillButton(Sym.chefHat, if (state.step > 0) "Continue at step ${state.step + 1}" else "Start cooking", primary = true, big = true) {
                    kitchen.cooking.setCookMode(true)
                }
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            LazyColumn(
                Modifier.weight(0.4f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                item {
                    RecipePhoto(kitchen, r, maxSide = 1400, modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(TileShape))
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        r.prepMinutes?.let { InfoPill(Sym.clock, "Prep ${RecipeTextUtil.duration(it)}") }
                        r.cookMinutes?.let { InfoPill(Sym.heat, "Cook ${RecipeTextUtil.duration(it)}") }
                        if (r.prepMinutes == null && r.cookMinutes == null) r.minutes?.let { InfoPill(Sym.clock, RecipeTextUtil.duration(it)) }
                        r.servings?.let { InfoPill(Sym.people, it) }
                        r.tags.forEach { InfoPill(Sym.tag, it) }
                    }
                }
                if (r.description.isNotBlank()) item {
                    Text(r.description, style = MaterialTheme.typography.bodyLarge, color = g.muted)
                }
                item { SectionLabel("Ingredients", Modifier.padding(top = 6.dp)) }
                itemsIndexed(r.ingredients) { i, ing ->
                    if (ing.section != null && ing.section != r.ingredients.getOrNull(i - 1)?.section) {
                        Text(ing.section!!, style = MaterialTheme.typography.titleSmall, color = g.fg, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                    }
                    IngredientRow(ing.text, i in state.checked) { kitchen.cooking.toggleIngredient(i) }
                }
                if (r.ingredients.isEmpty()) item { Text("No ingredients written down.", style = MaterialTheme.typography.bodyLarge, color = g.muted) }
            }
            LazyColumn(
                Modifier.weight(0.6f).fillMaxHeight().glass().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(top = 18.dp, bottom = 96.dp),
            ) {
                item { SectionLabel("Steps") }
                itemsIndexed(r.steps) { i, step ->
                    if (step.section != null && step.section != r.steps.getOrNull(i - 1)?.section) {
                        Text(step.section!!, style = MaterialTheme.typography.titleMedium, color = g.fg, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
                    }
                    StepRow(i + 1, step.text, current = i == state.step) { kitchen.cooking.goTo(i) }
                }
                if (r.steps.isEmpty()) item { Text("No steps written down. Tap the pencil to add them.", style = MaterialTheme.typography.bodyLarge, color = g.muted) }
                if (r.notes.isNotBlank()) {
                    item { SectionLabel("Notes", Modifier.padding(top = 12.dp)) }
                    item { Text(r.notes, style = MaterialTheme.typography.bodyLarge, color = g.fg, modifier = Modifier.padding(horizontal = 4.dp)) }
                }
                if (r.sourceUrl != null) item {
                    Text(r.sourceUrl!!, style = MaterialTheme.typography.bodyMedium, color = g.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun IngredientRow(text: String, checked: Boolean, big: Boolean = false, onToggle: () -> Unit) {
    val g = GlassTheme.colors
    Row(
        Modifier.fillMaxWidth().pressable(onToggle).padding(horizontal = 4.dp, vertical = if (big) 8.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(if (big) 28.dp else 24.dp).then(if (checked) Modifier.background(onBrush(), CircleShape) else Modifier.glass(CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) SymIcon(Sym.check, filled = true, size = 15.dp, tint = g.onInk)
        }
        Text(
            text,
            style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            color = if (checked) g.muted else g.fg,
            textDecoration = if (checked) TextDecoration.LineThrough else null,
        )
    }
}

@Composable
private fun StepRow(number: Int, text: String, current: Boolean, onClick: () -> Unit) {
    val g = GlassTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .pressable(onClick)
            .then(if (current) Modifier.background(g.onSoft, RoundedCornerShape(18.dp)) else Modifier)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(34.dp).then(if (current) Modifier.background(onBrush(), CircleShape) else Modifier.glass(CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.labelLarge, color = if (current) g.onInk else g.fg)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp), color = g.fg, modifier = Modifier.weight(1f).padding(top = 4.dp))
    }
}

// ---------------------------------------------------------------- Cooking

/** One step at a time in big type, readable from across the kitchen; swipe or tap to move on. */
@Composable
private fun CookView(kitchen: Kitchen, r: Recipe, state: CookingState, onTimer: (Int, String) -> Unit) {
    val g = GlassTheme.colors
    val scope = rememberCoroutineScope()
    val count = r.steps.size
    val index = state.step.coerceIn(0, (count - 1).coerceAtLeast(0))
    val step = r.steps.getOrNull(index)
    val last = index >= count - 1
    val timers = remember(step?.text) { step?.let { Cooking.timers(it.text) }.orEmpty() }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundButton(Sym.close, { kitchen.cooking.setCookMode(false) }, size = 48.dp, filledIcon = false, contentDescription = "Whole recipe")
            Column(Modifier.weight(1f)) {
                Text(r.title, style = MaterialTheme.typography.bodyLarge, color = g.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (count == 0) "No steps" else "Step ${index + 1} of $count", style = MaterialTheme.typography.headlineLarge, color = g.fg)
            }
        }
        // One segment per step; tap one to jump there.
        Row(Modifier.fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(count) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(PillShape)
                        .pressable({ kitchen.cooking.goTo(i) })
                        .then(if (i <= index) Modifier.background(onBrush(), PillShape) else Modifier.background(g.onSoft, PillShape)),
                )
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            var drag by remember { mutableStateOf(0f) }
            Column(
                Modifier
                    .weight(0.66f)
                    .fillMaxHeight()
                    .glass()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { drag = 0f },
                            onDragEnd = {
                                if (abs(drag) > 120f) {
                                    if (drag < 0) kitchen.cooking.next() else kitchen.cooking.previous()
                                }
                                drag = 0f
                            },
                        ) { _, amount -> drag += amount }
                    }
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                step?.section?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, color = g.muted) }
                Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(
                        step?.text ?: "This recipe has no steps yet.",
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 34.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
                        color = g.fg,
                    )
                }
                if (timers.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        timers.forEach { t -> PillButton(Sym.timer, "Timer ${t.label}") { onTimer(t.seconds, "Steg ${index + 1} · ${t.label}") } }
                    }
                }
                r.steps.getOrNull(index + 1)?.let { next ->
                    Text("Next: ${next.text}", style = MaterialTheme.typography.bodyLarge, color = g.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(Modifier.weight(0.34f).fillMaxHeight().glass().padding(horizontal = 18.dp, vertical = 16.dp)) {
                SectionLabel("Ingredients")
                val list = rememberLazyListState()
                LazyColumn(state = list, contentPadding = PaddingValues(bottom = 80.dp)) {
                    itemsIndexed(r.ingredients) { i, ing ->
                        if (ing.section != null && ing.section != r.ingredients.getOrNull(i - 1)?.section) {
                            Text(ing.section!!, style = MaterialTheme.typography.titleSmall, color = g.fg, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                        }
                        IngredientRow(ing.text, i in state.checked, big = true) { kitchen.cooking.toggleIngredient(i) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(end = 96.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CookButton(Sym.keyboardArrowLeft, "Back", enabled = index > 0, modifier = Modifier.weight(1f)) { kitchen.cooking.previous() }
            if (last) {
                CookButton(Sym.check, "Done", primary = true, modifier = Modifier.weight(2f)) {
                    scope.launch { kitchen.book.markCooked(r.id) }
                    kitchen.cooking.setCookMode(false)
                }
            } else {
                CookButton(Sym.keyboardArrowRight, "Next step", primary = true, modifier = Modifier.weight(2f)) { kitchen.cooking.next() }
            }
        }
    }
}

// ---------------------------------------------------------------- Pieces

@Composable
private fun CookButton(icon: Ic, text: String, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val g = GlassTheme.colors
    Row(
        modifier
            .height(76.dp)
            .pressable(onClick, enabled = enabled)
            .then(if (primary) Modifier.background(onBrush(), PillShape) else Modifier.glass(PillShape))
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val tint = when {
            !enabled -> g.muted.copy(alpha = 0.5f)
            primary -> g.onInk
            else -> g.fg
        }
        SymIcon(icon, filled = true, size = 28.dp, tint = tint)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleLarge, color = tint)
    }
}

@Composable
internal fun PillButton(icon: Ic, text: String, primary: Boolean = false, big: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val g = GlassTheme.colors
    Row(
        Modifier
            .pressable(onClick, enabled = enabled)
            .then(if (primary) Modifier.background(onBrush(), PillShape) else Modifier.glass(PillShape))
            .padding(start = 14.dp, end = 18.dp, top = if (big) 13.dp else 10.dp, bottom = if (big) 13.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val tint = if (primary) g.onInk else g.fg
        SymIcon(icon, filled = primary, size = 20.dp, tint = if (enabled) tint else g.muted)
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) tint else g.muted, maxLines = 1)
    }
}

@Composable
private fun InfoPill(icon: Ic, text: String) {
    val g = GlassTheme.colors
    Row(
        Modifier.glass(PillShape).padding(start = 10.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SymIcon(icon, size = 16.dp, tint = g.muted)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun FilterChip(text: String, selected: Boolean, icon: Ic? = null, onClick: () -> Unit) {
    val g = GlassTheme.colors
    Row(
        Modifier
            .pressable(onClick)
            .then(if (selected) Modifier.background(onBrush(), PillShape) else Modifier.glass(PillShape))
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { SymIcon(it, filled = selected, size = 16.dp, tint = if (selected) g.onInk else g.fg) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) g.onInk else g.fg, maxLines = 1)
    }
}

@Composable
private fun RecipeSearch(value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    val g = GlassTheme.colors
    Row(
        modifier.glass(PillShape).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SymIcon(Sym.search, size = 20.dp, tint = g.muted)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Search recipes or ingredients", style = MaterialTheme.typography.bodyLarge, color = g.muted, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = g.fg),
                cursorBrush = SolidColor(g.fg),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) SymIcon(Sym.close, size = 18.dp, tint = g.muted, modifier = Modifier.pressable({ onValue("") }))
    }
}
