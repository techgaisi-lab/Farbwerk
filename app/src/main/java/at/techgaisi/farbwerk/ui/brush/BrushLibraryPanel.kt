package at.techgaisi.farbwerk.ui.brush

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.brush.Brush
import at.techgaisi.farbwerk.brush.BrushLibrary
import at.techgaisi.farbwerk.brush.BrushSet
import at.techgaisi.farbwerk.ui.common.PanelCard
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** Anfrage, das Pinsel-Studio zu öffnen. */
private class StudioRequest(val brush: Brush, val isNew: Boolean)

/** Laufendes Ziehen eines Pinsels oder Sets. */
private class DragInfo(val id: String, val isSet: Boolean, val originSet: String, val brush: Brush?) {
    var pos by mutableStateOf(Offset.Zero)
    var hoverSet by mutableStateOf<String?>(null)
    var moved = false
}

/** Ergebnis der Gesten-Erkennung einer Zeile. */
private interface RowGestures {
    fun onTap()
    fun onSwipe(dx: Float) {}
    fun onSwipeEnd() {}
    fun onLongPress(rootPos: Offset)
    fun onDrag(rootPos: Offset)
    fun onDragEnd(moved: Boolean)
}

/**
 * Tippen / waagrecht wischen / halten (+ ziehen) in einem Gestenhandler, damit sich die Gesten nicht
 * gegenseitig stören. Senkrechtes Ziehen bleibt beim Scrollen der Liste.
 */
private suspend fun PointerInputScope.rowGestures(origin: () -> Offset, swipe: Boolean, g: RowGestures) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        var total = Offset.Zero
        val decision = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val ev = awaitPointerEvent()
                val c = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull "cancel"
                if (!c.pressed) return@withTimeoutOrNull "up"
                total += c.positionChange()
                if (swipe && abs(total.x) > slop && abs(total.x) > abs(total.y) * 1.2f) return@withTimeoutOrNull "swipe"
                if (abs(total.y) > slop || abs(total.x) > slop) return@withTimeoutOrNull "scroll"
            }
            @Suppress("UNREACHABLE_CODE") "cancel"
        }
        when (decision) {
            "up" -> g.onTap()
            "swipe" -> {
                g.onSwipe(total.x)
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    g.onSwipe(c.positionChange().x)
                    c.consume()
                }
                g.onSwipeEnd()
            }
            null -> {
                // Halten: Ziehen beginnt (ohne Bewegung → Menü)
                var p = origin() + down.position
                g.onLongPress(p)
                var moved = false
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) { c.consume(); break }
                    val d = c.positionChange()
                    if (d != Offset.Zero) {
                        p += d
                        if (!moved && (p - (origin() + down.position)).getDistance() > slop) moved = true
                        g.onDrag(p)
                    }
                    c.consume()
                }
                g.onDragEnd(moved)
            }
            else -> {}
        }
    }
}

/**
 * Popover "Pinselbibliothek": links Pinselsets (Symbol + Name), rechts Pinsel mit Name und Strichvorschau.
 * Tippen wählt den Pinsel für das aktuelle Werkzeug (Malen, Wischen oder Radieren); erneutes Tippen auf den
 * aktiven Pinsel öffnet das Pinsel-Studio. Nach links wischen: Teilen/Duplizieren/Löschen (bzw. Finden/
 * Anheften/Entfernen in „Zuletzt verwendet“). Halten: Menü; halten und ziehen: umsortieren, in ein Set
 * verschieben (innerhalb des geöffneten Sets ablegen) oder kopieren (auf den Set-Titel ablegen).
 * Nach unten ziehen oder Lupe: Suche über alle Sets.
 */
@Composable
fun BrushLibraryPanel(state: EditorState, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val lib = state.brushLibrary
    val libVersion = lib.version
    @Suppress("UNUSED_VARIABLE") val tsv = state.toolSettingsVersion
    val tool = state.brushTool
    val settings = state.currentToolSettings
    val activeId = settings.brushId
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var chosenSet by remember(tool) { mutableStateOf(lib.setOf(activeId)) }
    var studio by remember { mutableStateOf<StudioRequest?>(null) }
    val sets = remember(libVersion) { lib.sets() }
    val selectedSet = if (sets.any { it.id == chosenSet }) chosenSet
    else lib.setOf(activeId).takeIf { id -> sets.any { it.id == id } } ?: sets.first { !it.virtual }.id
    val brushes = remember(libVersion, selectedSet) { lib.brushes(selectedSet) }

    // Suche
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val results = remember(libVersion, query) { lib.search(query) }
    val searching = searchOpen && query.isNotBlank()

    // Dialoge / Menüs
    var addMenu by remember { mutableStateOf(false) }
    var brushMenu by remember { mutableStateOf<Brush?>(null) }
    var setMenu by remember { mutableStateOf<BrushSet?>(null) }
    var renameBrush by remember { mutableStateOf<Brush?>(null) }
    var renameSet by remember { mutableStateOf<BrushSet?>(null) }
    var iconSet by remember { mutableStateOf<BrushSet?>(null) }
    var newSetDialog by remember { mutableStateOf(false) }
    var deleteBrush by remember { mutableStateOf<Brush?>(null) }
    var deleteSet by remember { mutableStateOf<BrushSet?>(null) }
    var copyTarget by remember { mutableStateOf<Brush?>(null) }

    // Ziehen
    var drag by remember { mutableStateOf<DragInfo?>(null) }
    val setBounds = remember { mutableStateMapOf<String, Rect>() }
    var listBounds by remember { mutableStateOf(Rect.Zero) }
    var panelBounds by remember { mutableStateOf(Rect.Zero) }
    val localSets = remember { mutableStateListOf<BrushSet>() }
    val localBrushes = remember { mutableStateListOf<Brush>() }
    if (drag == null) {
        if (localSets.toList() != sets) { localSets.clear(); localSets.addAll(sets) }
        val shown = if (searching) results else brushes
        if (localBrushes.toList() != shown) { localBrushes.clear(); localBrushes.addAll(shown) }
    }
    val listState = rememberLazyListState()
    val setListState = rememberLazyListState()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) state.flash(lib.importUri(uri))
    }

    fun select(b: Brush) {
        if (b.id == activeId) studio = StudioRequest(b, isNew = false)
        else { settings.brushId = b.id; state.toolSettingsVersion++ }
    }

    fun afterDelete(id: String) {
        for ((t, ts) in state.toolSettings) if (ts.brushId == id) ts.brushId = lib.defaultBrushId(t)
        state.toolSettingsVersion++
    }

    // Halten über einem Set öffnet es (nach ~0,6 s)
    val hover = drag?.hoverSet
    LaunchedEffect(hover) {
        val d = drag ?: return@LaunchedEffect
        val h = hover ?: return@LaunchedEffect
        if (d.isSet) return@LaunchedEffect
        delay(600)
        if (drag?.hoverSet == h && h != chosenSet && sets.firstOrNull { it.id == h }?.virtual == false) {
            chosenSet = h
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            localBrushes.clear(); localBrushes.addAll(lib.brushes(h).filter { it.id != d.id })
            d.brush?.let { localBrushes.add(0, it) }
        }
    }
    // Automatisches Scrollen beim Ziehen am Listenrand
    LaunchedEffect(drag) {
        while (drag != null) {
            val d = drag ?: break
            val edge = with(density) { 40.dp.toPx() }
            val target = if (d.isSet) setListState else listState
            val bounds = if (d.isSet) Rect(0f, setBounds.values.minOfOrNull { it.top } ?: 0f, 0f,
                setBounds.values.maxOfOrNull { it.bottom } ?: 0f) else listBounds
            if (bounds.height > 0f) {
                if (d.pos.y < bounds.top + edge) target.scrollBy(-12f)
                else if (d.pos.y > bounds.bottom - edge) target.scrollBy(12f)
            }
            delay(16)
        }
    }

    /** Zielindex in der Pinselliste für eine Zeigerposition (Fenster-/Root-Koordinaten). */
    fun brushIndexAt(y: Float): Int {
        val info = listState.layoutInfo.visibleItemsInfo
        if (info.isEmpty()) return 0
        val rel = y - listBounds.top
        for (it in info) if (rel < it.offset + it.size / 2f) return it.index
        return info.last().index
    }

    fun setIndexAt(y: Float): Int {
        var best = -1
        localSets.forEachIndexed { i, s ->
            val r = setBounds[s.id] ?: return@forEachIndexed
            if (y >= r.top && y < r.bottom) best = i
        }
        return best
    }

    BoxWithConstraints(modifier) {
        val setsW = if (maxWidth >= 440.dp) 150.dp else 118.dp
        PanelCard(Modifier.fillMaxSize().onGloballyPositioned { panelBounds = it.boundsInRoot() }) {
            // ---- Kopfzeile
            Row(
                Modifier.fillMaxWidth().height(50.dp).padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Pinselbibliothek", color = ui.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                HeaderIcon(Icons.Outlined.Search, "Suchen", if (searchOpen) ui.accent else ui.icon) {
                    searchOpen = !searchOpen; if (!searchOpen) query = ""
                }
                Box {
                    HeaderIcon(Icons.Filled.Add, "Hinzufügen", ui.accent) { addMenu = true }
                    DropdownMenu(addMenu, onDismissRequest = { addMenu = false }, containerColor = ui.panelElevated) {
                        MenuRow("Neuer Pinsel") {
                            addMenu = false
                            val target = sets.firstOrNull { it.id == selectedSet && !it.virtual }?.id
                            studio = StudioRequest(lib.newBrush(target), isNew = true)
                        }
                        MenuRow("Neues Set") { addMenu = false; newSetDialog = true }
                        MenuRow("Aus Dateien importieren") { addMenu = false; importLauncher.launch("*/*") }
                        MenuRow("Aus Zwischenablage einfügen") {
                            addMenu = false
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                            val b = text?.let { lib.import(it) }
                            state.flash(if (b != null) "„${b.name}“ importiert" else "Kein Pinsel in der Zwischenablage")
                        }
                    }
                }
            }
            // ---- Suchfeld
            AnimatedVisibility(searchOpen, enter = expandVertically(tween(180)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(140)) + fadeOut(tween(120))) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(10.dp)).background(ui.panelElevated).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Search, null, tint = ui.textMuted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Pinsel suchen", color = ui.textMuted, fontSize = 15.sp)
                        BasicTextField(query, { query = it.take(40) }, singleLine = true,
                            textStyle = TextStyle(color = ui.text, fontSize = 15.sp), cursorBrush = SolidColor(ui.accent),
                            modifier = Modifier.fillMaxWidth())
                    }
                    if (query.isNotEmpty()) Icon(Icons.Outlined.Close, "Leeren", tint = ui.textMuted,
                        modifier = Modifier.size(18.dp).clickable { query = "" })
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(ui.divider))

            Row(Modifier.fillMaxSize()) {
                // ---- Sets
                if (!searching) {
                    LazyColumn(Modifier.width(setsW).fillMaxHeight(), state = setListState,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp)) {
                        items(localSets, key = { it.id }) { s ->
                            val sel = s.id == selectedSet
                            val isDragged = drag?.isSet == true && drag?.id == s.id
                            val isDropTarget = drag?.isSet == false && drag?.hoverSet == s.id
                            SetRow(
                                set = s, selected = sel, lifted = isDragged, dropTarget = isDropTarget,
                                modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null,
                                    placementSpec = spring(stiffness = Spring.StiffnessMediumLow))
                                    .onGloballyPositioned { setBounds[s.id] = it.boundsInRoot() },
                                gestures = object : RowGestures {
                                    override fun onTap() { if (sel && !s.virtual) setMenu = s else chosenSet = s.id }
                                    override fun onLongPress(rootPos: Offset) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        drag = DragInfo(s.id, true, s.id, null).also { it.pos = rootPos }
                                    }
                                    override fun onDrag(rootPos: Offset) {
                                        val d = drag ?: return
                                        d.pos = rootPos; d.moved = true
                                        if (s.virtual) return
                                        val to = setIndexAt(rootPos.y)
                                        val from = localSets.indexOfFirst { it.id == s.id }
                                        if (to >= 0 && from >= 0 && to != from && !localSets[to].virtual) {
                                            localSets.add(to, localSets.removeAt(from))
                                        }
                                    }
                                    override fun onDragEnd(moved: Boolean) {
                                        drag = null
                                        if (!moved) { if (!s.virtual) setMenu = s; return }
                                        lib.reorderSets(localSets.filter { !it.virtual }.map { it.id })
                                    }
                                },
                            )
                        }
                    }
                    Box(Modifier.width(0.5.dp).fillMaxHeight().background(ui.divider))
                }

                // ---- Pinsel
                val pullConnection = remember {
                    object : NestedScrollConnection {
                        var acc = 0f
                        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                            if (available.y > 0f && source == NestedScrollSource.UserInput) {
                                acc += available.y
                                if (acc > 140f && !searchOpen) { searchOpen = true; acc = 0f }
                            } else if (available.y < 0f || consumed.y != 0f) acc = 0f
                            return Offset.Zero
                        }
                    }
                }
                LaunchedEffect(selectedSet) {
                    val i = brushes.indexOfFirst { it.id == activeId }
                    if (i > 0) listState.scrollToItem(i) else listState.scrollToItem(0)
                }
                AnimatedContent(
                    targetState = if (searching) "_search" else selectedSet,
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(90)) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    label = "brushList",
                ) { key ->
                    val inRecent = key == BrushLibrary.SET_RECENT
                    LazyColumn(
                        Modifier.fillMaxSize().nestedScroll(pullConnection).onGloballyPositioned { listBounds = it.boundsInRoot() },
                        state = listState,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
                    ) {
                        if (localBrushes.isEmpty()) item(key = "_empty") {
                            Text(if (searching) "Keine Treffer" else "Keine Pinsel in diesem Set",
                                color = ui.textMuted, fontSize = 13.sp, modifier = Modifier.padding(20.dp))
                        }
                        items(localBrushes, key = { it.id }) { b ->
                            val lifted = drag?.isSet == false && drag?.id == b.id
                            BrushRow(
                                lib = lib, brush = b, selected = b.id == activeId, lifted = lifted,
                                custom = lib.isCustom(b.id), favorite = lib.isFavorite(b.id), pinned = inRecent && lib.isPinned(b.id),
                                subtitle = if (searching) lib.setName(lib.setOf(b.id)) else null,
                                version = libVersion,
                                modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null,
                                    placementSpec = spring(stiffness = Spring.StiffnessMediumLow)),
                                actions = when {
                                    searching -> emptyList()
                                    inRecent -> listOf(
                                        SwipeActionSpec(Icons.Outlined.MyLocation, "Finden", BrushUiColors.neutralAction) {
                                            chosenSet = lib.setOf(b.id)
                                        },
                                        SwipeActionSpec(if (lib.isPinned(b.id)) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                            if (lib.isPinned(b.id)) "Lösen" else "Anheften", BrushUiColors.duplicate) { lib.togglePin(b.id) },
                                        SwipeActionSpec(Icons.Outlined.RemoveCircleOutline, "Entfernen", BrushUiColors.destructive) {
                                            lib.removeRecent(b.id)
                                        },
                                    )
                                    else -> buildList {
                                        add(SwipeActionSpec(Icons.Outlined.Share, "Teilen", BrushUiColors.neutralAction) { lib.share(b.id) })
                                        add(SwipeActionSpec(Icons.Outlined.ContentCopy, "Duplizieren", BrushUiColors.duplicate) {
                                            lib.duplicate(b.id)
                                        })
                                        if (lib.isCustom(b.id)) add(SwipeActionSpec(Icons.Outlined.Delete, "Löschen", BrushUiColors.destructive) {
                                            deleteBrush = b
                                        })
                                    }
                                },
                                gestures = { rowOrigin ->
                                    object : RowGestures {
                                        override fun onTap() = select(b)
                                        override fun onLongPress(rootPos: Offset) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            drag = DragInfo(b.id, false, if (searching) "_search" else selectedSet, b).also { it.pos = rootPos }
                                        }
                                        override fun onDrag(rootPos: Offset) {
                                            val d = drag ?: return
                                            d.pos = rootPos; d.moved = true
                                            d.hoverSet = setBounds.entries.firstOrNull { (id, r) ->
                                                r.contains(rootPos) && localSets.any { it.id == id }
                                            }?.key
                                            if (searching) return
                                            if (d.hoverSet == null && listBounds.contains(rootPos)) {
                                                val to = brushIndexAt(rootPos.y).coerceIn(0, localBrushes.lastIndex)
                                                val from = localBrushes.indexOfFirst { it.id == b.id }
                                                if (from >= 0 && to != from) localBrushes.add(to, localBrushes.removeAt(from))
                                            }
                                        }
                                        override fun onDragEnd(moved: Boolean) {
                                            val d = drag
                                            drag = null
                                            if (d == null) return
                                            if (!moved) { brushMenu = b; return }
                                            val target = d.hoverSet
                                            val targetSet = sets.firstOrNull { it.id == target }
                                            when {
                                                target != null && targetSet != null && !targetSet.virtual -> {
                                                    // Auf Set-Titel abgelegt = kopieren
                                                    if (target != d.originSet || searching) {
                                                        lib.copyBrush(b.id, target)
                                                        state.flash("Kopiert nach „${targetSet.name}“")
                                                    }
                                                }
                                                searching -> {}
                                                listBounds.contains(d.pos) && selectedSet != d.originSet && !sets.first { it.id == selectedSet }.virtual -> {
                                                    // In geöffnetem anderen Set abgelegt = verschieben
                                                    val idx = localBrushes.indexOfFirst { it.id == b.id }
                                                    lib.moveBrush(b.id, selectedSet, idx)
                                                    state.flash("Verschoben nach „${lib.setName(selectedSet)}“")
                                                }
                                                listBounds.contains(d.pos) && selectedSet == d.originSet ->
                                                    lib.reorderBrushes(selectedSet, localBrushes.map { it.id })
                                                else -> {}
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // ---- gezogene Zeile (schwebt über allem)
        drag?.let { d ->
            if (!d.isSet && d.moved && d.brush != null) {
                val w = with(density) { 220.dp.toPx() }
                val h = with(density) { 64.dp.toPx() }
                Box(
                    Modifier
                        .offset { IntOffset((d.pos.x - panelBounds.left - w * 0.5f).roundToInt(), (d.pos.y - panelBounds.top - h * 0.6f).roundToInt()) }
                        .width(220.dp).height(64.dp)
                        .graphicsLayer { scaleX = 1.03f; scaleY = 1.03f; alpha = 0.95f }
                        .shadow(16.dp, RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp)).background(ui.selectedRow)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Column {
                        Text(d.brush.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        PreviewImage(lib, d.brush, Color.White, libVersion, Modifier.fillMaxWidth().height(34.dp))
                    }
                    if (d.hoverSet != null) Text("+", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopEnd))
                }
            }
        }
    }

    // ---- Menüs & Dialoge
    brushMenu?.let { b ->
        val custom = lib.isCustom(b.id)
        BrushContextMenu(
            title = b.name,
            onDismiss = { brushMenu = null },
            entries = buildList {
                add("Umbenennen" to { renameBrush = b })
                add("Bearbeiten" to { studio = StudioRequest(b, false) })
                add((if (lib.isFavorite(b.id)) "Aus Favoriten entfernen" else "Zu Favoriten") to { lib.toggleFavorite(b.id) })
                add((if (lib.isPinned(b.id)) "Lösen" else "In „Zuletzt“ anheften") to { lib.togglePin(b.id) })
                add("Duplizieren" to { lib.duplicate(b.id); Unit })
                add("Kopieren nach …" to { copyTarget = b })
                if (searching) add("Zum Set springen" to { searchOpen = false; query = ""; chosenSet = lib.setOf(b.id) })
                add("Teilen" to { lib.share(b.id) })
                if (custom) add("Löschen" to { deleteBrush = b })
            },
        )
    }
    setMenu?.let { s ->
        BrushContextMenu(
            title = s.name,
            onDismiss = { setMenu = null },
            entries = buildList {
                add("Umbenennen" to { renameSet = s })
                add("Symbol festlegen" to { iconSet = s })
                add("Teilen" to { lib.shareSet(s.id) })
                add("Duplizieren" to { lib.duplicateSet(s.id)?.let { chosenSet = it }; Unit })
                if (s.deletable) add("Löschen" to { deleteSet = s })
            },
        )
    }
    copyTarget?.let { b ->
        BrushContextMenu(
            title = "„${b.name}“ kopieren nach",
            onDismiss = { copyTarget = null },
            entries = sets.filter { !it.virtual }.map { s ->
                s.name to { lib.copyBrush(b.id, s.id); state.flash("Kopiert nach „${s.name}“") }
            },
        )
    }
    renameBrush?.let { b ->
        TextInputDialog("Pinsel umbenennen", b.name, maxLength = 60, onDismiss = { renameBrush = null }) {
            lib.renameBrush(b.id, it); renameBrush = null
        }
    }
    renameSet?.let { s ->
        TextInputDialog("Set umbenennen", s.name, onDismiss = { renameSet = null }) { lib.renameSet(s.id, it); renameSet = null }
    }
    iconSet?.let { s ->
        TextInputDialog("Symbol festlegen", s.icon ?: "", hint = "Ein Emoji oder Zeichen. Leer lassen für das Standardsymbol.",
            maxLength = 8, onDismiss = { iconSet = null }) { lib.setSetIcon(s.id, it); iconSet = null }
    }
    if (newSetDialog) {
        TextInputDialog("Neues Set", "Unbenanntes Set", onDismiss = { newSetDialog = false }) {
            chosenSet = lib.createSet(it); newSetDialog = false
        }
    }
    deleteBrush?.let { b ->
        ConfirmDialog("Pinsel löschen?", "„${b.name}“ wird endgültig gelöscht.", onDismiss = { deleteBrush = null }) {
            if (lib.delete(b.id)) { afterDelete(b.id); state.flash("Pinsel gelöscht") }
            deleteBrush = null
        }
    }
    deleteSet?.let { s ->
        ConfirmDialog("Set löschen?", "„${s.name}“ und alle eigenen Pinsel darin werden gelöscht.", onDismiss = { deleteSet = null }) {
            val ids = lib.brushes(s.id).map { it.id }
            if (lib.deleteSet(s.id)) {
                ids.forEach { if (lib.get(it) == null) afterDelete(it) }
                state.flash("Set gelöscht")
            }
            deleteSet = null
        }
    }

    studio?.let { req ->
        BrushStudio(state, req.brush, req.isNew, onClose = { studio = null })
    }
}

@Composable
private fun HeaderIcon(icon: ImageVector, desc: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/** Kontextmenü als kleines Dialogfenster (zentriert, wie die Popover-Karten gestaltet). */
@Composable
private fun BrushContextMenu(title: String, onDismiss: () -> Unit, entries: List<Pair<String, () -> Unit>>) {
    val ui = LocalUi.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(260.dp).shadow(24.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
                .background(ui.panel).padding(vertical = 6.dp),
        ) {
            Text(title, color = ui.textMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(ui.divider))
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().height((entries.size * 44).coerceAtMost(400).dp)) {
                items(entries) { (label, action) ->
                    MenuRow(label, if (label == "Löschen") BrushUiColors.destructive else null) { onDismiss(); action() }
                }
            }
        }
    }
}

@Composable
private fun SetRow(
    set: BrushSet,
    selected: Boolean,
    lifted: Boolean,
    dropTarget: Boolean,
    modifier: Modifier,
    gestures: RowGestures,
) {
    val ui = LocalUi.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val gRef = rememberUpdatedState(gestures)
    val bg by animateColorAsState(
        when {
            dropTarget -> BrushUiColors.secondarySelection
            selected -> ui.selectedRow
            else -> Color.Transparent
        }, tween(120), label = "setBg",
    )
    val scale by animateFloatAsState(if (lifted) 1.03f else 1f, tween(150), label = "setLift")
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (lifted) Modifier.shadow(10.dp, RoundedCornerShape(10.dp)) else Modifier)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
            .pointerInput(set.id) {
                rowGestures({ origin }, swipe = false, g = object : RowGestures {
                    override fun onTap() = gRef.value.onTap()
                    override fun onLongPress(rootPos: Offset) = gRef.value.onLongPress(rootPos)
                    override fun onDrag(rootPos: Offset) = gRef.value.onDrag(rootPos)
                    override fun onDragEnd(moved: Boolean) = gRef.value.onDragEnd(moved)
                })
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (set.virtual) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(if (selected) Color(0x33FFFFFF) else ui.panelElevated),
                contentAlignment = Alignment.Center) {
                Icon(if (set.id == BrushLibrary.SET_FAVORITES) Icons.Filled.Star else Icons.Outlined.MyLocation, null,
                    tint = if (selected) Color.White else ui.textMuted, modifier = Modifier.size(16.dp))
            }
        } else SetGlyph(set.icon, selected)
        Spacer(Modifier.width(8.dp))
        Text(
            set.name, color = if (selected || dropTarget) Color.White else ui.text, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Eine Wisch-Aktion hinter einer Pinselzeile. */
private class SwipeActionSpec(val icon: ImageVector, val label: String, val color: Color, val action: () -> Unit)

@Composable
private fun PreviewImage(lib: BrushLibrary, brush: Brush, tint: Color, version: Int, modifier: Modifier) {
    val preview by produceState<ImageBitmap?>(
        lib.cachedPreview(brush)?.asImageBitmap(), brush.id, brush.rev, version,
    ) {
        value = withContext(Dispatchers.Default) { lib.preview(brush).asImageBitmap() }
    }
    Box(modifier) {
        preview?.let {
            Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.tint(tint))
        }
    }
}

@Composable
private fun BrushRow(
    lib: BrushLibrary,
    brush: Brush,
    selected: Boolean,
    lifted: Boolean,
    custom: Boolean,
    favorite: Boolean,
    pinned: Boolean,
    subtitle: String?,
    version: Int,
    modifier: Modifier,
    actions: List<SwipeActionSpec>,
    gestures: (origin: () -> Offset) -> RowGestures,
) {
    val ui = LocalUi.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val actionW = 64.dp
    val maxShift = with(density) { (actionW * actions.size).toPx() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val snap = spring<Float>(dampingRatio = 0.85f, stiffness = 600f)
    fun close() { scope.launch { offset.animateTo(0f, snap) } }

    val bg by animateColorAsState(if (selected) ui.selectedRow else Color.Transparent, tween(120), label = "rowBg")
    val scale by animateFloatAsState(if (lifted) 1.03f else 1f, tween(150), label = "rowLift")
    val gRef = rememberUpdatedState(gestures { origin })

    Box(
        modifier.fillMaxWidth().height(if (subtitle != null) 86.dp else 76.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (lifted) 0.4f else 1f },
    ) {
        // Aktionen hinter der Zeile
        if (actions.isNotEmpty() && offset.value < -0.5f) {
            Row(Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 3.dp)) {
                for (a in actions) {
                    Column(
                        Modifier.width(actionW).fillMaxHeight().background(a.color).clickable { close(); a.action() },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(a.icon, a.label, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.height(2.dp))
                        Text(a.label, color = Color.White, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
        }
        // Vordergrund
        Column(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .fillMaxSize()
                .background(ui.panel)
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(bg)
                .onGloballyPositioned { origin = it.boundsInRoot().topLeft - Offset(offset.value, 0f) }
                .pointerInput(brush.id, maxShift) {
                    rowGestures({ origin }, swipe = actions.isNotEmpty(), g = object : RowGestures {
                        override fun onTap() { if (offset.value < -1f) close() else gRef.value.onTap() }
                        override fun onSwipe(dx: Float) {
                            scope.launch { offset.snapTo((offset.value + dx).coerceIn(-maxShift, 0f)) }
                        }
                        override fun onSwipeEnd() {
                            scope.launch { offset.animateTo(if (offset.value < -maxShift / 2.5f) -maxShift else 0f, snap) }
                        }
                        override fun onLongPress(rootPos: Offset) { close(); gRef.value.onLongPress(rootPos) }
                        override fun onDrag(rootPos: Offset) = gRef.value.onDrag(rootPos)
                        override fun onDragEnd(moved: Boolean) = gRef.value.onDragEnd(moved)
                    })
                }
                .padding(start = 12.dp, end = 10.dp, top = 7.dp, bottom = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    brush.name, color = if (selected) Color.White else ui.text, fontSize = 13.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (pinned) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.PushPin, null, tint = if (selected) Color.White else ui.textMuted, modifier = Modifier.size(12.dp))
                }
                if (favorite) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Star, null, tint = Color(0xFFFFC94D), modifier = Modifier.size(12.dp))
                }
                if (custom) {
                    Spacer(Modifier.width(5.dp))
                    Text("eigen", color = if (selected) Color(0xCCFFFFFF) else ui.textMuted, fontSize = 10.sp)
                }
            }
            if (subtitle != null) Text(subtitle, color = if (selected) Color(0xCCFFFFFF) else ui.textMuted, fontSize = 11.sp, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            PreviewImage(lib, brush, if (selected) Color.White else ui.text, version, Modifier.fillMaxWidth().weight(1f))
        }
    }
}
