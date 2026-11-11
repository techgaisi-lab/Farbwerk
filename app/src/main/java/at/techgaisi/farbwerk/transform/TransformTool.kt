package at.techgaisi.farbwerk.transform

import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.InputPoint
import at.techgaisi.farbwerk.model.ToolInputHandler

/** STUB – wird vom Transform-Modul ersetzt. */
class TransformTool(private val state: EditorState) : ToolInputHandler {
    fun begin() {}
    fun commit() {}
    fun cancel() {}
    override fun onStrokeStart(p: InputPoint) {}
    override fun onStrokeMove(points: List<InputPoint>) {}
    override fun onStrokeEnd(p: InputPoint) {}
    override fun onStrokeCancel() {}
}
