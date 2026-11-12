package at.techgaisi.farbwerk.ui.adjust

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import at.techgaisi.farbwerk.adjust.AdjParam
import at.techgaisi.farbwerk.ui.theme.LocalUi

/*
 * Gemeinsame Bausteine der Anpassungs-Oberfläche. Maße/Farben nach §30 der Anleitung:
 * Popover Radius 13 dp, weicher Schatten, Zeilen 44 dp, Texte 15 sp; schwebende untere Leiste Radius 14 dp.
 */

/** Popover-Hintergrund (dunkles Thema ca. #2C2C2E, nahezu deckend). */
@Composable
internal fun popoverColor(): Color = LocalUi.current.panel

/** Schwebende Karte unten für die Zusatzregler einer Anpassung. */
@Composable
internal fun AdjustPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val ui = LocalUi.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .widthIn(max = 640.dp)
            .padding(horizontal = 12.dp)
            .shadow(24.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(popoverColor())
            .border(0.5.dp, ui.divider, shape)
            // Berührungen nicht zur Leinwand durchlassen
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/**
 * Regler im Stil des Vorbilds: Beschriftung links, Wert rechts (Ziffern fester Breite), darunter eine
 * schmale Spur mit weißem Knopf. [track] = eigener Verlauf der Spur (z. B. Farbton), sonst Akzentfüllung
 * (bei vorzeichenbehafteten Reglern ab der Mitte).
 */
@Composable
internal fun AdjSlider(
    label: String,
    valueText: String,
    fraction: Float,
    modifier: Modifier = Modifier,
    signed: Boolean = false,
    track: Brush? = null,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    val ui = LocalUi.current
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = ui.textMuted, fontSize = 13.sp, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(valueText, color = ui.text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"), maxLines = 1)
        }
        Canvas(
            Modifier.fillMaxWidth().height(30.dp).pointerInput(Unit) {
                val pad = 11.dp.toPx()
                fun f(x: Float) = ((x - pad) / (size.width - 2 * pad).coerceAtLeast(1f)).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    change(f(down.position.x))
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) { c.consume(); break }
                        change(f(c.position.x))
                        c.consume()
                    }
                    done()
                }
            }
        ) {
            val pad = 11.dp.toPx()
            val w = size.width - 2 * pad
            val cy = size.height / 2f
            val th = if (track != null) 6.dp.toPx() else 4.dp.toPx()
            val cr = CornerRadius(th / 2f, th / 2f)
            if (track != null) {
                drawRoundRect(track, Offset(pad, cy - th / 2f), Size(w, th), cr)
            } else {
                drawRoundRect(Color(0x33FFFFFF), Offset(pad, cy - th / 2f), Size(w, th), cr)
                val from = if (signed) 0.5f else 0f
                val a = minOf(from, fraction); val b = maxOf(from, fraction)
                if (b > a) drawRoundRect(ui.accent, Offset(pad + w * a, cy - th / 2f), Size(w * (b - a), th), cr)
            }
            val kx = pad + w * fraction.coerceIn(0f, 1f)
            drawCircle(Color(0x55000000), 11.5.dp.toPx(), Offset(kx, cy + 1.dp.toPx()))
            drawCircle(Color.White, 10.dp.toPx(), Offset(kx, cy))
        }
    }
}

/** Regler für einen Anpassungs-Parameter. */
@Composable
internal fun ParamSlider(
    p: AdjParam,
    value: Float,
    modifier: Modifier = Modifier,
    track: Brush? = null,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    AdjSlider(
        p.label, if (p.unit == "%") p.percentText(value) else p.format(value), p.fraction(value), modifier,
        signed = p.signed, track = track, onChange = { onChange(p.fromFraction(it)) }, onDone = onDone,
    )
}

/** Segment-Umschalter (Art/Modus). */
@Composable
internal fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val ui = LocalUi.current
    Row(modifier.clip(RoundedCornerShape(9.dp)).background(Color(0x1FFFFFFF)).padding(2.dp)) {
        options.forEachIndexed { i, s ->
            Box(
                Modifier.clip(RoundedCornerShape(7.dp))
                    .background(if (i == selected) ui.accent else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(s, color = if (i == selected) Color.White else ui.text, fontSize = 13.sp,
                    fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

/** Kleine Text-Schaltfläche (Pille). */
@Composable
internal fun PillButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val ui = LocalUi.current
    Box(
        modifier.clip(RoundedCornerShape(9.dp))
            .background(if (active) ui.accent else Color(0x1FFFFFFF))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, maxLines = 1, textAlign = TextAlign.Center,
            color = when {
                !enabled -> ui.textMuted.copy(alpha = 0.5f)
                active -> Color.White
                destructive -> DESTRUCTIVE
                else -> ui.text
            })
    }
}

internal val DESTRUCTIVE = Color(0xFFFF453A)

/**
 * Popover an einer Position im Fenster (Pixel) mit Ein-/Ausblend-Animation wie im Vorbild:
 * öffnen 0,92 → 1,0 + Einblenden (gefedert, Dämpfung 0,8), schließen 1,0 → 0,95 + Ausblenden 130 ms.
 * [anchor] = null schließt das Popover. Liegt der Punkt zu tief, öffnet es sich nach oben.
 * [anchor].x < 0 → waagerecht in der Fenstermitte.
 */
@Composable
internal fun AnchoredPopover(
    anchor: Offset?,
    onDismiss: () -> Unit,
    gap: Dp = 14.dp,
    content: @Composable () -> Unit,
) {
    val vis = remember { MutableTransitionState(false) }
    val last = remember { arrayOfNulls<Offset>(1) }
    if (anchor != null) last[0] = anchor
    vis.targetState = anchor != null
    val pos = last[0] ?: return
    if (!vis.currentState && !vis.targetState) return
    val provider = remember(pos, gap) { PointPositionProvider(pos, gap) }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(
            visibleState = vis,
            enter = fadeIn(tween(180)) + scaleIn(
                spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
                initialScale = 0.92f, transformOrigin = TransformOrigin(0.5f, 0f)),
            exit = fadeOut(tween(130)) + scaleOut(tween(130), targetScale = 0.95f, transformOrigin = TransformOrigin(0.5f, 0f)),
        ) { content() }
    }
}

private class PointPositionProvider(private val p: Offset, private val gap: Dp) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        // Dichte aus dem Verhältnis ist hier nicht bekannt → Abstand grob über Fensterbreite skaliert
        val g = (gap.value * density(windowSize)).toInt()
        val margin = g / 2
        val cx = if (p.x < 0f) windowSize.width / 2f else p.x
        val x = (cx - popupContentSize.width / 2f).toInt().coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        var y = p.y.toInt() + g
        if (y + popupContentSize.height > windowSize.height - margin) y = p.y.toInt() - g - popupContentSize.height
        return IntOffset(x, y.coerceAtLeast(margin))
    }

    private fun density(ws: IntSize): Float = android.content.res.Resources.getSystem().displayMetrics.density
}

/** Inhalt eines Popover-Menüs (Breite ~220 dp, Zeilen 44 dp, Trenner 0,5 dp). */
@Composable
internal fun PopoverMenuCard(width: Dp = 220.dp, content: @Composable ColumnScope.() -> Unit) {
    val ui = LocalUi.current
    val shape = RoundedCornerShape(13.dp)
    Column(
        Modifier.padding(16.dp) // Platz für den Schatten
            .width(width)
            .shadow(24.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(popoverColor())
            .border(0.5.dp, ui.divider, shape),
        content = content,
    )
}

@Composable
internal fun MenuDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(LocalUi.current.divider))
}

/** Menüzeile; [trailing] rechts (z. B. Häkchen oder Zustand). */
@Composable
internal fun MenuRow(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    checked: Boolean = false,
    trailing: String? = null,
    onClick: (() -> Unit)?,
) {
    val ui = LocalUi.current
    Row(
        modifier.fillMaxWidth().height(44.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f),
            color = when { !enabled -> ui.textMuted.copy(alpha = 0.5f); destructive -> DESTRUCTIVE; else -> ui.text })
        if (trailing != null) Text(trailing, fontSize = 13.sp, color = ui.accent)
        if (checked) Text("✓", fontSize = 15.sp, color = ui.accent, fontWeight = FontWeight.SemiBold)
    }
}
