package at.techgaisi.farbwerk.transform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.InputPoint
import at.techgaisi.farbwerk.model.Layer
import at.techgaisi.farbwerk.model.Selection
import at.techgaisi.farbwerk.model.Tool
import at.techgaisi.farbwerk.model.ToolInputHandler
import at.techgaisi.farbwerk.selection.CompositeAction
import at.techgaisi.farbwerk.selection.MaskUtils
import at.techgaisi.farbwerk.selection.SelectionActions
import at.techgaisi.farbwerk.selection.SelectionPrefs
import at.techgaisi.farbwerk.undo.LayerPixelsAction
import at.techgaisi.farbwerk.undo.UndoAction
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class TransformMode(val label: String) {
    FREEFORM("Freiform"),
    UNIFORM("Gleichmäßig"),
    DISTORT("Verzerren"),
    WARP("Verkrümmen"),
}

enum class Interpolation(val label: String) {
    NEAREST("Nächster Nachbar"),
    BILINEAR("Bilinear"),
    BICUBIC("Bikubisch"),
}

/** Was das Zahlenfeld (nach Antippen eines Punkts) bearbeitet. */
enum class TransformNumericKind { SIZE, ANGLE }

/**
 * Inhalt des Zahlenfelds: aktuelle Breite/Höhe in px bzw. Winkel in Grad (positiv = gegen den
 * Uhrzeigersinn, wie im Vorbild).
 */
data class TransformNumericInput(
    val kind: TransformNumericKind,
    val width: Float,
    val height: Float,
    val angle: Float,
    /** Seitenverhältnis gekoppelt (Gleichmäßig). */
    val linked: Boolean,
)

/**
 * Transformieren: hebt den Inhalt der aktiven Ebene **und aller sekundär markierten Ebenen** (bzw. den
 * Auswahlbereich darin) als schwebende Bitmaps ab. Alle Teile teilen dieselbe Geometrie – ein Viereck
 * (4 Ecken, Dokument-Koordinaten) bzw. nach dem Verkrümmen ein 4×4-Kontrollgitter. Die Ebenen zeigen während
 * der Sitzung per drawOverride Rest + schwebenden Inhalt; erst [commit] schreibt die Pixel
 * (ein Verlaufsschritt „Transformieren“ für alle Ebenen).
 *
 * Gesten: 1 Finger innerhalb/außerhalb = verschieben, Tippen außerhalb = schubsen, Punkte ziehen = skalieren
 * (mit Größenanzeige B × H), grüner Punkt = drehen (mit Winkelanzeige), Punkt antippen = Zahlenfeld,
 * Kneifen/Drehen mit zwei Fingern **innerhalb** des Rahmens = gleichmäßig skalieren + drehen um die Mitte.
 *
 * Öffentliche API für die Tastatur (WP1): [nudge].
 */
class TransformTool(private val state: EditorState) : ToolInputHandler {

    // ---------------------------------------------------------------- UI-Zustand
    var mode by mutableStateOf(TransformMode.UNIFORM)
        private set
    var interpolation by mutableStateOf(Interpolation.BILINEAR)
        private set

    /** Magnetisch: Verschieben auf 15°-Achsen, Drehen in 15°-Schritten, Skalieren in 5-%-Schritten. */
    var magnetic: Boolean
        get() = SelectionPrefs.magnetic
        set(v) = SelectionPrefs.updateMagnetic(v)
    /** Einrasten: blaue Hilfslinien an anderen Objekten, gelbe an Leinwandmitte/-rändern. */
    var snapping: Boolean
        get() = SelectionPrefs.snapping
        set(v) = SelectionPrefs.updateSnapping(v)
    /** Einrast-Abstand in dp. */
    var snapDistance: Float
        get() = SelectionPrefs.snapDistance
        set(v) = SelectionPrefs.updateSnapDistance(v)
    /** Geschwindigkeit 0..1 (Keine … Max): bis zu welcher Zieh-Geschwindigkeit die Hilfslinien greifen. */
    var snapSpeed: Float
        get() = SelectionPrefs.snapSpeed
        set(v) = SelectionPrefs.updateSnapSpeed(v)

    /** true, solange eine Transform-Sitzung läuft. */
    var active by mutableStateOf(false)
        private set
    /** Anzahl der Ebenen in der laufenden Sitzung (für die Leiste). */
    var layerCount by mutableStateOf(0)
        private set
    /** Offenes Zahlenfeld (nach Antippen eines Punkts) oder null. */
    var numericInput by mutableStateOf<TransformNumericInput?>(null)
        private set

    private val density = state.context.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())

    init {
        SelectionPrefs.init(state.context)
    }

    // ---------------------------------------------------------------- Sitzung
    /** Eine Ebene der Sitzung. */
    private class Part(
        val layer: Layer,
        /** Ebene ohne den abgehobenen Inhalt (nur bei Auswahl; sonst ist der Rest leer). */
        val base: Bitmap?,
        val floating: Bitmap,
    ) {
        var draw: ((Canvas) -> Unit)? = null
    }

    private class Session(
        val parts: List<Part>,
        val srcRect: Rect,
        val selection: Selection?,
        /** Auswahlmaske im Koordinatensystem der schwebenden Bitmaps. */
        val floatingMask: Bitmap?,
        val undoVersion: Int,
    ) {
        val primary: Layer get() = parts[0].layer
    }

    private var s: Session? = null
    private val corners = FloatArray(8)       // p0 TL, p1 TR, p2 BR, p3 BL
    private val initCorners = FloatArray(8)
    private val mesh = FloatArray(GRID * GRID * 2)
    private var warped = false
    private val fine = FloatArray((FINE + 1) * (FINE + 1) * 2)
    private var fineDirty = true
    private val matrix = Matrix()
    private val srcPts = FloatArray(8)

    private val previewPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val srcPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

    // ---------------------------------------------------------------- Ziehen
    private enum class Op { NONE, MOVE, ROTATE, CORNER, EDGE, WARP_POINT, WARP_SOFT, NUDGE }
    private var op = Op.NONE
    private var opIndex = 0
    private var downX = 0f
    private var downY = 0f
    private var dragMoved = false
    private val startCorners = FloatArray(8)
    private val startMesh = FloatArray(GRID * GRID * 2)
    private var startWarped = false
    private val softWeights = FloatArray(GRID * GRID)

    /** Magnetisch: Achse, auf der verschoben wird (fixiert nach kurzer Zugstrecke). */
    private var axisLocked = false
    private var axisAngle = 0.0

    /** Geglättete Zieh-Geschwindigkeit in dp/ms (für „Geschwindigkeit“ beim Einrasten). */
    private var speedDp = 0f
    private var lastMoveViewX = 0f
    private var lastMoveViewY = 0f
    private var lastMoveTime = 0L

    // ---------------------------------------------------------------- Einrasten
    /** Senkrechte (x = const) und waagrechte (y = const) Hilfslinie; NaN = keine. */
    private var guideX = Float.NaN
    private var guideXObject = false
    private var guideY = Float.NaN
    private var guideYObject = false
    private var hadGuide = false
    /** Einrastziele (Dokument-Koordinaten) mit Herkunft (true = Objekt, false = Leinwand). */
    private var snapXs: FloatArray? = null
    private var snapXObj: BooleanArray? = null
    private var snapYs: FloatArray? = null
    private var snapYObj: BooleanArray? = null

    // ---------------------------------------------------------------- Zwei Finger
    private var pinching = false
    private var pinchDx = 0f
    private var pinchDy = 0f
    private var pinchScale = 1f
    private var pinchRot = 0f

    /** Welche Werte gerade neben dem Rahmen angezeigt werden. */
    private var showSize = false
    private var showAngle = false

    // ---------------------------------------------------------------- Overlay
    private val viewMatrix = Matrix()
    private val tmp = FloatArray(64)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = ACCENT
    }
    private val boxDash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = Color.WHITE
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3.5f * density; color = 0x55000000
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density; color = Color.WHITE
    }
    private val handleShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }
    private val rotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF34C759.toInt() }
    private val canvasGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density; color = 0xFFFFCC00.toInt()
    }
    private val objectGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density; color = 0xFF2F9BFF.toInt()
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE61C1C1E.toInt() }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * state.context.resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        fontFeatureSettings = "tnum"
        textAlign = Paint.Align.CENTER
    }
    private val labelRect = RectF()
    private val overlayPath = Path()
    private val mid = FloatArray(2)

    // ================================================================= Lebenszyklus

    fun begin() {
        if (s != null) return
        val doc = state.document ?: return
        val primary = doc.activeLayer ?: return
        if (primary.locked) { state.flash("Ebene gesperrt"); return }

        // Primär- und Sekundärebenen (gesperrte werden übersprungen)
        val layers = ArrayList<Layer>()
        layers += primary
        var skippedLocked = false
        if (doc.secondarySelection.isNotEmpty()) {
            for (l in doc.layers) {
                if (l === primary || l.id !in doc.secondarySelection) continue
                if (l.locked) skippedLocked = true else layers += l
            }
        }

        val sel = doc.selection
        val rect: Rect
        val used: List<Layer>
        if (sel != null) {
            rect = MaskUtils.bounds(sel.mask) ?: run { state.flash("Nichts zum Transformieren"); return }
            used = layers
        } else {
            var union: Rect? = null
            val withContent = ArrayList<Layer>(layers.size)
            for (l in layers) {
                val b = MaskUtils.alphaBounds(l.bitmap) ?: continue
                withContent += l
                if (union == null) union = Rect(b) else union.union(b)
            }
            rect = union ?: run { state.flash("Nichts zum Transformieren"); return }
            // Primärebene bleibt immer erste (bestimmt das Sitzungsende bei Ebenenwechsel)
            if (withContent.firstOrNull() !== primary) withContent.add(0, primary)
            used = withContent
        }

        val fMask: Bitmap? = sel?.let { MaskUtils.crop(it.mask, rect) }
        val dstIn = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        val dstOut = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
        val parts = used.map { l ->
            val floating = MaskUtils.crop(l.bitmap, rect)
            var base: Bitmap? = null
            if (sel != null && fMask != null) {
                Canvas(floating).drawBitmap(fMask, 0f, 0f, dstIn)
                base = l.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                Canvas(base).drawBitmap(sel.mask, 0f, 0f, dstOut)
            }
            floating.setHasMipMap(interpolation == Interpolation.BICUBIC)
            Part(l, base, floating)
        }
        val ss = Session(parts, rect, sel, fMask, state.undo.version)
        s = ss
        val l = rect.left.toFloat(); val t = rect.top.toFloat()
        val r = rect.right.toFloat(); val b = rect.bottom.toFloat()
        setRect(corners, l, t, r, b)
        corners.copyInto(initCorners)
        srcPts[0] = 0f; srcPts[1] = 0f
        srcPts[2] = rect.width().toFloat(); srcPts[3] = 0f
        srcPts[4] = rect.width().toFloat(); srcPts[5] = rect.height().toFloat()
        srcPts[6] = 0f; srcPts[7] = rect.height().toFloat()
        warped = false
        fineDirty = true
        updateMatrix()
        for (p in parts) {
            val fn: (Canvas) -> Unit = { c -> drawPart(c, p) }
            p.draw = fn
            p.layer.drawOverride = fn
        }
        active = true
        layerCount = parts.size
        numericInput = null
        if (skippedLocked) state.flash("Gesperrte Ebenen werden nicht transformiert")
        state.invalidate()
    }

    /** Ergebnis in die Ebenen schreiben (ein Verlaufsschritt). */
    fun commit() {
        val ss = s ?: return
        val doc = state.document
        if (doc == null || ss.parts.none { doc.indexOf(it.layer) >= 0 } || isUnchanged()) { cancel(); return }
        val bounds = transformedBounds()
        val dirty = Rect(ss.srcRect)
        dirty.union(Rect(floor(bounds.left).toInt() - 1, floor(bounds.top).toInt() - 1,
            bounds.right.toInt() + 2, bounds.bottom.toInt() + 2))
        if (!dirty.intersect(0, 0, doc.width, doc.height)) dirty.set(ss.srcRect)

        val actions = ArrayList<UndoAction>(ss.parts.size + 1)
        for (p in ss.parts) {
            val layer = p.layer
            if (layer.drawOverride === p.draw) layer.drawOverride = null
            if (doc.indexOf(layer) < 0) continue
            val before = MaskUtils.crop(layer.bitmap, dirty)
            val c = Canvas(layer.bitmap)
            if (p.base != null) c.drawBitmap(p.base, 0f, 0f, srcPaint) else c.drawColor(0, PorterDuff.Mode.CLEAR)
            drawFloatingFinal(c, p.floating, layer)
            val after = MaskUtils.crop(layer.bitmap, dirty)
            actions += LayerPixelsAction("Transformieren", layer, dirty, before, after)
        }
        if (ss.selection != null && ss.floatingMask != null) {
            val nm = MaskUtils.create(doc.width, doc.height)
            val mc = Canvas(nm)
            val mp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            if (warped) {
                ensureFine()
                mc.drawBitmapMesh(ss.floatingMask, FINE, FINE, fine, 0, null, 0, mp)
            } else {
                mc.save(); mc.concat(matrix); mc.drawBitmap(ss.floatingMask, 0f, 0f, mp); mc.restore()
            }
            val np = if (!warped) ss.selection.path?.let { p ->
                Path(p).apply {
                    offset(-ss.srcRect.left.toFloat(), -ss.srcRect.top.toFloat())
                    transform(matrix)
                }
            } else null
            val newSel = if (MaskUtils.bounds(nm) == null) null else Selection(nm, np).also { it.feather = ss.selection.feather }
            actions += SelectionActions.selectionChange(doc, ss.selection, newSel, "Transformieren")
            doc.selection = newSel
            SelectionActions.noteSelection(doc, newSel)
        }
        state.undo.push(CompositeAction("Transformieren", actions))
        end()
        state.invalidate()
    }

    /** Transformation verwerfen. */
    fun cancel() {
        val ss = s ?: return
        for (p in ss.parts) if (p.layer.drawOverride === p.draw) p.layer.drawOverride = null
        end()
        state.invalidate()
    }

    private fun end() {
        s = null
        active = false
        layerCount = 0
        op = Op.NONE
        pinching = false
        showSize = false; showAngle = false
        numericInput = null
        clearGuides()
        snapXs = null; snapYs = null; snapXObj = null; snapYObj = null
    }

    /** Prüft, ob sich Dokument/Ebene außerhalb der Sitzung geändert haben (z. B. Rückgängig-Geste). */
    private fun syncSession() {
        val ss = s ?: return
        val doc = state.document
        val stale = doc == null || doc.activeLayer !== ss.primary || state.undo.version != ss.undoVersion
        if (stale) main.post {
            val cur = s ?: return@post
            if (cur !== ss) return@post
            val d = state.document
            if (d != null && d.activeLayer !== ss.primary && state.undo.version == ss.undoVersion) commit() else cancel()
            if (state.tool == Tool.TRANSFORM) begin()
        }
    }

    // ================================================================= Toolbar-Befehle

    fun changeMode(m: TransformMode) {
        mode = m
        if (m == TransformMode.WARP) ensureMesh()
        numericInput = null
        state.invalidate()
    }

    fun changeInterpolation(i: Interpolation) {
        interpolation = i
        s?.parts?.forEach { it.floating.setHasMipMap(i == Interpolation.BICUBIC) }
        state.invalidate()
    }

    fun flipHorizontal() = applyLocal { m, cx, cy, a ->
        m.postRotate(-a, cx, cy); m.postScale(-1f, 1f, cx, cy); m.postRotate(a, cx, cy)
    }

    fun flipVertical() = applyLocal { m, cx, cy, a ->
        m.postRotate(-a, cx, cy); m.postScale(1f, -1f, cx, cy); m.postRotate(a, cx, cy)
    }

    fun rotate45() = applyLocal { m, cx, cy, _ -> m.postRotate(45f, cx, cy) }

    /**
     * Inhalt auf die Leinwand einpassen (zentriert, Drehung bleibt erhalten). Mit Magnetisch wird die
     * Leinwand ganz gefüllt (Überstand wird beim Übernehmen abgeschnitten), sonst passt der Inhalt hinein.
     */
    fun fitToCanvas() {
        val doc = state.document ?: return
        if (s == null) return
        val b = transformedBounds()
        if (b.width() <= 0f || b.height() <= 0f) return
        val sx = doc.width / b.width()
        val sy = doc.height / b.height()
        val sc = if (magnetic) max(sx, sy) else min(sx, sy)
        val m = Matrix()
        m.postScale(sc, sc, b.centerX(), b.centerY())
        m.postTranslate(doc.width / 2f - b.centerX(), doc.height / 2f - b.centerY())
        applyMatrix(m)
        state.invalidate()
    }

    fun reset() {
        if (s == null) return
        initCorners.copyInto(corners)
        warped = false
        fineDirty = true
        updateMatrix()
        if (mode == TransformMode.WARP) ensureMesh()
        numericInput = null
        state.invalidate()
    }

    /**
     * Schwebenden Inhalt um ([dx], [dy]) Dokument-Pixel verschieben (Pfeiltasten: 1 px).
     * Startet bei Bedarf eine Sitzung, wenn das Transform-Werkzeug aktiv ist.
     */
    fun nudge(dx: Float, dy: Float) {
        if (s == null && state.tool == Tool.TRANSFORM) begin()
        if (s == null || op != Op.NONE || pinching) return
        applyMatrix(Matrix().apply { setTranslate(dx, dy) })
        state.invalidate()
    }

    /** Zahlenfeld: exakte Größe in px setzen (um die Mitte, Drehung bleibt). */
    fun applyNumericSize(width: Float, height: Float) {
        if (s == null || width <= 0f || height <= 0f) return
        val cw = hypot(corners[2] - corners[0], corners[3] - corners[1])
        val ch = hypot(corners[6] - corners[0], corners[7] - corners[1])
        if (cw < 1e-3f || ch < 1e-3f) return
        val cx = (corners[0] + corners[2] + corners[4] + corners[6]) / 4f
        val cy = (corners[1] + corners[3] + corners[5] + corners[7]) / 4f
        val th = Math.toRadians(localAngleDeg(corners).toDouble())
        scaleAbout(cx, cy, th, width / cw, height / ch)
        numericInput = null
        state.invalidate()
    }

    /** Zahlenfeld: absoluten Winkel setzen (Grad, positiv = gegen den Uhrzeigersinn). */
    fun applyNumericAngle(deg: Float) {
        if (s == null) return
        val cur = displayAngle()
        val delta = deg - cur
        val cx = (corners[0] + corners[2] + corners[4] + corners[6]) / 4f
        val cy = (corners[1] + corners[3] + corners[5] + corners[7]) / 4f
        applyMatrix(Matrix().apply { setRotate(-delta, cx, cy) })
        numericInput = null
        state.invalidate()
    }

    fun dismissNumericInput() { numericInput = null }

    private inline fun applyLocal(block: (Matrix, Float, Float, Float) -> Unit) {
        if (s == null) return
        val cx = (corners[0] + corners[2] + corners[4] + corners[6]) / 4f
        val cy = (corners[1] + corners[3] + corners[5] + corners[7]) / 4f
        val a = localAngleDeg(corners)
        val m = Matrix()
        block(m, cx, cy, a)
        applyMatrix(m)
        state.invalidate()
    }

    // ================================================================= Eingabe (ein Finger / Stift)

    override fun onStrokeStart(p: InputPoint) {
        syncSession()
        if (s == null || pinching) return
        numericInput = null
        downX = p.x; downY = p.y
        dragMoved = false
        axisLocked = false
        speedDp = 0f
        lastMoveTime = p.time
        mapToView(p.x, p.y).let { lastMoveViewX = tmp[0]; lastMoveViewY = tmp[1] }
        corners.copyInto(startCorners)
        if (mode == TransformMode.WARP) ensureMesh()
        mesh.copyInto(startMesh)
        startWarped = warped
        op = hitTest(p.x, p.y)
        if (op == Op.WARP_SOFT) {
            // Weiche Verformung: Gewichte nach Abstand der Kontrollpunkte zum Startpunkt
            val diag = hypot(corners[4] - corners[0], corners[5] - corners[1]).coerceAtLeast(1f)
            val sigma = diag / 3.2f
            for (k in 0 until GRID * GRID) {
                val d = hypot(mesh[k * 2] - p.x, mesh[k * 2 + 1] - p.y)
                softWeights[k] = exp(-(d * d) / (sigma * sigma))
            }
        }
    }

    override fun onStrokeMove(points: List<InputPoint>) {
        if (s == null || op == Op.NONE) return
        val p = points.last()
        trackSpeed(p)
        if (!dragMoved && viewDist(downX, downY, p.x, p.y) > 8f * density) dragMoved = true
        if (!dragMoved) return
        if (op == Op.NUDGE) op = Op.MOVE
        // Immer vom Startzustand aus rechnen (robust für Einrasten/Magnetisch)
        startCorners.copyInto(corners)
        startMesh.copyInto(mesh)
        warped = startWarped
        var dx = p.x - downX
        var dy = p.y - downY
        clearGuides()
        showSize = op == Op.CORNER || op == Op.EDGE
        showAngle = op == Op.ROTATE
        when (op) {
            Op.MOVE -> doMove(dx, dy)
            Op.ROTATE -> doRotate(p.x, p.y)
            Op.CORNER -> if (mode == TransformMode.DISTORT) doDistortCorner(opIndex, dx, dy) else doScaleCorner(opIndex, p.x, p.y)
            Op.EDGE -> if (mode == TransformMode.DISTORT) {
                if (magnetic) {
                    // Scheren nur entlang der Kante
                    val j = (opIndex + 1) % 4
                    var ex = startCorners[j * 2] - startCorners[opIndex * 2]
                    var ey = startCorners[j * 2 + 1] - startCorners[opIndex * 2 + 1]
                    val len = hypot(ex, ey)
                    if (len > 1e-3f) {
                        ex /= len; ey /= len
                        val proj = dx * ex + dy * ey
                        dx = proj * ex; dy = proj * ey
                    }
                }
                doDistortCorner(opIndex, dx, dy)
                doDistortCorner((opIndex + 1) % 4, dx, dy)
            } else doScaleEdge(opIndex, p.x, p.y)
            Op.WARP_POINT -> {
                warped = true
                mesh[opIndex * 2] += dx
                mesh[opIndex * 2 + 1] += dy
                syncCornersFromMesh()
            }
            Op.WARP_SOFT -> {
                warped = true
                for (k in 0 until GRID * GRID) {
                    mesh[k * 2] += dx * softWeights[k]
                    mesh[k * 2 + 1] += dy * softWeights[k]
                }
                syncCornersFromMesh()
            }
            else -> {}
        }
        fineDirty = true
        updateMatrix()
        hapticOnGuide()
        state.canvasView?.postInvalidateOnAnimation()
    }

    override fun onStrokeEnd(p: InputPoint) {
        if (s == null) return
        if (!dragMoved) {
            when (op) {
                Op.NUDGE -> {
                    // Tippen außerhalb: in Richtung des Tippens schubsen (bei starkem Zoom feiner)
                    val cx = (corners[0] + corners[2] + corners[4] + corners[6]) / 4f
                    val cy = (corners[1] + corners[3] + corners[5] + corners[7]) / 4f
                    val ang = atan2(p.y - cy, p.x - cx)
                    val q = (ang / (Math.PI / 4)).roundToInt() * (Math.PI / 4)
                    val step = max(1f, floor(2f * density / viewScale()))
                    val nx = cos(q).roundToInt() * step
                    val ny = sin(q).roundToInt() * step
                    applyMatrix(Matrix().apply { setTranslate(nx, ny) })
                }
                Op.CORNER, Op.EDGE -> if (mode == TransformMode.FREEFORM || mode == TransformMode.UNIFORM) {
                    numericInput = TransformNumericInput(TransformNumericKind.SIZE,
                        hypot(corners[2] - corners[0], corners[3] - corners[1]),
                        hypot(corners[6] - corners[0], corners[7] - corners[1]),
                        displayAngle(), linked = mode == TransformMode.UNIFORM)
                }
                Op.ROTATE -> numericInput = TransformNumericInput(TransformNumericKind.ANGLE,
                    hypot(corners[2] - corners[0], corners[3] - corners[1]),
                    hypot(corners[6] - corners[0], corners[7] - corners[1]),
                    displayAngle(), linked = mode == TransformMode.UNIFORM)
                else -> {}
            }
        }
        op = Op.NONE
        showSize = false; showAngle = false
        clearGuides()
        state.invalidate()
    }

    override fun onStrokeCancel() {
        if (s == null) return
        if (op != Op.NONE) {
            startCorners.copyInto(corners)
            startMesh.copyInto(mesh)
            warped = startWarped
            fineDirty = true
            updateMatrix()
        }
        op = Op.NONE
        showSize = false; showAngle = false
        clearGuides()
        state.invalidate()
    }

    private fun trackSpeed(p: InputPoint) {
        mapToView(p.x, p.y)
        val vx = tmp[0]; val vy = tmp[1]
        val dt = (p.time - lastMoveTime).coerceAtLeast(1L)
        val inst = hypot(vx - lastMoveViewX, vy - lastMoveViewY) / density / dt
        speedDp = if (speedDp == 0f) inst else speedDp * 0.7f + inst * 0.3f
        lastMoveViewX = vx; lastMoveViewY = vy; lastMoveTime = p.time
    }

    // ================================================================= Zwei Finger (Kneifen innerhalb)

    override fun handlesTwoFingerAt(docX: Float, docY: Float): Boolean {
        syncSession()
        if (s == null) return false
        if (insideQuad(docX, docY)) return true
        if (warped || mode == TransformMode.WARP) return boundsOfArray(mesh).contains(docX, docY)
        return false
    }

    override fun onTwoFingerTransform(pivotX: Float, pivotY: Float, dx: Float, dy: Float, scale: Float, rotationDeg: Float) {
        if (s == null) return
        if (!pinching) {
            // Laufenden Ein-Finger-Vorgang verwerfen und vom aktuellen Zustand aus beginnen
            if (op != Op.NONE) onStrokeCancel()
            pinching = true
            numericInput = null
            if (mode == TransformMode.WARP) ensureMesh()
            corners.copyInto(startCorners)
            mesh.copyInto(startMesh)
            startWarped = warped
            pinchDx = 0f; pinchDy = 0f; pinchScale = 1f; pinchRot = 0f
        }
        pinchDx += dx; pinchDy += dy
        if (scale.isFinite() && scale > 0f) pinchScale *= scale
        if (rotationDeg.isFinite()) pinchRot += rotationDeg
        startCorners.copyInto(corners)
        startMesh.copyInto(mesh)
        warped = startWarped

        val cx = (startCorners[0] + startCorners[2] + startCorners[4] + startCorners[6]) / 4f
        val cy = (startCorners[1] + startCorners[3] + startCorners[5] + startCorners[7]) / 4f
        var sc = pinchScale
        var rot = pinchRot
        if (magnetic) {
            sc = snapScale(sc)
            val a0 = localAngleDeg(startCorners)
            rot = ((a0 + rot) / 15f).roundToInt() * 15f - a0
        }
        sc = clampScale(sc)
        val m = Matrix()
        m.postScale(sc, sc, cx, cy)
        m.postRotate(rot, cx, cy)
        m.postTranslate(pinchDx, pinchDy)
        applyMatrix(m)
        showSize = true
        showAngle = true
        state.canvasView?.postInvalidateOnAnimation()
    }

    override fun onTwoFingerEnd() {
        if (!pinching) return
        pinching = false
        showSize = false; showAngle = false
        state.invalidate()
    }

    // ================================================================= Operationen

    private fun doMove(dx0: Float, dy0: Float) {
        var dx = dx0
        var dy = dy0
        if (magnetic) {
            // Achse aus der Zugrichtung in 15°-Schritten; nach kurzer Strecke fixiert
            val q = if (axisLocked) axisAngle else ((atan2(dy, dx) / STEP15).roundToInt() * STEP15)
            if (!axisLocked && hypot(dx, dy) * viewScale() > 28f * density) {
                axisLocked = true
                axisAngle = q
                haptic()
            }
            val len = dx * cos(q).toFloat() + dy * sin(q).toFloat()
            dx = (len * cos(q)).toFloat()
            dy = (len * sin(q)).toFloat()
        }
        if (snapping && snapAllowed()) {
            val b = boundsOf(startCorners, if (startWarped) startMesh else null)
            val thr = snapDistance * density / viewScale()
            ensureSnapTargets()
            val tx = snapXs; val txo = snapXObj; val ty = snapYs; val tyo = snapYObj
            if (tx != null && txo != null) {
                var best = thr; var adj = 0f
                for (x in floatArrayOf(b.left + dx, b.centerX() + dx, b.right + dx)) {
                    for (k in tx.indices) {
                        val d = abs(tx[k] - x)
                        if (d < best) { best = d; adj = tx[k] - x; guideX = tx[k]; guideXObject = txo[k] }
                    }
                }
                // Magnetisch: Einrasten nur, wenn es die Achse nicht verlässt (waagrechte Achse)
                if (!magnetic || abs(sin(axisAngle)) < 1e-3) dx += adj else guideX = Float.NaN
            }
            if (ty != null && tyo != null) {
                var best = thr; var adj = 0f
                for (y in floatArrayOf(b.top + dy, b.centerY() + dy, b.bottom + dy)) {
                    for (k in ty.indices) {
                        val d = abs(ty[k] - y)
                        if (d < best) { best = d; adj = ty[k] - y; guideY = ty[k]; guideYObject = tyo[k] }
                    }
                }
                if (!magnetic || abs(cos(axisAngle)) < 1e-3) dy += adj else guideY = Float.NaN
            }
        }
        applyMatrix(Matrix().apply { setTranslate(dx, dy) })
    }

    /** Hilfslinien greifen nur bis zur eingestellten Zieh-Geschwindigkeit. */
    private fun snapAllowed(): Boolean {
        val v = snapSpeed
        if (v >= 0.99f) return true
        return speedDp <= 0.08f + v * 2.4f
    }

    /**
     * Einrastziele: Leinwandränder und -mitte (gelb) sowie Ränder und Mitten der Inhalte anderer sichtbarer
     * Ebenen bzw. des Rests der eigenen Ebenen (blau). Einmal pro Sitzung berechnet.
     */
    private fun ensureSnapTargets() {
        if (snapXs != null) return
        val doc = state.document ?: return
        val ss = s ?: return
        val xs = ArrayList<Float>(); val xo = ArrayList<Boolean>()
        val ys = ArrayList<Float>(); val yo = ArrayList<Boolean>()
        fun add(r: Rect?, obj: Boolean) {
            if (r == null) return
            xs += r.left.toFloat(); xs += r.exactCenterX(); xs += r.right.toFloat()
            repeat(3) { xo += obj }
            ys += r.top.toFloat(); ys += r.exactCenterY(); ys += r.bottom.toFloat()
            repeat(3) { yo += obj }
        }
        add(Rect(0, 0, doc.width, doc.height), false)
        val sessionIds = ss.parts.mapTo(HashSet()) { it.layer.id }
        for (l in doc.layers) {
            if (!l.visible || l.id in sessionIds) continue
            add(try { MaskUtils.alphaBounds(l.bitmap) } catch (t: Throwable) { null }, true)
        }
        for (p in ss.parts) p.base?.let { add(try { MaskUtils.alphaBounds(it) } catch (t: Throwable) { null }, true) }
        snapXs = xs.toFloatArray(); snapXObj = xo.toBooleanArray()
        snapYs = ys.toFloatArray(); snapYObj = yo.toBooleanArray()
    }

    private fun clearGuides() {
        guideX = Float.NaN; guideY = Float.NaN
    }

    private fun hapticOnGuide() {
        val has = !guideX.isNaN() || !guideY.isNaN()
        if (has && !hadGuide) haptic()
        hadGuide = has
    }

    private fun haptic() {
        state.canvasView?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun doRotate(x: Float, y: Float) {
        val cx = (startCorners[0] + startCorners[2] + startCorners[4] + startCorners[6]) / 4f
        val cy = (startCorners[1] + startCorners[3] + startCorners[5] + startCorners[7]) / 4f
        var delta = Math.toDegrees((atan2(y - cy, x - cx) - atan2(downY - cy, downX - cx)).toDouble()).toFloat()
        if (magnetic) {
            val a0 = localAngleDeg(startCorners)
            val target = ((a0 + delta) / 15f).roundToInt() * 15f
            delta = target - a0
        }
        applyMatrix(Matrix().apply { setRotate(delta, cx, cy) })
    }

    /** Ecke skalieren: gegenüberliegende Ecke bleibt fest (lokales, gedrehtes Koordinatensystem). */
    private fun doScaleCorner(i: Int, x: Float, y: Float) {
        val o = (i + 2) % 4
        val ax = startCorners[o * 2]; val ay = startCorners[o * 2 + 1]
        val th = Math.toRadians(localAngleDeg(startCorners).toDouble())
        val c = cos(-th).toFloat(); val sn = sin(-th).toFloat()
        val vx = startCorners[i * 2] - ax; val vy = startCorners[i * 2 + 1] - ay
        val wx = x - ax; val wy = y - ay
        val lvx = vx * c - vy * sn; val lvy = vx * sn + vy * c
        val lwx = wx * c - wy * sn; val lwy = wx * sn + wy * c
        var sx: Float
        var sy: Float
        if (mode == TransformMode.UNIFORM) {
            val d = lvx * lvx + lvy * lvy
            val sc = if (d > 1e-3f) (lwx * lvx + lwy * lvy) / d else 1f
            sx = sc; sy = sc
        } else {
            sx = if (abs(lvx) > 1e-3f) lwx / lvx else 1f
            sy = if (abs(lvy) > 1e-3f) lwy / lvy else 1f
        }
        if (magnetic) { sx = snapScale(sx); sy = snapScale(sy) }
        sx = clampScale(sx); sy = clampScale(sy)
        scaleAbout(ax, ay, th, sx, sy)
    }

    /**
     * Kante skalieren. Freiform: gegenüberliegende Kante bleibt fest (eine Achse).
     * Gleichmäßig: proportional vom gegenüberliegenden Punkt aus.
     * Kante i verbindet Ecke i und i+1.
     */
    private fun doScaleEdge(i: Int, x: Float, y: Float) {
        val i2 = (i + 1) % 4
        val o1 = (i + 2) % 4; val o2 = (i + 3) % 4
        val mx = (startCorners[i * 2] + startCorners[i2 * 2]) / 2f
        val my = (startCorners[i * 2 + 1] + startCorners[i2 * 2 + 1]) / 2f
        val ax = (startCorners[o1 * 2] + startCorners[o2 * 2]) / 2f
        val ay = (startCorners[o1 * 2 + 1] + startCorners[o2 * 2 + 1]) / 2f
        val th = Math.toRadians(localAngleDeg(startCorners).toDouble())
        val c = cos(-th).toFloat(); val sn = sin(-th).toFloat()
        val vx = mx - ax; val vy = my - ay
        val wx = x - ax; val wy = y - ay
        val lvx = vx * c - vy * sn; val lvy = vx * sn + vy * c
        val lwx = wx * c - wy * sn; val lwy = wx * sn + wy * c
        val horizontalEdge = i == 0 || i == 2   // obere/untere Kante → skaliert y
        var f = if (horizontalEdge) (if (abs(lvy) > 1e-3f) lwy / lvy else 1f)
        else (if (abs(lvx) > 1e-3f) lwx / lvx else 1f)
        if (magnetic) f = snapScale(f)
        f = clampScale(f)
        val sx: Float
        val sy: Float
        if (mode == TransformMode.UNIFORM) { sx = f; sy = f }
        else if (horizontalEdge) { sx = 1f; sy = f } else { sx = f; sy = 1f }
        scaleAbout(ax, ay, th, sx, sy)
    }

    private fun scaleAbout(ax: Float, ay: Float, thRad: Double, sx: Float, sy: Float) {
        val deg = Math.toDegrees(thRad).toFloat()
        val m = Matrix()
        m.postRotate(-deg, ax, ay)
        m.postScale(sx, sy, ax, ay)
        m.postRotate(deg, ax, ay)
        applyMatrix(m)
    }

    private fun doDistortCorner(i: Int, dx: Float, dy: Float) {
        if (warped) {
            // Kontrollgitter bilinear mitziehen
            val ci = if (i == 0 || i == 3) 0f else 1f
            val cj = if (i == 0 || i == 1) 0f else 1f
            for (j in 0 until GRID) for (k in 0 until GRID) {
                val u = k / (GRID - 1f); val v = j / (GRID - 1f)
                val w = (if (ci == 0f) 1f - u else u) * (if (cj == 0f) 1f - v else v)
                val idx = (j * GRID + k) * 2
                mesh[idx] += dx * w
                mesh[idx + 1] += dy * w
            }
            syncCornersFromMesh()
        } else {
            corners[i * 2] += dx
            corners[i * 2 + 1] += dy
        }
    }

    private fun snapScale(f: Float) = (f / 0.05f).roundToInt() * 0.05f
    private fun clampScale(f: Float): Float = when {
        abs(f) < 0.01f -> if (f < 0f) -0.01f else 0.01f
        else -> f.coerceIn(-100f, 100f)
    }

    // ================================================================= Geometrie

    private fun applyMatrix(m: Matrix) {
        m.mapPoints(corners)
        if (warped || mode == TransformMode.WARP) m.mapPoints(mesh)
        fineDirty = true
        updateMatrix()
    }

    private fun updateMatrix() {
        val m = Matrix()
        if (m.setPolyToPoly(srcPts, 0, corners, 0, 4)) matrix.set(m)
    }

    /** Kontrollgitter aus der aktuellen (perspektivischen) Abbildung erzeugen. */
    private fun ensureMesh() {
        val ss = s ?: return
        if (warped) return
        updateMatrix()
        val fw = ss.srcRect.width().toFloat()
        val fh = ss.srcRect.height().toFloat()
        for (j in 0 until GRID) for (i in 0 until GRID) {
            val idx = (j * GRID + i) * 2
            mesh[idx] = fw * i / (GRID - 1)
            mesh[idx + 1] = fh * j / (GRID - 1)
        }
        matrix.mapPoints(mesh)
        fineDirty = true
    }

    private fun syncCornersFromMesh() {
        val ids = CORNER_IDS
        for (c in 0 until 4) {
            corners[c * 2] = mesh[ids[c] * 2]
            corners[c * 2 + 1] = mesh[ids[c] * 2 + 1]
        }
    }

    /** Feines Render-Gitter per Catmull-Rom-Interpolation durch die Kontrollpunkte. */
    private fun ensureFine() {
        if (!fineDirty) return
        fineDirty = false
        val segs = GRID - 1
        val rowX = FloatArray(GRID + 2)
        val rowY = FloatArray(GRID + 2)
        for (fj in 0..FINE) {
            val v = fj * segs / FINE.toFloat()
            // Für jede Spalte die Kurve in v auswerten, dann in u interpolieren
            for (i in 0 until GRID) {
                rowX[i + 1] = cr(v) { j -> ctrl(i, j, 0) }
                rowY[i + 1] = cr(v) { j -> ctrl(i, j, 1) }
            }
            rowX[0] = 2 * rowX[1] - rowX[2]; rowY[0] = 2 * rowY[1] - rowY[2]
            rowX[GRID + 1] = 2 * rowX[GRID] - rowX[GRID - 1]; rowY[GRID + 1] = 2 * rowY[GRID] - rowY[GRID - 1]
            for (fi in 0..FINE) {
                val u = fi * segs / FINE.toFloat()
                val k = min(floor(u).toInt(), segs - 1)
                val t = u - k
                val idx = (fj * (FINE + 1) + fi) * 2
                fine[idx] = catmull(rowX[k], rowX[k + 1], rowX[k + 2], rowX[k + 3], t)
                fine[idx + 1] = catmull(rowY[k], rowY[k + 1], rowY[k + 2], rowY[k + 3], t)
            }
        }
    }

    /** Kontrollpunkt (i, j) mit linearer Extrapolation außerhalb des Gitters. */
    private fun ctrl(i: Int, j: Int, c: Int): Float = when {
        j < 0 -> 2 * ctrl(i, 0, c) - ctrl(i, 1, c)
        j >= GRID -> 2 * ctrl(i, GRID - 1, c) - ctrl(i, GRID - 2, c)
        else -> mesh[(j * GRID + i) * 2 + c]
    }

    private inline fun cr(v: Float, get: (Int) -> Float): Float {
        val k = min(floor(v).toInt(), GRID - 2)
        val t = v - k
        return catmull(get(k - 1), get(k), get(k + 1), get(k + 2), t)
    }

    private fun catmull(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * ((2f * p1) + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 + (-p0 + 3f * p1 - 3f * p2 + p3) * t3)
    }

    private fun transformedBounds(): RectF {
        if (warped) {
            ensureFine()
            return boundsOfArray(fine)
        }
        return boundsOf(corners, null)
    }

    private fun boundsOf(c: FloatArray, m: FloatArray?): RectF {
        val r = boundsOfArray(c)
        if (m != null) r.union(boundsOfArray(m))
        return r
    }

    private fun boundsOfArray(a: FloatArray): RectF {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var i = 0
        while (i < a.size) {
            l = min(l, a[i]); r = max(r, a[i]); t = min(t, a[i + 1]); b = max(b, a[i + 1]); i += 2
        }
        return RectF(l, t, r, b)
    }

    private fun localAngleDeg(c: FloatArray): Float =
        Math.toDegrees(atan2((c[3] - c[1]).toDouble(), (c[2] - c[0]).toDouble())).toFloat()

    /** Anzeigewinkel: positiv = gegen den Uhrzeigersinn, Bereich (-180, 180]. */
    private fun displayAngle(): Float {
        var a = -localAngleDeg(corners)
        while (a <= -180f) a += 360f
        while (a > 180f) a -= 360f
        return a
    }

    private fun isUnchanged(): Boolean {
        if (warped) return false
        for (i in 0 until 8) if (abs(corners[i] - initCorners[i]) > 0.01f) return false
        return true
    }

    private fun setRect(a: FloatArray, l: Float, t: Float, r: Float, b: Float) {
        a[0] = l; a[1] = t; a[2] = r; a[3] = t; a[4] = r; a[5] = b; a[6] = l; a[7] = b
    }

    // ================================================================= Zeichnen

    /** drawOverride einer Ebene: Rest + schwebender Inhalt mit Transformation. */
    private fun drawPart(c: Canvas, p: Part) {
        if (s == null) return
        p.base?.let { c.drawBitmap(it, 0f, 0f, null) }
        previewPaint.isFilterBitmap = interpolation != Interpolation.NEAREST
        previewPaint.isAntiAlias = interpolation != Interpolation.NEAREST
        drawFloating(c, p.floating, previewPaint)
    }

    private fun drawFloating(c: Canvas, floating: Bitmap, paint: Paint) {
        if (warped) {
            ensureFine()
            c.drawBitmapMesh(floating, FINE, FINE, fine, 0, null, 0, paint)
        } else {
            c.save()
            c.concat(matrix)
            c.drawBitmap(floating, 0f, 0f, paint)
            c.restore()
        }
    }

    private fun drawFloatingFinal(c: Canvas, floating: Bitmap, layer: Layer) {
        if (!warped && interpolation == Interpolation.BICUBIC) {
            val b = transformedBounds()
            val r = Rect(floor(b.left).toInt(), floor(b.top).toInt(), b.right.toInt() + 1, b.bottom.toInt() + 1)
            if (r.intersect(0, 0, layer.width, layer.height)) {
                val inv = Matrix()
                if (matrix.invert(inv)) {
                    val out = Bicubic.resample(floating, inv, r)
                    c.drawBitmap(out, r.left.toFloat(), r.top.toFloat(), null)
                    return
                }
            }
        }
        val p = Paint().apply {
            isFilterBitmap = interpolation != Interpolation.NEAREST
            isAntiAlias = interpolation != Interpolation.NEAREST
        }
        drawFloating(c, floating, p)
    }

    override fun drawOverlay(canvas: Canvas, docToView: Matrix) {
        viewMatrix.set(docToView)
        syncSession()
        if (s == null) return
        val showEdges = mode != TransformMode.WARP
        // Rahmen bzw. Gitter
        overlayPath.rewind()
        if (mode == TransformMode.WARP) {
            ensureMesh()
            if (warped) {
                ensureFine()
                val stepF = FINE / (GRID - 1)
                for (j in 0 until GRID) {
                    val fj = j * stepF
                    for (fi in 0..FINE) {
                        val idx = (fj * (FINE + 1) + fi) * 2
                        if (fi == 0) overlayPath.moveTo(fine[idx], fine[idx + 1]) else overlayPath.lineTo(fine[idx], fine[idx + 1])
                    }
                }
                for (i in 0 until GRID) {
                    val fi = i * stepF
                    for (fj in 0..FINE) {
                        val idx = (fj * (FINE + 1) + fi) * 2
                        if (fj == 0) overlayPath.moveTo(fine[idx], fine[idx + 1]) else overlayPath.lineTo(fine[idx], fine[idx + 1])
                    }
                }
            } else {
                for (j in 0 until GRID) for (i in 0 until GRID) {
                    val idx = (j * GRID + i) * 2
                    if (i == 0) overlayPath.moveTo(mesh[idx], mesh[idx + 1]) else overlayPath.lineTo(mesh[idx], mesh[idx + 1])
                }
                for (i in 0 until GRID) for (j in 0 until GRID) {
                    val idx = (j * GRID + i) * 2
                    if (j == 0) overlayPath.moveTo(mesh[idx], mesh[idx + 1]) else overlayPath.lineTo(mesh[idx], mesh[idx + 1])
                }
            }
        } else {
            overlayPath.moveTo(corners[0], corners[1])
            overlayPath.lineTo(corners[2], corners[3])
            overlayPath.lineTo(corners[4], corners[5])
            overlayPath.lineTo(corners[6], corners[7])
            overlayPath.close()
        }
        overlayPath.transform(docToView)
        canvas.drawPath(overlayPath, shadowPaint)
        canvas.drawPath(overlayPath, boxPaint)
        if (showEdges) {
            // Gestrichelter, laufender Rahmen (Verkrümmen: durchgezogen)
            val dash = 6f * density
            val phase = (SystemClock.uptimeMillis() % 100_000L) / 40f
            boxDash.pathEffect = DashPathEffect(floatArrayOf(dash, dash), -(phase % (2 * dash)))
            canvas.drawPath(overlayPath, boxDash)
            state.canvasView?.postInvalidateDelayed(85)
        }

        // Einrast-Hilfslinien (blau = Objekt, gelb = Leinwand)
        if (!guideX.isNaN() || !guideY.isNaN()) {
            val doc = state.document
            if (doc != null) {
                val big = max(doc.width, doc.height) * 4f
                if (!guideX.isNaN()) drawDocLine(canvas, guideX, -big, guideX, big,
                    if (guideXObject) objectGuidePaint else canvasGuidePaint)
                if (!guideY.isNaN()) drawDocLine(canvas, -big, guideY, big, guideY,
                    if (guideYObject) objectGuidePaint else canvasGuidePaint)
            }
        }

        val hr = 7.5f * density
        if (mode == TransformMode.WARP) {
            for (k in 0 until GRID * GRID) {
                tmp[0] = mesh[k * 2]; tmp[1] = mesh[k * 2 + 1]
                docToView.mapPoints(tmp, 0, tmp, 0, 1)
                drawHandle(canvas, tmp[0], tmp[1], hr * 0.8f, handleFill)
            }
            drawValueLabel(canvas)
            return
        }
        // Drehgriff (grün) mit Verbindungslinie
        rotationHandle(tmp, 0)
        mid[0] = (corners[0] + corners[2]) / 2f; mid[1] = (corners[1] + corners[3]) / 2f
        docToView.mapPoints(mid)
        canvas.drawLine(mid[0], mid[1], tmp[0], tmp[1], boxPaint)
        drawHandle(canvas, tmp[0], tmp[1], hr, rotFill)
        // Ecken
        for (i in 0 until 4) {
            tmp[0] = corners[i * 2]; tmp[1] = corners[i * 2 + 1]
            docToView.mapPoints(tmp, 0, tmp, 0, 1)
            drawHandle(canvas, tmp[0], tmp[1], hr, handleFill)
        }
        // Seitenmitten
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            tmp[0] = (corners[i * 2] + corners[j * 2]) / 2f; tmp[1] = (corners[i * 2 + 1] + corners[j * 2 + 1]) / 2f
            docToView.mapPoints(tmp, 0, tmp, 0, 1)
            drawHandle(canvas, tmp[0], tmp[1], hr * 0.78f, handleFill)
        }
        drawValueLabel(canvas)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float, r: Float, fill: Paint) {
        canvas.drawCircle(x, y + 1f * density, r + 1.5f * density, handleShadow)
        canvas.drawCircle(x, y, r, fill)
        canvas.drawCircle(x, y, r, handleRing)
    }

    /** Größen- (B × H px) bzw. Winkelanzeige als Pille über dem Rahmen. */
    private fun drawValueLabel(canvas: Canvas) {
        if (!showSize && !showAngle) return
        val w = hypot(corners[2] - corners[0], corners[3] - corners[1]).roundToInt()
        val h = hypot(corners[6] - corners[0], corners[7] - corners[1]).roundToInt()
        val size = "$w × $h px"
        val angle = String.format(Locale.GERMANY, "%.1f°", displayAngle())
        val text = when {
            showSize && showAngle -> "$size   $angle"
            showSize -> size
            else -> angle
        }
        // Position: mittig über dem Rahmen (oberhalb des Drehgriffs), sonst darunter
        for (i in 0 until 4) { tmp[i * 2] = corners[i * 2]; tmp[i * 2 + 1] = corners[i * 2 + 1] }
        viewMatrix.mapPoints(tmp, 0, tmp, 0, 4)
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until 4) {
            minX = min(minX, tmp[i * 2]); maxX = max(maxX, tmp[i * 2])
            minY = min(minY, tmp[i * 2 + 1]); maxY = max(maxY, tmp[i * 2 + 1])
        }
        val tw = labelText.measureText(text)
        val ph = 28f * density
        val pw = tw + 24f * density
        val cx = ((minX + maxX) / 2f).coerceIn(pw / 2f + 8f * density, canvas.width - pw / 2f - 8f * density)
        var top = minY - 58f * density - ph
        if (top < 8f * density) top = maxY + 30f * density
        top = top.coerceAtMost(canvas.height - ph - 8f * density)
        labelRect.set(cx - pw / 2f, top, cx + pw / 2f, top + ph)
        canvas.drawRoundRect(labelRect, ph / 2f, ph / 2f, labelBg)
        val fm = labelText.fontMetrics
        canvas.drawText(text, cx, labelRect.centerY() - (fm.ascent + fm.descent) / 2f, labelText)
    }

    private fun drawDocLine(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, paint: Paint) {
        tmp[0] = x0; tmp[1] = y0; tmp[2] = x1; tmp[3] = y1
        viewMatrix.mapPoints(tmp, 0, tmp, 0, 2)
        canvas.drawLine(tmp[0], tmp[1], tmp[2], tmp[3], paint)
    }

    /** Position des Drehgriffs in View-Koordinaten → out[off], out[off+1]. */
    private fun rotationHandle(out: FloatArray, off: Int) {
        val pts = floatArrayOf(
            (corners[0] + corners[2]) / 2f, (corners[1] + corners[3]) / 2f,
            (corners[0] + corners[2] + corners[4] + corners[6]) / 4f, (corners[1] + corners[3] + corners[5] + corners[7]) / 4f,
        )
        viewMatrix.mapPoints(pts)
        var nx = pts[0] - pts[2]
        var ny = pts[1] - pts[3]
        val len = sqrt(nx * nx + ny * ny)
        if (len < 1e-3f) { nx = 0f; ny = -1f } else { nx /= len; ny /= len }
        out[off] = pts[0] + nx * 30f * density
        out[off + 1] = pts[1] + ny * 30f * density
    }

    // ================================================================= Trefferprüfung

    private fun hitTest(x: Float, y: Float): Op {
        val pv = floatArrayOf(x, y)
        viewMatrix.mapPoints(pv)
        val r = 24f * density
        if (mode == TransformMode.WARP) {
            var best = -1
            var bestD = r
            for (k in 0 until GRID * GRID) {
                tmp[0] = mesh[k * 2]; tmp[1] = mesh[k * 2 + 1]
                viewMatrix.mapPoints(tmp, 0, tmp, 0, 1)
                val d = hypot(tmp[0] - pv[0], tmp[1] - pv[1])
                if (d < bestD) { bestD = d; best = k }
            }
            if (best >= 0) { opIndex = best; return Op.WARP_POINT }
            return if (insideQuad(x, y)) Op.WARP_SOFT else Op.NUDGE
        }
        rotationHandle(tmp, 0)
        if (hypot(tmp[0] - pv[0], tmp[1] - pv[1]) < r) return Op.ROTATE
        for (i in 0 until 4) {
            tmp[0] = corners[i * 2]; tmp[1] = corners[i * 2 + 1]
            viewMatrix.mapPoints(tmp, 0, tmp, 0, 1)
            if (hypot(tmp[0] - pv[0], tmp[1] - pv[1]) < r) { opIndex = i; return Op.CORNER }
        }
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            tmp[0] = (corners[i * 2] + corners[j * 2]) / 2f; tmp[1] = (corners[i * 2 + 1] + corners[j * 2 + 1]) / 2f
            viewMatrix.mapPoints(tmp, 0, tmp, 0, 1)
            if (hypot(tmp[0] - pv[0], tmp[1] - pv[1]) < r * 0.85f) { opIndex = i; return Op.EDGE }
        }
        return if (insideQuad(x, y)) Op.MOVE else Op.NUDGE
    }

    /** Punkt-im-Polygon (Strahlverfahren) für das Viereck. */
    private fun insideQuad(x: Float, y: Float): Boolean {
        var inside = false
        var j = 3
        for (i in 0 until 4) {
            val xi = corners[i * 2]; val yi = corners[i * 2 + 1]
            val xj = corners[j * 2]; val yj = corners[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    private val scaleVals = FloatArray(9)
    private fun viewScale(): Float {
        viewMatrix.getValues(scaleVals)
        return hypot(scaleVals[Matrix.MSCALE_X], scaleVals[Matrix.MSKEW_Y]).coerceAtLeast(1e-4f)
    }

    private fun mapToView(x: Float, y: Float) {
        tmp[0] = x; tmp[1] = y
        viewMatrix.mapPoints(tmp, 0, tmp, 0, 1)
    }

    private fun viewDist(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        tmp[0] = x0; tmp[1] = y0; tmp[2] = x1; tmp[3] = y1
        viewMatrix.mapPoints(tmp, 0, tmp, 0, 2)
        return hypot(tmp[2] - tmp[0], tmp[3] - tmp[1])
    }

    companion object {
        /** Kontrollpunkte je Richtung beim Verkrümmen. */
        const val GRID = 4
        /** Render-Gitter-Auflösung (Vielfaches von GRID-1). */
        const val FINE = 24
        private val CORNER_IDS = intArrayOf(0, GRID - 1, GRID * GRID - 1, GRID * (GRID - 1))
        private const val STEP15 = Math.PI / 12
        private const val ACCENT = 0xFF2F7CF6.toInt()
    }
}
