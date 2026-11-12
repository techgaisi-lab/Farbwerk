package at.techgaisi.farbwerk.ui.color

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.color.ColorMath

/**
 * HSB-Zustand des Farben-Panels.
 *
 * Hält Farbton/Sättigung/Helligkeit getrennt, damit der Farbton bei Grau/Schwarz nicht verloren geht.
 * Alle Änderungen gehen live nach [EditorState.color]; erst [release] (Loslassen) trägt die Farbe über
 * [EditorState.setColorAndRemember] in Verlauf und "vorherige Farbe" ein – genau einmal pro Geste.
 */
class HsvModel(private val state: EditorState) {
    var h by mutableFloatStateOf(0f); private set
    var s by mutableFloatStateOf(0f); private set
    var v by mutableFloatStateOf(0f); private set

    /** Zuletzt von diesem Modell gesetzte Farbe (um externe Änderungen zu erkennen). */
    private var emitted = 0
    /** Farbe zu Beginn der laufenden Geste (null = keine Geste aktiv). */
    private var gestureStart: Int? = null
    private val tmp = FloatArray(3)

    init { syncFrom(state.color, force = true) }

    val argb: Int get() = ColorMath.hsvToArgb(h, s, v)

    /** Übernimmt eine extern gesetzte Farbe (Pipette, Verlauf …). */
    fun syncFrom(color: Int, force: Boolean = false) {
        if (!force && color == emitted) return
        ColorMath.argbToHsv(color, tmp)
        // Farbton bei unbunten Farben beibehalten
        if (tmp[1] > 0.001f && tmp[2] > 0.001f) h = tmp[0]
        if (tmp[2] > 0.001f) s = tmp[1]
        v = tmp[2]
        emitted = color
    }

    /** Live-Änderung (während einer Geste). */
    fun set(h: Float = this.h, s: Float = this.s, v: Float = this.v) {
        this.h = ((h % 360f) + 360f) % 360f
        this.s = s.coerceIn(0f, 1f)
        this.v = v.coerceIn(0f, 1f)
        val c = ColorMath.hsvToArgb(this.h, this.s, this.v)
        if (gestureStart == null) gestureStart = state.color
        emitted = c
        state.color = c
    }

    /** Live-Änderung über eine RGB-Farbe (RGB-Regler). */
    fun setArgbLive(color: Int) {
        if (gestureStart == null) gestureStart = state.color
        syncFrom(color, force = true)
        state.color = color
    }

    /** Geste beendet: Farbe merken (Verlauf, vorherige Farbe). */
    fun release() {
        val start = gestureStart ?: return
        gestureStart = null
        val final = state.color
        if (final == start) return
        state.color = start
        state.setColorAndRemember(final)
        emitted = final
    }

    /**
     * Geste verwerfen: Farbe auf den Stand vor der Geste zurücksetzen, ohne Verlaufseintrag
     * (z. B. wenn aus einem Ein-Finger-Ziehen eine Zwei-Finger-Pinch-Geste wird).
     */
    fun cancelGesture() {
        val start = gestureStart ?: return
        gestureStart = null
        syncFrom(start, force = true)
        state.color = start
    }

    /** Sofortige Auswahl einer fertigen Farbe (Farbfeld, Harmonie, Hex). */
    fun pick(color: Int) {
        gestureStart = null
        val c = ColorMath.opaque(color)
        syncFrom(c, force = true)
        state.setColorAndRemember(c)
        emitted = c
    }
}
