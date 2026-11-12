package at.techgaisi.farbwerk.ui.text

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.InputPoint
import at.techgaisi.farbwerk.model.Layer
import at.techgaisi.farbwerk.model.Panel
import at.techgaisi.farbwerk.model.ToolInputHandler
import at.techgaisi.farbwerk.text.FontRegistry
import at.techgaisi.farbwerk.text.TextAlign
import at.techgaisi.farbwerk.text.TextData
import at.techgaisi.farbwerk.text.TextLayers
import at.techgaisi.farbwerk.text.TextRenderer
import at.techgaisi.farbwerk.ui.actions.BarTextButton
import at.techgaisi.farbwerk.ui.actions.Handles
import at.techgaisi.farbwerk.ui.actions.Segmented
import at.techgaisi.farbwerk.ui.actions.SliderRow
import at.techgaisi.farbwerk.ui.actions.SwitchRow
import at.techgaisi.farbwerk.ui.actions.consumeTouches
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Bearbeitungssitzung einer Textebene: Rahmen auf der Leinwand (ziehen = verschieben, blaue Punkte links/rechts
 * = Rahmenbreite → Umbruch, Tippen außerhalb = fertig), Text-Begleitleiste über der Tastatur und Stil-Panel.
 */
class TextEditSession(private val state: EditorState, val layer: Layer, val isNew: Boolean) : ToolInputHandler {
    val data: TextData = layer.text as TextData
    private val before = data.copy()
    private val beforeName = layer.name
    var tick by mutableIntStateOf(0)
        private set
    var showStyle by mutableStateOf(false)

    private var grab = 0 // 1 = verschieben, 2 = linke Kante, 3 = rechte Kante
    private var lastFx = 0f; private var lastFy = 0f
    private var downX = 0f; private var downY = 0f
    private var moved = false
    private val inv = Matrix()
    private val pts = FloatArray(2)
    private var laid: TextRenderer.Laid = TextRenderer.layout(data)

    fun changed() {
        laid = TextRenderer.layout(data)
        TextLayers.rerender(state, layer)
        tick++
    }

    fun start() {
        state.openPanel = Panel.NONE
        state.canvasOverride = this
        TextLayers.rerender(state, layer)
    }

    fun done() {
        if (state.canvasOverride === this) state.canvasOverride = null
        TextEditing.session = null
        if (data.text.isBlank()) {
            // Leerer Text: neue Ebene wieder entfernen, sonst alten Text behalten
            if (isNew) { state.undo.undo(); return }
            data.text = before.text
            TextLayers.rerender(state, layer)
        }
        val changed = data.toJson().toString() != before.toJson().toString()
        if (changed) TextLayers.commit(state, layer, before, beforeName)
        state.invalidate()
    }

    fun cancel() {
        if (state.canvasOverride === this) state.canvasOverride = null
        TextEditing.session = null
        if (isNew) { state.undo.undo(); return }
        layer.text = before.copy(); layer.name = beforeName
        TextLayers.rerender(state, layer)
    }

    // ------------------------------------------------------------------ Leinwand

    private fun toFrame(x: Float, y: Float): Pair<Float, Float> {
        data.matrix().invert(inv)
        pts[0] = x; pts[1] = y; inv.mapPoints(pts)
        return pts[0] to pts[1]
    }

    private fun viewScale(): Float {
        val m = state.canvasView?.docToViewMatrix() ?: return 1f
        val dm = data.matrix(); dm.postConcat(m)
        pts[0] = 1f; pts[1] = 0f; dm.mapVectors(pts); return hypot(pts[0], pts[1]).coerceAtLeast(1e-4f)
    }

    override fun onStrokeStart(p: InputPoint) {
        val (fx, fy) = toFrame(p.x, p.y)
        val r = TextRenderer.frame(data, laid)
        val tol = Handles.hitRadius / viewScale()
        grab = when {
            !data.vertical && abs(fx - r.left) < tol && fy > r.top - tol && fy < r.bottom + tol -> 2
            !data.vertical && abs(fx - r.right) < tol && fy > r.top - tol && fy < r.bottom + tol -> 3
            fx > r.left - tol && fx < r.right + tol && fy > r.top - tol && fy < r.bottom + tol -> 1
            else -> 0
        }
        lastFx = fx; lastFy = fy; downX = p.x; downY = p.y; moved = false
    }

    override fun onStrokeMove(points: List<InputPoint>) {
        val p = points.lastOrNull() ?: return
        if (!moved && hypot(p.x - downX, p.y - downY) * viewScale() < 6f * Handles.density) return
        moved = true
        val (fx, fy) = toFrame(p.x, p.y)
        val dx = fx - lastFx; val dy = fy - lastFy
        lastFx = fx; lastFy = fy
        val minW = data.size
        when (grab) {
            1 -> { data.x += dx; data.y += dy }
            2 -> {
                val w = (if (data.boxWidth > 0) data.boxWidth else laid.width) - dx
                if (w >= minW) { data.boxWidth = w; data.x += dx }
            }
            3 -> {
                val w = (if (data.boxWidth > 0) data.boxWidth else laid.width) + dx
                data.boxWidth = w.coerceAtLeast(minW)
            }
            else -> return
        }
        changed()
    }

    override fun onStrokeEnd(p: InputPoint) {
        if (!moved && grab == 0) done()
        grab = 0
    }

    override fun onStrokeCancel() { grab = 0 }

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Handles.BLUE
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }
    private val path = Path()
    private val corners = FloatArray(8)

    override fun drawOverlay(canvas: Canvas, docToView: Matrix) {
        framePaint.strokeWidth = 1.5f * Handles.density
        TextRenderer.frameCorners(data, laid, corners)
        docToView.mapPoints(corners)
        path.rewind()
        path.moveTo(corners[0], corners[1]); path.lineTo(corners[2], corners[3])
        path.lineTo(corners[4], corners[5]); path.lineTo(corners[6], corners[7]); path.close()
        canvas.drawPath(path, framePaint)
        if (!data.vertical) {
            Handles.draw(canvas, (corners[0] + corners[6]) / 2, (corners[1] + corners[7]) / 2, Handles.BLUE)
            Handles.draw(canvas, (corners[2] + corners[4]) / 2, (corners[3] + corners[5]) / 2, Handles.BLUE)
        }
    }
}

/** Laufende Text-Sitzung (höchstens eine). */
object TextEditing {
    var session by mutableStateOf<TextEditSession?>(null)

    /** Verbindet [TextLayers.beginEdit] mit dieser Oberfläche (idempotent). */
    fun ensureRegistered() {
        if (TextLayers.editorHook != null) return
        TextLayers.editorHook = { state, layer, isNew ->
            session?.done()
            val s = TextEditSession(state, layer, isNew)
            session = s
            s.start()
        }
    }
}

// =====================================================================================
// Oberfläche
// =====================================================================================

@Composable
fun TextEditorOverlay(state: EditorState) {
    TextEditing.ensureRegistered()
    val s = TextEditing.session ?: return
    // Dokument gewechselt → Sitzung verwerfen
    if (state.document?.layers?.any { it === s.layer } != true) {
        LaunchedEffect(s) { if (state.canvasOverride === s) state.canvasOverride = null; TextEditing.session = null }
        return
    }
    val ui = LocalUi.current
    @Suppress("UNUSED_VARIABLE") val t = s.tick
    Box(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(10.dp).widthIn(max = 760.dp).fillMaxWidth()
        ) {
            if (s.showStyle) {
                StylePanel(state, s)
                Spacer(Modifier.height(8.dp))
            }
            CompanionBar(s)
        }
    }
}

/** Text-Begleitleiste: Eingabefeld, Ausrichtung, Schriftname, „Aa“, Fertig. */
@Composable
private fun CompanionBar(s: TextEditSession) {
    val ui = LocalUi.current
    val d = s.data
    var value by remember(s) { mutableStateOf(TextFieldValue(d.text, TextRange(0, d.text.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(s) { try { focus.requestFocus() } catch (_: Throwable) {} }
    Row(
        Modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
            .background(ui.panel).consumeTouches().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = { v -> value = v; if (v.text != d.text) { d.text = v.text; s.changed() } },
            textStyle = TextStyle(color = ui.text, fontSize = 16.sp),
            cursorBrush = SolidColor(ui.accent),
            maxLines = 4,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(ui.panelElevated)
                .padding(horizontal = 10.dp, vertical = 8.dp).focusRequester(focus),
        )
        Spacer(Modifier.width(6.dp))
        val next = TextAlign.entries[(d.align.ordinal + 1) % TextAlign.entries.size]
        BarTextButton(alignGlyph(d.align), ui.text, { d.align = next; s.changed() })
        Text(
            FontRegistry.label(d.fontKey), color = ui.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 130.dp).clip(RoundedCornerShape(8.dp)).clickable { s.showStyle = !s.showStyle }
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )
        Text(
            "Aa", color = if (s.showStyle) ui.accent else ui.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { s.showStyle = !s.showStyle }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
        BarTextButton("Fertig", ui.accent, { s.done() }, bold = true)
    }
}

private fun alignGlyph(a: TextAlign) = when (a) {
    TextAlign.LEFT -> "≡ Links"; TextAlign.CENTER -> "≡ Mitte"; TextAlign.RIGHT -> "≡ Rechts"; TextAlign.JUSTIFY -> "≡ Block"
}

/** Stil bearbeiten: Schriftenliste mit Vorschau, Schnitt, Design-Regler und Attribute. */
@Composable
private fun StylePanel(state: EditorState, s: TextEditSession) {
    val ui = LocalUi.current
    val d = s.data
    val ctx = state.context
    val scope = rememberCoroutineScope()
    @Suppress("UNUSED_VARIABLE") val fv = FontRegistry.version
    val fonts = remember(FontRegistry.version) { FontRegistry.all(ctx) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val key = withContext(Dispatchers.IO) { try { FontRegistry.import(ctx, uri) } catch (_: Throwable) { null } }
            if (key == null) state.flash("Schrift konnte nicht gelesen werden")
            else { d.fontKey = key; s.changed(); state.flash("Schrift importiert") }
        }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(max = 380.dp).shadow(16.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
            .background(ui.panel).border(0.5.dp, ui.divider, RoundedCornerShape(14.dp)).consumeTouches()
    ) {
        // Schriften
        Column(Modifier.weight(0.42f).fillMaxHeight()) {
            Text("Schriften", color = ui.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 6.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(fonts, key = { it.key }) { f ->
                    val sel = f.key == d.fontKey
                    val tf = remember(f.key) { FontFamily(FontRegistry.typeface(f.key)) }
                    Row(
                        Modifier.fillMaxWidth().background(if (sel) ui.selectedRow else Color.Transparent)
                            .clickable { d.fontKey = f.key; s.changed() }.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(f.label, color = if (sel) Color.White else ui.text, fontSize = 16.sp, fontFamily = tf,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (f.imported) Text("✕", color = if (sel) Color.White else ui.textMuted, fontSize = 13.sp,
                            modifier = Modifier.clickable {
                                if (d.fontKey == f.key) { d.fontKey = FontRegistry.DEFAULT_KEY; s.changed() }
                                FontRegistry.delete(f.key)
                            }.padding(4.dp))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(ui.divider))
            BarTextButton("Schrift importieren …", ui.accent, {
                importer.launch(arrayOf("font/ttf", "font/otf", "font/collection", "application/x-font-ttf",
                    "application/x-font-otf", "application/vnd.ms-opentype", "application/octet-stream"))
            })
        }
        Box(Modifier.width(0.5.dp).fillMaxHeight().background(ui.divider))
        // Stil + Regler + Attribute
        Column(Modifier.weight(0.58f).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                Segmented(listOf(0 to "Normal", 1 to "Kursiv", 2 to "Fett", 3 to "Fett kursiv"),
                    (if (d.bold) 2 else 0) + (if (d.italic) 1 else 0), Modifier.fillMaxWidth()) {
                    d.bold = it >= 2; d.italic = it % 2 == 1; s.changed()
                }
            }
            SliderRow("Größe", d.size, 8f..1200f, { "${it.roundToInt()} px" }, onChange = { d.size = it; s.changed() })
            SliderRow("Laufweite", d.tracking, -200f..1000f, { "${it.roundToInt()}" }, onChange = { d.tracking = it; s.changed() })
            SliderRow("Zeilenabstand", d.lineSpacing, 0.5f..3f, { String.format(java.util.Locale.GERMANY, "%.2f", it) },
                onChange = { d.lineSpacing = it; s.changed() })
            SliderRow("Grundlinie", d.baseline, -d.size..d.size, { "${it.roundToInt()} px" }, onChange = { d.baseline = it; s.changed() })
            SliderRow("Deckkraft", d.opacity, 0f..1f, { "${(it * 100).roundToInt()} %" }, onChange = { d.opacity = it; s.changed() })
            Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                Segmented(TextAlign.entries.map { it to it.label }, d.align, Modifier.fillMaxWidth()) { d.align = it; s.changed() }
            }
            SwitchRow("Unterschneidung", d.kerning) { d.kerning = it; s.changed() }
            SwitchRow("Unterstrichen", d.underline) { d.underline = it; s.changed() }
            SwitchRow("Kontur", d.outline) { d.outline = it; s.changed() }
            SwitchRow("Vertikal", d.vertical) { d.vertical = it; s.changed() }
            SwitchRow("Großbuchstaben", d.uppercase) { d.uppercase = it; s.changed() }
            Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(Color(d.color)).border(1.dp, ui.divider, RoundedCornerShape(11.dp)))
                Spacer(Modifier.width(8.dp))
                BarTextButton("Aktuelle Farbe übernehmen", ui.accent, { d.color = state.color or (0xFF shl 24); s.changed() })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.End) {
                BarTextButton("Abbrechen", ui.textMuted, { s.cancel() })
                BarTextButton("Fertig", ui.accent, { s.showStyle = false }, bold = true)
            }
        }
    }
}
