package at.techgaisi.farbwerk.ui.color

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import at.techgaisi.farbwerk.color.ColorMath
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Scheibe: äußerer Farbtonring + innere Scheibe für Sättigung (waagrecht) und Helligkeit (senkrecht).
 * Das Sättigungs/Helligkeits-Quadrat wird per elliptischer Abbildung auf die Scheibe gelegt.
 * Doppeltippen rastet auf 10-%-Stufen (Scheibe) bzw. 15°-Stufen (Ring) ein.
 */
@Composable
fun DiscTab(model: HsvModel) {
    val ui = LocalUi.current
    val renderer = remember { DiscRenderer() }
    val density = LocalDensity.current
    // Welcher Bereich wird gerade bedient: 0 = nichts, 1 = Ring, 2 = Scheibe
    var region by remember { mutableFloatStateOf(0f) }

    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .colorGestures(
                    onPress = { p, s ->
                        val g = DiscGeometry(s, density.ringWidthPx(s))
                        region = if (g.dist(p) >= g.innerRadius + g.gap / 2f) 1f else 2f
                        applyDisc(model, g, p, region)
                    },
                    onMove = { p, s -> applyDisc(model, DiscGeometry(s, density.ringWidthPx(s)), p, region) },
                    onRelease = { region = 0f; model.release() },
                    onDoubleTap = { p, s ->
                        val g = DiscGeometry(s, density.ringWidthPx(s))
                        if (g.dist(p) >= g.innerRadius + g.gap / 2f) {
                            val hue = g.hueAt(p)
                            model.set(h = (hue / 15f).roundToInt() * 15f)
                        } else {
                            val (sat, bri) = g.sbAt(p)
                            model.set(s = (sat * 10f).roundToInt() / 10f, v = (bri * 10f).roundToInt() / 10f)
                        }
                    },
                )
        ) {
            val g = DiscGeometry(IntSize(size.width.toInt(), size.height.toInt()), ringWidthPx(IntSize(size.width.toInt(), 0)))
            // Farbtonring (Rot oben)
            rotate(-90f, g.center) {
                drawCircle(
                    Brush.sweepGradient(HueColors, g.center),
                    radius = g.outerRadius - g.ring / 2f,
                    center = g.center,
                    style = Stroke(g.ring)
                )
            }
            // innere Scheibe
            val d = (g.innerRadius * 2).roundToInt().coerceAtLeast(2)
            val img = renderer.image(d, model.h)
            drawImage(img, IntOffset((g.center.x - d / 2f).roundToInt(), (g.center.y - d / 2f).roundToInt()))
            drawCircle(ui.panel, g.innerRadius + 1.dp.toPx(), g.center, style = Stroke(2.dp.toPx()))

            // Griffe
            val hueRad = Math.toRadians((model.h - 90f).toDouble())
            val rr = g.outerRadius - g.ring / 2f
            val hp = Offset(g.center.x + (rr * kotlin.math.cos(hueRad)).toFloat(), g.center.y + (rr * kotlin.math.sin(hueRad)).toFloat())
            drawKnob(hp, g.ring / 2f - 1.dp.toPx(), ColorMath.hsvToArgb(model.h, 1f, 1f).toUiColor())
            drawKnob(g.sbToPoint(model.s, model.v), 11.dp.toPx(), model.argb.toUiColor())
        }
    }
}

private fun androidx.compose.ui.unit.Density.ringWidthPx(s: IntSize) = (s.width * 0.085f).coerceAtLeast(22.dp.toPx())

private fun applyDisc(model: HsvModel, g: DiscGeometry, p: Offset, region: Float) {
    when (region) {
        1f -> model.set(h = g.hueAt(p))
        2f -> { val (sat, bri) = g.sbAt(p); model.set(s = sat, v = bri) }
    }
}

/** Geometrie der Scheibe (Pixel). */
private class DiscGeometry(size: IntSize, val ring: Float) {
    val center = Offset(size.width / 2f, size.width / 2f)
    val outerRadius = size.width / 2f
    val gap = ring * 0.35f
    val innerRadius = outerRadius - ring - gap

    fun dist(p: Offset) = hypot(p.x - center.x, p.y - center.y)

    fun hueAt(p: Offset): Float {
        val deg = Math.toDegrees(atan2((p.y - center.y).toDouble(), (p.x - center.x).toDouble())).toFloat() + 90f
        return ((deg % 360f) + 360f) % 360f
    }

    /** Punkt → (Sättigung, Helligkeit) über die inverse elliptische Abbildung. */
    fun sbAt(p: Offset): Pair<Float, Float> {
        var u = (p.x - center.x) / innerRadius
        var v = (p.y - center.y) / innerRadius
        val len = hypot(u, v)
        if (len > 1f) { u /= len; v /= len }
        val (x, y) = discToSquare(u, v)
        return ((x + 1f) / 2f).coerceIn(0f, 1f) to ((1f - y) / 2f).coerceIn(0f, 1f)
    }

    fun sbToPoint(s: Float, b: Float): Offset {
        val x = s * 2f - 1f
        val y = 1f - b * 2f
        val u = x * sqrt(1f - y * y / 2f)
        val v = y * sqrt(1f - x * x / 2f)
        return Offset(center.x + u * innerRadius, center.y + v * innerRadius)
    }
}

/** Inverse der elliptischen Quadrat→Kreis-Abbildung (u,v im Einheitskreis → x,y in [-1,1]²). */
private fun discToSquare(u: Float, v: Float): Pair<Float, Float> {
    val u2 = u * u; val v2 = v * v
    val t = 2f * sqrt(2f)
    val x = 0.5f * sqrt((2f + u2 - v2 + t * u).coerceAtLeast(0f)) - 0.5f * sqrt((2f + u2 - v2 - t * u).coerceAtLeast(0f))
    val y = 0.5f * sqrt((2f - u2 + v2 + t * v).coerceAtLeast(0f)) - 0.5f * sqrt((2f - u2 + v2 - t * v).coerceAtLeast(0f))
    return x.coerceIn(-1f, 1f) to y.coerceIn(-1f, 1f)
}

/**
 * Rendert die innere Scheibe. Die Sättigungs/Helligkeitswerte je Pixel werden pro Größe einmal
 * vorberechnet; bei Farbtonänderung wird nur noch HSB→RGB gerechnet (Bitmap/Puffer werden wiederverwendet).
 */
private class DiscRenderer {
    private var dim = 0
    private var satMap = FloatArray(0)
    private var briMap = FloatArray(0)
    private var pixels = IntArray(0)
    private var bitmap: Bitmap? = null
    private var image: ImageBitmap? = null
    private var renderedHue = Float.NaN

    fun image(d: Int, hue: Float): ImageBitmap {
        if (d != dim) prepare(d)
        val img = image!!
        if (hue == renderedHue) return img
        renderedHue = hue
        for (i in pixels.indices) {
            val s = satMap[i]
            pixels[i] = if (s < 0f) 0 else ColorMath.hsvToArgb(hue, s, briMap[i])
        }
        bitmap!!.setPixels(pixels, 0, d, 0, 0, d, d)
        img.prepareToDraw()
        return img
    }

    private fun prepare(d: Int) {
        dim = d
        satMap = FloatArray(d * d)
        briMap = FloatArray(d * d)
        pixels = IntArray(d * d)
        val r = d / 2f
        for (yy in 0 until d) for (xx in 0 until d) {
            val i = yy * d + xx
            val u = (xx + 0.5f - r) / r
            val v = (yy + 0.5f - r) / r
            if (u * u + v * v > 1f) { satMap[i] = -1f; continue }
            val (x, y) = discToSquare(u, v)
            satMap[i] = (x + 1f) / 2f
            briMap[i] = (1f - y) / 2f
        }
        // alte Bitmap nicht recyceln: sie kann im laufenden Frame noch gezeichnet werden
        val b = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
        bitmap = b
        image = b.asImageBitmap()
        renderedHue = Float.NaN
    }
}
