package at.techgaisi.farbwerk.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.Panel
import at.techgaisi.farbwerk.model.Tool
import at.techgaisi.farbwerk.ui.theme.LocalUi
import at.techgaisi.farbwerk.ui.theme.UiMetrics
import at.techgaisi.farbwerk.ui.theme.UiMotion
import kotlin.math.roundToInt

/**
 * Positionen der Leistensymbole (Fenster-Koordinaten), an denen Popovers verankert werden.
 * Schlüssel siehe [PopoverAnchors.Keys].
 */
@Stable
class PopoverAnchors {
    internal val rects = mutableStateMapOf<String, Rect>()

    operator fun get(key: String): Rect? = rects[key]

    object Keys {
        const val BAR = "bar"
        const val ACTIONS = "actions"
        const val ADJUST = "adjust"
        const val SELECT = "select"
        const val TRANSFORM = "transform"
        const val PAINT = "paint"
        const val SMUDGE = "smudge"
        const val ERASE = "erase"
        const val LAYERS = "layers"
        const val COLOR = "color"
    }
}

/** Meldet die Fenster-Position dieses Elements als Anker [key]. */
fun Modifier.popoverAnchor(anchors: PopoverAnchors, key: String): Modifier =
    onGloballyPositioned { anchors.rects[key] = it.boundsInWindow() }

/** Breite je Panel (UI_SPEC §3). Die Pinselbibliothek nutzt [popoverWidth] mit Bildschirmbreite (400–470 dp). */
fun popoverWidth(panel: Panel): Dp = when (panel) {
    Panel.BRUSHES -> UiMetrics.brushPanelMin
    Panel.LAYERS -> UiMetrics.layersPanel
    Panel.COLORS -> UiMetrics.colorsPanel
    Panel.ACTIONS -> UiMetrics.actionsPanel
    Panel.ADJUSTMENTS -> UiMetrics.adjustPanel
    Panel.NONE -> 0.dp
}

/**
 * Breite je Panel abhängig von der Bildschirmbreite: Die Pinselbibliothek wächst auf großen Bildschirmen
 * von 400 dp bis 470 dp (≈ 40 % der Breite), alle anderen Panels haben feste Breiten.
 */
fun popoverWidth(panel: Panel, screenWidth: Dp): Dp = when (panel) {
    Panel.BRUSHES -> (screenWidth * 0.4f).coerceIn(UiMetrics.brushPanelMin, UiMetrics.brushPanelMax)
    else -> popoverWidth(panel)
}

/** Anker-Symbol eines Panels. Die Pinselbibliothek hängt am gerade aktiven Malwerkzeug. */
fun anchorKeyFor(state: EditorState, panel: Panel): String = when (panel) {
    Panel.ACTIONS -> PopoverAnchors.Keys.ACTIONS
    Panel.ADJUSTMENTS -> PopoverAnchors.Keys.ADJUST
    Panel.LAYERS -> PopoverAnchors.Keys.LAYERS
    Panel.COLORS -> PopoverAnchors.Keys.COLOR
    Panel.BRUSHES -> when (state.brushTool) {
        Tool.SMUDGE -> PopoverAnchors.Keys.SMUDGE
        Tool.ERASE -> PopoverAnchors.Keys.ERASE
        else -> PopoverAnchors.Keys.PAINT
    }
    Panel.NONE -> PopoverAnchors.Keys.BAR
}

/**
 * Gemeinsamer Popover-Host für alle Panels der oberen Leiste.
 *
 *  - UI_SPEC §3: Kartenoberkante 6 dp unter der oberen Leiste, Pfeil (Dreieck 18 × 9 dp, Panelfarbe) zeigt
 *    mittig auf das auslösende Symbol. Das Panel wird am Symbol zentriert und bleibt ≥ 8 dp vom Bildschirmrand.
 *  - Farbe `panel`, Radius 13 dp, Schatten 0 8 24 @ 45 % (PanelCard); der Pfeil teilt den Schatten.
 *  - Öffnen: Skalierung 0,94 → 1 + Einblenden, Ursprung = Pfeilspitze, 180 ms ease-out. Schließen 120 ms.
 *  - Wechsel: altes Panel sofort weg, neues mit Öffnen-Animation.
 *  - Tippen daneben schließt: die Leinwand schließt Popovers beim ersten Kontakt (malt dabei nicht).
 *
 * [content] zeichnet das Panel (über PanelCard) und bekommt den Modifier mit Breite und maximaler Höhe.
 */
@Composable
fun PopoverHost(
    state: EditorState,
    anchors: PopoverAnchors,
    modifier: Modifier = Modifier,
    content: @Composable (panel: Panel, modifier: Modifier) -> Unit,
) {
    val target = if (state.fullscreen) Panel.NONE else state.openPanel
    var shown by remember { mutableStateOf(Panel.NONE) }
    var closing by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(target) {
        if (target == Panel.NONE) {
            if (shown != Panel.NONE) {
                closing = true
                progress.animateTo(0f, UiMotion.popoverClose())
            }
            shown = Panel.NONE
            closing = false
        } else {
            closing = false
            if (shown != target) {
                shown = target
                progress.snapTo(0f)
            }
            progress.animateTo(1f, UiMotion.popoverOpen())
        }
    }

    if (shown == Panel.NONE) return
    val panel = shown
    val ui = LocalUi.current
    val density = LocalDensity.current
    var hostOrigin by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(
        modifier.fillMaxSize().onGloballyPositioned { hostOrigin = it.positionInWindow() }
    ) {
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        with(density) {
            val margin = UiMetrics.popoverMargin.toPx()
            val arrowW = UiMetrics.popoverArrowW.toPx()
            val arrowH = UiMetrics.popoverArrowH.toPx()
            val gap = UiMetrics.popoverGap.toPx()

            val anchor = anchors[anchorKeyFor(state, panel)]?.translate(-hostOrigin)
            val bar = anchors[PopoverAnchors.Keys.BAR]?.translate(-hostOrigin)
            val widthPx = popoverWidth(panel, screenW.toDp()).toPx().coerceAtMost(screenW - 2 * margin)
            val anchorCx = anchor?.center?.x ?: (screenW - margin - widthPx / 2)
            val anchorBottom = bar?.bottom ?: anchor?.bottom ?: 0f
            val left = (anchorCx - widthPx / 2).coerceIn(margin, (screenW - margin - widthPx).coerceAtLeast(margin))
            // Kartenoberkante 6 dp unter der Leiste; der Pfeil (9 dp) ragt darüber hinaus Richtung Symbol
            val cardTop = anchorBottom + gap
            val top = cardTop - arrowH.roundToInt()
            val maxH = minOf(screenH * UiMetrics.popoverMaxHeightFraction, screenH - cardTop - margin).coerceAtLeast(120.dp.toPx())
            // Pfeil innerhalb der abgerundeten Ecken halten
            val radius = UiMetrics.popoverRadius.toPx()
            val arrowX = (anchorCx - left).coerceIn(radius + arrowW / 2, widthPx - radius - arrowW / 2)
            val pivotX = (arrowX / widthPx).coerceIn(0f, 1f)

            Box(
                Modifier
                    .zIndex(1f)
                    .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .graphicsLayer {
                        val p = progress.value
                        val from = if (closing) UiMotion.POPOVER_CLOSE_SCALE else UiMotion.POPOVER_OPEN_SCALE
                        val s = from + (1f - from) * p
                        scaleX = s; scaleY = s
                        alpha = p.coerceIn(0f, 1f)
                        transformOrigin = TransformOrigin(pivotX, 0f)
                    }
            ) {
                Column {
                    // Pfeil zum Symbol (gleiche Farbe wie die Karte). Ohne Überlappung, da das Panel transluzent ist –
                    // eine Überdeckung würde als dunklere Naht sichtbar. Höhe auf ganze Pixel gerundet.
                    Canvas(Modifier.size(width = widthPx.toDp(), height = arrowH.roundToInt().toFloat().toDp())) {
                        val path = Path().apply {
                            moveTo(arrowX - arrowW / 2, size.height)
                            // weich abgerundete Spitze
                            lineTo(arrowX - 1.6.dp.toPx(), 1.4.dp.toPx())
                            quadraticBezierTo(arrowX, 0f, arrowX + 1.6.dp.toPx(), 1.4.dp.toPx())
                            lineTo(arrowX + arrowW / 2, size.height)
                            close()
                        }
                        drawPath(path, ui.panel)
                    }
                    content(panel, Modifier.width(widthPx.toDp()).heightIn(max = maxH.toDp()))
                }
            }
        }
    }
}
