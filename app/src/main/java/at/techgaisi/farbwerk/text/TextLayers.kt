package at.techgaisi.farbwerk.text

import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.io.Importers
import at.techgaisi.farbwerk.model.Layer
import at.techgaisi.farbwerk.model.Tool
import at.techgaisi.farbwerk.undo.LambdaAction

/**
 * Operationen auf Textebenen: anlegen, Änderungen festschreiben (Undo), rastern.
 * Öffentliche Einstiegspunkte für andere Module (Ebenenoptionen, Doppeltippen): [isText], [beginEdit], [rasterize].
 */
object TextLayers {

    fun data(layer: Layer?): TextData? = layer?.text as? TextData

    fun isText(layer: Layer?): Boolean = layer?.text is TextData

    /** Hook für die Oberfläche (setzt die Bearbeitungssitzung). Wird von ui/text registriert. */
    var editorHook: ((EditorState, Layer, Boolean) -> Unit)? = null

    /**
     * Neue Textebene über der aktiven Ebene: Rahmen mit „Text“ in aktueller Farbe, mittig; danach
     * Bearbeitung mit Tastatur. Undo-fähig.
     */
    fun create(state: EditorState, initial: String = "Text") {
        val doc = state.document ?: return
        if (state.tool == Tool.TRANSFORM || state.tool == Tool.ADJUST) state.selectTool(Tool.PAINT)
        val d = TextData().apply {
            text = initial
            color = state.color or (0xFF shl 24)
            size = (minOf(doc.width, doc.height) / 10f).coerceIn(16f, 600f)
        }
        val laid = TextRenderer.layout(d)
        d.x = (doc.width - laid.width) / 2f
        d.y = (doc.height - laid.height) / 2f
        val layer = Layer(d.displayName(), doc.newLayerBitmap())
        layer.text = d
        TextRenderer.render(d, layer.bitmap)
        if (Importers.addLayer(state, layer, "Text hinzufügen")) {
            beginEdit(state, layer, isNew = true)
        }
    }

    /** Startet die Bearbeitung einer vorhandenen Textebene (z. B. Doppeltippen/Ebenenoption). */
    fun beginEdit(state: EditorState, layer: Layer, isNew: Boolean = false) {
        if (!isText(layer)) { state.flash("Keine Textebene"); return }
        if (layer.locked) { state.flash("Ebene gesperrt"); return }
        val d = data(layer)!!
        if (d.renderedGeneration != -1 && d.renderedGeneration != layer.bitmap.generationId) {
            state.flash("Ebene wurde verändert – Text wird neu gesetzt")
        }
        editorHook?.invoke(state, layer, isNew)
    }

    /** Setzt die Pixel aus den Daten neu (ohne Undo; Vorschau während der Bearbeitung). */
    fun rerender(state: EditorState, layer: Layer) {
        val d = data(layer) ?: return
        TextRenderer.render(d, layer.bitmap)
        if (d.autoName) layer.name = d.displayName()
        state.invalidate()
    }

    /** Schreibt eine Bearbeitung fest: [before] → aktuelle Daten. */
    fun commit(state: EditorState, layer: Layer, before: TextData, beforeName: String) {
        val after = data(layer)?.copy() ?: return
        val afterName = layer.name
        state.undo.push(LambdaAction("Text bearbeiten",
            onUndo = { layer.text = before.copy(); layer.name = beforeName; TextRenderer.render(layer.text as TextData, layer.bitmap) },
            onRedo = { layer.text = after.copy(); layer.name = afterName; TextRenderer.render(layer.text as TextData, layer.bitmap) }))
        state.markDirty()
        state.invalidate()
    }

    /** Wandelt die Textebene in eine normale Pixelebene um (Undo-fähig). */
    fun rasterize(state: EditorState, layer: Layer? = state.document?.activeLayer) {
        val l = layer ?: return
        val d = data(l) ?: return
        l.text = null
        state.undo.push(LambdaAction("Text rastern", onUndo = { l.text = d }, onRedo = { l.text = null }))
        state.markDirty()
        state.invalidate()
        state.flash("Text gerastert")
    }
}
