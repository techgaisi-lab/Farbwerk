package at.techgaisi.farbwerk.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Farben der Bedienoberfläche (eigene Palette, Richtwerte aus Anleitung §30.1).
 *
 * Die ersten zehn Felder sind die ursprüngliche Palette (von allen Panels benutzt); die übrigen sind
 * Design-Tokens für Arbeitsfläche, Popover, Akzent-Varianten, Warnung und Destruktiv-Aktionen. Neue Felder
 * haben Standardwerte, damit bestehende Konstruktor-Aufrufe weiter kompilieren.
 */
@Immutable
data class UiColors(
    /** Obere Leiste und Seitenleiste (fast schwarz, leicht transparent). */
    val bar: Color,
    /** Popover-Hintergrund. */
    val panel: Color,
    /** Erhöhte Flächen in Popovers (Felder, Knöpfe, gewählte Reiter). */
    val panelElevated: Color,
    /** Zeilentrenner / feine Linien. */
    val divider: Color,
    /** Symbole inaktiv. */
    val icon: Color,
    /** Symbole deaktiviert / Nebeninfo. */
    val iconMuted: Color,
    /** Akzent (aktives Werkzeug, Primärauswahl, Hinweisleiste). */
    val accent: Color,
    val text: Color,
    val textMuted: Color,
    /** Primär gewählte Zeile (Ebenen, Pinsel). */
    val selectedRow: Color,
    /** Sekundärauswahl (mehrere Ebenen/Pinsel markiert). */
    val selectedRowSecondary: Color = Color(0xFF1E3E78),
    /** Arbeitsfläche um die Leinwand. */
    val workspace: Color = Color(0xFF1E1E20),
    /** Warnung (z. B. Gesten-Konflikt). */
    val warning: Color = Color(0xFFFFCC00),
    /** Löschen / Destruktiv. */
    val destructive: Color = Color(0xFFFF453A),
    /** Sperren-Schaltfläche. */
    val neutralButton: Color = Color(0xFF5A5A60),
    /** Schieberegler-Griff der Seitenleiste. */
    val sliderThumb: Color = Color(0xFFD8D8DC),
    /** Schieberegler-Rinne der Seitenleiste. */
    val sliderTrack: Color = Color(0x2EFFFFFF),
    /** Schatten der Popovers (Farbe, Deckkraft ~40 %). */
    val shadow: Color = Color(0x66000000),
    /** Text auf Akzentflächen. */
    val onAccent: Color = Color.White,
)

val DarkUi = UiColors(
    bar = Color(0xE6141416),
    panel = Color(0xFC28282A),
    panelElevated = Color(0xFF353538),
    divider = Color(0x1CFFFFFF),
    icon = Color(0xFFD8D8DC),
    iconMuted = Color(0xFF6E6E73),
    accent = Color(0xFF2F86F6),
    text = Color(0xFFF2F2F5),
    textMuted = Color(0xFF9A9AA0),
    selectedRow = Color(0xFF2F7CF6),
    selectedRowSecondary = Color(0xFF1E3E78),
    workspace = Color(0xFF1E1E20),
    sliderThumb = Color(0xFFD8D8DC),
    sliderTrack = Color(0x26FFFFFF),
)

val LightUi = UiColors(
    bar = Color(0xF0F2F2F4),
    panel = Color(0xFFFFFFFF),
    panelElevated = Color(0xFFEDEDF0),
    divider = Color(0x1F000000),
    icon = Color(0xFF1C1C1E),
    iconMuted = Color(0xFF8A8A92),
    accent = Color(0xFF1F6FEB),
    text = Color(0xFF17171A),
    textMuted = Color(0xFF6B6B73),
    selectedRow = Color(0xFF2F7CF6),
    selectedRowSecondary = Color(0xFFBFD5FA),
    workspace = Color(0xFFD9D9DD),
    warning = Color(0xFFE0A800),
    destructive = Color(0xFFE5342B),
    neutralButton = Color(0xFF9A9AA0),
    sliderThumb = Color(0xFF3A3A3E),
    sliderTrack = Color(0x22000000),
    shadow = Color(0x33000000),
)

val LocalUi = staticCompositionLocalOf { DarkUi }

/**
 * Maße und Bewegungs-Parameter der Shell (Anleitung §30.2–30.5). Zentral, damit Leiste, Popover,
 * Seitenleiste und Hinweisleiste dieselben Werte verwenden.
 */
object UiMetrics {
    // ---- obere Leiste
    val topBarHeight = 46.dp
    val topIconSize = 25.dp
    val topIconHit = 42.dp
    val topIconGap = 6.dp
    val topBarSidePadding = 8.dp
    val galleryText = 15.5.sp
    val colorDot = 29.dp

    // ---- Popover
    val popoverRadius = 13.dp
    val popoverGap = 4.dp          // Abstand Leistenunterkante → Pfeilspitze
    val popoverArrowW = 16.dp
    val popoverArrowH = 8.dp
    val popoverMargin = 8.dp       // Mindestabstand zum Bildschirmrand
    val popoverShadow = 24.dp
    const val popoverMaxHeightFraction = 0.78f

    // ---- Seitenleiste
    val sidebarWidth = 38.dp
    val sidebarEdge = 7.dp
    val sidebarSlider = 188.dp
    val sidebarSliderMin = 120.dp
    val modifierSize = 26.dp
    val modifierRadius = 6.dp
    val undoButton = 34.dp
    val bubbleSize = 90.dp

    // ---- Hinweisleiste
    val pillHeight = 31.dp

    // ---- Typografie
    val titleSize = 17.sp
    val listSize = 15.sp
    val captionSize = 12.5.sp
    val pillTextSize = 13.5.sp

    // ---- QuickMenu
    val quickMenuDiameter = 248.dp
    val quickMenuButton = 64.dp
    val quickMenuCenter = 56.dp
}

/** Animations-Vorgaben (Anleitung §30.5). */
object UiMotion {
    /** Popover öffnen: leicht gefedert (~200 ms, Dämpfung ~0,8, kein sichtbares Überschwingen). */
    fun <T> popoverOpen() = spring<T>(dampingRatio = 0.82f, stiffness = 900f)
    /** Popover schließen: 130 ms. */
    fun <T> popoverClose() = tween<T>(durationMillis = 130, easing = FastOutSlowInEasing)
    /** Leisten im Vollbild herein/heraus: 200 ms. */
    fun <T> chrome() = tween<T>(durationMillis = 200, easing = FastOutSlowInEasing)
    fun chromeOffset() = tween<IntOffset>(durationMillis = 200, easing = FastOutSlowInEasing)
    /** Kleine Zustandswechsel (Symbolfarbe, Drücken). */
    fun <T> quick() = tween<T>(durationMillis = 120)
    /** Federndes Aufploppen (QuickMenu, Pipetten-Ring). */
    fun <T> pop() = spring<T>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow * 2.4f)

    const val PILL_IN_MS = 150
    const val PILL_OUT_MS = 250
    const val BUBBLE_HIDE_DELAY_MS = 300L
    const val POPOVER_OPEN_SCALE = 0.92f
    const val POPOVER_CLOSE_SCALE = 0.95f
}

@Composable
fun FarbwerkTheme(light: Boolean, content: @Composable () -> Unit) {
    val ui = if (light) LightUi else DarkUi
    val scheme = if (light) lightColorScheme(primary = ui.accent, surface = ui.panel, background = ui.workspace)
    else darkColorScheme(primary = ui.accent, surface = ui.panel, background = ui.workspace)
    CompositionLocalProvider(LocalUi provides ui) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
