package at.techgaisi.farbwerk.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.ui.icons.AppIcons
import at.techgaisi.farbwerk.ui.theme.UiMotion
import kotlinx.coroutines.delay

/**
 * Schwebende Pipette (Modifizierer tippen, Anleitung §3): Ring erscheint an der zuletzt bemalten Stelle;
 * Ziehen irgendwo auf dem Bildschirm verschiebt ihn relativ, Loslassen übernimmt die Farbe.
 * Erneutes Tippen auf den Modifizierer bricht ab.
 */
object FloatingEyedropper {
    var active by mutableStateOf(false)
        private set

    /** Startet die schwebende Pipette. @return false, wenn keine Leinwand bereit ist. */
    fun start(state: EditorState): Boolean {
        val cv = state.canvasView ?: return false
        val doc = state.document ?: return false
        val p = state.lastPaintPoint
        val x = p?.x?.coerceIn(0f, doc.width - 1f) ?: (doc.width / 2f)
        val y = p?.y?.coerceIn(0f, doc.height - 1f) ?: (doc.height / 2f)
        cv.beginFloatingEyedropper(x, y)
        active = cv.floatingEyedropperActive
        return active
    }

    fun moveBy(state: EditorState, dx: Float, dy: Float) = state.canvasView?.moveFloatingEyedropperBy(dx, dy)

    fun finish(state: EditorState, commit: Boolean) {
        state.canvasView?.endFloatingEyedropper(commit)
        active = false
    }
}

/** Eingabe-Ebene der schwebenden Pipette (über der Leinwand, unter den Leisten). */
@Composable
fun FloatingEyedropperLayer(state: EditorState) {
    if (!FloatingEyedropper.active) return
    DisposableEffect(Unit) {
        onDispose { if (FloatingEyedropper.active) FloatingEyedropper.finish(state, commit = false) }
    }
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) { ch.consume(); break }
                    val d = ch.positionChange()
                    if (d.x != 0f || d.y != 0f) FloatingEyedropper.moveBy(state, d.x, d.y)
                    ch.consume()
                }
                FloatingEyedropper.finish(state, commit = true)
            }
        }
    )
}

/**
 * Kleiner halbtransparenter Vollbild-Indikator oben links; Antippen holt die Oberfläche zurück.
 * Erscheint mit kurzem Aufploppen.
 */
@Composable
fun FullscreenIndicator(state: EditorState, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        state.fullscreen,
        modifier = modifier,
        enter = fadeIn(UiMotion.chrome()) + scaleIn(UiMotion.pop(), initialScale = 0.7f),
        exit = fadeOut(UiMotion.quick()) + scaleOut(UiMotion.quick(), targetScale = 0.8f),
    ) {
        Box(
            Modifier.statusBarsPadding().padding(10.dp).size(34.dp)
                .clip(RoundedCornerShape(9.dp)).background(Color(0x8C141416))
                .clickable { state.fullscreen = false },
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.FullscreenExit, "Oberfläche einblenden", tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Automatisches Vollbild (Einstellung): Die Oberfläche verschwindet während des Malens und kehrt nach der
 * eingestellten Verzögerung zurück. Liefert, ob die Leisten gerade ausgeblendet sein sollen.
 */
class AutoFullscreen {
    var hidden by mutableStateOf(false)
        private set
    private var stroking by mutableStateOf(false)

    fun onStroke(active: Boolean, enabled: Boolean) { stroking = active; if (active && enabled) hidden = true }

    @Composable
    fun Effect(state: EditorState) {
        val enabled = state.prefs.autoFullscreen
        LaunchedEffect(stroking, enabled) {
            if (!enabled) { hidden = false; return@LaunchedEffect }
            if (!stroking && hidden) {
                delay(state.prefs.autoFullscreenDelayMs.toLong())
                hidden = false
            }
        }
    }
}
