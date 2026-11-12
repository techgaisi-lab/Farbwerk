package at.techgaisi.farbwerk.ui.layers

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.io.Exporters
import at.techgaisi.farbwerk.model.ArtDocument
import at.techgaisi.farbwerk.model.Layer
import at.techgaisi.farbwerk.model.LayerBlend
import at.techgaisi.farbwerk.model.LayerOps
import at.techgaisi.farbwerk.render.LayerGroup
import at.techgaisi.farbwerk.render.LayerGroups
import at.techgaisi.farbwerk.selection.ClipboardOps
import at.techgaisi.farbwerk.ui.common.PanelCard
import at.techgaisi.farbwerk.ui.theme.LocalUi
import at.techgaisi.farbwerk.ui.theme.UiColors
import at.techgaisi.farbwerk.undo.LambdaAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// =============================================================================================
// Maße und Farben (Richtwerte laut Anleitung §30)
// =============================================================================================

// Maße laut UI_SPEC §5 (Ebenen-Panel)
private val ROW_HEIGHT = 52.dp
private val MASK_ROW_HEIGHT = 46.dp
private val THUMB = 40.dp
private val MASK_THUMB = 32.dp
private val THUMB_RADIUS = 3.dp
private val ROW_RADIUS = 6.dp
/** Innenabstand der Zeilen (links/rechts). */
private val ROW_PAD = 10.dp
/** Abstand der ausgewählten (abgerundeten) Zeilenfläche zum Panelrand. */
private val ROW_INSET = 4.dp
/** Zusätzliche Einrückung von Gruppenmitgliedern. */
private val GROUP_INDENT = 18.dp
private val CHECKBOX = 20.dp
private val ACTION_WIDTH = 66.dp

// Farben laut UI_SPEC §0 (lokal, da nicht alle als gemeinsame Tokens existieren)
/** Primär ausgewählte Ebene: helles Blau-Violett (heller als der Akzent). */
private val PRIMARY_ROW = Color(0xFF5E7CFF)
/** Sekundärauswahl (gedämpftes Blau). */
private val SECONDARY_BLUE = Color(0xFF2E4C8F)
/** Haarlinie zwischen Zeilen: Weiß @ 8 %. */
private val SEPARATOR = Color(0x14FFFFFF)
/** Mischmodus-Buchstabe: Weiß @ 85 %. */
private val BLEND_LETTER = Color(0xD9FFFFFF)
private val LOCK_GREY = Color(0xFF5A5A60)
private val DESTRUCTIVE = Color(0xFFFF453A)

/** Federn: Wischaktionen rasten in ~200 ms ein, ohne sichtbares Überschwingen. */
private fun <T> snapSpring() = spring<T>(dampingRatio = 0.85f, stiffness = 650f)

// =============================================================================================
// Zeilenmodell
// =============================================================================================

/** Eine Zeile der Ebenenliste (von oben nach unten). */
private sealed class RowItem(val key: String) {
    class LayerItem(val layer: Layer, val index: Int, val inGroup: Boolean) : RowItem("l:" + layer.id)
    /** Ebenenmaske als eigene, eingerückte Zeile über ihrer Ebene. */
    class MaskItem(val layer: Layer, val index: Int, val inGroup: Boolean) : RowItem("m:" + layer.id)
    /** [members] von oben nach unten. */
    class GroupItem(val group: LayerGroup, val members: List<Layer>) : RowItem("g:" + group.id)
}

private fun layerRows(rows: MutableList<RowItem>, l: Layer, i: Int, inGroup: Boolean) {
    if (l.mask != null) rows += RowItem.MaskItem(l, i, inGroup)
    rows += RowItem.LayerItem(l, i, inGroup)
}

/** Baut die angezeigte Liste: oberste Ebene zuerst, Gruppen als (einklappbare) Ordner. */
private fun buildRows(doc: ArtDocument): List<RowItem> {
    val rows = ArrayList<RowItem>(doc.layers.size + 4)
    var i = doc.layers.lastIndex
    while (i >= 0) {
        val l = doc.layers[i]
        val gid = l.groupId
        if (gid == null) {
            layerRows(rows, l, i, false)
            i--
            continue
        }
        var j = i
        while (j >= 0 && doc.layers[j].groupId == gid) j--
        val group = LayerGroups.get(doc, gid)
        val members = (i downTo j + 1).map { doc.layers[it] }
        rows += RowItem.GroupItem(group, members)
        if (!group.collapsed) for (k in i downTo j + 1) layerRows(rows, doc.layers[k], k, true)
        i = j
    }
    return rows
}

private data class RowBounds(val top: Float, val bottom: Float) {
    val center get() = (top + bottom) / 2f
    val height get() = bottom - top
}

// =============================================================================================
// Interaktionszustand
// =============================================================================================

/** Hält Gesten- und Menüzustand des Panels. Koordinaten der Zeilen in Root-Koordinaten. */
private class PanelController(val state: EditorState, val scroll: ScrollState) {
    val bounds = HashMap<String, RowBounds>()
    var rows: List<RowItem> = emptyList()
    var containerTop = 0f
    var containerBottom = 0f

    // Wischen
    var revealedKey by mutableStateOf<String?>(null)

    // Umsortieren
    var dragKey by mutableStateOf<String?>(null)
    /** Alle mitgezogenen Zeilen (Gruppenmitglieder, Maskenzeilen). */
    var draggedKeys: Set<String> = emptySet()

    /** Wird die Zeile [key] gerade gezogen (angehoben)? */
    fun isDragged(key: String): Boolean = dragKey != null && key in draggedKeys
    var dragIds: List<String> = emptyList()
    var dragGroupId: String? = null
    var dragBlockHeight = 0f
    var fingerY by mutableFloatStateOf(0f)
    var grab = 0f
    var dropGap by mutableIntStateOf(-1)
    var dropOntoKey by mutableStateOf<String?>(null)

    // Zusammenkneifen (Root-y-Bereich)
    var pinchTop by mutableFloatStateOf(Float.NaN)
    var pinchBottom by mutableFloatStateOf(Float.NaN)

    // Menüs & Dialoge
    var optionsFor by mutableStateOf<String?>(null)
    var maskMenuFor by mutableStateOf<String?>(null)
    var blendFor by mutableStateOf<String?>(null)
    var groupMenuFor by mutableStateOf<String?>(null)
    var layerMenu by mutableStateOf(false)
    var renameLayer by mutableStateOf<String?>(null)
    var renameGroup by mutableStateOf<String?>(null)
    var bgDialog by mutableStateOf(false)

    fun closeTransient() {
        revealedKey = null
    }

    /** Aktuelle Zeile zu einem Schlüssel (Gesten-Callbacks dürfen keine veralteten Zeilen benutzen). */
    private fun fresh(key: String): RowItem? = rows.firstOrNull { it.key == key }

    /** Ebene unter einer Root-y-Position (Maskenzeilen zählen zu ihrer Ebene). */
    fun layerAtRootY(y: Float): Layer? {
        for (r in rows) {
            val b = bounds[r.key] ?: continue
            if (y < b.top || y > b.bottom) continue
            return when (r) {
                is RowItem.LayerItem -> r.layer
                is RowItem.MaskItem -> r.layer
                is RowItem.GroupItem -> null
            }
        }
        return null
    }

    // ---------------- Tippen ----------------

    /** Tippen auf eine Ebenenzeile: primär machen; erneutes Tippen auf die Primärebene → Ebenenoptionen. */
    fun onLayerTap(layer: Layer) {
        if (revealedKey != null) { revealedKey = null; return }
        val doc = state.document ?: return
        val index = doc.indexOf(layer)
        if (index < 0) return
        val editingMask = LayerOps.isEditingMask(doc, layer)
        if (doc.activeIndex == index && doc.secondarySelection.isEmpty() && !editingMask) {
            optionsFor = layer.id
            return
        }
        if (editingMask) LayerOps.setMaskEditing(state, layer, false)
        if (doc.activeIndex != index || doc.secondarySelection.isNotEmpty()) LayerOps.setActive(state, index)
        if (blendFor != layer.id) blendFor = null
    }

    /** Tippen auf die Maskenzeile: Maske primär (bemalbar); erneut → Maskenoptionen. */
    fun onMaskTap(layer: Layer) {
        if (revealedKey != null) { revealedKey = null; return }
        val doc = state.document ?: return
        if (LayerOps.isEditingMask(doc, layer)) { maskMenuFor = layer.id; return }
        LayerOps.setMaskEditing(state, layer, true)
        blendFor = null
    }

    fun onGroupTap(key: String) {
        val item = fresh(key) as? RowItem.GroupItem ?: return
        if (revealedKey != null) { revealedKey = null; return }
        val doc = state.document ?: return
        val activeInGroup = doc.activeLayer?.groupId == item.group.id
        val allSelected = item.members.all { it === doc.activeLayer || it.id in doc.secondarySelection }
        if (activeInGroup && allSelected) groupMenuFor = item.group.id
        else LayerOps.selectGroup(state, item.group.id)
    }

    // ---------------- Umsortieren ----------------

    fun startDrag(key: String, localY: Float) {
        val item = fresh(key) ?: return
        val b = bounds[item.key] ?: return
        revealedKey = null
        blendFor = null
        dragKey = item.key
        val keys = HashSet<String>()
        keys += item.key
        when (item) {
            is RowItem.LayerItem -> {
                dragIds = listOf(item.layer.id); dragGroupId = null
                keys += "m:" + item.layer.id
            }
            is RowItem.GroupItem -> {
                dragIds = item.members.map { it.id }; dragGroupId = item.group.id
                item.members.forEach { keys += "l:" + it.id; keys += "m:" + it.id }
            }
            is RowItem.MaskItem -> { dragKey = null; return }
        }
        draggedKeys = keys
        dragBlockHeight = keys.sumOf { k -> (bounds[k]?.height ?: 0f).toDouble() }.toFloat()
        grab = localY
        fingerY = b.top + localY
        updateTarget()
    }

    fun dragBy(dy: Float) {
        fingerY += dy
        updateTarget()
    }

    /** Zeilen, zwischen die abgelegt werden kann (ohne die gezogenen und ohne Maskenzeilen). */
    private fun candidates(): List<RowItem> = rows.filter {
        it !is RowItem.MaskItem && it.key !in draggedKeys
    }

    fun updateTarget() {
        if (dragKey == null) return
        val cands = candidates()
        dropOntoKey = null
        if (dragGroupId == null) {
            for (r in cands) {
                if (r !is RowItem.LayerItem) continue
                val b = bounds[r.key] ?: continue
                val h = b.bottom - b.top
                if (fingerY > b.top + h * 0.3f && fingerY < b.bottom - h * 0.3f) {
                    dropOntoKey = r.key
                    dropGap = -1
                    return
                }
            }
        }
        dropGap = cands.count { r -> bounds[r.key]?.let { it.center < fingerY } ?: false }
    }

    /** Verschiebung der Zeile [key], damit die übrigen Zeilen dem gezogenen Block ausweichen. */
    fun shiftFor(key: String): Float {
        val dk = dragKey ?: return 0f
        if (key in draggedKeys) return 0f
        val gap = dropGap
        if (gap < 0) return 0f
        // Maskenzeilen bewegen sich mit ihrer Ebene
        val ref = if (key.startsWith("m:")) "l:" + key.substring(2) else key
        val cands = candidates()
        val idx = cands.indexOfFirst { it.key == ref }
        if (idx < 0) return 0f
        val dragTop = bounds[dk]?.top ?: return 0f
        val myTop = bounds[ref]?.top ?: return 0f
        return if (myTop < dragTop) {
            if (idx >= gap) dragBlockHeight else 0f
        } else {
            if (idx < gap) -dragBlockHeight else 0f
        }
    }

    /** Verschiebung der gezogenen Zeilen (folgen dem Finger). */
    fun dragTranslation(): Float {
        val dk = dragKey ?: return 0f
        return fingerY - grab - (bounds[dk]?.top ?: 0f)
    }

    fun endDrag() {
        val key = dragKey ?: return
        val ids = dragIds
        val onto = dropOntoKey
        val gap = dropGap
        val ownGroup = dragGroupId
        val cands = candidates()
        cancelDrag()
        if (onto != null) {
            val target = (rows.firstOrNull { it.key == onto } as? RowItem.LayerItem)?.layer ?: return
            LayerOps.dropOnto(state, ids, target)
            return
        }
        if (gap < 0 || key.isEmpty()) return
        val above = cands.getOrNull(gap - 1)
        val belowRow = cands.getOrNull(gap)
        var anchor: String?
        var below: Boolean
        var group: String?
        when (above) {
            null -> { anchor = null; below = false; group = null }
            is RowItem.GroupItem -> if (above.group.collapsed) {
                anchor = above.members.last().id; below = true; group = null
            } else {
                anchor = above.members.first().id; below = false; group = above.group.id
            }
            is RowItem.LayerItem -> {
                val g = above.layer.groupId
                anchor = above.layer.id; below = true
                group = if (g != null && belowRow is RowItem.LayerItem && belowRow.layer.groupId == g) g else null
            }
            is RowItem.MaskItem -> return
        }
        if (ownGroup != null) {
            // Gruppen werden nicht verschachtelt: landet die Gruppe in einer anderen, kommt sie darüber.
            if (group != null) {
                val doc = state.document ?: return
                anchor = doc.layers.lastOrNull { it.groupId == group }?.id
                below = false
            }
            group = ownGroup
        }
        LayerOps.moveLayers(state, ids, anchor, below, group)
    }

    fun cancelDrag() {
        dragKey = null
        draggedKeys = emptySet()
        dragIds = emptyList()
        dragGroupId = null
        dropGap = -1
        dropOntoKey = null
    }

    // ---------------- Zusammenkneifen ----------------

    fun mergePinched() {
        val top = pinchTop
        val bottom = pinchBottom
        if (top.isNaN() || bottom.isNaN()) return
        val ids = LinkedHashSet<String>()
        for (r in rows) {
            val b = bounds[r.key] ?: continue
            if (b.bottom < top || b.top > bottom) continue
            when (r) {
                is RowItem.LayerItem -> ids += r.layer.id
                is RowItem.MaskItem -> ids += r.layer.id
                is RowItem.GroupItem -> r.members.forEach { ids += it.id }
            }
        }
        if (ids.size >= 2) LayerOps.mergeLayers(state, ids)
    }

    fun inPinch(key: String): Boolean {
        if (pinchTop.isNaN()) return false
        val b = bounds[key] ?: return false
        return !(b.bottom < pinchTop || b.top > pinchBottom)
    }

    // ---------------- Zwei-Finger-Gesten auf einer Ebene ----------------

    /** 2-Finger-Tippen: Ebene primär + Deckkraft-Modus auf der Leinwand. */
    fun twoFingerTap(l: Layer) {
        revealedKey = null
        blendFor = null
        LayerOpacityMode.start(state, l)
    }

    /** 2 Finger nach rechts wischen: Alpha-Sperre umschalten. */
    fun twoFingerSwipeRight(l: Layer) {
        revealedKey = null
        LayerOps.toggleAlphaLock(state, l)
    }

    /** 2 Finger halten: Ebeneninhalt auswählen. */
    fun twoFingerHold(l: Layer) {
        revealedKey = null
        LayerOps.selectLayerContents(state, l)
    }
}

// =============================================================================================
// Panel
// =============================================================================================

/** Popover-Panel "Ebenen". [modifier] enthält bereits Größe/Position. */
@Composable
fun LayersPanel(state: EditorState, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val doc = state.document
    val rev = state.revision
    val undoVersion = state.undo.version
    val scroll = rememberScrollState()
    val c = remember(state) { PanelController(state, scroll) }
    val thumbs = remember { LayerThumbnailCache() }
    val density = LocalDensity.current

    if (doc == null) {
        PanelCard(modifier, title = "Ebenen") { Text("Kein Dokument", color = ui.textMuted, modifier = Modifier.padding(16.dp)) }
        return
    }

    // Miniaturen gedrosselt aktualisieren
    LaunchedEffect(rev, undoVersion, doc) {
        val wait = LayerThumbnailCache.THROTTLE_MS - (SystemClock.uptimeMillis() - thumbs.lastRefresh)
        if (wait > 0) delay(wait)
        thumbs.refresh(doc)
    }

    // Automatisches Scrollen beim Ziehen an den Rand
    val dragging = c.dragKey != null
    LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        val edge = with(density) { 48.dp.toPx() }
        val maxSpeed = with(density) { 14.dp.toPx() }
        while (c.dragKey != null) {
            withFrameNanos { }
            val y = c.fingerY
            val dy = when {
                y < c.containerTop + edge -> -maxSpeed * ((c.containerTop + edge - y) / edge).coerceIn(0f, 1f)
                y > c.containerBottom - edge -> maxSpeed * ((y - (c.containerBottom - edge)) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (dy != 0f) {
                scroll.scrollBy(dy)
                c.updateTarget()
            }
        }
    }

    val rows = buildRows(doc)
    c.rows = rows
    val keys = rows.map { it.key }.toSet()
    c.bounds.keys.retainAll(keys + "bg")

    // Panel-Farbe (transluzent #2A2A2C @ 96 %) kommt aus dem gemeinsamen Theme (ui.panel)
    PanelCard(modifier) {
        LayersHeader(c, doc, rev)

        // ---------- Liste ----------
        Box(
            Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .onGloballyPositioned {
                    c.containerTop = it.positionInRoot().y
                    c.containerBottom = c.containerTop + it.size.height
                    panelLeftPx = it.positionInWindow().x.roundToInt()
                }
                .pointerInput(Unit) { twoFingerGestures(c) }
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(scroll, enabled = !dragging)) {
                for (item in rows) {
                    when (item) {
                        is RowItem.LayerItem -> {
                            LayerRow(c, item, doc, thumbs, rev)
                            AnimatedVisibility(
                                visible = c.blendFor == item.layer.id,
                                enter = expandVertically(tween(180)) + fadeIn(tween(180)),
                                exit = shrinkVertically(tween(140)) + fadeOut(tween(120)),
                            ) { BlendBar(state, item.layer, rev) }
                        }
                        is RowItem.MaskItem -> MaskRow(c, item, doc, thumbs, rev)
                        is RowItem.GroupItem -> GroupRow(c, item, doc, thumbs, rev)
                    }
                    RowSeparator()
                }
                BackgroundRow(c, doc, rev)
            }
        }
    }

    // ---------- Dialoge ----------
    c.renameLayer?.let { id ->
        val l = LayerOps.layerById(doc, id)
        if (l == null) c.renameLayer = null else RenameDialog("Ebene umbenennen", l.name, { c.renameLayer = null }) {
            LayerOps.rename(state, l, it); c.renameLayer = null
        }
    }
    c.renameGroup?.let { gid ->
        val g = LayerGroups.get(doc, gid)
        RenameDialog("Gruppe umbenennen", g.name, { c.renameGroup = null }) {
            LayerOps.renameGroup(state, gid, it); c.renameGroup = null
        }
    }
    if (c.bgDialog) {
        ColorPickerDialog(doc.backgroundColor, "Hintergrundfarbe", onDismiss = { c.bgDialog = false }) {
            LayerOps.setBackgroundColor(state, it)
            if (!doc.backgroundVisible) LayerOps.toggleBackgroundVisible(state)
            c.bgDialog = false
        }
    }
}

/** Kopfzeile: Titel (antippbar → Ebenenmenü) und „+“. */
@Composable
private fun LayersHeader(c: PanelController, doc: ArtDocument, rev: Int) {
    val ui = LocalUi.current
    val state = c.state
    // Titel 22 sp / 700, links 20 dp; Titelzeile 52 dp; „+“ 22 dp dünn, rechts 18 dp (UI_SPEC §3)
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(start = 12.dp, end = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Row(
                Modifier
                    .clip(RoundedCornerShape(ROW_RADIUS))
                    .clickable { c.closeTransient(); c.layerMenu = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Ebenen", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                val n = LayerOps.selectedLayers(doc).size
                if (n > 1) {
                    Spacer(Modifier.width(8.dp))
                    Text("$n markiert", color = ui.textMuted, fontSize = 13.sp)
                }
            }
            if (c.layerMenu) LayerMenu(c, doc) { c.layerMenu = false }
        }
        Spacer(Modifier.weight(1f))
        // Trefferfläche 44 dp, Symbol 22 dp mittig → sichtbarer rechter Abstand 7 + 11 = 18 dp
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(ROW_RADIUS)).clickable {
                c.closeTransient(); LayerOps.addLayer(state)
            }.semantics { contentDescription = "Neue Ebene" },
            contentAlignment = Alignment.Center,
        ) { ThinPlus(Modifier.size(22.dp), Color.White) }
    }
}

/** Dünnes „+“ (Strich 1.5 dp), selbst gezeichnet. */
@Composable
private fun ThinPlus(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val s = 1.5.dp.toPx()
        val m = size.minDimension / 2f
        drawLine(color, Offset(center.x - m, center.y), Offset(center.x + m, center.y), s, StrokeCap.Round)
        drawLine(color, Offset(center.x, center.y - m), Offset(center.x, center.y + m), s, StrokeCap.Round)
    }
}

/** Haarlinie zwischen zwei Zeilen (0.5 dp, Weiß @ 8 %). */
@Composable
private fun RowSeparator() {
    val ui = LocalUi.current
    val c = if (ui.text.luminance() > 0.5f) SEPARATOR else ui.divider
    HorizontalDivider(color = c, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = ROW_PAD))
}

/**
 * Hintergrund einer Zeile: ausgewählte Zeilen erhalten eine abgerundete Fläche (6 dp, leicht eingerückt),
 * unausgewählte Zeilen bleiben durchsichtig – nur während des Wischens deckend, damit die Aktionen
 * dahinter nicht durchscheinen.
 */
private fun Modifier.rowBackground(color: Color?, covering: Boolean, panel: Color): Modifier =
    if (color != null) this.padding(horizontal = ROW_INSET).clip(RoundedCornerShape(ROW_RADIUS)).background(color)
    else if (covering) this.background(panel.copy(alpha = 1f)).padding(horizontal = ROW_INSET)
    else this.padding(horizontal = ROW_INSET)

/**
 * Zwei-Finger-Gesten über der Liste. Läuft im Initial-Pass, damit die Zeilen-Gesten (Tippen, Wischen,
 * Ziehen, Scrollen) abgebrochen werden, sobald zwei Finger liegen:
 *  - Zusammenkneifen (vertikaler Abstand < 50 %) → Ebenen zusammenfügen
 *  - Tippen (kurz, ohne Bewegung) → Deckkraft-Modus
 *  - nach rechts wischen → Alpha-Sperre
 *  - halten (~0,5 s, ohne Bewegung) → Inhalt auswählen
 */
private suspend fun PointerInputScope.twoFingerGestures(c: PanelController) {
    val minStart = 24.dp.toPx()
    val slop = 10.dp.toPx()
    val swipeDist = 48.dp.toPx()
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var startDist = Float.NaN
        var startTime = 0L
        var startX = 0f
        var startY = 0f
        var spanTop = 0f
        var spanBottom = 0f
        var target: Layer? = null
        var maxMove = 0f
        var pinchActive = false
        var pinch = false
        var fired = false // Wischen/Halten ausgelöst
        var everTwo = false
        var downCount = 1
        while (true) {
            val holdLeft = if (everTwo && !fired && !pinchActive) HOLD_MS - (SystemClock.uptimeMillis() - startTime) else Long.MAX_VALUE
            val ev = if (holdLeft != Long.MAX_VALUE) {
                if (holdLeft <= 0) null else withTimeoutOrNull(holdLeft) { awaitPointerEvent(PointerEventPass.Initial) }
            } else awaitPointerEvent(PointerEventPass.Initial)
            if (ev == null) {
                // Halten abgelaufen
                if (maxMove < slop && downCount >= 2) target?.let { c.twoFingerHold(it) }
                fired = true
                continue
            }
            if (everTwo) ev.changes.forEach { it.consume() }
            val pressed = ev.changes.filter { it.pressed }
            downCount = pressed.size
            if (pressed.isEmpty()) break
            if (pressed.size >= 2 && c.dragKey == null) {
                val p0 = pressed[0].position
                val p1 = pressed[1].position
                val cx = (p0.x + p1.x) / 2f
                val cy = (p0.y + p1.y) / 2f
                if (!everTwo) {
                    everTwo = true
                    startDist = abs(p1.y - p0.y)
                    startTime = SystemClock.uptimeMillis()
                    startX = cx; startY = cy
                    spanTop = min(p0.y, p1.y); spanBottom = max(p0.y, p1.y)
                    target = c.layerAtRootY(c.containerTop + cy)
                    c.revealedKey = null
                    ev.changes.forEach { it.consume() }
                    continue
                }
                val dx = cx - startX
                val dy = cy - startY
                val d = abs(p1.y - p0.y)
                maxMove = max(maxMove, max(abs(dx), abs(dy)))
                maxMove = max(maxMove, abs(d - startDist))
                // Zusammenkneifen
                if (!fired && startDist > minStart && d < startDist * 0.85f) {
                    pinchActive = true
                    // Bereich: alle Zeilen zwischen den Fingern beim Aufsetzen
                    c.pinchTop = c.containerTop + spanTop
                    c.pinchBottom = c.containerTop + spanBottom
                }
                if (pinchActive) {
                    pinch = d < startDist * 0.5f
                }
                // Nach rechts wischen
                if (!fired && !pinchActive && dx > swipeDist && abs(dx) > 2f * abs(dy)) {
                    fired = true
                    target?.let { c.twoFingerSwipeRight(it) }
                }
            }
        }
        if (everTwo && !fired && !pinchActive && maxMove < slop &&
            SystemClock.uptimeMillis() - startTime < TAP_MS
        ) {
            target?.let { c.twoFingerTap(it) }
        }
        if (pinch) c.mergePinched()
        c.pinchTop = Float.NaN
        c.pinchBottom = Float.NaN
    }
}

private const val HOLD_MS = 550L
private const val TAP_MS = 400L

// =============================================================================================
// Zeilen
// =============================================================================================

/**
 * Gemeinsame Hülle: Position merken, Gesten anhängen, Ziehen und Ausweichen darstellen.
 */
@Composable
private fun rowShell(c: PanelController, key: String, height: androidx.compose.ui.unit.Dp, gestures: Modifier): Modifier {
    val dragged = key in c.draggedKeys && c.dragKey != null
    val anim = remember(key) { Animatable(0f) }
    val target = c.shiftFor(key)
    val active = c.dragKey != null
    LaunchedEffect(target, active) {
        if (active) anim.animateTo(target, tween(150)) else anim.snapTo(0f)
    }
    val density = LocalDensity.current
    return Modifier
        .fillMaxWidth()
        .height(height)
        .onGloballyPositioned {
            val t = it.positionInRoot().y
            c.bounds[key] = RowBounds(t, t + it.size.height)
        }
        .zIndex(if (dragged) 2f else 0f)
        // Gesten VOR der Verschiebung: sonst bewegt sich die Zeile mit dem Finger und es entsteht keine Ziehdistanz
        .then(gestures)
        .graphicsLayer {
            if (dragged) {
                translationY = c.dragTranslation()
                shadowElevation = with(density) { 12.dp.toPx() }
                scaleX = 1.03f; scaleY = 1.03f
                alpha = 0.96f
            } else {
                translationY = anim.value
                shadowElevation = 0f; scaleX = 1f; scaleY = 1f; alpha = 1f
            }
        }
}

/** Horizontale Wischverschiebung einer Zeile mit Einrasten (Feder). */
@Composable
private fun swipeModifier(
    c: PanelController, key: String, revealWidth: Float, onRight: (() -> Unit)?,
    offset: Animatable<Float, *>,
): Modifier {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val threshold = with(density) { 56.dp.toPx() }
    val rightLimit = with(density) { 90.dp.toPx() }
    var swiping by remember(key) { mutableStateOf(false) }
    val revealed = c.revealedKey == key
    LaunchedEffect(revealed) {
        if (!swiping) offset.animateTo(if (revealed) -revealWidth else 0f, snapSpring())
    }
    return Modifier.pointerInput(key, revealWidth) {
        detectHorizontalDragGestures(
            onDragStart = { swiping = true },
            onDragEnd = {
                swiping = false
                val v = offset.value
                val wasRevealed = c.revealedKey == key
                val reveal = when {
                    v > threshold && onRight != null && !wasRevealed -> { onRight(); false }
                    wasRevealed -> v < -revealWidth + threshold
                    else -> v < -threshold && revealWidth > 0f
                }
                c.revealedKey = if (reveal) key else if (c.revealedKey == key) null else c.revealedKey
                scope.launch { offset.animateTo(if (reveal) -revealWidth else 0f, snapSpring()) }
            },
            onDragCancel = {
                swiping = false
                scope.launch { offset.animateTo(if (c.revealedKey == key) -revealWidth else 0f, snapSpring()) }
            },
        ) { change, amount ->
            change.consume()
            if (c.revealedKey != null && c.revealedKey != key) c.revealedKey = null
            val nv = (offset.value + amount).coerceIn(-revealWidth - rightLimit * 0.5f, if (onRight != null) rightLimit else 0f)
            scope.launch { offset.snapTo(nv) }
        }
    }
}

@Composable
private fun LayerRow(c: PanelController, item: RowItem.LayerItem, doc: ArtDocument, thumbs: LayerThumbnailCache, rev: Int) {
    val ui = LocalUi.current
    val state = c.state
    val l = item.layer
    val density = LocalDensity.current
    val isActive = item.index == doc.activeIndex
    val maskEditing = isActive && LayerOps.isEditingMask(doc, l)
    val isSecondary = l.id in doc.secondarySelection || maskEditing
    val isPrimary = isActive && !maskEditing
    val actionsPx = with(density) { (ACTION_WIDTH * 3).toPx() }
    val offset = remember(l.id) { Animatable(0f) }
    val pinched = c.inPinch(item.key)
    val onto = c.dropOntoKey == item.key
    val groupVisible = LayerGroups.isVisible(doc, l.groupId)
    val solo = LayerOps.soloLayerId(doc) == l.id

    val bg: Color? = when {
        isPrimary -> PRIMARY_ROW
        isSecondary -> SECONDARY_BLUE
        pinched -> PRIMARY_ROW.copy(alpha = 0.25f)
        else -> null
    }
    val selected = isPrimary || isSecondary
    val fg = if (selected) Color.White else ui.text
    val fgMuted = if (selected) Color.White.copy(alpha = 0.75f) else ui.textMuted

    Box(
        rowShell(c, item.key, ROW_HEIGHT, Modifier
            .pointerInput(l.id) { detectTapGestures(onTap = { c.onLayerTap(l) }) }
            .then(swipeModifier(c, item.key, actionsPx, onRight = { LayerOps.toggleSecondary(state, l) }, offset))
            .pointerInput(l.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { c.startDrag(item.key, it.y) },
                    onDragEnd = { c.endDrag() },
                    onDragCancel = { c.cancelDrag() },
                ) { change, amount -> change.consume(); c.dragBy(amount.y) }
            })
    ) {
        // Deckend, solange gewischt oder (angehoben) gezogen wird
        val swiping = offset.value != 0f || c.revealedKey == item.key || c.isDragged(item.key)
        // Aktionen hinter der Zeile (nach links wischen)
        if (offset.value < 0f || c.revealedKey == item.key) {
            Row(Modifier.align(Alignment.CenterEnd).fillMaxHeight()) {
                SwipeAction(if (l.locked) "Entsperren" else "Sperren", if (l.locked) Icons.Filled.LockOpen else Icons.Filled.Lock, LOCK_GREY) {
                    LayerOps.toggleLocked(state, l); c.revealedKey = null
                }
                SwipeAction("Duplizieren", Icons.Outlined.ContentCopy, ui.accent) {
                    LayerOps.setActive(state, item.index); LayerOps.duplicateActiveLayer(state); c.revealedKey = null
                }
                SwipeAction("Löschen", Icons.Outlined.Delete, DESTRUCTIVE) {
                    c.revealedKey = null
                    LayerOps.deleteLayers(state, listOf(l.id))
                }
            }
        }

        // Inhalt
        Row(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offset.value }
                .rowBackground(bg, swiping, ui.panel)
                .then(if (onto) Modifier.border(2.dp, ui.accent, RoundedCornerShape(ROW_RADIUS)) else Modifier)
                .alpha(if (groupVisible) 1f else 0.5f)
                .padding(start = if (item.inGroup) ROW_PAD - ROW_INSET + GROUP_INDENT else ROW_PAD - ROW_INSET, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (l.clipping) {
                // Schnittmaske: kleiner Pfeil nach unten vor der Miniatur
                Icon(Icons.Filled.ArrowDownward, "Schnittmaske", tint = fgMuted,
                    modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
            }
            // Miniatur 40 × 40 dp mit Schachbrett (Transparenz)
            Box(Modifier.size(THUMB)) {
                val thumbShape = RoundedCornerShape(THUMB_RADIUS)
                ThumbnailImage(
                    thumbs.image(l.id), thumbs.version, doc.width.toFloat() / doc.height,
                    Modifier
                        .size(THUMB)
                        .clip(thumbShape)
                        .border(0.5.dp, if (l.alphaLock) fg else SEPARATOR, thumbShape),
                    checker = true,
                )
                if (l.text != null) {
                    // Textebene: „A“-Markierung
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(2.dp).size(13.dp)
                            .clip(RoundedCornerShape(3.dp)).background(Color(0xCC000000)),
                        contentAlignment = Alignment.Center,
                    ) { Text("A", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                }
                if (c.optionsFor == l.id) SideMenu(onDismiss = { c.optionsFor = null }) { LayerOptionsContent(c, l, doc) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(l.name, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val flags = buildList {
                    if (l.locked) add("Gesperrt")
                    if (l.alphaLock) add("Alpha-Sperre")
                    if (l.isReference) add("Referenz")
                    if (l.assisted) add("Assistiert")
                    if (l.isPrivate) add("Privat")
                    if (solo) add("Solo")
                }
                if (flags.isNotEmpty()) {
                    Text(flags.joinToString(" · "), color = fgMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (l.locked) {
                Icon(Icons.Filled.Lock, null, tint = fgMuted, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(2.dp))
            }
            // Mischmodus-Buchstabe (14 sp, Weiß @ 85 %) – antippen öffnet Deckkraft/Mischmodus
            Box(
                Modifier
                    .size(width = 30.dp, height = 40.dp)
                    .clip(RoundedCornerShape(ROW_RADIUS))
                    .clickable { c.revealedKey = null; c.blendFor = if (c.blendFor == l.id) null else l.id },
                contentAlignment = Alignment.Center,
            ) {
                Text(l.blend.short, color = if (selected) Color.White else blendLetterColor(ui), fontSize = 14.sp,
                    fontWeight = FontWeight.Normal)
            }
            VisibilityBox(l.visible, onLongPress = { LayerOps.toggleSolo(state, l) }) { LayerOps.toggleVisible(state, l) }
        }
    }
}

/** Farbe des Mischmodus-Buchstabens / Gruppenpfeils (dunkel: Weiß @ 85 %). */
private fun blendLetterColor(ui: UiColors): Color = if (ui.text.luminance() > 0.5f) BLEND_LETTER else ui.text.copy(alpha = 0.85f)

/** Maskenzeile: eingerückt über ihrer Ebene, wählbar (dann bemalbar) und mit eigenen Optionen. */
@Composable
private fun MaskRow(c: PanelController, item: RowItem.MaskItem, doc: ArtDocument, thumbs: LayerThumbnailCache, rev: Int) {
    val ui = LocalUi.current
    val state = c.state
    val l = item.layer
    val density = LocalDensity.current
    val isActive = item.index == doc.activeIndex
    val editing = isActive && LayerOps.isEditingMask(doc, l)
    val coupled = isActive && !editing
    val actionsPx = with(density) { (ACTION_WIDTH * 2).toPx() }
    val offset = remember("m" + l.id) { Animatable(0f) }
    val bg: Color? = when {
        editing -> PRIMARY_ROW
        coupled -> SECONDARY_BLUE
        else -> null
    }
    val selected = editing || coupled
    val fg = if (selected) Color.White else ui.text
    val fgMuted = if (selected) Color.White.copy(alpha = 0.75f) else ui.textMuted

    Box(
        rowShell(c, item.key, MASK_ROW_HEIGHT, Modifier
            .pointerInput(l.id) { detectTapGestures(onTap = { c.onMaskTap(l) }) }
            .then(swipeModifier(c, item.key, actionsPx, onRight = null, offset)))
    ) {
        // Deckend, solange gewischt oder (angehoben) gezogen wird
        val swiping = offset.value != 0f || c.revealedKey == item.key || c.isDragged(item.key)
        if (offset.value < 0f || c.revealedKey == item.key) {
            Row(Modifier.align(Alignment.CenterEnd).fillMaxHeight()) {
                SwipeAction(if (l.locked) "Entsperren" else "Sperren", if (l.locked) Icons.Filled.LockOpen else Icons.Filled.Lock, LOCK_GREY) {
                    LayerOps.toggleLocked(state, l); c.revealedKey = null
                }
                SwipeAction("Löschen", Icons.Outlined.Delete, DESTRUCTIVE) {
                    c.revealedKey = null
                    LayerOps.removeMask(state, l)
                }
            }
        }
        Row(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offset.value }
                .rowBackground(bg, swiping, ui.panel)
                // Maske sitzt eingerückt (eine Miniaturbreite tiefer) über ihrer Ebene
                .padding(start = ROW_PAD - ROW_INSET + (if (item.inGroup) GROUP_INDENT else 0.dp) + 16.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(MASK_THUMB)) {
                val shape = RoundedCornerShape(THUMB_RADIUS)
                ThumbnailImage(
                    thumbs.maskImage(l.id), thumbs.version, doc.width.toFloat() / doc.height,
                    Modifier.size(MASK_THUMB).clip(shape).border(0.5.dp, if (editing) Color.White else SEPARATOR, shape),
                    checker = false,
                )
                if (c.maskMenuFor == l.id) SideMenu(onDismiss = { c.maskMenuFor = null }) { MaskOptionsContent(c, l) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Ebenenmaske", color = fg, fontSize = 14.sp, maxLines = 1)
                if (editing) Text("Malen bearbeitet die Maske", color = fgMuted, fontSize = 11.sp, maxLines = 1)
            }
            if (l.locked) Icon(Icons.Filled.Lock, null, tint = fgMuted, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(12.dp))
        }
    }
}

/**
 * Gruppenzeile wie eine Ebenenzeile: Miniatur (Mitglieder übereinandergelegt), Name, rechts „>“-Pfeil statt
 * Mischmodus-Buchstabe (antippen = auf-/zuklappen, ausgeklappt zeigt er nach unten) und Sichtbarkeits-Kästchen.
 * Antippen der Miniatur/Zeile wählt die Gruppe bzw. öffnet die Gruppenoptionen.
 */
@Composable
private fun GroupRow(c: PanelController, item: RowItem.GroupItem, doc: ArtDocument, thumbs: LayerThumbnailCache, rev: Int) {
    val ui = LocalUi.current
    val state = c.state
    val g = item.group
    val activeInGroup = doc.activeLayer?.groupId == g.id
    val allSelected = activeInGroup && item.members.all { it === doc.activeLayer || it.id in doc.secondarySelection }
    val pinched = c.inPinch(item.key)
    val bg: Color? = when {
        allSelected -> PRIMARY_ROW
        activeInGroup -> SECONDARY_BLUE
        pinched -> PRIMARY_ROW.copy(alpha = 0.25f)
        else -> null
    }
    val selected = allSelected || activeInGroup
    val fg = if (selected) Color.White else ui.text
    val fgMuted = if (selected) Color.White.copy(alpha = 0.75f) else ui.textMuted
    Box(
        rowShell(c, item.key, ROW_HEIGHT, Modifier
            .pointerInput(g.id) { detectTapGestures(onTap = { c.onGroupTap(item.key) }) }
            .pointerInput(g.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { c.startDrag(item.key, it.y) },
                    onDragEnd = { c.endDrag() },
                    onDragCancel = { c.cancelDrag() },
                ) { change, amount -> change.consume(); c.dragBy(amount.y) }
            })
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .rowBackground(bg, c.isDragged(item.key), ui.panel)
                .alpha(if (g.visible) 1f else 0.5f)
                .padding(start = ROW_PAD - ROW_INSET, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Miniatur: Inhalte der Mitglieder (unten → oben) auf Schachbrett
            Box(Modifier.size(THUMB)) {
                val shape = RoundedCornerShape(THUMB_RADIUS)
                val images = item.members.asReversed().filter { it.visible }.map { thumbs.image(it.id) }
                StackedThumbnail(
                    images, thumbs.version, doc.width.toFloat() / doc.height,
                    Modifier.size(THUMB).clip(shape).border(0.5.dp, SEPARATOR, shape),
                )
                if (c.groupMenuFor == g.id) SideMenu(onDismiss = { c.groupMenuFor = null }) { GroupOptionsContent(c, item) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(g.name, color = fg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val n = item.members.size
                Text(
                    (if (n == 1) "1 Ebene" else "$n Ebenen") + if (g.opacity < 1f) " · ${(g.opacity * 100).roundToInt()} %" else "",
                    color = fgMuted, fontSize = 11.sp, maxLines = 1,
                )
            }
            // „>“-Pfeil statt Mischmodus: auf-/zuklappen (dreht sich 150 ms)
            Box(
                Modifier.size(width = 30.dp, height = 40.dp).clip(RoundedCornerShape(ROW_RADIUS))
                    .clickable { c.revealedKey = null; LayerOps.toggleGroupCollapsed(state, g.id) }
                    .semantics { contentDescription = if (g.collapsed) "Ausklappen" else "Einklappen" },
                contentAlignment = Alignment.Center,
            ) {
                val rot = remember { Animatable(if (g.collapsed) 0f else 90f) }
                LaunchedEffect(g.collapsed) { rot.animateTo(if (g.collapsed) 0f else 90f, tween(150)) }
                Chevron(
                    Modifier.size(12.dp).graphicsLayer { rotationZ = rot.value },
                    if (selected) Color.White else blendLetterColor(ui),
                )
            }
            VisibilityBox(g.visible) { LayerOps.toggleGroupVisible(state, g.id) }
        }
    }
}

/** Selbst gezeichneter „>“-Pfeil (Strich 1.6 dp). */
@Composable
private fun Chevron(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val s = 1.6.dp.toPx()
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w * 0.32f, h * 0.12f)
            lineTo(w * 0.72f, h * 0.5f)
            lineTo(w * 0.32f, h * 0.88f)
        }
        drawPath(p, color, style = Stroke(width = s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Unterste Zeile „Hintergrundfarbe“: Farbfläche als Miniatur, antippen = Farbwahl. */
@Composable
private fun BackgroundRow(c: PanelController, doc: ArtDocument, rev: Int) {
    val ui = LocalUi.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clickable { c.closeTransient(); c.bgDialog = true }
            .padding(start = ROW_PAD, end = ROW_INSET + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(THUMB_RADIUS)
        Box(
            Modifier
                .size(THUMB)
                .clip(shape)
                .background(Color(doc.backgroundColor))
                .border(0.5.dp, SEPARATOR, shape)
        )
        Spacer(Modifier.width(12.dp))
        Text("Hintergrundfarbe", color = ui.text, fontSize = 14.sp, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        VisibilityBox(doc.backgroundVisible) { LayerOps.toggleBackgroundVisible(c.state) }
    }
}

// =============================================================================================
// Bausteine
// =============================================================================================

/**
 * Sichtbarkeits-Kästchen 20 × 20 dp, Radius 4 dp, 1.2 dp weißer Rand; angehakt = weißes Häkchen auf weißer
 * Kontur (keine Füllung, kein Blau). Trefferfläche 40 dp. Halten = [onLongPress] (Solo-Sichtbarkeit).
 * Das Häkchen blendet in 150 ms ein/aus.
 */
@Composable
private fun VisibilityBox(checked: Boolean, onLongPress: (() -> Unit)? = null, onToggle: () -> Unit) {
    val ui = LocalUi.current
    val stroke = if (ui.text.luminance() > 0.5f) Color.White else ui.text
    val progress by animateFloatAsState(if (checked) 1f else 0f, tween(150), label = "check")
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(ROW_RADIUS))
            .pointerInput(onLongPress != null) {
                detectTapGestures(
                    onTap = { onToggle() },
                    onLongPress = if (onLongPress != null) { _ -> onLongPress() } else null,
                )
            }
            .semantics {
                contentDescription = if (checked) "Sichtbar" else "Ausgeblendet"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(CHECKBOX)) {
            val bw = 1.2.dp.toPx()
            val r = 4.dp.toPx()
            drawRoundRect(
                stroke.copy(alpha = 0.6f + 0.4f * progress),
                topLeft = Offset(bw / 2f, bw / 2f),
                size = Size(size.width - bw, size.height - bw),
                cornerRadius = CornerRadius(r, r),
                style = Stroke(bw),
            )
            if (progress > 0f) {
                val w = size.width
                val h = size.height
                val p = Path().apply {
                    moveTo(w * 0.26f, h * 0.52f)
                    lineTo(w * 0.43f, h * 0.68f)
                    lineTo(w * 0.75f, h * 0.33f)
                }
                drawPath(p, stroke.copy(alpha = progress),
                    style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
private fun SwipeAction(label: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Column(
        Modifier.width(ACTION_WIDTH).fillMaxHeight().background(color).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1)
    }
}

// =============================================================================================
// Mischmodus-Leiste
// =============================================================================================

/** Modi in der Reihenfolge des Vorbilds, Gruppen durch Trennlinien getrennt. */
private val BLEND_GROUPS: List<List<LayerBlend>> = listOf(
    listOf(LayerBlend.NORMAL),
    listOf(LayerBlend.MULTIPLY, LayerBlend.DARKEN, LayerBlend.SHADE, LayerBlend.COLOR_BURN, LayerBlend.LINEAR_BURN, LayerBlend.DARKER_COLOR),
    listOf(LayerBlend.LIGHTEN, LayerBlend.SCREEN, LayerBlend.COLOR_DODGE, LayerBlend.ADD, LayerBlend.LIGHTER_COLOR),
    listOf(LayerBlend.OVERLAY, LayerBlend.SOFT_LIGHT, LayerBlend.HARD_LIGHT, LayerBlend.VIVID_LIGHT, LayerBlend.LINEAR_LIGHT, LayerBlend.PIN_LIGHT, LayerBlend.HARD_MIX),
    listOf(LayerBlend.DIFFERENCE, LayerBlend.EXCLUSION, LayerBlend.SUBTRACT, LayerBlend.DIVIDE),
    listOf(LayerBlend.HUE, LayerBlend.SATURATION, LayerBlend.COLOR, LayerBlend.LUMINOSITY),
)

private val BLEND_ITEM_HEIGHT = 40.dp

/**
 * Ausklapp-Leiste unter der Zeile: Deckkraftregler und Name des Modus. Tippen auf den Namen öffnet die
 * scrollbare Liste; jeder angetippte Modus wird sofort live auf der Leinwand gezeigt. Beim Schließen der
 * Leiste entsteht ein einziger Undo-Eintrag für den gewählten Modus.
 */
@Composable
private fun BlendBar(state: EditorState, l: Layer, rev: Int) {
    val ui = LocalUi.current
    var dragStart by remember(l.id) { mutableStateOf<Float?>(null) }
    var live by remember(l.id) { mutableFloatStateOf(l.opacity) }
    var listOpen by remember(l.id) { mutableStateOf(false) }
    val startBlend = remember(l.id) { l.blend }
    val shown = if (dragStart == null) l.opacity else live

    // Gewählten Modus beim Schließen als einen Undo-Schritt festschreiben
    DisposableEffect(l.id) {
        onDispose {
            val end = l.blend
            if (end != startBlend) {
                state.undo.push(LambdaAction("Mischmodus", { l.blend = startBlend }, { l.blend = end }))
                state.invalidate()
            }
        }
    }

    Column(Modifier.fillMaxWidth().background(ui.panelElevated).padding(horizontal = 14.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${(shown * 100).roundToInt()} %", color = ui.text, fontSize = 13.sp, modifier = Modifier.width(44.dp))
            Slider(
                value = shown,
                onValueChange = {
                    if (dragStart == null) dragStart = l.opacity
                    live = it
                    LayerOps.setOpacityLive(state, l, it)
                },
                onValueChangeFinished = {
                    dragStart?.let { LayerOps.commitOpacity(state, l, it) }
                    dragStart = null
                },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = ui.accent),
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { listOpen = !listOpen }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(l.blend.label, color = ui.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(l.blend.short, color = ui.textMuted, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.KeyboardArrowDown, null, tint = ui.icon,
                modifier = Modifier.graphicsLayer { rotationZ = if (listOpen) 180f else 0f })
        }
        AnimatedVisibility(
            visible = listOpen,
            enter = expandVertically(tween(180)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(140)) + fadeOut(tween(120)),
        ) {
            val listScroll = rememberScrollState()
            val itemPx = with(LocalDensity.current) { BLEND_ITEM_HEIGHT.toPx() }
            LaunchedEffect(Unit) {
                // aktuellen Modus in die Mitte scrollen
                var pos = 0
                loop@ for (g in BLEND_GROUPS) for (b in g) { if (b == l.blend) break@loop; pos++ }
                listScroll.scrollTo(((pos - 2) * itemPx).roundToInt().coerceAtLeast(0))
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ui.panel)
                    .verticalScroll(listScroll)
            ) {
                BLEND_GROUPS.forEachIndexed { gi, group ->
                    if (gi > 0) HorizontalDivider(color = ui.divider, thickness = 0.5.dp)
                    for (b in group) {
                        val sel = b == l.blend
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(BLEND_ITEM_HEIGHT)
                                .clickable {
                                    // Live-Vorschau: sofort anwenden, Undo beim Schließen
                                    l.blend = b
                                    state.invalidate()
                                }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(b.label, color = if (sel) ui.accent else ui.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            if (sel) Icon(Icons.Filled.Check, null, tint = ui.accent, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================================
// Seitliche Menüs (Ebenen-, Masken-, Gruppenoptionen)
// =============================================================================================

/** Positioniert ein Menü links neben der Ankerzeile (bzw. rechts, wenn links kein Platz ist). */
private class SideMenuPosition(private val gap: Int, private val margin: Int, private val rowLeft: () -> Int) :
    PopupPositionProvider {
    var placedLeft = true
    override fun calculatePosition(
        anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
    ): IntOffset {
        val left = rowLeft()
        var x = left - popupContentSize.width - gap
        placedLeft = x >= margin
        if (!placedLeft) x = (anchorBounds.right + gap).coerceAtMost(windowSize.width - popupContentSize.width - margin)
        val cy = anchorBounds.top + anchorBounds.height / 2
        val y = (cy - popupContentSize.height / 2)
            .coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin))
        return IntOffset(x, y)
    }
}

/** Linker Rand des Ebenen-Panels in Fensterkoordinaten (für die Menüposition). */
private var panelLeftPx = 0

/**
 * Menü, das seitlich neben der Zeile einblendet (~180 ms Gleiten + Einblenden). Tippen außerhalb schließt.
 */
@Composable
private fun SideMenu(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val ui = LocalUi.current
    val density = LocalDensity.current
    val provider = remember {
        SideMenuPosition(
            with(density) { 10.dp.roundToPx() }, with(density) { 8.dp.roundToPx() },
        ) { panelLeftPx }
    }
    val visible = remember { MutableTransitionState(false) }.apply { targetState = true }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(
            visibleState = visible,
            enter = slideInHorizontally(tween(180)) { w -> if (provider.placedLeft) w / 5 else -w / 5 } +
                fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.96f),
        ) {
            Column(
                Modifier
                    .width(232.dp)
                    .shadow(24.dp, RoundedCornerShape(13.dp), ambientColor = Color.Black.copy(alpha = 0.4f), spotColor = Color.Black.copy(alpha = 0.4f))
                    .clip(RoundedCornerShape(13.dp))
                    .background(ui.panelElevated)
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun MenuItem(
    label: String, onClick: () -> Unit, checked: Boolean? = null, enabled: Boolean = true, destructive: Boolean = false,
) {
    val ui = LocalUi.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = when { !enabled -> ui.textMuted.copy(alpha = 0.6f); destructive -> DESTRUCTIVE; else -> ui.text },
            fontSize = 15.sp, modifier = Modifier.weight(1f),
        )
        if (checked != null) {
            Box(
                Modifier.size(20.dp).clip(RoundedCornerShape(5.dp))
                    .background(if (checked) ui.accent else Color.Transparent)
                    .border(1.5.dp, if (checked) ui.accent else ui.textMuted, RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center,
            ) { if (checked) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
        }
    }
}

@Composable
private fun MenuDivider() {
    HorizontalDivider(color = LocalUi.current.divider, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 2.dp))
}

/** Ebenenoptionen (Primärebene erneut antippen). */
@Composable
private fun ColumnScope.LayerOptionsContent(c: PanelController, l: Layer, doc: ArtDocument) {
    val state = c.state
    val idx = doc.indexOf(l)
    fun run(action: () -> Unit) { c.optionsFor = null; action() }
    Text(l.name, color = LocalUi.current.textMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    MenuItem("Umbenennen", { run { c.renameLayer = l.id } })
    MenuItem("Auswählen", { run { LayerOps.selectLayerContents(state, l) } })
    MenuItem("Kopieren", { run { ClipboardOps.copy(state) } })
    MenuItem("Ebene füllen", { run { LayerOps.fillLayer(state, l) } })
    MenuItem("Löschen", { run { LayerOps.clearActiveLayer(state) } })
    MenuDivider()
    MenuItem("Alpha-Sperre", { run { LayerOps.toggleAlphaLock(state, l) } }, checked = l.alphaLock)
    MenuItem("Maske", { run { LayerOps.addMask(state, l) } }, enabled = l.mask == null)
    MenuItem("Schnittmaske", { run { LayerOps.toggleClipping(state, l) } }, checked = l.clipping, enabled = idx > 0 || l.clipping)
    MenuItem("Zeichenassistent", { run { LayerOps.toggleAssisted(state, l) } }, checked = l.assisted)
    MenuDivider()
    MenuItem("Umkehren", { run { LayerOps.invert(state, l) } })
    MenuItem("Referenz", { run { LayerOps.setReference(state, l) } }, checked = l.isReference)
    MenuDivider()
    MenuItem("Nach unten zusammenfügen", { run { LayerOps.mergeDown(state) } }, enabled = idx > 0)
    MenuItem("Nach unten kombinieren", { run { LayerOps.combineDown(state) } }, enabled = idx > 0)
}

/** Optionen der Ebenenmaske (Maskenzeile erneut antippen). */
@Composable
private fun ColumnScope.MaskOptionsContent(c: PanelController, l: Layer) {
    val state = c.state
    fun run(action: () -> Unit) { c.maskMenuFor = null; action() }
    MenuItem("Umkehren", { run { LayerOps.invertMask(state, l) } })
    MenuItem("Anwenden", { run { LayerOps.applyMask(state, l) } })
    MenuItem("Auswählen", { run { LayerOps.selectLayerContents(state, l) } })
    MenuDivider()
    MenuItem("Löschen", { run { LayerOps.removeMask(state, l) } }, destructive = true)
}

/** Gruppenoptionen (Tippen auf eine bereits ausgewählte Gruppe). */
@Composable
private fun ColumnScope.GroupOptionsContent(c: PanelController, item: RowItem.GroupItem) {
    val ui = LocalUi.current
    val state = c.state
    val g = item.group
    fun run(action: () -> Unit) { c.groupMenuFor = null; action() }
    var start by remember(g.id) { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("Deckkraft ${(g.opacity * 100).roundToInt()} %", color = ui.textMuted, fontSize = 12.sp)
        Slider(
            value = g.opacity,
            onValueChange = {
                if (start == null) start = g.opacity
                LayerOps.setGroupOpacityLive(state, g.id, it)
            },
            onValueChangeFinished = { start?.let { LayerOps.commitGroupOpacity(state, g.id, it) }; start = null },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = ui.accent),
        )
    }
    MenuDivider()
    MenuItem("Umbenennen", { run { c.renameGroup = g.id } })
    MenuItem("Zusammenfügen", { run { LayerOps.mergeGroup(state, g.id) } })
    MenuItem("Gruppierung aufheben", { run { LayerOps.ungroup(state, g.id) } })
    MenuDivider()
    MenuItem("Gruppe löschen", { run { LayerOps.deleteGroup(state, g.id) } }, destructive = true)
}

/**
 * Ebenenmenü (Titel „Ebenen“ antippen): wirkt auf alle markierten Ebenen.
 */
@Composable
private fun LayerMenu(c: PanelController, doc: ArtDocument, onDismiss: () -> Unit) {
    val ui = LocalUi.current
    val state = c.state
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selected = LayerOps.selectedLayers(doc)
    val multi = selected.size > 1
    val density = LocalDensity.current
    val visible = remember { MutableTransitionState(false) }.apply { targetState = true }
    fun run(action: () -> Unit) { onDismiss(); action() }
    Popup(
        offset = IntOffset(0, with(density) { 40.dp.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(visibleState = visible, enter = fadeIn(tween(160)) + scaleIn(tween(180), initialScale = 0.94f)) {
            Column(
                Modifier
                    .width(232.dp)
                    .shadow(24.dp, RoundedCornerShape(13.dp))
                    .clip(RoundedCornerShape(13.dp))
                    .background(ui.panelElevated)
                    .padding(vertical = 4.dp),
            ) {
                if (doc.secondarySelection.size < doc.layers.size - 1) MenuItem("Alle auswählen", { run { LayerOps.selectAll(state) } })
                if (doc.secondarySelection.isNotEmpty()) MenuItem("Auswahl aufheben", { run { LayerOps.deselectAll(state) } })
                MenuDivider()
                MenuItem("Gruppieren", { run { LayerOps.groupSelected(state) } })
                MenuItem("Zusammenfügen", { run { LayerOps.mergeSelected(state) } }, enabled = multi)
                MenuItem("Duplizieren", { run { LayerOps.duplicateActiveLayer(state) } })
                MenuItem("Teilen", {
                    run {
                        val layers = selected.toList()
                        state.flash("Ebenen werden vorbereitet …")
                        scope.launch {
                            try {
                                val files = withContext(Dispatchers.IO) { LayerOps.exportLayersPng(context, doc, layers) }
                                Exporters.shareMultiple(context, files, "image/png")
                            } catch (t: Throwable) {
                                android.util.Log.e("Farbwerk", "Ebenen teilen", t)
                                state.flash("Teilen fehlgeschlagen")
                            }
                        }
                    }
                })
                MenuDivider()
                MenuItem("Löschen", { run { LayerOps.deleteActiveLayer(state) } }, destructive = true)
            }
        }
    }
}

@Composable
private fun RenameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val ui = LocalUi.current
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ui.panelElevated,
        titleContentColor = ui.text,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("OK", color = ui.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen", color = ui.textMuted) } },
    )
}
