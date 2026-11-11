package at.techgaisi.farbwerk.canvas

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.guides.GuidesOverlay
import at.techgaisi.farbwerk.model.InputPoint
import at.techgaisi.farbwerk.model.Tool
import at.techgaisi.farbwerk.model.ToolInputHandler
import at.techgaisi.farbwerk.render.Compositor
import at.techgaisi.farbwerk.selection.SelectionOverlay
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Die Leinwand. Zuständig für:
 *  - Darstellung (Dokument über [Compositor], Overlays der Werkzeuge)
 *  - Ansicht (Zoom, Drehen, Verschieben, Spiegeln)
 *  - Gestenerkennung:
 *      1 Finger/Stift: Werkzeug (Malen, Wischen, Radieren, Auswahl, ...)
 *      Berühren & halten (Finger, ohne Bewegung): Pipette
 *      Zeichnen & halten: QuickShape (an Werkzeug weitergereicht)
 *      2 Finger: Zoomen / Drehen / Verschieben; schnelles Zusammenkneifen = Einpassen
 *      2-Finger-Tippen: Rückgängig; 2 Finger halten: schnelles Rückgängig
 *      3-Finger-Tippen: Wiederholen; 3 Finger halten: schnelles Wiederholen
 *      3 Finger hin und her: Ebene leeren
 *      3 Finger nach unten wischen: Kopieren & Einfügen-Menü
 *      4-Finger-Tippen: Vollbild umschalten
 */
@SuppressLint("ViewConstructor")
class CanvasView(context: Context, private val state: EditorState) : View(context) {

    private val density = resources.displayMetrics.density
    private val viewMatrix = Matrix()
    private val inverse = Matrix()
    private var initialized = false
    private val handler = Handler(Looper.getMainLooper())

    private val workspacePaint = Paint()
    private val checkerPaint = Paint().apply {
        val b = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(250, 250, 250))
        val p = Paint().apply { color = Color.rgb(222, 222, 222) }
        c.drawRect(0f, 0f, 16f, 16f, p); c.drawRect(16f, 16f, 32f, 32f, p)
        shader = BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        setShadowLayer(18f, 0f, 4f, Color.argb(120, 0, 0, 0))
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    // ===================================================================== Ansicht

    fun docToViewMatrix(): Matrix = displayMatrix()

    /** Fenster-Koordinaten → Dokument-Koordinaten (berücksichtigt Zoom, Drehung und Spiegelung). */
    fun windowToDoc(windowX: Float, windowY: Float): android.graphics.PointF {
        val loc = IntArray(2); getLocationInWindow(loc)
        val pts = floatArrayOf(windowX - loc[0], windowY - loc[1])
        val inv = Matrix(); displayMatrix().invert(inv)
        inv.mapPoints(pts)
        return android.graphics.PointF(pts[0], pts[1])
    }

    private fun updateInverse() { viewMatrix.invert(inverse) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!initialized && w > 0 && h > 0) {
            initialized = true
            fitToScreen(false)
        }
    }

    private fun fitMatrix(): Matrix {
        val doc = state.document ?: return Matrix()
        val m = Matrix()
        val margin = 24f * density
        val s = min((width - 2 * margin) / doc.width, (height - 2 * margin) / doc.height).coerceAtLeast(0.01f)
        m.postScale(s, s)
        m.postTranslate((width - doc.width * s) / 2f, (height - doc.height * s) / 2f)
        return m
    }

    fun fitToScreen(animate: Boolean) {
        if (width == 0) return
        animateTo(fitMatrix(), animate)
    }

    fun actualSize() {
        val doc = state.document ?: return
        val m = Matrix()
        m.postTranslate((width - doc.width) / 2f, (height - doc.height) / 2f)
        animateTo(m, true)
    }

    fun zoomBy(factor: Float) {
        viewMatrix.postScale(factor, factor, width / 2f, height / 2f)
        updateInverse(); invalidate()
    }

    private var animator: ValueAnimator? = null
    private fun animateTo(target: Matrix, animate: Boolean) {
        animator?.cancel()
        if (!animate) {
            viewMatrix.set(target); updateInverse(); invalidate(); return
        }
        val from = FloatArray(9).also { viewMatrix.getValues(it) }
        val to = FloatArray(9).also { target.getValues(it) }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            addUpdateListener {
                val t = it.animatedValue as Float
                val v = FloatArray(9) { i -> from[i] + (to[i] - from[i]) * t }
                viewMatrix.setValues(v); updateInverse(); invalidate()
            }
            start()
        }
    }

    // ===================================================================== Zeichnen

    override fun onDraw(canvas: Canvas) {
        val light = state.prefs.lightInterface
        canvas.drawColor(if (light) Color.rgb(214, 214, 218) else Color.rgb(28, 28, 32))
        val doc = state.document ?: return
        val bounds = RectF(0f, 0f, doc.width.toFloat(), doc.height.toFloat())

        canvas.save()
        canvas.concat(viewMatrix)
        if (doc.flipHorizontal) canvas.scale(-1f, 1f, doc.width / 2f, doc.height / 2f)
        if (doc.flipVertical) canvas.scale(1f, -1f, doc.width / 2f, doc.height / 2f)
        canvas.drawRect(bounds, shadowPaint)
        canvas.save()
        canvas.clipRect(bounds)
        if (!doc.backgroundVisible) {
            val inv = 1f / max(0.0001f, currentScale())
            canvas.save(); canvas.scale(inv, inv)
            canvas.drawPaint(checkerPaint)
            canvas.restore()
        }
        try {
            Compositor.draw(canvas, doc)
        } catch (t: Throwable) {
            android.util.Log.e("Farbwerk", "Compositor", t)
        }
        canvas.restore()
        canvas.restore()

        val m = displayMatrix()
        try { GuidesOverlay.draw(canvas, state, m) } catch (t: Throwable) { android.util.Log.e("Farbwerk", "guides", t) }
        try { SelectionOverlay.draw(canvas, state, m) } catch (t: Throwable) { android.util.Log.e("Farbwerk", "selOverlay", t) }
        try { state.activeHandler()?.drawOverlay(canvas, m) } catch (t: Throwable) { android.util.Log.e("Farbwerk", "overlay", t) }
        if (eyedropperActive) state.eyedropper.drawOverlay(canvas, m)
    }

    /** Matrix Dokument → View inkl. Spiegelung. */
    private fun displayMatrix(): Matrix {
        val doc = state.document
        val m = Matrix()
        if (doc != null) {
            if (doc.flipHorizontal) m.postScale(-1f, 1f, doc.width / 2f, doc.height / 2f)
            if (doc.flipVertical) m.postScale(1f, -1f, doc.width / 2f, doc.height / 2f)
        }
        m.postConcat(viewMatrix)
        return m
    }

    private fun currentScale(): Float {
        val v = FloatArray(9); viewMatrix.getValues(v)
        return hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y])
    }

    // ===================================================================== Eingabe

    private enum class Mode { IDLE, STROKE, GESTURE, EYEDROPPER, PAN_SPACE, BLOCKED }

    private var mode = Mode.IDLE
    private var strokePointerId = -1
    private var strokeIsStylus = false
    private var strokeStartTime = 0L
    private var strokeHandler: ToolInputHandler? = null
    private var strokeMoved = false
    private var strokeStartX = 0f
    private var strokeStartY = 0f
    private var lastPoint: InputPoint? = null
    private var stylusEraserOverride = false

    // Gesten
    private var gestureMaxPointers = 0
    private var gestureStartTime = 0L
    private var gestureMoved = false
    private var gestureStartSpan = 0f
    private var gestureLastSpan = 0f
    private var gestureLastAngle = 0f
    private var gestureLastCx = 0f
    private var gestureLastCy = 0f
    private var gestureStartCy = 0f
    private var gestureScrubDir = 0
    private var gestureScrubReversals = 0
    private var gestureScrubAnchorX = 0f
    private var gestureConsumed = false
    private var savedViewBeforeFit: Matrix? = null
    private var rapidRepeating = false

    private val slop get() = 12f * density

    private fun toDoc(e: MotionEvent, idx: Int, hist: Int = -1): InputPoint {
        val x = if (hist >= 0) e.getHistoricalX(idx, hist) else e.getX(idx)
        val y = if (hist >= 0) e.getHistoricalY(idx, hist) else e.getY(idx)
        val pts = floatArrayOf(x, y)
        val inv = Matrix(); displayMatrix().invert(inv)
        inv.mapPoints(pts)
        val stylus = e.getToolType(idx) == MotionEvent.TOOL_TYPE_STYLUS || e.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER
        val rawP = if (hist >= 0) e.getHistoricalPressure(idx, hist) else e.getPressure(idx)
        val pressure = if (stylus) state.prefs.applyPressureCurve(rawP) else 1f
        val tilt = if (stylus) e.getAxisValue(MotionEvent.AXIS_TILT, idx) else 0f
        val orient = if (stylus) e.getOrientation(idx) else 0f
        val t = if (hist >= 0) e.getHistoricalEventTime(hist) else e.eventTime
        return InputPoint(pts[0], pts[1], pressure, tilt, orient, t, stylus)
    }

    private fun isStylus(e: MotionEvent, idx: Int) =
        e.getToolType(idx) == MotionEvent.TOOL_TYPE_STYLUS || e.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (state.document == null) return false
        requestFocus()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onFirstDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(e)
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(e)
            MotionEvent.ACTION_UP -> onLastUp(e)
            MotionEvent.ACTION_CANCEL -> onCancel()
        }
        return true
    }

    private fun onFirstDown(e: MotionEvent) {
        cancelTimers()
        state.copyPasteMenuVisible = false
        if (state.openPanel != at.techgaisi.farbwerk.model.Panel.NONE) {
            // Tippen auf die Leinwand schließt offene Panels (wie im Original) – und malt nicht.
            state.openPanel = at.techgaisi.farbwerk.model.Panel.NONE
            mode = Mode.BLOCKED
            return
        }
        val stylus = isStylus(e, 0)
        if (state.spaceHeld) {
            mode = Mode.PAN_SPACE
            gestureLastCx = e.x; gestureLastCy = e.y
            return
        }
        if (state.eyedropperArmed) {
            startEyedropper(toDoc(e, 0))
            return
        }
        if (!stylus && !state.prefs.fingerPainting && state.tool in setOf(Tool.PAINT, Tool.SMUDGE, Tool.ERASE)) {
            // Finger malen deaktiviert: nur Gesten/Pipette
            mode = Mode.IDLE
            strokeStartX = e.x; strokeStartY = e.y
            strokeIsStylus = false
            strokeStartTime = e.eventTime
            scheduleHoldEyedropper(e)
            return
        }
        stylusEraserOverride = e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER &&
            state.tool in setOf(Tool.PAINT, Tool.SMUDGE)
        startStroke(e, 0)
    }

    private fun startStroke(e: MotionEvent, idx: Int) {
        val handlerTool = if (stylusEraserOverride) {
            state.brushEngine.temporaryTool = Tool.ERASE
            state.brushEngine
        } else {
            state.brushEngine.temporaryTool = null
            state.activeHandler()
        }
        strokeHandler = handlerTool
        mode = Mode.STROKE
        strokePointerId = e.getPointerId(idx)
        strokeIsStylus = isStylus(e, idx)
        strokeStartTime = e.eventTime
        strokeMoved = false
        strokeStartX = e.getX(idx); strokeStartY = e.getY(idx)
        val p = toDoc(e, idx)
        lastPoint = p
        try { handlerTool?.onStrokeStart(p) } catch (t: Throwable) { android.util.Log.e("Farbwerk", "start", t) }
        if (!strokeIsStylus && state.tool in setOf(Tool.PAINT, Tool.SMUDGE, Tool.ERASE)) scheduleHoldEyedropper(e)
        invalidate()
    }

    private fun onPointerDown(e: MotionEvent) {
        val idx = e.actionIndex
        when (mode) {
            Mode.STROKE -> {
                if (strokeIsStylus) return // Stift malt weiter, Finger werden ignoriert (Handballen)
                // Wenn ein zweiter Finger nach kurzem Strich dazukommt → Strich verwerfen, Geste starten
                val age = e.eventTime - strokeStartTime
                if (isStylus(e, idx)) return
                if (age < 350 || !strokeMoved) {
                    cancelStroke()
                    startGesture(e)
                } else {
                    // Langer Strich: QuickShape-Perfektionierung (zweiter Finger tippt) an Werkzeug melden
                    lastPoint?.let { strokeHandler?.onHoldStill(it) }
                }
            }
            Mode.IDLE, Mode.EYEDROPPER -> {
                if (mode == Mode.EYEDROPPER) { state.eyedropper.cancel(); eyedropperActive = false }
                cancelTimers()
                startGesture(e)
            }
            Mode.GESTURE -> {
                gestureMaxPointers = max(gestureMaxPointers, e.pointerCount)
                resetGestureGeometry(e)
                cancelTimers()
                scheduleRapid()
            }
            else -> {}
        }
    }

    private fun onMove(e: MotionEvent) {
        when (mode) {
            Mode.STROKE -> {
                val idx = e.findPointerIndex(strokePointerId)
                if (idx < 0) return
                val pts = ArrayList<InputPoint>(e.historySize + 1)
                for (h in 0 until e.historySize) pts.add(toDoc(e, idx, h))
                pts.add(toDoc(e, idx))
                val dx = e.getX(idx) - strokeStartX
                val dy = e.getY(idx) - strokeStartY
                if (!strokeMoved && hypot(dx, dy) > slop) {
                    strokeMoved = true
                    handler.removeCallbacks(holdEyedropperRunnable)
                }
                // QuickShape: nach Bewegung still halten
                val lp = lastPoint
                val np = pts.last()
                if (strokeMoved && lp != null) {
                    val m = displayMatrix()
                    val a = floatArrayOf(lp.x, lp.y, np.x, np.y); m.mapPoints(a)
                    if (hypot(a[2] - a[0], a[3] - a[1]) > 3f * density || !quickShapeScheduled) {
                        scheduleQuickShape()
                    }
                }
                lastPoint = np
                try { strokeHandler?.onStrokeMove(pts) } catch (t: Throwable) { android.util.Log.e("Farbwerk", "move", t) }
                invalidate()
            }
            Mode.GESTURE -> onGestureMove(e)
            Mode.EYEDROPPER -> {
                state.eyedropper.move(toDoc(e, 0)); invalidate()
            }
            Mode.PAN_SPACE -> {
                viewMatrix.postTranslate(e.x - gestureLastCx, e.y - gestureLastCy)
                gestureLastCx = e.x; gestureLastCy = e.y
                updateInverse(); invalidate()
            }
            Mode.IDLE -> {
                if (hypot(e.x - strokeStartX, e.y - strokeStartY) > slop) handler.removeCallbacks(holdEyedropperRunnable)
            }
            else -> {}
        }
    }

    private fun onPointerUp(e: MotionEvent) {
        if (mode == Mode.STROKE && e.getPointerId(e.actionIndex) == strokePointerId) {
            finishStroke(toDoc(e, e.actionIndex))
            mode = Mode.BLOCKED
        } else if (mode == Mode.GESTURE) {
            resetGestureGeometry(e, excludeIndex = e.actionIndex)
        }
    }

    private fun onLastUp(e: MotionEvent) {
        cancelTimers()
        when (mode) {
            Mode.STROKE -> finishStroke(toDoc(e, 0))
            Mode.GESTURE -> endGesture(e)
            Mode.EYEDROPPER -> {
                state.eyedropper.end(toDoc(e, 0))
                eyedropperActive = false
                state.eyedropperArmed = false
            }
            else -> {}
        }
        mode = Mode.IDLE
        invalidate()
    }

    private fun onCancel() {
        cancelTimers()
        if (mode == Mode.STROKE) cancelStroke()
        if (mode == Mode.EYEDROPPER) { state.eyedropper.cancel(); eyedropperActive = false }
        mode = Mode.IDLE
        invalidate()
    }

    private fun finishStroke(p: InputPoint) {
        handler.removeCallbacks(quickShapeRunnable); quickShapeScheduled = false
        handler.removeCallbacks(holdEyedropperRunnable)
        try { strokeHandler?.onStrokeEnd(p) } catch (t: Throwable) { android.util.Log.e("Farbwerk", "end", t) }
        state.brushEngine.temporaryTool = null
        strokeHandler = null
        strokePointerId = -1
        invalidate()
    }

    private fun cancelStroke() {
        handler.removeCallbacks(quickShapeRunnable); quickShapeScheduled = false
        handler.removeCallbacks(holdEyedropperRunnable)
        try { strokeHandler?.onStrokeCancel() } catch (t: Throwable) { android.util.Log.e("Farbwerk", "cancel", t) }
        state.brushEngine.temporaryTool = null
        strokeHandler = null
        strokePointerId = -1
        mode = Mode.IDLE
        invalidate()
    }

    // ---------------------------------------------------------- Pipette (Berühren & Halten)

    private var eyedropperActive = false
    private var holdEvent: MotionEvent? = null
    private val holdEyedropperRunnable = Runnable {
        val ev = holdEvent ?: return@Runnable
        if (mode == Mode.STROKE && !strokeMoved) cancelStroke()
        if (mode == Mode.STROKE || mode == Mode.GESTURE) return@Runnable
        startEyedropper(toDoc(ev, 0))
    }

    private fun scheduleHoldEyedropper(e: MotionEvent) {
        if (!state.prefs.gestureHoldEyedropper) return
        holdEvent?.recycle()
        holdEvent = MotionEvent.obtain(e)
        handler.removeCallbacks(holdEyedropperRunnable)
        handler.postDelayed(holdEyedropperRunnable, state.prefs.holdDelayMs.toLong())
    }

    private fun startEyedropper(p: InputPoint) {
        mode = Mode.EYEDROPPER
        eyedropperActive = true
        state.eyedropper.begin(p)
        invalidate()
    }

    // ---------------------------------------------------------- QuickShape

    private var quickShapeScheduled = false
    private val quickShapeRunnable = Runnable {
        quickShapeScheduled = false
        if (mode == Mode.STROKE && strokeMoved && state.prefs.quickShapeEnabled) {
            lastPoint?.let { strokeHandler?.onHoldStill(it) }
            invalidate()
        }
    }

    private fun scheduleQuickShape() {
        handler.removeCallbacks(quickShapeRunnable)
        handler.postDelayed(quickShapeRunnable, state.prefs.quickShapeDelayMs.toLong())
        quickShapeScheduled = true
    }

    // ---------------------------------------------------------- Mehrfinger-Gesten

    private fun startGesture(e: MotionEvent) {
        mode = Mode.GESTURE
        gestureMaxPointers = e.pointerCount
        gestureStartTime = e.eventTime
        gestureMoved = false
        gestureConsumed = false
        gestureScrubDir = 0
        gestureScrubReversals = 0
        resetGestureGeometry(e)
        gestureStartSpan = gestureLastSpan
        gestureStartCy = gestureLastCy
        gestureScrubAnchorX = gestureLastCx
        scheduleRapid()
    }

    private fun resetGestureGeometry(e: MotionEvent, excludeIndex: Int = -1) {
        var cx = 0f; var cy = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != excludeIndex) { cx += e.getX(i); cy += e.getY(i); n++ }
        if (n == 0) return
        cx /= n; cy /= n
        gestureLastCx = cx; gestureLastCy = cy
        val ids = (0 until e.pointerCount).filter { it != excludeIndex }
        if (ids.size >= 2) {
            val a = ids[0]; val b = ids[1]
            gestureLastSpan = hypot(e.getX(a) - e.getX(b), e.getY(a) - e.getY(b))
            gestureLastAngle = atan2(e.getY(b) - e.getY(a), e.getX(b) - e.getX(a))
        }
        gestureScrubAnchorX = cx
    }

    private fun onGestureMove(e: MotionEvent) {
        if (gestureConsumed) return
        var cx = 0f; var cy = 0f
        for (i in 0 until e.pointerCount) { cx += e.getX(i); cy += e.getY(i) }
        cx /= e.pointerCount; cy /= e.pointerCount
        val dx = cx - gestureLastCx
        val dy = cy - gestureLastCy

        if (!gestureMoved && (hypot(dx, dy) > slop * 0.6f || (e.pointerCount >= 2 && abs(spanOf(e) - gestureLastSpan) > slop))) {
            gestureMoved = true
            cancelRapid()
        }

        when {
            e.pointerCount == 2 && gestureMaxPointers == 2 -> {
                val span = spanOf(e)
                val angle = atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0))
                if (gestureMoved) {
                    viewMatrix.postTranslate(dx, dy)
                    if (gestureLastSpan > 0f && span > 0f) {
                        val s = span / gestureLastSpan
                        val cur = currentScale()
                        val clamped = (cur * s).coerceIn(0.02f, 64f) / cur
                        viewMatrix.postScale(clamped, clamped, cx, cy)
                    }
                    var da = angle - gestureLastAngle
                    if (da > Math.PI) da -= (2 * Math.PI).toFloat()
                    if (da < -Math.PI) da += (2 * Math.PI).toFloat()
                    viewMatrix.postRotate(Math.toDegrees(da.toDouble()).toFloat(), cx, cy)
                    updateInverse(); invalidate()
                }
                gestureLastSpan = span
                gestureLastAngle = angle
            }
            gestureMaxPointers == 3 && e.pointerCount == 3 -> {
                // Wischen nach unten → Kopieren & Einfügen
                if (state.prefs.gestureCopyPaste && cy - gestureStartCy > 90f * density &&
                    abs(cx - gestureScrubAnchorX) < 60f * density && gestureScrubReversals == 0) {
                    gestureConsumed = true
                    state.copyPasteMenuVisible = true
                }
                // Hin und her schrubben → Ebene leeren
                if (state.prefs.gestureScrubClear) {
                    val off = cx - gestureScrubAnchorX
                    if (abs(off) > 40f * density) {
                        val dir = if (off > 0) 1 else -1
                        if (gestureScrubDir != 0 && dir != gestureScrubDir) gestureScrubReversals++
                        gestureScrubDir = dir
                        gestureScrubAnchorX = cx
                        if (gestureScrubReversals >= 3) {
                            gestureConsumed = true
                            at.techgaisi.farbwerk.model.LayerOps.clearActiveLayer(state)
                            state.flash("Ebene geleert")
                        }
                    }
                }
            }
        }
        gestureLastCx = cx; gestureLastCy = cy
    }

    private fun spanOf(e: MotionEvent): Float =
        if (e.pointerCount >= 2) hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)) else 0f

    private fun endGesture(e: MotionEvent) {
        cancelRapid()
        val dur = e.eventTime - gestureStartTime
        if (rapidRepeating) { rapidRepeating = false; return }
        if (gestureConsumed) return
        if (!gestureMoved && dur < 400) {
            when (gestureMaxPointers) {
                2 -> if (state.prefs.gestureTapUndo) state.undo.undo()
                3 -> if (state.prefs.gestureTapUndo) state.undo.redo()
                4 -> if (state.prefs.gestureFullscreen) state.fullscreen = !state.fullscreen
            }
            return
        }
        // Schnelles Zusammenkneifen → Einpassen / zurück
        if (gestureMaxPointers == 2 && dur < 300 && gestureStartSpan > 0f) {
            val ratio = gestureLastSpan / gestureStartSpan
            if (ratio < 0.7f) {
                savedViewBeforeFit = Matrix(viewMatrix)
                fitToScreen(true)
            } else if (ratio > 1.4f) {
                savedViewBeforeFit?.let { animateTo(it, true) }
                savedViewBeforeFit = null
            }
        }
    }

    // ---------------------------------------------------------- Schnelles Rückgängig / Wiederholen

    private val rapidRunnable = object : Runnable {
        override fun run() {
            if (mode != Mode.GESTURE || gestureMoved) return
            rapidRepeating = true
            val ok = when (gestureMaxPointers) {
                2 -> state.undo.undo()
                3 -> state.undo.redo()
                else -> false
            }
            if (ok) handler.postDelayed(this, 110)
        }
    }

    private fun scheduleRapid() {
        handler.removeCallbacks(rapidRunnable)
        handler.postDelayed(rapidRunnable, state.prefs.rapidUndoDelayMs.toLong())
    }

    private fun cancelRapid() = handler.removeCallbacks(rapidRunnable)

    private fun cancelTimers() {
        cancelRapid()
        handler.removeCallbacks(holdEyedropperRunnable)
        handler.removeCallbacks(quickShapeRunnable)
        quickShapeScheduled = false
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelTimers()
        animator?.cancel()
    }
}
