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

/**
 * Wie [LayerPixelsAction], aber für mehrere Teilbereiche (z. B. nur die von einem Pinselstrich veränderten Kacheln
 * statt des ganzen umschließenden Rechtecks).
 *
 * Ohne [after] wird der Zustand „nachher“ erst beim ersten Rückgängigmachen aus der Ebene gelesen: Dann steht die
 * Ebene (Verlauf in umgekehrter Reihenfolge) genau im Zustand nach dieser Aktion. Das spart beim Absetzen eines
 * Strichs das Kopieren aller Kacheln. Für das Speicherlimit wird „nachher“ trotzdem gleich mitgezählt.
 */
class LayerTilesAction(
    override val label: String,
    private val layer: Layer,
    private val rects: List<Rect>,
    private val before: List<Bitmap>,
    after: List<Bitmap>? = null,
) : UndoAction {
    private var after: List<Bitmap>? = after
    override val bytes: Long = before.sumOf { it.allocationByteCount.toLong() } *
        (if (after == null) 2 else 1) + (after?.sumOf { it.allocationByteCount.toLong() } ?: 0L)
    override fun undo() {
        if (after == null) after = rects.map { Bitmap.createBitmap(layer.bitmap, it.left, it.top, it.width(), it.height()) }
        put(before)
    }
    override fun redo() { after?.let { put(it) } }
    private fun put(src: List<Bitmap>) {
        val c = Canvas(layer.bitmap)
        val p = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        for (i in rects.indices) c.drawBitmap(src[i], rects[i].left.toFloat(), rects[i].top.toFloat(), p)
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
        try { at.techgaisi.farbwerk.timelapse.Timelapse.onChange(state) } catch (_: Throwable) {}
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
 *
 * Verzögerte Sicherung ([EditorState.beginLazyPixelEdit], [before] = null): Statt beim Beginn die ganze Ebene zu
 * kopieren (bei 2×-Leinwänden ~90 MB – verzögert sonst jeden Strichbeginn), sichert der Aufrufer mit [protect] die
 * Originalpixel der Kacheln ([TILE]×[TILE]) eines Bereichs unmittelbar *bevor* er die Ebene dort verändert. Nicht
 * gesicherte Kacheln sind unverändert, ihr Original ist also der aktuelle Ebeneninhalt. Der Undo-Eintrag enthält
 * nur die gesicherten Kacheln ([LayerTilesAction]) – bei langen, schrägen Strichen ein Bruchteil des umschließenden
 * Rechtecks. Commit, Abbrechen und [originalRegion] ergeben exakt dasselbe wie mit vollständiger Kopie.
 */
class PixelEdit internal constructor(
    private val state: EditorState,
    val layer: Layer,
    private val before: Bitmap?,
) {
    private var finished = false

    // ---------------- verzögerte Sicherung ----------------
    /** Kachelindex → Originalpixel der Kachel ([TILE]×[TILE], am Rand kleiner). */
    private val saved = HashMap<Int, Bitmap>()
    private val tilesX = (layer.width + TILE - 1) / TILE
    private val srcPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

    /**
     * Verzögerter Modus: sichert die Originalpixel aller noch nicht gesicherten Kacheln im Bereich (Dokumentpixel,
     * rechts/unten exklusiv). Muss vor jeder Veränderung der Ebene in diesem Bereich aufgerufen werden.
     */
    fun protect(left: Int, top: Int, right: Int, bottom: Int) {
        if (before != null || finished) return
        val l = maxOf(0, left); val t = maxOf(0, top)
        val r = minOf(layer.width, right); val b = minOf(layer.height, bottom)
        if (l >= r || t >= b) return
        for (j in t / TILE..(b - 1) / TILE) for (i in l / TILE..(r - 1) / TILE) protectTile(j * tilesX + i)
    }

    /** Verzögerter Modus: sichert die Kachel mit dem Index [k] (Zeile · Kachelspalten + Spalte), falls nötig. */
    fun protectTile(k: Int) {
        if (before != null || finished || saved.containsKey(k)) return
        saveTile(k)
    }

    private fun saveTile(k: Int) {
        val x0 = (k % tilesX) * TILE; val y0 = (k / tilesX) * TILE
        if (x0 >= layer.width || y0 >= layer.height) return
        saved[k] = Bitmap.createBitmap(layer.bitmap, x0, y0, minOf(TILE, layer.width - x0), minOf(TILE, layer.height - y0))
    }

    /** Originalpixel (Zustand vor der Bearbeitung) im Bereich [r] als neue Bitmap (nur lesen!). */
    fun originalRegion(r: Rect): Bitmap {
        before?.let { return Bitmap.createBitmap(it, r.left, r.top, r.width(), r.height()) }
        // aktueller Inhalt (= Original, wo nichts gesichert wurde), darüber die gesicherten Originale
        val out = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.translate(-r.left.toFloat(), -r.top.toFloat())
        c.drawBitmap(layer.bitmap, 0f, 0f, srcPaint)
        drawSaved(c, r)
        return out
    }

    /** Stellt im Bereich [r] den Zustand vor der Bearbeitung wieder her (Ebene bleibt in Bearbeitung). */
    fun restore(r: Rect) {
        val c = Canvas(layer.bitmap)
        c.save()
        c.clipRect(r)
        if (before != null) c.drawBitmap(before, 0f, 0f, srcPaint) else drawSaved(c, r)
        c.restore()
    }

    /** Zeichnet alle gesicherten Originale, die [r] berühren, an ihre Position (SRC). */
    private fun drawSaved(c: Canvas, r: Rect) {
        for ((k, b) in saved) {
            val x0 = (k % tilesX) * TILE; val y0 = (k / tilesX) * TILE
            if (x0 >= r.right || y0 >= r.bottom || x0 + b.width <= r.left || y0 + b.height <= r.top) continue
            c.drawBitmap(b, x0.toFloat(), y0.toFloat(), srcPaint)
        }
    }

    fun commit(dirty: Rect?, label: String) {
        if (finished) return
        finished = true
        val doc = state.document ?: return saved.clear()
        val r = Rect(dirty ?: Rect(0, 0, layer.width, layer.height))
        if (!r.intersect(0, 0, layer.width, layer.height) || r.isEmpty) return saved.clear()
        if (before == null) return commitTiles(doc, r, label)

        doc.selection?.let { sel -> applySelection(sel, r, Bitmap.createBitmap(before, r.left, r.top, r.width(), r.height())) }

        val b = Bitmap.createBitmap(before, r.left, r.top, r.width(), r.height())
        val a = Bitmap.createBitmap(layer.bitmap, r.left, r.top, r.width(), r.height())
        state.undo.push(LayerPixelsAction(label, layer, r, b, a))
        state.invalidate()
    }

    /** Verzögerter Modus: Undo-Eintrag aus den gesicherten Kacheln (je Kachel ∩ [r]). */
    private fun commitTiles(doc: at.techgaisi.farbwerk.model.ArtDocument, r: Rect, label: String) {
        if (doc.selection != null) {
            // Die Auswahl-Mischung wirkt (wie mit vollständiger Kopie) auf das ganze Rechteck: auch die unveränderten
            // Kacheln darin aufnehmen – ihr aktueller Inhalt ist das Original.
            for (j in r.top / TILE..(r.bottom - 1) / TILE) for (i in r.left / TILE..(r.right - 1) / TILE) {
                val k = j * tilesX + i
                if (!saved.containsKey(k)) saveTile(k)
            }
        }
        val rects = ArrayList<Rect>(saved.size)
        val bs = ArrayList<Bitmap>(saved.size)
        for (k in saved.keys.sorted()) {
            val tile = saved.getValue(k)
            val x0 = (k % tilesX) * TILE; val y0 = (k / tilesX) * TILE
            val tr = Rect(x0, y0, x0 + tile.width, y0 + tile.height)
            if (!tr.intersect(r)) continue
            rects += tr
            bs += if (tr.width() == tile.width && tr.height() == tile.height) tile
                else Bitmap.createBitmap(tile, tr.left - x0, tr.top - y0, tr.width(), tr.height())
        }
        saved.clear()
        if (rects.isEmpty()) { state.invalidate(); return }
        doc.selection?.let { sel -> for (i in rects.indices) applySelection(sel, rects[i], bs[i]) }
        // „nachher“ liest die Aktion erst beim Rückgängigmachen (siehe LayerTilesAction)
        state.undo.push(LayerTilesAction(label, layer, rects, bs))
        state.invalidate()
    }

    /**
     * Auswahl: Im Bereich [r] gilt ergebnis = vorher·(1−m) + nachher·m ([beforeCrop] = Original in [r]).
     * Vormultipliziert → ADD ist exakt.
     */
    private fun applySelection(sel: at.techgaisi.farbwerk.model.Selection, r: Rect, beforeCrop: Bitmap) {
        val afterCrop = Bitmap.createBitmap(layer.bitmap, r.left, r.top, r.width(), r.height())
            .copy(Bitmap.Config.ARGB_8888, true)
        val bc = beforeCrop.copy(Bitmap.Config.ARGB_8888, true)
        val maskCrop = Bitmap.createBitmap(sel.mask, r.left, r.top, r.width(), r.height())
        Canvas(afterCrop).drawBitmap(maskCrop, 0f, 0f,
            Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        Canvas(bc).drawBitmap(maskCrop, 0f, 0f,
            Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        val c = Canvas(layer.bitmap)
        c.drawBitmap(bc, r.left.toFloat(), r.top.toFloat(),
            Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) })
        c.drawBitmap(afterCrop, r.left.toFloat(), r.top.toFloat(),
            Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) })
    }

    fun cancel() {
        if (finished) return
        finished = true
        if (before != null) {
            val c = Canvas(layer.bitmap)
            c.drawBitmap(before, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) })
        } else {
            drawSaved(Canvas(layer.bitmap), Rect(0, 0, layer.width, layer.height))
            saved.clear()
        }
        state.invalidate()
    }

    /**
     * Zustand der Ebene vor Beginn der Bearbeitung (nur lesen!). Im verzögerten Modus wird er bei Bedarf einmal
     * vollständig zusammengesetzt (teuer – dort besser [originalRegion]/[restore] benutzen).
     */
    val original: Bitmap get() = before ?: fullOriginal ?: originalRegion(Rect(0, 0, layer.width, layer.height)).also { fullOriginal = it }
    private var fullOriginal: Bitmap? = null

    companion object {
        /** Kantenlänge der verzögert gesicherten Kacheln (Dokumentpixel). */
        const val TILE = 256
    }
}
