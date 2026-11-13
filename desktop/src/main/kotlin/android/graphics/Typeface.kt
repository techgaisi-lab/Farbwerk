package android.graphics

import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontSlant
import org.jetbrains.skia.FontStyle
import java.io.File

/**
 * android.graphics.Typeface auf Basis von org.jetbrains.skia.Typeface. Fehlt einer Schrift der gewünschte
 * Schnitt, wird er wie bei Android synthetisch erzeugt (fett: Emboldening, kursiv: Schrägstellung).
 */
class Typeface private constructor(
    /** Skia-Schrift. */
    val skia: org.jetbrains.skia.Typeface,
    val style: Int,
    internal val fakeBold: Boolean,
    internal val fakeItalic: Boolean,
    /** Familienliste (bei Systemschriften) für die Suche nach echten Schnitten. */
    private val families: Array<String>?,
    /** Ursprungsschrift (Normal-Schnitt) für create(Typeface, style). */
    private val base: org.jetbrains.skia.Typeface,
) {
    val isBold: Boolean get() = (style and BOLD) != 0
    val isItalic: Boolean get() = (style and ITALIC) != 0
    val weight: Int get() = if (isBold) 700 else skia.fontStyle.weight

    override fun equals(other: Any?): Boolean =
        other is Typeface && other.style == style && other.fakeBold == fakeBold && other.fakeItalic == fakeItalic &&
            other.skia.uniqueId == skia.uniqueId
    override fun hashCode(): Int = skia.uniqueId * 31 + style

    class Builder {
        private val path: String?

        constructor(path: File) { this.path = path.absolutePath }
        constructor(path: String) { this.path = path }

        private var weight = -1
        private var italic: Boolean? = null

        fun setWeight(weight: Int): Builder { this.weight = weight; return this }
        fun setItalic(italic: Boolean): Builder { this.italic = italic; return this }
        fun setFallback(familyName: String?): Builder = this
        fun setFontVariationSettings(variationSettings: String?): Builder = this
        fun setTtcIndex(ttcIndex: Int): Builder = this

        fun build(): Typeface? {
            val p = path ?: return null
            val tf = loadFile(p) ?: return null
            val t = fromSkia(tf)
            val st = (if (weight >= 600) BOLD else 0) or (if (italic == true) ITALIC else 0)
            return if (st == NORMAL) t else create(t, st)
        }
    }

    companion object {
        const val NORMAL = 0
        const val BOLD = 1
        const val ITALIC = 2
        const val BOLD_ITALIC = 3

        private val SANS = arrayOf("Roboto", "Segoe UI", "Arial", "Helvetica", "DejaVu Sans", "Liberation Sans", "Noto Sans")
        private val SERIF_F = arrayOf("Noto Serif", "Times New Roman", "Georgia", "DejaVu Serif", "Liberation Serif", "serif")
        private val MONO = arrayOf("Droid Sans Mono", "Consolas", "Cascadia Mono", "Courier New", "DejaVu Sans Mono", "Liberation Mono", "monospace")
        private val CONDENSED = arrayOf("Roboto Condensed", "Arial Narrow", "Bahnschrift Condensed", "Bahnschrift", "DejaVu Sans Condensed", "Liberation Sans Narrow")
        private val CURSIVE = arrayOf("Dancing Script", "Segoe Script", "Segoe Print", "Brush Script MT", "Comic Sans MS", "URW Chancery L", "cursive")

        private fun familiesFor(name: String?): Array<String> = when (name?.lowercase()) {
            null, "", "sans-serif", "sans", "default", "roboto", "arial", "helvetica" -> SANS
            "serif", "times", "times new roman", "georgia", "noto-serif" -> SERIF_F
            "monospace", "mono", "courier", "sans-serif-monospace", "serif-monospace" -> MONO
            "sans-serif-condensed", "sans-serif-condensed-light", "sans-serif-condensed-medium", "condensed" -> CONDENSED
            "cursive", "casual" -> CURSIVE
            else -> arrayOf(name) + SANS
        }

        private fun skStyle(style: Int): FontStyle = when (style) {
            BOLD -> FontStyle.BOLD
            ITALIC -> FontStyle.ITALIC
            BOLD_ITALIC -> FontStyle.BOLD_ITALIC
            else -> FontStyle.NORMAL
        }

        private fun match(families: Array<String>, style: Int): org.jetbrains.skia.Typeface {
            val mgr = FontMgr.default
            val st = skStyle(style)
            for (f in families) {
                val tf = try { mgr.matchFamilyStyle(f, st) } catch (_: Throwable) { null }
                if (tf != null) return tf
            }
            return mgr.matchFamiliesStyle(arrayOf<String?>(*families), st)
                ?: mgr.legacyMakeTypeface("", st)
                ?: org.jetbrains.skia.Typeface.makeEmpty()
        }

        private fun system(families: Array<String>, style: Int): Typeface {
            val base = match(families, NORMAL)
            val tf = if (style == NORMAL) base else match(families, style)
            val fs = tf.fontStyle
            val fakeBold = (style and BOLD) != 0 && fs.weight < 600
            val fakeItalic = (style and ITALIC) != 0 && fs.slant == FontSlant.UPRIGHT
            return Typeface(tf, style, fakeBold, fakeItalic, families, base)
        }

        internal fun fromSkia(tf: org.jetbrains.skia.Typeface): Typeface = Typeface(tf, NORMAL, false, false, null, tf)

        private fun loadFile(path: String): org.jetbrains.skia.Typeface? = try {
            FontMgr.default.makeFromFile(path, 0)
                ?: FontMgr.default.makeFromData(org.jetbrains.skia.Data.makeFromBytes(File(path).readBytes()), 0)
        } catch (_: Throwable) { null }

        @JvmField val DEFAULT: Typeface = system(SANS, NORMAL)
        @JvmField val DEFAULT_BOLD: Typeface = system(SANS, BOLD)
        @JvmField val SANS_SERIF: Typeface = DEFAULT
        @JvmField val SERIF: Typeface = system(SERIF_F, NORMAL)
        @JvmField val MONOSPACE: Typeface = system(MONO, NORMAL)

        private val cache = HashMap<String, Typeface>()

        @JvmStatic fun create(familyName: String?, style: Int): Typeface = synchronized(cache) {
            cache.getOrPut("${familyName ?: ""}#$style") { system(familiesFor(familyName), style and BOLD_ITALIC) }
        }

        @JvmStatic fun create(family: Typeface?, style: Int): Typeface {
            val f = family ?: DEFAULT
            val st = style and BOLD_ITALIC
            if (f.style == st) return f
            val fams = f.families
            if (fams != null) return system(fams, st)
            // Eigene Schriftdatei: Schnitt synthetisch
            return Typeface(f.base, st, (st and BOLD) != 0 && f.base.fontStyle.weight < 600,
                (st and ITALIC) != 0 && f.base.fontStyle.slant == FontSlant.UPRIGHT, null, f.base)
        }

        @JvmStatic fun create(family: Typeface?, weight: Int, italic: Boolean): Typeface =
            create(family, (if (weight >= 600) BOLD else 0) or (if (italic) ITALIC else 0))

        @JvmStatic fun defaultFromStyle(style: Int): Typeface = create(DEFAULT, style)

        @JvmStatic fun createFromFile(file: File?): Typeface {
            val tf = file?.let { loadFile(it.absolutePath) } ?: throw RuntimeException("Font not found $file")
            return fromSkia(tf)
        }

        @JvmStatic fun createFromFile(path: String?): Typeface = createFromFile(path?.let { File(it) })

        /** Lädt eine Schrift aus einer Klassenpfad-Ressource (z. B. "font/inter_regular.ttf"). */
        @JvmStatic fun createFromResource(path: String): Typeface? {
            val bytes = Typeface::class.java.classLoader?.getResourceAsStream(path)?.use { it.readBytes() } ?: return null
            val tf = FontMgr.default.makeFromData(org.jetbrains.skia.Data.makeFromBytes(bytes), 0) ?: return null
            return fromSkia(tf)
        }
    }
}
