package at.techgaisi.farbwerk.color

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.InputPoint
import at.techgaisi.farbwerk.render.Compositor
import kotlin.math.abs

/**
 * Pipette: nimmt die Farbe des zusammengesetzten Bildes unter dem Finger auf.
 *
 * Während des Ziehens wird eine Ring-Lupe um den Berührungspunkt gezeichnet:
 * obere Hälfte = gerade aufgenommene (neue) Farbe, untere Hälfte = bisherige Farbe.
 * Erst beim Loslassen wird die Farbe übernommen ([EditorState.setColorAndRemember]).
 *
 * Das Abtasten ist gedrosselt (höchstens alle [SAMPLE_INTERVAL_MS] und nur nach Bewegung
 * um mindestens einen halben Dokumentpixel), weil [Compositor.samplePixel] das Bild zusammensetzt.
 */
class Eyedropper(private val state: EditorState) {

    private var active = false
    private var docX = 0f
    private var docY = 0f
    private var lastSampleX = Float.NaN
    private var lastSampleY = Float.NaN
    private var lastSampleTime = 0L
    /** Farbe vor Beginn der Pipette (untere Ringhälfte). */
    private var currentColor = 0
    /** Zuletzt aufgenommene Farbe (obere Ringhälfte); null = noch nichts Gültiges aufgenommen. */
    private var sampledColor: Int? = null

    // Wiederverwendete Zeichenobjekte (keine Allokation pro Frame)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val oval = RectF()
    private val pt = FloatArray(2)

    fun begin(p: InputPoint) {
        active = true
        currentColor = state.color
        sampledColor = null
        lastSampleX = Float.NaN
        lastSampleY = Float.NaN
        lastSampleTime = 0L
        docX = p.x; docY = p.y
        sample(force = true)
    }

    fun move(p: InputPoint) {
        if (!active) return
        docX = p.x; docY = p.y
        sample(force = false)
    }

    fun end(p: InputPoint) {
        if (!active) return
        docX = p.x; docY = p.y
        sample(force = true)
        active = false
        sampledColor?.let { state.setColorAndRemember(it) }
        sampledColor = null
        state.invalidate()
    }

    fun cancel() {
        active = false
        sampledColor = null
        state.invalidate()
    }

    /** Ring-Lupe in View-Koordinaten. */
    fun drawOverlay(canvas: Canvas, docToView: Matrix) {
        if (!active) return
        pt[0] = docX; pt[1] = docY
        docToView.mapPoints(pt)
        val density = state.context.resources.displayMetrics.density
        val radius = 58f * density
        val thickness = 22f * density
        oval.set(pt[0] - radius, pt[1] - radius, pt[0] + radius, pt[1] + radius)

        ringPaint.strokeWidth = thickness
        // obere Hälfte: neue Farbe (Winkel 180°..360° im Android-Koordinatensystem)
        ringPaint.color = sampledColor ?: currentColor
        canvas.drawArc(oval, 180f, 180f, false, ringPaint)
        // untere Hälfte: aktuelle Farbe
        ringPaint.color = currentColor
        canvas.drawArc(oval, 0f, 180f, false, ringPaint)

        // feine Konturen innen/außen, damit der Ring auf jedem Untergrund sichtbar ist
        edgePaint.strokeWidth = 1.5f * density
        edgePaint.color = Color.argb(110, 0, 0, 0)
        val half = thickness / 2f
        canvas.drawCircle(pt[0], pt[1], radius + half, edgePaint)
        canvas.drawCircle(pt[0], pt[1], radius - half, edgePaint)
        edgePaint.color = Color.argb(200, 255, 255, 255)
        edgePaint.strokeWidth = 1f * density
        canvas.drawCircle(pt[0], pt[1], radius + half - edgePaint.strokeWidth, edgePaint)
        // kleines Fadenkreuz in der Mitte
        canvas.drawCircle(pt[0], pt[1], 3f * density, edgePaint)
    }

    private fun sample(force: Boolean) {
        val doc = state.document ?: return
        val now = SystemClock.uptimeMillis()
        if (!force) {
            if (now - lastSampleTime < SAMPLE_INTERVAL_MS) return
            if (!lastSampleX.isNaN() && abs(docX - lastSampleX) < 0.5f && abs(docY - lastSampleY) < 0.5f) return
        }
        lastSampleTime = now
        lastSampleX = docX; lastSampleY = docY
        if (docX < 0f || docY < 0f || docX >= doc.width || docY >= doc.height) return
        val c = Compositor.samplePixel(doc, docX, docY)
        // Vollständig transparente Stellen (Hintergrund ausgeblendet) liefern keine Farbe
        if (ColorMath.alpha(c) == 0) return
        sampledColor = ColorMath.opaque(c)
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 24L
    }
}
