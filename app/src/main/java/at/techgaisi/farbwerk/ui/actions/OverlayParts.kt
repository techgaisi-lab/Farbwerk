package at.techgaisi.farbwerk.ui.actions

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.ui.theme.LocalUi

/*
 * Gemeinsame Bausteine der Vollbild-Modi (Zeichenhilfe-Editor, Zuschneiden, Text): obere Modusleiste,
 * Farbton-Leiste, Steuerpunkte auf der Leinwand.
 */

/** Klicks/Berührungen nicht an die darunterliegende Leinwand durchlassen. */
@Composable
fun Modifier.consumeTouches(): Modifier =
    this.clickable(remember { MutableInteractionSource() }, indication = null) {}

/** Obere Leiste eines Vollbild-Modus: links „Abbrechen“, mittig Titel, rechts „Fertig“. */
@Composable
fun ModeTopBar(
    title: String,
    onCancel: (() -> Unit)?,
    onDone: () -> Unit,
    doneLabel: String = "Fertig",
    extra: @Composable RowScope.() -> Unit = {},
) {
    val ui = LocalUi.current
    Row(
        Modifier.fillMaxWidth().background(ui.bar).statusBarsPadding().height(48.dp).padding(horizontal = 8.dp)
            .consumeTouches(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onCancel != null) BarTextButton("Abbrechen", ui.text, onCancel)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(title, color = ui.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
        extra()
        BarTextButton(doneLabel, ui.accent, onDone, bold = true)
    }
}

@Composable
fun BarTextButton(label: String, color: Color, onClick: () -> Unit, bold: Boolean = false, enabled: Boolean = true) {
    Text(
        label, color = if (enabled) color else color.copy(alpha = 0.4f), fontSize = 15.sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

/**
 * Farbton-Leiste über die volle Breite (wie im Zeichenhilfe-Editor des Vorbilds). [hue] 0..360.
 * Ganz links/rechts zusätzlich Weiß bzw. Schwarz erreichbar über [onPickNeutral].
 */
@Composable
fun HueBar(hue: Float, modifier: Modifier = Modifier, onHue: (Float) -> Unit) {
    val colors = remember { (0..12).map { Color.hsv(it * 30f % 360f, 1f, 1f) } }
    BoxWithConstraints(modifier.height(28.dp)) {
        val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        Canvas(
            Modifier.fillMaxWidth().height(28.dp)
                .pointerInput(w) {
                    detectTapGestures { onHue((it.x / w).coerceIn(0f, 1f) * 360f) }
                }
                .pointerInput(w) {
                    detectDragGestures { change, _ -> onHue((change.position.x / w).coerceIn(0f, 1f) * 360f) }
                }
        ) {
            val barH = 10.dp.toPx()
            val top = (size.height - barH) / 2
            drawRoundRect(Brush.horizontalGradient(colors), Offset(0f, top), Size(size.width, barH), CornerRadius(barH / 2))
            val x = (hue / 360f).coerceIn(0f, 1f) * size.width
            drawCircle(Color.hsv(hue.coerceIn(0f, 360f) % 360f, 1f, 1f), 11.dp.toPx(), Offset(x, size.height / 2))
            drawCircle(Color.White, 11.dp.toPx(), Offset(x, size.height / 2), style = Stroke(2.5.dp.toPx()))
        }
    }
}

/** Zeichnet Steuerpunkte auf der Leinwand (View-Koordinaten). */
object Handles {
    private val d = Resources.getSystem().displayMetrics.density
    val radius = 11f * d
    /** Trefferradius (großzügiger als der sichtbare Punkt). */
    val hitRadius = 26f * d
    const val BLUE = 0xFF2F7CF6.toInt()
    const val GREEN = 0xFF34C759.toInt()
    const val YELLOW = 0xFFFFCC00.toInt()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = 0xFFFFFFFF.toInt() }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0xCCFFFFFF.toInt() }

    fun draw(c: Canvas, x: Float, y: Float, color: Int, selected: Boolean = false) {
        val r = if (selected) radius * 1.25f else radius
        c.drawCircle(x, y + d, r + 1.5f * d, shadow)
        fill.color = color
        c.drawCircle(x, y, r, fill)
        c.drawCircle(x, y, r, ring)
    }

    val density get() = d
}
