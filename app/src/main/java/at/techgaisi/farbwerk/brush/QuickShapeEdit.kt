package at.techgaisi.farbwerk.brush

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.StatusBar
import at.techgaisi.farbwerk.model.InputPoint
import at.techgaisi.farbwerk.model.ToolInputHandler
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Bearbeitbare QuickShape-Form („Form bearbeiten“): Geometrie in Dokument-Koordinaten mit Steuerpunkten.
 * - Linie/Polylinie/Polygon: Eckpunkte
 * - Viereck: 4 Ecken; Modus „Rechteck“ (Ecke ziehen hält den rechten Winkel) oder „Polygon“ (Ecken frei)
 * - Ellipse: Mittelpunkt, Radien, Drehung; Modus „Kreis“ koppelt beide Radien
 * - Bogen: Anfang, Mitte, Ende (Kreisbogen durch drei Punkte)
 */
class EditShape(val kind: QShape.Kind) {
    var pts = FloatArray(0)
    var cx = 0f; var cy = 0f; var rx = 0f; var ry = 0f; var angle = 0f
    /** Ellipse: Kreis-Modus. */
    var circle = false
    /** Viereck: frei bearbeitbares Polygon statt Rechteck. */
    var polygonMode = false

    val closed: Boolean get() = kind == QShape.Kind.ELLIPSE || kind == QShape.Kind.RECT || kind == QShape.Kind.POLYGON

    fun copy(): EditShape = EditShape(kind).also {
        it.pts = pts.copyOf(); it.cx = cx; it.cy = cy; it.rx = rx; it.ry = ry; it.angle = angle
        it.circle = circle; it.polygonMode = polygonMode
    }

    /** Bezeichnung in der Statusleiste. */
    val title: String
        get() = when (kind) {
            QShape.Kind.ELLIPSE -> if (circle) "Kreis" else "Ellipse"
            QShape.Kind.RECT -> if (polygonMode) "Polygon" else "Rechteck"
            QShape.Kind.LINE -> "Linie"
            QShape.Kind.POLYLINE -> "Polylinie"
            QShape.Kind.POLYGON -> "Polygon"
            QShape.Kind.ARC -> "Bogen"
        }

    /** Alternative Darstellung für den Knopf in der Statusleiste (null = keine). */
    val toggleLabel: String?
        get() = when (kind) {
            QShape.Kind.ELLIPSE -> if (circle) "Ellipse" else "Kreis"
            QShape.Kind.RECT -> if (polygonMode) "Rechteck" else "Polygon"
            else -> null
        }

    fun toggle() {
        when (kind) {
            QShape.Kind.ELLIPSE -> { circle = !circle; if (circle) { val r = (rx + ry) / 2f; rx = r; ry = r } }
            QShape.Kind.RECT -> {
                polygonMode = !polygonMode
                if (!polygonMode) squareUp()
            }
            else -> {}
        }
    }

    /** Polygon → Rechteck: an der ersten Kante ausgerichtetes umschließendes Rechteck. */
    private fun squareUp() {
        val ux0 = pts[2] - pts[0]; val uy0 = pts[3] - pts[1]
        val l = hypot(ux0, uy0).coerceAtLeast(1e-3f)
        val ux = ux0 / l; val uy = uy0 / l
        var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE; var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
        for (i in 0 until 4) {
            val u = pts[2 * i] * ux + pts[2 * i + 1] * uy
            val v = -pts[2 * i] * uy + pts[2 * i + 1] * ux
            minU = minOf(minU, u); maxU = maxOf(maxU, u); minV = minOf(minV, v); maxV = maxOf(maxV, v)
        }
        val us = floatArrayOf(minU, maxU, maxU, minU); val vs = floatArrayOf(minV, minV, maxV, maxV)
        for (i in 0 until 4) {
            pts[2 * i] = us[i] * ux - vs[i] * uy
            pts[2 * i + 1] = us[i] * uy + vs[i] * ux
        }
    }

    /** Steuerpunkte (x,y-Paare). */
    fun handles(): FloatArray = when (kind) {
        QShape.Kind.ELLIPSE -> {
            val c = cos(angle); val s = sin(angle)
            floatArrayOf(
                cx + c * rx, cy + s * rx, cx - s * ry, cy + c * ry,
                cx - c * rx, cy - s * rx, cx + s * ry, cy - c * ry,
            )
        }
        else -> pts.copyOf()
    }

    fun centroid(out: FloatArray) {
        if (kind == QShape.Kind.ELLIPSE) { out[0] = cx; out[1] = cy; return }
        val n = pts.size / 2
        var sx = 0f; var sy = 0f
        for (i in 0 until n) { sx += pts[2 * i]; sy += pts[2 * i + 1] }
        out[0] = sx / n; out[1] = sy / n
    }

    /** Steuerpunkt [i] an ([x],[y]) ziehen. */
    fun drag(i: Int, x: Float, y: Float) {
        when (kind) {
            QShape.Kind.ELLIPSE -> {
                val c = cos(angle); val s = sin(angle)
                val dx = x - cx; val dy = y - cy
                if (i % 2 == 0) {
                    rx = abs(dx * c + dy * s).coerceAtLeast(1f)
                    if (circle) ry = rx
                } else {
                    ry = abs(-dx * s + dy * c).coerceAtLeast(1f)
                    if (circle) rx = ry
                }
            }
            QShape.Kind.RECT -> if (polygonMode) { pts[2 * i] = x; pts[2 * i + 1] = y } else dragRectCorner(i, x, y)
            else -> { pts[2 * i] = x; pts[2 * i + 1] = y }
        }
    }

    private fun dragRectCorner(i: Int, x: Float, y: Float) {
        val o = (i + 2) % 4
        val ox = pts[2 * o]; val oy = pts[2 * o + 1]
        val ux0 = pts[2] - pts[0]; val uy0 = pts[3] - pts[1]
        val l = hypot(ux0, uy0).coerceAtLeast(1e-3f)
        val ux = ux0 / l; val uy = uy0 / l
        val du = (x - ox) * ux + (y - oy) * uy
        val dv = -(x - ox) * uy + (y - oy) * ux
        val ax = ox + ux * du; val ay = oy + uy * du       // entlang u
        val bx = ox - uy * dv; val by = oy + ux * dv       // entlang v
        val n1 = (i + 1) % 4; val n3 = (i + 3) % 4
        pts[2 * i] = x; pts[2 * i + 1] = y
        // den Nachbarn zuordnen, der näher an seiner bisherigen Lage liegt
        val d1a = hypot(pts[2 * n1] - ax, pts[2 * n1 + 1] - ay) + hypot(pts[2 * n3] - bx, pts[2 * n3 + 1] - by)
        val d1b = hypot(pts[2 * n1] - bx, pts[2 * n1 + 1] - by) + hypot(pts[2 * n3] - ax, pts[2 * n3 + 1] - ay)
        if (d1a <= d1b) {
            pts[2 * n1] = ax; pts[2 * n1 + 1] = ay; pts[2 * n3] = bx; pts[2 * n3 + 1] = by
        } else {
            pts[2 * n1] = bx; pts[2 * n1 + 1] = by; pts[2 * n3] = ax; pts[2 * n3 + 1] = ay
        }
    }

    /** Gleichmäßig um ([px],[py]) skalieren – ausgehend vom Zustand [from]. */
    fun scaleFrom(from: EditShape, f: Float, px: Float, py: Float) {
        val k = f.coerceIn(0.02f, 50f)
        for (i in 0 until pts.size / 2) {
            pts[2 * i] = px + (from.pts[2 * i] - px) * k
            pts[2 * i + 1] = py + (from.pts[2 * i + 1] - py) * k
        }
        cx = px + (from.cx - px) * k; cy = py + (from.cy - py) * k
        rx = (from.rx * k).coerceAtLeast(1f); ry = (from.ry * k).coerceAtLeast(1f)
    }

    /** Punktfolge der Form. */
    fun sample(xs: FloatBuf, ys: FloatBuf): Int {
        xs.clear(); ys.clear()
        when (kind) {
            QShape.Kind.ELLIPSE -> {
                val per = 2f * PI.toFloat() * sqrt((rx * rx + ry * ry) / 2f)
                val n = ceil(per / 3f).toInt().coerceIn(48, 1440)
                val c = cos(angle); val s = sin(angle)
                for (i in 0 until n) {
                    val t = 2f * PI.toFloat() * i / n
                    val ex = cos(t) * rx; val ey = sin(t) * ry
                    xs.add(cx + ex * c - ey * s); ys.add(cy + ex * s + ey * c)
                }
            }
            QShape.Kind.ARC -> sampleArc(xs, ys)
            else -> for (i in 0 until pts.size / 2) { xs.add(pts[2 * i]); ys.add(pts[2 * i + 1]) }
        }
        return xs.size
    }

    private fun sampleArc(xs: FloatBuf, ys: FloatBuf) {
        val ax = pts[0]; val ay = pts[1]; val bx = pts[2]; val by = pts[3]; val qx = pts[4]; val qy = pts[5]
        val d = 2f * (ax * (by - qy) + bx * (qy - ay) + qx * (ay - by))
        if (abs(d) < 1e-3f) {
            xs.add(ax); ys.add(ay); xs.add(bx); ys.add(by); xs.add(qx); ys.add(qy); return
        }
        val a2 = ax * ax + ay * ay; val b2 = bx * bx + by * by; val q2 = qx * qx + qy * qy
        val ux = (a2 * (by - qy) + b2 * (qy - ay) + q2 * (ay - by)) / d
        val uy = (a2 * (qx - bx) + b2 * (ax - qx) + q2 * (bx - ax)) / d
        val r = hypot(ax - ux, ay - uy)
        if (r > 1e5f) { xs.add(ax); ys.add(ay); xs.add(qx); ys.add(qy); return }
        val t0 = atan2(ay - uy, ax - ux)
        val tm = atan2(by - uy, bx - ux)
        val t1 = atan2(qy - uy, qx - ux)
        val twoPi = 2f * PI.toFloat()
        fun norm(a: Float): Float { var v = a % twoPi; if (v < 0) v += twoPi; return v }
        var sweep = norm(t1 - t0)
        // Mittelpunkt muss auf dem Bogen liegen, sonst andere Richtung
        if (norm(tm - t0) > sweep) sweep -= twoPi
        val n = ceil(abs(sweep) * r / 3f).toInt().coerceIn(12, 1440)
        for (i in 0..n) {
            val t = t0 + sweep * i / n
            xs.add(ux + cos(t) * r); ys.add(uy + sin(t) * r)
        }
    }

    companion object {
        /** Übernimmt eine erkannte (ggf. transformierte) Form. */
        fun from(sh: QShape): EditShape {
            val e = EditShape(sh.kind)
            val xs = FloatBuf(); val ys = FloatBuf()
            when (sh.kind) {
                QShape.Kind.ELLIPSE -> {
                    val v = FloatArray(9); sh.transform.getValues(v)
                    val s = hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y])
                    val rot = atan2(v[Matrix.MSKEW_Y], v[Matrix.MSCALE_X])
                    val c = floatArrayOf(sh.cx, sh.cy); sh.transform.mapPoints(c)
                    e.cx = c[0]; e.cy = c[1]; e.rx = sh.rx * s; e.ry = sh.ry * s; e.angle = sh.angle + rot
                    e.circle = sh.perfected || abs(sh.rx - sh.ry) < 0.5f
                }
                QShape.Kind.ARC -> {
                    val n = sh.sample(xs, ys)
                    val m = n / 2
                    e.pts = floatArrayOf(xs[0], ys[0], xs[m], ys[m], xs[n - 1], ys[n - 1])
                }
                else -> {
                    val n = sh.sample(xs, ys)
                    e.pts = FloatArray(n * 2) { if (it % 2 == 0) xs[it / 2] else ys[it / 2] }
                }
            }
            return e
        }
    }
}

/**
 * Bearbeitungsmodus „Form bearbeiten“: Läuft als [EditorState.canvasOverride]. Steuerpunkt ziehen ändert die
 * Form, Ziehen zwischen den Punkten skaliert gleichmäßig, Tippen auf die Leinwand übernimmt.
 * Die Statusleiste zeigt den Formnamen und – wo sinnvoll – einen Umschalter (Ellipse/Kreis, Rechteck/Polygon).
 */
internal class QuickShapeEditor(
    private val state: EditorState,
    private val host: Host,
    val shape: EditShape,
) : ToolInputHandler {

    /** Rückrufe in die Pinsel-Engine. */
    interface Host {
        fun renderEditShape(shape: EditShape)
        fun commitShapeEdit()
        /** Prüft, ob die Sitzung noch gültig ist (Verlauf/Dokument/Werkzeug unverändert); sonst beendet sie sich. */
        fun checkShapeEdit(): Boolean
        fun viewScale(): Float
    }

    private var dragIdx = -1
    private var scaling = false
    private var moved = false
    private var downX = 0f; private var downY = 0f
    private var startDist = 1f
    private var backup: EditShape? = null
    private val ctr = FloatArray(2)
    private val tmp = FloatArray(2)

    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2F7CF6.toInt() }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f; color = 0xFFFFFFFF.toInt() }
    private val handleShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f; color = 0x992F7CF6.toInt()
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val path = Path()
    private val xs = FloatBuf(); private val ys = FloatBuf()

    fun updateStatus() {
        val tl = shape.toggleLabel
        state.statusBar = StatusBar(
            text = shape.title,
            actionLabel = tl ?: "Übernehmen",
            onAction = if (tl != null) ({ shape.toggle(); host.renderEditShape(shape); updateStatus() })
            else ({ host.commitShapeEdit() }),
        )
    }

    override fun onStrokeStart(p: InputPoint) {
        if (!host.checkShapeEdit()) return
        moved = false; dragIdx = -1; scaling = false
        downX = p.x; downY = p.y
        val h = shape.handles()
        val hit = HIT_DP * (state.context.resources.displayMetrics.density) / host.viewScale()
        var best = hit
        for (i in 0 until h.size / 2) {
            val d = hypot(h[2 * i] - p.x, h[2 * i + 1] - p.y)
            if (d < best) { best = d; dragIdx = i }
        }
        if (dragIdx < 0) {
            shape.centroid(ctr)
            startDist = hypot(p.x - ctr[0], p.y - ctr[1]).coerceAtLeast(4f)
            backup = shape.copy()
            scaling = true
        }
    }

    override fun onStrokeMove(points: List<InputPoint>) {
        val p = points.last()
        if (!moved) {
            val slop = 6f * state.context.resources.displayMetrics.density / host.viewScale()
            if (hypot(p.x - downX, p.y - downY) < slop) return
            moved = true
        }
        if (dragIdx >= 0) shape.drag(dragIdx, p.x, p.y)
        else if (scaling) backup?.let { shape.scaleFrom(it, hypot(p.x - ctr[0], p.y - ctr[1]) / startDist, ctr[0], ctr[1]) }
        host.renderEditShape(shape)
    }

    override fun onStrokeEnd(p: InputPoint) {
        if (!moved) host.commitShapeEdit()
        dragIdx = -1; scaling = false; backup = null
    }

    override fun onStrokeCancel() {
        backup?.let { if (scaling) { shape.scaleFrom(it, 1f, ctr[0], ctr[1]); host.renderEditShape(shape) } }
        dragIdx = -1; scaling = false; backup = null
    }

    override fun drawOverlay(canvas: Canvas, docToView: Matrix) {
        if (!host.checkShapeEdit()) return
        // gestrichelter Umriss
        val n = shape.sample(xs, ys)
        path.reset()
        for (i in 0 until n) {
            tmp[0] = xs[i]; tmp[1] = ys[i]; docToView.mapPoints(tmp)
            if (i == 0) path.moveTo(tmp[0], tmp[1]) else path.lineTo(tmp[0], tmp[1])
        }
        if (shape.closed) path.close()
        canvas.drawPath(path, outline)
        val d = state.context.resources.displayMetrics.density
        val h = shape.handles()
        for (i in 0 until h.size / 2) {
            tmp[0] = h[2 * i]; tmp[1] = h[2 * i + 1]; docToView.mapPoints(tmp)
            val r = if (i == dragIdx) 9f * d else 7f * d
            canvas.drawCircle(tmp[0], tmp[1] + d, r + 1.5f * d, handleShadow)
            canvas.drawCircle(tmp[0], tmp[1], r, handleFill)
            canvas.drawCircle(tmp[0], tmp[1], r, handleRing)
        }
    }

    private companion object {
        const val HIT_DP = 28f
    }
}
