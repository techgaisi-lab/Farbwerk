package at.techgaisi.farbwerk.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.ui.theme.LocalUi
import at.techgaisi.farbwerk.ui.theme.UiMetrics
import at.techgaisi.farbwerk.ui.theme.UiMotion
import kotlin.math.roundToInt

/** Inhalt der Hinweisleiste (entweder kurzer Hinweis oder dauerhafte Statusleiste). */
@Immutable
private data class HintContent(
    val text: String,
    val percent: Float?,
    val actionLabel: String?,
    val onAction: (() -> Unit)?,
)

/**
 * Hinweisleiste oben Mitte (Anleitung §1, §30.3, §30.5): blaue Pille (Höhe ~31 dp, Radius = halbe Höhe)
 * mit weißer Schrift, Zahlen mit fester Ziffernbreite.
 *
 *  - Kurze Hinweise ([EditorState.flash]) überdecken kurz eine dauerhafte Statusleiste
 *    ([EditorState.statusBar]) und geben sie danach wieder frei.
 *  - Statusleisten mit Prozentwert zeigen den Wert zusätzlich als hellere Füllung der Pille.
 *  - Optionale Aktion rechts (z. B. „Form bearbeiten“, „Weiter füllen“), abgetrennt durch eine feine Linie.
 *  - Einblenden 150 ms, Ausblenden 250 ms; eine neue Meldung ersetzt die alte sofort.
 */
@Composable
fun HintBar(state: EditorState, modifier: Modifier = Modifier) {
    val flash = state.flashMessage
    val status = state.statusBar
    val content: HintContent? = when {
        flash != null -> HintContent(flash, null, state.flashActionLabel, if (state.flashActionLabel != null) ({ state.triggerFlashAction() }) else null)
        status != null -> HintContent(status.text, status.percent, status.actionLabel, status.onAction)
        else -> null
    }
    // Letzten Inhalt während der Ausblendanimation behalten
    var last by remember { mutableStateOf<HintContent?>(null) }
    if (content != null) last = content

    val topPad by animateDpAsState(
        if (state.fullscreen) 10.dp else UiMetrics.topBarHeight + 8.dp, UiMotion.chrome(), label = "hintTop",
    )
    Box(modifier.fillMaxWidth().statusBarsPadding().padding(top = topPad), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = content != null,
            enter = fadeIn(tween(UiMotion.PILL_IN_MS)) + scaleIn(tween(UiMotion.PILL_IN_MS), initialScale = 0.92f),
            exit = fadeOut(tween(UiMotion.PILL_OUT_MS)) + scaleOut(tween(UiMotion.PILL_OUT_MS), targetScale = 0.97f),
        ) {
            last?.let { HintPill(it) }
        }
    }
}

@Composable
private fun HintPill(c: HintContent) {
    val ui = LocalUi.current
    val shape = RoundedCornerShape(50)
    val fill by animateFloatAsState(c.percent ?: 0f, tween(90), label = "hintFill")
    Row(
        Modifier
            .height(UiMetrics.pillHeight)
            .widthIn(min = 120.dp, max = 560.dp)
            .shadow(10.dp, shape, ambientColor = ui.shadow, spotColor = ui.shadow)
            .clip(shape)
            .background(ui.accent)
            .drawBehind {
                if (c.percent != null) {
                    // Wert als hellere Füllung von links
                    drawRoundRect(
                        Color.White.copy(alpha = 0.16f),
                        size = Size(size.width * fill.coerceIn(0f, 1f), size.height),
                        cornerRadius = CornerRadius(size.height / 2),
                    )
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(16.dp))
        Text(
            c.text + (c.percent?.let { "  ${(it * 100).roundToInt()} %" } ?: ""),
            color = ui.onAccent,
            fontSize = UiMetrics.pillTextSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
            modifier = Modifier.weight(1f, fill = false),
        )
        if (c.actionLabel != null && c.onAction != null) {
            Spacer(Modifier.width(12.dp))
            Box(Modifier.width(0.5.dp).fillMaxHeight().padding(vertical = 7.dp).background(Color.White.copy(alpha = 0.45f)))
            Box(
                Modifier.fillMaxHeight().clickable { c.onAction.invoke() }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    c.actionLabel,
                    color = ui.onAccent,
                    fontSize = UiMetrics.pillTextSize,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        } else {
            Spacer(Modifier.width(16.dp))
        }
    }
}
