package at.techgaisi.farbwerk.ui.color

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.color.PaletteStore
import at.techgaisi.farbwerk.ui.common.PanelCard
import at.techgaisi.farbwerk.ui.theme.LocalUi

/** Tabs des Farben-Panels. */
enum class ColorTab(val label: String) {
    DISC("Scheibe"), CLASSIC("Klassisch"), HARMONY("Harmonie"), VALUES("Werte"), PALETTES("Paletten")
}

/** Zuletzt geöffneter Tab (das Panel wird beim Schließen aus der Komposition entfernt). */
private var lastTab = ColorTab.DISC

/**
 * Popover-Panel "Farben". [modifier] enthält bereits Größe/Position.
 *
 * Aufbau: Kopfzeile (Titel, Hauptfarbe, vorherige Farbe) – Inhalt des gewählten Tabs –
 * Verlauf und Standard-Palette als Mini-Leisten – Tab-Leiste.
 * Die Hauptfarbe lässt sich auf die Leinwand ziehen (ColorDrop); bleibt der Finger dort liegen,
 * wird der Schwellenwert per waagrechter Bewegung eingestellt.
 */
@Composable
fun ColorPanel(state: EditorState, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val model = remember { HsvModel(state) }
    val store = remember { PaletteStore.get(state.context) }
    var tab by remember { mutableStateOf(lastTab) }
    val drag = remember(state) { ColorDropController.of(state) }
    var panelBounds by remember { mutableStateOf(Rect.Zero) }

    // externe Farbänderungen (Pipette, Verlauf, Tastenkürzel …) ins Modell übernehmen
    LaunchedEffect(state.color) { model.syncFrom(state.color) }

    PanelCard(modifier.onGloballyPositioned { panelBounds = it.boundsInWindow() }) {
        Header(state, drag) { panelBounds }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                ColorTab.PALETTES -> PalettesTab(state, model, store)
                ColorTab.VALUES -> ValuesTab(model)
                // Scheibe nicht scrollen: sie passt sich an die verfügbare Höhe an (kleine Tablets),
                // und Scroll-Gesten würden mit dem Ziehen in Ring/Scheibe konkurrieren.
                ColorTab.DISC -> DiscTab(model)
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    when (tab) {
                        ColorTab.CLASSIC -> ClassicTab(model)
                        ColorTab.HARMONY -> HarmonyTab(model)
                        else -> {}
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(0.5.dp).background(ui.divider))
        MiniStrips(state, model, store)
        TabBar(tab) { tab = it; lastTab = it }
    }
}

// ============================================================================ Kopfzeile

@Composable
private fun Header(state: EditorState, drag: ColorDropController, panelBounds: () -> Rect) {
    val ui = LocalUi.current
    var swatchCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Farben", color = ui.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        // Hauptfarbe – ziehbar (ColorDrop)
        Box(
            Modifier
                .size(width = 44.dp, height = 30.dp)
                .clip(RoundedCornerShape(topStart = 7.dp, bottomStart = 7.dp))
                .background(Color(state.color))
                .border(0.5.dp, ui.divider, RoundedCornerShape(topStart = 7.dp, bottomStart = 7.dp))
                .onGloballyPositioned { swatchCoords = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        // Ablegen über dem Panel selbst füllt nicht
                        onDragStart = { off -> swatchCoords?.let { drag.start(it.localToWindow(off), panelBounds()) } },
                        onDrag = { change, _ ->
                            change.consume()
                            swatchCoords?.let { drag.move(it.localToWindow(change.position)) }
                        },
                        onDragEnd = { drag.end() },
                        onDragCancel = { drag.cancel() },
                    )
                }
        )
        // vorherige Farbe – Tippen tauscht
        Box(
            Modifier
                .size(width = 44.dp, height = 30.dp)
                .clip(RoundedCornerShape(topEnd = 7.dp, bottomEnd = 7.dp))
                .background(Color(state.previousColor))
                .border(0.5.dp, ui.divider, RoundedCornerShape(topEnd = 7.dp, bottomEnd = 7.dp))
                .clickable { state.setColorAndRemember(state.previousColor) }
        )
    }
}

// ============================================================================ Verlauf & Standard-Palette

@Composable
private fun MiniStrips(state: EditorState, model: HsvModel, store: PaletteStore) {
    val ui = LocalUi.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Verlauf", color = ui.textMuted, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 0 until 10) {
                val c = state.recentColors.getOrNull(i)
                Box(Modifier.weight(1f)) {
                    if (c != null) Swatch(c, 24.dp, Modifier.fillMaxWidth(), selected = c == state.color, corner = 3.dp) { model.pick(c) }
                    else Box(Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(3.dp)).background(ui.panelElevated.copy(alpha = 0.35f)))
                }
            }
        }
        val def = store.defaultPalette
        if (def != null) {
            Spacer(Modifier.height(8.dp))
            Text(def.name, color = ui.textMuted, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(def.colors) { i, c ->
                    Swatch(
                        c, 24.dp, selected = c != null && c == state.color, corner = 3.dp,
                        onLongClick = if (c != null) ({ store.setSlot(def, i, null) }) else null,
                    ) {
                        if (c != null) model.pick(c) else store.setSlot(def, i, state.color)
                    }
                }
            }
        }
    }
}

// ============================================================================ Tab-Leiste

@Composable
private fun TabBar(selected: ColorTab, onSelect: (ColorTab) -> Unit) {
    val ui = LocalUi.current
    Row(
        Modifier.fillMaxWidth().background(ui.panelElevated.copy(alpha = 0.5f)).padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        ColorTab.entries.forEach { t ->
            val active = t == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelect(t) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TabGlyph(t, if (active) ui.accent else ui.iconMuted)
                Spacer(Modifier.height(2.dp))
                Text(
                    t.label, fontSize = 11.sp,
                    color = if (active) ui.accent else ui.textMuted,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Selbst gezeichnete, schlichte Symbole für die Tabs. */
@Composable
private fun TabGlyph(tab: ColorTab, tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        val s = size.minDimension
        val c = center
        val sw = 1.6.dp.toPx()
        when (tab) {
            ColorTab.DISC -> {
                drawCircle(tint, s / 2f - sw, c, style = Stroke(sw))
                drawCircle(tint, s / 4f, c)
            }
            ColorTab.CLASSIC -> {
                drawRect(tint, Offset(sw, sw), androidx.compose.ui.geometry.Size(s - 2 * sw, s * 0.62f), style = Stroke(sw))
                drawLine(tint, Offset(sw, s * 0.86f), Offset(s - sw, s * 0.86f), sw)
            }
            ColorTab.HARMONY -> {
                drawCircle(tint, s / 2f - sw, c, style = Stroke(sw))
                drawLine(tint, Offset(c.x, sw * 2), Offset(c.x, s - sw * 2), sw)
                drawCircle(tint, sw * 1.6f, Offset(c.x, sw * 2.2f))
                drawCircle(tint, sw * 1.6f, Offset(c.x, s - sw * 2.2f))
            }
            ColorTab.VALUES -> {
                for (i in 0..2) {
                    val y = s * (0.22f + i * 0.28f)
                    drawLine(tint, Offset(sw, y), Offset(s - sw, y), sw)
                    drawCircle(tint, sw * 1.7f, Offset(s * (0.3f + 0.2f * i), y))
                }
            }
            ColorTab.PALETTES -> {
                val cell = (s - 3 * sw) / 2f
                for (i in 0..1) for (j in 0..1) {
                    drawRect(tint, Offset(sw + i * (cell + sw), sw + j * (cell + sw)), androidx.compose.ui.geometry.Size(cell, cell))
                }
            }
        }
    }
}
