package at.techgaisi.farbwerk.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.brush.BP
import at.techgaisi.farbwerk.brush.BrushTextures
import at.techgaisi.farbwerk.input.GestureFunction
import at.techgaisi.farbwerk.input.GestureMap
import at.techgaisi.farbwerk.input.GestureTrigger
import at.techgaisi.farbwerk.model.Tool
import at.techgaisi.farbwerk.prefs.Prefs
import at.techgaisi.farbwerk.ui.icons.AppIcons
import at.techgaisi.farbwerk.ui.quickmenu.QuickMenuController
import at.techgaisi.farbwerk.ui.shell.FloatingEyedropper
import at.techgaisi.farbwerk.ui.theme.LocalUi
import at.techgaisi.farbwerk.ui.theme.UiMetrics
import at.techgaisi.farbwerk.ui.theme.UiMotion
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Zustand der Regler-Vorschaublase. Die Blase wird auf Bildschirmebene gezeichnet ([SidebarBubble]),
 * damit sie neben der Seitenleiste liegen und trotzdem Berührungen (+ / −) annehmen kann.
 */
object SidebarBubbleState {
    /** true = Größenregler, false = Deckkraftregler. */
    var forSize by mutableStateOf(true)
    var visible by mutableStateOf(false)
    var value by mutableFloatStateOf(0f)
    /** Mitte der Blase (vertikal, Fenster-Koordinaten). */
    var centerY by mutableFloatStateOf(0f)
    /** Seitenleisten-Rechteck in Fenster-Koordinaten. */
    var sidebarBounds by mutableStateOf(Rect.Zero)
    /** Markierung, die gerade angetippt wurde (Blase zeigt dann „−“), sonst null. */
    var tappedMark by mutableStateOf<Float?>(null)
    /** Erhöht bei jeder Berührung – startet den Ausblend-Zeitgeber neu. */
    var touchSerial by mutableIntStateOf(0)
    /** Regler wird gerade gezogen. */
    var dragging by mutableStateOf(false)
}

/** Werte, die die Seitenleiste gerade steuert (Pinsel oder alternative Quelle wie Klonen/Verflüssigen). */
private class SidebarValues(
    val top: Float,
    val bottom: Float,
    val setTop: (Float) -> Unit,
    val setBottom: (Float) -> Unit,
    /** Schlüssel für Größen-/Deckkraftmarken (null = keine Marken, z. B. bei alternativer Quelle). */
    val marksKey: String?,
)

private fun sidebarValues(state: EditorState): SidebarValues {
    val src = state.sidebarSource
    if (src != null) {
        return SidebarValues(src.top, src.bottom,
            { src.top = it; state.toolSettingsVersion++ }, { src.bottom = it; state.toolSettingsVersion++ }, null)
    }
    val s = state.currentToolSettings
    return SidebarValues(
        s.size, s.opacity,
        { s.size = it; state.toolSettingsVersion++ },
        { s.opacity = it; state.toolSettingsVersion++ },
        "${state.brushTool.name}/${s.brushId}",
    )
}

/** Höhe eines Reglers so wählen, dass die ganze Leiste in [available] passt. */
fun sidebarSliderHeight(available: Dp): Dp {
    // Ränder 16 + Abstände 3·10 + Modifizierer 26 + 2 Pfeile à 34 ≈ 140 dp
    val free = (available - 140.dp - 24.dp) / 2
    return free.coerceIn(UiMetrics.sidebarSliderMin, UiMetrics.sidebarSlider)
}

/** Gesamthöhe der Seitenleiste bei gegebener Reglerhöhe. */
fun sidebarTotalHeight(slider: Dp): Dp = slider * 2 + 140.dp

/**
 * Seitenleiste (Anleitung §3, §30.3): abgerundete Kapsel (Breite 38 dp, Radius = halbe Breite) mit
 * Größenregler (oben), Modifizierer-Taste (Mitte), Deckkraftregler (unten), Rückgängig und Wiederholen.
 *
 *  - Regler: Tippen springt, Ziehen mit seitlichem Abstand = feiner; Marken rasten ein.
 *  - Vorschaublase mit Pinselabdruck in aktueller Größe bzw. Deckkraft und Prozentwert (+ / − für Marken).
 *  - Modifizierer: Tippen und Halten (+ Leinwand berühren) lösen die in der Gestensteuerung belegten
 *    Funktionen aus (Standard: Pipette). Waagrecht über die Taste ziehen = Leiste verschieben.
 *  - Rückgängig/Wiederholen: Tippen = ein Schritt, Halten = fortlaufend (~9 Schritte/s).
 *  - Steuert [EditorState.sidebarSource], wenn gesetzt (Klonen, Verflüssigen, Stift-Modus …).
 */
@Composable
fun Sidebar(state: EditorState, modifier: Modifier = Modifier, sliderHeight: Dp = UiMetrics.sidebarSlider) {
    val ui = LocalUi.current
    @Suppress("UNUSED_VARIABLE") val v = state.toolSettingsVersion
    @Suppress("UNUSED_VARIABLE") val t = state.tool
    @Suppress("UNUSED_VARIABLE") val mv = state.prefs.marksVersion
    val values = sidebarValues(state)
    val marksKey = values.marksKey
    val sizeMarks = marksKey?.let { state.prefs.sliderMarks(it, false) } ?: emptyList()
    val opacityMarks = marksKey?.let { state.prefs.sliderMarks(it, true) } ?: emptyList()
    val shape = RoundedCornerShape(50)

    Column(
        modifier
            .width(UiMetrics.sidebarWidth)
            .onGloballyPositioned { SidebarBubbleState.sidebarBounds = it.boundsInWindow() }
            .clip(shape)
            .background(ui.bar)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SidebarSlider(values.top, sizeMarks, true, sliderHeight, values.setTop)
        Spacer(Modifier.height(10.dp))
        ModifierButton(state)
        Spacer(Modifier.height(10.dp))
        SidebarSlider(values.bottom, opacityMarks, false, sliderHeight, values.setBottom)
        Spacer(Modifier.height(10.dp))
        @Suppress("UNUSED_VARIABLE") val hv = state.undo.version
        RepeatButton(state, AppIcons.Undo, "Rückgängig", state.undo.canUndo) { state.undo.undo() }
        RepeatButton(state, AppIcons.Redo, "Wiederholen", state.undo.canRedo) { state.undo.redo() }
    }
}

/**
 * Vertikaler Regler der Seitenleiste. Präzise Steuerung: Finger beim Ziehen seitlich vom Regler wegbewegen –
 * je weiter weg, desto feiner die Änderung. In der Nähe einer Marke rastet der Wert ein (mit Haptik).
 */
@Composable
private fun SidebarSlider(
    value: Float,
    marks: List<Float>,
    forSize: Boolean,
    height: Dp,
    onChange: (Float) -> Unit,
) {
    val ui = LocalUi.current
    val view = LocalView.current
    var dragging by remember { mutableStateOf(false) }
    var current by remember { mutableFloatStateOf(value) }
    if (!dragging) current = value
    val change by rememberUpdatedState(onChange)
    val marksNow by rememberUpdatedState(marks)
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val thumbScale by animateFloatAsState(if (dragging) 1.12f else 1f, UiMotion.quick(), label = "thumb")
    val thumbColor by animateColorAsState(if (dragging) Color.White else ui.sliderThumb, UiMotion.quick(), label = "thumbC")

    fun publish(v: Float, thumbY: Float) {
        val c = coords[0]?.takeIf { it.isAttached } ?: return
        SidebarBubbleState.forSize = forSize
        SidebarBubbleState.value = v
        SidebarBubbleState.centerY = c.positionInWindow().y + thumbY
        SidebarBubbleState.visible = true
        SidebarBubbleState.touchSerial++
    }

    Canvas(
        Modifier
            .width(UiMetrics.sidebarWidth)
            .height(height)
            .onGloballyPositioned { coords[0] = it }
            .pointerInput(forSize) {
                val thumbH = 13.dp.toPx()
                val snapPx = 7.dp.toPx()
                fun yFor(v: Float) = (1f - v) * (size.height - thumbH) + thumbH / 2
                fun vFor(y: Float) = (1f - (y - thumbH / 2) / (size.height - thumbH)).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    dragging = true
                    SidebarBubbleState.dragging = true
                    // Marke angetippt? → einrasten und „−“ anbieten
                    val hitMark = marksNow.firstOrNull { abs(yFor(it) - down.position.y) <= snapPx * 1.4f }
                    var v = hitMark ?: vFor(down.position.y)
                    SidebarBubbleState.tappedMark = hitMark
                    current = v; change(v); publish(v, yFor(v))
                    val startX = down.position.x
                    var snapped: Float? = hitMark
                    var moved = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) { ch.consume(); break }
                        val d = ch.positionChange()
                        if (d.y != 0f) {
                            if (!moved && abs(ch.position.y - down.position.y) > viewConfiguration.touchSlop / 2) {
                                moved = true
                                SidebarBubbleState.tappedMark = null
                            }
                            val dist = abs(ch.position.x - startX) / density
                            val fine = 1f / (1f + (dist / 40f).coerceAtLeast(0f))
                            v = (v - d.y / (size.height - thumbH) * fine).coerceIn(0f, 1f)
                            // Einrasten an Marken
                            val near = marksNow.firstOrNull { abs(yFor(it) - yFor(v)) <= snapPx }
                            if (near != null && near != snapped) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            snapped = near
                            val shown = near ?: v
                            current = shown; change(shown); publish(shown, yFor(shown))
                        }
                        ch.consume()
                    }
                    dragging = false
                    SidebarBubbleState.dragging = false
                    SidebarBubbleState.touchSerial++
                }
            }
    ) {
        val thumbH = 13.dp.toPx()
        val trackW = 4.dp.toPx()
        val cx = size.width / 2
        // Rinne
        drawRoundRect(ui.sliderTrack, Offset(cx - trackW / 2, thumbH / 2), Size(trackW, size.height - thumbH), CornerRadius(trackW / 2))
        // Marken
        val markW = 12.dp.toPx()
        for (m in marks) {
            val y = (1f - m) * (size.height - thumbH) + thumbH / 2
            drawRoundRect(ui.icon.copy(alpha = 0.55f), Offset(cx - markW / 2, y - 1.dp.toPx()), Size(markW, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
        }
        // Griff
        val cy = (1f - current) * (size.height - thumbH) + thumbH / 2
        val tw = 24.dp.toPx() * thumbScale
        val th = thumbH * thumbScale
        drawRoundRect(Color.Black.copy(alpha = 0.25f), Offset(cx - tw / 2, cy - th / 2 + 1.dp.toPx()), Size(tw, th), CornerRadius(4.dp.toPx()))
        drawRoundRect(thumbColor, Offset(cx - tw / 2, cy - th / 2), Size(tw, th), CornerRadius(4.dp.toPx()))
    }
}

/**
 * Modifizierer-Taste (kleines Quadrat, 26 dp, Radius 6 dp).
 *  - Tippen: Funktion aus „Modifizierer tippen“ (Standard: schwebende Pipette an der zuletzt bemalten Stelle).
 *  - Halten + Leinwand berühren: Funktion aus „Modifizierer halten + Berühren“ (Standard: Pipette).
 *  - Waagrecht vom Rand über die Taste ziehen, dann senkrecht: ganze Leiste verschieben.
 */
@Composable
private fun ModifierButton(state: EditorState) {
    val ui = LocalUi.current
    var held by remember { mutableStateOf(false) }
    val active = held || state.eyedropperArmed || FloatingEyedropper.active
    val bg by animateColorAsState(if (active) ui.accent else ui.panelElevated, UiMotion.quick(), label = "mod")
    val shape = RoundedCornerShape(UiMetrics.modifierRadius)
    Box(
        Modifier
            .size(UiMetrics.sidebarWidth, UiMetrics.modifierSize + 8.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val downTime = down.uptimeMillis
                    held = true
                    val cv = state.canvasView
                    val touchFn = GestureMap.owner(state.prefs, GestureTrigger.MODIFIER_TOUCH)
                    cv?.modifierUsed = false
                    cv?.heldModifier = touchFn
                    val armedBefore = state.eyedropperArmed
                    if (touchFn == GestureFunction.EYEDROPPER) state.eyedropperArmed = true
                    var moveMode = false
                    var startOffset = state.prefs.sidebarOffset
                    var accY = 0f
                    var up: PointerInputChange? = null
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) { up = ch; ch.consume(); break }
                        val dx = ch.position.x - down.position.x
                        val dy = ch.position.y - down.position.y
                        if (!moveMode && abs(dx) > viewConfiguration.touchSlop && abs(dx) > abs(dy) * 1.2f) {
                            // Verschieben der Leiste beginnt: Modifizierer-Wirkung zurücknehmen
                            moveMode = true
                            cv?.heldModifier = null
                            if (touchFn == GestureFunction.EYEDROPPER) state.eyedropperArmed = armedBefore
                            startOffset = state.prefs.sidebarOffset
                            accY = 0f
                        }
                        if (moveMode && ch.positionChanged()) {
                            accY += ch.positionChange().y
                            val travel = SidebarMove.travelPx.coerceAtLeast(1f)
                            state.prefs.sidebarOffset = (startOffset + accY / travel).coerceIn(-1f, 1f)
                        }
                        ch.consume()
                    }
                    held = false
                    val used = cv?.modifierUsed == true
                    cv?.heldModifier = null
                    if (moveMode) {
                        state.prefs.save()
                        return@awaitEachGesture
                    }
                    if (touchFn == GestureFunction.EYEDROPPER && !used) state.eyedropperArmed = false
                    val isTap = up != null && !used && up.uptimeMillis - downTime < 350
                    if (isTap) performModifierTap(state)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(UiMetrics.modifierSize)
                .graphicsLayer { val s = if (held) 0.92f else 1f; scaleX = s; scaleY = s }
                .clip(shape)
                .background(bg)
                .border(0.5.dp, ui.divider, shape)
        )
    }
}

/** Modifizierer wurde kurz angetippt (Seitenleiste, Alt-Taste). */
internal fun performModifierTap(state: EditorState) {
    if (FloatingEyedropper.active) {
        FloatingEyedropper.finish(state, commit = false)
        return
    }
    when (GestureMap.owner(state.prefs, GestureTrigger.MODIFIER_TAP)) {
        GestureFunction.EYEDROPPER -> FloatingEyedropper.start(state)
        GestureFunction.QUICKMENU -> QuickMenuController.openAtLastPoint(state)
        else -> {}
    }
}

/** Verschiebeweg der Seitenleiste (vom Editor-Bildschirm gesetzt). */
object SidebarMove {
    /** Pixel, um die die Leiste aus der Mitte maximal nach oben/unten verschoben werden kann. */
    var travelPx = 1f
}

/** Rückgängig/Wiederholen: Tippen = ein Schritt, Halten = nach der Verzögerung fortlaufend. */
@Composable
private fun RepeatButton(
    state: EditorState,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    action: () -> Boolean,
) {
    val ui = LocalUi.current
    var pressed by remember { mutableStateOf(false) }
    val tint by animateColorAsState(if (enabled) ui.icon else ui.iconMuted, UiMotion.quick(), label = "rep")
    val act by rememberUpdatedState(action)
    Box(
        Modifier
            .size(UiMetrics.undoButton)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    pressed = true
                    var up: PointerInputChange? = null
                    val finished = withTimeoutOrNull(state.prefs.rapidUndoDelayMs.toLong()) {
                        up = waitForUpOrCancellation(); true
                    }
                    if (finished != null) {
                        pressed = false
                        up?.let { it.consume(); act() }
                        return@awaitEachGesture
                    }
                    // Halten: fortlaufend wiederholen, bis losgelassen wird
                    var running = true
                    while (running) {
                        if (!act()) break
                        val r = withTimeoutOrNull(110L) { waitForUpOrCancellation(); true }
                        if (r != null) running = false
                    }
                    if (running) waitForUpOrCancellation()
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp).graphicsLayer {
            val s = if (pressed) 0.88f else 1f; scaleX = s; scaleY = s; alpha = if (pressed) 0.6f else 1f
        })
    }
}

/**
 * Vorschaublase neben der Seitenleiste (~90 dp, abgerundetes Quadrat): Pinselabdruck in aktueller Größe
 * (bzw. mit aktueller Deckkraft) und Prozentwert. Erscheint sofort beim Berühren und verschwindet kurz nach
 * dem Loslassen; solange sie sichtbar ist, legt „+“ eine Marke an bzw. entfernt „−“ die angetippte Marke.
 */
@Composable
fun SidebarBubble(state: EditorState) {
    val b = SidebarBubbleState
    val ui = LocalUi.current
    val density = LocalDensity.current
    val serial = b.touchSerial
    LaunchedEffect(serial, b.dragging) {
        if (!b.dragging && b.visible) {
            delay(if (state.sidebarSource == null) 1300L else UiMotion.BUBBLE_HIDE_DELAY_MS)
            b.visible = false
            b.tappedMark = null
        }
    }
    val alpha by animateFloatAsState(if (b.visible) 1f else 0f,
        if (b.visible) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(180), label = "bubble")
    if (alpha <= 0f) return
    @Suppress("UNUSED_VARIABLE") val tsv = state.toolSettingsVersion
    @Suppress("UNUSED_VARIABLE") val mv = state.prefs.marksVersion
    val values = sidebarValues(state)
    val right = state.prefs.sidebarRight
    val bounds = b.sidebarBounds
    val sizePx = with(density) { UiMetrics.bubbleSize.toPx() }
    val gapPx = with(density) { 10.dp.toPx() }
    val x = if (right) bounds.left - gapPx - sizePx else bounds.right + gapPx
    val y = b.centerY - sizePx / 2
    val shape = RoundedCornerShape(16.dp)
    val value = b.value
    val marksKey = values.marksKey

    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .size(UiMetrics.bubbleSize)
            .graphicsLayer { this.alpha = alpha }
            .shadow(14.dp, shape, ambientColor = ui.shadow, spotColor = ui.shadow)
            .clip(shape)
            .background(ui.panel)
    ) {
        BrushStamp(state, b.forSize, value, Modifier.fillMaxSize().padding(bottom = 16.dp))
        Text(
            "${(value * 100).roundToInt()} %",
            color = ui.text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            style = TextStyle(fontFeatureSettings = "tnum"),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
        )
        if (marksKey != null) {
            val marks = state.prefs.sliderMarks(marksKey, !b.forSize)
            val tapped = b.tappedMark
            val canAdd = marks.size < Prefs.MAX_MARKS && marks.none { abs(it - value) < 0.01f }
            if (tapped != null || canAdd) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape)
                        .background(ui.panelElevated)
                        .clickable {
                            if (tapped != null) {
                                state.prefs.setSliderMarks(marksKey, !b.forSize, marks.filter { abs(it - tapped) > 0.001f })
                                b.tappedMark = null
                            } else {
                                state.prefs.setSliderMarks(marksKey, !b.forSize, marks + value)
                            }
                            b.touchSerial++
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (tapped != null) AppIcons.Minus else AppIcons.Plus,
                        if (tapped != null) "Marke entfernen" else "Marke speichern",
                        tint = if (tapped != null) ui.destructive else ui.accent, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/**
 * Pinselabdruck in der Vorschaublase: Form des aktiven Pinsels in der Größe, mit der er auf dem Bildschirm
 * malen würde (größer als die Blase → angeschnitten), bzw. für die Deckkraft mittelgroß mit dieser Deckkraft.
 */
@Composable
private fun BrushStamp(state: EditorState, forSize: Boolean, value: Float, modifier: Modifier) {
    val ui = LocalUi.current
    val brush = if (state.sidebarSource == null) state.brushEngine.brushFor(state.brushTool) else null
    val shapeImg = remember(brush?.int(BP.SHAPE_KIND)) {
        brush?.let { BrushTextures.shape(it.int(BP.SHAPE_KIND)).asImageBitmap() }
    }
    Canvas(modifier) {
        val c = center
        val maxD = size.minDimension * 0.86f
        if (brush == null || shapeImg == null) {
            // Alternative Quelle: neutraler Kreis proportional zum Wert
            val d = if (forSize) maxD * (0.12f + 0.88f * value) else maxD * 0.6f
            drawCircle(ui.icon.copy(alpha = if (forSize) 1f else value.coerceIn(0.04f, 1f)), d / 2, c)
            return@Canvas
        }
        val settings = state.currentToolSettings
        val sizeVal = if (forSize) value else settings.size
        val docD = state.brushEngine.diameterFor(brush, sizeVal)
        val m = state.canvasView?.docToViewMatrix()
        val scale = if (m != null) {
            val v = FloatArray(9); m.getValues(v)
            hypot(v[android.graphics.Matrix.MSCALE_X], v[android.graphics.Matrix.MSKEW_Y])
        } else 1f
        val d = if (forSize) (docD * scale).coerceAtLeast(2f) else maxD * 0.62f
        val alpha = if (forSize) 1f else value.coerceIn(0.03f, 1f)
        val roundness = brush[BP.ROUNDNESS].coerceIn(0.05f, 1f)
        val rotation = brush[BP.ROTATION]
        rotate(rotation, c) {
            scale(1f, roundness, c) {
                drawImage(
                    shapeImg,
                    dstOffset = IntOffset((c.x - d / 2).roundToInt(), (c.y - d / 2).roundToInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(d.roundToInt().coerceAtLeast(1), d.roundToInt().coerceAtLeast(1)),
                    alpha = alpha,
                    colorFilter = ColorFilter.tint(ui.text),
                )
            }
        }
    }
}

/**
 * Farbkreis (aktuelle Farbe) oben rechts.
 *
 *  - Kurzes Tippen → [onClick] (Farben-Panel öffnen/schließen). Ausgelöst beim Loslassen, damit ein
 *    Ziehen nicht zusätzlich das Panel umschaltet.
 *  - Ziehen über die Berührungstoleranz hinaus → [onDragStart], [onDrag], [onDragEnd] bzw. [onDragCancel]
 *    mit FENSTER-Koordinaten (z. B. für ColorDrop auf die Leinwand). Compose liefert die Ereignisse auch
 *    dann weiter an diesen Kreis, wenn der Finger längst über der Leinwand (AndroidView) ist.
 *
 * Die Füllfarbe folgt [argb] live (der Aufrufer liest state.color, das löst die Neuzusammensetzung aus).
 */
@Composable
fun ColorDot(
    argb: Int,
    size: Int = 28,
    modifier: Modifier = Modifier,
    onDragStart: ((Offset) -> Unit)? = null,
    onDrag: ((Offset) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    onDragCancel: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val ui = LocalUi.current
    // Aktuelle Lambdas verwenden – pointerInput(Unit) startet nur einmal.
    val click by rememberUpdatedState(onClick)
    val dragStart by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val dragCancel by rememberUpdatedState(onDragCancel)
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Box(
        modifier
            .size(size.dp)
            .onGloballyPositioned { coords[0] = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val slop = viewConfiguration.touchSlop
                    var dragging = false
                    var finished = false
                    fun toWindow(p: Offset): Offset = coords[0]?.takeIf { it.isAttached }?.localToWindow(p) ?: p
                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) {
                                ch.consume()
                                finished = true
                                if (dragging) dragEnd?.invoke() else click()
                                break
                            }
                            if (!dragging && dragStart != null &&
                                (ch.position - down.position).getDistance() > slop
                            ) {
                                dragging = true
                                dragStart?.invoke(toWindow(ch.position))
                            } else if (dragging && ch.positionChanged()) {
                                drag?.invoke(toWindow(ch.position))
                            }
                            ch.consume()
                        }
                    } finally {
                        // Geste ohne regulären Abschluss (Abbruch, Komposition verlassen) → Ziehen verwerfen
                        if (dragging && !finished) dragCancel?.invoke()
                    }
                }
            }
            .clip(CircleShape)
            .background(Color(argb))
            .border(2.dp, ui.icon.copy(alpha = 0.85f), CircleShape)
    )
}
