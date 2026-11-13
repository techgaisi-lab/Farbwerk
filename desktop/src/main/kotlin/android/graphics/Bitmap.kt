package android.graphics

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.impl.BufferUtil
import java.io.OutputStream
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * android.graphics.Bitmap auf Basis einer org.jetbrains.skia.Bitmap.
 *
 * Speicherformat: ARGB_8888 → Skia BGRA_8888 vormultipliziert (als Little-Endian-Int gelesen = 0xAARRGGBB),
 * ALPHA_8 → Skia ALPHA_8. Die Skia-Bitmap ist als „immutable“ markiert, damit [image] die Pixel ohne Kopie
 * teilt; nach jeder Änderung wird über [touch] die Generation weitergezählt (notifyPixelsChanged), damit
 * gecachte Bilder/GPU-Texturen erneuert werden.
 */
class Bitmap private constructor(sk: org.jetbrains.skia.Bitmap, config: Config, mutable: Boolean) {

    enum class Config { ALPHA_8, RGB_565, ARGB_4444, ARGB_8888, RGBA_F16, HARDWARE, RGBA_1010102 }

    enum class CompressFormat(internal val sk: EncodedImageFormat) {
        JPEG(EncodedImageFormat.JPEG), PNG(EncodedImageFormat.PNG), WEBP(EncodedImageFormat.WEBP),
        WEBP_LOSSY(EncodedImageFormat.WEBP), WEBP_LOSSLESS(EncodedImageFormat.WEBP),
    }

    /** Die zugrunde liegende Skia-Bitmap (Pixel liegen direkt darin). */
    var skiaBitmap: org.jetbrains.skia.Bitmap = sk
        private set

    private var cfg: Config = config
    val config: Config get() = cfg

    var isMutable: Boolean = mutable
        private set

    var isRecycled: Boolean = false
        private set

    var density: Int = DENSITY_DEFAULT

    var isPremultiplied: Boolean = true

    private var hasAlphaFlag = true

    val width: Int get() = skiaBitmap.width
    val height: Int get() = skiaBitmap.height
    val rowBytes: Int get() = skiaBitmap.rowBytes
    val byteCount: Int get() = rowBytes * height

    private var allocBytes: Int = sk.rowBytes * sk.height
    val allocationByteCount: Int get() = maxOf(allocBytes, byteCount)

    // ---------- Änderungsverfolgung ----------
    private var stampValue: Long = STAMPS.incrementAndGet()
    private var cachedImage: Image? = null
    private var cachedBuffer: ByteBuffer? = null

    /** Monoton steigender Zähler; ändert sich mit jeder Pixeländerung. */
    val stamp: Long get() = stampValue

    val generationId: Int get() = stampValue.toInt()

    /**
     * Markiert die Pixel als geändert (Canvas & Co. rufen das nach jedem Schreibzugriff auf). Die Skia-Generation
     * wird sofort erneuert, damit auch fremde Nutzer der Skia-Bitmap (Compose-ImageBitmap, GPU-Texturcache)
     * die Änderung sehen.
     */
    fun touch() {
        if (isRecycled) return
        stampValue = STAMPS.incrementAndGet()
        skiaBitmap.notifyPixelsChanged()
        cachedImage = null
    }

    private fun flush() {}

    /** Skia-Image, das die Pixel ohne Kopie teilt (bis zur nächsten Änderung gecacht). */
    fun image(): Image {
        flush()
        return cachedImage ?: Image.makeFromBitmap(skiaBitmap).also { cachedImage = it }
    }

    /** Direkter Zugriff auf den Pixelspeicher (Little Endian). */
    fun pixelBuffer(): ByteBuffer {
        cachedBuffer?.let { return it.duplicate().order(ByteOrder.LITTLE_ENDIAN) }
        val pm = skiaBitmap.peekPixels() ?: error("Bitmap ohne Pixel")
        val buf = BufferUtil.getByteBufferFromPointer(pm.addr, rowBytes * height).order(ByteOrder.LITTLE_ENDIAN)
        cachedBuffer = buf
        return buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun skType(): ColorType = if (cfg == Config.ALPHA_8) ColorType.ALPHA_8 else ColorType.BGRA_8888

    // ---------- Eigenschaften ----------
    fun hasAlpha(): Boolean = config == Config.ALPHA_8 || hasAlphaFlag
    fun setHasAlpha(hasAlpha: Boolean) { hasAlphaFlag = hasAlpha }
    fun hasMipMap(): Boolean = false
    fun setHasMipMap(hasMipMap: Boolean) {}
    fun prepareToDraw() {}
    fun asShared(): Bitmap = this
    fun getScaledWidth(targetDensity: Int): Int = width
    fun getScaledHeight(targetDensity: Int): Int = height

    fun recycle() {
        if (isRecycled) return
        isRecycled = true
        cachedImage = null
        cachedBuffer = null
        skiaBitmap.close()
    }

    fun reconfigure(width: Int, height: Int, config: Config) {
        val c = normalize(config)
        val old = skiaBitmap
        skiaBitmap = alloc(width, height, c)
        this.cfg = c
        allocBytes = maxOf(allocBytes, skiaBitmap.rowBytes * height)
        cachedImage = null; cachedBuffer = null
        old.close()
        touch()
    }

    fun setWidth(width: Int) = reconfigure(width, height, config)
    fun setHeight(height: Int) = reconfigure(width, height, config)
    fun setConfig(config: Config) = reconfigure(width, height, config)

    // ---------- Pixelzugriff ----------
    fun eraseColor(c: Int) {
        skiaBitmap.erase(c)
        touch()
    }

    fun getPixel(x: Int, y: Int): Int {
        checkXY(x, y)
        return skiaBitmap.getColor(x, y)
    }

    fun setPixel(x: Int, y: Int, color: Int) {
        checkXY(x, y)
        val buf = pixelBuffer()
        if (cfg == Config.ALPHA_8) buf.put(y * rowBytes + x, (color ushr 24).toByte())
        else buf.putInt(y * rowBytes + x * 4, Color.premultiply(color))
        touch()
    }

    fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        checkPixelsAccess(x, y, width, height, offset, stride, pixels.size)
        if (width == 0 || height == 0) return
        val src = skiaBitmap.peekPixels() ?: return
        // Streifenweise über einen direkten Puffer: Skia rechnet nativ in nicht vormultipliziertes BGRA
        // (= 0xAARRGGBB als Little-Endian-Int) um, danach Blockkopie in das Int-Array.
        val rowsPerStrip = maxOf(1, STRIP_PIXELS / width)
        val scratch = scratch(minOf(height, rowsPerStrip) * width * 4)
        val addr = BufferUtil.getPointerFromByteBuffer(scratch)
        val ib = scratch.asIntBuffer()
        var row0 = 0
        while (row0 < height) {
            val n = minOf(rowsPerStrip, height - row0)
            src.readPixels(ImageInfo(width, n, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), addr, width * 4, x, y + row0)
            if (stride == width) {
                ib.position(0); ib.get(pixels, offset + row0 * width, n * width)
            } else for (r in 0 until n) {
                ib.position(r * width); ib.get(pixels, offset + (row0 + r) * stride, width)
            }
            row0 += n
        }
    }

    fun setPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        checkPixelsAccess(x, y, width, height, offset, stride, pixels.size)
        if (width == 0 || height == 0) return
        val dst = skiaBitmap.peekPixels() ?: return
        val rowsPerStrip = maxOf(1, STRIP_PIXELS / width)
        val scratch = scratch(minOf(height, rowsPerStrip) * width * 4)
        val addr = BufferUtil.getPointerFromByteBuffer(scratch)
        val ib = scratch.asIntBuffer()
        var row0 = 0
        while (row0 < height) {
            val n = minOf(rowsPerStrip, height - row0)
            if (stride == width) {
                ib.position(0); ib.put(pixels, offset + row0 * width, n * width)
            } else for (r in 0 until n) {
                ib.position(r * width); ib.put(pixels, offset + (row0 + r) * stride, width)
            }
            val sp = org.jetbrains.skia.Pixmap.make(ImageInfo(width, n, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), addr, width * 4, null)
            sp.readPixels(ImageInfo(width, n, skType(), ColorAlphaType.PREMUL), dst.getAddr(x, y + row0), dst.rowBytes)
            sp.close()
            row0 += n
        }
        touch()
    }

    /** Schreibt Pixel im Format [srcInfo] an (x, y) und rechnet dabei in das eigene Format um. */
    private fun writeRegion(bytes: ByteArray, srcInfo: ImageInfo, srcRowBytes: Int, x: Int, y: Int) {
        val tmp = org.jetbrains.skia.Bitmap()
        try {
            tmp.installPixels(srcInfo, bytes, srcRowBytes)
            val src = tmp.peekPixels() ?: return
            val dst = skiaBitmap.peekPixels() ?: return
            val dstInfo = ImageInfo(srcInfo.width, srcInfo.height, skType(), ColorAlphaType.PREMUL)
            src.readPixels(dstInfo, dst.getAddr(x, y), dst.rowBytes)
        } finally {
            tmp.close()
        }
        touch()
    }

    fun copyPixelsToBuffer(dst: Buffer) {
        flush()
        val bytes: ByteArray = if (cfg == Config.ALPHA_8) {
            ByteArray(byteCount).also { pixelBuffer().get(it) }
        } else {
            // Android-Speicherreihenfolge: R, G, B, A (vormultipliziert)
            skiaBitmap.readPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL), rowBytes, 0, 0)
                ?: return
        }
        when (dst) {
            is ByteBuffer -> dst.put(bytes)
            is IntBuffer -> {
                val ib = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asIntBuffer()
                val tmp = IntArray(ib.remaining()); ib.get(tmp); dst.put(tmp)
            }
            is ShortBuffer -> {
                val sb = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asShortBuffer()
                val tmp = ShortArray(sb.remaining()); sb.get(tmp); dst.put(tmp)
            }
            else -> throw RuntimeException("unsupported Buffer subclass")
        }
    }

    fun copyPixelsFromBuffer(src: Buffer) {
        val n = byteCount
        val bytes = ByteArray(n)
        when (src) {
            is ByteBuffer -> {
                if (src.remaining() < n) throw RuntimeException("Buffer not large enough for pixels")
                src.get(bytes)
            }
            is IntBuffer -> {
                if (src.remaining() * 4 < n) throw RuntimeException("Buffer not large enough for pixels")
                val tmp = IntArray(n / 4); src.get(tmp)
                ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asIntBuffer().put(tmp)
            }
            is ShortBuffer -> {
                if (src.remaining() * 2 < n) throw RuntimeException("Buffer not large enough for pixels")
                val tmp = ShortArray(n / 2); src.get(tmp)
                ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asShortBuffer().put(tmp)
            }
            else -> throw RuntimeException("unsupported Buffer subclass")
        }
        if (cfg == Config.ALPHA_8) {
            pixelBuffer().put(bytes)
            touch()
        } else {
            writeRegion(bytes, ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL), rowBytes, 0, 0)
        }
    }

    // ---------- Kopien ----------
    fun copy(config: Config, isMutable: Boolean): Bitmap {
        val c = normalize(config)
        val out = Bitmap(alloc(width, height, c), c, isMutable)
        copyInto(out, 0, 0)
        out.density = density
        return out
    }

    /** Kopiert den Ausschnitt ab (x, y) in [dst] (Größe von dst), mit Formatumwandlung. */
    internal fun copyInto(dst: Bitmap, x: Int, y: Int) {
        flush()
        val src = skiaBitmap.peekPixels() ?: return
        val d = dst.skiaBitmap.peekPixels() ?: return
        val info = ImageInfo(dst.width, dst.height, dst.skType(), ColorAlphaType.PREMUL)
        src.readPixels(info, d.addr, d.rowBytes, x, y)
        dst.touch()
    }

    fun extractAlpha(): Bitmap = copy(Config.ALPHA_8, true)

    fun extractAlpha(paint: Paint?, offsetXY: IntArray?): Bitmap {
        offsetXY?.let { it[0] = 0; it[1] = 0 }
        return extractAlpha()
    }

    fun sameAs(other: Bitmap?): Boolean {
        if (other == null) return false
        if (other === this) return true
        if (other.width != width || other.height != height || other.config != config) return false
        val a = pixelBuffer(); val b = other.pixelBuffer()
        return a == b
    }

    fun compress(format: CompressFormat, quality: Int, stream: OutputStream): Boolean {
        val img = if (cfg == Config.ALPHA_8) copy(Config.ARGB_8888, false).image() else image()
        val data = img.encodeToData(format.sk, quality.coerceIn(0, 100)) ?: return false
        stream.write(data.bytes)
        return true
    }

    private fun checkXY(x: Int, y: Int) {
        require(x in 0 until width) { "x must be < bitmap.width()" }
        require(y in 0 until height) { "y must be < bitmap.height()" }
    }

    private fun checkPixelsAccess(x: Int, y: Int, w: Int, h: Int, offset: Int, stride: Int, length: Int) {
        require(x >= 0 && y >= 0) { "x and y must be >= 0" }
        require(x + w <= width) { "x + width must be <= bitmap.width()" }
        require(y + h <= height) { "y + height must be <= bitmap.height()" }
        require(kotlin.math.abs(stride) >= w) { "abs(stride) must be >= width" }
        val lastScanline = offset + (h - 1) * stride
        if (offset < 0 || offset + w > length || lastScanline < 0 || lastScanline + w > length) {
            throw ArrayIndexOutOfBoundsException()
        }
    }

    override fun toString(): String = "Bitmap(${width}x$height, $config)"

    companion object {
        const val DENSITY_NONE = 0
        private const val DENSITY_DEFAULT = 160
        private val STAMPS = AtomicLong(1)
        private const val STRIP_PIXELS = 1 shl 20
        private val SCRATCH = ThreadLocal<ByteBuffer>()

        /** Thread-lokaler direkter Arbeitspuffer (Little Endian) mit mindestens [bytes] Bytes. */
        private fun scratch(bytes: Int): ByteBuffer {
            val cur = SCRATCH.get()
            if (cur != null && cur.capacity() >= bytes) { cur.clear(); return cur }
            val b = ByteBuffer.allocateDirect(maxOf(bytes, 1 shl 16)).order(ByteOrder.LITTLE_ENDIAN)
            SCRATCH.set(b)
            return b
        }

        private fun normalize(c: Config): Config = if (c == Config.ALPHA_8) Config.ALPHA_8 else Config.ARGB_8888

        internal fun alloc(w: Int, h: Int, c: Config): org.jetbrains.skia.Bitmap {
            require(w > 0 && h > 0) { "width and height must be > 0" }
            val sk = org.jetbrains.skia.Bitmap()
            val info = if (c == Config.ALPHA_8) ImageInfo(w, h, ColorType.ALPHA_8, ColorAlphaType.PREMUL)
            else ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
            if (!sk.allocPixels(info)) throw OutOfMemoryError("Bitmap ${w}x$h")
            sk.erase(0)
            sk.setImmutable()
            return sk
        }

        /** Hüllt eine vorhandene Skia-Bitmap (z. B. aus einem Decoder) ein; Format wird angepasst. */
        fun wrap(sk: org.jetbrains.skia.Bitmap, mutable: Boolean = true): Bitmap {
            val isA8 = sk.colorType == ColorType.ALPHA_8
            val c = if (isA8) Config.ALPHA_8 else Config.ARGB_8888
            if ((sk.colorType == ColorType.BGRA_8888 || isA8) && sk.alphaType == ColorAlphaType.PREMUL) {
                sk.setImmutable()
                return Bitmap(sk, c, mutable)
            }
            val out = Bitmap(alloc(sk.width, sk.height, c), c, mutable)
            val src = sk.peekPixels()
            val d = out.skiaBitmap.peekPixels()
            if (src != null && d != null) {
                src.readPixels(ImageInfo(sk.width, sk.height, out.skType(), ColorAlphaType.PREMUL), d.addr, d.rowBytes)
            }
            sk.close()
            out.touch()
            return out
        }

        @JvmStatic fun createBitmap(width: Int, height: Int, config: Config): Bitmap {
            val c = normalize(config)
            return Bitmap(alloc(width, height, c), c, true)
        }

        @JvmStatic fun createBitmap(width: Int, height: Int, config: Config, hasAlpha: Boolean): Bitmap =
            createBitmap(width, height, config).also { it.hasAlphaFlag = hasAlpha }

        @JvmStatic fun createBitmap(src: Bitmap): Bitmap = createBitmap(src, 0, 0, src.width, src.height)

        @JvmStatic fun createBitmap(source: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap {
            require(x >= 0 && y >= 0 && x + width <= source.width && y + height <= source.height) {
                "x + width must be <= bitmap.width()"
            }
            val out = Bitmap(alloc(width, height, source.config), source.config, false)
            source.copyInto(out, x, y)
            out.density = source.density
            return out
        }

        @JvmStatic fun createBitmap(
            source: Bitmap, x: Int, y: Int, width: Int, height: Int, m: Matrix?, filter: Boolean,
        ): Bitmap {
            if (m == null || m.isIdentity()) return createBitmap(source, x, y, width, height)
            val srcR = Rect(x, y, x + width, y + height)
            val dstR = RectF(0f, 0f, width.toFloat(), height.toFloat())
            val devR = RectF()
            m.mapRect(devR, dstR)
            val nw = kotlin.math.round(devR.width()).toInt().coerceAtLeast(1)
            val nh = kotlin.math.round(devR.height()).toInt().coerceAtLeast(1)
            val cfg = if (source.config == Config.ALPHA_8 && m.rectStaysRect()) Config.ALPHA_8 else Config.ARGB_8888
            val out = createBitmap(nw, nh, cfg)
            val c = Canvas(out)
            c.translate(-devR.left, -devR.top)
            c.concat(m)
            val p = Paint().apply { isFilterBitmap = filter; if (!m.rectStaysRect()) isAntiAlias = true }
            c.drawBitmap(source, srcR, dstR, p)
            out.isMutable = false
            out.density = source.density
            return out
        }

        @JvmStatic fun createScaledBitmap(src: Bitmap, dstWidth: Int, dstHeight: Int, filter: Boolean): Bitmap {
            if (src.width == dstWidth && src.height == dstHeight) return src
            val m = Matrix().apply { setScale(dstWidth / src.width.toFloat(), dstHeight / src.height.toFloat()) }
            return createBitmap(src, 0, 0, src.width, src.height, m, filter)
        }

        @JvmStatic fun createBitmap(colors: IntArray, offset: Int, stride: Int, width: Int, height: Int, config: Config): Bitmap {
            val b = createBitmap(width, height, config)
            b.setPixels(colors, offset, stride, 0, 0, width, height)
            b.isMutable = false
            return b
        }

        @JvmStatic fun createBitmap(colors: IntArray, width: Int, height: Int, config: Config): Bitmap =
            createBitmap(colors, 0, width, width, height, config)
    }
}
