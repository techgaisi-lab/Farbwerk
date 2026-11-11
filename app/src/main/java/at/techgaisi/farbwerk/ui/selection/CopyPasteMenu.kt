package at.techgaisi.farbwerk.ui.selection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ContentPasteGo
import androidx.compose.material.icons.outlined.FilterNone
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.selection.ClipboardOps
import at.techgaisi.farbwerk.ui.common.PanelCard
import at.techgaisi.farbwerk.ui.theme.LocalUi

/** Schwebendes Kopieren-&-Einfügen-Menü (wenn state.copyPasteMenuVisible). */
@Composable
fun CopyPasteMenu(state: EditorState, modifier: Modifier = Modifier) {
    fun run(op: (EditorState) -> Unit) {
        state.copyPasteMenuVisible = false
        op(state)
    }
    PanelCard(modifier.width(300.dp), title = "Kopieren & Einfügen") {
        Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MenuTile(Icons.Outlined.ContentCut, "Ausschneiden", Modifier.weight(1f)) { run(ClipboardOps::cut) }
                MenuTile(Icons.Outlined.ContentCopy, "Kopieren", Modifier.weight(1f)) { run(ClipboardOps::copy) }
                MenuTile(Icons.Outlined.FilterNone, "Alles kopieren", Modifier.weight(1f)) { run(ClipboardOps::copyAll) }
            }
            Spacer(Modifier.size(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MenuTile(Icons.Outlined.Layers, "Duplizieren", Modifier.weight(1f)) { run(ClipboardOps::duplicate) }
                MenuTile(Icons.Outlined.ContentPasteGo, "Ausschneiden & Einfügen", Modifier.weight(1f)) {
                    run(ClipboardOps::cutAndPaste)
                }
                MenuTile(Icons.Outlined.ContentPaste, "Einfügen", Modifier.weight(1f), enabled = ClipboardOps.hasClip) {
                    run(ClipboardOps::paste)
                }
            }
        }
    }
}

@Composable
private fun MenuTile(icon: ImageVector, label: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val ui = LocalUi.current
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (enabled) ui.icon else ui.iconMuted.copy(alpha = 0.5f), modifier = Modifier.size(26.dp))
        Spacer(Modifier.size(4.dp))
        Text(label, color = if (enabled) ui.text else ui.textMuted, fontSize = 11.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 13.sp)
    }
}
