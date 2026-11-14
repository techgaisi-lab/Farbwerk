package at.techgaisi.farbwerk.brush

import android.graphics.Matrix
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Eine von QuickShape erkannte Form. Geometrie in Dokument-Koordinaten; [transform] wird beim
 * Weiterziehen (Skalieren/Drehen um den Anker) gesetzt.
 */
class QShape(val kind: Kind) {
    enum class Kind(val label: String, val perfectLabel: String) {
        LINE("Linie erstellt", "Linie eingerastet"),
        ELLIPSE("Ellipse erstellt", "Kreis"),
        RECT("Rechteck erstellt", "Quadrat"),
        POLYGON("Polygon erstellt", "Regelmäßiges Polygon"),
        POLYLINE("Polylinie erstellt", "Polylinie eingerastet"),
        ARC("Bogen erstellt", "Bogen eingerastet"),
    }

    /** Linie/Polygon/Polylinie: Eckpunkte (x0,y0,x1,y1,…). */
    var pts = FloatArray(0)
    // Ellipse/Rechteck/Bogen
    var cx = 0f; var cy = 0f
    var rx = 0f; var ry = 0f
    var angle = 0f
    var startAngle = 0f
    var sweep = 0f
    /** Zweite Stufe (Kreis/Quadrat/15°-Raster) aktiv. */
    var perfected = false
    val transform: Matrix by lazy { Matrix() }

    val closed: Boolean get() = kind == Kind.ELLIPSE || kind == Kind.RECT || kind == Kind.POLYGON

    /** Ankerpunkt für Skalieren/Drehen beim Weiterziehen (ohne Transformation). */
    fun anchor(out: FloatArray) {
        when (kind) {
            Kind.LINE, Kind.POLYLINE -> { out[0] = pts[0]; out[1] = pts[1] }
            Kind.POLYGON -> {
                var sx = 0f; var sy = 0f; val n = pts.size / 2
                for (i in 0 until n) { sx += pts[2 * i]; sy += pts[2 * i + 1] }
                out[0] = sx / n; out[1] = sy / n
            }
            else -> { out[0] = cx; out[1] = cy }
        }
    }

    /**
     * Zweite Stufe anwenden: Ellipse→Kreis, Rechteck→Quadrat, Polygon→regelmäßig, Linie/Polylinie→15°-Schritte.
     * @return Hinweistext oder null, wenn nichts zu tun ist.
     */
    fun perfect(): String? {
        if (perfected) return null
        when (kind) {
            Kind.ELLIPSE -> { val r = (rx + ry) / 2f; rx = r; ry = r }
            Kind.RECT -> { val s = (rx + ry) / 2f; rx = s; ry = s; angle = snapAngle(angle, 15f) }
            Kind.POLYGON -> {
                val n = pts.size / 2
                val a = FloatArray(2); anchor(a)
                var rr = 0f
                for (i in 0 until n) rr += hypot(pts[2 * i] - a[0], pts[2 * i + 1] - a[1])
                rr /= n
                val a0 = atan2(pts[1] - a[1], pts[0] - a[0])
                for (i in 0 until n) {
                    val an = a0 + 2f * PI.toFloat() * i / n
                    pts[2 * i] = a[0] + cos(an) * rr; pts[2 * i + 1] = a[1] + sin(an) * rr
                }
            }
            Kind.ARC -> { sweep = snapAngle(sweep, 15f) }
            Kind.LINE, Kind.POLYLINE -> {}
        }
        perfected = true
        return kind.perfectLabel
    }

    /** Erzeugt die Punktfolge der (transformierten) Form. Gibt die Punktzahl zurück. */
    fun sample(xs: FloatBuf, ys: FloatBuf): Int {
        xs.clear(); ys.clear()
        when (kind) {
            Kind.LINE, Kind.POLYLINE, Kind.POLYGON -> {
                for (i in 0 until pts.size / 2) { xs.add(pts[2 * i]); ys.add(pts[2 * i + 1]) }
            }
            Kind.RECT -> {
                val c = cos(angle); val s = sin(angle)
                val hx = floatArrayOf(-rx, rx, rx, -rx); val hy = floatArrayOf(-ry, -ry, ry, ry)
                for (i in 0 until 4) { xs.add(cx + hx[i] * c - hy[i] * s); ys.add(cy + hx[i] * s + hy[i] * c) }
            }
            Kind.ELLIPSE -> {
                val per = 2f * PI.toFloat() * sqrt((rx * rx + ry * ry) / 2f)
                val n = ceil(per / 3f).toInt().coerceIn(48, 1440)
                val c = cos(angle); val s = sin(angle)
                for (i in 0 until n) {
                    val t = 2f * PI.toFloat() * i / n
                    val ex = cos(t) * rx; val ey = sin(t) * ry
                    xs.add(cx + ex * c - ey * s); ys.add(cy + ex * s + ey * c)
                }
            }
            Kind.ARC -> {
                val n = ceil(abs(sweep) * rx / 3f).toInt().coerceIn(12, 1440)
                for (i in 0..n) {
                    val t = startAngle + sweep * i / n
                    xs.add(cx + cos(t) * rx); ys.add(cy + sin(t) * rx)
                }
            }
        }
        // Transformation
        val tmp = FloatArray(2)
        for (i in 0 until xs.size) {
            tmp[0] = xs[i]; tmp[1] = ys[i]
            transform.mapPoints(tmp)
            xs.data[i] = tmp[0]; ys.data[i] = tmp[1]
        }
        // Eingerastete Linien: Winkel jedes Segments auf 15° (nach der Transformation)
        if (perfected && (kind == Kind.LINE || kind == Kind.POLYLINE)) {
            for (i in 1 until xs.size) {
                val dx = xs[i] - xs[i - 1]; val dy = ys[i] - ys[i - 1]
                val len = hypot(dx, dy)
                val a = snapAngle(atan2(dy, dx), 15f)
                xs.data[i] = xs[i - 1] + cos(a) * len; ys.data[i] = ys[i - 1] + sin(a) * len
            }
        }
        return xs.size
    }

    companion object {
        fun snapAngle(a: Float, stepDeg: Float): Float {
            val st = Math.toRadians(stepDeg.toDouble()).toFloat()
            return (a / st).roundToInt() * st
        }
    }
}

/** Erkennung von Linie, Ellipse/Kreis, Rechteck, Polygon, Polylinie und Bogen aus einem Freihandstrich. */
object QuickShapeDetector {
    private const val N = 96

    /** Pixel pro logischem Pixel (Feinberechnung) – feste Toleranzen in Pixeln werden damit skaliert. */
    private var unit = 1f

    /** @param detailScale Feinberechnung des Dokuments (feste Pixel-Toleranzen werden mitskaliert) */
    fun detect(px: FloatBuf, py: FloatBuf, detailScale: Float = 1f): QShape? {
        if (px.size < 3) return null
        unit = detailScale.coerceAtLeast(0.01f)
        // 1) gleichmäßig nach Bogenlänge abtasten
        var total = 0f
        for (i in 1 until px.size) total += hypot(px[i] - px[i - 1], py[i] - py[i - 1])
        if (total < 12f * unit) return null
        val xs = FloatArray(N); val ys = FloatArray(N)
        resample(px, py, total, xs, ys)

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until N) {
            minX = min(minX, xs[i]); maxX = max(maxX, xs[i]); minY = min(minY, ys[i]); maxY = max(maxY, ys[i])
        }
        val diag = hypot(maxX - minX, maxY - minY).coerceAtLeast(1f)
        val chord = hypot(xs[N - 1] - xs[0], ys[N - 1] - ys[0])

        // 2) Linie
        if (chord > 0.0001f) {
            var maxDev = 0f
            val dx = (xs[N - 1] - xs[0]) / chord; val dy = (ys[N - 1] - ys[0]) / chord
            for (i in 0 until N) maxDev = max(maxDev, abs((xs[i] - xs[0]) * dy - (ys[i] - ys[0]) * dx))
            if (chord > 0.8f * total && maxDev < 0.07f * chord + 3f * unit) {
                return QShape(QShape.Kind.LINE).apply { pts = floatArrayOf(xs[0], ys[0], xs[N - 1], ys[N - 1]) }
            }
        }

        val closed = chord < 0.22f * diag

        if (closed) {
            val ell = fitEllipse(xs, ys)
            val eErr = ellipseError(xs, ys, ell)
            val verts = corners(xs, ys, true, diag)
            if (verts.size in 3..8) {
                // Polygone sind flexibler → Fehler mit der Eckenzahl gewichten
                val pErr = polyError(xs, ys, verts, true) * (1f + 0.25f * (verts.size - 3)) + 0.004f * diag
                if (pErr < eErr) {
                    if (verts.size == 4 && rightAngled(xs, ys, verts)) return fitRect(xs, ys, verts)
                    return QShape(QShape.Kind.POLYGON).apply { pts = toPts(xs, ys, verts) }
                }
            }
            return if (eErr < 0.09f * diag) ell else null
        }

        // offen: Bogen oder Polylinie
        val arc = fitArc(xs, ys)
        val aErr = arc?.let { arcError(xs, ys, it) } ?: Float.MAX_VALUE
        val verts = corners(xs, ys, false, diag)
        if (verts.size in 3..10) {
            val pErr = polyError(xs, ys, verts, false) * (1f + 0.25f * (verts.size - 3)) + 0.004f * diag
            if (pErr < aErr) return QShape(QShape.Kind.POLYLINE).apply { pts = toPts(xs, ys, verts) }
        }
        return arc
    }

    /** Mittlere Abweichung der Punkte von der Ellipse (Pixel, angenähert). */
    private fun ellipseError(xs: FloatArray, ys: FloatArray, e: QShape): Float {
        val c = cos(e.angle); val s = sin(e.angle)
        val scale = (e.rx + e.ry) / 2f
        var err = 0f
        for (i in 0 until N) {
            val dx = xs[i] - e.cx; val dy = ys[i] - e.cy
            val u = (dx * c + dy * s) / e.rx; val v = (-dx * s + dy * c) / e.ry
            err += abs(sqrt(u * u + v * v) - 1f) * scale
        }
        return err / N
    }

    private fun arcError(xs: FloatArray, ys: FloatArray, a: QShape): Float {
        var err = 0f
        for (i in 0 until N) err += abs(hypot(xs[i] - a.cx, ys[i] - a.cy) - a.rx)
        return err / N
    }

    /** Mittlerer Abstand der Punkte zur Polylinie/zum Polygon aus den Eckpunkten. */
    private fun polyError(xs: FloatArray, ys: FloatArray, v: List<Int>, closed: Boolean): Float {
        val segs = if (closed) v.size else v.size - 1
        var err = 0f
        for (i in 0 until N) {
            var best = Float.MAX_VALUE
            for (k in 0 until segs) {
                val a = v[k]; val b = v[(k + 1) % v.size]
                best = min(best, segDist(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b]))
            }
            err += best
        }
        return err / N
    }

    private fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 < 1e-6f) 0f else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (ax + dx * t), py - (ay + dy * t))
    }

    private fun resample(px: FloatBuf, py: FloatBuf, total: Float, xs: FloatArray, ys: FloatArray) {
        val step = total / (N - 1)
        xs[0] = px[0]; ys[0] = py[0]
        var j = 1; var acc = 0f; var k = 1
        var ax = px[0]; var ay = py[0]
        while (k < N - 1 && j < px.size) {
            val seg = hypot(px[j] - ax, py[j] - ay)
            if (acc + seg >= step) {
                val t = (step - acc) / seg
                ax += (px[j] - ax) * t; ay += (py[j] - ay) * t
                xs[k] = ax; ys[k] = ay; k++
                acc = 0f
            } else {
                acc += seg; ax = px[j]; ay = py[j]; j++
            }
        }
        while (k < N) { xs[k] = px[px.size - 1]; ys[k] = py[py.size - 1]; k++ }
    }

    /** Ecken per Ramer–Douglas–Peucker, flache Ecken (< 28°) werden entfernt. */
    private fun corners(xs: FloatArray, ys: FloatArray, closed: Boolean, diag: Float): List<Int> {
        val keep = BooleanArray(N)
        keep[0] = true; keep[N - 1] = true
        val eps = 0.045f * diag
        if (closed) {
            // geschlossene Form: am entferntesten Punkt teilen
            var far = 0; var fd = 0f
            for (i in 0 until N) { val d = hypot(xs[i] - xs[0], ys[i] - ys[0]); if (d > fd) { fd = d; far = i } }
            keep[far] = true
            rdp(xs, ys, 0, far, eps, keep); rdp(xs, ys, far, N - 1, eps, keep)
        } else rdp(xs, ys, 0, N - 1, eps, keep)
        val idx = ArrayList<Int>()
        for (i in 0 until N) if (keep[i]) idx.add(i)
        if (closed && idx.size > 1) {
            // Endpunkt fällt mit dem Startpunkt zusammen
            idx.removeAt(idx.size - 1)
        }
        // flache Ecken entfernen
        var changed = true
        while (changed && idx.size > (if (closed) 3 else 2)) {
            changed = false
            val n = idx.size
            for (k in 0 until n) {
                if (!closed && (k == 0 || k == n - 1)) continue
                val a = idx[(k - 1 + n) % n]; val b = idx[k]; val c = idx[(k + 1) % n]
                val d1 = atan2(ys[b] - ys[a], xs[b] - xs[a])
                val d2 = atan2(ys[c] - ys[b], xs[c] - xs[b])
                var t = abs(d2 - d1); if (t > PI) t = (2 * PI - t).toFloat()
                if (t < Math.toRadians(28.0)) { idx.removeAt(k); changed = true; break }
            }
        }
        return idx
    }

    private fun rdp(xs: FloatArray, ys: FloatArray, a: Int, b: Int, eps: Float, keep: BooleanArray) {
        if (b <= a + 1) return
        val dx = xs[b] - xs[a]; val dy = ys[b] - ys[a]
        val len = hypot(dx, dy)
        var best = -1; var bd = 0f
        for (i in a + 1 until b) {
            val d = if (len < 1e-3f) hypot(xs[i] - xs[a], ys[i] - ys[a])
            else abs((xs[i] - xs[a]) * dy - (ys[i] - ys[a]) * dx) / len
            if (d > bd) { bd = d; best = i }
        }
        if (bd > eps && best > 0) {
            keep[best] = true
            rdp(xs, ys, a, best, eps, keep); rdp(xs, ys, best, b, eps, keep)
        }
    }

    private fun rightAngled(xs: FloatArray, ys: FloatArray, v: List<Int>): Boolean {
        for (k in 0 until 4) {
            val a = v[(k + 3) % 4]; val b = v[k]; val c = v[(k + 1) % 4]
            val ux = xs[a] - xs[b]; val uy = ys[a] - ys[b]; val wx = xs[c] - xs[b]; val wy = ys[c] - ys[b]
            val cosA = (ux * wx + uy * wy) / (hypot(ux, uy) * hypot(wx, wy)).coerceAtLeast(1e-3f)
            if (abs(cosA) > 0.42f) return false // > ±25° von 90° weg
        }
        return true
    }

    private fun toPts(xs: FloatArray, ys: FloatArray, v: List<Int>): FloatArray {
        val out = FloatArray(v.size * 2)
        for (k in v.indices) { out[2 * k] = xs[v[k]]; out[2 * k + 1] = ys[v[k]] }
        return out
    }

    private fun fitRect(xs: FloatArray, ys: FloatArray, v: List<Int>): QShape {
        // mittlere Kantenrichtung modulo 90°
        var sx = 0.0; var sy = 0.0
        for (k in 0 until 4) {
            val a = v[k]; val b = v[(k + 1) % 4]
            val ang = atan2((ys[b] - ys[a]).toDouble(), (xs[b] - xs[a]).toDouble()) * 4
            sx += cos(ang); sy += sin(ang)
        }
        val angle = (atan2(sy, sx) / 4).toFloat()
        val c = cos(-angle); val s = sin(-angle)
        var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE; var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
        for (k in 0 until 4) {
            val x = xs[v[k]]; val y = ys[v[k]]
            val u = x * c - y * s; val w = x * s + y * c
            minU = min(minU, u); maxU = max(maxU, u); minV = min(minV, w); maxV = max(maxV, w)
        }
        val cu = (minU + maxU) / 2f; val cv = (minV + maxV) / 2f
        val ci = cos(angle); val si = sin(angle)
        return QShape(QShape.Kind.RECT).apply {
            cx = cu * ci - cv * si; cy = cu * si + cv * ci
            rx = (maxU - minU) / 2f; ry = (maxV - minV) / 2f
            this.angle = angle
        }
    }

    private fun fitEllipse(xs: FloatArray, ys: FloatArray): QShape {
        var mx = 0f; var my = 0f
        for (i in 0 until N) { mx += xs[i]; my += ys[i] }
        mx /= N; my /= N
        var cxx = 0f; var cyy = 0f; var cxy = 0f
        for (i in 0 until N) {
            val dx = xs[i] - mx; val dy = ys[i] - my
            cxx += dx * dx; cyy += dy * dy; cxy += dx * dy
        }
        cxx /= N; cyy /= N; cxy /= N
        val angle = 0.5f * atan2(2f * cxy, cxx - cyy)
        val c = cos(angle); val s = sin(angle)
        // Radien per kleinster Quadrate: u²·A + v²·B ≈ 1  (A = 1/rx², B = 1/ry²)
        var vu = 0.0; var vv = 0.0; var u4 = 0.0; var v4 = 0.0; var uv = 0.0
        for (i in 0 until N) {
            val dx = xs[i] - mx; val dy = ys[i] - my
            val u = (dx * c + dy * s).toDouble(); val v = (-dx * s + dy * c).toDouble()
            val u2 = u * u; val v2 = v * v
            vu += u2; vv += v2; u4 += u2 * u2; v4 += v2 * v2; uv += u2 * v2
        }
        val det = u4 * v4 - uv * uv
        var rxF = sqrt(2.0 * vu / N).toFloat()
        var ryF = sqrt(2.0 * vv / N).toFloat()
        if (abs(det) > 1e-9) {
            val a = (vu * v4 - vv * uv) / det
            val b = (u4 * vv - uv * vu) / det
            if (a > 0 && b > 0) { rxF = (1.0 / sqrt(a)).toFloat(); ryF = (1.0 / sqrt(b)).toFloat() }
        }
        return QShape(QShape.Kind.ELLIPSE).apply {
            cx = mx; cy = my
            rx = rxF.coerceAtLeast(1f)
            ry = ryF.coerceAtLeast(1f)
            this.angle = angle
        }
    }

    /** Kreisbogen durch Start-, Mittel- und Endpunkt; null bei schlechter Passung. */
    private fun fitArc(xs: FloatArray, ys: FloatArray): QShape? {
        val ax = xs[0]; val ay = ys[0]; val bx = xs[N / 2]; val by = ys[N / 2]; val cx = xs[N - 1]; val cy = ys[N - 1]
        val d = 2f * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
        if (abs(d) < 1e-3f) return null
        val a2 = ax * ax + ay * ay; val b2 = bx * bx + by * by; val c2 = cx * cx + cy * cy
        val ux = (a2 * (by - cy) + b2 * (cy - ay) + c2 * (ay - by)) / d
        val uy = (a2 * (cx - bx) + b2 * (ax - cx) + c2 * (bx - ax)) / d
        val r = hypot(ax - ux, ay - uy)
        if (r < 4f * unit) return null
        var err = 0f
        for (i in 0 until N) err += abs(hypot(xs[i] - ux, ys[i] - uy) - r)
        err /= N
        if (err > 0.08f * r) return null
        val a0 = atan2(ay - uy, ax - ux)
        val am = atan2(by - uy, bx - ux)
        val a1 = atan2(cy - uy, cx - ux)
        // Richtung so wählen, dass der Mittelpunkt auf dem Bogen liegt
        fun norm(a: Float): Float { var x = a; while (x < 0) x += (2 * PI).toFloat(); while (x >= 2 * PI) x -= (2 * PI).toFloat(); return x }
        val ccwSweep = norm(a1 - a0)
        val ccwMid = norm(am - a0)
        val sweep = if (ccwMid <= ccwSweep) ccwSweep else ccwSweep - (2 * PI).toFloat()
        return QShape(QShape.Kind.ARC).apply {
            this.cx = ux; this.cy = uy; rx = r; ry = r; startAngle = a0; this.sweep = sweep
        }
    }
}
