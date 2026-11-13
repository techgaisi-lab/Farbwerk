package android.graphics

import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.EncodedOrigin
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.SurfaceProps
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer

/** Gemeinsame Dekodierung über Skias Codec. */
internal object SkDecode {
    class Header(val width: Int, val height: Int, val origin: EncodedOrigin, val mime: String)

    fun codec(bytes: ByteArray): Codec? = try { Codec.makeFromData(Data.makeFromBytes(bytes)) } catch (_: Throwable) { null }

    fun mime(f: EncodedImageFormat): String = when (f) {
        EncodedImageFormat.PNG -> "image/png"
        EncodedImageFormat.JPEG -> "image/jpeg"
        EncodedImageFormat.WEBP -> "image/webp"
        EncodedImageFormat.GIF -> "image/gif"
        EncodedImageFormat.BMP -> "image/bmp"
        EncodedImageFormat.ICO -> "image/x-ico"
        EncodedImageFormat.HEIF -> "image/heif"
        EncodedImageFormat.WBMP -> "image/vnd.wap.wbmp"
        EncodedImageFormat.DNG -> "image/x-adobe-dng"
        else -> "image/unknown"
    }

    fun header(c: Codec, applyOrigin: Boolean): Header {
        val o = if (applyOrigin) c.encodedOrigin else EncodedOrigin.TOP_LEFT
        val swap = o.swapsWidthHeight()
        return Header(if (swap) c.height else c.width, if (swap) c.width else c.height, o, mime(c.encodedImageFormat))
    }

    /** Dekodiert vollständig (BGRA, vormultipliziert), wendet ggf. die EXIF-Ausrichtung an und skaliert auf [tw]×[th]. */
    fun decode(c: Codec, applyOrigin: Boolean, tw: Int, th: Int, crop: Rect?): org.jetbrains.skia.Bitmap {
        val raw = org.jetbrains.skia.Bitmap()
        raw.allocPixels(ImageInfo(c.width, c.height, ColorType.BGRA_8888, ColorAlphaType.PREMUL))
        c.readPixels(raw)
        val h = header(c, applyOrigin)
        if (h.origin == EncodedOrigin.TOP_LEFT && tw == c.width && th == c.height && crop == null) return raw
        // Ausrichtung + Skalierung + Zuschnitt in einem Zeichenschritt
        val outW = crop?.width() ?: tw
        val outH = crop?.height() ?: th
        val out = org.jetbrains.skia.Bitmap()
        out.allocPixels(ImageInfo(maxOf(1, outW), maxOf(1, outH), ColorType.BGRA_8888, ColorAlphaType.PREMUL))
        out.erase(0)
        val cv = org.jetbrains.skia.Canvas(out, SurfaceProps())
        if (crop != null) cv.translate(-crop.left.toFloat(), -crop.top.toFloat())
        cv.scale(tw / h.width.toFloat(), th / h.height.toFloat())
        if (h.origin != EncodedOrigin.TOP_LEFT) cv.concat(h.origin.toMatrix(c.width, c.height))
        raw.setImmutable()
        val img = org.jetbrains.skia.Image.makeFromBitmap(raw)
        val p = org.jetbrains.skia.Paint().apply { blendMode = org.jetbrains.skia.BlendMode.SRC }
        cv.drawImageRect(
            img, org.jetbrains.skia.Rect.makeWH(c.width.toFloat(), c.height.toFloat()),
            org.jetbrains.skia.Rect.makeWH(c.width.toFloat(), c.height.toFloat()),
            if (tw < h.width / 2 || th < h.height / 2)
                org.jetbrains.skia.FilterMipmap(org.jetbrains.skia.FilterMode.LINEAR, org.jetbrains.skia.MipmapMode.LINEAR)
            else org.jetbrains.skia.SamplingMode.CATMULL_ROM, p, false,
        )
        p.close(); cv.close(); img.close(); raw.close()
        return out
    }
}

/** android.graphics.BitmapFactory über Skias Codec. */
object BitmapFactory {
    class Options {
        @JvmField var inBitmap: Bitmap? = null
        @JvmField var inMutable = false
        @JvmField var inJustDecodeBounds = false
        @JvmField var inSampleSize = 0
        @JvmField var inPreferredConfig: Bitmap.Config? = Bitmap.Config.ARGB_8888
        @JvmField var inPremultiplied = true
        @JvmField var inDither = false
        @JvmField var inDensity = 0
        @JvmField var inTargetDensity = 0
        @JvmField var inScreenDensity = 0
        @JvmField var inScaled = true
        @JvmField var inPurgeable = false
        @JvmField var inInputShareable = false
        @JvmField var inPreferQualityOverSpeed = false
        @JvmField var outWidth = 0
        @JvmField var outHeight = 0
        @JvmField var outMimeType: String? = null
        @JvmField var outConfig: Bitmap.Config? = null
        @JvmField var mCancel = false
        @JvmField var inTempStorage: ByteArray? = null
        fun requestCancelDecode() { mCancel = true }
    }

    @JvmStatic fun decodeByteArray(data: ByteArray, offset: Int, length: Int, opts: Options?): Bitmap? {
        val bytes = if (offset == 0 && length == data.size) data else data.copyOfRange(offset, offset + length)
        val c = SkDecode.codec(bytes) ?: run { opts?.apply { outWidth = -1; outHeight = -1; outMimeType = null }; return null }
        try {
            val h = SkDecode.header(c, false)
            var sample = opts?.inSampleSize ?: 1
            if (sample < 1) sample = 1
            // Android rundet auf die nächstkleinere Zweierpotenz
            sample = Integer.highestOneBit(sample)
            val w = maxOf(1, h.width / sample)
            val hh = maxOf(1, h.height / sample)
            opts?.apply {
                outWidth = w; outHeight = hh; outMimeType = h.mime
                outConfig = if (inPreferredConfig == Bitmap.Config.ALPHA_8) Bitmap.Config.ALPHA_8 else Bitmap.Config.ARGB_8888
            }
            if (opts?.inJustDecodeBounds == true) return null
            val sk = SkDecode.decode(c, false, w, hh, null)
            var bmp = Bitmap.wrap(sk, mutable = opts?.inMutable == true)
            if (opts?.inPreferredConfig == Bitmap.Config.ALPHA_8) {
                val a = bmp.copy(Bitmap.Config.ALPHA_8, opts.inMutable)
                bmp.recycle(); bmp = a
            }
            return bmp
        } finally {
            c.close()
        }
    }

    @JvmStatic fun decodeByteArray(data: ByteArray, offset: Int, length: Int): Bitmap? = decodeByteArray(data, offset, length, null)

    @JvmStatic fun decodeStream(`is`: InputStream?, outPadding: Rect?, opts: Options?): Bitmap? {
        if (`is` == null) return null
        outPadding?.set(-1, -1, -1, -1)
        val bytes = `is`.readBytes()
        return decodeByteArray(bytes, 0, bytes.size, opts)
    }

    @JvmStatic fun decodeStream(`is`: InputStream?): Bitmap? = decodeStream(`is`, null, null)

    @JvmStatic fun decodeFile(pathName: String?, opts: Options?): Bitmap? {
        if (pathName == null) return null
        return try { FileInputStream(pathName).use { decodeStream(it, null, opts) } } catch (_: Exception) { null }
    }

    @JvmStatic fun decodeFile(pathName: String?): Bitmap? = decodeFile(pathName, null)

    @JvmStatic fun decodeFileDescriptor(fd: FileDescriptor?, outPadding: Rect?, opts: Options?): Bitmap? =
        fd?.let { FileInputStream(it).use { s -> decodeStream(s, outPadding, opts) } }

    @JvmStatic fun decodeFileDescriptor(fd: FileDescriptor?): Bitmap? = decodeFileDescriptor(fd, null, null)
}

/** android.graphics.ImageDecoder über Skias Codec (inkl. EXIF-Ausrichtung wie Android). */
class ImageDecoder private constructor(private val codec: Codec, private val header: SkDecode.Header) : AutoCloseable {

    /** Bildquelle (liest die Bytes erst beim Dekodieren). */
    abstract class Source internal constructor() {
        internal abstract fun bytes(): ByteArray
    }

    private class BytesSource(private val provider: () -> ByteArray) : Source() {
        override fun bytes(): ByteArray = provider()
    }

    /** Größe (entspricht android.util.Size). */
    class Size(val width: Int, val height: Int) {
        override fun toString(): String = "${width}x$height"
        override fun equals(other: Any?): Boolean = other is Size && other.width == width && other.height == height
        override fun hashCode(): Int = width * 31 + height
    }

    class ImageInfo internal constructor(private val decoder: ImageDecoder) {
        val size: Size get() = Size(decoder.header.width, decoder.header.height)
        val mimeType: String get() = decoder.header.mime
        val isAnimated: Boolean get() = decoder.codec.frameCount > 1
        val colorSpace: Any? get() = null
    }

    fun interface OnHeaderDecodedListener {
        fun onHeaderDecoded(decoder: ImageDecoder, info: ImageInfo, source: Source)
    }

    fun interface OnPartialImageListener {
        fun onPartialImage(exception: DecodeException): Boolean
    }

    class DecodeException(val error: Int, msg: String?, cause: Throwable?) : java.io.IOException(msg, cause) {
        companion object {
            const val SOURCE_EXCEPTION = 1
            const val SOURCE_INCOMPLETE = 2
            const val SOURCE_MALFORMED_DATA = 3
        }
    }

    var allocator: Int = ALLOCATOR_DEFAULT
    var isMutableRequired: Boolean = false
    var isUnpremultipliedRequired: Boolean = false
    var memorySizePolicy: Int = MEMORY_POLICY_DEFAULT
    var isDecodeAsAlphaMaskEnabled: Boolean = false
    var crop: Rect? = null
    var onPartialImageListener: OnPartialImageListener? = null
    var postProcessor: Any? = null

    private var targetW = header.width
    private var targetH = header.height

    fun setTargetSize(width: Int, height: Int) {
        require(width > 0 && height > 0) { "Dimensions must be positive! provided ($width, $height)" }
        targetW = width; targetH = height
    }

    fun setTargetSampleSize(sampleSize: Int) {
        val s = maxOf(1, sampleSize)
        setTargetSize(maxOf(1, header.width / s), maxOf(1, header.height / s))
    }

    @Suppress("UNUSED_PARAMETER")
    fun setTargetColorSpace(colorSpace: Any?) {}

    override fun close() { codec.close() }

    private fun decode(): Bitmap {
        val sk = SkDecode.decode(codec, true, targetW, targetH, crop)
        var bmp = Bitmap.wrap(sk, mutable = isMutableRequired)
        if (isDecodeAsAlphaMaskEnabled) {
            val a = bmp.copy(Bitmap.Config.ALPHA_8, isMutableRequired)
            bmp.recycle(); bmp = a
        }
        return bmp
    }

    companion object {
        const val ALLOCATOR_DEFAULT = 0
        const val ALLOCATOR_SOFTWARE = 1
        const val ALLOCATOR_SHARED_MEMORY = 2
        const val ALLOCATOR_HARDWARE = 3
        const val MEMORY_POLICY_LOW_RAM = 0
        const val MEMORY_POLICY_DEFAULT = 1

        @JvmStatic fun createSource(cr: android.content.ContentResolver, uri: android.net.Uri): Source = BytesSource {
            cr.openInputStream(uri)?.use { it.readBytes() } ?: throw java.io.FileNotFoundException(uri.toString())
        }

        @JvmStatic fun createSource(file: File): Source = BytesSource { file.readBytes() }
        @JvmStatic fun createSource(data: ByteArray): Source = BytesSource { data }
        @JvmStatic fun createSource(data: ByteArray, offset: Int, length: Int): Source =
            BytesSource { data.copyOfRange(offset, offset + length) }
        @JvmStatic fun createSource(buffer: ByteBuffer): Source = BytesSource {
            val b = buffer.duplicate(); ByteArray(b.remaining()).also { b.get(it) }
        }

        @JvmStatic fun isMimeTypeSupported(mimeType: String): Boolean =
            mimeType.lowercase() in setOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp", "image/x-ico", "image/vnd.wap.wbmp")

        private fun open(src: Source): ImageDecoder {
            val bytes = try { src.bytes() } catch (e: java.io.IOException) { throw e } catch (t: Throwable) {
                throw DecodeException(DecodeException.SOURCE_EXCEPTION, t.message, t)
            }
            val c = SkDecode.codec(bytes)
                ?: throw DecodeException(DecodeException.SOURCE_MALFORMED_DATA, "Failed to create image decoder with message 'unimplemented'", null)
            return ImageDecoder(c, SkDecode.header(c, true))
        }

        @JvmStatic fun decodeBitmap(src: Source, listener: OnHeaderDecodedListener): Bitmap {
            val d = open(src)
            try {
                listener.onHeaderDecoded(d, ImageInfo(d), src)
                return d.decode()
            } finally {
                d.close()
            }
        }

        @JvmStatic fun decodeBitmap(src: Source): Bitmap {
            val d = open(src)
            try { return d.decode() } finally { d.close() }
        }
    }
}
