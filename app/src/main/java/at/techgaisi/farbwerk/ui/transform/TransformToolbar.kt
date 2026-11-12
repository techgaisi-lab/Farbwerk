package at.techgaisi.farbwerk.ui.transform

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.transform.Interpolation
import at.techgaisi.farbwerk.transform.TransformMode
import at.techgaisi.farbwerk.transform.TransformNumericInput
import at.techgaisi.farbwerk.transform.TransformNumericKind
import at.techgaisi.farbwerk.ui.selection.BarButton
import at.techgaisi.farbwerk.ui.selection.BarDivider
import at.techgaisi.farbwerk.ui.selection.BarPopover
import at.techgaisi.farbwerk.ui.selection.FloatingBar
import at.techgaisi.farbwerk.ui.selection.ModeTab
import at.techgaisi.farbwerk.ui.selection.barAnchor
import at.techgaisi.farbwerk.ui.theme.LocalUi
import java.util.Locale
import kotlin.math.roundToInt

private const val KEY_SNAP = "snap"
private const val KEY_INTERP = "interp"

/**
 * Transform-Werkzeugleiste unten (nur wenn tool == TRANSFORM), Reihenfolge wie im Vorbild:
 * Einrasten · Freiform · Gleichmäßig · Verzerren · Verkrümmen | Spiegeln H/V · 45° · An Bildschirm anpassen ·
 * Interpolation · Zurücksetzen. Darüber Popover (Einrasten, Interpolation) bzw. das Zahlenfeld nach dem
 * Antippen eines Punkts.
 */
@Composable
fun TransformToolbar(state: EditorState, modifier: Modifier = Modifier) {
    val tool = state.transformTool
    @Suppress("UNUSED_VARIABLE") val rev = state.revision
    var snapOpen by remember { mutableStateOf(false) }
    var interpOpen by remember { mutableStateOf(false) }
    val anchors = remember { mutableStateMapOf<String, Float>() }
    val enabled = tool.active
    val maxW = LocalConfiguration.current.screenWidthDp.dp - 24.dp
    val numeric = tool.numericInput

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BarPopover(snapOpen, anchors[KEY_SNAP] ?: Float.NaN, title = "Einrasten") { SnapContent(state) }
        BarPopover(interpOpen, anchors[KEY_INTERP] ?: Float.NaN, title = "Interpolation") {
            InterpolationContent(state) { interpOpen = false }
        }
        BarPopover(numeric != null, Float.NaN,
            title = if (numeric?.kind == TransformNumericKind.ANGLE) "Drehung" else "Größe") {
            // Zahlenfeld neu aufbauen, wenn ein anderer Punkt angetippt wurde
            if (numeric != null) NumericContent(state, numeric)
        }
        if (tool.layerCount > 1) {
            Text("${tool.layerCount} Ebenen", color = LocalUi.current.textMuted, fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 6.dp))
        }
        FloatingBar(Modifier.widthIn(max = maxW)) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarButton(
                    Icons.Outlined.Straighten, "Einrasten",
                    active = snapOpen || tool.magnetic || tool.snapping,
                    modifier = Modifier.barAnchor(anchors, KEY_SNAP),
                ) {
                    snapOpen = !snapOpen; interpOpen = false; tool.dismissNumericInput()
                }
                ModeTab("Freiform", tool.mode == TransformMode.FREEFORM) { tool.changeMode(TransformMode.FREEFORM) }
                ModeTab("Gleichmäßig", tool.mode == TransformMode.UNIFORM) { tool.changeMode(TransformMode.UNIFORM) }
                ModeTab("Verzerren", tool.mode == TransformMode.DISTORT) { tool.changeMode(TransformMode.DISTORT) }
                ModeTab("Verkrümmen", tool.mode == TransformMode.WARP) { tool.changeMode(TransformMode.WARP) }
                BarDivider()
                BarButton(Icons.Outlined.Flip, "Horizontal\nspiegeln", enabled = enabled) { tool.flipHorizontal() }
                BarButton(Icons.Outlined.Flip, "Vertikal\nspiegeln", enabled = enabled,
                    iconRotation = 90f) { tool.flipVertical() }
                BarButton(Icons.Outlined.RotateRight, "45° drehen", enabled = enabled) { tool.rotate45() }
                BarButton(Icons.Outlined.FitScreen, "An Bildschirm\nanpassen", enabled = enabled) { tool.fitToCanvas() }
                BarButton(
                    Icons.Outlined.Grain, "Interpolation", active = interpOpen,
                    modifier = Modifier.barAnchor(anchors, KEY_INTERP),
                ) {
                    interpOpen = !interpOpen; snapOpen = false; tool.dismissNumericInput()
                }
                BarButton(Icons.Outlined.RestartAlt, "Zurücksetzen", enabled = enabled) { tool.reset() }
            }
        }
    }
}

@Composable
private fun SnapContent(state: EditorState) {
    val tool = state.transformTool
    val ui = LocalUi.current
    val sliderColors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = ui.accent)
    val switchColors = SwitchDefaults.colors(checkedTrackColor = ui.accent)
    Column(Modifier.width(300.dp).padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Magnetisch", color = ui.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(tool.magnetic, { tool.magnetic = it }, colors = switchColors)
        }
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Einrasten", color = ui.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(tool.snapping, { tool.snapping = it }, colors = switchColors)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Abstand", color = ui.textMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("${tool.snapDistance.roundToInt()} pt", color = ui.text, fontSize = 13.sp)
        }
        Slider(tool.snapDistance, { tool.snapDistance = it }, valueRange = 1f..50f,
            enabled = tool.snapping, colors = sliderColors)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Geschwindigkeit", color = ui.textMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            val v = tool.snapSpeed
            Text(if (v <= 0.01f) "Keine" else if (v >= 0.99f) "Max" else "${(v * 100).roundToInt()} %",
                color = ui.text, fontSize = 13.sp)
        }
        Slider(tool.snapSpeed, { tool.snapSpeed = it }, enabled = tool.snapping, colors = sliderColors)
        Text("Blaue Linien richten an Objekten aus, gelbe an der Leinwand.", color = ui.textMuted, fontSize = 12.sp)
    }
}

@Composable
private fun InterpolationContent(state: EditorState, onDone: () -> Unit) {
    val tool = state.transformTool
    val ui = LocalUi.current
    Column(Modifier.width(260.dp).padding(bottom = 8.dp)) {
        for (i in Interpolation.entries) {
            Row(
                Modifier.fillMaxWidth().height(44.dp).clickable { tool.changeInterpolation(i); onDone() }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(i.label, color = ui.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                if (tool.interpolation == i) Icon(Icons.Outlined.Check, null, tint = ui.accent)
            }
        }
    }
}

private fun fmt(v: Float, decimals: Int): String =
    if (decimals == 0) v.roundToInt().toString() else String.format(Locale.GERMANY, "%.${decimals}f", v)

private fun parse(s: String): Float? = s.trim().replace(',', '.').toFloatOrNull()

/** Zahlenfeld für exakte Größe (px) bzw. Winkel (Grad, positiv = gegen den Uhrzeigersinn). */
@Composable
private fun NumericContent(state: EditorState, input: TransformNumericInput) {
    val tool = state.transformTool
    val ui = LocalUi.current
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = ui.text, unfocusedTextColor = ui.text,
        focusedBorderColor = ui.accent, unfocusedBorderColor = ui.divider,
        focusedLabelColor = ui.accent, unfocusedLabelColor = ui.textMuted, cursorColor = ui.accent,
    )
    Column(Modifier.width(300.dp).padding(horizontal = 16.dp).padding(bottom = 10.dp)) {
        if (input.kind == TransformNumericKind.ANGLE) {
            var a by remember(input) { mutableStateOf(fmt(input.angle, 1)) }
            val apply = { parse(a)?.let { tool.applyNumericAngle(it) } ?: Unit }
            OutlinedTextField(
                a, { a = it }, label = { Text("Winkel (°)") }, singleLine = true, colors = fieldColors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { apply() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Positiv = gegen den Uhrzeigersinn", color = ui.textMuted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
            Buttons(onCancel = { tool.dismissNumericInput() }, onApply = apply)
        } else {
            var linked by remember(input) { mutableStateOf(input.linked) }
            var w by remember(input) { mutableStateOf(fmt(input.width, 0)) }
            var h by remember(input) { mutableStateOf(fmt(input.height, 0)) }
            val ratio = if (input.width > 0f) input.height / input.width else 1f
            val apply = {
                val nw = parse(w); val nh = parse(h)
                if (nw != null && nh != null && nw > 0f && nh > 0f) tool.applyNumericSize(nw, nh)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    w, { v -> w = v; if (linked) parse(v)?.let { h = fmt(it * ratio, 0) } },
                    label = { Text("Breite (px)") }, singleLine = true, colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                )
                IconButton({ linked = !linked }) {
                    Icon(if (linked) Icons.Outlined.Link else Icons.Outlined.LinkOff,
                        if (linked) "Seitenverhältnis gekoppelt" else "Seitenverhältnis frei",
                        tint = if (linked) ui.accent else ui.iconMuted, modifier = Modifier.rotate(90f))
                }
                OutlinedTextField(
                    h, { v -> h = v; if (linked && ratio > 0f) parse(v)?.let { w = fmt(it / ratio, 0) } },
                    label = { Text("Höhe (px)") }, singleLine = true, colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { apply() }),
                    modifier = Modifier.weight(1f),
                )
            }
            Buttons(onCancel = { tool.dismissNumericInput() }, onApply = { apply() })
        }
    }
}

@Composable
private fun Buttons(onCancel: () -> Unit, onApply: () -> Unit) {
    val ui = LocalUi.current
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onCancel) { Text("Abbrechen", color = ui.textMuted) }
        TextButton(onApply) { Text("Übernehmen", color = ui.accent) }
    }
}
