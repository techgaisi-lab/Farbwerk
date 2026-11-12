package at.techgaisi.farbwerk.ui.actions

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.io.Importers
import at.techgaisi.farbwerk.model.InputPoint
import at.techgaisi.farbwerk.model.Panel
import at.techgaisi.farbwerk.model.ToolInputHandler
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Interaktives Zuschneiden & Größe ändern: Rahmen auf der Leinwand mit Kanten-/Eckgriffen, Einrasten an
 * Leinwandkanten und Mittellinien, Drehregler, Seitenverhältnis-Schloss, Zahleneingabe, DPI und
 * „Neu berechnen“ (Inhalt skalieren statt beschneiden).
 *
 * Der Rahmen liegt in Dokument-Koordinaten ([rect]) und ist um [rotation] Grad um seine Mitte gedreht
 * (gegen die Leinwand). Ergebnis: neues Dokument der Größe [outW]×[outH].
 */
class CropSession(private val state: EditorState) : ToolInputHandler {
    private val doc = state.document!!
    private val prevFullscreen = state.fullscreen

    val rect = RectF(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
    var rotation by mutableFloatStateOf(0f)
    var lockRatio by mutableStateOf(false)
    var resample by mutableStateOf(false)
    var dpi by mutableIntStateOf(doc.dpi)
    /** Zielgröße bei „Neu berechnen“ (sonst = Rahmengröße). */
    var resampleW by mutableIntStateOf(doc.width)
    var resampleH by mutableIntStateOf(doc.height)
    var tick by mutableIntStateOf(0)
        private set

    val outW: Int get() = if (resample) resampleW else rect.width().roundToInt().coerceAtLeast(1)
    val outH: Int get() = if (resample) resampleH else rect.height().roundToInt().coerceAtLeast(1)

    private fun changed() { tick++; state.invalidate() }

    // Griffe: Bits für links/oben/rechts/unten; 0xF = verschieben
    private var grab = 0
    private var lastX = 0f; private var lastY = 0f
    private var startAspect = 1f
    private val pts = FloatArray(2)
    private val tmp = Matrix()

    fun start() {
        state.openPanel = Panel.NONE
        state.fullscreen = true
        state.canvasOverride = this
        changed()
    }

    fun cancel() = finish()

    fun done() {
        val w = outW; val h = outH
        val err = sizeError(w, h)
        if (err != null) { state.flash(err); return }
        val m = contentMatrix()
        finish()
        CanvasOps.transformCanvas(state, m, w, h, dpi, "Zuschneiden & Größe ändern")
    }

    private fun finish() {
        if (state.canvasOverride === this) state.canvasOverride = null
        state.fullscreen = prevFullscreen
        ActionModes.crop = null
        state.invalidate()
    }

    fun reset() {
        rect.set(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
        rotation = 0f; resample = false; resampleW = doc.width; resampleH = doc.height; dpi = doc.dpi
        changed()
    }

    fun sizeError(w: Int, h: Int): String? = when {
        w < 1 || h < 1 -> "Ungültige Größe"
        w > Importers.MAX_SIDE || h > Importers.MAX_SIDE -> "Maximal ${Importers.MAX_SIDE} px je Seite"
        w.toLong() * h > Importers.MAX_CANVAS_PIXELS -> "Leinwand zu groß"
        else -> null
    }

    /** Dokument → Ergebnis: um die Rahmenmitte drehen, Rahmen nach (0,0) legen, ggf. skalieren. */
    fun contentMatrix(): Matrix = Matrix().apply {
        postTranslate(-rect.centerX(), -rect.centerY())
        postRotate(rotation)
        postScale(outW / rect.width().coerceAtLeast(1f), outH / rect.height().coerceAtLeast(1f))
        postTranslate(outW / 2f, outH / 2f)
    }

    /** Zahleneingabe: Rahmengröße setzen (um die Mitte). */
    fun setSize(w: Int, h: Int) {
        if (resample) {
            resampleW = w.coerceAtLeast(1); resampleH = h.coerceAtLeast(1)
        } else {
            val cx = rect.centerX(); val cy = rect.centerY()
            rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            if (rotation == 0f) {
                // ganzzahlig an der Leinwand ausrichten
                rect.offsetTo(rect.left.roundToInt().toFloat(), rect.top.roundToInt().toFloat())
            }
        }
        changed()
    }

    fun setResampleMode(on: Boolean) {
        resample = on
        if (on) {
            // Neu berechnen: ganze Leinwand, Inhalt wird skaliert; Schloss an
            rect.set(0f, 0f, doc.width.toFloat(), doc.height.toFloat()); rotation = 0f
            resampleW = doc.width; resampleH = doc.height; lockRatio = true
        }
        changed()
    }

    fun setRotationDeg(deg: Float) { rotation = deg; changed() }

    // ------------------------------------------------------------------ Eingabe

    private fun viewScale(): Float {
        val m = state.canvasView?.docToViewMatrix() ?: return 1f
        pts[0] = 1f; pts[1] = 0f; m.mapVectors(pts); return hypot(pts[0], pts[1]).coerceAtLeast(1e-4f)
    }

    /** Dokumentpunkt → Rahmenraum (Drehung aufheben). */
    private fun toFrame(x: Float, y: Float): Pair<Float, Float> {
        if (rotation == 0f) return x to y
        tmp.setRotate(rotation, rect.centerX(), rect.centerY())
        pts[0] = x; pts[1] = y; tmp.mapPoints(pts)
        return pts[0] to pts[1]
    }

    override fun onStrokeStart(p: InputPoint) {
        if (resample) { grab = 0; return }
        val (x, y) = toFrame(p.x, p.y)
        val tol = Handles.hitRadius / viewScale()
        var g = 0
        val inX = x > rect.left - tol && x < rect.right + tol
        val inY = y > rect.top - tol && y < rect.bottom + tol
        if (inY && abs(x - rect.left) < tol) g = g or L
        if (inY && abs(x - rect.right) < tol) g = g or R
        if (inX && abs(y - rect.top) < tol) g = g or T
        if (inX && abs(y - rect.bottom) < tol) g = g or B
        if (g and (L or R) == (L or R)) g = g and (if (abs(x - rect.left) < abs(x - rect.right)) R.inv() else L.inv())
        if (g and (T or B) == (T or B)) g = g and (if (abs(y - rect.top) < abs(y - rect.bottom)) B.inv() else T.inv())
        if (g == 0 && rect.contains(x, y)) g = MOVE
        grab = g
        lastX = x; lastY = y
        startAspect = rect.width() / rect.height().coerceAtLeast(1f)
    }

    override fun onStrokeMove(points: List<InputPoint>) {
        val p = points.lastOrNull() ?: return
        if (grab == 0) return
        val (x, y) = toFrame(p.x, p.y)
        val dx = x - lastX; val dy = y - lastY
        lastX = x; lastY = y
        val minSize = 8f
        if (grab == MOVE) {
            rect.offset(dx, dy)
        } else {
            if (grab and L != 0) rect.left = minOf(rect.left + dx, rect.right - minSize)
            if (grab and R != 0) rect.right = max(rect.right + dx, rect.left + minSize)
            if (grab and T != 0) rect.top = minOf(rect.top + dy, rect.bottom - minSize)
            if (grab and B != 0) rect.bottom = max(rect.bottom + dy, rect.top + minSize)
            if (lockRatio) keepAspect()
        }
        if (rotation == 0f) snap()
        changed()
    }

    private fun keepAspect() {
        val horizontal = grab and (L or R) != 0
        val vertical = grab and (T or B) != 0
        if (horizontal && (!vertical || rect.width() / startAspect >= rect.height())) {
            val h = rect.width() / startAspect
            if (grab and T != 0) rect.top = rect.bottom - h
            else if (grab and B != 0) rect.bottom = rect.top + h
            else { val c = rect.centerY(); rect.top = c - h / 2; rect.bottom = c + h / 2 }
        } else {
            val w = rect.height() * startAspect
            if (grab and L != 0) rect.left = rect.right - w
            else if (grab and R != 0) rect.right = rect.left + w
            else { val c = rect.centerX(); rect.left = c - w / 2; rect.right = c + w / 2 }
        }
    }

    /** Einrasten an Leinwandkanten und Mittellinien (8 dp Fangbereich). */
    private fun snap() {
        val tol = 8f * Handles.density / viewScale()
        val xs = floatArrayOf(0f, doc.width / 2f, doc.width.toFloat())
        val ys = floatArrayOf(0f, doc.height / 2f, doc.height.toFloat())
        fun nearest(v: Float, c: FloatArray): Float? = c.firstOrNull { abs(it - v) < tol }
        if (grab == MOVE) {
            (nearest(rect.left, xs)?.let { it - rect.left } ?: nearest(rect.right, xs)?.let { it - rect.right }
                ?: nearest(rect.centerX(), xs)?.let { it - rect.centerX() })?.let { rect.offset(it, 0f) }
            (nearest(rect.top, ys)?.let { it - rect.top } ?: nearest(rect.bottom, ys)?.let { it - rect.bottom }
                ?: nearest(rect.centerY(), ys)?.let { it - rect.centerY() })?.let { rect.offset(0f, it) }
        } else if (!lockRatio) {
            if (grab and L != 0) nearest(rect.left, xs)?.let { rect.left = it }
            if (grab and R != 0) nearest(rect.right, xs)?.let { rect.right = it }
            if (grab and T != 0) nearest(rect.top, ys)?.let { rect.top = it }
            if (grab and B != 0) nearest(rect.bottom, ys)?.let { rect.bottom = it }
        }
    }

    override fun onStrokeEnd(p: InputPoint) {
        if (grab != 0 && rotation == 0f) {
            // Auf ganze Pixel runden
            rect.set(rect.left.roundToInt().toFloat(), rect.top.roundToInt().toFloat(),
                rect.right.roundToInt().toFloat(), rect.bottom.roundToInt().toFloat())
        }
        grab = 0; changed()
    }

    override fun onStrokeCancel() { grab = 0 }

    // ------------------------------------------------------------------ Zeichnen

    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt() }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x88FFFFFF.toInt() }
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val quad = FloatArray(8)

    override fun drawOverlay(canvas: Canvas, docToView: Matrix) {
        val d = Handles.density
        border.strokeWidth = 1.5f * d; grid.strokeWidth = 1f * d; corner.strokeWidth = 4f * d
        // Rahmen in View-Koordinaten (gedreht um −rotation in Dokumentraum)
        val frameToView = Matrix().apply {
            setRotate(-rotation, rect.centerX(), rect.centerY()); postConcat(docToView)
        }
        quad[0] = rect.left; quad[1] = rect.top; quad[2] = rect.right; quad[3] = rect.top
        quad[4] = rect.right; quad[5] = rect.bottom; quad[6] = rect.left; quad[7] = rect.bottom
        frameToView.mapPoints(quad)
        path.rewind()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), Path.Direction.CW)
        path.moveTo(quad[0], quad[1]); path.lineTo(quad[2], quad[3]); path.lineTo(quad[4], quad[5]); path.lineTo(quad[6], quad[7]); path.close()
        canvas.drawPath(path, dim)

        canvas.save()
        canvas.concat(frameToView)
        val s = run { pts[0] = 1f; pts[1] = 0f; frameToView.mapVectors(pts); hypot(pts[0], pts[1]).coerceAtLeast(1e-4f) }
        val inv = 1f / s
        border.strokeWidth = 1.5f * d * inv; grid.strokeWidth = 1f * d * inv; corner.strokeWidth = 4f * d * inv
        // Drittel-Raster
        for (i in 1..2) {
            val x = rect.left + rect.width() * i / 3f; val y = rect.top + rect.height() * i / 3f
            canvas.drawLine(x, rect.top, x, rect.bottom, grid)
            canvas.drawLine(rect.left, y, rect.right, y, grid)
        }
        canvas.drawRect(rect, border)
        if (!resample) {
            val len = 18f * d * inv
            for ((cx, cy, sx, sy) in listOf(
                floatArrayOf(rect.left, rect.top, 1f, 1f), floatArrayOf(rect.right, rect.top, -1f, 1f),
                floatArrayOf(rect.right, rect.bottom, -1f, -1f), floatArrayOf(rect.left, rect.bottom, 1f, -1f))) {
                canvas.drawLine(cx, cy, cx + sx * len, cy, corner)
                canvas.drawLine(cx, cy, cx, cy + sy * len, corner)
            }
            // Kantenmitten
            val e = 10f * d * inv
            canvas.drawLine(rect.centerX() - e, rect.top, rect.centerX() + e, rect.top, corner)
            canvas.drawLine(rect.centerX() - e, rect.bottom, rect.centerX() + e, rect.bottom, corner)
            canvas.drawLine(rect.left, rect.centerY() - e, rect.left, rect.centerY() + e, corner)
            canvas.drawLine(rect.right, rect.centerY() - e, rect.right, rect.centerY() + e, corner)
        }
        canvas.restore()
    }

    companion object {
        private const val L = 1; private const val T = 2; private const val R = 4; private const val B = 8
        private const val MOVE = 0x10
    }
}

/** Startet das interaktive Zuschneiden. */
fun openCrop(state: EditorState) {
    if (state.document == null || ActionModes.guideEditor != null) return
    CanvasOps.finishModesPublic(state)
    val s = CropSession(state)
    ActionModes.crop = s
    s.start()
}

@Composable
fun CropScreen(state: EditorState, s: CropSession) {
    val ui = LocalUi.current
    @Suppress("UNUSED_VARIABLE") val t = s.tick
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            ModeTopBar("Zuschneiden & Größe ändern", onCancel = { s.cancel() }, onDone = { s.done() }, extra = {
                BarTextButton("Zurücksetzen", ui.text, { s.reset() })
            })
        }
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp).widthIn(max = 680.dp).fillMaxWidth()
                .shadow(16.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(ui.panel)
                .consumeTouches().padding(vertical = 8.dp)
        ) {
            // Zahleneingabe
            var wText by remember { mutableStateOf(s.outW.toString()) }
            var hText by remember { mutableStateOf(s.outH.toString()) }
            var dpiText by remember { mutableStateOf(s.dpi.toString()) }
            var editing by remember { mutableStateOf(false) }
            LaunchedEffect(s.tick) { if (!editing) { wText = s.outW.toString(); hText = s.outH.toString() } }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val ratio = s.outW / s.outH.toFloat().coerceAtLeast(1f)
                FwTextField(wText, { v ->
                    editing = true
                    wText = v.filter(Char::isDigit).take(5)
                    val w = wText.toIntOrNull() ?: return@FwTextField
                    val h = if (s.lockRatio) (w / ratio).roundToInt().coerceAtLeast(1).also { hText = it.toString() } else hText.toIntOrNull() ?: return@FwTextField
                    s.setSize(w, h); editing = false
                }, "Breite (px)", Modifier.weight(1f), number = true)
                FwTextField(hText, { v ->
                    editing = true
                    hText = v.filter(Char::isDigit).take(5)
                    val h = hText.toIntOrNull() ?: return@FwTextField
                    val w = if (s.lockRatio) (h * ratio).roundToInt().coerceAtLeast(1).also { wText = it.toString() } else wText.toIntOrNull() ?: return@FwTextField
                    s.setSize(w, h); editing = false
                }, "Höhe (px)", Modifier.weight(1f), number = true)
                FwTextField(dpiText, { v ->
                    dpiText = v.filter(Char::isDigit).take(4)
                    dpiText.toIntOrNull()?.let { s.dpi = it.coerceIn(1, 4800) }
                }, "DPI", Modifier.width(90.dp), number = true)
            }
            val cm = String.format(java.util.Locale.GERMANY, "%.1f × %.1f cm", s.outW / s.dpi.toFloat() * 2.54f, s.outH / s.dpi.toFloat() * 2.54f)
            val err = s.sizeError(s.outW, s.outH)
            Text(err ?: "Druckgröße $cm", color = if (err != null) Color(0xFFFF453A) else ui.textMuted, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) { SwitchRow("Seitenverhältnis sperren", s.lockRatio) { s.lockRatio = it; s.setRotationDeg(s.rotation) } }
                Box(Modifier.weight(1f)) {
                    SwitchRow("Neu berechnen", s.resample, subtitle = "Inhalt skalieren") { s.setResampleMode(it) }
                }
            }
            if (!s.resample) {
                SliderRow("Drehen", s.rotation, -45f..45f, { String.format(java.util.Locale.GERMANY, "%.1f°", it) },
                    onChange = { v -> s.setRotationDeg(if (abs(v) < 0.6f) 0f else v) })
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

/** Kleine Hilfe für Drehungen (Trigonometrie in Grad). */
internal fun rotatePoint(x: Float, y: Float, cx: Float, cy: Float, deg: Float): Pair<Float, Float> {
    val a = Math.toRadians(deg.toDouble())
    val c = cos(a).toFloat(); val s = sin(a).toFloat()
    val dx = x - cx; val dy = y - cy
    return (cx + dx * c - dy * s) to (cy + dx * s + dy * c)
}
