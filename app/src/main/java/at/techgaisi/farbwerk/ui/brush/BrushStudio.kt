package at.techgaisi.farbwerk.ui.brush

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.brush.BP
import at.techgaisi.farbwerk.brush.Brush
import at.techgaisi.farbwerk.brush.BrushGroup
import at.techgaisi.farbwerk.brush.BrushImages
import at.techgaisi.farbwerk.brush.BrushPad
import at.techgaisi.farbwerk.brush.BrushTextures
import at.techgaisi.farbwerk.brush.GrainKind
import at.techgaisi.farbwerk.brush.ParamKind
import at.techgaisi.farbwerk.brush.ShapeKind
import at.techgaisi.farbwerk.ui.theme.LocalUi
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private fun groupIcon(g: BrushGroup): ImageVector = when (g) {
    BrushGroup.STROKE -> Icons.Outlined.Gesture
    BrushGroup.STABILIZATION -> Icons.Outlined.Straighten
    BrushGroup.TAPER -> Icons.Outlined.Edit
    BrushGroup.SHAPE -> Icons.Outlined.Category
    BrushGroup.GRAIN -> Icons.Outlined.Grain
    BrushGroup.RENDERING -> Icons.Outlined.Layers
    BrushGroup.WET -> Icons.Outlined.WaterDrop
    BrushGroup.COLOR -> Icons.Outlined.Palette
    BrushGroup.DYNAMICS -> Icons.Outlined.Speed
    BrushGroup.PEN -> Icons.Outlined.ShowChart
    BrushGroup.PROPERTIES -> Icons.Outlined.Tune
    BrushGroup.PREVIEW -> Icons.Outlined.Visibility
    BrushGroup.INFO -> Icons.Outlined.Info
}

/** Vorschaufarben der Zeichenfläche (Zeichenflächen-Einstellungen). */
private val PAD_COLORS = listOf(
    0xFF1A1A1A, 0xFFFFFFFF, 0xFF2F7CF6, 0xFFE5484D, 0xFF30A46C, 0xFFF5A524, 0xFF8E4EC6, 0xFF8D6E63,
).map { it.toInt() }

/**
 * Pinsel-Studio (Vollbild): links Attribut-Gruppen, Mitte Einstellungen, rechts Zeichenfläche zum Testen.
 * "Fertig" speichert (eingebaute Pinsel → automatische eigene Kopie), "Abbrechen" verwirft.
 */
@Composable
fun BrushStudio(state: EditorState, source: Brush, isNew: Boolean, onClose: () -> Unit) {
    val ui = LocalUi.current
    val lib = state.brushLibrary
    val work = remember(source.id) { source.workingCopy() }
    var rev by remember { mutableIntStateOf(0) }
    var group by remember { mutableStateOf(BrushGroup.STROKE) }

    fun done() {
        val changed = isNew || !work.sameParams(source)
        if (changed) {
            val saved = lib.save(work)
            val ts = state.currentToolSettings
            ts.brushId = saved.id
            state.toolSettingsVersion++
            if (source.builtIn && saved.id != source.id) state.flash("Eigene Kopie „${saved.name}“ erstellt")
            else state.flash("Pinsel gespeichert")
        }
        onClose()
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(Modifier.fillMaxSize().background(Color(0xFF1E1E20)).systemBarsPadding()) {
            // ---- Kopfzeile
            Row(
                Modifier.fillMaxWidth().height(50.dp).background(Color(0xE6141416)).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderButton("Abbrechen", ui.text) { onClose() }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Pinsel-Studio", color = ui.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    @Suppress("UNUSED_VARIABLE") val r = rev
                    Text(work.name + if (source.builtIn) " · mitgeliefert" else "", color = ui.textMuted, fontSize = 12.sp, maxLines = 1)
                }
                HeaderButton("Fertig", ui.accent, bold = true) { done() }
            }

            Row(Modifier.fillMaxSize()) {
                // ---- Attribute
                Column(
                    Modifier.width(210.dp).fillMaxHeight().background(Color(0xFF242427))
                        .verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                ) {
                    for (g in BrushGroup.entries) {
                        val sel = g == group
                        val bg by animateColorAsState(if (sel) ui.selectedRow else Color.Transparent, tween(120), label = "grp")
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(RoundedCornerShape(10.dp))
                                .background(bg).clickable { group = g }.padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(groupIcon(g), null, tint = if (sel) Color.White else ui.textMuted, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(g.label, color = if (sel) Color.White else ui.text, fontSize = 14.sp,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }

                // ---- Einstellungen
                AnimatedContent(
                    group, transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(80)) },
                    modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFF2A2A2E)), label = "group",
                ) { g ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp)) {
                        @Suppress("UNUSED_VARIABLE") val r = rev
                        Text(g.label, color = ui.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        GroupContent(state, g, work, source, isNew) { rev++ }
                        Spacer(Modifier.height(40.dp))
                    }
                }

                // ---- Zeichenfläche
                TestPad(state, work, source, rev, { rev++ }, Modifier.weight(1.1f).fillMaxHeight().padding(12.dp))
            }
        }
    }
}

@Composable
private fun HeaderButton(label: String, color: Color, bold: Boolean = false, onClick: () -> Unit) {
    Text(
        label, color = color, fontSize = 16.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** Inhalt einer Gruppe: Parameter mit Abschnitten und Sonder-Widgets. */
@Composable
private fun GroupContent(state: EditorState, g: BrushGroup, work: Brush, source: Brush, isNew: Boolean, onChange: () -> Unit) {
    if (g == BrushGroup.INFO) { InfoSection(state, work, source, isNew, onChange); return }
    val params = BP.entries.filter { it.group == g }
    var i = 0
    while (i < params.size) {
        val p = params[i]
        p.section?.let { SectionHeader(it) }
        // Sonder-Widgets am Abschnittsanfang
        when (p) {
            BP.PRESSURE_SIZE -> { PressureCurveEditor(work, onChange); Spacer(Modifier.height(8.dp)) }
            BP.ROUNDNESS -> { RoundnessDiagram(work, onChange); Spacer(Modifier.height(8.dp)) }
            else -> {}
        }
        when (p) {
            BP.TAPER_START, BP.TOUCH_TAPER_START -> {
                val end = if (p == BP.TAPER_START) BP.TAPER_END else BP.TOUCH_TAPER_END
                val link = if (p == BP.TAPER_START) BP.TAPER_LINK else BP.TOUCH_TAPER_LINK
                TaperRange(work[p], work[end], work.on(link)) { s, e -> work[p] = s; work[end] = e; onChange() }
                i += 2 // Ende gehört zum Doppelregler
                continue
            }
            BP.SHAPE_KIND -> SourceCard(state, work, grain = false, onChange)
            BP.GRAIN_KIND -> SourceCard(state, work, grain = true, onChange)
            else -> ParamControl(work, p, onChange)
        }
        i++
    }
}

@Composable
private fun ParamControl(work: Brush, p: BP, onChange: () -> Unit) {
    when (p.kind) {
        ParamKind.TOGGLE -> ToggleRow(p.label, work.on(p)) {
            work[p] = if (it) 1f else 0f
            // Verknüpfen: Ende = Anfang
            if (it && p == BP.TAPER_LINK) work[BP.TAPER_END] = work[BP.TAPER_START]
            if (it && p == BP.TOUCH_TAPER_LINK) work[BP.TOUCH_TAPER_END] = work[BP.TOUCH_TAPER_START]
            onChange()
        }
        ParamKind.CHOICE -> ChoiceRow(p.label, p.options ?: emptyArray(), work.int(p)) { work[p] = it.toFloat(); onChange() }
        else -> ParamSlider(work, p, onChange)
    }
}

/** Form-/Körnungsquelle: Vorschau + „Bearbeiten“ öffnet den Editor. */
@Composable
private fun SourceCard(state: EditorState, work: Brush, grain: Boolean, onChange: () -> Unit) {
    val ui = LocalUi.current
    var editor by remember { mutableStateOf(false) }
    val img = if (grain) work.grainImage else work.shapeImage
    val idx = work.int(if (grain) BP.GRAIN_KIND else BP.SHAPE_KIND)
    val thumb = remember(img, idx) {
        (img?.let { BrushImages.thumbnail(it) } ?: if (grain && idx == 0) null else BrushTextures.thumbnail(grain, idx))?.asImageBitmap()
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF34343A)).clickable { editor = true }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            if (thumb != null) Image(thumb, null, Modifier.size(68.dp), colorFilter = ColorFilter.tint(Color.White))
            else Text("–", color = ui.textMuted, fontSize = 20.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (grain) "Körnungsquelle" else "Formquelle", color = ui.text, fontSize = 15.sp)
            Text(
                when {
                    img != null -> "Eigenes Bild"
                    grain -> GrainKind.entries[idx].label
                    else -> ShapeKind.entries[idx].label
                }, color = ui.textMuted, fontSize = 13.sp,
            )
        }
        Text("Bearbeiten", color = ui.accent, fontSize = 14.sp)
    }
    if (editor) SourceEditor(state, work, grain, onDismiss = { editor = false }, onChange = onChange)
}

/**
 * Form-/Körnungs-Editor: Bild importieren (Foto, Datei, Einfügen), Quellbibliothek, Invertieren (auch
 * Zwei-Finger-Tippen auf die Vorschau) und für Körnungen „Automatisch wiederholen“ (kachelbar machen).
 */
@Composable
private fun SourceEditor(state: EditorState, work: Brush, grain: Boolean, onDismiss: () -> Unit, onChange: () -> Unit) {
    val ui = LocalUi.current
    val context = LocalContext.current
    // Arbeitsstand: eigenes Bild oder Index der Quellbibliothek
    var image by remember { mutableStateOf(if (grain) work.grainImage else work.shapeImage) }
    var original by remember { mutableStateOf(image) }
    var index by remember { mutableIntStateOf(work.int(if (grain) BP.GRAIN_KIND else BP.SHAPE_KIND)) }
    var repScale by remember { mutableFloatStateOf(1f) }
    var repRotate by remember { mutableIntStateOf(0) }
    var repOverlap by remember { mutableFloatStateOf(0.15f) }
    var repHardness by remember { mutableFloatStateOf(0.3f) }
    var repMirror by remember { mutableStateOf(false) }

    fun load(uri: Uri?) {
        if (uri == null) return
        try {
            val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = info.size
                val m = max(s.width, s.height)
                if (m > 1024) d.setTargetSize(s.width * 1024 / m, s.height * 1024 / m)
            }
            val img = BrushImages.importImage(src.copy(Bitmap.Config.ARGB_8888, false), grain)
            image = img; original = img
        } catch (t: Throwable) {
            state.flash("Bild konnte nicht geladen werden")
        }
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { load(it) }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { load(it) }

    fun current(): Bitmap? = image ?: if (grain) BrushTextures.grain(index, 1f, false) else BrushTextures.shape(index)
    fun invert() { current()?.let { image = BrushImages.invert(it); original = image } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(560.dp).clip(RoundedCornerShape(14.dp)).background(ui.panel).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Abbrechen", color = ui.text, fontSize = 15.sp, modifier = Modifier.clickable(onClick = onDismiss).padding(6.dp))
                Text(if (grain) "Körnungs-Editor" else "Form-Editor", color = ui.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("Fertig", color = ui.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable {
                        if (grain) { work.grainImage = image; work[BP.GRAIN_KIND] = index.toFloat() }
                        else { work.shapeImage = image; work[BP.SHAPE_KIND] = index.toFloat() }
                        onChange(); onDismiss()
                    }.padding(6.dp))
            }
            Spacer(Modifier.height(10.dp))
            Row {
                // Vorschau (Zwei-Finger-Tippen = invertieren)
                val bmp = current()
                val shown = remember(bmp) { bmp?.let { BrushImages.grayscale(it).asImageBitmap() } }
                Box(
                    Modifier.size(240.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown()
                                var two = false
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    if (ev.changes.count { it.pressed } >= 2) two = true
                                    if (ev.changes.none { it.pressed }) break
                                }
                                if (two) invert()
                            }
                        },
                ) {
                    if (grain && shown != null) {
                        // Körnung gekachelt (2×2) zur Kontrolle der Nahtstellen
                        Canvas(Modifier.fillMaxSize()) {
                            val s = size.width / 2f
                            for (y in 0..1) for (x in 0..1) drawImage(shown, dstOffset = androidx.compose.ui.unit.IntOffset((x * s).toInt(), (y * s).toInt()),
                                dstSize = IntSize(s.toInt() + 1, s.toInt() + 1))
                        }
                    } else if (shown != null) Image(shown, null, Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditorButton("Foto importieren") { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    EditorButton("Datei importieren") { file.launch("image/*") }
                    EditorButton("Einfügen") {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val uri = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                        if (uri != null) load(uri) else state.flash("Kein Bild in der Zwischenablage")
                    }
                    EditorButton("Invertieren") { invert() }
                    if (image != null) EditorButton("Eigenes Bild entfernen") { image = null; original = null }
                    Text("Zwei-Finger-Tippen auf die Vorschau invertiert.", color = ui.textMuted, fontSize = 12.sp)
                }
            }
            SectionHeader("Quellbibliothek")
            val count = if (grain) GrainKind.entries.size else ShapeKind.entries.size
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items((0 until count).toList()) { i ->
                    val sel = image == null && index == i
                    Column(Modifier.width(64.dp).clickable { index = i; image = null; original = null },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black)
                                .border(if (sel) 2.dp else 0.5.dp, if (sel) ui.accent else ui.divider, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!(grain && i == 0)) {
                                val t = remember(grain, i) { BrushTextures.thumbnail(grain, i).asImageBitmap() }
                                Image(t, null, Modifier.size(50.dp), colorFilter = ColorFilter.tint(Color.White))
                            } else Text("–", color = ui.textMuted)
                        }
                        Text(if (grain) GrainKind.entries[i].label else ShapeKind.entries[i].label,
                            color = if (sel) ui.accent else ui.textMuted, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
            if (grain) {
                SectionHeader("Automatisch wiederholen")
                LabeledSlider("Körnungsmaßstab", repScale, 0.25f..4f, "%.2f×".format(repScale)) { repScale = it }
                LabeledSlider("Randüberlappung", repOverlap, 0f..0.5f, "${(repOverlap * 100).roundToInt()} %") { repOverlap = it }
                LabeledSlider("Maskenhärte", repHardness, 0f..1f, "${(repHardness * 100).roundToInt()} %") { repHardness = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChoiceRow("Drehen", arrayOf("0°", "90°", "180°", "270°"), repRotate) { repRotate = it }
                }
                ToggleRow("Spiegelüberlappung", repMirror) { repMirror = it }
                EditorButton("Anwenden") {
                    val base = original ?: BrushTextures.grain(index, 1f, false)
                    if (base != null) image = BrushImages.autoRepeat(base, repScale, repRotate, repOverlap, repHardness, repMirror)
                }
            }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, v: Float, range: ClosedFloatingPointRange<Float>, text: String, onChange: (Float) -> Unit) {
    val ui = LocalUi.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = ui.text, fontSize = 14.sp, modifier = Modifier.width(140.dp))
        Box(Modifier.weight(1f)) { StudioSlider(v, range, onChange = onChange) }
        Text(text, color = ui.textMuted, fontSize = 12.sp, modifier = Modifier.width(56.dp).padding(start = 8.dp))
    }
}

@Composable
private fun EditorButton(label: String, onClick: () -> Unit) {
    val ui = LocalUi.current
    Text(label, color = ui.text, fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ui.panelElevated).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp))
}

/** Gruppe „Über diesen Pinsel“: Name, Autor, Datum, Unterschrift, Rücksetzpunkt, Zurücksetzen. */
@Composable
private fun InfoSection(state: EditorState, work: Brush, source: Brush, isNew: Boolean, onChange: () -> Unit) {
    val ui = LocalUi.current
    val lib = state.brushLibrary
    var name by remember(work.id) { mutableStateOf(work.name) }
    var author by remember(work.id) { mutableStateOf(work.author) }
    var confirmReset by remember { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE") val lv = lib.version

    SectionHeader("Titel")
    if (isNew) {
        InfoField(name, "Name") { name = it.take(60); work.name = name; onChange() }
    } else {
        Text(work.name, color = ui.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Text("Umbenennen in der Bibliothek (Pinsel halten → Umbenennen).", color = ui.textMuted, fontSize = 12.sp)
    }
    SectionHeader("Erstellt von")
    InfoField(author, "Name des Autors") { author = it.take(60); work.author = author; onChange() }
    Spacer(Modifier.height(8.dp))
    InfoLine("Set", lib.setName(lib.setOf(work.id).takeIf { !isNew } ?: work.setId))
    InfoLine("Art", if (source.builtIn) "Mitgelieferter Pinsel" else if (isNew) "Neuer eigener Pinsel" else "Eigener Pinsel")
    if (work.created > 0) InfoLine("Erstellt", DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(work.created)))
    lib.original(work)?.takeIf { it.id != work.id }?.let { InfoLine("Vorlage", lib.factoryName(it.id) ?: it.name) }
    SectionHeader("Unterschrift")
    SignaturePad(work, onChange)
    if (source.builtIn) {
        Spacer(Modifier.height(10.dp))
        Text("Mitgelieferte Pinsel bleiben unverändert. Änderungen legen beim Tippen auf „Fertig“ automatisch eine eigene Kopie an.",
            color = ui.textMuted, fontSize = 12.sp)
    }
    SectionHeader("Zurücksetzen")
    InfoButton("Neuen Rücksetzpunkt erstellen", ui.accent) {
        work.resetPoint = work.snapshot(); work.resetPointTime = System.currentTimeMillis(); onChange()
        state.flash("Rücksetzpunkt erstellt")
    }
    if (work.resetPoint != null) {
        Text("Rücksetzpunkt vom " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(work.resetPointTime)),
            color = ui.textMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
    Spacer(Modifier.height(8.dp))
    InfoButton("Pinsel zurücksetzen", BrushUiColors.destructive) { confirmReset = true }
    if (!isNew) {
        Spacer(Modifier.height(8.dp))
        val fav = lib.isFavorite(source.id)
        InfoButton(if (fav) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen", ui.text) { lib.toggleFavorite(source.id) }
    }
    if (confirmReset) {
        ConfirmDialog(
            "Pinsel zurücksetzen?",
            if (work.resetPoint != null) "Alle Einstellungen werden auf den Rücksetzpunkt zurückgesetzt."
            else "Alle Einstellungen werden auf den Originalzustand zurückgesetzt.",
            confirmLabel = "Zurücksetzen",
            onDismiss = { confirmReset = false },
        ) {
            val rp = work.resetPoint
            if (rp != null) work.restoreSnapshot(rp)
            else {
                val orig = lib.original(work) ?: if (!isNew && !source.builtIn) source else null
                if (orig != null) work.copySettingsFrom(orig) else Brush("_", "", "", false).let { work.copySettingsFrom(it) }
            }
            onChange(); confirmReset = false
            state.flash("Einstellungen zurückgesetzt")
        }
    }
}

@Composable
private fun InfoField(value: String, hint: String, onChange: (String) -> Unit) {
    val ui = LocalUi.current
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(ui.panelElevated)
        .border(0.5.dp, ui.divider, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (value.isEmpty()) Text(hint, color = ui.textMuted, fontSize = 15.sp)
        BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = ui.text, fontSize = 15.sp),
            cursorBrush = SolidColor(ui.accent), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val ui = LocalUi.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = ui.textMuted, fontSize = 13.sp, modifier = Modifier.width(90.dp))
        Text(value, color = ui.text, fontSize = 13.sp)
    }
}

@Composable
private fun InfoButton(label: String, color: Color, onClick: () -> Unit) {
    val ui = LocalUi.current
    Text(
        label, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ui.panelElevated)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

/**
 * Zeichenfläche: echte Pinsel-Pipeline auf einer eigenen Bitmap. Solange nicht selbst gezeichnet wurde,
 * zeigt sie nach jeder Änderung einen Probestrich. Symbol oben links: Zeichenflächen-Einstellungen
 * (leeren, alle Einstellungen zurücksetzen, Vorschaugröße, 8 Vorschaufarben). Drei-Finger-Schrubben leert.
 */
@Composable
private fun TestPad(state: EditorState, work: Brush, source: Brush, rev: Int, onChange: () -> Unit, modifier: Modifier) {
    val ui = LocalUi.current
    var pad by remember { mutableStateOf<BrushPad?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var userDrew by remember { mutableStateOf(false) }
    var sampleKey by remember { mutableIntStateOf(0) }
    var testSize by remember { mutableFloatStateOf(state.currentToolSettings.size.coerceAtLeast(0.15f)) }
    var testOpacity by remember { mutableFloatStateOf(state.currentToolSettings.opacity) }
    var color by remember { mutableIntStateOf(state.color) }
    var menu by remember { mutableStateOf(false) }
    var confirmResetAll by remember { mutableStateOf(false) }
    val light = android.graphics.Color.luminance(color) > 0.6f
    val bg = if (light) Color(0xFF3A3A40) else Color(0xFFF7F6F2)

    fun diameter(): Float {
        val minS = work[BP.MIN_SIZE]; val maxS = max(work[BP.MAX_SIZE], minS)
        return minS + (maxS - minS) * testSize * testSize
    }

    DisposableEffect(Unit) { onDispose { pad?.release() } }

    LaunchedEffect(rev, testSize, testOpacity, pad, sampleKey, color) {
        val p = pad ?: return@LaunchedEffect
        if (!userDrew) {
            p.clear()
            p.secondaryColor = state.secondaryColor
            p.sampleStroke(work, diameter(), color, testOpacity)
            tick++
        }
    }

    Column(modifier) {
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg)
                .onSizeChanged { sz: IntSize ->
                    if (sz.width > 0 && sz.height > 0 && (pad?.width != sz.width || pad?.height != sz.height)) {
                        pad?.release()
                        pad = BrushPad(sz.width, sz.height)
                        userDrew = false
                    }
                }
                .pointerInput(pad) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val p = pad ?: return@awaitEachGesture
                        val stylus = down.type == PointerType.Stylus || down.type == PointerType.Eraser
                        fun pr(v: Float) = if (stylus) v.coerceIn(0.01f, 1f) else 1f
                        var started = false
                        var multi = false
                        // Drei-Finger-Schrubben
                        var lastX = 0f; var dir = 0; var reversals = 0; var travel = 0f
                        fun start() {
                            if (!userDrew) { p.clear(); userDrew = true }
                            p.secondaryColor = state.secondaryColor
                            p.begin(work, diameter(), testOpacity, color, down.position.x, down.position.y,
                                pr(down.pressure), down.uptimeMillis, stylus)
                            started = true
                        }
                        if (stylus) start()
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.size >= 2 && !stylus) {
                                if (!multi) { multi = true; if (started) { p.clear(); started = false } }
                                if (pressed.size >= 3) {
                                    val x = pressed.map { it.position.x }.average().toFloat()
                                    if (lastX != 0f) {
                                        val dx = x - lastX
                                        travel += abs(dx)
                                        val d = if (dx > 2f) 1 else if (dx < -2f) -1 else 0
                                        if (d != 0 && dir != 0 && d != dir) reversals++
                                        if (d != 0) dir = d
                                    }
                                    lastX = x
                                }
                                ev.changes.forEach { it.consume() }
                            }
                            val ch = ev.changes.firstOrNull { it.id == down.id }
                            if (!multi && ch != null) {
                                if (!started && (ch.position - down.position).getDistance() > 2f) start()
                                if (started) {
                                    for (h in ch.historical) p.move(h.position.x, h.position.y, pr(ch.pressure), h.uptimeMillis)
                                    p.move(ch.position.x, ch.position.y, pr(ch.pressure), ch.uptimeMillis)
                                }
                                ch.consume()
                                tick++
                            }
                            if (ev.changes.none { it.pressed }) break
                        }
                        if (multi) {
                            if (reversals >= 2 && travel > 80f) { p.clear(); userDrew = true; state.flash("Zeichenfläche geleert") }
                        } else {
                            if (!started) start()
                            p.end()
                        }
                        tick++
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                @Suppress("UNUSED_VARIABLE") val t = tick
                pad?.let { p -> drawIntoCanvas { c -> p.draw(c.nativeCanvas) } }
            }
            // Zeichenflächen-Einstellungen
            Box(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0x99202024)).clickable { menu = true },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Settings, "Zeichenflächen-Einstellungen", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = ui.panelElevated) {
                    Column(Modifier.width(280.dp).padding(horizontal = 4.dp)) {
                        MenuRow("Zeichenfläche leeren") { pad?.clear(); userDrew = true; tick++; menu = false }
                        MenuRow("Probestrich zeichnen") { userDrew = false; sampleKey++; menu = false }
                        MenuRow("Alle Einstellungen zurücksetzen", BrushUiColors.destructive) { menu = false; confirmResetAll = true }
                        Text("Vorschaugröße", color = ui.textMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 6.dp))
                        Box(Modifier.padding(horizontal = 12.dp)) { StudioSlider(testSize, 0f..1f) { testSize = it } }
                        Text("Deckkraft", color = ui.textMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp))
                        Box(Modifier.padding(horizontal = 12.dp)) { StudioSlider(testOpacity, 0f..1f) { testOpacity = it } }
                        Text("Vorschaufarbe", color = ui.textMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (c in PAD_COLORS + state.color) {
                                val sel = c == color
                                Box(Modifier.size(26.dp).clip(CircleShape).background(Color(c))
                                    .border(if (sel) 2.dp else 0.5.dp, if (sel) ui.accent else ui.divider, CircleShape)
                                    .clickable { color = c })
                            }
                        }
                    }
                }
            }
            if (!userDrew) {
                Text("Hier ausprobieren", color = if (light) Color(0x99FFFFFF) else Color(0x66000000), fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Größe", color = ui.textMuted, fontSize = 12.sp, modifier = Modifier.width(64.dp))
            Box(Modifier.weight(1f)) { StudioSlider(testSize, 0f..1f) { testSize = it } }
            Text("${(testSize * 100).roundToInt()} %", color = ui.textMuted, fontSize = 12.sp, modifier = Modifier.width(44.dp))
        }
    }
    if (confirmResetAll) {
        ConfirmDialog("Alle Einstellungen zurücksetzen?", "Der Pinsel wird auf den zuletzt gespeicherten Stand zurückgesetzt.",
            confirmLabel = "Zurücksetzen", onDismiss = { confirmResetAll = false }) {
            work.copySettingsFrom(source)
            work.pressureCurve = source.pressureCurve?.copyOf()
            onChange(); confirmResetAll = false
        }
    }
}
