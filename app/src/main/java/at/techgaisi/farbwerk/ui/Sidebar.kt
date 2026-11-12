package at.techgaisi.farbwerk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.ui.common.ToolIconButton
import at.techgaisi.farbwerk.ui.icons.AppIcons
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Seitenleiste: Größe (oben), Modifizierer-Taste (Mitte), Deckkraft (unten), Rückgängig/Wiederholen.
 */
@Composable
fun Sidebar(state: EditorState, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    @Suppress("UNUSED_VARIABLE") val v = state.toolSettingsVersion
    @Suppress("UNUSED_VARIABLE") val t = state.tool
    val settings = state.currentToolSettings
    Column(
        modifier
            .width(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(ui.bar)
            .border(0.5.dp, ui.divider, RoundedCornerShape(22.dp))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VerticalSlider(
            value = settings.size,
            label = { "${(it * 100).roundToInt()} %" },
            onChange = { settings.size = it; state.toolSettingsVersion++ },
            height = 150,
        )
        Spacer(Modifier.height(10.dp))
        // Modifizierer-Taste (Pipette)
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (state.eyedropperArmed) ui.accent else ui.panelElevated)
                .border(1.dp, ui.divider, RoundedCornerShape(6.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        state.eyedropperArmed = !state.eyedropperArmed
                    }
                }
        )
        Spacer(Modifier.height(10.dp))
        VerticalSlider(
            value = settings.opacity,
            label = { "${(it * 100).roundToInt()} %" },
            onChange = { settings.opacity = it; state.toolSettingsVersion++ },
            height = 150,
        )
        Spacer(Modifier.height(10.dp))
        @Suppress("UNUSED_VARIABLE") val hv = state.undo.version
        ToolIconButton(AppIcons.Undo, "Rückgängig", size = 36.dp,
            tint = if (state.undo.canUndo) ui.icon else ui.iconMuted) { state.undo.undo() }
        ToolIconButton(AppIcons.Redo, "Wiederholen", size = 36.dp,
            tint = if (state.undo.canRedo) ui.icon else ui.iconMuted) { state.undo.redo() }
    }
}

/**
 * Vertikaler Schieberegler. Präzise Steuerung: Finger beim Ziehen seitlich vom Regler wegbewegen –
 * je weiter weg, desto feiner die Änderung.
 */
@Composable
fun VerticalSlider(
    value: Float,
    label: (Float) -> String,
    onChange: (Float) -> Unit,
    height: Int,
) {
    val ui = LocalUi.current
    var dragging by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(value) }
    if (!dragging) current = value
    Box(Modifier.width(30.dp).height(height.dp)) {
        Canvas(
            Modifier
                .width(30.dp).fillMaxHeight()
                .pointerInput(height) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        dragging = true
                        var v = current
                        val h = size.height.toFloat()
                        val startX = down.position.x
                        // Direktes Tippen setzt den Wert
                        v = (1f - down.position.y / h).coerceIn(0f, 1f)
                        current = v; onChange(v)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            val dist = abs(ch.position.x - startX) / density
                            val fine = 1f / (1f + (dist / 40f).coerceAtLeast(0f))
                            v = (v - ch.positionChange().y / h * fine).coerceIn(0f, 1f)
                            current = v; onChange(v)
                            ch.consume()
                        }
                        dragging = false
                    }
                }
        ) {
            val trackW = 6.dp.toPx()
            val x = (size.width - trackW) / 2
            drawRoundRect(ui.panelElevated, Offset(x, 0f), Size(trackW, size.height), CornerRadius(trackW / 2))
            val fillH = size.height * current
            drawRoundRect(ui.icon.copy(alpha = 0.55f), Offset(x, size.height - fillH), Size(trackW, fillH), CornerRadius(trackW / 2))
            val thumbH = 22.dp.toPx(); val thumbW = 26.dp.toPx()
            val cy = (size.height - fillH).coerceIn(thumbH / 2, size.height - thumbH / 2)
            drawRoundRect(Color.White, Offset((size.width - thumbW) / 2, cy - thumbH / 2), Size(thumbW, thumbH), CornerRadius(6.dp.toPx()))
        }
        if (dragging) {
            Box(
                Modifier
                    .offset { IntOffset(48.dp.roundToPx(), ((1f - current) * height).dp.roundToPx() - 14.dp.roundToPx()) }
                    .clip(RoundedCornerShape(8.dp))
                    .background(ui.panelElevated)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) { Text(label(current), color = ui.text, fontSize = 13.sp) }
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
