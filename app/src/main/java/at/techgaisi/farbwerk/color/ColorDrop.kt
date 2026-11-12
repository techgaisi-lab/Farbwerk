package at.techgaisi.farbwerk.color

import android.graphics.Rect
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.Layer
import at.techgaisi.farbwerk.model.Tool
import at.techgaisi.farbwerk.render.Compositor
import at.techgaisi.farbwerk.undo.PixelEdit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * ColorDrop: füllt einen zusammenhängenden, farbähnlichen Bereich der aktiven Ebene mit einer Farbe.
 *
 * Grenzquelle:
 *  - Gibt es eine sichtbare Ebene mit `isReference = true`, bestimmen deren Pixel die Grenzen
 *    (Linienzeichnung auf eigener Ebene, Farbe auf einer anderen).
 *  - Sonst die aktive Ebene selbst.
 *
 * Auswahl: Ist eine Auswahl aktiv, wirkt sie als zusätzliche Grenze – gefüllt wird nur innerhalb der
 * Auswahl (weiche Auswahlkanten werden anteilig gefüllt). Auf einer leeren Ebene füllt ein Tropfen in
 * die Auswahl daher die gesamte Auswahl. Ein Tropfen außerhalb der Auswahl füllt nichts.
 * Alpha-Sperre: Nur vorhandene Pixel werden eingefärbt (deren Deckkraft bleibt erhalten).
 *
 * Schwellenwert 0..1: maximal erlaubter Farbabstand zur Startfarbe (0 = nur exakt gleiche Pixel).
 * Kantenglättung: Die an den gefüllten Bereich angrenzenden Pixel erhalten eine Teilabdeckung
 * proportional zu ihrer Ähnlichkeit, wodurch der Füllbereich weich unter geglättete Linien läuft.
 *
 * Zwei Nutzungsarten:
 *  - [fill] – einmalig füllen (Ergebnis sofort im Verlauf).
 *  - [begin] → beliebig oft [updateThreshold] → [commit] / [cancel] – interaktive Sitzung, in der der
 *    Schwellenwert live (z. B. per horizontaler Fingerbewegung) angepasst wird.
 */
object ColorDrop {

    /** Zuletzt benutzter Schwellenwert (wird bei interaktiver Anpassung gemerkt). */
    var lastThreshold: Float = 0.3f

    // ------------------------------------------------------------------ Sitzung

    private var session: Session? = null

    private class Session(
        val state: EditorState,
        val layer: Layer,
        val edit: PixelEdit,
        val w: Int,
        val h: Int,
        val seedX: Int,
        val seedY: Int,
        /** Grenzquelle (unpremultiplied ARGB, Dokumentgröße). */
        val source: IntArray,
        val color: Int,
        var threshold: Float,
        /** Auswahlmaske (0..255 je Pixel, Dokumentgröße) oder null ohne Auswahl. */
        val sel: ByteArray?,
        /** Bereich, der bisher in der Ebene verändert wurde (für Wiederherstellen und Commit). */
        val touched: Rect = Rect(),
    )

    /** Läuft gerade eine interaktive Sitzung? */
    val isActive: Boolean get() = session != null

    /** Aktueller Schwellenwert der laufenden Sitzung (oder [lastThreshold]). */
    val currentThreshold: Float get() = session?.threshold ?: lastThreshold

    /**
     * Einmaliges Füllen an Dokumentposition ([x], [y]) mit der aktuellen Farbe.
     * @return true, wenn gefüllt wurde.
     */
    fun fill(state: EditorState, x: Float, y: Float, threshold: Float): Boolean {
        if (!begin(state, x, y, threshold)) return false
        return commit()
    }

    /**
     * Füllen an einer Fenster-Position (z. B. Ende eines Drag aus dem Farben-Panel).
     * Nutzt [EditorState.windowToDoc] für die Umrechnung.
     */
    fun fillAtWindowPoint(state: EditorState, windowX: Float, windowY: Float, threshold: Float = lastThreshold): Boolean {
        val p = state.windowToDoc(windowX, windowY) ?: return false
        return fill(state, p.x, p.y, threshold)
    }

    /**
     * Kompatibilitätsname aus der Aufgabenbeschreibung. Die Koordinaten werden als Fensterkoordinaten
     * interpretiert (die Leinwand füllt das Fenster, daher identisch mit View-Koordinaten).
     */
    fun fillAtViewPoint(state: EditorState, viewX: Float, viewY: Float): Boolean =
        fillAtWindowPoint(state, viewX, viewY, lastThreshold)

    /** Beginnt eine interaktive Sitzung an einer Fensterposition. */
    fun beginAtWindowPoint(state: EditorState, windowX: Float, windowY: Float, threshold: Float = lastThreshold): Boolean {
        val p = state.windowToDoc(windowX, windowY) ?: return false
        return begin(state, p.x, p.y, threshold)
    }

    /**
     * Beginnt eine interaktive Füllsitzung und zeigt die erste Füllung als Vorschau direkt in der Ebene.
     * @return false, wenn nicht gefüllt werden kann (keine Ebene, gesperrt, außerhalb).
     */
    fun begin(state: EditorState, x: Float, y: Float, threshold: Float, color: Int = state.color): Boolean {
        cancel()
        val doc = state.document ?: return false
        val layer = doc.activeLayer ?: return false
        val sx = x.toInt(); val sy = y.toInt()
        if (x < 0f || y < 0f || sx >= doc.width || sy >= doc.height) return false
        // Transformieren/Anpassen halten eigene Kopien der Ebene – Füllen würde beim Abschluss überschrieben.
        if (state.tool == Tool.TRANSFORM || state.tool == Tool.ADJUST) {
            state.flash("Zuerst Transformieren/Anpassen abschließen"); return false
        }
        if (layer.locked) { state.flash("Ebene gesperrt"); return false }
        if (!layer.visible) { state.flash("Ebene ist ausgeblendet"); return false }
        if (!layer.bitmap.isMutable) return false

        val w = layer.width; val h = layer.height
        val sel = doc.selection?.let { readSelection(it.mask, w, h) }
        if (sel != null && sel[sy * w + sx] == 0.toByte()) {
            state.flash("Außerhalb der Auswahl"); return false
        }
        val source = IntArray(w * h)
        val ref = doc.layers.firstOrNull { it.isReference && it.visible && it !== layer }
        when {
            ref != null -> ref.bitmap.getPixels(source, 0, w, 0, 0, w, h)
            else -> layer.bitmap.getPixels(source, 0, w, 0, 0, w, h)
        }
        // Völlig leere aktive Ebene ohne Referenz: am zusammengesetzten Bild orientieren,
        // sonst würde immer die gesamte Ebene gefüllt.
        if (ref == null && isFullyTransparent(source)) {
            val flat = Compositor.flatten(doc, includeBackground = false)
            flat.getPixels(source, 0, w, 0, 0, w, h)
            flat.recycle()
        }

        val edit = state.beginPixelEdit(layer)
        val s = Session(state, layer, edit, w, h, sx, sy, source, ColorMath.opaque(color), threshold.coerceIn(0f, 1f), sel)
        session = s
        run(s)
        return true
    }

    /** Schwellenwert der laufenden Sitzung ändern (füllt neu, wenn sich der Wert merklich ändert). */
    fun updateThreshold(threshold: Float) {
        val s = session ?: return
        val t = threshold.coerceIn(0f, 1f)
        if (abs(t - s.threshold) < 0.004f) return
        s.threshold = t
        run(s)
    }

    /** Sitzung abschließen: Undo-Eintrag anlegen. @return true, wenn etwas gefüllt wurde. */
    fun commit(): Boolean {
        val s = session ?: return false
        session = null
        lastThreshold = s.threshold
        if (s.touched.isEmpty) { s.edit.cancel(); releaseBuffers(); return false }
        s.edit.commit(Rect(s.touched), "Farbe füllen")
        s.state.invalidate()
        releaseBuffers()
        return true
    }

    /** Sitzung verwerfen und Ebene wiederherstellen. */
    fun cancel() {
        val s = session ?: return
        session = null
        s.edit.cancel()
        s.state.invalidate()
        releaseBuffers()
    }

    // ------------------------------------------------------------------ Algorithmus

    // Wiederverwendete Puffer (während einer Sitzung für jede Neuberechnung genutzt)
    private var coverage = ByteArray(0)
    private var stack = IntArray(0)
    private var region = IntArray(0)

    private fun releaseBuffers() {
        coverage = ByteArray(0); stack = IntArray(0); region = IntArray(0)
    }

    private fun run(s: Session) {
        // 1) vorherige Vorschau zurücknehmen
        if (!s.touched.isEmpty) restore(s, s.touched)

        // 2) Füllmaske berechnen
        val bounds = floodFill(s)
        if (bounds.isEmpty) { s.state.invalidate(); return }

        // 3) Ebene in der Bounding-Box einfärben
        paint(s, bounds)
        s.touched.union(bounds)
        s.state.invalidate()
    }

    private fun restore(s: Session, r: Rect) {
        val n = r.width() * r.height()
        if (region.size < n) region = IntArray(n)
        s.edit.original.getPixels(region, 0, r.width(), r.left, r.top, r.width(), r.height())
        s.layer.bitmap.setPixels(region, 0, r.width(), r.left, r.top, r.width(), r.height())
    }

    /** Premultiplizierter Abstand (0..1) zweier unpremultiplizierter ARGB-Farben. */
    private fun distance(a: Int, sa: Int, sr: Int, sg: Int, sb: Int): Float {
        val aa = (a ushr 24) and 0xFF
        val ar = ((a ushr 16) and 0xFF) * aa / 255
        val ag = ((a ushr 8) and 0xFF) * aa / 255
        val ab = (a and 0xFF) * aa / 255
        val d = max(max(abs(aa - sa), abs(ar - sr)), max(abs(ag - sg), abs(ab - sb)))
        return d / 255f
    }

    /**
     * Scanline-Flood-Fill auf [Session.source]. Schreibt Abdeckungswerte (0..255) in [coverage]
     * und liefert die Bounding-Box (inkl. geglätteter Randpixel).
     */
    private fun floodFill(s: Session): Rect {
        val w = s.w; val h = s.h; val n = w * h
        if (coverage.size != n) coverage = ByteArray(n) else java.util.Arrays.fill(coverage, 0)
        val src = s.source
        val seed = src[s.seedY * w + s.seedX]
        val sa = (seed ushr 24) and 0xFF
        val sr = ((seed ushr 16) and 0xFF) * sa / 255
        val sg = ((seed ushr 8) and 0xFF) * sa / 255
        val sb = (seed and 0xFF) * sa / 255
        val t = s.threshold
        val sel = s.sel
        val zero = 0.toByte()

        var minX = s.seedX; var maxX = s.seedX; var minY = s.seedY; var maxY = s.seedY
        if (stack.size < 1024) stack = IntArray(1024)
        var sp = 0
        stack[sp++] = s.seedY * w + s.seedX
        val full = 255.toByte()

        while (sp > 0) {
            val idx = stack[--sp]
            if (coverage[idx] == full) continue
            val y = idx / w
            val rowStart = y * w
            var x = idx - rowStart
            // nach links laufen
            while (x > 0 && coverage[rowStart + x - 1] != full &&
                (sel == null || sel[rowStart + x - 1] != zero) &&
                distance(src[rowStart + x - 1], sa, sr, sg, sb) <= t) x--
            var spanAbove = false
            var spanBelow = false
            while (x < w) {
                val i = rowStart + x
                if (coverage[i] == full || (sel != null && sel[i] == zero) ||
                    distance(src[i], sa, sr, sg, sb) > t) break
                coverage[i] = full
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y > 0) {
                    val up = i - w
                    val ok = coverage[up] != full && (sel == null || sel[up] != zero) &&
                        distance(src[up], sa, sr, sg, sb) <= t
                    if (ok && !spanAbove) {
                        if (sp >= stack.size) stack = stack.copyOf(stack.size * 2)
                        stack[sp++] = up; spanAbove = true
                    } else if (!ok) spanAbove = false
                }
                if (y < h - 1) {
                    val dn = i + w
                    val ok = coverage[dn] != full && (sel == null || sel[dn] != zero) &&
                        distance(src[dn], sa, sr, sg, sb) <= t
                    if (ok && !spanBelow) {
                        if (sp >= stack.size) stack = stack.copyOf(stack.size * 2)
                        stack[sp++] = dn; spanBelow = true
                    } else if (!ok) spanBelow = false
                }
                x++
            }
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }

        // Kantenglättung: angrenzende Pixel erhalten Teilabdeckung nach Ähnlichkeit.
        val x0 = max(0, minX - 1); val x1 = minOf(w - 1, maxX + 1)
        val y0 = max(0, minY - 1); val y1 = minOf(h - 1, maxY + 1)
        val soft = max(1f - t, 1e-3f)
        for (y in y0..y1) {
            val row = y * w
            for (x in x0..x1) {
                val i = row + x
                if (coverage[i] != 0.toByte()) continue
                if (sel != null && sel[i] == zero) continue
                val adj = (x > 0 && coverage[i - 1] == full) || (x < w - 1 && coverage[i + 1] == full) ||
                    (y > 0 && coverage[i - w] == full) || (y < h - 1 && coverage[i + w] == full)
                if (!adj) continue
                val d = distance(src[i], sa, sr, sg, sb)
                val c = ((1f - (d - t) / soft).coerceIn(0f, 1f) * 254f).roundToInt()
                // 254 statt 255, damit Randpixel nicht als "voll" gelten
                if (c > 0) coverage[i] = c.toByte()
            }
        }
        return Rect(x0, y0, x1 + 1, y1 + 1)
    }

    /** Färbt die Ebene im Bereich [r] gemäß [coverage] ein (Mischung in premultiplied-Raum). */
    private fun paint(s: Session, r: Rect) {
        val rw = r.width(); val rh = r.height(); val n = rw * rh
        if (region.size < n) region = IntArray(n)
        val px = region
        s.layer.bitmap.getPixels(px, 0, rw, r.left, r.top, rw, rh)
        val fr = (s.color ushr 16) and 0xFF
        val fg = (s.color ushr 8) and 0xFF
        val fb = s.color and 0xFF
        val alphaLock = s.layer.alphaLock
        val w = s.w
        val sel = s.sel
        for (yy in 0 until rh) {
            val covRow = (r.top + yy) * w + r.left
            val outRow = yy * rw
            for (xx in 0 until rw) {
                var cov = coverage[covRow + xx].toInt() and 0xFF
                if (cov == 0) continue
                if (sel != null) {
                    // weiche Auswahlkanten: Abdeckung anteilig verringern
                    val sa = sel[covRow + xx].toInt() and 0xFF
                    if (sa < 255) cov = if (cov == 255) minOf(sa, 254) else cov * sa / 255
                    if (cov == 0) continue
                }
                val o = px[outRow + xx]
                val oa = (o ushr 24) and 0xFF
                if (alphaLock) {
                    if (oa == 0) continue
                    // Alpha bleibt erhalten, nur die Farbe wird gemischt
                    val k = if (cov >= 254) 255 else cov
                    val nr = (((o ushr 16) and 0xFF) * (255 - k) + fr * k) / 255
                    val ng = (((o ushr 8) and 0xFF) * (255 - k) + fg * k) / 255
                    val nb = ((o and 0xFF) * (255 - k) + fb * k) / 255
                    px[outRow + xx] = (oa shl 24) or (nr shl 16) or (ng shl 8) or nb
                } else if (cov == 255) {
                    px[outRow + xx] = s.color
                } else {
                    // premultiplied: res = orig*(1-k) + fill*k
                    val k = cov / 255f
                    val pa = oa * (1f - k) + 255f * k
                    if (pa <= 0f) continue
                    val pr = ((o ushr 16) and 0xFF) * oa / 255f * (1f - k) + fr * k
                    val pg = ((o ushr 8) and 0xFF) * oa / 255f * (1f - k) + fg * k
                    val pb = (o and 0xFF) * oa / 255f * (1f - k) + fb * k
                    val inv = 255f / pa
                    px[outRow + xx] = (pa.roundToInt().coerceIn(0, 255) shl 24) or
                        ((pr * inv).roundToInt().coerceIn(0, 255) shl 16) or
                        ((pg * inv).roundToInt().coerceIn(0, 255) shl 8) or
                        (pb * inv).roundToInt().coerceIn(0, 255)
                }
            }
        }
        s.layer.bitmap.setPixels(px, 0, rw, r.left, r.top, rw, rh)
    }

    /**
     * Liest eine ALPHA_8-Auswahlmaske (Dokumentgröße) in ein kompaktes Byte-Array (ohne Zeilenpolster).
     * null, wenn die Maske nicht zur Ebene passt (dann wird ohne Auswahl gefüllt).
     */
    private fun readSelection(mask: android.graphics.Bitmap, w: Int, h: Int): ByteArray? {
        if (mask.width != w || mask.height != h) return null
        if (mask.config != android.graphics.Bitmap.Config.ALPHA_8) {
            // Fallback für andere Formate: Alpha je Pixel
            val px = IntArray(w * h)
            mask.getPixels(px, 0, w, 0, 0, w, h)
            return ByteArray(w * h) { (px[it] ushr 24).toByte() }
        }
        val stride = mask.rowBytes
        val buf = java.nio.ByteBuffer.allocate(stride * h)
        mask.copyPixelsToBuffer(buf)
        val raw = buf.array()
        if (stride == w) return raw
        val out = ByteArray(w * h)
        for (y in 0 until h) System.arraycopy(raw, y * stride, out, y * w, w)
        return out
    }

    private fun isFullyTransparent(px: IntArray): Boolean {
        for (c in px) if ((c ushr 24) != 0) return false
        return true
    }
}
