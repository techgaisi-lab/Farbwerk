package at.techgaisi.farbwerk.ui.selection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.ui.theme.LocalUi

/** Schwebende, abgerundete Leiste (Auswahl-, Transform- und Anpassungsleisten). */
@Composable
fun FloatingBar(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val ui = LocalUi.current
    Column(
        modifier
            .shadow(14.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(ui.panel)
            .border(0.5.dp, ui.divider, RoundedCornerShape(16.dp))
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/** Modus-Reiter (Text, aktiv hervorgehoben). */
@Composable
fun ModeTab(label: String, active: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    val ui = LocalUi.current
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) ui.accent.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = if (active) ui.accent else ui.icon, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, color = if (active) ui.accent else ui.text, fontSize = 13.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

/** Symbol-Schaltfläche mit kleiner Beschriftung darunter. */
@Composable
fun BarButton(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val ui = LocalUi.current
    val tint = when {
        !enabled -> ui.iconMuted.copy(alpha = 0.5f)
        active -> ui.accent
        else -> ui.icon
    }
    Column(
        Modifier
            .widthIn(min = 60.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) ui.accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = if (enabled) ui.textMuted else ui.textMuted.copy(alpha = 0.5f),
            fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 11.sp)
    }
}

/** Kleine Hinweis-Pille (z. B. "Schwellenwert: 42 %"). */
@Composable
fun InfoPill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xE6202024))
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) { Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

/** Dünner senkrechter Trenner zwischen Gruppen in einer Leiste. */
@Composable
fun BarDivider() {
    val ui = LocalUi.current
    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(30.dp).background(ui.divider))
}
