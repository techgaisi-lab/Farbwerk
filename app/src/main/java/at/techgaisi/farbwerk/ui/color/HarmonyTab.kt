package at.techgaisi.farbwerk.ui.color

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.color.ColorMath
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Harmonie-Modi mit den Farbtonversätzen der Zusatzfarben (Grad). */
enum class HarmonyMode(val label: String, val offsets: List<Float>) {
    COMPLEMENTARY("Komplementär", listOf(180f)),
    SPLIT("Komplementär geteilt", listOf(150f, 210f)),
    ANALOGOUS("Analog", listOf(-30f, 30f)),
    TRIADIC("Triadisch", listOf(120f, 240f)),
    TETRADIC("Tetradisch", listOf(60f, 180f, 240f)),
}

/** Zuletzt gewählter Modus (bleibt erhalten, solange die App läuft). */
private var lastHarmonyMode = HarmonyMode.COMPLEMENTARY

/**
 * Harmonie: Farbrad (Winkel = Farbton, Radius = Sättigung) mit Hauptgriff und Harmoniegriffen.
 * Ziehen bewegt die Hauptfarbe; Tippen auf einen Harmoniegriff (oder ein Feld darunter) wählt diese Farbe.
 */
@Composable
fun HarmonyTab(model: HsvModel) {
    val ui = LocalUi.current
    var mode by remember { mutableStateOf(lastHarmonyMode) }
    var menu by remember { mutableStateOf(false) }
    val renderer = remember { WheelRenderer() }
    // true, wenn die Geste auf einem Harmoniegriff begann (dann Tippen = Farbe wählen)
    var tappedHarmony by remember { mutableStateOf<Int?>(null) }

    fun harmonyColors(): List<Int> = mode.offsets.map { ColorMath.hsvToArgb(model.h + it, model.s, model.v) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Box {
            Text(
                mode.label + "  ▾", color = ui.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { menu = true }.padding(6.dp)
            )
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                HarmonyMode.entries.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m.label, fontWeight = if (m == mode) FontWeight.SemiBold else FontWeight.Normal) },
                        onClick = { mode = m; lastHarmonyMode = m; menu = false }
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .colorGestures(
                        onPress = { p, s ->
                            val g = WheelGeometry(s)
                            val hit = mode.offsets.indexOfFirst { off ->
                                (g.point(model.h + off, model.s) - p).getDistance() < g.hitRadius
                            }
                            if (hit >= 0 && (g.point(model.h, model.s) - p).getDistance() > g.hitRadius) {
                                tappedHarmony = hit
                            } else {
                                tappedHarmony = null
                                g.apply(model, p)
                            }
                        },
                        onMove = { p, s ->
                            if (tappedHarmony == null) WheelGeometry(s).apply(model, p)
                        },
                        onRelease = {
                            val hit = tappedHarmony
                            tappedHarmony = null
                            if (hit != null) {
                                model.pick(ColorMath.hsvToArgb(model.h + mode.offsets[hit], model.s, model.v))
                            } else model.release()
                        },
                    )
            ) {
                val g = WheelGeometry(IntSize(size.width.toInt(), size.height.toInt()))
                val d = (g.radius * 2).roundToInt().coerceAtLeast(2)
                drawImage(renderer.image(d, model.v), IntOffset((g.center.x - d / 2f).roundToInt(), (g.center.y - d / 2f).roundToInt()))
                drawCircle(ui.divider, g.radius, g.center, style = Stroke(1.dp.toPx()))
                // Verbindungslinien
                val main = g.point(model.h, model.s)
                mode.offsets.forEach { off ->
                    drawLine(Color.White.copy(alpha = 0.7f), main, g.point(model.h + off, model.s), 1.5.dp.toPx())
                }
                mode.offsets.forEach { off ->
                    drawKnob(g.point(model.h + off, model.s), 9.dp.toPx(), ColorMath.hsvToArgb(model.h + off, model.s, model.v).toUiColor())
                }
                drawKnob(main, 13.dp.toPx(), model.argb.toUiColor())
            }
        }
        Spacer(Modifier.height(10.dp))
        // Harmoniefarben als Felder
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Swatch(model.argb, 40.dp, Modifier.weight(1f), selected = true, corner = 6.dp) {}
            harmonyColors().forEach { c ->
                Swatch(c, 40.dp, Modifier.weight(1f), corner = 6.dp) { model.pick(c) }
            }
        }
        Spacer(Modifier.height(10.dp))
        LabeledSlider(
            "Helligkeit", "${(model.v * 100).roundToInt()} %", model.v,
            Brush.horizontalGradient(listOf(Color.Black, ColorMath.hsvToArgb(model.h, model.s, 1f).toUiColor())),
            model.argb.toUiColor(),
            onChange = { model.set(v = it) }, onRelease = { model.release() }
        )
    }
}

private class WheelGeometry(size: IntSize) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = minOf(size.width, size.height) / 2f - 4f
    val hitRadius = radius * 0.12f

    /** Farbton (Rot oben, im Uhrzeigersinn) und Sättigung (Radius) → Punkt. */
    fun point(h: Float, s: Float): Offset {
        val a = Math.toRadians((h - 90f).toDouble())
        return Offset(center.x + (cos(a) * s * radius).toFloat(), center.y + (sin(a) * s * radius).toFloat())
    }

    fun apply(model: HsvModel, p: Offset) {
        val dx = p.x - center.x; val dy = p.y - center.y
        val s = (hypot(dx, dy) / radius).coerceIn(0f, 1f)
        val h = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
        model.set(h = h, s = s)
    }
}

/** Rendert das Farbrad für eine Helligkeit; Farbton/Sättigung je Pixel sind vorberechnet. */
private class WheelRenderer {
    private var dim = 0
    private var hueMap = FloatArray(0)
    private var satMap = FloatArray(0)
    private var pixels = IntArray(0)
    private var bitmap: Bitmap? = null
    private var image: ImageBitmap? = null
    private var renderedV = Float.NaN

    fun image(d: Int, v: Float): ImageBitmap {
        if (d != dim) prepare(d)
        val img = image!!
        if (v == renderedV) return img
        renderedV = v
        for (i in pixels.indices) {
            val s = satMap[i]
            pixels[i] = if (s < 0f) 0 else ColorMath.hsvToArgb(hueMap[i], s, v)
        }
        bitmap!!.setPixels(pixels, 0, d, 0, 0, d, d)
        img.prepareToDraw()
        return img
    }

    private fun prepare(d: Int) {
        dim = d
        hueMap = FloatArray(d * d); satMap = FloatArray(d * d); pixels = IntArray(d * d)
        val r = d / 2f
        for (y in 0 until d) for (x in 0 until d) {
            val i = y * d + x
            val dx = x + 0.5f - r; val dy = y + 0.5f - r
            val dist = hypot(dx, dy) / r
            if (dist > 1f) { satMap[i] = -1f; continue }
            satMap[i] = dist
            hueMap[i] = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
        }
        val b = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
        bitmap = b
        image = b.asImageBitmap()
        renderedV = Float.NaN
    }
}
