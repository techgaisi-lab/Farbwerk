package at.techgaisi.farbwerk.guides

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.ArtDocument
import at.techgaisi.farbwerk.model.GuideKind
import at.techgaisi.farbwerk.model.SymmetryMode
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Zeichnet die Zeichenhilfen (2D-Raster, isometrisches Raster, Symmetrielinien) über die Leinwand.
 * Linien werden im Dokumentraum berechnet (nur der sichtbare Ausschnitt), per Matrix in
 * View-Koordinaten abgebildet und dort 1 px breit gezeichnet. Keine Allokationen pro Frame,
 * außer wenn der Linienpuffer wachsen muss.
 */
object GuidesOverlay {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inverse = Matrix()
    private val visible = RectF()
    private val clipPath = Path()
    private val corners = FloatArray(8)
    private var lines = FloatArray(512)
    private var count = 0
    private val pt = FloatArray(2)

    /** Weniger als so viele View-Pixel Abstand → Raster wird ausgedünnt. */
    private const val MIN_SPACING_PX = 6f

    fun draw(canvas: Canvas, state: EditorState, docToView: Matrix) {
        val doc = state.document ?: return
        val g = doc.guides
        if (!g.visible || g.kind == GuideKind.NONE) return
        if (!docToView.invert(inverse)) return

        // Sichtbaren Bereich im Dokumentraum bestimmen
        val cw = canvas.width.toFloat(); val ch = canvas.height.toFloat()
        corners[0] = 0f; corners[1] = 0f; corners[2] = cw; corners[3] = 0f
        corners[4] = cw; corners[5] = ch; corners[6] = 0f; corners[7] = ch
        inverse.mapPoints(corners)
        visible.set(minOf(corners[0], corners[2], corners[4], corners[6]),
            minOf(corners[1], corners[3], corners[5], corners[7]),
            maxOf(corners[0], corners[2], corners[4], corners[6]),
            maxOf(corners[1], corners[3], corners[5], corners[7]))
        if (!visible.intersect(0f, 0f, doc.width.toFloat(), doc.height.toFloat())) return

        val alpha = (g.opacity.coerceIn(0f, 1f) * 255).toInt()
        paint.color = (g.color and 0x00FFFFFF) or (alpha shl 24)
        paint.strokeWidth = 1f

        // Auf die Leinwand begrenzen (auch bei Drehung)
        corners[0] = 0f; corners[1] = 0f; corners[2] = doc.width.toFloat(); corners[3] = 0f
        corners[4] = doc.width.toFloat(); corners[5] = doc.height.toFloat(); corners[6] = 0f; corners[7] = doc.height.toFloat()
        docToView.mapPoints(corners)
        clipPath.rewind()
        clipPath.moveTo(corners[0], corners[1]); clipPath.lineTo(corners[2], corners[3])
        clipPath.lineTo(corners[4], corners[5]); clipPath.lineTo(corners[6], corners[7]); clipPath.close()

        val scale = viewScale(docToView)
        canvas.save()
        canvas.clipPath(clipPath)
        count = 0
        when (g.kind) {
            GuideKind.GRID_2D -> grid2d(g.gridSize, scale)
            GuideKind.ISOMETRIC -> isometric(g.gridSize, scale)
            GuideKind.SYMMETRY -> symmetry(doc)
            GuideKind.NONE -> {}
        }
        if (count > 0) {
            docToView.mapPoints(lines, 0, lines, 0, count / 2)
            canvas.drawLines(lines, 0, count, paint)
        }
        if (g.kind == GuideKind.SYMMETRY) drawCenterHandle(canvas, doc, docToView, paint.color)
        canvas.restore()
    }

    private fun viewScale(m: Matrix): Float {
        pt[0] = 1f; pt[1] = 0f
        m.mapVectors(pt)
        return hypot(pt[0], pt[1]).coerceAtLeast(1e-4f)
    }

    private fun add(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (count + 4 > lines.size) lines = lines.copyOf(lines.size * 2)
        lines[count++] = x0; lines[count++] = y0; lines[count++] = x1; lines[count++] = y1
    }

    /** Rasterabstand so oft verdoppeln, bis er auf dem Bildschirm sichtbar bleibt. */
    private fun effectiveStep(size: Float, scale: Float): Float {
        var s = size.coerceAtLeast(2f)
        while (s * scale < MIN_SPACING_PX) s *= 2f
        return s
    }

    private fun grid2d(size: Float, scale: Float) {
        val s = effectiveStep(size, scale)
        var x = ceil(visible.left / s) * s
        while (x <= visible.right) { add(x, visible.top, x, visible.bottom); x += s }
        var y = ceil(visible.top / s) * s
        while (y <= visible.bottom) { add(visible.left, y, visible.right, y); y += s }
    }

    /**
     * Isometrisches Raster aus gleichseitigen Dreiecken der Seitenlänge [size]:
     * senkrechte Linien sowie Linien unter +30° und −30°.
     */
    private fun isometric(size: Float, scale: Float) {
        val side = effectiveStep(size, scale)
        val spacing = side * sqrt(3f) / 2f   // Abstand paralleler Linien
        // Senkrechte Linien
        var x = ceil(visible.left / spacing) * spacing
        while (x <= visible.right) { add(x, visible.top, x, visible.bottom); x += spacing }
        // Schräge Linien: Normale n, Linien n·p = k * spacing
        val cx = visible.centerX(); val cy = visible.centerY()
        val half = hypot(visible.width(), visible.height()) / 2f + spacing
        for (angleDeg in intArrayOf(30, -30)) {
            val a = Math.toRadians(angleDeg.toDouble())
            val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
            val nx = -dy; val ny = dx
            // Projektionsbereich des sichtbaren Rechtecks auf n
            var minP = Float.MAX_VALUE; var maxP = -Float.MAX_VALUE
            for (i in 0 until 4) {
                val px = if (i == 0 || i == 3) visible.left else visible.right
                val py = if (i < 2) visible.top else visible.bottom
                val p = px * nx + py * ny
                if (p < minP) minP = p; if (p > maxP) maxP = p
            }
            var k = floor(minP / spacing)
            val kEnd = ceil(maxP / spacing)
            val cProj = cx * nx + cy * ny
            while (k <= kEnd) {
                val c = k * spacing
                // Punkt auf der Linie nahe der Bildmitte
                val ox = cx + (c - cProj) * nx
                val oy = cy + (c - cProj) * ny
                add(ox - dx * half, oy - dy * half, ox + dx * half, oy + dy * half)
                k += 1f
            }
        }
    }

    private fun center(doc: ArtDocument): Pair<Float, Float> {
        val g = doc.guides
        return (if (g.symmetryCenterX.isNaN()) doc.width / 2f else g.symmetryCenterX) to
            (if (g.symmetryCenterY.isNaN()) doc.height / 2f else g.symmetryCenterY)
    }

    private fun symmetry(doc: ArtDocument) {
        val g = doc.guides
        val (cx, cy) = center(doc)
        val w = doc.width.toFloat(); val h = doc.height.toFloat()
        when (g.symmetry) {
            SymmetryMode.VERTICAL -> add(cx, 0f, cx, h)
            SymmetryMode.HORIZONTAL -> add(0f, cy, w, cy)
            SymmetryMode.QUADRANT -> { add(cx, 0f, cx, h); add(0f, cy, w, cy) }
            SymmetryMode.RADIAL -> {
                val n = g.radialSegments.coerceIn(2, 32)
                val len = hypot(w, h)
                for (i in 0 until n) {
                    val a = Math.toRadians(-90.0 + 360.0 * i / n)
                    add(cx, cy, cx + cos(a).toFloat() * len, cy + sin(a).toFloat() * len)
                }
            }
        }
    }

    /** Kleiner Kreis im Symmetriezentrum (Assistent aktiv = gefüllt). */
    private fun drawCenterHandle(canvas: Canvas, doc: ArtDocument, m: Matrix, color: Int) {
        val (cx, cy) = center(doc)
        pt[0] = cx; pt[1] = cy
        m.mapPoints(pt)
        handlePaint.style = Paint.Style.FILL
        handlePaint.color = if (doc.guides.assisted) color or (0xFF shl 24) else Color.argb(160, 255, 255, 255)
        canvas.drawCircle(pt[0], pt[1], 6f, handlePaint)
        handlePaint.style = Paint.Style.STROKE
        handlePaint.strokeWidth = 1.5f
        handlePaint.color = Color.argb(200, 0, 0, 0)
        canvas.drawCircle(pt[0], pt[1], 6f, handlePaint)
    }
}
