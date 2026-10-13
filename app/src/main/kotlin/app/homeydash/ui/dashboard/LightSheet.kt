package app.homeydash.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.homeydash.homey.Device
import app.homeydash.ui.components.Bubble
import app.homeydash.ui.components.DialogHeader
import app.homeydash.ui.components.GlassDialog
import app.homeydash.ui.components.PillShape
import app.homeydash.ui.components.Sym
import app.homeydash.ui.components.SymIcon
import app.homeydash.ui.components.deviceIcon
import app.homeydash.ui.components.glass
import app.homeydash.ui.components.inkOn
import app.homeydash.ui.components.pressable
import app.homeydash.ui.theme.GlassTheme
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private val CoolWhite = Color(0xFFDDE9FF)
private val WarmWhite = Color(0xFFFFBE73)

/** Homey's light_temperature runs from 0 (cool) to 1 (warm). */
fun whiteAt(temperature: Float): Color = lerp(CoolWhite, WarmWhite, temperature.coerceIn(0f, 1f))

/** The colour a light is set to, from its hue/saturation or white temperature; null if it has neither. */
fun lightColor(device: Device): Color? {
    val hue = device.capability("light_hue")?.numberValue?.toFloat()
    val saturation = device.capability("light_saturation")?.numberValue?.toFloat() ?: 1f
    val temperature = device.capability("light_temperature")?.numberValue?.toFloat()
    val mode = device.capability("light_mode")?.stringValue
    return when {
        hue != null && (mode == "color" || temperature == null || (mode == null && saturation > 0.15f)) ->
            Color.hsv((hue * 360f).coerceIn(0f, 359.9f), saturation.coerceIn(0f, 1f), 1f)
        temperature != null -> whiteAt(temperature)
        else -> null
    }
}

fun hasLightControls(device: Device): Boolean =
    listOf("dim", "light_hue", "light_temperature").any { device.capability(it)?.setable == true }

/** Sends at most every [intervalMs] while a finger drags, so the lamp follows without flooding the Homey. */
private class Throttle(private val intervalMs: Long = 300) {
    private var last = 0L
    fun ready(): Boolean {
        val now = System.currentTimeMillis()
        if (now - last < intervalMs) return false
        last = now
        return true
    }
}

/** A lamp up close: brightness, colour wheel and white temperature. */
@Composable
fun LightSheet(device: Device, actions: DashboardActions, onDismiss: () -> Unit) {
    val g = GlassTheme.colors
    val onoff = device.capability("onoff")?.takeIf { it.setable }
    val dim = device.capability("dim")?.takeIf { it.setable }
    val hueCap = device.capability("light_hue")?.takeIf { it.setable }
    val satCap = device.capability("light_saturation")?.takeIf { it.setable }
    val tempCap = device.capability("light_temperature")?.takeIf { it.setable }
    val modeCap = device.capability("light_mode")?.takeIf { it.setable }
    val isOn = device.capability("onoff")?.booleanValue ?: ((dim?.numberValue ?: 0.0) > 0)
    val color = lightColor(device) ?: g.on

    fun turnOn() {
        if (!isOn && onoff != null) actions.set(device.id, onoff.id, JsonPrimitive(true))
    }

    var colourTab by remember { mutableStateOf(hueCap != null && device.capability("light_mode")?.stringValue != "temperature") }

    GlassDialog(onDismiss = onDismiss, width = if (hueCap != null || tempCap != null) 780.dp else 420.dp) {
        DialogHeader(
            title = device.name,
            subtitle = when {
                !isOn -> "Off"
                dim != null -> "On · ${percent(fraction(dim) ?: 0f)}"
                else -> "On"
            },
            onClose = onDismiss,
            leading = { Bubble(deviceIcon(device), on = isOn, onColor = color, size = 52.dp) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Top) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                BrightnessSlider(
                    level = if (isOn) dim?.let(::fraction) ?: 1f else 0f,
                    color = color,
                    onLevel = dim?.let { cap ->
                        { f: Float ->
                            actions.set(device.id, cap.id, JsonPrimitive(valueAt(cap, f.coerceAtLeast(0.01f))))
                            turnOn()
                        }
                    },
                    onTap = onoff?.let { cap -> { actions.set(device.id, cap.id, JsonPrimitive(!isOn)) } },
                )
                onoff?.let { cap ->
                    Row(
                        Modifier
                            .pressable({ actions.set(device.id, cap.id, JsonPrimitive(!isOn)) })
                            .then(if (isOn) Modifier.background(color, PillShape) else Modifier.glass(PillShape))
                            .padding(horizontal = 22.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val ink = if (isOn) inkOn(color) else g.fg
                        SymIcon(Sym.power, size = 20.dp, tint = ink)
                        Text(if (isOn) "Turn off" else "Turn on", style = MaterialTheme.typography.labelLarge, color = ink)
                    }
                }
            }
            if (hueCap != null || tempCap != null) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (hueCap != null && tempCap != null) {
                        Segmented(
                            options = listOf("Colour", "White"),
                            selected = if (colourTab) 0 else 1,
                            onSelect = { colourTab = it == 0 },
                        )
                    }
                    if (hueCap != null && (colourTab || tempCap == null)) {
                        val hue = (device.capability("light_hue")?.numberValue ?: 0.0).toFloat()
                        val sat = (device.capability("light_saturation")?.numberValue ?: 1.0).toFloat()
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            ColorWheel(hue, sat, Modifier.size(300.dp)) { h, s, final ->
                                actions.set(device.id, hueCap.id, JsonPrimitive(h.toDouble()))
                                satCap?.let { actions.set(device.id, it.id, JsonPrimitive(s.toDouble())) }
                                if (final && modeCap != null && modeCap.stringValue != "color") actions.set(device.id, modeCap.id, JsonPrimitive("color"))
                                if (final) turnOn()
                            }
                        }
                        Swatches(
                            colors = QuickColours,
                            selected = null,
                            onPick = { i ->
                                val hsv = FloatArray(3)
                                android.graphics.Color.colorToHSV(QuickColours[i].toArgbInt(), hsv)
                                actions.set(device.id, hueCap.id, JsonPrimitive((hsv[0] / 360f).toDouble()))
                                satCap?.let { actions.set(device.id, it.id, JsonPrimitive(hsv[1].toDouble())) }
                                modeCap?.let { actions.set(device.id, it.id, JsonPrimitive("color")) }
                                turnOn()
                            },
                        )
                    } else if (tempCap != null) {
                        val temperature = fraction(tempCap) ?: 0.5f
                        Text("White", style = MaterialTheme.typography.titleMedium, color = g.fg)
                        TemperatureSlider(temperature) { t ->
                            actions.set(device.id, tempCap.id, JsonPrimitive(valueAt(tempCap, t)))
                            modeCap?.takeIf { it.stringValue != "temperature" }?.let { actions.set(device.id, it.id, JsonPrimitive("temperature")) }
                            turnOn()
                        }
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("Cool", style = MaterialTheme.typography.bodyMedium, color = g.muted)
                            Text("Warm", style = MaterialTheme.typography.bodyMedium, color = g.muted)
                        }
                        Swatches(
                            colors = WhitePresets.map { whiteAt(it) },
                            selected = WhitePresets.indexOfFirst { kotlin.math.abs(it - temperature) < 0.04f }.takeIf { it >= 0 },
                            onPick = { i ->
                                val t = WhitePresets[i]
                                actions.set(device.id, tempCap.id, JsonPrimitive(valueAt(tempCap, t)))
                                modeCap?.let { actions.set(device.id, it.id, JsonPrimitive("temperature")) }
                                turnOn()
                            },
                        )
                    }
                }
            }
        }
    }
}

private val QuickColours = listOf(
    Color(0xFFFF3B30), Color(0xFFFF9500), Color(0xFFFFCC00), Color(0xFF34C759),
    Color(0xFF30D5C8), Color(0xFF0A84FF), Color(0xFF5E5CE6), Color(0xFFBF5AF2), Color(0xFFFF2D92),
)

private val WhitePresets = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

private fun Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255).roundToInt(), (red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt(),
)

/** Tall iOS-style brightness control: drag up and down, tap to switch on or off. */
@Composable
private fun BrightnessSlider(level: Float, color: Color, onLevel: ((Float) -> Unit)?, onTap: (() -> Unit)?) {
    val g = GlassTheme.colors
    var drag by remember { mutableStateOf<Float?>(null) }
    val current by rememberUpdatedState(level)
    val send by rememberUpdatedState(onLevel)
    val throttle = remember { Throttle() }
    val shown = drag ?: level
    val shape = RoundedCornerShape(36.dp)
    Box(
        Modifier
            .width(140.dp)
            .height(360.dp)
            .glass(shape)
            .pointerInput(onTap) { detectTapGestures { onTap?.invoke() } }
            .then(
                if (onLevel == null) Modifier else Modifier.pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { drag = current },
                        onDragEnd = { drag?.let { send?.invoke(it) }; drag = null },
                        onDragCancel = { drag = null },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            val next = ((drag ?: current) - dy / size.height).coerceIn(0f, 1f)
                            drag = next
                            if (throttle.ready()) send?.invoke(next)
                        },
                    )
                },
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(shown.coerceIn(0f, 1f))
                .background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.75f)))),
        )
        Column(
            Modifier.fillMaxSize().padding(vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (shown <= 0f) "Off" else percent(shown),
                style = MaterialTheme.typography.headlineMedium,
                color = if (shown > 0.86f) inkOn(color) else g.fg,
            )
            Spacer(Modifier.weight(1f))
            SymIcon(Sym.brightness, filled = true, size = 30.dp, tint = if (shown > 0.12f) inkOn(color) else g.muted)
        }
    }
}

/** Hue around the circle, saturation from the centre out. Calls [onPick] with 0–1 values. */
@Composable
private fun ColorWheel(hue: Float, saturation: Float, modifier: Modifier, onPick: (Float, Float, Boolean) -> Unit) {
    var drag by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val pick by rememberUpdatedState(onPick)
    val throttle = remember { Throttle() }
    val (h, s) = drag ?: (hue to saturation)
    val hues = remember { (0..12).map { Color.hsv((it * 30f) % 360f, 1f, 1f) } }
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val radiusPx = with(density) { maxWidth.toPx() } / 2f
        fun at(offset: Offset): Pair<Float, Float> {
            val dx = offset.x - radiusPx
            val dy = offset.y - radiusPx
            val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat().let { if (it < 0) it + 360f else it }
            return angle / 360f to (hypot(dx, dy) / radiusPx).coerceIn(0f, 1f)
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { o -> at(o).let { (a, b) -> pick(a, b, true) } }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { o -> drag = at(o) },
                        onDragEnd = { drag?.let { (a, b) -> pick(a, b, true) }; drag = null },
                        onDragCancel = { drag = null },
                        onDrag = { change, _ ->
                            change.consume()
                            val next = at(change.position)
                            drag = next
                            if (throttle.ready()) pick(next.first, next.second, false)
                        },
                    )
                },
        ) {
            drawCircle(Brush.sweepGradient(hues, center))
            drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, size.minDimension / 2f))
        }
        val angle = Math.toRadians((h * 360f).toDouble())
        val thumb = with(density) { 40.dp.toPx() }
        val x = radiusPx + cos(angle).toFloat() * s * radiusPx - thumb / 2
        val y = radiusPx + sin(angle).toFloat() * s * radiusPx - thumb / 2
        Box(
            Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(40.dp)
                .background(Color.hsv((h * 360f).coerceIn(0f, 359.9f), s, 1f), CircleShape)
                .border(4.dp, Color.White, CircleShape),
        )
    }
}

/** Cool-to-warm white bar. */
@Composable
private fun TemperatureSlider(value: Float, onValue: (Float) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val current by rememberUpdatedState(value)
    val send by rememberUpdatedState(onValue)
    val throttle = remember { Throttle() }
    val shown = drag ?: value
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(PillShape)
            .background(Brush.horizontalGradient(listOf(CoolWhite, Color(0xFFFFF4E0), WarmWhite)))
            .pointerInput(Unit) { detectTapGestures { o -> send((o.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let(send); drag = null },
                    onDragCancel = { drag = null },
                    onDrag = { change, _ ->
                        change.consume()
                        val next = (change.position.x / size.width).coerceIn(0f, 1f)
                        drag = next
                        if (throttle.ready()) send(next)
                    },
                )
            },
    ) {
        val travel = maxWidth - 56.dp
        Box(
            Modifier
                .padding(start = travel * shown + 4.dp)
                .align(Alignment.CenterStart)
                .size(48.dp)
                .background(whiteAt(shown), CircleShape)
                .border(4.dp, Color.White, CircleShape),
        )
    }
}

@Composable
private fun Swatches(colors: List<Color>, selected: Int?, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEachIndexed { i, c ->
            Box(
                Modifier
                    .size(40.dp)
                    .pressable({ onPick(i) })
                    .background(c, CircleShape)
                    .then(if (i == selected) Modifier.border(3.dp, GlassTheme.colors.fg, CircleShape) else Modifier.border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)),
            )
        }
    }
}

/** iOS segmented control. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val g = GlassTheme.colors
    Row(modifier.glass(PillShape).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (on) g.wallBase else g.fg,
                modifier = Modifier
                    .pressable({ onSelect(i) })
                    .then(if (on) Modifier.background(g.fg, PillShape) else Modifier)
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }
    }
}
