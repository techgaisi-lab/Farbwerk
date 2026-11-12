package at.techgaisi.farbwerk.brush

import at.techgaisi.farbwerk.guides.Assist
import at.techgaisi.farbwerk.model.ArtDocument
import at.techgaisi.farbwerk.model.GuideKind

/**
 * Anbindung des Zeichenassistenten (Raster, Isometrie, Perspektive) an die Pinsel-Engine.
 * Die Geometrie liefert [Assist.constrain] (Paket Zeichenhilfen); hier wird nur entschieden, ob für den
 * aktuellen Strich eingerastet wird, und der Startpunkt verwaltet. Symmetrie läuft separat über
 * [at.techgaisi.farbwerk.guides.Symmetry].
 */
internal class AssistBridge {
    private var on = false
    private var sx = 0f
    private var sy = 0f
    private var doc: ArtDocument? = null

    val active: Boolean get() = on

    /** Strichbeginn: Einrasten aktiv, wenn die aktive Ebene „assistiert“ ist und eine passende Hilfe gewählt ist. */
    fun begin(d: ArtDocument, x: Float, y: Float) {
        val g = d.guides
        val kindOk = g.kind == GuideKind.GRID_2D || g.kind == GuideKind.ISOMETRIC ||
            (g.kind == GuideKind.PERSPECTIVE && g.vanishingPoints.isNotEmpty())
        on = kindOk && d.activeLayer?.assisted == true
        doc = d; sx = x; sy = y
    }

    fun end() { on = false; doc = null }

    /** Rastet ([x],[y]) ein; schreibt das Ergebnis nach [out]. */
    fun map(x: Float, y: Float, out: FloatArray) {
        val d = doc
        if (!on || d == null) { out[0] = x; out[1] = y; return }
        val p = Assist.constrain(d, sx, sy, x, y)
        out[0] = p.x; out[1] = p.y
    }
}
