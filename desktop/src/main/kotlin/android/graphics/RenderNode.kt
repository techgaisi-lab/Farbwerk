package android.graphics

import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.PictureRecorder

/** android.graphics.RecordingCanvas: zeichnet in eine Skia-Aufzeichnung (Picture). */
open class RecordingCanvas internal constructor(skia: org.jetbrains.skia.Canvas, private val w: Int, private val h: Int) :
    Canvas(skia, w, h) {
    override val width: Int get() = w
    override val height: Int get() = h
    override val isHardwareAccelerated: Boolean get() = false
}

/** android.graphics.Picture über Skias PictureRecorder. */
class Picture() {
    private var recorder: PictureRecorder? = null
    private var picture: org.jetbrains.skia.Picture? = null
    var width: Int = 0; private set
    var height: Int = 0; private set

    constructor(src: Picture) : this() { picture = src.picture; width = src.width; height = src.height }

    fun beginRecording(width: Int, height: Int): Canvas {
        val r = PictureRecorder()
        recorder = r
        this.width = width; this.height = height
        return RecordingCanvas(r.beginRecording(org.jetbrains.skia.Rect.makeWH(width.toFloat(), height.toFloat())), width, height)
    }

    fun endRecording() {
        val r = recorder ?: return
        picture = r.finishRecordingAsPicture()
        recorder = null
    }

    fun requiresHardwareAcceleration(): Boolean = false

    fun draw(canvas: Canvas) {
        endRecording()
        canvas.drawPicture(this)
    }

    internal fun skia(): org.jetbrains.skia.Picture? { endRecording(); return picture }
}

/**
 * android.graphics.RenderEffect als Skia-ImageFilter. Wird auf einen [RenderNode] gelegt und beim Zeichnen
 * per saveLayer angewandt.
 */
class RenderEffect private constructor(internal val filter: ImageFilter?) {
    companion object {
        @JvmStatic fun createOffsetEffect(offsetX: Float, offsetY: Float): RenderEffect =
            RenderEffect(ImageFilter.makeOffset(offsetX, offsetY, null, null))

        @JvmStatic fun createOffsetEffect(offsetX: Float, offsetY: Float, input: RenderEffect): RenderEffect =
            RenderEffect(ImageFilter.makeOffset(offsetX, offsetY, input.filter, null))

        @JvmStatic fun createBlurEffect(radiusX: Float, radiusY: Float, edgeTreatment: Shader.TileMode): RenderEffect =
            RenderEffect(ImageFilter.makeBlur(radiusX, radiusY, edgeTreatment.sk, null, null))

        @JvmStatic fun createBlurEffect(radiusX: Float, radiusY: Float, inputEffect: RenderEffect, edgeTreatment: Shader.TileMode): RenderEffect =
            RenderEffect(ImageFilter.makeBlur(radiusX, radiusY, edgeTreatment.sk, inputEffect.filter, null))

        @JvmStatic fun createBitmapEffect(bitmap: Bitmap): RenderEffect = RenderEffect(ImageFilter.makeImage(bitmap.image()))

        @JvmStatic fun createBitmapEffect(bitmap: Bitmap, src: Rect?, dst: Rect): RenderEffect {
            val img = bitmap.image()
            val s = src?.toSk() ?: org.jetbrains.skia.Rect.makeWH(img.width.toFloat(), img.height.toFloat())
            return RenderEffect(ImageFilter.makeImage(img, s, dst.toSk(), sampling(true)))
        }

        @JvmStatic fun createColorFilterEffect(colorFilter: ColorFilter): RenderEffect =
            RenderEffect(colorFilter.sk()?.let { ImageFilter.makeColorFilter(it, null, null) })

        @JvmStatic fun createColorFilterEffect(colorFilter: ColorFilter, renderEffect: RenderEffect): RenderEffect =
            RenderEffect(colorFilter.sk()?.let { ImageFilter.makeColorFilter(it, renderEffect.filter, null) } ?: renderEffect.filter)

        @JvmStatic fun createBlendModeEffect(dst: RenderEffect, src: RenderEffect, blendMode: BlendMode): RenderEffect =
            RenderEffect(ImageFilter.makeBlend(blendMode.sk, dst.filter, src.filter, null))

        @JvmStatic fun createChainEffect(outer: RenderEffect, inner: RenderEffect): RenderEffect = RenderEffect(
            when {
                outer.filter == null -> inner.filter
                inner.filter == null -> outer.filter
                else -> ImageFilter.makeCompose(outer.filter, inner.filter)
            },
        )

        @JvmStatic fun createShaderEffect(shader: Shader): RenderEffect =
            RenderEffect(shader.sk(true)?.let { ImageFilter.makeShader(it, false, null) })

        /** Momentaufnahme der Uniforms/Eingänge; [uniformShaderName] erhält den Inhalt des RenderNodes. */
        @JvmStatic fun createRuntimeShaderEffect(shader: RuntimeShader, uniformShaderName: String): RenderEffect =
            RenderEffect(ImageFilter.makeRuntimeShader(shader.builder(true, skip = uniformShaderName), uniformShaderName, null))
    }
}

/** android.graphics.RenderNode (Software-Umsetzung: Skia-Picture + optionaler ImageFilter). */
class RenderNode(@Suppress("unused") private val name: String?) {
    private var recorder: PictureRecorder? = null
    private var picture: org.jetbrains.skia.Picture? = null
    private var effect: RenderEffect? = null
    private var l = 0; private var t = 0; private var r = 0; private var b = 0
    private var alphaV = 1f
    private var txV = 0f
    private var tyV = 0f
    private var clipV = true
    val alpha: Float get() = alphaV
    val translationX: Float get() = txV
    val translationY: Float get() = tyV
    val clipToBounds: Boolean get() = clipV

    val width: Int get() = r - l
    val height: Int get() = b - t
    val left: Int get() = l
    val top: Int get() = t
    val right: Int get() = r
    val bottom: Int get() = b

    fun setPosition(left: Int, top: Int, right: Int, bottom: Int): Boolean {
        val changed = l != left || t != top || r != right || b != bottom
        l = left; t = top; r = right; b = bottom
        return changed
    }
    fun setPosition(position: Rect): Boolean = setPosition(position.left, position.top, position.right, position.bottom)

    fun setAlpha(alpha: Float): Boolean { alphaV = alpha; return true }
    fun setTranslationX(v: Float): Boolean { txV = v; return true }
    fun setTranslationY(v: Float): Boolean { tyV = v; return true }
    fun setClipToBounds(clip: Boolean): Boolean { clipV = clip; return true }

    fun setRenderEffect(renderEffect: RenderEffect?): Boolean { effect = renderEffect; return true }

    fun beginRecording(width: Int, height: Int): RecordingCanvas {
        val rec = PictureRecorder()
        recorder = rec
        return RecordingCanvas(rec.beginRecording(org.jetbrains.skia.Rect.makeWH(width.toFloat(), height.toFloat())), width, height)
    }

    fun beginRecording(): RecordingCanvas = beginRecording(width, height)

    fun endRecording() {
        val rec = recorder ?: return
        picture = rec.finishRecordingAsPicture()
        recorder = null
    }

    fun hasDisplayList(): Boolean = picture != null
    fun discardDisplayList() { picture = null }

    internal fun drawInto(canvas: Canvas) {
        endRecording()
        val pic = picture ?: return
        val c = canvas.skiaCanvas
        val n = c.save()
        c.translate(l + translationX, t + translationY)
        val bounds = org.jetbrains.skia.Rect.makeWH(width.toFloat(), height.toFloat())
        if (clipToBounds) c.clipRect(bounds)
        val f = effect?.filter
        if (f != null || alpha < 1f) {
            val p = org.jetbrains.skia.Paint()
            p.imageFilter = f
            if (alpha < 1f) p.alpha = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            c.saveLayer(bounds, p)
            c.drawPicture(pic, null, null)
            c.restore()
            p.close()
        } else {
            c.drawPicture(pic, null, null)
        }
        c.restoreToCount(n)
    }
}
