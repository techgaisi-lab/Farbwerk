package at.techgaisi.farbwerk.brush

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Kleine, eigenständige Zeichenfläche mit derselben Pinsel-Pipeline wie die Leinwand (Strich-Puffer,
 * Strichdeckkraft, Körnung, Dynamik). Für die Bibliotheksvorschau und die Testfläche im Pinsel-Studio.
 * Wischen wird hier wie Malen dargestellt.
 */
class BrushPad(val width: Int, val height: Int) {
    val base: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val buffer: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val baseCanvas = Canvas(base)
    private val bufferCanvas = Canvas(buffer)
    private val renderer = StrokeRenderer()
    private val painter = DabPainter()
    private val mergePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val plain = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dirty = RectF()
    private val tmp = RectF()
    var stroking = false
        private set

    private val sink = DabConsumer { x, y, d, asp, ang, a, c, f ->
        painter.draw(bufferCanvas, x, y, d, asp, ang, a, c, f)
        val r = d * 0.75f + 2f
        tmp.set(x - r, y - r, x + r, y + r); dirty.union(tmp)
    }

    private val sampler = ColorSampler { x, y ->
        val ix = x.toInt(); val iy = y.toInt()
        if (ix < 0 || iy < 0 || ix >= width || iy >= height) 0 else base.getPixel(ix, iy)
    }

    fun clear() {
        baseCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        bufferCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        dirty.setEmpty()
    }

    fun begin(
        brush: Brush, diameter: Float, opacity: Float, color: Int,
        x: Float, y: Float, pressure: Float, time: Long, stylus: Boolean, erase: Boolean = false,
    ) {
        renderer.brush = brush
        renderer.diameter = diameter
        renderer.opacity = opacity
        renderer.color = color
        renderer.secondaryColor = if (Color.luminance(color) > 0.5f) Color.DKGRAY else Color.LTGRAY
        renderer.viewScale = 1f
        renderer.sampler = sampler
        renderer.sink = sink
        painter.setup(brush)
        val heavy = brush.int(BP.RENDER_MODE) == RenderMode.HEAVY_GLAZE
        mergePaint.alpha = if (heavy && !erase) 255 else (opacity * 255f + 0.5f).toInt().coerceIn(0, 255)
        mergePaint.blendMode = if (erase) BlendMode.DST_OUT else BrushBlend.mode(brush.int(BP.BLEND))
        dirty.setEmpty()
        stroking = true
        renderer.begin(x, y, pressure, 0f, 0f, time, stylus)
    }

    fun move(x: Float, y: Float, pressure: Float, time: Long) {
        if (stroking) renderer.add(x, y, pressure, 0f, 0f, time)
    }

    fun end() {
        if (!stroking) return
        renderer.end()
        if (renderer.needsEndTaper()) {
            bufferCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
            renderer.replayWithEndTaper { x, y, d, asp, ang, a, c, f -> painter.draw(bufferCanvas, x, y, d, asp, ang, a, c, f) }
        }
        baseCanvas.drawBitmap(buffer, 0f, 0f, mergePaint)
        bufferCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        dirty.setEmpty()
        stroking = false
    }

    /** Zeichnet den aktuellen Stand (Fläche + laufender Strich). */
    fun draw(c: Canvas) {
        c.drawBitmap(base, 0f, 0f, plain)
        if (stroking) c.drawBitmap(buffer, 0f, 0f, mergePaint)
    }

    /** Zeichnet einen geschwungenen Probestrich mit Druckverlauf (dünn → dick → dünn). */
    fun sampleStroke(brush: Brush, diameter: Float, color: Int, opacity: Float = 1f) {
        val margin = max(diameter * 0.6f, height * 0.18f).coerceAtMost(width * 0.2f)
        val x0 = margin; val x1 = width - margin
        val amp = (height / 2f - margin * 0.9f).coerceAtLeast(2f)
        val steps = 90
        var t = 0L
        for (i in 0..steps) {
            val u = i / steps.toFloat()
            val x = x0 + (x1 - x0) * u
            val y = height / 2f + sin(u * 2f * PI.toFloat()) * amp * 0.75f
            val p = (0.25f + 0.75f * sin(u * PI.toFloat())).coerceIn(0.05f, 1f)
            t += 8
            if (i == 0) begin(brush, diameter, opacity, color, x, y, p, t, stylus = true) else move(x, y, p, t)
        }
        end()
    }

    fun release() {
        base.recycle(); buffer.recycle(); painter.release()
    }
}
