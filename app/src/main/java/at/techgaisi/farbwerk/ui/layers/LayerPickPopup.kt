package at.techgaisi.farbwerk.ui.layers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.LayerOps
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlin.math.roundToInt

/**
 * „Ebene wählen“ auf der Leinwand (§16.4, optional): Popup mit allen sichtbaren Ebenen, die an der
 * Dokumentposition ([docX], [docY]) Inhalt haben – oberste zuerst. Tippen macht die Ebene primär.
 *
 * Auslöser (Halten/Ziehen auf der Leinwand) liegt in der CanvasView; [windowX]/[windowY] = Fensterposition
 * des Fingers, an der das Popup erscheint.
 */
@Composable
fun LayerPickPopup(
    state: EditorState, docX: Float, docY: Float, windowX: Float, windowY: Float, onDismiss: () -> Unit,
) {
    val ui = LocalUi.current
    val doc = state.document ?: run { onDismiss(); return }
    val layers = remember(docX, docY, state.revision) { LayerOps.layersAt(doc, docX, docY) }
    val thumbs = remember { LayerThumbnailCache() }
    LaunchedEffect(doc) { thumbs.refresh(doc) }
    if (layers.isEmpty()) {
        LaunchedEffect(Unit) { state.flash("Keine Ebene an dieser Stelle"); onDismiss() }
        return
    }
    val visible = remember { MutableTransitionState(false) }.apply { targetState = true }
    Popup(
        offset = IntOffset(windowX.roundToInt() + 24, windowY.roundToInt() - 40),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(visibleState = visible, enter = fadeIn(tween(150)) + scaleIn(tween(180), initialScale = 0.92f)) {
            Column(
                Modifier
                    .width(240.dp)
                    .shadow(24.dp, RoundedCornerShape(13.dp))
                    .clip(RoundedCornerShape(13.dp))
                    .background(ui.panelElevated)
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
            ) {
                for (l in layers) {
                    val active = l === doc.activeLayer
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .background(if (active) ui.accent else Color.Transparent)
                            .clickable {
                                val i = doc.indexOf(l)
                                if (i >= 0) LayerOps.setActive(state, i)
                                state.flash("Ebene: ${l.name}")
                                onDismiss()
                            }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ThumbnailImage(
                            thumbs.image(l.id), thumbs.version, doc.width.toFloat() / doc.height,
                            Modifier.size(38.dp).clip(RoundedCornerShape(4.dp)).border(0.5.dp, ui.divider, RoundedCornerShape(4.dp)),
                            checker = l.alphaLock, background = Color.White,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            l.name, color = if (active) Color.White else ui.text, fontSize = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
