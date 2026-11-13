package android.graphics

import org.jetbrains.skia.BlendMode as SkBlendMode
import org.jetbrains.skia.FilterBlurMode

// ===================== PorterDuff / Xfermode / BlendMode =====================

class PorterDuff {
    enum class Mode(internal val sk: SkBlendMode) {
        CLEAR(SkBlendMode.CLEAR), SRC(SkBlendMode.SRC), DST(SkBlendMode.DST), SRC_OVER(SkBlendMode.SRC_OVER),
        DST_OVER(SkBlendMode.DST_OVER), SRC_IN(SkBlendMode.SRC_IN), DST_IN(SkBlendMode.DST_IN),
        SRC_OUT(SkBlendMode.SRC_OUT), DST_OUT(SkBlendMode.DST_OUT), SRC_ATOP(SkBlendMode.SRC_ATOP),
        DST_ATOP(SkBlendMode.DST_ATOP), XOR(SkBlendMode.XOR), DARKEN(SkBlendMode.DARKEN), LIGHTEN(SkBlendMode.LIGHTEN),
        /** Android: MULTIPLY entspricht Skias MODULATE (Sa·Da, Sc·Dc). */
        MULTIPLY(SkBlendMode.MODULATE), SCREEN(SkBlendMode.SCREEN), ADD(SkBlendMode.PLUS), OVERLAY(SkBlendMode.OVERLAY),
    }
}

open class Xfermode internal constructor() {
    internal open val skMode: SkBlendMode get() = SkBlendMode.SRC_OVER
}

class PorterDuffXfermode(val mode: PorterDuff.Mode) : Xfermode() {
    override val skMode: SkBlendMode get() = mode.sk
}

enum class BlendMode {
    CLEAR, SRC, DST, SRC_OVER, DST_OVER, SRC_IN, DST_IN, SRC_OUT, DST_OUT, SRC_ATOP, DST_ATOP, XOR, PLUS, MODULATE,
    SCREEN, OVERLAY, DARKEN, LIGHTEN, COLOR_DODGE, COLOR_BURN, HARD_LIGHT, SOFT_LIGHT, DIFFERENCE, EXCLUSION,
    MULTIPLY, HUE, SATURATION, COLOR, LUMINOSITY;

    internal val sk: SkBlendMode get() = SkBlendMode.valueOf(name)
}

// ===================== ColorFilter =====================

open class ColorFilter internal constructor() {
    internal open fun sk(): org.jetbrains.skia.ColorFilter? = null
}

class PorterDuffColorFilter(val color: Int, val mode: PorterDuff.Mode) : ColorFilter() {
    private val cached by lazy { org.jetbrains.skia.ColorFilter.makeBlend(color, mode.sk) }
    override fun sk(): org.jetbrains.skia.ColorFilter = cached
}

class BlendModeColorFilter(val color: Int, val mode: BlendMode) : ColorFilter() {
    private val cached by lazy { org.jetbrains.skia.ColorFilter.makeBlend(color, mode.sk) }
    override fun sk(): org.jetbrains.skia.ColorFilter = cached
}

class LightingColorFilter(val colorMultiply: Int, val colorAdd: Int) : ColorFilter() {
    private val cached by lazy { org.jetbrains.skia.ColorFilter.makeLighting(colorMultiply, colorAdd) }
    override fun sk(): org.jetbrains.skia.ColorFilter = cached
}

class ColorMatrixColorFilter(matrix: ColorMatrix) : ColorFilter() {
    constructor(array: FloatArray) : this(ColorMatrix(array))

    private val values = matrix.array.copyOf()
    private val cached by lazy {
        // Android: Verschiebung in 0..255, Skia: normiert 0..1
        val m = values.copyOf()
        m[4] /= 255f; m[9] /= 255f; m[14] /= 255f; m[19] /= 255f
        org.jetbrains.skia.ColorFilter.makeMatrix(org.jetbrains.skia.ColorMatrix(*m))
    }

    fun getColorMatrix(colorMatrix: ColorMatrix) { colorMatrix.set(values) }
    override fun sk(): org.jetbrains.skia.ColorFilter = cached
}

/** android.graphics.ColorMatrix (4×5, zeilenweise, Verschiebung in 0..255). */
class ColorMatrix() {
    private val m = FloatArray(20)

    init { reset() }

    constructor(src: FloatArray) : this() { set(src) }
    constructor(src: ColorMatrix) : this() { set(src) }

    val array: FloatArray get() = m

    fun reset() { m.fill(0f); m[0] = 1f; m[6] = 1f; m[12] = 1f; m[18] = 1f }
    fun set(src: ColorMatrix) { src.m.copyInto(m) }
    fun set(src: FloatArray) { src.copyInto(m, 0, 0, 20) }

    fun setScale(rScale: Float, gScale: Float, bScale: Float, aScale: Float) {
        m.fill(0f); m[0] = rScale; m[6] = gScale; m[12] = bScale; m[18] = aScale
    }

    fun setRotate(axis: Int, degrees: Float) {
        reset()
        val rad = Math.toRadians(degrees.toDouble())
        val c = kotlin.math.cos(rad).toFloat()
        val s = kotlin.math.sin(rad).toFloat()
        when (axis) {
            0 -> { m[6] = c; m[12] = c; m[7] = s; m[11] = -s }
            1 -> { m[0] = c; m[12] = c; m[2] = -s; m[10] = s }
            2 -> { m[0] = c; m[6] = c; m[1] = s; m[5] = -s }
            else -> throw RuntimeException()
        }
    }

    fun setConcat(matA: ColorMatrix, matB: ColorMatrix) {
        val tmp = FloatArray(20)
        val a = matA.m; val b = matB.m
        var index = 0
        var j = 0
        while (j < 20) {
            for (i in 0..3) {
                tmp[index++] = a[j] * b[i] + a[j + 1] * b[i + 5] + a[j + 2] * b[i + 10] + a[j + 3] * b[i + 15]
            }
            tmp[index++] = a[j] * b[4] + a[j + 1] * b[9] + a[j + 2] * b[14] + a[j + 3] * b[19] + a[j + 4]
            j += 5
        }
        tmp.copyInto(m)
    }

    fun preConcat(prematrix: ColorMatrix) = setConcat(this, prematrix)
    fun postConcat(postmatrix: ColorMatrix) = setConcat(postmatrix, this)

    fun setSaturation(sat: Float) {
        reset()
        val invSat = 1 - sat
        val r = 0.213f * invSat; val g = 0.715f * invSat; val b = 0.072f * invSat
        m[0] = r + sat; m[1] = g; m[2] = b
        m[5] = r; m[6] = g + sat; m[7] = b
        m[10] = r; m[11] = g; m[12] = b + sat
    }

    fun setRGB2YUV() {
        reset()
        m[0] = 0.299f; m[1] = 0.587f; m[2] = 0.114f
        m[5] = -0.16874f; m[6] = -0.33126f; m[7] = 0.5f
        m[10] = 0.5f; m[11] = -0.41869f; m[12] = -0.08131f
    }

    fun setYUV2RGB() {
        reset()
        m[2] = 1.402f
        m[5] = 1f; m[6] = -0.34414f; m[7] = -0.71414f
        m[10] = 1f; m[11] = 1.772f; m[12] = 0f
    }

    override fun equals(other: Any?): Boolean = other is ColorMatrix && other.m.contentEquals(m)
    override fun hashCode(): Int = m.contentHashCode()
}

// ===================== PathEffect =====================

open class PathEffect internal constructor() {
    internal open fun sk(): org.jetbrains.skia.PathEffect? = null
}

class DashPathEffect(intervals: FloatArray, phase: Float) : PathEffect() {
    private val cached = org.jetbrains.skia.PathEffect.makeDash(intervals.copyOf(), phase)
    override fun sk(): org.jetbrains.skia.PathEffect = cached
}

class CornerPathEffect(radius: Float) : PathEffect() {
    private val cached = org.jetbrains.skia.PathEffect.makeCorner(radius)
    override fun sk(): org.jetbrains.skia.PathEffect = cached
}

class DiscretePathEffect(segmentLength: Float, deviation: Float) : PathEffect() {
    private val cached = org.jetbrains.skia.PathEffect.makeDiscrete(segmentLength, deviation, 0)
    override fun sk(): org.jetbrains.skia.PathEffect = cached
}

class ComposePathEffect(outerpe: PathEffect, innerpe: PathEffect) : PathEffect() {
    private val cached = outerpe.sk()?.let { o -> innerpe.sk()?.let { o.makeCompose(it) } ?: o } ?: innerpe.sk()
    override fun sk(): org.jetbrains.skia.PathEffect? = cached
}

class SumPathEffect(first: PathEffect, second: PathEffect) : PathEffect() {
    private val cached = first.sk()?.let { f -> second.sk()?.let { f.makeSum(it) } ?: f } ?: second.sk()
    override fun sk(): org.jetbrains.skia.PathEffect? = cached
}

// ===================== MaskFilter =====================

open class MaskFilter internal constructor() {
    internal open fun sk(): org.jetbrains.skia.MaskFilter? = null
}

class BlurMaskFilter(radius: Float, style: Blur) : MaskFilter() {
    enum class Blur(internal val sk: FilterBlurMode) {
        NORMAL(FilterBlurMode.NORMAL), SOLID(FilterBlurMode.SOLID), OUTER(FilterBlurMode.OUTER), INNER(FilterBlurMode.INNER)
    }

    private val cached = org.jetbrains.skia.MaskFilter.makeBlur(style.sk, radiusToSigma(radius), true)
    override fun sk(): org.jetbrains.skia.MaskFilter = cached
}

/** Wie SkBlurMask::ConvertRadiusToSigma (Android). */
internal fun radiusToSigma(radius: Float): Float = if (radius > 0f) 0.57735f * radius + 0.5f else 0f
