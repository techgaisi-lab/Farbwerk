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
import at.techgaisi.farbwerk.undo.LayerPixelsAction
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
 *
 * Live = Ergebnis: Endgültige Tupfer werden fest in den Puffer gezeichnet. Das noch veränderliche Strichende
 * (Ende-Verjüngung, bei harten Spitzen ein kurzes Stück für saubere Kanten) und die Bewegungsvorhersage werden vor
 * jedem Bild neu in den Puffer gezeichnet ([refreshLive]) – der darunterliegende Pufferinhalt wird vorher gesichert
 * und danach wiederhergestellt ([TailStash]). Die Live-Ansicht zeigt so schon während des Malens genau den Strich,
 * der beim Absetzen entsteht (inkl. verjüngter Spitze, gleiche Deckkraft), statt nachträglich umzuzeichnen.
 * Wischen: arbeitet direkt auf der Ebene (siehe [Smudger]).
 *
 * QuickShape: Nach dem Halten wird der Strich durch eine perfekte Form ersetzt; solange gehalten wird,
 * skaliert/dreht weiteres Ziehen die Form, ein zweiter Finger perfektioniert sie (Kreis, Quadrat, 15°).
 */
class BrushEngine(private val state: EditorState) : ToolInputHandler, QuickShapeEditor.Host {
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
    /**
     * Vom Strich berührte Kacheln ([PixelEdit.TILE], Zeile · Spalten + Spalte): Nur dort enthält der Puffer etwas.
     * Einmischen, Undo-Sicherung und Leeren beschränken sich darauf statt auf das ganze umschließende Rechteck.
     */
    private val touched = java.util.BitSet()
    private val tileRect = Rect()
    private val tmpRect = RectF()
    private val tmpPts = FloatArray(2)

    private val mergePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val plainPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val restorePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    /** Alpha-Schwelle: Puffer wird mit diesem Filter in eine Ebene (mit [mergePaint]) gezeichnet. */
    private val thresholdPaint = Paint()
    private var thresholdOn = false
    private var strokeBrush: Brush? = null

    // Zeichenassistent (Raster/Isometrie/Perspektive)
    private val assist = AssistBridge()
    private val apt = FloatArray(2)

    // QuickShape "Form bearbeiten"
    private class LastShape(
        val shape: EditShape, val layer: Layer, val docId: String, val r0: Rect, val pre: Bitmap,
        val brush: Brush, val tool: Tool, val diameter: Float, val opacity: Float, val color: Int, val secondary: Int,
        val pressure: Float, val symm: List<Matrix>, val mergeAlpha: Int, val blend: BlendMode?, val threshold: Boolean,
        val selPath: android.graphics.Path?,
    ) { var undoVersion = 0 }
    private var lastShape: LastShape? = null
    private var editor: QuickShapeEditor? = null
    private var editAfter: Bitmap? = null

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

    // Bewegungsvorhersage (nur Vorschau, wird nie festgeschrieben)
    private var predN = 0
    private val predX = FloatArray(MAX_PREDICTED)
    private val predY = FloatArray(MAX_PREDICTED)
    /** Fortsetzung des Strichs für die Live-Ansicht: aktuelle Kopfposition + Vorhersage. */
    private val extX = FloatArray(MAX_PREDICTED + 1)
    private val extY = FloatArray(MAX_PREDICTED + 1)

    // Live-Strichende (siehe Klassenbeschreibung)
    /** Tupfer werden nicht sofort gezeichnet, sondern von [refreshLive] (Malen/Radieren während eines Strichs). */
    private var deferDabs = false
    /** Anzahl der Tupfer, die endgültig im Puffer stehen. */
    private var committed = 0
    /** Mindestlänge eines festzuschreibenden Abschnitts (harte Spitzen: weniger Nahtstellen der Vereinigung). */
    private var chunkLen = 0f
    /** Neue Eingabe/Vorhersage seit dem letzten [refreshLive]. */
    private var liveStale = false
    private val stash = TailStash()
    /** Seit der letzten Anzeige veränderter Pufferbereich (für [tiles]). */
    private val changed = RectF()
    /** Anzeige-Kacheln des Puffers: nur geänderte Kacheln müssen pro Bild neu zur GPU. */
    private val tiles = BufferTiles()
    private val tailBounds = RectF()
    private val stashRect = Rect()
    // Nahtstelle zwischen zwei festgeschriebenen Abschnitten (nur vereinigt gezeichnete harte Spitzen, ohne Symmetrie)
    private var junction = TailStash()
    private var jNext = TailStash()
    private var jValid = false
    private val jRect = Rect()
    private val pendingJ = Rect()
    /** Index des ersten Tupfers des Abschnitts, an dessen Ende [jRect] liegt. */
    private var jFrom = 0
    /** Halbe Kantenlänge des Nahtbereichs (Dokumentpixel). */
    private var jReach = 0f

    // Overlay
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val anchorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFF3D8BFF.toInt()
        pathEffect = DashPathEffect(floatArrayOf(6f, 5f), 0f)
    }

    // ------------------------------------------------------------ Tupfer-Ziele

    /** Malen/Radieren: in den Strich-Puffer, plus alle Symmetrie-Kopien. */
    private val drawSink = DabConsumer { x, y, d, asp, ang, a, c, f ->
        val bc = bufferCanvas ?: return@DabConsumer
        painter.draw(bc, x, y, d, asp, ang, a, c, f)
        addDirty(x, y, d, null)
        for (i in symm.indices) {
            val m = symm[i]
            painter.draw(bc, x, y, d, asp, ang, a, c, f, m, i + 1)
            addDirty(x, y, d, m)
        }
    }

    /** Ziel des Renderers: während eines Strichs nur aufzeichnen ([refreshLive] zeichnet), sonst sofort zeichnen. */
    private val paintSink = DabConsumer { x, y, d, asp, ang, a, c, f ->
        if (!deferDabs) drawSink.dab(x, y, d, asp, ang, a, c, f)
    }

    /** Sammelt nur die Ausdehnung (für die Sicherung des Pufferbereichs unter dem Strichende). */
    private val boundsSink = DabConsumer { x, y, d, _, _, _, _, _ ->
        val r = d * 0.75f + 2f
        tailBounds.union(x - r, y - r, x + r, y + r)
    }

    /** Wischen: direkt in der Ebene (Originalpixel werden vor jedem Tupfer kachelweise gesichert, siehe [protect]). */
    private val smudgeSink = DabConsumer { x, y, d, asp, ang, a, _, f ->
        val lc = layerCanvas ?: return@DabConsumer
        val l = layer ?: return@DabConsumer
        protect(x, y, d)
        smudger.dab(0, lc, l.bitmap, x, y, d, asp, ang, a, f, dirty)
        for (i in symm.indices) {
            tmpPts[0] = x; tmpPts[1] = y
            symm[i].mapPoints(tmpPts)
            protect(tmpPts[0], tmpPts[1], d)
            smudger.dab(i + 1, lc, l.bitmap, tmpPts[0], tmpPts[1], d, asp, ang + symmAngles[i], a, f, dirty)
        }
    }

    /** Sichert (Undo) den Bereich, den ein Wisch-Tupfer an ([x], [y]) mit Durchmesser [d] verändern kann. */
    private fun protect(x: Float, y: Float, d: Float) {
        val h = d / 2f
        edit?.protect(floor(x - h).toInt() - 2, floor(y - h).toInt() - 2, ceil(x + h).toInt() + 2, ceil(y + h).toInt() + 2)
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
            if (liveStale) { liveStale = false; if (active && deferDabs && shape == null) refreshLive(final = false) }
            c.drawBitmap(l.bitmap, 0f, 0f, plainPaint)
            val sel = state.document?.selection?.path
            if (sel != null) { c.save(); c.clipPath(sel) }
            if (!dirty.isEmpty) {
                c.save(); c.clipRect(dirty)
                drawBufferLive(c, buf)
                c.restore()
            }
            if (sel != null) c.restore()
        }
    }

    // ------------------------------------------------------------ ToolInputHandler

    override fun onStrokeStart(p: InputPoint) {
        reset()
        // Weitermalen verwirft die Schaltfläche "Form bearbeiten"
        lastShape = null
        if (state.flashActionLabel == EDIT_SHAPE_LABEL) { state.flashMessage = null; state.flashActionLabel = null }
        thresholdOn = false
        val doc = state.document ?: return
        val l = at.techgaisi.farbwerk.model.LayerOps.paintTarget(state) ?: return
        if (l.locked) { state.flash("Ebene gesperrt"); return }
        val tool = temporaryTool ?: state.tool
        if (tool != Tool.PAINT && tool != Tool.SMUDGE && tool != Tool.ERASE) return
        strokeTool = tool
        val settings = state.toolSettings[tool] ?: return
        val brush = state.brushLibrary.getOrDefault(settings.brushId, tool)
        state.brushLibrary.markUsed(brush.id)

        layer = l
        // Keine Kopie der ganzen Ebene beim Aufsetzen (bei 2×-Leinwänden ~90 MB): Malen/Radieren verändert die
        // Ebene erst beim Absetzen (Bereich wird dann gesichert), Wischen sichert vor jedem Tupfer kachelweise.
        edit = state.beginLazyPixelEdit(l)
        symm = Symmetry.matrices(doc)
        symmAngles = FloatArray(symm.size) { symmAngle(symm[it]) }
        resetDirty()

        val opacity = brush[BP.MIN_OPACITY] + (brush[BP.MAX_OPACITY] - brush[BP.MIN_OPACITY]) * settings.opacity.coerceIn(0f, 1f)
        val diameter = diameterFor(brush, settings.size)

        renderer.brush = brush
        renderer.diameter = diameter
        // "Starke Lasur" wendet die Deckkraft pro Tupfer an – nur beim Malen; sonst beim Zusammenführen
        renderer.opacity = if (tool == Tool.PAINT) opacity else 1f
        renderer.color = at.techgaisi.farbwerk.model.LayerOps.paintColor(state, state.color)
        renderer.secondaryColor = state.secondaryColor
        renderer.viewRotation = viewRotation()
        strokeBrush = brush
        renderer.globalPressureSmoothing = state.prefs.pressureSmoothing
        renderer.globalMotionFilter = state.prefs.motionFilteringAmount
        renderer.viewScale = viewScaleInternal()
        renderer.sampler = if (tool == Tool.PAINT) sampler else null
        renderer.detailScale = doc.detailScale
        painter.setup(brush, doc.detailScale)
        painter.union = tool != Tool.SMUDGE
        deferDabs = tool != Tool.SMUDGE
        committed = 0
        liveStale = false
        stash.discard()
        // Harte, deckende Spitzen werden vereinigt gezeichnet (saubere Kanten); längere Abschnitte = weniger Nähte
        val dMax = diameter * (1f + max(0f, brush[BP.TILT_SIZE]))
        jReach = 0.8f * dMax + 3f
        jValid = false
        chunkLen = if (deferDabs && painter.unionCapable && brush[BP.FLOW] >= 0.999f &&
            brush[BP.PRESSURE_OPACITY] <= 0f && brush[BP.PRESSURE_FLOW] <= 0f
        ) max(3f * dMax, 48f) else 0f

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
                thresholdOn = brush.on(BP.ALPHA_THRESHOLD)
                if (thresholdOn) thresholdPaint.colorFilter = StrokeEffects.thresholdFilter(brush[BP.ALPHA_THRESHOLD_VALUE])
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
        assist.begin(doc, p.x, p.y)
        renderer.begin(p.x, p.y, p.pressure, p.tilt, p.orientation, p.time, p.isStylus)
    }

    override fun onStrokeMove(points: List<InputPoint>) {
        if (!active) return
        // Echte Punkte sind da → alte Vorhersage verwerfen
        predN = 0
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
            assist.map(p.x, p.y, apt)
            renderer.add(apt[0], apt[1], p.pressure, p.tilt, p.orientation, p.time)
        }
        val last = points.last()
        lastX = last.x; lastY = last.y
        liveStale = true
    }

    override fun onStrokeEnd(p: InputPoint) {
        predN = 0
        if (!active) { reset(); return }
        if (shape == null) {
            assist.map(p.x, p.y, apt)
            renderer.add(apt[0], apt[1], p.pressure, p.tilt, p.orientation, p.time)
            renderer.end()
            if (deferDabs) refreshLive(final = true)
        }
        finish()
    }

    override fun onStrokeCancel() {
        if (active) {
            layer?.drawOverride = null
            painter.flush()
            stash.discard()
            clearBuffer()
            edit?.cancel()
        }
        reset()
        state.invalidate()
    }

    override fun onPredicted(points: List<InputPoint>) {
        val had = predN > 0
        predN = 0
        if (active && shape == null && strokeTool != Tool.SMUDGE && !assist.active && points.isNotEmpty() && predictionAllowed()) {
            val n = minOf(points.size, MAX_PREDICTED)
            for (i in 0 until n) { predX[i] = points[i].x; predY[i] = points[i].y }
            predN = n
        }
        if (had || predN > 0) {
            liveStale = true
            state.canvasView?.postInvalidateOnAnimation()
        }
    }

    /**
     * Vorhersage nur bei wenig Glättung: Starke StreamLine/Stabilisierung zieht den Strich absichtlich nach –
     * eine Vorschau an der Rohposition würde dort nur springen.
     */
    private fun predictionAllowed(): Boolean {
        val b = renderer.brush
        return b[BP.STREAMLINE] <= 0.6f && b[BP.STABILIZE] <= 0.05f && b[BP.MOTION_FILTER] <= 0.05f &&
            state.prefs.motionPrediction && state.prefs.motionFilteringAmount <= 0.05f
    }

    /**
     * Aktualisiert das veränderliche Strichende im Puffer (siehe Klassenbeschreibung):
     *  1. vorher gezeichnetes Strichende wiederherstellen (gesicherter Pufferinhalt),
     *  2. Tupfer, die sich nicht mehr ändern können (außerhalb der Ende-Verjüngung), fest zeichnen,
     *  3. Pufferbereich unter dem neuen Strichende sichern und Strichende + Fortsetzung (Kopf, Vorhersage) zeichnen.
     * [final] (Absetzen): restliche Tupfer mit der endgültigen Verjüngung fest zeichnen, keine Vorhersage.
     */
    private fun refreshLive(final: Boolean) {
        val bc = bufferCanvas ?: return
        val buf = buffer ?: return
        painter.flush()
        stash.restore(bc, changed)
        val l = renderer.dabs
        val n = l.size
        val total = renderer.totalLength
        if (final) {
            drawSpan(committed, n, total, renderer.needsEndTaper(), 0)
            committed = n
            return
        }
        // 2. endgültige Tupfer festschreiben
        val limit = total - renderer.liveEndTaperLength
        var f = committed
        while (f < n && l.dist[f] <= limit) f++
        if (f > committed && (chunkLen <= 0f || l.dist[f - 1] - l.dist[committed] >= chunkLen)) {
            commitSpan(f, total)
        }
        // 3. Strichende + Fortsetzung (Kopf + Vorhersage)
        var en = 0
        if (predN > 0 && n > 0) {
            extX[0] = renderer.headX; extY[0] = renderer.headY; en = 1
            for (k in 0 until predN) { extX[en] = predX[k]; extY[en] = predY[k]; en++ }
        }
        val liveTotal = if (en > 0) max(total, renderer.lastDabDist + renderer.extensionLength(extX, extY, en)) else total
        val taper = renderer.liveEndTaperLength > 0f
        if (committed >= n && en == 0) return
        tailBounds.setEmpty()
        renderer.emitTail(committed, liveTotal, taper, boundsSink, extX, extY, en)
        if (tailBounds.isEmpty) return
        stashRegion(buf, tailBounds, null)
        for (m in symm) stashRegion(buf, tailBounds, m)
        if (jValid) stash.save(buf, jRect)
        drawSpan(committed, n, liveTotal, taper, en)
    }

    /**
     * Schreibt die Tupfer [committed]..[to] fest. Bei vereinigt gezeichneten harten Spitzen wird vorher der
     * Pufferzustand um das Abschnittsende gesichert ([junction]), damit der nächste Abschnitt dort nahtlos
     * anschließen kann (siehe [drawSpan]).
     */
    private fun commitSpan(to: Int, total: Float) {
        val buf = buffer ?: return
        val bc = bufferCanvas ?: return
        val l = renderer.dabs
        // Nahtstelle am Ende dieses Abschnitts: Zustand VOR dem Zeichnen sichern
        var newJ = false
        if (chunkLen > 0f && symm.isEmpty() && to > 0) {
            val x = l.x[to - 1]; val y = l.y[to - 1]
            stashRect.set(floor(x - jReach).toInt(), floor(y - jReach).toInt(), ceil(x + jReach).toInt(), ceil(y + jReach).toInt())
            newJ = stashRect.intersect(0, 0, buf.width, buf.height) && !(jValid && Rect.intersects(stashRect, jRect))
        }
        if (jValid) junction.peek(bc, changed)
        val oldValid = jValid
        if (newJ) {
            // alter Nahtbereich liegt (falls vorhanden) außerhalb → aktueller Inhalt = Zustand vor diesem Abschnitt
            pendingJ.set(stashRect)
            jNext.discard(); jNext.save(buf, stashRect)
        }
        jValid = oldValid
        drawSpan(committed, to, total, false, 0, peeked = true)
        jFrom = committed
        committed = to
        // neue Nahtstelle übernehmen
        jValid = newJ
        if (newJ) { jRect.set(pendingJ); val t = junction; junction = jNext; jNext = t }
    }

    /**
     * Zeichnet Tupfer [from]..[to] (+ Fortsetzung mit [en] Punkten) in den Puffer. Nahtreparatur: Bei vereinigt
     * gezeichneten harten Spitzen überlappen sich zwei getrennt gefüllte Abschnitte an der Grenze – ihre
     * Kantenglättung würde sich dort addieren (dunklere Kantenpixel). Im (pixelgenauen) Nahtbereich wird daher
     * der Zustand vor dem vorigen Abschnitt eingesetzt und die Vereinigung beider Abschnitte gezeichnet; außerhalb
     * nur der neue Abschnitt.
     */
    private fun drawSpan(from: Int, to: Int, total: Float, taper: Boolean, en: Int, peeked: Boolean = false) {
        val bc = bufferCanvas ?: return
        if (!jValid) {
            renderer.emitTail(from, total, taper, drawSink, extX, extY, en, to)
            painter.flush()
            return
        }
        if (!peeked) junction.peek(bc, changed)
        // Tupfer des vorigen Abschnitts, die den Nahtbereich erreichen können
        val l = renderer.dabs
        var a0 = from
        val reach = 2f * jReach
        while (a0 > jFrom && a0 - 1 < l.size && l.dist[from - 1] - l.dist[a0 - 1] <= reach) a0--
        bc.save(); bc.clipRect(jRect)
        renderer.emitTail(a0, total, taper, drawSink, extX, extY, en, to)
        painter.flush()
        bc.restore()
        bc.save(); bc.clipOutRect(jRect)
        renderer.emitTail(from, total, taper, drawSink, extX, extY, en, to)
        painter.flush()
        bc.restore()
    }

    private fun stashRegion(buf: Bitmap, r: RectF, m: Matrix?) {
        tmpRect.set(r)
        m?.mapRect(tmpRect)
        stashRect.set(floor(tmpRect.left).toInt() - 1, floor(tmpRect.top).toInt() - 1, ceil(tmpRect.right).toInt() + 1, ceil(tmpRect.bottom).toInt() + 1)
        if (!stashRect.intersect(0, 0, buf.width, buf.height)) return
        stash.save(buf, stashRect)
    }

    override fun onHoldStill(p: InputPoint) {
        if (!active || !state.prefs.quickShapeEnabled) return
        val sh = shape
        if (sh == null) {
            val det = QuickShapeDetector.detect(renderer.pathX, renderer.pathY, state.document?.detailScale ?: 1f) ?: return
            shape = det
            shapePressure = renderer.meanPressure.coerceIn(0.05f, 1f)
            holdX = p.x; holdY = p.y
            lastX = p.x; lastY = p.y
            lastMoveX = p.x; lastMoveY = p.y
            moveThreshold = 6f / viewScaleInternal()
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
        // Pinselcursor beim Malen nur, wenn eingestellt ("Cursor anzeigen": beim Malen bzw. beides)
        if (!state.prefs.brushCursor || state.prefs.cursorVisibility == 0) return
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

    /**
     * Pinseldurchmesser in Dokumentpixeln für einen Größe-Wert 0..1. Mit der Feinberechnung des Dokuments
     * multipliziert – auf einer 2×-Leinwand ist der Pinsel physisch gleich groß wie auf einer 1×-Leinwand.
     */
    fun diameterFor(brush: Brush, size: Float): Float {
        val minS = brush[BP.MIN_SIZE]
        val maxS = max(brush[BP.MAX_SIZE], minS)
        val s = size.coerceIn(0f, 1f)
        var d = minS + (maxS - minS) * s * s
        // Dynamische Pinselskalierung aus: Größe bezieht sich auf den Bildschirm, nicht auf die Leinwand
        // (dann ist die Feinberechnung über den Zoom bereits enthalten)
        if (!state.prefs.dynamicBrushScaling) d /= viewScaleInternal().coerceAtLeast(0.01f)
        else d *= state.document?.detailScale ?: 1f
        return d
    }

    /**
     * Pinsel, mit dem [tool] gerade malen würde (für die Schwebevorschau des Stifts). null für Werkzeuge
     * ohne Pinsel.
     */
    fun brushFor(tool: Tool): Brush? {
        if (tool != Tool.PAINT && tool != Tool.SMUDGE && tool != Tool.ERASE) return null
        val settings = state.toolSettings[tool] ?: return null
        return state.brushLibrary.getOrDefault(settings.brushId, tool)
    }

    /** Ist gerade ein Strich aktiv? */
    val isStroking: Boolean get() = active

    // ------------------------------------------------------------ intern

    private fun finish() {
        val l = layer
        val e = edit
        val sh = shape
        if (l != null && e != null) {
            val r = Rect(floor(dirty.left).toInt(), floor(dirty.top).toInt(), ceil(dirty.right).toInt(), ceil(dirty.bottom).toInt())
            r.intersect(0, 0, l.width, l.height)
            if (strokeTool != Tool.SMUDGE) {
                val buf = buffer
                val b = strokeBrush
                if (buf != null && !dirty.isEmpty) {
                    if (b != null && strokeTool == Tool.PAINT && StrokeEffects.needsPostPass(b)) {
                        StrokeEffects.apply(buf, dirty, b, renderer.diameter)
                    }
                    // Originalpixel der berührten Kacheln sichern, bevor der Puffer in die Ebene gemischt wird
                    forEachTouchedTile { tr -> e.protect(tr.left, tr.top, tr.right, tr.bottom) }
                    mergeBuffer(l, buf, state.document?.selection?.path)
                }
                l.drawOverride = null
            }
            // QuickShape: Zustand für "Form bearbeiten" merken (nicht beim Wischen)
            val doc = state.document
            val b = strokeBrush
            val remember = sh != null && strokeTool != Tool.SMUDGE && !dirty.isEmpty && doc != null && b != null && !r.isEmpty
            val pre = if (remember) e.originalRegion(r) else null
            clearBuffer()
            if (dirty.isEmpty) e.cancel()
            else e.commit(r, if (sh != null) "QuickShape" else when (strokeTool) { Tool.SMUDGE -> "Wischen"; Tool.ERASE -> "Radieren"; else -> "Pinselstrich" })
            if (remember && pre != null && sh != null && doc != null && b != null) {
                val ls = LastShape(
                    EditShape.from(sh), l, doc.id, Rect(r), pre, b, strokeTool, renderer.diameter, renderer.opacity,
                    renderer.color, renderer.secondaryColor, shapePressure, symm, mergePaint.alpha, mergePaint.blendMode,
                    thresholdOn, doc.selection?.path?.let { android.graphics.Path(it) },
                )
                ls.undoVersion = state.undo.version
                lastShape = ls
                val label = EditShape.from(sh).title + " erstellt"
                state.flash(label, EDIT_SHAPE_LABEL) { beginShapeEdit() }
            }
        }
        reset()
    }

    /**
     * Live-Anzeige des Strich-Puffers: über [tiles] statt der dokumentgroßen Bitmap – sonst müsste die ganze
     * Pufferbitmap bei jedem Bild neu zur GPU übertragen werden (bei 2×-Leinwänden ~90 MB pro Bild).
     */
    private fun drawBufferLive(c: Canvas, buf: Bitmap) {
        tiles.sync(buf, changed)
        changed.setEmpty()
        if (thresholdOn) {
            val sc = c.saveLayer(null, mergePaint)
            tiles.draw(c, dirty, thresholdPaint)
            c.restoreToCount(sc)
        } else tiles.draw(c, dirty, mergePaint)
    }

    /** Zeichnet den Strich-Puffer mit Strichdeckkraft/Mischmodus (und ggf. Alpha-Schwelle). */
    private fun drawBuffer(c: Canvas, buf: Bitmap) {
        if (thresholdOn) {
            val sc = c.saveLayer(null, mergePaint)
            c.drawBitmap(buf, 0f, 0f, thresholdPaint)
            c.restoreToCount(sc)
        } else c.drawBitmap(buf, 0f, 0f, mergePaint)
    }

    /**
     * Mischt den Puffer in die Ebene – je berührter Kachel (außerhalb ist der Puffer leer, und ein leerer Puffer
     * verändert die Ebene in keinem der Mischmodi). Mit Auswahl in einem Durchgang wie bisher: Die Kantenglättung
     * des Auswahlpfads als Clip hängt in Skia vom Clip-Rechteck ab und wäre an Kachelgrenzen minimal anders.
     */
    private fun mergeBuffer(l: Layer, buf: Bitmap, sel: android.graphics.Path?) {
        val c = Canvas(l.bitmap)
        if (sel != null) {
            c.save()
            c.clipRect(dirty)
            c.clipPath(sel)
            drawBuffer(c, buf)
            c.restore()
            return
        }
        forEachTouchedTile { tr ->
            c.save()
            c.clipRect(dirty)
            c.clipRect(tr)
            drawBuffer(c, buf)
            c.restore()
        }
    }

    /** Ruft [fn] für jede berührte Kachel (Rechteck in Dokumentpixeln, auf den Puffer begrenzt) auf. */
    private inline fun forEachTouchedTile(fn: (Rect) -> Unit) {
        val buf = buffer ?: return
        val t = PixelEdit.TILE
        val nx = (buf.width + t - 1) / t
        var k = touched.nextSetBit(0)
        while (k >= 0) {
            val x0 = (k % nx) * t; val y0 = (k / nx) * t
            tileRect.set(x0, y0, minOf(buf.width, x0 + t), minOf(buf.height, y0 + t))
            if (!tileRect.isEmpty) fn(tileRect)
            k = touched.nextSetBit(k + 1)
        }
    }

    private fun resetDirty() {
        dirty.setEmpty()
        touched.clear()
    }

    private fun reset() {
        predN = 0
        painter.flush()
        stash.discard()
        junction.discard(); jNext.discard(); jValid = false
        deferDabs = false
        liveStale = false
        assist.end()
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
            tiles.release()
            changed.setEmpty()
        }
    }

    /**
     * Leert den Strichbereich im Puffer. Die Anzeige-Kacheln werden direkt mitgeleert – sonst müsste der nächste
     * Strich im ersten Bild den ganzen (leeren) Bereich des vorigen Strichs neu in die Kacheln kopieren.
     */
    private fun clearBuffer() {
        val bc = bufferCanvas ?: return
        if (dirty.isEmpty) return
        // Nur berührte Kacheln enthalten etwas (der übrige Puffer ist schon leer)
        forEachTouchedTile { tr ->
            bc.drawRect(tr, clearPaint)
            tmpRect.set(tr)
            tiles.clear(tmpRect)
        }
    }

    private fun addDirty(x: Float, y: Float, d: Float, m: Matrix?) {
        val r = d * 0.75f + 2f
        tmpRect.set(x - r, y - r, x + r, y + r)
        m?.mapRect(tmpRect)
        dirty.union(tmpRect)
        changed.union(tmpRect)
        markTouched(tmpRect)
    }

    private fun markTouched(r: RectF) {
        val buf = buffer ?: return
        val t = PixelEdit.TILE
        val l = max(0, floor(r.left).toInt()); val tp = max(0, floor(r.top).toInt())
        val rr = minOf(buf.width, ceil(r.right).toInt()); val bb = minOf(buf.height, ceil(r.bottom).toInt())
        if (l >= rr || tp >= bb) return
        val nx = (buf.width + t - 1) / t
        for (j in tp / t..(bb - 1) / t) touched.set(j * nx + l / t, j * nx + (rr - 1) / t + 1)
    }

    /** Drehwinkel einer Symmetrie-Matrix (für die Ausrichtung beim Wischen). */
    private fun symmAngle(m: Matrix): Float {
        m.getValues(mValues)
        return atan2(mValues[Matrix.MSKEW_Y], mValues[Matrix.MSCALE_X])
    }

    private fun viewScaleInternal(): Float {
        val m = state.canvasView?.docToViewMatrix() ?: return 1f
        m.getValues(mValues)
        return hypot(mValues[Matrix.MSCALE_X], mValues[Matrix.MSKEW_Y]).coerceAtLeast(0.001f)
    }

    /** Drehung der Ansicht in Radiant (für "An Bildschirm ausrichten"). */
    private fun viewRotation(): Float {
        val m = state.canvasView?.docToViewMatrix() ?: return 0f
        m.getValues(mValues)
        return atan2(mValues[Matrix.MSKEW_Y], mValues[Matrix.MSCALE_X])
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
                e.restore(Rect(floor(dirty.left).toInt(), floor(dirty.top).toInt(), ceil(dirty.right).toInt(), ceil(dirty.bottom).toInt()))
            }
            smudger.begin(renderer.diameter, 1 + symm.size)
        } else {
            // Form ersetzt den Strich: Live-Strichende verwerfen, ab jetzt wird sofort gezeichnet
            painter.flush()
            stash.discard()
            jValid = false
            deferDabs = false
            clearBuffer()
        }
        // Bereich bleibt als Vereinigung erhalten (für Undo/Puffer-Löschen), neu zeichnen
        renderer.renderPath(shapeXs.data, shapeYs.data, n, sh.closed, shapePressure)
        painter.flush()
        state.canvasView?.postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------ QuickShape "Form bearbeiten"

    /** Startet die Bearbeitung der zuletzt erstellten QuickShape-Form (Schaltfläche in der Hinweisleiste). */
    fun beginShapeEdit() {
        val ls = lastShape ?: return
        if (editor != null || active) return
        if (!shapeEditStillValid(ls)) { lastShape = null; state.flash("Form kann nicht mehr bearbeitet werden"); return }
        val l = ls.layer
        val doc = state.document ?: return
        // Zustand mit alter Form sichern, dann Ebene im Formbereich auf "vor dem Strich" zurücksetzen
        editAfter = Bitmap.createBitmap(l.bitmap, ls.r0.left, ls.r0.top, ls.r0.width(), ls.r0.height())
        Canvas(l.bitmap).drawBitmap(ls.pre, ls.r0.left.toFloat(), ls.r0.top.toFloat(), restorePaint)
        ensureBuffer(doc.width, doc.height)
        resetDirty()
        renderer.brush = ls.brush
        renderer.diameter = ls.diameter
        renderer.opacity = ls.opacity
        renderer.color = ls.color
        renderer.secondaryColor = ls.secondary
        renderer.sampler = null
        renderer.sink = paintSink
        renderer.detailScale = doc.detailScale
        painter.setup(ls.brush, doc.detailScale)
        painter.union = true
        deferDabs = false
        symm = ls.symm
        mergePaint.alpha = ls.mergeAlpha
        mergePaint.blendMode = ls.blend
        thresholdOn = ls.threshold
        if (thresholdOn) thresholdPaint.colorFilter = StrokeEffects.thresholdFilter(ls.brush[BP.ALPHA_THRESHOLD_VALUE])
        l.drawOverride = editOverride
        val ed = QuickShapeEditor(state, this, ls.shape)
        editor = ed
        state.canvasOverride = ed
        ed.updateStatus()
        renderEditShape(ls.shape)
    }

    private val editOverride: (Canvas) -> Unit = { c ->
        val ls = lastShape; val buf = buffer
        if (ls != null && buf != null) {
            c.drawBitmap(ls.layer.bitmap, 0f, 0f, plainPaint)
            if (!dirty.isEmpty) {
                c.save(); c.clipRect(dirty)
                ls.selPath?.let { c.clipPath(it) }
                drawBufferLive(c, buf)
                c.restore()
            }
        }
    }

    private fun shapeEditStillValid(ls: LastShape): Boolean {
        val doc = state.document ?: return false
        return doc.id == ls.docId && doc.indexOf(ls.layer) >= 0 && state.undo.version == ls.undoVersion && !ls.layer.locked
    }

    override fun renderEditShape(shape: EditShape) {
        val ls = lastShape ?: return
        val n = shape.sample(shapeXs, shapeYs)
        if (n < 2) return
        clearBuffer()
        resetDirty()
        renderer.renderPath(shapeXs.data, shapeYs.data, n, shape.closed, ls.pressure)
        painter.flush()
        state.canvasView?.postInvalidateOnAnimation()
    }

    override fun commitShapeEdit() {
        val ls = lastShape ?: return endShapeEdit()
        val after = editAfter ?: return endShapeEdit()
        val buf = buffer ?: return endShapeEdit()
        val l = ls.layer
        val r = Rect(ls.r0)
        if (!dirty.isEmpty) r.union(floor(dirty.left).toInt(), floor(dirty.top).toInt(), ceil(dirty.right).toInt(), ceil(dirty.bottom).toInt())
        if (!r.intersect(0, 0, l.width, l.height)) return endShapeEdit()
        // "vorher" = Zustand mit der alten Form
        val before = Bitmap.createBitmap(l.bitmap, r.left, r.top, r.width(), r.height()).copy(Bitmap.Config.ARGB_8888, true)
        Canvas(before).drawBitmap(after, (ls.r0.left - r.left).toFloat(), (ls.r0.top - r.top).toFloat(), restorePaint)
        if (!dirty.isEmpty) {
            if (ls.tool == Tool.PAINT && StrokeEffects.needsPostPass(ls.brush)) StrokeEffects.apply(buf, dirty, ls.brush, ls.diameter)
            mergeBuffer(l, buf, ls.selPath)
        }
        val afterBmp = Bitmap.createBitmap(l.bitmap, r.left, r.top, r.width(), r.height())
        clearBuffer()
        state.undo.push(LayerPixelsAction("Form bearbeiten", l, r, before, afterBmp))
        endShapeEdit()
    }

    override fun checkShapeEdit(): Boolean {
        val ls = lastShape ?: run { endShapeEdit(); return false }
        val doc = state.document
        if (doc == null || doc.id != ls.docId || doc.indexOf(ls.layer) < 0 || state.undo.version != ls.undoVersion) {
            // Verlauf/Dokument hat sich geändert (z. B. Rückgängig): Sitzung ohne Wiederherstellen beenden
            clearBuffer()
            endShapeEdit()
            return false
        }
        if (state.tool != ls.tool && state.tool != Tool.PAINT && state.tool != Tool.ERASE) {
            commitShapeEdit()
            return false
        }
        return true
    }

    override fun viewScale(): Float = viewScaleInternal()

    private fun endShapeEdit() {
        lastShape?.layer?.drawOverride = null
        if (state.canvasOverride === editor) state.canvasOverride = null
        if (editor != null) state.statusBar = null
        editor = null
        editAfter = null
        lastShape = null
        symm = emptyList()
        renderer.sink = null
        resetDirty()
        state.invalidate()
    }

    /**
     * Übernimmt eine laufende „Form bearbeiten“-Sitzung (z. B. vor dem Speichern/Schließen) – sonst fehlte die
     * Form in der Ebene, weil diese während der Bearbeitung den Zustand ohne Form enthält.
     */
    fun commitPendingShapeEdit() {
        if (editor != null && checkShapeEdit()) commitShapeEdit()
    }

    /** Läuft gerade "Form bearbeiten"? */
    val isEditingShape: Boolean get() = editor != null

    private companion object {
        const val EDIT_SHAPE_LABEL = "Form bearbeiten"
        const val MAX_PREDICTED = 32
    }

    /** Gibt Speicher frei (z. B. beim Schließen eines Dokuments). */
    fun release() {
        if (editor != null) { clearBuffer(); endShapeEdit() }
        lastShape = null
        buffer?.recycle(); buffer = null; bufferCanvas = null
        stash.release(); junction.release(); jNext.release()
        tiles.release(); changed.setEmpty()
        painter.release()
        smudger.release()
    }
}

/**
 * Sicherung von Pufferbereichen unter dem live gezeichneten Strichende: [save] kopiert einen Bereich (1:1,
 * vormultipliziert, ohne Filterung), [restore] schreibt alle gesicherten Bereiche zurück. Bitmaps werden
 * wiederverwendet.
 */
private class TailStash {
    private val rects = ArrayList<Rect>()
    private val bmps = ArrayList<Bitmap>()
    private val canvases = ArrayList<Canvas>()
    private var count = 0
    private val src = Rect()
    private val dst = Rect()
    private val copyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

    fun save(buf: Bitmap, r: Rect) {
        if (r.isEmpty) return
        val w = r.width(); val h = r.height()
        if (count == bmps.size || bmps[count].width < w || bmps[count].height < h) {
            val bw = ((w + 63) / 64) * 64; val bh = ((h + 63) / 64) * 64
            val b = Bitmap.createBitmap(
                maxOf(bw, bmps.getOrNull(count)?.width ?: 0), maxOf(bh, bmps.getOrNull(count)?.height ?: 0),
                Bitmap.Config.ARGB_8888,
            )
            if (count == bmps.size) { bmps.add(b); canvases.add(Canvas(b)); rects.add(Rect()) }
            else { bmps[count].recycle(); bmps[count] = b; canvases[count] = Canvas(b) }
        }
        dst.set(0, 0, w, h)
        canvases[count].drawBitmap(buf, r, dst, copyPaint)
        rects[count].set(r)
        count++
    }

    fun restore(c: Canvas, changed: RectF) {
        peek(c, changed)
        count = 0
    }

    /** Schreibt die gesicherten Bereiche zurück, behält sie aber (für erneutes Einsetzen). */
    fun peek(c: Canvas, changed: RectF) {
        for (i in count - 1 downTo 0) {
            val r = rects[i]
            src.set(0, 0, r.width(), r.height())
            c.drawBitmap(bmps[i], src, r, copyPaint)
            changed.union(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
        }
    }

    fun discard() { count = 0 }

    fun release() {
        for (b in bmps) b.recycle()
        bmps.clear(); canvases.clear(); rects.clear(); count = 0
    }
}

/**
 * Anzeige-Kacheln des Strich-Puffers (je [T]×[T] Dokumentpixel plus 1 Pixel Rand aus den Nachbarkacheln, damit die
 * bilineare Filterung beim Zoomen an den Kachelgrenzen keine Nähte zeigt). [sync] kopiert geänderte Bereiche aus
 * dem Puffer; nur diese Kacheln ändern sich und werden von der GPU neu geladen.
 */
private class BufferTiles {
    private var nx = 0
    private var ny = 0
    private var w = 0
    private var h = 0
    private var tiles = arrayOfNulls<Bitmap>(0)
    private var canvases = arrayOfNulls<Canvas>(0)
    private val src = Rect()
    private val dst = Rect()
    private val dstF = RectF()
    private val clip = Rect()
    private val copyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    private fun ensure(buf: Bitmap) {
        if (buf.width == w && buf.height == h && tiles.isNotEmpty()) return
        release()
        w = buf.width; h = buf.height
        nx = (w + T - 1) / T; ny = (h + T - 1) / T
        tiles = arrayOfNulls(nx * ny)
        canvases = arrayOfNulls(nx * ny)
    }

    /** Überträgt den Bereich [r] (Dokumentpixel) aus [buf] in die betroffenen Kacheln. */
    fun sync(buf: Bitmap, r: RectF) {
        if (r.isEmpty) return
        ensure(buf)
        val l = max(0, floor(r.left).toInt() - 1); val t = max(0, floor(r.top).toInt() - 1)
        val rr = minOf(w, ceil(r.right).toInt() + 1); val bb = minOf(h, ceil(r.bottom).toInt() + 1)
        if (l >= rr || t >= bb) return
        // Kacheln, deren Bereich inkl. Rand (x0−1 … x0+T+1) das Rechteck berührt
        val i0 = max(0, (l - 1) / T); val i1 = minOf(nx - 1, rr / T)
        val j0 = max(0, (t - 1) / T); val j1 = minOf(ny - 1, bb / T)
        for (j in j0..j1) for (i in i0..i1) {
            val x0 = i * T - 1; val y0 = j * T - 1
            src.set(max(l, x0), max(t, y0), minOf(rr, x0 + T + 2), minOf(bb, y0 + T + 2))
            if (src.isEmpty) continue
            val k = j * nx + i
            var c = canvases[k]
            if (c == null) {
                val b = Bitmap.createBitmap(T + 2, T + 2, Bitmap.Config.ARGB_8888)
                tiles[k] = b; c = Canvas(b); canvases[k] = c
                // neue Kachel: ganzen Bereich übernehmen (sonst fehlte älterer Pufferinhalt)
                src.set(max(0, x0), max(0, y0), minOf(w, x0 + T + 2), minOf(h, y0 + T + 2))
            }
            dst.set(src.left - x0, src.top - y0, src.right - x0, src.bottom - y0)
            c.drawBitmap(buf, src, dst, copyPaint)
        }
    }

    /**
     * Leert den Bereich [r] (Dokumentpixel) in den vorhandenen Kacheln – gleiches Ergebnis wie [sync] aus einem dort
     * leeren Puffer, aber ohne Kopieren. Fehlende Kacheln übernehmen beim Anlegen ohnehin den Pufferinhalt.
     */
    fun clear(r: RectF) {
        if (r.isEmpty || tiles.isEmpty()) return
        val l = max(0, floor(r.left).toInt() - 1); val t = max(0, floor(r.top).toInt() - 1)
        val rr = minOf(w, ceil(r.right).toInt() + 1); val bb = minOf(h, ceil(r.bottom).toInt() + 1)
        if (l >= rr || t >= bb) return
        val i0 = max(0, (l - 1) / T); val i1 = minOf(nx - 1, rr / T)
        val j0 = max(0, (t - 1) / T); val j1 = minOf(ny - 1, bb / T)
        for (j in j0..j1) for (i in i0..i1) {
            val k = j * nx + i
            val c = canvases[k] ?: continue
            val x0 = i * T - 1; val y0 = j * T - 1
            src.set(max(l, x0), max(t, y0), minOf(rr, x0 + T + 2), minOf(bb, y0 + T + 2))
            if (src.isEmpty) continue
            dst.set(src.left - x0, src.top - y0, src.right - x0, src.bottom - y0)
            if (dst.left <= 0 && dst.top <= 0 && dst.right >= T + 2 && dst.bottom >= T + 2) tiles[k]!!.eraseColor(0)
            else c.drawRect(dst, clearPaint)
        }
    }

    /** Zeichnet alle vorhandenen Kacheln, die [area] berühren, mit [paint] an ihre Dokumentposition. */
    fun draw(c: Canvas, area: RectF, paint: Paint) {
        if (tiles.isEmpty() || area.isEmpty) return
        val i0 = max(0, floor(area.left).toInt() / T); val i1 = minOf(nx - 1, ceil(area.right).toInt() / T)
        val j0 = max(0, floor(area.top).toInt() / T); val j1 = minOf(ny - 1, ceil(area.bottom).toInt() / T)
        for (j in j0..j1) for (i in i0..i1) {
            val b = tiles[j * nx + i] ?: continue
            val cw = minOf(T, w - i * T); val ch = minOf(T, h - j * T)
            if (cw <= 0 || ch <= 0) continue
            clip.set(1, 1, 1 + cw, 1 + ch)
            dstF.set((i * T).toFloat(), (j * T).toFloat(), (i * T + cw).toFloat(), (j * T + ch).toFloat())
            c.drawBitmap(b, clip, dstF, paint)
        }
    }

    fun release() {
        for (b in tiles) b?.recycle()
        tiles = arrayOfNulls(0); canvases = arrayOfNulls(0)
        nx = 0; ny = 0; w = 0; h = 0
    }

    private companion object { const val T = 256 }
}
