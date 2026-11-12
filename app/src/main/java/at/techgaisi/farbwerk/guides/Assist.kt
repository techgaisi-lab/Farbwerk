package at.techgaisi.farbwerk.guides

import android.graphics.PointF
import at.techgaisi.farbwerk.model.ArtDocument
import at.techgaisi.farbwerk.model.GuideKind
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Einrast-Geometrie des Zeichenassistenten für 2D-Raster, Isometrie und Perspektive.
 *
 * Die Pinsel-Engine ruft [constrain] für jeden Punkt eines Strichs mit dem Startpunkt des Strichs auf.
 * Ergebnis ist der Punkt auf der am besten passenden Hilfslinie durch den Startpunkt:
 * - 2D-Raster: waagrecht / senkrecht (um [at.techgaisi.farbwerk.model.GuideSettings.rotation] gedreht)
 * - Isometrie: senkrecht, +30°, −30° (ebenfalls gedreht)
 * - Perspektive: Linie zum jeweiligen Fluchtpunkt; bei 1 Fluchtpunkt zusätzlich parallel/senkrecht zum
 *   Horizont, bei 2 Fluchtpunkten zusätzlich senkrecht zum Horizont, bei 3 nur die drei Fluchtlinien.
 * - Symmetrie (und alles andere): Punkt bleibt unverändert – die Spiegelung erledigt [Symmetry].
 *
 * Die Richtung wird festgelegt, sobald sich der Strich [LOCK_DISTANCE] Dokumentpixel vom Start entfernt hat
 * (wie im Vorbild: der Anfang des Strichs entscheidet die Achse). Vorher wird jeweils die aktuell beste Richtung
 * genommen. Kein Allokieren außer dem Rückgabe-Punkt.
 */
object Assist {
    /** Ab dieser Entfernung (Dokumentpixel) vom Startpunkt bleibt die gewählte Richtung bis Strichende fest. */
    private const val LOCK_DISTANCE = 14f

    // Zustand der aktuellen Strich-Richtung (ein Strich zur Zeit; Aufrufe kommen vom UI-/Malthread)
    private var lockStartX = Float.NaN
    private var lockStartY = Float.NaN
    private var lockedDirX = 0f
    private var lockedDirY = 0f
    private var locked = false

    // Wiederverwendeter Richtungspuffer (max. 4 Richtungen à x,y)
    private val dirs = FloatArray(8)

    /**
     * Ist der Zeichenassistent für Raster/Isometrie/Perspektive gerade wirksam?
     * Bedingung: Zeichenhilfe sichtbar, Art ist kein Symmetrie-Modus, und die aktive Ebene ist „assistiert“.
     */
    fun isActive(doc: ArtDocument): Boolean {
        val g = doc.guides
        if (!g.visible) return false
        if (g.kind != GuideKind.GRID_2D && g.kind != GuideKind.ISOMETRIC && g.kind != GuideKind.PERSPECTIVE) return false
        if (g.kind == GuideKind.PERSPECTIVE && g.vanishingPoints.isEmpty()) return false
        return doc.activeLayer?.assisted == true
    }

    /**
     * Rastet ([x], [y]) an der Hilfslinie durch ([startX], [startY]) ein.
     * Gibt den Punkt unverändert zurück, wenn der Assistent nicht wirksam ist (siehe [isActive]).
     */
    fun constrain(doc: ArtDocument, startX: Float, startY: Float, x: Float, y: Float): PointF {
        if (!isActive(doc)) { resetLock(); return PointF(x, y) }

        // Neuer Strich? → Richtung wieder frei
        if (startX != lockStartX || startY != lockStartY) {
            lockStartX = startX; lockStartY = startY; locked = false
        }
        val vx = x - startX
        val vy = y - startY
        val dist = hypot(vx, vy)
        if (dist < 1e-3f) return PointF(x, y)

        if (!locked) {
            val n = directions(doc, startX, startY)
            if (n == 0) return PointF(x, y)
            // Richtung mit dem größten |cos| zum Bewegungsvektor wählen
            var best = 0; var bestDot = -1f
            for (i in 0 until n) {
                val d = abs(vx * dirs[i * 2] + vy * dirs[i * 2 + 1])
                if (d > bestDot) { bestDot = d; best = i }
            }
            lockedDirX = dirs[best * 2]; lockedDirY = dirs[best * 2 + 1]
            if (dist >= LOCK_DISTANCE) locked = true
        }
        // Gesperrt: Fluchtlinien gehen durch den Startpunkt, daher ist die Richtung pro Strich konstant.
        val t = vx * lockedDirX + vy * lockedDirY
        return PointF(startX + lockedDirX * t, startY + lockedDirY * t)
    }

    /** Setzt die Richtungs-Sperre zurück (z. B. bei Strichende/-abbruch); optional. */
    fun resetLock() {
        lockStartX = Float.NaN; lockStartY = Float.NaN; locked = false
    }

    /** Füllt [dirs] mit Einheitsvektoren und liefert deren Anzahl. */
    private fun directions(doc: ArtDocument, sx: Float, sy: Float): Int {
        val g = doc.guides
        val rot = Math.toRadians(g.rotation.toDouble())
        return when (g.kind) {
            GuideKind.GRID_2D -> {
                put(0, rot); put(1, rot + Math.PI / 2); 2
            }
            GuideKind.ISOMETRIC -> {
                put(0, rot + Math.PI / 2)
                put(1, rot + Math.toRadians(30.0))
                put(2, rot - Math.toRadians(30.0))
                3
            }
            GuideKind.PERSPECTIVE -> {
                val vps = g.vanishingPoints
                var n = 0
                for (vp in vps.take(3)) {
                    val dx = vp.x - sx; val dy = vp.y - sy
                    val len = hypot(dx, dy)
                    if (len > 1e-3f) { dirs[n * 2] = dx / len; dirs[n * 2 + 1] = dy / len; n++ }
                }
                val horizon = horizonAngle(doc)
                when (vps.size) {
                    1 -> { put(n, horizon); n++; put(n, horizon + Math.PI / 2); n++ }
                    2 -> { put(n, horizon + Math.PI / 2); n++ }
                    else -> {}
                }
                n
            }
            else -> 0
        }
    }

    private fun put(i: Int, angle: Double) {
        dirs[i * 2] = cos(angle).toFloat(); dirs[i * 2 + 1] = sin(angle).toFloat()
    }

    /**
     * Winkel des Horizonts (Radiant): bei ≥ 2 Fluchtpunkten die Verbindungslinie der ersten beiden,
     * sonst die Rasterdrehung.
     */
    fun horizonAngle(doc: ArtDocument): Double {
        val vps = doc.guides.vanishingPoints
        if (vps.size >= 2) {
            val a = vps[0]; val b = vps[1]
            if (hypot(b.x - a.x, b.y - a.y) > 1e-3f) {
                var ang = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
                // in (−90°, 90°] normieren, damit „senkrecht“ stets nach unten zeigt
                if (ang > Math.PI / 2) ang -= Math.PI
                if (ang <= -Math.PI / 2) ang += Math.PI
                return ang
            }
        }
        return Math.toRadians(doc.guides.rotation.toDouble())
    }
}
