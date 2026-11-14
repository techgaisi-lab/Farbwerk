package at.techgaisi.farbwerk.io

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import at.techgaisi.farbwerk.model.ArtDocument
import at.techgaisi.farbwerk.model.GuideKind
import at.techgaisi.farbwerk.model.GuideSettings
import at.techgaisi.farbwerk.model.Layer
import at.techgaisi.farbwerk.model.LayerBlend
import at.techgaisi.farbwerk.model.SymmetryMode
import at.techgaisi.farbwerk.render.LayerGroups
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Eigenes Projektformat ".farbwerk" (ZIP-Container):
 *
 * ```
 * document.json        – alle Dokument- und Ebenenfelder, Zeichenhilfen, Hintergrund
 * thumb.png            – Vorschaubild (max. 512 px)
 * layers/<n>.fwl       – Ebenenpixel, schnelles Kachelformat ([LayerCodec], ab Version 2, interne Speicherung)
 * layers/<n>.png       – Ebenenpixel als PNG (Version 1 und Export über [write])
 * masks/<n>.fwl|png    – optionale Ebenenmasken (FWL: ALPHA_8 roh; PNG: Weiß mit Alpha = Maskenwert)
 * ```
 * Ebenen sind von unten (Index 0) nach oben gespeichert. Der Leser erkennt das Ebenenformat an der Endung.
 */
internal object ProjectFormat {
    const val FORMAT = "farbwerk"
    const val VERSION = 1
    /** Version der intern (mit [writeInternal]) geschriebenen Dateien. */
    const val VERSION_FAST = 2
    const val DOC_ENTRY = "document.json"
    const val THUMB_ENTRY = "thumb.png"
    const val EXTENSION = "farbwerk"

    /** Unveränderliche Momentaufnahme einer Ebene, wie sie geschrieben wird. */
    class LayerData(val bitmap: Bitmap, val mask: Bitmap?)

    // ------------------------------------------------------------------ JSON

    /**
     * Serialisiert alle Metadaten. [withTimes] = false liefert eine "Signatur" ohne Zeitstempel,
     * mit der der Speicher erkennt, ob sich seit dem letzten Speichern etwas geändert hat.
     */
    fun toJson(doc: ArtDocument, withTimes: Boolean = true): JSONObject {
        val o = JSONObject()
        o.put("format", FORMAT)
        o.put("version", VERSION)
        o.put("id", doc.id)
        o.put("name", doc.name)
        o.put("width", doc.width)
        o.put("height", doc.height)
        o.put("dpi", doc.dpi)
        o.put("detailScale", doc.detailScale.toDouble())
        o.put("backgroundColor", doc.backgroundColor)
        o.put("backgroundVisible", doc.backgroundVisible)
        o.put("activeIndex", doc.activeIndex)
        o.put("flipHorizontal", doc.flipHorizontal)
        o.put("flipVertical", doc.flipVertical)
        o.put("colorProfile", "sRGB")
        if (withTimes) {
            o.put("created", doc.created)
            o.put("modified", doc.modified)
        }
        o.put("guides", guidesToJson(doc.guides))
        val arr = JSONArray()
        doc.layers.forEachIndexed { i, l ->
            val lo = JSONObject()
            lo.put("id", l.id)
            lo.put("name", l.name)
            lo.put("file", "layers/$i.png")
            lo.put("visible", l.visible)
            lo.put("opacity", l.opacity.toDouble())
            lo.put("blend", l.blend.name)
            lo.put("alphaLock", l.alphaLock)
            lo.put("clipping", l.clipping)
            lo.put("locked", l.locked)
            lo.put("isReference", l.isReference)
            l.groupId?.let { lo.put("groupId", it) }
            if (l.assisted) lo.put("assisted", true)
            if (l.isPrivate) lo.put("private", true)
            (l.text as? at.techgaisi.farbwerk.text.TextData)?.let { lo.put("text", it.toJson()) }
            l.mask?.let { m ->
                lo.put("mask", "masks/$i.png")
                lo.put("maskConfig", if (m.config == Bitmap.Config.ALPHA_8) "ALPHA_8" else "ARGB_8888")
            }
            arr.put(lo)
        }
        o.put("layers", arr)
        // Gruppen-Metadaten (Name, Sichtbarkeit, Deckkraft, eingeklappt) vom Ebenen-Modul
        o.put("groups", LayerGroups.toJson(doc))
        (doc.animation as? at.techgaisi.farbwerk.animation.AnimationSettings)?.let { o.put("animation", it.toJson()) }
        return o
    }

    private fun guidesToJson(g: GuideSettings) = JSONObject().apply {
        put("kind", g.kind.name)
        put("visible", g.visible)
        put("gridSize", g.gridSize.toDouble())
        put("color", g.color)
        put("opacity", g.opacity.toDouble())
        put("symmetry", g.symmetry.name)
        // NaN ist in JSON nicht erlaubt → weglassen bedeutet "Dokumentmitte"
        if (!g.symmetryCenterX.isNaN()) put("centerX", g.symmetryCenterX.toDouble())
        if (!g.symmetryCenterY.isNaN()) put("centerY", g.symmetryCenterY.toDouble())
        put("rotational", g.rotationalSymmetry)
        put("radialSegments", g.radialSegments)
        put("assisted", g.assisted)
        put("thickness", g.thickness.toDouble())
        put("offsetX", g.offsetX.toDouble())
        put("offsetY", g.offsetY.toDouble())
        put("rotation", g.rotation.toDouble())
        put("vanishingPoints", JSONArray().apply {
            g.vanishingPoints.forEach { put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) }
        })
    }

    private fun guidesFromJson(o: JSONObject?, g: GuideSettings) {
        if (o == null) return
        g.kind = enumOr(o.optString("kind"), GuideKind.NONE)
        g.visible = o.optBoolean("visible", false)
        g.gridSize = o.optDouble("gridSize", 100.0).toFloat().coerceAtLeast(2f)
        g.color = o.optInt("color", g.color)
        g.opacity = o.optDouble("opacity", 0.6).toFloat().coerceIn(0f, 1f)
        g.symmetry = enumOr(o.optString("symmetry"), SymmetryMode.VERTICAL)
        g.symmetryCenterX = if (o.has("centerX")) o.optDouble("centerX").toFloat() else Float.NaN
        g.symmetryCenterY = if (o.has("centerY")) o.optDouble("centerY").toFloat() else Float.NaN
        g.rotationalSymmetry = o.optBoolean("rotational", false)
        g.radialSegments = o.optInt("radialSegments", 8).coerceIn(2, 32)
        g.assisted = o.optBoolean("assisted", false)
        g.thickness = o.optDouble("thickness", 1.0).toFloat().coerceIn(0.25f, 8f)
        g.offsetX = o.optDouble("offsetX", 0.0).toFloat()
        g.offsetY = o.optDouble("offsetY", 0.0).toFloat()
        g.rotation = o.optDouble("rotation", 0.0).toFloat()
        g.vanishingPoints.clear()
        o.optJSONArray("vanishingPoints")?.let { arr ->
            for (i in 0 until minOf(arr.length(), 3)) {
                val p = arr.optJSONArray(i) ?: continue
                g.vanishingPoints.add(android.graphics.PointF(p.optDouble(0).toFloat(), p.optDouble(1).toFloat()))
            }
        }
    }

    inline fun <reified T : Enum<T>> enumOr(name: String?, def: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: def

    // ------------------------------------------------------------------ Schreiben

    /**
     * Schreibt ein Projekt atomar: zuerst in "<ziel>.tmp", danach Umbenennen.
     * Darf im Hintergrund laufen; die Bitmaps dürfen währenddessen nicht verändert werden.
     */
    fun write(target: File, json: JSONObject, layers: List<LayerData>, thumb: Bitmap?) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { zip ->
                // PNG ist bereits komprimiert – schnellste Deflate-Stufe genügt.
                zip.setLevel(Deflater.BEST_SPEED)
                zip.putNextEntry(ZipEntry(DOC_ENTRY))
                zip.write(json.toString(1).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                if (thumb != null) {
                    zip.putNextEntry(ZipEntry(THUMB_ENTRY))
                    thumb.compress(Bitmap.CompressFormat.PNG, 100, NonClosing(zip))
                    zip.closeEntry()
                }
                val maskPaint = Paint().apply { color = Color.WHITE }
                layers.forEachIndexed { i, l ->
                    zip.putNextEntry(ZipEntry("layers/$i.png"))
                    if (!l.bitmap.compress(Bitmap.CompressFormat.PNG, 100, NonClosing(zip))) {
                        throw IOException("PNG-Kodierung fehlgeschlagen (Ebene $i)")
                    }
                    zip.closeEntry()
                    l.mask?.let { m ->
                        zip.putNextEntry(ZipEntry("masks/$i.png"))
                        if (m.config == Bitmap.Config.ALPHA_8) {
                            // ALPHA_8 → Weiß mit Alpha, damit jeder PNG-Encoder es sicher schreibt
                            val argb = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
                            Canvas(argb).drawBitmap(m, 0f, 0f, maskPaint)
                            argb.compress(Bitmap.CompressFormat.PNG, 100, NonClosing(zip))
                            argb.recycle()
                        } else {
                            m.compress(Bitmap.CompressFormat.PNG, 100, NonClosing(zip))
                        }
                        zip.closeEntry()
                    }
                }
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("Umbenennen fehlgeschlagen: $target")
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Zähler einer [writeInternal]-Speicherung (für Messungen/Protokoll). */
    class WriteStats {
        /** [0] neu kodierte Kacheln, [1] übernommene Kacheln. */
        val tiles = IntArray(2)
        var layersReused = 0
    }

    /**
     * Schreibt ein Projekt für den internen Speicher atomar ("<ziel>.tmp" → Umbenennen) im schnellen
     * Kachelformat. Unveränderte Kacheln werden aus der bisherigen Datei [target] übernommen (Vergleich der
     * Prüfsummen), nur Geänderte werden neu komprimiert. Darf im Hintergrund laufen.
     */
    fun writeInternal(target: File, json: JSONObject, layers: List<LayerData>, thumb: Bitmap?, stats: WriteStats? = null) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        val old = try { if (target.exists()) OldProject(target) else null } catch (t: Throwable) { null }
        try {
            val arr = json.optJSONArray("layers")
            json.put("version", VERSION_FAST)
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { zip ->
                zip.setLevel(Deflater.BEST_SPEED)
                if (thumb != null) {
                    val bytes = java.io.ByteArrayOutputStream(256 * 1024)
                    thumb.compress(Bitmap.CompressFormat.PNG, 100, bytes)
                    putStored(zip, THUMB_ENTRY, bytes.toByteArray())
                }
                layers.forEachIndexed { i, l ->
                    val lo = arr?.optJSONObject(i)
                    val id = lo?.optString("id").orEmpty()
                    val blob = LayerCodec.encode(l.bitmap, old?.layer(id, false), stats?.tiles)
                    putStored(zip, "layers/$i.fwl", blob)
                    lo?.put("file", "layers/$i.fwl")
                    val m = l.mask
                    if (m != null) {
                        putStored(zip, "masks/$i.fwl", LayerCodec.encode(m, old?.layer(id, true), stats?.tiles))
                        lo?.put("mask", "masks/$i.fwl")
                        lo?.put("maskConfig", if (m.config == Bitmap.Config.ALPHA_8) "ALPHA_8" else "ARGB_8888")
                    }
                }
                // document.json zuletzt: enthält die endgültigen Eintragsnamen
                zip.putNextEntry(ZipEntry(DOC_ENTRY))
                zip.write(json.toString(1).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            // Alte Datei vor dem Umbenennen schließen (Windows: geöffnete Dateien lassen sich nicht ersetzen)
            old?.close()
            FileOutputStream(tmp, true).use { it.fd.sync() }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("Umbenennen fehlgeschlagen: $target")
            }
        } finally {
            old?.close()
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Ungepackter ZIP-Eintrag (Inhalt ist bereits komprimiert). */
    private fun putStored(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val e = ZipEntry(name)
        e.method = ZipEntry.STORED
        e.size = bytes.size.toLong(); e.compressedSize = bytes.size.toLong()
        e.crc = java.util.zip.CRC32().apply { update(bytes) }.value
        zip.putNextEntry(e); zip.write(bytes); zip.closeEntry()
    }

    /** Bisherige Projektdatei: liefert die FWL-Daten einer Ebene/Maske anhand der Ebenen-ID. */
    private class OldProject(file: File) {
        private val zip = ZipFile(file)
        private val entries = HashMap<String, String>()
        @Volatile private var closed = false
        init {
            try {
                val o = JSONObject(zip.getInputStream(zip.getEntry(DOC_ENTRY)).bufferedReader(Charsets.UTF_8).readText())
                val arr = o.optJSONArray("layers") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val lo = arr.optJSONObject(i) ?: continue
                    val id = lo.optString("id"); if (id.isEmpty()) continue
                    lo.optString("file").takeIf { it.endsWith(".fwl") }?.let { entries[id] = it }
                    lo.optString("mask").takeIf { it.endsWith(".fwl") }?.let { entries["m:$id"] = it }
                }
            } catch (t: Throwable) { zip.close(); throw t }
        }
        fun layer(id: String, mask: Boolean): LayerCodec.Parsed? {
            if (closed || id.isEmpty()) return null
            val name = entries[if (mask) "m:$id" else id] ?: return null
            return try {
                val e = zip.getEntry(name) ?: return null
                LayerCodec.Parsed(zip.getInputStream(e).use { it.readBytes() })
            } catch (t: Throwable) { null }
        }
        fun close() { if (!closed) { closed = true; try { zip.close() } catch (_: Throwable) {} } }
    }

    // ------------------------------------------------------------------ Lesen

    fun readJson(file: File): JSONObject = ZipFile(file).use { zip ->
        val e = zip.getEntry(DOC_ENTRY) ?: throw IOException("Kein Farbwerk-Projekt (document.json fehlt)")
        JSONObject(zip.getInputStream(e).bufferedReader(Charsets.UTF_8).readText())
    }

    /** Lädt ein Projekt vollständig. [idOverride] ersetzt die gespeicherte ID (z. B. Verzeichnisname). */
    fun read(file: File, idOverride: String? = null): ArtDocument = ZipFile(file).use { zip ->
        val e = zip.getEntry(DOC_ENTRY) ?: throw IOException("document.json fehlt")
        val o = JSONObject(zip.getInputStream(e).bufferedReader(Charsets.UTF_8).readText())
        if (o.optString("format", FORMAT) != FORMAT) throw IOException("Unbekanntes Format")
        val w = o.getInt("width"); val h = o.getInt("height")
        require(w in 1..32768 && h in 1..32768) { "Ungültige Leinwandgröße" }
        val doc = ArtDocument(o.optString("name", "Ohne Titel"), w, h, idOverride ?: o.optString("id"))
        doc.dpi = o.optInt("dpi", 300).coerceIn(1, 4800)
        // Feinberechnung (ältere Projekte ohne Feld: 1×)
        doc.detailScale = o.optDouble("detailScale", 1.0).toFloat().let { if (it.isNaN()) 1f else it.coerceIn(1f, 4f) }
        doc.backgroundColor = o.optInt("backgroundColor", Color.WHITE)
        doc.backgroundVisible = o.optBoolean("backgroundVisible", true)
        doc.flipHorizontal = o.optBoolean("flipHorizontal", false)
        doc.flipVertical = o.optBoolean("flipVertical", false)
        doc.created = o.optLong("created", System.currentTimeMillis())
        doc.modified = o.optLong("modified", doc.created)
        guidesFromJson(o.optJSONObject("guides"), doc.guides)

        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val arr = o.optJSONArray("layers") ?: JSONArray()
        /** Liest einen Ebenen-/Maskeneintrag: FWL (schnell, bitgenau) oder PNG. */
        fun readEntry(name: String): Bitmap? {
            val entry = zip.getEntry(name) ?: return null
            return if (name.endsWith(".fwl")) {
                LayerCodec.decode(zip.getInputStream(entry).use { it.readBytes() })
            } else zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, opts) }
        }
        for (i in 0 until arr.length()) {
            val lo = arr.getJSONObject(i)
            val bmp = readEntry(lo.optString("file", "layers/$i.png"))
            val layer = Layer(
                lo.optString("name", "Ebene ${i + 1}"),
                fitToSize(bmp, w, h),
                lo.optString("id").ifEmpty { java.util.UUID.randomUUID().toString() },
            )
            layer.visible = lo.optBoolean("visible", true)
            layer.opacity = lo.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f)
            layer.blend = enumOr(lo.optString("blend"), LayerBlend.NORMAL)
            layer.alphaLock = lo.optBoolean("alphaLock", false)
            layer.clipping = lo.optBoolean("clipping", false)
            layer.locked = lo.optBoolean("locked", false)
            layer.isReference = lo.optBoolean("isReference", false)
            layer.groupId = if (lo.has("groupId") && !lo.isNull("groupId")) lo.getString("groupId") else null
            layer.assisted = lo.optBoolean("assisted", false)
            layer.isPrivate = lo.optBoolean("private", false)
            at.techgaisi.farbwerk.text.TextData.fromJson(lo.optJSONObject("text"))?.let {
                it.renderedGeneration = layer.bitmap.generationId
                layer.text = it
            }
            if (lo.has("mask")) {
                val m = readEntry(lo.getString("mask"))
                if (m != null && m.config == Bitmap.Config.ALPHA_8) {
                    // FWL-Maske: bereits ALPHA_8 in Dokumentgröße
                    layer.mask = if (m.width == w && m.height == h) m else {
                        val a = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
                        Canvas(a).drawBitmap(m, 0f, 0f, null); m.recycle(); a
                    }
                } else if (m != null) {
                    val full = fitToSize(m, w, h)
                    layer.mask = if (lo.optString("maskConfig") == "ALPHA_8") {
                        val a = full.extractAlpha().copy(Bitmap.Config.ALPHA_8, true)
                        full.recycle(); a
                    } else full
                }
            }
            doc.layers.add(layer)
        }
        if (doc.layers.isEmpty()) doc.layers.add(Layer("Ebene 1", doc.newLayerBitmap()))
        o.optJSONArray("groups")?.let { LayerGroups.fromJson(doc, it) }
        doc.animation = at.techgaisi.farbwerk.animation.AnimationSettings.fromJson(o.optJSONObject("animation"))
        doc.activeIndex = o.optInt("activeIndex", 0).coerceIn(0, doc.layers.lastIndex)
        doc.dirty = false
        doc
    }

    /** Liefert eine veränderbare ARGB-Bitmap exakt in Dokumentgröße (leer, falls [src] fehlt). */
    fun fitToSize(src: Bitmap?, w: Int, h: Int): Bitmap {
        if (src != null && src.width == w && src.height == h && src.isMutable &&
            src.config == Bitmap.Config.ARGB_8888
        ) return src
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (src != null) {
            Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) })
            src.recycle()
        }
        return out
    }

    /**
     * Kopiert ein Projekt-ZIP und ersetzt dabei document.json durch [transform]. Für Duplizieren,
     * Umbenennen und Import (neue ID). Schreibt atomar.
     */
    fun rewrite(src: File, target: File, transform: (JSONObject) -> Unit) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            ZipFile(src).use { inZip ->
                val json = JSONObject(
                    inZip.getInputStream(inZip.getEntry(DOC_ENTRY) ?: throw IOException("document.json fehlt"))
                        .bufferedReader(Charsets.UTF_8).readText()
                )
                transform(json)
                ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { out ->
                    out.setLevel(Deflater.BEST_SPEED)
                    out.putNextEntry(ZipEntry(DOC_ENTRY))
                    out.write(json.toString(1).toByteArray(Charsets.UTF_8))
                    out.closeEntry()
                    val buf = ByteArray(1 shl 16)
                    for (entry in inZip.entries()) {
                        if (entry.name == DOC_ENTRY || entry.isDirectory) continue
                        // Schutz vor Pfad-Tricks in fremden Archiven
                        if (entry.name.contains("..")) continue
                        val ne = ZipEntry(entry.name)
                        if (entry.method == ZipEntry.STORED && entry.size >= 0 && entry.crc >= 0) {
                            // Ungepackte Einträge (FWL-Ebenen, Miniatur) ungepackt übernehmen
                            ne.method = ZipEntry.STORED
                            ne.size = entry.size; ne.compressedSize = entry.size; ne.crc = entry.crc
                        }
                        out.putNextEntry(ne)
                        inZip.getInputStream(entry).use { ins ->
                            while (true) {
                                val n = ins.read(buf); if (n < 0) break
                                out.write(buf, 0, n)
                            }
                        }
                        out.closeEntry()
                    }
                }
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("Umbenennen fehlgeschlagen")
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Liest das Vorschaubild aus dem ZIP (falls vorhanden). */
    fun readThumb(file: File): Bitmap? = try {
        ZipFile(file).use { zip ->
            zip.getEntry(THUMB_ENTRY)?.let { e -> zip.getInputStream(e).use { BitmapFactory.decodeStream(it) } }
        }
    } catch (_: Exception) { null }

    /** OutputStream-Hülle, die close() ignoriert (Bitmap.compress in ZIP-Einträge). */
    private class NonClosing(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) = out.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun flush() = out.flush()
        override fun close() {}
    }
}
