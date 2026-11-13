@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package android.view

/**
 * MotionEvent-Nachbildung: Mehrere Zeiger mit Position, Druck, Werkzeugtyp, Ausrichtung, Neigung und Historie.
 * Erzeugt vom Desktop-Host (compat.PointerBridge) aus Compose-Zeigerereignissen.
 */
class MotionEvent private constructor() : InputEvent() {
    /** Vollständige Aktion inkl. Zeigerindex (wie getAction/setAction). */
    var action: Int = 0
    val actionMasked: Int get() = action and ACTION_MASK
    val actionIndex: Int get() = (action and ACTION_POINTER_INDEX_MASK) shr ACTION_POINTER_INDEX_SHIFT

    var downTime: Long = 0L
    override var eventTime: Long = 0L
    var buttonState: Int = 0
    var flags: Int = 0
    var metaState: Int = 0
    var source: Int = 0
    var edgeFlags: Int = 0

    // Zeigerdaten (aktueller Stand)
    private var count = 0
    private var ids = IntArray(4)
    private var tools = IntArray(4)
    private var xs = FloatArray(4)
    private var ys = FloatArray(4)
    private var pressures = FloatArray(4)
    private var sizes = FloatArray(4)
    private var orientations = FloatArray(4)
    private var tilts = FloatArray(4)
    private var scrollV = 0f
    private var scrollH = 0f

    // Historie: [pos][pointer]
    private var histCount = 0
    private var histTimes = LongArray(0)
    private var histX = FloatArray(0)
    private var histY = FloatArray(0)
    private var histP = FloatArray(0)
    private var histO = FloatArray(0)
    private var histT = FloatArray(0)

    val pointerCount: Int get() = count
    val historySize: Int get() = histCount
    val x: Float get() = xs[0]
    val y: Float get() = ys[0]
    val rawX: Float get() = xs[0] + rawOffsetX
    val rawY: Float get() = ys[0] + rawOffsetY
    val pressure: Float get() = pressures[0]
    val size: Float get() = sizes[0]
    val orientation: Float get() = orientations[0]
    val toolMajor: Float get() = sizes[0]
    val touchMajor: Float get() = sizes[0]
    internal var rawOffsetX = 0f
    internal var rawOffsetY = 0f

    fun getX(i: Int): Float = xs[i]
    fun getY(i: Int): Float = ys[i]
    fun getRawX(i: Int): Float = xs[i] + rawOffsetX
    fun getRawY(i: Int): Float = ys[i] + rawOffsetY
    fun getPressure(i: Int): Float = pressures[i]
    fun getSize(i: Int): Float = sizes[i]
    fun getTouchMajor(i: Int): Float = sizes[i]
    fun getTouchMinor(i: Int): Float = sizes[i]
    fun getToolMajor(i: Int): Float = sizes[i]
    fun getOrientation(i: Int): Float = orientations[i]
    fun getPointerId(i: Int): Int = ids[i]
    fun getToolType(i: Int): Int = tools[i]
    fun findPointerIndex(id: Int): Int { for (i in 0 until count) if (ids[i] == id) return i; return -1 }
    fun isButtonPressed(button: Int): Boolean = button != 0 && (buttonState and button) == button
    fun getAxisValue(axis: Int): Float = getAxisValue(axis, 0)

    fun getAxisValue(axis: Int, i: Int): Float = when (axis) {
        AXIS_X -> xs[i]
        AXIS_Y -> ys[i]
        AXIS_PRESSURE -> pressures[i]
        AXIS_SIZE, AXIS_TOUCH_MAJOR, AXIS_TOUCH_MINOR, AXIS_TOOL_MAJOR, AXIS_TOOL_MINOR -> sizes[i]
        AXIS_ORIENTATION -> orientations[i]
        AXIS_TILT -> tilts[i]
        AXIS_VSCROLL -> scrollV
        AXIS_HSCROLL -> scrollH
        else -> 0f
    }

    fun getHistoricalEventTime(pos: Int): Long = histTimes[pos]
    fun getHistoricalX(pos: Int): Float = histX[pos * count]
    fun getHistoricalY(pos: Int): Float = histY[pos * count]
    fun getHistoricalX(i: Int, pos: Int): Float = histX[pos * count + i]
    fun getHistoricalY(i: Int, pos: Int): Float = histY[pos * count + i]
    fun getHistoricalPressure(pos: Int): Float = histP[pos * count]
    fun getHistoricalPressure(i: Int, pos: Int): Float = histP[pos * count + i]
    fun getHistoricalOrientation(pos: Int): Float = histO[pos * count]
    fun getHistoricalOrientation(i: Int, pos: Int): Float = histO[pos * count + i]
    fun getHistoricalSize(i: Int, pos: Int): Float = sizes[i]
    fun getHistoricalTouchMajor(i: Int, pos: Int): Float = sizes[i]
    fun getHistoricalAxisValue(axis: Int, pos: Int): Float = getHistoricalAxisValue(axis, 0, pos)
    fun getHistoricalAxisValue(axis: Int, i: Int, pos: Int): Float = when (axis) {
        AXIS_X -> histX[pos * count + i]
        AXIS_Y -> histY[pos * count + i]
        AXIS_PRESSURE -> histP[pos * count + i]
        AXIS_ORIENTATION -> histO[pos * count + i]
        AXIS_TILT -> histT[pos * count + i]
        else -> getAxisValue(axis, i)
    }

    fun offsetLocation(dx: Float, dy: Float) {
        for (i in 0 until count) { xs[i] += dx; ys[i] += dy }
        for (k in histX.indices) { histX[k] += dx; histY[k] += dy }
        rawOffsetX -= dx; rawOffsetY -= dy
    }

    fun setLocation(x: Float, y: Float) = offsetLocation(x - xs[0], y - ys[0])

    fun recycle() {}

    fun isFromSource(s: Int): Boolean = (source and s) == s

    override fun toString(): String = "MotionEvent(action=$action, count=$count, x=${xs[0]}, y=${ys[0]}, tool=${tools[0]})"

    /** Baut MotionEvents (für den Desktop-Host). */
    class Builder(pointers: Int) {
        private val e = MotionEvent().apply { ensure(pointers.coerceAtLeast(1)); count = pointers }
        fun action(a: Int) = apply { e.action = a }
        fun times(down: Long, time: Long) = apply { e.downTime = down; e.eventTime = time }
        fun buttons(b: Int) = apply { e.buttonState = b }
        fun meta(m: Int) = apply { e.metaState = m }
        fun scroll(v: Float, h: Float) = apply { e.scrollV = v; e.scrollH = h }
        fun rawOffset(dx: Float, dy: Float) = apply { e.rawOffsetX = dx; e.rawOffsetY = dy }
        fun pointer(i: Int, id: Int, tool: Int, x: Float, y: Float, pressure: Float, size: Float = 0.05f,
                    orientation: Float = 0f, tilt: Float = 0f) = apply {
            e.ids[i] = id; e.tools[i] = tool; e.xs[i] = x; e.ys[i] = y; e.pressures[i] = pressure
            e.sizes[i] = size; e.orientations[i] = orientation; e.tilts[i] = tilt
        }
        /** Historie: je Position [historyTimes] Koordinaten aller Zeiger in Zeiger-Reihenfolge. */
        fun history(times: LongArray, x: FloatArray, y: FloatArray, p: FloatArray) = apply {
            e.histCount = times.size; e.histTimes = times; e.histX = x; e.histY = y; e.histP = p
            e.histO = FloatArray(x.size); e.histT = FloatArray(x.size)
        }
        fun build(): MotionEvent = e
    }

    private fun ensure(n: Int) {
        if (ids.size >= n) return
        ids = ids.copyOf(n); tools = tools.copyOf(n); xs = xs.copyOf(n); ys = ys.copyOf(n)
        pressures = pressures.copyOf(n); sizes = sizes.copyOf(n); orientations = orientations.copyOf(n); tilts = tilts.copyOf(n)
    }

    companion object {
        const val ACTION_MASK = 0xff
        const val ACTION_POINTER_INDEX_MASK = 0xff00
        const val ACTION_POINTER_INDEX_SHIFT = 8
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val ACTION_OUTSIDE = 4
        const val ACTION_POINTER_DOWN = 5
        const val ACTION_POINTER_UP = 6
        const val ACTION_HOVER_MOVE = 7
        const val ACTION_SCROLL = 8
        const val ACTION_HOVER_ENTER = 9
        const val ACTION_HOVER_EXIT = 10
        const val ACTION_BUTTON_PRESS = 11
        const val ACTION_BUTTON_RELEASE = 12

        const val AXIS_X = 0
        const val AXIS_Y = 1
        const val AXIS_PRESSURE = 2
        const val AXIS_SIZE = 3
        const val AXIS_TOUCH_MAJOR = 4
        const val AXIS_TOUCH_MINOR = 5
        const val AXIS_TOOL_MAJOR = 6
        const val AXIS_TOOL_MINOR = 7
        const val AXIS_ORIENTATION = 8
        const val AXIS_VSCROLL = 9
        const val AXIS_HSCROLL = 10
        const val AXIS_TILT = 25
        const val AXIS_DISTANCE = 24

        const val TOOL_TYPE_UNKNOWN = 0
        const val TOOL_TYPE_FINGER = 1
        const val TOOL_TYPE_STYLUS = 2
        const val TOOL_TYPE_MOUSE = 3
        const val TOOL_TYPE_ERASER = 4
        const val TOOL_TYPE_PALM = 5

        const val BUTTON_PRIMARY = 1
        const val BUTTON_SECONDARY = 2
        const val BUTTON_TERTIARY = 4
        const val BUTTON_BACK = 8
        const val BUTTON_FORWARD = 16
        const val BUTTON_STYLUS_PRIMARY = 32
        const val BUTTON_STYLUS_SECONDARY = 64

        const val FLAG_WINDOW_IS_OBSCURED = 0x1
        const val FLAG_CANCELED = 0x20
        const val EDGE_TOP = 1

        /** Kopie eines Ereignisses (inkl. Historie). */
        @JvmStatic fun obtain(o: MotionEvent): MotionEvent = MotionEvent().also { e ->
            e.action = o.action; e.downTime = o.downTime; e.eventTime = o.eventTime; e.buttonState = o.buttonState
            e.flags = o.flags; e.metaState = o.metaState; e.source = o.source; e.count = o.count
            e.ids = o.ids.copyOf(); e.tools = o.tools.copyOf(); e.xs = o.xs.copyOf(); e.ys = o.ys.copyOf()
            e.pressures = o.pressures.copyOf(); e.sizes = o.sizes.copyOf(); e.orientations = o.orientations.copyOf()
            e.tilts = o.tilts.copyOf(); e.scrollV = o.scrollV; e.scrollH = o.scrollH
            e.histCount = o.histCount; e.histTimes = o.histTimes.copyOf(); e.histX = o.histX.copyOf(); e.histY = o.histY.copyOf()
            e.histP = o.histP.copyOf(); e.histO = o.histO.copyOf(); e.histT = o.histT.copyOf()
            e.rawOffsetX = o.rawOffsetX; e.rawOffsetY = o.rawOffsetY
        }

        @JvmStatic fun obtain(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float, metaState: Int): MotionEvent =
            Builder(1).action(action).times(downTime, eventTime).meta(metaState)
                .pointer(0, 0, TOOL_TYPE_FINGER, x, y, 1f).build()

        @JvmStatic fun obtainNoHistory(o: MotionEvent): MotionEvent = obtain(o).also {
            it.histCount = 0; it.histTimes = LongArray(0); it.histX = FloatArray(0); it.histY = FloatArray(0)
            it.histP = FloatArray(0); it.histO = FloatArray(0); it.histT = FloatArray(0)
        }

        @JvmStatic fun actionToString(action: Int): String = "ACTION_$action"
    }
}

abstract class InputEvent {
    abstract val eventTime: Long
}
