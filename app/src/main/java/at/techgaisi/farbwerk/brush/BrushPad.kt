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
 * Strichdeckkraft, Körnung, Dynamik, Rendering-Effekte). Für die Bibliotheksvorschau und die Testfläche im
 * Pinsel-Studio. Wischen wird hier wie Malen dargestellt.
 */
class BrushPad(val width: Int, val height: Int) {
    val base: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val buffer: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val baseCanvas = Canvas(base)
    private val bufferCanvas = Canvas(buffer)
    private val renderer = StrokeRenderer()
    private val painter = DabPainter()
    private val mergePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val thresholdPaint = Paint()
    private var thresholdOn = false
    private val plain = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dirty = RectF()
    private val tmp = RectF()
    private var brush: Brush? = null
    private var erase = false
    var stroking = false
        private set

    /** Sekundärfarbe für Farbdynamik (Standard: Kontrast zur Malfarbe). */
    var secondaryColor: Int? = null

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
        tilt: Float = 0f, orientation: Float = 0f, wet: Boolean = true,
    ) {
        this.brush = brush
        this.erase = erase
        renderer.brush = brush
        renderer.diameter = diameter
        renderer.opacity = opacity
        renderer.color = color
        renderer.secondaryColor = secondaryColor ?: if (Color.luminance(color) > 0.5f) Color.DKGRAY else Color.LTGRAY
        renderer.viewScale = 1f
        renderer.viewRotation = 0f
        renderer.sampler = if (wet) sampler else null
        renderer.sink = sink
        painter.setup(brush)
        val heavy = brush.int(BP.RENDER_MODE) == RenderMode.HEAVY_GLAZE
        mergePaint.alpha = if (heavy && !erase) 255 else (opacity * 255f + 0.5f).toInt().coerceIn(0, 255)
        mergePaint.blendMode = if (erase) BlendMode.DST_OUT else BrushBlend.mode(brush.int(BP.BLEND))
        thresholdOn = brush.on(BP.ALPHA_THRESHOLD)
        if (thresholdOn) thresholdPaint.colorFilter = StrokeEffects.thresholdFilter(brush[BP.ALPHA_THRESHOLD_VALUE])
        dirty.setEmpty()
        stroking = true
        renderer.begin(x, y, pressure, tilt, orientation, time, stylus)
    }

    fun move(x: Float, y: Float, pressure: Float, time: Long, tilt: Float = 0f, orientation: Float = 0f) {
        if (stroking) renderer.add(x, y, pressure, tilt, orientation, time)
    }

    fun end() {
        if (!stroking) return
        renderer.end()
        if (renderer.needsEndTaper()) {
            bufferCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
            renderer.replayWithEndTaper { x, y, d, asp, ang, a, c, f -> painter.draw(bufferCanvas, x, y, d, asp, ang, a, c, f) }
        }
        val b = brush
        if (b != null && !erase && !dirty.isEmpty && StrokeEffects.needsPostPass(b)) {
            StrokeEffects.apply(buffer, dirty, b, renderer.diameter)
        }
        drawBuffer(baseCanvas)
        bufferCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        dirty.setEmpty()
        stroking = false
    }

    private fun drawBuffer(c: Canvas) {
        if (thresholdOn) {
            val sc = c.saveLayer(null, mergePaint)
            c.drawBitmap(buffer, 0f, 0f, thresholdPaint)
            c.restoreToCount(sc)
        } else c.drawBitmap(buffer, 0f, 0f, mergePaint)
    }

    /** Zeichnet den aktuellen Stand (Fläche + laufender Strich). */
    fun draw(c: Canvas) {
        c.drawBitmap(base, 0f, 0f, plain)
        if (stroking) drawBuffer(c)
    }

    /**
     * Zeichnet einen geschwungenen Probestrich mit Druckverlauf (dünn → dick → dünn). Druck-Minimum,
     * Druck-Maßstab, Neigung und nasse Farbe richten sich nach der Gruppe "Vorschau" des Pinsels.
     */
    fun sampleStroke(brush: Brush, diameter: Float, color: Int, opacity: Float = 1f, usePreviewSettings: Boolean = false) {
        val margin = max(diameter * 0.6f, height * 0.18f).coerceAtMost(width * 0.2f)
        val x0 = margin; val x1 = width - margin
        val amp = (height / 2f - margin * 0.9f).coerceAtLeast(2f)
        val steps = 90
        val pMin = if (usePreviewSettings) brush[BP.PREVIEW_PRESSURE_MIN] else 0.25f
        val pScale = if (usePreviewSettings) brush[BP.PREVIEW_PRESSURE_SCALE] else 1f
        val tilt = if (usePreviewSettings) Math.toRadians((90f - brush[BP.PREVIEW_TILT]).toDouble()).toFloat()
            .takeIf { brush[BP.PREVIEW_TILT] > 0.5f } ?: 0f else 0f
        val wet = !usePreviewSettings || brush.on(BP.PREVIEW_WET)
        // Ende-Verjüngung entsteht mit dem Stift durch das Nachlassen des Drucks beim Abheben (wie auf der Leinwand)
        val release = brush[BP.TAPER_END] > 0f || (brush.on(BP.TAPER_LINK) && brush[BP.TAPER_START] > 0f)
        var t = 0L
        for (i in 0..steps) {
            val u = i / steps.toFloat()
            val x = x0 + (x1 - x0) * u
            val y = height / 2f + sin(u * 2f * PI.toFloat()) * amp * 0.75f
            var p = ((pMin + (1f - pMin) * sin(u * PI.toFloat())) * pScale).coerceIn(0.05f, 1f)
            if (release && u > RELEASE_FROM) p = (p * (1f - u) / (1f - RELEASE_FROM)).coerceAtLeast(0.01f)
            t += 8
            if (i == 0) begin(brush, diameter, opacity, color, x, y, p, t, stylus = true, tilt = tilt, wet = wet)
            else move(x, y, p, t, tilt)
        }
        end()
    }

    /** Ein einzelner Stempel in der Mitte (Eigenschaft "Stempelvorschau verwenden"). */
    fun stampPreview(brush: Brush, diameter: Float, color: Int) {
        clear()
        painter.setup(brush)
        val d = diameter.coerceAtMost(height * 0.9f)
        painter.draw(baseCanvas, width / 2f, height / 2f, d, brush[BP.ROUNDNESS],
            Math.toRadians(brush[BP.ROTATION].toDouble()).toFloat(), 1f, color, 0)
    }

    fun release() {
        base.recycle(); buffer.recycle(); painter.release()
    }

    private companion object {
        /** Anteil des Probestrichs, ab dem der Druck wie beim Abheben ausläuft. */
        const val RELEASE_FROM = 0.86f
    }
}
