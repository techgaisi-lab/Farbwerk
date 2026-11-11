package at.techgaisi.farbwerk.ui.brush

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.brush.Brush
import at.techgaisi.farbwerk.brush.BrushLibrary
import at.techgaisi.farbwerk.model.Tool
import at.techgaisi.farbwerk.ui.common.PanelCard
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Anfrage, das Pinsel-Studio zu öffnen. */
private class StudioRequest(val brush: Brush, val isNew: Boolean)

/**
 * Popover "Pinselbibliothek": links Pinselsets, rechts Pinsel mit Name und Strichvorschau.
 * Tippen wählt den Pinsel für das aktuelle Werkzeug (Malen, Wischen oder Radieren),
 * erneutes Tippen auf den aktiven Pinsel öffnet das Pinsel-Studio.
 * Nach links wischen oder lange drücken: Teilen, Duplizieren, Löschen (nur eigene), Favorit.
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

    var chosenSet by remember(tool) { mutableStateOf(lib.setOf(activeId)) }
    var studio by remember { mutableStateOf<StudioRequest?>(null) }
    val sets = remember(libVersion) { lib.sets() }
    // Falls das gewählte Set verschwunden ist (z. B. letzter eigener Pinsel gelöscht): auf ein vorhandenes ausweichen
    val selectedSet = if (sets.any { it.id == chosenSet }) chosenSet
    else lib.setOf(activeId).takeIf { id -> sets.any { it.id == id } } ?: sets.first { !it.virtual }.id
    val brushes = remember(libVersion, selectedSet) { lib.brushes(selectedSet) }

    PanelCard(modifier) {
        // Kopfzeile
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Pinselbibliothek", color = ui.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when (tool) { Tool.SMUDGE -> "Wischen"; Tool.ERASE -> "Radieren"; else -> "Malen" } +
                        " · " + (lib.get(activeId)?.name ?: ""),
                    color = ui.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(8.dp))
                    .clickable { studio = StudioRequest(lib.newBrush(), isNew = true) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Add, "Neuer Pinsel", tint = ui.accent, modifier = Modifier.size(26.dp)) }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(ui.divider))

        Row(Modifier.fillMaxSize()) {
            // ---- Sets
            LazyColumn(Modifier.width(124.dp).fillMaxHeight().padding(vertical = 4.dp)) {
                items(sets, key = { it.id }) { s ->
                    val sel = s.id == selectedSet
                    Text(
                        s.name,
                        color = if (sel) Color.White else ui.text,
                        fontSize = 13.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (sel) ui.selectedRow else Color.Transparent)
                            .clickable { chosenSet = s.id }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                    )
                }
            }
            Box(Modifier.width(0.5.dp).fillMaxHeight().background(ui.divider))

            // ---- Pinsel
            val listState = rememberLazyListState()
            LaunchedEffect(selectedSet) {
                val i = brushes.indexOfFirst { it.id == activeId }
                if (i > 0) listState.scrollToItem(i)
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = listState) {
                items(brushes, key = { it.id }) { b ->
                    BrushRow(
                        lib = lib,
                        brush = b,
                        selected = b.id == activeId,
                        custom = lib.isCustom(b.id),
                        favorite = lib.isFavorite(b.id),
                        version = libVersion,
                        onTap = {
                            if (b.id == activeId) {
                                studio = StudioRequest(b, isNew = false)
                            } else {
                                settings.brushId = b.id
                                state.toolSettingsVersion++
                            }
                        },
                        onDuplicate = { lib.duplicate(b.id)?.let { c -> settings.brushId = c.id; state.toolSettingsVersion++ } },
                        onDelete = {
                            if (lib.delete(b.id)) {
                                // Werkzeuge, die den gelöschten Pinsel nutzten, auf den Standard zurücksetzen
                                for ((t, ts) in state.toolSettings) if (ts.brushId == b.id) ts.brushId = lib.defaultBrushId(t)
                                state.toolSettingsVersion++
                                state.flash("Pinsel gelöscht")
                            }
                        },
                        onShare = { lib.share(b.id) },
                        onFavorite = { lib.toggleFavorite(b.id) },
                        onEdit = { studio = StudioRequest(b, isNew = false) },
                    )
                }
            }
        }
    }

    studio?.let { req ->
        BrushStudio(state, req.brush, req.isNew, onClose = { studio = null })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrushRow(
    lib: BrushLibrary,
    brush: Brush,
    selected: Boolean,
    custom: Boolean,
    favorite: Boolean,
    version: Int,
    onTap: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onFavorite: () -> Unit,
    onEdit: () -> Unit,
) {
    val ui = LocalUi.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var menu by remember { mutableStateOf(false) }
    val actionW = 62.dp
    val actionCount = if (custom) 3 else 2
    val maxShift = with(density) { (actionW * actionCount).toPx() }

    fun close() { scope.launch { offset.animateTo(0f, tween(160)) } }

    val preview by produceState<ImageBitmap?>(
        lib.cachedPreview(brush)?.asImageBitmap(), brush.id, brush.rev, version,
    ) {
        value = withContext(Dispatchers.Default) { lib.preview(brush).asImageBitmap() }
    }

    Box(Modifier.fillMaxWidth().height(70.dp)) {
        // Aktionen hinter der Zeile
        Row(Modifier.align(Alignment.CenterEnd).fillMaxHeight()) {
            SwipeAction(Icons.Outlined.Share, "Teilen", ui.panelElevated, actionW) { close(); onShare() }
            SwipeAction(Icons.Outlined.ContentCopy, "Duplizieren", Color(0xFF2E7D5B), actionW) { close(); onDuplicate() }
            if (custom) SwipeAction(Icons.Outlined.Delete, "Löschen", Color(0xFFD03B3B), actionW) { close(); onDelete() }
        }
        // Vordergrund
        Row(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .fillMaxSize()
                .background(ui.panel)
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) ui.selectedRow else Color.Transparent)
                .pointerInput(maxShift) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                offset.animateTo(if (offset.value < -maxShift / 2f) -maxShift else 0f, tween(160))
                            }
                        },
                        onDragCancel = { close() },
                    ) { change, dx ->
                        change.consume()
                        scope.launch { offset.snapTo((offset.value + dx).coerceIn(-maxShift, 0f)) }
                    }
                }
                .combinedClickable(
                    onClick = { if (offset.value < -1f) close() else onTap() },
                    onLongClick = { menu = true },
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        brush.name, color = if (selected) Color.White else ui.text, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (favorite) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.Star, null, tint = Color(0xFFFFC94D), modifier = Modifier.size(12.dp))
                    }
                    if (custom) {
                        Spacer(Modifier.width(4.dp))
                        Text("eigen", color = if (selected) Color(0xCCFFFFFF) else ui.textMuted, fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Box(Modifier.fillMaxWidth().height(38.dp)) {
                    preview?.let {
                        Image(
                            it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                            colorFilter = ColorFilter.tint(if (selected) Color.White else ui.text),
                        )
                    }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Bearbeiten") }, leadingIcon = { Icon(Icons.Outlined.Tune, null) },
                onClick = { menu = false; onEdit() })
            DropdownMenuItem(text = { Text(if (favorite) "Aus Favoriten entfernen" else "Zu Favoriten") },
                leadingIcon = { Icon(if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder, null) },
                onClick = { menu = false; onFavorite() })
            DropdownMenuItem(text = { Text("Duplizieren") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                onClick = { menu = false; onDuplicate() })
            DropdownMenuItem(text = { Text("Teilen") }, leadingIcon = { Icon(Icons.Outlined.Share, null) },
                onClick = { menu = false; onShare() })
            if (custom) DropdownMenuItem(text = { Text("Löschen", color = Color(0xFFD03B3B)) },
                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Color(0xFFD03B3B)) },
                onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
private fun SwipeAction(icon: ImageVector, label: String, bg: Color, width: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Column(
        Modifier.width(width).fillMaxHeight().background(bg).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(label, color = Color.White, fontSize = 9.sp, maxLines = 1)
    }
}
