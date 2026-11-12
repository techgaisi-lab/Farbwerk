package at.techgaisi.farbwerk.brush

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import at.techgaisi.farbwerk.model.Tool
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Ein Pinselset in der linken Spalte der Bibliothek.
 * [virtual] = automatisch gefüllt (Zuletzt, Favoriten). [icon] = Emoji/Zeichen (null = eigenes Standardsymbol).
 * [deletable] = eigenes Set (löschen erlaubt).
 */
data class BrushSet(
    val id: String,
    val name: String,
    val builtIn: Boolean,
    val virtual: Boolean = false,
    val icon: String? = null,
    val deletable: Boolean = false,
)

/**
 * Pinselbibliothek: mitgelieferte Sets, eigene Sets und Pinsel (JSON + PNG in filesDir), Reihenfolge von Sets
 * und Pinseln, Verschieben/Kopieren zwischen Sets, Favoriten, „Zuletzt verwendet“ mit Anheften, Suche,
 * Größen-/Deckkraftmarken, Dateiformat `.fwbrush`/`.fwbrushset` (ZIP aus JSON + Form/Körnung-PNG) und
 * gecachte Strichvorschauen.
 */
class BrushLibrary(private val context: Context) {

    /** Erhöht sich bei jeder Änderung (Compose liest das). */
    var version by mutableIntStateOf(0)
        private set

    private class UserSet(var name: String, var icon: String?)

    private val builtIn = LinkedHashMap<String, Brush>()
    private val builtInNames = HashMap<String, String>()
    private val custom = LinkedHashMap<String, Brush>()
    private val userSets = LinkedHashMap<String, UserSet>()
    private val setNameOverride = HashMap<String, String>()
    private val setIconOverride = HashMap<String, String>()
    private val setOrder = ArrayList<String>()
    private val placement = HashMap<String, String>()
    private val brushOrder = HashMap<String, MutableList<String>>()
    private val favorites = LinkedHashSet<String>()
    private val recent = ArrayList<String>()
    private val pinned = LinkedHashSet<String>()
    private val nameOverrides = HashMap<String, String>()
    private val marks = HashMap<String, FloatArray>()

    private val file = File(context.filesDir, "brushes.json")
    private val imgDir = File(context.filesDir, "brushimg")
    private val previews = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    init {
        for (s in BrushPresets.sets) for (b in s.brushes) { builtIn[b.id] = b; builtInNames[b.id] = b.name }
        load()
        normalize()
    }

    // ------------------------------------------------------------ Abfragen

    fun defaultBrushId(tool: Tool): String = when (tool) {
        Tool.SMUDGE -> "airbrush_weich"
        Tool.ERASE -> "airbrush_hart"
        else -> "studiotinte"
    }

    fun get(id: String): Brush? = custom[id] ?: builtIn[id]

    fun getOrDefault(id: String, tool: Tool): Brush = get(id) ?: builtIn.getValue(defaultBrushId(tool))

    private fun presetSet(id: String) = BrushPresets.sets.firstOrNull { it.id == id }

    private fun setExists(id: String) = presetSet(id) != null || userSets.containsKey(id)

    /** Set, in dem ein Pinsel angezeigt wird. */
    fun setOf(id: String): String {
        custom[id]?.let { return it.setId }
        placement[id]?.let { return it }
        return builtIn[id]?.setId ?: BrushPresets.sets.first().id
    }

    fun setName(id: String): String = when (id) {
        SET_RECENT -> "Zuletzt verwendet"
        SET_FAVORITES -> "Favoriten"
        else -> userSets[id]?.name ?: setNameOverride[id] ?: presetSet(id)?.name ?: "Set"
    }

    private fun setIcon(id: String): String? {
        val explicit = userSets[id]?.icon ?: setIconOverride[id]
        if (!explicit.isNullOrBlank()) return explicit
        return firstEmoji(setName(id))
    }

    /** Alle Sets in Anzeigereihenfolge. */
    fun sets(): List<BrushSet> {
        val out = ArrayList<BrushSet>()
        if (recent.isNotEmpty() || pinned.isNotEmpty()) out.add(BrushSet(SET_RECENT, setName(SET_RECENT), builtIn = true, virtual = true))
        if (favorites.isNotEmpty()) out.add(BrushSet(SET_FAVORITES, setName(SET_FAVORITES), builtIn = true, virtual = true))
        for (id in setOrder) {
            val user = userSets.containsKey(id)
            out.add(BrushSet(id, setName(id), builtIn = !user, icon = setIcon(id), deletable = user))
        }
        return out
    }

    /** Pinsel eines Sets in Anzeigereihenfolge. */
    fun brushes(setId: String): List<Brush> = when (setId) {
        SET_RECENT -> {
            val l = pinned.mapNotNull { get(it) }.toMutableList()
            for (id in recent) { if (l.size >= MAX_RECENT + pinned.size) break; if (id !in pinned) get(id)?.let { l.add(it) } }
            l.take(maxOf(MAX_RECENT, pinned.size))
        }
        SET_FAVORITES -> favorites.mapNotNull { get(it) }
        else -> {
            val members = ArrayList<Brush>()
            presetSet(setId)?.brushes?.forEach { b ->
                if (setOf(b.id) == setId) members.add(b)
                custom.values.filter { it.setId == setId && it.basedOn == b.id }.forEach { members.add(it) }
            }
            for (b in builtIn.values) if (placement[b.id] == setId && members.none { it.id == b.id }) members.add(b)
            for (b in custom.values) if (b.setId == setId && members.none { it.id == b.id }) members.add(b)
            val order = brushOrder[setId]
            if (order.isNullOrEmpty()) members
            else {
                val byId = members.associateBy { it.id }
                val l = order.mapNotNull { byId[it] }.toMutableList()
                for (b in members) if (b !in l) l.add(b)
                l
            }
        }
    }

    /** Suche über alle Sets (Name enthält [q], ohne Groß-/Kleinschreibung). */
    fun search(q: String): List<Brush> {
        val t = q.trim()
        if (t.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, Brush>()
        for (s in setOrder) for (b in brushes(s)) if (b.name.contains(t, ignoreCase = true)) out[b.id] = b
        return out.values.toList()
    }

    fun isFavorite(id: String) = id in favorites
    fun isCustom(id: String) = custom.containsKey(id)
    fun isPinned(id: String) = id in pinned

    // ------------------------------------------------------------ Größen-/Deckkraftmarken (für die Seitenleiste)

    /** Gespeicherte Marken (0..1, bis zu 4) für Pinsel + Werkzeug; [opacity] = Deckkraft- statt Größenregler. */
    fun marks(brushId: String, tool: Tool, opacity: Boolean): FloatArray =
        marks[markKey(brushId, tool, opacity)]?.copyOf() ?: FloatArray(0)

    fun setMarks(brushId: String, tool: Tool, opacity: Boolean, values: FloatArray) {
        val k = markKey(brushId, tool, opacity)
        if (values.isEmpty()) marks.remove(k) else marks[k] = values.copyOf(minOf(values.size, 4)).also { it.sort() }
        changed()
    }

    private fun markKey(id: String, tool: Tool, opacity: Boolean) = "$id|${tool.name}|${if (opacity) "o" else "s"}"

    // ------------------------------------------------------------ Pinsel ändern

    fun toggleFavorite(id: String) {
        if (!favorites.remove(id)) favorites.add(id)
        changed()
    }

    /** Merkt einen Pinsel als zuletzt verwendet (max. 8, angeheftete bleiben oben). */
    fun markUsed(id: String) {
        if (recent.firstOrNull() == id) return
        recent.remove(id)
        recent.add(0, id)
        while (recent.size > MAX_RECENT * 2) recent.removeAt(recent.lastIndex)
        changed()
    }

    fun togglePin(id: String) {
        if (!pinned.remove(id)) pinned.add(id)
        changed()
    }

    /** Entfernt einen Pinsel nur aus „Zuletzt verwendet“. */
    fun removeRecent(id: String) {
        recent.remove(id); pinned.remove(id)
        changed()
    }

    /** Dupliziert einen Pinsel direkt unter dem Original. */
    fun duplicate(id: String): Brush? {
        val src = get(id) ?: return null
        val set = setOf(id)
        val copy = src.duplicate("${src.name} Kopie", set)
        copy.basedOn = src.basedOn ?: src.id
        custom[copy.id] = copy
        saveImages(copy)
        insertAfter(set, id, copy.id)
        changed()
        return copy
    }

    /** Benennt einen Pinsel um (mitgelieferte Pinsel: Anzeigename wird gemerkt). */
    fun renameBrush(id: String, name: String) {
        val n = name.trim().take(60).ifEmpty { return }
        custom[id]?.let { it.name = n; it.rev++; changed(); return }
        builtIn[id]?.let { it.name = n; nameOverrides[id] = n; changed() }
    }

    /** Löscht einen eigenen Pinsel. Eingebaute Pinsel können nicht gelöscht werden. */
    fun delete(id: String): Boolean {
        val b = custom.remove(id) ?: return false
        favorites.remove(id); recent.remove(id); pinned.remove(id)
        brushOrder.values.forEach { it.remove(id) }
        marks.keys.removeAll { it.startsWith("$id|") }
        deleteImages(b.id)
        previews.snapshot().keys.filter { it.startsWith("$id@") }.forEach { previews.remove(it) }
        changed()
        return true
    }

    /** Verschiebt einen Pinsel in ein anderes Set (optional an Position [index]). */
    fun moveBrush(id: String, toSet: String, index: Int = -1) {
        if (!setExists(toSet)) return
        val from = setOf(id)
        val c = custom[id]
        if (c != null) c.setId = toSet
        else if (builtIn.containsKey(id)) {
            if (builtIn.getValue(id).setId == toSet) placement.remove(id) else placement[id] = toSet
        } else return
        brushOrder[from]?.remove(id)
        val list = brushes(toSet).map { it.id }.filter { it != id }.toMutableList()
        list.add(if (index in 0..list.size) index else list.size, id)
        brushOrder[toSet] = list
        changed()
    }

    /** Kopiert einen Pinsel (als eigenen Pinsel) in ein anderes Set. */
    fun copyBrush(id: String, toSet: String): Brush? {
        val src = get(id) ?: return null
        if (!setExists(toSet)) return null
        val copy = src.duplicate(src.name, toSet)
        copy.basedOn = src.basedOn ?: src.id
        custom[copy.id] = copy
        saveImages(copy)
        changed()
        return copy
    }

    /** Neue Reihenfolge der Pinsel eines Sets. */
    fun reorderBrushes(setId: String, ids: List<String>) {
        if (setId == SET_RECENT) {
            // nur die angehefteten lassen sich umsortieren
            val p = ids.filter { it in pinned }
            pinned.clear(); pinned.addAll(p)
        } else if (setId == SET_FAVORITES) {
            favorites.clear(); favorites.addAll(ids)
        } else brushOrder[setId] = ids.toMutableList()
        changed()
    }

    private fun insertAfter(set: String, afterId: String, id: String) {
        val list = brushes(set).map { it.id }.filter { it != id }.toMutableList()
        val i = list.indexOf(afterId)
        list.add(if (i >= 0) i + 1 else list.size, id)
        brushOrder[set] = list
    }

    // ------------------------------------------------------------ Sets ändern

    /** Legt ein neues Set ganz oben an und gibt die ID zurück. */
    fun createSet(name: String, icon: String? = null): String {
        val id = "set_" + UUID.randomUUID().toString().take(8)
        userSets[id] = UserSet(name.trim().ifEmpty { "Unbenanntes Set" }.take(40), icon?.takeIf { it.isNotBlank() })
        setOrder.add(0, id)
        changed()
        return id
    }

    fun renameSet(id: String, name: String) {
        val n = name.trim().take(40).ifEmpty { return }
        userSets[id]?.let { it.name = n; changed(); return }
        if (presetSet(id) != null) { setNameOverride[id] = n; changed() }
    }

    /** Symbol (Emoji/Zeichen) festlegen; leer = zurück zum Standard. */
    fun setSetIcon(id: String, icon: String?) {
        val i = icon?.trim()?.takeIf { it.isNotEmpty() }
        userSets[id]?.let { it.icon = i; changed(); return }
        if (presetSet(id) != null) { if (i == null) setIconOverride.remove(id) else setIconOverride[id] = i; changed() }
    }

    /** Löscht ein eigenes Set samt seiner eigenen Pinsel; verschobene mitgelieferte Pinsel kehren zurück. */
    fun deleteSet(id: String): Boolean {
        if (!userSets.containsKey(id)) return false
        custom.values.filter { it.setId == id }.map { it.id }.forEach { bid ->
            custom.remove(bid)?.let { deleteImages(it.id) }
            favorites.remove(bid); recent.remove(bid); pinned.remove(bid)
        }
        placement.entries.removeAll { it.value == id }
        userSets.remove(id); setOrder.remove(id); brushOrder.remove(id)
        changed()
        return true
    }

    /** Dupliziert ein Set (Kopie mit Nummer direkt darunter). Gibt die neue ID zurück. */
    fun duplicateSet(id: String): String? {
        if (!setExists(id)) return null
        val base = setName(id)
        var n = 1
        val names = setOrder.map { setName(it) }.toSet()
        while ("$base $n" in names) n++
        val nid = "set_" + UUID.randomUUID().toString().take(8)
        userSets[nid] = UserSet("$base $n", userSets[id]?.icon ?: setIconOverride[id])
        setOrder.add(setOrder.indexOf(id) + 1, nid)
        val order = ArrayList<String>()
        for (b in brushes(id)) {
            val c = b.duplicate(b.name, nid)
            c.basedOn = b.basedOn ?: b.id
            custom[c.id] = c
            saveImages(c)
            order.add(c.id)
        }
        brushOrder[nid] = order
        changed()
        return nid
    }

    /** Neue Reihenfolge der (nicht virtuellen) Sets. */
    fun reorderSets(ids: List<String>) {
        val valid = ids.filter { setExists(it) }
        setOrder.clear(); setOrder.addAll(valid)
        normalize()
        changed()
    }

    // ------------------------------------------------------------ Studio

    /**
     * Speichert die Arbeitskopie aus dem Studio. Eingebaute Pinsel werden nie verändert – stattdessen entsteht
     * automatisch eine eigene Kopie direkt darunter. Gibt den gespeicherten Pinsel zurück.
     */
    fun save(work: Brush): Brush {
        val existing = custom[work.id]
        val target: Brush = if (existing != null) {
            existing.name = work.name.ifBlank { existing.name }
            existing.setId = work.setId
            existing.copySettingsFrom(work)
            existing.author = work.author
            existing.signature.clear(); existing.signature.addAll(work.signature.map { it.copyOf() })
            existing.resetPoint = work.resetPoint; existing.resetPointTime = work.resetPointTime
            existing.rev++
            existing
        } else if (builtIn.containsKey(work.id)) {
            val orig = builtIn.getValue(work.id)
            val set = setOf(orig.id)
            val c = work.duplicate(if (work.name == orig.name) "${orig.name} (eigen)" else work.name, set)
            c.basedOn = orig.id
            custom[c.id] = c
            insertAfter(set, orig.id, c.id)
            c
        } else {
            val set = if (setExists(work.setId)) work.setId else ensureCustomSet()
            val c = work.workingCopy()
            val n = Brush(c.id, work.name.ifBlank { "Neuer Pinsel" }, set, false, c.v, work.basedOn)
            n.copySettingsFrom(work)
            n.author = work.author; n.signature.addAll(work.signature.map { it.copyOf() })
            n.resetPoint = work.resetPoint; n.resetPointTime = work.resetPointTime
            custom[n.id] = n
            n
        }
        saveImages(target)
        changed()
        return target
    }

    /** Neuer, leerer Pinsel (noch nicht gespeichert) für das Studio, im Set [setId]. */
    fun newBrush(setId: String? = null): Brush {
        val set = setId?.takeIf { setExists(it) } ?: SET_CUSTOM
        return Brush(UUID.randomUUID().toString(), "Neuer Pinsel", set, false)
    }

    /** Ursprüngliche Werte (für "Zurücksetzen"): Original bzw. Vorlage. */
    fun original(b: Brush): Brush? = builtIn[b.id]?.let { o ->
        // mitgelieferte Pinsel: frisch aus den Vorgaben (Name evtl. überschrieben)
        BrushPresets.sets.flatMap { it.brushes }.firstOrNull { it.id == o.id }
    } ?: b.basedOn?.let { builtIn[it] ?: custom[it] }

    /** Name eines mitgelieferten Pinsels ab Werk. */
    fun factoryName(id: String): String? = builtInNames[id]

    private fun ensureCustomSet(): String {
        if (!userSets.containsKey(SET_CUSTOM)) {
            userSets[SET_CUSTOM] = UserSet("Eigene Pinsel", null)
            setOrder.add(0, SET_CUSTOM)
        }
        return SET_CUSTOM
    }

    // ------------------------------------------------------------ Teilen / Import

    /** Teilt einen Pinsel als `.fwbrush`-Datei. */
    fun share(id: String) {
        val b = get(id) ?: return
        val f = writeArchive(listOf(b), null, null, "brush", safeName(b.name) + ".fwbrush")
        shareFile(f, "Pinsel teilen", b.name)
    }

    /** Teilt ein ganzes Set als `.fwbrushset`-Datei. */
    fun shareSet(setId: String) {
        val list = brushes(setId)
        if (list.isEmpty()) return
        val name = setName(setId)
        val f = writeArchive(list, name, setIcon(setId), "set", safeName(name) + ".fwbrushset")
        shareFile(f, "Pinselset teilen", name)
    }

    private fun shareFile(f: File, title: String, subject: String) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun safeName(n: String) = n.replace(Regex("[^A-Za-z0-9ÄÖÜäöüß _-]"), "_").ifBlank { "Pinsel" }

    private fun writeArchive(list: List<Brush>, setName: String?, icon: String?, type: String, fileName: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, fileName)
        ZipOutputStream(f.outputStream().buffered()).use { zip ->
            val man = JSONObject().put("farbwerkBrushes", 2).put("type", type)
            setName?.let { man.put("name", it) }
            icon?.let { man.put("icon", it) }
            man.put("brushes", JSONArray().also { a -> list.forEach { a.put(it.toJson()) } })
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(man.toString(1).toByteArray()); zip.closeEntry()
            for (b in list) {
                b.shapeImage?.let { zip.putNextEntry(ZipEntry("img/${b.id}_shape.png")); zip.write(png(it)); zip.closeEntry() }
                b.grainImage?.let { zip.putNextEntry(ZipEntry("img/${b.id}_grain.png")); zip.write(png(it)); zip.closeEntry() }
            }
        }
        return f
    }

    /**
     * Importiert eine Pinsel-/Set-Datei (`.fwbrush`, `.fwbrushset`) oder geteilten JSON-Text.
     * Einzelpinsel landen im Set „Importiert“, Sets werden oben eingefügt. Gibt eine Meldung zurück.
     */
    fun importUri(uri: Uri): String = try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("leer")
        importBytes(bytes)
    } catch (t: Throwable) {
        Log.w("Farbwerk", "Pinsel-Import fehlgeschlagen", t); "Import fehlgeschlagen"
    }

    fun importBytes(bytes: ByteArray): String {
        // ZIP?
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            var manifest: JSONObject? = null
            val images = HashMap<String, Bitmap>()
            ZipInputStream(bytes.inputStream()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val data = zin.readBytes()
                    when {
                        e.name == "manifest.json" -> manifest = JSONObject(String(data))
                        e.name.startsWith("img/") && e.name.endsWith(".png") ->
                            BitmapFactory.decodeByteArray(data, 0, data.size)?.let { images[e.name.removePrefix("img/").removeSuffix(".png")] = toAlpha(it) }
                    }
                }
            }
            val m = manifest ?: return "Keine Pinsel gefunden"
            return importManifest(m, images)
        }
        val o = JSONObject(String(bytes))
        if (o.has("brushes")) return importManifest(o, emptyMap())
        val b = Brush.fromJson(o.optJSONObject("brush") ?: o, forceNewId = true)
        addImported(listOf(b), null, null)
        return "„${b.name}“ importiert"
    }

    /** Importiert einen geteilten Pinsel (JSON-Text). */
    fun import(text: String): Brush? = try {
        val o = JSONObject(text)
        val b = Brush.fromJson(o.optJSONObject("brush") ?: o, forceNewId = true)
        addImported(listOf(b), null, null)
        b
    } catch (t: Throwable) {
        Log.w("Farbwerk", "Pinsel-Import fehlgeschlagen", t); null
    }

    private fun importManifest(m: JSONObject, images: Map<String, Bitmap>): String {
        val arr = m.optJSONArray("brushes") ?: return "Keine Pinsel gefunden"
        val list = ArrayList<Brush>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val oldId = o.optString("id")
            val b = Brush.fromJson(o, forceNewId = true)
            b.shapeImage = images["${oldId}_shape"]
            b.grainImage = images["${oldId}_grain"]
            list.add(b)
        }
        if (list.isEmpty()) return "Keine Pinsel gefunden"
        val isSet = m.optString("type") == "set"
        addImported(list, if (isSet) m.optString("name", "Importiertes Set") else null, m.optString("icon").ifBlank { null })
        return if (isSet) "Set „${m.optString("name")}“ importiert" else if (list.size == 1) "„${list[0].name}“ importiert"
        else "${list.size} Pinsel importiert"
    }

    private fun addImported(list: List<Brush>, setName: String?, icon: String?) {
        val set = if (setName != null) {
            val id = "set_" + UUID.randomUUID().toString().take(8)
            userSets[id] = UserSet(setName, icon); setOrder.add(0, id); id
        } else {
            if (!userSets.containsKey(SET_IMPORTED)) { userSets[SET_IMPORTED] = UserSet("Importiert", null); setOrder.add(0, SET_IMPORTED) }
            SET_IMPORTED
        }
        val order = brushOrder.getOrPut(set) { brushes(set).map { it.id }.toMutableList() }
        for (b in list) {
            b.setId = set; b.basedOn = null
            custom[b.id] = b
            saveImages(b)
            order.add(b.id)
        }
        changed()
    }

    private fun toAlpha(b: Bitmap): Bitmap {
        if (b.config == Bitmap.Config.ALPHA_8) return b
        // PNG wurde als Graustufen/ARGB gelesen: Helligkeit bzw. Alpha als Wert
        val n = b.width * b.height
        val px = IntArray(n)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val opaque = px.all { Color.alpha(it) == 255 }
        val v = FloatArray(n) {
            val q = px[it]
            if (opaque) Color.red(q) / 255f else Color.alpha(q) / 255f
        }
        return BrushImages.fromFloats(v, b.width, b.height)
    }

    private fun png(alpha8: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        BrushImages.grayscale(alpha8).compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    // ------------------------------------------------------------ Vorschau

    private fun previewKey(b: Brush, w: Int, h: Int) =
        "${b.id}@${b.rev}@${w}x$h@${b.v.contentHashCode()}@${b.couplings.contentHashCode()}@" +
            "${b.pressureCurve?.contentHashCode()}@${System.identityHashCode(b.shapeImage)}@${System.identityHashCode(b.grainImage)}"

    /**
     * Strichprobe als Bitmap (weiß auf transparent – beim Anzeigen einfärben). Gecacht pro Pinsel-Stand.
     * Darf im Hintergrund aufgerufen werden.
     */
    fun preview(b: Brush, w: Int = 420, h: Int = 96): Bitmap {
        val key = previewKey(b, w, h)
        synchronized(previews) { previews.get(key)?.let { return it } }
        val bmp = renderPreview(b, w, h)
        synchronized(previews) { previews.put(key, bmp) }
        return bmp
    }

    fun cachedPreview(b: Brush, w: Int = 420, h: Int = 96): Bitmap? =
        synchronized(previews) { previews.get(previewKey(b, w, h)) }

    private fun renderPreview(b: Brush, w: Int, h: Int): Bitmap {
        val pad = BrushPad(w, h)
        pad.secondaryColor = Color.GRAY
        val d = (h * (0.08f + 0.5f * b[BP.PREVIEW_SIZE])).coerceAtMost(b[BP.MAX_SIZE].coerceAtLeast(3f) * 2f)
        if (b.on(BP.STAMP_PREVIEW)) pad.stampPreview(b, h * (0.3f + 0.6f * b[BP.PREVIEW_SIZE]), Color.WHITE)
        else pad.sampleStroke(b, d, Color.WHITE, usePreviewSettings = true)
        val out = pad.base.copy(Bitmap.Config.ARGB_8888, false)
        pad.release()
        return out
    }

    // ------------------------------------------------------------ Speichern / Laden

    private fun changed() {
        version++
        persist()
    }

    private fun imgFile(id: String, kind: String) = File(imgDir, "${id}_$kind.png")

    private fun saveImages(b: Brush) {
        try {
            imgDir.mkdirs()
            for ((kind, img) in listOf("shape" to b.shapeImage, "grain" to b.grainImage)) {
                val f = imgFile(b.id, kind)
                if (img == null) { f.delete(); continue }
                f.writeBytes(png(img))
            }
        } catch (t: Throwable) {
            Log.w("Farbwerk", "Pinselbild speichern fehlgeschlagen", t)
        }
    }

    private fun deleteImages(id: String) {
        imgFile(id, "shape").delete(); imgFile(id, "grain").delete()
    }

    private fun loadImages(b: Brush) {
        for (kind in listOf("shape", "grain")) {
            val f = imgFile(b.id, kind)
            if (!f.exists()) continue
            val bmp = BitmapFactory.decodeFile(f.path)?.let { toAlpha(it) } ?: continue
            if (kind == "shape") b.shapeImage = bmp else b.grainImage = bmp
        }
    }

    private fun persist() {
        try {
            val o = JSONObject()
            o.put("version", 2)
            o.put("custom", JSONArray().also { a -> custom.values.forEach { a.put(it.toJson()) } })
            o.put("favorites", JSONArray(favorites.toList()))
            o.put("recent", JSONArray(recent))
            o.put("pinned", JSONArray(pinned.toList()))
            o.put("sets", JSONArray().also { a ->
                userSets.forEach { (id, s) -> a.put(JSONObject().put("id", id).put("name", s.name).also { j -> s.icon?.let { j.put("icon", it) } }) }
            })
            o.put("setOrder", JSONArray(setOrder))
            o.put("setNames", JSONObject(setNameOverride as Map<*, *>))
            o.put("setIcons", JSONObject(setIconOverride as Map<*, *>))
            o.put("placement", JSONObject(placement as Map<*, *>))
            o.put("names", JSONObject(nameOverrides as Map<*, *>))
            o.put("brushOrder", JSONObject().also { j -> brushOrder.forEach { (k, v) -> j.put(k, JSONArray(v)) } })
            o.put("marks", JSONObject().also { j ->
                marks.forEach { (k, v) -> j.put(k, JSONArray().also { a -> v.forEach { x -> a.put(x.toDouble()) } }) }
            })
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (t: Throwable) {
            Log.w("Farbwerk", "Pinsel speichern fehlgeschlagen", t)
        }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val o = JSONObject(file.readText())
            o.optJSONArray("sets")?.let { a ->
                for (i in 0 until a.length()) {
                    val j = a.getJSONObject(i)
                    userSets[j.getString("id")] = UserSet(j.optString("name", "Set"), j.optString("icon").ifBlank { null })
                }
            }
            o.optJSONArray("custom")?.let { a ->
                for (i in 0 until a.length()) {
                    val b = Brush.fromJson(a.getJSONObject(i))
                    loadImages(b)
                    custom[b.id] = b
                }
            }
            fun strings(name: String, into: MutableCollection<String>) =
                o.optJSONArray(name)?.let { a -> for (i in 0 until a.length()) into.add(a.getString(i)) }
            strings("favorites", favorites); strings("recent", recent); strings("pinned", pinned); strings("setOrder", setOrder)
            fun map(name: String, into: MutableMap<String, String>) = o.optJSONObject(name)?.let { j ->
                j.keys().forEach { k -> into[k] = j.getString(k) }
            }
            map("setNames", setNameOverride); map("setIcons", setIconOverride); map("placement", placement); map("names", nameOverrides)
            o.optJSONObject("brushOrder")?.let { j ->
                j.keys().forEach { k -> val a = j.getJSONArray(k); brushOrder[k] = MutableList(a.length()) { a.getString(it) } }
            }
            o.optJSONObject("marks")?.let { j ->
                j.keys().forEach { k -> val a = j.getJSONArray(k); marks[k] = FloatArray(a.length()) { a.getDouble(it).toFloat() } }
            }
            nameOverrides.forEach { (id, n) -> builtIn[id]?.name = n }
        } catch (t: Throwable) {
            Log.w("Farbwerk", "Pinsel laden fehlgeschlagen", t)
        }
    }

    /** Konsistenz: verwaiste Verweise entfernen, fehlende Sets ergänzen. */
    private fun normalize() {
        // eigene Pinsel ohne gültiges Set → "Eigene Pinsel"
        for (b in custom.values) if (!setExists(b.setId)) b.setId = ensureCustomSet()
        if (custom.values.any { it.setId == SET_CUSTOM }) ensureCustomSet()
        placement.entries.removeAll { !setExists(it.value) || !builtIn.containsKey(it.key) }
        setOrder.retainAll { setExists(it) }
        val seen = LinkedHashSet(setOrder)
        setOrder.clear(); setOrder.addAll(seen)
        for (id in userSets.keys) if (id !in setOrder) setOrder.add(0, id)
        for (s in BrushPresets.sets) if (s.id !in setOrder) setOrder.add(s.id)
        favorites.retainAll { get(it) != null }
        recent.retainAll { get(it) != null }
        pinned.retainAll { get(it) != null }
    }

    companion object {
        const val SET_RECENT = "_recent"
        const val SET_FAVORITES = "_favorites"
        const val SET_CUSTOM = "eigene"
        const val SET_IMPORTED = "importiert"
        const val MAX_RECENT = 8

        /** Erstes Emoji im Text (für Set-Symbole ohne eigenes Symbol). */
        fun firstEmoji(s: String): String? {
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val n = Character.charCount(cp)
                val emoji = cp >= 0x1F000 || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF
                if (emoji) {
                    // inkl. Variationsselektor / ZWJ-Sequenzen grob mitnehmen
                    var end = i + n
                    while (end < s.length) {
                        val c2 = s.codePointAt(end)
                        if (c2 == 0xFE0F || c2 == 0x200D || c2 in 0x1F3FB..0x1F3FF || (s.codePointAt(end - 1) == 0x200D)) end += Character.charCount(c2)
                        else break
                    }
                    return s.substring(i, end)
                }
                i += n
            }
            return null
        }
    }
}
