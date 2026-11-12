package at.techgaisi.farbwerk.ui.selection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.selection.ClipboardOps
import at.techgaisi.farbwerk.ui.reference.FloatingTokens
import at.techgaisi.farbwerk.ui.reference.FloatingWindowCard
import at.techgaisi.farbwerk.ui.reference.FloatingWindowHeader
import kotlin.math.roundToInt

/**
 * Verschiebung des Fensters gegenüber seiner Ausgangslage (px). Bleibt erhalten, solange die App läuft,
 * damit das Fenster beim erneuten Öffnen dort erscheint, wo es zuletzt lag.
 */
private var windowOffset by mutableStateOf(Offset.Zero)

/**
 * Schwebendes Fenster „Kopieren & Einfügen“ (wenn state.copyPasteMenuVisible), UI_SPEC §8:
 * Greifleiste (Ziehen verschiebt), Titel 18 sp / 700, Schließen-Kreis, darunter eine Reihe aus
 * 6 Aktionen (Symbol 18 dp + Beschriftung 10 sp), gleich verteilt.
 *
 * [modifier] enthält die Ausgangslage (vom Editor: mittig); das Ziehen verschiebt relativ dazu.
 */
@Composable
fun CopyPasteMenu(state: EditorState, modifier: Modifier = Modifier) {
    fun run(op: (EditorState) -> Unit) {
        state.copyPasteMenuVisible = false
        op(state)
    }
    FloatingWindowCard(
        modifier
            .offset { IntOffset(windowOffset.x.roundToInt(), windowOffset.y.roundToInt()) }
            .width(372.dp)
    ) {
        FloatingWindowHeader(
            title = "Kopieren & Einfügen",
            onClose = { state.copyPasteMenuVisible = false },
            onDrag = { d -> windowOffset += d },
        )
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 12.dp)) {
            val m = Modifier.weight(1f)
            ActionItem(Icons.Outlined.ContentCut, "Ausschneiden", m) { run(ClipboardOps::cut) }
            ActionItem(Icons.Outlined.ContentCopy, "Kopieren", m) { run(ClipboardOps::copy) }
            ActionItem(Icons.Outlined.FilterNone, "Alles kopieren", m) { run(ClipboardOps::copyAll) }
            ActionItem(Icons.Outlined.Layers, "Duplizieren", m) { run(ClipboardOps::duplicate) }
            ActionItem(Icons.Outlined.ContentPasteGo, "Ausschneiden & Einfügen", m) { run(ClipboardOps::cutAndPaste) }
            ActionItem(Icons.Outlined.ContentPaste, "Einfügen", m, enabled = ClipboardOps.hasClip) { run(ClipboardOps::paste) }
        }
    }
}

/** Aktion: Symbol 18 dp (weiß @ 85 %) über Beschriftung 10 sp (@ 55 %), höchstens zwei Zeilen. */
@Composable
private fun ActionItem(icon: ImageVector, label: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.35f)
            .padding(vertical = 6.dp, horizontal = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = FloatingTokens.iconIdle, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(5.dp))
        Text(
            label, color = FloatingTokens.textSecondary, fontSize = 10.sp, lineHeight = 12.sp,
            textAlign = TextAlign.Center, maxLines = 2,
        )
    }
}
