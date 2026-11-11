package at.techgaisi.farbwerk.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.ArtDocument

/** STUB – Galerie (Startbildschirm). */
@Composable
fun GalleryScreen(state: EditorState) {
    Box(Modifier.fillMaxSize().background(Color(0xFF141416)), contentAlignment = Alignment.Center) {
        Button(onClick = { state.openDocument(ArtDocument.create("Ohne Titel", 2048, 2048)) }) { Text("Neue Leinwand") }
    }
}
