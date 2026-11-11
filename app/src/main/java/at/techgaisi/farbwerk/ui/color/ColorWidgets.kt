package at.techgaisi.farbwerk.ui.color

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.color.ColorMath
import at.techgaisi.farbwerk.ui.theme.LocalUi

/** Ganzzahlige ARGB-Farbe → Compose-Farbe. */
fun Int.toUiColor() = Color(this)

/**
 * Einheitliche Zeige-Geste für Farbflächen: Drücken, Ziehen, Loslassen, optional Doppeltippen.
 * Callbacks erhalten die lokale Position und die Größe der Fläche.
 */
@Composable
fun Modifier.colorGestures(
    onPress: (Offset, IntSize) -> Unit,
    onMove: (Offset, IntSize) -> Unit = onPress,
    onRelease: () -> Unit,
    onDoubleTap: ((Offset, IntSize) -> Unit)? = null,
): Modifier {
    val press by rememberUpdatedState(onPress)
    val move by rememberUpdatedState(onMove)
    val release by rememberUpdatedState(onRelease)
    val dbl by rememberUpdatedState(onDoubleTap)
    return this.pointerInput(Unit) {
        var lastUpTime = 0L
        var lastUpPos = Offset.Zero
        val dblSlop = 36.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            val d = dbl
            val isDouble = d != null && down.uptimeMillis - lastUpTime < 320 &&
                (down.position - lastUpPos).getDistance() < dblSlop
            if (isDouble) d!!(down.position, size) else press(down.position, size)
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) {
                    lastUpTime = if (isDouble) 0L else ch.uptimeMillis
                    lastUpPos = ch.position
                    break
                }
                if (ch.positionChanged()) {
                    ch.consume()
                    if (!isDouble) move(ch.position, size)
                }
            }
            release()
        }
    }
}

/**
 * Regler mit Farbverlaufs-Spur (für Farbton/Sättigung/Helligkeit/RGB).
 * [value] 0..1; [onChange] live, [onRelease] beim Loslassen.
 */
@Composable
fun GradientSlider(
    value: Float,
    track: Brush,
    thumbColor: Color,
    onChange: (Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = LocalUi.current
    val density = LocalDensity.current
    Canvas(
        modifier
            .fillMaxWidth()
            .height(30.dp)
            .colorGestures(
                onPress = { p, s -> onChange(density.xToValue(p.x, s.width.toFloat())) },
                onRelease = onRelease,
            )
    ) {
        val r = size.height / 2f
        val trackH = 14.dp.toPx()
        val top = (size.height - trackH) / 2f
        drawRoundRect(track, Offset(r, top), Size(size.width - 2 * r, trackH), CornerRadius(trackH / 2))
        drawRoundRect(ui.divider, Offset(r, top), Size(size.width - 2 * r, trackH), CornerRadius(trackH / 2), style = Stroke(1.dp.toPx()))
        val cx = r + value.coerceIn(0f, 1f) * (size.width - 2 * r)
        drawKnob(Offset(cx, size.height / 2f), r - 2.dp.toPx(), thumbColor)
    }
}

private fun androidx.compose.ui.unit.Density.xToValue(x: Float, width: Float): Float {
    val r = 15.dp.toPx()
    return ((x - r) / (width - 2 * r)).coerceIn(0f, 1f)
}

/** Runder Griff mit weißem Rand und dunkler Kontur – auf allen Untergründen sichtbar. */
fun DrawScope.drawKnob(center: Offset, radius: Float, fill: Color) {
    drawCircle(Color(0x55000000), radius + 1.5.dp.toPx(), center)
    drawCircle(Color.White, radius, center)
    drawCircle(fill, radius - 3.dp.toPx(), center)
}

/**
 * Farbfeld. [color] null = leeres Feld (gestrichelte Optik).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Swatch(
    color: Int?,
    size: Dp,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    corner: Dp = 4.dp,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val ui = LocalUi.current
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(color?.toUiColor() ?: ui.panelElevated.copy(alpha = 0.5f))
            .border(
                if (selected) 2.dp else 0.5.dp,
                if (selected) ui.accent else ui.divider,
                shape
            )
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
    )
}

/** Beschriftete Zeile: Name – Regler – Wert. */
@Composable
fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    track: Brush,
    thumbColor: Color,
    onChange: (Float) -> Unit,
    onRelease: () -> Unit,
) {
    val ui = LocalUi.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = ui.textMuted, fontSize = 12.sp, modifier = Modifier.width(76.dp))
        GradientSlider(value, track, thumbColor, onChange, onRelease, Modifier.weight(1f))
        Text(
            valueText, color = ui.text, fontSize = 12.sp,
            style = TextStyle(fontFeatureSettings = "tnum"),
            modifier = Modifier.width(44.dp).padding(start = 6.dp)
        )
    }
}

/** Farbtonverlauf (für Spuren und Ringe). */
val HueColors: List<Color> = (0..12).map { Color(ColorMath.hsvToArgb(it * 30f, 1f, 1f)) }
