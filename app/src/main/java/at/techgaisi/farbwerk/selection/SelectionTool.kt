package at.techgaisi.farbwerk.selection

import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.InputPoint
import at.techgaisi.farbwerk.model.ToolInputHandler

/** STUB – wird vom Auswahl-Modul ersetzt. */
class SelectionTool(private val state: EditorState) : ToolInputHandler {
    fun onActivate() {}
    fun onDeactivate() {}
    override fun onStrokeStart(p: InputPoint) {}
    override fun onStrokeMove(points: List<InputPoint>) {}
    override fun onStrokeEnd(p: InputPoint) {}
    override fun onStrokeCancel() {}
}
