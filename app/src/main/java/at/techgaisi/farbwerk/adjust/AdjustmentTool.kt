package at.techgaisi.farbwerk.adjust

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.InputPoint
import at.techgaisi.farbwerk.model.Layer
import at.techgaisi.farbwerk.model.Tool
import at.techgaisi.farbwerk.model.ToolInputHandler
import at.techgaisi.farbwerk.selection.MaskUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Anpassungen. Ablauf: [start] (aus dem Anpassungsmenü) → Regler/Leinwand-Gesten → [apply] oder [cancel].
 *
 * Regler-Anpassungen ("Ebene"-Modus): die Vorschau wird im Hintergrund auf einer herunterskalierten
 * Kopie berechnet (während des Ziehens) und beim Loslassen in voller Auflösung; angezeigt wird sie über
 * Layer.displayOverride. Im "Stift"-Modus wird das volle Ergebnis per Pinselmaske aufgetragen.
 * Verflüssigen nutzt ein Verschiebungsgitter (drawOverride + drawBitmapMesh), Klonen malt mit Versatz
 * in eine Arbeitskopie.
 */
class AdjustmentTool(private val state: EditorState) : ToolInputHandler {

    // ================================================================= UI-Zustand (Compose)

    var kind by mutableStateOf<AdjustmentKind?>(null)
        private set
    val values = mutableStateListOf<Float>()
    var pencilMode by mutableStateOf(false)
        private set
    /** Pinselgröße im Stift-Modus (1..100). */
    var pencilSize by mutableFloatStateOf(30f)
    var curveChannel by mutableIntStateOf(0)
    val curves = Curves()
    /** Erhöht sich bei jeder Kurvenänderung (Compose liest das). */
    var curvesVersion by mutableIntStateOf(0)
        private set
    var gradientIndex by mutableIntStateOf(0)
        private set
    /** Volle Auflösung wird gerade berechnet. */
    var busy by mutableStateOf(false)
        private set
    var historySize by mutableIntStateOf(0)
        private set
    /** Werkzeug, zu dem nach Anwenden/Abbrechen zurückgekehrt wird. */
    var returnTool: Tool = Tool.PAINT

    private val density = state.context.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ================================================================= Sitzung

    private var layer: Layer? = null
    private var sessionId = 0
    private var undoVersion = 0
    private var fullW = 0
    private var fullH = 0
    private var srcFull: IntArray? = null
    private var srcPreview: IntArray? = null
    private var pw = 0
    private var ph = 0
    private var previewScale = 1f
    private var overrideBmp: Bitmap? = null
    private var overrideCanvas: Canvas? = null
    private var smallBmp: Bitmap? = null
    private var scratch: Bitmap? = null
    private var scratchCanvas: Canvas? = null
    private var fullResult: IntArray? = null
    private var fullGen = -1
    private var gen = 0
    /** Mittelpunkt (normiert) für Perspektivische Unschärfe / Chromatische Aberration. */
    var centerX = 0.5f
        private set
    var centerY = 0.5f
        private set

    private var previewRunning = false
    private var previewPending = false
    private var fullToken: CancelToken? = null
    private var paramEditing = false

    // Stift-Modus
    private var pencilMask: Bitmap? = null
    private var pencilCanvas: Canvas? = null
    private var adjustedBmp: Bitmap? = null
    private var adjustedGen = -1

    // Verflüssigen
    private var liquify: LiquifyEngine? = null
    private val liquifyDraw: (Canvas) -> Unit = { c ->
        val l = layer
        val eng = liquify
        if (l != null && eng != null) eng.draw(c, l.bitmap, meshPaint)
    }

    // Klonen
    private var cloneWork: Bitmap? = null
    private var cloneWorkCanvas: Canvas? = null
    private var cloneShadow: Bitmap? = null
    private var cloneScratch: Bitmap? = null
    private var cloneScratchCanvas: Canvas? = null
    private var cloneSrcX = 0f
    private var cloneSrcY = 0f
    private var cloneOffX = 0f
    private var cloneOffY = 0f
    private var cloneOffsetValid = false
    private val cloneDirty = Rect()

    // Verlauf innerhalb der Anpassung (Schaltfläche "Rückgängig")
    private sealed class Step {
        class Params(val values: FloatArray, val curves: Curves, val gradient: Int, val cx: Float, val cy: Float) : Step()
        class Mask(val mask: Bitmap?) : Step()
        class Liquify(val pos: FloatArray) : Step()
        class CloneRegion(val rect: Rect, val before: Bitmap) : Step()
    }
    private val history = ArrayDeque<Step>()

    // Zeichnen
    private val srcPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private val srcFilterPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private val dstInPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val dstOutPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val addPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val meshPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dabPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clonePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6202024.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 14f * density; textAlign = Paint.Align.CENTER
    }
    private val dabCache = HashMap<Int, Bitmap>()

    // Eingabe
    private enum class Drag { NONE, PARAM, CENTER, PENCIL, LIQUIFY, CLONE_PAINT, CLONE_SOURCE }
    private var drag = Drag.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastPressure = 1f
    private var dragStartValue = 0f
    private var dragMoved = false
    private var dragLabel: String? = null
    private val viewMatrix = Matrix()
    private val tmp = FloatArray(4)

    // Verflüssigen: Halten (Zwirbeln etc.) und Dynamik
    private var momentumFrames = 0
    private var momentumTotal = 0
    private var momentumDx = 0f
    private var momentumDy = 0f
    private val liquifyTick = object : Runnable {
        override fun run() {
            val eng = liquify ?: return
            if (drag == Drag.LIQUIFY) {
                val mode = liquifyMode()
                if (mode != LiquifyEngine.Mode.PUSH && mode != LiquifyEngine.Mode.EDGE) {
                    eng.apply(mode, lastX, lastY, 0f, 0f, liquifyRadius(), liquifyStrength() * 0.5f, values[3] / 100f)
                    state.canvasView?.postInvalidateOnAnimation()
                }
                main.postDelayed(this, 16)
            } else if (momentumFrames > 0) {
                val f = momentumFrames / momentumTotal.toFloat()
                momentumDx *= 0.9f; momentumDy *= 0.9f
                eng.apply(liquifyMode(), lastX + momentumDx, lastY + momentumDy, momentumDx, momentumDy,
                    liquifyRadius(), liquifyStrength() * f, values[3] / 100f)
                lastX += momentumDx; lastY += momentumDy
                momentumFrames--
                state.canvasView?.postInvalidateOnAnimation()
                main.postDelayed(this, 16)
            }
        }
    }

    // ================================================================= Start / Ende

    /**
     * Öffnet die Anpassung [k] vollständig: schließt Panels, merkt sich das bisherige Werkzeug,
     * wechselt zu Tool.ADJUST und startet die Anpassung (für Menü und Tastenkürzel).
     */
    fun open(k: AdjustmentKind) {
        val prev = state.tool
        state.openPanel = at.techgaisi.farbwerk.model.Panel.NONE
        if (prev != Tool.ADJUST) {
            returnTool = if (prev == Tool.PAINT || prev == Tool.SMUDGE || prev == Tool.ERASE || prev == Tool.SELECT) prev else Tool.PAINT
        }
        state.selectTool(Tool.ADJUST)
        start(k)
    }

    /** Startet eine Anpassung auf der aktiven Ebene (eine laufende wird vorher übernommen). */
    fun start(k: AdjustmentKind) {
        if (kind != null) apply()
        val doc = state.document ?: return
        val l = doc.activeLayer ?: return
        if (l.locked) {
            state.flash("Ebene gesperrt")
            main.post { if (kind == null && state.tool == Tool.ADJUST) state.selectTool(returnTool) }
            return
        }
        if (!l.visible) state.flash("Hinweis: Ebene ist ausgeblendet")
        sessionId++
        layer = l
        kind = k
        undoVersion = state.undo.version
        fullW = l.width
        fullH = l.height
        values.clear()
        values.addAll(k.params.map { it.default })
        pencilMode = false
        history.clear(); historySize = 0
        curves.reset(); curveChannel = 0; curvesVersion++
        gradientIndex = 0
        centerX = 0.5f; centerY = 0.5f
        fullResult = null; fullGen = -1; gen++
        adjustedGen = -1
        paramEditing = false
        drag = Drag.NONE
        when (k) {
            AdjustmentKind.LIQUIFY -> {
                liquify = LiquifyEngine(fullW, fullH)
                l.drawOverride = liquifyDraw
            }
            AdjustmentKind.CLONE -> {
                val work = l.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                cloneWork = work
                cloneWorkCanvas = Canvas(work)
                cloneShadow = l.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                cloneSrcX = fullW * 0.35f
                cloneSrcY = fullH * 0.5f
                cloneOffsetValid = false
                l.displayOverride = work
            }
            else -> prepareSources(l)
        }
        // Verlaufsumsetzung wirkt bereits mit den Standardwerten
        if (k == AdjustmentKind.GRADIENT_MAP) onParamsChanged(false)
        state.invalidate()
    }

    private fun prepareSources(l: Layer) {
        val full = IntArray(fullW * fullH)
        l.bitmap.getPixels(full, 0, fullW, 0, 0, fullW, fullH)
        srcFull = full
        val longSide = max(fullW, fullH)
        previewScale = min(1f, PREVIEW_MAX / longSide.toFloat())
        if (previewScale >= 1f) {
            pw = fullW; ph = fullH
            srcPreview = full
        } else {
            pw = max(1, (fullW * previewScale).roundToInt())
            ph = max(1, (fullH * previewScale).roundToInt())
            val small = Bitmap.createScaledBitmap(l.bitmap, pw, ph, true)
            val arr = IntArray(pw * ph)
            small.getPixels(arr, 0, pw, 0, 0, pw, ph)
            if (small !== l.bitmap) small.recycle()
            srcPreview = arr
        }
    }

    /** Aktuelle Anpassung übernehmen (Undo-Eintrag) und Vorschau entfernen. */
    fun apply() {
        val k = kind ?: return
        val l = layer
        val doc = state.document
        if (l == null || doc == null || doc.indexOf(l) < 0) { endSession(); return }
        try {
            when (k) {
                AdjustmentKind.LIQUIFY -> {
                    val eng = liquify
                    if (eng != null && !eng.isIdentity) {
                        l.drawOverride = null
                        val e = state.beginPixelEdit(l)
                        val c = Canvas(l.bitmap)
                        c.drawColor(0, PorterDuff.Mode.CLEAR)
                        eng.draw(c, e.original, meshPaint)
                        e.commit(null, k.label)
                    }
                }
                AdjustmentKind.CLONE -> {
                    val work = cloneWork
                    if (work != null && history.any { it is Step.CloneRegion }) {
                        l.displayOverride = null
                        val e = state.beginPixelEdit(l)
                        Canvas(l.bitmap).drawBitmap(work, 0f, 0f, srcPaint)
                        e.commit(null, k.label)
                    }
                }
                else -> {
                    if (pencilMode) {
                        val m = pencilMask
                        val bounds = m?.let { MaskUtils.bounds(it) }
                        if (bounds != null) {
                            ensureAdjustedSync()
                            rebuildPencil(null)
                            val ov = overrideBmp
                            if (ov != null) {
                                l.displayOverride = null
                                val e = state.beginPixelEdit(l)
                                Canvas(l.bitmap).drawBitmap(ov, 0f, 0f, srcPaint)
                                e.commit(bounds, k.label)
                            }
                        }
                    } else if (!isNeutral()) {
                        val out = ensureFullSync()
                        if (out != null) {
                            l.displayOverride = null
                            val e = state.beginPixelEdit(l)
                            l.bitmap.setPixels(out, 0, fullW, 0, 0, fullW, fullH)
                            e.commit(null, k.label)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("Farbwerk", "Anpassung anwenden", t)
            state.flash("Anpassung fehlgeschlagen")
        }
        endSession()
    }

    /** Anpassung verwerfen. */
    fun cancel() {
        if (kind == null) return
        endSession()
    }

    /** Anwenden und zum vorherigen Werkzeug zurückkehren. */
    fun applyAndClose() {
        apply()
        if (state.tool == Tool.ADJUST) state.selectTool(returnTool)
    }

    /** Abbrechen und zum vorherigen Werkzeug zurückkehren. */
    fun cancelAndClose() {
        cancel()
        if (state.tool == Tool.ADJUST) state.selectTool(returnTool)
    }

    private fun endSession() {
        fullToken?.cancelled = true
        fullToken = null
        main.removeCallbacks(liquifyTick)
        momentumFrames = 0
        layer?.let { l ->
            if (l.displayOverride === overrideBmp || l.displayOverride === cloneWork) l.displayOverride = null
            if (l.drawOverride === liquifyDraw) l.drawOverride = null
        }
        layer = null
        kind = null
        sessionId++
        srcFull = null; srcPreview = null; fullResult = null
        overrideBmp = null; overrideCanvas = null
        smallBmp = null; scratch = null; scratchCanvas = null
        pencilMask = null; pencilCanvas = null; adjustedBmp = null
        liquify = null
        cloneWork = null; cloneWorkCanvas = null; cloneShadow = null; cloneScratch = null; cloneScratchCanvas = null
        history.clear(); historySize = 0
        pencilMode = false
        busy = false
        drag = Drag.NONE
        dragLabel = null
        previewRunning = false
        previewPending = false
        state.invalidate()
    }

    /** Erkennt Änderungen außerhalb der Sitzung (Rückgängig-Geste, Ebenenwechsel) und startet neu. */
    private fun syncSession() {
        val l = layer ?: return
        val k = kind ?: return
        val doc = state.document
        val layerChanged = doc == null || doc.activeLayer !== l
        val versionChanged = state.undo.version != undoVersion
        if (!layerChanged && !versionChanged) return
        val sid = sessionId
        main.post {
            if (sid != sessionId) return@post
            val saved = values.toFloatArray()
            val savedCurves = curves.copy()
            val savedGradient = gradientIndex
            if (!versionChanged) apply() else endSession()
            if (state.tool == Tool.ADJUST && state.document != null) {
                start(k)
                if (!k.isBrushTool && values.size == saved.size) {
                    for (i in saved.indices) values[i] = saved[i]
                    restoreCurves(savedCurves)
                    gradientIndex = savedGradient
                    onParamsChanged(false)
                }
            }
        }
    }

    // ================================================================= Parameter (UI)

    fun setValue(i: Int, v: Float) {
        if (i !in values.indices) return
        if (!paramEditing) { pushParams(); paramEditing = true }
        values[i] = v
        onParamsChanged(interactive = true)
    }

    /** Regler losgelassen → volle Auflösung berechnen. */
    fun endValueChange() {
        paramEditing = false
        onParamsChanged(interactive = false)
    }

    fun setChoice(i: Int, v: Int) {
        if (i !in values.indices) return
        pushParams()
        values[i] = v.toFloat()
        onParamsChanged(false)
    }

    fun setGradient(index: Int) {
        pushParams()
        gradientIndex = index
        onParamsChanged(false)
    }

    /** Vor dem Bearbeiten eines Kurvenpunkts aufrufen (Rückgängig-Schritt). */
    fun beginCurveEdit() = pushParams()

    /** Nach Änderung der Kurvenpunkte aufrufen. */
    fun curvesChanged(interactive: Boolean) {
        curvesVersion++
        onParamsChanged(interactive)
    }

    fun setPencil(on: Boolean) {
        val k = kind ?: return
        if (!k.pencil || on == pencilMode) return
        pencilMode = on
        if (on) {
            val l = layer ?: return
            if (pencilMask == null) {
                val m = MaskUtils.create(fullW, fullH)
                pencilMask = m
                pencilCanvas = Canvas(m)
            }
            ensureAdjustedSync()
            rebuildPencil(null)
            l.displayOverride = overrideBmp
        } else {
            onParamsChanged(false)
        }
        state.canvasView?.postInvalidateOnAnimation()
    }

    /** Alle Werte zurücksetzen (als Rückgängig-Schritt). */
    fun reset() {
        val k = kind ?: return
        when (k) {
            AdjustmentKind.LIQUIFY -> liquify?.let { history.addLast(Step.Liquify(it.snapshot())); it.reset() }
            AdjustmentKind.CLONE -> {
                val l = layer ?: return
                val work = cloneWork ?: return
                history.addLast(Step.CloneRegion(Rect(0, 0, fullW, fullH), MaskUtils.crop(work, Rect(0, 0, fullW, fullH))))
                cloneWorkCanvas?.drawBitmap(l.bitmap, 0f, 0f, srcPaint)
                cloneShadow?.let { Canvas(it).drawBitmap(l.bitmap, 0f, 0f, srcPaint) }
            }
            else -> {
                pushParams()
                for (i in values.indices) values[i] = k.params[i].default
                curves.reset(); curvesVersion++
                gradientIndex = 0
                centerX = 0.5f; centerY = 0.5f
                if (pencilMode) {
                    history.addLast(Step.Mask(pencilMask?.copy(Bitmap.Config.ALPHA_8, true)))
                    pencilMask?.eraseColor(0)
                }
                onParamsChanged(false)
            }
        }
        trimHistory()
        state.canvasView?.postInvalidateOnAnimation()
    }

    /** Letzten Schritt innerhalb der Anpassung rückgängig machen. */
    fun undoStep() {
        val s = history.removeLastOrNull() ?: return
        historySize = history.size
        when (s) {
            is Step.Params -> {
                for (i in s.values.indices) if (i < values.size) values[i] = s.values[i]
                restoreCurves(s.curves)
                gradientIndex = s.gradient
                centerX = s.cx; centerY = s.cy
                onParamsChanged(false)
            }
            is Step.Mask -> {
                val m = pencilMask
                if (m != null) {
                    m.eraseColor(0)
                    s.mask?.let { pencilCanvas?.drawBitmap(it, 0f, 0f, srcPaint) }
                    rebuildPencil(null)
                }
            }
            is Step.Liquify -> liquify?.restore(s.pos)
            is Step.CloneRegion -> {
                cloneWorkCanvas?.drawBitmap(s.before, s.rect.left.toFloat(), s.rect.top.toFloat(), srcPaint)
                cloneShadow?.let { Canvas(it).drawBitmap(s.before, s.rect.left.toFloat(), s.rect.top.toFloat(), srcPaint) }
            }
        }
        state.canvasView?.postInvalidateOnAnimation()
    }

    private fun restoreCurves(c: Curves) {
        for (i in 0 until 4) {
            curves.channels[i].clear()
            c.channels[i].forEach { curves.channels[i].add(android.graphics.PointF(it.x, it.y)) }
        }
        curvesVersion++
    }

    private fun pushParams() {
        history.addLast(Step.Params(values.toFloatArray(), curves.copy(), gradientIndex, centerX, centerY))
        trimHistory()
    }

    private fun trimHistory() {
        // Bitmap-Schritte begrenzen, Parameter-Schritte sind klein
        while (history.size > 40) history.removeFirst()
        var heavy = history.count { it is Step.Mask }
        while (heavy > 8) {
            val idx = history.indexOfFirst { it is Step.Mask }
            history.removeAt(idx); heavy--
        }
        historySize = history.size
    }

    // ================================================================= Berechnung

    private fun isNeutral(): Boolean {
        val k = kind ?: return true
        return Filters.isNeutral(k, values.toFloatArray(), curves)
    }

    private fun makeInput(scale: Float, token: CancelToken): FilterInput? {
        val k = kind ?: return null
        return FilterInput(
            kind = k,
            v = values.toFloatArray(),
            scale = scale,
            fullMax = max(fullW, fullH),
            cx = centerX, cy = centerY,
            curveLuts = if (k == AdjustmentKind.CURVES) curves.luts() else null,
            gradientLut = if (k == AdjustmentKind.GRADIENT_MAP)
                GradientPresets.lut(GradientPresets.all(state.color)[gradientIndex].colors) else null,
            token = token,
        )
    }

    private fun onParamsChanged(interactive: Boolean) {
        val k = kind ?: return
        if (k.isBrushTool) return
        gen++
        fullResult = null
        fullToken?.cancelled = true
        if (!pencilMode && isNeutral()) {
            layer?.let { if (it.displayOverride === overrideBmp) it.displayOverride = null }
            busy = false
            state.canvasView?.postInvalidateOnAnimation()
            return
        }
        if (!pencilMode) requestPreview()
        if (!interactive) requestFull()
    }

    private fun requestPreview() {
        if (previewRunning) { previewPending = true; return }
        val k = kind ?: return
        val src = srcPreview ?: return
        val inp = makeInput(previewScale, CancelToken()) ?: return
        previewRunning = true
        previewPending = false
        val myGen = gen
        val sid = sessionId
        val w = pw
        val h = ph
        scope.launch {
            val out = try {
                withContext(Dispatchers.Default) { Filters.process(src, w, h, inp) }
            } catch (c: CancellationException) { null } catch (t: Throwable) { Log.e("Farbwerk", "Vorschau", t); null }
            if (sid != sessionId) return@launch
            previewRunning = false
            if (out != null && !pencilMode && !isNeutral()) {
                if (previewScale >= 1f && myGen == gen) { fullResult = out; fullGen = myGen }
                showResult(out, w, h)
            }
            if (previewPending) requestPreview()
        }
    }

    private fun requestFull() {
        if (previewScale >= 1f && !pencilMode) {
            // Vorschau = volle Auflösung
            if (fullGen != gen) requestPreview()
            return
        }
        val k = kind ?: return
        val src = srcFull ?: return
        fullToken?.cancelled = true
        val token = CancelToken()
        fullToken = token
        val inp = makeInput(1f, token) ?: return
        val myGen = gen
        val sid = sessionId
        busy = true
        scope.launch {
            val out = try {
                withContext(Dispatchers.Default) { Filters.process(src, fullW, fullH, inp) }
            } catch (c: CancellationException) { null } catch (t: Throwable) { Log.e("Farbwerk", "Anpassung", t); null }
            if (sid != sessionId || token.cancelled) return@launch
            busy = false
            if (out == null || myGen != gen) return@launch
            fullResult = out
            fullGen = myGen
            if (pencilMode) {
                updateAdjusted(out, myGen)
                rebuildPencil(null)
            } else if (!isNeutral()) {
                showResult(out, fullW, fullH)
            }
        }
    }

    /** Volles Ergebnis (bei Bedarf synchron berechnen). */
    private fun ensureFullSync(): IntArray? {
        if (fullResult != null && fullGen == gen) return fullResult
        val k = kind ?: return null
        val src = srcFull ?: return null
        val inp = makeInput(1f, CancelToken()) ?: return null
        val out = Filters.process(src, fullW, fullH, inp)
        fullResult = out
        fullGen = gen
        return out
    }

    private fun ensureOverride(): Bitmap {
        overrideBmp?.let { return it }
        val b = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888)
        overrideBmp = b
        overrideCanvas = Canvas(b)
        return b
    }

    private fun ensureScratch(): Canvas {
        scratchCanvas?.let { return it }
        val b = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888)
        scratch = b
        return Canvas(b).also { scratchCanvas = it }
    }

    /** Ergebnis anzeigen (ggf. hochskaliert) und auf die Auswahl beschränken. */
    private fun showResult(out: IntArray, w: Int, h: Int) {
        val l = layer ?: return
        val ov = ensureOverride()
        val oc = overrideCanvas ?: return
        if (w == fullW && h == fullH) {
            ov.setPixels(out, 0, w, 0, 0, w, h)
        } else {
            val sb = smallBmp?.takeIf { it.width == w && it.height == h }
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { smallBmp = it }
            sb.setPixels(out, 0, w, 0, 0, w, h)
            oc.drawBitmap(sb, null, Rect(0, 0, fullW, fullH), srcFilterPaint)
        }
        // Auswahl: ergebnis = original·(1−m) + vorschau·m
        state.document?.selection?.let { sel ->
            val sc = ensureScratch()
            sc.drawBitmap(l.bitmap, 0f, 0f, srcPaint)
            sc.drawBitmap(sel.mask, 0f, 0f, dstOutPaint)
            oc.drawBitmap(sel.mask, 0f, 0f, dstInPaint)
            scratch?.let { oc.drawBitmap(it, 0f, 0f, addPaint) }
        }
        l.displayOverride = ov
        state.canvasView?.postInvalidateOnAnimation()
    }

    // ================================================================= Stift-Modus

    private fun ensureAdjustedSync() {
        if (adjustedBmp != null && adjustedGen == gen) return
        val out = ensureFullSync() ?: return
        updateAdjusted(out, gen)
    }

    private fun updateAdjusted(out: IntArray, g: Int) {
        val b = adjustedBmp ?: Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888).also { adjustedBmp = it }
        b.setPixels(out, 0, fullW, 0, 0, fullW, fullH)
        adjustedGen = g
    }

    /** Vorschau im Stift-Modus: original·(1−m) + angepasst·m im Bereich [r] (null = alles). */
    private fun rebuildPencil(r: Rect?) {
        val l = layer ?: return
        val adj = adjustedBmp ?: return
        val m = pencilMask ?: return
        ensureOverride()
        val oc = overrideCanvas ?: return
        val sc = ensureScratch()
        val rr = r ?: Rect(0, 0, fullW, fullH)
        oc.save(); oc.clipRect(rr)
        oc.drawBitmap(l.bitmap, 0f, 0f, srcPaint)
        oc.drawBitmap(m, 0f, 0f, dstOutPaint)
        oc.restore()
        sc.save(); sc.clipRect(rr)
        sc.drawBitmap(adj, 0f, 0f, srcPaint)
        sc.drawBitmap(m, 0f, 0f, dstInPaint)
        sc.restore()
        oc.save(); oc.clipRect(rr)
        scratch?.let { oc.drawBitmap(it, 0f, 0f, addPaint) }
        oc.restore()
        l.displayOverride = overrideBmp
        state.canvasView?.postInvalidateOnAnimation()
    }

    private fun pencilRadius() = 2f + (pencilSize / 100f).pow(1.5f) * max(fullW, fullH) * 0.15f

    private fun pencilDab(x: Float, y: Float, pressure: Float, dirty: RectF) {
        val c = pencilCanvas ?: return
        val r = pencilRadius() * (0.4f + 0.6f * pressure)
        dabPaint.alpha = (110 * (0.3f + 0.7f * pressure)).toInt()
        val rect = RectF(x - r, y - r, x + r, y + r)
        c.drawBitmap(dab(30), null, rect, dabPaint)
        dirty.union(rect)
    }

    /** Weiche, runde Pinselspitze (ALPHA_8, 128 px) mit Härte 0..100. */
    private fun dab(hardness: Int): Bitmap {
        val key = (hardness / 5) * 5
        dabCache[key]?.let { return it }
        val size = 128
        val bytes = ByteArray(size * size)
        val hd = key / 100f * 0.95f
        for (y in 0 until size) for (x in 0 until size) {
            val d = hypot(x + 0.5f - size / 2f, y + 0.5f - size / 2f) / (size / 2f)
            val a = when {
                d >= 1f -> 0f
                d <= hd -> 1f
                else -> { val t = (d - hd) / (1f - hd); 1f - t * t * (3f - 2f * t) }
            }
            bytes[y * size + x] = (a * 255f).roundToInt().toByte()
        }
        return MaskUtils.fromBytes(size, size, bytes).also { dabCache[key] = it }
    }

    // ================================================================= Verflüssigen

    private fun liquifyMode(): LiquifyEngine.Mode =
        LiquifyEngine.Mode.entries[values.getOrElse(0) { 0f }.roundToInt().coerceIn(0, LiquifyEngine.Mode.entries.size - 1)]

    fun liquifyRadius(): Float = 4f + (values.getOrElse(1) { 25f } / 100f).pow(1.5f) * max(fullW, fullH) * 0.2f

    private fun liquifyStrength(): Float = values.getOrElse(2) { 50f } / 100f * lastPressure

    // ================================================================= Klonen

    private fun cloneRadius(): Float = 2f + (values.getOrElse(0) { 20f } / 100f).pow(1.5f) * max(fullW, fullH) * 0.12f

    private fun cloneDab(x: Float, y: Float, pressure: Float) {
        val work = cloneWorkCanvas ?: return
        val shadow = cloneShadow ?: return
        val r = cloneRadius() * (0.5f + 0.5f * pressure)
        val size = max(2, ceil(r * 2f).toInt())
        var sb = cloneScratch
        if (sb == null || sb.width < size || sb.height < size) {
            val s2 = max(size, 64)
            sb = Bitmap.createBitmap(s2, s2, Bitmap.Config.ARGB_8888)
            cloneScratch = sb
            cloneScratchCanvas = Canvas(sb)
        }
        val sc = cloneScratchCanvas ?: return
        sc.drawColor(0, PorterDuff.Mode.CLEAR)
        sc.drawBitmap(shadow, -(x + cloneOffX - r), -(y + cloneOffY - r), srcPaint)
        sc.drawBitmap(dab(values.getOrElse(2) { 50f }.roundToInt()), null, RectF(0f, 0f, size.toFloat(), size.toFloat()), dstInPaint)
        clonePaint.alpha = (255 * values.getOrElse(1) { 100f } / 100f * (0.5f + 0.5f * pressure)).toInt().coerceIn(1, 255)
        work.drawBitmap(sb, Rect(0, 0, size, size), RectF(x - r, y - r, x - r + size, y - r + size), clonePaint)
        val d = Rect((x - r).toInt() - 1, (y - r).toInt() - 1, (x + r).toInt() + 2, (y + r).toInt() + 2)
        if (cloneDirty.isEmpty) cloneDirty.set(d) else cloneDirty.union(d)
    }

    // ================================================================= Eingabe

    override fun onStrokeStart(p: InputPoint) {
        syncSession()
        val k = kind ?: return
        downX = p.x; downY = p.y; lastX = p.x; lastY = p.y
        lastPressure = if (p.isStylus) p.pressure.coerceIn(0.05f, 1f) else 1f
        dragMoved = false
        main.removeCallbacks(liquifyTick)
        momentumFrames = 0
        when {
            k == AdjustmentKind.LIQUIFY -> {
                val eng = liquify ?: return
                history.addLast(Step.Liquify(eng.snapshot())); trimHistory()
                drag = Drag.LIQUIFY
                main.postDelayed(liquifyTick, 16)
            }
            k == AdjustmentKind.CLONE -> {
                val srcX = cloneSrcX; val srcY = cloneSrcY
                if (viewDist(srcX, srcY, p.x, p.y) < max(28f * density, cloneRadius() * viewScale())) {
                    drag = Drag.CLONE_SOURCE
                } else {
                    if (!cloneOffsetValid) {
                        cloneOffX = srcX - p.x; cloneOffY = srcY - p.y
                        cloneOffsetValid = true
                    }
                    drag = Drag.CLONE_PAINT
                    cloneDirty.setEmpty()
                    cloneDab(p.x, p.y, lastPressure)
                }
            }
            pencilMode -> {
                ensureAdjustedSync()
                history.addLast(Step.Mask(pencilMask?.copy(Bitmap.Config.ALPHA_8, true))); trimHistory()
                drag = Drag.PENCIL
                val dirty = RectF(p.x, p.y, p.x, p.y)
                pencilDab(p.x, p.y, lastPressure, dirty)
                rebuildPencil(roundOut(dirty))
            }
            k.hasCenter && viewDist(centerX * fullW, centerY * fullH, p.x, p.y) < 30f * density -> {
                pushParams()
                drag = Drag.CENTER
            }
            k.dragParam >= 0 -> {
                pushParams()
                drag = Drag.PARAM
                dragStartValue = values[k.dragParam]
                dragLabel = labelFor(k, k.dragParam)
            }
            else -> drag = Drag.NONE
        }
        state.canvasView?.postInvalidateOnAnimation()
    }

    override fun onStrokeMove(points: List<InputPoint>) {
        val k = kind ?: return
        val p = points.last()
        if (!dragMoved && viewDist(downX, downY, p.x, p.y) > 6f * density) dragMoved = true
        when (drag) {
            Drag.PARAM -> {
                val idx = k.dragParam
                val prm = k.params[idx]
                val vw = (state.canvasView?.width ?: 1000).toFloat()
                tmp[0] = downX; tmp[1] = downY; tmp[2] = p.x; tmp[3] = p.y
                viewMatrix.mapPoints(tmp)
                val dxv = tmp[2] - tmp[0]
                val dyv = tmp[3] - tmp[1]
                if (k == AdjustmentKind.MOTION_BLUR) {
                    // Richtung des Ziehens = Bewegungsrichtung, Länge = Stärke
                    if (dragMoved) {
                        values[idx] = (hypot(dxv, dyv) / (vw * 0.6f) * 100f).coerceIn(prm.min, prm.max)
                        var ang = Math.toDegrees(atan2((p.y - downY).toDouble(), (p.x - downX).toDouble())).toFloat()
                        if (ang < 0f) ang += 360f
                        values[1] = ang
                    }
                } else {
                    values[idx] = (dragStartValue + dxv / (vw * 0.6f) * (prm.max - prm.min)).coerceIn(prm.min, prm.max)
                }
                dragLabel = labelFor(k, idx)
                onParamsChanged(interactive = true)
            }
            Drag.CENTER -> {
                centerX = (p.x / fullW).coerceIn(0f, 1f)
                centerY = (p.y / fullH).coerceIn(0f, 1f)
                onParamsChanged(interactive = true)
            }
            Drag.PENCIL -> {
                val dirty = RectF(p.x, p.y, p.x, p.y)
                for (pt in points) strokeTo(pt) { x, y, pr -> pencilDab(x, y, pr, dirty) }
                rebuildPencil(roundOut(dirty))
            }
            Drag.LIQUIFY -> {
                val eng = liquify ?: return
                val mode = liquifyMode()
                for (pt in points) {
                    lastPressure = if (pt.isStylus) pt.pressure.coerceIn(0.05f, 1f) else 1f
                    val dx = pt.x - lastX
                    val dy = pt.y - lastY
                    eng.apply(mode, pt.x, pt.y, dx, dy, liquifyRadius(), liquifyStrength(), values[3] / 100f)
                    momentumDx = dx; momentumDy = dy
                    lastX = pt.x; lastY = pt.y
                }
                state.canvasView?.postInvalidateOnAnimation()
            }
            Drag.CLONE_PAINT -> {
                for (pt in points) strokeTo(pt) { x, y, pr -> cloneDab(x, y, pr) }
                state.canvasView?.postInvalidateOnAnimation()
            }
            Drag.CLONE_SOURCE -> {
                cloneSrcX = p.x; cloneSrcY = p.y
                cloneOffsetValid = false
                state.canvasView?.postInvalidateOnAnimation()
            }
            Drag.NONE -> {}
        }
    }

    /** Tupft entlang der Strecke vom letzten Punkt zu [pt] (Abstand ≈ ¼ Pinselradius). */
    private inline fun strokeTo(pt: InputPoint, dabAt: (Float, Float, Float) -> Unit) {
        val pr = if (pt.isStylus) pt.pressure.coerceIn(0.05f, 1f) else 1f
        val r = if (drag == Drag.PENCIL) pencilRadius() else cloneRadius()
        val spacing = max(1f, r * 0.25f)
        val dx = pt.x - lastX
        val dy = pt.y - lastY
        val dist = hypot(dx, dy)
        if (dist < spacing) return
        val steps = (dist / spacing).toInt()
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            dabAt(lastX + dx * t, lastY + dy * t, lastPressure + (pr - lastPressure) * t)
        }
        lastX = pt.x; lastY = pt.y; lastPressure = pr
    }

    override fun onStrokeEnd(p: InputPoint) {
        val k = kind ?: return
        when (drag) {
            Drag.PARAM, Drag.CENTER -> onParamsChanged(interactive = false)
            Drag.LIQUIFY -> {
                val dyn = values.getOrElse(4) { 0f } / 100f
                if (dyn > 0f) {
                    momentumTotal = max(1, (dyn * 45).toInt())
                    momentumFrames = momentumTotal
                    drag = Drag.NONE
                    main.removeCallbacks(liquifyTick)
                    main.postDelayed(liquifyTick, 16)
                }
            }
            Drag.CLONE_PAINT -> {
                // Quelle wandert mit dem Pinsel mit; Schritt für "Rückgängig" sichern
                cloneSrcX = lastX + cloneOffX
                cloneSrcY = lastY + cloneOffY
                val shadow = cloneShadow
                val work = cloneWork
                if (shadow != null && work != null && !cloneDirty.isEmpty &&
                    cloneDirty.intersect(0, 0, fullW, fullH)) {
                    history.addLast(Step.CloneRegion(Rect(cloneDirty), MaskUtils.crop(shadow, cloneDirty)))
                    trimHistory()
                    val c = Canvas(shadow)
                    c.save(); c.clipRect(cloneDirty)
                    c.drawBitmap(work, 0f, 0f, srcPaint)
                    c.restore()
                }
            }
            else -> {}
        }
        if (k != AdjustmentKind.LIQUIFY || momentumFrames == 0) main.removeCallbacks(liquifyTick)
        drag = Drag.NONE
        dragLabel = null
        state.invalidate()
    }

    override fun onStrokeCancel() {
        when (drag) {
            Drag.PARAM, Drag.CENTER -> undoStep()
            Drag.PENCIL -> undoStep()
            Drag.LIQUIFY -> undoStep()
            Drag.CLONE_PAINT -> {
                val shadow = cloneShadow
                if (shadow != null && !cloneDirty.isEmpty) {
                    val c = cloneWorkCanvas
                    c?.save(); c?.clipRect(cloneDirty); c?.drawBitmap(shadow, 0f, 0f, srcPaint); c?.restore()
                }
            }
            else -> {}
        }
        main.removeCallbacks(liquifyTick)
        drag = Drag.NONE
        dragLabel = null
        state.invalidate()
    }

    // ================================================================= Overlay

    override fun drawOverlay(canvas: Canvas, docToView: Matrix) {
        viewMatrix.set(docToView)
        syncSession()
        val k = kind ?: return
        val scale = viewScale()
        if (k.hasCenter && !pencilMode) {
            tmp[0] = centerX * fullW; tmp[1] = centerY * fullH
            docToView.mapPoints(tmp, 0, tmp, 0, 1)
            ringPaint.color = 0x80000000.toInt(); ringPaint.strokeWidth = 4f * density
            canvas.drawCircle(tmp[0], tmp[1], 22f * density, ringPaint)
            ringPaint.color = Color.WHITE; ringPaint.strokeWidth = 2f * density
            canvas.drawCircle(tmp[0], tmp[1], 22f * density, ringPaint)
            canvas.drawCircle(tmp[0], tmp[1], 3f * density, ringPaint)
        }
        when (k) {
            AdjustmentKind.CLONE -> {
                val sx = if (drag == Drag.CLONE_PAINT) lastX + cloneOffX else cloneSrcX
                val sy = if (drag == Drag.CLONE_PAINT) lastY + cloneOffY else cloneSrcY
                tmp[0] = sx; tmp[1] = sy
                docToView.mapPoints(tmp, 0, tmp, 0, 1)
                val r = max(10f * density, cloneRadius() * scale)
                ringPaint.color = 0x99000000.toInt(); ringPaint.strokeWidth = 3.5f * density
                canvas.drawCircle(tmp[0], tmp[1], r, ringPaint)
                ringPaint.color = Color.WHITE; ringPaint.strokeWidth = 1.5f * density
                canvas.drawCircle(tmp[0], tmp[1], r, ringPaint)
                canvas.drawLine(tmp[0] - 6 * density, tmp[1], tmp[0] + 6 * density, tmp[1], ringPaint)
                canvas.drawLine(tmp[0], tmp[1] - 6 * density, tmp[0], tmp[1] + 6 * density, ringPaint)
                if (drag == Drag.CLONE_PAINT) brushRing(canvas, lastX, lastY, cloneRadius() * scale)
            }
            AdjustmentKind.LIQUIFY -> if (drag == Drag.LIQUIFY || momentumFrames > 0) brushRing(canvas, lastX, lastY, liquifyRadius() * scale)
            else -> if (drag == Drag.PENCIL) brushRing(canvas, lastX, lastY, pencilRadius() * scale)
        }
        dragLabel?.let { label ->
            val cx = canvas.width / 2f
            val y = 110f * density
            val tw = textPaint.measureText(label) + 32f * density
            canvas.drawRoundRect(cx - tw / 2f, y - 22f * density, cx + tw / 2f, y + 12f * density,
                14f * density, 14f * density, pillPaint)
            canvas.drawText(label, cx, y, textPaint)
        }
    }

    private fun brushRing(canvas: Canvas, x: Float, y: Float, rView: Float) {
        tmp[0] = x; tmp[1] = y
        viewMatrix.mapPoints(tmp, 0, tmp, 0, 1)
        ringPaint.color = 0x66000000; ringPaint.strokeWidth = 3f * density
        canvas.drawCircle(tmp[0], tmp[1], max(4f * density, rView), ringPaint)
        ringPaint.color = 0xDDFFFFFF.toInt(); ringPaint.strokeWidth = 1.2f * density
        canvas.drawCircle(tmp[0], tmp[1], max(4f * density, rView), ringPaint)
    }

    private fun labelFor(k: AdjustmentKind, idx: Int): String = "${k.label}  ${k.params[idx].format(values[idx])}"

    // ================================================================= Hilfen

    private fun roundOut(r: RectF): Rect {
        val out = Rect(r.left.toInt() - 2, r.top.toInt() - 2, r.right.toInt() + 3, r.bottom.toInt() + 3)
        if (!out.intersect(0, 0, fullW, fullH)) out.setEmpty()
        return out
    }

    private fun viewScale(): Float {
        val v = FloatArray(9)
        viewMatrix.getValues(v)
        return hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y]).coerceAtLeast(1e-4f)
    }

    private fun viewDist(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        tmp[0] = x0; tmp[1] = y0; tmp[2] = x1; tmp[3] = y1
        viewMatrix.mapPoints(tmp)
        return hypot(tmp[2] - tmp[0], tmp[3] - tmp[1])
    }

    companion object {
        /** Längere Seite der Vorschau während des Ziehens. */
        const val PREVIEW_MAX = 1024f
    }
}
