package at.techgaisi.farbwerk.ui.reference

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.ui.theme.LocalUi

/**
 * Gemeinsame Optik der schwebenden Fenster (UI_SPEC §7/§8): Farben (losgelöst), Kopieren & Einfügen, Referenz.
 *
 *  - Karte `panel`, Radius 13 dp, weicher Schatten 24 dp.
 *  - Greifleiste oben mittig 36 × 4 dp, Weiß @ 30 %, Radius 2 dp – Ziehen am Kopf verschiebt das Fenster.
 *  - Titel 18 sp / 700 links, optional Untertitel 12 sp @ 55 % darunter.
 *  - Schließen-Kreis rechts oben: Ø 18 dp Weiß @ 20 % mit weißem ×.
 *
 * Fehlende Tokens aus UI_SPEC §0 sind hier lokal definiert (Theme.kt gehört der Shell).
 */
object FloatingTokens {
    /** `panelInset` – Segmentleiste, Gruppen-Container. */
    val panelInset = Color(0xFF3A3A3C)
    /** `textSecondary` – Untertitel, Beschriftungen. */
    val textSecondary = Color(0x8CFFFFFF)
    /** `iconIdle` – inaktive Symbole. */
    val iconIdle = Color(0xD9FFFFFF)
    /** `accent` laut Spezifikation. */
    val accent = Color(0xFF0A84FF)
    val grip = Color(0x4DFFFFFF)
    val closeBg = Color(0x33FFFFFF)
    val radius = 13.dp
    val shadow = 24.dp
}

/**
 * Hülle eines schwebenden Fensters (Hintergrund, Radius, Schatten; Berührungen gehen nicht zur Leinwand durch).
 */
@Composable
fun FloatingWindowCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val ui = LocalUi.current
    val shape = RoundedCornerShape(FloatingTokens.radius)
    Column(
        modifier
            .shadow(FloatingTokens.shadow, shape, clip = false, ambientColor = ui.shadow, spotColor = ui.shadow)
            .clip(shape)
            .background(ui.panel)
            .border(0.5.dp, ui.divider.copy(alpha = ui.divider.alpha * 0.6f), shape)
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
        content = content,
    )
}

/** Greifleiste 36 × 4 dp. */
@Composable
fun GripBar(modifier: Modifier = Modifier) {
    Box(modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(FloatingTokens.grip))
}

/** Runder Schließen-Knopf: Kreis Ø 18 dp (Weiß @ 20 %) mit ×, Trefferfläche 32 dp. */
@Composable
fun CloseCircle(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            drawCircle(FloatingTokens.closeBg)
            val r = size.minDimension * 0.19f
            val c = center
            val sw = 1.5.dp.toPx()
            drawLine(Color.White, Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r), sw, StrokeCap.Round)
            drawLine(Color.White, Offset(c.x + r, c.y - r), Offset(c.x - r, c.y + r), sw, StrokeCap.Round)
        }
    }
}

/**
 * Kopf eines schwebenden Fensters: Greifleiste, Titel (+ Untertitel) und rechts [trailing]
 * (Standard: Schließen-Kreis). Der ganze Kopf ist Ziehfläche.
 *
 * @param onDrag Verschiebung in px seit dem letzten Ereignis.
 * @param subtitleContent ersetzt den einfachen Untertitel (z. B. antippbarer Modusname mit Menü).
 */
@Composable
fun FloatingWindowHeader(
    title: String,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDrag: (Offset) -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val ui = LocalUi.current
    val drag by rememberUpdatedState(onDrag)
    val start by rememberUpdatedState(onDragStart)
    val end by rememberUpdatedState(onDragEnd)
    Column(
        modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start() },
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                ) { ch, d -> ch.consume(); drag(d) }
            }
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), contentAlignment = Alignment.Center) { GripBar() }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f).padding(top = 2.dp)) {
                Text(
                    title, color = ui.text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 22.sp,
                )
                when {
                    subtitleContent != null -> subtitleContent()
                    subtitle != null -> Text(subtitle, color = FloatingTokens.textSecondary, fontSize = 12.sp, maxLines = 1)
                }
            }
            if (trailing != null) trailing()
            if (onClose != null) CloseCircle(onClose)
        }
    }
}
