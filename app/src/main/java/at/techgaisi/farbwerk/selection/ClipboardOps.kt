package at.techgaisi.farbwerk.selection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.Layer
import at.techgaisi.farbwerk.model.LayerOps
import at.techgaisi.farbwerk.model.Selection
import at.techgaisi.farbwerk.render.Compositor
import at.techgaisi.farbwerk.undo.LayerPixelsAction
import at.techgaisi.farbwerk.undo.UndoAction

/**
 * Kopieren & Einfügen sowie Auswahlbefehle. Die Zwischenablage ist App-intern
 * (Bitmap + Position im Dokument) und überlebt einen Dokumentwechsel.
 */
object ClipboardOps {

    private class Clip(val bitmap: Bitmap, val left: Int, val top: Int)

    private var clip: Clip? = null

    val hasClip: Boolean get() = clip != null

    // ================================================================= Zwischenablage

    fun cut(state: EditorState) {
        val doc = state.document ?: return
        val layer = doc.activeLayer ?: return
        if (layer.locked) { state.flash("Ebene gesperrt"); return }
        val (bmp, rect) = extract(layer.bitmap, doc.selection) ?: run { state.flash("Nichts zum Ausschneiden"); return }
        clip = Clip(bmp, rect.left, rect.top)
        val action = clearRegion(layer, doc.selection, rect, "Ausschneiden")
        state.undo.push(action)
        state.invalidate()
        state.flash("Ausgeschnitten")
    }

    fun copy(state: EditorState) {
        val doc = state.document ?: return
        val layer = doc.activeLayer ?: return
        val (bmp, rect) = extract(layer.bitmap, doc.selection) ?: run { state.flash("Nichts zum Kopieren"); return }
        clip = Clip(bmp, rect.left, rect.top)
        state.flash("Kopiert")
    }

    /** Kopiert das zusammengesetzte Bild aller sichtbaren Ebenen (innerhalb der Auswahl). */
    fun copyAll(state: EditorState) {
        val doc = state.document ?: return
        val flat = Compositor.flatten(doc, includeBackground = false)
        val (bmp, rect) = extract(flat, doc.selection) ?: run { state.flash("Nichts zum Kopieren"); return }
        clip = Clip(bmp, rect.left, rect.top)
        state.flash("Alles kopiert")
    }

    /** Dupliziert die Auswahl (bzw. die ganze Ebene) auf eine neue Ebene darüber. */
    fun duplicate(state: EditorState) {
        val doc = state.document ?: return
        val layer = doc.activeLayer ?: return
        val sel = doc.selection
        if (sel == null) {
            val nl = LayerOps.copyLayer(layer).also { it.name = "${layer.name} Kopie" }
            val a = SelectionActions.insertLayerAbove(state, nl, "Duplizieren") ?: return
            state.undo.push(a)
        } else {
            val (bmp, rect) = extract(layer.bitmap, sel) ?: run { state.flash("Auswahl ist leer"); return }
            val nl = newLayerWith(state, bmp, rect.left, rect.top, "Aus Auswahl") ?: return
            nl.blend = layer.blend
            val a = SelectionActions.insertLayerAbove(state, nl, "Duplizieren") ?: return
            state.undo.push(a)
        }
        state.invalidate()
        state.flash("Dupliziert")
    }

    /** Schneidet die Auswahl aus und fügt sie als neue Ebene darüber ein (ein Verlaufsschritt). */
    fun cutAndPaste(state: EditorState) {
        val doc = state.document ?: return
        val layer = doc.activeLayer ?: return
        if (layer.locked) { state.flash("Ebene gesperrt"); return }
        val sel = doc.selection
        val (bmp, rect) = extract(layer.bitmap, sel) ?: run { state.flash("Nichts zum Ausschneiden"); return }
        val parts = ArrayList<UndoAction>(2)
        parts += clearRegion(layer, sel, rect, "Ausschneiden & Einfügen")
        val nl = newLayerWith(state, bmp, rect.left, rect.top, "Ausgeschnitten") ?: return
        parts += SelectionActions.insertLayerAbove(state, nl, "Ausschneiden & Einfügen") ?: return
        state.undo.push(CompositeAction("Ausschneiden & Einfügen", parts))
        state.invalidate()
        state.flash("Ausgeschnitten & eingefügt")
    }

    /** Fügt die Zwischenablage als neue Ebene über der aktiven ein. */
    fun paste(state: EditorState) {
        val doc = state.document ?: return
        val c = clip ?: run { state.flash("Zwischenablage leer"); return }
        var left = c.left
        var top = c.top
        // Aus einem anderen (kleineren) Dokument: so verschieben, dass der Inhalt sichtbar bleibt.
        if (left >= doc.width || top >= doc.height || left + c.bitmap.width <= 0 || top + c.bitmap.height <= 0) {
            left = (doc.width - c.bitmap.width) / 2
            top = (doc.height - c.bitmap.height) / 2
        }
        val nl = newLayerWith(state, c.bitmap, left, top, "Eingefügt") ?: return
        val a = SelectionActions.insertLayerAbove(state, nl, "Einfügen") ?: return
        state.undo.push(a)
        state.invalidate()
        state.flash("Eingefügt")
    }

    // ================================================================= Auswahlbefehle

    fun selectAll(state: EditorState) {
        val doc = state.document ?: return
        val path = Path().apply { addRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat(), Path.Direction.CW) }
        SelectionActions.setSelection(state, Selection(MaskUtils.full(doc.width, doc.height), path), "Alles auswählen")
    }

    fun deselect(state: EditorState) {
        val doc = state.document ?: return
        if (doc.selection == null) return
        SelectionActions.setSelection(state, null, "Auswahl aufheben")
    }

    fun invertSelection(state: EditorState) {
        val doc = state.document ?: return
        val sel = doc.selection
        if (sel == null) {
            // Keine Auswahl umkehren = alles auswählen
            selectAll(state)
            return
        }
        val inv = MaskUtils.invert(sel.mask)
        if (MaskUtils.bounds(inv) == null) { SelectionActions.setSelection(state, null, "Auswahl umkehren"); return }
        val path = sel.path?.let { Path(it).apply { toggleInverseFillType() } }
        SelectionActions.setSelection(state, Selection(inv, path).also { it.feather = sel.feather }, "Auswahl umkehren")
    }

    // ================================================================= Hilfen

    /**
     * Liefert den (ggf. maskierten) Inhalt von [src] als zugeschnittene Bitmap und ihr Rechteck
     * im Dokument, oder null wenn nichts vorhanden ist.
     */
    private fun extract(src: Bitmap, sel: Selection?): Pair<Bitmap, Rect>? {
        val r = (if (sel != null) MaskUtils.bounds(sel.mask) else MaskUtils.alphaBounds(src)) ?: return null
        val out = MaskUtils.crop(src, r)
        if (sel != null) {
            Canvas(out).drawBitmap(sel.mask, -r.left.toFloat(), -r.top.toFloat(),
                Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
            if (MaskUtils.alphaBounds(out) == null) return null
        }
        return out to r
    }

    /** Löscht [rect] von [layer] (innerhalb der Auswahl, sonst komplett) und liefert den Verlaufsschritt. */
    private fun clearRegion(layer: Layer, sel: Selection?, rect: Rect, label: String): UndoAction {
        val before = MaskUtils.crop(layer.bitmap, rect)
        val c = Canvas(layer.bitmap)
        if (sel != null) {
            c.drawBitmap(sel.mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        } else {
            c.save()
            c.clipRect(rect)
            c.drawColor(0, PorterDuff.Mode.CLEAR)
            c.restore()
        }
        val after = MaskUtils.crop(layer.bitmap, rect)
        return LayerPixelsAction(label, layer, Rect(rect), before, after)
    }

    private fun newLayerWith(state: EditorState, bmp: Bitmap, left: Int, top: Int, name: String): Layer? {
        val doc = state.document ?: return null
        val nb = doc.newLayerBitmap()
        Canvas(nb).drawBitmap(bmp, left.toFloat(), top.toFloat(), null)
        return Layer(name, nb)
    }
}
