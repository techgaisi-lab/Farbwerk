package at.techgaisi.farbwerk.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.adjust.AdjustmentKind
import at.techgaisi.farbwerk.model.GuideKind
import at.techgaisi.farbwerk.model.LayerOps
import at.techgaisi.farbwerk.model.Panel
import at.techgaisi.farbwerk.model.Tool
import at.techgaisi.farbwerk.selection.ClipboardOps
import at.techgaisi.farbwerk.ui.actions.ActionTab
import at.techgaisi.farbwerk.ui.actions.ActionsMemory
import at.techgaisi.farbwerk.ui.performModifierTap
import at.techgaisi.farbwerk.ui.quickmenu.QuickMenuController
import at.techgaisi.farbwerk.ui.shell.FloatingEyedropper

/**
 * Tastenkürzel für Hardware-Tastaturen nach der Belegung des Vorbilds (Anleitung §4; ⌘ = Strg, ⌥ = Alt).
 * Wird von MainActivity.dispatchKeyEvent aufgerufen, solange ein Dokument offen ist.
 *
 * Farbwerk-Erweiterungen bleiben erreichbar: Leertaste halten + ziehen = Leinwand verschieben (kurz tippen =
 * QuickMenu), Strg+Umschalt+A = alles auswählen, Strg+S speichern, Strg+T transformieren, Strg+E zusammenfügen,
 * W = Wischen.
 */
object KeyboardShortcuts {

    /** Liste für die Hilfe-Seite und das Strg-Overlay (Taste → Beschreibung). */
    val SHORTCUTS: List<Pair<String, String>> = listOf(
        "Strg+Z / Strg+Umschalt+Z" to "Rückgängig / Wiederholen",
        "Strg+X / C / V" to "Ausschneiden / Kopieren / Einfügen",
        "Strg+A" to "Alles kopieren",
        "Strg+Umschalt+A" to "Alles auswählen",
        "Strg+D" to "Auswahl aufheben",
        "Strg+Umschalt+I" to "Auswahl umkehren",
        "Strg+J" to "Duplizieren",
        "Strg+Rücktaste" to "Ebene / Auswahl leeren",
        "Strg+Umschalt+N" to "Neue Ebene",
        "Strg+E / Strg+Umschalt+E" to "Zusammenfügen / Alle",
        "B / E / W" to "Malen / Radieren / Wischen (erneut: Bibliothek)",
        "S / V" to "Auswahl / Transformieren",
        "L / C" to "Ebenen / Farben",
        "X" to "Vorherige Farbe",
        "I" to "Pipette",
        "[ / ]" to "Größe −/+ 5 %",
        "Strg+[ / ]" to "Größe −/+ 1 %",
        "Umschalt+[ / ]" to "Größe −/+ 10 %",
        "Alt+[ / ]" to "Deckkraft −/+ 10 %",
        "Alt halten (oder M)" to "Modifizierer",
        "Leertaste" to "QuickMenu (halten + ziehen: verschieben)",
        "Pfeile (Transformieren)" to "1 px verschieben (Umschalt: 10 px)",
        "Pfeil hoch / runter" to "Ebene darüber / darunter",
        "Strg+U / Strg+B" to "Farbton… / Farbbalance",
        "Strg+K / Strg+Komma" to "Aktionen / Einstellungen",
        "Strg+;" to "Perspektiv-Hilfe ein/aus",
        "Strg+0 / Tab" to "Vollbild",
        "0 / Strg+1" to "Einpassen / 100 %",
        "Strg+Plus / Minus" to "Zoomen",
        "Strg+S" to "Speichern",
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
        if (ctrlState == null || otherKeySinceCtrl) return@Runnable
        overlayVisible = true
    }

    // Leertaste: kurz = QuickMenu, halten = verschieben
    private var spaceDownAt = 0L
    // Alt/M = Modifizierer
    private var modDownAt = 0L
    private var modKeyUsed = false
    private var modActive = false

    fun onKeyDown(state: EditorState, event: KeyEvent): Boolean {
        val code = event.keyCode
        val ctrl = event.isCtrlPressed || event.isMetaPressed
        val shift = event.isShiftPressed
        val alt = event.isAltPressed
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
        if (code == KeyEvent.KEYCODE_ALT_LEFT || code == KeyEvent.KEYCODE_ALT_RIGHT ||
            (code == KeyEvent.KEYCODE_M && !ctrl)
        ) {
            if (first) modifierDown(state)
            return true
        }
        otherKeySinceCtrl = true
        hideOverlay()
        if (modActive) modKeyUsed = true

        if (ctrl) return onCtrlKey(state, code, shift, first)

        when (code) {
            KeyEvent.KEYCODE_SPACE -> {
                if (first) {
                    spaceDownAt = SystemClock.uptimeMillis()
                    state.canvasView?.spacePanned = false
                    state.spaceHeld = true
                }
                return true
            }
            KeyEvent.KEYCODE_LEFT_BRACKET -> {
                if (alt) changeOpacity(state, -0.1f) else changeSize(state, if (shift) -0.1f else -0.05f); return true
            }
            KeyEvent.KEYCODE_RIGHT_BRACKET -> {
                if (alt) changeOpacity(state, 0.1f) else changeSize(state, if (shift) 0.1f else 0.05f); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (state.tool == Tool.TRANSFORM) {
                    val step = if (shift) 10f else 1f
                    val dx = when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> -step; KeyEvent.KEYCODE_DPAD_RIGHT -> step; else -> 0f }
                    val dy = when (code) { KeyEvent.KEYCODE_DPAD_UP -> -step; KeyEvent.KEYCODE_DPAD_DOWN -> step; else -> 0f }
                    nudgeTransform(state, dx, dy)
                    return true
                }
            }
        }
        if (!first) return isHandledPlainKey(code)
        when (code) {
            KeyEvent.KEYCODE_B -> brushTool(state, Tool.PAINT)
            KeyEvent.KEYCODE_E -> brushTool(state, Tool.ERASE)
            KeyEvent.KEYCODE_W -> brushTool(state, Tool.SMUDGE)
            KeyEvent.KEYCODE_S -> toggleTool(state, Tool.SELECT)
            KeyEvent.KEYCODE_V -> toggleTool(state, Tool.TRANSFORM)
            KeyEvent.KEYCODE_L -> togglePanel(state, Panel.LAYERS)
            KeyEvent.KEYCODE_C -> togglePanel(state, Panel.COLORS)
            KeyEvent.KEYCODE_X -> {
                state.setColorAndRemember(state.previousColor)
                state.flash("Farbe getauscht")
            }
            KeyEvent.KEYCODE_I -> {
                if (FloatingEyedropper.active) FloatingEyedropper.finish(state, commit = false)
                else FloatingEyedropper.start(state)
            }
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> state.fitCanvasToScreen()
            KeyEvent.KEYCODE_TAB -> state.fullscreen = !state.fullscreen
            KeyEvent.KEYCODE_DPAD_UP -> LayerOps.selectLayerAbove(state)
            KeyEvent.KEYCODE_DPAD_DOWN -> LayerOps.selectLayerBelow(state)
            KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_DEL -> clearLayer(state)
            KeyEvent.KEYCODE_ESCAPE -> when {
                state.quickMenuAt != null -> QuickMenuController.close(state)
                FloatingEyedropper.active -> FloatingEyedropper.finish(state, commit = false)
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
        KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_V,
        KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_0,
        KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_FORWARD_DEL,
        KeyEvent.KEYCODE_DEL,
    )

    private fun onCtrlKey(state: EditorState, code: Int, shift: Boolean, first: Boolean): Boolean {
        // Wiederholbare Kürzel (Taste gehalten)
        when (code) {
            KeyEvent.KEYCODE_Z -> { if (shift) state.undo.redo() else state.undo.undo(); return true }
            KeyEvent.KEYCODE_Y -> { state.undo.redo(); return true }
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> { state.zoomBy(1.25f); return true }
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> { state.zoomBy(0.8f); return true }
            KeyEvent.KEYCODE_LEFT_BRACKET -> { changeSize(state, -0.01f); return true }
            KeyEvent.KEYCODE_RIGHT_BRACKET -> { changeSize(state, 0.01f); return true }
        }
        if (!first) return true
        when (code) {
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> state.fullscreen = !state.fullscreen
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> state.actualSize()
            KeyEvent.KEYCODE_A -> if (shift) ClipboardOps.selectAll(state) else ClipboardOps.copyAll(state)
            KeyEvent.KEYCODE_D -> ClipboardOps.deselect(state)
            KeyEvent.KEYCODE_I -> if (shift) ClipboardOps.invertSelection(state) else return false
            KeyEvent.KEYCODE_X -> ClipboardOps.cut(state)
            KeyEvent.KEYCODE_C -> if (shift) ClipboardOps.copyAll(state) else ClipboardOps.copy(state)
            KeyEvent.KEYCODE_V -> ClipboardOps.paste(state)
            KeyEvent.KEYCODE_J -> ClipboardOps.duplicate(state)
            KeyEvent.KEYCODE_N -> if (shift) LayerOps.addLayer(state) else return false
            KeyEvent.KEYCODE_E -> if (shift) LayerOps.flattenAll(state) else LayerOps.mergeDown(state)
            KeyEvent.KEYCODE_T -> toggleTool(state, Tool.TRANSFORM)
            KeyEvent.KEYCODE_U -> state.adjustmentTool.open(AdjustmentKind.HSB)
            KeyEvent.KEYCODE_B -> state.adjustmentTool.open(AdjustmentKind.COLOR_BALANCE)
            KeyEvent.KEYCODE_S -> { state.saveNow(); state.flash("Gespeichert") }
            KeyEvent.KEYCODE_H -> if (shift) state.fullscreen = !state.fullscreen else return false
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> clearLayer(state)
            KeyEvent.KEYCODE_K -> togglePanel(state, Panel.ACTIONS)
            KeyEvent.KEYCODE_COMMA -> {
                ActionsMemory.tab = ActionTab.SETTINGS
                state.openPanel = Panel.ACTIONS
            }
            KeyEvent.KEYCODE_SEMICOLON -> togglePerspective(state)
            else -> return false
        }
        state.invalidate()
        return true
    }

    fun onKeyUp(state: EditorState, event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_SPACE -> {
                state.spaceHeld = false
                val panned = state.canvasView?.spacePanned == true
                if (state.prefs.spaceQuickMenu && !panned && SystemClock.uptimeMillis() - spaceDownAt < 350) {
                    QuickMenuController.toggle(state)
                }
                return true
            }
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_M -> {
                if (modActive) modifierUp(state)
                return true
            }
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

    // ------------------------------------------------------------------ Modifizierer (Alt / M)

    private fun modifierDown(state: EditorState) {
        modActive = true
        modKeyUsed = false
        modDownAt = SystemClock.uptimeMillis()
        val fn = GestureMap.owner(state.prefs, GestureTrigger.MODIFIER_TOUCH)
        state.canvasView?.let { it.heldModifier = fn; it.modifierUsed = false }
        if (fn == GestureFunction.EYEDROPPER) state.eyedropperArmed = true
    }

    private fun modifierUp(state: EditorState) {
        modActive = false
        val cv = state.canvasView
        val used = cv?.modifierUsed == true || modKeyUsed
        val fn = cv?.heldModifier
        cv?.heldModifier = null
        if (fn == GestureFunction.EYEDROPPER && cv?.modifierUsed != true) state.eyedropperArmed = false
        if (!used && SystemClock.uptimeMillis() - modDownAt < 350) performModifierTap(state)
    }

    // ------------------------------------------------------------------ Hilfen

    private fun hideOverlay() {
        handler.removeCallbacks(showOverlay)
        if (overlayVisible) overlayVisible = false
    }

    /** B/E/W: Werkzeug wählen; ist es schon aktiv, die Pinselbibliothek öffnen bzw. schließen. */
    private fun brushTool(state: EditorState, t: Tool) {
        if (state.tool == t) {
            togglePanel(state, Panel.BRUSHES)
        } else {
            state.selectTool(t)
            if (state.openPanel == Panel.BRUSHES) state.openPanel = Panel.NONE
        }
    }

    private fun toggleTool(state: EditorState, t: Tool) {
        state.openPanel = Panel.NONE
        state.selectTool(if (state.tool == t) Tool.PAINT else t)
    }

    private fun togglePanel(state: EditorState, p: Panel) {
        state.openPanel = if (state.openPanel == p) Panel.NONE else p
    }

    private fun clearLayer(state: EditorState) {
        val l = state.document?.activeLayer ?: return
        if (l.locked) state.flash("Ebene gesperrt") else { LayerOps.clearActiveLayer(state); state.flash("Ebene geleert") }
    }

    /** Strg+; – Perspektiv-Hilfe ein/aus. */
    private fun togglePerspective(state: EditorState) {
        val g = state.document?.guides ?: return
        if (g.kind == GuideKind.PERSPECTIVE) g.visible = !g.visible
        else { g.kind = GuideKind.PERSPECTIVE; g.visible = true }
        state.markDirty()
        state.flash(if (g.visible) "Perspektiv-Hilfe an" else "Perspektiv-Hilfe aus")
    }

    /** Pfeiltasten im Transformieren: Inhalt um [dx]/[dy] Dokumentpixel verschieben. */
    private fun nudgeTransform(state: EditorState, dx: Float, dy: Float) {
        state.transformTool.nudge(dx, dy)
        state.invalidate()
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
