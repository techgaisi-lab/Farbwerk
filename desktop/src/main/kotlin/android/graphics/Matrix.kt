package android.graphics

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * android.graphics.Matrix: 3×3, zeilenweise (MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, MTRANS_Y,
 * MPERSP_0..2) – identisch mit SkMatrix. post*: M' = T·M, pre*: M' = M·T.
 */
open class Matrix() {
    @JvmField internal val v = FloatArray(9).also { it[0] = 1f; it[4] = 1f; it[8] = 1f }

    constructor(src: Matrix?) : this() { if (src != null) set(src) }

    enum class ScaleToFit { FILL, START, CENTER, END }

    @get:JvmName("isIdentityProp")
    val isIdentity: Boolean get() = isIdentity()
    @get:JvmName("isAffineProp")
    val isAffine: Boolean get() = isAffine()

    fun isIdentity(): Boolean =
        v[0] == 1f && v[1] == 0f && v[2] == 0f && v[3] == 0f && v[4] == 1f && v[5] == 0f && v[6] == 0f && v[7] == 0f && v[8] == 1f

    fun isAffine(): Boolean = v[6] == 0f && v[7] == 0f && v[8] == 1f

    fun rectStaysRect(): Boolean {
        if (!isAffine()) return false
        val m00 = v[0]; val m01 = v[1]; val m10 = v[3]; val m11 = v[4]
        return if (m01 == 0f && m10 == 0f) m00 != 0f && m11 != 0f
        else m00 == 0f && m11 == 0f && m01 != 0f && m10 != 0f
    }

    fun set(src: Matrix?) { if (src == null) reset() else src.v.copyInto(v) }
    fun reset() { v.fill(0f); v[0] = 1f; v[4] = 1f; v[8] = 1f }

    fun setValues(values: FloatArray) { values.copyInto(v, 0, 0, 9) }
    fun getValues(values: FloatArray) { v.copyInto(values, 0, 0, 9) }

    // ---------- set ----------
    fun setTranslate(dx: Float, dy: Float) { reset(); v[2] = dx; v[5] = dy }
    fun setScale(sx: Float, sy: Float) { reset(); v[0] = sx; v[4] = sy }
    fun setScale(sx: Float, sy: Float, px: Float, py: Float) {
        reset(); v[0] = sx; v[4] = sy; v[2] = px - sx * px; v[5] = py - sy * py
    }
    fun setRotate(degrees: Float) { setRotate(degrees, 0f, 0f) }
    fun setRotate(degrees: Float, px: Float, py: Float) {
        val (s, c) = sinCos(degrees)
        setSinCos(s, c, px, py)
    }
    fun setSinCos(sinValue: Float, cosValue: Float) = setSinCos(sinValue, cosValue, 0f, 0f)
    fun setSinCos(sinValue: Float, cosValue: Float, px: Float, py: Float) {
        reset()
        v[0] = cosValue; v[1] = -sinValue; v[3] = sinValue; v[4] = cosValue
        v[2] = sinValue * py + (1f - cosValue) * px
        v[5] = -sinValue * px + (1f - cosValue) * py
    }
    fun setSkew(kx: Float, ky: Float) { reset(); v[1] = kx; v[3] = ky }
    fun setSkew(kx: Float, ky: Float, px: Float, py: Float) {
        reset(); v[1] = kx; v[3] = ky; v[2] = -kx * py; v[5] = -ky * px
    }
    fun setConcat(a: Matrix, b: Matrix): Boolean { val r = mul(a.v, b.v); r.copyInto(v); return true }

    // ---------- pre ----------
    fun preTranslate(dx: Float, dy: Float): Boolean = preConcat(make { setTranslate(dx, dy) })
    fun preScale(sx: Float, sy: Float): Boolean = preConcat(make { setScale(sx, sy) })
    fun preScale(sx: Float, sy: Float, px: Float, py: Float): Boolean = preConcat(make { setScale(sx, sy, px, py) })
    fun preRotate(degrees: Float): Boolean = preConcat(make { setRotate(degrees) })
    fun preRotate(degrees: Float, px: Float, py: Float): Boolean = preConcat(make { setRotate(degrees, px, py) })
    fun preSkew(kx: Float, ky: Float): Boolean = preConcat(make { setSkew(kx, ky) })
    fun preSkew(kx: Float, ky: Float, px: Float, py: Float): Boolean = preConcat(make { setSkew(kx, ky, px, py) })
    fun preConcat(other: Matrix): Boolean { mul(v, other.v).copyInto(v); return true }

    // ---------- post ----------
    fun postTranslate(dx: Float, dy: Float): Boolean {
        if (isAffine()) { v[2] += dx; v[5] += dy; return true }
        return postConcat(make { setTranslate(dx, dy) })
    }
    fun postScale(sx: Float, sy: Float): Boolean = postConcat(make { setScale(sx, sy) })
    fun postScale(sx: Float, sy: Float, px: Float, py: Float): Boolean = postConcat(make { setScale(sx, sy, px, py) })
    fun postRotate(degrees: Float): Boolean = postConcat(make { setRotate(degrees) })
    fun postRotate(degrees: Float, px: Float, py: Float): Boolean = postConcat(make { setRotate(degrees, px, py) })
    fun postSkew(kx: Float, ky: Float): Boolean = postConcat(make { setSkew(kx, ky) })
    fun postSkew(kx: Float, ky: Float, px: Float, py: Float): Boolean = postConcat(make { setSkew(kx, ky, px, py) })
    fun postConcat(other: Matrix): Boolean { mul(other.v, v).copyInto(v); return true }

    // ---------- Inverse ----------
    fun invert(inverse: Matrix?): Boolean {
        val a = v
        val det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
        if (det == 0f || !det.isFinite() || abs(det.toDouble()) < 1e-30) return false
        if (inverse == null) return true
        val inv = 1.0 / det.toDouble()
        val r = FloatArray(9)
        r[0] = ((a[4] * a[8] - a[5] * a[7]) * inv).toFloat()
        r[1] = ((a[2] * a[7] - a[1] * a[8]) * inv).toFloat()
        r[2] = ((a[1] * a[5] - a[2] * a[4]) * inv).toFloat()
        r[3] = ((a[5] * a[6] - a[3] * a[8]) * inv).toFloat()
        r[4] = ((a[0] * a[8] - a[2] * a[6]) * inv).toFloat()
        r[5] = ((a[2] * a[3] - a[0] * a[5]) * inv).toFloat()
        r[6] = ((a[3] * a[7] - a[4] * a[6]) * inv).toFloat()
        r[7] = ((a[1] * a[6] - a[0] * a[7]) * inv).toFloat()
        r[8] = ((a[0] * a[4] - a[1] * a[3]) * inv).toFloat()
        if (isAffine()) { r[6] = 0f; r[7] = 0f; r[8] = 1f }
        r.copyInto(inverse.v)
        return true
    }

    // ---------- Abbilden ----------
    fun mapPoints(dst: FloatArray, dstIndex: Int, src: FloatArray, srcIndex: Int, pointCount: Int) {
        val a = v
        val affine = isAffine()
        // src und dst dürfen dasselbe Array sein → zwischenspeichern
        for (i in 0 until pointCount) {
            val x = src[srcIndex + 2 * i]
            val y = src[srcIndex + 2 * i + 1]
            var nx = a[0] * x + a[1] * y + a[2]
            var ny = a[3] * x + a[4] * y + a[5]
            if (!affine) {
                val w = a[6] * x + a[7] * y + a[8]
                val iw = if (w != 0f) 1f / w else 0f
                nx *= iw; ny *= iw
            }
            dst[dstIndex + 2 * i] = nx
            dst[dstIndex + 2 * i + 1] = ny
        }
    }
    fun mapPoints(dst: FloatArray, src: FloatArray) = mapPoints(dst, 0, src, 0, src.size shr 1)
    fun mapPoints(pts: FloatArray) = mapPoints(pts, 0, pts, 0, pts.size shr 1)

    fun mapVectors(dst: FloatArray, dstIndex: Int, src: FloatArray, srcIndex: Int, vectorCount: Int) {
        val a = v
        if (isAffine()) {
            for (i in 0 until vectorCount) {
                val x = src[srcIndex + 2 * i]; val y = src[srcIndex + 2 * i + 1]
                dst[dstIndex + 2 * i] = a[0] * x + a[1] * y
                dst[dstIndex + 2 * i + 1] = a[3] * x + a[4] * y
            }
        } else {
            // wie Skia: Abbildung von (0,0) und (x,y), Differenz
            val o = floatArrayOf(0f, 0f); mapPoints(o)
            for (i in 0 until vectorCount) {
                val p = floatArrayOf(src[srcIndex + 2 * i], src[srcIndex + 2 * i + 1]); mapPoints(p)
                dst[dstIndex + 2 * i] = p[0] - o[0]; dst[dstIndex + 2 * i + 1] = p[1] - o[1]
            }
        }
    }
    fun mapVectors(dst: FloatArray, src: FloatArray) = mapVectors(dst, 0, src, 0, src.size shr 1)
    fun mapVectors(vecs: FloatArray) = mapVectors(vecs, 0, vecs, 0, vecs.size shr 1)

    fun mapRect(dst: RectF, src: RectF): Boolean {
        val p = floatArrayOf(src.left, src.top, src.right, src.top, src.right, src.bottom, src.left, src.bottom)
        mapPoints(p)
        dst.set(
            minOf(minOf(p[0], p[2]), minOf(p[4], p[6])), minOf(minOf(p[1], p[3]), minOf(p[5], p[7])),
            maxOf(maxOf(p[0], p[2]), maxOf(p[4], p[6])), maxOf(maxOf(p[1], p[3]), maxOf(p[5], p[7])),
        )
        return rectStaysRect()
    }
    fun mapRect(rect: RectF): Boolean = mapRect(rect, RectF(rect))

    fun mapRadius(radius: Float): Float {
        val vec = floatArrayOf(radius, 0f, 0f, radius)
        mapVectors(vec)
        val d0 = sqrt(vec[0] * vec[0] + vec[1] * vec[1])
        val d1 = sqrt(vec[2] * vec[2] + vec[3] * vec[3])
        return sqrt(d0 * d1)
    }

    // ---------- Rechteck/Polygon ----------
    fun setRectToRect(src: RectF, dst: RectF, stf: ScaleToFit): Boolean {
        if (src.isEmpty()) { reset(); return false }
        if (dst.isEmpty()) { v.fill(0f); v[8] = 1f; return true }
        var sx = dst.width() / src.width()
        var sy = dst.height() / src.height()
        var xLarger = false
        if (stf != ScaleToFit.FILL) {
            if (sx > sy) { xLarger = true; sx = sy } else sy = sx
        }
        var tx = dst.left - src.left * sx
        var ty = dst.top - src.top * sy
        if (stf == ScaleToFit.CENTER || stf == ScaleToFit.END) {
            var diff = if (xLarger) dst.width() - src.width() * sy else dst.height() - src.height() * sy
            if (stf == ScaleToFit.CENTER) diff *= 0.5f
            if (xLarger) tx += diff else ty += diff
        }
        reset(); v[0] = sx; v[4] = sy; v[2] = tx; v[5] = ty
        return true
    }

    fun setPolyToPoly(src: FloatArray, srcIndex: Int, dst: FloatArray, dstIndex: Int, pointCount: Int): Boolean {
        require(pointCount in 0..4)
        if (pointCount == 0) { reset(); return true }
        if (pointCount == 1) { setTranslate(dst[dstIndex] - src[srcIndex], dst[dstIndex + 1] - src[srcIndex + 1]); return true }
        val s = polyBasis(src, srcIndex, pointCount) ?: return false
        val d = polyBasis(dst, dstIndex, pointCount) ?: return false
        val sInv = Matrix(); val sm = Matrix(); s.copyInto(sm.v)
        if (!sm.invert(sInv)) return false
        mul(d, sInv.v).copyInto(v)
        return true
    }

    private fun polyBasis(p: FloatArray, o: Int, n: Int): FloatArray? {
        val x0 = p[o]; val y0 = p[o + 1]; val x1 = p[o + 2]; val y1 = p[o + 3]
        return when (n) {
            2 -> {
                // Ähnlichkeitsabbildung: dritter Punkt senkrecht zu p0→p1
                val x2 = x0 - (y1 - y0); val y2 = y0 + (x1 - x0)
                floatArrayOf(x1 - x0, x2 - x0, x0, y1 - y0, y2 - y0, y0, 0f, 0f, 1f)
            }
            3 -> {
                val x2 = p[o + 4]; val y2 = p[o + 5]
                floatArrayOf(x1 - x0, x2 - x0, x0, y1 - y0, y2 - y0, y0, 0f, 0f, 1f)
            }
            else -> {
                // Einheitsquadrat (0,0)(1,0)(1,1)(0,1) → Viereck (Heckbert)
                val x2 = p[o + 4]; val y2 = p[o + 5]; val x3 = p[o + 6]; val y3 = p[o + 7]
                val sx = x0 - x1 + x2 - x3
                val sy = y0 - y1 + y2 - y3
                if (sx == 0f && sy == 0f) {
                    floatArrayOf(x1 - x0, x2 - x1, x0, y1 - y0, y2 - y1, y0, 0f, 0f, 1f)
                } else {
                    val dx1 = x1 - x2; val dx2 = x3 - x2; val dy1 = y1 - y2; val dy2 = y3 - y2
                    val den = dx1 * dy2 - dx2 * dy1
                    if (den == 0f) return null
                    val g = (sx * dy2 - dx2 * sy) / den
                    val h = (dx1 * sy - sx * dy1) / den
                    floatArrayOf(
                        x1 - x0 + g * x1, x3 - x0 + h * x3, x0,
                        y1 - y0 + g * y1, y3 - y0 + h * y3, y0,
                        g, h, 1f,
                    )
                }
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is Matrix && other.v.contentEquals(v)
    override fun hashCode(): Int = v.contentHashCode()
    override fun toString(): String = "Matrix{" + toShortString() + "}"
    fun toShortString(): String = "[${v[0]}, ${v[1]}, ${v[2]}][${v[3]}, ${v[4]}, ${v[5]}][${v[6]}, ${v[7]}, ${v[8]}]"

    /** Skia-Gegenstück (gleiche Reihenfolge). */
    fun toSkia(): org.jetbrains.skia.Matrix33 = org.jetbrains.skia.Matrix33(*v.copyOf())

    companion object {
        const val MSCALE_X = 0
        const val MSKEW_X = 1
        const val MTRANS_X = 2
        const val MSKEW_Y = 3
        const val MSCALE_Y = 4
        const val MTRANS_Y = 5
        const val MPERSP_0 = 6
        const val MPERSP_1 = 7
        const val MPERSP_2 = 8

        @JvmField val IDENTITY_MATRIX: Matrix = Matrix()

        private inline fun make(block: Matrix.() -> Unit): Matrix = Matrix().apply(block)

        /** Ergebnis a·b (zeilenweise). */
        internal fun mul(a: FloatArray, b: FloatArray): FloatArray {
            val r = FloatArray(9)
            for (i in 0..2) for (j in 0..2) {
                r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
            }
            return r
        }

        /** Sinus/Kosinus mit exakten Werten für Vielfache von 90° (wie SkScalarSinSnapToZero). */
        internal fun sinCos(degrees: Float): Pair<Float, Float> {
            val rad = Math.toRadians(degrees.toDouble())
            var s = sin(rad).toFloat()
            var c = cos(rad).toFloat()
            if (abs(s) < 1f / (1 shl 12)) s = 0f
            if (abs(c) < 1f / (1 shl 12)) c = 0f
            return s to c
        }

        fun fromSkia(m: org.jetbrains.skia.Matrix33): Matrix = Matrix().also { m.mat.copyInto(it.v) }
    }
}
