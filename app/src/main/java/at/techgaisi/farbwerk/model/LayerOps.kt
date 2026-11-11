package at.techgaisi.farbwerk.model

import at.techgaisi.farbwerk.EditorState

/** STUB – wird vom Ebenen-Modul ersetzt. Alle Operationen erzeugen Undo-Einträge. */
object LayerOps {
    fun clearActiveLayer(state: EditorState) {}
    fun addLayer(state: EditorState) {}
    fun deleteActiveLayer(state: EditorState) {}
    fun duplicateActiveLayer(state: EditorState) {}
    fun mergeDown(state: EditorState) {}
    fun flattenAll(state: EditorState) {}
    fun toggleAlphaLock(state: EditorState) {}
    fun selectLayerAbove(state: EditorState) {}
    fun selectLayerBelow(state: EditorState) {}
}
