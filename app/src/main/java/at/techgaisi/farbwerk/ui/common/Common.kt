package at.techgaisi.farbwerk.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import at.techgaisi.farbwerk.ui.theme.LocalUi
import at.techgaisi.farbwerk.ui.theme.UiMetrics
import at.techgaisi.farbwerk.ui.theme.UiMotion

/**
 * Popover-Karte für Panels (Pinsel, Ebenen, Farben, Aktionen, Anpassungen) und schwebende Karten.
 *
 * Eckenradius 13 dp, weicher Schatten (Blur ~24 dp, Schwarz ~40 %), nahezu deckender dunkelgrauer
 * Hintergrund, kein sichtbarer Rahmen (nur eine Haarlinie für den Kontrast auf hellen Leinwänden).
 * Position, Pfeil und Öffnen/Schließen-Animation übernimmt der Popover-Host (ui/shell/PopoverHost).
 */
@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ui = LocalUi.current
    val shape = RoundedCornerShape(UiMetrics.popoverRadius)
    Column(
        modifier
            .shadow(UiMetrics.popoverShadow, shape, clip = false, ambientColor = ui.shadow, spotColor = ui.shadow)
            .clip(shape)
            .background(ui.panel)
            .border(0.5.dp, ui.divider.copy(alpha = ui.divider.alpha * 0.6f), shape)
            // Klicks nicht zur Leinwand durchlassen
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
    ) {
        if (title != null) PanelTitle(title)
        content()
    }
}

/** Titelzeile eines Popovers: 17 sp halbfett, 16 dp Rand. */
@Composable
fun PanelTitle(title: String, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    Text(
        title, color = ui.text, fontSize = UiMetrics.titleSize, fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = 16.dp, top = 14.dp, bottom = 8.dp)
    )
}

/**
 * Symbol-Schaltfläche der Leisten.
 *
 *  - Aktive Symbole werden in der Akzentfarbe (Blau) gezeigt, der Farbwechsel ist kurz animiert.
 *  - Beim Drücken wird das Symbol leicht abgedunkelt und skaliert (wie im Vorbild, ohne Ripple).
 *  - Optional [onLongClick] (Halten ~450 ms), z. B. „Wischen mit aktuellem Pinsel“.
 *  - [modifier] wird auf die Trefferfläche angewendet (z. B. für die Popover-Verankerung).
 */
@Composable
fun ToolIconButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean = false,
    size: Dp = UiMetrics.topIconHit,
    tint: Color? = null,
    modifier: Modifier = Modifier,
    iconSize: Dp = if (size >= 40.dp) UiMetrics.topIconSize else 24.dp,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val ui = LocalUi.current
    val target = tint ?: when {
        !enabled -> ui.iconMuted
        active -> ui.accent
        else -> ui.icon
    }
    val color by animateColorAsState(target, UiMotion.quick(), label = "iconTint")
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(if (pressed) 0.9f else 1f, UiMotion.quick(), label = "press")
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val haptic = LocalHapticFeedback.current
    Box(
        modifier
            .size(size)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
                onClick { click(); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    pressed = true
                    var up: androidx.compose.ui.input.pointer.PointerInputChange? = null
                    val lc = longClick
                    val completed = if (lc != null) {
                        withTimeoutOrNull(LONG_PRESS_MS) { up = waitForUpOrCancellation(); true }
                    } else {
                        up = waitForUpOrCancellation(); true
                    }
                    pressed = false
                    if (completed == null) {
                        // Halten erkannt: lange Aktion, Loslassen danach löst keinen Klick mehr aus
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        lc?.invoke()
                        waitForUpOrCancellation()?.consume()
                    } else {
                        up?.let { it.consume(); click() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, null,
            tint = color,
            modifier = Modifier.size(iconSize).graphicsLayer {
                scaleX = pressScale; scaleY = pressScale
                alpha = if (pressed) 0.6f else 1f
            }
        )
    }
}

/** Haltezeit für lange Berührungen auf Leistensymbolen. */
const val LONG_PRESS_MS = 450L
