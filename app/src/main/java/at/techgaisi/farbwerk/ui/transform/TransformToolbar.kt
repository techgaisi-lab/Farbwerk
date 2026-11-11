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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Transform
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.transform.Interpolation
import at.techgaisi.farbwerk.transform.TransformMode
import at.techgaisi.farbwerk.ui.common.PanelCard
import at.techgaisi.farbwerk.ui.selection.BarButton
import at.techgaisi.farbwerk.ui.selection.BarDivider
import at.techgaisi.farbwerk.ui.selection.FloatingBar
import at.techgaisi.farbwerk.ui.selection.ModeTab
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlin.math.roundToInt

/** Transform-Werkzeugleiste unten (nur wenn tool == TRANSFORM). */
@Composable
fun TransformToolbar(state: EditorState, modifier: Modifier = Modifier) {
    val tool = state.transformTool
    @Suppress("UNUSED_VARIABLE") val rev = state.revision
    var snapOpen by remember { mutableStateOf(false) }
    var interpOpen by remember { mutableStateOf(false) }
    val enabled = tool.active

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (snapOpen) {
            SnapPanel(state)
            Spacer(Modifier.height(8.dp))
        }
        if (interpOpen) {
            InterpolationPanel(state) { interpOpen = false }
            Spacer(Modifier.height(8.dp))
        }
        FloatingBar(Modifier.widthIn(max = 860.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                ModeTab("Freiform", tool.mode == TransformMode.FREEFORM, Icons.Outlined.CropFree) {
                    tool.changeMode(TransformMode.FREEFORM)
                }
                ModeTab("Gleichmäßig", tool.mode == TransformMode.UNIFORM, Icons.Outlined.AspectRatio) {
                    tool.changeMode(TransformMode.UNIFORM)
                }
                ModeTab("Verzerren", tool.mode == TransformMode.DISTORT, Icons.Outlined.Transform) {
                    tool.changeMode(TransformMode.DISTORT)
                }
                ModeTab("Verkrümmen", tool.mode == TransformMode.WARP, Icons.Outlined.GridOn) {
                    tool.changeMode(TransformMode.WARP)
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarButton(Icons.Outlined.Straighten, "Einrasten", active = snapOpen || tool.magnetic || tool.snapping) {
                    snapOpen = !snapOpen; interpOpen = false
                }
                BarDivider()
                BarButton(Icons.Outlined.Flip, "Horizontal\nspiegeln", enabled = enabled) { tool.flipHorizontal() }
                VerticalFlipButton(enabled) { tool.flipVertical() }
                BarButton(Icons.Outlined.RotateRight, "45° drehen", enabled = enabled) { tool.rotate45() }
                BarButton(Icons.Outlined.FitScreen, "An Bildschirm\nanpassen", enabled = enabled) { tool.fitToCanvas() }
                BarButton(Icons.Outlined.Grain, "Interpolation:\n${tool.interpolation.label}", active = interpOpen) {
                    interpOpen = !interpOpen; snapOpen = false
                }
                BarDivider()
                BarButton(Icons.Outlined.RestartAlt, "Zurücksetzen", enabled = enabled) { tool.reset() }
            }
        }
    }
}

@Composable
private fun VerticalFlipButton(enabled: Boolean, onClick: () -> Unit) {
    val ui = LocalUi.current
    Column(
        Modifier.widthIn(min = 60.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Flip, "Vertikal spiegeln",
            tint = if (enabled) ui.icon else ui.iconMuted.copy(alpha = 0.5f),
            modifier = Modifier.rotate(90f).padding(1.dp))
        Spacer(Modifier.height(2.dp))
        Text("Vertikal\nspiegeln", color = ui.textMuted, fontSize = 10.sp, lineHeight = 11.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun SnapPanel(state: EditorState) {
    val tool = state.transformTool
    val ui = LocalUi.current
    PanelCard(Modifier.width(300.dp), title = "Einrasten") {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.OpenWith, null, tint = ui.icon)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Magnetisch", color = ui.text, fontSize = 14.sp)
                    Text("45°-Achsen, 15°-Drehschritte, 5-%-Skalierung", color = ui.textMuted, fontSize = 11.sp)
                }
                Switch(tool.magnetic, { tool.magnetic = it })
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.GridOn, null, tint = ui.icon)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Einrasten", color = ui.text, fontSize = 14.sp)
                    Text("An Mitte und Rändern der Leinwand", color = ui.textMuted, fontSize = 11.sp)
                }
                Switch(tool.snapping, { tool.snapping = it })
            }
            Spacer(Modifier.height(8.dp))
            Text("Abstand: ${tool.snapDistance.roundToInt()} pt", color = ui.textMuted, fontSize = 12.sp)
            Slider(tool.snapDistance, { tool.snapDistance = it }, valueRange = 4f..40f, enabled = tool.snapping)
        }
    }
}

@Composable
private fun InterpolationPanel(state: EditorState, onDone: () -> Unit) {
    val tool = state.transformTool
    val ui = LocalUi.current
    PanelCard(Modifier.width(260.dp), title = "Interpolation") {
        Column(Modifier.padding(bottom = 8.dp)) {
            for (i in Interpolation.entries) {
                Row(
                    Modifier.fillMaxWidth().clickable { tool.changeInterpolation(i); onDone() }
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(i.label, color = ui.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (tool.interpolation == i) Icon(Icons.Outlined.Check, null, tint = ui.accent)
                }
            }
        }
    }
}
