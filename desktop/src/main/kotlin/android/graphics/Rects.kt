package android.graphics

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** android.graphics.Rect (Int, halboffen: rechts/unten exklusiv). */
class Rect() {
    @JvmField var left: Int = 0
    @JvmField var top: Int = 0
    @JvmField var right: Int = 0
    @JvmField var bottom: Int = 0

    constructor(left: Int, top: Int, right: Int, bottom: Int) : this() { set(left, top, right, bottom) }
    constructor(r: Rect?) : this() { if (r != null) set(r) }

    fun width(): Int = right - left
    fun height(): Int = bottom - top
    fun centerX(): Int = (left + right) shr 1
    fun centerY(): Int = (top + bottom) shr 1
    fun exactCenterX(): Float = (left + right) * 0.5f
    fun exactCenterY(): Float = (top + bottom) * 0.5f
    fun isEmpty(): Boolean = left >= right || top >= bottom
    @get:JvmName("isEmptyProp")
    val isEmpty: Boolean get() = isEmpty()
    fun setEmpty() { left = 0; top = 0; right = 0; bottom = 0 }

    fun set(left: Int, top: Int, right: Int, bottom: Int) { this.left = left; this.top = top; this.right = right; this.bottom = bottom }
    fun set(src: Rect) = set(src.left, src.top, src.right, src.bottom)

    fun offset(dx: Int, dy: Int) { left += dx; top += dy; right += dx; bottom += dy }
    fun offsetTo(newLeft: Int, newTop: Int) { right += newLeft - left; bottom += newTop - top; left = newLeft; top = newTop }
    fun inset(dx: Int, dy: Int) { left += dx; top += dy; right -= dx; bottom -= dy }
    fun inset(l: Int, t: Int, r: Int, b: Int) { left += l; top += t; right -= r; bottom -= b }

    fun contains(x: Int, y: Int): Boolean = left < right && top < bottom && x >= left && x < right && y >= top && y < bottom
    fun contains(l: Int, t: Int, r: Int, b: Int): Boolean =
        left < right && top < bottom && left <= l && top <= t && right >= r && bottom >= b
    fun contains(r: Rect): Boolean = contains(r.left, r.top, r.right, r.bottom)

    fun intersect(l: Int, t: Int, r: Int, b: Int): Boolean {
        if (left < r && l < right && top < b && t < bottom) {
            if (left < l) left = l
            if (top < t) top = t
            if (right > r) right = r
            if (bottom > b) bottom = b
            return true
        }
        return false
    }
    fun intersect(r: Rect): Boolean = intersect(r.left, r.top, r.right, r.bottom)
    fun intersects(l: Int, t: Int, r: Int, b: Int): Boolean = left < r && l < right && top < b && t < bottom
    fun setIntersect(a: Rect, b: Rect): Boolean {
        if (a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom) {
            set(maxOf(a.left, b.left), maxOf(a.top, b.top), minOf(a.right, b.right), minOf(a.bottom, b.bottom)); return true
        }
        return false
    }

    fun union(l: Int, t: Int, r: Int, b: Int) {
        if (l < r && t < b) {
            if (left < right && top < bottom) {
                if (left > l) left = l
                if (top > t) top = t
                if (right < r) right = r
                if (bottom < b) bottom = b
            } else set(l, t, r, b)
        }
    }
    fun union(r: Rect) = union(r.left, r.top, r.right, r.bottom)
    fun union(x: Int, y: Int) {
        if (x < left) left = x else if (x > right) right = x
        if (y < top) top = y else if (y > bottom) bottom = y
    }

    fun sort() {
        if (left > right) { val t = left; left = right; right = t }
        if (top > bottom) { val t = top; top = bottom; bottom = t }
    }

    fun toShortString(): String = "[$left,$top][$right,$bottom]"
    fun flattenToString(): String = "$left $top $right $bottom"

    override fun equals(other: Any?): Boolean =
        other is Rect && left == other.left && top == other.top && right == other.right && bottom == other.bottom
    override fun hashCode(): Int = ((left * 31 + top) * 31 + right) * 31 + bottom
    override fun toString(): String = "Rect($left, $top - $right, $bottom)"

    internal fun toSk(): org.jetbrains.skia.Rect =
        org.jetbrains.skia.Rect.makeLTRB(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    companion object {
        @JvmStatic fun intersects(a: Rect, b: Rect): Boolean =
            a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
    }
}

/** android.graphics.RectF. */
class RectF() {
    @JvmField var left: Float = 0f
    @JvmField var top: Float = 0f
    @JvmField var right: Float = 0f
    @JvmField var bottom: Float = 0f

    constructor(left: Float, top: Float, right: Float, bottom: Float) : this() { set(left, top, right, bottom) }
    constructor(r: RectF?) : this() { if (r != null) set(r) }
    constructor(r: Rect?) : this() { if (r != null) set(r) }

    fun width(): Float = right - left
    fun height(): Float = bottom - top
    fun centerX(): Float = (left + right) * 0.5f
    fun centerY(): Float = (top + bottom) * 0.5f
    fun isEmpty(): Boolean = !(left < right && top < bottom)
    @get:JvmName("isEmptyProp")
    val isEmpty: Boolean get() = isEmpty()
    fun setEmpty() { left = 0f; top = 0f; right = 0f; bottom = 0f }

    fun set(left: Float, top: Float, right: Float, bottom: Float) { this.left = left; this.top = top; this.right = right; this.bottom = bottom }
    fun set(src: RectF) = set(src.left, src.top, src.right, src.bottom)
    fun set(src: Rect) = set(src.left.toFloat(), src.top.toFloat(), src.right.toFloat(), src.bottom.toFloat())

    fun offset(dx: Float, dy: Float) { left += dx; top += dy; right += dx; bottom += dy }
    fun offsetTo(newLeft: Float, newTop: Float) { right += newLeft - left; bottom += newTop - top; left = newLeft; top = newTop }
    fun inset(dx: Float, dy: Float) { left += dx; top += dy; right -= dx; bottom -= dy }

    fun contains(x: Float, y: Float): Boolean = left < right && top < bottom && x >= left && x < right && y >= top && y < bottom
    fun contains(l: Float, t: Float, r: Float, b: Float): Boolean =
        left < right && top < bottom && left <= l && top <= t && right >= r && bottom >= b
    fun contains(r: RectF): Boolean = contains(r.left, r.top, r.right, r.bottom)

    fun intersect(l: Float, t: Float, r: Float, b: Float): Boolean {
        if (left < r && l < right && top < b && t < bottom) {
            if (left < l) left = l
            if (top < t) top = t
            if (right > r) right = r
            if (bottom > b) bottom = b
            return true
        }
        return false
    }
    fun intersect(r: RectF): Boolean = intersect(r.left, r.top, r.right, r.bottom)
    fun intersects(l: Float, t: Float, r: Float, b: Float): Boolean = left < r && l < right && top < b && t < bottom
    fun setIntersect(a: RectF, b: RectF): Boolean {
        if (a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom) {
            set(maxOf(a.left, b.left), maxOf(a.top, b.top), minOf(a.right, b.right), minOf(a.bottom, b.bottom)); return true
        }
        return false
    }

    fun union(l: Float, t: Float, r: Float, b: Float) {
        if (l < r && t < b) {
            if (left < right && top < bottom) {
                if (left > l) left = l
                if (top > t) top = t
                if (right < r) right = r
                if (bottom < b) bottom = b
            } else set(l, t, r, b)
        }
    }
    fun union(r: RectF) = union(r.left, r.top, r.right, r.bottom)
    fun union(x: Float, y: Float) {
        if (x < left) left = x else if (x > right) right = x
        if (y < top) top = y else if (y > bottom) bottom = y
    }

    fun round(dst: Rect) = dst.set(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())
    fun roundOut(dst: Rect) = dst.set(floor(left).toInt(), floor(top).toInt(), ceil(right).toInt(), ceil(bottom).toInt())

    fun sort() {
        if (left > right) { val t = left; left = right; right = t }
        if (top > bottom) { val t = top; top = bottom; bottom = t }
    }

    fun toShortString(): String = "[$left,$top][$right,$bottom]"

    override fun equals(other: Any?): Boolean =
        other is RectF && left == other.left && top == other.top && right == other.right && bottom == other.bottom
    override fun hashCode(): Int = ((left.hashCode() * 31 + top.hashCode()) * 31 + right.hashCode()) * 31 + bottom.hashCode()
    override fun toString(): String = "RectF($left, $top, $right, $bottom)"

    internal fun toSk(): org.jetbrains.skia.Rect = org.jetbrains.skia.Rect.makeLTRB(left, top, right, bottom)

    companion object {
        @JvmStatic fun intersects(a: RectF, b: RectF): Boolean =
            a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
    }
}

/** android.graphics.Point. */
class Point() {
    @JvmField var x: Int = 0
    @JvmField var y: Int = 0
    constructor(x: Int, y: Int) : this() { this.x = x; this.y = y }
    constructor(src: Point) : this(src.x, src.y)
    fun set(x: Int, y: Int) { this.x = x; this.y = y }
    fun negate() { x = -x; y = -y }
    fun offset(dx: Int, dy: Int) { x += dx; y += dy }
    fun equals(x: Int, y: Int): Boolean = this.x == x && this.y == y
    override fun equals(other: Any?): Boolean = other is Point && other.x == x && other.y == y
    override fun hashCode(): Int = 31 * x + y
    override fun toString(): String = "Point($x, $y)"
}

/** android.graphics.PointF. */
class PointF() {
    @JvmField var x: Float = 0f
    @JvmField var y: Float = 0f
    constructor(x: Float, y: Float) : this() { this.x = x; this.y = y }
    constructor(p: PointF) : this(p.x, p.y)
    constructor(p: Point) : this(p.x.toFloat(), p.y.toFloat())
    fun set(x: Float, y: Float) { this.x = x; this.y = y }
    fun set(p: PointF) { x = p.x; y = p.y }
    fun negate() { x = -x; y = -y }
    fun offset(dx: Float, dy: Float) { x += dx; y += dy }
    fun equals(x: Float, y: Float): Boolean = this.x == x && this.y == y
    fun length(): Float = length(x, y)
    override fun equals(other: Any?): Boolean = other is PointF && other.x == x && other.y == y
    override fun hashCode(): Int = 31 * x.hashCode() + y.hashCode()
    override fun toString(): String = "PointF($x, $y)"

    companion object {
        @JvmStatic fun length(x: Float, y: Float): Float = kotlin.math.hypot(x, y)
    }
}
