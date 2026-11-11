package at.techgaisi.farbwerk.brush

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.guides.Symmetry
import at.techgaisi.farbwerk.model.InputPoint
import at.techgaisi.farbwerk.model.Layer
import at.techgaisi.farbwerk.model.Tool
import at.techgaisi.farbwerk.model.ToolInputHandler
import at.techgaisi.farbwerk.undo.PixelEdit
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * Pinsel-Engine für Malen, Wischen und Radieren.
 *
 * Malen/Radieren: Tupfer werden in einen Strich-Puffer (Dokumentgröße) gezeichnet. Während des Strichs zeigt
 * der Compositor über [Layer.drawOverride] Ebene + Puffer (mit Strichdeckkraft, Mischmodus, DST_OUT beim
 * Radieren, SRC_ATOP bei Alpha-Sperre). Beim Absetzen wird der Puffer einmal mit der eingestellten Deckkraft in die
 * Ebene gemischt – ein Strich überschreitet also nie die eingestellte Deckkraft.
 * Wischen: arbeitet direkt auf der Ebene (siehe [Smudger]).
 *
 * QuickShape: Nach dem Halten wird der Strich durch eine perfekte Form ersetzt; solange gehalten wird,
 * skaliert/dreht weiteres Ziehen die Form, ein zweiter Finger perfektioniert sie (Kreis, Quadrat, 15°).
 */
class BrushEngine(private val state: EditorState) : ToolInputHandler {
    /** Wenn gesetzt, überschreibt das Werkzeug (z. B. Radierer-Ende des Stifts). */
    var temporaryTool: Tool? = null

    private val renderer = StrokeRenderer()
    private val painter = DabPainter()
    private val smudger = Smudger(painter)

    private var buffer: Bitmap? = null
    private var bufferCanvas: Canvas? = null
    private var layerCanvas: Canvas? = null
    private var edit: PixelEdit? = null
    private var layer: Layer? = null
    private var strokeTool = Tool.PAINT
    private var active = false
    private var symm: List<Matrix> = emptyList()
    private var symmAngles = FloatArray(0)
    private val mValues = FloatArray(9)
    private val dirty = RectF()
    private val tmpRect = RectF()
    private val tmpPts = FloatArray(2)

    private val mergePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val plainPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val restorePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

    // QuickShape
    private var shape: QShape? = null
    private val shapeXs = FloatBuf()
    private val shapeYs = FloatBuf()
    private var holdX = 0f
    private var holdY = 0f
    private val anchor = FloatArray(2)
    private var shapePressure = 1f
    private var lastMoveTime = 0L
    private var movedSinceHold = false
    /** Bewegung (Dokumentpixel), ab der ein Ziehen nach der Erkennung als echte Bewegung zählt. */
    private var moveThreshold = 4f
    private var lastMoveX = 0f
    private var lastMoveY = 0f
    private var lastX = 0f
    private var lastY = 0f

    // Overlay
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val anchorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFF3D8BFF.toInt()
        pathEffect = DashPathEffect(floatArrayOf(6f, 5f), 0f)
    }

    // ------------------------------------------------------------ Tupfer-Ziele

    /** Malen/Radieren: in den Strich-Puffer, plus alle Symmetrie-Kopien. */
    private val paintSink = DabConsumer { x, y, d, asp, ang, a, c, f ->
        val bc = bufferCanvas ?: return@DabConsumer
        painter.draw(bc, x, y, d, asp, ang, a, c, f)
        addDirty(x, y, d, null)
        for (m in symm) {
            bc.save(); bc.concat(m)
            painter.draw(bc, x, y, d, asp, ang, a, c, f)
            bc.restore()
            addDirty(x, y, d, m)
        }
    }

    /** Wischen: direkt in der Ebene. */
    private val smudgeSink = DabConsumer { x, y, d, asp, ang, a, _, f ->
        val lc = layerCanvas ?: return@DabConsumer
        val l = layer ?: return@DabConsumer
        smudger.dab(0, lc, l.bitmap, x, y, d, asp, ang, a, f, dirty)
        for (i in symm.indices) {
            tmpPts[0] = x; tmpPts[1] = y
            symm[i].mapPoints(tmpPts)
            smudger.dab(i + 1, lc, l.bitmap, tmpPts[0], tmpPts[1], d, asp, ang + symmAngles[i], a, f, dirty)
        }
    }

    /** Ende-Verjüngung: nur Tupfer zeichnen, die den neu zu zeichnenden Bereich berühren. */
    private val replayRect = RectF()
    private val replaySink = DabConsumer { x, y, d, asp, ang, a, c, f ->
        val bc = bufferCanvas ?: return@DabConsumer
        val r = d * 0.75f + 2f
        tmpRect.set(x - r, y - r, x + r, y + r)
        if (RectF.intersects(tmpRect, replayRect)) painter.draw(bc, x, y, d, asp, ang, a, c, f)
        for (m in symm) {
            tmpRect.set(x - r, y - r, x + r, y + r); m.mapRect(tmpRect)
            if (RectF.intersects(tmpRect, replayRect)) {
                bc.save(); bc.concat(m); painter.draw(bc, x, y, d, asp, ang, a, c, f); bc.restore()
            }
        }
    }

    private val sampler = ColorSampler { x, y ->
        val l = layer ?: return@ColorSampler 0
        val ix = x.toInt(); val iy = y.toInt()
        if (ix < 0 || iy < 0 || ix >= l.width || iy >= l.height) 0 else l.bitmap.getPixel(ix, iy)
    }

    /** Live-Anzeige: Ebene + Strich-Puffer. Der Compositor kümmert sich um Ebenen-Deckkraft/Mischmodus. */
    private val override: (Canvas) -> Unit = { c ->
        val l = layer; val buf = buffer
        if (l != null && buf != null) {
            c.drawBitmap(l.bitmap, 0f, 0f, plainPaint)
            val sel = state.document?.selection?.path
            if (sel != null) { c.save(); c.clipPath(sel) }
            if (!dirty.isEmpty) {
                c.save(); c.clipRect(dirty)
                c.drawBitmap(buf, 0f, 0f, mergePaint)
                c.restore()
            }
            if (sel != null) c.restore()
        }
    }

    // ------------------------------------------------------------ ToolInputHandler

    override fun onStrokeStart(p: InputPoint) {
        reset()
        val doc = state.document ?: return
        val l = doc.activeLayer ?: return
        if (l.locked) { state.flash("Ebene gesperrt"); return }
        val tool = temporaryTool ?: state.tool
        if (tool != Tool.PAINT && tool != Tool.SMUDGE && tool != Tool.ERASE) return
        strokeTool = tool
        val settings = state.toolSettings[tool] ?: return
        val brush = state.brushLibrary.getOrDefault(settings.brushId, tool)
        state.brushLibrary.markUsed(brush.id)

        layer = l
        edit = state.beginPixelEdit(l)
        symm = Symmetry.matrices(doc)
        symmAngles = FloatArray(symm.size) { symmAngle(symm[it]) }
        dirty.setEmpty()

        val opacity = brush[BP.MIN_OPACITY] + (brush[BP.MAX_OPACITY] - brush[BP.MIN_OPACITY]) * settings.opacity.coerceIn(0f, 1f)
        val diameter = diameterFor(brush, settings.size)

        renderer.brush = brush
        renderer.diameter = diameter
        // "Starke Lasur" wendet die Deckkraft pro Tupfer an – nur beim Malen; sonst beim Zusammenführen
        renderer.opacity = if (tool == Tool.PAINT) opacity else 1f
        renderer.color = state.color
        renderer.secondaryColor = state.previousColor
        renderer.globalPressureSmoothing = state.prefs.pressureSmoothing
        renderer.globalMotionFilter = state.prefs.motionFilteringAmount
        renderer.viewScale = viewScale()
        renderer.sampler = if (tool == Tool.PAINT) sampler else null
        painter.setup(brush)

        when (tool) {
            Tool.SMUDGE -> {
                val lc = Canvas(l.bitmap)
                doc.selection?.path?.let { lc.clipPath(it) }
                layerCanvas = lc
                smudger.pickup = brush[BP.SMUDGE_PICKUP]
                smudger.alphaLock = l.alphaLock
                smudger.begin(diameter, 1 + symm.size)
                // Stärke = Deckkraft (Fluss wird beim Wischen auf die Stärke nicht angewandt)
                renderer.sink = DabConsumer { x, y, d, asp, ang, a, c, f ->
                    smudgeSink.dab(x, y, d, asp, ang, (a / max(brush[BP.FLOW], 0.01f)).coerceAtMost(1f) * opacity, c, f)
                }
            }
            else -> {
                ensureBuffer(doc.width, doc.height)
                val heavy = brush.int(BP.RENDER_MODE) == RenderMode.HEAVY_GLAZE && tool == Tool.PAINT
                mergePaint.alpha = if (heavy) 255 else (opacity * 255f + 0.5f).toInt().coerceIn(0, 255)
                mergePaint.blendMode = when {
                    tool == Tool.ERASE -> BlendMode.DST_OUT
                    l.alphaLock -> BlendMode.SRC_ATOP
                    else -> BrushBlend.mode(brush.int(BP.BLEND))
                }
                renderer.sink = paintSink
                l.drawOverride = override
            }
        }
        active = true
        lastX = p.x; lastY = p.y
        renderer.begin(p.x, p.y, p.pressure, p.tilt, p.orientation, p.time, p.isStylus)
    }

    override fun onStrokeMove(points: List<InputPoint>) {
        if (!active) return
        val sh = shape
        if (sh != null) {
            val p = points.last()
            if (hypot(p.x - lastMoveX, p.y - lastMoveY) > moveThreshold) {
                movedSinceHold = true
                lastMoveTime = SystemClock.uptimeMillis()
                lastMoveX = p.x; lastMoveY = p.y
            }
            lastX = p.x; lastY = p.y
            transformShape(sh, p.x, p.y)
            renderShape(sh)
            return
        }
        for (i in points.indices) {
            val p = points[i]
            renderer.add(p.x, p.y, p.pressure, p.tilt, p.orientation, p.time)
        }
        val last = points.last()
        lastX = last.x; lastY = last.y
    }

    override fun onStrokeEnd(p: InputPoint) {
        if (!active) { reset(); return }
        if (shape == null) {
            renderer.add(p.x, p.y, p.pressure, p.tilt, p.orientation, p.time)
            renderer.end()
            if (strokeTool != Tool.SMUDGE && renderer.needsEndTaper()) applyEndTaper()
        }
        finish()
    }

    override fun onStrokeCancel() {
        if (active) {
            layer?.drawOverride = null
            clearBuffer()
            edit?.cancel()
        }
        reset()
        state.invalidate()
    }

    override fun onHoldStill(p: InputPoint) {
        if (!active || !state.prefs.quickShapeEnabled) return
        val sh = shape
        if (sh == null) {
            val det = QuickShapeDetector.detect(renderer.pathX, renderer.pathY) ?: return
            shape = det
            shapePressure = renderer.meanPressure.coerceIn(0.05f, 1f)
            holdX = p.x; holdY = p.y
            lastX = p.x; lastY = p.y
            lastMoveX = p.x; lastMoveY = p.y
            moveThreshold = 6f / viewScale()
            movedSinceHold = false
            lastMoveTime = SystemClock.uptimeMillis()
            renderShape(det)
            state.flash(det.kind.label)
            return
        }
        // Bereits erkannt: zweiter Finger (oder erneutes Halten ohne Ziehen) perfektioniert die Form.
        // Ein Halten nach dem Ziehen (Timer der Leinwand) wird ignoriert.
        val sinceMove = SystemClock.uptimeMillis() - lastMoveTime
        if (movedSinceHold && sinceMove >= state.prefs.quickShapeDelayMs * 0.8f) {
            movedSinceHold = false
            return
        }
        val msg = sh.perfect() ?: return
        renderShape(sh)
        state.flash(msg)
    }

    override fun drawOverlay(canvas: Canvas, docToView: Matrix) {
        if (!active) return
        val sh = shape
        if (sh != null) {
            sh.anchor(anchor)
            tmpPts[0] = anchor[0]; tmpPts[1] = anchor[1]
            sh.transform.mapPoints(tmpPts)
            docToView.mapPoints(tmpPts)
            canvas.drawCircle(tmpPts[0], tmpPts[1], 7f, anchorPaint)
            return
        }
        if (!state.prefs.brushCursor) return
        tmpPts[0] = renderer.headX; tmpPts[1] = renderer.headY
        docToView.mapPoints(tmpPts)
        docToView.getValues(mValues)
        val r = renderer.headDiameter / 2f * hypot(mValues[Matrix.MSCALE_X], mValues[Matrix.MSKEW_Y])
        if (r < 3f) return
        cursorPaint.color = 0x99000000.toInt(); cursorPaint.strokeWidth = 2.5f
        canvas.drawCircle(tmpPts[0], tmpPts[1], r, cursorPaint)
        cursorPaint.color = 0xCCFFFFFF.toInt(); cursorPaint.strokeWidth = 1f
        canvas.drawCircle(tmpPts[0], tmpPts[1], r, cursorPaint)
    }

    // ------------------------------------------------------------ öffentliche Hilfen

    /** Pinseldurchmesser in Dokumentpixeln für einen Größe-Wert 0..1. */
    fun diameterFor(brush: Brush, size: Float): Float {
        val minS = brush[BP.MIN_SIZE]
        val maxS = max(brush[BP.MAX_SIZE], minS)
        val s = size.coerceIn(0f, 1f)
        var d = minS + (maxS - minS) * s * s
        // Dynamische Pinselskalierung aus: Größe bezieht sich auf den Bildschirm, nicht auf die Leinwand
        if (!state.prefs.dynamicBrushScaling) d /= viewScale().coerceAtLeast(0.01f)
        return d
    }

    /** Ist gerade ein Strich aktiv? */
    val isStroking: Boolean get() = active

    // ------------------------------------------------------------ intern

    private fun finish() {
        val l = layer
        val e = edit
        if (l != null && e != null) {
            if (strokeTool != Tool.SMUDGE) {
                val buf = buffer
                if (buf != null && !dirty.isEmpty) {
                    val c = Canvas(l.bitmap)
                    c.save()
                    c.clipRect(dirty)
                    c.drawBitmap(buf, 0f, 0f, mergePaint)
                    c.restore()
                }
                l.drawOverride = null
            }
            val r = Rect(floor(dirty.left).toInt(), floor(dirty.top).toInt(), ceil(dirty.right).toInt(), ceil(dirty.bottom).toInt())
            clearBuffer()
            if (dirty.isEmpty) e.cancel()
            else e.commit(r, when (strokeTool) { Tool.SMUDGE -> "Wischen"; Tool.ERASE -> "Radieren"; else -> "Pinselstrich" })
        }
        reset()
    }

    private fun reset() {
        active = false
        layer = null
        edit = null
        layerCanvas = null
        shape = null
        symm = emptyList()
        renderer.sink = null
    }

    private fun ensureBuffer(w: Int, h: Int) {
        val b = buffer
        if (b == null || b.width != w || b.height != h) {
            b?.recycle()
            val nb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            buffer = nb
            bufferCanvas = Canvas(nb)
        }
    }

    private fun clearBuffer() {
        val bc = bufferCanvas ?: return
        if (!dirty.isEmpty) bc.drawRect(dirty, clearPaint)
    }

    private fun addDirty(x: Float, y: Float, d: Float, m: Matrix?) {
        val r = d * 0.75f + 2f
        tmpRect.set(x - r, y - r, x + r, y + r)
        m?.mapRect(tmpRect)
        dirty.union(tmpRect)
    }

    /** Drehwinkel einer Symmetrie-Matrix (für die Ausrichtung beim Wischen). */
    private fun symmAngle(m: Matrix): Float {
        m.getValues(mValues)
        return atan2(mValues[Matrix.MSKEW_Y], mValues[Matrix.MSCALE_X])
    }

    private fun viewScale(): Float {
        val m = state.canvasView?.docToViewMatrix() ?: return 1f
        m.getValues(mValues)
        return hypot(mValues[Matrix.MSCALE_X], mValues[Matrix.MSKEW_Y]).coerceAtLeast(0.001f)
    }

    /** Ende des Strichs verjüngen: betroffenen Bereich leeren und alle berührenden Tupfer neu zeichnen. */
    private fun applyEndTaper() {
        val bc = bufferCanvas ?: return
        val l = renderer.dabs
        val from = renderer.tailStartIndex()
        val tail = RectF()
        for (i in from until l.size) {
            val r = l.diam[i] * 0.75f + 2f
            tail.union(l.x[i] - r, l.y[i] - r, l.x[i] + r, l.y[i] + r)
        }
        if (tail.isEmpty) return
        replayRect.set(tail)
        for (m in symm) { tmpRect.set(tail); m.mapRect(tmpRect); replayRect.union(tmpRect) }
        bc.save()
        bc.clipRect(replayRect)
        bc.drawRect(replayRect, clearPaint)
        renderer.replayWithEndTaper(replaySink)
        bc.restore()
    }

    // ------------------------------------------------------------ QuickShape

    /** Skalieren/Drehen um den Anker: Verhältnis von aktuellem zu ursprünglichem Haltepunkt. */
    private fun transformShape(sh: QShape, x: Float, y: Float) {
        sh.anchor(anchor)
        val ax = anchor[0]; val ay = anchor[1]
        val d0 = hypot(holdX - ax, holdY - ay)
        val d1 = hypot(x - ax, y - ay)
        sh.transform.reset()
        if (d0 < 4f) {
            // Haltepunkt liegt am Anker (z. B. Kreis, Start=Ende): nur verschieben ist nicht gewünscht → Skalierung über Abstand
            return
        }
        val s = (d1 / d0).coerceIn(0.02f, 50f)
        val rot = Math.toDegrees((atan2(y - ay, x - ax) - atan2(holdY - ay, holdX - ax)).toDouble()).toFloat()
        sh.transform.postScale(s, s, ax, ay)
        sh.transform.postRotate(rot, ax, ay)
    }

    /** Ersetzt den bisherigen Strich durch die Form. */
    private fun renderShape(sh: QShape) {
        val l = layer ?: return
        val n = sh.sample(shapeXs, shapeYs)
        if (n < 2) return
        if (strokeTool == Tool.SMUDGE) {
            // Ebene im bisher veränderten Bereich wiederherstellen
            val e = edit ?: return
            if (!dirty.isEmpty) {
                val c = Canvas(l.bitmap)
                c.save(); c.clipRect(dirty)
                c.drawBitmap(e.original, 0f, 0f, restorePaint)
                c.restore()
            }
            smudger.begin(renderer.diameter, 1 + symm.size)
        } else {
            clearBuffer()
        }
        // Bereich bleibt als Vereinigung erhalten (für Undo/Puffer-Löschen), neu zeichnen
        renderer.renderPath(shapeXs.data, shapeYs.data, n, sh.closed, shapePressure)
        state.canvasView?.postInvalidateOnAnimation()
    }

    /** Gibt Speicher frei (z. B. beim Schließen eines Dokuments). */
    fun release() {
        buffer?.recycle(); buffer = null; bufferCanvas = null
        painter.release()
        smudger.release()
    }
}
