package at.techgaisi.farbwerk.adjust

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import at.techgaisi.farbwerk.brush.BP
import at.techgaisi.farbwerk.brush.Brush
import at.techgaisi.farbwerk.brush.DabConsumer
import at.techgaisi.farbwerk.brush.DabPainter
import at.techgaisi.farbwerk.brush.StrokeRenderer
import at.techgaisi.farbwerk.model.InputPoint
import at.techgaisi.farbwerk.selection.MaskUtils

/**
 * Malt einen Pinselstrich mit einem beliebigen Pinsel der Bibliothek in eine Graustufen-Maske (ALPHA_8).
 * Genutzt für den Stift-Modus der Anpassungen und für Klonen („mit dem aktuellen Pinsel“).
 * Die Strichdeckkraft wird nicht hier, sondern beim Zusammenführen der Maske angewandt (wie beim Malen).
 *
 * Verwendet [StrokeRenderer]/[DabPainter] der Pinsel-Engine nur lesend (eigene Instanzen).
 */
internal class MaskStroke {
    private val renderer = StrokeRenderer()
    private val painter = DabPainter()

    /** Maske des laufenden Strichs (volle Dokumentgröße, wird wiederverwendet). */
    var mask: Bitmap? = null
        private set
    private var canvas: Canvas? = null

    /** Seit dem letzten [takeDirty] veränderter Bereich (Dokumentpixel). */
    private val dirty = RectF()
    /** Gesamter Bereich des laufenden Strichs. */
    val strokeBounds = RectF()

    /** Kopfposition (geglättet) für den Pinsel-Ring. */
    val headX: Float get() = renderer.headX
    val headY: Float get() = renderer.headY
    val headDiameter: Float get() = renderer.headDiameter

    var active = false
        private set

    private val sink = DabConsumer { x, y, d, asp, ang, a, _, f ->
        val c = canvas ?: return@DabConsumer
        painter.draw(c, x, y, d, asp, ang, a, Color.BLACK, f)
        val r = d * 0.75f + 2f
        if (dirty.isEmpty) dirty.set(x - r, y - r, x + r, y + r) else dirty.union(x - r, y - r, x + r, y + r)
    }

    fun ensure(w: Int, h: Int) {
        val m = mask
        if (m != null && m.width == w && m.height == h) return
        val b = MaskUtils.create(w, h)
        mask = b
        canvas = Canvas(b)
    }

    /** Beginnt einen Strich mit [brush] und Durchmesser [diameter] (Dokumentpixel). */
    fun begin(
        brush: Brush, diameter: Float, viewScale: Float, pressureSmoothing: Float, motionFilter: Float, p: InputPoint,
        detailScale: Float = 1f,
    ) {
        renderer.brush = brush
        renderer.diameter = diameter
        renderer.opacity = 1f
        renderer.color = Color.BLACK
        renderer.secondaryColor = Color.BLACK
        renderer.globalPressureSmoothing = pressureSmoothing
        renderer.globalMotionFilter = motionFilter
        renderer.viewScale = viewScale
        renderer.sampler = null
        renderer.sink = sink
        renderer.detailScale = detailScale
        painter.setup(brush, detailScale)
        dirty.setEmpty()
        strokeBounds.setEmpty()
        active = true
        renderer.begin(p.x, p.y, p.pressure, p.tilt, p.orientation, p.time, p.isStylus)
    }

    fun add(points: List<InputPoint>) {
        if (!active) return
        for (i in points.indices) {
            val p = points[i]
            renderer.add(p.x, p.y, p.pressure, p.tilt, p.orientation, p.time)
        }
    }

    fun end() {
        if (!active) return
        renderer.end()
        active = false
    }

    /**
     * Liefert den seit dem letzten Aufruf veränderten Bereich (auf [w]×[h] begrenzt) oder null und setzt
     * ihn zurück.
     */
    fun takeDirty(w: Int, h: Int): Rect? {
        if (dirty.isEmpty) return null
        val r = Rect(dirty.left.toInt() - 1, dirty.top.toInt() - 1, dirty.right.toInt() + 2, dirty.bottom.toInt() + 2)
        if (strokeBounds.isEmpty) strokeBounds.set(dirty) else strokeBounds.union(dirty)
        dirty.setEmpty()
        return if (r.intersect(0, 0, w, h)) r else null
    }

    /** Leert die Strichmaske im Bereich des letzten Strichs. */
    fun clear() {
        val c = canvas ?: return
        if (!strokeBounds.isEmpty) {
            c.save()
            c.clipRect(strokeBounds.left - 2, strokeBounds.top - 2, strokeBounds.right + 2, strokeBounds.bottom + 2)
            c.drawColor(0, PorterDuff.Mode.CLEAR)
            c.restore()
        }
        strokeBounds.setEmpty()
        dirty.setEmpty()
    }

    fun release() {
        active = false
        mask = null
        canvas = null
        painter.release()
    }

    companion object {
        /** Strichdeckkraft wie in der Pinsel-Engine (Deckkraft-Regler auf Min/Max des Pinsels abgebildet). */
        fun opacityFor(brush: Brush, sliderOpacity: Float): Float =
            brush[BP.MIN_OPACITY] + (brush[BP.MAX_OPACITY] - brush[BP.MIN_OPACITY]) * sliderOpacity.coerceIn(0f, 1f)
    }
}
