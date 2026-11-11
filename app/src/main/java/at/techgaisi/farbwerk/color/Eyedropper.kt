package at.techgaisi.farbwerk.color

import android.graphics.Canvas
import android.graphics.Matrix
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.InputPoint

/** STUB – wird vom Farb-Modul ersetzt. */
class Eyedropper(private val state: EditorState) {
    fun begin(p: InputPoint) {}
    fun move(p: InputPoint) {}
    fun end(p: InputPoint) {}
    fun cancel() {}
    fun drawOverlay(canvas: Canvas, docToView: Matrix) {}
}
