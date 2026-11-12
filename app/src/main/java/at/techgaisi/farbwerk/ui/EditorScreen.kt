package at.techgaisi.farbwerk.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.canvas.CanvasView
import at.techgaisi.farbwerk.model.Panel
import at.techgaisi.farbwerk.model.Tool
import at.techgaisi.farbwerk.ui.actions.ActionsPanel
import at.techgaisi.farbwerk.ui.adjust.AdjustmentControls
import at.techgaisi.farbwerk.ui.adjust.AdjustmentsMenu
import at.techgaisi.farbwerk.ui.brush.BrushLibraryPanel
import at.techgaisi.farbwerk.ui.color.ColorDropController
import at.techgaisi.farbwerk.ui.color.ColorDropOverlay
import at.techgaisi.farbwerk.ui.color.ColorDropTapLayer
import at.techgaisi.farbwerk.ui.color.ColorPanel
import at.techgaisi.farbwerk.ui.common.ToolIconButton
import at.techgaisi.farbwerk.ui.gallery.GalleryScreen
import at.techgaisi.farbwerk.ui.icons.AppIcons
import at.techgaisi.farbwerk.ui.layers.LayersPanel
import at.techgaisi.farbwerk.ui.quickmenu.QuickMenuController
import at.techgaisi.farbwerk.ui.quickmenu.QuickMenuOverlay
import at.techgaisi.farbwerk.ui.quickmenu.QuickMenuStore
import at.techgaisi.farbwerk.ui.selection.CopyPasteMenu
import at.techgaisi.farbwerk.ui.selection.SelectionToolbar
import at.techgaisi.farbwerk.ui.shell.AutoFullscreen
import at.techgaisi.farbwerk.ui.shell.FloatingEyedropperLayer
import at.techgaisi.farbwerk.ui.shell.FullscreenIndicator
import at.techgaisi.farbwerk.ui.shell.HintBar
import at.techgaisi.farbwerk.ui.shell.PopoverAnchors
import at.techgaisi.farbwerk.ui.shell.PopoverHost
import at.techgaisi.farbwerk.ui.shell.popoverAnchor
import at.techgaisi.farbwerk.ui.theme.LocalUi
import at.techgaisi.farbwerk.ui.theme.UiMetrics
import at.techgaisi.farbwerk.ui.theme.UiMotion
import at.techgaisi.farbwerk.ui.transform.TransformToolbar
import kotlin.math.roundToInt

/**
 * Hauptbildschirm. Aufbau:
 *   Obere Leiste links:  Galerie · Aktionen · Anpassungen · Auswahl · Transformieren
 *   Obere Leiste rechts: Malen · Wischen · Radieren · Ebenen · Farbe
 *   Seitenleiste (links, optional rechts): Größe · Modifizierer · Deckkraft · Rückgängig/Wiederholen
 *   Popover-Panels erscheinen verankert unter dem jeweiligen Symbol (ui/shell/PopoverHost).
 *   Hinweisleiste oben Mitte, QuickMenu, schwebende Pipette, Vollbild-Indikator.
 */
@Composable
fun EditorScreen(state: EditorState) {
    if (state.showGallery || state.document == null) {
        GalleryScreen(state)
        return
    }
    val ui = LocalUi.current
    val anchors = remember { PopoverAnchors() }
    val auto = remember { AutoFullscreen() }
    auto.Effect(state)
    val chromeHidden = state.fullscreen || auto.hidden
    TrackPreviousBrushes(state)

    Box(Modifier.fillMaxSize().background(ui.workspace)) {
        var layerPick by remember { mutableStateOf<FloatArray?>(null) }
        AndroidView(
            factory = { ctx -> CanvasView(ctx, state).also { state.canvasView = it } },
            update = { cv ->
                state.canvasView = cv
                cv.quickMenuHook = QuickMenuController.bind(state)
                cv.onStrokeActivity = { active -> auto.onStroke(active, state.prefs.autoFullscreen) }
                cv.onSelectLayerAt = { dx, dy, wx, wy -> layerPick = floatArrayOf(dx, dy, wx, wy) }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // ColorDrop-Nachfüllmodus: Tipp-Ebene über der Leinwand, unter Leisten/Panels (nur wenn aktiv)
        ColorDropTapLayer(state)
        // Schwebende Pipette (Modifizierer tippen): fängt das Ziehen ab, solange sie läuft
        FloatingEyedropperLayer(state)

        AnimatedVisibility(
            !chromeHidden,
            enter = slideInVertically(UiMotion.chromeOffset()) { -it } + fadeIn(UiMotion.chrome()),
            exit = slideOutVertically(UiMotion.chromeOffset()) { -it } + fadeOut(UiMotion.chrome()),
            modifier = Modifier.align(Alignment.TopCenter)
        ) { TopBar(state, anchors) }

        SidebarArea(state, visible = !chromeHidden && state.prefs.sidebarVisible)

        // Popover-Panels (verankert, mit Pfeil und Federanimation)
        PopoverHost(state, anchors) { panel, mod ->
            when (panel) {
                Panel.BRUSHES -> BrushLibraryPanel(state, mod)
                Panel.LAYERS -> LayersPanel(state, mod)
                Panel.COLORS -> ColorPanel(state, mod)
                Panel.ACTIONS -> ActionsPanel(state, mod)
                Panel.ADJUSTMENTS -> AdjustmentsMenu(state, mod)
                Panel.NONE -> {}
            }
        }

        // Werkzeugleisten unten
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp)) {
            when (state.tool) {
                Tool.SELECT -> SelectionToolbar(state)
                Tool.TRANSFORM -> TransformToolbar(state)
                Tool.ADJUST -> AdjustmentControls(state)
                else -> {}
            }
        }

        if (state.copyPasteMenuVisible) {
            CopyPasteMenu(state, Modifier.align(Alignment.Center))
        }

        // Tastenkürzel-Übersicht (Strg gedrückt halten)
        at.techgaisi.farbwerk.input.ShortcutOverlay(Modifier.align(Alignment.Center))

        // QuickMenu und Regler-Vorschaublase (Bildschirmebene, damit sie Berührungen annehmen)
        QuickMenuOverlay(state)
        SidebarBubble(state)

        // Hinweis-/Statusleiste (blaue Pille oben Mitte)
        HintBar(state, Modifier.align(Alignment.TopCenter))

        // ColorDrop: Farbtropfen beim Ziehen, Schwellenwert-Leiste, „Nachfüllen fortsetzen“ (ganz oben)
        ColorDropOverlay(state)

        // Vollbild-Indikator oben links
        FullscreenIndicator(state, Modifier.align(Alignment.TopStart))

        // "Ebene wählen" auf der Leinwand
        layerPick?.let { lp ->
            at.techgaisi.farbwerk.ui.layers.LayerPickPopup(state, lp[0], lp[1], lp[2], lp[3]) { layerPick = null }
        }

        // Overlays aus dem Aktionen-Modul (Zuschneiden, Zeichenhilfe-Editor, Text, Referenz, Animation)
        at.techgaisi.farbwerk.ui.actions.ActionOverlays(state)
    }
}

/** Seitenleiste: Größe der Regler an die Bildschirmhöhe angepasst, verschiebbar, gleitet im Vollbild seitlich heraus. */
@Composable
private fun SidebarArea(state: EditorState, visible: Boolean) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val statusTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
        val reserveTop = statusTop + UiMetrics.topBarHeight + 8.dp
        val reserveBottom = 16.dp
        val available = maxHeight - reserveTop - reserveBottom
        val slider = sidebarSliderHeight(available)
        val total = sidebarTotalHeight(slider)
        // Verschiebeweg: symmetrisch um die Mitte, ohne unter die obere Leiste zu geraten
        val travel = ((maxHeight / 2 - total / 2 - reserveTop).coerceAtLeast(0.dp))
        val travelPx = with(density) { travel.toPx() }
        SidebarMove.travelPx = travelPx
        val right = state.prefs.sidebarRight
        AnimatedVisibility(
            visible,
            enter = slideInHorizontally(UiMotion.chromeOffset()) { if (right) it else -it } + fadeIn(UiMotion.chrome()),
            exit = slideOutHorizontally(UiMotion.chromeOffset()) { if (right) it else -it } + fadeOut(UiMotion.chrome()),
            modifier = Modifier
                .align(if (right) Alignment.CenterEnd else Alignment.CenterStart)
                .offset { IntOffset(0, (state.prefs.sidebarOffset * travelPx).roundToInt()) }
                .padding(horizontal = UiMetrics.sidebarEdge)
        ) { Sidebar(state, sliderHeight = slider) }
    }
}

/** Merkt sich je Werkzeug den vorher benutzten Pinsel (QuickMenu „Vorheriger Pinsel“). */
@Composable
private fun TrackPreviousBrushes(state: EditorState) {
    @Suppress("UNUSED_VARIABLE") val v = state.toolSettingsVersion
    val seen = remember { HashMap<Tool, String>() }
    SideEffect {
        for ((t, ts) in state.toolSettings) {
            val old = seen[t]
            if (old != null && old != ts.brushId) QuickMenuStore.lastBrush[t] = old
            seen[t] = ts.brushId
        }
    }
}

@Composable
private fun TopBar(state: EditorState, anchors: PopoverAnchors) {
    val ui = LocalUi.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(ui.bar)
            .statusBarsPadding()
            .height(UiMetrics.topBarHeight)
            .popoverAnchor(anchors, PopoverAnchors.Keys.BAR)
            .padding(horizontal = UiMetrics.topBarSidePadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(UiMetrics.topIconGap),
    ) {
        // ---------- links ----------
        Text(
            "Galerie", color = ui.text, fontSize = UiMetrics.galleryText, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { state.closeToGallery() }
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )
        ToolIconButton(
            AppIcons.Actions, "Aktionen", active = state.openPanel == Panel.ACTIONS,
            modifier = Modifier.popoverAnchor(anchors, PopoverAnchors.Keys.ACTIONS),
        ) { togglePanel(state, Panel.ACTIONS) }
        ToolIconButton(
            AppIcons.Adjust, "Anpassungen",
            active = state.openPanel == Panel.ADJUSTMENTS || state.tool == Tool.ADJUST,
            modifier = Modifier.popoverAnchor(anchors, PopoverAnchors.Keys.ADJUST),
        ) {
            if (state.tool == Tool.ADJUST && state.openPanel != Panel.ADJUSTMENTS) {
                // Erneutes Tippen während einer Anpassung: übernehmen und verlassen
                state.openPanel = Panel.NONE
                state.adjustmentTool.applyAndClose()
            } else togglePanel(state, Panel.ADJUSTMENTS)
        }
        ToolIconButton(
            AppIcons.Select, "Auswahl", active = state.tool == Tool.SELECT,
            modifier = Modifier.popoverAnchor(anchors, PopoverAnchors.Keys.SELECT),
            // Halten: letzte Auswahl neu laden
            onLongClick = { state.openPanel = Panel.NONE; state.selectionTool.reloadLast() },
        ) {
            state.openPanel = Panel.NONE
            state.selectTool(if (state.tool == Tool.SELECT) Tool.PAINT else Tool.SELECT)
        }
        ToolIconButton(
            AppIcons.Transform, "Transformieren", active = state.tool == Tool.TRANSFORM,
            modifier = Modifier.popoverAnchor(anchors, PopoverAnchors.Keys.TRANSFORM),
        ) {
            state.openPanel = Panel.NONE
            state.selectTool(if (state.tool == Tool.TRANSFORM) Tool.PAINT else Tool.TRANSFORM)
        }

        Spacer(Modifier.weight(1f))

        // ---------- rechts ----------
        BrushToolButton(state, anchors, Tool.PAINT, AppIcons.Paint, "Malen", PopoverAnchors.Keys.PAINT)
        BrushToolButton(state, anchors, Tool.SMUDGE, AppIcons.Smudge, "Wischen", PopoverAnchors.Keys.SMUDGE)
        BrushToolButton(state, anchors, Tool.ERASE, AppIcons.Erase, "Radieren", PopoverAnchors.Keys.ERASE)
        ToolIconButton(
            AppIcons.Layers, "Ebenen", active = state.openPanel == Panel.LAYERS,
            modifier = Modifier.popoverAnchor(anchors, PopoverAnchors.Keys.LAYERS),
        ) { togglePanel(state, Panel.LAYERS) }
        Spacer(Modifier.width(2.dp))
        // Farbkreis: Tippen öffnet/schließt das Farben-Panel, Ziehen auf die Leinwand = ColorDrop
        val drop = remember(state) { ColorDropController.of(state) }
        val barGap = with(LocalDensity.current) { 10.dp.toPx() }
        Box(Modifier.popoverAnchor(anchors, PopoverAnchors.Keys.COLOR)) {
            ColorDot(
                state.color,
                // Unterkante der Leiste ≈ Unterkante des Kreises + Restabstand – darüber wird nicht gefüllt
                modifier = Modifier.onGloballyPositioned { drop.blockAboveY = it.boundsInWindow().bottom + barGap },
                onDragStart = { p ->
                    state.openPanel = Panel.NONE   // Leinwand freigeben
                    drop.start(p)
                },
                onDrag = { drop.move(it) },
                onDragEnd = { drop.end() },
                onDragCancel = { drop.cancel() },
            ) {
                state.openPanel = if (state.openPanel == Panel.COLORS) Panel.NONE else Panel.COLORS
            }
        }
        Spacer(Modifier.width(4.dp))
    }
}

private fun togglePanel(state: EditorState, p: Panel) {
    state.openPanel = if (state.openPanel == p) Panel.NONE else p
}

/**
 * Erstes Tippen wählt das Werkzeug, erneutes Tippen öffnet die Pinselbibliothek.
 * Halten auf Wischen/Radieren, solange es nicht aktiv ist: Werkzeug mit dem aktuellen Malpinsel wählen.
 */
@Composable
private fun BrushToolButton(
    state: EditorState,
    anchors: PopoverAnchors,
    tool: Tool,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    anchorKey: String,
) {
    val hold: (() -> Unit)? = if (tool != Tool.PAINT && state.tool != tool) {
        {
            val paintBrush = state.toolSettings[Tool.PAINT]?.brushId
            val ts = state.toolSettings[tool]
            if (paintBrush != null && ts != null) {
                ts.brushId = paintBrush
                state.selectTool(tool)
                state.openPanel = Panel.NONE
                state.toolSettingsVersion++
                state.flash("$label mit aktuellem Pinsel")
            }
        }
    } else null
    ToolIconButton(
        icon, label, active = state.tool == tool,
        modifier = Modifier.popoverAnchor(anchors, anchorKey),
        onLongClick = hold,
    ) {
        if (state.tool == tool) {
            state.openPanel = if (state.openPanel == Panel.BRUSHES) Panel.NONE else Panel.BRUSHES
        } else {
            state.selectTool(tool)
            state.openPanel = Panel.NONE
        }
    }
}
