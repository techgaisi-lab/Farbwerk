package at.techgaisi.farbwerk.undo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.Layer

interface UndoAction {
    val label: String
    fun undo()
    fun redo()
    /** Geschätzter Speicherbedarf in Bytes (für das Speicherlimit). */
    val bytes: Long get() = 0L
}

/** Allgemeine Aktion aus zwei Lambdas (für strukturelle Änderungen wie Ebene hinzufügen). */
class LambdaAction(
    override val label: String,
    private val onUndo: () -> Unit,
    private val onRedo: () -> Unit,
    override val bytes: Long = 0L,
) : UndoAction {
    override fun undo() = onUndo()
    override fun redo() = onRedo()
}

/** Speichert vorher/nachher-Ausschnitte eines Pixelbereichs einer Ebene. */
class LayerPixelsAction(
    override val label: String,
    private val layer: Layer,
    private val rect: Rect,
    private val before: Bitmap,
    private val after: Bitmap,
) : UndoAction {
    override val bytes: Long get() = before.allocationByteCount.toLong() + after.allocationByteCount
    override fun undo() = put(before)
    override fun redo() = put(after)
    private fun put(src: Bitmap) {
        val c = Canvas(layer.bitmap)
        val p = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        c.drawBitmap(src, rect.left.toFloat(), rect.top.toFloat(), p)
    }
}

class UndoManager(private val state: EditorState) {
    private val undoStack = ArrayDeque<UndoAction>()
    private val redoStack = ArrayDeque<UndoAction>()
    private var totalBytes = 0L
    private val maxBytes = 384L * 1024 * 1024

    /** Ändert sich bei jeder Verlaufsänderung (für Compose). */
    var version by mutableIntStateOf(0)
        private set

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun push(action: UndoAction) {
        undoStack.addLast(action)
        totalBytes += action.bytes
        redoStack.forEach { totalBytes -= it.bytes }
        redoStack.clear()
        trim()
        state.markDirty()
        version++
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        // Laufenden Strich (Tastenkürzel/Schaltfläche während des Malens) zuerst verwerfen,
        // sonst würde sein PixelEdit den Zustand vor dem Rückgängigmachen festschreiben.
        state.canvasView?.abortStroke()
        val a = undoStack.removeLastOrNull() ?: return false
        a.undo()
        redoStack.addLast(a)
        state.markDirty()
        state.flash("Rückgängig: ${a.label}")
        state.invalidate()
        version++
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        state.canvasView?.abortStroke()
        val a = redoStack.removeLastOrNull() ?: return false
        a.redo()
        undoStack.addLast(a)
        state.markDirty()
        state.flash("Wiederholen: ${a.label}")
        state.invalidate()
        version++
        return true
    }

    fun clear() {
        undoStack.clear(); redoStack.clear(); totalBytes = 0; version++
    }

    private fun trim() {
        val limit = state.prefs.undoLimit.coerceAtLeast(1)
        while (undoStack.size > limit || (totalBytes > maxBytes && undoStack.size > 1)) {
            val a = undoStack.removeFirst()
            totalBytes -= a.bytes
        }
    }
}

/**
 * Pixel-Bearbeitungssitzung. Jedes Werkzeug, das Pixel einer Ebene verändert, benutzt diese Klasse:
 *
 * ```
 * val edit = state.beginPixelEdit(layer)   // sichert den Zustand
 * ... layer.bitmap verändern ...
 * edit.commit(dirtyRect, "Pinselstrich")   // Auswahl-Clipping + Undo-Eintrag
 * // oder edit.cancel()                      // stellt alles wieder her
 * ```
 * Beim Commit wird eine aktive Auswahl berücksichtigt: Änderungen außerhalb der Auswahl werden verworfen
 * (weiche Kanten werden linear gemischt).
 */
class PixelEdit internal constructor(
    private val state: EditorState,
    val layer: Layer,
    private val before: Bitmap,
) {
    private var finished = false

    fun commit(dirty: Rect?, label: String) {
        if (finished) return
        finished = true
        val doc = state.document ?: return
        val r = Rect(dirty ?: Rect(0, 0, layer.width, layer.height))
        if (!r.intersect(0, 0, layer.width, layer.height) || r.isEmpty) return

        doc.selection?.let { sel ->
            // ergebnis = before*(1-m) + after*m  (premultiplied → ADD ist exakt)
            val afterCrop = Bitmap.createBitmap(layer.bitmap, r.left, r.top, r.width(), r.height())
                .copy(Bitmap.Config.ARGB_8888, true)
            val beforeCrop = Bitmap.createBitmap(before, r.left, r.top, r.width(), r.height())
                .copy(Bitmap.Config.ARGB_8888, true)
            val maskCrop = Bitmap.createBitmap(sel.mask, r.left, r.top, r.width(), r.height())
            Canvas(afterCrop).drawBitmap(maskCrop, 0f, 0f,
                Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
            Canvas(beforeCrop).drawBitmap(maskCrop, 0f, 0f,
                Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
            val c = Canvas(layer.bitmap)
            c.drawBitmap(beforeCrop, r.left.toFloat(), r.top.toFloat(),
                Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) })
            c.drawBitmap(afterCrop, r.left.toFloat(), r.top.toFloat(),
                Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) })
        }

        val b = Bitmap.createBitmap(before, r.left, r.top, r.width(), r.height())
        val a = Bitmap.createBitmap(layer.bitmap, r.left, r.top, r.width(), r.height())
        state.undo.push(LayerPixelsAction(label, layer, r, b, a))
        state.invalidate()
    }

    fun cancel() {
        if (finished) return
        finished = true
        val c = Canvas(layer.bitmap)
        c.drawBitmap(before, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) })
        state.invalidate()
    }

    /** Zustand der Ebene vor Beginn der Bearbeitung (nur lesen!). */
    val original: Bitmap get() = before
}
