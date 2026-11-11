package at.techgaisi.farbwerk.ui.adjust

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.adjust.AdjustmentKind
import at.techgaisi.farbwerk.adjust.AdjustmentTool
import at.techgaisi.farbwerk.adjust.Curves
import at.techgaisi.farbwerk.adjust.GradientPresets
import at.techgaisi.farbwerk.ui.selection.FloatingBar
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Bedienelemente der aktiven Anpassung (unten am Bildschirm), nur sichtbar wenn tool == ADJUST. */
@Composable
fun AdjustmentControls(state: EditorState, modifier: Modifier = Modifier) {
    val tool = state.adjustmentTool
    val kind = tool.kind ?: return
    val ui = LocalUi.current

    FloatingBar(modifier.widthIn(max = 720.dp).padding(horizontal = 4.dp)) {
        // ---------- Kopfzeile ----------
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(kind.label, color = ui.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            if (kind.pencil) {
                Segmented(listOf("Ebene", "Stift"), if (tool.pencilMode) 1 else 0) { tool.setPencil(it == 1) }
            }
            if (tool.busy) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ui.accent)
            }
            Spacer(Modifier.weight(1f))
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TextAction("Zurücksetzen") { tool.reset() }
            TextAction("Rückgängig", enabled = tool.historySize > 0) { tool.undoStep() }
            Spacer(Modifier.width(12.dp))
            TextAction("Abbrechen") { tool.cancelAndClose() }
            TextAction("Anwenden", primary = true) { tool.applyAndClose() }
        }

        // ---------- Inhalt ----------
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
            when (kind) {
                AdjustmentKind.CURVES -> CurvesEditor(tool)
                AdjustmentKind.GRADIENT_MAP -> {
                    GradientPicker(state, tool)
                    ParamSliders(tool, kind)
                }
                else -> ParamSliders(tool, kind)
            }
            if (tool.pencilMode) {
                LabeledSlider("Pinselgröße", "${tool.pencilSize.roundToInt()} %", tool.pencilSize, 1f, 100f,
                    onChange = { tool.pencilSize = it }, onDone = {})
                Hint("Mit dem Stift die Anpassung auf die Ebene malen.")
            }
            when {
                kind == AdjustmentKind.LIQUIFY -> Hint("Auf der Leinwand malen, um zu verformen. Zurücksetzen stellt alles wieder her.")
                kind == AdjustmentKind.CLONE -> Hint("Quellkreis ziehen, um die Quelle festzulegen – dann daneben malen.")
                !tool.pencilMode && kind.dragParam >= 0 -> Hint(
                    if (kind == AdjustmentKind.MOTION_BLUR) "Auf der Leinwand in Bewegungsrichtung ziehen."
                    else "Auf der Leinwand nach links/rechts ziehen, um die Stärke zu ändern." +
                        if (kind.hasCenter) " Kreis verschieben = Mittelpunkt." else ""
                )
            }
        }
    }
}

@Composable
private fun ParamSliders(tool: AdjustmentTool, kind: AdjustmentKind) {
    val params = kind.params
    for ((i, p) in params.withIndex()) {
        if (i >= tool.values.size) break
        if (p.group >= 0 && tool.values.getOrElse(p.groupOf) { 0f }.roundToInt() != p.group) continue
        val v = tool.values[i]
        if (p.choices != null) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.label, color = LocalUi.current.textMuted, fontSize = 12.sp, modifier = Modifier.width(96.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Segmented(p.choices, v.roundToInt()) { tool.setChoice(i, it) }
                }
            }
        } else {
            LabeledSlider(p.label, p.format(v), v, p.min, p.max,
                onChange = { tool.setValue(i, it) }, onDone = { tool.endValueChange() })
        }
    }
}

@Composable
private fun LabeledSlider(label: String, valueText: String, value: Float, min: Float, max: Float,
                          onChange: (Float) -> Unit, onDone: () -> Unit) {
    val ui = LocalUi.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = ui.textMuted, fontSize = 12.sp, modifier = Modifier.width(110.dp))
        Slider(value, onChange, Modifier.weight(1f).height(32.dp), valueRange = min..max, onValueChangeFinished = onDone)
        Text(valueText, color = ui.text, fontSize = 12.sp, modifier = Modifier.width(64.dp).padding(start = 8.dp))
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val ui = LocalUi.current
    Row(Modifier.clip(RoundedCornerShape(9.dp)).background(ui.panelElevated).padding(2.dp)) {
        options.forEachIndexed { i, s ->
            Box(
                Modifier.clip(RoundedCornerShape(7.dp))
                    .background(if (i == selected) ui.accent else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) { Text(s, color = if (i == selected) Color.White else ui.text, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun TextAction(label: String, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    val ui = LocalUi.current
    Box(
        Modifier.clip(RoundedCornerShape(9.dp))
            .background(if (primary) ui.accent else ui.panelElevated)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(label, fontSize = 13.sp,
            color = when { primary -> Color.White; enabled -> ui.text; else -> ui.textMuted.copy(alpha = 0.5f) })
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = LocalUi.current.textMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
}

// ===================================================================== Verlaufsumsetzung

@Composable
private fun GradientPicker(state: EditorState, tool: AdjustmentTool) {
    val ui = LocalUi.current
    val presets = GradientPresets.all(state.color)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEachIndexed { i, p ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { tool.setGradient(i) }) {
                Box(
                    Modifier.size(width = 76.dp, height = 22.dp).clip(RoundedCornerShape(6.dp))
                        .background(Brush.horizontalGradient(p.colors.map { Color(it) }))
                        .border(if (tool.gradientIndex == i) 2.dp else 0.5.dp,
                            if (tool.gradientIndex == i) ui.accent else ui.divider, RoundedCornerShape(6.dp))
                )
                Text(p.name, color = ui.textMuted, fontSize = 10.sp)
            }
        }
    }
}

// ===================================================================== Kurven

@Composable
private fun CurvesEditor(tool: AdjustmentTool) {
    val ui = LocalUi.current
    @Suppress("UNUSED_VARIABLE") val ver = tool.curvesVersion
    val channel = tool.curveChannel
    val names = listOf("Gamma", "Rot", "Grün", "Blau")
    val colors = listOf(Color.White, Color(0xFFFF5A5A), Color(0xFF4CD964), Color(0xFF4A90FF))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Segmented(names, channel) { tool.curveChannel = it }
        Spacer(Modifier.width(10.dp))
        Text("Tippen: Punkt hinzufügen · Punkt antippen: entfernen", color = ui.textMuted, fontSize = 10.sp)
    }
    Spacer(Modifier.height(6.dp))
    val pts = tool.curves.channels[channel]
    var dragIndex = -1
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.size(width = 300.dp, height = 170.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1A1A1E))
                .pointerInput(channel) {
                    detectTapGestures { pos ->
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        val hit = pts.indexOfFirst { hypot(it.x * w - pos.x, (1 - it.y) * h - pos.y) < 22.dp.toPx() }
                        tool.beginCurveEdit()
                        if (hit > 0 && hit < pts.size - 1) pts.removeAt(hit)
                        else if (hit < 0) {
                            pts.add(PointF((pos.x / w).coerceIn(0f, 1f), (1 - pos.y / h).coerceIn(0f, 1f)))
                            pts.sortBy { it.x }
                        }
                        tool.curvesChanged(false)
                    }
                }
                .pointerInput(channel) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            tool.beginCurveEdit()
                            dragIndex = pts.indices.minByOrNull { hypot(pts[it].x * w - pos.x, (1 - pts[it].y) * h - pos.y) } ?: -1
                            if (dragIndex >= 0 && hypot(pts[dragIndex].x * w - pos.x, (1 - pts[dragIndex].y) * h - pos.y) > 28.dp.toPx()) {
                                pts.add(PointF((pos.x / w).coerceIn(0f, 1f), (1 - pos.y / h).coerceIn(0f, 1f)))
                                pts.sortBy { it.x }
                                dragIndex = pts.indexOfFirst { it.x == (pos.x / w).coerceIn(0f, 1f) }
                            }
                        },
                        onDragEnd = { dragIndex = -1; tool.curvesChanged(false) },
                        onDragCancel = { dragIndex = -1; tool.curvesChanged(false) },
                        onDrag = { change, _ ->
                            change.consume()
                            if (dragIndex !in pts.indices) return@detectDragGestures
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            val p = pts[dragIndex]
                            val ny = (1 - change.position.y / h).coerceIn(0f, 1f)
                            val nx = when (dragIndex) {
                                0 -> 0f
                                pts.size - 1 -> 1f
                                else -> (change.position.x / w).coerceIn(pts[dragIndex - 1].x + 0.01f, pts[dragIndex + 1].x - 0.01f)
                            }
                            p.set(nx, ny)
                            tool.curvesChanged(true)
                        },
                    )
                }
        ) {
            val w = size.width; val h = size.height
            val grid = Color(0x33FFFFFF)
            for (i in 1..3) {
                drawLine(grid, Offset(w * i / 4, 0f), Offset(w * i / 4, h))
                drawLine(grid, Offset(0f, h * i / 4), Offset(w, h * i / 4))
            }
            drawLine(Color(0x22FFFFFF), Offset(0f, h), Offset(w, 0f))
            val lut = Curves.lut(pts)
            val path = Path()
            for (i in 0 until 256) {
                val x = i / 255f * w
                val y = (1 - lut[i] / 255f) * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, colors[channel], style = Stroke(width = 2.dp.toPx()))
            for (p in pts) {
                drawCircle(Color.White, 5.dp.toPx(), Offset(p.x * w, (1 - p.y) * h))
                drawCircle(colors[channel], 3.5.dp.toPx(), Offset(p.x * w, (1 - p.y) * h))
            }
        }
    }
}
