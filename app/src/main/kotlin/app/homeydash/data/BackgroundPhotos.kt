package app.homeydash.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Photos chosen as room backgrounds. They are softened once when imported and
 * saved small, so the tablet only ever draws a ready-made bitmap behind the glass.
 */
class BackgroundPhotos(private val context: Context) {
    private val dir get() = File(context.filesDir, "backgrounds").apply { mkdirs() }
    private val cache = LruCache<String, Bitmap>(4)

    /** Copies, shrinks and blurs the picked photo; returns a version to put in the spec. */
    suspend fun import(key: String, uri: Uri): Long = withContext(Dispatchers.IO) {
        val source = decode(uri, maxSide = 1600)
        val small = scaleToWidth(source, 720)
        val soft = Blur.box(small, radius = 6, passes = 3)
        val file = File(dir, fileName(key))
        file.outputStream().use { soft.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        cache.evictAll()
        System.currentTimeMillis()
    }

    suspend fun load(key: String, version: String): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "$key@$version"
        cache.get(cacheKey) ?: runCatching { BitmapFactory.decodeFile(File(dir, fileName(key)).path) }.getOrNull()
            ?.also { cache.put(cacheKey, it) }
    }

    fun delete(key: String) {
        File(dir, fileName(key)).delete()
        cache.evictAll()
    }

    private fun fileName(key: String) = key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jpg"

    private fun decode(uri: Uri, maxSide: Int): Bitmap =
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val side = max(info.size.width, info.size.height)
                if (side > maxSide) {
                    val scale = maxSide.toFloat() / side
                    decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
                ?: error("Couldn't read the photo")
        }

    private fun scaleToWidth(bitmap: Bitmap, width: Int): Bitmap {
        if (bitmap.width <= width) return bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val height = (bitmap.height * width.toFloat() / bitmap.width).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}

/** A small, fast box blur for album art and photos; done once, never per frame. */
object Blur {
    fun box(input: Bitmap, radius: Int, passes: Int = 3): Bitmap {
        val bitmap = if (input.config == Bitmap.Config.ARGB_8888 && input.isMutable) input else input.copy(Bitmap.Config.ARGB_8888, true)
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val buffer = IntArray(w * h)
        repeat(passes) {
            blurPass(pixels, buffer, w, h, radius)
            blurPass(buffer, pixels, h, w, radius)
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    /** Blurs each row of [src] and writes it transposed into [dst], so two calls blur both ways. */
    private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            var a = 0; var red = 0; var g = 0; var b = 0
            val row = y * w
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                a += p ushr 24; red += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            for (x in 0 until w) {
                dst[x * h + y] = ((a / div) shl 24) or ((red / div) shl 16) or ((g / div) shl 8) or (b / div)
                val out = src[row + (x - r).coerceIn(0, w - 1)]
                val inn = src[row + (x + r + 1).coerceIn(0, w - 1)]
                a += (inn ushr 24) - (out ushr 24)
                red += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                b += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }
}
