package at.techgaisi.farbwerk.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Farben der Bedienoberfläche (eigene Palette). */
@Immutable
data class UiColors(
    val bar: Color,
    val panel: Color,
    val panelElevated: Color,
    val divider: Color,
    val icon: Color,
    val iconMuted: Color,
    val accent: Color,
    val text: Color,
    val textMuted: Color,
    val selectedRow: Color,
)

val DarkUi = UiColors(
    bar = Color(0xF2202024),
    panel = Color(0xFA2A2A30),
    panelElevated = Color(0xFF34343C),
    divider = Color(0x33FFFFFF),
    icon = Color(0xFFEDEDF0),
    iconMuted = Color(0xFF8E8E96),
    accent = Color(0xFF3D8BFF),
    text = Color(0xFFF2F2F5),
    textMuted = Color(0xFF9A9AA3),
    selectedRow = Color(0xFF2F6BD8),
)

val LightUi = UiColors(
    bar = Color(0xF2F4F4F6),
    panel = Color(0xFAFAFAFC),
    panelElevated = Color(0xFFFFFFFF),
    divider = Color(0x22000000),
    icon = Color(0xFF26262B),
    iconMuted = Color(0xFF8A8A92),
    accent = Color(0xFF1F6FEB),
    text = Color(0xFF17171A),
    textMuted = Color(0xFF6B6B73),
    selectedRow = Color(0xFF2F6BD8),
)

val LocalUi = staticCompositionLocalOf { DarkUi }

@Composable
fun FarbwerkTheme(light: Boolean, content: @Composable () -> Unit) {
    val ui = if (light) LightUi else DarkUi
    val scheme = if (light) lightColorScheme(primary = ui.accent) else darkColorScheme(primary = ui.accent)
    CompositionLocalProvider(LocalUi provides ui) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
