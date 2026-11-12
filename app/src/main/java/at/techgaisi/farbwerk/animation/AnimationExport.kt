package at.techgaisi.farbwerk.animation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import at.techgaisi.farbwerk.model.ArtDocument
import at.techgaisi.farbwerk.model.Layer
import at.techgaisi.farbwerk.render.Compositor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Export des Animationsassistenten als animiertes GIF oder MP4 (H.264 über MediaCodec).
 * Ist der Assistent aus, wird jede Ebene/Gruppe als ein Bild mit 15 fps exportiert.
 * Private Ebenen werden ausgelassen. Bilder werden auf dem Main-Thread gerendert, kodiert wird im Hintergrund.
 */
object AnimationExport {

    /** Ein Bild der Ausgabe: Bildindex und Anzeigedauer in Ausgabebildern. */
    private class Step(val frame: Int, val repeat: Int)

    private fun steps(doc: ArtDocument): Pair<List<Step>, AnimationSettings> {
        val s = AnimationController.settings(doc) ?: AnimationSettings()
        val frames = AnimationController.frames(doc)
        val seq = if (AnimationController.settings(doc) != null) AnimationController.playbackSequence(doc).toList()
        else frames.indices.toList()
        // aufeinanderfolgende gleiche Bilder (Haltedauer) zusammenfassen
        val out = ArrayList<Step>()
        for (i in seq) {
            val last = out.lastOrNull()
            if (last != null && last.frame == i) out[out.lastIndex] = Step(i, last.repeat + 1) else out += Step(i, 1)
        }
        return out to s
    }

    /** Ausgabegröße: auf [maxSide] begrenzt, auf [align] Pixel abgerundet. */
    private fun outSize(doc: ArtDocument, maxSide: Int, align: Int): Pair<Int, Int> {
        val sc = min(1f, maxSide / max(doc.width, doc.height).toFloat())
        val w = max(align, ((doc.width * sc).roundToInt() / align) * align)
        val h = max(align, ((doc.height * sc).roundToInt() / align) * align)
        return w to h
    }

    /**
     * Rendert ein Bild (inkl. Hinter-/Vordergrund) in [target]. Muss auf dem Main-Thread laufen.
     * HIDE-Overrides des Assistenten werden dafür kurz aufgehoben.
     */
    private fun renderFrame(doc: ArtDocument, s: AnimationSettings, frames: List<Frame>, index: Int, target: Bitmap,
                            opaqueBackground: Boolean) {
        val layers = ArrayList<Layer>()
        if (s.firstIsBackground && frames.size > 1 && index != 0) layers += frames[0].layers
        layers += frames[index].layers
        if (s.lastIsForeground && frames.size > 1 && index != frames.lastIndex && s.mode == AssistMode.ANIMATION) {
            layers += frames.last().layers
        }
        val visible = layers.filter { !it.isPrivate }
        val saved = visible.map { it.drawOverride }
        visible.forEach { if (it.drawOverride === AnimationController.HIDE) it.drawOverride = null }
        try {
            target.eraseColor(Color.TRANSPARENT)
            val c = Canvas(target)
            if (doc.backgroundVisible) c.drawColor(doc.backgroundColor)
            else if (opaqueBackground) c.drawColor(Color.WHITE)
            c.scale(target.width / doc.width.toFloat(), target.height / doc.height.toFloat())
            Compositor.renderLayers(c, doc, visible, forceVisible = false)
        } finally {
            visible.forEachIndexed { i, l -> if (l.drawOverride == null) l.drawOverride = saved[i] }
        }
    }

    /** Animiertes GIF. Aufruf aus einer Coroutine auf dem Main-Thread. */
    suspend fun exportGif(doc: ArtDocument, out: File, maxSide: Int = 1024) {
        val (steps, s) = steps(doc)
        require(steps.isNotEmpty()) { "Keine Bilder" }
        val frames = AnimationController.frames(doc)
        val (w, h) = outSize(doc, maxSide, 2)
        val transparent = !doc.backgroundVisible
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val enc = withContext(Dispatchers.IO) { GifEncoder(out, w, h, loop = s.playback != PlaybackMode.ONCE) }
        try {
            val frameMs = 1000f / s.fps.coerceIn(1, 60)
            for (st in steps) {
                renderFrame(doc, s, frames, st.frame, bmp, opaqueBackground = false)
                withContext(Dispatchers.IO) { enc.addFrame(bmp, (frameMs * st.repeat).roundToInt(), transparent) }
            }
        } finally {
            withContext(Dispatchers.IO) { enc.close() }
            bmp.recycle()
        }
    }

    /** MP4 (H.264). Aufruf aus einer Coroutine auf dem Main-Thread. */
    suspend fun exportMp4(doc: ArtDocument, out: File, maxSide: Int = 1920) {
        val (steps, s) = steps(doc)
        require(steps.isNotEmpty()) { "Keine Bilder" }
        val frames = AnimationController.frames(doc)
        val (w, h) = outSize(doc, maxSide, 16)
        val fps = s.fps.coerceIn(1, 60)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (w.toLong() * h * fps / 6).coerceIn(1_000_000L, 40_000_000L).toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = MediaCodec.BufferInfo()
        var track = -1
        var muxing = false
        var outIndex = 0L
        val frameUs = 1_000_000L / fps

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
                            // Zeitstempel gleichmäßig aus der Bildnummer (Eingabe über Canvas hat Systemzeit)
                            info.presentationTimeUs = outIndex * frameUs
                            outIndex++
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val dst = Rect(0, 0, w, h)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        try {
            for (st in steps) {
                renderFrame(doc, s, frames, st.frame, bmp, opaqueBackground = true)
                repeat(st.repeat) {
                    withContext(Dispatchers.IO) {
                        val c = surface.lockHardwareCanvas()
                        try { c.drawColor(Color.WHITE); c.drawBitmap(bmp, null, dst, paint) } finally { surface.unlockCanvasAndPost(c) }
                        drain(false)
                    }
                }
            }
            withContext(Dispatchers.IO) { drain(true) }
        } finally {
            withContext(Dispatchers.IO) {
                try { codec.stop() } catch (_: Throwable) {}
                codec.release()
                surface.release()
                try { if (muxing) muxer.stop() } catch (_: Throwable) {}
                muxer.release()
            }
            bmp.recycle()
        }
    }

    /** Seiten (bzw. Bilder) als mehrseitiges PDF – je Bild eine Seite in Druckgröße. */
    suspend fun exportPagesPdf(doc: ArtDocument, out: File) {
        val s = AnimationController.settings(doc) ?: AnimationSettings(AssistMode.PAGES)
        val frames = AnimationController.frames(doc)
        val first = if (s.firstIsBackground && frames.size > 1) 1 else 0
        val pdf = android.graphics.pdf.PdfDocument()
        val ptW = (doc.width * 72f / doc.dpi).roundToInt().coerceAtLeast(1)
        val ptH = (doc.height * 72f / doc.dpi).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        try {
            for (i in first until frames.size) {
                renderFrame(doc, s, frames, i, bmp, opaqueBackground = true)
                val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(ptW, ptH, i + 1).create())
                page.canvas.drawBitmap(bmp, null, Rect(0, 0, ptW, ptH), Paint(Paint.FILTER_BITMAP_FLAG))
                pdf.finishPage(page)
            }
            withContext(Dispatchers.IO) { out.outputStream().use { pdf.writeTo(it) } }
        } finally {
            pdf.close(); bmp.recycle()
        }
    }
}
