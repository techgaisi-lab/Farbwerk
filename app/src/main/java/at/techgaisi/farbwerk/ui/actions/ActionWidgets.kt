package at.techgaisi.farbwerk.ui.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import at.techgaisi.farbwerk.io.Background
import at.techgaisi.farbwerk.ui.theme.LocalUi

/*
 * Gemeinsame Bausteine für Aktionen-Panel, Dialoge und Galerie (Zeilen, Schalter, Regler, Dialograhmen).
 */

@Composable
fun SectionHeader(text: String) {
    val ui = LocalUi.current
    Text(
        text.uppercase(), color = ui.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp)
    )
}

@Composable
fun ActionRow(
    label: String,
    icon: ImageVector? = null,
    trailing: String? = null,
    chevron: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val ui = LocalUi.current
    val color = when {
        !enabled -> ui.textMuted.copy(alpha = 0.5f)
        destructive -> Color(0xFFFF5A52)
        else -> ui.text
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) ui.icon else ui.iconMuted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(label, color = color, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = ui.textMuted, fontSize = 13.sp)
        if (chevron) Icon(ActionIcons.Chevron, null, tint = ui.iconMuted, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, subtitle: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val ui = LocalUi.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (enabled) ui.text else ui.textMuted, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = ui.textMuted, fontSize = 12.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = ui.accent, checkedThumbColor = Color.White)
        )
    }
}

@Composable
fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    steps: Int = 0,
    onChange: (Float) -> Unit,
    onDone: () -> Unit = {},
) {
    val ui = LocalUi.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = ui.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(format(value), color = ui.textMuted, fontSize = 13.sp)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange,
            onValueChangeFinished = onDone, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = ui.accent,
                inactiveTrackColor = ui.divider),
            modifier = Modifier.height(32.dp)
        )
    }
}

@Composable
fun FwDivider() {
    val ui = LocalUi.current
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(ui.divider))
}

/** Segmentierte Auswahl (z. B. Einheit, Rasterart). */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val ui = LocalUi.current
    Row(
        modifier.clip(RoundedCornerShape(9.dp)).background(ui.panelElevated).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEach { (v, label) ->
            val sel = v == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(7.dp))
                    .background(if (sel) ui.accent else Color.Transparent)
                    .clickable { onSelect(v) }.padding(vertical = 7.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) { Text(label, color = if (sel) Color.White else ui.text, fontSize = 12.sp, maxLines = 1) }
        }
    }
}

/** Dialograhmen im App-Design mit Titel, Inhalt und Schaltflächen. */
@Composable
fun FwDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String? = "OK",
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit = {},
    dismissLabel: String = "Abbrechen",
    destructive: Boolean = false,
    width: Int = 420,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ui = LocalUi.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = width.dp).padding(24.dp).clip(RoundedCornerShape(16.dp))
                .background(ui.panel).border(0.5.dp, ui.divider, RoundedCornerShape(16.dp))
        ) {
            Text(title, color = ui.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content() }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                DialogButton(dismissLabel, ui.textMuted, onDismiss)
                if (confirmLabel != null) {
                    DialogButton(confirmLabel, if (!confirmEnabled) ui.textMuted.copy(alpha = 0.4f)
                    else if (destructive) Color(0xFFFF5A52) else ui.accent) { if (confirmEnabled) onConfirm() }
                }
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, color: Color, onClick: () -> Unit) {
    Text(label, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp))
}

/** Einzeiliges Eingabefeld im App-Design. */
@Composable
fun FwTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    number: Boolean = false,
) {
    val ui = LocalUi.current
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = ui.text, unfocusedTextColor = ui.text,
            focusedBorderColor = ui.accent, unfocusedBorderColor = ui.divider,
            focusedLabelColor = ui.accent, unfocusedLabelColor = ui.textMuted, cursorColor = ui.accent,
        ),
        modifier = modifier,
    )
}

/** Modaler Fortschrittsdialog für [Background]-Aufgaben (blockiert die Leinwand während des Exports). */
@Composable
fun BusyDialog() {
    val msg = Background.busyMessage ?: return
    val ui = LocalUi.current
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Row(
            Modifier.clip(RoundedCornerShape(14.dp)).background(ui.panel).padding(horizontal = 22.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(color = ui.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Text(msg, color = ui.text, fontSize = 15.sp)
        }
    }
}
