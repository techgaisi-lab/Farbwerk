package at.techgaisi.farbwerk.adjust

import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.InputPoint
import at.techgaisi.farbwerk.model.ToolInputHandler

/** STUB – wird vom Anpassungs-Modul ersetzt. */
class AdjustmentTool(private val state: EditorState) : ToolInputHandler {
    /** Aktuelle Anpassung übernehmen (Undo-Eintrag) und Vorschau entfernen. */
    fun apply() {}
    fun cancel() {}
    override fun onStrokeStart(p: InputPoint) {}
    override fun onStrokeMove(points: List<InputPoint>) {}
    override fun onStrokeEnd(p: InputPoint) {}
    override fun onStrokeCancel() {}
}
