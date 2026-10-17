package app.homeydash.ui.dashboard

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.homeydash.assistant.FaceService
import app.homeydash.data.Kitchen
import app.homeydash.fridge.Basis
import app.homeydash.fridge.DateKind
import app.homeydash.fridge.Estimate
import app.homeydash.fridge.FoodRules
import app.homeydash.fridge.FridgeItem
import app.homeydash.fridge.Place
import app.homeydash.fridge.Urgency
import app.homeydash.ui.components.GlassDialog
import app.homeydash.ui.components.DialogHeader
import app.homeydash.ui.components.Ic
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.RoundButton
import app.homeydash.ui.components.SectionLabel
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.TileShape
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.onBrush
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The fridge, freezer and pantry: everything tracked, what to eat first at the top, and a
 * scanner on the front camera for putting the shopping away. The voice assistant reads and
 * changes the same list through [Kitchen.fridge].
 */
@Composable
fun FridgeScreen(kitchen: Kitchen, faces: FaceService?) {
    val g = GlassTheme.colors
    val stock by kitchen.fridge.items.collectAsStateWithLifecycle()
    val loaded by kitchen.fridge.loaded.collectAsStateWithLifecycle()
    val scanRequest by kitchen.scanRequest.collectAsStateWithLifecycle()
    val today = rememberToday()
    var place by rememberSaveable { mutableStateOf<Place?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FridgeItem?>(null) }

    // The overview's scan button lands here with the scanner open.
    LaunchedEffect(scanRequest) {
        if (scanRequest) {
            scanning = true
            kitchen.scanRequest.value = false
        }
    }

    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = g.fg) }
        return
    }
    val all = remember(stock, today) { stock.map { it to FoodRules.estimate(it, today) }.sortedWith(compareBy({ it.second.date }, { it.first.name.lowercase() })) }
    val shown = all.filter { place == null || it.first.place == place }
    val first = shown.filter { it.first.place != Place.Freezer && it.second.urgency != Urgency.Fine }
    val rest = shown - first.toSet()

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(eyebrow = "Kitchen", title = "Fridge") {
            PillButton(Sym.add, "Add by hand") { editing = FridgeItem(name = "", bought = today.toString()) }
            PillButton(Sym.barcode, "Scan", primary = true) { scanning = true }
        }
        if (stock.isEmpty()) {
            EmptyFridge(onScan = { scanning = true }, onAdd = { editing = FridgeItem(name = "", bought = today.toString()) })
            return@Column
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip("All · ${all.size}", place == null) { place = null } }
            Place.entries.forEach { p ->
                val count = all.count { it.first.place == p }
                if (count > 0) item(p.name) { FilterChip("${p.en} · $count", place == p, placeIcon(p)) { place = if (place == p) null else p } }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 340.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 96.dp),
            modifier = Modifier.weight(1f),
        ) {
            if (first.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Eat first") }
                items(first, key = { it.first.id }) { (item, e) -> FridgeItemCard(item, e, onClick = { editing = item }) }
                if (rest.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Later") }
            }
            items(rest, key = { it.first.id }) { (item, e) -> FridgeItemCard(item, e, onClick = { editing = item }) }
        }
    }

    if (scanning) {
        ScannerDialog(kitchen, faces, onEdit = { draft -> scanning = false; editing = draft }, onDismiss = { scanning = false })
    }
    editing?.let { item ->
        FridgeItemDialog(
            kitchen = kitchen,
            initial = item,
            onDone = { editing = null },
            onScanNext = if (item.code != null && item.id.isBlank()) ({ editing = null; scanning = true }) else null,
        )
    }
}

@Composable
private fun EmptyFridge(onScan: () -> Unit, onAdd: () -> Unit) {
    val g = GlassTheme.colors
    Column(
        Modifier.fillMaxWidth().glass(TileShape).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SymIcon(Sym.fridge, filled = true, size = 56.dp, tint = g.fg)
        Text("Nothing in the fridge yet", style = MaterialTheme.typography.headlineSmall, color = g.fg)
        Text(
            "Scan each thing as you put the shopping away. Hold its barcode 15 to 25 cm in front of the camera above the screen. " +
                "The tablet works out how long it keeps and reminds you what to eat first. You can also tell the assistant, e.g. \"lägg till köttfärs i kylen\".",
            style = MaterialTheme.typography.bodyLarge,
            color = g.muted,
            modifier = Modifier.width(620.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            PillButton(Sym.add, "Add by hand", big = true, onClick = onAdd)
            PillButton(Sym.barcode, "Scan groceries", primary = true, big = true, onClick = onScan)
        }
    }
}

@Composable
private fun FridgeItemCard(item: FridgeItem, e: Estimate, onClick: () -> Unit) {
    val g = GlassTheme.colors
    Row(
        Modifier.fillMaxWidth().pressable(onClick).glass(TileShape).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FoodEmoji(item.categoryInfo.emoji, 52.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (item.quantity > 1) Text("  ×${item.quantity}", style = MaterialTheme.typography.titleMedium, color = g.muted)
            }
            Text(
                listOfNotNull(
                    item.brand,
                    item.amount,
                    if (item.place == Place.Freezer) "Frozen ${shortDay(item.frozenDate() ?: item.boughtDate())}" else "Bought ${shortDay(item.boughtDate())}",
                    "Opened".takeIf { item.opened != null && item.place != Place.Freezer },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = g.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ShelfPill(e)
    }
}

/** The days left, coloured by how soon, and always in words so the matte ink mode still reads it. */
@Composable
internal fun ShelfPill(e: Estimate, compact: Boolean = false) {
    val g = GlassTheme.colors
    val color = urgencyColor(e)
    Row(
        Modifier.background(color.copy(alpha = 0.18f), PillShape).padding(horizontal = if (compact) 10.dp else 12.dp, vertical = if (compact) 4.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(
            if (e.basis == Basis.Guessed && e.daysLeft >= 0) "~" + e.label() else e.label(),
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            color = g.fg,
            maxLines = 1,
        )
    }
}

@Composable
private fun urgencyColor(e: Estimate): Color {
    val g = GlassTheme.colors
    return when (e.urgency) {
        Urgency.Gone, Urgency.Today -> g.danger
        Urgency.Soon -> Amber
        Urgency.Fine -> if (e.basis == Basis.Frozen) g.cool else g.ok
    }
}

private val Amber = Color(0xFFE2B33C)

@Composable
internal fun FoodEmoji(emoji: String, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).glass(CircleShape), contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = (size.value * 0.48f).sp)
    }
}

private fun placeIcon(p: Place): Ic = when (p) {
    Place.Fridge -> Sym.fridge
    Place.Freezer -> Sym.acUnit
    Place.Pantry -> Sym.pantry
}

private val SHORT_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

internal fun shortDay(d: LocalDate?): String = d?.format(SHORT_DAY) ?: "?"

/** Today's date, moving on at midnight while the dashboard stays on screen. */
@Composable
internal fun rememberToday(): LocalDate {
    val day by produceState(LocalDate.now()) {
        while (true) {
            value = LocalDate.now()
            delay(5 * 60_000L)
        }
    }
    return day
}

// ---------------------------------------------------------------- Add and edit

/**
 * Confirms a scanned item, adds one by hand, or changes one already in the fridge
 * ([initial] has an id). The eat-by date updates as the kind, place and dates change.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FridgeItemDialog(
    kitchen: Kitchen,
    initial: FridgeItem,
    onDone: () -> Unit,
    /** Shown on new scanned items: save and go straight back to the scanner. */
    onScanNext: (() -> Unit)? = null,
) {
    val g = GlassTheme.colors
    val scope = rememberCoroutineScope()
    val isNew = initial.id.isBlank()
    val today = rememberToday()
    var item by remember(initial) { mutableStateOf(initial) }
    var confirmDelete by remember { mutableStateOf(false) }
    val category = item.categoryInfo
    val e = FoodRules.estimate(item, today)

    fun save(then: () -> Unit) = scope.launch {
        if (isNew) kitchen.fridge.add(item) else kitchen.fridge.save(item)
        then()
    }

    GlassDialog(onDismiss = onDone, width = 860.dp) {
        DialogHeader(
            when {
                !isNew -> item.name.ifBlank { "Edit" }
                initial.code != null -> "Scanned"
                else -> "Add to the fridge"
            },
            when {
                !isNew -> "${category.en} · in the ${item.place.en.lowercase()}"
                initial.code?.startsWith("store:") == true -> "A label from the store's counter. Say what it is once and the tablet remembers it."
                initial.code != null && initial.name.isBlank() -> "Not a barcode the tablet knows. Name it once and it is remembered."
                else -> null
            },
            onDone,
            leading = { FoodEmoji(category.emoji, 52.dp) },
        )
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassField(
                item.name,
                { name ->
                    // While the kind hasn't been picked by hand, it follows the name.
                    val guess = FoodRules.classify(name)
                    item = if (item.category == FoodRules.classify(item.name).key || item.category == FoodRules.other.key) {
                        item.copy(name = name, category = guess.key, place = if (isNew) guess.place else item.place)
                    } else item.copy(name = name)
                },
                "What is it? e.g. Köttfärs, Mellanmjölk, Rester lasagne",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassField(item.brand.orEmpty(), { item = item.copy(brand = it.ifBlank { null }) }, "Brand (optional)", Modifier.weight(1f))
                GlassField(item.amount.orEmpty(), { item = item.copy(amount = it.ifBlank { null }) }, "Size, e.g. 500 g", Modifier.weight(1f))
                Stepper(item.quantity, { item = item.copy(quantity = it.coerceIn(1, 99)) })
            }

            SectionLabel("Kind · sets how long it keeps")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FoodRules.all.forEach { c ->
                    FilterChip("${c.emoji} ${c.en}", c.key == item.category) {
                        item = item.copy(category = c.key, place = if (isNew) c.place else item.place)
                    }
                }
            }

            SectionLabel("Where")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Place.entries.forEach { p ->
                    FilterChip(p.en, item.place == p, placeIcon(p)) {
                        item = when {
                            isNew -> item.copy(place = p, frozen = if (p == Place.Freezer) item.bought else null)
                            p == item.place -> item
                            p == Place.Freezer -> item.copy(place = p, frozen = today.toString(), thawed = null)
                            item.place == Place.Freezer -> item.copy(place = p, thawed = today.toString())
                            else -> item.copy(place = p)
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("Bought")
                    DateButton(item.boughtDate() ?: today, onPick = { item = item.copy(bought = it.toString()) })
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("Date on the pack")
                    val printed = item.printedDate()
                    if (printed == null) {
                        DateButton(null, placeholder = "None · tap to add", onPick = { item = item.copy(date = it.toString()) })
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            DateButton(printed, onPick = { item = item.copy(date = it.toString()) }, modifier = Modifier.weight(1f))
                            RoundButton(Sym.close, { item = item.copy(date = null, dateKind = null) }, size = 40.dp, filledIcon = false)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val kind = item.dateKind ?: if (category.strict) DateKind.UseBy else DateKind.BestBefore
                            FilterChip("Bäst före", kind == DateKind.BestBefore) { item = item.copy(dateKind = DateKind.BestBefore) }
                            FilterChip("Sista förbr.dag", kind == DateKind.UseBy) { item = item.copy(dateKind = DateKind.UseBy) }
                        }
                    }
                }
            }

            if (item.place != Place.Freezer) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(if (item.opened != null) "Opened ${shortDay(item.openedDate())}" else "Not opened", item.opened != null) {
                        item = item.copy(opened = if (item.opened == null) today.toString() else null)
                    }
                }
            }

            EatByRow(item, e)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!isNew) {
                PillButton(Sym.trash, if (confirmDelete) "Tap again to remove" else "Remove") {
                    if (!confirmDelete) confirmDelete = true
                    else scope.launch { kitchen.fridge.remove(item.id); onDone() }
                }
                if (item.quantity > 1) PillButton(Sym.remove, "Used one") {
                    scope.launch { kitchen.fridge.useOne(item.id); onDone() }
                }
            }
            Spacer(Modifier.weight(1f))
            if (isNew && onScanNext != null) PillButton(Sym.barcode, "Save & scan next", enabled = item.name.isNotBlank()) { save(onScanNext) }
            PillButton(Sym.check, if (isNew) "Add" else "Save", primary = true, enabled = item.name.isNotBlank()) { save(onDone) }
        }
    }
}

/** The eat-by date and how it was worked out, in plain words. */
@Composable
private fun EatByRow(item: FridgeItem, e: Estimate) {
    val g = GlassTheme.colors
    val c = item.categoryInfo
    val why = when (e.basis) {
        Basis.Printed -> if (e.strict) "the use-by date on the pack" else "the best-before date on the pack"
        Basis.Product -> "this product usually keeps ${days(item.keeps ?: c.days)} unopened"
        Basis.Guessed -> "${c.en.lowercase()} usually keeps ${days(c.days)} unopened"
        Basis.Opened -> "${if (item.keepsOpened != null) "this product" else c.en.lowercase()} keeps about ${days(item.keepsOpened ?: c.openedDays)} once opened"
        Basis.Frozen -> "best quality for about ${days(c.freezerDays.takeIf { it > 0 } ?: 180)} frozen"
        Basis.Thawed -> "thawed food keeps a day or two in the fridge"
    }
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SymIcon(Sym.hourglass, filled = true, size = 28.dp, tint = urgencyColor(e))
        Column(Modifier.weight(1f)) {
            Text("Eat by ${shortDay(e.date)}", style = MaterialTheme.typography.titleMedium, color = g.fg, fontWeight = FontWeight.SemiBold)
            Text("From $why." + (e.advice()?.let { " $it" } ?: ""), style = MaterialTheme.typography.bodyMedium, color = g.muted)
        }
        ShelfPill(e)
    }
}

private fun days(n: Int): String = when {
    n >= 60 -> "${n / 30} months"
    n >= 14 -> "${n / 7} weeks"
    n == 1 -> "1 day"
    else -> "$n days"
}

@Composable
private fun DateButton(date: LocalDate?, onPick: (LocalDate) -> Unit, modifier: Modifier = Modifier, placeholder: String = "") {
    val g = GlassTheme.colors
    val context = LocalContext.current
    Row(
        modifier
            .pressable({
                val d = date ?: LocalDate.now()
                DatePickerDialog(context, { _, y, m, day -> onPick(LocalDate.of(y, m + 1, day)) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
            })
            .glass(PillShape)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SymIcon(Sym.calendar, size = 18.dp, tint = g.muted)
        Text(date?.let { shortDay(it) } ?: placeholder, style = MaterialTheme.typography.bodyLarge, color = if (date == null) g.muted else g.fg, maxLines = 1)
    }
}

@Composable
private fun Stepper(value: Int, onValue: (Int) -> Unit) {
    val g = GlassTheme.colors
    Row(Modifier.glass(PillShape).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(Sym.remove, { onValue(value - 1) }, size = 38.dp, enabled = value > 1)
        Text("$value", style = MaterialTheme.typography.titleMedium, color = g.fg, modifier = Modifier.padding(horizontal = 12.dp))
        RoundButton(Sym.add, { onValue(value + 1) }, size = 38.dp)
    }
}

// ---------------------------------------------------------------- Overview card

/** The overview's fridge card: the few things to eat first, and a scan button. */
@Composable
fun FridgeCard(kitchen: Kitchen, onOpen: () -> Unit, onScan: () -> Unit) {
    val g = GlassTheme.colors
    val items by kitchen.fridge.items.collectAsStateWithLifecycle()
    val today = rememberToday()
    val sorted = remember(items, today) {
        items.filter { it.place != Place.Freezer }.map { it to FoodRules.estimate(it, today) }.sortedBy { it.second.date }
    }
    val urgent = sorted.count { it.second.urgency != Urgency.Fine }
    Column(
        Modifier.fillMaxWidth().pressable(onOpen).glass(TileShape).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SymIcon(Sym.fridge, filled = true, size = 20.dp, tint = g.fg)
            Column(Modifier.weight(1f)) {
                Text("Fridge", style = MaterialTheme.typography.titleMedium, color = g.fg)
                Text(
                    when {
                        items.isEmpty() -> "Nothing tracked yet"
                        urgent == 0 -> "${items.size} things · nothing goes off soon"
                        urgent == 1 -> "${items.size} things · 1 to eat soon"
                        else -> "${items.size} things · $urgent to eat soon"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = g.muted,
                    maxLines = 1,
                )
            }
            Box(
                Modifier.size(40.dp).pressable(onScan).background(onBrush(), CircleShape),
                contentAlignment = Alignment.Center,
            ) { SymIcon(Sym.barcode, filled = true, size = 20.dp, tint = g.onInk) }
        }
        if (items.isEmpty()) {
            Text(
                "Tap the barcode button and hold groceries up to the camera as you put them away.",
                style = MaterialTheme.typography.bodyLarge,
                color = g.muted,
            )
        } else {
            sorted.take(4).forEach { (item, e) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(item.categoryInfo.emoji, fontSize = 20.sp)
                    Text(
                        item.name + if (item.quantity > 1) " ×${item.quantity}" else "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = g.fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    ShelfPill(e, compact = true)
                }
            }
        }
    }
}
