package compat

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.DesktopContext
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.view.View
import java.awt.Desktop
import java.awt.EventQueue
import java.io.File
import javax.swing.JOptionPane

/**
 * Plattformzustand der Desktop-Version: Datenverzeichnis, Fenster, Bildschirmdichte, aktive Activity und die
 * Umsetzung von Android-Intents (Teilen → Speichern/Explorer, Text → Zwischenablage).
 */
object DesktopPlatform {
    val isWindows: Boolean = (System.getProperty("os.name") ?: "").lowercase().contains("win")
    val isMac: Boolean = (System.getProperty("os.name") ?: "").lowercase().contains("mac")

    /** %APPDATA%\Farbwerk (Windows), sonst ~/.farbwerk. */
    val dataDir: File by lazy {
        val appData = System.getenv("APPDATA")
        val dir = if (isWindows && !appData.isNullOrBlank()) File(appData, "Farbwerk")
            else File(System.getProperty("user.home") ?: ".", ".farbwerk")
        dir.apply { mkdirs() }
    }

    /** Version der App (aus den Build-Ressourcen). */
    val appVersion: String by lazy {
        try {
            DesktopPlatform::class.java.getResourceAsStream("/farbwerk-version.properties")?.use {
                java.util.Properties().apply { load(it) }.getProperty("version")
            }
        } catch (_: Exception) { null } ?: "0.3.3"
    }

    @Volatile var density: Float = 1f
    @Volatile var fontScale: Float = 1f
    @Volatile var windowWidthPx: Int = 1440
    @Volatile var windowHeightPx: Int = 960
    @Volatile var configuration: Configuration = Configuration()

    /** Hauptfenster (Elternfenster für Dialoge). */
    @Volatile var window: java.awt.Frame? = null
    @Volatile var activity: Activity? = null
    /** Zustand des Editors (für Desktop-Extras wie Mausrad-Zoom, Pan mit mittlerer Taste). */
    @Volatile var editorState: at.techgaisi.farbwerk.EditorState? = null
    /** Wird vom Einstieg gesetzt: Fenster schließen (inkl. Speichern). */
    @Volatile var exitHandler: (() -> Unit)? = null

    val rootView: View by lazy { View(DesktopContext) }

    fun requestExit() { EventQueue.invokeLater { exitHandler?.invoke() } }

    fun updateWindowMetrics(widthPx: Int, heightPx: Int, density: Float, fontScale: Float): Configuration {
        this.density = density; this.fontScale = fontScale
        windowWidthPx = widthPx; windowHeightPx = heightPx
        val c = Configuration().apply {
            screenWidthDp = (widthPx / density).toInt()
            screenHeightDp = (heightPx / density).toInt()
            smallestScreenWidthDp = minOf(screenWidthDp, screenHeightDp)
            densityDpi = (160 * density).toInt()
            this.fontScale = fontScale
            orientation = if (widthPx >= heightPx) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        }
        configuration = c
        return c
    }

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "psd" -> "image/vnd.adobe.photoshop"
        "pdf" -> "application/pdf"
        "mp4" -> "video/mp4"
        "zip" -> "application/zip"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "json", "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    // ------------------------------------------------------------------ Intents

    /** Setzt Android-Intents auf dem Desktop um (asynchron auf dem UI-Thread). */
    fun handleIntent(intent: Intent) {
        EventQueue.invokeLater {
            try { handleNow(intent) } catch (t: Throwable) {
                System.err.println("Intent fehlgeschlagen: $t")
                message("Aktion fehlgeschlagen: ${t.message ?: t.javaClass.simpleName}", error = true)
            }
        }
    }

    private fun handleNow(intent: Intent) {
        val target = if (intent.action == Intent.ACTION_CHOOSER) (intent.extras[Intent.EXTRA_INTENT] as? Intent ?: return) else intent
        when (target.action) {
            Intent.ACTION_SEND -> {
                val stream = target.extras[Intent.EXTRA_STREAM] as? Uri
                val file = stream?.toFile()
                if (file != null && file.exists()) {
                    saveCopies(listOf(file))
                } else {
                    val text = target.extras[Intent.EXTRA_TEXT]?.toString() ?: return
                    ClipboardManager.setPrimaryClip(ClipData.newPlainText("Farbwerk", text))
                    message("Der Text wurde in die Zwischenablage kopiert.")
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("UNCHECKED_CAST")
                val uris = target.extras[Intent.EXTRA_STREAM] as? List<Uri> ?: return
                saveCopies(uris.mapNotNull { it.toFile() }.filter { it.exists() })
            }
            Intent.ACTION_VIEW -> {
                val u = target.data ?: return
                val f = u.toFile()
                if (f != null && Desktop.isDesktopSupported()) Desktop.getDesktop().open(f)
                else if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(java.net.URI(u.toString()))
            }
        }
    }

    /** „Teilen“ einer oder mehrerer Dateien: Speichern-unter-Dialog bzw. Ordnerwahl, danach im Explorer zeigen. */
    private fun saveCopies(files: List<File>) {
        if (files.isEmpty()) return
        if (files.size == 1) {
            val src = files[0]
            val dst = FileDialogs.save("Speichern unter", src.name) ?: return
            if (src.canonicalPath != dst.canonicalPath) src.copyTo(dst, overwrite = true)
            revealInExplorer(dst)
        } else {
            val dir = FileDialogs.chooseDirectory("Zielordner für ${files.size} Dateien wählen") ?: return
            var last: File? = null
            for (f in files) { val d = File(dir, f.name); f.copyTo(d, overwrite = true); last = d }
            last?.let { revealInExplorer(it) }
        }
    }

    /** Zeigt die Datei im Explorer (Windows: markiert), sonst öffnet den Ordner. */
    fun revealInExplorer(f: File) {
        try {
            when {
                isWindows -> ProcessBuilder("explorer.exe", "/select,", f.absolutePath).start()
                isMac -> ProcessBuilder("open", "-R", f.absolutePath).start()
                Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN) ->
                    Desktop.getDesktop().open(f.parentFile)
            }
        } catch (t: Throwable) {
            System.err.println("Explorer konnte nicht geöffnet werden: $t")
        }
    }

    fun message(text: String, error: Boolean = false) {
        JOptionPane.showMessageDialog(window, text, "Farbwerk",
            if (error) JOptionPane.ERROR_MESSAGE else JOptionPane.INFORMATION_MESSAGE)
    }
}
