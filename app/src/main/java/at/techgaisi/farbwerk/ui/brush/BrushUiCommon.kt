package at.techgaisi.farbwerk.ui.brush

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.ui.theme.LocalUi

/** Farbtöne der Pinsel-Oberfläche (dunkles Thema laut Design-Vorgaben). */
internal object BrushUiColors {
    val destructive = Color(0xFFFF453A)
    val duplicate = Color(0xFF2F7CF6)
    val neutralAction = Color(0xFF5A5A60)
    val secondarySelection = Color(0xFF1E3E78)
    val handleGreen = Color(0xFF34C759)
}

/** Texteingabe-Dialog (Umbenennen, Neues Set, Symbol). */
@Composable
internal fun TextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String = "Anwenden",
    hint: String? = null,
    maxLength: Int = 40,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val ui = LocalUi.current
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ui.panel,
        shape = RoundedCornerShape(14.dp),
        title = { Text(title, color = ui.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it.copy(text = it.text.take(maxLength)) },
                    singleLine = true,
                    textStyle = TextStyle(color = ui.text, fontSize = 16.sp),
                    cursorBrush = SolidColor(ui.accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).clip(RoundedCornerShape(8.dp))
                        .background(ui.panelElevated).border(0.5.dp, ui.divider, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
                if (hint != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(hint, color = ui.textMuted, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }) { Text(confirmLabel, color = ui.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen", color = ui.text) } },
    )
}

/** Rückfrage vor destruktiven Aktionen. */
@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Löschen",
    destructive: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val ui = LocalUi.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ui.panel,
        shape = RoundedCornerShape(14.dp),
        title = { Text(title, color = ui.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = { Text(message, color = ui.textMuted, fontSize = 14.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) BrushUiColors.destructive else ui.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen", color = ui.text) } },
    )
}

/**
 * Eigenes Set-Symbol (wenn kein Emoji gesetzt ist): stilisierter Pinselstrich in einem abgerundeten Quadrat.
 */
@Composable
internal fun SetGlyph(icon: String?, selected: Boolean, size: Dp = 28.dp) {
    val ui = LocalUi.current
    Box(
        Modifier.size(size).clip(RoundedCornerShape(7.dp))
            .background(if (selected) Color(0x33FFFFFF) else ui.panelElevated),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Text(icon, fontSize = 15.sp)
        } else {
            val col = if (selected) Color.White else ui.textMuted
            Canvas(Modifier.size(size * 0.62f)) {
                val w = this.size.width; val h = this.size.height
                val p = Path().apply {
                    moveTo(w * 0.1f, h * 0.8f)
                    cubicTo(w * 0.35f, h * 0.2f, w * 0.55f, h * 1.0f, w * 0.9f, h * 0.2f)
                }
                drawPath(p, col, style = Stroke(width = w * 0.16f, cap = StrokeCap.Round))
                drawCircle(col, radius = w * 0.07f, center = Offset(w * 0.9f, h * 0.2f))
            }
        }
    }
}

/** Einfache Menüzeile für eigene Popup-Menüs. */
@Composable
internal fun MenuRow(label: String, color: Color? = null, onClick: () -> Unit) {
    val ui = LocalUi.current
    Text(
        label, color = color ?: ui.text, fontSize = 15.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
    )
}
