package at.techgaisi.farbwerk.ui.color

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.color.ColorMath
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import androidx.compose.ui.unit.min
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

/** Gewählter Harmonie-Modus (bleibt erhalten, solange die App läuft; Kopfzeile zeigt/ändert ihn). */
object HarmonySettings {
    var mode by mutableStateOf(HarmonyMode.COMPLEMENTARY)
}

/**
 * Antippbarer Modusname für die Kopfzeile des Farben-Panels (Untertitel, 12 sp @ 55 %): öffnet die
 * Liste der Harmonie-Modi.
 */
@Composable
fun HarmonyModeSubtitle(color: Color) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Text(
            HarmonySettings.mode.label, color = color, fontSize = 12.sp, maxLines = 1,
            modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { menu = true }
        )
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            HarmonyMode.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.label, fontWeight = if (m == HarmonySettings.mode) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = { HarmonySettings.mode = m; menu = false }
                )
            }
        }
    }
}

/**
 * Harmonie: Farbrad (Winkel = Farbton, Sättigung nach innen zu Weiß) mit Hauptgriff (Ø 22 dp) und
 * Harmoniegriffen (Ø 12 dp), beide mit 2 dp weißem Rand; darunter die dünne Helligkeitslinie.
 * Ziehen bewegt die Hauptfarbe; Tippen auf einen Harmoniegriff wählt diese Farbe.
 * Der Modus wird über den Untertitel der Kopfzeile gewählt ([HarmonyModeSubtitle]).
 */
@Composable
fun HarmonyTab(model: HsvModel, modifier: Modifier = Modifier) {
    val mode = HarmonySettings.mode
    val renderer = remember { WheelRenderer() }
    // Index des Harmoniegriffs, auf dem die Geste begann (dann Tippen = Farbe wählen)
    var tappedHarmony by remember { mutableStateOf<Int?>(null) }

    Column(modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 2.dp)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val d = min(maxWidth, maxHeight).coerceAtLeast(120.dp)
            Canvas(
                Modifier
                    .size(d)
                    .colorGestures(
                        onPress = { p, s ->
                            val g = WheelGeometry(s)
                            val m = HarmonySettings.mode
                            val hit = m.offsets.indexOfFirst { off ->
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
                                val off = HarmonySettings.mode.offsets.getOrNull(hit)
                                if (off != null) model.pick(ColorMath.hsvToArgb(model.h + off, model.s, model.v))
                            } else model.release()
                        },
                    )
            ) {
                val g = WheelGeometry(IntSize(size.width.toInt(), size.height.toInt()))
                val dp = (g.radius * 2).roundToInt().coerceAtLeast(2)
                // Rad mit voller Helligkeit einmal pro Größe rendern; Helligkeit als schwarze Überlagerung
                // (HSB: V · Farbe) – dadurch kostet das Ziehen an der Helligkeitslinie keine Pixelrechnung.
                // Abdunkeln direkt auf den Pixeln des Rads (SrcAtop) – deckt exakt dieselbe Fläche ab,
                // statt eines separaten Kreises, der um Rundungspixel versetzt sein kann.
                drawImage(
                    renderer.image(dp),
                    dstOffset = IntOffset((g.center.x - dp / 2f).roundToInt(), (g.center.y - dp / 2f).roundToInt()),
                    dstSize = IntSize(dp, dp),
                    colorFilter = if (model.v < 1f)
                        androidx.compose.ui.graphics.ColorFilter.tint(Color.Black.copy(alpha = 1f - model.v), androidx.compose.ui.graphics.BlendMode.SrcAtop)
                    else null,
                )
                mode.offsets.forEach { off ->
                    drawHandle(g.point(model.h + off, model.s), 6.dp.toPx(), ColorMath.hsvToArgb(model.h + off, model.s, model.v).toUiColor())
                }
                drawHandle(g.point(model.h, model.s), 11.dp.toPx(), model.argb.toUiColor())
            }
        }
        Spacer(Modifier.height(8.dp))
        BrightnessLine(model.v, onChange = { model.set(v = it) }, onRelease = { model.release() })
    }
}

/** Griff: Kreis mit 2 dp weißem Rand und zarter dunkler Kontur (auf hellem Grund sichtbar). */
private fun DrawScope.drawHandle(c: Offset, radius: Float, fill: Color) {
    val sw = 2.dp.toPx()
    drawCircle(Color(0x40000000), radius + 1.dp.toPx(), c)
    drawCircle(fill, radius - sw / 2f, c)
    drawCircle(Color.White, radius - sw / 2f, c, style = Stroke(sw))
}

/**
 * Helligkeitsregler im Stil des Vorbilds: dünne Linie (2 dp, dunkel → hell), Daumen Ø 12 dp weiß.
 * Trefferhöhe 28 dp.
 */
@Composable
fun BrightnessLine(value: Float, onChange: (Float) -> Unit, onRelease: () -> Unit, modifier: Modifier = Modifier) {
    val thumbR = with(LocalDensity.current) { 6.dp.toPx() }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .colorGestures(
                onPress = { p, s -> onChange(((p.x - thumbR) / (s.width - 2 * thumbR)).coerceIn(0f, 1f)) },
                onRelease = onRelease,
            )
    ) {
        val y = size.height / 2f
        val sw = 2.dp.toPx()
        drawLine(
            Brush.horizontalGradient(listOf(Color(0xFF111111), Color(0xFFEDEDED)), startX = thumbR, endX = size.width - thumbR),
            Offset(thumbR, y), Offset(size.width - thumbR, y), sw, StrokeCap.Round,
        )
        val x = thumbR + value.coerceIn(0f, 1f) * (size.width - 2 * thumbR)
        drawCircle(Color(0x55000000), thumbR + 1.dp.toPx(), Offset(x, y))
        drawCircle(Color.White, thumbR, Offset(x, y))
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

/** Rendert das Farbrad mit voller Helligkeit; wird nur bei Größenänderung neu berechnet. */
private class WheelRenderer {
    private var dim = 0
    private var image: ImageBitmap? = null

    fun image(d: Int): ImageBitmap {
        image?.let { if (d == dim) return it }
        dim = d
        val pixels = IntArray(d * d)
        val r = d / 2f
        for (y in 0 until d) for (x in 0 until d) {
            val dx = x + 0.5f - r; val dy = y + 0.5f - r
            val dist = hypot(dx, dy) / r
            if (dist > 1f) continue
            // Farbton: Rot oben, im Uhrzeigersinn – identisch zu WheelGeometry.point()/apply()
            val hue = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
            pixels[y * d + x] = ColorMath.hsvToArgb(hue, dist, 1f)
        }
        val b = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
        b.setPixels(pixels, 0, d, 0, 0, d, d)
        // alte Bitmap nicht recyceln: sie kann im laufenden Frame noch gezeichnet werden
        return b.asImageBitmap().also { it.prepareToDraw(); image = it }
    }
}
