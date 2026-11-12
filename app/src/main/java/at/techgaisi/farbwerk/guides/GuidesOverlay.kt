package at.techgaisi.farbwerk.guides

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.animation.AnimationController
import at.techgaisi.farbwerk.model.ArtDocument
import at.techgaisi.farbwerk.model.GuideKind
import at.techgaisi.farbwerk.model.GuideSettings
import at.techgaisi.farbwerk.model.SymmetryMode
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Zeichnet die Zeichenhilfen (2D-Raster, isometrisches Raster, Perspektive, Symmetrielinien) über die Leinwand
 * sowie die Zwiebelschichten des Animationsassistenten (darunter).
 *
 * Raster werden im „Rasterraum“ berechnet (Ursprung = Dokumentmitte + Verschiebung, gedreht um
 * [GuideSettings.rotation]), nur für den sichtbaren Ausschnitt, und per Matrix in View-Koordinaten abgebildet.
 * Keine Allokationen pro Frame, außer wenn der Linienpuffer wachsen muss.
 */
object GuidesOverlay {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inverse = Matrix()
    private val gridToView = Matrix()
    private val gridToDoc = Matrix()
    private val visible = RectF()
    private val clipPath = Path()
    private val corners = FloatArray(8)
    private var lines = FloatArray(512)
    private var count = 0
    private val pt = FloatArray(2)
    private val invTmp = Matrix()
    private val toGridTmp = Matrix()
    private val docRectTmp = RectF()
    private val density = Resources.getSystem().displayMetrics.density

    /** Weniger als so viele View-Pixel Abstand → Raster wird ausgedünnt. */
    private const val MIN_SPACING_PX = 6f

    /** Anzahl Strahlen je Fluchtpunkt (alle 5°). */
    private const val RAYS = 72

    fun draw(canvas: Canvas, state: EditorState, docToView: Matrix) {
        val doc = state.document ?: return
        // Zwiebelschichten des Animationsassistenten (liegen unter den Hilfslinien)
        try { AnimationController.drawOnCanvas(canvas, state, doc, docToView) } catch (t: Throwable) {
            android.util.Log.e("Farbwerk", "onion", t)
        }

        val g = doc.guides
        if (!g.visible || g.kind == GuideKind.NONE) return
        drawGuides(canvas, doc, docToView, g, editing = false)
    }

    /**
     * Zeichnet die Hilfe mit den Einstellungen [g] (der Zeichenhilfe-Editor übergibt seine Arbeitskopie).
     */
    fun drawGuides(canvas: Canvas, doc: ArtDocument, docToView: Matrix, g: GuideSettings, editing: Boolean) {
        if (!docToView.invert(inverse)) return

        val alpha = (g.opacity.coerceIn(0f, 1f) * 255).toInt()
        paint.color = (g.color and 0x00FFFFFF) or (alpha shl 24)
        paint.strokeWidth = (g.thickness.coerceIn(0.25f, 8f) * density).coerceAtLeast(1f)

        // Auf die Leinwand begrenzen (auch bei Drehung)
        corners[0] = 0f; corners[1] = 0f; corners[2] = doc.width.toFloat(); corners[3] = 0f
        corners[4] = doc.width.toFloat(); corners[5] = doc.height.toFloat(); corners[6] = 0f; corners[7] = doc.height.toFloat()
        docToView.mapPoints(corners)
        clipPath.rewind()
        clipPath.moveTo(corners[0], corners[1]); clipPath.lineTo(corners[2], corners[3])
        clipPath.lineTo(corners[4], corners[5]); clipPath.lineTo(corners[6], corners[7]); clipPath.close()

        canvas.save()
        canvas.clipPath(clipPath)
        count = 0
        when (g.kind) {
            GuideKind.GRID_2D, GuideKind.ISOMETRIC -> {
                gridMatrix(doc, g, gridToDoc)
                gridToView.set(gridToDoc); gridToView.postConcat(docToView)
                if (visibleIn(gridToView, canvas, doc)) {
                    val scale = viewScale(gridToView)
                    if (g.kind == GuideKind.GRID_2D) grid2d(g.gridSize, scale) else isometric(g.gridSize, scale)
                    flush(canvas, gridToView)
                }
            }
            GuideKind.PERSPECTIVE -> {
                perspective(doc, g)
                flush(canvas, docToView)
            }
            GuideKind.SYMMETRY -> {
                symmetry(doc, g)
                flush(canvas, docToView)
                if (!editing) drawCenterHandle(canvas, doc, g, docToView, paint.color)
            }
            GuideKind.NONE -> {}
        }
        canvas.restore()
    }

    /** Rasterraum → Dokument: Ursprung in der Mitte + Verschiebung, gedreht. */
    fun gridMatrix(doc: ArtDocument, g: GuideSettings, out: Matrix) {
        out.reset()
        out.setRotate(g.rotation)
        out.postTranslate(doc.width / 2f + g.offsetX, doc.height / 2f + g.offsetY)
    }

    /** Sichtbaren Bildschirmbereich (∩ Leinwand) in Rasterkoordinaten nach [visible]. */
    private fun visibleIn(toView: Matrix, canvas: Canvas, doc: ArtDocument): Boolean {
        val inv = invTmp
        if (!toView.invert(inv)) return false
        val cw = canvas.width.toFloat(); val ch = canvas.height.toFloat()
        corners[0] = 0f; corners[1] = 0f; corners[2] = cw; corners[3] = 0f
        corners[4] = cw; corners[5] = ch; corners[6] = 0f; corners[7] = ch
        inv.mapPoints(corners)
        visible.set(minOf(corners[0], corners[2], corners[4], corners[6]),
            minOf(corners[1], corners[3], corners[5], corners[7]),
            maxOf(corners[0], corners[2], corners[4], corners[6]),
            maxOf(corners[1], corners[3], corners[5], corners[7]))
        // Leinwand im Rasterraum
        val docRect = docRectTmp
        val toGrid = toGridTmp; gridToDoc.invert(toGrid)
        corners[0] = 0f; corners[1] = 0f; corners[2] = doc.width.toFloat(); corners[3] = 0f
        corners[4] = doc.width.toFloat(); corners[5] = doc.height.toFloat(); corners[6] = 0f; corners[7] = doc.height.toFloat()
        toGrid.mapPoints(corners)
        docRect.set(minOf(corners[0], corners[2], corners[4], corners[6]),
            minOf(corners[1], corners[3], corners[5], corners[7]),
            maxOf(corners[0], corners[2], corners[4], corners[6]),
            maxOf(corners[1], corners[3], corners[5], corners[7]))
        return visible.intersect(docRect)
    }

    private fun flush(canvas: Canvas, m: Matrix) {
        if (count > 0) {
            m.mapPoints(lines, 0, lines, 0, count / 2)
            canvas.drawLines(lines, 0, count, paint)
        }
        count = 0
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
        var x = ceil(visible.left / spacing) * spacing
        while (x <= visible.right) { add(x, visible.top, x, visible.bottom); x += spacing }
        val cx = visible.centerX(); val cy = visible.centerY()
        val half = hypot(visible.width(), visible.height()) / 2f + spacing
        for (angleDeg in intArrayOf(30, -30)) {
            val a = Math.toRadians(angleDeg.toDouble())
            val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
            val nx = -dy; val ny = dx
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
                val ox = cx + (c - cProj) * nx
                val oy = cy + (c - cProj) * ny
                add(ox - dx * half, oy - dy * half, ox + dx * half, oy + dy * half)
                k += 1f
            }
        }
    }

    /**
     * Perspektive: Strahlen aus jedem Fluchtpunkt (alle 5°), Horizontlinie durch Fluchtpunkt 1 (bzw. 1 und 2).
     * Bei einem Fluchtpunkt zusätzlich ein lockeres Raster aus Waagerechten/Senkrechten (Zentralperspektive).
     */
    private fun perspective(doc: ArtDocument, g: GuideSettings) {
        val vps = g.vanishingPoints
        if (vps.isEmpty()) return
        val len = hypot(doc.width.toFloat(), doc.height.toFloat()) * 3f
        for (vp in vps.take(3)) {
            for (i in 0 until RAYS) {
                val a = Math.PI * 2 * i / RAYS
                add(vp.x, vp.y, vp.x + cos(a).toFloat() * len, vp.y + sin(a).toFloat() * len)
            }
        }
        val h = Assist.horizonAngle(doc)
        val hx = cos(h).toFloat() * len; val hy = sin(h).toFloat() * len
        val o = vps[0]
        add(o.x - hx, o.y - hy, o.x + hx, o.y + hy)
        if (vps.size == 1) {
            // Waagerechte/Senkrechte im Rasterabstand (parallel zum Horizont bzw. senkrecht dazu)
            val step = g.gridSize.coerceAtLeast(20f) * 2f
            val vx = -sin(h).toFloat(); val vy = cos(h).toFloat()
            val n = (len / step).toInt().coerceAtMost(200)
            for (k in -n..n) {
                if (k == 0) continue
                val ox = o.x + vx * k * step; val oy = o.y + vy * k * step
                add(ox - hx, oy - hy, ox + hx, oy + hy)
                val px = o.x + cos(h).toFloat() * k * step; val py = o.y + sin(h).toFloat() * k * step
                add(px - vx * len, py - vy * len, px + vx * len, py + vy * len)
            }
        }
    }

    fun symmetryCenter(doc: ArtDocument, g: GuideSettings = doc.guides): Pair<Float, Float> =
        (if (g.symmetryCenterX.isNaN()) doc.width / 2f else g.symmetryCenterX) to
            (if (g.symmetryCenterY.isNaN()) doc.height / 2f else g.symmetryCenterY)

    private fun symmetry(doc: ArtDocument, g: GuideSettings) {
        val (cx, cy) = symmetryCenter(doc, g)
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
    private fun drawCenterHandle(canvas: Canvas, doc: ArtDocument, g: GuideSettings, m: Matrix, color: Int) {
        val (cx, cy) = symmetryCenter(doc, g)
        pt[0] = cx; pt[1] = cy
        m.mapPoints(pt)
        val assisted = doc.activeLayer?.assisted == true || g.assisted
        handlePaint.style = Paint.Style.FILL
        handlePaint.color = if (assisted) color or (0xFF shl 24) else Color.argb(160, 255, 255, 255)
        canvas.drawCircle(pt[0], pt[1], 6f * density / 2f + 3f, handlePaint)
        handlePaint.style = Paint.Style.STROKE
        handlePaint.strokeWidth = 1.5f
        handlePaint.color = Color.argb(200, 0, 0, 0)
        canvas.drawCircle(pt[0], pt[1], 6f * density / 2f + 3f, handlePaint)
    }
}
