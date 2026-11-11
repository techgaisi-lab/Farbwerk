package at.techgaisi.farbwerk.transform

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import kotlin.math.floor

/**
 * Bikubische Neuabtastung (Catmull-Rom-Kern, a = -0.5) für affine und perspektivische
 * Transformationen. Wird beim Übernehmen einer Transformation mit Interpolation "Bikubisch" benutzt.
 */
object Bicubic {

    /**
     * @param inv Abbildung Dokument → Quellbitmap
     * @param r   Zielbereich im Dokument
     * @return Bitmap der Größe von [r]
     */
    fun resample(src: Bitmap, inv: Matrix, r: Rect): Bitmap {
        val sw = src.width
        val sh = src.height
        val raw = IntArray(sw * sh)
        src.getPixels(raw, 0, sw, 0, 0, sw, sh)
        // vormultiplizierte Kanäle
        val pa = IntArray(sw * sh); val pr = IntArray(sw * sh); val pg = IntArray(sw * sh); val pb = IntArray(sw * sh)
        for (i in raw.indices) {
            val c = raw[i]
            val a = c ushr 24
            pa[i] = a
            pr[i] = ((c shr 16) and 0xFF) * a / 255
            pg[i] = ((c shr 8) and 0xFF) * a / 255
            pb[i] = (c and 0xFF) * a / 255
        }
        val v = FloatArray(9)
        inv.getValues(v)
        val w = r.width()
        val h = r.height()
        val out = IntArray(w * h)
        val wx = FloatArray(4)
        val wy = FloatArray(4)
        for (y in 0 until h) {
            val dy = r.top + y + 0.5f
            for (x in 0 until w) {
                val dx = r.left + x + 0.5f
                val ww = v[6] * dx + v[7] * dy + v[8]
                if (ww == 0f) continue
                val sx = (v[0] * dx + v[1] * dy + v[2]) / ww - 0.5f
                val sy = (v[3] * dx + v[4] * dy + v[5]) / ww - 0.5f
                if (sx < -2f || sy < -2f || sx > sw + 1f || sy > sh + 1f) continue
                val ix = floor(sx).toInt()
                val iy = floor(sy).toInt()
                val fx = sx - ix
                val fy = sy - iy
                weights(fx, wx)
                weights(fy, wy)
                var a = 0f; var rr = 0f; var gg = 0f; var bb = 0f
                for (m in 0 until 4) {
                    val yy = iy - 1 + m
                    if (yy < 0 || yy >= sh) continue
                    val wym = wy[m]
                    val row = yy * sw
                    for (n in 0 until 4) {
                        val xx = ix - 1 + n
                        if (xx < 0 || xx >= sw) continue
                        val k = wx[n] * wym
                        val i = row + xx
                        a += pa[i] * k; rr += pr[i] * k; gg += pg[i] * k; bb += pb[i] * k
                    }
                }
                val ai = a.toInt().coerceIn(0, 255)
                if (ai == 0) continue
                val ri = rr.toInt().coerceIn(0, ai) * 255 / ai
                val gi = gg.toInt().coerceIn(0, ai) * 255 / ai
                val bi = bb.toInt().coerceIn(0, ai) * 255 / ai
                out[y * w + x] = (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun weights(f: Float, out: FloatArray) {
        out[0] = kernel(1f + f)
        out[1] = kernel(f)
        out[2] = kernel(1f - f)
        out[3] = kernel(2f - f)
    }

    private fun kernel(t0: Float): Float {
        val t = if (t0 < 0f) -t0 else t0
        return when {
            t <= 1f -> 1.5f * t * t * t - 2.5f * t * t + 1f
            t < 2f -> -0.5f * t * t * t + 2.5f * t * t - 4f * t + 2f
            else -> 0f
        }
    }
}
