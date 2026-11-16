package at.techgaisi.farbwerk.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.LinkedHashMap
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.concurrent.atomic.AtomicLong

/** Immutable, lossless premultiplied pixels. Empty tiles have no object or pixel allocation. */
internal class PixelTile private constructor(val width: Int, val height: Int, val bytes: ByteArray, val packed: Boolean) {
    val revision = nextRevision.incrementAndGet()
    fun decode(): Bitmap {
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val raw = if (!packed) bytes else {
            val out = ByteArray(b.rowBytes * height)
            val z = Inflater()
            try {
                z.setInput(bytes)
                var offset = 0
                while (!z.finished() && offset < out.size) {
                    val n = z.inflate(out, offset, out.size - offset)
                    check(n > 0) { "Incomplete pixel tile" }
                    offset += n
                }
                check(offset == out.size && z.finished()) { "Invalid pixel tile" }
            } finally { z.end() }
            out
        }
        b.copyPixelsFromBuffer(ByteBuffer.wrap(raw))
        return b
    }

    companion object {
        private val nextRevision = AtomicLong()
        fun capture(b: Bitmap): PixelTile? {
            val raw = ByteArray(b.rowBytes * b.height)
            b.copyPixelsToBuffer(ByteBuffer.wrap(raw))
            if (raw.all { it == 0.toByte() }) return null
            val z = Deflater(Deflater.BEST_SPEED)
            val out = ByteArrayOutputStream()
            try {
                z.setInput(raw); z.finish()
                val block = ByteArray(8192)
                while (!z.finished()) { val n = z.deflate(block); check(n > 0); out.write(block, 0, n) }
            } finally { z.end() }
            val compressed = out.toByteArray()
            return if (compressed.size < raw.size) PixelTile(b.width, b.height, compressed, true)
                else PixelTile(b.width, b.height, raw, false)
        }
    }
}

/** Shared bounded decode/texture working set. Eviction drops ownership; never recycle a displayed bitmap. */
internal object PixelTileCache {
    private val entries = LinkedHashMap<PixelTile, Bitmap>(16, 0.75f, true)
    private var bytes = 0L
    var maxBytes = 48L * 1024 * 1024
        @Synchronized set(value) { field = value.coerceAtLeast(1024 * 1024); trim() }
    val residentBytes: Long @Synchronized get() = bytes
    @Synchronized fun obtain(tile: PixelTile): Bitmap = entries[tile] ?: tile.decode().also {
        entries[tile] = it; bytes += it.allocationByteCount; trim()
    }
    @Synchronized fun clear() { entries.clear(); bytes = 0 }
    private fun trim() {
        val iter = entries.entries.iterator()
        while (bytes > maxBytes && iter.hasNext()) { bytes -= iter.next().value.allocationByteCount; iter.remove() }
    }
}

/** Sparse authoritative layer storage, plus a temporary compatibility bitmap for legacy tools. */
class PixelTiles(val width: Int, val height: Int) {
    private val tiles = HashMap<Int, PixelTile>()
    private var dense: Bitmap? = null
    private var denseGeneration = 0
    private var denseDirty = false
    private var serial = 1L
    private val columns = (width + TILE - 1) / TILE
    private val copy = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private val clip = Rect()
    val version: Long @Synchronized get() { syncDense(); return serial }
    val storedBytes: Long @Synchronized get() = tiles.values.sumOf { it.bytes.size.toLong() } + (dense?.allocationByteCount ?: 0)
    val encodedBytes: Long @Synchronized get() = tiles.values.sumOf { it.bytes.size.toLong() }
    val allocatedTiles: Int @Synchronized get() = tiles.size
    val hasDenseBitmap: Boolean @Synchronized get() = dense != null

    @Synchronized fun regionVersion(area: Rect): Long {
        syncDense()
        dense?.let { return serial }
        val r = Rect(area)
        if (!r.intersect(0, 0, width, height)) return 0
        var result = 1L
        for (j in r.top / TILE..(r.bottom - 1) / TILE) for (i in r.left / TILE..(r.right - 1) / TILE) {
            result = result * 1000003L xor (tiles[j * columns + i]?.revision ?: 0)
        }
        return result
    }

    @Synchronized fun bitmap(): Bitmap {
        dense?.let { return it }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(out), Paint())
        dense = out; denseGeneration = out.generationId; denseDirty = false
        return out
    }

    /** Keep immutable tiles when the compatibility bitmap was only read. */
    @Synchronized fun compact() {
        val b = dense ?: return
        syncDense()
        if (b.config != Bitmap.Config.ARGB_8888) return // Editable ALPHA_8 mask proxies retain their format.
        if (denseDirty) {
            tiles.clear()
            for (y in 0 until height step TILE) for (x in 0 until width step TILE) {
                val part = Bitmap.createBitmap(b, x, y, minOf(TILE, width - x), minOf(TILE, height - y))
                PixelTile.capture(part)?.let { tiles[y / TILE * columns + x / TILE] = it }
                if (part !== b) part.recycle()
            }
        }
        dense = null; denseDirty = false
    }

    @Synchronized fun replace(bitmap: Bitmap) {
        require(bitmap.width == width && bitmap.height == height)
        tiles.clear(); dense = bitmap; denseGeneration = bitmap.generationId; denseDirty = true; serial++
    }

    @Synchronized fun draw(canvas: Canvas, paint: Paint) {
        dense?.let { canvas.drawBitmap(it, 0f, 0f, paint); return }
        if (!canvas.getClipBounds(clip) || !clip.intersect(0, 0, width, height)) return
        for (j in clip.top / TILE..(clip.bottom - 1) / TILE) for (i in clip.left / TILE..(clip.right - 1) / TILE) {
            val tile = tiles[j * columns + i] ?: continue
            val x = i * TILE; val y = j * TILE
            canvas.drawBitmap(PixelTileCache.obtain(tile), x.toFloat(), y.toFloat(), paint)
        }
    }

    @Synchronized fun region(rect: Rect): Bitmap {
        require(rect.left >= 0 && rect.top >= 0 && rect.right <= width && rect.bottom <= height && !rect.isEmpty)
        val out = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.translate(-rect.left.toFloat(), -rect.top.toFloat())
        draw(c, Paint())
        return out
    }

    @Synchronized fun snapshotRegion(rect: Rect): PixelTiles {
        require(rect.left >= 0 && rect.top >= 0 && rect.right <= width && rect.bottom <= height && !rect.isEmpty)
        if (dense == null && rect.left % TILE == 0 && rect.top % TILE == 0 &&
            rect.width() == minOf(TILE, width - rect.left) && rect.height() == minOf(TILE, height - rect.top)) {
            return PixelTiles(rect.width(), rect.height()).also { out ->
                tiles[rect.top / TILE * columns + rect.left / TILE]?.let { out.tiles[0] = it }
            }
        }
        val b = region(rect)
        return try { from(b) } finally { b.recycle() }
    }

    @Synchronized fun sample(x: Int, y: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return 0
        dense?.let { return it.getPixel(x, y) }
        val t = tiles[y / TILE * columns + x / TILE] ?: return 0
        return PixelTileCache.obtain(t).getPixel(x % TILE, y % TILE)
    }

    /** Mutate only intersecting tiles. Canvas coordinates remain document coordinates. */
    @Synchronized fun edit(area: Rect, operation: (Canvas) -> Unit) {
        val r = Rect(area)
        if (!r.intersect(0, 0, width, height)) return
        dense?.let {
            val c = Canvas(it); c.clipRect(r); operation(c); syncDense(); return
        }
        for (j in r.top / TILE..(r.bottom - 1) / TILE) for (i in r.left / TILE..(r.right - 1) / TILE) {
            val x = i * TILE; val y = j * TILE; val k = j * columns + i
            val b = tiles[k]?.decode() ?: Bitmap.createBitmap(minOf(TILE, width - x), minOf(TILE, height - y), Bitmap.Config.ARGB_8888)
            try {
                val c = Canvas(b); c.translate(-x.toFloat(), -y.toFloat()); c.clipRect(r); operation(c)
                val t = PixelTile.capture(b)
                if (t == null) tiles.remove(k) else tiles[k] = t
            } finally { b.recycle() }
        }
        serial++
    }

    fun put(src: Bitmap, rect: Rect) = edit(rect) { it.drawBitmap(src, rect.left.toFloat(), rect.top.toFloat(), copy) }

    private fun syncDense() {
        val b = dense ?: return
        val generation = b.generationId
        if (generation != denseGeneration) { denseGeneration = generation; denseDirty = true; serial++ }
    }

    /** Cheap immutable snapshot for background saving; edits replace tiles instead of mutating them. */
    @Synchronized fun snapshot(): PixelTiles {
        compact()
        return PixelTiles(width, height).also { it.tiles.putAll(tiles); it.serial = serial }
    }

    companion object {
        const val TILE = 256
        fun from(bitmap: Bitmap) = PixelTiles(bitmap.width, bitmap.height).also { it.replace(bitmap); it.compact() }
    }
}
