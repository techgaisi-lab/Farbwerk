package at.techgaisi.farbwerk.brush

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Rendering-Effekte, die auf den fertigen Strich-Puffer wirken (vor dem Einmischen in die Ebene):
 * - Nasse Kanten: Inneres heller/transparenter, Ränder dichter (wie getrocknete Aquarellfarbe)
 * - Verbrannte Kanten: Ränder dunkler, Mischmodus wählbar
 * - Alpha-Schwelle: als Farbfilter beim Einmischen ([thresholdFilter]) – wirkt live und beim Festschreiben
 *
 * Kanten werden über die Differenz Alpha − weichgezeichnetes Alpha bestimmt (O(n), unabhängig vom Radius).
 */
object StrokeEffects {

    fun needsPostPass(b: Brush): Boolean = b[BP.WET_EDGES] > 0.001f || b[BP.BURNT_EDGES] > 0.001f

    /**
     * Wendet nasse/verbrannte Kanten auf [buffer] im Bereich [area] an. [diameter] bestimmt die Kantenbreite.
     * Läuft auf dem Malthread beim Absetzen (einmal pro Strich).
     */
    fun apply(buffer: Bitmap, area: RectF, b: Brush, diameter: Float) {
        val wet = b[BP.WET_EDGES]
        val burnt = b[BP.BURNT_EDGES]
        if (wet <= 0.001f && burnt <= 0.001f) return
        val r = Rect(floor(area.left).toInt(), floor(area.top).toInt(), ceil(area.right).toInt(), ceil(area.bottom).toInt())
        if (!r.intersect(0, 0, buffer.width, buffer.height) || r.isEmpty) return
        val w = r.width(); val h = r.height()
        if (w.toLong() * h > 40_000_000L) return // Schutz vor riesigen Bereichen
        val px = IntArray(w * h)
        buffer.getPixels(px, 0, w, r.left, r.top, w, h)
        val a = FloatArray(px.size)
        for (i in px.indices) a[i] = (px[i] ushr 24) / 255f
        val bl = a.copyOf()
        val rad = max(1, (diameter * 0.12f).toInt()).coerceAtMost(48)
        BrushImages.boxBlur(bl, w, h, rad, wrap = false)
        BrushImages.boxBlur(bl, w, h, rad, wrap = false)
        val mode = b.int(BP.BURNT_MODE)
        for (i in px.indices) {
            val al = a[i]
            if (al <= 0f) continue
            val edge = ((al - bl[i]) * 3f).coerceIn(0f, 1f)
            val c = px[i]
            // nicht vormultiplizierte Farbe
            var rr = Color.red(c) / al / 255f; var gg = Color.green(c) / al / 255f; var bb = Color.blue(c) / al / 255f
            var na = al
            if (wet > 0f) {
                na = (al * (1f - 0.55f * wet) + wet * edge * al * 1.2f).coerceIn(0f, al)
            }
            if (burnt > 0f && edge > 0f) {
                val k = (burnt * edge).coerceIn(0f, 1f)
                rr += (burn(rr, mode) - rr) * k; gg += (burn(gg, mode) - gg) * k; bb += (burn(bb, mode) - bb) * k
            }
            val ai = (na * 255f + 0.5f).toInt().coerceIn(0, 255)
            px[i] = Color.argb(
                ai,
                (rr.coerceIn(0f, 1f) * ai + 0.5f).toInt(),
                (gg.coerceIn(0f, 1f) * ai + 0.5f).toInt(),
                (bb.coerceIn(0f, 1f) * ai + 0.5f).toInt(),
            )
        }
        buffer.setPixels(px, 0, w, r.left, r.top, w, h)
    }

    private fun burn(c: Float, mode: Int): Float = when (mode) {
        BurnMode.COLOR_BURN -> if (c <= 0.001f) 0f else (1f - (1f - c) / c).coerceIn(0f, 1f)
        BurnMode.LINEAR_BURN -> (2f * c - 1f).coerceIn(0f, 1f)
        BurnMode.DARKEN -> min(c, 0.35f)
        else -> c * c
    }

    /**
     * Farbfilter für die Alpha-Schwelle: jedes Pixel wird ganz deckend oder ganz transparent
     * (steile lineare Rampe auf dem Alphakanal, die durch Begrenzung zur Stufe wird).
     */
    fun thresholdFilter(t: Float): ColorMatrixColorFilter {
        val k = 255f
        val cm = ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, k, -k * t.coerceIn(0.01f, 0.99f) * 255f + 0.5f,
        ))
        return ColorMatrixColorFilter(cm)
    }
}
