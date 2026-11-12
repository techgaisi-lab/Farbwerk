package at.techgaisi.farbwerk.ui.color

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import at.techgaisi.farbwerk.color.ColorMath
import at.techgaisi.farbwerk.ui.theme.LocalUi
import java.nio.ByteBuffer
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Farbscheibe (Aufbau wie im Vorbild):
 *  - Äußerer Ring: Farbton. Rot oben, im Uhrzeigersinn steigend. Griff = Kreis in Farbtonfarbe.
 *  - Innere Scheibe: Sättigung waagrecht (links grau → rechts voll gesättigt) und Helligkeit senkrecht
 *    (unten schwarz → oben hell). Das S/B-Quadrat wird per elliptischer Abbildung auf den Kreis gelegt,
 *    dadurch liegt Weiß oben links, die reine Farbe oben rechts und Schwarz am unteren Rand.
 *    Griff = Kreis in der aktuellen Farbe.
 *  - Doppeltippen in der Scheibe rastet auf den nächstgelegenen „perfekten“ Wert ein (reines Weiß,
 *    Mittelgrau, Schwarz, halbe/volle Sättigung bei voller/halber Helligkeit); im Ring auf 15°-Stufen.
 *  - Zwei Finger auseinander ziehen vergrößert die Scheibe auf volle Größe (der Ring blendet aus) für
 *    feinere Kontrolle, zusammenziehen verkleinert sie wieder. Beim Schließen des Panels wird sie
 *    zurückgesetzt (der Zustand lebt nur, solange der Tab zusammengesetzt ist).
 *
 * Die Scheibe passt sich an den verfügbaren Platz an (kleine und große Tablets), statt in einer
 * Scroll-Fläche abgeschnitten zu werden.
 */
@Composable
fun DiscTab(model: HsvModel, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val masks = remember { DiscMasks() }
    val currentModel by rememberUpdatedState(model)
    var expanded by remember { mutableStateOf(false) }
    val expandedNow by rememberUpdatedState(expanded)
    val expand by animateFloatAsState(if (expanded) 1f else 0f, tween(220), label = "discExpand")

    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        val diameter = min(maxWidth, maxHeight).coerceAtLeast(140.dp)
        Canvas(
            Modifier
                .size(diameter)
                .pointerInput(Unit) {
                    var lastTapTime = 0L
                    var lastTapPos = Offset.Zero
                    val dblSlop = 36.dp.toPx()
                    val tapSlop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val m = currentModel
                        val g = DiscGeometry(size.width.toFloat(), ringWidthPx(size.width.toFloat()), if (expandedNow) 1f else 0f)
                        // Bereich einmal beim Aufsetzen festlegen – wer im Ring beginnt, bleibt im Ring.
                        val onRing = !expandedNow && g.isRing(down.position)
                        val isDouble = down.uptimeMillis - lastTapTime < DOUBLE_TAP_MS &&
                            (down.position - lastTapPos).getDistance() < dblSlop
                        if (isDouble) g.snap(m, down.position, onRing) else g.apply(m, down.position, onRing)

                        var moved = false
                        var pinching = false
                        var pinchDecided = false
                        var startSpan = 0f
                        try {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val pressed = ev.changes.filter { it.pressed }
                                if (pressed.isEmpty()) {
                                    ev.changes.forEach { it.consume() }
                                    val up = ev.changes.firstOrNull { it.id == down.id }
                                    val isTap = !isDouble && !moved && !pinching && up != null &&
                                        up.uptimeMillis - down.uptimeMillis < TAP_MAX_MS
                                    lastTapTime = if (isTap) up!!.uptimeMillis else 0L
                                    lastTapPos = up?.position ?: down.position
                                    break
                                }
                                if (pressed.size >= 2) {
                                    // Zwei Finger: Pinch. Die bisherige Farbänderung des ersten Fingers verwerfen.
                                    if (!pinching) {
                                        pinching = true
                                        m.cancelGesture()
                                        startSpan = span(pressed)
                                    }
                                    if (!pinchDecided && startSpan > 0f) {
                                        val sp = span(pressed)
                                        if (sp > startSpan * PINCH_OPEN) { expanded = true; pinchDecided = true }
                                        else if (sp < startSpan * PINCH_CLOSE) { expanded = false; pinchDecided = true }
                                    }
                                    ev.changes.forEach { it.consume() }
                                    continue
                                }
                                if (pinching) {
                                    // Nach einem Pinch bleibt ein Finger liegen: nichts mehr ändern.
                                    ev.changes.forEach { it.consume() }
                                    continue
                                }
                                val ch = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                                if (ch.positionChanged()) {
                                    ch.consume()
                                    if ((ch.position - down.position).getDistance() > tapSlop) moved = true
                                    if (!isDouble) g.apply(m, ch.position, onRing)
                                }
                            }
                        } finally {
                            // Pinch hat die Farbe bereits zurückgesetzt; sonst Farbe einmal pro Geste merken.
                            if (!pinching) m.release()
                        }
                    }
                }
        ) {
            val g = DiscGeometry(size.width, ringWidthPx(size.width), expand)
            val ringAlpha = (1f - expand).coerceIn(0f, 1f)

            // ---------- Farbtonring: SweepGradient beginnt bei 3 Uhr und läuft im Uhrzeigersinn;
            // um −90° gedreht liegt Rot (0°) oben – passend zu hueAt()/huePoint().
            if (ringAlpha > 0.01f) {
                rotate(-90f, g.center) {
                    drawCircle(
                        Brush.sweepGradient(HueColors, g.center),
                        radius = g.outerRadius - g.ring / 2f,
                        center = g.center,
                        style = Stroke(g.ring),
                        alpha = ringAlpha,
                    )
                }
            }

            // ---------- innere Scheibe: Weiß → (Sättigungsmaske × Farbton) → (Dunkelmaske × Schwarz)
            // ergibt exakt HSB: V · ((1 − S) · Weiß + S · Farbton). Keine Pixelrechnung pro Farbtonänderung.
            val d = (g.outerRadius * 2f).roundToInt().coerceAtLeast(2)
            masks.ensure(d)
            val r = g.innerRadius
            val dstOff = IntOffset((g.center.x - r).roundToInt(), (g.center.y - r).roundToInt())
            val dstSize = IntSize((r * 2f).roundToInt().coerceAtLeast(1), (r * 2f).roundToInt().coerceAtLeast(1))
            val clip = Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(g.center, r))
            }
            val hueColor = ColorMath.hsvToArgb(model.h, 1f, 1f).toUiColor()
            clipPath(clip) {
                drawCircle(Color.White, r, g.center)
                drawImage(
                    masks.sat!!, IntOffset.Zero, IntSize(d, d), dstOff, dstSize,
                    colorFilter = ColorFilter.tint(hueColor, BlendMode.SrcIn), filterQuality = FilterQuality.Low,
                )
                drawImage(
                    masks.dark!!, IntOffset.Zero, IntSize(d, d), dstOff, dstSize,
                    colorFilter = ColorFilter.tint(Color.Black, BlendMode.SrcIn), filterQuality = FilterQuality.Low,
                )
            }
            // weicher Abschluss der Scheibenkante
            drawCircle(ui.panel, r + 1.dp.toPx(), g.center, style = Stroke(2.dp.toPx()))

            // ---------- Griffe
            if (ringAlpha > 0.01f) {
                drawKnob(g.huePoint(model.h), g.ring / 2f - 1.dp.toPx(), hueColor, ringAlpha)
            }
            drawKnob(g.sbToPoint(model.s, model.v), 11.dp.toPx(), model.argb.toUiColor())
        }
    }
}

private const val DOUBLE_TAP_MS = 320L
private const val TAP_MAX_MS = 300L
private const val PINCH_OPEN = 1.18f
private const val PINCH_CLOSE = 0.85f

/** Ringbreite: ~9 % des Durchmessers, auf kleinen wie großen Geräten gut greifbar. */
private fun Density.ringWidthPx(diameter: Float) = (diameter * 0.09f).coerceIn(20.dp.toPx(), 34.dp.toPx())

private fun span(p: List<PointerInputChange>): Float =
    if (p.size < 2) 0f else (p[0].position - p[1].position).getDistance()

/** Griff mit optionaler Deckkraft (für das Ausblenden des Ringgriffs beim Vergrößern). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawKnob(center: Offset, radius: Float, fill: Color, alpha: Float) {
    if (alpha >= 0.99f) { drawKnob(center, radius, fill); return }
    drawCircle(Color(0x55000000), radius + 1.5.dp.toPx(), center, alpha = alpha)
    drawCircle(Color.White, radius, center, alpha = alpha)
    drawCircle(fill, radius - 3.dp.toPx(), center, alpha = alpha)
}

/**
 * Geometrie der Scheibe in Pixeln. [expand] 0 = normal, 1 = Scheibe auf volle Größe vergrößert.
 * Zeichnen und Treffererkennung verwenden dieselbe Klasse – dadurch stimmen Anzeige und Touch überein.
 */
private class DiscGeometry(diameter: Float, val ring: Float, expand: Float) {
    val center = Offset(diameter / 2f, diameter / 2f)
    val outerRadius = diameter / 2f
    val gap = ring * 0.3f
    private val normalInner = outerRadius - ring - gap
    val innerRadius = normalInner + (outerRadius - normalInner) * expand.coerceIn(0f, 1f)

    fun dist(p: Offset) = hypot(p.x - center.x, p.y - center.y)

    /** Ring beginnt in der Mitte der Lücke zwischen Scheibe und Ring (größere Trefferfläche). */
    fun isRing(p: Offset) = dist(p) >= normalInner + gap / 2f

    /** Farbton am Punkt: 0° oben, im Uhrzeigersinn (Bildschirm-y zeigt nach unten). */
    fun hueAt(p: Offset): Float {
        val deg = Math.toDegrees(atan2((p.y - center.y).toDouble(), (p.x - center.x).toDouble())).toFloat() + 90f
        return ((deg % 360f) + 360f) % 360f
    }

    fun huePoint(h: Float): Offset {
        val a = Math.toRadians((h - 90f).toDouble())
        val rr = outerRadius - ring / 2f
        return Offset(center.x + (rr * cos(a)).toFloat(), center.y + (rr * sin(a)).toFloat())
    }

    /** Punkt → (Sättigung, Helligkeit); Punkte außerhalb werden auf den Rand geklemmt. */
    fun sbAt(p: Offset, out: FloatArray) {
        var u = (p.x - center.x) / innerRadius
        var v = (p.y - center.y) / innerRadius
        val len = hypot(u, v)
        if (len > 1f) { u /= len; v /= len }
        discToSquare(u, v, out)
        val x = out[0]; val y = out[1]
        out[0] = ((x + 1f) / 2f).coerceIn(0f, 1f)
        out[1] = ((1f - y) / 2f).coerceIn(0f, 1f)
    }

    /** (Sättigung, Helligkeit) → Punkt (elliptische Quadrat→Kreis-Abbildung). */
    fun sbToPoint(s: Float, b: Float): Offset {
        val x = s * 2f - 1f
        val y = 1f - b * 2f
        val u = x * sqrt(1f - y * y / 2f)
        val v = y * sqrt(1f - x * x / 2f)
        return Offset(center.x + u * innerRadius, center.y + v * innerRadius)
    }

    private val tmp = FloatArray(2)

    fun apply(model: HsvModel, p: Offset, onRing: Boolean) {
        if (onRing) model.set(h = hueAt(p))
        else { sbAt(p, tmp); model.set(s = tmp[0], v = tmp[1]) }
    }

    /** Doppeltippen: auf den nächstgelegenen Rasterpunkt einrasten. */
    fun snap(model: HsvModel, p: Offset, onRing: Boolean) {
        if (onRing) {
            model.set(h = (hueAt(p) / 15f).roundToInt() * 15f)
            return
        }
        var best = Float.MAX_VALUE
        var bs = 0f; var bv = 0f
        for (sv in SNAP_VALUES) for (vv in SNAP_VALUES) {
            val q = sbToPoint(sv, vv)
            val dd = (q - p).getDistanceSquared()
            if (dd < best) { best = dd; bs = sv; bv = vv }
        }
        // Schwarz ist bei jeder Sättigung gleich – Sättigung dann beibehalten (Griff springt nicht seitlich).
        model.set(s = if (bv == 0f) model.s else bs, v = bv)
    }

    companion object {
        /** 0 / 50 / 100 % für Sättigung und Helligkeit → Weiß, Mittelgrau, Schwarz, halbe/volle Sättigung. */
        val SNAP_VALUES = floatArrayOf(0f, 0.5f, 1f)
    }
}

/**
 * Inverse der elliptischen Quadrat→Kreis-Abbildung (u,v im Einheitskreis → x,y in [-1,1]²).
 * Schreibt x, y nach [out] (keine Allokation).
 */
private fun discToSquare(u: Float, v: Float, out: FloatArray) {
    val u2 = u * u; val v2 = v * v
    val t = 2f * sqrt(2f)
    val x = 0.5f * sqrt((2f + u2 - v2 + t * u).coerceAtLeast(0f)) - 0.5f * sqrt((2f + u2 - v2 - t * u).coerceAtLeast(0f))
    val y = 0.5f * sqrt((2f - u2 + v2 + t * v).coerceAtLeast(0f)) - 0.5f * sqrt((2f - u2 + v2 - t * v).coerceAtLeast(0f))
    out[0] = x.coerceIn(-1f, 1f)
    out[1] = y.coerceIn(-1f, 1f)
}

/**
 * Zwei vorberechnete Alpha-Masken in voller Ringgröße (werden für die vergrößerte Scheibe nur skaliert):
 *  - [sat]: Alpha = Sättigung S am Pixel
 *  - [dark]: Alpha = 1 − Helligkeit V am Pixel
 * Werte außerhalb des Kreises werden auf den Rand geklemmt, damit die Kante beim Zuschneiden sauber bleibt.
 */
private class DiscMasks {
    private var dim = 0
    var sat: ImageBitmap? = null; private set
    var dark: ImageBitmap? = null; private set

    fun ensure(d: Int) {
        if (d == dim && sat != null) return
        dim = d
        val satBmp = Bitmap.createBitmap(d, d, Bitmap.Config.ALPHA_8)
        val darkBmp = Bitmap.createBitmap(d, d, Bitmap.Config.ALPHA_8)
        val stride = satBmp.rowBytes
        val satBytes = ByteArray(stride * d)
        val darkBytes = ByteArray(darkBmp.rowBytes * d)
        val darkStride = darkBmp.rowBytes
        val r = d / 2f
        val out = FloatArray(2)
        for (yy in 0 until d) for (xx in 0 until d) {
            var u = (xx + 0.5f - r) / r
            var v = (yy + 0.5f - r) / r
            val len = hypot(u, v)
            if (len > 1f) { u /= len; v /= len }
            discToSquare(u, v, out)
            val s = (out[0] + 1f) / 2f
            val b = (1f - out[1]) / 2f
            satBytes[yy * stride + xx] = (s * 255f).roundToInt().coerceIn(0, 255).toByte()
            darkBytes[yy * darkStride + xx] = ((1f - b) * 255f).roundToInt().coerceIn(0, 255).toByte()
        }
        satBmp.copyPixelsFromBuffer(ByteBuffer.wrap(satBytes))
        darkBmp.copyPixelsFromBuffer(ByteBuffer.wrap(darkBytes))
        // alte Bitmaps nicht recyceln: sie können im laufenden Frame noch gezeichnet werden
        sat = satBmp.asImageBitmap().also { it.prepareToDraw() }
        dark = darkBmp.asImageBitmap().also { it.prepareToDraw() }
    }
}
