package at.techgaisi.farbwerk.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
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
 * Farben der Bedienoberfläche (Optik-Vorgabe docs/UI_SPEC.md §0).
 *
 * Die ersten zehn Felder sind die ursprüngliche Palette (von allen Panels benutzt) und bleiben namentlich
 * erhalten; ihre Werte entsprechen jetzt den Spec-Tokens:
 *
 * | Spec-Token      | Feld                                   |
 * |-----------------|----------------------------------------|
 * | `bar`           | [bar]                                  |
 * | `panel`         | [panel]                                |
 * | `panelInset`    | [panelInset] (= [panelElevated])       |
 * | `rowPressed`    | [rowPressed]                           |
 * | `separator`     | [separator] (= [divider])              |
 * | `accent`        | [accent]                               |
 * | `selectRow`     | [selectedRow], [layerSelected], [layerSelectedSecondary] |
 * | `textPrimary`   | [text]                                 |
 * | `textSecondary` | [textMuted] (= [textSecondary])        |
 * | `iconIdle`      | [icon]                                 |
 * | `workspace`     | [workspace]                            |
 * | `galleryBg`     | [galleryBg]                            |
 *
 * Neue Felder haben Standardwerte (Dunkel-Modus), damit bestehende Konstruktor-Aufrufe weiter kompilieren.
 */
@Immutable
data class UiColors(
    /** Obere Leiste (`bar`: #1C1C1E @ 92 %). */
    val bar: Color,
    /** Popover-Hintergrund (`panel`: #2A2A2C @ 96 %, Leinwand schimmert leicht durch). */
    val panel: Color,
    /** Erhöhte Flächen in Popovers (gruppierte Container, Kacheln, Badges) – identisch mit [panelInset]. */
    val panelElevated: Color,
    /** Zeilentrenner / Haarlinien (`separator`: Weiß @ 8 %). */
    val divider: Color,
    /** Symbole inaktiv (`iconIdle`: Weiß @ 85 %). */
    val icon: Color,
    /** Symbole deaktiviert (Weiß @ 30–35 %). */
    val iconMuted: Color,
    /** Akzent (`accent`: #0A84FF) – aktive Symbole, Toggle an, Auswahl. */
    val accent: Color,
    /** `textPrimary`. */
    val text: Color,
    /** `textSecondary` (Weiß @ 55 %): Untertitel, Maße, Badges. */
    val textMuted: Color,
    /** Markierte Zeile (Pinsel; `selectRow`). */
    val selectedRow: Color,
    /** Sekundärauswahl (mehrere Ebenen/Pinsel markiert; #2E4C8F). */
    val selectedRowSecondary: Color = Color(0xFF2E4C8F),
    /** Arbeitsfläche um die Leinwand (`workspace`: #1E1E1E). */
    val workspace: Color = Color(0xFF1E1E1E),
    /** Warnung (z. B. Gesten-Konflikt). */
    val warning: Color = Color(0xFFFFCC00),
    /** Löschen / Destruktiv. */
    val destructive: Color = Color(0xFFFF453A),
    /** Sperren-Schaltfläche. */
    val neutralButton: Color = Color(0xFF5A5A5E),
    /** Daumen der Seitenleisten-Regler (Rechteck 20 × 11 dp, #CFCFCF). */
    val sliderThumb: Color = Color(0xFFCFCFCF),
    /** Rille der Seitenleisten-Regler (#1A1A1A). */
    val sliderTrack: Color = Color(0xFF1A1A1A),
    /** Schatten der Popovers (Schwarz @ 45 %). */
    val shadow: Color = Color(0x73000000),
    /** Text auf Akzentflächen. */
    val onAccent: Color = Color.White,

    // ---------------- Ergänzte Spec-Tokens (§0) ----------------
    /** `panelInset` (#3A3A3C): gruppierte Listen-Container, Set-Kacheln, Badges. */
    val panelInset: Color = Color(0xFF3A3A3C),
    /** `rowPressed` (#48484A): Gedrückt-Zustand von Zeilen. */
    val rowPressed: Color = Color(0xFF48484A),
    /** `separator` (Weiß @ 8 %): Haarlinien 0,5 dp zwischen Zeilen. */
    val separator: Color = Color(0x14FFFFFF),
    /** `textSecondary` (Weiß @ 55 %). */
    val textSecondary: Color = Color(0x8CFFFFFF),
    /** Primär ausgewählte Ebene (heller als accent, blau-violett). */
    val layerSelected: Color = Color(0xFF5E7CFF),
    /** Sekundär ausgewählte Ebene. */
    val layerSelectedSecondary: Color = Color(0xFF2E4C8F),
    /** `galleryBg`: Galerie-Hintergrund. */
    val galleryBg: Color = Color(0xFF0A0A0A),
    /** Hintergrund der Seitenleiste (#2C2C2E). */
    val sidebar: Color = Color(0xFF2C2C2E),
    /** Kontur des Modifizierer-Quadrats (Weiß @ 70 %). */
    val modifierStroke: Color = Color(0xB3FFFFFF),
    /** Toggle aus (#5A5A5E). */
    val toggleOff: Color = Color(0xFF5A5A5E),
    /** Greifleiste schwebender Fenster (Weiß @ 30 %). */
    val grabber: Color = Color(0x4DFFFFFF),
    /** Schließen-Kreis schwebender Fenster (Weiß @ 20 %). */
    val closeCircle: Color = Color(0x33FFFFFF),
)

val DarkUi = UiColors(
    bar = Color(0xEB1C1C1E),
    panel = Color(0xF52A2A2C),
    panelElevated = Color(0xFF3A3A3C),
    divider = Color(0x14FFFFFF),
    icon = Color(0xD9FFFFFF),
    iconMuted = Color(0x4DFFFFFF),
    accent = Color(0xFF0A84FF),
    text = Color(0xFFFFFFFF),
    textMuted = Color(0x8CFFFFFF),
    selectedRow = Color(0xFF0A84FF),
)

/** Helle Oberfläche (Einstellung „Helle Oberfläche“): gleiche Struktur, invertierte Helligkeiten. */
val LightUi = UiColors(
    bar = Color(0xEBF2F2F4),
    panel = Color(0xF5FFFFFF),
    panelElevated = Color(0xFFEDEDF0),
    divider = Color(0x1F000000),
    icon = Color(0xD91C1C1E),
    iconMuted = Color(0x4D1C1C1E),
    accent = Color(0xFF007AFF),
    text = Color(0xFF17171A),
    textMuted = Color(0x8C17171A),
    selectedRow = Color(0xFF007AFF),
    selectedRowSecondary = Color(0xFFBFD5FA),
    workspace = Color(0xFFD9D9DD),
    warning = Color(0xFFE0A800),
    destructive = Color(0xFFE5342B),
    neutralButton = Color(0xFF9A9AA0),
    sliderThumb = Color(0xFF3A3A3E),
    sliderTrack = Color(0xFFBDBDC2),
    shadow = Color(0x40000000),
    panelInset = Color(0xFFEDEDF0),
    rowPressed = Color(0xFFDCDCE0),
    separator = Color(0x1F000000),
    textSecondary = Color(0x8C17171A),
    layerSelected = Color(0xFF5E7CFF),
    layerSelectedSecondary = Color(0xFFBFD5FA),
    galleryBg = Color(0xFFF2F2F4),
    sidebar = Color(0xFFE4E4E8),
    modifierStroke = Color(0xB31C1C1E),
    toggleOff = Color(0xFFC7C7CC),
    grabber = Color(0x4D000000),
    closeCircle = Color(0x26000000),
)

val LocalUi = staticCompositionLocalOf { DarkUi }

/**
 * Maße der Shell (UI_SPEC §0–§3). Zentral, damit Leiste, Popover, Seitenleiste und Hinweisleiste dieselben
 * Werte verwenden. Bestehende Namen bleiben erhalten (andere Module lesen sie).
 */
object UiMetrics {
    // ---- obere Leiste (§1)
    val topBarHeight = 44.dp
    /** Symbolgröße links (Aktionen, Anpassungen, Auswahl, Transformieren). */
    val topIconSize = 22.dp
    /** Trefferfläche/Abstand Mitte-zu-Mitte links. */
    val topIconHit = 36.dp
    /** Symbolgröße rechts (Malen, Wischen, Radieren, Ebenen). */
    val topRightIconSize = 24.dp
    /** Abstand Mitte-zu-Mitte rechts. */
    val topRightIconPitch = 44.dp
    /** Lücke zwischen den Trefferflächen (Schrittweite ergibt sich aus Trefferfläche). */
    val topIconGap = 0.dp
    /** Linker Rand bis „Galerie“. */
    val topBarSidePadding = 12.dp
    /** Rechter Rand bis zum Farbkreis. */
    val topBarEndPadding = 14.dp
    /** Abstand Textende „Galerie“ → linke Kante des ersten Symbols. */
    val galleryToFirstIcon = 20.dp
    val galleryText = 15.sp
    /** Blaue Kreisscheibe hinter aktivem linken Symbol. */
    val topActiveCircle = 30.dp
    val colorDot = 28.dp
    val colorDotBorder = 1.5.dp
    /** Strichstärke der Leisten-Symbole. */
    val iconStroke = 1.8.dp

    // ---- Popover (§3)
    val popoverRadius = 13.dp
    /** Abstand Leistenunterkante → Oberkante der Karte. */
    val popoverGap = 6.dp
    val popoverArrowW = 18.dp
    val popoverArrowH = 9.dp
    /** Mindestabstand zum Bildschirmrand. */
    val popoverMargin = 8.dp
    /** Weichzeichnung des Panel-Schattens (0 8dp 24dp Schwarz @ 45 %). */
    val popoverShadow = 24.dp
    val popoverShadowOffsetY = 8.dp
    const val popoverMaxHeightFraction = 0.82f
    val popoverTitleRow = 52.dp
    val popoverTitleStart = 20.dp
    val popoverTitleTop = 16.dp
    val popoverTitleSize = 22.sp
    val popoverPlusSize = 22.dp
    val popoverPlusEnd = 18.dp

    // ---- Popover-Breiten (§3)
    val brushPanelMin = 400.dp
    val brushPanelMax = 470.dp
    val layersPanel = 300.dp
    val actionsPanel = 320.dp
    val colorsPanel = 320.dp
    val adjustPanel = 280.dp

    // ---- Seitenleiste (§2)
    val sidebarWidth = 34.dp
    /** Abstand zum Bildschirmrand (bündig). */
    val sidebarEdge = 0.dp
    val sidebarCorner = 7.dp
    /** Rillenhöhe (Spec 120 dp; auf kleinen Bildschirmen bis [sidebarSliderMin] verkürzt). */
    val sidebarSlider = 120.dp
    val sidebarSliderMin = 72.dp
    val sidebarGrooveWidth = 12.dp
    val sidebarGrooveRadius = 6.dp
    val sidebarThumbW = 20.dp
    val sidebarThumbH = 11.dp
    val sidebarThumbRadius = 3.dp
    val sidebarPadTop = 12.dp
    val sidebarPadBottom = 10.dp
    val sidebarGapModifier = 16.dp
    val sidebarGapUndo = 18.dp
    val sidebarGapRedo = 10.dp
    /** Vertikale Lage der Leistenmitte (Anteil der Bildschirmhöhe). */
    const val sidebarCenterFraction = 0.45f
    val modifierSize = 13.dp
    val modifierRadius = 2.5.dp
    val modifierStroke = 1.2.dp
    val undoIcon = 18.dp
    val undoButton = 26.dp
    val bubbleSize = 90.dp
    val bubbleRadius = 10.dp

    // ---- Hinweisleiste
    val pillHeight = 31.dp

    // ---- Eckenradien (§0)
    val rowRadius = 6.dp
    val pillRadius = 5.dp
    val hairline = 0.5.dp

    // ---- Typografie
    val titleSize = 22.sp
    val listSize = 15.sp
    val captionSize = 12.5.sp
    val pillTextSize = 13.5.sp

    // ---- Leinwand (§10)
    val canvasFitMargin = 40.dp

    // ---- QuickMenu
    val quickMenuDiameter = 248.dp
    val quickMenuButton = 64.dp
    val quickMenuCenter = 56.dp
}

/** Animations-Vorgaben (UI_SPEC §3, §11). */
object UiMotion {
    /** Ease-out (Material „decelerate“). */
    val EaseOut = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)

    const val POPOVER_OPEN_MS = 180
    const val POPOVER_CLOSE_MS = 120
    const val TOGGLE_MS = 150

    /** Popover öffnen: 180 ms ease-out (Skalierung 0,94 → 1 + Einblenden, Ursprung Pfeilspitze). */
    fun <T> popoverOpen() = tween<T>(durationMillis = POPOVER_OPEN_MS, easing = EaseOut)
    /** Popover schließen: 120 ms. */
    fun <T> popoverClose() = tween<T>(durationMillis = POPOVER_CLOSE_MS, easing = FastOutSlowInEasing)
    /** Toggles: 150 ms. */
    fun <T> toggle() = tween<T>(durationMillis = TOGGLE_MS, easing = FastOutSlowInEasing)
    /** Leisten im Vollbild herein/heraus: 200 ms. */
    fun <T> chrome() = tween<T>(durationMillis = 200, easing = FastOutSlowInEasing)
    fun chromeOffset() = tween<IntOffset>(durationMillis = 200, easing = FastOutSlowInEasing)
    /** Kleine Zustandswechsel (Drücken). Werkzeug-Tönung wechselt dagegen sofort (§11). */
    fun <T> quick() = tween<T>(durationMillis = 120)
    /** Federndes Aufploppen (QuickMenu, Pipetten-Ring). */
    fun <T> pop() = spring<T>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow * 2.4f)

    const val PILL_IN_MS = 150
    const val PILL_OUT_MS = 250
    const val BUBBLE_HIDE_DELAY_MS = 300L
    const val POPOVER_OPEN_SCALE = 0.94f
    const val POPOVER_CLOSE_SCALE = 0.94f
}

@Composable
fun FarbwerkTheme(light: Boolean, content: @Composable () -> Unit) {
    val ui = if (light) LightUi else DarkUi
    val scheme = if (light) lightColorScheme(primary = ui.accent, surface = ui.panel, background = ui.workspace)
    else darkColorScheme(primary = ui.accent, surface = ui.panel, background = ui.workspace)
    CompositionLocalProvider(LocalUi provides ui) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}


/**
 * App-Schrift: Inter (SIL Open Font License, siehe licenses/Inter-OFL.txt).
 * Inter ist eine freie, für Bildschirme gezeichnete Grotesk im Stil der iOS-Systemschrift;
 * die Apple-Schriften selbst dürfen nicht in Android-Apps mitgeliefert werden.
 */
val AppFont = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(at.techgaisi.farbwerk.R.font.inter_regular, androidx.compose.ui.text.font.FontWeight.Normal),
    androidx.compose.ui.text.font.Font(at.techgaisi.farbwerk.R.font.inter_medium, androidx.compose.ui.text.font.FontWeight.Medium),
    androidx.compose.ui.text.font.Font(at.techgaisi.farbwerk.R.font.inter_semibold, androidx.compose.ui.text.font.FontWeight.SemiBold),
    androidx.compose.ui.text.font.Font(at.techgaisi.farbwerk.R.font.inter_bold, androidx.compose.ui.text.font.FontWeight.Bold),
)

/** Alle Material-Textstile auf die App-Schrift umstellen (gilt auch für Text ohne eigenen Stil). */
val AppTypography: androidx.compose.material3.Typography = androidx.compose.material3.Typography().let { t ->
    fun androidx.compose.ui.text.TextStyle.f() = copy(fontFamily = AppFont)
    t.copy(
        displayLarge = t.displayLarge.f(), displayMedium = t.displayMedium.f(), displaySmall = t.displaySmall.f(),
        headlineLarge = t.headlineLarge.f(), headlineMedium = t.headlineMedium.f(), headlineSmall = t.headlineSmall.f(),
        titleLarge = t.titleLarge.f(), titleMedium = t.titleMedium.f(), titleSmall = t.titleSmall.f(),
        bodyLarge = t.bodyLarge.f(), bodyMedium = t.bodyMedium.f(), bodySmall = t.bodySmall.f(),
        labelLarge = t.labelLarge.f(), labelMedium = t.labelMedium.f(), labelSmall = t.labelSmall.f(),
    )
}
