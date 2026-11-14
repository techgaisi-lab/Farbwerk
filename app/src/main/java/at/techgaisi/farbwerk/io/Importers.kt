package at.techgaisi.farbwerk.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.ArtDocument
import at.techgaisi.farbwerk.model.Layer
import at.techgaisi.farbwerk.model.Tool
import at.techgaisi.farbwerk.undo.LambdaAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Langlebige Hintergrundarbeit (Import/Export), die nicht abbricht, wenn ein Panel geschlossen wird.
 * [busyMessage] steuert den Fortschrittsdialog in Galerie und Aktionen.
 */
object Background {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var busyMessage by mutableStateOf<String?>(null)
        private set

    /** Führt [block] aus (Main-Thread-Coroutine), zeigt [message] und meldet Fehler per [onError]. */
    fun run(message: String, onError: (Throwable) -> Unit, block: suspend CoroutineScope.() -> Unit) {
        if (busyMessage != null) return
        busyMessage = message
        scope.launch {
            try { block() } catch (t: Throwable) {
                android.util.Log.e("Farbwerk", "Hintergrundaufgabe", t)
                onError(t)
            } finally { busyMessage = null }
        }
    }
}

/** Erkannter Dateityp eines Imports (anhand der ersten Bytes). */
enum class ImportKind { PROJECT, PSD, IMAGE, UNKNOWN }

object Importers {
    /** Obergrenze für Leinwände aus Bildern (Speicher/Darstellung). */
    const val MAX_CANVAS_PIXELS = 25_000_000L
    const val MAX_SIDE = 8192

    fun sniff(context: Context, uri: Uri): ImportKind = try {
        context.contentResolver.openInputStream(uri)?.use { ins ->
            val h = ByteArray(4)
            val n = ins.read(h)
            when {
                n >= 4 && h[0] == '8'.code.toByte() && h[1] == 'B'.code.toByte() && h[2] == 'P'.code.toByte() && h[3] == 'S'.code.toByte() -> ImportKind.PSD
                n >= 2 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() -> ImportKind.PROJECT
                n > 0 -> ImportKind.IMAGE
                else -> ImportKind.UNKNOWN
            }
        } ?: ImportKind.UNKNOWN
    } catch (_: Exception) { ImportKind.UNKNOWN }

    /** Anzeigename der Datei ohne Endung. */
    fun displayName(context: Context, uri: Uri): String {
        val raw = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) { null } ?: uri.lastPathSegment ?: "Import"
        return raw.substringBeforeLast('.').ifBlank { "Import" }
    }

    /**
     * Dekodiert ein Bild (JPEG/PNG/WebP/HEIF/GIF …) inkl. EXIF-Drehung als veränderbare ARGB_8888-Bitmap,
     * verkleinert auf höchstens [maxW]×[maxH] bzw. [maxPixels].
     */
    fun decodeImage(context: Context, uri: Uri, maxW: Int = MAX_SIDE, maxH: Int = MAX_SIDE,
                    maxPixels: Long = MAX_CANVAS_PIXELS): Bitmap {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            val w = info.size.width; val h = info.size.height
            var s = min(1f, min(maxW / w.toFloat(), maxH / h.toFloat()))
            val px = w.toLong() * h * s * s
            if (px > maxPixels) s *= sqrt(maxPixels / px.toDouble()).toFloat()
            if (s < 1f) decoder.setTargetSize(maxOf(1, (w * s).toInt()), maxOf(1, (h * s).toInt()))
        }
        if (bmp.config == Bitmap.Config.ARGB_8888 && bmp.isMutable) return bmp
        val c = bmp.copy(Bitmap.Config.ARGB_8888, true)
        bmp.recycle()
        return c
    }

    /** Neues Dokument aus einem Bild (Galerie → Foto/Importieren). */
    fun documentFromImage(bitmap: Bitmap, name: String, context: Context? = null): ArtDocument {
        // Bilder mit niedriger Auflösung intern hochrechnen, damit darauf Gemaltes scharf bleibt
        val detail = if (context != null) CanvasQuality.detailFor(context, bitmap.width, bitmap.height) else 1
        val w = bitmap.width * detail
        val h = bitmap.height * detail
        val doc = ArtDocument(name, w, h)
        doc.detailScale = detail.toFloat()
        doc.dpi = (doc.dpi * detail).coerceAtMost(4800)
        doc.guides.gridSize *= detail
        val layerBmp = if (detail == 1) ProjectFormat.fitToSize(bitmap, w, h) else {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.scale(detail.toFloat(), detail.toFloat())
            c.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            out
        }
        doc.layers.add(Layer("Ebene 1", layerBmp))
        doc.activeIndex = 0
        doc.dirty = true
        return doc
    }

    fun readPsd(context: Context, uri: Uri): ArtDocument =
        context.contentResolver.openInputStream(uri)?.use { PsdReader.read(it, displayName(context, uri)) }
            ?: throw java.io.IOException("Datei nicht lesbar")

    /**
     * Fügt [image] als neue Ebene über der aktiven ein (zentriert, bei Bedarf auf Leinwand eingepasst)
     * und wechselt ins Transformieren, damit das Bild gleich platziert werden kann. Mit Undo.
     */
    fun insertImageAsLayer(state: EditorState, image: Bitmap, name: String, transform: Boolean = true, private: Boolean = false) {
        val doc = state.document ?: return
        val layerBmp = doc.newLayerBitmap()
        val s = min(1f, min(doc.width / image.width.toFloat(), doc.height / image.height.toFloat()))
        val dw = image.width * s; val dh = image.height * s
        val c = Canvas(layerBmp)
        c.translate((doc.width - dw) / 2f, (doc.height - dh) / 2f)
        c.scale(s, s)
        c.drawBitmap(image, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        val layer = Layer(name.take(60).ifBlank { "Eingefügtes Bild" }, layerBmp).also { it.isPrivate = private }
        val ok = addLayer(state, layer, if (private) "Privat einfügen" else "Bild einfügen")
        if (ok && transform) state.selectTool(Tool.TRANSFORM)
    }

    /** Fügt eine fertige Ebene über der aktiven ein (Undo-fähig). */
    fun addLayer(state: EditorState, layer: Layer, label: String): Boolean {
        val doc = state.document ?: return false
        val max = at.techgaisi.farbwerk.model.LayerOps.maxLayers(state)
        if (doc.layers.size >= max) {
            state.flash("Maximale Ebenenzahl erreicht ($max)")
            layer.bitmap.recycle()
            return false
        }
        if (state.tool == Tool.TRANSFORM) state.transformTool.commit()
        val prevActive = doc.activeIndex
        val idx = (doc.activeIndex + 1).coerceIn(0, doc.layers.size)
        doc.layers.add(idx, layer)
        doc.activeIndex = idx
        state.undo.push(LambdaAction(label,
            onUndo = {
                doc.layers.remove(layer)
                doc.activeIndex = prevActive.coerceIn(0, maxOf(0, doc.layers.lastIndex))
            },
            onRedo = {
                doc.layers.add(idx.coerceAtMost(doc.layers.size), layer)
                doc.activeIndex = doc.indexOf(layer)
            },
            bytes = layer.bitmap.allocationByteCount.toLong(),
        ))
        state.invalidate()
        return true
    }

    /**
     * Datei in die offene Leinwand einfügen (Aktionen → Hinzufügen → Datei einfügen).
     * PSD-Dateien werden als zusammengefügtes Bild eingefügt, Projekte ebenso.
     */
    fun insertFile(state: EditorState, uri: Uri, private: Boolean = false) {
        val ctx = state.context
        Background.run("Datei wird eingefügt …", { state.flash("Import fehlgeschlagen: ${it.message ?: "Fehler"}") }) {
            val name = displayName(ctx, uri)
            val doc = state.document ?: return@run
            val maxW = doc.width * 2; val maxH = doc.height * 2
            val image: Bitmap = kotlinx.coroutines.withContext(Dispatchers.IO) {
                when (sniff(ctx, uri)) {
                    ImportKind.PSD -> {
                        val psd = readPsd(ctx, uri)
                        flattenOffline(psd)
                    }
                    ImportKind.PROJECT -> throw IllegalArgumentException("Projekte bitte in der Galerie importieren")
                    else -> decodeImage(ctx, uri, maxW, maxH)
                }
            }
            insertImageAsLayer(state, image, name, private = private)
            image.recycle()
        }
    }

    /** Einfaches Zusammenfügen eines nicht geöffneten Dokuments (Hintergrund-Thread). */
    private fun flattenOffline(doc: ArtDocument): Bitmap {
        val out = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        for (l in doc.layers) if (l.visible) {
            p.alpha = (l.opacity * 255).toInt()
            c.drawBitmap(l.bitmap, 0f, 0f, p)
        }
        doc.layers.forEach { it.bitmap.recycle(); it.mask?.recycle() }
        return out
    }
}
