package at.techgaisi.farbwerk.stylus

import android.view.MotionEvent
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.Panel
import at.techgaisi.farbwerk.model.Tool
import at.techgaisi.farbwerk.render.Compositor

/**
 * Aktionen der S-Pen-Seitentaste (Ersatz für Doppeltippen/Drücken am Apple Pencil bzw. "Modifizierer + Stift").
 * [id] wird in den Einstellungen gespeichert und darf sich nicht ändern.
 */
enum class StylusButtonAction(val id: Int, val label: String) {
    OFF(0, "Aus"),
    EYEDROPPER(1, "Pipette"),
    ERASER(2, "Radierer"),
    UNDO(3, "Rückgängig"),
    REDO(4, "Wiederholen"),
    COLORS(5, "Farben"),
    ;

    companion object {
        fun fromId(id: Int): StylusButtonAction = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/**
 * Prozessweiter Stift-Zustand (überlebt das Neuerzeugen der Leinwand beim Wechsel Galerie ↔ Dokument).
 * Zeiten in [android.os.SystemClock.uptimeMillis] (gleiche Basis wie [MotionEvent.getEventTime]).
 */
object StylusSession {
    /** Wurde in dieser Sitzung schon mit dem Stift gemalt/berührt? ("Nur Stift malt" greift erst dann.) */
    var stylusUsed = false
    /** Letztes Ereignis (Schweben oder Berühren) eines Stifts. */
    var lastStylusTime = Long.MIN_VALUE / 2

    fun seen(eventTime: Long, touched: Boolean) {
        lastStylusTime = eventTime
        if (touched) stylusUsed = true
    }

    /** Zeitfenster der Handballenerkennung nach dem letzten Stift-Ereignis (ms). */
    fun palmWindowMs(strength: Int): Long = when (strength) {
        0 -> 0L
        1 -> 400L
        else -> 1000L
    }

    /** Kontaktgröße (Hauptachse in mm), ab der ein Finger-Kontakt als Handballen gilt. */
    fun palmSizeMm(strength: Int): Float = when (strength) {
        0 -> Float.MAX_VALUE
        1 -> 24f
        else -> 16f
    }

    /** MotionEvent.TOOL_TYPE_PALM (öffentlich erst ab neueren API-Stufen). */
    const val TOOL_TYPE_PALM = 5
    /** MotionEvent.FLAG_CANCELED (API 33): Zeiger war unbeabsichtigt (z. B. Handballen). */
    const val FLAG_CANCELED = 0x20

    /** Seitentasten-Bits: Android-Standard (STYLUS_PRIMARY/SECONDARY) und Samsung-Altgeräte (SECONDARY). */
    const val BUTTON_MASK = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY or
        MotionEvent.BUTTON_SECONDARY
}

/** Ausführung der Seitentasten-Aktionen, die nicht an einen Strich gebunden sind. */
object StylusActions {
    /** Werkzeug vor dem Umschalten auf den Radierer (für "zurück"). */
    private var toolBeforeEraser: Tool = Tool.PAINT

    /** Klick der Seitentaste, während der Stift schwebt ([docX]/[docY] = Stiftposition im Dokument). */
    fun performClick(state: EditorState, action: StylusButtonAction, docX: Float, docY: Float) {
        if (state.document == null) return
        when (action) {
            StylusButtonAction.OFF -> {}
            StylusButtonAction.EYEDROPPER -> if (!pickColorAt(state, docX, docY)) state.flash("Keine Farbe")
            StylusButtonAction.ERASER -> toggleEraser(state)
            StylusButtonAction.UNDO -> if (!state.undo.undo()) state.flash("Nichts rückgängig zu machen")
            StylusButtonAction.REDO -> if (!state.undo.redo()) state.flash("Nichts zu wiederholen")
            StylusButtonAction.COLORS -> state.openPanel = if (state.openPanel == Panel.COLORS) Panel.NONE else Panel.COLORS
        }
    }

    /** Wechselt zwischen aktuellem Werkzeug und Radierer (wie Doppeltippen am Apple Pencil). */
    fun toggleEraser(state: EditorState) {
        if (state.tool == Tool.ERASE) {
            state.selectTool(toolBeforeEraser)
            state.flash(
                when (toolBeforeEraser) {
                    Tool.SMUDGE -> "Wischen"
                    Tool.SELECT -> "Auswählen"
                    Tool.TRANSFORM -> "Transformieren"
                    Tool.ADJUST -> "Anpassungen"
                    else -> "Malen"
                }
            )
        } else {
            toolBeforeEraser = state.tool
            state.selectTool(Tool.ERASE)
            state.flash("Radieren")
        }
    }

    /** Nimmt die Farbe des zusammengesetzten Bildes an ([x],[y]) auf. false, wenn dort nichts ist. */
    fun pickColorAt(state: EditorState, x: Float, y: Float): Boolean {
        val doc = state.document ?: return false
        if (x < 0f || y < 0f || x >= doc.width || y >= doc.height) return false
        val c = Compositor.samplePixel(doc, x, y)
        if (android.graphics.Color.alpha(c) == 0) return false
        state.setColorAndRemember(c or 0xFF000000.toInt())
        state.invalidate()
        return true
    }
}
