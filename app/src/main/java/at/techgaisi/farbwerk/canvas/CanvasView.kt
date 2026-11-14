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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.input.motionprediction.MotionEventPredictor
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.guides.GuidesOverlay
import at.techgaisi.farbwerk.input.GestureFunction
import at.techgaisi.farbwerk.input.GestureMap
import at.techgaisi.farbwerk.input.GestureTrigger
import at.techgaisi.farbwerk.input.LayerPick
import at.techgaisi.farbwerk.model.InputPoint
import at.techgaisi.farbwerk.model.Tool
import at.techgaisi.farbwerk.model.ToolInputHandler
import at.techgaisi.farbwerk.render.Compositor
import at.techgaisi.farbwerk.selection.SelectionOverlay
import at.techgaisi.farbwerk.stylus.HoverPreview
import at.techgaisi.farbwerk.stylus.StylusActions
import at.techgaisi.farbwerk.stylus.StylusButtonAction
import at.techgaisi.farbwerk.stylus.StylusSession
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
 *  - Stift (S Pen), angelehnt an das Apple-Pencil-Verhalten:
 *      ungebündelte Zustellung + Bewegungsvorhersage (nur Vorschau), Druck/Neigung/Ausrichtung je Sample,
 *      Handballenerkennung (Stift bevorzugt, Finger-Kontakte in Stiftnähe gedämpft, große Kontakte verworfen),
 *      "Nur Stift malt", sobald ein Stift benutzt wurde, Schwebevorschau des Pinsels,
 *      Seitentaste (beim Berühren: Pipette/Radierer/…; Klick in der Luft: konfigurierbare Aktion),
 *      Radierer-Ende radiert.
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

    /** Dokument-Koordinaten → Fenster-Koordinaten (z. B. für die schwebende Pipette, QuickMenu). */
    fun docToWindow(docX: Float, docY: Float): android.graphics.PointF {
        val loc = IntArray(2); getLocationInWindow(loc)
        val pts = floatArrayOf(docX, docY)
        displayMatrix().mapPoints(pts)
        return android.graphics.PointF(pts[0] + loc[0], pts[1] + loc[1])
    }

    // ===================================================================== Hooks der Oberfläche (WP1/WP2)

    /** QuickMenu-Anbindung (gesetzt vom Editor-Bildschirm). */
    var quickMenuHook: QuickMenuHook? = null

    /**
     * „Ebene wählen“-Auslöser (Halten bzw. Modifizierer + Berühren). Das Ebenen-Paket kann hier ein Popup
     * anmelden; ohne Anmeldung wird die oberste Ebene mit Inhalt unter dem Finger aktiv ([LayerPick]).
     */
    var onSelectLayerAt: ((docX: Float, docY: Float, windowX: Float, windowY: Float) -> Unit)? = null

    /** Wird mit true beim Strichbeginn und mit false beim Strichende/-abbruch aufgerufen (Auto-Vollbild). */
    var onStrokeActivity: ((Boolean) -> Unit)? = null

    /** Modifizierer-Taste der Seitenleiste wird gehalten: Funktion für „Modifizierer + Berühren“. */
    var heldModifier: GestureFunction? = null
    /** Während des Haltens wurde die Leinwand berührt (dann löst das Loslassen kein „Tippen“ aus). */
    var modifierUsed = false
    /** Leertaste gehalten und dabei die Leinwand verschoben (dann öffnet Loslassen kein QuickMenu). */
    var spacePanned = false

    private var floatingEyedropper = false
    private var floatingDocX = 0f
    private var floatingDocY = 0f

    /** Läuft gerade die schwebende Pipette (Modifizierer tippen)? */
    val floatingEyedropperActive: Boolean get() = floatingEyedropper

    /** Schwebende Pipette an ([docX], [docY]) zeigen (Ring wie bei Berühren & halten). */
    fun beginFloatingEyedropper(docX: Float, docY: Float) {
        if (mode == Mode.STROKE || mode == Mode.EYEDROPPER) return
        floatingEyedropper = true
        eyedropperActive = true
        floatingDocX = docX; floatingDocY = docY
        state.eyedropper.begin(InputPoint(docX, docY, 1f, 0f, 0f, SystemClock.uptimeMillis(), false))
        invalidate()
    }

    /** Schwebende Pipette um ([dxView], [dyView]) Bildschirmpixel verschieben. */
    fun moveFloatingEyedropperBy(dxView: Float, dyView: Float) {
        if (!floatingEyedropper) return
        val m = displayMatrix()
        val pts = floatArrayOf(floatingDocX, floatingDocY)
        m.mapPoints(pts)
        pts[0] += dxView; pts[1] += dyView
        val inv = Matrix(); m.invert(inv); inv.mapPoints(pts)
        floatingDocX = pts[0]; floatingDocY = pts[1]
        state.eyedropper.move(InputPoint(floatingDocX, floatingDocY, 1f, 0f, 0f, SystemClock.uptimeMillis(), false))
        invalidate()
    }

    /** Schwebende Pipette beenden; [commit] übernimmt die Farbe. */
    fun endFloatingEyedropper(commit: Boolean) {
        if (!floatingEyedropper) return
        floatingEyedropper = false
        eyedropperActive = false
        if (commit) state.eyedropper.end(InputPoint(floatingDocX, floatingDocY, 1f, 0f, 0f, SystemClock.uptimeMillis(), false))
        else state.eyedropper.cancel()
        invalidate()
    }

    private fun viewToWindow(x: Float, y: Float): android.graphics.PointF {
        val loc = IntArray(2); getLocationInWindow(loc)
        return android.graphics.PointF(x + loc[0], y + loc[1])
    }

    /** QuickMenu an einer Berührung öffnen; [dragging] = Finger bleibt liegen (Schnellwahl per Ziehen). */
    private fun openQuickMenu(x: Float, y: Float, dragging: Boolean) {
        val hook = quickMenuHook ?: return
        val w = viewToWindow(x, y)
        hook.openAt(w.x, w.y, dragging)
        if (dragging) mode = Mode.QUICKMENU
    }

    /** „Ebene wählen“ an einer View-Position auslösen. */
    private fun selectLayerAt(x: Float, y: Float) {
        prepareInverse()
        mapTmp[0] = x; mapTmp[1] = y
        invDisplay.mapPoints(mapTmp)
        val w = viewToWindow(x, y)
        val cb = onSelectLayerAt
        if (cb != null) cb(mapTmp[0], mapTmp[1], w.x, w.y) else LayerPick.pickTopmost(state, mapTmp[0], mapTmp[1])
    }

    /** Finger-Kontakt ohne Malen (nur Stift malt) – Kandidat für den Auslöser „Tippen“. */
    private var idleFingerTap = false

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
        val margin = 40f * density
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

    /** Feines Raster auf der Arbeitsfläche hinter der Leinwand (fest am Bildschirm, nicht am Dokument). */
    private val workspaceGridPaint = Paint().apply { strokeWidth = 1f; isAntiAlias = false }

    private fun drawWorkspaceGrid(canvas: Canvas, light: Boolean) {
        val step = 28f * density
        workspaceGridPaint.color = if (light) Color.argb(14, 0, 0, 0) else Color.argb(11, 255, 255, 255)
        val w = width.toFloat(); val h = height.toFloat()
        // Raster mittig ausrichten, damit es symmetrisch zu den Bildschirmrändern liegt
        var x = (w / 2f) % step
        while (x < w) { canvas.drawLine(x, 0f, x, h, workspaceGridPaint); x += step }
        var y = (h / 2f) % step
        while (y < h) { canvas.drawLine(0f, y, w, y, workspaceGridPaint); y += step }
    }

    override fun onDraw(canvas: Canvas) {
        val light = state.prefs.lightInterface
        canvas.drawColor(if (light) Color.rgb(217, 217, 221) else Color.rgb(30, 30, 32)) // Arbeitsfläche (Theme: workspace)
        drawWorkspaceGrid(canvas, light)
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
            Compositor.displayScale = currentScale()
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
        try { state.canvasOverride?.drawOverlay(canvas, m) } catch (t: Throwable) { android.util.Log.e("Farbwerk", "ovOverlay", t) }
        if (eyedropperActive) state.eyedropper.drawOverlay(canvas, m)
        if (hovering && mode != Mode.STROKE && mode != Mode.EYEDROPPER && state.prefs.hoverPreview) drawHover(canvas, m)
    }

    /** Schwebevorschau: Pinselumriss bzw. Pipetten-Ring an der Stiftposition. */
    private fun drawHover(canvas: Canvas, m: Matrix) {
        val pipette = state.eyedropperArmed || (penButtonDown &&
            (StylusButtonAction.fromId(state.prefs.stylusButtonAction) == StylusButtonAction.EYEDROPPER ||
                StylusButtonAction.fromId(state.prefs.stylusButtonHoverAction) == StylusButtonAction.EYEDROPPER))
        val inv = Matrix(); m.invert(inv)
        val pt = floatArrayOf(hoverX, hoverY); inv.mapPoints(pt)
        try {
            // "Cursor anzeigen: Beim Malen" → kein Pinselumriss beim Schweben (Pipetten-Ring bleibt)
            hoverPreview.draw(canvas, m, hoverX, hoverY, pt[0], pt[1], hoverTilt, hoverOrient, hoverEraser,
                showBrush = state.prefs.cursorVisibility != 1, pipette = pipette)
        } catch (t: Throwable) { android.util.Log.e("Farbwerk", "hover", t) }
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

    private enum class Mode { IDLE, STROKE, GESTURE, EYEDROPPER, PAN_SPACE, BLOCKED, QUICKMENU }

    private var mode = Mode.IDLE
    private var strokePointerId = -1
    private var strokeIsStylus = false
    private var strokeStartTime = 0L
    private var strokeHandler: ToolInputHandler? = null
    private var strokeMoved = false
    private var strokeStartX = 0f
    private var strokeStartY = 0f
    private var lastPoint: InputPoint? = null
    /** Vorübergehendes Werkzeug für den nächsten Strich (Radierer-Ende, Seitentaste "Radierer"). */
    private var strokeTempTool: Tool? = null

    /** Aktueller Finger-Kontakt wurde von der Handballenerkennung gedämpft (Stift in der Nähe). */
    private var fingerSuppressed = false

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
    /** Startposition je Finger (Pointer-ID → x,y) – für kumulierte Bewegung (Tipp vs. Zoom/Verschieben). */
    private val gesturePointerStart = HashMap<Int, FloatArray>()
    /** Größte Strecke, die ein einzelner Finger seit Gestenbeginn zurückgelegt hat (px). */
    private var gestureMaxTravel = 0f

    private fun recordGesturePointers(e: MotionEvent) {
        for (i in 0 until e.pointerCount) {
            val id = e.getPointerId(i)
            if (!gesturePointerStart.containsKey(id)) gesturePointerStart[id] = floatArrayOf(e.getX(i), e.getY(i))
        }
    }

    private fun updateGestureTravel(e: MotionEvent) {
        for (i in 0 until e.pointerCount) {
            val st = gesturePointerStart[e.getPointerId(i)] ?: continue
            val d = hypot(e.getX(i) - st[0], e.getY(i) - st[1])
            if (d > gestureMaxTravel) gestureMaxTravel = d
        }
    }
    /** Tipp-Gesten (Rückgängig, Vollbild …) unterdrücken – Geste begann, während der Stift nahe war. */
    private var gestureSuppressTaps = false
    private var savedViewBeforeFit: Matrix? = null
    private var rapidRepeating = false

    private val slop get() = 12f * density
    private val pxPerMm = resources.displayMetrics.xdpi.coerceAtLeast(80f) / 25.4f

    // Koordinaten-Umrechnung: Inverse der Anzeige-Matrix, einmal pro Ereignis berechnet
    private val invDisplay = Matrix()
    private val mapTmp = FloatArray(2)

    private fun prepareInverse() { displayMatrix().invert(invDisplay) }

    // Bewegungsvorhersage
    private val predictor: MotionEventPredictor? = try { MotionEventPredictor.newInstance(this) } catch (t: Throwable) { null }
    private val predictedPts = ArrayList<InputPoint>(32)

    // Schweben (Hover) und Seitentaste
    private val hoverPreview = HoverPreview(state)
    private var hovering = false
    private var lastHoverTime = 0L
    private var hoverX = 0f
    private var hoverY = 0f
    private var hoverTilt = 0f
    private var hoverOrient = 0f
    private var hoverEraser = false
    private var penButtonDown = false
    private var penButtonDownTime = 0L
    /** Taste wurde während einer Berührung benutzt → beim Loslassen kein "Klick in der Luft". */
    private var penButtonUsed = false
    private var lastPenButtonMotion = Long.MIN_VALUE / 2
    /** Samsung-Altgeräte melden Striche mit gedrückter Taste als eigene Aktionen (211–214). */
    private var samsungButtonEvent = false

    /**
     * Wandelt einen Zeiger (optional einen historischen Wert) in einen Eingabepunkt in Dokument-Koordinaten.
     * Druck (mit Druckkurve), Neigung (AXIS_TILT, Radiant ab Senkrechte) und Ausrichtung (in Dokument-Richtung
     * umgerechnet) werden auch für alle historischen Samples einzeln gelesen. [prepareInverse] vorher aufrufen.
     */
    private fun toDoc(e: MotionEvent, idx: Int, hist: Int = -1, forceStylus: Boolean? = null): InputPoint {
        val x = if (hist >= 0) e.getHistoricalX(idx, hist) else e.getX(idx)
        val y = if (hist >= 0) e.getHistoricalY(idx, hist) else e.getY(idx)
        mapTmp[0] = x; mapTmp[1] = y
        invDisplay.mapPoints(mapTmp)
        val stylus = forceStylus ?: isStylus(e, idx)
        var pressure = 1f
        var tilt = 0f
        var orient = 0f
        if (stylus) {
            val rawP = if (hist >= 0) e.getHistoricalPressure(idx, hist) else e.getPressure(idx)
            pressure = state.prefs.applyPressureCurve(rawP)
            tilt = (if (hist >= 0) e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, idx, hist)
                    else e.getAxisValue(MotionEvent.AXIS_TILT, idx)).coerceIn(0f, HALF_PI)
            val o = if (hist >= 0) e.getHistoricalOrientation(idx, hist) else e.getOrientation(idx)
            orient = screenOrientationToDoc(o)
        }
        val t = if (hist >= 0) e.getHistoricalEventTime(hist) else e.eventTime
        return InputPoint(mapTmp[0], mapTmp[1], pressure, tilt, orient, t, stylus)
    }

    /**
     * MotionEvent-Ausrichtung des Stifts (0 = nach oben, +π/2 = nach rechts, Bildschirm) → Winkel im
     * Dokument (atan2-Konvention, x nach rechts, y nach unten), berücksichtigt Drehung/Spiegelung der Ansicht.
     */
    private fun screenOrientationToDoc(o: Float): Float {
        if (o == 0f && !orientationSeen) return 0f
        orientationSeen = true
        val a = o - HALF_PI
        val v0 = cos(a); val v1 = sin(a)
        mapTmp2[0] = v0; mapTmp2[1] = v1
        invDisplay.mapVectors(mapTmp2)
        return atan2(mapTmp2[1], mapTmp2[0])
    }
    private val mapTmp2 = FloatArray(2)
    /** Bis der Digitizer einmal eine Ausrichtung ≠ 0 liefert, gilt er als ohne Ausrichtung (Winkel 0). */
    private var orientationSeen = false

    private fun isStylus(e: MotionEvent, idx: Int) =
        e.getToolType(idx) == MotionEvent.TOOL_TYPE_STYLUS || e.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER

    private fun hasStylus(e: MotionEvent): Boolean {
        for (i in 0 until e.pointerCount) if (isStylus(e, i)) return true
        return false
    }

    private fun penButtonPressed(e: MotionEvent): Boolean =
        samsungButtonEvent || (e.buttonState and StylusSession.BUTTON_MASK) != 0

    // ---------------------------------------------------------- Handballenerkennung

    /** Großflächiger Kontakt oder vom System als Handballen gemeldet. */
    private fun isPalmContact(e: MotionEvent, idx: Int): Boolean {
        val strength = state.prefs.palmRejection
        if (e.getToolType(idx) == StylusSession.TOOL_TYPE_PALM) return true
        if (strength <= 0 || e.getToolType(idx) != MotionEvent.TOOL_TYPE_FINGER) return false
        val major = e.getTouchMajor(idx)
        return major > StylusSession.palmSizeMm(strength) * pxPerMm
    }

    /** Ist der Stift gerade nah (schwebt, berührt oder war bis vor Kurzem in Reichweite)? */
    private fun stylusNear(now: Long): Boolean {
        val strength = state.prefs.palmRejection
        if (strength <= 0) return false
        if (mode == Mode.STROKE && strokeIsStylus) return true
        // Ein verpasstes HOVER_EXIT (z. B. Stift über einem Panel verlassen) darf Finger nicht dauerhaft sperren
        if (hovering && now - lastHoverTime < 2000L) return true
        return now - StylusSession.lastStylusTime < StylusSession.palmWindowMs(strength)
    }

    /** Dürfen Finger gerade malen? ("Mit Finger malen" und "Nur Stift malt nach Stifterkennung") */
    private fun fingerMayPaint(): Boolean =
        state.prefs.fingerPainting && !(state.prefs.stylusOnlyAfterPen && StylusSession.stylusUsed)

    private fun isCanceledPointer(e: MotionEvent): Boolean =
        Build.VERSION.SDK_INT >= 33 && (e.flags and StylusSession.FLAG_CANCELED) != 0

    // ---------------------------------------------------------- Ereignisverteilung

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (state.document == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT -> return handleHover(e)
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                if (hasStylus(e)) trackPenButton(e, touching = mode == Mode.STROKE || mode == Mode.EYEDROPPER)
                return true
            }
        }
        // Samsung-S-Pen-Aktionen mit gedrückter Taste (ältere Geräte) auf Standardaktionen abbilden
        samsungButtonEvent = false
        when (e.actionMasked) {
            SPEN_ACTION_DOWN -> { e.action = MotionEvent.ACTION_DOWN; samsungButtonEvent = true }
            SPEN_ACTION_UP -> { e.action = MotionEvent.ACTION_UP; samsungButtonEvent = true }
            SPEN_ACTION_MOVE -> { e.action = MotionEvent.ACTION_MOVE; samsungButtonEvent = true }
            SPEN_ACTION_CANCEL -> { e.action = MotionEvent.ACTION_CANCEL; samsungButtonEvent = true }
        }
        requestFocus()
        prepareInverse()
        if (hasStylus(e)) {
            StylusSession.seen(e.eventTime, touched = true)
            if (hovering) { hovering = false; hoverPreview.reset() }
            trackPenButton(e, touching = true)
        }
        if (predictor != null && state.prefs.motionPrediction) {
            try { predictor.record(e) } catch (t: Throwable) { android.util.Log.w("Farbwerk", "predictor", t) }
        }
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

    override fun onHoverEvent(e: MotionEvent): Boolean = handleHover(e) || super.onHoverEvent(e)

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (state.document != null && hasStylus(e) &&
            (e.actionMasked == MotionEvent.ACTION_BUTTON_PRESS || e.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE)
        ) {
            trackPenButton(e, touching = mode == Mode.STROKE || mode == Mode.EYEDROPPER)
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    /** Stift schwebt über dem Display: Position für Vorschau/Handballenerkennung merken, Seitentaste verfolgen. */
    private fun handleHover(e: MotionEvent): Boolean {
        if (state.document == null) return false
        if (!isStylus(e, 0)) {
            if (hovering) { hovering = false; invalidate() }
            return false
        }
        StylusSession.seen(e.eventTime, touched = false)
        prepareInverse()
        samsungButtonEvent = false
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                // Manche Digitizer melden Schwebe-Ereignisse noch kurz nach dem Aufsetzen: während eines
                // Stiftstrichs nie die Schwebevorschau (Pinselumriss mit Füllung) aktivieren
                if (mode == Mode.STROKE && strokeIsStylus) return true
                if (!hovering) hoverPreview.reset()
                hovering = true
                lastHoverTime = e.eventTime
                hoverX = e.x; hoverY = e.y
                hoverTilt = e.getAxisValue(MotionEvent.AXIS_TILT, 0).coerceIn(0f, HALF_PI)
                hoverOrient = screenOrientationToDoc(e.getOrientation(0))
                hoverEraser = e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                trackPenButton(e, touching = false)
                if (state.prefs.hoverPreview && mode != Mode.STROKE) invalidate()
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                trackPenButton(e, touching = false)
                hovering = false
                invalidate()
            }
        }
        return true
    }

    /**
     * Verfolgt die Seitentaste über Zustandswechsel (funktioniert unabhängig davon, ob das Gerät
     * ACTION_BUTTON_PRESS sendet oder nur buttonState in Schwebe-/Berührungsereignissen setzt).
     * Drücken und Loslassen ohne Berührung dazwischen = Klick in der Luft → [performHoverClick].
     */
    private fun trackPenButton(e: MotionEvent, touching: Boolean) {
        val pressed = penButtonPressed(e)
        if (pressed && !penButtonDown) {
            penButtonDown = true
            penButtonDownTime = e.eventTime
            penButtonUsed = touching
            lastPenButtonMotion = SystemClock.uptimeMillis()
            if (hovering) invalidate()
        } else if (!pressed && penButtonDown) {
            penButtonDown = false
            lastPenButtonMotion = SystemClock.uptimeMillis()
            if (!penButtonUsed && !touching && e.eventTime - penButtonDownTime < 1000L) performHoverClick()
            if (hovering) invalidate()
        } else if (pressed && touching) {
            penButtonUsed = true
        }
    }

    /** Seitentaste in der Luft geklickt (Stift schwebt). */
    private fun performHoverClick() {
        if (mode == Mode.STROKE || mode == Mode.EYEDROPPER || mode == Mode.GESTURE) return
        val action = StylusButtonAction.fromId(state.prefs.stylusButtonHoverAction)
        if (action == StylusButtonAction.OFF) return
        prepareInverse()
        mapTmp[0] = hoverX; mapTmp[1] = hoverY
        invDisplay.mapPoints(mapTmp)
        StylusActions.performClick(state, action, mapTmp[0], mapTmp[1])
        invalidate()
    }

    /**
     * Stift-Tastenereignisse (Android 14+: KEYCODE_STYLUS_BUTTON_*), falls das Gerät die Seitentaste so meldet
     * statt über MotionEvent.buttonState. Wird ignoriert, wenn die Taste gerade schon über Bewegungsereignisse
     * erkannt wurde.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in KEYCODE_STYLUS_FIRST..KEYCODE_STYLUS_LAST) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in KEYCODE_STYLUS_FIRST..KEYCODE_STYLUS_LAST) {
            if (SystemClock.uptimeMillis() - lastPenButtonMotion > 300L && !penButtonDown) performHoverClick()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // ---------------------------------------------------------- Berührungen

    private fun onFirstDown(e: MotionEvent) {
        cancelTimers()
        fingerSuppressed = false
        strokeTempTool = null
        // Geringste Latenz: Ereignisse dieses Kontakts nicht bündeln (nicht erst zum nächsten Frame liefern)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) requestUnbufferedDispatch(e)
        state.copyPasteMenuVisible = false
        if (state.openPanel != at.techgaisi.farbwerk.model.Panel.NONE) {
            // Tippen auf die Leinwand schließt offene Panels (wie im Original) – und malt nicht.
            state.openPanel = at.techgaisi.farbwerk.model.Panel.NONE
            mode = Mode.BLOCKED
            return
        }
        val stylus = isStylus(e, 0)
        idleFingerTap = false
        if (state.quickMenuAt != null) {
            // Offenes QuickMenu: Wegtippen schließt (Finger malt dabei nicht), Weitermalen mit dem Stift schließt und malt
            quickMenuHook?.dismiss() ?: run { state.quickMenuAt = null }
            if (!stylus) { mode = Mode.BLOCKED; return }
        }
        if (state.spaceHeld) {
            spacePanned = true
            mode = Mode.PAN_SPACE
            gestureLastCx = e.x; gestureLastCy = e.y
            return
        }
        if (!stylus && isPalmContact(e, 0)) {
            // Handballen: ganzen Kontakt verwerfen (ein später aufsetzender Stift malt trotzdem)
            mode = Mode.BLOCKED
            return
        }
        if (stylus && handleStylusButtonDown(e, 0)) return
        // Modifizierer der Seitenleiste gehalten + Leinwand berühren
        when (heldModifier) {
            GestureFunction.SMUDGE -> { modifierUsed = true; strokeTempTool = Tool.SMUDGE }
            GestureFunction.ERASE -> { modifierUsed = true; strokeTempTool = Tool.ERASE }
            GestureFunction.QUICKMENU -> { modifierUsed = true; openQuickMenu(e.x, e.y, dragging = true); if (mode == Mode.QUICKMENU) return }
            GestureFunction.SELECT_LAYER -> { modifierUsed = true; selectLayerAt(e.x, e.y); mode = Mode.BLOCKED; return }
            GestureFunction.EYEDROPPER -> modifierUsed = true
            null -> {}
        }
        if (state.eyedropperArmed) {
            startEyedropper(toDoc(e, 0), e.getPointerId(0), stylus)
            return
        }
        if (!stylus) {
            strokeStartX = e.x; strokeStartY = e.y
            strokeIsStylus = false
            strokeStartTime = e.eventTime
            if (stylusNear(e.eventTime)) {
                // Stift in der Nähe: einzelner Finger-Kontakt ist sehr wahrscheinlich der Handballen
                fingerSuppressed = true
                mode = Mode.IDLE
                return
            }
            if (!fingerMayPaint() && state.tool in BRUSH_TOOLS) {
                // Finger malen nicht: nur Gesten/Pipette
                mode = Mode.IDLE
                idleFingerTap = true
                scheduleHoldEyedropper(e)
                return
            }
        }
        prepareTempTool(e, 0)
        startStroke(e, 0)
    }

    /**
     * Seitentaste beim Aufsetzen gedrückt: konfigurierte Aktion ausführen.
     * @return true, wenn der Kontakt damit erledigt ist (kein Strich)
     */
    private fun handleStylusButtonDown(e: MotionEvent, idx: Int): Boolean {
        if (!penButtonPressed(e)) return false
        penButtonUsed = true
        return when (StylusButtonAction.fromId(state.prefs.stylusButtonAction)) {
            StylusButtonAction.OFF -> false
            StylusButtonAction.EYEDROPPER -> {
                // Taste halten + ziehen = Pipette mit Lupe, Farbe wird beim Abheben übernommen
                startEyedropper(toDoc(e, idx), e.getPointerId(idx), true)
                true
            }
            StylusButtonAction.ERASER -> {
                // Radieren, solange die Taste gehalten wird (bzw. Malen, wenn der Radierer aktiv ist)
                strokeTempTool = when (state.tool) {
                    Tool.PAINT, Tool.SMUDGE -> Tool.ERASE
                    Tool.ERASE -> Tool.PAINT
                    else -> null
                }
                false
            }
            StylusButtonAction.UNDO -> { state.undo.undo(); mode = Mode.BLOCKED; true }
            StylusButtonAction.REDO -> { state.undo.redo(); mode = Mode.BLOCKED; true }
            StylusButtonAction.COLORS -> {
                state.openPanel = at.techgaisi.farbwerk.model.Panel.COLORS
                mode = Mode.BLOCKED
                true
            }
        }
    }

    /** Radierer-Ende des Stifts: Malen/Wischen wird für diesen Strich zum Radieren. */
    private fun prepareTempTool(e: MotionEvent, idx: Int) {
        if (strokeTempTool != null) return
        if (e.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER && (state.tool == Tool.PAINT || state.tool == Tool.SMUDGE)) {
            strokeTempTool = Tool.ERASE
        }
    }

    private fun startStroke(e: MotionEvent, idx: Int) {
        val temp = strokeTempTool?.takeIf { it in BRUSH_TOOLS }
        val override = state.canvasOverride
        val handlerTool = if (override != null) {
            override
        } else if (temp != null) {
            state.brushEngine.temporaryTool = temp
            state.brushEngine
        } else {
            state.brushEngine.temporaryTool = null
            state.activeHandler()
        }
        strokeTempTool = null
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
        onStrokeActivity?.invoke(true)
        if (!strokeIsStylus && state.tool in BRUSH_TOOLS) scheduleHoldEyedropper(e)
        invalidate()
    }

    private fun onPointerDown(e: MotionEvent) {
        val idx = e.actionIndex
        idleFingerTap = false
        if (mode == Mode.QUICKMENU) return
        if (isStylus(e, idx)) { onStylusJoins(e, idx); return }
        if (isPalmContact(e, idx)) { onPalmJoins(); return }
        when (mode) {
            Mode.STROKE -> {
                if (strokeIsStylus) return // Stift malt weiter, Finger werden ignoriert (Handballen)
                // Wenn ein zweiter Finger nach kurzem Strich dazukommt → Strich verwerfen, Geste starten
                val age = e.eventTime - strokeStartTime
                if (age < 350 || !strokeMoved) {
                    cancelStroke()
                    startGesture(e)
                } else {
                    // Langer Strich: QuickShape-Perfektionierung (zweiter Finger tippt) an Werkzeug melden
                    lastPoint?.let { strokeHandler?.onHoldStill(it) }
                }
            }
            Mode.IDLE, Mode.EYEDROPPER -> {
                if (mode == Mode.EYEDROPPER) {
                    if (eyedropperByStylus) return // Pipette per Stift: weitere Finger ignorieren
                    state.eyedropper.cancel(); eyedropperActive = false
                }
                cancelTimers()
                startGesture(e)
            }
            Mode.GESTURE -> {
                gestureMaxPointers = max(gestureMaxPointers, e.pointerCount)
                recordGesturePointers(e)
                resetGestureGeometry(e)
                cancelTimers()
                if (!gestureSuppressTaps) scheduleRapid()
            }
            else -> {}
        }
    }

    /** Ein Handballen kommt zu einem laufenden Kontakt hinzu: Finger-Aktionen verwerfen, Stift-Striche bleiben. */
    private fun onPalmJoins() {
        when (mode) {
            Mode.STROKE -> if (!strokeIsStylus) { cancelStroke(); mode = Mode.BLOCKED }
            Mode.GESTURE -> { gestureConsumed = true; cancelRapid() }
            Mode.IDLE -> { cancelTimers(); mode = Mode.BLOCKED }
            else -> {}
        }
    }

    /**
     * Der Stift setzt auf, während schon Finger (meist der Handballen) liegen: Finger-Aktion verwerfen
     * und mit dem Stift malen – wie Procreate, das den Stift immer bevorzugt.
     */
    private fun onStylusJoins(e: MotionEvent, idx: Int) {
        when (mode) {
            Mode.STROKE -> {
                if (strokeIsStylus) return
                cancelStroke()
            }
            Mode.GESTURE -> {
                if (gestureMoved) return // laufendes Zoomen/Drehen nicht unterbrechen
                cancelRapid(); rapidRepeating = false
                gestureConsumed = true
            }
            Mode.EYEDROPPER -> {
                if (eyedropperByStylus) return
                state.eyedropper.cancel(); eyedropperActive = false
            }
            Mode.PAN_SPACE -> return
            else -> {}
        }
        cancelTimers()
        fingerSuppressed = false
        strokeTempTool = null
        if (handleStylusButtonDown(e, idx)) return
        if (state.eyedropperArmed) {
            startEyedropper(toDoc(e, idx), e.getPointerId(idx), true)
            return
        }
        prepareTempTool(e, idx)
        startStroke(e, idx)
    }

    private fun onMove(e: MotionEvent) {
        when (mode) {
            Mode.STROKE -> {
                val idx = e.findPointerIndex(strokePointerId)
                if (idx < 0) return
                // Finger-Strich, dessen Kontaktfläche kurz nach dem Aufsetzen zum Handballen anwächst → verwerfen
                if (!strokeIsStylus && !strokeMoved && isPalmContact(e, idx)) {
                    cancelStroke()
                    mode = Mode.BLOCKED
                    return
                }
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
                val h = strokeHandler
                try { h?.onStrokeMove(pts) } catch (t: Throwable) { android.util.Log.e("Farbwerk", "move", t) }
                dispatchPrediction(h)
                invalidate()
            }
            Mode.GESTURE -> {
                if (!gestureMoved && !gestureConsumed) {
                    for (i in 0 until e.pointerCount) if (isPalmContact(e, i)) { gestureConsumed = true; cancelRapid(); break }
                }
                onGestureMove(e)
            }
            Mode.EYEDROPPER -> {
                val idx = e.findPointerIndex(eyedropperPointerId).let { if (it < 0) 0 else it }
                state.eyedropper.move(toDoc(e, idx)); invalidate()
            }
            Mode.QUICKMENU -> {
                val w = viewToWindow(e.x, e.y)
                quickMenuHook?.dragTo(w.x, w.y)
            }
            Mode.PAN_SPACE -> {
                viewMatrix.postTranslate(e.x - gestureLastCx, e.y - gestureLastCy)
                gestureLastCx = e.x; gestureLastCy = e.y
                updateInverse(); invalidate()
            }
            Mode.IDLE -> {
                if (fingerSuppressed && isPalmContact(e, 0)) { mode = Mode.BLOCKED; return }
                if (hypot(e.x - strokeStartX, e.y - strokeStartY) > slop) {
                    handler.removeCallbacks(holdEyedropperRunnable)
                    idleFingerTap = false
                }
            }
            else -> {}
        }
    }

    /**
     * Fragt die Bewegungsvorhersage ab und reicht die vorhergesagten Punkte als temporäre Vorschau an das
     * Werkzeug weiter (sie werden beim nächsten echten Punkt verworfen).
     */
    private fun dispatchPrediction(h: ToolInputHandler?) {
        if (h == null) return
        val pr = predictor ?: return
        if (!state.prefs.motionPrediction) return
        predictedPts.clear()
        val pe = try { pr.predict() } catch (t: Throwable) { null }
        if (pe != null) {
            try {
                var idx = pe.findPointerIndex(strokePointerId)
                // Ohne passende Zeiger-ID nur bei eindeutigem Einzelzeiger verwenden (nie den Handballen vorhersagen)
                if (idx < 0 && pe.pointerCount == 1) idx = 0
                if (idx >= 0) {
                    for (k in 0 until pe.historySize) predictedPts.add(toDoc(pe, idx, k, strokeIsStylus))
                    predictedPts.add(toDoc(pe, idx, -1, strokeIsStylus))
                }
            } finally {
                pe.recycle()
            }
        }
        try { h.onPredicted(predictedPts) } catch (t: Throwable) { android.util.Log.e("Farbwerk", "predict", t) }
    }

    private fun onPointerUp(e: MotionEvent) {
        val pid = e.getPointerId(e.actionIndex)
        val canceled = isCanceledPointer(e)
        if (mode == Mode.STROKE && pid == strokePointerId) {
            // FLAG_CANCELED: System hat den Kontakt nachträglich als unbeabsichtigt (Handballen) erkannt
            if (canceled) cancelStroke() else finishStroke(toDoc(e, e.actionIndex))
            mode = Mode.BLOCKED
        } else if (mode == Mode.EYEDROPPER && pid == eyedropperPointerId) {
            if (canceled) state.eyedropper.cancel() else state.eyedropper.end(toDoc(e, e.actionIndex))
            eyedropperActive = false
            state.eyedropperArmed = false
            mode = Mode.BLOCKED
            invalidate()
        } else if (mode == Mode.GESTURE) {
            if (canceled) { gestureConsumed = true; cancelRapid() }
            resetGestureGeometry(e, excludeIndex = e.actionIndex)
        }
    }

    private fun onLastUp(e: MotionEvent) {
        cancelTimers()
        val canceled = isCanceledPointer(e)
        when (mode) {
            Mode.STROKE -> {
                val idx = e.findPointerIndex(strokePointerId).let { if (it < 0) 0 else it }
                if (canceled) cancelStroke() else finishStroke(toDoc(e, idx))
            }
            Mode.GESTURE -> if (!canceled) endGesture(e)
            Mode.EYEDROPPER -> {
                if (canceled) state.eyedropper.cancel() else state.eyedropper.end(toDoc(e, 0))
                eyedropperActive = false
                state.eyedropperArmed = false
            }
            Mode.QUICKMENU -> {
                val w = viewToWindow(e.x, e.y)
                if (canceled) quickMenuHook?.dismiss() else quickMenuHook?.release(w.x, w.y)
            }
            Mode.IDLE -> {
                // Auslöser „Tippen“ (Finger, während nur der Stift malt)
                if (idleFingerTap && !fingerSuppressed && !canceled && e.eventTime - strokeStartTime < 350 &&
                    hypot(e.x - strokeStartX, e.y - strokeStartY) <= slop &&
                    GestureMap.owner(state.prefs, GestureTrigger.FINGER_TAP) == GestureFunction.QUICKMENU
                ) openQuickMenu(e.x, e.y, dragging = false)
            }
            else -> {}
        }
        idleFingerTap = false
        mode = Mode.IDLE
        fingerSuppressed = false
        invalidate()
    }

    private fun onCancel() {
        cancelTimers()
        if (mode == Mode.STROKE) cancelStroke()
        if (mode == Mode.EYEDROPPER) { state.eyedropper.cancel(); eyedropperActive = false }
        if (mode == Mode.QUICKMENU) quickMenuHook?.dismiss()
        idleFingerTap = false
        mode = Mode.IDLE
        fingerSuppressed = false
        invalidate()
    }

    private fun finishStroke(p: InputPoint) {
        handler.removeCallbacks(quickShapeRunnable); quickShapeScheduled = false
        handler.removeCallbacks(holdEyedropperRunnable)
        // Handler vor dem Aufruf lösen: falls onStrokeEnd z. B. einen Werkzeugwechsel auslöst,
        // darf abortStroke() diesen bereits beendeten Strich nicht zusätzlich abbrechen.
        val h = strokeHandler
        strokeHandler = null
        strokePointerId = -1
        try { h?.onStrokeEnd(p) } catch (t: Throwable) { android.util.Log.e("Farbwerk", "end", t) }
        state.brushEngine.temporaryTool = null
        // Zuletzt bemalte Stelle (schwebende Pipette per Modifizierer)
        if (h === state.brushEngine) state.lastPaintPoint = android.graphics.PointF(p.x, p.y)
        onStrokeActivity?.invoke(false)
        invalidate()
    }

    /**
     * Bricht einen laufenden Strich ab (Werkzeugwechsel, Rückgängig per Taste/Schaltfläche, Galerie).
     * Die restlichen Bewegungen dieses Kontakts werden ignoriert, bis alle Finger/Stift abgehoben sind.
     */
    fun abortStroke() {
        if (mode == Mode.STROKE && strokeHandler != null) {
            cancelStroke()
            mode = Mode.BLOCKED
        }
    }

    private fun cancelStroke() {
        handler.removeCallbacks(quickShapeRunnable); quickShapeScheduled = false
        handler.removeCallbacks(holdEyedropperRunnable)
        try { strokeHandler?.onStrokeCancel() } catch (t: Throwable) { android.util.Log.e("Farbwerk", "cancel", t) }
        state.brushEngine.temporaryTool = null
        if (strokeHandler != null) onStrokeActivity?.invoke(false)
        strokeHandler = null
        strokePointerId = -1
        mode = Mode.IDLE
        invalidate()
    }

    // ---------------------------------------------------------- Pipette (Berühren & Halten)

    private var eyedropperActive = false
    private var eyedropperPointerId = -1
    private var eyedropperByStylus = false
    private var holdEvent: MotionEvent? = null
    private val holdEyedropperRunnable = Runnable {
        val ev = holdEvent ?: return@Runnable
        if (state.canvasOverride != null) return@Runnable // Overlay-Werkzeuge (Zuschneiden, Text …) nicht stören
        if (mode == Mode.STROKE && !strokeMoved) cancelStroke()
        if (mode == Mode.STROKE || mode == Mode.GESTURE || mode == Mode.BLOCKED) return@Runnable
        idleFingerTap = false
        when (GestureMap.owner(state.prefs, GestureTrigger.TOUCH_HOLD)) {
            GestureFunction.QUICKMENU -> openQuickMenu(ev.x, ev.y, dragging = true)
            GestureFunction.SELECT_LAYER -> { selectLayerAt(ev.x, ev.y); mode = Mode.BLOCKED }
            else -> {
                prepareInverse()
                startEyedropper(toDoc(ev, 0), ev.getPointerId(0), false)
            }
        }
    }

    private fun scheduleHoldEyedropper(e: MotionEvent) {
        val owner = GestureMap.owner(state.prefs, GestureTrigger.TOUCH_HOLD) ?: return
        holdEvent?.recycle()
        holdEvent = MotionEvent.obtain(e)
        handler.removeCallbacks(holdEyedropperRunnable)
        val delay = if (owner == GestureFunction.EYEDROPPER) state.prefs.holdDelayMs else state.prefs.holdMenuDelayMs
        handler.postDelayed(holdEyedropperRunnable, delay.toLong())
    }

    private fun startEyedropper(p: InputPoint, pointerId: Int, byStylus: Boolean) {
        mode = Mode.EYEDROPPER
        eyedropperActive = true
        eyedropperPointerId = pointerId
        eyedropperByStylus = byStylus
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
        gesturePointerStart.clear()
        gestureMaxTravel = 0f
        recordGesturePointers(e)
        resetGestureGeometry(e)
        gestureStartSpan = gestureLastSpan
        gestureStartCy = gestureLastCy
        gestureScrubAnchorX = gestureLastCx
        // Lag der erste Kontakt schon länger, während der Stift nahe war (aufliegender Handballen + Finger),
        // keine Tipp-Aktionen (Rückgängig …) auslösen. Gleichzeitig aufgesetzte Finger (bewusstes Tippen mit der
        // anderen Hand, während der Stift schwebt) bleiben erlaubt.
        // Zwei-Finger-Geste innerhalb eines Werkzeugbereichs (z. B. Transform-Rahmen) ans Werkzeug geben
        gestureToTool = false
        if (e.pointerCount == 2) {
            val p = viewToDocPoint(gestureLastCx, gestureLastCy)
            gestureToTool = try { state.activeHandler()?.handlesTwoFingerAt(p[0], p[1]) == true } catch (t: Throwable) { false }
        }
        val restMs = if (state.prefs.palmRejection >= 2) 100L else 200L
        gestureSuppressTaps = fingerSuppressed && e.eventTime - strokeStartTime > restMs
        if (!gestureSuppressTaps) scheduleRapid()
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

        // Kumulierte Bewegung seit Gestenbeginn (nicht nur seit dem letzten Ereignis): langsames Zoomen/
        // Verschieben darf nie als Tippen (Rückgängig) oder Halten (schnelles Rückgängig) gelten.
        updateGestureTravel(e)
        val spanDelta = if (e.pointerCount >= 2 && gestureStartSpan > 0f) abs(spanOf(e) - gestureStartSpan) else 0f
        if (!gestureMoved && (gestureMaxTravel > slop * 0.8f || spanDelta > slop * 0.8f)) {
            gestureMoved = true
            cancelRapid()
        }

        when {
            e.pointerCount == 2 && gestureMaxPointers == 2 -> {
                val span = spanOf(e)
                val angle = atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0))
                if (gestureMoved && gestureToTool) {
                    val a = viewToDocPoint(gestureLastCx, gestureLastCy)
                    val b = viewToDocPoint(cx, cy)
                    val s = if (gestureLastSpan > 0f && span > 0f) span / gestureLastSpan else 1f
                    var da = angle - gestureLastAngle
                    if (da > Math.PI) da -= (2 * Math.PI).toFloat()
                    if (da < -Math.PI) da += (2 * Math.PI).toFloat()
                    val doc = state.document
                    val flipSign = if (doc != null && (doc.flipHorizontal xor doc.flipVertical)) -1f else 1f
                    try {
                        state.activeHandler()?.onTwoFingerTransform(b[0], b[1], b[0] - a[0], b[1] - a[1], s,
                            flipSign * Math.toDegrees(da.toDouble()).toFloat())
                    } catch (t: Throwable) { android.util.Log.e("Farbwerk", "twoFinger", t) }
                    invalidate()
                } else if (gestureMoved) {
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
                    if (state.prefs.rotateWithPinch) viewMatrix.postRotate(Math.toDegrees(da.toDouble()).toFloat(), cx, cy)
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

    private var gestureToTool = false

    private fun viewToDocPoint(x: Float, y: Float): FloatArray {
        val pts = floatArrayOf(x, y)
        val inv = Matrix(); displayMatrix().invert(inv)
        inv.mapPoints(pts)
        return pts
    }

    private fun endGesture(e: MotionEvent) {
        if (gestureToTool) {
            gestureToTool = false
            try { state.activeHandler()?.onTwoFingerEnd() } catch (t: Throwable) { android.util.Log.e("Farbwerk", "twoFingerEnd", t) }
            if (gestureMoved) return
        }
        cancelRapid()
        val dur = e.eventTime - gestureStartTime
        if (rapidRepeating) { rapidRepeating = false; return }
        if (gestureConsumed) return
        // Tippen = kurz und praktisch ohne Bewegung (wie im Vorbild); alles andere war Zoomen/Verschieben.
        if (!gestureMoved && dur < 300 && gestureMaxTravel < slop * 0.8f) {
            if (gestureSuppressTaps) return
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

    private companion object {
        const val HALF_PI = (Math.PI / 2.0).toFloat()
        val BRUSH_TOOLS = setOf(Tool.PAINT, Tool.SMUDGE, Tool.ERASE)
        /** Samsung-S-Pen-Aktionen mit gedrückter Seitentaste (ältere Galaxy-Geräte). */
        const val SPEN_ACTION_DOWN = 211
        const val SPEN_ACTION_UP = 212
        const val SPEN_ACTION_MOVE = 213
        const val SPEN_ACTION_CANCEL = 214
        /** KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY … _TAIL (API 34). */
        const val KEYCODE_STYLUS_FIRST = 308
        const val KEYCODE_STYLUS_LAST = 311
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        state.canvasView = this
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Laufenden Strich sauber beenden (sonst bleibt z. B. drawOverride/PixelEdit offen)
        if (mode == Mode.STROKE) cancelStroke()
        if (mode == Mode.EYEDROPPER) { state.eyedropper.cancel(); eyedropperActive = false }
        if (floatingEyedropper) endFloatingEyedropper(commit = false)
        mode = Mode.IDLE
        cancelTimers()
        animator?.cancel()
        holdEvent?.recycle(); holdEvent = null
        // Keine Referenz auf eine abgelöste Ansicht behalten (Galerie → neues Dokument erzeugt eine neue View)
        if (state.canvasView === this) state.canvasView = null
    }
}

/**
 * Anbindung des QuickMenus an die Leinwand. Koordinaten sind Fenster-Koordinaten.
 * [openAt] mit dragging = true: Finger bleibt liegen, [dragTo]/[release] folgen (Schnellwahl per Ziehen).
 */
interface QuickMenuHook {
    fun openAt(windowX: Float, windowY: Float, dragging: Boolean)
    fun dragTo(windowX: Float, windowY: Float)
    fun release(windowX: Float, windowY: Float)
    fun dismiss()
}
