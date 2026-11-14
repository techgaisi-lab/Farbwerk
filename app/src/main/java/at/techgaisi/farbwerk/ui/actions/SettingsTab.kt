package at.techgaisi.farbwerk.ui.actions

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import at.techgaisi.farbwerk.input.GestureFunction
import at.techgaisi.farbwerk.input.GestureMap
import at.techgaisi.farbwerk.input.GestureTrigger
import at.techgaisi.farbwerk.prefs.Prefs
import at.techgaisi.farbwerk.selection.SelectionPrefs
import at.techgaisi.farbwerk.stylus.StylusButtonAction
import at.techgaisi.farbwerk.ui.icons.AppIcons
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Seiten des Einstellungen-Reiters; Unterseiten öffnen sich per Navigationszeile, „‹ Einstellungen“ führt zurück. */
private enum class SettingsPage(val title: String) {
    MAIN("Einstellungen"),
    CURSOR("Cursor-Einstellungen"),
    PRESSURE("Druck und Glättung"),
    GESTURES("Gestensteuerung"),
    STYLUS("S Pen"),
}

private object SettingsMemory { var page by mutableStateOf(SettingsPage.MAIN) }

/** Springt im Einstellungen-Reiter zur Startseite zurück (z. B. erneutes Tippen auf den Tab). */
internal fun resetSettingsPage() { SettingsMemory.page = SettingsPage.MAIN }

private val DEFAULT_CURVE = listOf(0.25f, 0.25f, 0.75f, 0.75f)

/** Aktionen → Einstellungen (inkl. Unterseiten Cursor, Druck und Glättung, Gestensteuerung, S Pen). */
@Composable
internal fun SettingsTab(state: EditorState) {
    val p = state.prefs
    // Zurück-Taste schließt zuerst die Unterseite
    BackHandler(enabled = SettingsMemory.page != SettingsPage.MAIN) { SettingsMemory.page = SettingsPage.MAIN }
    AnimatedContent(
        targetState = SettingsMemory.page,
        transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            val dir = if (forward) 1 else -1
            (slideInHorizontally(tween(220)) { w -> dir * w } + fadeIn(tween(160)))
                .togetherWith(slideOutHorizontally(tween(220)) { w -> -dir * w / 3 } + fadeOut(tween(120)))
                .using(SizeTransform(clip = true))
        },
        label = "settingsPage",
    ) { page ->
        when (page) {
            SettingsPage.MAIN -> MainPage(p)
            SettingsPage.CURSOR -> SubPage(page) { CursorPage(p) }
            SettingsPage.PRESSURE -> SubPage(page) { PressurePage(p) }
            SettingsPage.GESTURES -> SubPage(page) { GestureControlPage(p) }
            SettingsPage.STYLUS -> SubPage(page) { StylusPage(p) }
        }
    }
}

// =====================================================================================
// Startseite
// =====================================================================================

@Composable
private fun MainPage(p: Prefs) {
    ActionPage {
        ActionGroup {
            SwitchRow("Helle Oberfläche", p.lightInterface) { p.lightInterface = it; p.save() }
            SwitchRow("Rechtshändige Oberfläche", p.sidebarRight) { p.sidebarRight = it; p.save() }
            SwitchRow("Dynamische Pinselskalierung", p.dynamicBrushScaling) { p.dynamicBrushScaling = it; p.save() }
            SwitchRow("Leinwand projizieren", p.projectCanvas) { p.projectCanvas = it; p.save() }
            SwitchRow("Pinselcursor", p.brushCursor) { p.brushCursor = it; p.save() }
            // Wie im Vorbild: ausgegraut, solange der Pinselcursor aus ist
            ActionRow("Erweiterte Cursoreinstellungen", enabled = p.brushCursor) { SettingsMemory.page = SettingsPage.CURSOR }
        }
        ActionGroup {
            SwitchRow("Seitenleiste anzeigen", p.sidebarVisible) { p.sidebarVisible = it; p.save() }
            if (p.sidebarOffset != 0f) {
                ActionRow("Seitenleiste zentrieren") { p.sidebarOffset = 0f; p.save() }
            }
            SwitchRow("Automatisches Vollbild", p.autoFullscreen) { p.autoFullscreen = it; p.save() }
            if (p.autoFullscreen) {
                SliderRow("Rückkehr nach", p.autoFullscreenDelayMs.toFloat(), 200f..3000f, { "%.1f s".format(it / 1000f) },
                    onChange = { p.autoFullscreenDelayMs = it.roundToInt() }, onDone = { p.save() })
            }
            SwitchRow("Mit Finger malen", p.fingerPainting) { p.fingerPainting = it; p.save() }
        }
        // Navigationszeilen als eigene Gruppe ohne Pfeil (UI_SPEC §6)
        ActionGroup {
            ActionRow("Druck und Glättung") { SettingsMemory.page = SettingsPage.PRESSURE }
            ActionRow("Gestensteuerung") { SettingsMemory.page = SettingsPage.GESTURES }
            ActionRow("S Pen") { SettingsMemory.page = SettingsPage.STYLUS }
        }
        ActionGroup {
            SliderRow("Sichtbarkeit der Auswahlmaske", SelectionPrefs.maskOpacity, 0f..1f,
                { "${(it * 100).roundToInt()} %" },
                onChange = { SelectionPrefs.updateMaskOpacity(it); p.selectionMaskOpacity = it },
                onDone = { p.save() })
            SwitchRow("Vereinfachtes Transformieren-Rückgängig", p.simplifiedUndo) { p.simplifiedUndo = it; p.save() }
            SliderRow("Rückgängig-Schritte", p.undoLimit.toFloat(), 10f..500f, { it.roundToInt().toString() },
                onChange = { p.undoLimit = it.roundToInt() }, onDone = { p.save() })
        }
        ActionGroup(footer = "Kurz tippen öffnet das QuickMenu; halten und ziehen verschiebt die Leinwand.") {
            SwitchRow("Leertaste öffnet QuickMenu", p.spaceQuickMenu) { p.spaceQuickMenu = it; p.save() }
        }
    }
}

// =====================================================================================
// Unterseiten
// =====================================================================================

/** Rahmen einer Unterseite: Zurück-Zeile „‹ Einstellungen“ (Akzent) links, Seitentitel rechts. */
@Composable
private fun SubPage(page: SettingsPage, content: @Composable () -> Unit) {
    val t = actionTokens()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(38.dp).padding(start = 6.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clickable(remember { MutableInteractionSource() }, indication = null) {
                    SettingsMemory.page = SettingsPage.MAIN
                }.padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(ActionIcons.Back, "Zurück", tint = t.accent, modifier = Modifier.size(20.dp))
                Text("Einstellungen", color = t.accent, fontSize = 15.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(page.title, color = t.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

/** Erweiterte Cursoreinstellungen: Sichtbarkeit und Umrissstil als Auswahllisten. */
@Composable
private fun CursorPage(p: Prefs) {
    ActionPage {
        ActionGroup(header = "Cursor anzeigen") {
            listOf(0 to "Beim Schweben", 1 to "Beim Malen", 2 to "Beides").forEach { (id, label) ->
                CheckRow(label, p.cursorVisibility == id) { p.cursorVisibility = id; p.save() }
            }
        }
        ActionGroup(header = "Umrissstil") {
            listOf(0 to "Hoher Kontrast", 1 to "Aktive Farbe", 2 to "Pro Pinsel").forEach { (id, label) ->
                CheckRow(label, p.cursorStyle == id) { p.cursorStyle = id; p.save() }
            }
        }
    }
}

@Composable
private fun PressurePage(p: Prefs) {
    ActionPage {
        ActionGroup(header = "Druckkurve") {
            PressureCurveEditor(p)
            ActionRow("Kurve zurücksetzen", accent = true) { p.pressureCurve = DEFAULT_CURVE; p.save() }
        }
        ActionGroup(header = "Glättung") {
            SliderRow("Stabilisierung", p.pressureSmoothing, 0f..1f, { "${(it * 100).roundToInt()} %" },
                onChange = { p.pressureSmoothing = it }, onDone = { p.save() })
            SliderRow("Bewegungsfilterung", p.motionFilteringAmount, 0f..1f, { "${(it * 100).roundToInt()} %" },
                onChange = { p.motionFilteringAmount = it }, onDone = { p.save() })
        }
        ActionGroup {
            ActionRow("Alle Werte zurücksetzen", accent = true) {
                p.pressureCurve = DEFAULT_CURVE
                p.pressureSmoothing = Prefs.DEFAULT_PRESSURE_SMOOTHING; p.motionFilteringAmount = 0f; p.save()
            }
        }
    }
}

/** S Pen: Seitentaste, Handballenerkennung, Schwebevorschau, Bewegungsvorhersage, Druckglättung. */
@Composable
private fun StylusPage(p: Prefs) {
    ActionPage {
        ActionGroup(header = "Seitentaste + Berühren", footer = "Taste gedrückt halten und Stift aufsetzen.") {
            StylusButtonAction.entries.forEach { a ->
                CheckRow(touchLabel(a), p.stylusButtonAction == a.id) { p.stylusButtonAction = a.id; p.save() }
            }
        }
        ActionGroup(header = "Seitentaste in der Luft", footer = "Taste klicken, während der Stift über dem Display schwebt.") {
            StylusButtonAction.entries.forEach { a ->
                CheckRow(hoverLabel(a), p.stylusButtonHoverAction == a.id) { p.stylusButtonHoverAction = a.id; p.save() }
            }
        }
        ActionGroup(footer = "Sobald der Stift benutzt wurde, steuern Finger nur noch Gesten.") {
            SwitchRow("Nur Stift malt nach Stifterkennung", p.stylusOnlyAfterPen) { p.stylusOnlyAfterPen = it; p.save() }
        }
        ActionGroup(header = "Handballenerkennung",
            footer = "Finger-Berührungen in Stiftnähe und großflächige Kontakte ignorieren.") {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Segmented(listOf(0 to "Aus", 1 to "Normal", 2 to "Stark"), p.palmRejection.coerceIn(0, 2),
                    Modifier.fillMaxWidth()) { p.palmRejection = it; p.save() }
            }
        }
        ActionGroup {
            SwitchRow("Schwebevorschau", p.hoverPreview) { p.hoverPreview = it; p.save() }
            SwitchRow("Bewegungsvorhersage", p.motionPrediction) { p.motionPrediction = it; p.save() }
            SliderRow("Druckglättung", p.pressureSmoothing, 0f..1f, { "${(it * 100).roundToInt()} %" },
                onChange = { p.pressureSmoothing = it }, onDone = { p.save() })
        }
    }
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

/**
 * Editor für die Druckkurve: kubische Bézierkurve von (0,0) nach (1,1) mit zwei ziehbaren Griffen.
 * x = Stiftdruck, y = Ergebnis. Schreibt live in [Prefs.pressureCurve], speichert beim Loslassen.
 */
@Composable
private fun PressureCurveEditor(p: Prefs) {
    val t = actionTokens()
    var dragging by remember { mutableIntStateOf(-1) }
    val curve = p.pressureCurve.takeIf { it.size >= 4 } ?: DEFAULT_CURVE
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(1.4f)
                .pointerInput(Unit) {
                    val pad = 14.dp.toPx()
                    fun toCurve(o: Offset): Pair<Float, Float> {
                        val w = size.width - 2 * pad; val h = size.height - 2 * pad
                        return ((o.x - pad) / w).coerceIn(0f, 1f) to (1f - (o.y - pad) / h).coerceIn(0f, 1f)
                    }
                    detectDragGestures(
                        onDragStart = { o ->
                            val c = p.pressureCurve.takeIf { it.size >= 4 } ?: DEFAULT_CURVE
                            val w = size.width - 2 * pad; val h = size.height - 2 * pad
                            val d1 = hypot(o.x - (pad + c[0] * w), o.y - (pad + (1 - c[1]) * h))
                            val d2 = hypot(o.x - (pad + c[2] * w), o.y - (pad + (1 - c[3]) * h))
                            dragging = if (d1 <= d2) 0 else 1
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val (x, y) = toCurve(change.position)
                            val c = (p.pressureCurve.takeIf { it.size >= 4 } ?: DEFAULT_CURVE).toMutableList()
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
                drawLine(t.separator, pt(f, 0f), pt(f, 1f), 1f)
                drawLine(t.separator, pt(0f, f), pt(1f, f), 1f)
            }
            // Diagonale (linear)
            drawLine(t.textDisabled, pt(0f, 0f), pt(1f, 1f), 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            val p1 = pt(curve[0], curve[1]); val p2 = pt(curve[2], curve[3])
            val start = pt(0f, 0f); val end = pt(1f, 1f)
            drawLine(t.textSecondary, start, p1, 1.5f)
            drawLine(t.textSecondary, end, p2, 1.5f)
            val path = Path().apply { moveTo(start.x, start.y); cubicTo(p1.x, p1.y, p2.x, p2.y, end.x, end.y) }
            drawPath(path, t.accent, style = Stroke(width = 3.dp.toPx()))
            for ((i, c) in listOf(p1, p2).withIndex()) {
                drawCircle(Color.White, radius = if (dragging == i) 10.dp.toPx() else 8.dp.toPx(), center = c)
                drawCircle(t.accent, radius = 4.dp.toPx(), center = c)
            }
        }
        Text("Druck", color = t.textSecondary, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd))
        Text("Ergebnis", color = t.textSecondary, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopStart))
    }
}

/**
 * Gestensteuerung: je Funktion die möglichen Auslöser als Schalter. Ein Auslöser sollte nur einer Funktion
 * gehören – Konflikte zeigen ein gelbes Warnsymbol (es gewinnt die weiter oben stehende Funktion).
 * Halte-Auslöser haben einen Verzögerungsregler.
 */
@Composable
private fun GestureControlPage(p: Prefs) {
    @Suppress("UNUSED_VARIABLE") val raw = p.gestureTriggers
    @Suppress("UNUSED_VARIABLE") val hold = p.gestureHoldEyedropper
    ActionPage {
        for (f in GestureFunction.entries) {
            ActionGroup(header = f.label, footer = f.description) {
                for (tr in f.allowed) {
                    TriggerRow(tr.label, GestureMap.isOn(p, f, tr), GestureMap.conflicts(p, f, tr)) { on -> GestureMap.set(p, f, tr, on) }
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
            }
        }
        ActionGroup(header = "QuickShape") {
            SwitchRow("Zeichnen & halten", p.quickShapeEnabled, subtitle = "Strich wird nach kurzem Halten zur Form") {
                p.quickShapeEnabled = it; p.save()
            }
            SliderRow("Verzögerung", p.quickShapeDelayMs.toFloat(), 0f..1500f, { "%.2f s".format(it / 1000f) },
                onChange = { p.quickShapeDelayMs = it.roundToInt() }, onDone = { p.save() })
        }
        ActionGroup(header = "Rückgängig & Wiederholen") {
            SwitchRow("Tippen zum Rückgängigmachen", p.gestureTapUndo, subtitle = "2 Finger: rückgängig · 3 Finger: wiederholen") {
                p.gestureTapUndo = it; p.save()
            }
            SliderRow("Verzögerung schnelles Rückgängig", p.rapidUndoDelayMs.toFloat(), 0f..1500f, { "%.2f s".format(it / 1000f) },
                onChange = { p.rapidUndoDelayMs = it.roundToInt() }, onDone = { p.save() })
        }
        ActionGroup(header = "Weitere Gesten") {
            SwitchRow("Ebene leeren", p.gestureScrubClear, subtitle = "3 Finger hin und her schrubben") { p.gestureScrubClear = it; p.save() }
            SwitchRow("Kopieren & Einfügen", p.gestureCopyPaste, subtitle = "3 Finger nach unten wischen") { p.gestureCopyPaste = it; p.save() }
            SwitchRow("Vollbild", p.gestureFullscreen, subtitle = "4 Finger tippen") { p.gestureFullscreen = it; p.save() }
        }
        ActionGroup(header = "Allgemein") {
            SwitchRow("Drehen beim Zoomen", p.rotateWithPinch, subtitle = "Zwei-Finger-Geste dreht die Leinwand mit") {
                p.rotateWithPinch = it; p.save()
            }
            SwitchRow("Touch-Aktionen deaktivieren", !p.fingerPainting, subtitle = "Finger steuern nur Gesten, nur der Stift malt") {
                p.fingerPainting = !it; p.save()
            }
        }
        ActionGroup {
            ActionRow("Gestensteuerung zurücksetzen", accent = true) { GestureMap.reset(p) }
        }
    }
}

/** Schalterzeile für einen Auslöser, mit gelbem Warnsymbol bei Konflikt. */
@Composable
private fun TriggerRow(label: String, checked: Boolean, conflict: Boolean, onChange: (Boolean) -> Unit) {
    val t = actionTokens()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 38.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null) { onChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = t.text, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(end = 8.dp))
        if (conflict) {
            Icon(AppIcons.Warning, "Auslöser auch anderer Funktion zugewiesen", tint = LocalUi.current.warning,
                modifier = Modifier.padding(end = 8.dp).size(18.dp))
        }
        FwToggle(checked, null)
    }
}
