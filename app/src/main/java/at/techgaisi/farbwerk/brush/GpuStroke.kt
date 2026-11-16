package at.techgaisi.farbwerk.brush

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderNode
import at.techgaisi.farbwerk.render.EngineMemory
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** GPU-rasterized live stroke, bounded to small dirty textures; CPU replay remains the commit reference. */
internal class GpuStroke(private val width: Int, private val height: Int) {
    private data class Dab(val x: Float, val y: Float, val d: Float, val aspect: Float, val angle: Float, val alpha: Float, val color: Int, val flags: Int)
    private class Tile(val x: Int, val y: Int) {
        val node = RenderNode("Farbwerk-stroke-tile").apply { setUseCompositingLayer(true, null) }
        val dabs = ArrayList<Dab>()
        var dirty = true
    }
    private val tiles = LinkedHashMap<Int, Tile>()
    private val replay = ArrayList<Dab>()
    private val columns = (width + TILE - 1) / TILE
    private var references = 0
    val bytes get() = tiles.size.toLong() * (TILE + 2) * (TILE + 2) * 4 + references * 256L + replay.size * 48L

    fun append(x: Float, y: Float, d: Float, aspect: Float, angle: Float, alpha: Float, color: Int, flags: Int): Boolean {
        val cs = abs(cos(angle)); val sn = abs(sin(angle))
        val ex = d * 0.5f * (cs + aspect * sn) + 5f
        val ey = d * 0.5f * (sn + aspect * cs) + 5f
        val l = maxOf(0, floor(x - ex).toInt()); val t = maxOf(0, floor(y - ey).toInt())
        val r = minOf(width, ceil(x + ex).toInt()); val b = minOf(height, ceil(y + ey).toInt())
        if (l >= r || t >= b) return true
        var missing = 0
        val count = ((r - 1) / TILE - l / TILE + 1) * ((b - 1) / TILE - t / TILE + 1)
        for (j in t / TILE..(b - 1) / TILE) for (i in l / TILE..(r - 1) / TILE) if (!tiles.containsKey(j * columns + i)) missing++
        if (bytes + missing.toLong() * (TILE + 2) * (TILE + 2) * 4 + count * 256L + 48 > EngineMemory.gpuStrokeBudget || references + count > 65536) return false
        val dab = Dab(x, y, d, aspect, angle, alpha, color, flags)
        replay += dab
        for (j in t / TILE..(b - 1) / TILE) for (i in l / TILE..(r - 1) / TILE) {
            val tile = tiles.getOrPut(j * columns + i) { Tile(i * TILE, j * TILE) }
            tile.dabs += dab; tile.dirty = true; references++
        }
        return true
    }

    fun draw(canvas: Canvas, painter: DabPainter, area: RectF, paint: Paint) {
        val sc = canvas.saveLayer(area, paint)
        try {
            for (tile in tiles.values) {
                val w = minOf(TILE, width - tile.x); val h = minOf(TILE, height - tile.y)
                if (tile.dirty) {
                    tile.node.setPosition(0, 0, w + 2, h + 2)
                    val c = tile.node.beginRecording(w + 2, h + 2)
                    try {
                        c.translate(1f - tile.x, 1f - tile.y)
                        for (d in tile.dabs) painter.draw(c, d.x, d.y, d.d, d.aspect, d.angle, d.alpha, d.color, d.flags)
                        painter.flush()
                    } finally { tile.node.endRecording() }
                    tile.dirty = false
                }
                canvas.save(); canvas.clipRect(tile.x, tile.y, tile.x + w, tile.y + h)
                canvas.translate(tile.x - 1f, tile.y - 1f); canvas.drawRenderNode(tile.node); canvas.restore()
            }
        } finally { canvas.restoreToCount(sc) }
    }

    fun replayInto(canvas: Canvas, painter: DabPainter) {
        for (d in replay) painter.draw(canvas, d.x, d.y, d.d, d.aspect, d.angle, d.alpha, d.color, d.flags)
        painter.flush()
    }

    fun release() {
        for (tile in tiles.values) { tile.node.setUseCompositingLayer(false, null); tile.node.discardDisplayList() }
        tiles.clear(); replay.clear(); references = 0
    }

    companion object { private const val TILE = 256 }
}
