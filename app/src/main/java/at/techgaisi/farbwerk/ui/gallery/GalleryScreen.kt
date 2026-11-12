package at.techgaisi.farbwerk.ui.gallery

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.io.Background
import at.techgaisi.farbwerk.io.ExportFormat
import at.techgaisi.farbwerk.io.Exporters
import at.techgaisi.farbwerk.io.ImportKind
import at.techgaisi.farbwerk.io.Importers
import at.techgaisi.farbwerk.io.ProjectInfo
import at.techgaisi.farbwerk.io.StackInfo
import at.techgaisi.farbwerk.model.ArtDocument
import at.techgaisi.farbwerk.ui.actions.ActionIcons
import at.techgaisi.farbwerk.ui.actions.ActionRow
import at.techgaisi.farbwerk.ui.actions.BusyDialog
import at.techgaisi.farbwerk.ui.actions.FwDialog
import at.techgaisi.farbwerk.ui.actions.FwTextField
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Ein Eintrag im Galerie-Raster. */
private sealed class GalleryItem {
    abstract val key: String
    abstract val sortTime: Long
    data class Project(val info: ProjectInfo) : GalleryItem() {
        override val key get() = "p:" + info.id
        override val sortTime get() = info.modified
    }
    data class Stack(val stack: StackInfo, val members: List<ProjectInfo>) : GalleryItem() {
        override val key get() = "s:" + stack.id
        override val sortTime get() = members.maxOfOrNull { it.modified } ?: stack.created
    }
}

/** Galerie (Startbildschirm): Werke, Stapel, Import, Neue Leinwand. */
@Composable
fun GalleryScreen(state: EditorState) {
    val ui = LocalUi.current
    val store = state.store
    val ctx = state.context

    var projects by remember { mutableStateOf<List<ProjectInfo>>(emptyList()) }
    var stacks by remember { mutableStateOf<List<StackInfo>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var openStackId by remember { mutableStateOf<String?>(null) }
    var selectMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var showNew by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ProjectInfo?>(null) }
    var renameStack by remember { mutableStateOf<StackInfo?>(null) }
    var deleteTargets by remember { mutableStateOf<List<ProjectInfo>?>(null) }
    var shareTarget by remember { mutableStateOf<ProjectInfo?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var order by remember { mutableStateOf<List<String>>(emptyList()) }
    var preview by remember { mutableStateOf<Pair<List<ProjectInfo>, Int>?>(null) }
    val drag = remember { GalleryDragState() }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    fun toast(msg: String) { message = msg }
    // Overlays des Aktionen-Pakets (Fallback-Host, falls nicht im EditorScreen eingebettet)
    val activity = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        at.techgaisi.farbwerk.ui.actions.OverlayHost.install(findAct(activity), state)
    }
    val onError: (Throwable) -> Unit = { t -> toast("Fehler: ${t.message ?: t.javaClass.simpleName}") }

    LaunchedEffect(store.changeCount) {
        val (p, s) = withContext(Dispatchers.IO) { store.list() to store.stacks() }
        order = withContext(Dispatchers.IO) { store.order() }
        projects = p; stacks = s; loaded = true
        selected.retainAll { id -> p.any { it.id == id } }
    }
    LaunchedEffect(message) { if (message != null) { delay(2200); message = null } }

    val openStack = stacks.firstOrNull { it.id == openStackId }
    LaunchedEffect(stacks, loaded) {
        if (loaded && openStackId != null && stacks.none { it.id == openStackId }) openStackId = null
    }

    BackHandler(enabled = selectMode || openStackId != null) {
        if (selectMode) { selectMode = false; selected.clear() } else openStackId = null
    }

    // ----------------------------------------------------------- Launcher
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) Background.run("Wird importiert …", onError) {
            val stackId = openStackId
            val ok = withContext(Dispatchers.IO) {
                when (Importers.sniff(ctx, uri)) {
                    ImportKind.PROJECT -> store.importProjectZip(uri, stackId) != null
                    ImportKind.PSD -> { store.saveDetached(Importers.readPsd(ctx, uri), stackId); true }
                    ImportKind.IMAGE -> {
                        val bmp = Importers.decodeImage(ctx, uri)
                        store.saveDetached(Importers.documentFromImage(bmp, Importers.displayName(ctx, uri)), stackId); true
                    }
                    ImportKind.UNKNOWN -> false
                }
            }
            toast(if (ok) "Importiert" else "Datei konnte nicht importiert werden")
        }
    }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) Background.run("Foto wird geöffnet …", onError) {
            val stackId = openStackId
            val doc = withContext(Dispatchers.IO) {
                val bmp = Importers.decodeImage(ctx, uri)
                Importers.documentFromImage(bmp, Importers.displayName(ctx, uri)).also { store.saveDetached(it, stackId) }
            }
            state.openDocument(doc)
        }
    }

    fun open(info: ProjectInfo) = Background.run("Wird geöffnet …", onError) {
        val doc = withContext(Dispatchers.IO) { store.load(info.id) }
        if (doc == null) toast("Projekt konnte nicht geladen werden") else state.openDocument(doc)
    }

    fun duplicate(ids: List<String>) = Background.run("Wird dupliziert …", onError) {
        withContext(Dispatchers.IO) { ids.forEach { store.duplicate(it) } }
        toast(if (ids.size == 1) "Dupliziert" else "${ids.size} Werke dupliziert")
    }

    // ----------------------------------------------------------- Inhalt
    val items: List<GalleryItem> = remember(projects, stacks, openStackId, order) {
        val raw = if (openStackId != null) projects.filter { it.stackId == openStackId }.map { GalleryItem.Project(it) }
        else {
            val byStack = projects.filter { it.stackId != null }.groupBy { it.stackId!! }
            (projects.filter { it.stackId == null }.map { GalleryItem.Project(it) } +
                stacks.mapNotNull { s -> byStack[s.id]?.let { GalleryItem.Stack(s, it) } })
        }
        // Manuelle Reihenfolge; neue (noch nicht einsortierte) Werke stehen vorne, neueste zuerst
        val pos = HashMap<String, Int>().apply { order.forEachIndexed { i, k -> putIfAbsent(k, i) } }
        raw.sortedWith(compareBy<GalleryItem>({ pos[it.key] ?: -1 }, { -it.sortTime }))
    }

    /** Ablegen nach dem Ziehen: stapeln, in Stapel legen, aus Stapel entfernen oder umsortieren. */
    fun onDrop(key: String, target: String?, before: String?, overHeader: Boolean) {
        val pid = key.removePrefix("p:")
        val isProject = key.startsWith("p:")
        when {
            overHeader && openStackId != null && isProject -> {
                scope.launch(Dispatchers.IO) { store.moveToStack(listOf(pid), null) }
                toast("Aus Stapel entfernt")
            }
            target != null && isProject && target.startsWith("s:") -> {
                scope.launch(Dispatchers.IO) { store.moveToStack(listOf(pid), target.removePrefix("s:")) }
                toast("Zum Stapel hinzugefügt")
            }
            target != null && isProject && target.startsWith("p:") && openStackId == null -> {
                val keys = items.map { it.key }
                val at = keys.indexOf(target)
                scope.launch(Dispatchers.IO) {
                    val sid = store.createStack("Stapel", listOf(target.removePrefix("p:"), pid))
                    val newKeys = keys.filter { it != key && it != target }.toMutableList()
                    newKeys.add(at.coerceIn(0, newKeys.size), "s:$sid")
                    store.setOrder(newKeys)
                }
                toast("Stapel erstellt")
            }
            before != null -> {
                val keys = items.map { it.key }.toMutableList()
                keys.remove(key)
                val i = if (before.isEmpty()) keys.size else keys.indexOf(before).let { if (it < 0) keys.size else it }
                keys.add(i, key)
                order = keys + order.filter { it !in keys }
                scope.launch(Dispatchers.IO) { store.setOrder(keys) }
            }
        }
    }

    val bg = ui.bar.copy(alpha = 1f)
    Box(Modifier.fillMaxSize().background(bg)) {
        Column(Modifier.fillMaxSize()) {
            // ------------------------------------------------------- Kopfzeile
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (openStack != null) {
                    Row(Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (drag.overHeader) ui.accent.copy(alpha = 0.25f) else Color.Transparent)
                        .clickable { openStackId = null; selectMode = false; selected.clear() }
                        .padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(ActionIcons.Back, "Zurück", tint = ui.accent, modifier = Modifier.size(22.dp))
                        Text("Galerie", color = ui.accent, fontSize = 16.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(openStack.name, color = ui.text, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { renameStack = openStack })
                } else {
                    Text("Galerie", color = ui.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                if (selectMode) {
                    val selInfos = projects.filter { it.id in selected }
                    if (openStackId == null) HeaderButton("Stapeln", enabled = selected.isNotEmpty()) {
                        val ids = selected.toList()
                        scope.launch(Dispatchers.IO) { store.createStack("Stapel", ids) }
                        selectMode = false; selected.clear()
                    } else HeaderButton("Aus Stapel", enabled = selected.isNotEmpty()) {
                        val ids = selected.toList()
                        scope.launch(Dispatchers.IO) { store.moveToStack(ids, null) }
                        selectMode = false; selected.clear()
                    }
                    HeaderButton("Vorschau", enabled = selected.isNotEmpty()) {
                        preview = selInfos to 0
                    }
                    HeaderButton("Teilen", enabled = selected.isNotEmpty()) {
                        Background.run("Wird vorbereitet …", onError) {
                            val files = withContext(Dispatchers.IO) { selInfos.mapNotNull { store.exportStoredProject(it.id, it.name) } }
                            Exporters.shareMultiple(ctx, files, ExportFormat.PROJECT.mime)
                        }
                    }
                    HeaderButton("Duplizieren", enabled = selected.isNotEmpty()) { duplicate(selected.toList()); selectMode = false; selected.clear() }
                    HeaderButton("Löschen", enabled = selected.isNotEmpty(), destructive = true) { deleteTargets = selInfos }
                    HeaderButton("Fertig", bold = true) { selectMode = false; selected.clear() }
                } else {
                    HeaderButton("Auswählen") { selectMode = true }
                    HeaderButton("Importieren") { importLauncher.launch(arrayOf("*/*")) }
                    HeaderButton("Foto") {
                        photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    Box(
                        Modifier.padding(start = 6.dp).size(36.dp).clip(CircleShape).clickable { showNew = true },
                        contentAlignment = Alignment.Center
                    ) { Icon(ActionIcons.Plus, "Neue Leinwand", tint = ui.icon, modifier = Modifier.size(26.dp)) }
                }
            }

            // ------------------------------------------------------- Raster
            if (loaded && items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Noch keine Werke", color = ui.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Text("Tippe auf + für eine neue Leinwand oder importiere ein Bild.", color = ui.textMuted, fontSize = 14.sp)
                    }
                }
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(210.dp),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                    modifier = Modifier.fillMaxSize().navigationBarsPadding()
                        .onGloballyPositioned { drag.gridOrigin = it.positionInRoot() },
                ) {
                    items(items, key = { it.key }) { item ->
                        val dragging = drag.dragKey == item.key
                        val projectsHere = items.mapNotNull { (it as? GalleryItem.Project)?.info }
                        Box(
                            Modifier
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer {
                                    if (dragging) {
                                        translationX = drag.offset.x; translationY = drag.offset.y
                                        scaleX = 1.05f; scaleY = 1.05f; alpha = 0.92f
                                    }
                                }
                                .galleryDraggable(drag, item.key, gridState, enabled = !selectMode,
                                    canStackOnto = { k -> openStackId == null && item is GalleryItem.Project && (k.startsWith("p:") || k.startsWith("s:")) },
                                    onDrop = { k, t, b, h -> onDrop(k, t, b, h) })
                                .then(if (item is GalleryItem.Project) Modifier.pinchOutToPreview {
                                    preview = projectsHere to projectsHere.indexOfFirst { it.id == item.info.id }.coerceAtLeast(0)
                                } else Modifier)
                        ) {
                        // Einfügemarke links neben der Kachel
                        if (drag.dragKey != null && drag.insertBefore == item.key) {
                            Box(Modifier.align(Alignment.CenterStart).offset(x = (-12).dp).width(3.dp).height(150.dp)
                                .clip(RoundedCornerShape(2.dp)).background(ui.accent))
                        }
                        val highlight = drag.stackTarget == item.key
                        when (item) {
                            is GalleryItem.Project -> ProjectTile(
                                state, item.info, bg,
                                selectMode = selectMode, selected = item.info.id in selected,
                                inStack = item.info.stackId != null,
                                onOpen = {
                                    if (selectMode) {
                                        if (item.info.id in selected) selected.remove(item.info.id) else selected.add(item.info.id)
                                    } else open(item.info)
                                },
                                onRename = { renameTarget = item.info },
                                onShare = { shareTarget = item.info },
                                onDuplicate = { duplicate(listOf(item.info.id)) },
                                onDelete = { deleteTargets = listOf(item.info) },
                                onUnstack = { scope.launch(Dispatchers.IO) { store.moveToStack(listOf(item.info.id), null) } },
                                menu = drag.menuKey == item.key,
                                onMenuDismiss = { drag.menuKey = null },
                                highlight = highlight,
                            )
                            is GalleryItem.Stack -> StackTile(
                                state, item,
                                onOpen = { if (!selectMode) openStackId = item.stack.id },
                                onRename = { renameStack = item.stack },
                                onDissolve = { scope.launch(Dispatchers.IO) { store.dissolveStack(item.stack.id) } },
                                menu = drag.menuKey == item.key,
                                onMenuDismiss = { drag.menuKey = null },
                                highlight = highlight,
                            )
                        }
                        }
                    }
                }
            }
        }

        // Kurzmeldung unten
        message?.let { msg ->
            Box(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Color(0xE0202024))
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) { Text(msg, color = Color.White, fontSize = 14.sp) }
        }
    }

    // ----------------------------------------------------------- Dialoge
    if (showNew) NewCanvasDialog(onDismiss = { showNew = false }) { spec ->
        showNew = false
        val doc = ArtDocument.create(spec.name, spec.width, spec.height)
        doc.dpi = spec.dpi
        doc.backgroundColor = spec.background
        openStackId?.let { sid -> scope.launch(Dispatchers.IO) { store.moveToStack(listOf(doc.id), sid) } }
        state.openDocument(doc)
    }

    renameTarget?.let { info ->
        var name by remember(info.id) { mutableStateOf(info.name) }
        FwDialog("Umbenennen", onDismiss = { renameTarget = null }, confirmLabel = "Sichern", onConfirm = {
            val n = name; renameTarget = null
            scope.launch(Dispatchers.IO) { store.rename(info.id, n) }
        }) {
            FwTextField(name, { name = it }, "Name", Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        }
    }

    renameStack?.let { s ->
        var name by remember(s.id) { mutableStateOf(s.name) }
        FwDialog("Stapel umbenennen", onDismiss = { renameStack = null }, confirmLabel = "Sichern", onConfirm = {
            val n = name; renameStack = null
            scope.launch(Dispatchers.IO) { store.renameStack(s.id, n) }
        }) {
            FwTextField(name, { name = it }, "Name", Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        }
    }

    deleteTargets?.let { list ->
        FwDialog(if (list.size == 1) "„${list[0].name}“ löschen?" else "${list.size} Werke löschen?",
            onDismiss = { deleteTargets = null }, confirmLabel = "Löschen", destructive = true, onConfirm = {
                deleteTargets = null; selectMode = false; selected.clear()
                scope.launch(Dispatchers.IO) { list.forEach { store.delete(it.id) } }
            }) {
            Text("Das kann nicht rückgängig gemacht werden.", color = ui.textMuted, fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
    }

    shareTarget?.let { info ->
        FwDialog("„${info.name}“ teilen", onDismiss = { shareTarget = null }, confirmLabel = null) {
            listOf(ExportFormat.PROJECT, ExportFormat.PSD, ExportFormat.PDF, ExportFormat.JPEG, ExportFormat.PNG,
                ExportFormat.TIFF, ExportFormat.LAYERS_PNG).forEach { f ->
                ActionRow(f.label, trailing = f.description) {
                    shareTarget = null
                    Background.run("Exportiere ${f.label} …", onError) {
                        if (f == ExportFormat.PROJECT) {
                            val file = withContext(Dispatchers.IO) { store.exportStoredProject(info.id, info.name) }
                            if (file != null) Exporters.share(ctx, file, f.mime)
                        } else {
                            val doc = withContext(Dispatchers.IO) { store.load(info.id) } ?: return@run
                            try { Exporters.exportAndShare(ctx, doc, f) }
                            finally { doc.layers.forEach { it.bitmap.recycle(); it.mask?.recycle() } }
                        }
                    }
                }
            }
        }
    }

    preview?.let { (list, idx) ->
        GalleryPreview(state, list, idx, onClose = { preview = null }) { info -> preview = null; open(info) }
    }

    BusyDialog()
}

@Composable
private fun HeaderButton(label: String, enabled: Boolean = true, destructive: Boolean = false, bold: Boolean = false,
                         onClick: () -> Unit) {
    val ui = LocalUi.current
    Text(
        label,
        color = when { !enabled -> ui.textMuted.copy(alpha = 0.5f); destructive -> Color(0xFFFF5A52); else -> ui.accent },
        fontSize = 16.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

/** Vorschaubild asynchron laden (Cache im ProjectStore). */
@Composable
private fun rememberThumb(state: EditorState, id: String, modified: Long): Bitmap? {
    val b by produceState<Bitmap?>(null, id, modified) {
        value = withContext(Dispatchers.IO) { state.store.thumbnail(id) }
    }
    return b
}

/** Vorschau mit gewahrtem Seitenverhältnis, mittig in einer festen Fläche. */
@Composable
private fun Thumbnail(bmp: Bitmap?, w: Int, h: Int, modifier: Modifier = Modifier, highlight: Boolean = false) {
    val ui = LocalUi.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.BottomCenter) {
        val ratio = if (h > 0) w / h.toFloat() else 1f
        val boxRatio = maxWidth / maxHeight
        val (tw, th) = if (ratio > boxRatio) maxWidth to maxWidth / ratio else maxHeight * ratio to maxHeight
        Box(
            Modifier.size(tw, th).shadow(6.dp, RoundedCornerShape(3.dp)).clip(RoundedCornerShape(3.dp))
                .background(Color.White)
                .border(if (highlight) 3.dp else 0.dp, if (highlight) LocalUi.current.accent else Color.Transparent, RoundedCornerShape(3.dp))
        ) {
            if (bmp != null) {
                Image(bmp.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            } else Box(Modifier.fillMaxSize().background(ui.panelElevated))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectTile(
    state: EditorState,
    info: ProjectInfo,
    bg: Color,
    selectMode: Boolean,
    selected: Boolean,
    inStack: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onUnstack: () -> Unit,
    menu: Boolean,
    onMenuDismiss: () -> Unit,
    highlight: Boolean,
) {
    val ui = LocalUi.current
    val thumb = rememberThumb(state, info.id, info.modified)
    val density = LocalDensity.current
    val revealPx = with(density) { 186.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    fun closeSwipe() { scope.launch { offset.animateTo(0f) } }

    Box(Modifier.fillMaxWidth()) {
        // Hinter der Kachel: Wisch-Aktionen
        if (offset.value < -1f) {
            Row(Modifier.align(Alignment.CenterEnd).height(160.dp).padding(bottom = 0.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                SwipeButton("Teilen", ActionIcons.Share, ui.accent) { closeSwipe(); onShare() }
                SwipeButton("Duplizieren", ActionIcons.Duplicate, Color(0xFF6E6E78)) { closeSwipe(); onDuplicate() }
                SwipeButton("Löschen", ActionIcons.Trash, Color(0xFFE5483F)) { closeSwipe(); onDelete() }
            }
        }
        Column(
            Modifier.fillMaxWidth()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(bg)
                .draggable(
                    orientation = Orientation.Horizontal,
                    enabled = !selectMode,
                    state = rememberDraggableState { d -> scope.launch { offset.snapTo((offset.value + d).coerceIn(-revealPx, 0f)) } },
                    onDragStopped = { v ->
                        val open = offset.value < -revealPx / 2 || v < -800f
                        offset.animateTo(if (open) -revealPx else 0f)
                    },
                )
        ) {
            Box(
                Modifier.fillMaxWidth().height(160.dp)
                    .clickable { if (offset.value < -1f) closeSwipe() else onOpen() }
            ) {
                Thumbnail(thumb, info.width, info.height, Modifier.fillMaxSize(), highlight)
                if (selectMode) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape)
                            .background(if (selected) ui.accent else Color(0x66000000))
                            .border(1.5.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) { if (selected) Icon(ActionIcons.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                }
                DropdownMenu(expanded = menu, onDismissRequest = onMenuDismiss) {
                    DropdownMenuItem(text = { Text("Teilen") }, onClick = { onMenuDismiss(); onShare() })
                    DropdownMenuItem(text = { Text("Duplizieren") }, onClick = { onMenuDismiss(); onDuplicate() })
                    DropdownMenuItem(text = { Text("Umbenennen") }, onClick = { onMenuDismiss(); onRename() })
                    if (inStack) DropdownMenuItem(text = { Text("Aus Stapel entfernen") }, onClick = { onMenuDismiss(); onUnstack() })
                    DropdownMenuItem(text = { Text("Löschen", color = Color(0xFFFF5A52)) }, onClick = { onMenuDismiss(); onDelete() })
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                info.name, color = ui.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(4.dp))
                    .clickable(enabled = !selectMode, onClick = onRename).padding(horizontal = 4.dp, vertical = 1.dp)
            )
            Text("${info.width} × ${info.height} px", color = ui.textMuted, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun SwipeButton(label: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Column(
        Modifier.width(58.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(color).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StackTile(state: EditorState, item: GalleryItem.Stack, onOpen: () -> Unit, onRename: () -> Unit, onDissolve: () -> Unit,
                      menu: Boolean, onMenuDismiss: () -> Unit, highlight: Boolean) {
    val ui = LocalUi.current
    val top = item.members.sortedByDescending { it.modified }.take(3)
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(6.dp))
                .border(if (highlight) 3.dp else 0.dp, if (highlight) ui.accent else Color.Transparent, RoundedCornerShape(6.dp))
                .clickable(onClick = onOpen),
            contentAlignment = Alignment.Center
        ) {
            // Gestapelte Vorschauen, hinterste zuerst
            top.reversed().forEachIndexed { i, p ->
                val depth = top.size - 1 - i
                val thumb = rememberThumb(state, p.id, p.modified)
                Thumbnail(thumb, p.width, p.height,
                    Modifier.fillMaxSize().padding(start = (depth * 10).dp, end = (20 - depth * 10).dp,
                        top = (20 - depth * 10).dp, bottom = (depth * 10).dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = onMenuDismiss) {
                DropdownMenuItem(text = { Text("Umbenennen") }, onClick = { onMenuDismiss(); onRename() })
                DropdownMenuItem(text = { Text("Stapel auflösen") }, onClick = { onMenuDismiss(); onDissolve() })
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(item.stack.name, color = ui.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text(if (item.members.size == 1) "1 Werk" else "${item.members.size} Werke", color = ui.textMuted, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

private fun findAct(c: android.content.Context): android.app.Activity? {
    var x: android.content.Context? = c
    while (x is android.content.ContextWrapper) { if (x is android.app.Activity) return x; x = x.baseContext }
    return null
}
