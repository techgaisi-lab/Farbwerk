package at.techgaisi.farbwerk.input

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Übersicht der Tastenkürzel, solange Strg gehalten wird ([KeyboardShortcuts.overlayVisible]).
 * Kann vom Editor-Bildschirm zentriert eingeblendet werden.
 */
@Composable
fun ShortcutOverlay(modifier: Modifier = Modifier) {
    if (!KeyboardShortcuts.overlayVisible) return
    val list = KeyboardShortcuts.SHORTCUTS
    val half = (list.size + 1) / 2
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xEE1E1E22)).padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        for (col in listOf(list.take(half), list.drop(half))) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                col.forEach { (k, v) ->
                    Row {
                        Text(k, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(170.dp))
                        Text(v, color = Color(0xFFB8B8C0), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
