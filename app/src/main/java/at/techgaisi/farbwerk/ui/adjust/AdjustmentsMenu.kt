package at.techgaisi.farbwerk.ui.adjust

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.ui.common.PanelCard

/** STUB – Popover-Panel "Anpassungen". [modifier] enthält bereits Größe/Position. */
@Composable
fun AdjustmentsMenu(state: EditorState, modifier: Modifier = Modifier) {
    PanelCard(modifier, title = "Anpassungen") { Text("Folgt", Modifier.padding(16.dp)) }
}
