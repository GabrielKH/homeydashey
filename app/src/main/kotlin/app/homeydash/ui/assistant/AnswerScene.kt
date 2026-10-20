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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import app.homeydash.assistant.SceneKind
import app.homeydash.ui.theme.GlassTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A realistic weather picture behind an answer, like a glossy weather icon set: a glowing sun,
 * soft shaded clouds, blue raindrops falling from them, snowflakes, a lightning bolt, a moon.
 * One canvas drawn from the time alone, so it costs a few gradients per frame and nothing at
 * all once it settles or the answer is gone.
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
    val dark = g.isDark
    Canvas(modifier) {
        val t = seconds
        // Up and to the right, out of the way of the answer's words.
        val s = size.minDimension * 0.3f
        val c = Offset(size.width * 0.78f, size.height * 0.24f)
        val bob = sin(t * 0.9f) * s * 0.025f
        val a = intensity.coerceIn(0f, 1f)
        when (kind) {
            SceneKind.Sun -> sun(c, s * 0.42f, t, a)
            SceneKind.Night -> {
                stars(t, a, dark)
                moon(c, s * 0.38f, a)
            }
            SceneKind.Clouds -> {
                sun(Offset(c.x + s * 0.3f, c.y - s * 0.22f), s * 0.28f, t, a)
                cloud(Offset(c.x - s * 0.05f, c.y + s * 0.08f + bob), s, a, storm = false, dark = dark)
            }
            SceneKind.Rain -> {
                drops(Offset(c.x, c.y + bob), s, t, a, count = 9)
                cloud(Offset(c.x, c.y + bob), s, a, storm = false, dark = dark)
            }
            SceneKind.Storm -> {
                drops(Offset(c.x, c.y + bob), s, t, a, count = 7)
                bolt(Offset(c.x + s * 0.05f, c.y + s * 0.28f + bob), s, t, a)
                cloud(Offset(c.x, c.y + bob), s, a, storm = true, dark = dark)
                // A short flash every few seconds.
                if (t % 4.3f < 0.12f) drawRect(Color.White.copy(alpha = 0.25f * a))
            }
            SceneKind.Snow -> {
                flakes(Offset(c.x, c.y + bob), s, t, a)
                cloud(Offset(c.x, c.y + bob), s, a, storm = false, dark = dark)
            }
            SceneKind.Fog -> {
                cloud(Offset(c.x, c.y - s * 0.08f + bob), s * 0.9f, a, storm = false, dark = dark)
                fog(Offset(c.x, c.y + s * 0.3f), s, t, a, dark)
            }
        }
    }
}

private const val MAX_SECONDS = 30f

/** A steady pseudo-random number in 0..1 for [i], the same every frame. */
private fun noise(i: Int, salt: Int = 0): Float {
    val x = sin((i * 12.9898f + salt * 78.233f).toDouble()) * 43758.5453
    return (x - kotlin.math.floor(x)).toFloat()
}

/** A glowing sun: a wide warm halo, soft turning rays and a disc lit from the upper left. */
private fun DrawScope.sun(c: Offset, r: Float, t: Float, a: Float) {
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFFFD54F).copy(alpha = 0.55f * a), Color(0x00FFB300)), c, r * 2.4f),
        r * 2.4f, c,
    )
    rotate(t * 8f, c) {
        for (i in 0 until 12) {
            val ang = i * PI.toFloat() / 6f
            val pulse = 1f + 0.06f * sin(t * 1.6f + i)
            val ray = Path().apply {
                val tip = r * 1.75f * pulse
                val base = r * 1.08f
                val half = 0.13f
                moveTo(c.x + cos(ang) * tip, c.y + sin(ang) * tip)
                lineTo(c.x + cos(ang - half) * base, c.y + sin(ang - half) * base)
                lineTo(c.x + cos(ang + half) * base, c.y + sin(ang + half) * base)
                close()
            }
            drawPath(ray, Brush.radialGradient(listOf(Color(0xFFFFE082), Color(0xFFFFA000).copy(alpha = 0.2f)), c, r * 1.8f), alpha = 0.9f * a)
        }
    }
    drawCircle(
        Brush.radialGradient(
            listOf(Color(0xFFFFFDE7), Color(0xFFFFE066), Color(0xFFFFB300), Color(0xFFFB8C00)),
            Offset(c.x - r * 0.35f, c.y - r * 0.35f), r * 1.5f,
        ),
        r, c, alpha = a,
    )
}

/** A crescent moon with a soft glow, lit pale gold. */
private fun DrawScope.moon(c: Offset, r: Float, a: Float) {
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF3C4).copy(alpha = 0.35f * a), Color(0x00FFF3C4)), c, r * 2.2f), r * 2.2f, c)
    val disc = Path().apply { addOval(Rect(c, r)) }
    val bite = Path().apply { addOval(Rect(Offset(c.x + r * 0.55f, c.y - r * 0.3f), r * 0.9f)) }
    val crescent = Path().apply { op(disc, bite, PathOperation.Difference) }
    drawPath(
        crescent,
        Brush.radialGradient(listOf(Color(0xFFFFFBE6), Color(0xFFFFE29A), Color(0xFFF2B950)), Offset(c.x - r * 0.4f, c.y - r * 0.3f), r * 1.6f),
        alpha = a,
    )
}

private fun DrawScope.stars(t: Float, a: Float, dark: Boolean) {
    val ink = if (dark) Color.White else Color(0xFF3A4A66)
    for (i in 0 until 36) {
        val twinkle = 0.5f + 0.5f * sin(t * (1f + noise(i, 1) * 2f) + i)
        drawCircle(
            ink.copy(alpha = (0.12f + 0.4f * twinkle) * a),
            radius = 1.5f + noise(i, 2) * 2f,
            center = Offset(noise(i, 3) * size.width, noise(i, 4) * size.height * 0.6f),
        )
    }
}

/**
 * A soft, rounded cloud of overlapping puffs: shaded from a bright top to a cooler underside,
 * with a faint shadow below and a highlight on the top puff. A storm cloud is darker.
 */
private fun DrawScope.cloud(c: Offset, s: Float, a: Float, storm: Boolean, dark: Boolean) {
    val w = s * 1.25f
    val shape = Path().apply {
        addOval(Rect(Offset(c.x - w * 0.18f, c.y - s * 0.12f), s * 0.3f))
        addOval(Rect(Offset(c.x + w * 0.16f, c.y - s * 0.02f), s * 0.24f))
        addOval(Rect(Offset(c.x - w * 0.36f, c.y + s * 0.06f), s * 0.19f))
        addRoundRect(RoundRect(Rect(c.x - w * 0.5f, c.y + s * 0.02f, c.x + w * 0.45f, c.y + s * 0.25f), CornerRadius(s * 0.12f)))
    }
    val top = if (storm) Color(0xFFA9B3C2) else if (dark) Color(0xFFF4F7FB) else Color.White
    val bottom = if (storm) Color(0xFF55606F) else if (dark) Color(0xFFAEB9C8) else Color(0xFFC9D3E0)
    translate(s * 0.03f, s * 0.07f) { drawPath(shape, Color.Black.copy(alpha = 0.16f * a)) }
    drawPath(shape, Brush.verticalGradient(listOf(top, bottom), c.y - s * 0.42f, c.y + s * 0.25f), alpha = a)
    drawCircle(
        Brush.radialGradient(listOf(Color.White.copy(alpha = if (storm) 0.25f else 0.7f), Color.Transparent), Offset(c.x - w * 0.24f, c.y - s * 0.26f), s * 0.2f),
        s * 0.2f, Offset(c.x - w * 0.24f, c.y - s * 0.26f), alpha = a,
    )
}

/** Glossy blue drops falling from under the cloud, each starting again at the cloud. */
private fun DrawScope.drops(c: Offset, s: Float, t: Float, a: Float, count: Int) {
    val fall = s * 0.9f
    for (i in 0 until count) {
        val x = c.x - s * 0.5f + s * (i + 0.5f) / count + (noise(i, 1) - 0.5f) * s * 0.08f
        val p = (t * (0.9f + noise(i, 2) * 0.5f) + noise(i, 3)) % 1f
        val y = c.y + s * 0.2f + p * fall
        val fade = (1f - p) * a
        val d = s * 0.055f
        val drop = Path().apply {
            moveTo(x, y - d * 1.6f)
            cubicTo(x + d * 0.2f, y - d * 0.6f, x + d, y, x + d, y + d * 0.35f)
            cubicTo(x + d, y + d * 1.1f, x - d, y + d * 1.1f, x - d, y + d * 0.35f)
            cubicTo(x - d, y, x - d * 0.2f, y - d * 0.6f, x, y - d * 1.6f)
            close()
        }
        drawPath(drop, Brush.verticalGradient(listOf(Color(0xFFB3E5FC), Color(0xFF29B6F6), Color(0xFF1565C0)), y - d * 1.6f, y + d * 1.2f), alpha = fade)
        drawCircle(Color.White.copy(alpha = 0.7f * fade), d * 0.25f, Offset(x - d * 0.35f, y + d * 0.1f))
    }
}

/** Six-armed flakes drifting down from the cloud. */
private fun DrawScope.flakes(c: Offset, s: Float, t: Float, a: Float) {
    for (i in 0 until 8) {
        val p = (t * (0.18f + noise(i, 2) * 0.12f) + noise(i, 3)) % 1f
        val x = c.x - s * 0.5f + s * (i + 0.5f) / 8f + sin(t * 1.2f + i) * s * 0.04f
        val y = c.y + s * 0.22f + p * s * 0.85f
        val r = s * (0.035f + noise(i, 4) * 0.025f)
        val fade = (1f - p * 0.8f) * a
        rotate(t * 30f + i * 20f, Offset(x, y)) {
            for (k in 0 until 3) {
                val ang = k * PI.toFloat() / 3f
                drawLine(
                    Color(0xFFE3F2FD).copy(alpha = fade),
                    Offset(x - cos(ang) * r, y - sin(ang) * r), Offset(x + cos(ang) * r, y + sin(ang) * r),
                    strokeWidth = r * 0.28f, cap = StrokeCap.Round,
                )
            }
        }
        drawCircle(Color(0xFF90CAF9).copy(alpha = 0.35f * fade), r * 0.35f, Offset(x, y))
    }
}

/** A golden lightning bolt under the storm cloud, brightest as it flashes. */
private fun DrawScope.bolt(at: Offset, s: Float, t: Float, a: Float) {
    val flash = if (t % 4.3f < 0.5f) 1f else 0.75f
    val u = s * 0.1f
    val bolt = Path().apply {
        moveTo(at.x + u * 0.6f, at.y - u * 0.6f)
        lineTo(at.x - u * 1.1f, at.y + u * 1.9f)
        lineTo(at.x + u * 0.05f, at.y + u * 1.9f)
        lineTo(at.x - u * 0.7f, at.y + u * 4.2f)
        lineTo(at.x + u * 1.3f, at.y + u * 1.2f)
        lineTo(at.x + u * 0.1f, at.y + u * 1.2f)
        lineTo(at.x + u * 1.0f, at.y - u * 0.6f)
        close()
    }
    drawPath(bolt, Brush.verticalGradient(listOf(Color(0xFFFFF59D), Color(0xFFFFCA28), Color(0xFFFF8F00)), at.y - u, at.y + u * 4.2f), alpha = flash * a)
}

/** Soft grey bands drifting slowly under the cloud. */
private fun DrawScope.fog(c: Offset, s: Float, t: Float, a: Float, dark: Boolean) {
    val tint = if (dark) Color(0xFFCFD8E3) else Color(0xFF9AA8BA)
    for (i in 0 until 3) {
        val y = c.y + i * s * 0.14f
        val drift = sin(t * 0.4f + i) * s * 0.06f
        val half = s * (0.55f - i * 0.08f)
        drawLine(tint.copy(alpha = (0.55f - i * 0.12f) * a), Offset(c.x - half + drift, y), Offset(c.x + half + drift, y), strokeWidth = s * 0.07f, cap = StrokeCap.Round)
    }
}
