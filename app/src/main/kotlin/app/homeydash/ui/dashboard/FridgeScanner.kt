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

/**
 * Scans groceries on the tablet's front camera. Something scanned before is added straight
 * away (with an undo); anything new opens [onEdit] to confirm its name, kind and date.
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

    fun handle(raw: String) {
        val now = SystemClock.elapsedRealtime()
        if (busy) return
        // The same code held in view is read many times a second.
        last?.let { (code, at) -> if (code == raw && now - at < SAME_CODE_PAUSE_MS) return }
        last = raw to now
        busy = true
        beep?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        scope.launch {
            try {
                val code = ScanCodes.parse(raw) ?: ScanCode(raw, key = "qr:$raw")
                val found = kitchen.fridge.identify(code)
                if (found.source == Source.Known && found.item.name.isNotBlank()) {
                    val item = kitchen.fridge.add(found.item)
                    added = listOf(item.id) + added.filterNot { it == item.id }
                    status = "Added ${item.name}" + (found.item.printedDate()?.let { " · date ${shortDay(it)}" } ?: "")
                } else {
                    onEdit(found.item)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Couldn't read that: ${e.message ?: e::class.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    GlassDialog(onDismiss = onDismiss, width = 1040.dp) {
        DialogHeader("Scan groceries", "Hold the barcode 15 to 25 cm in front of the camera above the screen", onDismiss) {
            SymIcon(Sym.barcode, filled = true, size = 30.dp, tint = g.fg)
        }
        Row(Modifier.fillMaxWidth().height(440.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(
                Modifier.weight(1.35f).fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (allowed) {
                    BarcodeCamera(front, onCode = ::handle, modifier = Modifier.fillMaxSize())
                    // Where to hold the code.
                    Box(
                        Modifier.fillMaxWidth(0.62f).aspectRatio(2.2f).border(3.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(18.dp)),
                    )
                    if (busy) CircularProgressIndicator(color = Color.White)
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
                        "Things scanned before are added straight away. New ones ask what they are once. " +
                            "Weighed meat and fish from the counter work too.",
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
                                    modifier = Modifier.pressable({ scope.launch { kitchen.fridge.useOne(item.id) } }).glass(PillShape).padding(horizontal = 14.dp, vertical = 8.dp),
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

private const val SAME_CODE_PAUSE_MS = 2500L

/** The camera picture with ML Kit reading every code it sees; [onCode] runs on the main thread. */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun BarcodeCamera(front: Boolean, onCode: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
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
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_CODE_128,
                )
                .build(),
        )
        var provider: ProcessCameraProvider? = null
        var disposed = false
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
            // About 1280x720: enough pixels for an EAN-13 held at arm's length, light enough to read many times a second.
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(android.util.Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                val image = proxy.image
                if (image == null) {
                    proxy.close()
                    return@setAnalyzer
                }
                scanner.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener(main) { codes ->
                        if (!disposed) codes.firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }?.let { currentOnCode(it) }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            runCatching {
                p.unbindAll()
                p.bindToLifecycle(owner, selector, preview, analysis)
                provider = p
            }
        }, main)
        onDispose {
            disposed = true
            runCatching { provider?.unbindAll() }
            scanner.close()
            executor.shutdown()
        }
    }
}
