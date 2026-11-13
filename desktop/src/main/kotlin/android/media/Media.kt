@file:Suppress("unused", "UNUSED_PARAMETER")

package android.media

import android.view.Surface
import java.nio.ByteBuffer

/**
 * MediaCodec-Nachbildung. Die Desktop-Version hat keinen H.264-Encoder (kein MediaCodec, kein ffmpeg).
 * [createEncoderByType] wirft daher eine verständliche Ausnahme, die die App als Fehlermeldung anzeigt
 * („Export fehlgeschlagen: …“). Animationen lassen sich als GIF/PNG/PDF exportieren.
 */
class MediaCodec private constructor() {
    class BufferInfo {
        @JvmField var offset: Int = 0
        @JvmField var size: Int = 0
        @JvmField var presentationTimeUs: Long = 0L
        @JvmField var flags: Int = 0
        fun set(offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
            this.offset = offset; this.size = size; this.presentationTimeUs = presentationTimeUs; this.flags = flags
        }
    }

    val outputFormat: MediaFormat get() = unsupported()
    fun configure(format: MediaFormat?, surface: Surface?, crypto: Any?, flags: Int): Unit = unsupported()
    fun createInputSurface(): Surface = unsupported()
    fun start(): Unit = unsupported()
    fun stop() {}
    fun release() {}
    fun signalEndOfInputStream(): Unit = unsupported()
    fun dequeueOutputBuffer(info: BufferInfo, timeoutUs: Long): Int = unsupported()
    fun getOutputBuffer(index: Int): ByteBuffer? = unsupported()
    fun releaseOutputBuffer(index: Int, render: Boolean) {}

    companion object {
        const val DESKTOP_MESSAGE =
            "MP4-Videoexport ist in der Windows-Version nicht verfügbar (kein Video-Encoder). " +
                "Bitte als animiertes GIF oder als PNG-Bilder exportieren."
        const val CONFIGURE_FLAG_ENCODE = 1
        const val INFO_TRY_AGAIN_LATER = -1
        const val INFO_OUTPUT_FORMAT_CHANGED = -2
        const val INFO_OUTPUT_BUFFERS_CHANGED = -3
        const val BUFFER_FLAG_KEY_FRAME = 1
        const val BUFFER_FLAG_CODEC_CONFIG = 2
        const val BUFFER_FLAG_END_OF_STREAM = 4

        private fun unsupported(): Nothing = throw UnsupportedOperationException(DESKTOP_MESSAGE)

        @JvmStatic fun createEncoderByType(type: String): MediaCodec = unsupported()
        @JvmStatic fun createDecoderByType(type: String): MediaCodec = unsupported()
    }
}

class MediaCodecInfo private constructor() {
    object CodecCapabilities {
        const val COLOR_FormatSurface = 0x7F000789
        const val COLOR_FormatYUV420Flexible = 0x7F420888
    }
    object CodecProfileLevel {
        const val AVCProfileHigh = 8
        const val AVCLevel4 = 0x800
    }
}

class MediaFormat private constructor(val mime: String) {
    private val values = HashMap<String, Any>()
    fun setInteger(key: String, value: Int) { values[key] = value }
    fun setLong(key: String, value: Long) { values[key] = value }
    fun setFloat(key: String, value: Float) { values[key] = value }
    fun setString(key: String, value: String) { values[key] = value }
    fun getInteger(key: String): Int = values[key] as? Int ?: 0
    fun getString(key: String): String? = values[key] as? String
    fun containsKey(key: String): Boolean = values.containsKey(key)

    companion object {
        const val MIMETYPE_VIDEO_AVC = "video/avc"
        const val MIMETYPE_VIDEO_HEVC = "video/hevc"
        const val KEY_COLOR_FORMAT = "color-format"
        const val KEY_BIT_RATE = "bitrate"
        const val KEY_FRAME_RATE = "frame-rate"
        const val KEY_I_FRAME_INTERVAL = "i-frame-interval"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_MIME = "mime"
        const val KEY_PROFILE = "profile"
        const val KEY_LEVEL = "level"

        @JvmStatic fun createVideoFormat(mime: String, width: Int, height: Int): MediaFormat =
            MediaFormat(mime).apply { setInteger(KEY_WIDTH, width); setInteger(KEY_HEIGHT, height) }
    }
}

class MediaMuxer(path: String, format: Int) {
    init { throw UnsupportedOperationException(MediaCodec.DESKTOP_MESSAGE) }

    fun addTrack(format: MediaFormat): Int = 0
    fun start() {}
    fun stop() {}
    fun release() {}
    fun setOrientationHint(degrees: Int) {}
    fun writeSampleData(trackIndex: Int, byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {}

    object OutputFormat {
        const val MUXER_OUTPUT_MPEG_4 = 0
        const val MUXER_OUTPUT_WEBM = 1
    }
}
