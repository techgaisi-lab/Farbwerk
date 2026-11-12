package at.techgaisi.farbwerk.guides

import android.graphics.Matrix
import at.techgaisi.farbwerk.model.ArtDocument
import at.techgaisi.farbwerk.model.GuideKind
import at.techgaisi.farbwerk.model.SymmetryMode

/**
 * Liefert die Spiegel-/Rotationsmatrizen für den Zeichenassistenten (Symmetrie).
 * Die Pinsel-Engine zeichnet jeden Tupfer zusätzlich mit jeder Matrix aus der Liste.
 * Die Identität ist NICHT enthalten. Leere Liste = keine Symmetrie aktiv.
 */
object Symmetry {
    fun matrices(doc: ArtDocument): List<Matrix> {
        val g = doc.guides
        if (g.kind != GuideKind.SYMMETRY || doc.activeLayer?.assisted != true) return emptyList()
        val cx = if (g.symmetryCenterX.isNaN()) doc.width / 2f else g.symmetryCenterX
        val cy = if (g.symmetryCenterY.isNaN()) doc.height / 2f else g.symmetryCenterY
        val rot = g.rotationalSymmetry
        fun mirrorX() = Matrix().apply { if (rot) setRotate(180f, cx, cy) else setScale(-1f, 1f, cx, cy) }
        fun mirrorY() = Matrix().apply { if (rot) setRotate(180f, cx, cy) else setScale(1f, -1f, cx, cy) }
        return when (g.symmetry) {
            SymmetryMode.VERTICAL -> listOf(mirrorX())
            SymmetryMode.HORIZONTAL -> listOf(mirrorY())
            SymmetryMode.QUADRANT -> if (rot) {
                listOf(90f, 180f, 270f).map { Matrix().apply { setRotate(it, cx, cy) } }
            } else listOf(
                Matrix().apply { setScale(-1f, 1f, cx, cy) },
                Matrix().apply { setScale(1f, -1f, cx, cy) },
                Matrix().apply { setScale(-1f, -1f, cx, cy) },
            )
            SymmetryMode.RADIAL -> {
                val n = g.radialSegments.coerceIn(2, 32)
                val out = ArrayList<Matrix>()
                for (i in 1 until n) out.add(Matrix().apply { setRotate(360f * i / n, cx, cy) })
                if (!rot) {
                    // zusätzlich gespiegelte Kopien (Kaleidoskop)
                    for (i in 0 until n) out.add(Matrix().apply {
                        setScale(-1f, 1f, cx, cy); postRotate(360f * i / n, cx, cy)
                    })
                }
                out
            }
        }
    }
}
