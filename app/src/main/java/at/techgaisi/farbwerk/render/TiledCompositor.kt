package at.techgaisi.farbwerk.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Matrix
import at.techgaisi.farbwerk.model.ArtDocument
import at.techgaisi.farbwerk.model.Layer
import java.util.LinkedHashMap

/** Cache native layer stacks at document resolution; never allocate a document-sized GPU layer. */
internal object TiledCompositor {
    private data class Slot(val document: String, val stage: Int, val divisor: Int, val x: Int, val y: Int)
    private data class Entry(val signature: Long, val bitmap: Bitmap)
    private val entries = LinkedHashMap<Slot, Entry>(16, 0.75f, true)
    private var bytes = 0L
    var maxBytes = 32L * 1024 * 1024
    val residentBytes get() = bytes
    var rasterizedTiles = 0L
        private set
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private const val TILE = 256

    @Synchronized fun clear() { entries.clear(); bytes = 0; rasterizedTiles = 0 }

    @Synchronized fun drawLayerPixels(canvas: Canvas, doc: ArtDocument, layer: Layer) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { layer.drawPixels(canvas, Paint(Paint.FILTER_BITMAP_FLAG)); return }
        val index = doc.indexOf(layer)
        if (index < 0) { layer.drawPixels(canvas, paint); return }
        drawStack(canvas, doc, index, index + 1, false, 3, layer)
    }

    @Synchronized fun draw(canvas: Canvas, doc: ArtDocument): Boolean {
        val overrides = doc.layers.indices.filter { doc.layers[it].drawOverride != null }
        if (overrides.isEmpty()) {
            drawStack(canvas, doc, 0, doc.layers.size, doc.backgroundVisible, 0)
            return true
        }
        val active = doc.activeIndex
        if (overrides.size != 1 || overrides[0] != active || !Compositor.canSplit(doc, active)) return false
        drawStack(canvas, doc, 0, active, doc.backgroundVisible, 1)
        Compositor.drawLayers(canvas, doc, active, active + 1)
        drawStack(canvas, doc, active + 1, doc.layers.size, false, 2)
        return true
    }

    private fun drawStack(canvas: Canvas, doc: ArtDocument, from: Int, to: Int, background: Boolean, stage: Int, pixelsOnly: Layer? = null) {
        if (from == to && !background) return
        val clip = Rect()
        if (!canvas.getClipBounds(clip) || !clip.intersect(0, 0, doc.width, doc.height)) return
        val scale = if (canvas.isHardwareAccelerated) Compositor.displayScale else {
            val matrix = Matrix(); val values = FloatArray(9)
            @Suppress("DEPRECATION") canvas.getMatrix(matrix)
            matrix.getValues(values)
            kotlin.math.hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y])
        }
        var divisor = 1
        while (divisor < 64 && scale * divisor <= 0.5f) divisor *= 2
        for (y in clip.top / TILE * TILE until clip.bottom step TILE) {
            for (x in clip.left / TILE * TILE until clip.right step TILE) {
                val w = minOf(TILE, doc.width - x); val h = minOf(TILE, doc.height - y)
                val area = Rect(maxOf(0, x - 1), maxOf(0, y - 1), minOf(doc.width, x + w + 1), minOf(doc.height, y + h + 1))
                var signature = (from.toLong() * 31 + to) * 31 + divisor
                signature = signature * 31 + if (background) doc.backgroundColor.toLong() else 0x100000000L
                for (i in from until to) {
                    val l = doc.layers[i]
                    signature = signature * 31 + System.identityHashCode(l)
                    signature = signature * 31 + if (l.visible && LayerGroups.isVisible(doc, l.groupId)) 1 else 0
                    signature = signature * 31 + java.lang.Float.floatToIntBits(l.opacity * LayerGroups.opacity(doc, l.groupId))
                    signature = signature * 31 + l.blend.ordinal
                    signature = signature * 31 + if (l.clipping) 1 else 0
                    signature = signature * 31 + l.regionVersion(area)
                    signature = signature * 31 + (l.displayOverride?.generationId ?: 0)
                    signature = signature * 31 + (l.mask?.generationId ?: 0)
                }
                // A clipping base can lie below the requested range; canSplit excludes crossing bases
                // for the upper stack. Full/low stacks start at zero.
                val slot = Slot(doc.id, stage, divisor, x, y)
                var entry = entries[slot]
                if (entry == null || entry.signature != signature) {
                    rasterizedTiles++
                    val rw = (w + divisor - 1) / divisor; val rh = (h + divisor - 1) / divisor
                    val b = Bitmap.createBitmap(rw + 2, rh + 2, Bitmap.Config.ARGB_8888)
                    val c = Canvas(b); c.translate(1f, 1f); c.scale(1f / divisor, 1f / divisor); c.translate(-x.toFloat(), -y.toFloat())
                    c.clipRect(0, 0, doc.width, doc.height)
                    if (background) c.drawColor(doc.backgroundColor)
                    if (pixelsOnly != null) pixelsOnly.drawPixels(c, paint) else Compositor.drawLayers(c, doc, from, to)
                    // Replicate document-edge pixels into the gutter so filtering matches CLAMP.
                    if (x == 0) Canvas(b).drawBitmap(b, Rect(1, 0, 2, rh + 2), Rect(0, 0, 1, rh + 2), null)
                    if (y == 0) Canvas(b).drawBitmap(b, Rect(0, 1, rw + 2, 2), Rect(0, 0, rw + 2, 1), null)
                    if (x + w == doc.width) Canvas(b).drawBitmap(b, Rect(rw, 0, rw + 1, rh + 2), Rect(rw + 1, 0, rw + 2, rh + 2), null)
                    if (y + h == doc.height) Canvas(b).drawBitmap(b, Rect(0, rh, rw + 2, rh + 1), Rect(0, rh + 1, rw + 2, rh + 2), null)
                    if (entry != null) bytes -= entry.bitmap.allocationByteCount
                    entry = Entry(signature, b); entries[slot] = entry; bytes += b.allocationByteCount
                    val iter = entries.entries.iterator()
                    while (bytes > maxBytes && iter.hasNext()) { bytes -= iter.next().value.bitmap.allocationByteCount; iter.remove() }
                }
                canvas.save(); canvas.clipRect(x, y, x + w, y + h)
                canvas.translate(x - divisor.toFloat(), y - divisor.toFloat()); canvas.scale(divisor.toFloat(), divisor.toFloat())
                canvas.drawBitmap(entry.bitmap, 0f, 0f, paint)
                canvas.restore()
            }
        }
    }
}
