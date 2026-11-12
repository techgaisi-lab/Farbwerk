package at.techgaisi.farbwerk.adjust

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.util.Random
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Verflüssigen über ein Verschiebungsgitter: Jeder Gitterpunkt hat eine Ruheposition (Textur-
 * koordinate) und eine aktuelle Zielposition. Gerendert wird mit drawBitmapMesh (Vorwärts-Verzerrung).
 */
class LiquifyEngine(val width: Int, val height: Int) {

    enum class Mode { PUSH, TWIRL_RIGHT, TWIRL_LEFT, PINCH, EXPAND, CRYSTALS, EDGE, RECONSTRUCT }

    val cols: Int
    val rows: Int
    /** Ruhepositionen (x,y)… */
    private val base: FloatArray
    /** Aktuelle Positionen (x,y)… – direkt als Mesh-Vertices nutzbar. */
    val pos: FloatArray
    private val rnd = Random(7)

    init {
        val longSide = max(width, height)
        val cell = max(4f, longSide / 160f)
        cols = max(2, (width / cell).toInt())
        rows = max(2, (height / cell).toInt())
        base = FloatArray((cols + 1) * (rows + 1) * 2)
        for (j in 0..rows) for (i in 0..cols) {
            val k = (j * (cols + 1) + i) * 2
            base[k] = width * i / cols.toFloat()
            base[k + 1] = height * j / rows.toFloat()
        }
        pos = base.copyOf()
    }

    val isIdentity: Boolean get() = pos.contentEquals(base)

    /** Stand bei Beginn von „Anpassen“ (100 %), null = nicht aktiv. */
    private var adjustFrom: FloatArray? = null

    fun reset() { adjustFrom = null; base.copyInto(pos) }
    fun snapshot(): FloatArray = pos.copyOf()
    fun restore(s: FloatArray) { adjustFrom = null; s.copyInto(pos) }

    /**
     * „Anpassen“: schwächt alle bisherigen Verformungen auf [amount] (0..1) ab. Bezugspunkt ist der Stand
     * beim ersten Aufruf; [commitAdjust] macht den abgeschwächten Stand zum neuen Ausgangspunkt.
     */
    fun setAdjust(amount: Float) {
        val from = adjustFrom ?: pos.copyOf().also { adjustFrom = it }
        val a = amount.coerceIn(0f, 1f)
        for (i in pos.indices) pos[i] = base[i] + (from[i] - base[i]) * a
    }

    /** Beendet „Anpassen“ (vor dem nächsten Pinselstrich). */
    fun commitAdjust() { adjustFrom = null }

    /**
     * Einen Pinselschritt anwenden.
     * @param dx, dy Bewegung seit dem letzten Schritt (für Schieben/Kante)
     * @param radius Pinselradius (Dokument-Pixel)
     * @param strength 0..1 (Druck × Druckempfindlichkeit)
     * @param distortion 0..1 Zufallsanteil
     */
    fun apply(mode: Mode, cx: Float, cy: Float, dx: Float, dy: Float, radius: Float, strength: Float, distortion: Float) {
        if (radius <= 0f || strength <= 0f) return
        val r2 = radius * radius
        val n = pos.size / 2
        val moveLen = hypot(dx, dy)
        val nx = if (moveLen > 1e-4f) -dy / moveLen else 0f
        val ny = if (moveLen > 1e-4f) dx / moveLen else 0f
        val twirl = 0.12f * strength
        for (v in 0 until n) {
            val k = v * 2
            val px = pos[k]
            val py = pos[k + 1]
            val ox = px - cx
            val oy = py - cy
            val d2 = ox * ox + oy * oy
            if (d2 >= r2) continue
            val t = 1f - d2 / r2
            var f = t * t                       // weicher Abfall zum Rand
            if (distortion > 0f) f *= 1f + (rnd.nextFloat() - 0.5f) * 2f * distortion
            when (mode) {
                Mode.PUSH -> {
                    pos[k] = px + dx * f * strength * 1.2f
                    pos[k + 1] = py + dy * f * strength * 1.2f
                }
                Mode.TWIRL_RIGHT, Mode.TWIRL_LEFT -> {
                    val a = (if (mode == Mode.TWIRL_RIGHT) twirl else -twirl) * f
                    val c = cos(a); val s = sin(a)
                    pos[k] = cx + ox * c - oy * s
                    pos[k + 1] = cy + ox * s + oy * c
                }
                Mode.PINCH -> {
                    val q = min(0.9f, 0.08f * strength * f)
                    pos[k] = px - ox * q
                    pos[k + 1] = py - oy * q
                }
                Mode.EXPAND -> {
                    val q = 0.08f * strength * f
                    pos[k] = px + ox * q
                    pos[k + 1] = py + oy * q
                }
                Mode.CRYSTALS -> {
                    val j = radius * 0.04f * strength * f
                    pos[k] = px + (rnd.nextFloat() - 0.5f) * 2f * j
                    pos[k + 1] = py + (rnd.nextFloat() - 0.5f) * 2f * j
                }
                Mode.EDGE -> {
                    // Zur Strichlinie hin zusammenziehen (senkrecht zur Bewegungsrichtung)
                    if (moveLen > 1e-4f) {
                        val dPerp = ox * nx + oy * ny
                        val q = min(0.9f, 0.15f * strength * f)
                        pos[k] = px - nx * dPerp * q
                        pos[k + 1] = py - ny * dPerp * q
                    }
                }
                Mode.RECONSTRUCT -> {
                    val q = min(1f, 0.15f * strength * f)
                    pos[k] = px + (base[k] - px) * q
                    pos[k + 1] = py + (base[k + 1] - py) * q
                }
            }
        }
    }

    /** Zeichnet [src] verzerrt auf [c] (Dokument-Koordinaten). */
    fun draw(c: Canvas, src: Bitmap, paint: Paint) {
        c.drawBitmapMesh(src, cols, rows, pos, 0, null, 0, paint)
    }
}
