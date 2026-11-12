package at.techgaisi.farbwerk.ui.selection

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.selection.ClipboardOps
import at.techgaisi.farbwerk.selection.SavedSelection
import at.techgaisi.farbwerk.selection.SelectionMode
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val KEY_FEATHER = "feather"
private const val KEY_SAVED = "saved"

/**
 * Auswahl-Werkzeugleiste unten (nur wenn tool == SELECT): eine Zeile mit den Modi links
 * (Automatisch, Freihand, Rechteck, Ellipse) und den Optionen rechts. Weiche Kante und
 * Speichern & Laden öffnen Popover mit Pfeil zum Knopf. Der Schwellenwert der automatischen Auswahl
 * erscheint in der Statusleiste oben (state.statusBar).
 */
@Composable
fun SelectionToolbar(state: EditorState, modifier: Modifier = Modifier) {
    val tool = state.selectionTool
    @Suppress("UNUSED_VARIABLE") val rev = state.revision
    val hasSelection = state.document?.selection != null
    val anchors = remember { mutableStateMapOf<String, Float>() }
    val maxW = LocalConfiguration.current.screenWidthDp.dp - 24.dp

    // Farbfüllung: Farbwechsel färbt die zuletzt gefüllte Auswahl um (entprellt beim Ziehen im Farbkreis)
    val color = state.color
    LaunchedEffect(color) {
        delay(140)
        tool.onColorChanged(color)
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BarPopover(tool.featherDialogVisible, anchors[KEY_FEATHER] ?: Float.NaN, title = "Weiche Kante") {
            FeatherContent(state)
        }
        BarPopover(tool.savedPanelVisible, anchors[KEY_SAVED] ?: Float.NaN, title = "Speichern & Laden") {
            SavedSelectionsContent(state)
        }
        FloatingBar(Modifier.widthIn(max = maxW)) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                BarDivider()
                val auto = tool.mode == SelectionMode.AUTOMATIC
                BarButton(Icons.Outlined.AddCircleOutline, "Hinzufügen", active = !tool.subtract && !auto, enabled = !auto) {
                    tool.setCombine(false)
                }
                BarButton(Icons.Outlined.RemoveCircleOutline, "Entfernen", active = tool.subtract) {
                    tool.setCombine(!tool.subtract)
                }
                BarButton(Icons.Outlined.InvertColors, "Umkehren", enabled = hasSelection) { tool.invert() }
                BarButton(Icons.Outlined.ContentCopy, "Kopieren &\nEinfügen", enabled = hasSelection) {
                    ClipboardOps.duplicate(state)
                }
                BarButton(
                    Icons.Outlined.BlurOn, "Weiche\nKante", active = tool.featherDialogVisible,
                    enabled = hasSelection || tool.featherDialogVisible,
                    modifier = Modifier.barAnchor(anchors, KEY_FEATHER),
                ) {
                    if (tool.featherDialogVisible) tool.endFeather() else tool.beginFeather()
                }
                BarButton(
                    Icons.Outlined.Bookmarks, "Speichern\n& Laden", active = tool.savedPanelVisible,
                    modifier = Modifier.barAnchor(anchors, KEY_SAVED),
                ) {
                    if (tool.featherDialogVisible) tool.endFeather()
                    tool.savedPanelVisible = !tool.savedPanelVisible
                }
                BarButton(Icons.Outlined.FormatColorFill, "Farbfüllung", active = tool.colorFill) { tool.toggleColorFill() }
                BarButton(Icons.Outlined.Deselect, "Löschen", enabled = hasSelection) {
                    if (tool.featherDialogVisible) tool.endFeather()
                    tool.clear()
                }
            }
        }
    }
}

/** Weiche Kante: Regler 0–100 % mit Live-Vorschau; Schließen übernimmt als ein Schritt. */
@Composable
private fun FeatherContent(state: EditorState) {
    val tool = state.selectionTool
    val ui = LocalUi.current
    Column(Modifier.width(300.dp).padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Stärke", color = ui.textMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("${(tool.featherAmount * 100).roundToInt()} %", color = ui.text, fontSize = 13.sp)
        }
        Slider(
            value = tool.featherAmount,
            onValueChange = { tool.setFeatherLive(it) },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = ui.accent),
        )
    }
}

/** Gespeicherte Auswahlen: „+“ speichert, Antippen lädt, nach links wischen zeigt „Löschen“. */
@Composable
private fun SavedSelectionsContent(state: EditorState) {
    val tool = state.selectionTool
    val ui = LocalUi.current
    Column(
        Modifier.width(300.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState())
            .padding(bottom = 8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { tool.saveCurrent() }.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, ui.divider, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Add, null, tint = ui.accent) }
            Spacer(Modifier.width(12.dp))
            Text("Aktuelle Auswahl speichern", color = ui.accent, fontSize = 15.sp)
        }
        if (tool.savedSelections.isEmpty()) {
            Text("Noch keine gespeicherten Auswahlen", color = ui.textMuted, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        for (s in tool.savedSelections.asReversed()) {
            SavedRow(state, s)
        }
    }
}

@Composable
private fun SavedRow(state: EditorState, s: SavedSelection) {
    val tool = state.selectionTool
    val ui = LocalUi.current
    val density = LocalDensity.current
    val revealPx = with(density) { 88.dp.toPx() }
    val offset = remember(s.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxWidth().height(60.dp)) {
        // Löschen-Fläche hinter der Zeile
        Box(
            Modifier.align(Alignment.CenterEnd).width(88.dp).fillMaxHeight().background(Color(0xFFFF453A))
                .clickable { tool.deleteSaved(s) },
            contentAlignment = Alignment.Center,
        ) { Text("Löschen", color = Color.White, fontSize = 14.sp) }
        Row(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .fillMaxWidth().fillMaxHeight()
                .background(ui.panel)
                .pointerInput(s.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                offset.animateTo(if (offset.value < -revealPx / 2f) -revealPx else 0f,
                                    spring(dampingRatio = 0.85f, stiffness = 600f))
                            }
                        },
                    ) { _, dx ->
                        scope.launch { offset.snapTo((offset.value + dx).coerceIn(-revealPx, 0f)) }
                    }
                }
                .clickable {
                    if (offset.value < -1f) scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.85f)) }
                    else tool.load(s)
                }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                s.thumbnail, null,
                Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(ui.panelElevated)
            )
            Spacer(Modifier.width(12.dp))
            Text(s.name, color = ui.text, fontSize = 15.sp)
        }
    }
}
