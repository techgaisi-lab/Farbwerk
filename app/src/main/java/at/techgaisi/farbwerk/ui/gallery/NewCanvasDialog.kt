package at.techgaisi.farbwerk.ui.gallery

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
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
import at.techgaisi.farbwerk.io.Importers
import at.techgaisi.farbwerk.ui.actions.CanvasOps
import at.techgaisi.farbwerk.ui.actions.FwDialog
import at.techgaisi.farbwerk.ui.actions.FwTextField
import at.techgaisi.farbwerk.ui.actions.Segmented
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Ergebnis des Dialogs "Neue Leinwand". [width]/[height]/[dpi] sind die gewählten (logischen) Maße; [detail] ist
 * die Feinberechnung (1 oder 2) – die Leinwand wird mit [pixelWidth] × [pixelHeight] Pixeln angelegt.
 */
data class CanvasSpec(
    val name: String, val width: Int, val height: Int, val dpi: Int, val background: Int, val detail: Int = 1,
) {
    val pixelWidth: Int get() = width * detail
    val pixelHeight: Int get() = height * detail
    val pixelDpi: Int get() = (dpi * detail).coerceAtMost(4800)
}

/** Feinberechnung 2× ist möglich, wenn die doppelten Maße in die Leinwandgrenzen passen (≤ 8192 px, ≤ 25 MP). */
fun highQualityPossible(w: Int, h: Int): Boolean =
    w * 2 <= Importers.MAX_SIDE && h * 2 <= Importers.MAX_SIDE && (w * 2L) * (h * 2L) <= Importers.MAX_CANVAS_PIXELS

/** Merkt sich die Wahl „Hohe Qualität (2×)“ für die laufende Sitzung (Standard: an). */
internal object NewCanvasMemory { var highQuality by mutableStateOf(true) }

/** Lokale Optik-Werte der Galerie (UI_SPEC §0, §3, §9) – bewusst nicht in Theme.kt. */
internal object GalleryTokens {
    val galleryBg = Color(0xFF050505)
    val panel = Color(0xF52A2A2C)          // #2A2A2C @ 96 %
    val panelInset = Color(0xFF3A3A3C)
    val rowPressed = Color(0xFF48484A)
    val separator = Color(0x14FFFFFF)      // Weiß @ 8 %
    val textPrimary = Color.White
    val textSecondary = Color(0x8CFFFFFF)  // Weiß @ 55 %
    val iconIdle = Color(0xD9FFFFFF)       // Weiß @ 85 %
    val accent = Color(0xFF0A84FF)
    val destructive = Color(0xFFFF453A)
    val panelRadius = 13.dp
    val arrowW = 18.dp
    val arrowH = 9.dp
    val screenMargin = 8.dp
}

/**
 * Vorlage für eine neue Leinwand.
 * [pill] ist der Text der Maß-Pill (Pixel oder physische Maße wie im Vorbild), [profile] der Farbprofil-Text.
 */
private data class Template(val name: String, val w: Int, val h: Int, val dpi: Int, val pill: String, val profile: String = "sRGB")

private enum class SizeUnit(val label: String, val perInch: Float) {
    PX("px", 0f), MM("mm", 25.4f), CM("cm", 2.54f), IN("in", 1f);

    fun toPx(v: Float, dpi: Int) = if (this == PX) v else v / perInch * dpi
    fun fromPx(px: Int, dpi: Int) = if (this == PX) px.toFloat() else px / dpi.toFloat() * perInch
}

private val backgroundChoices = listOf(
    0xFFFFFFFF.toInt(), 0xFFF4EEDF.toInt(), 0xFFBDBDBD.toInt(), 0xFF3A3A40.toInt(), 0xFF000000.toInt(),
)

/**
 * "Neue Leinwand" als Popover mit Pfeil (UI_SPEC §3/§9).
 *
 * Der Popover richtet sich an der Fläche aus, in der dieser Composable aufgerufen wird (typisch: das „+“ in der
 * Galerie-Kopfzeile) – der Pfeil zeigt mittig darauf. Titelzeile mit Ordner-Symbol rechts („Eigene Leinwand“),
 * darunter die Vorlagen: Name links, rechts Farbprofil-Text und Maß-Pill; Haarlinien zwischen den Zeilen,
 * „Bildschirmgröße“ als erste Zeile abgesetzt.
 */
@Composable
fun NewCanvasDialog(onDismiss: () -> Unit, onCreate: (CanvasSpec) -> Unit) {
    val ctx = LocalContext.current
    var custom by remember { mutableStateOf(false) }
    val templates = remember {
        val dm = ctx.resources.displayMetrics
        val sw = max(dm.widthPixels, dm.heightPixels); val sh = min(dm.widthPixels, dm.heightPixels)
        listOf(
            Template("Bildschirmgröße", sw, sh, 132, px(sw, sh)),
            Template("Quadrat", 2048, 2048, 300, px(2048, 2048)),
            Template("Quadrat groß", 4096, 4096, 300, px(4096, 4096)),
            Template("4K", 3840, 2160, 300, px(3840, 2160)),
            Template("Full HD", 1920, 1080, 72, px(1920, 1080)),
            Template("A4", 2480, 3508, 300, "210 × 297mm"),
            Template("A5", 1748, 2480, 300, "148 × 210mm"),
            Template("Comicseite", 1988, 3075, 300, "6,6 × 10,25\""),
            Template("Postkarte", 1748, 1240, 300, "148 × 105mm"),
            Template("Skizzenblatt", 1500, 1500, 150, px(1500, 1500)),
            Template("Aufkleber", 512, 512, 72, px(512, 512)),
        )
    }

    if (custom) {
        CustomCanvasDialog(onBack = { custom = false }, onCreate = onCreate)
        return
    }

    AnchoredPopover(onDismiss = onDismiss, width = 320.dp) { dismiss ->
        // ---------------------------------------------------------------- Titel
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Neue Leinwand", color = GalleryTokens.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).clickable { custom = true },
                contentAlignment = Alignment.Center
            ) { FolderPlusIcon(Modifier.size(width = 24.dp, height = 20.dp)) }
        }
        HighQualityRow()
        Hairline()

        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            // Erste Zeile abgesetzt
            val hq = NewCanvasMemory.highQuality
            TemplateRow(templates.first(), hq) { dismiss { onCreate(spec(templates.first(), hq)) } }
            Spacer(Modifier.height(18.dp))
            Hairline()
            templates.drop(1).forEachIndexed { i, t ->
                if (i > 0) Hairline()
                TemplateRow(t, hq) { dismiss { onCreate(spec(t, hq)) } }
            }
        }
    }
}

private fun px(w: Int, h: Int) = "$w × ${h}px"
/** Feinberechnung: bei Hoher Qualität mind. 2×, kleine Leinwände mehr; ohne nur sehr kleine bis Bildschirmgröße. */
private fun detailFor(w: Int, h: Int, hq: Boolean) = at.techgaisi.farbwerk.io.CanvasQuality.detailFor(w, h, hq)
private fun spec(t: Template, hq: Boolean) =
    CanvasSpec("Ohne Titel", t.w, t.h, t.dpi, 0xFFFFFFFF.toInt(), detailFor(t.w, t.h, hq))

/** Schalter „Hohe Qualität (2×)“: Leinwand intern mit doppelter Auflösung (Pinsel wirken gleich groß). */
@Composable
private fun HighQualityRow() {
    val on = NewCanvasMemory.highQuality
    Row(
        Modifier.fillMaxWidth().clickable { NewCanvasMemory.highQuality = !on }
            .padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Hohe Qualität", color = GalleryTokens.textPrimary, fontSize = 14.sp)
            Text("Doppelte Auflösung – auch stark gezoomt scharf", color = GalleryTokens.textSecondary, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        MiniSwitch(on)
    }
}

/** Kleiner Schalter im Stil der Galerie. */
@Composable
private fun MiniSwitch(on: Boolean) {
    Box(
        Modifier.size(width = 40.dp, height = 24.dp).clip(RoundedCornerShape(12.dp))
            .background(if (on) GalleryTokens.accent else GalleryTokens.panelInset)
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart
    ) { Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White)) }
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(GalleryTokens.separator))
}

@Composable
private fun TemplateRow(t: Template, hq: Boolean, onClick: () -> Unit) {
    // Pixel-Vorlagen zeigen die tatsächlich angelegten Pixelmaße
    val k = detailFor(t.w, t.h, hq)
    val pill = if (t.pill == px(t.w, t.h)) px(t.w * k, t.h * k) else t.pill
    Row(
        Modifier.fillMaxWidth().height(34.dp).clickable(onClick = onClick).padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(t.name, color = GalleryTokens.textPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        Text(t.profile, color = GalleryTokens.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp))
        Box(
            Modifier.width(104.dp).clip(RoundedCornerShape(5.dp)).background(GalleryTokens.panelInset)
                .padding(vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) { Text(pill, color = GalleryTokens.textPrimary, fontSize = 11.sp, maxLines = 1) }
    }
}

/** Ordner mit kleinem „+“ (eigene Zeichnung, 1.5 dp Strich) – öffnet „Eigene Leinwand“. */
@Composable
private fun FolderPlusIcon(modifier: Modifier) {
    Canvas(modifier) {
        val s = 1.5.dp.toPx()
        val w = size.width; val h = size.height
        val stroke = Stroke(s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p = Path().apply {
            moveTo(s, h * 0.22f)
            lineTo(w * 0.36f, h * 0.22f)
            lineTo(w * 0.44f, h * 0.36f)
            lineTo(w - s, h * 0.36f)
            lineTo(w - s, h - s)
            lineTo(s, h - s)
            close()
        }
        drawPath(p, GalleryTokens.iconIdle, style = stroke)
        val cx = w * 0.5f; val cy = h * 0.66f; val r = h * 0.17f
        drawLine(GalleryTokens.iconIdle, Offset(cx - r, cy), Offset(cx + r, cy), s, StrokeCap.Round)
        drawLine(GalleryTokens.iconIdle, Offset(cx, cy - r), Offset(cx, cy + r), s, StrokeCap.Round)
    }
}

/** Dünnes „+“ (Strich 1.5 dp) für die Galerie-Kopfzeile. */
@Composable
internal fun ThinPlus(size: Dp, color: Color = GalleryTokens.textPrimary, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = 1.5.dp.toPx()
        val c = this.size.width / 2f
        drawLine(color, Offset(c, s), Offset(c, this.size.height - s), s, StrokeCap.Round)
        drawLine(color, Offset(s, c), Offset(this.size.width - s, c), s, StrokeCap.Round)
    }
}

// ============================================================================ Popover-Rahmen

/** Gemeinsame Pfeil-Position zwischen Positionsberechnung und Zeichnung (nur Zeichenphase liest sie). */
private class ArrowHolder { var x by mutableFloatStateOf(0f) }

/**
 * Popover, der unter der umgebenden Fläche (Anker) hängt: Pfeil 18 × 9 dp zeigt mittig auf den Anker,
 * Panel hält ≥ 8 dp Abstand zum Rand. Öffnen 180 ms (Skalierung 0.94 → 1 + Einblenden, Ursprung Pfeilspitze),
 * Schließen 120 ms. [content] erhält eine `dismiss`-Funktion, die nach der Schließanimation den Rückruf ausführt.
 */
@Composable
private fun AnchoredPopover(
    onDismiss: () -> Unit,
    width: Dp,
    content: @Composable (dismiss: (after: () -> Unit) -> Unit) -> Unit,
) {
    val density = LocalDensity.current
    val arrow = remember { ArrowHolder() }
    val anim = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { anim.animateTo(1f, tween(180, easing = LinearOutSlowInEasing)) }

    fun dismiss(after: () -> Unit) {
        if (closing) return
        closing = true
        scope.launch {
            anim.animateTo(0f, tween(120, easing = FastOutSlowInEasing))
            after()
        }
    }

    val provider = remember(density) { PopoverPosition(density, width, arrow) }
    val maxPanelH = (LocalConfiguration.current.screenHeightDp * 0.82f).dp

    Popup(
        popupPositionProvider = provider,
        onDismissRequest = { dismiss(onDismiss) },
        properties = PopupProperties(focusable = true, dismissOnClickOutside = true, dismissOnBackPress = true),
    ) {
        val widthPx = with(density) { width.toPx() }
        Column(
            Modifier.width(width).graphicsLayer {
                val t = anim.value
                alpha = t
                scaleX = 0.94f + 0.06f * t; scaleY = scaleX
                transformOrigin = TransformOrigin((arrow.x / widthPx).coerceIn(0f, 1f), 0f)
            }
        ) {
            // Pfeil
            Canvas(Modifier.fillMaxWidth().height(GalleryTokens.arrowH)) {
                val aw = GalleryTokens.arrowW.toPx()
                val ax = arrow.x.coerceIn(aw, size.width - aw)
                val p = Path().apply {
                    moveTo(ax - aw / 2f, size.height + 1f)
                    lineTo(ax, 0f)
                    lineTo(ax + aw / 2f, size.height + 1f)
                    close()
                }
                drawPath(p, GalleryTokens.panel)
            }
            Column(
                Modifier.fillMaxWidth().heightIn(max = maxPanelH)
                    .shadow(24.dp, RoundedCornerShape(GalleryTokens.panelRadius), ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(RoundedCornerShape(GalleryTokens.panelRadius))
                    .background(GalleryTokens.panel)
            ) { content(::dismiss) }
        }
    }
}

/** Platziert den Popover unter dem Anker, rechtsbündig verschoben so dass der Pfeil mittig auf den Anker zeigt. */
private class PopoverPosition(private val density: Density, private val width: Dp, private val arrow: ArrowHolder) :
    PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
    ): IntOffset {
        val margin = with(density) { GalleryTokens.screenMargin.roundToPx() }
        val w = popupContentSize.width.takeIf { it > 0 } ?: with(density) { width.roundToPx() }
        val cx = anchorBounds.center.x
        // Panel möglichst so, dass der Anker im rechten Bereich liegt (wie beim Vorbild am Bildschirmrand)
        val x = (cx - w / 2).coerceIn(margin, max(margin, windowSize.width - margin - w))
        arrow.x = (cx - x).toFloat()
        val y = anchorBounds.bottom + with(density) { 2.dp.roundToPx() }
        return IntOffset(x, y)
    }
}

// ============================================================================ Eigene Leinwand

@Composable
private fun CustomCanvasDialog(onBack: () -> Unit, onCreate: (CanvasSpec) -> Unit) {
    val ui = LocalUi.current
    var name by remember { mutableStateOf("Ohne Titel") }
    var unit by remember { mutableStateOf(SizeUnit.PX) }
    var dpiText by remember { mutableStateOf("300") }
    var wText by remember { mutableStateOf("2048") }
    var hText by remember { mutableStateOf("2048") }
    var bg by remember { mutableIntStateOf(0xFFFFFFFF.toInt()) }

    val dpi = dpiText.toIntOrNull()?.coerceIn(1, 4800) ?: 300
    fun parse(s: String) = s.replace(',', '.').toFloatOrNull()
    val wPx = parse(wText)?.let { unit.toPx(it, dpi).roundToInt() } ?: 0
    val hPx = parse(hText)?.let { unit.toPx(it, dpi).roundToInt() } ?: 0
    val error = when {
        wPx < 16 || hPx < 16 -> "Mindestens 16 px je Seite"
        wPx > Importers.MAX_SIDE || hPx > Importers.MAX_SIDE -> "Maximal ${Importers.MAX_SIDE} px je Seite"
        wPx.toLong() * hPx > Importers.MAX_CANVAS_PIXELS -> "Zu groß (max. 25 Megapixel)"
        else -> null
    }
    val hq = NewCanvasMemory.highQuality
    val detail = if (error == null) detailFor(wPx, hPx, hq) else 1

    fun fmt(v: Float) = if (unit == SizeUnit.PX) v.roundToInt().toString() else String.format(Locale.GERMANY, "%.1f", v)

    FwDialog("Eigene Leinwand", onBack, confirmLabel = "Erstellen", confirmEnabled = error == null,
        dismissLabel = "Zurück", onConfirm = { onCreate(CanvasSpec(name.trim().ifEmpty { "Ohne Titel" }, wPx, hPx, dpi, bg, detail)) }) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            FwTextField(name, { name = it }, "Name", Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text("Einheit", color = ui.textMuted, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Segmented(SizeUnit.entries.map { it to it.label }, unit, Modifier.fillMaxWidth()) { nu ->
                // Werte beim Einheitenwechsel umrechnen
                val wp = wPx; val hp = hPx
                unit = nu
                if (wp > 0) wText = fmt(nu.fromPx(wp, dpi))
                if (hp > 0) hText = fmt(nu.fromPx(hp, dpi))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FwTextField(wText, { wText = it.take(8) }, "Breite (${unit.label})", Modifier.weight(1f), number = true)
                FwTextField(hText, { hText = it.take(8) }, "Höhe (${unit.label})", Modifier.weight(1f), number = true)
            }
            Spacer(Modifier.height(8.dp))
            FwTextField(dpiText, { dpiText = it.filter(Char::isDigit).take(4) }, "DPI", Modifier.fillMaxWidth(), number = true)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().clickable { NewCanvasMemory.highQuality = !hq }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Hohe Qualität", color = ui.text, fontSize = 15.sp)
                    Text(
                        when {
                            !hq -> "Aus"
                            error == null && detail == 1 -> "Bei dieser Größe nicht möglich (max. 8192 px, 25 MP)"
                            else -> "${detail}× feiner berechnet – auch stark gezoomt scharf"
                        },
                        color = ui.textMuted, fontSize = 12.sp,
                    )
                }
                MiniSwitch(hq)
            }
            Spacer(Modifier.height(6.dp))
            Text(if (error != null) error else "${wPx * detail} × ${hPx * detail} px · max. ${CanvasOps.maxLayers(LocalContext.current, wPx * detail, hPx * detail)} Ebenen",
                color = if (error != null) Color(0xFFFF5A52) else ui.textMuted, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Farbprofil", color = ui.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text("sRGB IEC61966-2.1", color = ui.textMuted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hintergrund", color = ui.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                backgroundChoices.forEach { c ->
                    val sel = c == bg
                    Box(
                        Modifier.padding(start = 6.dp).size(26.dp).clip(CircleShape).background(Color(c))
                            .border(if (sel) 2.dp else 1.dp, if (sel) ui.accent else ui.divider, CircleShape)
                            .clickable { bg = c }
                    )
                }
            }
            Spacer(Modifier.heightIn(min = 8.dp))
        }
    }
}
