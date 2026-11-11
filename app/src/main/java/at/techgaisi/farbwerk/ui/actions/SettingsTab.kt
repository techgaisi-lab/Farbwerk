package at.techgaisi.farbwerk.ui.actions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.prefs.Prefs
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlin.math.hypot
import kotlin.math.roundToInt

private enum class SettingsPage(val title: String) { MAIN("Einstellungen"), PRESSURE("Druck und Glättung"), GESTURES("Gestensteuerung") }

private object SettingsMemory { var page by mutableStateOf(SettingsPage.MAIN) }

/** Aktionen → Einstellungen (inkl. Unterseiten Druck & Glättung sowie Gestensteuerung). */
@Composable
internal fun SettingsTab(state: EditorState) {
    val p = state.prefs
    when (SettingsMemory.page) {
        SettingsPage.MAIN -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader("Oberfläche")
            SwitchRow("Helle Oberfläche", p.lightInterface) { p.lightInterface = it; p.save() }
            SwitchRow("Seitenleiste rechts", p.sidebarRight, subtitle = "Rechtshändige Benutzeroberfläche") {
                p.sidebarRight = it; p.save()
            }
            SwitchRow("Pinselcursor", p.brushCursor, subtitle = "Pinselumriss beim Malen anzeigen") { p.brushCursor = it; p.save() }
            SwitchRow("Dynamische Pinselskalierung", p.dynamicBrushScaling, subtitle = "Pinselgröße folgt dem Zoom") {
                p.dynamicBrushScaling = it; p.save()
            }
            SwitchRow("Leinwand projizieren", p.projectCanvas, subtitle = "Auf externem Bildschirm zeigen") {
                p.projectCanvas = it; p.save()
            }
            FwDivider()
            SectionHeader("Eingabe")
            SwitchRow("Mit Finger malen", p.fingerPainting, subtitle = "Aus: nur der Stift malt, Finger steuern Gesten") {
                p.fingerPainting = it; p.save()
            }
            ActionRow("Druck und Glättung", chevron = true) { SettingsMemory.page = SettingsPage.PRESSURE }
            ActionRow("Gestensteuerung", chevron = true) { SettingsMemory.page = SettingsPage.GESTURES }
            FwDivider()
            SectionHeader("Verlauf")
            SliderRow("Rückgängig-Schritte", p.undoLimit.toFloat(), 10f..500f, { it.roundToInt().toString() },
                onChange = { p.undoLimit = it.roundToInt() }, onDone = { p.save() })
            Spacer(Modifier.height(10.dp))
        }
        SettingsPage.PRESSURE -> SubPage(SettingsPage.PRESSURE) {
            SectionHeader("Druckkurve")
            PressureCurveEditor(p)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Spacer(Modifier.weight(1f))
                Text("Kurve zurücksetzen", color = LocalUi.current.accent, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                        p.pressureCurve = listOf(0.25f, 0.25f, 0.75f, 0.75f); p.save()
                    }.padding(6.dp))
            }
            FwDivider()
            SectionHeader("Glättung")
            SliderRow("Stabilisierung", p.pressureSmoothing, 0f..1f, { "${(it * 100).roundToInt()} %" },
                onChange = { p.pressureSmoothing = it }, onDone = { p.save() })
            SliderRow("Bewegungsfilterung", p.motionFilteringAmount, 0f..1f, { "${(it * 100).roundToInt()} %" },
                onChange = { p.motionFilteringAmount = it }, onDone = { p.save() })
            ActionRow("Alle Werte zurücksetzen") {
                p.pressureCurve = listOf(0.25f, 0.25f, 0.75f, 0.75f)
                p.pressureSmoothing = 0.1f; p.motionFilteringAmount = 0f; p.save()
            }
        }
        SettingsPage.GESTURES -> SubPage(SettingsPage.GESTURES) {
            SectionHeader("Gesten")
            SwitchRow("Tippen zum Rückgängigmachen", p.gestureTapUndo, subtitle = "2 Finger: rückgängig · 3 Finger: wiederholen") {
                p.gestureTapUndo = it; p.save()
            }
            SwitchRow("Wischen zum Leeren", p.gestureScrubClear, subtitle = "3 Finger hin und her") { p.gestureScrubClear = it; p.save() }
            SwitchRow("Kopieren & Einfügen", p.gestureCopyPaste, subtitle = "3 Finger nach unten wischen") { p.gestureCopyPaste = it; p.save() }
            SwitchRow("Vollbild", p.gestureFullscreen, subtitle = "4 Finger tippen") { p.gestureFullscreen = it; p.save() }
            SwitchRow("Pipette", p.gestureHoldEyedropper, subtitle = "Berühren und halten") { p.gestureHoldEyedropper = it; p.save() }
            SwitchRow("Schnellform", p.quickShapeEnabled, subtitle = "Zeichnen und halten") { p.quickShapeEnabled = it; p.save() }
            FwDivider()
            SectionHeader("Verzögerungen")
            SliderRow("Pipette (Halten)", p.holdDelayMs.toFloat(), 100f..1500f, { "${(it / 10).roundToInt() * 10} ms" },
                onChange = { p.holdDelayMs = it.roundToInt() }, onDone = { p.save() })
            SliderRow("Schnellform", p.quickShapeDelayMs.toFloat(), 200f..1500f, { "${(it / 10).roundToInt() * 10} ms" },
                onChange = { p.quickShapeDelayMs = it.roundToInt() }, onDone = { p.save() })
            SliderRow("Schnelles Rückgängig", p.rapidUndoDelayMs.toFloat(), 200f..1500f, { "${(it / 10).roundToInt() * 10} ms" },
                onChange = { p.rapidUndoDelayMs = it.roundToInt() }, onDone = { p.save() })
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun SubPage(page: SettingsPage, content: @Composable () -> Unit) {
    val ui = LocalUi.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().clickable { SettingsMemory.page = SettingsPage.MAIN }.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(ActionIcons.Back, "Zurück", tint = ui.accent, modifier = Modifier.size(20.dp))
            Text("Einstellungen", color = ui.accent, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text(page.title, color = ui.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() }
    }
}

/**
 * Editor für die Druckkurve: kubische Bézierkurve von (0,0) nach (1,1) mit zwei ziehbaren Griffen.
 * x = Stiftdruck, y = Ergebnis. Schreibt live in [Prefs.pressureCurve], speichert beim Loslassen.
 */
@Composable
private fun PressureCurveEditor(p: Prefs) {
    val ui = LocalUi.current
    var dragging by remember { mutableIntStateOf(-1) }
    val curve = p.pressureCurve.takeIf { it.size >= 4 } ?: listOf(0.25f, 0.25f, 0.75f, 0.75f)
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(1.4f).clip(RoundedCornerShape(10.dp)).background(ui.panelElevated)
                .pointerInput(Unit) {
                    val pad = 14.dp.toPx()
                    fun toCurve(o: Offset): Pair<Float, Float> {
                        val w = size.width - 2 * pad; val h = size.height - 2 * pad
                        return ((o.x - pad) / w).coerceIn(0f, 1f) to (1f - (o.y - pad) / h).coerceIn(0f, 1f)
                    }
                    detectDragGestures(
                        onDragStart = { o ->
                            val c = p.pressureCurve
                            val w = size.width - 2 * pad; val h = size.height - 2 * pad
                            val d1 = hypot(o.x - (pad + c[0] * w), o.y - (pad + (1 - c[1]) * h))
                            val d2 = hypot(o.x - (pad + c[2] * w), o.y - (pad + (1 - c[3]) * h))
                            dragging = if (d1 <= d2) 0 else 1
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val (x, y) = toCurve(change.position)
                            val c = p.pressureCurve.toMutableList()
                            if (dragging == 0) { c[0] = x; c[1] = y } else if (dragging == 1) { c[2] = x; c[3] = y }
                            p.pressureCurve = c
                        },
                        onDragEnd = { dragging = -1; p.save() },
                        onDragCancel = { dragging = -1; p.save() },
                    )
                }
        ) {
            val pad = 14.dp.toPx()
            val w = size.width - 2 * pad; val h = size.height - 2 * pad
            fun pt(x: Float, y: Float) = Offset(pad + x * w, pad + (1 - y) * h)
            // Raster
            for (i in 0..4) {
                val f = i / 4f
                drawLine(ui.divider, pt(f, 0f), pt(f, 1f), 1f)
                drawLine(ui.divider, pt(0f, f), pt(1f, f), 1f)
            }
            // Diagonale (linear)
            drawLine(ui.textMuted.copy(alpha = 0.4f), pt(0f, 0f), pt(1f, 1f), 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            val p1 = pt(curve[0], curve[1]); val p2 = pt(curve[2], curve[3])
            val start = pt(0f, 0f); val end = pt(1f, 1f)
            drawLine(ui.textMuted, start, p1, 1.5f)
            drawLine(ui.textMuted, end, p2, 1.5f)
            val path = Path().apply { moveTo(start.x, start.y); cubicTo(p1.x, p1.y, p2.x, p2.y, end.x, end.y) }
            drawPath(path, ui.accent, style = Stroke(width = 3.dp.toPx()))
            for ((i, c) in listOf(p1, p2).withIndex()) {
                drawCircle(Color.White, radius = if (dragging == i) 10.dp.toPx() else 8.dp.toPx(), center = c)
                drawCircle(ui.accent, radius = 4.dp.toPx(), center = c)
            }
        }
        Text("Druck", color = ui.textMuted, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
        Text("Ergebnis", color = ui.textMuted, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
    }
}
