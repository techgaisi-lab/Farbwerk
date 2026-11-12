package at.techgaisi.farbwerk.text

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Eine wählbare Schrift. [key]: "sys:<familie>" oder "file:<dateiname>". */
data class FontEntry(val key: String, val label: String, val imported: Boolean)

/**
 * Verwaltung der Schriften: System-Schriftfamilien plus importierte TTF/OTF/TTC-Dateien
 * (gespeichert unter filesDir/fonts). Typefaces werden zwischengespeichert.
 */
object FontRegistry {
    const val DEFAULT_KEY = "sys:sans-serif"

    private val systemFamilies = listOf(
        "sans-serif" to "Sans",
        "sans-serif-light" to "Sans Leicht",
        "sans-serif-medium" to "Sans Mittel",
        "sans-serif-black" to "Sans Schwarz",
        "sans-serif-condensed" to "Sans Schmal",
        "serif" to "Serif",
        "monospace" to "Mono",
        "serif-monospace" to "Mono Serif",
        "casual" to "Locker",
        "cursive" to "Schreibschrift",
        "sans-serif-smallcaps" to "Kapitälchen",
    )

    private val cache = HashMap<String, Typeface>()
    private var dir: File? = null

    /** Erhöht sich nach jedem Import/Löschen (Compose-Listen beobachten das). */
    var version by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        if (dir == null) dir = File(context.filesDir, "fonts").apply { mkdirs() }
    }

    fun all(context: Context): List<FontEntry> {
        init(context)
        val sys = systemFamilies.map { (k, l) -> FontEntry("sys:$k", l, false) }
        val imp = dir?.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("ttf", "otf", "ttc") }
            ?.sortedBy { it.name.lowercase() }
            ?.map { FontEntry("file:${it.name}", it.nameWithoutExtension, true) } ?: emptyList()
        return sys + imp
    }

    fun label(key: String): String =
        systemFamilies.firstOrNull { "sys:${it.first}" == key }?.second ?: key.removePrefix("file:").substringBeforeLast('.')

    /** Basis-Typeface (ohne Stil). Fehlt eine importierte Datei, wird die Standardschrift verwendet. */
    fun typeface(key: String): Typeface = synchronized(cache) {
        cache.getOrPut(key) {
            when {
                key.startsWith("sys:") -> Typeface.create(key.removePrefix("sys:"), Typeface.NORMAL)
                key.startsWith("file:") -> {
                    val f = dir?.let { File(it, key.removePrefix("file:")) }
                    try { if (f != null && f.exists()) Typeface.createFromFile(f) else Typeface.SANS_SERIF }
                    catch (_: Throwable) { Typeface.SANS_SERIF }
                }
                else -> Typeface.SANS_SERIF
            }
        }
    }

    /** Typeface mit Stil (fett/kursiv; synthetisch, falls die Schrift keinen Schnitt hat). */
    fun styled(key: String, bold: Boolean, italic: Boolean): Typeface {
        val style = when { bold && italic -> Typeface.BOLD_ITALIC; bold -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL }
        return if (style == Typeface.NORMAL) typeface(key) else Typeface.create(typeface(key), style)
    }

    /** Importiert eine Schriftdatei. Liefert den neuen Schlüssel oder null. Hintergrund-Thread. */
    fun import(context: Context, uri: Uri): String? {
        init(context)
        var name = "Schrift.ttf"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        name = name.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("ttf", "otf", "ttc")) name += ".ttf"
        val target = File(dir, name)
        context.contentResolver.openInputStream(uri)?.use { ins -> target.outputStream().use { ins.copyTo(it) } } ?: return null
        // Prüfen, ob die Datei als Schrift lesbar ist
        val ok = try { Typeface.Builder(target).build() != null } catch (_: Throwable) { false }
        if (!ok) { target.delete(); return null }
        val key = "file:${target.name}"
        synchronized(cache) { cache.remove(key) }
        version++
        return key
    }

    fun delete(key: String) {
        if (!key.startsWith("file:")) return
        dir?.let { File(it, key.removePrefix("file:")).delete() }
        synchronized(cache) { cache.remove(key) }
        version++
    }
}
