package at.techgaisi.farbwerk.input

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.LayerOps
import at.techgaisi.farbwerk.model.Panel
import at.techgaisi.farbwerk.model.Tool
import at.techgaisi.farbwerk.selection.ClipboardOps

/**
 * Tastenkürzel für Hardware-Tastaturen (Strg statt ⌘). Wird von MainActivity.dispatchKeyEvent
 * aufgerufen, solange ein Dokument offen ist.
 */
object KeyboardShortcuts {

    /** Liste für die Hilfe-Seite und das Strg-Overlay (Taste → Beschreibung). */
    val SHORTCUTS: List<Pair<String, String>> = listOf(
        "Strg+Z" to "Rückgängig",
        "Strg+Umschalt+Z / Strg+Y" to "Wiederholen",
        "B / S / E" to "Malen / Wischen / Radieren",
        "V oder Strg+T" to "Transformieren",
        "L / C" to "Ebenen / Farben",
        "Leertaste halten" to "Leinwand verschieben",
        "[ / ]" to "Pinselgröße − / +",
        "Umschalt+[ / ]" to "Deckkraft − / +",
        "X" to "Farbe tauschen",
        "I" to "Pipette",
        "0 / Strg+0" to "Einpassen",
        "Strg+1" to "100 %",
        "Strg+Plus / Minus" to "Zoomen",
        "Strg+A / Strg+D" to "Alles auswählen / Auswahl aufheben",
        "Strg+Umschalt+I" to "Auswahl umkehren",
        "Strg+X / C / V" to "Ausschneiden / Kopieren / Einfügen",
        "Strg+Umschalt+C" to "Alles kopieren",
        "Strg+J" to "Duplizieren",
        "Strg+Umschalt+N" to "Neue Ebene",
        "Strg+E" to "Nach unten zusammenfügen",
        "Strg+Umschalt+E" to "Alle zusammenfügen",
        "Strg+U / Strg+B" to "Anpassungen (Farbton / Weichzeichnen)",
        "Strg+S" to "Speichern",
        "Tab / Strg+Umschalt+H" to "Vollbild",
        "Pfeil hoch / runter" to "Ebene darüber / darunter",
        "Entf" to "Ebene leeren",
        "Esc" to "Panel schließen",
    )

    /**
     * Strg wird gehalten → Übersicht anzeigen. Compose-State; eine Oberfläche kann
     * [ShortcutOverlay] anzeigen, solange das true ist.
     */
    var overlayVisible by mutableStateOf(false)
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var ctrlState: EditorState? = null
    private var otherKeySinceCtrl = false
    private val showOverlay = Runnable {
        val s = ctrlState ?: return@Runnable
        if (otherKeySinceCtrl) return@Runnable
        overlayVisible = true
        s.flash("Strg+Z Rückgängig · Strg+S Speichern · Strg+T Transformieren · [ ] Größe · Hilfe: Aktionen → Hilfe")
    }

    fun onKeyDown(state: EditorState, event: KeyEvent): Boolean {
        val code = event.keyCode
        val ctrl = event.isCtrlPressed || event.isMetaPressed
        val shift = event.isShiftPressed
        val first = event.repeatCount == 0

        if (code == KeyEvent.KEYCODE_CTRL_LEFT || code == KeyEvent.KEYCODE_CTRL_RIGHT ||
            code == KeyEvent.KEYCODE_META_LEFT || code == KeyEvent.KEYCODE_META_RIGHT
        ) {
            if (first) {
                ctrlState = state
                otherKeySinceCtrl = false
                handler.removeCallbacks(showOverlay)
                handler.postDelayed(showOverlay, 900)
            }
            return false
        }
        otherKeySinceCtrl = true
        hideOverlay()

        if (ctrl) return onCtrlKey(state, code, shift, first)

        when (code) {
            KeyEvent.KEYCODE_SPACE -> { state.spaceHeld = true; return true }
            KeyEvent.KEYCODE_LEFT_BRACKET -> { if (shift) changeOpacity(state, -0.1f) else changeSize(state, -0.05f); return true }
            KeyEvent.KEYCODE_RIGHT_BRACKET -> { if (shift) changeOpacity(state, 0.1f) else changeSize(state, 0.05f); return true }
        }
        if (!first) return isHandledPlainKey(code)
        when (code) {
            KeyEvent.KEYCODE_B -> brushTool(state, Tool.PAINT)
            KeyEvent.KEYCODE_S -> brushTool(state, Tool.SMUDGE)
            KeyEvent.KEYCODE_E -> brushTool(state, Tool.ERASE)
            KeyEvent.KEYCODE_V -> toggleTool(state, Tool.TRANSFORM)
            KeyEvent.KEYCODE_L -> togglePanel(state, Panel.LAYERS)
            KeyEvent.KEYCODE_C -> togglePanel(state, Panel.COLORS)
            KeyEvent.KEYCODE_X -> {
                val prev = state.previousColor
                state.setColorAndRemember(prev)
                state.flash("Farbe getauscht")
            }
            KeyEvent.KEYCODE_I -> {
                state.eyedropperArmed = !state.eyedropperArmed
                state.flash(if (state.eyedropperArmed) "Pipette: auf die Leinwand tippen" else "Pipette aus")
            }
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> state.fitCanvasToScreen()
            KeyEvent.KEYCODE_TAB -> state.fullscreen = !state.fullscreen
            KeyEvent.KEYCODE_DPAD_UP -> LayerOps.selectLayerAbove(state)
            KeyEvent.KEYCODE_DPAD_DOWN -> LayerOps.selectLayerBelow(state)
            KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_DEL -> {
                val l = state.document?.activeLayer
                if (l?.locked == true) state.flash("Ebene gesperrt") else LayerOps.clearActiveLayer(state)
            }
            KeyEvent.KEYCODE_ESCAPE -> when {
                state.copyPasteMenuVisible -> state.copyPasteMenuVisible = false
                state.openPanel != Panel.NONE -> state.openPanel = Panel.NONE
                state.fullscreen -> state.fullscreen = false
                else -> return false
            }
            else -> return false
        }
        state.invalidate()
        return true
    }

    private fun isHandledPlainKey(code: Int) = code in setOf(
        KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_L,
        KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_TAB,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_DEL,
    )

    private fun onCtrlKey(state: EditorState, code: Int, shift: Boolean, first: Boolean): Boolean {
        // Wiederholbare Kürzel (Taste gehalten)
        when (code) {
            KeyEvent.KEYCODE_Z -> { if (shift) state.undo.redo() else state.undo.undo(); return true }
            KeyEvent.KEYCODE_Y -> { state.undo.redo(); return true }
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> { state.zoomBy(1.25f); return true }
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> { state.zoomBy(0.8f); return true }
        }
        if (!first) return true
        when (code) {
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> state.fitCanvasToScreen()
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> state.actualSize()
            KeyEvent.KEYCODE_A -> ClipboardOps.selectAll(state)
            KeyEvent.KEYCODE_D -> ClipboardOps.deselect(state)
            KeyEvent.KEYCODE_I -> if (shift) ClipboardOps.invertSelection(state) else return false
            KeyEvent.KEYCODE_X -> ClipboardOps.cut(state)
            KeyEvent.KEYCODE_C -> if (shift) ClipboardOps.copyAll(state) else ClipboardOps.copy(state)
            KeyEvent.KEYCODE_V -> ClipboardOps.paste(state)
            KeyEvent.KEYCODE_J -> ClipboardOps.duplicate(state)
            KeyEvent.KEYCODE_N -> if (shift) LayerOps.addLayer(state) else return false
            KeyEvent.KEYCODE_E -> if (shift) LayerOps.flattenAll(state) else LayerOps.mergeDown(state)
            KeyEvent.KEYCODE_T -> toggleTool(state, Tool.TRANSFORM)
            KeyEvent.KEYCODE_U -> state.adjustmentTool.open(at.techgaisi.farbwerk.adjust.AdjustmentKind.HSB)
            KeyEvent.KEYCODE_B -> state.adjustmentTool.open(at.techgaisi.farbwerk.adjust.AdjustmentKind.GAUSSIAN_BLUR)
            KeyEvent.KEYCODE_S -> { state.saveNow(); state.flash("Gespeichert") }
            KeyEvent.KEYCODE_H -> if (shift) state.fullscreen = !state.fullscreen else return false
            else -> return false
        }
        state.invalidate()
        return true
    }

    fun onKeyUp(state: EditorState, event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_SPACE -> { state.spaceHeld = false; return true }
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> {
                handler.removeCallbacks(showOverlay)
                hideOverlay()
                ctrlState = null
                return false
            }
        }
        return false
    }

    private fun hideOverlay() {
        handler.removeCallbacks(showOverlay)
        if (overlayVisible) overlayVisible = false
    }

    // ------------------------------------------------------------------ Hilfen

    private fun brushTool(state: EditorState, t: Tool) {
        state.selectTool(t)
        if (state.openPanel == Panel.BRUSHES) state.openPanel = Panel.NONE
    }

    private fun toggleTool(state: EditorState, t: Tool) {
        state.openPanel = Panel.NONE
        state.selectTool(if (state.tool == t) Tool.PAINT else t)
    }

    private fun togglePanel(state: EditorState, p: Panel) {
        state.openPanel = if (state.openPanel == p) Panel.NONE else p
    }

    private fun openAdjustments(state: EditorState) {
        if (state.tool == Tool.TRANSFORM) state.selectTool(Tool.PAINT)
        state.openPanel = Panel.ADJUSTMENTS
    }

    private fun changeSize(state: EditorState, delta: Float) {
        val ts = state.currentToolSettings
        ts.size = (ts.size + delta).coerceIn(0f, 1f)
        state.toolSettingsVersion++
        state.flash("Größe ${(ts.size * 100).toInt()} %")
    }

    private fun changeOpacity(state: EditorState, delta: Float) {
        val ts = state.currentToolSettings
        ts.opacity = (ts.opacity + delta).coerceIn(0f, 1f)
        state.toolSettingsVersion++
        state.flash("Deckkraft ${(ts.opacity * 100).toInt()} %")
    }
}
