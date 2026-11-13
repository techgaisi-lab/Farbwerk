@file:Suppress("unused")

package androidx.compose.ui.viewinterop

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import compat.PointerBridge
import compat.pointerBridge

/**
 * Bettet eine android.view.View in Compose ein (Desktop): Die Ansicht zeichnet über einen
 * Skia-gestützten android.graphics.Canvas, bekommt Größe/Fensterposition und Zeigerereignisse
 * (Maus, Stift, Touch – siehe [PointerBridge]) als MotionEvents.
 */
@Composable
fun <T : View> AndroidView(
    factory: (Context) -> T,
    modifier: Modifier = Modifier,
    update: (T) -> Unit = {},
) {
    val context = LocalContext.current
    val view = remember { factory(context) }
    val bridge = remember { PointerBridge(view) }
    val focus = remember { FocusRequester() }
    SideEffect { update(view) }
    DisposableEffect(view) {
        view.hostAttach()
        onDispose { view.hostDetach() }
    }
    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { view.hostSetSize(it.width, it.height) }
            .onGloballyPositioned { c ->
                val p = c.positionInWindow()
                view.hostSetWindowPosition(p.x.toInt(), p.y.toInt())
            }
            .focusRequester(focus)
            .onFocusChanged { view.hostFocus(it.isFocused) }
            .focusable()
            .pointerBridge(bridge) { if (view.isFocusable) try { focus.requestFocus() } catch (_: Throwable) {} }
            .drawBehind {
                @Suppress("UNUSED_VARIABLE") val tick = view.drawTick.intValue
                drawIntoCanvas { c ->
                    val ac = c.nativeCanvas
                    val save = ac.save()
                    // Sichtbaren Bereich begrenzen: getClipBounds liefert dann die View-Fläche
                    // (sonst rendert der Compositor pro Bild das ganze Dokument)
                    ac.setDeviceClip(0f, 0f, size.width, size.height)
                    ac.clipRect(0f, 0f, size.width, size.height)
                    try { view.draw(ac) } finally { ac.restoreToCount(save) }
                }
            }
    )
}
