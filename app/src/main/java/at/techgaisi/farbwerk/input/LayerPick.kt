package at.techgaisi.farbwerk.input

import android.graphics.Color
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.LayerOps
import kotlin.math.roundToInt

/**
 * Einfaches „Ebene wählen“ ohne Popup: macht die oberste sichtbare Ebene mit nicht-transparenten Pixeln
 * an einer Dokumentposition zur Primärebene. Wird von der Leinwand verwendet, solange das Ebenen-Paket
 * kein eigenes Popup über [at.techgaisi.farbwerk.canvas.CanvasView.onSelectLayerAt] anmeldet, und vom
 * QuickMenu („Ebene wählen“ nimmt die oberste Ebene unter dem Menümittelpunkt).
 */
object LayerPick {

    /** Indizes (oben → unten) aller sichtbaren Ebenen mit Inhalt an ([docX], [docY]). */
    fun layersAt(state: EditorState, docX: Float, docY: Float): List<Int> {
        val doc = state.document ?: return emptyList()
        val x = docX.roundToInt(); val y = docY.roundToInt()
        if (x < 0 || y < 0 || x >= doc.width || y >= doc.height) return emptyList()
        val out = ArrayList<Int>()
        for (i in doc.layers.indices.reversed()) {
            val l = doc.layers[i]
            if (!l.visible) continue
            val b = l.bitmap
            if (x >= b.width || y >= b.height) continue
            if (Color.alpha(b.getPixel(x, y)) > 8) out.add(i)
        }
        return out
    }

    /** Oberste Ebene mit Inhalt an der Stelle wählen; meldet das Ergebnis in der Hinweisleiste. */
    fun pickTopmost(state: EditorState, docX: Float, docY: Float): Boolean {
        val doc = state.document ?: return false
        val idx = layersAt(state, docX, docY).firstOrNull()
        if (idx == null) {
            state.flash("Keine Ebene mit Inhalt an dieser Stelle")
            return false
        }
        if (idx != doc.activeIndex) LayerOps.setActive(state, idx)
        state.flash("Ebene: ${doc.layers[idx].name}")
        state.invalidate()
        return true
    }
}
