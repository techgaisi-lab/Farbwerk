package at.techgaisi.farbwerk.ui.layers

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.snapshotFlow
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.StatusBar
import at.techgaisi.farbwerk.model.InputPoint
import at.techgaisi.farbwerk.model.Layer
import at.techgaisi.farbwerk.model.Panel
import at.techgaisi.farbwerk.model.ToolInputHandler
import at.techgaisi.farbwerk.undo.LambdaAction
import at.techgaisi.farbwerk.undo.PixelEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Deckkraft-Modus (2-Finger-Tippen auf eine Ebenenzeile).
 *
 * Wird als [EditorState.canvasOverride] gesetzt: horizontales Ziehen irgendwo auf der Leinwand ändert die
 * Deckkraft der Ebene, die Statusleiste zeigt „Deckkraft NN %“. Ein Tippen auf die Leinwand, ein
 * Werkzeugwechsel, das Öffnen eines Panels oder ein Dokumentwechsel beendet den Modus. Beim Beenden entsteht
 * genau ein Undo-Eintrag (falls sich etwas geändert hat).
 *
 * Mit aktiver Auswahl wirkt der Regler – wie im Vorbild – nur innerhalb der Auswahl: dann werden die Pixel
 * innerhalb der Auswahl abgeschwächt (0–100 %, Start 100 %) statt der Ebenen-Deckkraft.
 */
class LayerOpacityMode private constructor(
    private val state: EditorState,
    private val layer: Layer,
) : ToolInputHandler {

    private val startOpacity = layer.opacity
    private val doc = state.document

    /** Pixelmodus bei aktiver Auswahl. */
    private val selectionMask: Bitmap? = doc?.selection?.mask
    private var edit: PixelEdit? = null
    private var factor = 1f
    private var appliedFactor = 1f
    private val dstOut = Paint().apply { blendMode = BlendMode.DST_OUT }
    private val srcPaint = Paint().apply { blendMode = BlendMode.SRC }

    private var dragStartValue = 0f
    private var startScreenX = 0f
    private var moved = false
    private var downX = 0f
    private var downY = 0f
    private var finished = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    /** Aktueller Wert 0..1 (Deckkraft bzw. Faktor in der Auswahl). */
    private val value: Float get() = if (selectionMask != null) factor else layer.opacity

    private fun start() {
        if (selectionMask != null) edit = state.beginPixelEdit(layer)
        state.openPanel = Panel.NONE
        state.canvasOverride = this
        updateStatus()
        val startTool = state.tool
        val startDoc = state.document
        // Werkzeugwechsel, geöffnetes Panel oder anderes Dokument beendet den Modus
        watcher = scope.launch {
            snapshotFlow { Triple(state.tool, state.openPanel, state.document) }
                .drop(1)
                .first { (t, p, d) -> t != startTool || p != Panel.NONE || d !== startDoc }
            finish()
        }
    }

    private fun updateStatus() {
        val pct = (value * 100f).roundToInt()
        state.statusBar = StatusBar("Deckkraft $pct %", value)
    }

    /** Beendet den Modus und legt den Undo-Eintrag an. */
    fun finish() {
        if (finished) return
        finished = true
        watcher?.cancel()
        if (state.canvasOverride === this) state.canvasOverride = null
        if (state.statusBar?.text?.startsWith("Deckkraft") == true) state.statusBar = null
        val e = edit
        if (e != null) {
            if (factor < 1f) e.commit(null, "Deckkraft (Auswahl)") else e.cancel()
        } else {
            val end = layer.opacity
            if (end != startOpacity) {
                val l = layer
                val s = startOpacity
                state.undo.push(LambdaAction("Ebenendeckkraft", { l.opacity = s }, { l.opacity = end }))
            }
        }
        state.invalidate()
        if (active === this) active = null
    }

    // ---------------------------------------------------------------------------------------- Eingabe

    /** Bildschirm-Pixel pro Dokumenteinheit entlang der Bildschirm-x-Achse (berücksichtigt Zoom/Drehung). */
    private var axisX = 1f
    private var axisY = 0f

    override fun onStrokeStart(p: InputPoint) {
        moved = false
        downX = p.x; downY = p.y
        dragStartValue = value
        // Richtung „Bildschirm horizontal“ im Dokument bestimmen: Dokumentvektor pro 1000 Bildschirm-px
        val a = state.windowToDoc(0f, 0f)
        val b = state.windowToDoc(1000f, 0f)
        if (a != null && b != null) {
            val vx = b.x - a.x
            val vy = b.y - a.y
            val len2 = vx * vx + vy * vy
            if (len2 > 0f) { axisX = vx / len2 * 1000f; axisY = vy / len2 * 1000f }
        }
        startScreenX = 0f
    }

    override fun onStrokeMove(points: List<InputPoint>) {
        val p = points.lastOrNull() ?: return
        val dx = p.x - downX
        val dy = p.y - downY
        // Verschiebung in Bildschirm-Pixeln entlang der horizontalen Bildschirmachse
        val screenDx = dx * axisX + dy * axisY
        val dist = hypot(dx * axisX + dy * axisY, -dx * axisY + dy * axisX)
        if (!moved && dist < TAP_SLOP_PX) return
        moved = true
        val width = (state.canvasView?.width ?: 1200).coerceAtLeast(300)
        val v = (dragStartValue + screenDx / (width * RANGE_FRACTION)).coerceIn(0f, 1f)
        apply(v)
    }

    override fun onStrokeEnd(p: InputPoint) {
        if (!moved) { finish(); return }  // Tippen beendet den Modus
    }

    override fun onStrokeCancel() {}

    private fun apply(v: Float) {
        val mask = selectionMask
        if (mask == null) {
            if (abs(layer.opacity - v) < 0.001f) return
            layer.opacity = v
        } else {
            factor = v
            // Nur neu rechnen, wenn sich der Wert um mindestens 1 % geändert hat (zwei Vollbild-Durchgänge)
            if (abs(appliedFactor - v) < 0.01f && v != 0f && v != 1f) { updateStatus(); return }
            appliedFactor = v
            val e = edit ?: return
            val c = Canvas(layer.bitmap)
            c.drawBitmap(e.original, 0f, 0f, srcPaint)
            dstOut.alpha = ((1f - v) * 255f + 0.5f).toInt().coerceIn(0, 255)
            if (dstOut.alpha > 0) c.drawBitmap(mask, 0f, 0f, dstOut)
        }
        updateStatus()
        state.invalidate()
    }

    companion object {
        /** Anteil der Leinwandbreite, der 0 → 100 % entspricht. */
        private const val RANGE_FRACTION = 0.6f
        private const val TAP_SLOP_PX = 12f

        /** Laufender Modus (höchstens einer). */
        var active: LayerOpacityMode? = null
            private set

        /**
         * Startet den Deckkraft-Modus für [layer] (Ebene wird primär). Gesperrte Ebenen werden abgelehnt.
         */
        fun start(state: EditorState, layer: Layer): Boolean {
            val doc = state.document ?: return false
            active?.finish()
            if (layer.locked) { state.flash("Ebene gesperrt"); return false }
            val idx = doc.indexOf(layer)
            if (idx < 0) return false
            if (doc.activeIndex != idx || doc.secondarySelection.isNotEmpty()) {
                doc.activeIndex = idx
                doc.secondarySelection.clear()
            }
            state.canvasView?.abortStroke()
            val m = LayerOpacityMode(state, layer)
            active = m
            m.start()
            return true
        }
    }
}
