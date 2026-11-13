package android.graphics

import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.GradientStyle
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.SamplingMode

/** android.graphics.Shader – Basisklasse; Skia-Shader werden beim Zeichnen erzeugt (Bitmaps können sich ändern). */
open class Shader internal constructor() {
    enum class TileMode(internal val sk: FilterTileMode) {
        CLAMP(FilterTileMode.CLAMP), REPEAT(FilterTileMode.REPEAT), MIRROR(FilterTileMode.MIRROR), DECAL(FilterTileMode.DECAL)
    }

    private var local: Matrix? = null
    internal var version = 0

    fun setLocalMatrix(localM: Matrix?) {
        local = if (localM == null || localM.isIdentity()) null else Matrix(localM)
        version++
    }

    fun getLocalMatrix(localM: Matrix): Boolean {
        val l = local
        if (l == null) { localM.reset(); return false }
        localM.set(l); return true
    }

    internal val localSk: Matrix33? get() = local?.toSkia()

    /** Erzeugt den Skia-Shader; [filter] = Paint.isFilterBitmap des zeichnenden Paints. */
    internal open fun sk(filter: Boolean): org.jetbrains.skia.Shader? = null
}

internal fun sampling(filter: Boolean): SamplingMode =
    if (filter) FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE) else FilterMipmap(FilterMode.NEAREST, MipmapMode.NONE)

class BitmapShader(bitmap: Bitmap, tileX: TileMode, tileY: TileMode) : Shader() {
    private var bmp = bitmap
    private var tx = tileX
    private var ty = tileY

    var filterMode: Int = FILTER_MODE_DEFAULT
        set(value) { field = value; version++ }

    private var cache: org.jetbrains.skia.Shader? = null
    private var cacheImage: org.jetbrains.skia.Image? = null
    private var cacheVersion = -1
    private var cacheFilter = false

    override fun sk(filter: Boolean): org.jetbrains.skia.Shader? {
        if (bmp.isRecycled) return null
        val f = when (filterMode) { FILTER_MODE_LINEAR -> true; FILTER_MODE_NEAREST -> false; else -> filter }
        val img = bmp.image()
        val c = cache
        if (c != null && img === cacheImage && version == cacheVersion && f == cacheFilter) return c
        val s = img.makeShader(tx.sk, ty.sk, sampling(f), localSk)
        cache = s; cacheImage = img; cacheVersion = version; cacheFilter = f
        return s
    }

    companion object {
        const val FILTER_MODE_DEFAULT = 0
        const val FILTER_MODE_NEAREST = 1
        const val FILTER_MODE_LINEAR = 2
    }
}

class LinearGradient : Shader {
    private val x0: Float; private val y0: Float; private val x1: Float; private val y1: Float
    private val colors: IntArray
    private val positions: FloatArray?
    private val tile: TileMode

    constructor(x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, positions: FloatArray?, tile: TileMode) : super() {
        this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1
        this.colors = colors.copyOf(); this.positions = positions?.copyOf(); this.tile = tile
    }

    constructor(x0: Float, y0: Float, x1: Float, y1: Float, color0: Int, color1: Int, tile: TileMode) :
        this(x0, y0, x1, y1, intArrayOf(color0, color1), null, tile)

    private var cache: org.jetbrains.skia.Shader? = null
    private var cacheVersion = -1

    override fun sk(filter: Boolean): org.jetbrains.skia.Shader? {
        if (cache == null || cacheVersion != version) {
            cache = org.jetbrains.skia.Shader.makeLinearGradient(
                x0, y0, x1, y1, colors, positions, GradientStyle(tile.sk, false, localSk ?: Matrix33.IDENTITY),
            )
            cacheVersion = version
        }
        return cache
    }
}

class RadialGradient : Shader {
    private val cx: Float; private val cy: Float; private val r: Float
    private val colors: IntArray
    private val positions: FloatArray?
    private val tile: TileMode

    constructor(centerX: Float, centerY: Float, radius: Float, colors: IntArray, stops: FloatArray?, tileMode: TileMode) : super() {
        cx = centerX; cy = centerY; r = radius; this.colors = colors.copyOf(); positions = stops?.copyOf(); tile = tileMode
    }

    constructor(centerX: Float, centerY: Float, radius: Float, centerColor: Int, edgeColor: Int, tileMode: TileMode) :
        this(centerX, centerY, radius, intArrayOf(centerColor, edgeColor), null, tileMode)

    override fun sk(filter: Boolean): org.jetbrains.skia.Shader =
        org.jetbrains.skia.Shader.makeRadialGradient(cx, cy, r, colors, positions, GradientStyle(tile.sk, false, localSk ?: Matrix33.IDENTITY))
}

class SweepGradient : Shader {
    private val cx: Float; private val cy: Float
    private val colors: IntArray
    private val positions: FloatArray?

    constructor(cx: Float, cy: Float, colors: IntArray, positions: FloatArray?) : super() {
        this.cx = cx; this.cy = cy; this.colors = colors.copyOf(); this.positions = positions?.copyOf()
    }

    constructor(cx: Float, cy: Float, color0: Int, color1: Int) : this(cx, cy, intArrayOf(color0, color1), null)

    override fun sk(filter: Boolean): org.jetbrains.skia.Shader =
        org.jetbrains.skia.Shader.makeSweepGradient(cx, cy, 0f, 360f, colors, positions,
            GradientStyle(FilterTileMode.CLAMP, false, localSk ?: Matrix33.IDENTITY))
}

/** android.graphics.ComposeShader: Ergebnis = mode(dst = shaderA, src = shaderB). */
class ComposeShader : Shader {
    private val a: Shader
    private val b: Shader
    private val mode: org.jetbrains.skia.BlendMode

    constructor(shaderA: Shader, shaderB: Shader, mode: PorterDuff.Mode) : super() { a = shaderA; b = shaderB; this.mode = mode.sk }
    constructor(shaderA: Shader, shaderB: Shader, mode: BlendMode) : super() { a = shaderA; b = shaderB; this.mode = mode.sk }
    constructor(shaderA: Shader, shaderB: Shader, mode: Xfermode) : super() { a = shaderA; b = shaderB; this.mode = mode.skMode }

    override fun sk(filter: Boolean): org.jetbrains.skia.Shader? {
        val sa = a.sk(filter) ?: return null
        val sb = b.sk(filter) ?: return null
        return org.jetbrains.skia.Shader.makeBlend(mode, sa, sb)
    }
}

/**
 * android.graphics.RuntimeShader (AGSL) über Skias RuntimeEffect (SkSL; AGSL ist SkSL-kompatibel).
 * Eingangs-Shader und Uniforms werden gesammelt und beim Erzeugen gesetzt.
 */
class RuntimeShader(shader: String) : Shader() {
    internal val effect: org.jetbrains.skia.RuntimeEffect = org.jetbrains.skia.RuntimeEffect.makeForShader(shader)
    internal val floats = LinkedHashMap<String, FloatArray>()
    internal val ints = LinkedHashMap<String, IntArray>()
    internal val children = LinkedHashMap<String, Shader>()

    fun setFloatUniform(uniformName: String, value: Float) { floats[uniformName] = floatArrayOf(value) }
    fun setFloatUniform(uniformName: String, value1: Float, value2: Float) { floats[uniformName] = floatArrayOf(value1, value2) }
    fun setFloatUniform(uniformName: String, value1: Float, value2: Float, value3: Float) {
        floats[uniformName] = floatArrayOf(value1, value2, value3)
    }
    fun setFloatUniform(uniformName: String, value1: Float, value2: Float, value3: Float, value4: Float) {
        floats[uniformName] = floatArrayOf(value1, value2, value3, value4)
    }
    fun setFloatUniform(uniformName: String, values: FloatArray) { floats[uniformName] = values.copyOf() }
    fun setIntUniform(uniformName: String, value: Int) { ints[uniformName] = intArrayOf(value) }
    fun setIntUniform(uniformName: String, value1: Int, value2: Int) { ints[uniformName] = intArrayOf(value1, value2) }
    fun setIntUniform(uniformName: String, values: IntArray) { ints[uniformName] = values.copyOf() }
    fun setColorUniform(uniformName: String, color: Int) {
        floats[uniformName] = floatArrayOf(
            Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f, Color.alpha(color) / 255f,
        )
    }
    fun setInputShader(shaderName: String, shader: Shader) { children[shaderName] = shader }
    fun setInputBuffer(shaderName: String, shader: BitmapShader) { children[shaderName] = shader }

    /** Builder mit allen Uniforms und Kind-Shadern (außer [skip], z. B. dem RenderEffect-Eingang). */
    internal fun builder(filter: Boolean, skip: String? = null): org.jetbrains.skia.RuntimeShaderBuilder {
        val b = org.jetbrains.skia.RuntimeShaderBuilder(effect)
        for ((k, v) in floats) when (v.size) {
            1 -> b.uniform(k, v[0])
            2 -> b.uniform(k, v[0], v[1])
            3 -> b.uniform(k, v[0], v[1], v[2])
            4 -> b.uniform(k, v[0], v[1], v[2], v[3])
            else -> b.uniform(k, v)
        }
        for ((k, v) in ints) when (v.size) {
            1 -> b.uniform(k, v[0])
            2 -> b.uniform(k, v[0], v[1])
            3 -> b.uniform(k, v[0], v[1], v[2])
            else -> b.uniform(k, v[0], v[1], v[2], v[3])
        }
        for ((k, s) in children) if (k != skip) s.sk(filter)?.let { b.child(k, it) }
        return b
    }

    override fun sk(filter: Boolean): org.jetbrains.skia.Shader = builder(filter).makeShader(localSk)
}
