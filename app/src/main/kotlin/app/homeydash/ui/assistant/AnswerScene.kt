package app.homeydash.ui.assistant

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import app.homeydash.assistant.SceneKind
import app.homeydash.ui.theme.GlassTheme
import kotlin.math.PI
import kotlin.math.sin

/**
 * A light moving picture behind an answer: rain falling, snow drifting, the sun turning.
 * One canvas and a fixed set of drops, positioned from the time alone, so it costs a few
 * lines of drawing per frame and nothing at all once the answer is gone.
 */
@Composable
fun AnswerScene(kind: SceneKind, modifier: Modifier = Modifier, intensity: Float = 1f) {
    val g = GlassTheme.colors
    var seconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(kind) {
        val start = withFrameMillis { it }
        // It settles after half a minute; a still picture costs nothing.
        while (seconds < MAX_SECONDS) withFrameMillis { seconds = (it - start) / 1000f }
    }
    val ink = if (g.isDark) Color.White else Color(0xFF1C2B3A)
    Canvas(modifier) {
        val t = seconds
        when (kind) {
            SceneKind.Rain -> rain(t, ink, intensity, drops = 70)
            SceneKind.Storm -> {
                rain(t, ink, intensity, drops = 110)
                // A short flash every few seconds.
                val phase = t % 4.3f
                if (phase < 0.12f) drawRect(Color.White.copy(alpha = 0.35f * intensity))
            }
            SceneKind.Snow -> snow(t, ink, intensity)
            SceneKind.Sun -> sun(t, intensity)
            SceneKind.Night -> night(t, ink, intensity)
            SceneKind.Clouds -> clouds(t, ink, intensity)
            SceneKind.Fog -> fog(t, ink, intensity)
        }
    }
}

private const val MAX_SECONDS = 30f

/** A steady pseudo-random number in 0..1 for drop [i], the same every frame. */
private fun noise(i: Int, salt: Int = 0): Float {
    val x = sin((i * 12.9898f + salt * 78.233f).toDouble()) * 43758.5453
    return (x - kotlin.math.floor(x)).toFloat()
}

private fun DrawScope.rain(t: Float, ink: Color, intensity: Float, drops: Int) {
    val w = size.width
    val h = size.height
    val length = h * 0.05f
    for (i in 0 until drops) {
        val speed = h * (0.9f + noise(i, 1) * 0.6f)
        val y = (noise(i, 2) * h + t * speed) % (h + length) - length
        val x = noise(i, 3) * w + y * 0.12f
        drawLine(
            ink.copy(alpha = (0.18f + noise(i, 4) * 0.22f) * intensity),
            Offset(x, y), Offset(x + length * 0.12f, y + length),
            strokeWidth = 2.2f, cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.snow(t: Float, ink: Color, intensity: Float) {
    val w = size.width
    val h = size.height
    for (i in 0 until 60) {
        val speed = h * (0.06f + noise(i, 1) * 0.08f)
        val y = (noise(i, 2) * h + t * speed) % h
        val x = noise(i, 3) * w + sin(t * 0.8f + i) * 18f
        drawCircle(ink.copy(alpha = (0.35f + noise(i, 4) * 0.35f) * intensity), radius = 2.5f + noise(i, 5) * 4f, center = Offset(x, y))
    }
}

private fun DrawScope.sun(t: Float, intensity: Float) {
    val c = Offset(size.width * 0.82f, size.height * 0.2f)
    val r = size.minDimension * 0.12f
    val warm = Color(0xFFFFC23D)
    drawCircle(warm.copy(alpha = 0.18f * intensity), radius = r * 2.6f, center = c)
    drawCircle(warm.copy(alpha = 0.85f * intensity), radius = r, center = c)
    rotate(t * 12f, c) {
        for (i in 0 until 12) {
            val a = i * PI.toFloat() / 6f
            val pulse = 1f + 0.08f * sin(t * 2f + i)
            val from = Offset(c.x + kotlin.math.cos(a) * r * 1.35f, c.y + sin(a) * r * 1.35f)
            val to = Offset(c.x + kotlin.math.cos(a) * r * 1.9f * pulse, c.y + sin(a) * r * 1.9f * pulse)
            drawLine(warm.copy(alpha = 0.7f * intensity), from, to, strokeWidth = r * 0.16f, cap = StrokeCap.Round)
        }
    }
}

private fun DrawScope.night(t: Float, ink: Color, intensity: Float) {
    for (i in 0 until 40) {
        val twinkle = 0.5f + 0.5f * sin(t * (1f + noise(i, 1) * 2f) + i)
        drawCircle(
            ink.copy(alpha = (0.15f + 0.45f * twinkle) * intensity),
            radius = 1.5f + noise(i, 2) * 2f,
            center = Offset(noise(i, 3) * size.width, noise(i, 4) * size.height * 0.7f),
        )
    }
    val c = Offset(size.width * 0.82f, size.height * 0.2f)
    val r = size.minDimension * 0.09f
    drawCircle(Color(0xFFF4F1DE).copy(alpha = 0.85f * intensity), radius = r, center = c)
}

private fun DrawScope.clouds(t: Float, ink: Color, intensity: Float) {
    val w = size.width
    for (i in 0 until 4) {
        val span = w * 1.4f
        val x = (noise(i, 1) * span + t * w * (0.015f + noise(i, 2) * 0.02f)) % span - w * 0.2f
        val y = size.height * (0.12f + noise(i, 3) * 0.35f)
        cloud(Offset(x, y), size.minDimension * (0.1f + noise(i, 4) * 0.08f), ink.copy(alpha = 0.12f * intensity))
    }
}

private fun DrawScope.cloud(at: Offset, r: Float, color: Color) {
    drawCircle(color, r, at)
    drawCircle(color, r * 0.75f, Offset(at.x - r * 0.9f, at.y + r * 0.25f))
    drawCircle(color, r * 0.85f, Offset(at.x + r * 0.95f, at.y + r * 0.2f))
    drawRect(color, Offset(at.x - r * 0.9f, at.y + r * 0.2f), Size(r * 1.9f, r * 0.8f))
}

private fun DrawScope.fog(t: Float, ink: Color, intensity: Float) {
    for (i in 0 until 6) {
        val y = size.height * (0.15f + i * 0.14f)
        val drift = sin(t * 0.25f + i) * size.width * 0.06f
        drawLine(
            ink.copy(alpha = 0.08f * intensity),
            Offset(-40f + drift, y), Offset(size.width + 40f + drift, y),
            strokeWidth = size.height * 0.06f, cap = StrokeCap.Round,
        )
    }
}
