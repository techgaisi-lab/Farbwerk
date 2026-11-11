package at.techgaisi.farbwerk.ui.layers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Farbwähler-Dialog mit Farbrad (Farbton/Sättigung), Helligkeitsregler und Hex-Eingabe.
 * Wird für die Hintergrundfarbe verwendet.
 */
@Composable
fun ColorPickerDialog(
    initial: Int,
    title: String,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val ui = LocalUi.current
    val hsv0 = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember { mutableFloatStateOf(hsv0[0]) }
    var sat by remember { mutableFloatStateOf(hsv0[1]) }
    var value by remember { mutableFloatStateOf(hsv0[2]) }
    val current = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
    var hexText by remember { mutableStateOf(toHex(initial)) }
    var hexEditing by remember { mutableStateOf(false) }
    val shownHex = if (hexEditing) hexText else toHex(current)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ui.panelElevated,
        titleContentColor = ui.text,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                // ---------- Farbrad ----------
                Canvas(
                    Modifier
                        .size(220.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                fun handle(p: Offset) {
                                    val cx = size.width / 2f
                                    val cy = size.height / 2f
                                    val r = min(cx, cy)
                                    val dx = p.x - cx
                                    val dy = p.y - cy
                                    hue = ((Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()) + 360f) % 360f
                                    sat = (sqrt(dx * dx + dy * dy) / r).coerceIn(0f, 1f)
                                    hexEditing = false
                                }
                                val down = awaitFirstDown()
                                handle(down.position); down.consume()
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) break
                                    handle(c.position); c.consume()
                                }
                            }
                        }
                ) {
                    val r = size.minDimension / 2f
                    val hues = listOf(
                        Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                    )
                    drawCircle(Brush.sweepGradient(hues, center), r, center)
                    drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, r), r, center)
                    drawCircle(Color.Black.copy(alpha = 1f - value), r, center)
                    // Markierung
                    val a = Math.toRadians(hue.toDouble())
                    val mp = Offset(center.x + (cos(a) * sat * r).toFloat(), center.y + (sin(a) * sat * r).toFloat())
                    drawCircle(Color(current), 11.dp.toPx(), mp)
                    drawCircle(Color.White, 11.dp.toPx(), mp, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.Black.copy(alpha = 0.4f), 12.5.dp.toPx(), mp, style = Stroke(1.dp.toPx()))
                }
                Spacer(Modifier.height(12.dp))
                // ---------- Helligkeit ----------
                Text("Helligkeit", color = ui.textMuted, fontSize = 12.sp, modifier = Modifier.align(Alignment.Start))
                Slider(
                    value = value,
                    onValueChange = { value = it; hexEditing = false },
                    colors = SliderDefaults.colors(thumbColor = ui.accent, activeTrackColor = ui.accent),
                )
                // ---------- Vorschau + Hex ----------
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.clip(RoundedCornerShape(8.dp)).border(0.5.dp, ui.divider, RoundedCornerShape(8.dp))) {
                        Box(Modifier.size(width = 36.dp, height = 40.dp).background(Color(initial)))
                        Box(Modifier.size(width = 36.dp, height = 40.dp).background(Color(current)))
                    }
                    OutlinedTextField(
                        value = shownHex,
                        onValueChange = { t ->
                            val clean = t.uppercase().filter { it in "0123456789ABCDEF#" }.removePrefix("#").take(6)
                            hexText = "#$clean"
                            hexEditing = true
                            if (clean.length == 6) {
                                val c = (0xFF000000L or clean.toLong(16)).toInt()
                                val hsv = FloatArray(3)
                                android.graphics.Color.colorToHSV(c, hsv)
                                hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                            }
                        },
                        singleLine = true,
                        label = { Text("Hex") },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            keyboardType = KeyboardType.Ascii,
                        ),
                        modifier = Modifier.width(150.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(Color(current).toArgb()) }) { Text("Übernehmen", color = ui.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen", color = ui.textMuted) }
        },
    )
}

private fun toHex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)
