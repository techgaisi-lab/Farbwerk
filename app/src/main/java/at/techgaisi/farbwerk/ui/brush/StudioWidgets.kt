package at.techgaisi.farbwerk.ui.brush

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.brush.BP
import at.techgaisi.farbwerk.brush.Brush
import at.techgaisi.farbwerk.brush.Curves
import at.techgaisi.farbwerk.brush.ParamKind
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Abschnittsüberschrift im Studio. */
@Composable
internal fun SectionHeader(text: String) {
    val ui = LocalUi.current
    Text(text.uppercase(), color = ui.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

/** Dünner Regler im Stil des Studios. */
@Composable
internal fun StudioSlider(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    val ui = LocalUi.current
    Slider(
        value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, steps = steps,
        modifier = Modifier.fillMaxWidth().height(28.dp),
        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = ui.accent, inactiveTrackColor = ui.divider,
            activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
    )
}

/**
 * Zahlen-Regler eines Parameters. Tippen auf den Wert öffnet die erweiterten Optionen (Zahleneingabe und – bei
 * Parametern pro Tupfer – Kopplung an Druck/Neigung).
 */
@Composable
internal fun ParamSlider(work: Brush, p: BP, onChange: () -> Unit) {
    val ui = LocalUi.current
    var dialog by remember { mutableStateOf(false) }
    val v = work[p]
    val coupled = p.dyn && (work.pressureCoupling(p) != 0f || work.tiltCoupling(p) != 0f)
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(p.label, color = ui.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (coupled) Box(Modifier.size(6.dp).clip(CircleShape).background(ui.accent))
            Spacer(Modifier.width(6.dp))
            Text(
                p.format(v), color = ui.textMuted, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { dialog = true }
                    .background(ui.panelElevated).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        val pixels = p.kind == ParamKind.PIXELS
        val pos = if (pixels) sqrt(((v - p.min) / (p.max - p.min)).coerceIn(0f, 1f)) else v
        StudioSlider(pos, if (pixels) 0f..1f else p.min..p.max,
            steps = if (p.kind == ParamKind.COUNT) (p.max - p.min).toInt() - 1 else 0) { x ->
            work[p] = if (pixels) p.min + (p.max - p.min) * x * x else x
            onChange()
        }
    }
    if (dialog) ParamValueDialog(work, p, onChange) { dialog = false }
}

/** Erweiterte Optionen eines Reglers: Zahleneingabe, ±-Schritte, Kopplung an Druck/Neigung. */
@Composable
private fun ParamValueDialog(work: Brush, p: BP, onChange: () -> Unit, onDismiss: () -> Unit) {
    val ui = LocalUi.current
    var text by remember { mutableStateOf(fmtNum(p.toDisplay(work[p]))) }
    var pc by remember { mutableFloatStateOf(work.pressureCoupling(p)) }
    var tc by remember { mutableFloatStateOf(work.tiltCoupling(p)) }
    val unit = when (p.kind) {
        ParamKind.PERCENT, ParamKind.SIGNED_PERCENT -> "%"
        ParamKind.PIXELS -> "px"; ParamKind.DEGREES -> "°"; ParamKind.FACTOR -> "×"; else -> ""
    }
    fun apply() {
        text.replace(',', '.').toFloatOrNull()?.let { work[p] = p.fromDisplay(it) }
        if (p.dyn) work.setCoupling(p, pc, tc)
        onChange()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ui.panel,
        shape = RoundedCornerShape(14.dp),
        title = { Text(p.label, color = ui.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.widthIn(min = 260.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepButton("−") {
                        val d = (text.replace(',', '.').toFloatOrNull() ?: 0f) - step(p)
                        text = fmtNum(p.toDisplay(p.fromDisplay(d)))
                    }
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        text, { text = it.take(10) }, singleLine = true,
                        textStyle = TextStyle(color = ui.text, fontSize = 20.sp, fontWeight = FontWeight.Medium),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        cursorBrush = SolidColor(ui.accent),
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(ui.panelElevated)
                            .border(0.5.dp, ui.divider, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    Text(" $unit", color = ui.textMuted, fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    StepButton("+") {
                        val d = (text.replace(',', '.').toFloatOrNull() ?: 0f) + step(p)
                        text = fmtNum(p.toDisplay(p.fromDisplay(d)))
                    }
                }
                Text("Bereich ${fmtNum(p.toDisplay(p.min))} … ${fmtNum(p.toDisplay(p.max))} $unit",
                    color = ui.textMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                if (p.dyn) {
                    SectionHeader("Druck")
                    CouplingRow(pc) { pc = it }
                    SectionHeader("Neigung")
                    CouplingRow(tc) { tc = it }
                    Text("Positiv: weniger Druck bzw. flacherer Stift verringert den Wert. Negativ: vergrößert ihn.",
                        color = ui.textMuted, fontSize = 12.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = { apply(); onDismiss() }) { Text("Fertig", color = ui.accent, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen", color = ui.text) } },
    )
}

@Composable
private fun CouplingRow(v: Float, onChange: (Float) -> Unit) {
    val ui = LocalUi.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { StudioSlider(v, -1f..1f) { onChange(if (abs(it) < 0.03f) 0f else it) } }
        Text((if (v > 0.004f) "+" else "") + "${(v * 100).roundToInt()} %", color = ui.textMuted, fontSize = 13.sp,
            modifier = Modifier.width(56.dp).padding(start = 8.dp))
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    val ui = LocalUi.current
    Box(Modifier.size(36.dp).clip(CircleShape).background(ui.panelElevated).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = ui.text, fontSize = 20.sp)
    }
}

private fun step(p: BP): Float = when (p.kind) {
    ParamKind.PERCENT, ParamKind.SIGNED_PERCENT -> 1f
    ParamKind.FACTOR -> 0.05f
    else -> 1f
}

private fun fmtNum(f: Float): String =
    if (abs(f - f.roundToInt()) < 0.005f) f.roundToInt().toString() else "%.2f".format(f).trimEnd('0').trimEnd('.', ',')

/** Schalter-Zeile. */
@Composable
internal fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val ui = LocalUi.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = ui.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = ui.accent, checkedThumbColor = Color.White))
    }
}

/** Auswahl als Segmentleiste. */
@Composable
internal fun ChoiceRow(label: String, options: Array<String>, selected: Int, onSelect: (Int) -> Unit) {
    val ui = LocalUi.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = ui.text, fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { i, o ->
                val sel = i == selected
                Text(
                    o, color = if (sel) Color.White else ui.text, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (sel) ui.accent else ui.panelElevated)
                        .clickable { onSelect(i) }.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/**
 * Doppelregler Anfang/Ende der Verjüngung auf einer Spur: linker Griff = Anfang (von links), rechter Griff = Ende
 * (von rechts). Mit [linked] bewegen sich beide spiegelbildlich.
 */
@Composable
internal fun TaperRange(start: Float, end: Float, linked: Boolean, onChange: (Float, Float) -> Unit) {
    val ui = LocalUi.current
    var active by remember { mutableIntStateOf(-1) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row {
            Text("Anfang ${(start * 100).roundToInt()} %", color = ui.textMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("Ende ${(end * 100).roundToInt()} %", color = ui.textMuted, fontSize = 12.sp)
        }
        Canvas(
            Modifier.fillMaxWidth().height(40.dp).pointerInput(linked) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat()
                    val half = w / 2f
                    val sx = start * half; val ex = w - end * half
                    active = if (abs(down.position.x - sx) <= abs(down.position.x - ex)) 0 else 1
                    var pos = down.position.x
                    fun upd() {
                        if (active == 0) {
                            val s = (pos / half).coerceIn(0f, 1f)
                            onChange(s, if (linked) s else end)
                        } else {
                            val e = ((w - pos) / half).coerceIn(0f, 1f)
                            onChange(if (linked) e else start, e)
                        }
                    }
                    upd()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        pos += c.positionChange().x
                        c.consume()
                        upd()
                    }
                    active = -1
                }
            },
        ) {
            val w = size.width; val cy = size.height / 2f
            val half = w / 2f
            val sx = start * half; val ex = w - end * half
            // Spur als Verjüngungsform
            val path = Path().apply {
                moveTo(0f, cy); lineTo(sx, cy - 7.dp.toPx()); lineTo(ex, cy - 7.dp.toPx()); lineTo(w, cy)
                lineTo(ex, cy + 7.dp.toPx()); lineTo(sx, cy + 7.dp.toPx()); close()
            }
            drawPath(path, ui.divider)
            drawLine(ui.accent, Offset(0f, cy), Offset(sx, cy), 3.dp.toPx(), StrokeCap.Round)
            drawLine(ui.accent, Offset(ex, cy), Offset(w, cy), 3.dp.toPx(), StrokeCap.Round)
            for ((i, x) in listOf(sx, ex).withIndex()) {
                drawCircle(Color(0x55000000), (if (active == i) 13f else 11f).dp.toPx(), Offset(x, cy + 1.dp.toPx()))
                drawCircle(Color.White, (if (active == i) 12f else 10f).dp.toPx(), Offset(x, cy))
            }
        }
    }
}

/**
 * Druckdiagramm des Pinsels: Druck (x) → Wirkung (y). Bis zu 4 Zusatzpunkte: auf die Kurve tippen fügt einen
 * hinzu, einen Zusatzpunkt antippen löscht ihn, ziehen verschiebt. Endpunkte nur senkrecht.
 */
@Composable
internal fun PressureCurveEditor(work: Brush, onChange: () -> Unit) {
    val ui = LocalUi.current
    var tick by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth(0.8f).aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1C1C1F))
                .border(0.5.dp, ui.divider, RoundedCornerShape(10.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        val pad = 14.dp.toPx()
                        fun toX(px: Float) = ((px - pad) / (w - 2 * pad)).coerceIn(0f, 1f)
                        fun toY(py: Float) = (1f - (py - pad) / (h - 2 * pad)).coerceIn(0f, 1f)
                        val c = (work.pressureCurve ?: Curves.linear()).toMutableList()
                        val n = c.size / 2
                        var idx = -1; var best = 22.dp.toPx()
                        for (i in 0 until n) {
                            val px = pad + c[2 * i] * (w - 2 * pad); val py = pad + (1f - c[2 * i + 1]) * (h - 2 * pad)
                            val d = hypot(px - down.position.x, py - down.position.y)
                            if (d < best) { best = d; idx = i }
                        }
                        var moved = false
                        if (idx < 0) {
                            if (n - 2 >= 4) return@awaitEachGesture
                            val x = toX(down.position.x)
                            var at = 1
                            while (at < n && c[2 * at] < x) at++
                            c.add(2 * at, x); c.add(2 * at + 1, toY(down.position.y))
                            idx = at; moved = true
                        }
                        var pos = down.position
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            val d = ch.positionChange()
                            if (d != Offset.Zero) {
                                pos += d; ch.consume()
                                if (!moved && (pos - down.position).getDistance() > 6.dp.toPx()) moved = true
                                if (moved) {
                                    val cnt = c.size / 2
                                    if (idx == 0 || idx == cnt - 1) c[2 * idx + 1] = toY(pos.y)
                                    else {
                                        val lo = c[2 * (idx - 1)] + 0.02f; val hi = c[2 * (idx + 1)] - 0.02f
                                        c[2 * idx] = toX(pos.x).coerceIn(lo, hi.coerceAtLeast(lo)); c[2 * idx + 1] = toY(pos.y)
                                    }
                                    work.pressureCurve = c.toFloatArray(); tick++; onChange()
                                }
                            }
                        }
                        if (!moved && idx > 0 && idx < c.size / 2 - 1) {
                            c.removeAt(2 * idx); c.removeAt(2 * idx)
                        }
                        work.pressureCurve = c.toFloatArray().let { if (it.contentEquals(Curves.linear())) null else it }
                        tick++; onChange()
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                @Suppress("UNUSED_VARIABLE") val t = tick
                val pad = 14.dp.toPx()
                val w = size.width - 2 * pad; val h = size.height - 2 * pad
                for (i in 0..4) {
                    val f = i / 4f
                    drawLine(Color(0x22FFFFFF), Offset(pad + f * w, pad), Offset(pad + f * w, pad + h), 1f)
                    drawLine(Color(0x22FFFFFF), Offset(pad, pad + f * h), Offset(pad + w, pad + f * h), 1f)
                }
                val c = work.pressureCurve ?: Curves.linear()
                val path = Path()
                for (i in 0..100) {
                    val x = i / 100f
                    val y = Curves.eval(c, x)
                    val px = pad + x * w; val py = pad + (1f - y) * h
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(path, ui.accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                for (i in 0 until c.size / 2) {
                    val o = Offset(pad + c[2 * i] * w, pad + (1f - c[2 * i + 1]) * h)
                    drawCircle(Color.White, 7.dp.toPx(), o)
                    drawCircle(ui.accent, 5.dp.toPx(), o)
                }
            }
            Text("Druck", color = ui.textMuted, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomCenter))
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Auf die Kurve tippen: Punkt hinzufügen · Punkt antippen: löschen", color = ui.textMuted, fontSize = 12.sp,
                modifier = Modifier.weight(1f))
            Text("Zurücksetzen", color = ui.accent, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { work.pressureCurve = null; tick++; onChange() }
                    .padding(8.dp))
        }
    }
}

/**
 * Rundheits-Diagramm: grüner Punkt = Grunddrehung (auf dem Kreis), blaue Punkte = Stauchung (Rundheit).
 */
@Composable
internal fun RoundnessDiagram(work: Brush, onChange: () -> Unit) {
    val ui = LocalUi.current
    var tick by remember { mutableIntStateOf(0) }
    Box(
        Modifier.fillMaxWidth(0.62f).aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1C1C1F))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val cx = size.width / 2f; val cy = size.height / 2f
                    val r = size.width * 0.38f
                    val ang = Math.toRadians(work[BP.ROTATION].toDouble()).toFloat()
                    val gx = cx + cos(ang) * r; val gy = cy + sin(ang) * r
                    val rr = work[BP.ROUNDNESS] * r
                    val bx = cx - sin(ang) * rr; val by = cy + cos(ang) * rr
                    val bx2 = cx + sin(ang) * rr; val by2 = cy - cos(ang) * rr
                    val dg = hypot(down.position.x - gx, down.position.y - gy)
                    val db = minOf(hypot(down.position.x - bx, down.position.y - by), hypot(down.position.x - bx2, down.position.y - by2))
                    val green = dg <= db
                    var pos = down.position
                    fun upd() {
                        if (green) {
                            work[BP.ROTATION] = Math.toDegrees(atan2(pos.y - cy, pos.x - cx).toDouble()).toFloat().roundToInt().toFloat()
                        } else {
                            val a = Math.toRadians(work[BP.ROTATION].toDouble()).toFloat()
                            val d = abs(-(pos.x - cx) * sin(a) + (pos.y - cy) * cos(a))
                            work[BP.ROUNDNESS] = (d / r).coerceIn(BP.ROUNDNESS.min, 1f)
                        }
                        tick++; onChange()
                    }
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        pos += c.positionChange(); c.consume(); upd()
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE") val t = tick
            val cx = size.width / 2f; val cy = size.height / 2f
            val r = size.width * 0.38f
            val deg = work[BP.ROTATION]
            val ang = Math.toRadians(deg.toDouble()).toFloat()
            drawCircle(Color(0x33FFFFFF), r, Offset(cx, cy), style = Stroke(1.dp.toPx()))
            rotate(deg, Offset(cx, cy)) {
                val rr = work[BP.ROUNDNESS] * r
                drawOval(Color(0x553D8BFF), Offset(cx - r, cy - rr), Size(2 * r, 2 * rr))
                drawOval(ui.accent, Offset(cx - r, cy - rr), Size(2 * r, 2 * rr), style = Stroke(2.dp.toPx()))
            }
            val rr = work[BP.ROUNDNESS] * r
            drawCircle(BrushUiColors.handleGreen, 8.dp.toPx(), Offset(cx + cos(ang) * r, cy + sin(ang) * r))
            drawCircle(ui.accent, 7.dp.toPx(), Offset(cx - sin(ang) * rr, cy + cos(ang) * rr))
            drawCircle(ui.accent, 7.dp.toPx(), Offset(cx + sin(ang) * rr, cy - cos(ang) * rr))
            drawCircle(Color.White, 2.dp.toPx(), Offset(cx, cy))
        }
        Text("${(work[BP.ROUNDNESS] * 100).roundToInt()} % · ${work[BP.ROTATION].roundToInt()}°", color = ui.textMuted, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp))
    }
}

/** Unterschriftsfeld: Linienzüge in 0..1-Koordinaten; ⓧ löscht. */
@Composable
internal fun SignaturePad(work: Brush, onChange: () -> Unit) {
    val ui = LocalUi.current
    var tick by remember { mutableIntStateOf(0) }
    Box(
        Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1C1C1F))
            .border(0.5.dp, ui.divider, RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    val pts = ArrayList<Float>()
                    var pos = down.position
                    pts.add(pos.x / w); pts.add(pos.y / h)
                    work.signature.add(pts.toFloatArray())
                    val idx = work.signature.lastIndex
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        pos += c.positionChange(); c.consume()
                        pts.add((pos.x / w).coerceIn(0f, 1f)); pts.add((pos.y / h).coerceIn(0f, 1f))
                        work.signature[idx] = pts.toFloatArray(); tick++
                    }
                    onChange()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE") val t = tick
            for (line in work.signature) {
                val path = Path()
                for (i in 0 until line.size / 2) {
                    val x = line[2 * i] * size.width; val y = line[2 * i + 1] * size.height
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, Color.White, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        if (work.signature.isEmpty()) Text("Hier unterschreiben", color = ui.textMuted, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
        else Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(Color(0x33FFFFFF))
                .clickable { work.signature.clear(); tick++; onChange() },
            contentAlignment = Alignment.Center,
        ) { Text("×", color = Color.White, fontSize = 16.sp) }
    }
}
