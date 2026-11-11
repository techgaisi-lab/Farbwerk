package at.techgaisi.farbwerk.ui.layers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import at.techgaisi.farbwerk.model.ArtDocument
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * Cache verkleinerter Ebenen-Miniaturen. Die Bitmaps werden je Ebene einmal angelegt und bei jedem
 * [refresh] nur neu bemalt (keine Neuallokation). [version] ändert sich nach jedem Refresh, damit die
 * Miniaturen neu gezeichnet werden.
 */
class LayerThumbnailCache {
    private class Entry(val bitmap: Bitmap) { val image: ImageBitmap = bitmap.asImageBitmap() }

    private val thumbs = HashMap<String, Entry>()
    private val maskThumbs = HashMap<String, Entry>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = Color.WHITE }
    private val dst = Rect()

    /** Zeitpunkt des letzten Refresh (für die Drosselung). */
    var lastRefresh = 0L
        private set

    var version by mutableIntStateOf(0)
        private set

    fun image(layerId: String): ImageBitmap? = thumbs[layerId]?.image
    fun maskImage(layerId: String): ImageBitmap? = maskThumbs[layerId]?.image

    /** Zeichnet alle Miniaturen des Dokuments neu. */
    fun refresh(doc: ArtDocument) {
        val scale = THUMB_MAX.toFloat() / max(doc.width, doc.height)
        val tw = (doc.width * scale).roundToInt().coerceAtLeast(1)
        val th = (doc.height * scale).roundToInt().coerceAtLeast(1)
        dst.set(0, 0, tw, th)
        val alive = HashSet<String>()
        for (l in doc.layers) {
            alive += l.id
            val e = obtain(thumbs, l.id, tw, th)
            e.bitmap.eraseColor(Color.TRANSPARENT)
            Canvas(e.bitmap).drawBitmap(l.displayOverride ?: l.bitmap, null, dst, paint)
            val m = l.mask
            if (m != null) {
                val me = obtain(maskThumbs, l.id, tw, th)
                me.bitmap.eraseColor(Color.BLACK)
                Canvas(me.bitmap).drawBitmap(m, null, dst, maskPaint)
            } else maskThumbs.remove(l.id)
        }
        thumbs.keys.retainAll(alive)
        maskThumbs.keys.retainAll(alive)
        lastRefresh = SystemClock.uptimeMillis()
        version++
    }

    private fun obtain(map: HashMap<String, Entry>, id: String, w: Int, h: Int): Entry {
        val cur = map[id]
        if (cur != null && cur.bitmap.width == w && cur.bitmap.height == h) return cur
        return Entry(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)).also { map[id] = it }
    }

    companion object {
        /** Längere Kante der Miniaturen in Pixeln. */
        const val THUMB_MAX = 112
        /** Mindestabstand zwischen zwei Refreshes. */
        const val THROTTLE_MS = 220L
    }
}

/** Zeichnet eine Miniatur seitenverhältnistreu auf Schachbrett (Transparenz). */
@Composable
fun ThumbnailImage(
    image: ImageBitmap?, version: Int, docAspect: Float, modifier: Modifier, checker: Boolean = true,
) {
    Canvas(modifier) {
        if (version < 0) return@Canvas // Lesen → Neuzeichnen nach Refresh
        val boxAspect = size.width / size.height
        val w: Float
        val h: Float
        if (docAspect > boxAspect) { w = size.width; h = size.width / docAspect } else { h = size.height; w = size.height * docAspect }
        val left = (size.width - w) / 2f
        val top = (size.height - h) / 2f
        if (checker) {
            drawRect(ComposeColor.White, Offset(left, top), Size(w, h))
            val cell = 5f * density
            var y = 0
            while (y * cell < h) {
                var x = 0
                while (x * cell < w) {
                    if ((x + y) % 2 == 1) {
                        val cw = minOf(cell, w - x * cell)
                        val ch = minOf(cell, h - y * cell)
                        drawRect(ComposeColor(0xFFD6D6D6), Offset(left + x * cell, top + y * cell), Size(cw, ch))
                    }
                    x++
                }
                y++
            }
        }
        if (image != null) {
            drawImage(
                image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
            )
        }
    }
}
