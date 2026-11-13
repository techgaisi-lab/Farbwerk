@file:Suppress("unused", "UNUSED_PARAMETER", "MemberVisibilityCanBePrivate")

package android.text

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.ceil
import kotlin.math.max

/** Paint für Text (wie auf Android eine Unterklasse von [Paint]). */
open class TextPaint : Paint {
    constructor() : super()
    constructor(flags: Int) : super(flags)
    constructor(p: Paint) : super(p)

    @JvmField var baselineShift: Int = 0
    @JvmField var bgColor: Int = 0
    @JvmField var linkColor: Int = 0
    @JvmField var density: Float = 1f
}

/**
 * Textlayout (Umbruch, Ausrichtung, Zeilenabstand) – auf dem Desktop mit den öffentlichen
 * android.graphics-Funktionen (Paint.measureText, Paint.fontMetrics, Canvas.drawText) umgesetzt,
 * die ihrerseits Skia benutzen.
 */
abstract class Layout internal constructor(
    val text: CharSequence,
    val paint: TextPaint,
    val width: Int,
    val alignment: Alignment,
    val spacingMultiplier: Float,
    val spacingAdd: Float,
) {
    enum class Alignment { ALIGN_NORMAL, ALIGN_OPPOSITE, ALIGN_CENTER }

    abstract val height: Int
    abstract val lineCount: Int
    abstract fun getLineTop(line: Int): Int
    abstract fun getLineBottom(line: Int): Int
    abstract fun getLineBaseline(line: Int): Int
    abstract fun getLineStart(line: Int): Int
    abstract fun getLineEnd(line: Int): Int
    abstract fun getLineWidth(line: Int): Float
    abstract fun getLineLeft(line: Int): Float
    abstract fun getLineRight(line: Int): Float
    abstract fun draw(canvas: Canvas)

    fun getLineAscent(line: Int): Int = getLineTop(line) - getLineBaseline(line)
    fun getLineDescent(line: Int): Int = getLineBottom(line) - getLineBaseline(line)
    fun getLineMax(line: Int): Float = getLineWidth(line)
    fun getLineForVertical(vertical: Int): Int {
        for (i in 0 until lineCount) if (vertical < getLineBottom(i)) return i
        return lineCount - 1
    }

    companion object {
        const val JUSTIFICATION_MODE_NONE = 0
        const val JUSTIFICATION_MODE_INTER_WORD = 1
        const val BREAK_STRATEGY_SIMPLE = 0
        const val BREAK_STRATEGY_HIGH_QUALITY = 1
        const val BREAK_STRATEGY_BALANCED = 2

        /** Breite des Textes ohne Umbruch (längste Zeile). */
        @JvmStatic fun getDesiredWidth(source: CharSequence, paint: TextPaint): Float =
            source.split('\n').maxOf { paint.measureText(it) }

        @JvmStatic fun getDesiredWidth(source: CharSequence, start: Int, end: Int, paint: TextPaint): Float =
            getDesiredWidth(source.subSequence(start, end), paint)
    }
}

class StaticLayout private constructor(
    text: CharSequence, paint: TextPaint, width: Int, alignment: Alignment,
    spacingMult: Float, spacingAdd: Float, private val includePad: Boolean, private val justify: Boolean,
    private val maxLines: Int,
) : Layout(text, paint, width, alignment, spacingMult, spacingAdd) {

    @Deprecated("Builder verwenden")
    constructor(source: CharSequence, paint: TextPaint, width: Int, align: Alignment, spacingMult: Float,
                spacingAdd: Float, includePad: Boolean) :
        this(source, paint, width, align, spacingMult, spacingAdd, includePad, false, Int.MAX_VALUE)

    /** Eine gesetzte Zeile: Textbereich [start, end) (ohne Zeilenumbruch), Breite, Absatzende? */
    private class Line(val start: Int, val end: Int, val width: Float, val paragraphEnd: Boolean)

    private val lines = ArrayList<Line>()
    private val ascent: Float
    private val descent: Float
    private val topPad: Float
    private val bottomPad: Float
    private val lineAdvance: Float
    override val height: Int

    init {
        val fm = paint.fontMetrics
        ascent = fm.ascent
        descent = fm.descent
        topPad = if (includePad) max(0f, fm.ascent - fm.top) else 0f
        bottomPad = if (includePad) max(0f, fm.bottom - fm.descent) else 0f
        val base = descent - ascent
        lineAdvance = base * spacingMultiplier + spacingAdd
        breakLines()
        val n = lines.size
        height = ceil(topPad + base + (n - 1) * lineAdvance + bottomPad).toInt().coerceAtLeast(1)
    }

    private fun breakLines() {
        val s = text.toString()
        val maxW = width.toFloat().coerceAtLeast(1f)
        var paraStart = 0
        while (paraStart <= s.length && lines.size < maxLines) {
            val nl = s.indexOf('\n', paraStart).let { if (it < 0) s.length else it }
            breakParagraph(s, paraStart, nl, maxW)
            if (nl >= s.length) break
            paraStart = nl + 1
        }
        if (lines.isEmpty()) lines.add(Line(0, 0, 0f, true))
    }

    /** Greedy-Umbruch an Leerzeichen; zu lange Wörter werden zeichenweise umbrochen. */
    private fun breakParagraph(s: String, start: Int, end: Int, maxW: Float) {
        if (start == end) { lines.add(Line(start, end, 0f, true)); return }
        var lineStart = start
        while (lineStart < end && lines.size < maxLines) {
            var pos = lineStart
            var lastFit = -1
            var lastFitWidth = 0f
            while (pos < end) {
                var wordEnd = pos
                while (wordEnd < end && s[wordEnd] == ' ') wordEnd++
                while (wordEnd < end && s[wordEnd] != ' ') wordEnd++
                val w = paint.measureText(s.substring(lineStart, wordEnd))
                if (w <= maxW || lastFit < 0) {
                    if (w > maxW) {
                        // erstes Wort passt nicht: zeichenweise kürzen
                        var cut = wordEnd
                        while (cut > lineStart + 1 && paint.measureText(s.substring(lineStart, cut)) > maxW) cut--
                        lastFit = cut; lastFitWidth = paint.measureText(s.substring(lineStart, cut))
                        break
                    }
                    lastFit = wordEnd; lastFitWidth = w
                    pos = wordEnd
                } else break
            }
            val lineEnd = lastFit.coerceAtLeast(lineStart + 1).coerceAtMost(end)
            // Leerzeichen am Zeilenende zählen nicht zur Breite
            var visEnd = lineEnd
            while (visEnd > lineStart && s[visEnd - 1] == ' ') visEnd--
            val width = if (visEnd == lineEnd) lastFitWidth else paint.measureText(s.substring(lineStart, visEnd))
            var next = lineEnd
            while (next < end && s[next] == ' ') next++
            lines.add(Line(lineStart, visEnd, width, next >= end))
            lineStart = next
        }
    }

    override val lineCount: Int get() = lines.size
    private fun baselineOf(i: Int): Float = topPad - ascent + i * lineAdvance
    override fun getLineTop(line: Int): Int = (baselineOf(line) + ascent - if (line == 0) topPad else 0f).toInt()
    override fun getLineBottom(line: Int): Int =
        if (line == lines.size - 1) height else (baselineOf(line) + ascent + lineAdvance).toInt()
    override fun getLineBaseline(line: Int): Int = baselineOf(line).toInt()
    override fun getLineStart(line: Int): Int = lines[line].start
    override fun getLineEnd(line: Int): Int = lines[line].end
    override fun getLineWidth(line: Int): Float = lines[line].width
    override fun getLineLeft(line: Int): Float = when (alignment) {
        Alignment.ALIGN_NORMAL -> 0f
        Alignment.ALIGN_OPPOSITE -> width - lines[line].width
        Alignment.ALIGN_CENTER -> (width - lines[line].width) / 2f
    }
    override fun getLineRight(line: Int): Float = getLineLeft(line) + lines[line].width

    override fun draw(canvas: Canvas) {
        val s = text.toString()
        val saved = paint.textAlign
        paint.textAlign = Paint.Align.LEFT
        try {
            for (i in lines.indices) {
                val l = lines[i]
                if (l.end <= l.start) continue
                val y = baselineOf(i)
                if (justify && !l.paragraphEnd && width > l.width) drawJustified(canvas, s, l, y)
                else canvas.drawText(s.substring(l.start, l.end), getLineLeft(i), y, paint)
            }
        } finally {
            paint.textAlign = saved
        }
    }

    /** Blocksatz: zusätzlicher Raum gleichmäßig auf die Wortzwischenräume verteilt. */
    private fun drawJustified(canvas: Canvas, s: String, l: Line, y: Float) {
        val words = ArrayList<IntArray>()
        var p = l.start
        while (p < l.end) {
            while (p < l.end && s[p] == ' ') p++
            val ws = p
            while (p < l.end && s[p] != ' ') p++
            if (p > ws) words.add(intArrayOf(ws, p))
        }
        if (words.size < 2) { canvas.drawText(s.substring(l.start, l.end), 0f, y, paint); return }
        val wordsWidth = words.sumOf { paint.measureText(s.substring(it[0], it[1])).toDouble() }.toFloat()
        val gap = (width - wordsWidth) / (words.size - 1)
        var x = 0f
        for (w in words) {
            canvas.drawText(s.substring(w[0], w[1]), x, y, paint)
            x += paint.measureText(s.substring(w[0], w[1])) + gap
        }
    }

    class Builder private constructor(
        private val source: CharSequence, private val start: Int, private val end: Int,
        private val paint: TextPaint, private val width: Int,
    ) {
        private var alignment = Alignment.ALIGN_NORMAL
        private var spacingMult = 1f
        private var spacingAdd = 0f
        private var includePad = true
        private var justification = JUSTIFICATION_MODE_NONE
        private var maxLines = Int.MAX_VALUE

        fun setAlignment(a: Alignment) = apply { alignment = a }
        fun setIncludePad(b: Boolean) = apply { includePad = b }
        fun setLineSpacing(add: Float, mult: Float) = apply { spacingAdd = add; spacingMult = mult }
        fun setJustificationMode(mode: Int) = apply { justification = mode }
        fun setMaxLines(n: Int) = apply { maxLines = n.coerceAtLeast(1) }
        fun setBreakStrategy(s: Int) = this
        fun setHyphenationFrequency(f: Int) = this
        fun setEllipsize(e: Any?) = this
        fun setTextDirection(d: Any?) = this
        fun setUseLineSpacingFromFallbacks(b: Boolean) = this

        fun build(): StaticLayout = StaticLayout(source.subSequence(start, end), paint, width, alignment, spacingMult,
            spacingAdd, includePad, justification == JUSTIFICATION_MODE_INTER_WORD, maxLines)

        companion object {
            @JvmStatic fun obtain(source: CharSequence, start: Int, end: Int, paint: TextPaint, width: Int): Builder =
                Builder(source, start, end, paint, width)
        }
    }
}
