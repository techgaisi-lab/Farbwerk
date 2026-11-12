package at.techgaisi.farbwerk.ui.actions

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.io.Background
import at.techgaisi.farbwerk.io.ExportFormat
import at.techgaisi.farbwerk.io.Exporters
import at.techgaisi.farbwerk.io.Importers
import at.techgaisi.farbwerk.model.GuideKind
import at.techgaisi.farbwerk.model.Panel
import at.techgaisi.farbwerk.model.Tool
import at.techgaisi.farbwerk.selection.ClipboardOps
import at.techgaisi.farbwerk.animation.AnimationController
import at.techgaisi.farbwerk.animation.AssistMode
import at.techgaisi.farbwerk.text.TextLayers
import at.techgaisi.farbwerk.ui.common.PanelCard
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.offset
import java.io.File

/** Reiter des Aktionen-Panels. */
enum class ActionTab(val label: String, val icon: ImageVector) {
    ADD("Hinzufügen", ActionIcons.Add),
    CANVAS("Leinwand", ActionIcons.Canvas),
    SHARE("Teilen", ActionIcons.Share),
    VIDEO("Video", ActionIcons.Video),
    SETTINGS("Einstellungen", ActionIcons.Settings),
    HELP("Hilfe", ActionIcons.Help),
}

/** Merkt sich den zuletzt geöffneten Reiter über das Schließen des Panels hinaus. */
internal object ActionsMemory {
    var tab by mutableStateOf(ActionTab.ADD)
}

/** Popover-Panel "Aktionen". [modifier] enthält bereits Größe/Position. */
@Composable
fun ActionsPanel(state: EditorState, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val rev = state.revision
    val ui = LocalUi.current
    val act = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { OverlayHost.install(act.findActivity(), state) }
    PanelCard(modifier, title = "Aktionen") {
        // Reiterleiste
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            ActionTab.entries.forEach { t ->
                val sel = ActionsMemory.tab == t
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (sel) ui.panelElevated else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { ActionsMemory.tab = t }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(t.icon, t.label, tint = if (sel) ui.accent else ui.icon, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(t.label, color = if (sel) ui.accent else ui.textMuted, fontSize = 9.5.sp, maxLines = 1,
                        textAlign = TextAlign.Center)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(ui.divider))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (ActionsMemory.tab) {
                ActionTab.ADD -> AddTab(state)
                ActionTab.CANVAS -> CanvasTab(state)
                ActionTab.SHARE -> ShareTab(state)
                ActionTab.VIDEO -> VideoTab()
                ActionTab.SETTINGS -> SettingsTab(state)
                ActionTab.HELP -> HelpTab()
            }
        }
    }
    BusyDialog()
}

private fun closePanel(state: EditorState) { state.openPanel = Panel.NONE }

private fun errorFlash(state: EditorState): (Throwable) -> Unit = { t ->
    state.flash("Fehler: ${t.message ?: t.javaClass.simpleName}")
}

// =====================================================================================
// Hinzufügen
// =====================================================================================

@Composable
private fun AddTab(state: EditorState) {
    val ctx = state.context
    var cameraFile by remember { mutableStateOf<File?>(null) }
    // „Privat einfügen“ (nach links wischen): gilt für den nächsten Import
    var privateInsert by remember { mutableStateOf(false) }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) { closePanel(state); Importers.insertFile(state, uri, privateInsert) }
    }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) insertPhoto(state, uri, privateInsert)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        if (ok && f != null) insertPhoto(state, Exporters.uriFor(ctx, f), privateInsert)
    }
    fun pickFile(priv: Boolean) {
        privateInsert = priv
        fileLauncher.launch(arrayOf("image/*", "image/vnd.adobe.photoshop", "application/x-photoshop",
            "application/photoshop", "application/octet-stream"))
    }
    fun pickPhoto(priv: Boolean) {
        privateInsert = priv
        photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    fun takePhoto(priv: Boolean) {
        privateInsert = priv
        val f = File(Exporters.exportDir(ctx), "kamera_${System.currentTimeMillis()}.jpg")
        cameraFile = f
        try { cameraLauncher.launch(Exporters.uriFor(ctx, f)) }
        catch (_: Exception) { state.flash("Keine Kamera-App gefunden") }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("Einfügen")
        SwipePrivateRow("Datei einfügen", ActionIcons.Import, { pickFile(false) }) { pickFile(true) }
        SwipePrivateRow("Foto einfügen", ActionIcons.Photo, { pickPhoto(false) }) { pickPhoto(true) }
        SwipePrivateRow("Foto aufnehmen", ActionIcons.Photo, { takePhoto(false) }) { takePhoto(true) }
        ActionRow("Text hinzufügen", ActionIcons.Add) { closePanel(state); TextLayers.create(state) }
        val active = state.document?.activeLayer
        if (TextLayers.isText(active)) {
            ActionRow("Text bearbeiten", chevron = true) { closePanel(state); TextLayers.beginEdit(state, active!!) }
            ActionRow("Text rastern") { TextLayers.rasterize(state, active) }
        }
        FwDivider()
        SectionHeader("Zwischenablage")
        val hasSel = state.document?.selection != null
        ActionRow("Ausschneiden") { ClipboardOps.cut(state); closePanel(state) }
        ActionRow("Kopieren") { ClipboardOps.copy(state); closePanel(state) }
        ActionRow("Alles kopieren") { ClipboardOps.copyAll(state); closePanel(state) }
        ActionRow("Duplizieren") { ClipboardOps.duplicate(state); closePanel(state) }
        ActionRow("Ausschneiden & Einfügen", enabled = hasSel) { ClipboardOps.cutAndPaste(state); closePanel(state) }
        SwipePrivateRow("Einfügen", null, { ClipboardOps.paste(state); closePanel(state) }) {
            ClipboardOps.paste(state)
            state.document?.activeLayer?.isPrivate = true
            state.flash("Privat eingefügt")
            closePanel(state)
        }
    }
}

private fun insertPhoto(state: EditorState, uri: Uri, private: Boolean = false) {
    closePanel(state)
    Background.run("Foto wird eingefügt …", errorFlash(state)) {
        val doc = state.document ?: return@run
        val img = withContext(Dispatchers.IO) { Importers.decodeImage(state.context, uri, doc.width * 2, doc.height * 2) }
        Importers.insertImageAsLayer(state, img, "Eingefügtes Bild", private = private)
        img.recycle()
    }
}

// =====================================================================================
// Leinwand
// =====================================================================================

@Composable
private fun CanvasTab(state: EditorState) {
    val doc = state.document ?: return
    var showInfo by remember { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE") val av = AnimationController.version
    val g = doc.guides
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("Leinwand")
        ActionRow("Zuschneiden & Größe ändern", ActionIcons.Canvas, trailing = "${doc.width} × ${doc.height}") { openCrop(state) }
        SwitchRow("Animationsassistent", AnimationController.isOn(doc, AssistMode.ANIMATION)) {
            AnimationController.toggle(state, AssistMode.ANIMATION); closePanel(state)
        }
        SwitchRow("Seitenassistent", AnimationController.isOn(doc, AssistMode.PAGES)) {
            AnimationController.toggle(state, AssistMode.PAGES); closePanel(state)
        }
        FwDivider()
        SectionHeader("Zeichenhilfe")
        SwitchRow("Zeichenhilfe", g.visible && g.kind != GuideKind.NONE, subtitle = guideSummary(g.kind)) { on ->
            if (on && g.kind == GuideKind.NONE) g.kind = GuideKind.GRID_2D
            g.visible = on
            state.markDirty(); state.invalidate()
        }
        ActionRow("Zeichenhilfe bearbeiten", chevron = true) { openGuideEditor(state) }
        SwitchRow("Zeichenassistent", doc.activeLayer?.assisted == true, subtitle = "Für die aktive Ebene") { on ->
            val layer = doc.activeLayer ?: return@SwitchRow
            val prevG = g.assisted
            layer.assisted = on; g.assisted = on
            state.undo.push(at.techgaisi.farbwerk.undo.LambdaAction("Zeichenassistent",
                onUndo = { layer.assisted = !on; g.assisted = prevG }, onRedo = { layer.assisted = on; g.assisted = on }))
            state.flash(if (on) "Zeichenassistent an" else "Zeichenassistent aus")
            state.invalidate()
        }
        FwDivider()
        SectionHeader("Ansicht")
        SwitchRow("Referenz", ActionModes.reference.visible, subtitle = "Schwebendes Referenzfenster") {
            ActionModes.reference.visible = it; if (it) closePanel(state)
        }
        ActionRow("Leinwand horizontal spiegeln") { CanvasOps.flipCanvas(state, horizontal = true) }
        ActionRow("Leinwand vertikal spiegeln") { CanvasOps.flipCanvas(state, horizontal = false) }
        ActionRow("Leinwandinformationen", ActionIcons.Help, chevron = true) { showInfo = true }
    }
    if (showInfo) CanvasInfoDialog(state) { showInfo = false }
}

private fun guideSummary(k: GuideKind) = when (k) {
    GuideKind.NONE -> "Keine"
    GuideKind.GRID_2D -> "2D-Raster"
    GuideKind.ISOMETRIC -> "Isometrisch"
    GuideKind.PERSPECTIVE -> "Perspektive"
    GuideKind.SYMMETRY -> "Symmetrie"
}

// =====================================================================================
// Teilen
// =====================================================================================

@Composable
private fun ShareTab(state: EditorState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("Bild teilen")
        listOf(ExportFormat.PROJECT, ExportFormat.PSD, ExportFormat.PDF, ExportFormat.JPEG, ExportFormat.PNG,
            ExportFormat.TIFF).forEach { f ->
            ActionRow(f.label, trailing = f.description) { share(state, f) }
        }
        FwDivider()
        SectionHeader("Ebenen teilen")
        listOf(ExportFormat.PDF_PAGES, ExportFormat.LAYERS_PNG, ExportFormat.ANIM_GIF, ExportFormat.ANIM_MP4).forEach { f ->
            ActionRow(f.label, trailing = f.description) { share(state, f) }
        }
    }
}

private fun share(state: EditorState, f: ExportFormat) {
    val doc = state.document ?: return
    if (state.tool == Tool.TRANSFORM || state.tool == Tool.ADJUST) state.selectTool(Tool.PAINT)
    Background.run("Exportiere ${f.label} …", errorFlash(state)) {
        Exporters.exportAndShare(state.context, doc, f)
    }
}

// =====================================================================================
// Video / Hilfe
// =====================================================================================

@Composable
private fun VideoTab() {
    val ui = LocalUi.current
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(ActionIcons.Video, null, tint = ui.iconMuted, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text("Zeitraffer-Aufnahme", color = ui.text, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text("Diese Funktion ist in dieser Version nicht verfügbar.", color = ui.textMuted, fontSize = 13.sp,
            textAlign = TextAlign.Center)
    }
}

@Composable
private fun HelpTab() {
    val ui = LocalUi.current
    val gestures = listOf(
        "Zwei Finger tippen" to "Rückgängig (halten: schnell rückgängig)",
        "Drei Finger tippen" to "Wiederholen",
        "Zwei Finger zusammenziehen" to "Zoomen und drehen",
        "Zwei Finger schnell zusammenziehen" to "Leinwand einpassen",
        "Drei Finger hin und her wischen" to "Ebene leeren",
        "Drei Finger nach unten wischen" to "Kopieren & Einfügen",
        "Vier Finger tippen" to "Vollbild",
        "Berühren und halten" to "Pipette",
        "Zeichnen und halten" to "Schnellform (Linie, Kreis, Rechteck …)",
        "Seitenleiste: Quadrat" to "Pipette / Modifizierer",
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        SectionHeader("Gesten")
        gestures.forEach { (k, v) -> HelpLine(k, v) }
        FwDivider()
        SectionHeader("Tastenkürzel")
        at.techgaisi.farbwerk.input.KeyboardShortcuts.SHORTCUTS.forEach { (k, v) -> HelpLine(k, v) }
        FwDivider()
        SectionHeader("Tipps")
        Text(
            "Projekte werden automatisch gespeichert, wenn du zur Galerie zurückkehrst oder die App verlässt. " +
                "Mit Strg+S kannst du jederzeit sofort speichern. Exporte findest du unter Aktionen → Teilen.",
            color = ui.textMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun HelpLine(key: String, value: String) {
    val ui = LocalUi.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
        Text(key, color = ui.text, fontSize = 13.sp, modifier = Modifier.weight(0.48f))
        Text(value, color = ui.textMuted, fontSize = 13.sp, modifier = Modifier.weight(0.52f))
    }
}

/**
 * Zeile mit Wischaktion: nach links wischen zeigt „Privat“ (Einfügen als private Ebene, wie im Vorbild).
 */
@Composable
private fun SwipePrivateRow(label: String, icon: ImageVector?, onClick: () -> Unit, onPrivate: () -> Unit) {
    val ui = LocalUi.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val reveal = with(density) { 88.dp.toPx() }
    val off = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier.matchParentSize().background(ui.accent).clickable {
                scope.launch { off.animateTo(0f) }; onPrivate()
            }.padding(end = 16.dp),
            contentAlignment = Alignment.CenterEnd,
        ) { Text("Privat", color = androidx.compose.ui.graphics.Color.White, fontSize = 14.sp) }
        Box(
            Modifier.offset { androidx.compose.ui.unit.IntOffset(off.value.toInt(), 0) }.background(ui.panel)
                .draggable(
                    orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
                    state = androidx.compose.foundation.gestures.rememberDraggableState { d ->
                        scope.launch { off.snapTo((off.value + d).coerceIn(-reveal, 0f)) }
                    },
                    onDragStopped = { v -> off.animateTo(if (off.value < -reveal / 2 || v < -800f) -reveal else 0f) },
                )
        ) {
            ActionRow(label, icon) { if (off.value < -1f) scope.launch { off.animateTo(0f) } else onClick() }
        }
    }
}
