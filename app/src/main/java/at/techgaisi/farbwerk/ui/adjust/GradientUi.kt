package at.techgaisi.farbwerk.ui.adjust

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.adjust.AdjustmentTool
import at.techgaisi.farbwerk.adjust.GradientDef
import at.techgaisi.farbwerk.adjust.GradientStop
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Verlaufsumsetzung: Bibliothek (Tippen = anwenden, gewählten erneut tippen = Editor, Halten = Duplizieren/
 * Löschen, Halten + Ziehen = umsortieren, „+“ = neuer Verlauf, „+“ halten = Standards wiederherstellen) und
 * Verlaufseditor (links Schatten, rechts Lichter; 2–12 Stopps; Stopp tippen = Farbe, Leere tippen = neuer
 * Stopp, Stopp halten = löschen, Titel = Name).
 */

private val TILE_W = 96.dp
private val TILE_GAP = 8.dp
private const val MAX_STOPS = 12

private fun GradientDef.brush(): Brush {
    val s = sorted()
    return Brush.horizontalGradient(colorStops = s.map { it.pos to Color(it.color) }.toTypedArray())
}

/** Ergebnis einer Halten-Geste: Tippen, Ziehen (Rollen) oder Halten (+ Versatz beim Loslassen). */
private sealed class Press {
    data object Tap : Press()
    data object Scroll : Press()
    class Hold(val dx: Float) : Press()
}

/**
 * Unterscheidet Tippen / Rollen / Halten(+Ziehen). Rollen wird nicht verbraucht (die Leiste scrollt).
 * [onHoldMove] meldet während des Haltens den waagerechten Versatz.
 */
private suspend fun PointerInputScope.pressGestures(onHoldStart: () -> Unit, onHoldMove: (Float) -> Unit, onResult: (Press) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val first = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val ev = awaitPointerEvent()
                val c = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull Press.Scroll
                if (!c.pressed) return@withTimeoutOrNull Press.Tap
                if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull Press.Scroll
            }
            @Suppress("UNREACHABLE_CODE") Press.Scroll
        }
        if (first != null) { onResult(first); return@awaitEachGesture }
        onHoldStart()
        var dx = 0f
        while (true) {
            val ev = awaitPointerEvent()
            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
            dx = c.position.x - down.position.x
            c.consume()
            if (!c.pressed) break
            onHoldMove(dx)
        }
        onResult(Press.Hold(dx))
    }
}

@Composable
internal fun GradientLibraryRow(tool: AdjustmentTool) {
    val ui = LocalUi.current
    val lib = tool.gradients
    @Suppress("UNUSED_VARIABLE") val ver = lib.version
    val density = LocalDensity.current
    val stepPx = with(density) { (TILE_W + TILE_GAP).toPx() }
    val slopPx = with(density) { 12.dp.toPx() }
    var holdIndex by remember { mutableIntStateOf(-1) }
    var holdDx by remember { mutableFloatStateOf(0f) }
    var menuFor by remember { mutableIntStateOf(-1) }
    var menuAnchor by remember { mutableStateOf<Offset?>(null) }
    var plusMenu by remember { mutableStateOf<Offset?>(null) }
    val tilePos = remember { HashMap<String, Offset>() }
    var plusPos by remember { mutableStateOf(Offset.Zero) }
    val selected = tool.gradientIndex

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
    ) {
        lib.items.forEachIndexed { i, g ->
            val target = if (holdIndex >= 0 && abs(holdDx) > slopPx)
                (holdIndex + (holdDx / stepPx).roundToInt()).coerceIn(0, lib.items.lastIndex) else -1
            val idx by rememberUpdatedState(i)
            Box {
                Column(
                    Modifier.width(TILE_W)
                        .onGloballyPositioned { c ->
                            val p = c.positionInWindow()
                            tilePos[g.id] = Offset(p.x + c.size.width / 2f, p.y)
                        }
                        .scale(if (holdIndex == i) 1.05f else 1f)
                        .alpha(if (holdIndex == i && target >= 0) 0.6f else 1f)
                        .pointerInput(g.id) {
                            pressGestures(
                                onHoldStart = { holdIndex = idx; holdDx = 0f },
                                onHoldMove = { holdDx = it },
                            ) { r ->
                                when (r) {
                                    Press.Tap -> if (idx == tool.gradientIndex) tool.gradientEditorOpen = true else tool.setGradient(idx)
                                    Press.Scroll -> {}
                                    is Press.Hold -> {
                                        if (abs(r.dx) <= slopPx) {
                                            menuFor = idx
                                            menuAnchor = tilePos[g.id]
                                        } else {
                                            lib.move(idx, idx + (r.dx / stepPx).roundToInt())
                                        }
                                        holdIndex = -1; holdDx = 0f
                                    }
                                }
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(6.dp))
                            .background(g.brush())
                            .border(if (selected == i) 2.dp else 0.5.dp,
                                if (selected == i) ui.accent else ui.divider, RoundedCornerShape(6.dp))
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(g.name, color = if (selected == i) ui.text else ui.textMuted, fontSize = 11.sp, maxLines = 1)
                }
                // Einfügemarke beim Umsortieren
                if (target == i && target != holdIndex) {
                    Box(Modifier.align(if (target > holdIndex) Alignment.CenterEnd else Alignment.CenterStart)
                        .padding(bottom = 14.dp).width(3.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(ui.accent))
                }
            }
        }
        // „+“ neuer Verlauf (Halten: Standards wiederherstellen)
        Box(
            Modifier.size(width = 44.dp, height = 30.dp).clip(RoundedCornerShape(6.dp))
                .background(Color(0x1FFFFFFF))
                .onGloballyPositioned { c -> val p = c.positionInWindow(); plusPos = Offset(p.x + c.size.width / 2f, p.y) }
                .pointerInput(Unit) {
                    pressGestures(onHoldStart = {}, onHoldMove = {}) { r ->
                        when (r) {
                            Press.Tap -> {
                                val n = lib.add()
                                tool.setGradient(n)
                                tool.gradientEditorOpen = true
                            }
                            is Press.Hold -> plusMenu = plusPos
                            Press.Scroll -> {}
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Text("+", color = ui.text, fontSize = 20.sp) }
    }

    // Halten auf einen Verlauf: Duplizieren / Löschen (öffnet nach oben, über der Karte)
    AnchoredPopover(menuAnchor?.let { Offset(it.x, it.y - 1f) }, onDismiss = { menuAnchor = null }) {
        PopoverMenuCard(width = 200.dp) {
            MenuRow("Bearbeiten") {
                tool.setGradient(menuFor); tool.gradientEditorOpen = true; menuAnchor = null
            }
            MenuDivider()
            MenuRow("Duplizieren") {
                val n = lib.duplicate(menuFor)
                if (n >= 0) tool.setGradient(n)
                menuAnchor = null
            }
            MenuDivider()
            MenuRow("Löschen", destructive = true, enabled = lib.items.size > 1) {
                val wasSel = menuFor == tool.gradientIndex
                if (lib.delete(menuFor) && wasSel) tool.setGradient((menuFor - 1).coerceAtLeast(0))
                tool.gradientEdited(false)
                menuAnchor = null
            }
        }
    }
    AnchoredPopover(plusMenu, onDismiss = { plusMenu = null }) {
        PopoverMenuCard(width = 260.dp) {
            MenuRow("Standards wiederherstellen") {
                lib.restoreDefaults(); tool.gradientEdited(false); plusMenu = null
            }
        }
    }
}

// ===================================================================== Editor

@Composable
internal fun GradientEditor(tool: AdjustmentTool, def: GradientDef) {
    val ui = LocalUi.current
    val lib = tool.gradients
    @Suppress("UNUSED_VARIABLE") val ver = lib.version
    var sel by remember(def.id) { mutableIntStateOf(-1) }
    var name by remember(def.id) { mutableStateOf(def.name) }

    // ---------- Kopfzeile: zurück, Name, Fertig ----------
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("‹ Bibliothek", color = ui.accent, fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { tool.gradientEditorOpen = false }.padding(4.dp))
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value = name,
            onValueChange = { name = it.take(40); def.name = name.ifBlank { "Verlauf" }; lib.changed() },
            singleLine = true,
            textStyle = TextStyle(color = ui.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            cursorBrush = SolidColor(ui.accent),
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(Color(0x14FFFFFF))
                .padding(horizontal = 8.dp, vertical = 5.dp),
        )
        Spacer(Modifier.width(12.dp))
        PillButton("Fertig", active = true) { tool.gradientEditorOpen = false }
    }

    // ---------- Verlaufsleiste mit Stopps ----------
    val density = LocalDensity.current
    val hitPx = with(density) { 16.dp.toPx() }
    val padPx = with(density) { 12.dp.toPx() }
    val stops = def.stops
    Canvas(
        Modifier.fillMaxWidth().height(64.dp).pointerInput(def.id) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                val w = size.width - 2 * padPx
                fun posOf(x: Float) = ((x - padPx) / w).coerceIn(0f, 1f)
                val hit = stops.indices.minByOrNull { abs(padPx + stops[it].pos * w - down.position.x) }
                    ?.takeIf { abs(padPx + stops[it].pos * w - down.position.x) < hitPx }
                var moved = false
                var longHandled = false
                val res = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                        c.consume()
                        if (!c.pressed) return@withTimeoutOrNull true
                        if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            moved = true
                            return@withTimeoutOrNull false
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") false
                }
                if (res == null && hit != null) {
                    // Halten auf einen Stopp = löschen (mind. 2 bleiben)
                    if (stops.size > 2) {
                        stops.removeAt(hit); sel = -1
                        lib.changed(); tool.gradientEdited(false)
                    }
                    longHandled = true
                }
                if (moved && hit != null) {
                    sel = hit
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        c.consume()
                        if (!c.pressed) break
                        stops[hit].pos = posOf(c.position.x)
                        lib.touch(); tool.gradientEdited(true)
                    }
                    lib.changed(); tool.gradientEdited(false)
                } else if (res == true && !longHandled) {
                    if (hit != null) sel = if (sel == hit) -1 else hit
                    else if (stops.size < MAX_STOPS) {
                        val t = posOf(down.position.x)
                        stops.add(GradientStop(t, def.colorAt(t)))
                        sel = stops.lastIndex
                        lib.changed(); tool.gradientEdited(false)
                    }
                }
            }
        }
    ) {
        val w = size.width - 2 * padPx
        val barH = 30.dp.toPx()
        drawRoundRect(def.brush(), Offset(padPx, 0f), Size(w, barH), CornerRadius(6.dp.toPx()))
        val ky = barH + 14.dp.toPx()
        for ((i, s) in stops.withIndex()) {
            val x = padPx + s.pos * w
            drawLine(Color(0x88FFFFFF), Offset(x, barH), Offset(x, ky - 9.dp.toPx()), 1.dp.toPx())
            if (i == sel) drawCircle(ui.accent, 12.dp.toPx(), Offset(x, ky))
            drawCircle(Color.White, 9.5.dp.toPx(), Offset(x, ky))
            drawCircle(Color(s.color), 7.5.dp.toPx(), Offset(x, ky))
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text("Schatten", color = ui.textMuted, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text(if (sel in stops.indices) "Stopp halten = löschen" else "Leere tippen = neuer Stopp",
            color = ui.textMuted, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text("Lichter", color = ui.textMuted, fontSize = 11.sp)
    }

    // ---------- Farbe des gewählten Stopps ----------
    val stop = stops.getOrNull(sel)
    if (stop != null) StopColorPicker(stop.color, key = "${def.id}#$sel") { c, final ->
        stop.color = c or (0xFF shl 24)
        if (final) lib.changed() else lib.touch()
        tool.gradientEdited(!final)
    }
}

/** Kompakter Farbwähler: Sättigung/Helligkeit-Feld + Farbtonstreifen. */
@Composable
private fun StopColorPicker(initial: Int, key: String, onColor: (Int, Boolean) -> Unit) {
    val hsv0 = remember(key) { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember(key) { mutableFloatStateOf(hsv0[0]) }
    var sat by remember(key) { mutableFloatStateOf(hsv0[1]) }
    var value by remember(key) { mutableFloatStateOf(hsv0[2]) }
    val emit by rememberUpdatedState(onColor)
    fun color() = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
    Row(Modifier.fillMaxWidth().height(112.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Sättigung (x) / Helligkeit (y)
        Canvas(
            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).pointerInput(key) {
                dragTrack { x, y, final ->
                    sat = (x / size.width).coerceIn(0f, 1f)
                    value = 1f - (y / size.height).coerceIn(0f, 1f)
                    emit(color(), final)
                }
            }
        ) {
            val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
            drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val p = Offset(sat * size.width, (1f - value) * size.height)
            drawCircle(Color.White, 8.dp.toPx(), p, style = Stroke(2.5.dp.toPx()))
            drawCircle(Color(0x66000000), 9.5.dp.toPx(), p, style = Stroke(1.dp.toPx()))
        }
        // Farbton (senkrecht)
        Canvas(
            Modifier.width(28.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)).pointerInput(key) {
                dragTrack { _, y, final ->
                    hue = (y / size.height).coerceIn(0f, 1f) * 360f
                    emit(color(), final)
                }
            }
        ) {
            drawRect(Brush.verticalGradient(List(7) { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }))
            val y = hue / 360f * size.height
            drawRect(Color.White, Offset(0f, y - 2.dp.toPx()), Size(size.width, 4.dp.toPx()), style = Stroke(2.dp.toPx()))
        }
        // Vorschau
        Box(Modifier.width(36.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Color(color())))
    }
}

/** Einfache Zieh-Geste: meldet jede Position, zuletzt mit final = true. */
private suspend fun PointerInputScope.dragTrack(onPos: (Float, Float, Boolean) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        onPos(down.position.x, down.position.y, false)
        var last = down.position
        while (true) {
            val ev = awaitPointerEvent()
            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
            c.consume()
            last = c.position
            if (!c.pressed) break
            onPos(last.x, last.y, false)
        }
        onPos(last.x, last.y, true)
    }
}
