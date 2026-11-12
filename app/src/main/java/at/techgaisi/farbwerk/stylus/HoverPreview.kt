package at.techgaisi.farbwerk.stylus

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.brush.BP
import at.techgaisi.farbwerk.brush.BrushTextures
import at.techgaisi.farbwerk.brush.RotationMode
import at.techgaisi.farbwerk.brush.ShapeKind
import at.techgaisi.farbwerk.brush.StrokeRenderer
import at.techgaisi.farbwerk.model.Tool
import at.techgaisi.farbwerk.render.Compositor
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Schwebevorschau des Stifts (S Pen über dem Display, wie die Hover-Vorschau mit dem Apple Pencil):
 *  - Umriss der Pinselspitze in Größe, Rundheit und Drehung des aktuellen Pinsels, mit zarter Füllung der
 *    Pinselform in der aktuellen Farbe. Neigung/Ausrichtung des schwebenden Stifts fließen ein, so dass die
 *    Vorschau beim Schraffieren mit geneigtem Stift breiter/flacher wird.
 *  - Pipetten-Vorschau (kleiner Ring: oben Farbe unter dem Stift, unten aktuelle Farbe), wenn die Pipette scharf
 *    ist bzw. die Seitentaste mit Pipetten-Aktion gehalten wird.
 *
 * Wiederverwendete Zeichenobjekte – keine Allokationen pro Frame.
 */
class HoverPreview(private val state: EditorState) {
    private val density = state.context.resources.displayMetrics.density
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ovalFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val oval = RectF()
    private val m = Matrix()
    private val values = FloatArray(9)
    private val vec = FloatArray(2)

    private var sampled: Int? = null
    private var lastSampleTime = 0L
    private var lastSampleX = Float.NaN
    private var lastSampleY = Float.NaN

    /** Neue Schwebe-Sitzung: Pipetten-Zwischenspeicher verwerfen. */
    fun reset() {
        sampled = null
        lastSampleX = Float.NaN; lastSampleY = Float.NaN
    }

    /**
     * @param vx/vy Stiftposition in View-Koordinaten, [docX]/[docY] im Dokument
     * @param tilt Neigung in Radiant ab der Senkrechten, [orientDoc] Ausrichtung im Dokument (Radiant)
     * @param eraserEnd Radierer-Ende des Stifts schwebt
     */
    fun draw(
        canvas: Canvas, docToView: Matrix, vx: Float, vy: Float, docX: Float, docY: Float,
        tilt: Float, orientDoc: Float, eraserEnd: Boolean, showBrush: Boolean, pipette: Boolean,
    ) {
        if (pipette) { drawPipette(canvas, vx, vy, docX, docY); return }
        if (showBrush) drawBrush(canvas, docToView, vx, vy, tilt, orientDoc, eraserEnd)
    }

    private fun drawBrush(
        canvas: Canvas, docToView: Matrix, vx: Float, vy: Float, tilt: Float, orientDoc: Float, eraserEnd: Boolean,
    ) {
        val tool = when {
            eraserEnd && (state.tool == Tool.PAINT || state.tool == Tool.SMUDGE) -> Tool.ERASE
            else -> state.tool
        }
        val engine = state.brushEngine
        val brush = engine.brushFor(tool) ?: return
        val settings = state.toolSettings[tool] ?: return
        var d = engine.diameterFor(brush, settings.size)
        val tiltT = StrokeRenderer.tiltFactor(brush, tilt)
        d *= 1f + brush[BP.TILT_SIZE] * tiltT
        val tiltRound = brush[BP.TILT_ROUNDNESS]
        val aspect = (brush[BP.ROUNDNESS] * (1f - tiltRound * tiltT * 0.7f)).coerceIn(0.03f, 1f)
        val baseRot = Math.toRadians(brush[BP.ROTATION].toDouble()).toFloat()
        val rotMode = brush.int(BP.ROTATION_MODE)
        val docAngle = when {
            rotMode == RotationMode.AZIMUTH -> orientDoc + baseRot
            rotMode == RotationMode.FIXED && tiltRound > 0f && tiltT > 0f && brush[BP.ROUNDNESS] > 0.95f -> orientDoc + baseRot
            else -> baseRot
        }
        // Dokument → View: Maßstab und Drehung (inkl. Spiegelung) der Ansicht
        docToView.getValues(values)
        val scale = hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y])
        vec[0] = cos(docAngle); vec[1] = sin(docAngle)
        docToView.mapVectors(vec)
        val viewDeg = Math.toDegrees(atan2(vec[1], vec[0]).toDouble()).toFloat()
        val r = d * scale / 2f

        if (r < 2.5f) {
            // Sehr kleine Spitze: Fadenkreuz statt Umriss
            val s = 5f * density
            outlinePaint.color = 0x99000000.toInt(); outlinePaint.strokeWidth = 2.5f
            canvas.drawLine(vx - s, vy, vx + s, vy, outlinePaint); canvas.drawLine(vx, vy - s, vx, vy + s, outlinePaint)
            outlinePaint.color = 0xDDFFFFFF.toInt(); outlinePaint.strokeWidth = 1f
            canvas.drawLine(vx - s, vy, vx + s, vy, outlinePaint); canvas.drawLine(vx, vy - s, vx, vy + s, outlinePaint)
            return
        }

        // zarte Füllung in Pinselform (Radierer: neutralgrau)
        val tint = if (tool == Tool.ERASE) Color.rgb(128, 128, 128) else (state.color or 0xFF000000.toInt())
        val alpha = if (tool == Tool.SMUDGE) 40 else 70
        val shapeIndex = brush.int(BP.SHAPE_KIND)
        if (shapeIndex == ShapeKind.HARD.ordinal) {
            ovalFill.color = (tint and 0x00FFFFFF) or (alpha shl 24)
            canvas.save(); canvas.translate(vx, vy); canvas.rotate(viewDeg)
            oval.set(-r, -r * aspect, r, r * aspect)
            canvas.drawOval(oval, ovalFill)
            canvas.restore()
        } else {
            val shape = BrushTextures.shape(shapeIndex)
            val n = BrushTextures.SHAPE_SIZE.toFloat()
            m.setTranslate(-n / 2f, -n / 2f)
            m.postScale(2f * r / n, 2f * r * aspect / n)
            m.postRotate(viewDeg)
            m.postTranslate(vx, vy)
            fillPaint.color = (tint and 0x00FFFFFF) or (alpha shl 24)
            canvas.drawBitmap(shape, m, fillPaint)
        }

        // Umriss: dunkel + hell, damit er auf jedem Untergrund sichtbar ist
        canvas.save(); canvas.translate(vx, vy); canvas.rotate(viewDeg)
        oval.set(-r, -r * aspect, r, r * aspect)
        outlinePaint.color = 0x88000000.toInt(); outlinePaint.strokeWidth = 2.5f
        canvas.drawOval(oval, outlinePaint)
        outlinePaint.color = 0xDDFFFFFF.toInt(); outlinePaint.strokeWidth = 1f
        canvas.drawOval(oval, outlinePaint)
        canvas.restore()
    }

    private fun drawPipette(canvas: Canvas, vx: Float, vy: Float, docX: Float, docY: Float) {
        sample(docX, docY)
        val radius = 18f * density
        val thick = 8f * density
        oval.set(vx - radius, vy - radius, vx + radius, vy + radius)
        ringPaint.strokeWidth = thick
        ringPaint.color = sampled ?: state.color
        canvas.drawArc(oval, 180f, 180f, false, ringPaint)
        ringPaint.color = state.color
        canvas.drawArc(oval, 0f, 180f, false, ringPaint)
        outlinePaint.strokeWidth = 1.2f * density
        outlinePaint.color = Color.argb(110, 0, 0, 0)
        canvas.drawCircle(vx, vy, radius + thick / 2f, outlinePaint)
        canvas.drawCircle(vx, vy, radius - thick / 2f, outlinePaint)
        outlinePaint.color = Color.argb(210, 255, 255, 255)
        outlinePaint.strokeWidth = 1f * density
        canvas.drawCircle(vx, vy, 2.5f * density, outlinePaint)
    }

    /** Gedrosseltes Abtasten des zusammengesetzten Bildes unter dem schwebenden Stift. */
    private fun sample(x: Float, y: Float) {
        val doc = state.document ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastSampleTime < 30L) return
        if (!lastSampleX.isNaN() && abs(x - lastSampleX) < 0.5f && abs(y - lastSampleY) < 0.5f) return
        lastSampleTime = now
        lastSampleX = x; lastSampleY = y
        if (x < 0f || y < 0f || x >= doc.width || y >= doc.height) { sampled = null; return }
        val c = Compositor.samplePixel(doc, x, y)
        sampled = if (Color.alpha(c) == 0) null else (c or 0xFF000000.toInt())
    }
}
