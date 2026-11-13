package android.graphics

import org.jetbrains.skia.PathDirection
import org.jetbrains.skia.PathFillMode
import org.jetbrains.skia.PathOp
import org.jetbrains.skia.RRect

/** android.graphics.Path auf Basis von org.jetbrains.skia.Path. */
open class Path(skia: org.jetbrains.skia.Path) {
    /** Das zugrunde liegende Skia-Objekt. */
    var skia: org.jetbrains.skia.Path = skia
        internal set

    constructor() : this(org.jetbrains.skia.Path())
    constructor(src: Path?) : this(org.jetbrains.skia.Path().also { p -> if (src != null) p.addPath(src.skia, false) }) {
        if (src != null) skia.fillMode = src.skia.fillMode
    }

    enum class Direction(internal val sk: PathDirection) { CW(PathDirection.CLOCKWISE), CCW(PathDirection.COUNTER_CLOCKWISE) }

    enum class FillType(internal val sk: PathFillMode) {
        WINDING(PathFillMode.WINDING), EVEN_ODD(PathFillMode.EVEN_ODD),
        INVERSE_WINDING(PathFillMode.INVERSE_WINDING), INVERSE_EVEN_ODD(PathFillMode.INVERSE_EVEN_ODD),
    }

    enum class Op(internal val sk: PathOp) {
        DIFFERENCE(PathOp.DIFFERENCE), INTERSECT(PathOp.INTERSECT), UNION(PathOp.UNION),
        XOR(PathOp.XOR), REVERSE_DIFFERENCE(PathOp.REVERSE_DIFFERENCE),
    }

    var fillType: FillType
        get() = FillType.entries.first { it.sk == skia.fillMode }
        set(value) { skia.fillMode = value.sk }

    @get:JvmName("isInverseFillTypeProp")
    val isInverseFillType: Boolean
        get() = fillType == FillType.INVERSE_WINDING || fillType == FillType.INVERSE_EVEN_ODD
    fun isInverseFillType(): Boolean = isInverseFillType

    fun toggleInverseFillType() {
        fillType = when (fillType) {
            FillType.WINDING -> FillType.INVERSE_WINDING
            FillType.EVEN_ODD -> FillType.INVERSE_EVEN_ODD
            FillType.INVERSE_WINDING -> FillType.WINDING
            FillType.INVERSE_EVEN_ODD -> FillType.EVEN_ODD
        }
    }

    @get:JvmName("isEmptyProp")
    val isEmpty: Boolean get() = skia.isEmpty
    fun isEmpty(): Boolean = skia.isEmpty
    @get:JvmName("isConvexProp")
    val isConvex: Boolean get() = skia.isConvex
    fun isConvex(): Boolean = skia.isConvex

    fun isRect(rect: RectF?): Boolean {
        val r = skia.isRect ?: return false
        rect?.set(r.left, r.top, r.right, r.bottom)
        return true
    }

    /** Wie Android: leert den Pfad, die Füllregel bleibt erhalten. */
    fun reset() { val ft = skia.fillMode; skia.reset(); skia.fillMode = ft }
    fun rewind() { val ft = skia.fillMode; skia.rewind(); skia.fillMode = ft }
    fun set(src: Path) {
        if (src === this) return
        skia.reset()
        skia.addPath(src.skia, false)
        skia.fillMode = src.skia.fillMode
    }

    fun incReserve(extraPtCount: Int) { skia.incReserve(extraPtCount) }

    fun moveTo(x: Float, y: Float) { skia.moveTo(x, y) }
    fun rMoveTo(dx: Float, dy: Float) { skia.rMoveTo(dx, dy) }
    fun lineTo(x: Float, y: Float) { skia.lineTo(x, y) }
    fun rLineTo(dx: Float, dy: Float) { skia.rLineTo(dx, dy) }
    fun quadTo(x1: Float, y1: Float, x2: Float, y2: Float) { skia.quadTo(x1, y1, x2, y2) }
    fun rQuadTo(dx1: Float, dy1: Float, dx2: Float, dy2: Float) { skia.rQuadTo(dx1, dy1, dx2, dy2) }
    fun conicTo(x1: Float, y1: Float, x2: Float, y2: Float, weight: Float) { skia.conicTo(x1, y1, x2, y2, weight) }
    fun rConicTo(dx1: Float, dy1: Float, dx2: Float, dy2: Float, weight: Float) { skia.rConicTo(dx1, dy1, dx2, dy2, weight) }
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) { skia.cubicTo(x1, y1, x2, y2, x3, y3) }
    fun rCubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) { skia.rCubicTo(x1, y1, x2, y2, x3, y3) }

    fun arcTo(oval: RectF, startAngle: Float, sweepAngle: Float, forceMoveTo: Boolean) {
        skia.arcTo(oval.toSk(), startAngle, sweepAngle, forceMoveTo)
    }
    fun arcTo(oval: RectF, startAngle: Float, sweepAngle: Float) = arcTo(oval, startAngle, sweepAngle, false)
    fun arcTo(left: Float, top: Float, right: Float, bottom: Float, startAngle: Float, sweepAngle: Float, forceMoveTo: Boolean) =
        arcTo(RectF(left, top, right, bottom), startAngle, sweepAngle, forceMoveTo)

    fun close() { skia.closePath() }

    fun addRect(rect: RectF, dir: Direction) { skia.addRect(rect.toSk(), dir.sk, 0) }
    fun addRect(left: Float, top: Float, right: Float, bottom: Float, dir: Direction) =
        addRect(RectF(left, top, right, bottom), dir)
    fun addOval(oval: RectF, dir: Direction) { skia.addOval(oval.toSk(), dir.sk, 1) }
    fun addOval(left: Float, top: Float, right: Float, bottom: Float, dir: Direction) =
        addOval(RectF(left, top, right, bottom), dir)
    fun addCircle(x: Float, y: Float, radius: Float, dir: Direction) { skia.addCircle(x, y, radius, dir.sk) }
    fun addArc(oval: RectF, startAngle: Float, sweepAngle: Float) { skia.addArc(oval.toSk(), startAngle, sweepAngle) }
    fun addArc(left: Float, top: Float, right: Float, bottom: Float, startAngle: Float, sweepAngle: Float) =
        addArc(RectF(left, top, right, bottom), startAngle, sweepAngle)
    fun addRoundRect(rect: RectF, rx: Float, ry: Float, dir: Direction) {
        skia.addRRect(RRect.makeLTRB(rect.left, rect.top, rect.right, rect.bottom, rx, ry), dir.sk, 0)
    }
    fun addRoundRect(left: Float, top: Float, right: Float, bottom: Float, rx: Float, ry: Float, dir: Direction) =
        addRoundRect(RectF(left, top, right, bottom), rx, ry, dir)
    fun addRoundRect(rect: RectF, radii: FloatArray, dir: Direction) {
        skia.addRRect(RRect.makeComplexLTRB(rect.left, rect.top, rect.right, rect.bottom, radii.copyOf(8)), dir.sk, 0)
    }
    fun addRoundRect(left: Float, top: Float, right: Float, bottom: Float, radii: FloatArray, dir: Direction) =
        addRoundRect(RectF(left, top, right, bottom), radii, dir)

    fun addPath(src: Path) { skia.addPath(src.skia, false) }
    fun addPath(src: Path, dx: Float, dy: Float) { skia.addPath(src.skia, dx, dy, false) }
    fun addPath(src: Path, matrix: Matrix) { skia.addPath(src.skia, matrix.toSkia(), false) }

    fun offset(dx: Float, dy: Float) { skia.offset(dx, dy, null) }
    fun offset(dx: Float, dy: Float, dst: Path?) {
        if (dst == null) offset(dx, dy) else { dst.set(this); dst.offset(dx, dy) }
    }

    fun transform(matrix: Matrix) { skia.transform(matrix.toSkia(), true) }
    fun transform(matrix: Matrix, dst: Path?) {
        if (dst == null) transform(matrix) else { dst.set(this); dst.transform(matrix) }
    }

    fun setLastPoint(dx: Float, dy: Float) { skia.setLastPt(dx, dy) }

    @Suppress("UNUSED_PARAMETER")
    fun computeBounds(bounds: RectF, exact: Boolean) {
        val b = skia.bounds
        bounds.set(b.left, b.top, b.right, b.bottom)
    }

    fun op(path: Path, op: Op): Boolean = op(this, path, op)
    fun op(path1: Path, path2: Path, op: Op): Boolean {
        val r = org.jetbrains.skia.Path.makeCombining(path1.skia, path2.skia, op.sk) ?: return false
        skia = r
        return true
    }

    /** Annäherung durch Liniensegmente: [Anteil, x, y]-Tripel wie Path.approximate. */
    fun approximate(acceptableError: Float): FloatArray {
        val out = ArrayList<Float>()
        val pm = org.jetbrains.skia.PathMeasure(skia, false, 1f)
        val segs = ArrayList<Pair<Float, org.jetbrains.skia.Point>>()
        var total = 0f
        do {
            val len = pm.length
            val step = maxOf(acceptableError, len / 256f).coerceAtLeast(0.01f)
            var d = 0f
            while (d < len) { pm.getPosition(d)?.let { segs += (total + d) to it }; d += step }
            pm.getPosition(len)?.let { segs += (total + len) to it }
            total += len
        } while (pm.nextContour())
        for ((d, p) in segs) { out += if (total > 0f) d / total else 0f; out += p.x; out += p.y }
        return out.toFloatArray()
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** android.graphics.PathMeasure. */
open class PathMeasure() {
    private var pm = org.jetbrains.skia.PathMeasure()

    constructor(path: Path?, forceClosed: Boolean) : this() { setPath(path, forceClosed) }

    fun setPath(path: Path?, forceClosed: Boolean) {
        pm = if (path == null) org.jetbrains.skia.PathMeasure() else org.jetbrains.skia.PathMeasure(path.skia, forceClosed, 1f)
    }

    val length: Float get() = pm.length

    fun getPosTan(distance: Float, pos: FloatArray?, tan: FloatArray?): Boolean {
        val p = pm.getPosition(distance) ?: return false
        val t = pm.getTangent(distance) ?: return false
        pos?.let { it[0] = p.x; it[1] = p.y }
        tan?.let { it[0] = t.x; it[1] = t.y }
        return true
    }

    fun getSegment(startD: Float, stopD: Float, dst: Path, startWithMoveTo: Boolean): Boolean =
        pm.getSegment(startD, stopD, dst.skia, startWithMoveTo)

    fun isClosed(): Boolean = pm.isClosed
    fun nextContour(): Boolean = pm.nextContour()
}
