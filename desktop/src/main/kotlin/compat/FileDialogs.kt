package compat

import java.awt.FileDialog
import java.io.File
import javax.swing.JFileChooser

/**
 * Native Dateidialoge (java.awt.FileDialog → Windows-Standarddialog). Filter werden aus MIME-Typen abgeleitet.
 * Der zuletzt benutzte Ordner wird gemerkt.
 */
object FileDialogs {
    private var lastDir: File? = null

    private val defaultDir: File
        get() = lastDir?.takeIf { it.isDirectory }
            ?: File(System.getProperty("user.home") ?: ".", "Pictures").takeIf { it.isDirectory }
            ?: File(System.getProperty("user.home") ?: ".")

    /** Dateiendungen zu MIME-Typen; leer = alle Dateien. */
    fun extensionsFor(mimes: List<String>): Set<String> {
        val out = LinkedHashSet<String>()
        for (m in mimes) {
            when {
                m == "*/*" || m.isBlank() -> return emptySet()
                m == "image/*" -> out += listOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "heic", "heif", "psd")
                m == "video/*" -> out += listOf("mp4", "webm", "mkv", "mov")
                m.startsWith("font/") || m.contains("font") -> out += listOf("ttf", "otf", "ttc")
                m == "image/png" -> out += "png"
                m == "image/jpeg" -> out += listOf("jpg", "jpeg")
                m == "image/webp" -> out += "webp"
                m.contains("photoshop") -> out += "psd"
                m == "application/zip" -> out += listOf("zip", "fwproj", "farbwerk")
                m == "application/json" || m == "text/plain" -> out += listOf("json", "txt")
                else -> return emptySet() // unbekannte Typen (z. B. eigene Projektformate): nichts ausfiltern
            }
        }
        return out
    }

    private fun dialog(title: String, mode: Int): FileDialog {
        val owner = DesktopPlatform.window
        val d = if (owner != null) FileDialog(owner, title, mode) else FileDialog(null as java.awt.Frame?, title, mode)
        d.directory = defaultDir.absolutePath
        return d
    }

    fun open(title: String, mimes: List<String>): File? = openMultiple(title, mimes, multiple = false).firstOrNull()

    fun openMultiple(title: String, mimes: List<String>, multiple: Boolean = true): List<File> {
        val exts = extensionsFor(mimes)
        val d = dialog(title, FileDialog.LOAD)
        d.isMultipleMode = multiple
        if (exts.isNotEmpty()) {
            d.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in exts }
            // Windows wertet FilenameFilter nicht aus, wohl aber ein Muster im Dateinamen
            if (DesktopPlatform.isWindows) d.file = exts.joinToString(";") { "*.$it" }
        }
        d.isVisible = true
        val files = d.files?.toList().orEmpty().filter { it.isFile }
        files.firstOrNull()?.parentFile?.let { lastDir = it }
        return files
    }

    fun save(title: String, suggestedName: String): File? {
        val d = dialog(title, FileDialog.SAVE)
        d.file = suggestedName
        d.isVisible = true
        val name = d.file ?: return null
        val dir = d.directory ?: return null
        var f = File(dir, name)
        // Endung ergänzen, falls der Nutzer sie entfernt hat
        val ext = suggestedName.substringAfterLast('.', "")
        if (ext.isNotEmpty() && f.extension.isEmpty()) f = File(dir, "$name.$ext")
        lastDir = f.parentFile
        return f
    }

    fun chooseDirectory(title: String): File? {
        val c = JFileChooser(defaultDir).apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (c.showSaveDialog(DesktopPlatform.window) == JFileChooser.APPROVE_OPTION) c.selectedFile?.also { lastDir = it } else null
    }
}
