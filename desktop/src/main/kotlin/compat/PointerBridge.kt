package compat

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import at.techgaisi.farbwerk.canvas.CanvasView
import at.techgaisi.farbwerk.model.Panel
import kotlin.math.pow

/**
 * Übersetzt Compose-Zeigerereignisse in Android-MotionEvents.
 *
 * - Linke Maustaste / Stift → Werkzeugtyp STYLUS (Druck, sofern geliefert; Maus = 1,0). Ohne gedrückte Taste
 *   entstehen Schwebe-Ereignisse (Pinselvorschau unter dem Mauszeiger).
 * - Touch → FINGER (mehrere Zeiger, Gesten wie auf dem Tablet).
 * - Nur für die Leinwand ([CanvasView]): rechte Maustaste = Pipette (schwebende Pipette, Farbe beim Loslassen),
 *   mittlere Maustaste = Verschieben (wie gedrückte Leertaste), Mausrad (auch mit Strg) = Zoom am Mauszeiger.
 *
 * Ist [view] null, gehen die Ereignisse an [handler] (pointerInteropFilter).
 */
class PointerBridge(
    private val view: View?,
    private val handler: ((MotionEvent) -> Boolean)? = null,
) {
    private enum class MouseMode { NONE, DRAW, EYEDROPPER, PAN }

    private class Ptr(val androidId: Int, var tool: Int, var x: Float, var y: Float, var pressure: Float)

    /** Gedrückte Zeiger in Android-Reihenfolge. */
    private val active = LinkedHashMap<PointerId, Ptr>()
    private var downTime = 0L
    private var mouseMode = MouseMode.NONE
    private var mouseId: PointerId? = null
    private var hovering = false
    private var lastMouseX = 0f
    private var lastMouseY = 0f
    private var savedSpaceHeld = false

    private val canvasView: CanvasView? get() = view as? CanvasView
    private val touchSize: Float get() = 7f * 96f * DesktopPlatform.density / 25.4f

    private fun nextId(): Int {
        var id = 0
        while (active.values.any { it.androidId == id }) id++
        return id
    }

    private fun metaOf(ev: PointerEvent): Int {
        val m = ev.keyboardModifiers
        var meta = 0
        if (m.isShiftPressed) meta = meta or KeyEvent.META_SHIFT_ON
        if (m.isAltPressed) meta = meta or KeyEvent.META_ALT_ON
        if (m.isCtrlPressed) meta = meta or KeyEvent.META_CTRL_ON
        if (m.isMetaPressed) meta = meta or KeyEvent.META_META_ON
        return meta
    }

    private fun toolOf(c: PointerInputChange): Int = when (c.type) {
        PointerType.Touch -> MotionEvent.TOOL_TYPE_FINGER
        PointerType.Eraser -> MotionEvent.TOOL_TYPE_ERASER
        else -> MotionEvent.TOOL_TYPE_STYLUS
    }

    private fun pressureOf(c: PointerInputChange): Float =
        if (c.type == PointerType.Mouse) 1f else c.pressure.takeIf { it > 0f } ?: 1f

    // ------------------------------------------------------------------ Versand

    private fun dispatchTouch(e: MotionEvent): Boolean {
        val v = view
        return if (v != null) v.dispatchTouchEvent(e) else handler?.invoke(e) ?: false
    }

    private fun dispatchHover(e: MotionEvent): Boolean = view?.dispatchGenericMotionEvent(e) ?: false

    /** Ereignis aus dem aktuellen Zeigerstand. */
    private fun build(action: Int, meta: Int, buttons: Int, history: PointerInputChange? = null): MotionEvent {
        val list = active.values.toList()
        val b = MotionEvent.Builder(list.size).action(action).times(downTime, SystemClock.uptimeMillis())
            .meta(meta).buttons(buttons)
        list.forEachIndexed { i, p ->
            b.pointer(i, p.androidId, p.tool, p.x, p.y, p.pressure,
                size = if (p.tool == MotionEvent.TOOL_TYPE_FINGER) touchSize else 1f)
        }
        if (history != null && list.size == 1) {
            val hs = history.historical
            if (hs.isNotEmpty()) {
                val now = SystemClock.uptimeMillis()
                val times = LongArray(hs.size) { now - (history.uptimeMillis - hs[it].uptimeMillis).coerceAtLeast(0L) }
                val xs = FloatArray(hs.size) { hs[it].position.x }
                val ys = FloatArray(hs.size) { hs[it].position.y }
                val ps = FloatArray(hs.size) { list[0].pressure }
                b.history(times, xs, ys, ps)
            }
        }
        return b.build()
    }

    private fun hover(action: Int, c: PointerInputChange, meta: Int) {
        if (view == null) return
        val e = MotionEvent.Builder(1).action(action).times(SystemClock.uptimeMillis(), SystemClock.uptimeMillis())
            .meta(meta).pointer(0, 0, toolOf(c), c.position.x, c.position.y, 0f).build()
        dispatchHover(e)
    }

    // ------------------------------------------------------------------ Verarbeitung

    /** Verarbeitet ein Compose-Ereignis; true = verbraucht. */
    fun process(ev: PointerEvent): Boolean {
        val meta = metaOf(ev)
        if (ev.type == PointerEventType.Scroll) return scroll(ev)
        var consumed = false
        for (c in ev.changes) {
            consumed = if (c.type == PointerType.Touch) touch(c, meta) || consumed
            else mouse(ev, c, meta) || consumed
        }
        // Gemeinsames MOVE für alle bewegten, gedrückten Zeiger
        val moved = ev.changes.filter { it.pressed && it.previousPressed && active.containsKey(it.id) &&
            (it.type == PointerType.Touch || mouseMode == MouseMode.DRAW || mouseMode == MouseMode.PAN) }
        if (moved.isNotEmpty()) {
            for (c in moved) active[c.id]?.let { it.x = c.position.x; it.y = c.position.y; it.pressure = pressureOf(c) }
            consumed = dispatchTouch(build(MotionEvent.ACTION_MOVE, meta, 0, moved.singleOrNull())) || consumed
        }
        return consumed || view != null
    }

    private fun pointerDown(c: PointerInputChange, tool: Int, meta: Int): Boolean {
        val first = active.isEmpty()
        if (first) downTime = SystemClock.uptimeMillis()
        active[c.id] = Ptr(nextId(), tool, c.position.x, c.position.y, pressureOf(c))
        val idx = active.keys.indexOf(c.id)
        val action = if (first) MotionEvent.ACTION_DOWN
            else MotionEvent.ACTION_POINTER_DOWN or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        return dispatchTouch(build(action, meta, 0))
    }

    private fun pointerUp(c: PointerInputChange, meta: Int, cancel: Boolean = false): Boolean {
        val p = active[c.id] ?: return false
        p.x = c.position.x; p.y = c.position.y
        val idx = active.keys.indexOf(c.id)
        val last = active.size == 1
        val action = when {
            cancel -> MotionEvent.ACTION_CANCEL
            last -> MotionEvent.ACTION_UP
            else -> MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        val r = dispatchTouch(build(action, meta, 0))
        active.remove(c.id)
        return r
    }

    private fun touch(c: PointerInputChange, meta: Int): Boolean = when {
        c.pressed && !c.previousPressed -> pointerDown(c, MotionEvent.TOOL_TYPE_FINGER, meta)
        !c.pressed && c.previousPressed -> pointerUp(c, meta)
        else -> false
    }

    private fun mouse(ev: PointerEvent, c: PointerInputChange, meta: Int): Boolean {
        val cv = canvasView
        val x = c.position.x; val y = c.position.y
        when (ev.type) {
            PointerEventType.Press -> {
                if (mouseMode != MouseMode.NONE) return true
                if (hovering) { hover(MotionEvent.ACTION_HOVER_EXIT, c, meta); hovering = false }
                val button = ev.button
                mouseId = c.id
                lastMouseX = x; lastMouseY = y
                when {
                    cv != null && button == PointerButton.Secondary -> {
                        mouseMode = MouseMode.EYEDROPPER
                        val d = cv.windowToDoc(x + windowX(), y + windowY())
                        cv.beginFloatingEyedropper(d.x, d.y)
                        return true
                    }
                    cv != null && button == PointerButton.Tertiary -> {
                        val st = DesktopPlatform.editorState
                        mouseMode = MouseMode.PAN
                        savedSpaceHeld = st?.spaceHeld ?: false
                        st?.spaceHeld = true
                        return pointerDown(c, MotionEvent.TOOL_TYPE_MOUSE, meta)
                    }
                    else -> {
                        mouseMode = MouseMode.DRAW
                        return pointerDown(c, toolOf(c), meta)
                    }
                }
            }
            PointerEventType.Release -> {
                if (c.id != mouseId || ev.buttons.isPrimaryPressed && mouseMode == MouseMode.DRAW) return true
                val r = when (mouseMode) {
                    MouseMode.EYEDROPPER -> { cv?.endFloatingEyedropper(commit = true); true }
                    MouseMode.PAN -> pointerUp(c, meta).also { DesktopPlatform.editorState?.spaceHeld = savedSpaceHeld }
                    MouseMode.DRAW -> pointerUp(c, meta)
                    MouseMode.NONE -> false
                }
                mouseMode = MouseMode.NONE
                mouseId = null
                return r
            }
            PointerEventType.Move -> {
                when (mouseMode) {
                    MouseMode.EYEDROPPER -> {
                        cv?.moveFloatingEyedropperBy(x - lastMouseX, y - lastMouseY)
                        lastMouseX = x; lastMouseY = y
                        return true
                    }
                    MouseMode.NONE -> {
                        if (!c.pressed) {
                            hover(if (hovering) MotionEvent.ACTION_HOVER_MOVE else MotionEvent.ACTION_HOVER_ENTER, c, meta)
                            hovering = true
                        }
                        return false
                    }
                    else -> return false // gemeinsames MOVE in process()
                }
            }
            PointerEventType.Enter -> {
                if (mouseMode == MouseMode.NONE && !c.pressed) { hover(MotionEvent.ACTION_HOVER_ENTER, c, meta); hovering = true }
                return false
            }
            PointerEventType.Exit -> {
                if (hovering) { hover(MotionEvent.ACTION_HOVER_EXIT, c, meta); hovering = false }
                return false
            }
        }
        return false
    }

    private fun windowX(): Float = view?.let { val a = IntArray(2); it.getLocationInWindow(a); a[0].toFloat() } ?: 0f
    private fun windowY(): Float = view?.let { val a = IntArray(2); it.getLocationInWindow(a); a[1].toFloat() } ?: 0f

    /** Mausrad: Zoom am Mauszeiger (mit und ohne Strg). */
    private fun scroll(ev: PointerEvent): Boolean {
        val cv = canvasView ?: return false
        val st = DesktopPlatform.editorState
        val c = ev.changes.firstOrNull() ?: return false
        val dy = c.scrollDelta.y.takeIf { it != 0f } ?: c.scrollDelta.x
        if (dy == 0f || st?.document == null) return true
        val factor = 1.15f.pow(-dy.coerceIn(-5f, 5f))
        val canPan = st.openPanel == Panel.NONE && st.quickMenuAt == null && active.isEmpty() && mouseMode == MouseMode.NONE
        if (!canPan) { cv.zoomBy(factor); return true }
        // Mauspunkt in die Mitte schieben, um die Mitte zoomen, zurückschieben → Zoom um den Mauszeiger
        val cx = cv.width / 2f; val cy = cv.height / 2f
        val px = c.position.x; val py = c.position.y
        pan(cv, st, px, py, cx - px, cy - py)
        cv.zoomBy(factor)
        pan(cv, st, cx, cy, px - cx, py - cy)
        return true
    }

    /** Verschiebt die Ansicht über die Leertasten-Verschiebung der Leinwand (synthetische Mausereignisse). */
    private fun pan(cv: CanvasView, st: at.techgaisi.farbwerk.EditorState, x: Float, y: Float, dx: Float, dy: Float) {
        val held = st.spaceHeld
        val panned = cv.spacePanned
        st.spaceHeld = true
        try {
            val t = SystemClock.uptimeMillis()
            fun ev(a: Int, px: Float, py: Float) = MotionEvent.Builder(1).action(a).times(t, t)
                .pointer(0, 0, MotionEvent.TOOL_TYPE_MOUSE, px, py, 1f).build()
            cv.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, x, y))
            cv.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, x + dx, y + dy))
            cv.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, x + dx, y + dy))
        } finally {
            st.spaceHeld = held
            cv.spacePanned = panned
        }
    }
}

/** Hängt eine [PointerBridge] an ein Compose-Element. [onPress] läuft bei jedem Drücken (z. B. Fokus holen). */
fun Modifier.pointerBridge(bridge: PointerBridge, onPress: (() -> Unit)?): Modifier = this.pointerInput(bridge) {
    awaitPointerEventScope {
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Main)
            if (ev.type == PointerEventType.Press) onPress?.invoke()
            val consumed = try { bridge.process(ev) } catch (t: Throwable) {
                System.err.println("Zeigerereignis fehlgeschlagen: $t"); t.printStackTrace(); false
            }
            if (consumed) ev.changes.forEach { it.consume() }
        }
    }
}
