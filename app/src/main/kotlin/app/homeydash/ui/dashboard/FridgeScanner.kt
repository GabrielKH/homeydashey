package app.homeydash.ui.dashboard

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.homeydash.assistant.FaceService
import app.homeydash.data.Kitchen
import app.homeydash.fridge.FoodRules
import app.homeydash.fridge.FridgeItem
import app.homeydash.fridge.ScanCode
import app.homeydash.fridge.ScanCodes
import app.homeydash.fridge.Source
import app.homeydash.ui.components.DialogHeader
import app.homeydash.ui.components.GlassDialog
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.RoundButton
import app.homeydash.ui.components.SectionLabel
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import android.view.MotionEvent
import android.view.WindowManager
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import app.homeydash.fridge.DateKind
import app.homeydash.fridge.FoundDate
import app.homeydash.fridge.PackDate
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scans groceries on the tablet's front camera. Something scanned before is added straight
 * away (with an undo); anything new is looked up (databases, then the internet AI) and opens
 * [onEdit] to confirm. After each barcode the camera keeps looking for the date on the pack
 * until it finds it, the next barcode is scanned, or Skip date is tapped.
 */
@Composable
internal fun ScannerDialog(kitchen: Kitchen, faces: FaceService?, onEdit: (FridgeItem) -> Unit, onDismiss: () -> Unit) {
    val g = GlassTheme.colors
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val stock by kitchen.fridge.items.collectAsStateWithLifecycle()
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        if (!granted && permanentlyRefused(context, Manifest.permission.CAMERA)) openAppSettings(context)
    }
    LaunchedEffect(Unit) { if (!allowed) ask.launch(Manifest.permission.CAMERA) }

    var front by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var typed by remember { mutableStateOf("") }
    var added by remember { mutableStateOf(listOf<String>()) }
    var last by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var hunt by remember { mutableStateOf<DateHunt?>(null) }
    var seenDate by remember { mutableStateOf<java.time.LocalDate?>(null) }
    // What the last scan took out because it was already in the fridge, to put back.
    var used by remember { mutableStateOf<Pair<FridgeItem, String>?>(null) }
    val beep = remember { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }.getOrNull() }

    // Face recognition watches the same front camera; it gets the camera back afterwards.
    DisposableEffect(owner) {
        val hadFaces = faces?.running == true
        faces?.stop()
        onDispose {
            beep?.release()
            if (hadFaces) faces?.start(owner)
        }
    }

    fun withDate(item: FridgeItem, d: FoundDate) = item.copy(date = d.date.toString(), dateKind = d.kind ?: item.dateKind)

    /** Skip date: a new product goes to the confirm dialog without one. */
    fun endHunt() {
        val h = hunt ?: return
        hunt = null
        seenDate = null
        h.draft?.let(onEdit)
    }

    fun dateFound(d: FoundDate) {
        val h = hunt ?: return
        beep?.startTone(ToneGenerator.TONE_PROP_ACK, 90)
        when {
            h.itemId != null -> {
                hunt = null
                scope.launch {
                    kitchen.fridge.get(h.itemId)?.let { kitchen.fridge.save(withDate(it, d)) }
                    status = "${h.name ?: "Added"} · ${if (d.kind == DateKind.UseBy) "use by" else "date"} ${shortDay(d.date)}"
                }
            }
            h.draft != null -> {
                hunt = null
                onEdit(withDate(h.draft, d))
            }
            // Still looking the barcode up: keep the date for when it is back.
            else -> hunt = h.copy(date = d)
        }
        seenDate = null
    }

    fun handleText(text: String) {
        val h = hunt ?: return
        if (h.date != null) return
        val d = PackDate.find(text, kitchen.fridge.today()) ?: return
        // Two frames agreeing, so one misread digit doesn't make it in.
        if (seenDate == d.date) dateFound(d) else seenDate = d.date
    }

    fun handle(raw: String, forceAdd: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (busy) return
        // The same code held in view is read many times a second: it counts again only after
        // it has been out of view for a moment.
        if (!forceAdd) last?.let { (code, at) ->
            if (code == raw && now - at < SAME_CODE_PAUSE_MS) {
                last = raw to now
                return
            }
        }
        // A new product waiting for its date is confirmed first.
        if (hunt?.draft != null) return
        last = raw to now
        hunt = null
        seenDate = null
        used = null
        beep?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
        val code = ScanCodes.parse(raw) ?: ScanCode(raw, key = "qr:$raw")
        // Already in the fridge and not just bought: scanning it again means it is eaten or finished.
        val inStock = if (forceAdd) null else kitchen.fridge.scannedAgain(code.key)
        if (inStock != null) {
            busy = true
            scope.launch {
                try {
                    val left = kitchen.fridge.useOne(inStock.id)
                    used = inStock to raw
                    added = added.filterNot { it == inStock.id && left == 0 }
                    status = if (left > 0) "Took out one ${inStock.name} · $left left" else "${inStock.name} is finished"
                } finally {
                    busy = false
                }
            }
            return
        }
        busy = true
        // A GS1 2D code carries its own date; otherwise read it off the pack meanwhile.
        if (code.date == null) hunt = DateHunt(raw)
        status = if (code.date == null) "Now show the date on the pack" else null
        scope.launch {
            try {
                val found = kitchen.fridge.identify(code)
                val h = hunt?.takeIf { it.raw == raw }
                val item = h?.date?.let { withDate(found.item, it) } ?: found.item
                if (found.source == Source.Known && item.name.isNotBlank()) {
                    val saved = kitchen.fridge.add(item)
                    added = listOf(saved.id) + added.filterNot { it == saved.id }
                    if (h != null && h.date == null) {
                        hunt = h.copy(itemId = saved.id, name = saved.name)
                        status = "Added ${saved.name} · show the date"
                    } else {
                        hunt = null
                        status = "Added ${saved.name}" + (saved.printedDate()?.let { " · date ${shortDay(it)}" } ?: "")
                    }
                } else if (h != null && h.date == null) {
                    hunt = h.copy(draft = item, name = item.name.ifBlank { null })
                    status = (if (item.name.isNotBlank()) "${item.name} · " else "New product · ") + "show the date"
                } else {
                    hunt = null
                    onEdit(item)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hunt = null
                status = "Couldn't read that: ${e.message ?: e::class.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    if (front) BrightScreen()

    GlassDialog(onDismiss = onDismiss, width = 1040.dp) {
        DialogHeader("Scan groceries", "Hold the barcode 15 to 25 cm in front of the camera above the screen, then the date. Tap the picture to focus.", onDismiss) {
            SymIcon(Sym.barcode, filled = true, size = 30.dp, tint = g.fg)
        }
        Row(Modifier.fillMaxWidth().height(440.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(
                Modifier.weight(1.35f).fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (allowed) {
                    val readDate = hunt?.date == null && hunt != null
                    BarcodeCamera(front, readText = readDate, onCode = { handle(it) }, onText = ::handleText, modifier = Modifier.fillMaxSize())
                    // Where to hold the code, or the date.
                    Box(
                        Modifier.fillMaxWidth(0.62f).aspectRatio(if (readDate) 3.2f else 2.2f)
                            .border(3.dp, if (readDate) g.on else Color.White.copy(alpha = 0.85f), RoundedCornerShape(18.dp)),
                    )
                    if (busy) CircularProgressIndicator(color = Color.White)
                    used?.let { (u, raw) ->
                        Row(Modifier.align(Alignment.TopEnd).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Put back" to false, "Add one instead" to true).forEach { (label, addInstead) ->
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White,
                                    modifier = Modifier
                                        .pressable({
                                            used = null
                                            scope.launch {
                                                kitchen.fridge.putBack(u)
                                                status = "Put back ${u.name}"
                                                if (addInstead) handle(raw, forceAdd = true)
                                            }
                                        })
                                        .background(Color.Black.copy(alpha = 0.55f), PillShape)
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                )
                            }
                        }
                    }
                    if (hunt != null) {
                        Text(
                            "Skip date",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            modifier = Modifier.align(Alignment.TopEnd).padding(14.dp)
                                .pressable({ endHunt() }).background(Color.Black.copy(alpha = 0.55f), PillShape)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("The scanner needs the camera.", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        PillButton(Sym.face, "Allow camera", primary = true) { ask.launch(Manifest.permission.CAMERA) }
                    }
                }
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(14.dp).background(Color.Black.copy(alpha = 0.55f), PillShape).padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("Added now")
                val now = added.mapNotNull { id -> stock.firstOrNull { it.id == id } }
                if (now.isEmpty()) {
                    Text(
                        "Things scanned before are added straight away. New ones are looked up, on the internet if need be, and asked about once. " +
                            "After the barcode, show the date and it is read off the pack. Weighed meat and fish from the counter work too.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = g.muted,
                    )
                } else {
                    LazyColumn(Modifier.weight(4f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(now, key = { it.id }) { item ->
                            Row(
                                Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(item.categoryInfo.emoji, style = MaterialTheme.typography.titleLarge)
                                Column(Modifier.weight(1f)) {
                                    Text(item.name + if (item.quantity > 1) " ×${item.quantity}" else "", style = MaterialTheme.typography.bodyLarge, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    ShelfPill(FoodRules.estimate(item, kitchen.fridge.today()), compact = true)
                                }
                                Text(
                                    "Undo",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = g.fg,
                                    modifier = Modifier.pressable({ scope.launch { kitchen.fridge.useOne(item.id, eaten = false) } }).glass(PillShape).padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                SectionLabel("Or type the barcode")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassField(
                        typed, { typed = it.filter { c -> c.isDigit() } }, "e.g. 7310865004703",
                        Modifier.weight(1f), keyboardType = KeyboardType.Number,
                        onGo = { if (typed.length >= 8) { handle(typed); typed = "" } },
                    )
                    RoundButton(Sym.check, { if (typed.length >= 8) { handle(typed); typed = "" } }, size = 44.dp, enabled = typed.length >= 8)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(Sym.refresh, if (front) "Use back camera" else "Use front camera") { front = !front }
                    Spacer(Modifier.weight(1f))
                    PillButton(Sym.check, "Done", primary = true, onClick = onDismiss)
                }
            }
        }
    }
}

private const val SAME_CODE_PAUSE_MS = 1500L

/**
 * Reading the date after a barcode: [raw] is the barcode; [itemId] the item already added (a
 * known product), or [draft] a new one waiting to be confirmed; [date] when found before the
 * lookup came back.
 */
private data class DateHunt(
    val raw: String,
    val itemId: String? = null,
    val draft: FridgeItem? = null,
    val name: String? = null,
    val date: FoundDate? = null,
)

/** Full screen brightness while scanning on the front camera: the screen lights the pack. */
@Composable
private fun BrightScreen() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        fun set(value: Float) {
            window ?: return
            window.attributes = window.attributes.apply { screenBrightness = value }
        }
        val before = window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        set(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL)
        onDispose { set(before) }
    }
}

/**
 * The camera picture with ML Kit reading every code it sees, zooming in by itself on a code
 * that is too small, and focusing on the middle (or where it is tapped). With [readText] every
 * other frame is read as text instead, for the date. Callbacks run on the main thread.
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun BarcodeCamera(front: Boolean, readText: Boolean, onCode: (String) -> Unit, onText: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val currentOnText by rememberUpdatedState(onText)
    val wantText = remember { AtomicBoolean(false) }
    SideEffect { wantText.set(readText) }
    val view = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // A TextureView, which draws correctly inside a dialog window.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    AndroidView({ view }, modifier)
    DisposableEffect(owner, front) {
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val reader = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var scanner: BarcodeScanner? = null
        var camera: Camera? = null
        var provider: ProcessCameraProvider? = null
        var disposed = false

        fun focus(point: MeteringPoint) {
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
            camera?.let { c -> if (c.cameraInfo.isFocusMeteringSupported(action)) c.cameraControl.startFocusAndMetering(action) }
        }
        val center = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f, 0.35f)
        @Suppress("ClickableViewAccessibility")
        view.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                focus(view.meteringPointFactory.createPoint(e.x, e.y))
                v.performClick()
            }
            true
        }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            fun has(s: CameraSelector) = runCatching { p.hasCamera(s) }.getOrDefault(false)
            val selector = when {
                front && has(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                has(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                else -> CameraSelector.DEFAULT_FRONT_CAMERA
            }
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            // About 1280x720: enough pixels for an EAN-13 held at arm's length and a printed date,
            // light enough to read many times a second.
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(android.util.Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val bound = runCatching {
                p.unbindAll()
                p.bindToLifecycle(owner, selector, preview, analysis).also { provider = p }
            }.getOrNull() ?: return@addListener
            camera = bound
            focus(center)

            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_CODE_128,
                )
            // ML Kit asks for more zoom when it sees a code too small to read.
            val maxZoom = bound.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
            if (maxZoom > 1.05f) {
                options.setZoomSuggestionOptions(
                    ZoomSuggestionOptions.Builder { ratio ->
                        if (disposed) false else {
                            bound.cameraControl.setZoomRatio(ratio.coerceAtMost(maxZoom))
                            true
                        }
                    }.setMaxSupportedZoomRatio(maxZoom).build(),
                )
            }
            val codes = BarcodeScanning.getClient(options.build()).also { scanner = it }

            var frame = 0L
            analysis.setAnalyzer(executor) { proxy ->
                val image = proxy.image
                if (image == null || disposed) {
                    proxy.close()
                    return@setAnalyzer
                }
                val input = InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees)
                frame++
                if (wantText.get() && frame % 2 == 0L) {
                    reader.process(input)
                        .addOnSuccessListener(main) { text -> if (!disposed && text.text.isNotBlank()) currentOnText(text.text) }
                        .addOnCompleteListener { proxy.close() }
                } else {
                    codes.process(input)
                        .addOnSuccessListener(main) { found ->
                            if (!disposed) found.firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }?.let { currentOnCode(it) }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
            }
        }, main)
        onDispose {
            disposed = true
            view.setOnTouchListener(null)
            runCatching { provider?.unbindAll() }
            scanner?.close()
            reader.close()
            executor.shutdown()
        }
    }
}
