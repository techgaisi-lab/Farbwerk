@file:Suppress("unused", "UNUSED_PARAMETER", "MemberVisibilityCanBePrivate")

package android.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.mutableIntStateOf
import java.awt.EventQueue

/**
 * Basisklasse für eigene Ansichten (CanvasView). Wird auf dem Desktop von
 * androidx.compose.ui.viewinterop.AndroidView in Compose eingebettet: Größe, Fensterposition,
 * Zeichnen (über einen Skia-gestützten android.graphics.Canvas) und Eingaben kommen vom Host.
 */
open class View(val context: Context) {
    var width: Int = 0
        private set
    var height: Int = 0
        private set
    val left: Int get() = 0
    val top: Int get() = 0
    val right: Int get() = width
    val bottom: Int get() = height
    val measuredWidth: Int get() = width
    val measuredHeight: Int get() = height

    var isFocusable: Boolean = false
    var isFocusableInTouchMode: Boolean = false
    var isFocused: Boolean = false
        private set
    var isEnabled: Boolean = true
    var isClickable: Boolean = false
    var visibility: Int = VISIBLE
    var alpha: Float = 1f
    var keepScreenOn: Boolean = false
    var contentDescription: CharSequence? = null
    var id: Int = NO_ID
    var tag: Any? = null
    var layoutParams: ViewGroup.LayoutParams? = null
    var parent: ViewGroup? = null
        internal set
    val isHardwareAccelerated: Boolean get() = true
    val resources: android.content.res.Resources get() = context.resources
    val isAttachedToWindow: Boolean get() = attached
    val rootView: View get() = parent?.rootView ?: this

    private val tags = HashMap<Int, Any?>()

    // -------- Host-Anbindung (compat) --------
    /** Zeichen-Zähler: Compose liest ihn im Zeichenschritt, [invalidate] erhöht ihn → Neuzeichnen. */
    internal val drawTick = mutableIntStateOf(0)
    internal var windowX = 0
    internal var windowY = 0
    internal var attached = false

    /** Vom Host aufgerufen, wenn sich die Größe ändert. */
    fun hostSetSize(w: Int, h: Int) {
        if (w == width && h == height) return
        val ow = width; val oh = height
        width = w; height = h
        onSizeChanged(w, h, ow, oh)
        invalidate()
    }

    fun hostSetWindowPosition(x: Int, y: Int) { windowX = x; windowY = y }

    fun hostAttach() { if (!attached) { attached = true; onAttachedToWindow() } }

    fun hostDetach() { if (attached) { attached = false; onDetachedFromWindow() } }

    fun hostFocus(focused: Boolean) { isFocused = focused }

    // -------- Zeichnen --------
    open fun draw(canvas: Canvas) { onDraw(canvas) }
    protected open fun onDraw(canvas: Canvas) {}

    fun invalidate() {
        if (EventQueue.isDispatchThread()) drawTick.intValue++ else EventQueue.invokeLater { drawTick.intValue++ }
    }
    fun invalidate(l: Int, t: Int, r: Int, b: Int) = invalidate()
    fun postInvalidate() = EventQueue.invokeLater { invalidate() }
    fun postInvalidateOnAnimation() = postInvalidate()
    fun postInvalidateDelayed(delayMillis: Long) {
        if (delayMillis <= 0) postInvalidate() else uiHandler.postDelayed({ invalidate() }, delayMillis)
    }
    fun postOnAnimation(r: Runnable) = EventQueue.invokeLater(r)
    fun post(r: Runnable): Boolean { EventQueue.invokeLater(r); return true }
    fun postDelayed(r: Runnable, delayMillis: Long): Boolean = uiHandler.postDelayed(r, delayMillis)
    fun removeCallbacks(r: Runnable): Boolean { uiHandler.removeCallbacks(r); return true }
    fun requestLayout() = invalidate()
    /** Ebenentyp (auf dem Desktop nur gemerkt, ohne Wirkung). */
    var layerType: Int = LAYER_TYPE_NONE
        private set
    fun setLayerType(type: Int, paint: Paint?) { layerType = type }
    fun setWillNotDraw(willNotDraw: Boolean) {}

    // -------- Lage --------
    fun getLocationInWindow(out: IntArray) { out[0] = windowX; out[1] = windowY }
    fun getLocationOnScreen(out: IntArray) = getLocationInWindow(out)

    // -------- Fokus / Eingabe --------
    fun requestFocus(): Boolean { isFocused = true; return true }
    fun clearFocus() { isFocused = false }
    fun requestUnbufferedDispatch(event: MotionEvent) {}
    fun requestUnbufferedDispatch(source: Int) {}
    fun performHapticFeedback(feedbackConstant: Int): Boolean = true
    fun performHapticFeedback(feedbackConstant: Int, flags: Int): Boolean = true
    fun performClick(): Boolean = false

    open fun dispatchTouchEvent(event: MotionEvent): Boolean = onTouchEvent(event)
    open fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val a = event.actionMasked
        if (a == MotionEvent.ACTION_HOVER_ENTER || a == MotionEvent.ACTION_HOVER_MOVE || a == MotionEvent.ACTION_HOVER_EXIT) {
            if (onHoverEvent(event)) return true
        }
        return onGenericMotionEvent(event)
    }
    open fun dispatchKeyEvent(event: KeyEvent): Boolean = when (event.action) {
        KeyEvent.ACTION_DOWN -> onKeyDown(event.keyCode, event)
        KeyEvent.ACTION_UP -> onKeyUp(event.keyCode, event)
        else -> false
    }
    open fun onTouchEvent(event: MotionEvent): Boolean = false
    open fun onHoverEvent(event: MotionEvent): Boolean = false
    open fun onGenericMotionEvent(event: MotionEvent): Boolean = false
    open fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = false
    open fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = false

    protected open fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {}
    protected open fun onAttachedToWindow() {}
    protected open fun onDetachedFromWindow() {}

    // -------- Tags --------
    fun getTag(key: Int): Any? = tags[key]
    fun setTag(key: Int, value: Any?) { tags[key] = value }

    fun setOnTouchListener(l: OnTouchListener?) {}
    fun interface OnTouchListener { fun onTouch(v: View, event: MotionEvent): Boolean }

    companion object {
        const val VISIBLE = 0
        const val INVISIBLE = 4
        const val GONE = 8
        const val NO_ID = -1
        const val LAYER_TYPE_NONE = 0
        const val LAYER_TYPE_SOFTWARE = 1
        const val LAYER_TYPE_HARDWARE = 2
        private val uiHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    }
}

/** Container für Ansichten (auf dem Desktop nur strukturell vorhanden). */
open class ViewGroup(context: Context) : View(context) {
    private val children = ArrayList<View>()
    val childCount: Int get() = children.size
    fun getChildAt(i: Int): View = children[i]
    fun addView(v: View) { children.add(v); v.parent = this }
    fun addView(v: View, lp: LayoutParams?) { v.layoutParams = lp; addView(v) }
    fun removeView(v: View) { children.remove(v); v.parent = null }
    fun removeAllViews() { children.forEach { it.parent = null }; children.clear() }

    open class LayoutParams(var width: Int, var height: Int) {
        companion object {
            const val MATCH_PARENT = -1
            const val WRAP_CONTENT = -2
        }
    }
}

/** Haptik gibt es auf dem Desktop nicht – Konstanten nur für die Kompatibilität. */
object HapticFeedbackConstants {
    const val LONG_PRESS = 0
    const val VIRTUAL_KEY = 1
    const val KEYBOARD_TAP = 3
    const val CLOCK_TICK = 4
    const val CONTEXT_CLICK = 6
    const val KEYBOARD_PRESS = 3
    const val KEYBOARD_RELEASE = 7
    const val TEXT_HANDLE_MOVE = 9
    const val GESTURE_START = 12
    const val GESTURE_END = 13
    const val CONFIRM = 16
    const val REJECT = 17
    const val SEGMENT_TICK = 26
    const val SEGMENT_FREQUENT_TICK = 27
    const val FLAG_IGNORE_VIEW_SETTING = 1
}

/**
 * Video-Eingabefläche eines Encoders. Auf dem Desktop gibt es keinen Hardware-Encoder;
 * MediaCodec wirft bereits beim Erzeugen eine verständliche Ausnahme, sodass diese Klasse nur
 * für die Kompilierung nötig ist.
 */
class Surface internal constructor() {
    fun lockHardwareCanvas(): Canvas = throw UnsupportedOperationException(android.media.MediaCodec.DESKTOP_MESSAGE)
    fun lockCanvas(dirty: android.graphics.Rect?): Canvas = lockHardwareCanvas()
    fun unlockCanvasAndPost(canvas: Canvas) {}
    fun release() {}
    val isValid: Boolean get() = false
}

/** Fenster der Activity (Systemleisten-Einstellungen sind auf dem Desktop wirkungslos). */
class Window internal constructor() {
    var isNavigationBarContrastEnforced: Boolean = false
    var isStatusBarContrastEnforced: Boolean = false
    var statusBarColor: Int = 0
    var navigationBarColor: Int = 0
    val decorView: View? get() = null
    val attributes: WindowManager.LayoutParams = WindowManager.LayoutParams()
    fun addFlags(flags: Int) {}
    fun clearFlags(flags: Int) {}
    fun setSoftInputMode(mode: Int) {}
}

object WindowManager {
    class LayoutParams {
        var screenBrightness: Float = -1f
        companion object {
            const val FLAG_KEEP_SCREEN_ON = 0x80
        }
    }
}
