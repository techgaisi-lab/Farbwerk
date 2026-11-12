package at.techgaisi.farbwerk.selection

import android.graphics.Bitmap
import android.graphics.BlendMode
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
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.StatusBar
import at.techgaisi.farbwerk.model.InputPoint
import at.techgaisi.farbwerk.model.Layer
import at.techgaisi.farbwerk.model.Selection
import at.techgaisi.farbwerk.model.Tool
import at.techgaisi.farbwerk.model.ToolInputHandler
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class SelectionMode(val label: String) {
    AUTOMATIC("Automatisch"),
    FREEHAND("Freihand"),
    RECTANGLE("Rechteck"),
    ELLIPSE("Ellipse"),
}

/** Im Speicher abgelegte Auswahl (Speichern & Laden). */
class SavedSelection(
    val id: String,
    val selection: Selection,
    val thumbnail: ImageBitmap,
    /** Anzeigename („Auswahl 3“). */
    val name: String = "Auswahl",
)

/**
 * Auswahlwerkzeug.
 *  - Automatisch: Tippen wählt per Flutfüllung; nach dem Tippen horizontal ziehen = Schwellenwert.
 *  - Freihand: Lasso zeichnen und/oder Polygonpunkte tippen; Tippen auf den Startpunkt schließt.
 *  - Rechteck / Ellipse: Aufziehen.
 * Neue Formen werden zur Auswahl hinzugefügt bzw. (Entfernen) davon abgezogen.
 *
 * Öffentliche API für andere Module:
 *  - [reloadLast] – „Letzte Auswahl neu laden“ (Halten auf das Auswahl-Symbol, WP1),
 *  - [beginFeather]/[setFeatherLive]/[endFeather] – Weiche Kante als Live-Regler,
 *  - [onColorChanged] – Farbfüllung färbt die zuletzt gefüllte Auswahl um.
 */
class SelectionTool(private val state: EditorState) : ToolInputHandler {

    // ---------------------------------------------------------------- UI-Zustand (Compose)
    var mode by mutableStateOf(SelectionMode.FREEHAND)
    /** false = Hinzufügen, true = Entfernen. */
    var subtract by mutableStateOf(false)
    /** Neue Formen sofort mit der aktuellen Farbe füllen. */
    var colorFill by mutableStateOf(false)
    /** Schwellenwert der automatischen Auswahl (0..1). */
    var threshold by mutableFloatStateOf(0.35f)
    /** Schwellenwert-Anzeige während des Ziehens (die Anzeige selbst läuft über [EditorState.statusBar]). */
    var thresholdVisible by mutableStateOf(false)
        private set
    /** Weiche-Kante-Popover sichtbar (Live-Regler). Zum Öffnen/Schließen [beginFeather]/[endFeather] nutzen. */
    var featherDialogVisible by mutableStateOf(false)
    /** Aktueller Wert des Weiche-Kante-Reglers (0..1) während der Live-Vorschau. */
    var featherAmount by mutableFloatStateOf(0f)
        private set
    var savedPanelVisible by mutableStateOf(false)
    val savedSelections = mutableStateListOf<SavedSelection>()
    private var savedDocId: String? = null
    private var savedCounter = 0

    /** Von uns gesetzte Statusleiste (nur diese wird wieder entfernt). */
    private var ownStatus: StatusBar? = null

    // ---------------------------------------------------------------- Weiche Kante (live)
    private var featherBase: Selection? = null
    private var featherResult: Selection? = null
    private var featherToken = 0
    private var featherBusy = false
    private var featherPending = false

    // ---------------------------------------------------------------- Farbfüllung
    /** Zuletzt gefüllte Form – wird bei Farbwechsel umgefärbt, solange nichts anderes passiert ist. */
    private class FillRecord(
        val layerId: String,
        val mask: Bitmap,
        val bounds: Rect,
        val before: Bitmap,
        var color: Int,
        var undoVersion: Int,
    )
    private var lastFill: FillRecord? = null

    private val density = state.context.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())

    /** Letzte Dokument→View-Matrix (aus drawOverlay), für Abstände in Bildschirmpunkten. */
    private val viewMatrix = Matrix()
    private val tmp = FloatArray(4)

    // ---------------------------------------------------------------- Ziehen
    private var downX = 0f
    private var downY = 0f
    private var curX = 0f
    private var curY = 0f
    private var dragging = false
    private var moved = false

    // ---------------------------------------------------------------- Freihand
    private var poly = FloatArray(512)
    private var polyCount = 0          // Anzahl Punkte
    private var segmentStart = 0

    // ---------------------------------------------------------------- Automatisch
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "Farbwerk-Auswahl").apply { isDaemon = true } }
    private var autoSrc: IntArray? = null
    private var autoSrcKey: String? = null
    private var autoW = 0
    private var autoH = 0
    private var autoSeedX = 0
    private var autoSeedY = 0
    private var autoStartThreshold = 0f
    private var autoActive = false
    private var previewMask: Bitmap? = null
    private var previewBytes: ByteArray? = null
    private var previewThreshold = -1f
    private var floodBusy = false
    private var floodPending = false
    private var finishAfterFlood = false

    // ---------------------------------------------------------------- Zeichenhilfen
    private val shapePath = Path()
    private val viewPath = Path()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2.5f * density
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF3D8BFF.toInt(); strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** Vorschau der automatischen Auswahl: Bereich farblich invertiert (Differenz mit Weiß). */
    private val previewPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        color = Color.WHITE
        blendMode = BlendMode.DIFFERENCE
    }
    private val srcPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

    init {
        SelectionPrefs.init(state.context)
    }

    // ================================================================= Lebenszyklus

    fun onActivate() {
        resetTransient()
        val doc = state.document
        if (doc != null && doc.id != savedDocId) {
            savedSelections.clear()
            savedCounter = 0
            savedDocId = doc.id
        }
    }

    fun onDeactivate() {
        if (polyCount >= 3) closePolygon() else polyCount = 0
        resetTransient()
        endFeather()
        savedPanelVisible = false
        lastFill = null
    }

    private fun resetTransient() {
        dragging = false
        moved = false
        autoActive = false
        thresholdVisible = false
        finishAfterFlood = false
        previewBytes = null
        previewThreshold = -1f
        polyCount = 0
        segmentStart = 0
        clearStatus()
    }

    private fun showThresholdStatus() {
        val sb = StatusBar("Auswahl-Schwellenwert", threshold)
        ownStatus = sb
        state.statusBar = sb
    }

    private fun clearStatus() {
        val own = ownStatus ?: return
        if (state.statusBar === own) state.statusBar = null
        ownStatus = null
    }

    // ================================================================= Letzte Auswahl

    /**
     * „Letzte Auswahl neu laden“: stellt die zuletzt vorhandene Auswahl des Dokuments wieder her und
     * aktiviert das Auswahlwerkzeug (Hinweis „Maske neu geladen“). Für das Halten auf das Auswahl-Symbol.
     */
    fun reloadLast() {
        val doc = state.document ?: return
        val last = SelectionActions.lastSelectionFor(doc)
        if (last == null) {
            state.flash("Keine frühere Auswahl")
            return
        }
        if (state.tool != Tool.SELECT) state.selectTool(Tool.SELECT)
        if (doc.selection !== last) {
            SelectionActions.setSelection(state, SelectionActions.copyOf(last), "Auswahl neu laden")
        }
        state.flash("Maske neu geladen")
        state.invalidate()
    }

    /**
     * „Hinzufügen“ bzw. „Entfernen“: übernimmt eine offene Freihand-Form (schließt die Schleife) und
     * stellt die Verknüpfung der nächsten Form ein.
     */
    fun setCombine(subtractNext: Boolean) {
        if (mode == SelectionMode.FREEHAND && polyCount >= 3) closePolygon()
        subtract = subtractNext
        state.invalidate()
    }

    /** Moduswechsel: offene Freihand-Polygone werden abgeschlossen. */
    fun changeMode(m: SelectionMode) {
        if (mode == SelectionMode.FREEHAND && m != mode && polyCount >= 3) closePolygon()
        polyCount = 0
        mode = m
        state.invalidate()
    }

    // ================================================================= Eingabe

    override fun onStrokeStart(p: InputPoint) {
        val doc = state.document ?: return
        savedPanelVisible = false
        downX = p.x; downY = p.y; curX = p.x; curY = p.y
        dragging = true
        moved = false
        when (mode) {
            SelectionMode.FREEHAND -> {
                segmentStart = polyCount
                if (polyCount == 0) addPoint(p.x, p.y)
            }
            SelectionMode.AUTOMATIC -> {
                val src = loadAutoSource(doc) ?: return
                autoSrc = src
                autoSeedX = p.x.toInt().coerceIn(0, autoW - 1)
                autoSeedY = p.y.toInt().coerceIn(0, autoH - 1)
                if (p.x < 0 || p.y < 0 || p.x >= autoW || p.y >= autoH) return
                autoActive = true
                autoStartThreshold = threshold
                thresholdVisible = true
                previewThreshold = -1f
                requestFlood()
            }
            else -> {}
        }
    }

    override fun onStrokeMove(points: List<InputPoint>) {
        if (!dragging) return
        val last = points.last()
        curX = last.x; curY = last.y
        if (!moved && viewDistance(downX, downY, curX, curY) > 10f * density) moved = true
        when (mode) {
            SelectionMode.FREEHAND -> if (moved) {
                for (pt in points) {
                    val lx = poly[(polyCount - 1) * 2]
                    val ly = poly[(polyCount - 1) * 2 + 1]
                    if (viewDistance(lx, ly, pt.x, pt.y) >= 2.5f * density) addPoint(pt.x, pt.y)
                }
            }
            SelectionMode.AUTOMATIC -> if (autoActive) {
                val dx = viewDx(downX, downY, curX, curY)
                val nt = (autoStartThreshold + dx / (300f * density)).coerceIn(0f, 1f)
                if (abs(nt - threshold) >= 0.002f) {
                    threshold = nt
                    requestFlood()
                }
                // Statusleiste erst beim Ziehen zeigen (einfaches Tippen bleibt ruhig)
                if (moved) showThresholdStatus()
            }
            else -> {}
        }
        state.canvasView?.postInvalidateOnAnimation()
    }

    override fun onStrokeEnd(p: InputPoint) {
        if (!dragging) return
        dragging = false
        curX = p.x; curY = p.y
        val doc = state.document ?: return
        when (mode) {
            SelectionMode.RECTANGLE, SelectionMode.ELLIPSE -> {
                if (moved) {
                    val r = RectF(min(downX, curX), min(downY, curY), max(downX, curX), max(downY, curY))
                    if (r.width() >= 1f && r.height() >= 1f) {
                        val path = Path()
                        if (mode == SelectionMode.RECTANGLE) path.addRect(r, Path.Direction.CW)
                        else path.addOval(r, Path.Direction.CW)
                        applyShape(MaskUtils.fromPath(doc.width, doc.height, path), path)
                    }
                }
            }
            SelectionMode.FREEHAND -> {
                val closeR = 22f * density
                if (!moved) {
                    // Tippen: Polygonpunkt hinzufügen oder am Startpunkt schließen
                    if (polyCount >= 3 && viewDistance(poly[0], poly[1], downX, downY) < closeR) {
                        closePolygon()
                    } else if (polyCount == segmentStart) {
                        addPoint(downX, downY)
                    }
                } else {
                    addPoint(curX, curY)
                    if (polyCount >= 4 && viewDistance(poly[0], poly[1], curX, curY) < closeR) closePolygon()
                }
            }
            SelectionMode.AUTOMATIC -> if (autoActive) {
                finishAfterFlood = true
                if (!floodBusy && previewThreshold == threshold) commitAuto() else if (!floodBusy) requestFlood()
            }
        }
        state.invalidate()
    }

    override fun onStrokeCancel() {
        dragging = false
        when (mode) {
            SelectionMode.FREEHAND -> polyCount = segmentStart
            SelectionMode.AUTOMATIC -> {
                autoActive = false
                finishAfterFlood = false
                thresholdVisible = false
                previewBytes = null
                threshold = autoStartThreshold
                clearStatus()
            }
            else -> {}
        }
        state.invalidate()
    }

    // ================================================================= Freihand

    private fun addPoint(x: Float, y: Float) {
        if ((polyCount + 1) * 2 > poly.size) poly = poly.copyOf(poly.size * 2)
        poly[polyCount * 2] = x
        poly[polyCount * 2 + 1] = y
        polyCount++
    }

    private fun closePolygon() {
        val doc = state.document
        if (doc == null || polyCount < 3) { polyCount = 0; return }
        val path = Path()
        path.moveTo(poly[0], poly[1])
        for (i in 1 until polyCount) path.lineTo(poly[i * 2], poly[i * 2 + 1])
        path.close()
        polyCount = 0
        segmentStart = 0
        applyShape(MaskUtils.fromPath(doc.width, doc.height, path), path)
    }

    // ================================================================= Automatisch

    /** Quelle der Flutfüllung: Referenzebene (falls vorhanden), sonst aktive Ebene. */
    private fun loadAutoSource(doc: at.techgaisi.farbwerk.model.ArtDocument): IntArray? {
        val layer: Layer = doc.layers.firstOrNull { it.isReference } ?: doc.activeLayer ?: return null
        val key = "${layer.id}:${state.undo.version}:${layer.bitmap.generationId}"
        autoW = layer.width
        autoH = layer.height
        if (key == autoSrcKey && autoSrc?.size == autoW * autoH) return autoSrc
        // Puffer nicht überschreiben, solange eine Flutfüllung im Hintergrund noch daraus liest
        val arr = autoSrc?.takeIf { it.size == autoW * autoH && !floodBusy } ?: IntArray(autoW * autoH)
        layer.bitmap.getPixels(arr, 0, autoW, 0, 0, autoW, autoH)
        autoSrcKey = key
        return arr
    }

    private fun requestFlood() {
        if (floodBusy) { floodPending = true; return }
        val src = autoSrc ?: return
        floodBusy = true
        floodPending = false
        val t = threshold
        val sx = autoSeedX
        val sy = autoSeedY
        val w = autoW
        val h = autoH
        exec.execute {
            val bytes = try { MaskUtils.floodFill(src, w, h, sx, sy, t) } catch (e: Throwable) { null }
            main.post {
                floodBusy = false
                if (!autoActive) return@post
                previewBytes = bytes
                previewThreshold = t
                if (bytes != null) {
                    val pm = previewMask?.takeIf { it.width == w && it.height == h } ?: MaskUtils.create(w, h)
                    MaskUtils.writeBytes(pm, bytes)
                    previewMask = pm
                }
                when {
                    floodPending || previewThreshold != threshold -> requestFlood()
                    finishAfterFlood -> commitAuto()
                }
                state.canvasView?.postInvalidateOnAnimation()
            }
        }
    }

    private fun commitAuto() {
        val doc = state.document
        val bytes = previewBytes
        autoActive = false
        finishAfterFlood = false
        thresholdVisible = false
        previewBytes = null
        clearStatus()
        if (doc == null || bytes == null || bytes.size != doc.width * doc.height) { state.invalidate(); return }
        applyShape(MaskUtils.fromBytes(doc.width, doc.height, bytes), null)
    }

    // ================================================================= Auswahl ändern

    /** Kombiniert eine neue Form mit der bestehenden Auswahl (Hinzufügen/Entfernen) inkl. Verlauf. */
    private fun applyShape(shape: Bitmap, shapePath: Path?) {
        val doc = state.document ?: return
        val old = doc.selection
        val result: Selection? = if (old == null) {
            if (subtract) null else Selection(shape, shapePath)
        } else {
            val mask = MaskUtils.combine(old.mask, shape, subtract)
            val oldPath = old.path
            val path = if (oldPath != null && shapePath != null) {
                Path().apply { op(oldPath, shapePath, if (subtract) Path.Op.DIFFERENCE else Path.Op.UNION) }
            } else null
            if (MaskUtils.bounds(mask) == null) null
            else Selection(mask, path).also { it.feather = old.feather }
        }
        if (result == null && old == null) return
        SelectionActions.setSelection(state, result, if (subtract) "Auswahl entfernen" else "Auswahl")
        if (colorFill && !subtract) fillMask(shape)
    }

    /** Füllt [mask] (ALPHA_8) auf der aktiven Ebene mit der aktuellen Farbe. */
    private fun fillMask(mask: Bitmap) {
        val doc = state.document ?: return
        val layer = doc.activeLayer ?: return
        if (layer.locked) { state.flash("Ebene gesperrt"); return }
        val bounds = MaskUtils.bounds(mask) ?: return
        val before = MaskUtils.crop(layer.bitmap, bounds)
        val e = state.beginPixelEdit(layer)
        val argb = state.color
        Canvas(layer.bitmap).drawBitmap(mask, 0f, 0f, Paint().apply { color = argb })
        e.commit(bounds, "Farbfüllung")
        lastFill = FillRecord(layer.id, mask, bounds, before, argb, state.undo.version)
    }

    /**
     * Farbe wurde geändert: Ist die Farbfüllung aktiv und die zuletzt gefüllte Form noch der letzte
     * Schritt, wird sie in der neuen Farbe neu gefüllt (wie im Vorbild). Die UI ruft das entprellt auf.
     */
    fun onColorChanged(argb: Int) {
        if (!colorFill) return
        val rec = lastFill ?: return
        if (rec.color == argb) return
        val doc = state.document
        val layer = doc?.layers?.firstOrNull { it.id == rec.layerId }
        if (layer == null || state.undo.version != rec.undoVersion ||
            layer.bitmap.width < rec.bounds.right || layer.bitmap.height < rec.bounds.bottom) {
            lastFill = null
            return
        }
        if (layer.locked) return
        val e = state.beginPixelEdit(layer)
        val c = Canvas(layer.bitmap)
        c.drawBitmap(rec.before, rec.bounds.left.toFloat(), rec.bounds.top.toFloat(), srcPaint)
        c.drawBitmap(rec.mask, 0f, 0f, Paint().apply { color = argb })
        e.commit(rec.bounds, "Farbfüllung")
        rec.color = argb
        rec.undoVersion = state.undo.version
        state.invalidate()
    }

    /** Schaltet Farbfüllung um; beim Einschalten wird eine bestehende Auswahl sofort gefüllt. */
    fun toggleColorFill() {
        colorFill = !colorFill
        if (!colorFill) lastFill = null
        if (colorFill) state.document?.selection?.let { fillMask(it.mask) }
        state.flash(if (colorFill) "Farbfüllung an" else "Farbfüllung aus")
    }

    /** Weiche Kante (0..1 → bis 10 % der kürzeren Dokumentseite) in einem Schritt anwenden. */
    fun applyFeather(amount: Float) {
        val doc = state.document ?: return
        val sel = doc.selection ?: run { state.flash("Keine Auswahl"); return }
        if (amount <= 0f) return
        val radius = featherRadius(amount)
        val mask = MaskUtils.feather(sel.mask, radius)
        val ns = Selection(mask, sel.path?.let { Path(it) }).also { it.feather = sel.feather + radius }
        SelectionActions.setSelection(state, ns, "Weiche Kante")
    }

    private fun featherRadius(amount: Float): Float {
        val doc = state.document ?: return 0f
        return amount * min(doc.width, doc.height) * 0.1f
    }

    /** Weiche-Kante-Regler öffnen: merkt sich die Ausgangsauswahl für die Live-Vorschau. */
    fun beginFeather() {
        val sel = state.document?.selection ?: run { state.flash("Keine Auswahl"); return }
        savedPanelVisible = false
        featherBase = sel
        featherResult = null
        featherAmount = 0f
        featherToken++
        featherDialogVisible = true
    }

    /** Live-Vorschau: rechnet im Hintergrund (neuester Wert gewinnt) und zeigt das Ergebnis sofort an. */
    fun setFeatherLive(amount: Float) {
        val base = featherBase ?: return
        featherAmount = amount.coerceIn(0f, 1f)
        val doc = state.document ?: return
        if (featherAmount <= 0.001f) {
            featherResult = null
            doc.selection = base
            state.invalidate()
            return
        }
        if (featherBusy) { featherPending = true; return }
        featherBusy = true
        featherPending = false
        val token = featherToken
        val a = featherAmount
        val radius = featherRadius(a)
        exec.execute {
            val mask = try { MaskUtils.feather(base.mask, radius) } catch (t: Throwable) { null }
            main.post {
                featherBusy = false
                if (token != featherToken) return@post
                val d = state.document
                if (mask != null && d != null && (d.selection === base || d.selection === featherResult)) {
                    val ns = Selection(mask, base.path?.let { Path(it) }).also { it.feather = base.feather + radius }
                    featherResult = ns
                    d.selection = ns
                    state.invalidate()
                }
                if (featherPending || a != featherAmount) setFeatherLive(featherAmount)
            }
        }
    }

    /** Regler schließen: Ergebnis als ein Verlaufsschritt „Weiche Kante“ übernehmen. */
    fun endFeather() {
        if (!featherDialogVisible && featherBase == null) return
        featherDialogVisible = false
        val base = featherBase
        val result = featherResult
        featherBase = null
        featherResult = null
        featherToken++
        featherPending = false
        val doc = state.document ?: return
        if (base == null) return
        if (result != null && doc.selection === result && featherAmount > 0.001f) {
            state.undo.push(SelectionActions.selectionChange(doc, base, result, "Weiche Kante"))
            SelectionActions.noteSelection(doc, result)
        } else if (doc.selection === result) {
            doc.selection = base
        }
        featherAmount = 0f
        state.invalidate()
    }

    fun invert() = ClipboardOps.invertSelection(state)
    fun clear() {
        polyCount = 0
        ClipboardOps.deselect(state)
    }

    // ---------------------------------------------------------------- Speichern & Laden

    fun saveCurrent() {
        val sel = state.document?.selection ?: run { state.flash("Keine Auswahl"); return }
        val copy = SelectionActions.copyOf(sel)
        savedCounter++
        savedSelections.add(SavedSelection(UUID.randomUUID().toString(), copy, thumbnail(copy.mask), "Auswahl $savedCounter"))
        state.flash("Auswahl gespeichert")
    }

    fun load(saved: SavedSelection) {
        val doc = state.document ?: return
        if (saved.selection.mask.width != doc.width || saved.selection.mask.height != doc.height) return
        SelectionActions.setSelection(state, SelectionActions.copyOf(saved.selection), "Auswahl laden")
    }

    fun deleteSaved(saved: SavedSelection) { savedSelections.removeAll { it.id == saved.id } }

    private fun thumbnail(mask: Bitmap): ImageBitmap {
        val s = 72f / max(mask.width, mask.height)
        val tw = max(1, (mask.width * s).roundToInt())
        val th = max(1, (mask.height * s).roundToInt())
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFF3A3A42.toInt())
        c.scale(s, s)
        c.drawBitmap(mask, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { color = Color.WHITE })
        return out.asImageBitmap()
    }

    // ================================================================= Overlay

    override fun drawOverlay(canvas: Canvas, docToView: Matrix) {
        viewMatrix.set(docToView)
        val phase = (android.os.SystemClock.uptimeMillis() / 40L % 1000L).toFloat()
        when (mode) {
            SelectionMode.RECTANGLE, SelectionMode.ELLIPSE -> if (dragging && moved) {
                val r = RectF(min(downX, curX), min(downY, curY), max(downX, curX), max(downY, curY))
                shapePath.rewind()
                if (mode == SelectionMode.RECTANGLE) shapePath.addRect(r, Path.Direction.CW)
                else shapePath.addOval(r, Path.Direction.CW)
                shapePath.transform(docToView, viewPath)
                drawAnts(canvas, viewPath, phase)
            }
            SelectionMode.FREEHAND -> if (polyCount > 0) {
                shapePath.rewind()
                shapePath.moveTo(poly[0], poly[1])
                for (i in 1 until polyCount) shapePath.lineTo(poly[i * 2], poly[i * 2 + 1])
                if (dragging && moved) shapePath.lineTo(curX, curY)
                shapePath.transform(docToView, viewPath)
                drawAnts(canvas, viewPath, phase)
                // Startpunkt (Tippen schließt die Form)
                tmp[0] = poly[0]; tmp[1] = poly[1]
                docToView.mapPoints(tmp, 0, tmp, 0, 1)
                dotPaint.style = Paint.Style.FILL
                dotPaint.color = 0xFF9A9AA3.toInt()
                canvas.drawCircle(tmp[0], tmp[1], 9f * density, dotPaint)
                dotPaint.style = Paint.Style.STROKE
                dotPaint.strokeWidth = 2f * density
                dotPaint.color = Color.WHITE
                canvas.drawCircle(tmp[0], tmp[1], 9f * density, dotPaint)
                // Polygonecken
                dotPaint.style = Paint.Style.FILL
                for (i in 1 until polyCount) {
                    if (polyCount > 64) break // Lasso-Punkte nicht einzeln markieren
                    tmp[0] = poly[i * 2]; tmp[1] = poly[i * 2 + 1]
                    docToView.mapPoints(tmp, 0, tmp, 0, 1)
                    canvas.drawCircle(tmp[0], tmp[1], 3.5f * density, dotPaint)
                }
            }
            SelectionMode.AUTOMATIC -> {
                val pm = previewMask
                if (autoActive && previewBytes != null && pm != null) {
                    canvas.save()
                    canvas.concat(docToView)
                    canvas.drawBitmap(pm, 0f, 0f, previewPaint)
                    canvas.restore()
                }
            }
        }
        if (dragging || polyCount > 0) state.canvasView?.postInvalidateDelayed(80)
    }

    private fun drawAnts(canvas: Canvas, path: Path, phase: Float) {
        canvas.drawPath(path, linePaint)
        dashPaint.pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), -phase % (10f * density))
        canvas.drawPath(path, dashPaint)
    }

    // ================================================================= Hilfen

    private fun viewDistance(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        tmp[0] = x0; tmp[1] = y0; tmp[2] = x1; tmp[3] = y1
        viewMatrix.mapPoints(tmp)
        return hypot(tmp[2] - tmp[0], tmp[3] - tmp[1])
    }

    private fun viewDx(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        tmp[0] = x0; tmp[1] = y0; tmp[2] = x1; tmp[3] = y1
        viewMatrix.mapPoints(tmp)
        return tmp[2] - tmp[0]
    }
}
