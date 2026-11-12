package at.techgaisi.farbwerk.selection

import android.graphics.Path
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.ArtDocument
import at.techgaisi.farbwerk.model.Layer
import at.techgaisi.farbwerk.model.Selection
import at.techgaisi.farbwerk.undo.LambdaAction
import at.techgaisi.farbwerk.undo.UndoAction

/** Fasst mehrere Verlaufsschritte zu einem zusammen (z. B. Ausschneiden + neue Ebene). */
class CompositeAction(override val label: String, private val parts: List<UndoAction>) : UndoAction {
    override fun undo() { for (i in parts.indices.reversed()) parts[i].undo() }
    override fun redo() { parts.forEach { it.redo() } }
    override val bytes: Long get() = parts.sumOf { it.bytes }
}

/** Gemeinsame Hilfen für Auswahl- und Ebenenänderungen mit Verlaufseintrag. */
object SelectionActions {

    // ------------------------------------------------------------ Letzte Auswahl ("Maske neu laden")
    private var lastDocId: String? = null
    private var lastSelection: Selection? = null

    /**
     * Merkt sich [sel] als zuletzt sichtbare Auswahl von [doc]. Wird bei jeder Auswahländerung und beim
     * Zeichnen des Overlays aufgerufen (fängt so auch direkt gesetzte Auswahlen ab, z. B. Ebeneninhalt
     * auswählen oder Transformieren). Billig: nur ein Referenzvergleich.
     */
    fun noteSelection(doc: ArtDocument, sel: Selection?) {
        if (sel == null) return
        if (lastSelection === sel && lastDocId == doc.id) return
        if (sel.mask.width != doc.width || sel.mask.height != doc.height) return
        lastSelection = sel
        lastDocId = doc.id
    }

    /** Zuletzt bekannte Auswahl des Dokuments oder null. */
    fun lastSelectionFor(doc: ArtDocument): Selection? {
        val s = lastSelection ?: return null
        if (lastDocId != doc.id || s.mask.width != doc.width || s.mask.height != doc.height) return null
        return s
    }

    /** Verlaufsschritt für eine Auswahländerung (ohne ihn auszuführen). */
    fun selectionChange(doc: ArtDocument, old: Selection?, new: Selection?, label: String): UndoAction =
        LambdaAction(label, { doc.selection = old }, { doc.selection = new },
            bytes = (new?.mask?.allocationByteCount ?: 0).toLong())

    /** Setzt die Auswahl und legt einen Verlaufseintrag an. */
    fun setSelection(state: EditorState, new: Selection?, label: String = "Auswahl") {
        val doc = state.document ?: return
        val old = doc.selection
        if (old === new) return
        noteSelection(doc, old)
        noteSelection(doc, new)
        doc.selection = new
        state.undo.push(selectionChange(doc, old, new, label))
        state.invalidate()
    }

    /** Kopie einer Auswahl (eigene Maske und eigener Pfad). */
    fun copyOf(sel: Selection): Selection =
        Selection(sel.mask.copy(android.graphics.Bitmap.Config.ALPHA_8, true), sel.path?.let { Path(it) })
            .also { it.feather = sel.feather }

    /**
     * Fügt [layer] direkt über der aktiven Ebene ein und macht sie aktiv. Liefert den
     * Verlaufsschritt (bereits ausgeführt, aber noch nicht gepusht).
     */
    fun insertLayerAbove(state: EditorState, layer: Layer, label: String): UndoAction? {
        val doc = state.document ?: return null
        val prevActive = doc.activeIndex
        val idx = (doc.activeIndex + 1).coerceIn(0, doc.layers.size)
        doc.activeLayer?.groupId?.let { layer.groupId = it }
        doc.layers.add(idx, layer)
        doc.activeIndex = idx
        doc.secondarySelection.clear()
        return LambdaAction(label,
            onUndo = {
                doc.layers.removeAll { it.id == layer.id }
                doc.activeIndex = prevActive.coerceIn(0, (doc.layers.size - 1).coerceAtLeast(0))
            },
            onRedo = {
                doc.layers.add(idx.coerceAtMost(doc.layers.size), layer)
                doc.activeIndex = doc.indexOf(layer)
            },
            bytes = layer.bitmap.allocationByteCount.toLong())
    }
}
