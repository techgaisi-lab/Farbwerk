package at.techgaisi.farbwerk.ui.brush

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
import at.techgaisi.farbwerk.brush.BrushPad
import at.techgaisi.farbwerk.brush.BrushTextures
import at.techgaisi.farbwerk.brush.GrainKind
import at.techgaisi.farbwerk.brush.ParamKind
import at.techgaisi.farbwerk.brush.ShapeKind
import at.techgaisi.farbwerk.ui.theme.LocalUi
import java.text.DateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pinsel-Studio (Vollbild): links Attribut-Gruppen, Mitte Regler, rechts Zeichenfläche zum Testen.
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
        Column(Modifier.fillMaxSize().background(ui.panel).systemBarsPadding()) {
            // ---- Kopfzeile
            Row(
                Modifier.fillMaxWidth().height(52.dp).background(ui.bar).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderButton("Abbrechen", ui.text) { onClose() }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Pinsel-Studio", color = ui.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    @Suppress("UNUSED_VARIABLE") val r = rev
                    Text(work.name + if (source.builtIn) " · eingebaut" else "", color = ui.textMuted, fontSize = 12.sp, maxLines = 1)
                }
                HeaderButton("Fertig", ui.accent, bold = true) { done() }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(ui.divider))

            Row(Modifier.fillMaxSize()) {
                // ---- Gruppen
                Column(
                    Modifier.width(180.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                ) {
                    for (g in BrushGroup.entries) {
                        val sel = g == group
                        Text(
                            g.label, color = if (sel) Color.White else ui.text, fontSize = 14.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (sel) ui.selectedRow else Color.Transparent)
                                .clickable { group = g }
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                        )
                    }
                }
                Box(Modifier.width(0.5.dp).fillMaxHeight().background(ui.divider))

                // ---- Regler
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    @Suppress("UNUSED_VARIABLE") val r = rev
                    Text(group.label, color = ui.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    if (group == BrushGroup.INFO) {
                        InfoSection(state, work, source, isNew) { rev++ }
                    } else {
                        for (p in BP.entries.filter { it.group == group }) {
                            ParamControl(work, p) { rev++ }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
                Box(Modifier.width(0.5.dp).fillMaxHeight().background(ui.divider))

                // ---- Zeichenfläche
                TestPad(state, work, rev, Modifier.weight(1.15f).fillMaxHeight().padding(12.dp))
            }
        }
    }
}

@Composable
private fun HeaderButton(label: String, color: Color, bold: Boolean = false, onClick: () -> Unit) {
    Text(
        label, color = color, fontSize = 15.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** Ein Regler/Schalter/Auswahl für einen Parameter. */
@Composable
private fun ParamControl(work: Brush, p: BP, onChange: () -> Unit) {
    val ui = LocalUi.current
    val v = work[p]
    when (p.kind) {
        ParamKind.TOGGLE -> Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(p.label, color = ui.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = v >= 0.5f,
                onCheckedChange = { work[p] = if (it) 1f else 0f; onChange() },
                colors = SwitchDefaults.colors(checkedTrackColor = ui.accent),
            )
        }
        ParamKind.CHOICE -> Column(Modifier.fillMaxWidth()) {
            Text(p.label, color = ui.text, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                p.options?.forEachIndexed { i, label ->
                    val sel = v.roundToInt() == i
                    Text(
                        label, color = if (sel) Color.White else ui.text, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp))
                            .background(if (sel) ui.accent else ui.panelElevated)
                            .border(0.5.dp, ui.divider, RoundedCornerShape(16.dp))
                            .clickable { work[p] = i.toFloat(); onChange() }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        ParamKind.SHAPE, ParamKind.GRAIN -> {
            val grain = p.kind == ParamKind.GRAIN
            val count = if (grain) GrainKind.entries.size else ShapeKind.entries.size
            Column(Modifier.fillMaxWidth()) {
                Text("${p.label}: ${p.format(v)}", color = ui.text, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items((0 until count).toList()) { i ->
                        val sel = v.roundToInt() == i
                        Column(
                            Modifier.width(72.dp).clip(RoundedCornerShape(10.dp))
                                .clickable { work[p] = i.toFloat(); onChange() },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF16161A))
                                    .border(if (sel) 2.dp else 0.5.dp, if (sel) ui.accent else ui.divider, RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (!(grain && i == 0)) {
                                    val bmp = remember(grain, i) { BrushTextures.thumbnail(grain, i).asImageBitmap() }
                                    Image(bmp, null, Modifier.size(if (grain) 62.dp else 54.dp),
                                        colorFilter = ColorFilter.tint(Color.White))
                                } else Text("–", color = ui.textMuted, fontSize = 18.sp)
                            }
                            Text(
                                if (grain) GrainKind.entries[i].label else ShapeKind.entries[i].label,
                                color = if (sel) ui.accent else ui.textMuted, fontSize = 11.sp, maxLines = 1,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        else -> Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(p.label, color = ui.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(p.format(v), color = ui.textMuted, fontSize = 13.sp)
            }
            // Pixel-Werte quadratisch abbilden → feinere Kontrolle bei kleinen Größen
            val pixels = p.kind == ParamKind.PIXELS
            val pos = if (pixels) sqrt(((v - p.min) / (p.max - p.min)).coerceIn(0f, 1f)) else v
            Slider(
                value = pos,
                onValueChange = { x ->
                    work[p] = if (pixels) p.min + (p.max - p.min) * x * x else x
                    onChange()
                },
                valueRange = if (pixels) 0f..1f else p.min..p.max,
                steps = if (p.kind == ParamKind.COUNT) (p.max - p.min).toInt() - 1 else 0,
                colors = SliderDefaults.colors(thumbColor = ui.accent, activeTrackColor = ui.accent, inactiveTrackColor = ui.divider),
            )
        }
    }
}

/** Gruppe "Info": Name, Set, Favorit, Zurücksetzen. */
@Composable
private fun InfoSection(state: EditorState, work: Brush, source: Brush, isNew: Boolean, onChange: () -> Unit) {
    val ui = LocalUi.current
    val lib = state.brushLibrary
    var name by remember(work.id) { mutableStateOf(work.name) }
    @Suppress("UNUSED_VARIABLE") val lv = lib.version

    Text("Name", color = ui.textMuted, fontSize = 12.sp)
    Spacer(Modifier.height(4.dp))
    BasicTextField(
        value = name,
        onValueChange = { name = it.take(40); work.name = name; onChange() },
        singleLine = true,
        textStyle = TextStyle(color = ui.text, fontSize = 16.sp),
        cursorBrush = SolidColor(ui.accent),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(ui.panelElevated)
            .border(0.5.dp, ui.divider, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
    )
    Spacer(Modifier.height(14.dp))
    val setName = lib.sets().firstOrNull { it.id == work.setId }?.name ?: "Eigene Pinsel"
    InfoLine("Set", setName)
    InfoLine("Art", if (source.builtIn) "Mitgelieferter Pinsel" else if (isNew) "Neuer eigener Pinsel" else "Eigener Pinsel")
    if (!source.builtIn && !isNew && work.created > 0) {
        InfoLine("Erstellt", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(work.created)))
    }
    lib.original(work)?.takeIf { it.id != work.id }?.let { InfoLine("Vorlage", it.name) }
    if (source.builtIn) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Mitgelieferte Pinsel bleiben unverändert. Wenn du hier etwas änderst, wird beim Tippen auf „Fertig“ " +
                "automatisch eine eigene Kopie angelegt.",
            color = ui.textMuted, fontSize = 12.sp,
        )
    }
    Spacer(Modifier.height(16.dp))
    if (!isNew) {
        val fav = lib.isFavorite(source.id)
        InfoButton(if (fav) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen", ui.text) { lib.toggleFavorite(source.id) }
        Spacer(Modifier.height(8.dp))
    }
    val orig = lib.original(work) ?: if (!isNew && !source.builtIn) source else null
    InfoButton("Alle Einstellungen zurücksetzen", Color(0xFFE0A03A)) {
        val ref = orig ?: Brush("_", "", "", false)
        ref.v.copyInto(work.v)
        onChange()
        state.flash("Einstellungen zurückgesetzt")
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val ui = LocalUi.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
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
            .border(0.5.dp, ui.divider, RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

/**
 * Testfläche: echte Pinsel-Pipeline auf einer eigenen Bitmap. Solange nicht selbst gezeichnet wurde,
 * zeigt sie nach jeder Änderung einen Probestrich.
 */
@Composable
private fun TestPad(state: EditorState, work: Brush, rev: Int, modifier: Modifier) {
    val ui = LocalUi.current
    var pad by remember { mutableStateOf<BrushPad?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var userDrew by remember { mutableStateOf(false) }
    var sampleKey by remember { mutableIntStateOf(0) }
    var testSize by remember { mutableFloatStateOf(state.currentToolSettings.size.coerceAtLeast(0.15f)) }
    var testOpacity by remember { mutableFloatStateOf(state.currentToolSettings.opacity) }
    val color = state.color
    val dark = android.graphics.Color.luminance(color) > 0.75f
    val bg = if (dark) Color(0xFF3A3A40) else Color(0xFFF7F6F2)

    fun diameter(): Float {
        val minS = work[BP.MIN_SIZE]; val maxS = max(work[BP.MAX_SIZE], minS)
        return minS + (maxS - minS) * testSize * testSize
    }

    DisposableEffect(Unit) { onDispose { pad?.release() } }

    LaunchedEffect(rev, testSize, testOpacity, pad, sampleKey) {
        val p = pad ?: return@LaunchedEffect
        if (!userDrew) {
            p.clear()
            p.sampleStroke(work, diameter(), color, testOpacity)
            tick++
        }
    }

    Column(modifier) {
        Text("Zeichenfläche", color = ui.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg)
                .border(0.5.dp, ui.divider, RoundedCornerShape(12.dp))
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
                        if (!userDrew) { p.clear(); userDrew = true }
                        val stylus = down.type == PointerType.Stylus || down.type == PointerType.Eraser
                        fun pr(v: Float) = if (stylus) v.coerceIn(0.01f, 1f) else 1f
                        p.begin(work, diameter(), testOpacity, color, down.position.x, down.position.y,
                            pr(down.pressure), down.uptimeMillis, stylus)
                        down.consume()
                        tick++
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            for (h in ch.historical) p.move(h.position.x, h.position.y, pr(ch.pressure), h.uptimeMillis)
                            p.move(ch.position.x, ch.position.y, pr(ch.pressure), ch.uptimeMillis)
                            ch.consume()
                            tick++
                            if (!ch.pressed) break
                        }
                        p.end()
                        tick++
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                @Suppress("UNUSED_VARIABLE") val t = tick
                pad?.let { p -> drawIntoCanvas { c -> p.draw(c.nativeCanvas) } }
            }
            if (!userDrew) {
                Text("Hier ausprobieren", color = if (dark) Color(0x99FFFFFF) else Color(0x66000000), fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        PadSlider("Größe", testSize, "${(testSize * 100).roundToInt()} %") { testSize = it }
        PadSlider("Deckkraft", testOpacity, "${(testOpacity * 100).roundToInt()} %") { testOpacity = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { InfoButton("Leeren", ui.text) { pad?.clear(); userDrew = true; tick++ } }
            Box(Modifier.weight(1f)) { InfoButton("Probestrich", ui.accent) { userDrew = false; sampleKey++ } }
        }
    }
}

@Composable
private fun PadSlider(label: String, value: Float, text: String, onChange: (Float) -> Unit) {
    val ui = LocalUi.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = ui.text, fontSize = 13.sp, modifier = Modifier.width(72.dp))
        Slider(
            value = value, onValueChange = onChange, valueRange = 0f..1f, modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = ui.accent, activeTrackColor = ui.accent, inactiveTrackColor = ui.divider),
        )
        Text(text, color = ui.textMuted, fontSize = 12.sp, modifier = Modifier.width(44.dp))
    }
}
