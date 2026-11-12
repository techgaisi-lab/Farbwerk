package at.techgaisi.farbwerk.ui.adjust

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.adjust.AdjustmentKind
import at.techgaisi.farbwerk.adjust.AdjustmentTool
import at.techgaisi.farbwerk.adjust.Curves
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Bedienelemente der aktiven Anpassung, sichtbar wenn tool == ADJUST:
 *  - untere Karte nur mit den Zusatzreglern (Wisch-Anpassungen ohne Zusatzregler: keine Karte),
 *  - Aktionsmenü nach Tippen auf die Leinwand (Vorschau, Anwenden, Zurücksetzen, Rückgängig, Abbrechen),
 *  - Modus-Wähler Ebene/Stift unter der Statusleiste (öffnet sich über die Aktion der Statusleiste).
 * Der Stärke-Wert der Wisch-Anpassungen steht in der Statusleiste oben ([EditorState.statusBar]).
 */
@Composable
fun AdjustmentControls(state: EditorState, modifier: Modifier = Modifier) {
    val tool = state.adjustmentTool
    val kind = tool.kind ?: return

    TapActionMenu(tool)
    ModeMenu(tool)
    if (kind == AdjustmentKind.RECOLOR) LaunchedEffect(state.color) { tool.colorChanged() }

    when (kind) {
        AdjustmentKind.HSB -> AdjustPanel(modifier) { BusyRow(tool); HsbSliders(tool) }
        AdjustmentKind.COLOR_BALANCE -> AdjustPanel(modifier) { BusyRow(tool); ColorBalanceSliders(tool) }
        AdjustmentKind.CURVES -> AdjustPanel(modifier) { BusyRow(tool); CurvesEditor(tool) }
        AdjustmentKind.GRADIENT_MAP -> AdjustPanel(modifier) {
            BusyRow(tool)
            val lib = tool.gradients
            @Suppress("UNUSED_VARIABLE") val v = lib.version
            val def = lib.items.getOrNull(tool.gradientIndex)
            if (tool.gradientEditorOpen && def != null) GradientEditor(tool, def)
            else GradientLibraryRow(tool)
            ParamRows(tool, kind, skip = -1)
        }
        AdjustmentKind.LIQUIFY -> AdjustPanel(modifier) { LiquifyPanel(tool) }
        AdjustmentKind.CLONE -> AdjustPanel(modifier) { ClonePanel(tool) }
        AdjustmentKind.RECOLOR -> AdjustPanel(modifier) { RecolorPanel(state, tool) }
        else -> {
            // Wisch-Anpassungen: nur Zusatzregler; im Stift-Modus auch die Stärke (Ziehen malt dann)
            val skip = if (tool.pencilMode) -1 else kind.dragParam
            if (hasVisibleParams(tool, kind, skip)) {
                AdjustPanel(modifier) { BusyRow(tool); ParamRows(tool, kind, skip) }
            }
        }
    }
}

private fun visible(tool: AdjustmentTool, kind: AdjustmentKind, i: Int, skip: Int): Boolean {
    val p = kind.params[i]
    if (i == skip || p.hidden || i >= tool.values.size) return false
    return p.group < 0 || tool.values.getOrElse(p.groupOf) { 0f }.roundToInt() == p.group
}

private fun hasVisibleParams(tool: AdjustmentTool, kind: AdjustmentKind, skip: Int): Boolean =
    kind.params.indices.any { visible(tool, kind, it, skip) }

/** Fortschrittsanzeige, während die volle Auflösung berechnet wird (ohne Platzbedarf sonst). */
@Composable
private fun BusyRow(tool: AdjustmentTool) {
    if (!tool.busy) return
    Box(Modifier.fillMaxWidth().height(0.dp)) {
        CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(top = 0.dp).size(14.dp),
            strokeWidth = 2.dp, color = LocalUi.current.accent)
    }
}

/** Auswahl-Parameter als Segmente, Zahlen-Parameter als Regler (zwei Spalten auf breiten Bildschirmen). */
@Composable
private fun ParamRows(tool: AdjustmentTool, kind: AdjustmentKind, skip: Int) {
    val ui = LocalUi.current
    val idx = kind.params.indices.filter { visible(tool, kind, it, skip) }
    val choices = idx.filter { kind.params[it].choices != null }
    val sliders = idx.filter { kind.params[it].choices == null }
    if (choices.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in choices) {
                val p = kind.params[i]
                Text(p.label, color = ui.textMuted, fontSize = 13.sp)
                Segmented(p.choices!!, tool.values[i].roundToInt()) { tool.setChoice(i, it) }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = if (maxWidth > 460.dp && sliders.size > 1) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (row in sliders.chunked(cols)) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    for (i in row) {
                        ParamSlider(kind.params[i], tool.values[i], Modifier.weight(1f),
                            onChange = { tool.setValue(i, it) }, onDone = { tool.endValueChange() })
                    }
                    if (row.size < cols) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

// ===================================================================== Aktionsmenü

@Composable
private fun TapActionMenu(tool: AdjustmentTool) {
    AnchoredPopover(tool.tapMenuAt, onDismiss = { tool.dismissTapMenu() }) {
        PopoverMenuCard {
            // Vorschau: Tippen schaltet um, Halten zeigt das Original, solange gehalten wird
            val ui = LocalUi.current
            Row(
                Modifier.fillMaxWidth().height(44.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            val before = tool.showingOriginal
                            val t0 = System.currentTimeMillis()
                            tool.setShowOriginal(!before)
                            tryAwaitRelease()
                            if (System.currentTimeMillis() - t0 > 350) tool.setShowOriginal(before)
                        })
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Vorschau", fontSize = 15.sp, color = ui.text, modifier = Modifier.weight(1f))
                Text(if (tool.showingOriginal) "Original" else "Ergebnis", fontSize = 13.sp,
                    color = if (tool.showingOriginal) ui.accent else ui.textMuted)
            }
            MenuDivider()
            MenuRow("Anwenden") { tool.dismissTapMenu(); tool.applyAndStay() }
            MenuDivider()
            MenuRow("Zurücksetzen") { tool.reset() }
            MenuDivider()
            MenuRow("Rückgängig", enabled = tool.historySize > 0) { tool.undoStep() }
            MenuDivider()
            MenuRow("Abbrechen", destructive = true) { tool.dismissTapMenu(); tool.cancelAndClose() }
        }
    }
}

/** Modus Ebene/Stift – öffnet sich unter der Statusleiste (Aktion „Ebene ▾“). */
@Composable
private fun ModeMenu(tool: AdjustmentTool) {
    val d = LocalDensity.current
    val top = WindowInsets.statusBars.getTop(d)
    val anchor = if (tool.modeMenuOpen) Offset(-1f, top + with(d) { 86.dp.toPx() }) else null
    AnchoredPopover(anchor, onDismiss = { tool.modeMenuOpen = false }, gap = 6.dp) {
        PopoverMenuCard(width = 200.dp) {
            MenuRow("Ebene", checked = !tool.pencilMode) { tool.setPencil(false) }
            MenuDivider()
            MenuRow("Stift", checked = tool.pencilMode) { tool.setPencil(true) }
        }
    }
}

// ===================================================================== Farbanpassungen

private val HUE_TRACK = Brush.horizontalGradient(
    listOf(Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
        Color(0xFFFFFF00), Color(0xFF00FF00), Color(0xFF00FFFF))
)

@Composable
private fun HsbSliders(tool: AdjustmentTool) {
    val k = AdjustmentKind.HSB
    val tracks = listOf(
        HUE_TRACK,
        Brush.horizontalGradient(listOf(Color(0xFF808080), Color(0xFFE5402F))),
        Brush.horizontalGradient(listOf(Color.Black, Color.White)),
    )
    for (i in 0 until 3) {
        if (i >= tool.values.size) break
        ParamSlider(k.params[i], tool.values[i], Modifier.fillMaxWidth(), track = tracks[i],
            onChange = { tool.setValue(i, it) }, onDone = { tool.endValueChange() })
    }
}

@Composable
private fun ColorBalanceSliders(tool: AdjustmentTool) {
    val k = AdjustmentKind.COLOR_BALANCE
    if (tool.values.isEmpty()) return
    val range = tool.values[0].roundToInt()
    Segmented(AdjustmentKind.COLOR_BALANCE_RANGES, range) { tool.setChoice(0, it) }
    val tracks = listOf(
        Brush.horizontalGradient(listOf(Color(0xFF00C8E6), Color(0xFFE63232))),
        Brush.horizontalGradient(listOf(Color(0xFFE632C8), Color(0xFF32C850))),
        Brush.horizontalGradient(listOf(Color(0xFFF0DC32), Color(0xFF3264F0))),
    )
    for (j in 0 until 3) {
        val i = 1 + range * 3 + j
        if (i >= tool.values.size) break
        ParamSlider(k.params[i], tool.values[i], Modifier.fillMaxWidth(), track = tracks[j],
            onChange = { tool.setValue(i, it) }, onDone = { tool.endValueChange() })
    }
}

// ===================================================================== Verflüssigen / Klonen / Umfärben

@Composable
private fun LiquifyPanel(tool: AdjustmentTool) {
    val k = AdjustmentKind.LIQUIFY
    if (tool.values.size < k.params.size) return
    var modeAnchor by remember { mutableStateOf<Offset?>(null) }
    var btnPos by remember { mutableStateOf(Offset.Zero) }
    val ui = LocalUi.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // Modus-Knopf links (öffnet die Modusliste)
        Box(
            Modifier.onGloballyPositioned { c ->
                val p = c.positionInWindow()
                btnPos = Offset(p.x + c.size.width / 2f, p.y)
            }
        ) {
            PillButton(AdjustmentKind.LIQUIFY_MODES[tool.values[0].roundToInt()] + " ▾", active = true) {
                modeAnchor = btnPos
            }
        }
        PillButton("Zurücksetzen") { tool.reset() }
        ParamSlider(k.params[AdjustmentKind.LIQUIFY_ADJUST], tool.values[AdjustmentKind.LIQUIFY_ADJUST], Modifier.weight(1f),
            onChange = { tool.setValue(AdjustmentKind.LIQUIFY_ADJUST, it) }, onDone = { tool.endValueChange() })
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = if (maxWidth > 460.dp) 4 else 2
        Column {
            for (row in listOf(2, 1, 3, 4).chunked(cols)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (i in row) ParamSlider(k.params[i], tool.values[i], Modifier.weight(1f),
                        onChange = { tool.setValue(i, it) }, onDone = { tool.endValueChange() })
                }
            }
        }
    }
    // Modusliste öffnet sich oberhalb des Knopfs
    AnchoredPopover(modeAnchor, onDismiss = { modeAnchor = null }) {
        PopoverMenuCard(width = 230.dp) {
            AdjustmentKind.LIQUIFY_MODES.forEachIndexed { i, name ->
                if (i > 0) MenuDivider()
                MenuRow(name, checked = tool.values.getOrElse(0) { 0f }.roundToInt() == i) {
                    tool.setChoice(0, i); modeAnchor = null
                }
            }
        }
    }
    Text("Seitenleiste: Größe / Druck", color = ui.textMuted, fontSize = 11.sp)
}

@Composable
private fun ClonePanel(tool: AdjustmentTool) {
    val ui = LocalUi.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Klonen", color = ui.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        PillButton(if (tool.cloneLocked) "Quelle gesperrt" else "Quelle sperren", active = tool.cloneLocked) { tool.toggleCloneLock() }
        PillButton("Rückgängig", enabled = tool.historySize > 0) { tool.undoStep() }
        PillButton("Zurücksetzen") { tool.reset() }
    }
    Text("Scheibe ziehen = Quelle setzen · Scheibe halten = sperren · daneben mit dem Malpinsel malen",
        color = ui.textMuted, fontSize = 11.sp)
}

@Composable
private fun RecolorPanel(state: EditorState, tool: AdjustmentTool) {
    val ui = LocalUi.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Umfärben", color = ui.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Box(Modifier.size(20.dp).clip(CircleShape).background(Color(state.color)))
        Spacer(Modifier.weight(1f))
        PillButton("Abbrechen", destructive = true) { tool.cancelAndClose() }
        PillButton("Anwenden", active = true) { tool.applyAndClose() }
    }
    ParamRows(tool, AdjustmentKind.RECOLOR, skip = -1)
    Text("Fadenkreuz auf die zu ersetzende Farbe ziehen – sie wird durch die aktuelle Farbe ersetzt.",
        color = ui.textMuted, fontSize = 11.sp)
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
        Spacer(Modifier.width(12.dp))
        Text("Tippen: Punkt · Punkt antippen: löschen", color = ui.textMuted, fontSize = 11.sp)
    }
    val pts = tool.curves.channels[channel]
    val hist = tool.histogram?.getOrNull(channel)
    var dragIndex by remember { mutableStateOf(-1) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxWidth().height(180.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1A1A1E))
                .pointerInput(channel) {
                    detectTapGestures { pos ->
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        val hit = pts.indexOfFirst { hypot(it.x * w - pos.x, (1 - it.y) * h - pos.y) < 22.dp.toPx() }
                        if (hit > 0 && hit < pts.size - 1) {
                            tool.beginCurveEdit(); pts.removeAt(hit)
                        } else if (hit < 0 && pts.size < Curves.MAX_POINTS) {
                            tool.beginCurveEdit()
                            pts.add(PointF((pos.x / w).coerceIn(0f, 1f), (1 - pos.y / h).coerceIn(0f, 1f)))
                            pts.sortBy { it.x }
                        } else return@detectTapGestures
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
                                if (pts.size < Curves.MAX_POINTS) {
                                    val np = PointF((pos.x / w).coerceIn(0.01f, 0.99f), (1 - pos.y / h).coerceIn(0f, 1f))
                                    pts.add(np)
                                    pts.sortBy { it.x }
                                    dragIndex = pts.indexOf(np)
                                } else dragIndex = -1
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
                                0 -> (change.position.x / w).coerceIn(0f, pts[1].x - 0.01f)
                                pts.size - 1 -> (change.position.x / w).coerceIn(pts[dragIndex - 1].x + 0.01f, 1f)
                                else -> (change.position.x / w).coerceIn(pts[dragIndex - 1].x + 0.01f, pts[dragIndex + 1].x - 0.01f)
                            }
                            p.set(nx, ny)
                            tool.curvesChanged(true)
                        },
                    )
                }
        ) {
            val w = size.width; val h = size.height
            // Histogramm im Hintergrund
            if (hist != null) {
                val hp = Path()
                hp.moveTo(0f, h)
                for (i in 0 until 256) hp.lineTo(i / 255f * w, h - hist[i] * h * 0.9f)
                hp.lineTo(w, h); hp.close()
                drawPath(hp, colors[channel].copy(alpha = 0.16f))
            }
            val grid = Color(0x26FFFFFF)
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
                drawCircle(Color.White, 6.dp.toPx(), Offset(p.x * w, (1 - p.y) * h))
                drawCircle(colors[channel].copy(alpha = 1f), 4.dp.toPx(), Offset(p.x * w, (1 - p.y) * h))
            }
        }
    }
}
