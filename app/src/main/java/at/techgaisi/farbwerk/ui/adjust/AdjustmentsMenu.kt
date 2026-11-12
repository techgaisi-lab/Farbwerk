package at.techgaisi.farbwerk.ui.adjust

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BlurCircular
import androidx.compose.material.icons.outlined.BlurLinear
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Details
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.Lens
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Tonality
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Water
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.adjust.AdjustmentKind
import at.techgaisi.farbwerk.model.Tool
import at.techgaisi.farbwerk.ui.common.PanelCard
import at.techgaisi.farbwerk.ui.theme.LocalUi

/** Popover-Panel "Anpassungen". [modifier] enthält bereits Größe/Position. */
@Composable
fun AdjustmentsMenu(state: EditorState, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    PanelCard(modifier, title = "Anpassungen") {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            var lastSection = -1
            for (k in AdjustmentKind.entries.filter { it.inMenu }) {
                if (lastSection >= 0 && k.section != lastSection) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).height(0.5.dp).background(ui.divider))
                }
                lastSection = k.section
                val active = state.tool == Tool.ADJUST && state.adjustmentTool.kind == k
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { startAdjustment(state, k) }
                        .background(if (active) ui.selectedRow.copy(alpha = 0.35f) else androidx.compose.ui.graphics.Color.Transparent)
                        .padding(horizontal = 16.dp)
                        .height(46.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(iconFor(k), null, tint = ui.icon, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(k.label, color = ui.text, fontSize = 15.sp)
                }
            }
        }
    }
}

/** Öffnet die Anpassung [k] (auch für Tastenkürzel und QuickMenu nutzbar, z. B. Umfärben). */
fun startAdjustment(state: EditorState, k: AdjustmentKind) = state.adjustmentTool.open(k)

private fun iconFor(k: AdjustmentKind): ImageVector = when (k) {
    AdjustmentKind.HSB -> Icons.Outlined.Tune
    AdjustmentKind.COLOR_BALANCE -> Icons.Outlined.Palette
    AdjustmentKind.CURVES -> Icons.Outlined.ShowChart
    AdjustmentKind.GRADIENT_MAP -> Icons.Outlined.Gradient
    AdjustmentKind.GAUSSIAN_BLUR -> Icons.Outlined.BlurOn
    AdjustmentKind.MOTION_BLUR -> Icons.Outlined.BlurLinear
    AdjustmentKind.PERSPECTIVE_BLUR -> Icons.Outlined.BlurCircular
    AdjustmentKind.NOISE -> Icons.Outlined.Grain
    AdjustmentKind.SHARPEN -> Icons.Outlined.Details
    AdjustmentKind.BLOOM -> Icons.Outlined.AutoAwesome
    AdjustmentKind.GLITCH -> Icons.Outlined.BrokenImage
    AdjustmentKind.HALFTONE -> Icons.Outlined.Tonality
    AdjustmentKind.CHROMATIC -> Icons.Outlined.Lens
    AdjustmentKind.LIQUIFY -> Icons.Outlined.Water
    AdjustmentKind.CLONE -> Icons.Outlined.ContentCopy
    AdjustmentKind.RECOLOR -> Icons.Outlined.Palette
}
