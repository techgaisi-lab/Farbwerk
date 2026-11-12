package at.techgaisi.farbwerk.ui.actions

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
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
 *
 * Aktionen-Panel (UI_SPEC §6): Inhalte stehen in gruppierten, dunkleren Containern ([ActionGroup]) innerhalb
 * einer [ActionPage]. Zeilen in einer Gruppe sind 38 dp hoch, 12 dp eingerückt und durch Haarlinien getrennt.
 * Außerhalb von Gruppen (Dialoge, Galerie) behalten die Zeilen ihre großzügigeren Abstände.
 */

// =====================================================================================
// Lokale Design-Tokens (Werte aus UI_SPEC §0; Theme.kt gehört der Shell)
// =====================================================================================

/** Farben des Aktionen-Panels, passend zu hellem/dunklem Modus. */
@Immutable
internal data class ActionTokens(
    /** Gruppierte Container, inaktive Tab-Kacheln (`panelInset`). */
    val inset: Color,
    /** Gedrückt-Zustand einer Zeile (`rowPressed`). */
    val pressed: Color,
    /** Haarlinien zwischen Zeilen (`separator`). */
    val separator: Color,
    /** Aktiver Tab, Toggle an, Auswahl (`accent`). */
    val accent: Color,
    /** Toggle aus. */
    val toggleOff: Color,
    val text: Color,
    /** Untertitel, Werte, Gruppenüberschriften (`textSecondary`). */
    val textSecondary: Color,
    /** Deaktivierte Zeilen (Text @ 35 %). */
    val textDisabled: Color,
    /** Symbole inaktiv (`iconIdle`). */
    val icon: Color,
    val destructive: Color,
)

private val DarkActionTokens = ActionTokens(
    inset = Color(0xFF3A3A3C),
    pressed = Color(0xFF48484A),
    separator = Color(0x14FFFFFF),
    accent = Color(0xFF0A84FF),
    toggleOff = Color(0xFF5A5A5E),
    text = Color.White,
    textSecondary = Color(0x8CFFFFFF),
    textDisabled = Color(0x59FFFFFF),
    icon = Color(0xD9FFFFFF),
    destructive = Color(0xFFFF453A),
)

private val LightActionTokens = ActionTokens(
    inset = Color(0xFFEFEFF3),
    pressed = Color(0xFFDCDCE1),
    separator = Color(0x1A000000),
    accent = Color(0xFF007AFF),
    toggleOff = Color(0xFFD1D1D6),
    text = Color(0xFF111114),
    textSecondary = Color(0x8C111114),
    textDisabled = Color(0x59111114),
    icon = Color(0xD9111114),
    destructive = Color(0xFFFF3B30),
)

/** Liefert die Tokens passend zur aktuellen Oberfläche (hell/dunkel). */
@Composable
internal fun actionTokens(): ActionTokens =
    if (LocalUi.current.panel.luminance() > 0.5f) LightActionTokens else DarkActionTokens

/** true, solange eine Zeile innerhalb eines [ActionGroup]-Containers liegt. */
internal val LocalInGroup = staticCompositionLocalOf { false }

private val RowHeight = 38.dp
private val RowPadGroup = 12.dp
private val RowPadPlain = 16.dp
private val GroupRadius = 9.dp
private val GroupSideMargin = 12.dp
private val GroupSpacing = 14.dp

// =====================================================================================
// Seiten & Gruppen
// =====================================================================================

/** Scrollbare Inhaltsseite des Aktionen-Panels: Gruppen mit 14 dp Abstand. */
@Composable
fun ActionPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 2.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(GroupSpacing),
        content = content,
    )
}

/** Hält die y-Positionen der Haarlinien zwischen den Zeilen einer Gruppe (wird beim Layout gefüllt). */
private class SeparatorPositions {
    var ys = FloatArray(16)
    var count = 0
    fun reset(capacity: Int) {
        if (ys.size < capacity) ys = FloatArray(capacity)
        count = 0
    }
}

/**
 * Gruppierter Container (`panelInset`, Radius 9 dp, 12 dp seitlicher Abstand). Jedes direkte Kind ist eine
 * Zeile; zwischen sichtbaren Zeilen wird eine 0,5-dp-Haarlinie gezeichnet. Optional mit Überschrift darüber
 * und Fußnote darunter (beide in `textSecondary`).
 */
@Composable
fun ActionGroup(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    val t = actionTokens()
    val seps = remember { SeparatorPositions() }
    Column(modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                header, color = t.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = GroupSideMargin + RowPadGroup, end = 24.dp, bottom = 6.dp),
            )
        }
        Layout(
            content = { CompositionLocalProvider(LocalInGroup provides true) { content() } },
            modifier = Modifier
                .padding(horizontal = GroupSideMargin)
                .fillMaxWidth()
                .clip(RoundedCornerShape(GroupRadius))
                .background(t.inset)
                .drawWithContent {
                    drawContent()
                    val stroke = 0.5.dp.toPx()
                    val start = RowPadGroup.toPx()
                    for (i in 0 until seps.count) {
                        val y = seps.ys[i]
                        drawLine(t.separator, Offset(start, y), Offset(size.width, y), stroke)
                    }
                },
        ) { measurables, constraints ->
            val childConstraints = constraints.copy(minHeight = 0)
            val placeables = measurables.map { it.measure(childConstraints) }
            seps.reset(placeables.size)
            var y = 0
            var seen = false
            for (p in placeables) {
                if (p.height > 0) {
                    if (seen) seps.ys[seps.count++] = y.toFloat()
                    seen = true
                }
                y += p.height
            }
            layout(constraints.maxWidth, y) {
                var py = 0
                for (p in placeables) { p.place(0, py); py += p.height }
            }
        }
        if (footer != null) {
            Text(
                footer, color = t.textSecondary, fontSize = 12.sp, lineHeight = 15.sp,
                modifier = Modifier.padding(start = GroupSideMargin + RowPadGroup, end = 24.dp, top = 6.dp),
            )
        }
    }
}

/** Kleine Überschrift über einer Gruppe (alternativ zu `ActionGroup(header = …)`). */
@Composable
fun SectionHeader(text: String) {
    val t = actionTokens()
    Text(
        text, color = t.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = GroupSideMargin + RowPadGroup, top = 10.dp, bottom = 6.dp)
    )
}

// =====================================================================================
// Zeilen
// =====================================================================================

/** Klickbare Zeile ohne Ripple: Hintergrund wechselt beim Drücken sofort auf `rowPressed`. */
@Composable
internal fun Modifier.pressableRow(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val t = actionTokens()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    return this
        .background(if (pressed && enabled) t.pressed else Color.Transparent)
        .clickable(src, indication = null, enabled = enabled, onClick = onClick)
}

/** Horizontaler Innenabstand einer Zeile – in Gruppen 12 dp, sonst 16 dp. */
@Composable
internal fun rowPadding(): androidx.compose.ui.unit.Dp = if (LocalInGroup.current) RowPadGroup else RowPadPlain

@Composable
fun ActionRow(
    label: String,
    icon: ImageVector? = null,
    trailing: String? = null,
    chevron: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val t = actionTokens()
    val inGroup = LocalInGroup.current
    val color = when {
        !enabled -> t.textDisabled
        destructive -> t.destructive
        accent -> t.accent
        else -> t.text
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (inGroup) RowHeight else 44.dp)
            .pressableRow(enabled, onClick)
            .padding(horizontal = rowPadding(), vertical = if (inGroup) 0.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) t.icon else t.textDisabled, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(label, color = color, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, color = if (enabled) t.textSecondary else t.textDisabled, fontSize = 13.sp, maxLines = 1,
                modifier = Modifier.padding(start = 8.dp))
        }
        // In Gruppen erscheinen Navigationszeilen ohne Pfeil (UI_SPEC §6).
        if (chevron && !inGroup) {
            Icon(ActionIcons.Chevron, null, tint = t.textSecondary, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * iOS-artiger Schalter 44 × 26 dp: an = `accent`, aus = grau, weißer Knopf Ø 22 dp; Wechsel animiert 150 ms.
 * Mit [onChange] = null ist der Schalter nur Anzeige (die umgebende Zeile übernimmt das Umschalten).
 */
@Composable
fun FwToggle(checked: Boolean, onChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val t = actionTokens()
    val frac by animateFloatAsState(if (checked) 1f else 0f, tween(150), label = "toggle")
    val track by animateColorAsState(if (checked) t.accent else t.toggleOff, tween(150), label = "toggleTrack")
    var m = modifier.size(44.dp, 26.dp).alpha(if (enabled) 1f else 0.45f).clip(CircleShape).background(track)
    if (onChange != null) {
        m = m.toggleable(checked, remember { MutableInteractionSource() }, indication = null, enabled = enabled,
            role = Role.Switch, onValueChange = onChange)
    }
    Box(m) {
        Box(
            Modifier.offset(x = 2.dp + 18.dp * frac, y = 2.dp).size(22.dp)
                .shadow(1.5.dp, CircleShape).background(Color.White, CircleShape)
        )
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, subtitle: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val t = actionTokens()
    val inGroup = LocalInGroup.current
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (inGroup) RowHeight else 46.dp)
            .toggleable(checked, src, indication = null, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = rowPadding(), vertical = if (subtitle != null) 6.dp else if (inGroup) 0.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(label, color = if (enabled) t.text else t.textDisabled, fontSize = 15.sp)
            if (subtitle != null) {
                Text(subtitle, color = if (enabled) t.textSecondary else t.textDisabled, fontSize = 12.sp, lineHeight = 15.sp)
            }
        }
        FwToggle(checked, null, enabled = enabled)
    }
}

/** Zeile einer Auswahlliste: Häkchen in `accent` rechts, wenn gewählt. */
@Composable
fun CheckRow(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val t = actionTokens()
    Row(
        Modifier.fillMaxWidth().heightIn(min = RowHeight).pressableRow(enabled, onClick).padding(horizontal = rowPadding()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (enabled) t.text else t.textDisabled, fontSize = 15.sp, maxLines = 1,
            modifier = Modifier.weight(1f))
        if (selected) Icon(ActionIcons.Check, "Ausgewählt", tint = t.accent, modifier = Modifier.size(18.dp))
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
    val t = actionTokens()
    val inGroup = LocalInGroup.current
    Column(Modifier.fillMaxWidth().padding(horizontal = rowPadding(), vertical = if (inGroup) 6.dp else 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = t.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(format(value), color = t.textSecondary, fontSize = 13.sp)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange,
            onValueChangeFinished = onDone, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = t.accent,
                inactiveTrackColor = t.toggleOff, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
            modifier = Modifier.height(30.dp)
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
    val t = actionTokens()
    val bg = if (LocalInGroup.current) t.toggleOff.copy(alpha = 0.45f) else ui.panelElevated
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEach { (v, label) ->
            val sel = v == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                    .background(if (sel) t.accent else Color.Transparent)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(v) }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) { Text(label, color = if (sel) Color.White else t.text, fontSize = 12.sp, maxLines = 1) }
        }
    }
}

// =====================================================================================
// Dialoge
// =====================================================================================

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
