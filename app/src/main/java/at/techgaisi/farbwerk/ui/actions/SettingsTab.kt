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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import at.techgaisi.farbwerk.input.GestureFunction
import at.techgaisi.farbwerk.input.GestureMap
import at.techgaisi.farbwerk.input.GestureTrigger
import at.techgaisi.farbwerk.stylus.StylusButtonAction
import at.techgaisi.farbwerk.ui.icons.AppIcons
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
            if (p.brushCursor) {
                ChoiceRow("Cursor-Sichtbarkeit", null,
                    listOf(0 to "Beim Schweben", 1 to "Beim Malen", 2 to "Beides"), p.cursorVisibility) {
                    p.cursorVisibility = it; p.save()
                }
                ChoiceRow("Umrissstil", null,
                    listOf(0 to "Hoher Kontrast", 1 to "Aktive Farbe", 2 to "Pro Pinsel"), p.cursorStyle) {
                    p.cursorStyle = it; p.save()
                }
            }
            SwitchRow("Seitenleiste Größe & Deckkraft", p.sidebarVisible, subtitle = "Regler am Bildschirmrand anzeigen") {
                p.sidebarVisible = it; p.save()
            }
            if (p.sidebarOffset != 0f) {
                ActionRow("Seitenleiste zentrieren") { p.sidebarOffset = 0f; p.save() }
            }
            SwitchRow("Automatisches Vollbild", p.autoFullscreen, subtitle = "Oberfläche beim Malen ausblenden") {
                p.autoFullscreen = it; p.save()
            }
            if (p.autoFullscreen) {
                SliderRow("Rückkehr nach", p.autoFullscreenDelayMs.toFloat(), 200f..3000f, { "%.1f s".format(it / 1000f) },
                    onChange = { p.autoFullscreenDelayMs = it.roundToInt() }, onDone = { p.save() })
            }
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
            StylusSection(p)
            FwDivider()
            SectionHeader("Auswahl & Transformieren")
            SliderRow("Sichtbarkeit der Auswahlmaske", at.techgaisi.farbwerk.selection.SelectionPrefs.maskOpacity, 0f..1f,
                { "${(it * 100).roundToInt()} %" },
                onChange = { at.techgaisi.farbwerk.selection.SelectionPrefs.updateMaskOpacity(it); p.selectionMaskOpacity = it },
                onDone = { p.save() })
            SwitchRow("Vereinfachtes Rückgängig", p.simplifiedUndo, subtitle = "Alle Transformieren-Schritte als ein Schritt") {
                p.simplifiedUndo = it; p.save()
            }
            FwDivider()
            SectionHeader("Tastatur")
            SwitchRow("Leertaste öffnet QuickMenu", p.spaceQuickMenu, subtitle = "Kurz tippen; halten und ziehen verschiebt die Leinwand") {
                p.spaceQuickMenu = it; p.save()
            }
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
            GestureControlPage(p)
        }
    }
}

/** Abschnitt "S Pen": Seitentaste, Handballenerkennung, Schwebevorschau, Bewegungsvorhersage, Druckglättung. */
@Composable
private fun StylusSection(p: Prefs) {
    val ui = LocalUi.current
    SectionHeader("S Pen")
    ChoiceRow(
        "Seitentaste + Berühren", "Taste gedrückt halten und Stift aufsetzen",
        StylusButtonAction.entries.map { it.id to touchLabel(it) }, p.stylusButtonAction,
    ) { p.stylusButtonAction = it; p.save() }
    ChoiceRow(
        "Seitentaste in der Luft", "Taste klicken, während der Stift über dem Display schwebt",
        StylusButtonAction.entries.map { it.id to hoverLabel(it) }, p.stylusButtonHoverAction,
    ) { p.stylusButtonHoverAction = it; p.save() }
    SwitchRow("Nur Stift malt nach Stifterkennung", p.stylusOnlyAfterPen,
        subtitle = "Sobald der Stift benutzt wurde, steuern Finger nur noch Gesten") {
        p.stylusOnlyAfterPen = it; p.save()
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("Handballenerkennung", color = ui.text, fontSize = 15.sp)
        Text("Finger-Berührungen in Stiftnähe und großflächige Kontakte ignorieren", color = ui.textMuted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Segmented(listOf(0 to "Aus", 1 to "Normal", 2 to "Stark"), p.palmRejection.coerceIn(0, 2),
            Modifier.fillMaxWidth()) { p.palmRejection = it; p.save() }
    }
    SwitchRow("Schwebevorschau", p.hoverPreview, subtitle = "Pinselumriss zeigen, wenn der Stift über dem Display schwebt") {
        p.hoverPreview = it; p.save()
    }
    SwitchRow("Bewegungsvorhersage", p.motionPrediction, subtitle = "Verringert die sichtbare Verzögerung beim Zeichnen") {
        p.motionPrediction = it; p.save()
    }
    SliderRow("Druckglättung", p.pressureSmoothing, 0f..1f, { "${(it * 100).roundToInt()} %" },
        onChange = { p.pressureSmoothing = it }, onDone = { p.save() })
}

private fun touchLabel(a: StylusButtonAction): String = when (a) {
    StylusButtonAction.EYEDROPPER -> "Pipette (mit Lupe)"
    StylusButtonAction.ERASER -> "Radieren, solange gedrückt"
    StylusButtonAction.COLORS -> "Farbpanel öffnen"
    else -> a.label
}

private fun hoverLabel(a: StylusButtonAction): String = when (a) {
    StylusButtonAction.EYEDROPPER -> "Farbe unter dem Stift aufnehmen"
    StylusButtonAction.ERASER -> "Zum Radierer wechseln und zurück"
    StylusButtonAction.COLORS -> "Farbpanel ein/aus"
    else -> a.label
}

/** Zeile mit aufklappbarer Auswahl (aktueller Wert rechts). */
@Composable
private fun ChoiceRow(label: String, subtitle: String?, options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    val ui = LocalUi.current
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 46.dp).clickable { open = true }.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = ui.text, fontSize = 15.sp)
                if (subtitle != null) Text(subtitle, color = ui.textMuted, fontSize = 12.sp)
            }
            Text(options.firstOrNull { it.first == selected }?.second ?: "", color = ui.accent, fontSize = 13.sp)
            Icon(ActionIcons.Chevron, null, tint = ui.iconMuted, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.background(ui.panelElevated),
        ) {
            for ((id, text) in options) {
                DropdownMenuItem(
                    text = {
                        Text(text, color = if (id == selected) ui.accent else ui.text, fontSize = 14.sp,
                            fontWeight = if (id == selected) FontWeight.SemiBold else FontWeight.Normal)
                    },
                    onClick = { open = false; onSelect(id) },
                )
            }
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

/**
 * Gestensteuerung (Anleitung §2.4): je Funktion die möglichen Auslöser als Schalter. Ein Auslöser sollte nur
 * einer Funktion gehören – Konflikte zeigen ein gelbes Warnsymbol (es gewinnt die weiter oben stehende
 * Funktion). Halte-Auslöser haben einen Verzögerungsregler.
 */
@Composable
private fun GestureControlPage(p: Prefs) {
    @Suppress("UNUSED_VARIABLE") val raw = p.gestureTriggers
    @Suppress("UNUSED_VARIABLE") val hold = p.gestureHoldEyedropper
    for (f in GestureFunction.entries) {
        SectionHeader(f.label)
        Text(f.description, color = LocalUi.current.textMuted, fontSize = 12.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 2.dp))
        for (t in f.allowed) {
            TriggerRow(t.label, GestureMap.isOn(p, f, t), GestureMap.conflicts(p, f, t)) { on -> GestureMap.set(p, f, t, on) }
        }
        if (GestureMap.isOn(p, f, GestureTrigger.TOUCH_HOLD)) {
            if (f == GestureFunction.EYEDROPPER) {
                SliderRow("Verzögerung", p.holdDelayMs.toFloat(), 0f..1500f, { "%.2f s".format(it / 1000f) },
                    onChange = { p.holdDelayMs = it.roundToInt() }, onDone = { p.save() })
            } else {
                SliderRow("Verzögerung", p.holdMenuDelayMs.toFloat(), 0f..1500f, { "%.2f s".format(it / 1000f) },
                    onChange = { p.holdMenuDelayMs = it.roundToInt() }, onDone = { p.save() })
            }
        }
        FwDivider()
    }
    SectionHeader("QuickShape")
    SwitchRow("Zeichnen & halten", p.quickShapeEnabled, subtitle = "Strich wird nach kurzem Halten zur Form") {
        p.quickShapeEnabled = it; p.save()
    }
    SliderRow("Verzögerung", p.quickShapeDelayMs.toFloat(), 0f..1500f, { "%.2f s".format(it / 1000f) },
        onChange = { p.quickShapeDelayMs = it.roundToInt() }, onDone = { p.save() })
    FwDivider()
    SectionHeader("Rückgängig & Wiederholen")
    SwitchRow("Tippen zum Rückgängigmachen", p.gestureTapUndo, subtitle = "2 Finger: rückgängig · 3 Finger: wiederholen") {
        p.gestureTapUndo = it; p.save()
    }
    SliderRow("Verzögerung schnelles Rückgängig", p.rapidUndoDelayMs.toFloat(), 0f..1500f, { "%.2f s".format(it / 1000f) },
        onChange = { p.rapidUndoDelayMs = it.roundToInt() }, onDone = { p.save() })
    FwDivider()
    SectionHeader("Weitere Gesten")
    SwitchRow("Ebene leeren", p.gestureScrubClear, subtitle = "3 Finger hin und her schrubben") { p.gestureScrubClear = it; p.save() }
    SwitchRow("Kopieren & Einfügen", p.gestureCopyPaste, subtitle = "3 Finger nach unten wischen") { p.gestureCopyPaste = it; p.save() }
    SwitchRow("Vollbild", p.gestureFullscreen, subtitle = "4 Finger tippen") { p.gestureFullscreen = it; p.save() }
    FwDivider()
    SectionHeader("Allgemein")
    SwitchRow("Drehen beim Zoomen", p.rotateWithPinch, subtitle = "Zwei-Finger-Geste dreht die Leinwand mit") {
        p.rotateWithPinch = it; p.save()
    }
    SwitchRow("Touch-Aktionen deaktivieren", !p.fingerPainting, subtitle = "Finger steuern nur Gesten, nur der Stift malt") {
        p.fingerPainting = !it; p.save()
    }
    ActionRow("Gestensteuerung zurücksetzen") { GestureMap.reset(p) }
    Spacer(Modifier.height(10.dp))
}

/** Schalterzeile für einen Auslöser, mit gelbem Warnsymbol bei Konflikt. */
@Composable
private fun TriggerRow(label: String, checked: Boolean, conflict: Boolean, onChange: (Boolean) -> Unit) {
    val ui = LocalUi.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = ui.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (conflict) {
            Icon(AppIcons.Warning, "Auslöser auch anderer Funktion zugewiesen", tint = ui.warning,
                modifier = Modifier.padding(end = 8.dp).size(20.dp))
        }
        androidx.compose.material3.Switch(
            checked = checked, onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = ui.accent, checkedThumbColor = Color.White),
        )
    }
}
