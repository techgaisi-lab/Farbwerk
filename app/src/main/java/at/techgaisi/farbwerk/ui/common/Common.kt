package at.techgaisi.farbwerk.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.ui.theme.LocalUi

/** Popover-Karte für Panels (Pinsel, Ebenen, Farben, Aktionen, Anpassungen). */
@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ui = LocalUi.current
    Column(
        modifier
            .shadow(16.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(ui.panel)
            .border(0.5.dp, ui.divider, RoundedCornerShape(14.dp))
            // Klicks nicht zur Leinwand durchlassen
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
    ) {
        if (title != null) {
            Text(
                title, color = ui.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 8.dp)
            )
        }
        content()
    }
}

@Composable
fun ToolIconButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean = false,
    size: Dp = 40.dp,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val ui = LocalUi.current
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription,
            tint = tint ?: if (active) ui.accent else ui.icon,
            modifier = Modifier.size(24.dp)
        )
    }
}
