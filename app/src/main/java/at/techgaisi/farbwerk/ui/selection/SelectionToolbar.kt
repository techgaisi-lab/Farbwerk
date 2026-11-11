package at.techgaisi.farbwerk.ui.selection

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.selection.ClipboardOps
import at.techgaisi.farbwerk.selection.SelectionMode
import at.techgaisi.farbwerk.ui.common.PanelCard
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlin.math.roundToInt

/** Auswahl-Werkzeugleiste unten (nur wenn tool == SELECT). */
@Composable
fun SelectionToolbar(state: EditorState, modifier: Modifier = Modifier) {
    val tool = state.selectionTool
    @Suppress("UNUSED_VARIABLE") val rev = state.revision
    val hasSelection = state.document?.selection != null

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (tool.thresholdVisible) {
            InfoPill("Schwellenwert: ${(tool.threshold * 100).roundToInt()} %")
            Spacer(Modifier.height(8.dp))
        }
        if (tool.savedPanelVisible) {
            SavedSelectionsPanel(state)
            Spacer(Modifier.height(8.dp))
        }
        FloatingBar(Modifier.widthIn(max = 760.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                ModeTab("Automatisch", tool.mode == SelectionMode.AUTOMATIC, Icons.Outlined.AutoFixHigh) {
                    tool.changeMode(SelectionMode.AUTOMATIC)
                }
                ModeTab("Freihand", tool.mode == SelectionMode.FREEHAND, Icons.Outlined.Gesture) {
                    tool.changeMode(SelectionMode.FREEHAND)
                }
                ModeTab("Rechteck", tool.mode == SelectionMode.RECTANGLE, Icons.Outlined.CropSquare) {
                    tool.changeMode(SelectionMode.RECTANGLE)
                }
                ModeTab("Ellipse", tool.mode == SelectionMode.ELLIPSE, Icons.Outlined.RadioButtonUnchecked) {
                    tool.changeMode(SelectionMode.ELLIPSE)
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarButton(Icons.Outlined.AddCircleOutline, "Hinzufügen", active = !tool.subtract) { tool.subtract = false }
                BarButton(Icons.Outlined.RemoveCircleOutline, "Entfernen", active = tool.subtract) { tool.subtract = true }
                BarButton(Icons.Outlined.InvertColors, "Umkehren") { tool.invert() }
                BarButton(Icons.Outlined.ContentCopy, "Kopieren &\nEinfügen", enabled = hasSelection) {
                    ClipboardOps.duplicate(state)
                }
                BarButton(Icons.Outlined.BlurOn, "Weiche\nKante", enabled = hasSelection) {
                    tool.featherDialogVisible = true
                }
                BarButton(Icons.Outlined.Bookmarks, "Speichern\n& Laden", active = tool.savedPanelVisible) {
                    tool.savedPanelVisible = !tool.savedPanelVisible
                }
                BarButton(Icons.Outlined.FormatColorFill, "Farbfüllung", active = tool.colorFill) { tool.toggleColorFill() }
                BarButton(Icons.Outlined.Deselect, "Löschen", enabled = hasSelection) { tool.clear() }
            }
        }
    }

    if (tool.featherDialogVisible) FeatherDialog(state)
}

@Composable
private fun FeatherDialog(state: EditorState) {
    val tool = state.selectionTool
    val ui = LocalUi.current
    var amount by remember { mutableFloatStateOf(0.1f) }
    AlertDialog(
        onDismissRequest = { tool.featherDialogVisible = false },
        containerColor = ui.panel,
        title = { Text("Weiche Kante", color = ui.text) },
        text = {
            Column {
                Text("Stärke: ${(amount * 100).roundToInt()} %", color = ui.textMuted, fontSize = 13.sp)
                Slider(value = amount, onValueChange = { amount = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                tool.featherDialogVisible = false
                tool.applyFeather(amount)
            }) { Text("Anwenden") }
        },
        dismissButton = {
            TextButton(onClick = { tool.featherDialogVisible = false }) { Text("Abbrechen") }
        },
    )
}

@Composable
private fun SavedSelectionsPanel(state: EditorState) {
    val tool = state.selectionTool
    val ui = LocalUi.current
    PanelCard(Modifier.widthIn(min = 260.dp, max = 560.dp), title = "Gespeicherte Auswahlen") {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(10.dp))
                    .border(1.dp, ui.divider, RoundedCornerShape(10.dp))
                    .clickable { tool.saveCurrent() },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Add, "Speichern", tint = ui.icon)
                    Text("Speichern", color = ui.textMuted, fontSize = 10.sp)
                }
            }
            if (tool.savedSelections.isEmpty()) {
                Text("Noch keine gespeicherten Auswahlen", color = ui.textMuted, fontSize = 12.sp,
                    modifier = Modifier.width(180.dp))
            }
            for (s in tool.savedSelections) {
                Box(Modifier.size(72.dp)) {
                    Image(
                        s.thumbnail, "Auswahl laden",
                        Modifier.size(72.dp).clip(RoundedCornerShape(10.dp))
                            .background(ui.panelElevated)
                            .clickable { tool.load(s) }
                    )
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp)
                            .clip(RoundedCornerShape(10.dp)).background(ui.panel)
                            .clickable { tool.deleteSaved(s) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Close, "Löschen", tint = ui.icon, modifier = Modifier.size(14.dp)) }
                }
            }
        }
    }
}
