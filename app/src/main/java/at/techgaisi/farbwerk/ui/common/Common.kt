package at.techgaisi.farbwerk.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import at.techgaisi.farbwerk.ui.icons.AppIcons
import at.techgaisi.farbwerk.ui.theme.LocalUi
import at.techgaisi.farbwerk.ui.theme.UiMetrics
import at.techgaisi.farbwerk.ui.theme.UiMotion

/**
 * Weicher Panel-Schatten nach UI_SPEC §0: `0 8dp 24dp` Schwarz @ 45 %.
 *
 * Compose' `shadow()` kennt keinen Versatz; deshalb wird der Schatten mit `Paint.setShadowLayer` gezeichnet.
 * Die Fläche der Form selbst wird dabei ausgespart (clipOut), damit transluzente Panels transluzent bleiben.
 */
fun Modifier.panelShadow(
    color: Color,
    radius: Dp = UiMetrics.popoverRadius,
    blur: Dp = UiMetrics.popoverShadow,
    offsetY: Dp = UiMetrics.popoverShadowOffsetY,
): Modifier = drawWithCache {
    val r = radius.toPx()
    val path = Path().apply {
        addRoundRect(RoundRect(0f, 0f, size.width, size.height, r, r))
    }.asAndroidPath()
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.BLACK
        // CSS-Blur (≈ 2σ) → Android-Radius (σ ≈ 0,577 · r + 0,5)
        setShadowLayer((blur.toPx() * 0.5f - 0.5f) / 0.57735f, 0f, offsetY.toPx(), color.toArgb())
    }
    onDrawBehind {
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            nc.clipOutPath(path)
            nc.drawPath(path, paint)
            nc.restore()
        }
    }
}

/**
 * Popover-Karte für Panels (Pinsel, Ebenen, Farben, Aktionen, Anpassungen) und schwebende Karten.
 *
 * UI_SPEC §0/§3: Farbe `panel` (leicht transluzent), Eckenradius 13 dp, weicher Schatten 0 8 24 @ 45 %,
 * kein sichtbarer Rahmen. Position, Pfeil und Öffnen/Schließen-Animation übernimmt der Popover-Host
 * (ui/shell/PopoverHost).
 *
 * @param title optionaler Titel (22 sp, 700, Titelzeile 52 dp).
 * @param onAdd wenn gesetzt, erscheint rechts im Titel ein dünnes „+“ (22 dp, 1,5 dp Strich, 18 dp Rand).
 */
@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    onAdd: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ui = LocalUi.current
    val shape = RoundedCornerShape(UiMetrics.popoverRadius)
    Column(
        modifier
            .panelShadow(ui.shadow)
            .clip(shape)
            .background(ui.panel)
            // Klicks nicht zur Leinwand durchlassen
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
    ) {
        if (title != null) PanelTitle(title, onAdd = onAdd)
        content()
    }
}

/**
 * Titelzeile eines Popovers (UI_SPEC §3): 52 dp hoch, Titel 22 sp Gewicht 700 weiß bei 20 dp links / 16 dp oben,
 * optional „+“ rechts (22 dp, dünn, 18 dp vom rechten Rand).
 */
@Composable
fun PanelTitle(title: String, modifier: Modifier = Modifier, onAdd: (() -> Unit)? = null) {
    val ui = LocalUi.current
    Box(modifier.fillMaxWidth().height(UiMetrics.popoverTitleRow)) {
        Text(
            title, color = ui.text, fontSize = UiMetrics.popoverTitleSize, fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(start = UiMetrics.popoverTitleStart, top = UiMetrics.popoverTitleTop),
        )
        if (onAdd != null) {
            val click by rememberUpdatedState(onAdd)
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    // Trefferfläche 36 dp, Symbol 22 dp mit 18 dp Abstand zum rechten Rand
                    .offset(x = -(UiMetrics.popoverPlusEnd - 7.dp), y = UiMetrics.popoverTitleTop - 5.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable { click() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Plus, "Hinzufügen", tint = ui.text, modifier = Modifier.size(UiMetrics.popoverPlusSize))
            }
        }
    }
}

/** Haarlinie `separator` (0,5 dp) über die volle Breite, optional mit Einzug links. */
@Composable
fun Hairline(modifier: Modifier = Modifier, startIndent: Dp = 0.dp) {
    val ui = LocalUi.current
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(UiMetrics.hairline)
            .background(ui.separator)
    )
}

/**
 * iOS-artiger Schalter (UI_SPEC §6/§11): 44 × 26 dp, an = `accent`, aus = #5A5A5E, Knopf weiß Ø 22 dp,
 * Übergang 150 ms.
 */
@Composable
fun FwToggle(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val ui = LocalUi.current
    val track by animateColorAsState(if (checked) ui.accent else ui.toggleOff, UiMotion.toggle(), label = "tgTrack")
    val knobX by animateDpAsState(if (checked) 20.dp else 2.dp, UiMotion.toggle(), label = "tgKnob")
    Box(
        modifier
            .size(44.dp, 26.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.35f }
            .clip(RoundedCornerShape(13.dp))
            .background(track)
            .semantics {
                role = Role.Switch
                stateDescription = if (checked) "An" else "Aus"
            }
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null) {
                onChange(!checked)
            }
    ) {
        Box(
            Modifier
                .offset(x = knobX, y = 2.dp)
                .size(22.dp)
                .panelShadow(Color(0x40000000), radius = 11.dp, blur = 4.dp, offsetY = 1.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

/**
 * Symbol-Schaltfläche der Leisten.
 *
 *  - Aktiv: ohne [activeCircle] wird das Symbol in `accent` getönt (rechte Werkzeuge, §1); mit [activeCircle] > 0
 *    liegt eine blaue Kreisscheibe dieses Durchmessers hinter dem weißen Symbol (linke Symbole, §1).
 *  - Die Tönung wechselt sofort, ohne Animation (§11). Beim Drücken wird das Symbol leicht abgedunkelt.
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
    iconSize: Dp = if (size >= 36.dp) UiMetrics.topIconSize else 24.dp,
    enabled: Boolean = true,
    activeCircle: Dp = 0.dp,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val ui = LocalUi.current
    val circle = active && enabled && activeCircle > 0.dp
    val color = tint ?: when {
        !enabled -> ui.iconMuted
        circle -> ui.onAccent
        active -> ui.accent
        else -> ui.icon
    }
    var pressed by remember { mutableStateOf(false) }
    val pressAlpha by animateFloatAsState(if (pressed) 0.55f else 1f, UiMotion.quick(), label = "press")
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val haptic = LocalHapticFeedback.current
    Box(
        modifier
            .size(size)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
                if (active) stateDescription = "aktiv"
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
        if (circle) {
            Box(Modifier.size(activeCircle).clip(CircleShape).background(ui.accent))
        }
        Icon(
            icon, null,
            tint = color,
            modifier = Modifier.size(iconSize).graphicsLayer { alpha = pressAlpha }
        )
    }
}

/** Haltezeit für lange Berührungen auf Leistensymbolen. */
const val LONG_PRESS_MS = 450L
