package at.techgaisi.farbwerk.ui.gallery

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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.io.Importers
import at.techgaisi.farbwerk.ui.actions.ActionIcons
import at.techgaisi.farbwerk.ui.actions.CanvasOps
import at.techgaisi.farbwerk.ui.actions.FwDialog
import at.techgaisi.farbwerk.ui.actions.FwDivider
import at.techgaisi.farbwerk.ui.actions.FwTextField
import at.techgaisi.farbwerk.ui.actions.Segmented
import at.techgaisi.farbwerk.ui.theme.LocalUi
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Ergebnis des Dialogs "Neue Leinwand". */
data class CanvasSpec(val name: String, val width: Int, val height: Int, val dpi: Int, val background: Int)

private data class Template(val name: String, val w: Int, val h: Int, val dpi: Int, val note: String)

private enum class SizeUnit(val label: String, val perInch: Float) {
    PX("px", 0f), MM("mm", 25.4f), CM("cm", 2.54f), IN("in", 1f);

    fun toPx(v: Float, dpi: Int) = if (this == PX) v else v / perInch * dpi
    fun fromPx(px: Int, dpi: Int) = if (this == PX) px.toFloat() else px / dpi.toFloat() * perInch
}

private val backgroundChoices = listOf(
    0xFFFFFFFF.toInt(), 0xFFF4EEDF.toInt(), 0xFFBDBDBD.toInt(), 0xFF3A3A40.toInt(), 0xFF000000.toInt(),
)

/**
 * Menü "Neue Leinwand": Vorlagen und "Eigene Leinwand" (Breite, Höhe, Einheit, DPI,
 * Hinweis auf max. Ebenenanzahl, Farbprofil, Hintergrundfarbe).
 */
@Composable
fun NewCanvasDialog(onDismiss: () -> Unit, onCreate: (CanvasSpec) -> Unit) {
    val ui = LocalUi.current
    val ctx = LocalContext.current
    var custom by remember { mutableStateOf(false) }
    val templates = remember {
        val dm = ctx.resources.displayMetrics
        val sw = max(dm.widthPixels, dm.heightPixels); val sh = min(dm.widthPixels, dm.heightPixels)
        listOf(
            Template("Bildschirmgröße", sw, sh, 132, "Querformat"),
            Template("Quadrat", 2048, 2048, 300, "2048 px"),
            Template("Quadrat groß", 4096, 4096, 300, "4096 px"),
            Template("4K", 3840, 2160, 300, "UHD 16:9"),
            Template("Full HD", 1920, 1080, 72, "16:9"),
            Template("A4", 2480, 3508, 300, "210 × 297 mm, 300 dpi"),
            Template("A5", 1748, 2480, 300, "148 × 210 mm, 300 dpi"),
            Template("Comic", 1988, 3075, 300, "6,6 × 10,25 in, 300 dpi"),
            Template("Postkarte", 1748, 1240, 300, "148 × 105 mm, 300 dpi"),
        )
    }

    if (!custom) {
        FwDialog("Neue Leinwand", onDismiss, confirmLabel = null) {
            Row(
                Modifier.fillMaxWidth().clickable { custom = true }.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(ActionIcons.Plus, null, tint = ui.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Eigene Leinwand", color = ui.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f))
                Icon(ActionIcons.Chevron, null, tint = ui.iconMuted, modifier = Modifier.size(16.dp))
            }
            FwDivider()
            templates.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        onCreate(CanvasSpec("Ohne Titel", t.w, t.h, t.dpi, 0xFFFFFFFF.toInt()))
                    }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Seitenverhältnis-Vorschau
                    val r = t.w / t.h.toFloat()
                    Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(if (r >= 1) 28.dp else (28 * r).dp, if (r >= 1) (28 / r).dp else 28.dp)
                            .border(1.5.dp, ui.iconMuted, RoundedCornerShape(2.dp)))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.name, color = ui.text, fontSize = 15.sp)
                        Text(t.note, color = ui.textMuted, fontSize = 12.sp)
                    }
                    Text("${t.w} × ${t.h} px", color = ui.textMuted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    } else {
        CustomCanvasDialog(onBack = { custom = false }, onCreate = onCreate)
    }
}

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

    fun fmt(v: Float) = if (unit == SizeUnit.PX) v.roundToInt().toString() else String.format(Locale.GERMANY, "%.1f", v)

    FwDialog("Eigene Leinwand", onBack, confirmLabel = "Erstellen", confirmEnabled = error == null,
        dismissLabel = "Zurück", onConfirm = { onCreate(CanvasSpec(name.trim().ifEmpty { "Ohne Titel" }, wPx, hPx, dpi, bg)) }) {
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
            Text(if (error != null) error else "$wPx × $hPx px · max. ${CanvasOps.maxLayers(LocalContext.current, wPx, hPx)} Ebenen",
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
