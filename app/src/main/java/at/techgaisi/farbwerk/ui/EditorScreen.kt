package at.techgaisi.farbwerk.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import at.techgaisi.farbwerk.ui.selection.CopyPasteMenu
import at.techgaisi.farbwerk.ui.selection.SelectionToolbar
import at.techgaisi.farbwerk.ui.theme.LocalUi
import at.techgaisi.farbwerk.ui.transform.TransformToolbar

/**
 * Hauptbildschirm. Aufbau:
 *   Obere Leiste links:  Galerie · Aktionen · Anpassungen · Auswahl · Transformieren
 *   Obere Leiste rechts: Malen · Wischen · Radieren · Ebenen · Farbe
 *   Seitenleiste (links, optional rechts): Größe · Modifizierer · Deckkraft · Rückgängig/Wiederholen
 *   Popover-Panels erscheinen unter dem jeweiligen Symbol.
 */
@Composable
fun EditorScreen(state: EditorState) {
    if (state.showGallery || state.document == null) {
        GalleryScreen(state)
        return
    }
    val ui = LocalUi.current
    Box(Modifier.fillMaxSize().background(Color(0xFF1C1C20))) {
        AndroidView(
            factory = { ctx -> CanvasView(ctx, state).also { state.canvasView = it } },
            update = { state.canvasView = it },
            modifier = Modifier.fillMaxSize(),
        )
        // ColorDrop-Nachfüllmodus: Tipp-Ebene über der Leinwand, unter Leisten/Panels (nur wenn aktiv)
        ColorDropTapLayer(state)

        AnimatedVisibility(
            !state.fullscreen, enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) { TopBar(state) }

        val sidebarVisible = !state.fullscreen && state.tool in setOf(Tool.PAINT, Tool.SMUDGE, Tool.ERASE)
        AnimatedVisibility(
            sidebarVisible,
            enter = slideInHorizontally { if (state.prefs.sidebarRight) it else -it } + fadeIn(),
            exit = slideOutHorizontally { if (state.prefs.sidebarRight) it else -it } + fadeOut(),
            modifier = Modifier
                .align(if (state.prefs.sidebarRight) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 6.dp)
        ) { Sidebar(state) }

        // Popover-Panels
        if (!state.fullscreen) {
            val panelMod = Modifier
                .statusBarsPadding()
                .padding(top = 52.dp, end = 8.dp, start = 8.dp)
                .width(360.dp)
                .heightIn(max = 640.dp)
                .fillMaxHeight(0.82f)
            when (state.openPanel) {
                Panel.BRUSHES -> BrushLibraryPanel(state, panelMod.align(Alignment.TopEnd).padding(end = 80.dp))
                Panel.LAYERS -> LayersPanel(state, panelMod.align(Alignment.TopEnd).padding(end = 40.dp))
                Panel.COLORS -> ColorPanel(state, panelMod.align(Alignment.TopEnd))
                Panel.ACTIONS -> ActionsPanel(state, panelMod.align(Alignment.TopStart).padding(start = 60.dp))
                Panel.ADJUSTMENTS -> AdjustmentsMenu(state, Modifier
                    .statusBarsPadding().padding(top = 52.dp, start = 100.dp)
                    .width(280.dp).heightIn(max = 600.dp).align(Alignment.TopStart))
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

        // Hinweis (z. B. "Rückgängig: Pinselstrich")
        state.flashMessage?.let { msg ->
            Box(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 60.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Color(0xE0202024))
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) { Text(msg, color = Color.White, fontSize = 13.sp) }
        }

        // ColorDrop: Farbtropfen beim Ziehen, Schwellenwert-Leiste, „Nachfüllen fortsetzen“ (ganz oben)
        ColorDropOverlay(state)

        // Vollbild-Indikator oben links
        if (state.fullscreen) {
            Box(
                Modifier.align(Alignment.TopStart).statusBarsPadding().padding(10.dp).size(30.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Color(0x80202024))
                    .clickable { state.fullscreen = false },
                contentAlignment = Alignment.Center
            ) { Text("⤡", color = Color.White, fontSize = 16.sp) }
        }
    }
}

@Composable
private fun TopBar(state: EditorState) {
    val ui = LocalUi.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(ui.bar)
            .statusBarsPadding()
            .height(48.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ---------- links ----------
        Text(
            "Galerie", color = ui.text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { state.closeToGallery() }
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
        Spacer(Modifier.width(4.dp))
        ToolIconButton(AppIcons.Actions, "Aktionen", active = state.openPanel == Panel.ACTIONS) {
            state.openPanel = if (state.openPanel == Panel.ACTIONS) Panel.NONE else Panel.ACTIONS
        }
        ToolIconButton(AppIcons.Adjust, "Anpassungen",
            active = state.openPanel == Panel.ADJUSTMENTS || state.tool == Tool.ADJUST) {
            state.openPanel = if (state.openPanel == Panel.ADJUSTMENTS) Panel.NONE else Panel.ADJUSTMENTS
        }
        ToolIconButton(AppIcons.Select, "Auswahl", active = state.tool == Tool.SELECT) {
            state.openPanel = Panel.NONE
            state.selectTool(if (state.tool == Tool.SELECT) Tool.PAINT else Tool.SELECT)
        }
        ToolIconButton(AppIcons.Transform, "Transformieren", active = state.tool == Tool.TRANSFORM) {
            state.openPanel = Panel.NONE
            state.selectTool(if (state.tool == Tool.TRANSFORM) Tool.PAINT else Tool.TRANSFORM)
        }

        Spacer(Modifier.weight(1f))

        // ---------- rechts ----------
        BrushToolButton(state, Tool.PAINT, AppIcons.Paint, "Malen")
        BrushToolButton(state, Tool.SMUDGE, AppIcons.Smudge, "Wischen")
        BrushToolButton(state, Tool.ERASE, AppIcons.Erase, "Radieren")
        ToolIconButton(AppIcons.Layers, "Ebenen", active = state.openPanel == Panel.LAYERS) {
            state.openPanel = if (state.openPanel == Panel.LAYERS) Panel.NONE else Panel.LAYERS
        }
        Spacer(Modifier.width(6.dp))
        // Farbkreis: Tippen öffnet/schließt das Farben-Panel, Ziehen auf die Leinwand = ColorDrop
        val drop = remember(state) { ColorDropController.of(state) }
        val barGap = with(LocalDensity.current) { 10.dp.toPx() }
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
}

/** Erstes Tippen wählt das Werkzeug, erneutes Tippen öffnet die Pinselbibliothek. */
@Composable
private fun BrushToolButton(state: EditorState, tool: Tool, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    ToolIconButton(icon, label, active = state.tool == tool) {
        if (state.tool == tool) {
            state.openPanel = if (state.openPanel == Panel.BRUSHES) Panel.NONE else Panel.BRUSHES
        } else {
            state.selectTool(tool)
            state.openPanel = Panel.NONE
        }
    }
}
