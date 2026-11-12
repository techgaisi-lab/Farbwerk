package at.techgaisi.farbwerk.ui.color

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.color.PaletteStore
import at.techgaisi.farbwerk.model.Panel
import at.techgaisi.farbwerk.ui.common.PanelCard
import at.techgaisi.farbwerk.ui.reference.FloatingTokens
import at.techgaisi.farbwerk.ui.reference.FloatingWindowCard
import at.techgaisi.farbwerk.ui.reference.FloatingWindowHeader
import at.techgaisi.farbwerk.ui.theme.LocalUi
import at.techgaisi.farbwerk.ui.theme.UiMetrics
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Tabs des Farben-Panels. */
enum class ColorTab(val label: String) {
    DISC("Scheibe"), CLASSIC("Klassisch"), HARMONY("Harmonie"), VALUES("Werte"), PALETTES("Paletten")
}

/** Zuletzt geöffneter Tab (das Panel wird beim Schließen aus der Komposition entfernt). */
private var lastTab by mutableStateOf(ColorTab.DISC)

/** Breite des schwebenden Panels (UI_SPEC §7). */
private val FLOAT_WIDTH = 300.dp
/** Bevorzugte Höhe des schwebenden Panels (passt sich kleineren Bildschirmen an). */
private val FLOAT_HEIGHT = 470.dp
/** Mindest-Ziehweg an der Greifleiste, ab dem das angedockte Panel losgelöst wird. */
private val DETACH_DISTANCE = 28.dp

/**
 * Zustand der losgelösten (schwebenden) Variante des Farben-Panels.
 * Position in Fenster-Pixeln (obere linke Ecke); der Host rechnet in seine eigenen Koordinaten um.
 */
object ColorPanelFloat {
    /** true = Panel schwebt frei (unabhängig von state.openPanel; bleibt beim Malen offen). */
    var detached by mutableStateOf(false)
    var windowX by mutableFloatStateOf(Float.NaN)
    var windowY by mutableFloatStateOf(Float.NaN)

    /** Anzahl eingehängter [FloatingColorPanelHost]s – die Ersatz-Einhängung zeichnet nur, wenn 0. */
    internal var hosts by mutableIntStateOf(0)

    fun close() { detached = false }
}

/**
 * Popover-Panel "Farben" (angedockt am Farbkreis der oberen Leiste). [modifier] enthält Breite/max. Höhe.
 *
 * Aufbau wie im Vorbild: Greifleiste – Titel „Farben“ + Modusname – (Hauptfarbe / vorherige Farbe) –
 * Inhalt des Tabs – Verlauf und Standardpalette (Scheibe/Klassisch/Werte) – Tab-Leiste mit 5 Symbolen.
 *
 * - Die Hauptfarbe lässt sich auf die Leinwand ziehen (ColorDrop).
 * - **Ziehen an der Greifleiste** löst das Panel ab: es wird zu einem frei verschiebbaren Fenster
 *   ([FloatingColorPanelHost]), das auch beim Malen offen bleibt. Zieht man es wieder an die obere Leiste,
 *   dockt es an. Tippen auf den Farbkreis, während es schwebt, schließt das schwebende Panel.
 */
@Composable
fun ColorPanel(state: EditorState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    var panelBounds by remember { mutableStateOf(Rect.Zero) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    // Beim Ablösen bleibt die angedockte Karte bis zum Ende der Schließ-Animation unsichtbar stehen.
    var handedOff by remember { mutableStateOf(false) }
    // Farbkreis angetippt, während das Panel schwebte → schwebendes Panel schließen (Umschalten wie im Vorbild)
    val openedWhileFloating = remember { ColorPanelFloat.detached }
    LaunchedEffect(Unit) {
        if (openedWhileFloating) {
            ColorPanelFloat.close()
            state.openPanel = Panel.NONE
        }
    }
    val hidden = handedOff || openedWhileFloating

    PanelCard(
        modifier
            .onGloballyPositioned { panelBounds = it.boundsInWindow() }
            .graphicsLayer {
                translationX = dragOffset.x
                translationY = dragOffset.y
                alpha = if (hidden) 0f else 1f
            }
    ) {
        ColorPanelBody(
            state = state,
            floating = false,
            panelBounds = { panelBounds.translate(dragOffset) },
            onGripDrag = { d -> if (!handedOff) dragOffset += d },
            onGripDragEnd = {
                val dist = hypot(dragOffset.x, dragOffset.y)
                if (!handedOff && dist > with(density) { DETACH_DISTANCE.toPx() }) {
                    ColorPanelFloat.windowX = panelBounds.left + dragOffset.x
                    ColorPanelFloat.windowY = panelBounds.top + dragOffset.y
                    ColorPanelFloat.detached = true
                    handedOff = true
                    state.openPanel = Panel.NONE
                } else if (!handedOff) {
                    dragOffset = Offset.Zero
                }
            },
            onClose = null,
        )
    }
}

/**
 * Host für das schwebende Farben-Panel. Gehört als Vollflächen-Kind in die Editor-Box (über die Leinwand,
 * unter die Leisten-Overlays). Berührungen außerhalb der Karte gehen an die Leinwand durch.
 */
@Composable
fun FloatingColorPanelHost(state: EditorState) {
    DisposableEffect(Unit) {
        ColorPanelFloat.hosts++
        onDispose { ColorPanelFloat.hosts-- }
    }
    FloatingColorPanelContent(state)
}

/**
 * Ersatz-Einhängung (aus dem Overlay-Bereich des Referenzfensters), solange der Editor
 * [FloatingColorPanelHost] nicht selbst einbindet. Zeichnet nichts, wenn ein eigener Host vorhanden ist.
 */
@Composable
internal fun FloatingColorPanelFallback(state: EditorState) {
    if (ColorPanelFloat.hosts == 0) FloatingColorPanelContent(state)
}

@Composable
private fun FloatingColorPanelContent(state: EditorState) {
    val visible = ColorPanelFloat.detached && !state.fullscreen && !state.showGallery && state.document != null
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val maxWpx = constraints.maxWidth.toFloat()
        val maxHpx = constraints.maxHeight.toFloat()
        val margin = with(density) { UiMetrics.popoverMargin.toPx() }
        val wPx = with(density) { FLOAT_WIDTH.toPx() }.coerceAtMost(maxWpx - 2 * margin)
        val hPx = with(density) { FLOAT_HEIGHT.toPx() }.coerceAtMost(maxHpx - 2 * margin)
        val dockZone = with(density) { (UiMetrics.topBarHeight - 6.dp).toPx() }

        // Erstposition (z. B. beim Öffnen über das QuickMenu ohne vorheriges Ziehen): links unten
        if (ColorPanelFloat.windowX.isNaN() || ColorPanelFloat.windowY.isNaN()) {
            ColorPanelFloat.windowX = origin.x + with(density) { 56.dp.toPx() }
            ColorPanelFloat.windowY = origin.y + maxHpx - hPx - with(density) { 80.dp.toPx() }
        }
        val x = (ColorPanelFloat.windowX - origin.x).coerceIn(margin, (maxWpx - wPx - margin).coerceAtLeast(margin))
        val y = (ColorPanelFloat.windowY - origin.y).coerceIn(0f, (maxHpx - hPx - margin).coerceAtLeast(0f))

        AnimatedVisibility(
            visible,
            modifier = Modifier.zIndex(1f).offset { IntOffset(x.roundToInt(), y.roundToInt()) },
            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.94f, transformOrigin = TransformOrigin(0.5f, 0f)),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.96f, transformOrigin = TransformOrigin(0.5f, 0f)),
        ) {
            FloatingWindowCard(Modifier.width(with(density) { wPx.toDp() }).height(with(density) { hPx.toDp() })) {
                ColorPanelBody(
                    state = state,
                    floating = true,
                    panelBounds = { Rect(origin.x + x, origin.y + y, origin.x + x + wPx, origin.y + y + hPx) },
                    onGripDrag = { d ->
                        // in Fensterkoordinaten weiterführen, dabei geklemmt (kein „Weglaufen“ über den Rand)
                        val nx = (ColorPanelFloat.windowX - origin.x + d.x).coerceIn(margin, (maxWpx - wPx - margin).coerceAtLeast(margin))
                        val ny = (ColorPanelFloat.windowY - origin.y + d.y).coerceIn(0f, (maxHpx - hPx - margin).coerceAtLeast(0f))
                        ColorPanelFloat.windowX = origin.x + nx
                        ColorPanelFloat.windowY = origin.y + ny
                    },
                    onGripDragEnd = {
                        // An die obere Leiste gezogen → wieder andocken
                        if (ColorPanelFloat.windowY - origin.y < dockZone) {
                            ColorPanelFloat.detached = false
                            state.openPanel = Panel.COLORS
                        }
                    },
                    onClose = { ColorPanelFloat.close() },
                )
            }
        }
    }
}

// ============================================================================ gemeinsamer Inhalt

/**
 * Inhalt beider Varianten. [floating] = schwebend (Schließen-Kreis statt Farbfeldern, feste Höhe).
 */
@Composable
private fun ColumnScope.ColorPanelBody(
    state: EditorState,
    floating: Boolean,
    panelBounds: () -> Rect,
    onGripDrag: (Offset) -> Unit,
    onGripDragEnd: () -> Unit,
    onClose: (() -> Unit)?,
) {
    val model = remember { HsvModel(state) }
    val store = remember { PaletteStore.get(state.context) }
    val drag = remember(state) { ColorDropController.of(state) }
    val tab = lastTab

    // externe Farbänderungen (Pipette, Verlauf, Tastenkürzel …) ins Modell übernehmen
    LaunchedEffect(state.color) { model.syncFrom(state.color) }

    FloatingWindowHeader(
        title = "Farben",
        onClose = onClose,
        subtitleContent = {
            if (tab == ColorTab.HARMONY) HarmonyModeSubtitle(FloatingTokens.textSecondary)
            else Text(tab.label, color = FloatingTokens.textSecondary, fontSize = 12.sp, maxLines = 1)
        },
        onDrag = onGripDrag,
        onDragEnd = onGripDragEnd,
        trailing = if (floating) null else ({ ColorSwatches(state, drag, panelBounds) }),
    )

    Box(Modifier.weight(1f).fillMaxWidth()) {
        when (tab) {
            ColorTab.PALETTES -> PalettesTab(state, model, store)
            ColorTab.VALUES -> ValuesTab(model)
            // Scheibe/Harmonie nicht scrollen: sie passen sich an die verfügbare Höhe an, und Scroll-Gesten
            // würden mit dem Ziehen im Rad konkurrieren.
            ColorTab.DISC -> DiscTab(model)
            ColorTab.HARMONY -> HarmonyTab(model)
            ColorTab.CLASSIC -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { ClassicTab(model) }
        }
    }

    // Verlauf/Standardpalette wie im Vorbild nur unter Scheibe, Klassisch und Werte
    if (tab == ColorTab.DISC || tab == ColorTab.CLASSIC || tab == ColorTab.VALUES) MiniStrips(state, model, store)
    TabBar(tab) { lastTab = it }
}

// ============================================================================ Farbfelder (angedockt)

/** Hauptfarbe (ziehbar → ColorDrop) und vorherige Farbe (Tippen tauscht). */
@Composable
private fun ColorSwatches(state: EditorState, drag: ColorDropController, panelBounds: () -> Rect) {
    val ui = LocalUi.current
    var swatchCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val left = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
    val right = RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
    Row(Modifier.padding(top = 4.dp, end = 8.dp)) {
        Box(
            Modifier
                .size(width = 40.dp, height = 28.dp)
                .clip(left)
                .background(Color(state.color))
                .border(0.5.dp, ui.divider, left)
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
        Box(
            Modifier
                .size(width = 40.dp, height = 28.dp)
                .clip(right)
                .background(Color(state.previousColor))
                .border(0.5.dp, ui.divider, right)
                .clickable { state.setColorAndRemember(state.previousColor) }
        )
    }
}

// ============================================================================ Verlauf & Standard-Palette

@Composable
private fun MiniStrips(state: EditorState, model: HsvModel, store: PaletteStore) {
    val ui = LocalUi.current
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp)) {
        Text("Verlauf", color = FloatingTokens.textSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 0 until 10) {
                val c = state.recentColors.getOrNull(i)
                Box(Modifier.weight(1f)) {
                    if (c != null) Swatch(c, 22.dp, Modifier.fillMaxWidth(), selected = c == state.color, corner = 3.dp) { model.pick(c) }
                    else Box(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(3.dp)).background(ui.panelElevated.copy(alpha = 0.35f)))
                }
            }
        }
        val def = store.defaultPalette
        if (def != null) {
            Spacer(Modifier.height(6.dp))
            Text(def.name, color = FloatingTokens.textSecondary, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(def.colors) { i, c ->
                    Swatch(
                        c, 22.dp, selected = c != null && c == state.color, corner = 3.dp,
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

/** Tab-Leiste: 5 eigene Symbole (22 dp), gleich verteilt, aktiv = `accent`, ohne Beschriftung. */
@Composable
private fun TabBar(selected: ColorTab, onSelect: (ColorTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorTab.entries.forEach { t ->
            val active = t == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(t) },
                contentAlignment = Alignment.Center,
            ) {
                TabGlyph(t, if (active) FloatingTokens.accent else FloatingTokens.iconIdle)
            }
        }
    }
}

/** Selbst gezeichnete Tab-Symbole (22 dp, Strich 1,8 dp). */
@Composable
private fun TabGlyph(tab: ColorTab, tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        val s = size.minDimension
        val c = center
        val sw = 1.8.dp.toPx()
        when (tab) {
            // Scheibe: Kreisring
            ColorTab.DISC -> drawCircle(tint, s * 0.34f, c, style = Stroke(sw))
            // Klassisch: gefülltes, leicht abgerundetes Quadrat
            ColorTab.CLASSIC -> {
                val q = s * 0.56f
                drawRoundRect(tint, Offset(c.x - q / 2, c.y - q / 2), Size(q, q), CornerRadius(2.5.dp.toPx()))
            }
            // Harmonie: Y-Gabel (drei Arme aus einem Punkt)
            ColorTab.HARMONY -> {
                val j = Offset(c.x, c.y + s * 0.04f)
                drawLine(tint, j, Offset(c.x - s * 0.30f, c.y - s * 0.30f), sw, StrokeCap.Round)
                drawLine(tint, j, Offset(c.x + s * 0.30f, c.y - s * 0.30f), sw, StrokeCap.Round)
                drawLine(tint, j, Offset(c.x, c.y + s * 0.36f), sw, StrokeCap.Round)
            }
            // Werte: Schiebereglerlinien (lang/kurz)
            ColorTab.VALUES -> {
                drawLine(tint, Offset(s * 0.14f, s * 0.36f), Offset(s * 0.86f, s * 0.36f), sw, StrokeCap.Round)
                drawLine(tint, Offset(s * 0.14f, s * 0.64f), Offset(s * 0.56f, s * 0.64f), sw, StrokeCap.Round)
            }
            // Paletten: 3 × 3 Punkteraster
            ColorTab.PALETTES -> {
                val step = s * 0.26f
                val r = s * 0.075f
                for (i in -1..1) for (k in -1..1) drawCircle(tint, r, Offset(c.x + i * step, c.y + k * step))
            }
        }
    }
}
