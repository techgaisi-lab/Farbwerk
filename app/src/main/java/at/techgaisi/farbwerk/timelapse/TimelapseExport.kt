package at.techgaisi.farbwerk.timelapse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import at.techgaisi.farbwerk.io.Exporters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Export der Zeitraffer-Aufnahme als MP4 (H.264 über MediaCodec-Eingabefläche + MediaMuxer).
 *
 * Die JPEG-Sequenz wird im Hintergrund gelesen und mit [FPS] Bildern/s kodiert. Bei [Length.SECONDS_30] wird die
 * Sequenz gleichmäßig auf höchstens 30 s ausgedünnt (kürzere Aufnahmen bleiben unverändert). Die Ausgabegröße
 * richtet sich nach dem letzten Bild (Endzustand des Kunstwerks); abweichend große Bilder (z. B. nach
 * Leinwandänderung) werden eingepasst und mit Weiß aufgefüllt.
 */
object TimelapseExport {
    const val FPS = 30

    enum class Length(val label: String) { FULL("Volle Länge"), SECONDS_30("30 Sekunden") }

    /** Indizes der zu kodierenden Bilder aus [count] Aufnahmen. */
    fun selection(count: Int, length: Length): IntArray {
        val max = FPS * 30
        if (length == Length.FULL || count <= max) return IntArray(count) { it }
        return IntArray(max) { i -> ((i.toLong() * (count - 1)) / (max - 1)).toInt() }
    }

    /** Dauer in Sekunden für die Anzeige. */
    fun durationSeconds(count: Int, length: Length): Float = selection(count, length).size / FPS.toFloat()

    /**
     * Kodiert die Aufnahme von [docId] nach `cacheDir/exports/<name>_Zeitraffer.mp4`.
     * [onProgress] erhält Werte 0…1 (aus dem Hintergrund-Thread). Abbruch über die Coroutine.
     */
    suspend fun exportMp4(
        context: Context,
        docId: String,
        name: String,
        length: Length,
        onProgress: (Float) -> Unit,
    ): File {
        val frames = Timelapse.framesFlushed(context, docId)
        require(frames.isNotEmpty()) { "Keine Zeitraffer-Aufnahme vorhanden" }
        return withContext(Dispatchers.Default) {
            val out = File(Exporters.exportDir(context), Exporters.safeName(name) + "_Zeitraffer.mp4")
            out.delete()
            try {
                encode(frames, selection(frames.size, length), out, onProgress)
            } catch (t: Throwable) {
                out.delete(); throw t
            }
            out
        }
    }

    private suspend fun encode(frames: List<File>, pick: IntArray, out: File, onProgress: (Float) -> Unit) {
        // Ausgabegröße aus dem letzten Bild, auf 16 px ausgerichtet (Encoder-Anforderung)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(frames.last().path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Zeitraffer-Bild unlesbar" }
        val w = max(16, (bounds.outWidth / 16) * 16)
        val h = max(16, (bounds.outHeight / 16) * 16)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (w.toLong() * h * FPS / 5).coerceIn(2_000_000L, 50_000_000L).toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var surface: android.view.Surface? = null
        var muxer: MediaMuxer? = null
        var muxing = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var outIndex = 0L
            val frameUs = 1_000_000L / FPS

            fun drain(endOfStream: Boolean) {
                if (endOfStream) codec.signalEndOfInputStream()
                while (true) {
                    val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true
                        }
                        idx >= 0 -> {
                            val buf = codec.getOutputBuffer(idx)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && buf != null && muxing) {
                                // Gleichmäßige Zeitstempel aus der Bildnummer
                                info.presentationTimeUs = outIndex++ * frameUs
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buf, info)
                            }
                            codec.releaseOutputBuffer(idx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val dst = RectF()
            val opts = BitmapFactory.Options().apply { inMutable = true }
            var reuse: Bitmap? = null
            var lastDecoded = -1
            for ((n, i) in pick.withIndex()) {
                coroutineContext.ensureActive()
                if (i != lastDecoded) {
                    reuse = decode(frames[i].path, opts, reuse) ?: reuse
                    lastDecoded = i
                }
                val bmp = reuse
                val c = surface.lockHardwareCanvas()
                try {
                    c.drawColor(Color.WHITE)
                    if (bmp != null) {
                        // Einpassen (Seitenverhältnis bleibt erhalten)
                        val s = min(w / bmp.width.toFloat(), h / bmp.height.toFloat())
                        val dw = bmp.width * s; val dh = bmp.height * s
                        dst.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
                        c.drawBitmap(bmp, null, dst, paint)
                    }
                } finally {
                    surface.unlockCanvasAndPost(c)
                }
                drain(false)
                if (n % 4 == 0) onProgress(n / pick.size.toFloat())
            }
            drain(true)
            reuse?.recycle()
            onProgress(1f)
        } finally {
            try { codec.stop() } catch (_: Throwable) {}
            codec.release()
            surface?.release()
            try { if (muxing) muxer?.stop() } catch (_: Throwable) {}
            muxer?.release()
        }
    }

    /** Dekodiert ein JPEG, wenn möglich in [reuse] (gleiche Größe), sonst in ein neues Bitmap. */
    internal fun decode(path: String, opts: BitmapFactory.Options, reuse: Bitmap?): Bitmap? {
        if (reuse != null && !reuse.isRecycled) {
            opts.inBitmap = reuse
            try {
                BitmapFactory.decodeFile(path, opts)?.let { return it }
            } catch (_: IllegalArgumentException) {
                // Größe passt nicht – neu anlegen
            }
        }
        opts.inBitmap = null
        val fresh = BitmapFactory.decodeFile(path, opts) ?: return null
        if (reuse != null && reuse !== fresh && !reuse.isRecycled) reuse.recycle()
        return fresh
    }

    /** Formatiert Sekunden als m:ss. */
    fun formatTime(seconds: Float): String {
        val s = seconds.roundToInt().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}
