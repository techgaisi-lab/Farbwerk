package at.techgaisi.farbwerk.ui.actions

import android.text.Layout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.io.Importers
import at.techgaisi.farbwerk.model.GuideKind
import at.techgaisi.farbwerk.model.Panel
import at.techgaisi.farbwerk.model.SymmetryMode
import at.techgaisi.farbwerk.ui.theme.LocalUi
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// =====================================================================================
// Text hinzufügen
// =====================================================================================

@Composable
fun TextDialog(state: EditorState, onDismiss: () -> Unit) {
    val ui = LocalUi.current
    val doc = state.document ?: return
    var text by remember { mutableStateOf("") }
    var font by remember { mutableStateOf(CanvasOps.FontChoice.SANS) }
    var size by remember { mutableFloatStateOf((doc.height / 12f).coerceIn(24f, 400f)) }
    var bold by remember { mutableStateOf(false) }
    var italic by remember { mutableStateOf(false) }
    var align by remember { mutableStateOf(Layout.Alignment.ALIGN_CENTER) }

    FwDialog("Text hinzufügen", onDismiss, confirmLabel = "Hinzufügen", confirmEnabled = text.isNotBlank(),
        onConfirm = {
            CanvasOps.addTextLayer(state, text, font, size, state.color, bold, italic, align)
            onDismiss(); state.openPanel = Panel.NONE
        }) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, label = { Text("Text") },
                minLines = 2, maxLines = 6,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = ui.text, unfocusedTextColor = ui.text, focusedBorderColor = ui.accent,
                    unfocusedBorderColor = ui.divider, focusedLabelColor = ui.accent, unfocusedLabelColor = ui.textMuted,
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Text("Schriftart", color = ui.textMuted, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Segmented(CanvasOps.FontChoice.entries.map { it to it.label }, font, Modifier.fillMaxWidth()) { font = it }
            Spacer(Modifier.height(10.dp))
            Segmented(listOf(Layout.Alignment.ALIGN_NORMAL to "Links", Layout.Alignment.ALIGN_CENTER to "Mitte",
                Layout.Alignment.ALIGN_OPPOSITE to "Rechts"), align, Modifier.fillMaxWidth()) { align = it }
        }
        SliderRow("Größe", size, 8f..800f, { "${it.roundToInt()} px" }, onChange = { size = it })
        SwitchRow("Fett", bold) { bold = it }
        SwitchRow("Kursiv", italic) { italic = it }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(Color(state.color)).border(1.dp, ui.divider, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text("Aktuelle Farbe wird verwendet", color = ui.textMuted, fontSize = 13.sp)
        }
    }
}

// =====================================================================================
// Zuschneiden & Größe ändern
// =====================================================================================

@Composable
fun ResizeDialog(state: EditorState, onDismiss: () -> Unit) {
    val ui = LocalUi.current
    val doc = state.document ?: return
    var wText by remember { mutableStateOf(doc.width.toString()) }
    var hText by remember { mutableStateOf(doc.height.toString()) }
    var keepRatio by remember { mutableStateOf(false) }
    var scale by remember { mutableStateOf(false) }
    var ax by remember { mutableIntStateOf(1) }
    var ay by remember { mutableIntStateOf(1) }
    val ratio = doc.width / doc.height.toFloat()

    val w = wText.toIntOrNull() ?: 0
    val h = hText.toIntOrNull() ?: 0
    val px = w.toLong() * h
    val error = when {
        w < 1 || h < 1 -> "Ungültige Größe"
        w > Importers.MAX_SIDE || h > Importers.MAX_SIDE -> "Maximal ${Importers.MAX_SIDE} px je Seite"
        px > Importers.MAX_CANVAS_PIXELS -> "Leinwand zu groß (max. 25 Megapixel)"
        else -> null
    }

    FwDialog("Zuschneiden & Größe ändern", onDismiss, confirmLabel = "Anwenden", confirmEnabled = error == null,
        onConfirm = {
            CanvasOps.resizeCanvas(state, w, h, ax, ay, scale)
            onDismiss(); state.openPanel = Panel.NONE
        }) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FwTextField(wText, { v ->
                    wText = v.filter(Char::isDigit).take(5)
                    if (keepRatio) wText.toIntOrNull()?.let { hText = (it / ratio).roundToInt().coerceAtLeast(1).toString() }
                }, "Breite (px)", Modifier.weight(1f), number = true)
                FwTextField(hText, { v ->
                    hText = v.filter(Char::isDigit).take(5)
                    if (keepRatio) hText.toIntOrNull()?.let { wText = (it * ratio).roundToInt().coerceAtLeast(1).toString() }
                }, "Höhe (px)", Modifier.weight(1f), number = true)
            }
            Spacer(Modifier.height(6.dp))
            Text("Aktuell: ${doc.width} × ${doc.height} px", color = ui.textMuted, fontSize = 12.sp)
            error?.let { Text(it, color = Color(0xFFFF5A52), fontSize = 12.sp) }
        }
        SwitchRow("Seitenverhältnis beibehalten", keepRatio) { keepRatio = it }
        SwitchRow("Inhalt skalieren", scale, subtitle = if (scale) "Bild wird neu berechnet" else "Leinwand wird zugeschnitten bzw. erweitert") { scale = it }
        if (!scale) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ankerpunkt", color = ui.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (y in 0..2) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (x in 0..2) {
                            val sel = x == ax && y == ay
                            Box(
                                Modifier.size(26.dp).clip(RoundedCornerShape(5.dp))
                                    .background(if (sel) ui.accent else ui.panelElevated)
                                    .clickable { ax = x; ay = y },
                                contentAlignment = Alignment.Center
                            ) { Box(Modifier.size(6.dp).clip(CircleShape).background(if (sel) Color.White else ui.iconMuted)) }
                        }
                    }
                }
            }
        }
        Text(
            "Hinweis: Dabei wird der Verlauf (Rückgängig) geleert.",
            color = ui.textMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
        )
    }
}

// =====================================================================================
// Zeichenhilfe bearbeiten
// =====================================================================================

private val guideColors = listOf(
    0xFF3FA9F5.toInt(), 0xFFFF5A52.toInt(), 0xFF34C759.toInt(), 0xFFFFCC00.toInt(),
    0xFFAF52DE.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(),
)

@Composable
fun GuidesDialog(state: EditorState, onDismiss: () -> Unit) {
    val ui = LocalUi.current
    val doc = state.document ?: return
    val g = doc.guides
    // lokaler Zähler, damit die Steuerelemente nach jeder Änderung neu zeichnen
    var tick by remember { mutableIntStateOf(0) }
    fun changed() { tick++; state.invalidate() }
    @Suppress("UNUSED_VARIABLE") val observed = tick
    // Beim ersten Öffnen ohne Hilfe: 2D-Raster als Startpunkt
    LaunchedEffect(Unit) {
        if (g.kind == GuideKind.NONE) { g.kind = GuideKind.GRID_2D; g.visible = true; changed() }
    }

    FwDialog("Zeichenhilfe bearbeiten", onDismiss, confirmLabel = "Fertig", onConfirm = onDismiss,
        dismissLabel = "Schließen") {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Segmented(listOf(GuideKind.GRID_2D to "2D-Raster", GuideKind.ISOMETRIC to "Isometrisch",
                GuideKind.SYMMETRY to "Symmetrie"), g.kind, Modifier.fillMaxWidth()) {
                g.kind = it; g.visible = true; changed()
            }
        }
        SwitchRow("Anzeigen", g.visible) { g.visible = it; changed() }
        if (g.kind == GuideKind.GRID_2D || g.kind == GuideKind.ISOMETRIC) {
            SliderRow("Rastergröße", g.gridSize, 10f..1000f, { "${it.roundToInt()} px" },
                onChange = { g.gridSize = it.roundToInt().toFloat(); changed() })
        }
        if (g.kind == GuideKind.SYMMETRY) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Segmented(listOf(SymmetryMode.VERTICAL to "Vertikal", SymmetryMode.HORIZONTAL to "Horizontal",
                    SymmetryMode.QUADRANT to "Quadrant", SymmetryMode.RADIAL to "Radial"), g.symmetry, Modifier.fillMaxWidth()) {
                    g.symmetry = it; changed()
                }
            }
            if (g.symmetry == SymmetryMode.RADIAL) {
                SliderRow("Segmente", g.radialSegments.toFloat(), 2f..16f, { it.roundToInt().toString() }, steps = 13,
                    onChange = { g.radialSegments = it.roundToInt(); changed() })
            }
            SwitchRow("Rotationssymmetrie", g.rotationalSymmetry, subtitle = "Drehen statt spiegeln") {
                g.rotationalSymmetry = it; changed()
            }
            SwitchRow("Zeichenassistent", g.assisted, subtitle = "Striche werden automatisch übertragen") {
                g.assisted = it; changed()
            }
            ActionRow("Zentrum zurücksetzen", enabled = !g.symmetryCenterX.isNaN() || !g.symmetryCenterY.isNaN()) {
                g.symmetryCenterX = Float.NaN; g.symmetryCenterY = Float.NaN; changed()
            }
        }
        FwDivider()
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Farbe", color = ui.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            guideColors.forEach { c ->
                val sel = (g.color or (0xFF shl 24)) == c
                Box(
                    Modifier.padding(start = 6.dp).size(24.dp).clip(CircleShape).background(Color(c))
                        .border(if (sel) 2.dp else 1.dp, if (sel) ui.accent else ui.divider, CircleShape)
                        .clickable { g.color = c; changed() }
                )
            }
        }
        SliderRow("Deckkraft", g.opacity, 0.05f..1f, { "${(it * 100).roundToInt()} %" },
            onChange = { g.opacity = it; changed() })
        Spacer(Modifier.height(6.dp))
    }
}

// =====================================================================================
// Leinwandinformationen
// =====================================================================================

@Composable
fun CanvasInfoDialog(state: EditorState, onDismiss: () -> Unit) {
    val ui = LocalUi.current
    val doc = state.document ?: return
    var name by remember { mutableStateOf(doc.name) }
    var dpiText by remember { mutableStateOf(doc.dpi.toString()) }
    val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.GERMANY)
    val dpi = dpiText.toIntOrNull()?.coerceIn(1, 4800) ?: doc.dpi
    val cmW = doc.width / dpi.toFloat() * 2.54f
    val cmH = doc.height / dpi.toFloat() * 2.54f
    val memMb = doc.layers.size.toLong() * doc.width * doc.height * 4 / (1024 * 1024)

    FwDialog("Leinwandinformationen", onDismiss, confirmLabel = "Übernehmen", onConfirm = {
        doc.name = name.trim().ifEmpty { "Ohne Titel" }
        doc.dpi = dpi
        state.markDirty(); state.invalidate(); onDismiss()
    }) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            FwTextField(name, { name = it }, "Name", Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            FwTextField(dpiText, { dpiText = it.filter(Char::isDigit).take(4) }, "DPI", Modifier.fillMaxWidth(), number = true)
            Spacer(Modifier.height(8.dp))
        }
        InfoLine("Abmessungen", "${doc.width} × ${doc.height} px")
        InfoLine("Druckgröße", String.format(Locale.GERMANY, "%.1f × %.1f cm", cmW, cmH))
        InfoLine("Ebenen", "${doc.layers.size} von max. ${CanvasOps.maxLayers(state.context, doc.width, doc.height)}")
        InfoLine("Arbeitsspeicher (Ebenen)", "$memMb MB")
        InfoLine("Farbprofil", "sRGB")
        InfoLine("Hintergrund", if (doc.backgroundVisible) String.format("#%06X", doc.backgroundColor and 0xFFFFFF) else "Transparent")
        InfoLine("Erstellt", df.format(Date(doc.created)))
        InfoLine("Zuletzt geändert", df.format(Date(doc.modified)))
        InfoLine("Zeitspanne bis zur letzten Änderung", formatDuration(doc.modified - doc.created))
        Spacer(Modifier.heightIn(min = 6.dp))
    }
}

private fun formatDuration(ms: Long): String {
    val min = (ms / 60000).coerceAtLeast(0)
    return when {
        min < 60 -> "$min min"
        min < 60 * 48 -> "${min / 60} h ${min % 60} min"
        else -> "${min / (60 * 24)} Tage"
    }
}

@Composable
private fun InfoLine(k: String, v: String) {
    val ui = LocalUi.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp)) {
        Text(k, color = ui.textMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(v, color = ui.text, fontSize = 13.sp)
    }
}
