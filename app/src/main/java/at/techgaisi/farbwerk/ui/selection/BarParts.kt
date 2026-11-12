package at.techgaisi.farbwerk.ui.selection

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.ui.common.PanelCard
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlin.math.roundToInt

/*
 * Gemeinsame Bausteine der unteren Werkzeugleisten (Auswahl, Transformieren, Anpassungen).
 * Maße/Timing nach „Visuelles Design & Animation“ der Anleitung: schwebende Leiste mittig unten,
 * Radius 14 dp, Knöpfe mit 24-dp-Symbol und 10–11-sp-Beschriftung, Popover mit Pfeil, Öffnen
 * 0,92 → 1,0 gefedert (~200 ms), Schließen auf 0,95 in ~130 ms.
 */

private val BarShape = RoundedCornerShape(14.dp)

/**
 * Schwebende, abgerundete Leiste. Gleitet beim Erscheinen von unten ein (Feder, kein Überschwingen).
 */
@Composable
fun FloatingBar(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val ui = LocalUi.current
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 520f)) }
    val density = LocalDensity.current
    Column(
        modifier
            .graphicsLayer {
                val p = enter.value
                alpha = p.coerceIn(0f, 1f)
                translationY = (1f - p) * with(density) { 24.dp.toPx() }
            }
            .shadow(18.dp, BarShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(BarShape)
            .background(ui.panel)
            .border(0.5.dp, ui.divider, BarShape)
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/** Einzeilige Leiste: linke Gruppe (Modi), Trenner, rechte Gruppe (Optionen). */
@Composable
fun BarRow(content: @Composable RowScope.() -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Modus-Reiter (Text, aktiv blau hervorgehoben, Farbwechsel animiert). */
@Composable
fun ModeTab(label: String, active: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    val ui = LocalUi.current
    val bg by animateColorAsState(if (active) ui.accent.copy(alpha = 0.2f) else Color.Transparent, tween(150), label = "tabBg")
    val fg by animateColorAsState(if (active) ui.accent else ui.text, tween(150), label = "tabFg")
    Box(
        Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, color = fg, fontSize = 13.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
        }
    }
}

/** Symbol-Schaltfläche mit kleiner Beschriftung darunter (aktiv = blau, animiert). */
@Composable
fun BarButton(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    /** Drehung des Symbols in Grad (z. B. 90 für „Vertikal spiegeln“). */
    iconRotation: Float = 0f,
    onClick: () -> Unit,
) {
    val ui = LocalUi.current
    val tint by animateColorAsState(
        when {
            !enabled -> ui.iconMuted.copy(alpha = 0.5f)
            active -> ui.accent
            else -> ui.icon
        }, tween(150), label = "btnTint",
    )
    val bg by animateColorAsState(if (active) ui.accent.copy(alpha = 0.14f) else Color.Transparent, tween(150), label = "btnBg")
    Column(
        modifier
            .widthIn(min = 60.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label.replace('\n', ' '), tint = tint, modifier = Modifier.size(24.dp).rotate(iconRotation))
        Spacer(Modifier.height(3.dp))
        Text(label, color = if (active) ui.accent else if (enabled) ui.textMuted else ui.textMuted.copy(alpha = 0.5f),
            fontSize = 10.5.sp, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 12.sp)
    }
}

/** Kleine Hinweis-Pille (z. B. "Schwellenwert: 42 %"). */
@Composable
fun InfoPill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xE61C1C1E))
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) { Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

/** Dünner senkrechter Trenner zwischen Gruppen in einer Leiste. */
@Composable
fun BarDivider() {
    val ui = LocalUi.current
    Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(32.dp).background(ui.divider))
}

// ---------------------------------------------------------------------------------------- Popover

/**
 * Merkt sich die waagrechte Mitte (Fenster-x in px) eines Leistenknopfs unter [key], damit ein Popover
 * mit seinem Pfeil darauf zeigen kann.
 */
fun Modifier.barAnchor(anchors: SnapshotStateMap<String, Float>, key: String): Modifier =
    onGloballyPositioned { c -> anchors[key] = c.positionInRoot().x + c.size.width / 2f }

/**
 * Popover über der Leiste mit Pfeil (14 × 8 dp) zum auslösenden Knopf.
 * [anchorRootX] = Mitte des Knopfs in Fenster-x (px, siehe [barAnchor]); NaN = mittig.
 * Öffnen: Einblenden + Skalieren 0,92 → 1 vom Pfeil aus (gefedert, Dämpfung 0,8);
 * Schließen: Ausblenden + Skalieren auf 0,95 in 130 ms.
 */
@Composable
fun BarPopover(
    visible: Boolean,
    anchorRootX: Float,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ui = LocalUi.current
    var ownRootX by remember { mutableFloatStateOf(0f) }
    var ownWidth by remember { mutableFloatStateOf(0f) }
    Box(modifier.onGloballyPositioned { ownRootX = it.positionInRoot().x; ownWidth = it.size.width.toFloat() }) {
        AnimatedVisibility(visible, enter = EnterTransition.None, exit = ExitTransition.None) {
            val p by transition.animateFloat(
                transitionSpec = {
                    if (targetState == EnterExitState.Visible) spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
                    else tween(130)
                },
                label = "popover",
            ) { if (it == EnterExitState.Visible) 1f else 0f }
            val entering = transition.targetState == EnterExitState.Visible
            val density = LocalDensity.current
            val arrowW = with(density) { 14.dp.toPx() }
            val arrowH = with(density) { 8.dp.toPx() }
            val margin = with(density) { 6.dp.toPx() }
            Layout(
                content = {
                    PanelCard(title = title, content = content)
                    Canvas(Modifier.size(14.dp, 8.dp)) {
                        val path = Path().apply {
                            moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height); close()
                        }
                        drawPath(path, ui.panel)
                    }
                },
                modifier = Modifier.graphicsLayer {
                    val a = if (ownWidth > 0f && !anchorRootX.isNaN()) ((anchorRootX - ownRootX) / ownWidth).coerceIn(0f, 1f) else 0.5f
                    transformOrigin = TransformOrigin(a, 1f)
                    alpha = p.coerceIn(0f, 1f)
                    val sc = if (entering) 0.92f + 0.08f * p else 0.95f + 0.05f * p
                    scaleX = sc; scaleY = sc
                },
            ) { measurables, constraints ->
                val card = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
                val arrow = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
                val width = if (constraints.hasBoundedWidth) constraints.maxWidth else card.width
                val ax = if (anchorRootX.isNaN()) width / 2f else (anchorRootX - ownRootX)
                val cardX = (ax - card.width / 2f).coerceIn(0f, (width - card.width).toFloat().coerceAtLeast(0f))
                val lo = cardX + margin * 2
                val hi = (cardX + card.width - arrowW - margin * 2).coerceAtLeast(lo)
                val arrowX = (ax - arrowW / 2f).coerceIn(lo, hi)
                layout(width, card.height + arrowH.roundToInt() + margin.roundToInt()) {
                    card.place(cardX.roundToInt(), 0)
                    arrow.place(arrowX.roundToInt(), card.height - 1)
                }
            }
        }
    }
}
