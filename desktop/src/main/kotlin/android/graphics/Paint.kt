package android.graphics

import org.jetbrains.skia.Font
import org.jetbrains.skia.FontEdging
import org.jetbrains.skia.FontFeature
import org.jetbrains.skia.FontHinting
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.TextLine
import org.jetbrains.skia.shaper.ShapingOptions

/**
 * android.graphics.Paint. Hält den Android-Zustand und erzeugt daraus bei Bedarf ein org.jetbrains.skia.Paint
 * ([toSkia]) bzw. eine Font ([skiaFont]) für Text.
 */
open class Paint() {

    enum class Style(internal val sk: PaintMode) { FILL(PaintMode.FILL), STROKE(PaintMode.STROKE), FILL_AND_STROKE(PaintMode.STROKE_AND_FILL) }
    enum class Cap(internal val sk: PaintStrokeCap) { BUTT(PaintStrokeCap.BUTT), ROUND(PaintStrokeCap.ROUND), SQUARE(PaintStrokeCap.SQUARE) }
    enum class Join(internal val sk: PaintStrokeJoin) { MITER(PaintStrokeJoin.MITER), ROUND(PaintStrokeJoin.ROUND), BEVEL(PaintStrokeJoin.BEVEL) }
    enum class Align { LEFT, CENTER, RIGHT }

    open class FontMetrics {
        @JvmField var top = 0f
        @JvmField var ascent = 0f
        @JvmField var descent = 0f
        @JvmField var bottom = 0f
        @JvmField var leading = 0f
    }

    open class FontMetricsInt {
        @JvmField var top = 0
        @JvmField var ascent = 0
        @JvmField var descent = 0
        @JvmField var bottom = 0
        @JvmField var leading = 0
    }

    constructor(flags: Int) : this() { this.flags = flags or HIDDEN_DEFAULT_PAINT_FLAGS }
    constructor(paint: Paint?) : this() { if (paint != null) set(paint) }

    // ---------- Zustand ----------
    private var gen = 0
    private fun ch() { gen++ }

    var flags: Int = HIDDEN_DEFAULT_PAINT_FLAGS
        set(value) { field = value; ch() }

    var color: Int = Color.BLACK
        set(value) { field = value; ch() }

    var alpha: Int
        get() = color ushr 24
        set(value) { color = (color and 0x00FFFFFF) or ((value and 0xFF) shl 24) }

    var style: Style = Style.FILL
        set(value) { field = value; ch() }
    var strokeWidth: Float = 0f
        set(value) { field = value; ch() }
    var strokeMiter: Float = 4f
        set(value) { field = value; ch() }
    var strokeCap: Cap = Cap.BUTT
        set(value) { field = value; ch() }
    var strokeJoin: Join = Join.MITER
        set(value) { field = value; ch() }

    var shader: Shader? = null
        set(value) { field = value; ch() }
    var colorFilter: ColorFilter? = null
        set(value) { field = value; ch() }
    var pathEffect: PathEffect? = null
        set(value) { field = value; ch() }
    var maskFilter: MaskFilter? = null
        set(value) { field = value; ch() }

    var xfermode: Xfermode? = null
        set(value) { field = value; if (value != null) blendModeField = null; ch() }

    private var blendModeField: BlendMode? = null
    var blendMode: BlendMode?
        get() = blendModeField ?: (xfermode as? PorterDuffXfermode)?.mode?.let { pdToBlend(it) }
        set(value) { blendModeField = value; if (value != null) xfermode = null; ch() }

    // Schatten (setShadowLayer)
    var shadowLayerRadius = 0f; private set
    var shadowLayerDx = 0f; private set
    var shadowLayerDy = 0f; private set
    var shadowLayerColor = 0; private set

    // Text
    var typeface: Typeface? = null
        set(value) { field = value; ch() }
    var textSize: Float = 12f
        set(value) { field = value; ch() }
    var textScaleX: Float = 1f
        set(value) { field = value; ch() }
    var textSkewX: Float = 0f
        set(value) { field = value; ch() }
    var textAlign: Align = Align.LEFT
    var letterSpacing: Float = 0f
    var wordSpacing: Float = 0f
    var fontFeatureSettings: String? = null
    var fontVariationSettings: String? = null
    var textLocale: java.util.Locale = java.util.Locale.getDefault()
    var isElegantTextHeight: Boolean = false
    var hinting: Int = HINTING_ON
    var startHyphenEdit: Int = 0
    var endHyphenEdit: Int = 0

    // Flag-Eigenschaften
    var isAntiAlias: Boolean
        get() = flags and ANTI_ALIAS_FLAG != 0
        set(v) { flags = if (v) flags or ANTI_ALIAS_FLAG else flags and ANTI_ALIAS_FLAG.inv() }
    var isFilterBitmap: Boolean
        get() = flags and FILTER_BITMAP_FLAG != 0
        set(v) { flags = if (v) flags or FILTER_BITMAP_FLAG else flags and FILTER_BITMAP_FLAG.inv() }
    var isDither: Boolean
        get() = flags and DITHER_FLAG != 0
        set(v) { flags = if (v) flags or DITHER_FLAG else flags and DITHER_FLAG.inv() }
    var isUnderlineText: Boolean
        get() = flags and UNDERLINE_TEXT_FLAG != 0
        set(v) { flags = if (v) flags or UNDERLINE_TEXT_FLAG else flags and UNDERLINE_TEXT_FLAG.inv() }
    var isStrikeThruText: Boolean
        get() = flags and STRIKE_THRU_TEXT_FLAG != 0
        set(v) { flags = if (v) flags or STRIKE_THRU_TEXT_FLAG else flags and STRIKE_THRU_TEXT_FLAG.inv() }
    var isFakeBoldText: Boolean
        get() = flags and FAKE_BOLD_TEXT_FLAG != 0
        set(v) { flags = if (v) flags or FAKE_BOLD_TEXT_FLAG else flags and FAKE_BOLD_TEXT_FLAG.inv() }
    var isLinearText: Boolean
        get() = flags and LINEAR_TEXT_FLAG != 0
        set(v) { flags = if (v) flags or LINEAR_TEXT_FLAG else flags and LINEAR_TEXT_FLAG.inv() }
    var isSubpixelText: Boolean
        get() = flags and SUBPIXEL_TEXT_FLAG != 0
        set(v) { flags = if (v) flags or SUBPIXEL_TEXT_FLAG else flags and SUBPIXEL_TEXT_FLAG.inv() }

    fun setARGB(a: Int, r: Int, g: Int, b: Int) { color = Color.argb(a, r, g, b) }

    fun setShadowLayer(radius: Float, dx: Float, dy: Float, shadowColor: Int) {
        shadowLayerRadius = radius; shadowLayerDx = dx; shadowLayerDy = dy; shadowLayerColor = shadowColor; ch()
    }
    fun clearShadowLayer() = setShadowLayer(0f, 0f, 0f, 0)

    fun reset() {
        flags = HIDDEN_DEFAULT_PAINT_FLAGS
        color = Color.BLACK; style = Style.FILL; strokeWidth = 0f; strokeMiter = 4f
        strokeCap = Cap.BUTT; strokeJoin = Join.MITER
        shader = null; colorFilter = null; pathEffect = null; maskFilter = null
        xfermode = null; blendModeField = null
        clearShadowLayer()
        typeface = null; textSize = 12f; textScaleX = 1f; textSkewX = 0f; textAlign = Align.LEFT
        letterSpacing = 0f; wordSpacing = 0f; fontFeatureSettings = null; fontVariationSettings = null
        isElegantTextHeight = false; hinting = HINTING_ON
        ch()
    }

    fun set(src: Paint) {
        if (src === this) return
        flags = src.flags; color = src.color; style = src.style; strokeWidth = src.strokeWidth; strokeMiter = src.strokeMiter
        strokeCap = src.strokeCap; strokeJoin = src.strokeJoin
        shader = src.shader; colorFilter = src.colorFilter; pathEffect = src.pathEffect; maskFilter = src.maskFilter
        xfermode = src.xfermode; blendModeField = src.blendModeField
        shadowLayerRadius = src.shadowLayerRadius; shadowLayerDx = src.shadowLayerDx; shadowLayerDy = src.shadowLayerDy
        shadowLayerColor = src.shadowLayerColor
        typeface = src.typeface; textSize = src.textSize; textScaleX = src.textScaleX; textSkewX = src.textSkewX
        textAlign = src.textAlign; letterSpacing = src.letterSpacing; wordSpacing = src.wordSpacing
        fontFeatureSettings = src.fontFeatureSettings; fontVariationSettings = src.fontVariationSettings
        textLocale = src.textLocale; isElegantTextHeight = src.isElegantTextHeight; hinting = src.hinting
        ch()
    }

    fun hasShadowLayer(): Boolean = shadowLayerRadius > 0f || shadowLayerDx != 0f || shadowLayerDy != 0f

    // ---------- Skia-Umsetzung ----------
    private val sk = org.jetbrains.skia.Paint()
    private var skGen = -1

    /** Skia-Paint mit dem aktuellen Zustand. Das Objekt wird wiederverwendet. */
    fun toSkia(): org.jetbrains.skia.Paint {
        if (skGen != gen) {
            skGen = gen
            sk.isAntiAlias = isAntiAlias
            sk.isDither = isDither
            sk.color = color
            sk.mode = style.sk
            sk.strokeWidth = strokeWidth
            sk.strokeMiter = strokeMiter
            sk.strokeCap = strokeCap.sk
            sk.strokeJoin = strokeJoin.sk
            sk.colorFilter = colorFilter?.sk()
            sk.pathEffect = pathEffect?.sk()
            sk.maskFilter = maskFilter?.sk()
            sk.blendMode = blendModeField?.sk ?: xfermode?.skMode ?: org.jetbrains.skia.BlendMode.SRC_OVER
            sk.imageFilter = if (hasShadowLayer()) {
                val sigma = radiusToSigma(shadowLayerRadius)
                // Android: Schattenfarbe wird mit dem Paint-Alpha moduliert
                val a = (Color.alpha(shadowLayerColor) * alpha / 255f + 0.5f).toInt()
                ImageFilter.makeDropShadow(shadowLayerDx, shadowLayerDy, sigma, sigma,
                    (shadowLayerColor and 0x00FFFFFF) or (a shl 24), null, null)
            } else null
        }
        // Shader immer frisch (BitmapShader folgt Pixeländerungen; intern gecacht)
        sk.shader = shader?.sk(isFilterBitmap)
        return sk
    }

    // ---------- Text ----------
    private var font: Font? = null
    private var fontGen = -1

    /** Skia-Font aus Schrift, Größe, Skalierung und Schrägstellung. */
    fun skiaFont(): Font {
        val f0 = font
        if (f0 != null && fontGen == gen) return f0
        val tf = typeface ?: Typeface.DEFAULT
        val f = Font(tf.skia, textSize)
        f.scaleX = textScaleX
        f.skewX = textSkewX + if (tf.fakeItalic) -0.25f else 0f
        f.isEmboldened = isFakeBoldText || tf.fakeBold
        f.isSubpixel = isSubpixelText || isLinearText
        f.isLinearMetrics = isLinearText
        f.edging = if (isAntiAlias) FontEdging.ANTI_ALIAS else FontEdging.ALIAS
        f.hinting = if (hinting == HINTING_OFF) FontHinting.NONE else FontHinting.SLIGHT
        font = f; fontGen = gen
        return f
    }

    private fun features(): Array<FontFeature> {
        val s = fontFeatureSettings?.trim().orEmpty()
        if (s.isEmpty()) return emptyArray()
        val out = ArrayList<FontFeature>()
        for (part in s.split(',')) {
            val tokens = part.trim().replace("\"", "").replace("'", "").split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue
            val tag = tokens[0]
            if (tag.length != 4) continue
            val value = when (tokens.getOrNull(1)?.lowercase()) {
                null, "on" -> 1
                "off" -> 0
                else -> tokens[1].toIntOrNull() ?: 1
            }
            out += FontFeature(tag, value)
        }
        return out.toTypedArray()
    }

    private fun shaping(): ShapingOptions = ShapingOptions.DEFAULT.withFontMgr(FontMgr.default).withFeatures(features())

    /** Geformte Textzeile (Skia-Shaper mit Fallback-Schriften und OpenType-Features). */
    fun textLine(text: String): TextLine = TextLine.make(text, skiaFont(), shaping())

    private fun spacingPx(): Float = letterSpacing * textSize

    /** Einzelne Zeichen (Graphem-Näherung: Codepoints) für Laufweite ≠ 0. */
    private fun clusters(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            out += text.substring(i, i + n)
            i += n
        }
        return out
    }

    /** Breite wie Android (inkl. Laufweite). */
    fun measureText(text: String): Float {
        if (text.isEmpty()) return 0f
        val ls = spacingPx()
        if (ls == 0f) return textLine(text).width
        return clusters(text).sumOf { textLine(it).width.toDouble() }.toFloat() + ls * clusters(text).size
    }
    fun measureText(text: String, start: Int, end: Int): Float = measureText(text.substring(start, end))
    fun measureText(text: CharSequence, start: Int, end: Int): Float = measureText(text.subSequence(start, end).toString())
    fun measureText(text: CharArray, index: Int, count: Int): Float = measureText(String(text, index, count))

    fun getTextWidths(text: String, widths: FloatArray): Int = getTextWidths(text, 0, text.length, widths)
    fun getTextWidths(text: String, start: Int, end: Int, widths: FloatArray): Int {
        val ls = spacingPx()
        var i = start
        var k = 0
        while (i < end) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            widths[k++] = textLine(text.substring(i, i + n)).width + ls
            if (n == 2 && k < widths.size) widths[k++] = 0f
            i += n
        }
        return end - start
    }

    fun breakText(text: String, measureForwards: Boolean, maxWidth: Float, measuredWidth: FloatArray?): Int {
        val chars = clusters(text)
        val seq = if (measureForwards) chars else chars.reversed()
        var w = 0f
        var count = 0
        for (c in seq) {
            val cw = measureText(c)
            if (w + cw > maxWidth) break
            w += cw; count += c.length
        }
        measuredWidth?.let { it[0] = w }
        return count
    }

    fun getTextBounds(text: String, start: Int, end: Int, bounds: Rect) {
        val r = RectF(); textBoundsF(text.substring(start, end), r)
        r.roundOut(bounds)
    }
    fun getTextBounds(text: CharSequence, start: Int, end: Int, bounds: Rect) = getTextBounds(text.toString(), start, end, bounds)
    fun getTextBounds(text: CharArray, index: Int, count: Int, bounds: Rect) = getTextBounds(String(text, index, count), 0, count, bounds)

    internal fun textBoundsF(text: String, out: RectF) {
        if (text.isEmpty()) { out.setEmpty(); return }
        val p = textPath(text, 0f, 0f)
        val b = p.skia.computeTightBounds()
        out.set(b.left, b.top, b.right, b.bottom)
    }

    /** Umrisse des Texts (Grundlinie bei y, Beginn bei x, ohne Ausrichtung). */
    internal fun textPath(text: String, x: Float, y: Float): Path {
        val out = Path()
        val ls = spacingPx()
        val parts = if (ls == 0f) listOf(text) else clusters(text)
        var cx = x + ls / 2f
        for (part in parts) {
            val line = textLine(part)
            val blob = line.textBlob
            if (blob != null) {
                val pos = blob.positions
                val total = blob.glyphs.size
                val full = pos.size >= 2 * total
                val base = skiaFont()
                var gi = 0
                for (run in blob) {
                    val rf = Font(run.typeface, base.size).apply {
                        scaleX = base.scaleX; skewX = base.skewX; isEmboldened = base.isEmboldened
                    }
                    for (g in run.glyphs) {
                        val px = if (full) pos[2 * gi] else pos.getOrElse(gi) { 0f }
                        val py = if (full) pos[2 * gi + 1] else 0f
                        gi++
                        val gp = rf.getPath(g) ?: continue
                        out.skia.addPath(gp, cx + px, y + py, false)
                    }
                }
            }
            cx += line.width + ls
        }
        return out
    }

    fun getTextPath(text: String, start: Int, end: Int, x: Float, y: Float, path: Path) {
        val s = text.substring(start, end)
        path.set(textPath(s, x - alignOffset(measureText(s)), y))
    }

    internal fun alignOffset(width: Float): Float = when (textAlign) {
        Align.LEFT -> 0f
        Align.CENTER -> width / 2f
        Align.RIGHT -> width
    }

    fun getFontMetrics(metrics: FontMetrics?): Float {
        val m = skiaFont().metrics
        metrics?.apply { top = m.top; ascent = m.ascent; descent = m.descent; bottom = m.bottom; leading = m.leading }
        return m.descent - m.ascent + m.leading
    }

    val fontMetrics: FontMetrics get() = FontMetrics().also { getFontMetrics(it) }

    fun getFontMetricsInt(fmi: FontMetricsInt?): Int {
        val m = skiaFont().metrics
        fmi?.apply {
            top = kotlin.math.floor(m.top).toInt(); ascent = kotlin.math.round(m.ascent).toInt()
            descent = kotlin.math.round(m.descent).toInt(); bottom = kotlin.math.ceil(m.bottom).toInt()
            leading = kotlin.math.round(m.leading).toInt()
        }
        return kotlin.math.round(m.descent - m.ascent + m.leading).toInt()
    }

    val fontMetricsInt: FontMetricsInt get() = FontMetricsInt().also { getFontMetricsInt(it) }

    val fontSpacing: Float get() = getFontMetrics(null)

    fun ascent(): Float = skiaFont().metrics.ascent
    fun descent(): Float = skiaFont().metrics.descent

    fun hasGlyph(string: String): Boolean {
        val f = skiaFont()
        return f.getStringGlyphs(string).all { it.toInt() != 0 }
    }

    companion object {
        const val ANTI_ALIAS_FLAG = 0x01
        const val FILTER_BITMAP_FLAG = 0x02
        const val DITHER_FLAG = 0x04
        const val UNDERLINE_TEXT_FLAG = 0x08
        const val STRIKE_THRU_TEXT_FLAG = 0x10
        const val FAKE_BOLD_TEXT_FLAG = 0x20
        const val LINEAR_TEXT_FLAG = 0x40
        const val SUBPIXEL_TEXT_FLAG = 0x80
        const val EMBEDDED_BITMAP_TEXT_FLAG = 0x400
        const val DEV_KERN_TEXT_FLAG = 0x100

        const val HINTING_OFF = 0
        const val HINTING_ON = 1

        const val CURSOR_AFTER = 0
        const val CURSOR_AT_OR_AFTER = 1
        const val CURSOR_BEFORE = 2
        const val CURSOR_AT_OR_BEFORE = 3
        const val CURSOR_AT = 4

        /** Wie Android ab API 28: Bitmap-Filterung ist standardmäßig an. */
        private const val HIDDEN_DEFAULT_PAINT_FLAGS = DEV_KERN_TEXT_FLAG or EMBEDDED_BITMAP_TEXT_FLAG or FILTER_BITMAP_FLAG

        internal fun pdToBlend(m: PorterDuff.Mode): BlendMode = when (m) {
            PorterDuff.Mode.MULTIPLY -> BlendMode.MODULATE
            PorterDuff.Mode.ADD -> BlendMode.PLUS
            else -> BlendMode.valueOf(m.name)
        }
    }
}
