package at.techgaisi.farbwerk.adjust

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.SidebarSource
import at.techgaisi.farbwerk.StatusBar
import at.techgaisi.farbwerk.brush.Brush
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
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Anpassungen. Ablauf: [open]/[start] (aus dem Anpassungsmenü) → Leinwand-Gesten/Regler → [apply] oder [cancel].
 *
 * Bedienung wie im Vorbild:
 *  - Wisch-Anpassungen: waagerecht über die Leinwand ziehen ändert die Stärke; oben steht die Statusleiste
 *    „<Filter> NN %“ ([EditorState.statusBar]) mit dem Modus-Wähler Ebene/Stift.
 *  - Tippen auf die Leinwand öffnet das Aktionsmenü ([tapMenuAt]): Vorschau, Anwenden, Zurücksetzen,
 *    Rückgängig, Abbrechen.
 *  - Stift-Modus: der Effekt wird mit dem aktuellen Malpinsel aufgetragen; die Seitenleiste steuert
 *    dessen Größe/Deckkraft ([EditorState.sidebarSource]).
 *  - Verflüssigen/Klonen sind Pinselwerkzeuge; die Seitenleiste steuert Größe/Druck bzw. Größe/Stärke.
 *  - Anpassungen-Symbol erneut tippen = [applyAndClose].
 *
 * Regler-Anpassungen („Ebene“-Modus): die Vorschau wird im Hintergrund auf einer herunterskalierten
 * Kopie berechnet (während des Ziehens) und beim Loslassen in voller Auflösung; angezeigt wird sie über
 * Layer.displayOverride. Im Stift-Modus wird das volle Ergebnis per Pinselmaske eingeblendet.
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
    /** Veraltet (früherer eigener Größenregler) – der Stift-Modus nutzt Größe/Deckkraft des Malpinsels. */
    var pencilSize by mutableFloatStateOf(30f)
    var curveChannel by mutableIntStateOf(0)
    val curves = Curves()
    /** Erhöht sich bei jeder Kurvenänderung (Compose liest das). */
    var curvesVersion by mutableIntStateOf(0)
        private set
    /** Histogramme für die Kurven (0 = Helligkeit, 1..3 = R/G/B), je 256 Werte 0..1; null = keins. */
    var histogram by mutableStateOf<Array<FloatArray>?>(null)
        private set

    /** Verlaufsbibliothek der Verlaufsumsetzung (dauerhaft gespeichert). */
    val gradients: GradientLibrary by lazy { GradientLibrary(state.context) }
    /** Gewählter Verlauf (bleibt über Sitzungen hinweg erhalten). */
    var gradientId by mutableStateOf<String?>(null)
        private set
    val gradientIndex: Int get() = gradients.indexOf(gradientId).coerceAtLeast(0)
    /** Verlaufseditor geöffnet (statt Bibliothek). */
    var gradientEditorOpen by mutableStateOf(false)

    /** Volle Auflösung wird gerade berechnet. */
    var busy by mutableStateOf(false)
        private set
    var historySize by mutableIntStateOf(0)
        private set
    /** Werkzeug, zu dem nach Anwenden/Abbrechen zurückgekehrt wird. */
    var returnTool: Tool = Tool.PAINT

    /** Vorschau zeigt gerade das Original (Aktionsmenü „Vorschau“). */
    var showingOriginal by mutableStateOf(false)
        private set
    /** Aktionsmenü nach Tippen auf die Leinwand – Position in Fenster-Pixeln, null = geschlossen. */
    var tapMenuAt by mutableStateOf<Offset?>(null)
        private set
    /** Modus-Wähler (Ebene/Stift) unter der Statusleiste geöffnet. */
    var modeMenuOpen by mutableStateOf(false)
    /** Klonen: Quelle gesperrt (bleibt bei jedem neuen Strich an derselben Stelle). */
    var cloneLocked by mutableStateOf(false)
        private set

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
    /** [overrideBmp] enthält ein gültiges Ergebnis. */
    private var resultShown = false
    private var smallBmp: Bitmap? = null
    private var scratch: Bitmap? = null
    private var scratchCanvas: Canvas? = null
    private var fullResult: IntArray? = null
    private var fullGen = -1
    private var gen = 0
    /** Mittelpunkt (normiert) für Perspektivische Unschärfe / Chromatische Aberration / Umfärben. */
    var centerX = 0.5f
        private set
    var centerY = 0.5f
        private set

    private var previewRunning = false
    private var previewPending = false
    private var fullToken: CancelToken? = null
    private var paramEditing = false
    private var lastRecolorColor = 0

    // Stift-Modus
    private var pencilMask: Bitmap? = null
    private var pencilCanvas: Canvas? = null
    private var adjustedBmp: Bitmap? = null
    private var adjustedGen = -1
    private val maskStroke = MaskStroke()
    /** Maske vor dem laufenden Strich (Stift-Modus). */
    private var strokeBefore: Bitmap? = null
    private var strokeOpacity = 1f

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
    private var cloneSrcX = 0f
    private var cloneSrcY = 0f
    private var cloneOffX = 0f
    private var cloneOffY = 0f
    private var cloneOffsetValid = false
    private val cloneDirty = Rect()
    private var cloneStrength = 1f
    private var pulseStart = 0L

    // Verlauf innerhalb der Anpassung (Aktion „Rückgängig“)
    private sealed class Step {
        class Params(val values: FloatArray, val curves: Curves, val gradient: String?, val cx: Float, val cy: Float) : Step()
        class Mask(val mask: Bitmap?) : Step()
        class Liquify(val pos: FloatArray, val adjust: Float) : Step()
        class CloneRegion(val rect: Rect, val before: Bitmap) : Step()
    }
    private val history = ArrayDeque<Step>()

    // Zeichnen
    private val srcPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private val srcFilterPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private val dstInPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val dstOutPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val addPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val maskMergePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val meshPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val clonePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrowPath = Path()

    // Eingabe
    private enum class Drag { NONE, PARAM, CENTER, ANGLE, PENCIL, LIQUIFY, CLONE_PAINT, CLONE_SOURCE }
    private var drag = Drag.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastPressure = 1f
    private var dragStartValue = 0f
    private var dragMoved = false
    /** Rückgängig-Schritt für die laufende Geste wurde bereits angelegt. */
    private var dragPushed = false
    private val viewMatrix = Matrix()
    private val tmp = FloatArray(4)
    private val mv = FloatArray(9)
    private val loc = IntArray(2)

    // Verflüssigen: Halten (Wirbel etc.) und Dynamik
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

    /** Klonen: Halten auf der Quellscheibe sperrt/entsperrt sie. */
    private val cloneLockRunnable = Runnable {
        if (drag == Drag.CLONE_SOURCE && !dragMoved && kind == AdjustmentKind.CLONE) toggleCloneLock()
    }

    // ================================================================= Statusleiste / Seitenleiste

    /** Zuletzt von hier gesetzte Statusleiste (nur diese wird wieder entfernt). */
    private var ownBar: StatusBar? = null
    private val modeAction: () -> Unit = { modeMenuOpen = !modeMenuOpen }

    private fun updateStatusBar() {
        val k = kind
        val bar = if (k == null || k.isBrushTool || k == AdjustmentKind.RECOLOR) null else {
            val modeLabel = if (k.pencil) (if (pencilMode) "Stift" else "Ebene") + " ▾" else null
            if (k.isSwipe && k.dragParam < values.size) {
                val p = k.params[k.dragParam]
                val v = values[k.dragParam]
                StatusBar("${k.label} ${p.percentText(v)}", p.fraction(v), modeLabel, if (modeLabel != null) modeAction else null)
            } else {
                StatusBar(k.label, null, modeLabel, if (modeLabel != null) modeAction else null)
            }
        }
        val cur = ownBar
        if (bar != null && cur != null && bar.text == cur.text && bar.percent == cur.percent &&
            bar.actionLabel == cur.actionLabel && state.statusBar === cur) return
        if (bar == null) {
            if (cur != null && state.statusBar === cur) state.statusBar = null
        } else {
            state.statusBar = bar
        }
        ownBar = bar
    }

    /** Seitenleiste steuert zwei Parameter der Anpassung (Verflüssigen, Klonen). */
    private inner class ParamSidebar(
        private val topIdx: Int,
        private val bottomIdx: Int,
        override val topLabel: String,
        override val bottomLabel: String,
    ) : SidebarSource {
        override var top: Float
            get() = frac(topIdx)
            set(v) = setFrac(topIdx, v)
        override var bottom: Float
            get() = frac(bottomIdx)
            set(v) = setFrac(bottomIdx, v)

        private fun frac(i: Int): Float {
            val k = kind ?: return 0f
            val v = values.getOrNull(i) ?: return 0f
            return k.params[i].fraction(v)
        }

        private fun setFrac(i: Int, f: Float) {
            val k = kind ?: return
            if (i !in values.indices) return
            values[i] = k.params[i].fromFraction(f)
        }
    }

    /** Stift-Modus: Seitenleiste = Größe/Deckkraft des Malpinsels (wie beim Malen). */
    private inner class BrushSidebar : SidebarSource {
        override var top: Float
            get() { state.toolSettingsVersion; return state.toolSettings[Tool.PAINT]?.size ?: 0f }
            set(v) { state.toolSettings[Tool.PAINT]?.size = v.coerceIn(0f, 1f); state.toolSettingsVersion++ }
        override var bottom: Float
            get() { state.toolSettingsVersion; return state.toolSettings[Tool.PAINT]?.opacity ?: 1f }
            set(v) { state.toolSettings[Tool.PAINT]?.opacity = v.coerceIn(0f, 1f); state.toolSettingsVersion++ }
    }

    private var ownSidebar: SidebarSource? = null

    private fun updateSidebar() {
        val k = kind
        val src: SidebarSource? = when {
            k == null -> null
            k == AdjustmentKind.LIQUIFY -> ParamSidebar(1, 2, "Größe", "Druck")
            k == AdjustmentKind.CLONE -> ParamSidebar(0, 1, "Größe", "Stärke")
            pencilMode -> BrushSidebar()
            else -> null
        }
        val cur = ownSidebar
        if (src == null) {
            if (cur != null && state.sidebarSource === cur) state.sidebarSource = null
        } else if (cur == null || cur.javaClass != src.javaClass || state.sidebarSource !== cur ||
            (k == AdjustmentKind.LIQUIFY) != (cur.bottomLabel == "Druck")) {
            state.sidebarSource = src
            ownSidebar = src
            return
        } else return
        ownSidebar = null
    }

    // ================================================================= Start / Ende

    /**
     * Öffnet die Anpassung [k] vollständig: schließt Panels, merkt sich das bisherige Werkzeug,
     * wechselt zu Tool.ADJUST und startet die Anpassung (für Menü, Tastenkürzel und QuickMenu).
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
        showingOriginal = false
        resultShown = false
        tapMenuAt = null
        modeMenuOpen = false
        gradientEditorOpen = false
        cloneLocked = false
        history.clear(); historySize = 0
        curves.reset(); curveChannel = 0; curvesVersion++
        histogram = null
        if (gradients.indexOf(gradientId) < 0) gradientId = gradients.items.firstOrNull()?.id
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
                cloneSrcX = fullW * 0.5f
                cloneSrcY = fullH * 0.5f
                cloneOffsetValid = false
                l.displayOverride = work
            }
            else -> {
                prepareSources(l)
                if (k == AdjustmentKind.CURVES) histogram = computeHistogram()
            }
        }
        updateSidebar()
        updateStatusBar()
        // Verlaufsumsetzung und Umfärben wirken bereits mit den Standardwerten
        if (k == AdjustmentKind.GRADIENT_MAP || k == AdjustmentKind.RECOLOR) onParamsChanged(false)
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

    /** Histogramme (Helligkeit, R, G, B) der Vorschau-Kopie, normiert auf das Maximum. */
    private fun computeHistogram(): Array<FloatArray>? {
        val src = srcPreview ?: return null
        val cnt = Array(4) { IntArray(256) }
        for (p in src) {
            val a = p ushr 24
            if (a < 8) continue
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            cnt[0][(r * 77 + g * 150 + b * 29) shr 8]++
            cnt[1][r]++; cnt[2][g]++; cnt[3][b]++
        }
        return Array(4) { c ->
            // Wurzel-Skalierung, damit einzelne Spitzen nicht alles flach drücken
            val m = cnt[c].maxOrNull()?.coerceAtLeast(1) ?: 1
            FloatArray(256) { kotlin.math.sqrt(cnt[c][it] / m.toFloat()) }
        }
    }

    /** Aktuelle Anpassung übernehmen (Undo-Eintrag) und Vorschau entfernen. */
    fun apply() {
        val k = kind ?: return
        val l = layer
        val doc = state.document
        if (l == null || doc == null || doc.indexOf(l) < 0) { endSession(); return }
        if (drag == Drag.PENCIL || drag == Drag.CLONE_PAINT) finishMaskStroke()
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
                        if (bounds != null && !isNeutral()) {
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
                            // Auswahl: nur innerhalb anwenden
                            doc.selection?.let { sel ->
                                val c = Canvas(l.bitmap)
                                val sc = ensureScratch()
                                sc.drawBitmap(e.original, 0f, 0f, srcPaint)
                                sc.drawBitmap(sel.mask, 0f, 0f, dstOutPaint)
                                c.drawBitmap(sel.mask, 0f, 0f, dstInPaint)
                                scratch?.let { c.drawBitmap(it, 0f, 0f, addPaint) }
                            }
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

    /**
     * Übernehmen und verlassen (Anpassungen-Symbol erneut tippen, wie im Vorbild): wendet die laufende
     * Anpassung an und kehrt zum vorherigen Werkzeug zurück.
     */
    fun applyAndClose() {
        apply()
        if (state.tool == Tool.ADJUST) state.selectTool(returnTool)
    }

    /** Abbrechen und zum vorherigen Werkzeug zurückkehren. */
    fun cancelAndClose() {
        cancel()
        if (state.tool == Tool.ADJUST) state.selectTool(returnTool)
    }

    /**
     * Aktionsmenü „Anwenden“: übernimmt den aktuellen Stand und bleibt in derselben Anpassung
     * (Werte beginnen wieder bei 0 – erneutes Anwenden verstärkt den Effekt).
     */
    fun applyAndStay() {
        val k = kind ?: return
        val wasPencil = pencilMode
        val cx = centerX; val cy = centerY
        val keep = values.toFloatArray()
        apply()
        if (state.tool != Tool.ADJUST) return
        start(k)
        if (kind != k) return
        // Auswahl-Parameter (Art/Modus/Kanäle …) und Mittelpunkt bleiben erhalten
        for ((i, p) in k.params.withIndex()) {
            if (i < keep.size && (p.choices != null || p.hidden)) values[i] = keep[i]
        }
        centerX = cx; centerY = cy
        if (wasPencil && k.pencil) setPencil(true)
        if (k == AdjustmentKind.GRADIENT_MAP || k == AdjustmentKind.RECOLOR) onParamsChanged(false)
        updateStatusBar()
        state.flash("${k.label} angewendet")
    }

    private fun endSession() {
        fullToken?.cancelled = true
        fullToken = null
        main.removeCallbacks(liquifyTick)
        main.removeCallbacks(cloneLockRunnable)
        momentumFrames = 0
        layer?.let { l ->
            if (l.displayOverride === overrideBmp || l.displayOverride === cloneWork) l.displayOverride = null
            if (l.drawOverride === liquifyDraw) l.drawOverride = null
        }
        layer = null
        kind = null
        sessionId++
        srcFull = null; srcPreview = null; fullResult = null
        overrideBmp = null; overrideCanvas = null; resultShown = false
        smallBmp = null; scratch = null; scratchCanvas = null
        pencilMask = null; pencilCanvas = null; adjustedBmp = null
        strokeBefore = null
        maskStroke.release()
        liquify = null
        cloneWork = null; cloneWorkCanvas = null; cloneShadow = null
        history.clear(); historySize = 0
        pencilMode = false
        showingOriginal = false
        tapMenuAt = null
        modeMenuOpen = false
        gradientEditorOpen = false
        histogram = null
        busy = false
        drag = Drag.NONE
        previewRunning = false
        previewPending = false
        updateStatusBar()
        updateSidebar()
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
            if (!versionChanged) apply() else endSession()
            if (state.tool == Tool.ADJUST && state.document != null) {
                start(k)
                if (!k.isBrushTool && values.size == saved.size) {
                    for (i in saved.indices) values[i] = saved[i]
                    restoreCurves(savedCurves)
                    onParamsChanged(false)
                }
            }
        }
    }

    // ================================================================= Aktionsmenü

    /** Schließt das Aktionsmenü (Tippen daneben). */
    fun dismissTapMenu() { tapMenuAt = null }

    /** Vorschau: Original (true) oder Ergebnis (false) anzeigen. */
    fun setShowOriginal(on: Boolean) {
        if (kind == null || showingOriginal == on) return
        showingOriginal = on
        refreshDisplay()
    }

    /** Setzt die Ebenen-Überlagerung passend zu [showingOriginal]. */
    private fun refreshDisplay() {
        val l = layer ?: return
        when (kind) {
            null -> return
            AdjustmentKind.LIQUIFY -> l.drawOverride = if (showingOriginal) null else liquifyDraw
            AdjustmentKind.CLONE -> l.displayOverride = if (showingOriginal) null else cloneWork
            else -> l.displayOverride = if (!showingOriginal && resultShown) overrideBmp else null
        }
        state.canvasView?.postInvalidateOnAnimation()
    }

    /** Nach jeder Benutzeränderung wieder das Ergebnis zeigen. */
    private fun leaveOriginal() {
        if (showingOriginal) { showingOriginal = false; refreshDisplay() }
    }

    // ================================================================= Parameter (UI)

    fun setValue(i: Int, v: Float) {
        val k = kind ?: return
        if (i !in values.indices) return
        if (k == AdjustmentKind.LIQUIFY && i == AdjustmentKind.LIQUIFY_ADJUST) {
            val eng = liquify ?: return
            if (!paramEditing) {
                history.addLast(Step.Liquify(eng.snapshot(), values[i])); trimHistory()
                paramEditing = true
            }
            values[i] = v
            eng.setAdjust(v / 100f)
            leaveOriginal()
            state.canvasView?.postInvalidateOnAnimation()
            return
        }
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
        val g = gradients.items.getOrNull(index) ?: return
        if (g.id == gradientId) return
        pushParams()
        gradientId = g.id
        onParamsChanged(false)
    }

    /** Verlauf wurde im Editor geändert (Stopps/Farben). */
    fun gradientEdited(interactive: Boolean) {
        if (kind == AdjustmentKind.GRADIENT_MAP) onParamsChanged(interactive)
    }

    /** Umfärben: aktuelle Farbe hat sich geändert → neu berechnen. */
    fun colorChanged() {
        if (kind == AdjustmentKind.RECOLOR && state.color != lastRecolorColor) onParamsChanged(false)
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
        modeMenuOpen = false
        if (!k.pencil || on == pencilMode) return
        pencilMode = on
        showingOriginal = false
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
        updateSidebar()
        updateStatusBar()
        state.canvasView?.postInvalidateOnAnimation()
    }

    /** Alle Werte zurücksetzen (als Rückgängig-Schritt); bleibt in der Anpassung. */
    fun reset() {
        val k = kind ?: return
        tapMenuAt = null
        when (k) {
            AdjustmentKind.LIQUIFY -> liquify?.let {
                history.addLast(Step.Liquify(it.snapshot(), values[AdjustmentKind.LIQUIFY_ADJUST]))
                it.reset()
                values[AdjustmentKind.LIQUIFY_ADJUST] = 100f
            }
            AdjustmentKind.CLONE -> {
                val l = layer ?: return
                val work = cloneWork ?: return
                history.addLast(Step.CloneRegion(Rect(0, 0, fullW, fullH), MaskUtils.crop(work, Rect(0, 0, fullW, fullH))))
                cloneWorkCanvas?.drawBitmap(l.bitmap, 0f, 0f, srcPaint)
                cloneShadow?.let { Canvas(it).drawBitmap(l.bitmap, 0f, 0f, srcPaint) }
            }
            else -> {
                pushParams()
                for (i in values.indices) {
                    val p = k.params[i]
                    // Auswahl-Parameter (Art/Modus) bleiben wie gewählt
                    if (p.choices == null) values[i] = p.default
                }
                curves.reset(); curvesVersion++
                centerX = 0.5f; centerY = 0.5f
                if (pencilMode) {
                    history.addLast(Step.Mask(pencilMask?.copy(Bitmap.Config.ALPHA_8, true)))
                    pencilMask?.eraseColor(0)
                }
                onParamsChanged(false)
            }
        }
        trimHistory()
        leaveOriginal()
        state.canvasView?.postInvalidateOnAnimation()
    }

    /** Letzten Schritt innerhalb der Anpassung rückgängig machen. */
    fun undoStep() {
        tapMenuAt = null
        val s = history.removeLastOrNull() ?: run { state.flash("Nichts rückgängig zu machen"); return }
        historySize = history.size
        when (s) {
            is Step.Params -> {
                for (i in s.values.indices) if (i < values.size) values[i] = s.values[i]
                restoreCurves(s.curves)
                if (gradients.indexOf(s.gradient) >= 0) gradientId = s.gradient
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
            is Step.Liquify -> {
                liquify?.restore(s.pos)
                if (AdjustmentKind.LIQUIFY_ADJUST < values.size) values[AdjustmentKind.LIQUIFY_ADJUST] = s.adjust
            }
            is Step.CloneRegion -> {
                cloneWorkCanvas?.drawBitmap(s.before, s.rect.left.toFloat(), s.rect.top.toFloat(), srcPaint)
                cloneShadow?.let { Canvas(it).drawBitmap(s.before, s.rect.left.toFloat(), s.rect.top.toFloat(), srcPaint) }
            }
        }
        leaveOriginal()
        updateStatusBar()
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
        history.addLast(Step.Params(values.toFloatArray(), curves.copy(), gradientId, centerX, centerY))
        trimHistory()
    }

    private fun trimHistory() {
        // Bitmap-Schritte begrenzen, Parameter-Schritte sind klein
        while (history.size > 40) history.removeFirst()
        var heavy = history.count { it is Step.Mask || it is Step.CloneRegion }
        while (heavy > 8) {
            val idx = history.indexOfFirst { it is Step.Mask || it is Step.CloneRegion }
            history.removeAt(idx); heavy--
        }
        historySize = history.size
    }

    /** Klonen: Quelle sperren/entsperren (auch per Halten auf der Scheibe). */
    fun toggleCloneLock() {
        if (kind != AdjustmentKind.CLONE) return
        cloneLocked = !cloneLocked
        cloneOffsetValid = false
        pulseStart = SystemClock.uptimeMillis()
        drag = Drag.NONE
        state.canvasView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        state.flash(if (cloneLocked) "Klonen gesperrt" else "Klonen entsperrt")
        state.canvasView?.postInvalidateOnAnimation()
    }

    /** Ist der verschiebbare Mittelpunkt/das Fadenkreuz gerade aktiv? */
    fun centerActive(): Boolean {
        val k = kind ?: return false
        if (pencilMode) return false
        return when (k) {
            AdjustmentKind.PERSPECTIVE_BLUR, AdjustmentKind.RECOLOR -> true
            AdjustmentKind.CHROMATIC -> values.getOrElse(0) { 0f } < 0.5f
            else -> false
        }
    }

    /** Ziehen bestimmt Richtung + Stärke (Bewegungsunschärfe, Chromatische Aberration „Verschieben“). */
    private fun directionalAngleIndex(k: AdjustmentKind): Int = when {
        k == AdjustmentKind.MOTION_BLUR -> 1
        k == AdjustmentKind.CHROMATIC && values.getOrElse(0) { 0f } >= 0.5f -> AdjustmentKind.CHROMATIC_ANGLE
        else -> -1
    }

    // ================================================================= Berechnung

    private fun isNeutral(): Boolean {
        val k = kind ?: return true
        return Filters.isNeutral(k, values.toFloatArray(), curves)
    }

    private fun makeInput(scale: Float, token: CancelToken): FilterInput? {
        val k = kind ?: return null
        lastRecolorColor = state.color
        return FilterInput(
            kind = k,
            v = values.toFloatArray(),
            scale = scale,
            fullMax = max(fullW, fullH),
            cx = centerX, cy = centerY,
            curveLuts = if (k == AdjustmentKind.CURVES) curves.luts() else null,
            gradientLut = if (k == AdjustmentKind.GRADIENT_MAP) gradients.items.getOrNull(gradientIndex)?.lut() else null,
            token = token,
            recolorColor = state.color,
        )
    }

    private fun onParamsChanged(interactive: Boolean) {
        val k = kind ?: return
        updateStatusBar()
        if (k.isBrushTool) return
        leaveOriginal()
        gen++
        fullResult = null
        fullToken?.cancelled = true
        if (!pencilMode && isNeutral()) {
            resultShown = false
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
        resultShown = true
        if (!showingOriginal) l.displayOverride = ov
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
        resultShown = true
        if (!showingOriginal) l.displayOverride = overrideBmp
        state.canvasView?.postInvalidateOnAnimation()
    }

    /** Malpinsel (Werkzeug Malen) – Stift-Modus und Klonen malen damit. */
    private fun paintBrush(): Brush? = state.brushEngine.brushFor(Tool.PAINT)

    /** Überträgt den neuen Teil des Stift-Strichs in die Effektmaske. */
    private fun flushPencil() {
        val r = maskStroke.takeDirty(fullW, fullH) ?: return
        val pc = pencilCanvas ?: return
        val before = strokeBefore ?: return
        val sm = maskStroke.mask ?: return
        pc.save(); pc.clipRect(r)
        pc.drawBitmap(before, 0f, 0f, srcPaint)
        maskMergePaint.alpha = (strokeOpacity * 255f + 0.5f).toInt().coerceIn(0, 255)
        pc.drawBitmap(sm, 0f, 0f, maskMergePaint)
        state.document?.selection?.let { pc.drawBitmap(it.mask, 0f, 0f, dstInPaint) }
        pc.restore()
        rebuildPencil(r)
    }

    /** Überträgt den neuen Teil des Klon-Strichs in die Arbeitskopie. */
    private fun flushClone() {
        val r = maskStroke.takeDirty(fullW, fullH) ?: return
        val wc = cloneWorkCanvas ?: return
        val shadow = cloneShadow ?: return
        val sm = maskStroke.mask ?: return
        val sc = ensureScratch()
        sc.save(); sc.clipRect(r)
        sc.drawColor(0, PorterDuff.Mode.CLEAR)
        // Ziel (x) = Quelle (x + Versatz)
        sc.drawBitmap(shadow, -cloneOffX, -cloneOffY, srcPaint)
        sc.drawBitmap(sm, 0f, 0f, dstInPaint)
        state.document?.selection?.let { sc.drawBitmap(it.mask, 0f, 0f, dstInPaint) }
        sc.restore()
        wc.save(); wc.clipRect(r)
        wc.drawBitmap(shadow, 0f, 0f, srcPaint)
        clonePaint.alpha = (cloneStrength * 255f + 0.5f).toInt().coerceIn(1, 255)
        scratch?.let { wc.drawBitmap(it, 0f, 0f, clonePaint) }
        wc.restore()
        if (cloneDirty.isEmpty) cloneDirty.set(r) else cloneDirty.union(r)
        state.canvasView?.postInvalidateOnAnimation()
    }

    /** Beendet einen laufenden Masken-Strich (Stift/Klonen), z. B. beim Anwenden mitten im Strich. */
    private fun finishMaskStroke() {
        if (!maskStroke.active) return
        maskStroke.end()
        if (drag == Drag.PENCIL) flushPencil() else flushClone()
        maskStroke.clear()
        strokeBefore = null
    }

    // ================================================================= Verflüssigen

    private fun liquifyMode(): LiquifyEngine.Mode =
        LiquifyEngine.Mode.entries[values.getOrElse(0) { 0f }.roundToInt().coerceIn(0, LiquifyEngine.Mode.entries.size - 1)]

    fun liquifyRadius(): Float = 4f + (values.getOrElse(1) { 25f } / 100f).pow(1.5f) * max(fullW, fullH) * 0.2f

    private fun liquifyStrength(): Float = values.getOrElse(2) { 50f } / 100f * lastPressure

    // ================================================================= Klonen

    private fun cloneDiameter(brush: Brush): Float =
        state.brushEngine.diameterFor(brush, AdjustmentKind.CLONE.params[0].fraction(values.getOrElse(0) { 25f }))

    private fun cloneDiscRadiusView(): Float = 26f * density

    // ================================================================= Eingabe

    override fun onStrokeStart(p: InputPoint) {
        syncSession()
        val k = kind ?: return
        tapMenuAt = null
        modeMenuOpen = false
        downX = p.x; downY = p.y; lastX = p.x; lastY = p.y
        lastPressure = if (p.isStylus) p.pressure.coerceIn(0.05f, 1f) else 1f
        dragMoved = false
        dragPushed = false
        main.removeCallbacks(liquifyTick)
        main.removeCallbacks(cloneLockRunnable)
        momentumFrames = 0
        when {
            k == AdjustmentKind.LIQUIFY -> {
                val eng = liquify ?: return
                leaveOriginal()
                // „Anpassen“ festschreiben, bevor weiter verformt wird
                if (values[AdjustmentKind.LIQUIFY_ADJUST] < 100f) {
                    eng.commitAdjust()
                    values[AdjustmentKind.LIQUIFY_ADJUST] = 100f
                }
                history.addLast(Step.Liquify(eng.snapshot(), 100f)); trimHistory()
                drag = Drag.LIQUIFY
                main.postDelayed(liquifyTick, 16)
            }
            k == AdjustmentKind.CLONE -> {
                if (viewDist(cloneSrcX, cloneSrcY, p.x, p.y) < cloneDiscRadiusView() + 10f * density) {
                    drag = Drag.CLONE_SOURCE
                    main.postDelayed(cloneLockRunnable, CLONE_LOCK_DELAY)
                } else {
                    val brush = paintBrush() ?: return
                    leaveOriginal()
                    if (!cloneOffsetValid || cloneLocked) {
                        cloneOffX = cloneSrcX - p.x; cloneOffY = cloneSrcY - p.y
                        cloneOffsetValid = true
                    }
                    cloneStrength = MaskStroke.opacityFor(brush, AdjustmentKind.CLONE.params[1].fraction(values.getOrElse(1) { 100f }))
                    drag = Drag.CLONE_PAINT
                    cloneDirty.setEmpty()
                    maskStroke.ensure(fullW, fullH)
                    maskStroke.begin(brush, cloneDiameter(brush), viewScale(), state.prefs.pressureSmoothing,
                        state.prefs.motionFilteringAmount, p)
                }
            }
            pencilMode -> {
                val brush = paintBrush() ?: return
                val settings = state.toolSettings[Tool.PAINT] ?: return
                ensureAdjustedSync()
                val m = pencilMask ?: return
                val before = m.copy(Bitmap.Config.ALPHA_8, true)
                history.addLast(Step.Mask(before)); trimHistory()
                strokeBefore = before
                strokeOpacity = MaskStroke.opacityFor(brush, settings.opacity)
                leaveOriginal()
                drag = Drag.PENCIL
                maskStroke.ensure(fullW, fullH)
                maskStroke.begin(brush, state.brushEngine.diameterFor(brush, settings.size), viewScale(),
                    state.prefs.pressureSmoothing, state.prefs.motionFilteringAmount, p)
            }
            k == AdjustmentKind.PERSPECTIVE_BLUR && values[0] >= 0.5f && nearArrowTip(p.x, p.y) -> drag = Drag.ANGLE
            centerActive() && (k == AdjustmentKind.RECOLOR ||
                viewDist(centerX * fullW, centerY * fullH, p.x, p.y) < centerRadiusView() + 12f * density) -> {
                drag = Drag.CENTER
            }
            k.dragParam >= 0 -> {
                drag = Drag.PARAM
                dragStartValue = values[k.dragParam]
            }
            else -> drag = Drag.NONE
        }
        state.canvasView?.postInvalidateOnAnimation()
    }

    override fun onStrokeMove(points: List<InputPoint>) {
        val k = kind ?: return
        val p = points.last()
        if (!dragMoved && viewDist(downX, downY, p.x, p.y) > TAP_SLOP_DP * density) {
            dragMoved = true
            main.removeCallbacks(cloneLockRunnable)
        }
        when (drag) {
            Drag.PARAM -> {
                if (!dragMoved) return
                beginGestureStep()
                val idx = k.dragParam
                val prm = k.params[idx]
                val vw = (state.canvasView?.width ?: 1000).toFloat()
                tmp[0] = downX; tmp[1] = downY; tmp[2] = p.x; tmp[3] = p.y
                viewMatrix.mapPoints(tmp)
                val dxv = tmp[2] - tmp[0]
                val dyv = tmp[3] - tmp[1]
                val angIdx = directionalAngleIndex(k)
                if (angIdx >= 0) {
                    // Richtung des Ziehens = Effektrichtung, Länge = Stärke
                    values[idx] = (hypot(dxv, dyv) / (vw * 0.6f) * 100f).coerceIn(prm.min, prm.max)
                    var ang = Math.toDegrees(atan2((p.y - downY).toDouble(), (p.x - downX).toDouble())).toFloat()
                    if (ang < 0f) ang += 360f
                    values[angIdx] = ang
                } else {
                    values[idx] = (dragStartValue + dxv / (vw * 0.6f) * (prm.max - prm.min)).coerceIn(prm.min, prm.max)
                }
                onParamsChanged(interactive = true)
            }
            Drag.CENTER -> {
                if (!dragMoved) return
                beginGestureStep()
                centerX = (p.x / fullW).coerceIn(-0.5f, 1.5f)
                centerY = (p.y / fullH).coerceIn(-0.5f, 1.5f)
                if (k == AdjustmentKind.RECOLOR || k == AdjustmentKind.PERSPECTIVE_BLUR) {
                    centerX = centerX.coerceIn(0f, 1f); centerY = centerY.coerceIn(0f, 1f)
                }
                onParamsChanged(interactive = true)
            }
            Drag.ANGLE -> {
                beginGestureStep()
                var ang = Math.toDegrees(atan2((p.y - centerY * fullH).toDouble(), (p.x - centerX * fullW).toDouble())).toFloat()
                if (ang < 0f) ang += 360f
                values[2] = ang
                onParamsChanged(interactive = true)
            }
            Drag.PENCIL -> {
                maskStroke.add(points)
                flushPencil()
                lastX = p.x; lastY = p.y
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
                maskStroke.add(points)
                flushClone()
                lastX = p.x; lastY = p.y
            }
            Drag.CLONE_SOURCE -> {
                if (!dragMoved) return
                cloneSrcX = p.x; cloneSrcY = p.y
                cloneOffsetValid = false
                state.canvasView?.postInvalidateOnAnimation()
            }
            Drag.NONE -> {}
        }
    }

    /** Legt beim ersten echten Ziehen einer Parameter-Geste einen Rückgängig-Schritt an. */
    private fun beginGestureStep() {
        if (dragPushed) return
        pushParams()
        dragPushed = true
    }

    override fun onStrokeEnd(p: InputPoint) {
        val k = kind ?: return
        main.removeCallbacks(cloneLockRunnable)
        when (drag) {
            Drag.PARAM, Drag.CENTER, Drag.NONE, Drag.ANGLE -> {
                if (!dragMoved && drag != Drag.ANGLE) {
                    // Tippen → Aktionsmenü
                    if (dragPushed) undoStep()
                    if (!k.isBrushTool && !pencilMode) openTapMenu(p.x, p.y)
                } else if (drag != Drag.NONE) {
                    onParamsChanged(interactive = false)
                }
            }
            Drag.PENCIL -> {
                maskStroke.end()
                flushPencil()
                maskStroke.clear()
                strokeBefore = null
            }
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
                maskStroke.end()
                flushClone()
                maskStroke.clear()
                // Quelle wandert mit dem Pinsel mit (außer gesperrt); Schritt für „Rückgängig“ sichern
                if (!cloneLocked) {
                    cloneSrcX = lastX + cloneOffX
                    cloneSrcY = lastY + cloneOffY
                }
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
            Drag.CLONE_SOURCE -> {}
        }
        if (k != AdjustmentKind.LIQUIFY || momentumFrames == 0) main.removeCallbacks(liquifyTick)
        drag = Drag.NONE
        state.invalidate()
    }

    override fun onStrokeCancel() {
        main.removeCallbacks(cloneLockRunnable)
        when (drag) {
            Drag.PARAM, Drag.CENTER, Drag.ANGLE -> if (dragPushed) undoStep() else onParamsChanged(false)
            Drag.PENCIL -> {
                maskStroke.end(); maskStroke.clear(); strokeBefore = null
                undoStep()
            }
            Drag.LIQUIFY -> undoStep()
            Drag.CLONE_PAINT -> {
                maskStroke.end(); maskStroke.clear()
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
        state.invalidate()
    }

    /** Öffnet das Aktionsmenü an der Tipp-Position (Dokumentkoordinaten → Fensterpixel). */
    private fun openTapMenu(docX: Float, docY: Float) {
        val cv = state.canvasView ?: return
        tmp[0] = docX; tmp[1] = docY
        viewMatrix.mapPoints(tmp, 0, tmp, 0, 1)
        cv.getLocationInWindow(loc)
        tapMenuAt = Offset(tmp[0] + loc[0], tmp[1] + loc[1])
    }

    // ================================================================= Overlay

    override fun drawOverlay(canvas: Canvas, docToView: Matrix) {
        viewMatrix.set(docToView)
        syncSession()
        val k = kind ?: return
        val scale = viewScale()
        if (k == AdjustmentKind.RECOLOR && state.color != lastRecolorColor && drag == Drag.NONE) main.post { colorChanged() }
        if (centerActive()) {
            tmp[0] = centerX * fullW; tmp[1] = centerY * fullH
            docToView.mapPoints(tmp, 0, tmp, 0, 1)
            val cx = tmp[0]; val cy = tmp[1]
            val r = centerRadiusView()
            if (k == AdjustmentKind.RECOLOR) {
                // Fadenkreuz mit Farbpunkt der neuen Farbe
                ringPaint.color = 0x80000000.toInt(); ringPaint.strokeWidth = 4f * density
                canvas.drawCircle(cx, cy, r, ringPaint)
                ringPaint.color = Color.WHITE; ringPaint.strokeWidth = 2f * density
                canvas.drawCircle(cx, cy, r, ringPaint)
                val g = r * 0.45f
                canvas.drawLine(cx - r - 6 * density, cy, cx - g, cy, ringPaint)
                canvas.drawLine(cx + g, cy, cx + r + 6 * density, cy, ringPaint)
                canvas.drawLine(cx, cy - r - 6 * density, cx, cy - g, ringPaint)
                canvas.drawLine(cx, cy + g, cx, cy + r + 6 * density, ringPaint)
                fillPaint.color = state.color or 0xFF000000.toInt()
                canvas.drawCircle(cx, cy, 5f * density, fillPaint)
            } else {
                ringPaint.color = 0x80000000.toInt(); ringPaint.strokeWidth = 4f * density
                canvas.drawCircle(cx, cy, r, ringPaint)
                ringPaint.color = Color.WHITE; ringPaint.strokeWidth = 2f * density
                canvas.drawCircle(cx, cy, r, ringPaint)
                fillPaint.color = Color.WHITE
                canvas.drawCircle(cx, cy, 3f * density, fillPaint)
                if (k == AdjustmentKind.PERSPECTIVE_BLUR && values.getOrElse(0) { 0f } >= 0.5f) {
                    drawArrow(canvas, cx, cy, Math.toRadians(values[2].toDouble()).toFloat(), r)
                }
            }
        }
        when (k) {
            AdjustmentKind.CLONE -> {
                val sx: Float
                val sy: Float
                if (drag == Drag.CLONE_PAINT) { sx = maskStroke.headX + cloneOffX; sy = maskStroke.headY + cloneOffY }
                else { sx = cloneSrcX; sy = cloneSrcY }
                tmp[0] = sx; tmp[1] = sy
                docToView.mapPoints(tmp, 0, tmp, 0, 1)
                // Puls beim Sperren/Entsperren: 1 → 1,15 → 1 in ~300 ms
                val t = (SystemClock.uptimeMillis() - pulseStart) / PULSE_MS.toFloat()
                val pulse = if (t in 0f..1f) 1f + 0.15f * sin(t * PI.toFloat()) else 1f
                if (t in 0f..1f) state.canvasView?.postInvalidateOnAnimation()
                val r = cloneDiscRadiusView() * pulse
                fillPaint.color = if (cloneLocked) 0x552F7CF6 else 0x33FFFFFF
                canvas.drawCircle(tmp[0], tmp[1], r, fillPaint)
                ringPaint.color = 0x99000000.toInt(); ringPaint.strokeWidth = 3.5f * density
                canvas.drawCircle(tmp[0], tmp[1], r, ringPaint)
                ringPaint.color = if (cloneLocked) 0xFF2F7CF6.toInt() else Color.WHITE
                ringPaint.strokeWidth = (if (cloneLocked) 2.5f else 1.5f) * density
                canvas.drawCircle(tmp[0], tmp[1], r, ringPaint)
                ringPaint.color = Color.WHITE; ringPaint.strokeWidth = 1.5f * density
                canvas.drawLine(tmp[0] - 7 * density, tmp[1], tmp[0] + 7 * density, tmp[1], ringPaint)
                canvas.drawLine(tmp[0], tmp[1] - 7 * density, tmp[0], tmp[1] + 7 * density, ringPaint)
                if (drag == Drag.CLONE_PAINT) brushRing(canvas, maskStroke.headX, maskStroke.headY, maskStroke.headDiameter / 2f * scale)
            }
            AdjustmentKind.LIQUIFY -> if (drag == Drag.LIQUIFY || momentumFrames > 0) brushRing(canvas, lastX, lastY, liquifyRadius() * scale)
            else -> if (drag == Drag.PENCIL) brushRing(canvas, maskStroke.headX, maskStroke.headY, maskStroke.headDiameter / 2f * scale)
        }
    }

    private fun centerRadiusView(): Float {
        val k = kind
        return when {
            k == AdjustmentKind.PERSPECTIVE_BLUR && values.getOrElse(0) { 0f } >= 0.5f -> 34f * density
            k == AdjustmentKind.RECOLOR -> 18f * density
            else -> 22f * density
        }
    }

    /** Drehbarer Pfeil der richtungsbezogenen Perspektivischen Unschärfe. */
    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, ang: Float, r: Float) {
        val len = r + 34f * density
        val ex = cx + cos(ang) * len
        val ey = cy + sin(ang) * len
        ringPaint.color = 0x80000000.toInt(); ringPaint.strokeWidth = 4f * density
        canvas.drawLine(cx + cos(ang) * r, cy + sin(ang) * r, ex, ey, ringPaint)
        ringPaint.color = Color.WHITE; ringPaint.strokeWidth = 2f * density
        canvas.drawLine(cx + cos(ang) * r, cy + sin(ang) * r, ex, ey, ringPaint)
        val hw = 9f * density
        arrowPath.reset()
        arrowPath.moveTo(ex + cos(ang) * hw, ey + sin(ang) * hw)
        arrowPath.lineTo(ex + cos(ang + 2.4f) * hw, ey + sin(ang + 2.4f) * hw)
        arrowPath.lineTo(ex + cos(ang - 2.4f) * hw, ey + sin(ang - 2.4f) * hw)
        arrowPath.close()
        fillPaint.color = Color.WHITE
        canvas.drawPath(arrowPath, fillPaint)
    }

    private fun nearArrowTip(docX: Float, docY: Float): Boolean {
        tmp[0] = centerX * fullW; tmp[1] = centerY * fullH; tmp[2] = docX; tmp[3] = docY
        viewMatrix.mapPoints(tmp)
        val ang = Math.toRadians(values.getOrElse(2) { 0f }.toDouble()).toFloat()
        val len = centerRadiusView() + 34f * density
        val tx = tmp[0] + cos(ang) * len
        val ty = tmp[1] + sin(ang) * len
        return hypot(tmp[2] - tx, tmp[3] - ty) < 24f * density
    }

    private fun brushRing(canvas: Canvas, x: Float, y: Float, rView: Float) {
        tmp[0] = x; tmp[1] = y
        viewMatrix.mapPoints(tmp, 0, tmp, 0, 1)
        ringPaint.color = 0x66000000; ringPaint.strokeWidth = 3f * density
        canvas.drawCircle(tmp[0], tmp[1], max(4f * density, rView), ringPaint)
        ringPaint.color = 0xDDFFFFFF.toInt(); ringPaint.strokeWidth = 1.2f * density
        canvas.drawCircle(tmp[0], tmp[1], max(4f * density, rView), ringPaint)
    }

    // ================================================================= Hilfen

    private fun viewScale(): Float {
        viewMatrix.getValues(mv)
        return hypot(mv[Matrix.MSCALE_X], mv[Matrix.MSKEW_Y]).coerceAtLeast(1e-4f)
    }

    private fun viewDist(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        tmp[0] = x0; tmp[1] = y0; tmp[2] = x1; tmp[3] = y1
        viewMatrix.mapPoints(tmp)
        return hypot(tmp[2] - tmp[0], tmp[3] - tmp[1])
    }

    companion object {
        /** Längere Seite der Vorschau während des Ziehens. */
        const val PREVIEW_MAX = 1024f
        /** Bewegung (dp), ab der aus einem Tippen ein Ziehen wird. */
        private const val TAP_SLOP_DP = 8f
        /** Haltezeit auf der Klon-Scheibe bis zum Sperren (ms). */
        private const val CLONE_LOCK_DELAY = 550L
        private const val PULSE_MS = 300L
    }
}
