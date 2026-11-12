package at.techgaisi.farbwerk.ui.actions

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.animation.AnimationController
import at.techgaisi.farbwerk.animation.AssistMode
import at.techgaisi.farbwerk.io.Background
import at.techgaisi.farbwerk.io.ExportFormat
import at.techgaisi.farbwerk.io.Exporters
import at.techgaisi.farbwerk.io.Importers
import at.techgaisi.farbwerk.model.GuideKind
import at.techgaisi.farbwerk.model.Panel
import at.techgaisi.farbwerk.model.Tool
import at.techgaisi.farbwerk.selection.ClipboardOps
import at.techgaisi.farbwerk.text.TextLayers
import at.techgaisi.farbwerk.ui.common.PanelCard
import at.techgaisi.farbwerk.undo.LambdaAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/**
 * Popover-Panel "Aktionen" (UI_SPEC §6). [modifier] enthält bereits Größe/Position.
 *
 * Aufbau: Titel (22 sp, fett) · Tab-Reihe mit Icon-Kacheln 28 × 22 dp und Label (aktiv = Akzent-Kachel) ·
 * Inhalt als gruppierte Container. Das Panel wächst mit dem Inhalt bis zur vom Host vorgegebenen Maximalhöhe.
 */
@Composable
fun ActionsPanel(state: EditorState, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val rev = state.revision
    PanelCard(modifier, title = null) {
        ActionsTitle()
        ActionTabRow()
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
            when (ActionsMemory.tab) {
                ActionTab.ADD -> AddTab(state)
                ActionTab.CANVAS -> CanvasTab(state)
                ActionTab.SHARE -> ShareTab(state)
                ActionTab.VIDEO -> at.techgaisi.farbwerk.ui.video.VideoTab(state)
                ActionTab.SETTINGS -> SettingsTab(state)
                ActionTab.HELP -> HelpTab()
            }
        }
    }
    BusyDialog()
}

/** Titelzeile 52 dp: „Aktionen“, 22 sp, Gewicht 700, links 20 dp, oben 16 dp. */
@Composable
private fun ActionsTitle() {
    val t = actionTokens()
    Box(Modifier.fillMaxWidth().height(52.dp).padding(start = 20.dp, top = 14.dp)) {
        Text("Aktionen", color = t.text, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Tab-Reihe: sechs gleich verteilte Tabs, je Kachel 28 × 22 dp (Radius 6 dp, `panelInset`, aktiv `accent`)
 * mit 16-dp-Symbol und 10-sp-Label darunter. Erneutes Tippen auf den aktiven Tab führt zur Startseite zurück.
 */
@Composable
private fun ActionTabRow() {
    val t = actionTokens()
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        ActionTab.entries.forEach { tab ->
            val sel = ActionsMemory.tab == tab
            Column(
                Modifier.weight(1f)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {
                        if (sel && tab == ActionTab.SETTINGS) resetSettingsPage()
                        ActionsMemory.tab = tab
                    }
                    .padding(vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(width = 28.dp, height = 22.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (sel) t.accent else t.inset),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(tab.icon, tab.label, tint = if (sel) Color.White else t.text, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.height(4.dp))
                // Lange deutsche Labels dürfen minimal in die Nachbarzelle ragen (statt umzubrechen).
                Text(
                    tab.label, color = if (sel) t.text else t.text.copy(alpha = 0.85f), fontSize = 10.sp,
                    lineHeight = 12.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
                    modifier = Modifier.wrapContentWidth(unbounded = true),
                )
            }
        }
    }
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

    ActionPage {
        ActionGroup(footer = "Nach links wischen fügt als private Ebene ein.") {
            SwipePrivateRow("Datei einfügen", { pickFile(false) }) { pickFile(true) }
            SwipePrivateRow("Foto einfügen", { pickPhoto(false) }) { pickPhoto(true) }
            SwipePrivateRow("Foto aufnehmen", { takePhoto(false) }) { takePhoto(true) }
        }
        ActionGroup {
            ActionRow("Text hinzufügen") { closePanel(state); TextLayers.create(state) }
            val active = state.document?.activeLayer
            if (TextLayers.isText(active)) {
                ActionRow("Text bearbeiten") { closePanel(state); TextLayers.beginEdit(state, active!!) }
                ActionRow("Text rastern") { TextLayers.rasterize(state, active) }
            }
        }
        val hasSel = state.document?.selection != null
        ActionGroup {
            ActionRow("Ausschneiden") { ClipboardOps.cut(state); closePanel(state) }
            ActionRow("Kopieren") { ClipboardOps.copy(state); closePanel(state) }
            ActionRow("Alles kopieren") { ClipboardOps.copyAll(state); closePanel(state) }
            ActionRow("Duplizieren") { ClipboardOps.duplicate(state); closePanel(state) }
            ActionRow("Ausschneiden & Einfügen", enabled = hasSel) { ClipboardOps.cutAndPaste(state); closePanel(state) }
            SwipePrivateRow("Einfügen", { ClipboardOps.paste(state); closePanel(state) }) {
                ClipboardOps.paste(state)
                state.document?.activeLayer?.isPrivate = true
                state.flash("Privat eingefügt")
                closePanel(state)
            }
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
    val guideOn = g.visible && g.kind != GuideKind.NONE
    ActionPage {
        ActionGroup {
            ActionRow("Zuschneiden und Größe ändern", trailing = "${doc.width} × ${doc.height}") { openCrop(state) }
            SwitchRow("Animationsassistent", AnimationController.isOn(doc, AssistMode.ANIMATION)) {
                AnimationController.toggle(state, AssistMode.ANIMATION); closePanel(state)
            }
            SwitchRow("Seitenassistent", AnimationController.isOn(doc, AssistMode.PAGES)) {
                AnimationController.toggle(state, AssistMode.PAGES); closePanel(state)
            }
        }
        ActionGroup {
            SwitchRow("Zeichenhilfe", guideOn) { on ->
                if (on && g.kind == GuideKind.NONE) g.kind = GuideKind.GRID_2D
                g.visible = on
                state.markDirty(); state.invalidate()
            }
            // Wie im Vorbild nur bei eingeschalteter Zeichenhilfe bedienbar (sonst ausgegraut).
            ActionRow("Zeichenhilfe bearbeiten", trailing = guideSummary(g.kind), enabled = guideOn) { openGuideEditor(state) }
            SwitchRow("Zeichenassistent", doc.activeLayer?.assisted == true) { on ->
                val layer = doc.activeLayer ?: return@SwitchRow
                val prevG = g.assisted
                layer.assisted = on; g.assisted = on
                state.undo.push(LambdaAction("Zeichenassistent",
                    onUndo = { layer.assisted = !on; g.assisted = prevG }, onRedo = { layer.assisted = on; g.assisted = on }))
                state.flash(if (on) "Zeichenassistent an" else "Zeichenassistent aus")
                state.invalidate()
            }
        }
        ActionGroup {
            SwitchRow("Referenz", ActionModes.reference.visible) {
                ActionModes.reference.visible = it; if (it) closePanel(state)
            }
        }
        ActionGroup {
            ActionRow("Leinwand horizontal spiegeln") { CanvasOps.flipCanvas(state, horizontal = true) }
            ActionRow("Leinwand vertikal spiegeln") { CanvasOps.flipCanvas(state, horizontal = false) }
        }
        ActionGroup {
            ActionRow("Leinwandinformationen") { showInfo = true }
        }
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
    ActionPage {
        ActionGroup(header = "Bild teilen") {
            listOf(ExportFormat.PROJECT, ExportFormat.PSD, ExportFormat.PDF, ExportFormat.JPEG, ExportFormat.PNG,
                ExportFormat.TIFF).forEach { f ->
                ActionRow(f.label, trailing = f.description) { share(state, f) }
            }
        }
        ActionGroup(header = "Ebenen teilen") {
            listOf(ExportFormat.PDF_PAGES, ExportFormat.LAYERS_PNG, ExportFormat.ANIM_GIF, ExportFormat.ANIM_MP4).forEach { f ->
                ActionRow(f.label, trailing = f.description) { share(state, f) }
            }
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
// Hilfe (der Video-Reiter liegt in ui/video/VideoTab.kt)
// =====================================================================================

@Composable
private fun HelpTab() {
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
    ActionPage {
        ActionGroup(header = "Gesten") { gestures.forEach { (k, v) -> HelpLine(k, v) } }
        ActionGroup(header = "Tastenkürzel") {
            at.techgaisi.farbwerk.input.KeyboardShortcuts.SHORTCUTS.forEach { (k, v) -> HelpLine(k, v) }
        }
        ActionGroup(header = "Tipps") {
            Text(
                "Projekte werden automatisch gespeichert, wenn du zur Galerie zurückkehrst oder die App verlässt. " +
                    "Mit Strg+S kannst du jederzeit sofort speichern. Exporte findest du unter Aktionen → Teilen.",
                color = actionTokens().textSecondary, fontSize = 13.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun HelpLine(key: String, value: String) {
    val t = actionTokens()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 38.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, color = t.text, fontSize = 13.sp, lineHeight = 16.sp, modifier = Modifier.weight(0.48f))
        Text(value, color = t.textSecondary, fontSize = 13.sp, lineHeight = 16.sp, modifier = Modifier.weight(0.52f))
    }
}

/**
 * Zeile mit Wischaktion: nach links wischen zeigt „Privat“ (Einfügen als private Ebene, wie im Vorbild).
 * Die Zeile rastet federnd offen oder geschlossen ein.
 */
@Composable
private fun SwipePrivateRow(label: String, onClick: () -> Unit, onPrivate: () -> Unit) {
    val t = actionTokens()
    val reveal = with(LocalDensity.current) { 88.dp.toPx() }
    val off = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier.matchParentSize().background(t.accent)
                .clickable { scope.launch { off.animateTo(0f) }; onPrivate() }
                .padding(end = 16.dp),
            contentAlignment = Alignment.CenterEnd,
        ) { Text("Privat", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        Box(
            Modifier.offset { IntOffset(off.value.toInt(), 0) }.background(t.inset)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { d ->
                        scope.launch { off.snapTo((off.value + d).coerceIn(-reveal, 0f)) }
                    },
                    onDragStopped = { v -> off.animateTo(if (off.value < -reveal / 2 || v < -800f) -reveal else 0f) },
                )
        ) {
            ActionRow(label) { if (off.value < -1f) scope.launch { off.animateTo(0f) } else onClick() }
        }
    }
}
