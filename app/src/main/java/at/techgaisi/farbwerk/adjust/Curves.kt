package at.techgaisi.farbwerk.adjust

import android.graphics.PointF
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Kurven-Daten: je Kanal (0 = Gamma/alle, 1 = Rot, 2 = Grün, 3 = Blau) eine Liste von Punkten in 0..1,
 * nach x sortiert. Interpolation monoton kubisch (Fritsch-Carlson), damit keine Überschwinger entstehen.
 */
class Curves {
    val channels: Array<MutableList<PointF>> = Array(4) { identity() }

    fun copy(): Curves = Curves().also { c ->
        for (i in 0 until 4) {
            c.channels[i].clear()
            channels[i].forEach { c.channels[i].add(PointF(it.x, it.y)) }
        }
    }

    fun reset() { for (i in 0 until 4) { channels[i].clear(); channels[i].addAll(identity()) } }

    val isIdentity: Boolean
        get() = channels.all { ch -> ch.all { kotlin.math.abs(it.x - it.y) < 1e-3f } }

    /** Kombinierte LUTs für R, G, B (Kanalkurve, danach Gammakurve). */
    fun luts(): Array<IntArray> {
        val master = lut(channels[0])
        return Array(3) { c ->
            val ch = lut(channels[c + 1])
            IntArray(256) { master[ch[it]] }
        }
    }

    companion object {
        /** Höchstzahl der Punkte je Kanal (wie im Vorbild). */
        const val MAX_POINTS = 11

        fun identity(): MutableList<PointF> = mutableListOf(PointF(0f, 0f), PointF(1f, 1f))

        fun lut(points: List<PointF>): IntArray {
            val pts = points.sortedBy { it.x }
            val n = pts.size
            val out = IntArray(256)
            if (n < 2) { for (i in 0 until 256) out[i] = i; return out }
            val xs = FloatArray(n) { pts[it].x }
            val ys = FloatArray(n) { pts[it].y }
            val d = FloatArray(n - 1)
            for (i in 0 until n - 1) {
                val dx = (xs[i + 1] - xs[i]).coerceAtLeast(1e-5f)
                d[i] = (ys[i + 1] - ys[i]) / dx
            }
            val m = FloatArray(n)
            m[0] = d[0]
            m[n - 1] = d[n - 2]
            for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
            for (i in 0 until n - 1) {
                if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
                val a = m[i] / d[i]
                val b = m[i + 1] / d[i]
                val s = a * a + b * b
                if (s > 9f) {
                    val t = 3f / sqrt(s)
                    m[i] = t * a * d[i]
                    m[i + 1] = t * b * d[i]
                }
            }
            var seg = 0
            for (i in 0 until 256) {
                val x = i / 255f
                val y = when {
                    x <= xs[0] -> ys[0]
                    x >= xs[n - 1] -> ys[n - 1]
                    else -> {
                        while (seg < n - 2 && x > xs[seg + 1]) seg++
                        val h = (xs[seg + 1] - xs[seg]).coerceAtLeast(1e-5f)
                        val t = (x - xs[seg]) / h
                        val t2 = t * t
                        val t3 = t2 * t
                        (2 * t3 - 3 * t2 + 1) * ys[seg] + (t3 - 2 * t2 + t) * h * m[seg] +
                            (-2 * t3 + 3 * t2) * ys[seg + 1] + (t3 - t2) * h * m[seg + 1]
                    }
                }
                out[i] = (y * 255f).roundToInt().coerceIn(0, 255)
            }
            return out
        }
    }
}
