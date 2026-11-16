package at.techgaisi.farbwerk.io

import android.content.Context
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Optionale höhere Zeichenauflösung (detailScale).
 *
 * Standard und Bildimporte behalten die angeforderten Pixelmaße. Nur ausdrücklich gewählte hohe
 * Qualität rechnet intern feiner: Ziel ist die doppelte Bildschirm-Langseite, höchstens Faktor 4,
 * 8192 px je Seite und 25 Megapixel. Bestehende Dokumente werden hier nicht verändert.
 */
object CanvasQuality {
    const val MAX_SIDE = 8192
    const val MAX_PIXELS = 25_000_000L
    const val MAX_DETAIL = 4

    /** Zuletzt ermittelte lange Bildschirmseite (wird beim App-Start gesetzt). */
    @Volatile var cachedScreenLong: Int = 2560

    /** Lange Bildschirmseite in Pixeln (Bezug für „scharf genug beim Hineinzoomen“). */
    fun screenLongSide(context: Context): Int {
        val m = context.resources.displayMetrics
        return max(m.widthPixels, m.heightPixels).coerceAtLeast(1280).also { cachedScreenLong = it }
    }

    /**
     * Faktor für eine Leinwand mit den Nennmaßen [w]×[h]. Bei [highQuality] mindestens 2 (wenn möglich),
     * bei kleinen Leinwänden mehr. Ohne [highQuality] bleiben die angeforderten Pixelmaße erhalten.
     */
    fun detailFor(context: Context, w: Int, h: Int, highQuality: Boolean = false): Int {
        screenLongSide(context)
        return detailFor(w, h, highQuality)
    }

    /** Wie oben, mit der zuletzt ermittelten Bildschirmgröße. */
    fun detailFor(w: Int, h: Int, highQuality: Boolean = false): Int {
        if (w <= 0 || h <= 0 || !highQuality) return 1
        val longSide = max(w, h)
        val target = cachedScreenLong * (if (highQuality) 2 else 1)
        var d = max(if (highQuality) 2 else 1, ceil(target / longSide.toDouble()).toInt())
        d = min(d, MAX_DETAIL)
        // Grenzen: Seitenlänge und Pixelanzahl
        d = min(d, MAX_SIDE / longSide)
        d = min(d, sqrt(MAX_PIXELS / (w.toDouble() * h)).toInt())
        return d.coerceAtLeast(1)
    }
}
