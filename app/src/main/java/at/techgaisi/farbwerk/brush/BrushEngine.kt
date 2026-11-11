package at.techgaisi.farbwerk.brush

import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.InputPoint
import at.techgaisi.farbwerk.model.Tool
import at.techgaisi.farbwerk.model.ToolInputHandler

/** STUB – wird vom Pinsel-Modul ersetzt. */
class BrushEngine(private val state: EditorState) : ToolInputHandler {
    /** Wenn gesetzt, überschreibt das Werkzeug (z. B. Radierer-Ende des Stifts). */
    var temporaryTool: Tool? = null
    override fun onStrokeStart(p: InputPoint) {}
    override fun onStrokeMove(points: List<InputPoint>) {}
    override fun onStrokeEnd(p: InputPoint) {}
    override fun onStrokeCancel() {}
}
