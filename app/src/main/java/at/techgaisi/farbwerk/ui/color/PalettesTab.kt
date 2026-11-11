package at.techgaisi.farbwerk.ui.color

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.color.Palette
import at.techgaisi.farbwerk.color.PaletteExtractor
import at.techgaisi.farbwerk.color.PaletteStore
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Paletten: Liste aller Paletten in kompakter oder Kartenansicht.
 * Farbfeld tippen = Farbe wählen, leeres Feld tippen = aktuelle Farbe speichern, lange drücken = Feld leeren.
 */
@Composable
fun PalettesTab(state: EditorState, model: HsvModel, store: PaletteStore) {
    val ui = LocalUi.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var addMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Palette?>(null) }
    var deleting by remember { mutableStateOf<Palette?>(null) }
    var busy by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val colors = withContext(Dispatchers.Default) {
                runCatching { PaletteExtractor.fromUri(context, uri) }.getOrDefault(emptyList())
            }
            busy = false
            if (colors.isEmpty()) state.flash("Bild konnte nicht gelesen werden")
            else { store.create("Aus Bild", colors); state.flash("Palette erstellt") }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Kopfzeile: Ansicht + Hinzufügen
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ViewToggle("Kompakt", store.compactView) { store.setCompact(true) }
            Spacer(Modifier.width(6.dp))
            ViewToggle("Karten", !store.compactView) { store.setCompact(false) }
            Spacer(Modifier.weight(1f))
            if (busy) Text("Wird erstellt …", color = ui.textMuted, fontSize = 12.sp)
            Box {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).clickable { addMenu = true },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Add, "Palette hinzufügen", tint = ui.icon) }
                DropdownMenu(addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(text = { Text("Neue Palette") }, onClick = {
                        addMenu = false
                        renaming = store.create()
                    })
                    DropdownMenuItem(text = { Text("Aus aktuellem Verlauf") }, onClick = {
                        addMenu = false
                        if (state.recentColors.isEmpty()) state.flash("Verlauf ist leer")
                        else store.create("Verlauf", state.recentColors.toList())
                    })
                    DropdownMenuItem(text = { Text("Aus Foto erstellen") }, onClick = {
                        addMenu = false
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                }
            }
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(store.palettes, key = { it.id }) { p ->
                PaletteItem(
                    palette = p,
                    isDefault = store.defaultPalette?.id == p.id,
                    compact = store.compactView,
                    currentColor = state.color,
                    onPick = { model.pick(it) },
                    onSave = { i -> store.setSlot(p, i, state.color) },
                    onClear = { i -> store.setSlot(p, i, null) },
                    onRename = { renaming = p },
                    onSetDefault = { store.setDefault(p) },
                    onDuplicate = { store.duplicate(p) },
                    onDelete = { deleting = p },
                )
            }
        }
    }

    renaming?.let { p ->
        var text by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Palette umbenennen") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton({ store.rename(p, text); renaming = null }) { Text("OK") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Abbrechen") } },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Palette löschen?") },
            text = { Text("„${p.name}“ wird endgültig gelöscht.") },
            confirmButton = { TextButton({ store.delete(p); deleting = null }) { Text("Löschen") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun ViewToggle(label: String, active: Boolean, onClick: () -> Unit) {
    val ui = LocalUi.current
    Text(
        label,
        color = if (active) ui.text else ui.textMuted,
        fontSize = 12.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) ui.panelElevated else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
private fun PaletteItem(
    palette: Palette,
    isDefault: Boolean,
    compact: Boolean,
    currentColor: Int,
    onPick: (Int) -> Unit,
    onSave: (Int) -> Unit,
    onClear: (Int) -> Unit,
    onRename: () -> Unit,
    onSetDefault: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val ui = LocalUi.current
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                palette.name, color = ui.text, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(4.dp)).clickable(onClick = onRename)
            )
            Spacer(Modifier.width(8.dp))
            if (isDefault) {
                Text(
                    "Standard", color = ui.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.background(ui.accent.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            } else {
                Text(
                    "Als Standard", color = ui.textMuted, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onSetDefault)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            Box {
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable { menu = true },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.MoreHoriz, "Palettenoptionen", tint = ui.iconMuted) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Umbenennen") }, onClick = { menu = false; onRename() })
                    if (!isDefault) DropdownMenuItem(text = { Text("Als Standard festlegen") }, onClick = { menu = false; onSetDefault() })
                    DropdownMenuItem(text = { Text("Duplizieren") }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Löschen") }, onClick = { menu = false; onDelete() })
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        SwatchGrid(palette, if (compact) 10 else 6, if (compact) 4.dp else 8.dp, currentColor, onPick, onSave, onClear)
    }
}

/** Raster aus [Palette.SLOTS] Feldern; Feldgröße ergibt sich aus der Breite. */
@Composable
private fun SwatchGrid(
    palette: Palette,
    columns: Int,
    spacing: Dp,
    currentColor: Int,
    onPick: (Int) -> Unit,
    onSave: (Int) -> Unit,
    onClear: (Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cell = (maxWidth - spacing * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            val rows = (Palette.SLOTS + columns - 1) / columns
            for (r in 0 until rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    for (c in 0 until columns) {
                        val i = r * columns + c
                        if (i >= Palette.SLOTS) { Spacer(Modifier.size(cell)); continue }
                        val color = palette.colors[i]
                        Swatch(
                            color, cell,
                            selected = color != null && color == currentColor,
                            corner = if (columns > 6) 3.dp else 6.dp,
                            onLongClick = if (color != null) ({ onClear(i) }) else null,
                        ) {
                            if (color != null) onPick(color) else onSave(i)
                        }
                    }
                }
            }
        }
    }
}
