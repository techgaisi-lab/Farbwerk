@file:Suppress("unused", "UNUSED_PARAMETER")

package android.content

import android.net.Uri
import compat.DesktopPlatform
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Inhalt der Zwischenablage. */
class ClipData(val label: CharSequence?, private val mimeTypes: Array<String>, first: Item) {
    private val items = arrayListOf(first)

    class Item {
        val text: CharSequence?
        val uri: Uri?
        val intent: Intent?

        constructor(text: CharSequence?) { this.text = text; this.uri = null; this.intent = null }
        constructor(uri: Uri?) { this.text = null; this.uri = uri; this.intent = null }
        constructor(text: CharSequence?, intent: Intent?, uri: Uri?) { this.text = text; this.intent = intent; this.uri = uri }

        fun coerceToText(context: Context?): CharSequence =
            text ?: uri?.let { u -> u.toFile()?.takeIf { it.isFile && it.length() < 4_000_000 }?.readText() ?: u.toString() } ?: ""
    }

    val itemCount: Int get() = items.size
    fun getItemAt(index: Int): Item = items[index]
    fun addItem(item: Item) { items.add(item) }
    val description: ClipDescription get() = ClipDescription(label, mimeTypes)

    companion object {
        @JvmStatic fun newPlainText(label: CharSequence?, text: CharSequence?): ClipData =
            ClipData(label, arrayOf("text/plain"), Item(text))
        @JvmStatic fun newRawUri(label: CharSequence?, uri: Uri?): ClipData =
            ClipData(label, arrayOf("text/uri-list"), Item(uri))
        @JvmStatic fun newUri(resolver: ContentResolver?, label: CharSequence?, uri: Uri?): ClipData = newRawUri(label, uri)
    }
}

class ClipDescription(val label: CharSequence?, private val mimeTypes: Array<String>) {
    val mimeTypeCount: Int get() = mimeTypes.size
    fun getMimeType(i: Int): String = mimeTypes[i]
    fun hasMimeType(type: String): Boolean = mimeTypes.any { it == type || (type.endsWith("/*") && it.startsWith(type.dropLast(1))) }
}

/**
 * Systemzwischenablage über AWT. Bilder in der Zwischenablage werden als temporäre PNG-Datei
 * bereitgestellt (ClipData.Item.uri), Dateien als Datei-URI, Text als Text.
 */
object ClipboardManager {
    private val clipboard get() = Toolkit.getDefaultToolkit().systemClipboard

    val primaryClip: ClipData?
        get() = try { readClip() } catch (t: Throwable) { null }

    fun setPrimaryClip(clip: ClipData) {
        val item = clip.getItemAt(0)
        val text = item.text?.toString() ?: item.uri?.toString() ?: ""
        try { clipboard.setContents(StringSelection(text), null) } catch (_: Throwable) {}
    }

    fun clearPrimaryClip() { try { clipboard.setContents(StringSelection(""), null) } catch (_: Throwable) {} }

    fun hasPrimaryClip(): Boolean = try { clipboard.availableDataFlavors.isNotEmpty() } catch (_: Throwable) { false }

    val text: CharSequence? get() = try { clipboard.getData(DataFlavor.stringFlavor) as? String } catch (_: Throwable) { null }

    private fun readClip(): ClipData? {
        val cb = clipboard
        if (cb.isDataFlavorAvailable(DataFlavor.javaFileListFlavor)) {
            @Suppress("UNCHECKED_CAST")
            val files = cb.getData(DataFlavor.javaFileListFlavor) as? List<File>
            if (!files.isNullOrEmpty()) {
                val clip = ClipData.newRawUri("Dateien", Uri.fromFile(files[0]))
                files.drop(1).forEach { clip.addItem(ClipData.Item(Uri.fromFile(it))) }
                return clip
            }
        }
        if (cb.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
            val img = cb.getData(DataFlavor.imageFlavor) as? java.awt.Image
            if (img != null) {
                val w = img.getWidth(null); val h = img.getHeight(null)
                if (w > 0 && h > 0) {
                    val bi = img as? BufferedImage ?: BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { b ->
                        val g = b.createGraphics(); g.drawImage(img, 0, 0, null); g.dispose()
                    }
                    val dir = File(DesktopPlatform.dataDir, "cache/clipboard").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val f = File(dir, "zwischenablage_${System.currentTimeMillis()}.png")
                    ImageIO.write(bi, "png", f)
                    return ClipData(null, arrayOf("image/png"), ClipData.Item(Uri.fromFile(f)))
                }
            }
        }
        if (cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
            val s = cb.getData(DataFlavor.stringFlavor) as? String ?: return null
            return ClipData.newPlainText("Text", s)
        }
        return null
    }
}
