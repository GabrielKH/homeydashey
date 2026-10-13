package app.homeydash.ui.assistant

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import app.homeydash.agent.FaceStyle
import app.homeydash.assistant.AssistantState
import app.homeydash.ui.theme.GlassColors
import app.homeydash.ui.theme.GlassTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** The voice right now: overall loudness and its low, middle and high parts, all 0..1. */
class VoiceLevels(val level: Float, val low: Float, val mid: Float, val high: Float)

/**
 * The assistant's face: drawn in the theme's accent and wallpaper colours, moving with
 * the voice ([levels]) while it speaks, the microphone while it listens, and slowly
 * while it thinks. [demo] makes up a voice, for the preview in Settings.
 */
@Composable
fun VoiceFace(
    style: FaceStyle,
    state: AssistantState,
    levels: () -> VoiceLevels,
    modifier: Modifier = Modifier,
    classicRed: Boolean = false,
    demo: Boolean = false,
) {
    val g = GlassTheme.colors
    val time by produceState(0f) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { value = (it - start) / 1e9f }
    }
    // Eased, so the face glides between the 60-a-second measurements.
    val smooth = remember { FloatArray(4) }
    Canvas(modifier) {
        val t = time
        val v = if (demo) demoVoice(t, state) else levels()
        val target = floatArrayOf(v.level, v.low, v.mid, v.high)
        for (i in 0..3) smooth[i] += (target[i] - smooth[i]) * 0.35f
        val lv = smooth[0]
        val bands = floatArrayOf(smooth[1], smooth[2], smooth[3])
        when (style) {
            FaceStyle.Orb -> orb(g, t, lv, bands, state)
            FaceStyle.Ribbon -> ribbon(g, t, bands)
            FaceStyle.Pills -> pills(g, t, bands, state)
            FaceStyle.Scanner -> scanner(g, t, lv, bands, state, classicRed)
            FaceStyle.Lens -> lens(g, t, lv, state)
            FaceStyle.Rings -> rings(g, t, lv, bands, state)
        }
    }
}

/** A made-up voice for previews: syllables about 4.5 a second, in words with short gaps. */
private fun demoVoice(t: Float, state: AssistantState): VoiceLevels {
    if (state == AssistantState.Thinking) return VoiceLevels(0.12f, 0.12f, 0.12f, 0.12f)
    if (state == AssistantState.Listening) {
        val l = 0.18f + 0.32f * max(0f, sin(t * 2.3f) * sin(t * 5.1f + 1f))
        return VoiceLevels(l, l, l * 0.8f, l * 0.6f)
    }
    val word = (t / 0.36f).toInt()
    val inWord = (t % 0.36f) / 0.36f
    if (word % 9 == 8) return VoiceLevels(0f, 0f, 0f, 0f)
    val syl = max(0f, sin(inWord * PI.toFloat() * (1 + word % 3)))
    val r = ((sin(word * 12.9898f) * 43758.547f) % 1f).let { abs(it) }
    val gap = if (inWord > 0.82f) 0.15f else 1f
    val l = min(1f, (0.35f + 0.65f * syl) * gap * (0.6f + 0.4f * r))
    return VoiceLevels(l, l * (0.7f + 0.3f * sin(t * 7)), l * (0.8f + 0.2f * sin(t * 11 + 1)), l * (0.5f + 0.5f * r))
}

private fun DrawScope.orb(g: GlassColors, t: Float, lv: Float, bands: FloatArray, state: AssistantState) {
    val c = center
    val r = min(size.width, size.height) * (0.36f + lv * 0.08f)
    val ball = Path().apply { addOval(Rect(c, r)) }
    clipPath(ball) {
        drawRect(if (g.isDark) Color.White.copy(alpha = 0.04f) else Color.White.copy(alpha = 0.35f))
        val speed = if (state == AssistantState.Thinking) 1.6f else 0.8f
        listOf(Triple(g.on, 0f, 0), Triple(g.onDeep, 2.1f, 1), Triple(g.wallA, 4.2f, 0), Triple(g.wallC, 1f, 2)).forEachIndexed { i, (col, phase, b) ->
            val ang = t * speed * (if (i % 2 == 1) -1 else 1) + phase
            val rr = r * (0.35f + 0.25f * bands[b])
            val p = Offset(c.x + cos(ang) * rr, c.y + sin(ang * 1.3f) * rr)
            val radius = r * (0.75f + 0.35f * lv)
            drawCircle(
                Brush.radialGradient(listOf(col.copy(alpha = if (g.isDark) 0.85f else 0.75f), col.copy(alpha = 0f)), center = p, radius = radius),
                radius = radius,
                center = p,
                blendMode = if (g.isDark) BlendMode.Screen else BlendMode.SrcOver,
            )
        }
    }
    drawCircle(if (g.isDark) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.95f), r, c, style = Stroke(1.5f))
    val h = Offset(c.x - r * 0.35f, c.y - r * 0.45f)
    clipPath(ball) {
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.5f), Color.Transparent), center = h, radius = r * 0.6f), r * 0.6f, h)
    }
}

private fun DrawScope.ribbon(g: GlassColors, t: Float, bands: FloatArray) {
    val w = size.width
    val mid = size.height / 2
    listOf(g.on, g.onDeep, g.wallA).forEachIndexed { i, col ->
        val amp = size.height * 0.42f * (0.08f + bands[i] * 0.92f)
        val k = 2.2f + i * 0.7f
        val ph = t * (2.5f + i) + i * 1.7f
        val path = Path()
        var x = 0f
        path.moveTo(0f, mid)
        while (x <= w) {
            val u = x / w * 2 - 1
            val env = (1 - u * u).pow(2.2f)
            path.lineTo(x, mid + sin(u * k * PI.toFloat() + ph) * amp * env)
            x += 3f
        }
        x = w
        while (x >= 0f) {
            val u = x / w * 2 - 1
            val env = (1 - u * u).pow(2.2f)
            path.lineTo(x, mid - sin(u * k * PI.toFloat() + ph) * amp * env * 0.35f)
            x -= 3f
        }
        path.close()
        drawPath(
            path,
            Brush.horizontalGradient(listOf(col.copy(alpha = 0f), col.copy(alpha = if (g.isDark) 0.6f else 0.55f), col.copy(alpha = 0f))),
            blendMode = if (g.isDark) BlendMode.Screen else BlendMode.SrcOver,
        )
    }
}

private fun DrawScope.pills(g: GlassColors, t: Float, bands: FloatArray, state: AssistantState) {
    val n = 5
    val pw = min(size.width * 0.06f, size.height * 0.16f)
    val gap = pw * 0.7f
    val x0 = (size.width - (n * pw + (n - 1) * gap)) / 2
    val shape = floatArrayOf(0.55f, 0.85f, 1f, 0.85f, 0.55f)
    val band = intArrayOf(2, 1, 0, 1, 2)
    for (i in 0 until n) {
        val wobble = if (state == AssistantState.Thinking) 0.5f + 0.5f * sin(t * 4 - i * 0.9f) else 1f
        val h = max(pw, size.height * 0.8f * shape[i] * max(0.12f, bands[band[i]]) * wobble)
        val top = (size.height - h) / 2
        drawRoundRect(
            Brush.verticalGradient(listOf(g.on, g.onDeep), startY = top, endY = top + h),
            topLeft = Offset(x0 + i * (pw + gap), top),
            size = Size(pw, h),
            cornerRadius = CornerRadius(pw / 2),
        )
    }
}

private fun DrawScope.scanner(g: GlassColors, t: Float, lv: Float, bands: FloatArray, state: AssistantState, classicRed: Boolean) {
    val top = if (classicRed) Color(0xFFFF3B2F) else g.on
    val deep = if (classicRed) Color(0xFFB50F0A) else g.onDeep
    val off = if (g.isDark) Color.White.copy(alpha = 0.06f) else Color(0xFF14161C).copy(alpha = 0.07f)
    if (state != AssistantState.Speaking) {
        // KITT's front scanner: one light running side to side with a fading tail.
        val n = 16
        val sw = size.width * 0.78f / n
        val x0 = size.width * 0.11f
        val y = size.height * 0.44f
        val sh = size.height * 0.12f
        val speed = if (state == AssistantState.Thinking) 2.2f else 1.1f
        val pos = (sin(t * speed * PI.toFloat()) * 0.5f + 0.5f) * (n - 1)
        for (i in 0 until n) {
            val a = max(0f, 1 - abs(i - pos) / 3.2f)
            val tl = Offset(x0 + i * sw + 2, y)
            drawRoundRect(off, tl, Size(sw - 4, sh), CornerRadius(3f))
            if (a > 0f) {
                drawRoundRect(top.copy(alpha = 0.25f * a), tl - Offset(3f, 3f), Size(sw + 2, sh + 6), CornerRadius(6f))
                drawRoundRect(top.copy(alpha = a), tl, Size(sw - 4, sh), CornerRadius(3f))
            }
        }
        return
    }
    // The voice box: three columns of segments that light up from the middle.
    val segs = 13
    val colW = size.width * 0.075f
    val gapX = colW * 0.9f
    val segH = size.height * 0.84f / segs
    val xs = floatArrayOf(size.width / 2 - colW * 1.5f - gapX, size.width / 2 - colW / 2, size.width / 2 + colW / 2 + gapX)
    val levels = floatArrayOf(bands[0] * 0.8f, min(1f, lv * 1.15f), bands[2] * 0.8f)
    xs.forEachIndexed { ci, x ->
        val lit = (levels[ci] * (segs / 2f)).roundToInt()
        for (s in 0 until segs) {
            val fromMid = abs(s - (segs - 1) / 2f)
            val y = size.height * 0.08f + s * segH
            val on = fromMid <= lit - 0.5f || (ci == 1 && fromMid < 0.5f && lv > 0.05f)
            val tl = Offset(x, y + 1.5f)
            if (on) drawRoundRect(top.copy(alpha = 0.22f), tl - Offset(3f, 2f), Size(colW + 6, segH + 1), CornerRadius(4f))
            drawRoundRect(if (!on) off else if (fromMid < 2) top else deep, tl, Size(colW, segH - 3), CornerRadius(2f))
        }
    }
}

private fun DrawScope.lens(g: GlassColors, t: Float, lv: Float, state: AssistantState) {
    val c = center
    val r = min(size.width, size.height) * 0.42f
    drawCircle(
        Brush.radialGradient(
            0.7f to (if (g.isDark) Color(0xFF1A1C26) else Color(0xFF2A2D38)),
            1f to (if (g.isDark) Color(0xFF3A3E4D) else Color(0xFF5A5F70)),
            center = c,
            radius = r,
        ),
        r,
        c,
    )
    drawCircle(Color(0xFF05060A), r * 0.82f, c)
    val breathe = if (state == AssistantState.Thinking) 0.2f * (0.5f + 0.5f * sin(t * 3)) else 0f
    val k = 0.18f + lv * 0.5f + breathe
    drawCircle(
        Brush.radialGradient(
            0f to Color.White,
            (0.08f + k * 0.1f) to g.on,
            (0.25f + k * 0.4f).coerceAtMost(0.98f) to g.onDeep.copy(alpha = 0.85f),
            1f to g.onDeep.copy(alpha = 0f),
            center = c,
            radius = r * 0.82f,
        ),
        r * 0.82f,
        c,
    )
    rotate(-28f, Offset(c.x - r * 0.28f, c.y - r * 0.38f)) {
        drawOval(Color.White.copy(alpha = 0.16f), Offset(c.x - r * 0.6f, c.y - r * 0.52f), Size(r * 0.64f, r * 0.28f))
    }
}

private fun DrawScope.rings(g: GlassColors, t: Float, lv: Float, bands: FloatArray, state: AssistantState) {
    val c = center
    val r = min(size.width, size.height) * 0.4f
    data class Ring(val f: Float, val speed: Float, val color: Color, val parts: Int, val len: Float)
    listOf(
        Ring(1f, 0.6f, g.on, 3, 0.9f),
        Ring(0.8f, -1.1f, g.onDeep, 4, 0.6f),
        Ring(0.62f, 1.7f, g.on, 2, 0.75f),
        Ring(0.45f, -2.4f, g.wallA, 3, 0.5f),
    ).forEachIndexed { i, ring ->
        val speed = if (state == AssistantState.Thinking) ring.speed * 2 else ring.speed
        val radius = r * ring.f
        val width = 1.5f + bands[i % 3] * 7f
        for (p in 0 until ring.parts) {
            val start = Math.toDegrees((t * speed).toDouble()).toFloat() + p * 360f / ring.parts
            val sweep = 360f / ring.parts * ring.len
            val tl = Offset(c.x - radius, c.y - radius)
            drawArc(ring.color.copy(alpha = 0.25f), start, sweep, false, tl, Size(radius * 2, radius * 2), style = Stroke(width + 6f + lv * 6f, cap = StrokeCap.Round))
            drawArc(ring.color.copy(alpha = 0.9f), start, sweep, false, tl, Size(radius * 2, radius * 2), style = Stroke(width, cap = StrokeCap.Round))
        }
    }
    for (i in 0 until 60) {
        val a = i / 60f * 2 * PI.toFloat() - t * 0.2f
        val r1 = r * 1.06f
        val r2 = r * if (i % 5 == 0) 1.15f else 1.1f
        drawLine(g.on.copy(alpha = 0.35f), Offset(c.x + cos(a) * r1, c.y + sin(a) * r1), Offset(c.x + cos(a) * r2, c.y + sin(a) * r2), 1f)
    }
    val core = r * (0.25f + lv * 0.15f)
    drawCircle(Brush.radialGradient(0f to Color.White, 0.4f to g.on, 1f to g.onDeep.copy(alpha = 0f), center = c, radius = core), core, c)
}
