package at.techgaisi.farbwerk.render

import android.graphics.Bitmap
import at.techgaisi.farbwerk.model.LayerBlend

/**
 * Exakte Berechnung der Mischmodi, die android.graphics.BlendMode nicht kennt
 * (Linear nachbelichten, Schattieren, Dunklere/Hellere Farbe, Strahlendes/Lineares Licht, Lichtpunkt,
 * Harte Mischung, Subtrahieren, Dividieren).
 *
 * Zusammensetzung nach W3C „Compositing and Blending“ (wie die nativen Skia-Modi), alle Werte 0..1,
 * Farben nicht vormultipliziert:
 * ```
 *   αo = αs + αb·(1 − αs)
 *   co = αs·(1 − αb)·Cs + αs·αb·B(Cb, Cs) + (1 − αs)·αb·Cb      (vormultipliziert)
 * ```
 * αs enthält bereits Ebenendeckkraft, Gruppendeckkraft, Maske und Schnittmaske.
 *
 * Mischfunktionen B(Cb = unten, Cs = Ebene):
 *  - Linear nachbelichten: max(0, Cb + Cs − 1)
 *  - Subtrahieren:         max(0, Cb − Cs)
 *  - Dividieren:           Cs = 0 → (Cb = 0 ? 0 : 1), sonst min(1, Cb / Cs)
 *  - Strahlendes Licht:    Cs < ½ → Farbig nachbelichten mit 2·Cs, sonst Farbig abwedeln mit 2·(Cs − ½)
 *  - Lineares Licht:       clamp(Cb + 2·Cs − 1)
 *  - Lichtpunkt:           Cs < ½ → min(Cb, 2·Cs), sonst max(Cb, 2·Cs − 1)
 *  - Harte Mischung:       Cb + Cs ≥ 1 → 1, sonst 0 (je Kanal)
 *  - Dunklere/Hellere Farbe: die ganze Farbe (alle Kanäle) mit geringerer/höherer Luminanz
 *    (Gewichte 0,3 / 0,59 / 0,11 wie bei den Farb-Modi)
 *  - Schattieren: weiches Abdunkeln. Die Ebene wird gedanklich auf Weiß gelegt und dann per
 *    „Abdunkeln“ verrechnet – halbtransparente Bereiche dunkeln dadurch sanft ab, statt die Farbe
 *    teilweise zu ersetzen:  co = αs·(1 − αb)·Cs + αb·min(Cb, 1 − αs·(1 − Cs))
 *
 * Dieselben Formeln stehen als AGSL in [BlendShader]; beide Pfade liefern dasselbe Ergebnis.
 */
object BlendMath {

    /** Modi, die nicht nativ gezeichnet werden können. */
    fun isCustom(b: LayerBlend): Boolean = when (b) {
        LayerBlend.LINEAR_BURN, LayerBlend.SHADE, LayerBlend.DARKER_COLOR, LayerBlend.LIGHTER_COLOR,
        LayerBlend.VIVID_LIGHT, LayerBlend.LINEAR_LIGHT, LayerBlend.PIN_LIGHT, LayerBlend.HARD_MIX,
        LayerBlend.SUBTRACT, LayerBlend.DIVIDE -> true
        else -> false
    }

    /** Kennzahl des Modus für Shader und CPU-Schleife. */
    fun code(b: LayerBlend): Int = when (b) {
        LayerBlend.LINEAR_BURN -> LINEAR_BURN
        LayerBlend.SHADE -> SHADE
        LayerBlend.DARKER_COLOR -> DARKER_COLOR
        LayerBlend.LIGHTER_COLOR -> LIGHTER_COLOR
        LayerBlend.VIVID_LIGHT -> VIVID_LIGHT
        LayerBlend.LINEAR_LIGHT -> LINEAR_LIGHT
        LayerBlend.PIN_LIGHT -> PIN_LIGHT
        LayerBlend.HARD_MIX -> HARD_MIX
        LayerBlend.SUBTRACT -> SUBTRACT
        LayerBlend.DIVIDE -> DIVIDE
        else -> -1
    }

    const val LINEAR_BURN = 0
    const val SHADE = 1
    const val DARKER_COLOR = 2
    const val LIGHTER_COLOR = 3
    const val VIVID_LIGHT = 4
    const val LINEAR_LIGHT = 5
    const val PIN_LIGHT = 6
    const val HARD_MIX = 7
    const val SUBTRACT = 8
    const val DIVIDE = 9

    /** Zeilen je Durchgang (begrenzt den Speicher der Arbeitspuffer). */
    private const val STRIP_ROWS = 64

    /** Arbeitspuffer je Thread (werden nur vergrößert, nie pro Aufruf neu angelegt). */
    private class Buffers {
        var dst = IntArray(0)
        var src = IntArray(0)
        fun ensure(n: Int) {
            if (dst.size < n) { dst = IntArray(n); src = IntArray(n) }
        }
    }

    private val buffers = ThreadLocal.withInitial { Buffers() }

    /**
     * Mischt [src] (gleich groß wie [dst], vormultiplizierter Ebeneninhalt inkl. Maske/Schnittmaske)
     * mit dem Modus [mode] und der Deckkraft [opacity] in [dst] (Hintergrund + darunterliegende Ebenen).
     */
    fun blendInto(dst: Bitmap, src: Bitmap, mode: LayerBlend, opacity: Float) {
        val code = code(mode)
        if (code < 0 || opacity <= 0f) return
        val w = dst.width
        val h = dst.height
        val buf = buffers.get()!!
        buf.ensure(w * STRIP_ROWS)
        val d = buf.dst
        val s = buf.src
        var y = 0
        while (y < h) {
            val rows = minOf(STRIP_ROWS, h - y)
            // getPixels liefert nicht vormultiplizierte Farben – genau das, was die Formeln brauchen
            src.getPixels(s, 0, w, 0, y, w, rows)
            dst.getPixels(d, 0, w, 0, y, w, rows)
            var changed = false
            for (i in 0 until w * rows) {
                val sp = s[i]
                if (sp ushr 24 == 0) continue
                d[i] = blendPixel(d[i], sp, code, opacity)
                changed = true
            }
            if (changed) dst.setPixels(d, 0, w, 0, y, w, rows)
            y += rows
        }
    }

    /**
     * Mischt ein einzelnes Pixel. [b] = unten, [s] = Ebene, beide nicht vormultipliziertes ARGB.
     * Rückgabe: nicht vormultipliziertes ARGB.
     */
    fun blendPixel(b: Int, s: Int, code: Int, opacity: Float): Int {
        val aS = (s ushr 24) / 255f * opacity
        if (aS <= 0f) return b
        val aB = (b ushr 24) / 255f
        val sr = ((s shr 16) and 0xFF) / 255f
        val sg = ((s shr 8) and 0xFF) / 255f
        val sb = (s and 0xFF) / 255f
        val br = ((b shr 16) and 0xFF) / 255f
        val bg = ((b shr 8) and 0xFF) / 255f
        val bb = (b and 0xFF) / 255f

        val aO = aS + aB * (1f - aS)
        if (aO <= 0f) return 0
        var or: Float
        var og: Float
        var ob: Float
        if (code == SHADE) {
            // Ebene auf Weiß gelegt, dann Abdunkeln
            val k = 1f - aS
            or = aS * (1f - aB) * sr + aB * minOf(br, k + aS * sr)
            og = aS * (1f - aB) * sg + aB * minOf(bg, k + aS * sg)
            ob = aS * (1f - aB) * sb + aB * minOf(bb, k + aS * sb)
        } else {
            val mr: Float
            val mg: Float
            val mb: Float
            when (code) {
                DARKER_COLOR, LIGHTER_COLOR -> {
                    val ls = 0.3f * sr + 0.59f * sg + 0.11f * sb
                    val lb = 0.3f * br + 0.59f * bg + 0.11f * bb
                    val takeSrc = if (code == DARKER_COLOR) ls < lb else ls > lb
                    if (takeSrc) { mr = sr; mg = sg; mb = sb } else { mr = br; mg = bg; mb = bb }
                }
                else -> {
                    mr = channel(br, sr, code)
                    mg = channel(bg, sg, code)
                    mb = channel(bb, sb, code)
                }
            }
            val ws = aS * (1f - aB)
            val wm = aS * aB
            val wb = (1f - aS) * aB
            or = ws * sr + wm * mr + wb * br
            og = ws * sg + wm * mg + wb * bg
            ob = ws * sb + wm * mb + wb * bb
        }
        // zurück in nicht vormultiplizierte Bytes
        val inv = 1f / aO
        val a8 = (aO * 255f + 0.5f).toInt().coerceIn(0, 255)
        val r8 = (or * inv * 255f + 0.5f).toInt().coerceIn(0, 255)
        val g8 = (og * inv * 255f + 0.5f).toInt().coerceIn(0, 255)
        val b8 = (ob * inv * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (a8 shl 24) or (r8 shl 16) or (g8 shl 8) or b8
    }

    /** Separierbare Mischfunktion je Kanal. */
    fun channel(b: Float, s: Float, code: Int): Float = when (code) {
        LINEAR_BURN -> maxOf(0f, b + s - 1f)
        SUBTRACT -> maxOf(0f, b - s)
        DIVIDE -> if (s <= 0f) (if (b <= 0f) 0f else 1f) else minOf(1f, b / s)
        VIVID_LIGHT -> if (s < 0.5f) burn(b, 2f * s) else dodge(b, 2f * (s - 0.5f))
        LINEAR_LIGHT -> (b + 2f * s - 1f).coerceIn(0f, 1f)
        PIN_LIGHT -> if (s < 0.5f) minOf(b, 2f * s) else maxOf(b, 2f * s - 1f)
        // Schwelle auf halbe Byte-Stufe, damit 8-Bit-Werte mit b + s = 255 eindeutig „1“ ergeben
        HARD_MIX -> if (b + s >= HARD_MIX_THRESHOLD) 1f else 0f
        else -> s
    }

    const val HARD_MIX_THRESHOLD = 1f - 0.5f / 255f

    private fun burn(b: Float, s: Float): Float = when {
        b >= 1f -> 1f
        s <= 0f -> 0f
        else -> maxOf(0f, 1f - (1f - b) / s)
    }

    private fun dodge(b: Float, s: Float): Float = when {
        b <= 0f -> 0f
        s >= 1f -> 1f
        else -> minOf(1f, b / (1f - s))
    }
}
