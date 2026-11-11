package at.techgaisi.farbwerk.ui.layers

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.ArtDocument
import at.techgaisi.farbwerk.model.Layer
import at.techgaisi.farbwerk.model.LayerBlend
import at.techgaisi.farbwerk.model.LayerOps
import at.techgaisi.farbwerk.render.LayerGroup
import at.techgaisi.farbwerk.render.LayerGroups
import at.techgaisi.farbwerk.selection.ClipboardOps
import at.techgaisi.farbwerk.ui.common.PanelCard
import at.techgaisi.farbwerk.ui.theme.LocalUi
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// =============================================================================================
// Zeilenmodell
// =============================================================================================

private val ROW_HEIGHT = 56.dp
private val ACTION_WIDTH = 60.dp

/** Eine Zeile der Ebenenliste (von oben nach unten). */
private sealed class RowItem(val key: String) {
    class LayerItem(val layer: Layer, val index: Int, val inGroup: Boolean) : RowItem("l:" + layer.id)
    /** [members] von oben nach unten. */
    class GroupItem(val group: LayerGroup, val members: List<Layer>) : RowItem("g:" + group.id)
}

/** Baut die angezeigte Liste: oberste Ebene zuerst, Gruppen als (einklappbare) Ordner. */
private fun buildRows(doc: ArtDocument): List<RowItem> {
    val rows = ArrayList<RowItem>(doc.layers.size + 4)
    var i = doc.layers.lastIndex
    while (i >= 0) {
        val l = doc.layers[i]
        val gid = l.groupId
        if (gid == null) {
            rows += RowItem.LayerItem(l, i, false)
            i--
            continue
        }
        var j = i
        while (j >= 0 && doc.layers[j].groupId == gid) j--
        val group = LayerGroups.get(doc, gid)
        val members = (i downTo j + 1).map { doc.layers[it] }
        rows += RowItem.GroupItem(group, members)
        if (!group.collapsed) for (k in i downTo j + 1) rows += RowItem.LayerItem(doc.layers[k], k, true)
        i = j
    }
    return rows
}

private data class RowBounds(val top: Float, val bottom: Float) {
    val center get() = (top + bottom) / 2f
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
    var dragIds: List<String> = emptyList()
    var dragGroupId: String? = null
    var fingerY by mutableFloatStateOf(0f)
    var grab = 0f
    var dropGap by mutableIntStateOf(-1)
    var dropOntoKey by mutableStateOf<String?>(null)

    // Zusammenkneifen (Root-y-Bereich)
    var pinchTop by mutableFloatStateOf(Float.NaN)
    var pinchBottom by mutableFloatStateOf(Float.NaN)

    // Menüs & Dialoge
    var optionsFor by mutableStateOf<String?>(null)
    var blendFor by mutableStateOf<String?>(null)
    var groupMenuFor by mutableStateOf<String?>(null)
    var renameLayer by mutableStateOf<String?>(null)
    var renameGroup by mutableStateOf<String?>(null)
    var bgDialog by mutableStateOf(false)

    fun closeTransient() {
        revealedKey = null
    }

    // ---------------- Tippen ----------------

    /** Aktuelle Zeile zu einem Schlüssel (Gesten-Callbacks dürfen keine veralteten Zeilen benutzen). */
    private fun fresh(key: String): RowItem? = rows.firstOrNull { it.key == key }

    fun onLayerTap(layer: Layer) {
        if (revealedKey != null) { revealedKey = null; return }
        val doc = state.document ?: return
        val index = doc.indexOf(layer)
        if (index < 0) return
        if (doc.activeIndex != index || doc.secondarySelection.isNotEmpty()) {
            LayerOps.setActive(state, index)
            blendFor = null
        }
    }

    fun onThumbTap(layer: Layer) {
        if (revealedKey != null) { revealedKey = null; return }
        val doc = state.document ?: return
        val index = doc.indexOf(layer)
        if (index < 0) return
        if (doc.activeIndex == index) optionsFor = layer.id
        else { LayerOps.setActive(state, index); blendFor = null }
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
        when (item) {
            is RowItem.LayerItem -> { dragIds = listOf(item.layer.id); dragGroupId = null }
            is RowItem.GroupItem -> { dragIds = item.members.map { it.id }; dragGroupId = item.group.id }
        }
        grab = localY
        fingerY = b.top + localY
        updateTarget()
    }

    fun dragBy(dy: Float) {
        fingerY += dy
        updateTarget()
    }

    /** Zeilen, zwischen die abgelegt werden kann (ohne die gezogenen). */
    private fun candidates(): List<RowItem> = rows.filter {
        it.key != dragKey && !(it is RowItem.LayerItem && it.layer.id in dragIds)
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

    // Einfügemarkierung: welche Kandidatenzeile bekommt oben/unten eine Linie?
    val cands = if (dragging) rows.filter { it.key != c.dragKey && !(it is RowItem.LayerItem && it.layer.id in c.dragIds) } else emptyList()
    val lineTopKey = if (dragging && c.dropGap in cands.indices) cands[c.dropGap].key else null
    val lineBottomKey = if (dragging && c.dropGap == cands.size && cands.isNotEmpty()) cands.last().key else null

    PanelCard(modifier) {
        // ---------- Kopfzeile ----------
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Ebenen", color = ui.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (doc.secondarySelection.isNotEmpty()) {
                TextButton(onClick = { LayerOps.groupSelected(state) }) { Text("Gruppieren", color = ui.accent, fontSize = 14.sp) }
                TextButton(onClick = { LayerOps.deleteActiveLayer(state) }) { Text("Löschen", color = Color(0xFFFF5A52), fontSize = 14.sp) }
            }
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).clickable {
                    c.closeTransient(); LayerOps.addLayer(state)
                },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Add, "Neue Ebene", tint = ui.icon, modifier = Modifier.size(28.dp)) }
        }
        HorizontalDivider(color = ui.divider, thickness = 0.5.dp)

        // ---------- Liste ----------
        Box(
            Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .onGloballyPositioned {
                    c.containerTop = it.positionInRoot().y
                    c.containerBottom = c.containerTop + it.size.height
                }
                .pointerInput(Unit) { pinchGesture(c) }
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(scroll, enabled = !dragging)) {
                for (item in rows) {
                    when (item) {
                        is RowItem.LayerItem -> {
                            LayerRow(
                                c, item, doc, thumbs, rev,
                                lineTop = item.key == lineTopKey, lineBottom = item.key == lineBottomKey,
                            )
                            if (c.blendFor == item.layer.id) BlendBar(state, item.layer, rev)
                        }
                        is RowItem.GroupItem -> GroupRow(
                            c, item, doc, rev,
                            lineTop = item.key == lineTopKey, lineBottom = item.key == lineBottomKey,
                        )
                    }
                    HorizontalDivider(color = ui.divider, thickness = 0.5.dp)
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

/**
 * Zwei-Finger-Zusammenkneifen über mehreren Zeilen. Läuft im Initial-Pass, damit die Zeilen-Gesten
 * (Tippen, Wischen, Ziehen, Scrollen) abgebrochen werden, sobald zwei Finger liegen.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.pinchGesture(c: PanelController) {
    val minStart = 24.dp.toPx()
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var startDist = Float.NaN
        var triggered = false
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = ev.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size >= 2 && c.dragKey == null) {
                val y0 = pressed[0].position.y
                val y1 = pressed[1].position.y
                if (startDist.isNaN()) {
                    startDist = abs(y1 - y0)
                    c.pinchTop = c.containerTop + min(y0, y1)
                    c.pinchBottom = c.containerTop + max(y0, y1)
                    c.revealedKey = null
                }
                ev.changes.forEach { it.consume() }
                val d = abs(y1 - y0)
                triggered = startDist > minStart && d < startDist * 0.5f
            }
        }
        if (triggered) c.mergePinched()
        c.pinchTop = Float.NaN
        c.pinchBottom = Float.NaN
    }
}

// =============================================================================================
// Zeilen
// =============================================================================================

/**
 * Gemeinsame Hülle: Position merken, Gesten anhängen, Ziehen darstellen, Einfügelinie zeichnen.
 */
@Composable
private fun rowShell(
    c: PanelController, key: String, lineTop: Boolean, lineBottom: Boolean, accent: Color, gestures: Modifier,
): Modifier {
    val dragging = c.dragKey == key
    val lineW = with(LocalDensity.current) { 3.dp.toPx() }
    return Modifier
        .fillMaxWidth()
        .height(ROW_HEIGHT)
        .onGloballyPositioned {
            val t = it.positionInRoot().y
            c.bounds[key] = RowBounds(t, t + it.size.height)
        }
        .zIndex(if (dragging) 2f else 0f)
        // Gesten VOR der Verschiebung: sonst bewegt sich die Zeile mit dem Finger und es entsteht keine Ziehdistanz
        .then(gestures)
        .graphicsLayer {
            if (c.dragKey == key) {
                translationY = c.fingerY - c.grab - (c.bounds[key]?.top ?: 0f)
                shadowElevation = 16f
                scaleX = 1.02f; scaleY = 1.02f
                alpha = 0.95f
            } else {
                translationY = 0f; shadowElevation = 0f; scaleX = 1f; scaleY = 1f; alpha = 1f
            }
        }
        .drawWithContent {
            drawContent()
            if (lineTop) drawLine(accent, Offset(0f, lineW / 2), Offset(size.width, lineW / 2), lineW)
            if (lineBottom) drawLine(accent, Offset(0f, size.height - lineW / 2), Offset(size.width, size.height - lineW / 2), lineW)
        }
}

@Composable
private fun LayerRow(
    c: PanelController, item: RowItem.LayerItem, doc: ArtDocument, thumbs: LayerThumbnailCache, rev: Int,
    lineTop: Boolean, lineBottom: Boolean,
) {
    val ui = LocalUi.current
    val state = c.state
    val l = item.layer
    val density = LocalDensity.current
    val isActive = item.index == doc.activeIndex
    val isSecondary = l.id in doc.secondarySelection
    val actionsPx = with(density) { (ACTION_WIDTH * 3).toPx() }
    val swipeLimit = with(density) { 90.dp.toPx() }
    val threshold = with(density) { 56.dp.toPx() }
    var swipe by remember(l.id) { mutableFloatStateOf(0f) }
    val revealed = c.revealedKey == item.key
    val baseX by animateFloatAsState(if (revealed) -actionsPx else 0f, label = "swipe")
    val pinched = c.inPinch(item.key)
    val onto = c.dropOntoKey == item.key
    val groupVisible = LayerGroups.isVisible(doc, l.groupId)

    val bg = when {
        isActive -> ui.selectedRow
        isSecondary -> ui.selectedRow.copy(alpha = 0.45f)
        pinched -> ui.accent.copy(alpha = 0.25f)
        else -> ui.panel
    }
    val fg = if (isActive) Color.White else ui.text
    val fgMuted = if (isActive) Color.White.copy(alpha = 0.75f) else ui.textMuted

    Box(
        rowShell(c, item.key, lineTop, lineBottom, ui.accent, Modifier
            .pointerInput(l.id) { detectTapGestures(onTap = { c.onLayerTap(l) }) }
            .pointerInput(l.id) {
                detectHorizontalDragGestures(
                    onDragStart = { swipe = 0f },
                    onDragEnd = {
                        val isRevealed = c.revealedKey == item.key
                        when {
                            swipe > threshold -> if (isRevealed) c.revealedKey = null else LayerOps.toggleSecondary(state, l)
                            swipe < -threshold -> c.revealedKey = item.key
                            else -> {}
                        }
                        swipe = 0f
                    },
                    onDragCancel = { swipe = 0f },
                ) { change, amount ->
                    change.consume()
                    swipe = (swipe + amount).coerceIn(-actionsPx - swipeLimit, swipeLimit)
                }
            }
            .pointerInput(l.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { c.startDrag(item.key, it.y) },
                    onDragEnd = { c.endDrag() },
                    onDragCancel = { c.cancelDrag() },
                ) { change, amount -> change.consume(); c.dragBy(amount.y) }
            })
    ) {
        // Aktionen hinter der Zeile (nach links wischen)
        if (revealed || swipe < 0f || baseX < 0f) {
            Row(Modifier.align(Alignment.CenterEnd).fillMaxHeight()) {
                SwipeAction(if (l.locked) "Entsperren" else "Sperren", if (l.locked) Icons.Filled.LockOpen else Icons.Filled.Lock, Color(0xFF5A5A66)) {
                    LayerOps.toggleLocked(state, l); c.revealedKey = null
                }
                SwipeAction("Duplizieren", Icons.Outlined.ContentCopy, Color(0xFF3D6FD8)) {
                    LayerOps.setActive(state, item.index); LayerOps.duplicateActiveLayer(state); c.revealedKey = null
                }
                SwipeAction("Löschen", Icons.Outlined.Delete, Color(0xFFE5483F)) {
                    c.revealedKey = null
                    LayerOps.deleteLayers(state, listOf(l.id))
                }
            }
        }

        // Inhalt
        Row(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = baseX + swipe }
                .background(bg)
                .then(if (onto) Modifier.border(2.dp, ui.accent) else Modifier)
                .alpha(if (groupVisible) 1f else 0.5f)
                .padding(start = if (item.inGroup) 22.dp else 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (l.clipping) {
                Icon(Icons.Filled.ArrowDownward, "Schnittmaske", tint = fgMuted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
            }
            // Miniatur (Tippen auf aktive → Ebenenoptionen)
            Box {
                ThumbnailImage(
                    thumbs.image(l.id), thumbs.version, doc.width.toFloat() / doc.height,
                    Modifier
                        .size(width = 52.dp, height = 42.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .border(if (isActive) 1.5.dp else 0.5.dp, if (isActive) Color.White else ui.divider, RoundedCornerShape(4.dp))
                        .pointerInput(l.id) { detectTapGestures(onTap = { c.onThumbTap(l) }) }
                )
                LayerOptionsMenu(c, l, doc, expanded = c.optionsFor == l.id)
            }
            thumbs.maskImage(l.id)?.let { img ->
                Spacer(Modifier.width(4.dp))
                ThumbnailImage(
                    img, thumbs.version, doc.width.toFloat() / doc.height,
                    Modifier.size(width = 30.dp, height = 42.dp).clip(RoundedCornerShape(3.dp))
                        .border(0.5.dp, ui.divider, RoundedCornerShape(3.dp)),
                    checker = false,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(l.name, color = fg, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val flags = buildList {
                    if (l.locked) add("Gesperrt")
                    if (l.alphaLock) add("α-Sperre")
                    if (l.isReference) add("Referenz")
                    if (l.mask != null) add("Maske")
                    if (l.opacity < 1f) add("${(l.opacity * 100).roundToInt()} %")
                }
                if (flags.isNotEmpty()) {
                    Text(flags.joinToString(" · "), color = fgMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (l.locked) {
                Icon(Icons.Filled.Lock, null, tint = fgMuted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            // Mischmodus-Kürzel
            Box(
                Modifier
                    .size(width = 36.dp, height = 36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { c.revealedKey = null; c.blendFor = if (c.blendFor == l.id) null else l.id },
                contentAlignment = Alignment.Center,
            ) {
                Text(l.blend.short, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            VisibilityBox(l.visible, fg) { LayerOps.toggleVisible(state, l) }
        }
    }
}

@Composable
private fun GroupRow(
    c: PanelController, item: RowItem.GroupItem, doc: ArtDocument, rev: Int,
    lineTop: Boolean, lineBottom: Boolean,
) {
    val ui = LocalUi.current
    val state = c.state
    val g = item.group
    val selected = doc.activeLayer?.groupId == g.id
    val pinched = c.inPinch(item.key)
    val bg = when {
        selected -> ui.selectedRow.copy(alpha = 0.3f)
        pinched -> ui.accent.copy(alpha = 0.25f)
        else -> ui.panelElevated
    }
    Box(
        rowShell(c, item.key, lineTop, lineBottom, ui.accent, Modifier
            .pointerInput(g.id) { detectTapGestures(onTap = { c.onGroupTap(item.key) }) }
            .pointerInput(g.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { c.startDrag(item.key, it.y) },
                    onDragEnd = { c.endDrag() },
                    onDragCancel = { c.cancelDrag() },
                ) { change, amount -> change.consume(); c.dragBy(amount.y) }
            }).background(bg)
    ) {
        Row(
            Modifier.fillMaxSize().alpha(if (g.visible) 1f else 0.5f).padding(start = 2.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).clickable { LayerOps.toggleGroupCollapsed(state, g.id) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (g.collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                    if (g.collapsed) "Ausklappen" else "Einklappen", tint = ui.icon,
                )
            }
            Box {
                Icon(Icons.Outlined.Folder, null, tint = ui.icon, modifier = Modifier.size(26.dp))
                GroupMenu(c, item, expanded = c.groupMenuFor == g.id)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(g.name, color = ui.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val n = item.members.size
                Text(
                    (if (n == 1) "1 Ebene" else "$n Ebenen") + if (g.opacity < 1f) " · ${(g.opacity * 100).roundToInt()} %" else "",
                    color = ui.textMuted, fontSize = 11.sp,
                )
            }
            VisibilityBox(g.visible, ui.text) { LayerOps.toggleGroupVisible(state, g.id) }
        }
    }
}

@Composable
private fun BackgroundRow(c: PanelController, doc: ArtDocument, rev: Int) {
    val ui = LocalUi.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clickable { c.closeTransient(); c.bgDialog = true }
            .padding(start = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 52.dp, height = 42.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(doc.backgroundColor))
                .border(0.5.dp, ui.divider, RoundedCornerShape(4.dp))
        )
        Spacer(Modifier.width(10.dp))
        Text("Hintergrundfarbe", color = ui.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        VisibilityBox(doc.backgroundVisible, ui.text) { LayerOps.toggleBackgroundVisible(c.state) }
    }
}

// =============================================================================================
// Bausteine
// =============================================================================================

/** Sichtbarkeits-Kästchen. */
@Composable
private fun VisibilityBox(checked: Boolean, tint: Color, onToggle: () -> Unit) {
    val ui = LocalUi.current
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (checked) ui.accent else Color.Transparent)
                .border(1.5.dp, if (checked) ui.accent else tint.copy(alpha = 0.6f), RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Filled.Check, "Sichtbar", tint = Color.White, modifier = Modifier.size(16.dp))
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
        Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1)
    }
}

/** Leiste mit Deckkraftregler und Mischmodus-Liste (Tippen auf das Kürzel). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlendBar(state: EditorState, l: Layer, rev: Int) {
    val ui = LocalUi.current
    var dragStart by remember(l.id) { mutableStateOf<Float?>(null) }
    var live by remember(l.id) { mutableFloatStateOf(l.opacity) }
    val shown = if (dragStart == null) l.opacity else live
    Column(Modifier.fillMaxWidth().background(ui.panelElevated).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Deckkraft", color = ui.textMuted, fontSize = 12.sp)
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
                colors = SliderDefaults.colors(thumbColor = ui.accent, activeTrackColor = ui.accent),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text("${(shown * 100).roundToInt()} %", color = ui.text, fontSize = 13.sp, modifier = Modifier.width(44.dp))
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (b in LayerBlend.entries) {
                val sel = b == l.blend
                Text(
                    b.label,
                    color = if (sel) Color.White else ui.text,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (sel) ui.accent else ui.panel)
                        .border(0.5.dp, ui.divider, RoundedCornerShape(12.dp))
                        .clickable { LayerOps.setBlend(state, l, b) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

/** Ebenenoptionen (Tippen auf die Miniatur der aktiven Ebene). */
@Composable
private fun LayerOptionsMenu(c: PanelController, l: Layer, doc: ArtDocument, expanded: Boolean) {
    val ui = LocalUi.current
    val state = c.state
    val close = { c.optionsFor = null }
    DropdownMenu(expanded = expanded, onDismissRequest = close, containerColor = ui.panelElevated) {
        val idx = doc.indexOf(l)
        OptionItem(ui, close, "Umbenennen") { c.renameLayer = l.id }
        OptionItem(ui, close, "Auswählen") { LayerOps.selectLayerContents(state, l) }
        OptionItem(ui, close, "Kopieren") { ClipboardOps.copy(state) }
        OptionItem(ui, close, "Ebene füllen") { LayerOps.fillLayer(state, l) }
        OptionItem(ui, close, "Löschen") { LayerOps.clearActiveLayer(state) }
        HorizontalDivider(color = ui.divider)
        OptionItem(ui, close, "Alpha-Sperre", checked = l.alphaLock) { LayerOps.toggleAlphaLock(state, l) }
        if (l.mask == null) OptionItem(ui, close, "Maske") { LayerOps.addMask(state, l) }
        else {
            OptionItem(ui, close, "Maske anwenden") { LayerOps.applyMask(state, l) }
            OptionItem(ui, close, "Maske entfernen") { LayerOps.removeMask(state, l) }
        }
        OptionItem(ui, close, "Schnittmaske", checked = l.clipping, enabled = idx > 0) { LayerOps.toggleClipping(state, l) }
        OptionItem(ui, close, "Umkehren") { LayerOps.invert(state, l) }
        OptionItem(ui, close, "Referenz", checked = l.isReference) { LayerOps.setReference(state, l) }
        HorizontalDivider(color = ui.divider)
        OptionItem(ui, close, "Nach unten zusammenfügen", enabled = idx > 0) { LayerOps.mergeDown(state) }
        OptionItem(ui, close, "Nach unten kombinieren", enabled = idx > 0) { LayerOps.combineDown(state) }
        if (doc.secondarySelection.isNotEmpty()) OptionItem(ui, close, "Auswahl gruppieren") { LayerOps.groupSelected(state) }
    }
}

@Composable
private fun OptionItem(
    ui: at.techgaisi.farbwerk.ui.theme.UiColors, close: () -> Unit, label: String,
    checked: Boolean? = null, enabled: Boolean = true, action: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label, color = if (enabled) ui.text else ui.textMuted, fontSize = 15.sp) },
        trailingIcon = if (checked == true) {
            { Icon(Icons.Filled.Check, null, tint = ui.accent, modifier = Modifier.size(18.dp)) }
        } else null,
        enabled = enabled,
        onClick = { close(); action() },
        modifier = Modifier.height(42.dp),
    )
}

/** Gruppenoptionen (Tippen auf eine bereits ausgewählte Gruppe). */
@Composable
private fun GroupMenu(c: PanelController, item: RowItem.GroupItem, expanded: Boolean) {
    val ui = LocalUi.current
    val state = c.state
    val g = item.group
    val close = { c.groupMenuFor = null }
    DropdownMenu(expanded = expanded, onDismissRequest = close, containerColor = ui.panelElevated) {
        var start by remember(g.id) { mutableStateOf<Float?>(null) }
        Column(Modifier.width(240.dp).padding(horizontal = 14.dp, vertical = 4.dp)) {
            Text("Deckkraft ${(g.opacity * 100).roundToInt()} %", color = ui.textMuted, fontSize = 12.sp)
            Slider(
                value = g.opacity,
                onValueChange = {
                    if (start == null) start = g.opacity
                    LayerOps.setGroupOpacityLive(state, g.id, it)
                },
                onValueChangeFinished = { start?.let { LayerOps.commitGroupOpacity(state, g.id, it) }; start = null },
                colors = SliderDefaults.colors(thumbColor = ui.accent, activeTrackColor = ui.accent),
            )
        }
        HorizontalDivider(color = ui.divider)
        DropdownMenuItem(text = { Text("Umbenennen", color = ui.text) }, onClick = { close(); c.renameGroup = g.id })
        DropdownMenuItem(text = { Text("Zusammenfügen", color = ui.text) }, onClick = { close(); LayerOps.mergeGroup(state, g.id) })
        DropdownMenuItem(text = { Text("Gruppierung aufheben", color = ui.text) }, onClick = { close(); LayerOps.ungroup(state, g.id) })
        DropdownMenuItem(
            text = { Text("Gruppe löschen", color = Color(0xFFFF5A52)) },
            onClick = { close(); LayerOps.deleteGroup(state, g.id) },
        )
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
