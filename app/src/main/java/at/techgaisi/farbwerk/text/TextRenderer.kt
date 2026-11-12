package at.techgaisi.farbwerk.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.ceil
import kotlin.math.max

/**
 * Setzt [TextData] in Pixel. Horizontaler Text über [StaticLayout] (Umbruch an der Rahmenbreite,
 * Zeilenabstand, Blocksatz ab API 26), vertikaler Text als Spalten von rechts nach links mit einem Zeichen je Zeile.
 */
object TextRenderer {

    /** Gesetzter Text (Rahmenraum, Ursprung oben links im Rahmen). */
    class Laid(val layouts: List<StaticLayout>, val columnWidth: Float, val width: Float, val height: Float)

    fun paint(d: TextData): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = d.color
        alpha = (Color.alpha(d.color) * d.opacity.coerceIn(0f, 1f)).toInt()
        textSize = d.size.coerceAtLeast(1f)
        typeface = FontRegistry.styled(d.fontKey, d.bold, d.italic)
        letterSpacing = d.tracking / 1000f
        isUnderlineText = d.underline
        fontFeatureSettings = if (d.kerning) "kern" else "kern 0"
        if (d.outline) {
            style = Paint.Style.STROKE
            strokeWidth = (d.size * 0.035f).coerceAtLeast(1f)
            strokeJoin = Paint.Join.ROUND
        }
    }

    private fun content(d: TextData) = (if (d.uppercase) d.text.uppercase() else d.text).ifEmpty { " " }

    private fun alignment(a: TextAlign) = when (a) {
        TextAlign.LEFT, TextAlign.JUSTIFY -> Layout.Alignment.ALIGN_NORMAL
        TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
        TextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
    }

    private fun build(s: CharSequence, tp: TextPaint, width: Int, d: TextData, align: Layout.Alignment): StaticLayout {
        val b = StaticLayout.Builder.obtain(s, 0, s.length, tp, max(1, width))
            .setAlignment(align).setIncludePad(true)
            .setLineSpacing(0f, d.lineSpacing.coerceIn(0.3f, 4f))
        if (d.align == TextAlign.JUSTIFY && android.os.Build.VERSION.SDK_INT >= 26) {
            b.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        }
        return b.build()
    }

    fun layout(d: TextData): Laid {
        val tp = paint(d)
        val s = content(d)
        if (d.vertical) {
            // Jede Zeile wird eine Spalte; Zeichen untereinander, Spalten von rechts nach links
            val cols = s.split('\n').map { line -> line.toList().joinToString("\n").ifEmpty { " " } }
            val colW = ceil(tp.textSize * 1.25f)
            val ls = cols.map { build(it, tp, colW.toInt(), d, Layout.Alignment.ALIGN_CENTER) }
            val h = ls.maxOf { it.height }.toFloat()
            return Laid(ls, colW, colW * ls.size, h)
        }
        val natural = s.split('\n').maxOf { ceil(Layout.getDesiredWidth(it, tp)).toInt() } + 2
        val w = if (d.boxWidth > 0f) d.boxWidth.toInt() else natural
        val l = build(s, tp, w, d, alignment(d.align))
        return Laid(listOf(l), w.toFloat(), w.toFloat(), l.height.toFloat())
    }

    /** Rahmen im Rahmenraum. */
    fun frame(d: TextData, laid: Laid = layout(d), out: RectF = RectF()): RectF =
        out.apply { set(d.x, d.y, d.x + laid.width, d.y + laid.height) }

    /** Zeichnet den Text in [canvas] (Dokument-Koordinaten). */
    fun draw(canvas: Canvas, d: TextData, laid: Laid = layout(d)) {
        canvas.save()
        canvas.concat(d.matrix())
        canvas.translate(d.x, d.y - d.baseline)
        if (d.vertical) {
            laid.layouts.forEachIndexed { i, l ->
                canvas.save()
                canvas.translate(laid.width - laid.columnWidth * (i + 1), 0f)
                l.draw(canvas)
                canvas.restore()
            }
        } else laid.layouts.first().draw(canvas)
        canvas.restore()
    }

    /** Ersetzt den Inhalt von [target] (Dokumentgröße) durch den gesetzten Text. */
    fun render(d: TextData, target: Bitmap) {
        target.eraseColor(Color.TRANSPARENT)
        draw(Canvas(target), d)
        d.renderedGeneration = target.generationId
    }

    /** Rahmen-Eckpunkte in Dokument-Koordinaten (für Overlays): 8 Werte. */
    fun frameCorners(d: TextData, laid: Laid, out: FloatArray = FloatArray(8)): FloatArray {
        val r = frame(d, laid)
        out[0] = r.left; out[1] = r.top; out[2] = r.right; out[3] = r.top
        out[4] = r.right; out[5] = r.bottom; out[6] = r.left; out[7] = r.bottom
        d.matrix(Matrix()).mapPoints(out)
        return out
    }
}
