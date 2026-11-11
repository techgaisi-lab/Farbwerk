package at.techgaisi.farbwerk.brush

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import at.techgaisi.farbwerk.model.Tool
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Ein Pinselset in der linken Spalte der Bibliothek. [virtual] = automatisch gefüllt (Zuletzt, Favoriten). */
data class BrushSet(val id: String, val name: String, val builtIn: Boolean, val virtual: Boolean = false)

/**
 * Pinselbibliothek: mitgelieferte Sets, eigene Pinsel (JSON in filesDir), Favoriten, zuletzt verwendet,
 * Duplizieren/Löschen/Teilen und gecachte Strichvorschauen.
 */
class BrushLibrary(private val context: Context) {

    /** Erhöht sich bei jeder Änderung (Compose liest das). */
    var version by mutableIntStateOf(0)
        private set

    private val builtIn = LinkedHashMap<String, Brush>()
    private val custom = LinkedHashMap<String, Brush>()
    private val favorites = LinkedHashSet<String>()
    private val recent = ArrayList<String>()
    private val file = File(context.filesDir, "brushes.json")
    private val previews = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    init {
        for (s in BrushPresets.sets) for (b in s.brushes) builtIn[b.id] = b
        load()
    }

    // ------------------------------------------------------------ Abfragen

    fun defaultBrushId(tool: Tool): String = when (tool) {
        Tool.SMUDGE -> "airbrush_weich"
        Tool.ERASE -> "airbrush_hart"
        else -> "studiotinte"
    }

    fun get(id: String): Brush? = custom[id] ?: builtIn[id]

    fun getOrDefault(id: String, tool: Tool): Brush = get(id) ?: builtIn.getValue(defaultBrushId(tool))

    /** Alle Sets in Anzeigereihenfolge. */
    fun sets(): List<BrushSet> {
        val out = ArrayList<BrushSet>()
        if (recent.isNotEmpty()) out.add(BrushSet(SET_RECENT, "Zuletzt verwendet", builtIn = true, virtual = true))
        if (favorites.isNotEmpty()) out.add(BrushSet(SET_FAVORITES, "Favoriten", builtIn = true, virtual = true))
        if (custom.values.any { it.setId == SET_CUSTOM }) out.add(BrushSet(SET_CUSTOM, "Eigene Pinsel", builtIn = false))
        for (s in BrushPresets.sets) out.add(BrushSet(s.id, s.name, builtIn = true))
        return out
    }

    /** Pinsel eines Sets (eingebaute zuerst, eigene Kopien direkt nach ihrem Original). */
    fun brushes(setId: String): List<Brush> = when (setId) {
        SET_RECENT -> recent.mapNotNull { get(it) }
        SET_FAVORITES -> favorites.mapNotNull { get(it) }
        SET_CUSTOM -> custom.values.filter { it.setId == SET_CUSTOM }
        else -> {
            val list = ArrayList<Brush>()
            val preset = BrushPresets.sets.firstOrNull { it.id == setId }
            val mine = custom.values.filter { it.setId == setId }
            preset?.brushes?.forEach { b ->
                list.add(b)
                mine.filter { it.basedOn == b.id }.forEach { list.add(it) }
            }
            mine.filter { m -> list.none { it.id == m.id } }.forEach { list.add(it) }
            list
        }
    }

    /** Set, in dem ein Pinsel angezeigt wird. */
    fun setOf(id: String): String = get(id)?.setId ?: BrushPresets.sets.first().id

    fun isFavorite(id: String) = id in favorites
    fun isCustom(id: String) = custom.containsKey(id)

    // ------------------------------------------------------------ Änderungen

    fun toggleFavorite(id: String) {
        if (!favorites.remove(id)) favorites.add(id)
        changed()
    }

    /** Merkt einen Pinsel als zuletzt verwendet (max. 12). */
    fun markUsed(id: String) {
        if (recent.firstOrNull() == id) return
        recent.remove(id)
        recent.add(0, id)
        while (recent.size > 12) recent.removeAt(recent.lastIndex)
        changed()
    }

    /** Dupliziert einen Pinsel in dasselbe Set (bzw. "Eigene Pinsel" bei virtuellen Sets). */
    fun duplicate(id: String): Brush? {
        val src = get(id) ?: return null
        val copy = src.duplicate("${src.name} Kopie")
        custom[copy.id] = copy
        changed()
        return copy
    }

    /** Löscht einen eigenen Pinsel. Eingebaute Pinsel können nicht gelöscht werden. */
    fun delete(id: String): Boolean {
        if (custom.remove(id) == null) return false
        favorites.remove(id); recent.remove(id)
        previews.snapshot().keys.filter { it.startsWith("$id@") }.forEach { previews.remove(it) }
        changed()
        return true
    }

    /**
     * Speichert die Arbeitskopie aus dem Studio. Eingebaute Pinsel werden nie verändert – stattdessen entsteht
     * automatisch eine eigene Kopie. Gibt den gespeicherten Pinsel zurück.
     */
    fun save(work: Brush): Brush {
        val existing = custom[work.id]
        val target: Brush = if (existing != null) {
            existing.name = work.name.ifBlank { existing.name }
            existing.setId = work.setId
            work.v.copyInto(existing.v)
            existing.rev++
            existing
        } else if (builtIn.containsKey(work.id)) {
            val orig = builtIn.getValue(work.id)
            val c = Brush(java.util.UUID.randomUUID().toString(),
                if (work.name == orig.name) "${orig.name} (eigen)" else work.name,
                orig.setId, false, work.v.copyOf(), orig.id)
            custom[c.id] = c
            c
        } else {
            val c = Brush(work.id, work.name.ifBlank { "Neuer Pinsel" }, work.setId, false, work.v.copyOf(), work.basedOn)
            custom[c.id] = c
            c
        }
        changed()
        return target
    }

    /** Neuer, leerer Pinsel (noch nicht gespeichert) für das Studio. */
    fun newBrush(): Brush = Brush(java.util.UUID.randomUUID().toString(), "Neuer Pinsel", SET_CUSTOM, false)

    /** Ursprüngliche Werte (für "Zurücksetzen"): Original bzw. Vorlage. */
    fun original(b: Brush): Brush? = builtIn[b.id] ?: b.basedOn?.let { builtIn[it] ?: custom[it] }

    /** Teilt einen Pinsel als JSON-Text (z. B. per Messenger oder Mail). */
    fun share(id: String) {
        val b = get(id) ?: return
        val json = JSONObject().put("farbwerkBrush", 1).put("brush", b.toJson()).toString(2)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Pinsel: ${b.name}")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        context.startActivity(Intent.createChooser(send, "Pinsel teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Importiert einen geteilten Pinsel (JSON-Text). */
    fun import(text: String): Brush? = try {
        val o = JSONObject(text)
        val b = Brush.fromJson(o.optJSONObject("brush") ?: o, forceNewId = true)
        b.setId = SET_CUSTOM
        custom[b.id] = b
        changed()
        b
    } catch (t: Throwable) {
        Log.w("Farbwerk", "Pinsel-Import fehlgeschlagen", t); null
    }

    // ------------------------------------------------------------ Vorschau

    /**
     * Strichprobe als Bitmap (weiß auf transparent – beim Anzeigen einfärben). Gecacht pro Pinsel-Revision.
     * Darf im Hintergrund aufgerufen werden.
     */
    fun preview(b: Brush, w: Int = 420, h: Int = 96): Bitmap {
        val key = "${b.id}@${b.rev}@${w}x$h@${b.v.contentHashCode()}"
        synchronized(previews) { previews.get(key)?.let { return it } }
        val bmp = renderPreview(b, w, h)
        synchronized(previews) { previews.put(key, bmp) }
        return bmp
    }

    fun cachedPreview(b: Brush, w: Int = 420, h: Int = 96): Bitmap? =
        synchronized(previews) { previews.get("${b.id}@${b.rev}@${w}x$h@${b.v.contentHashCode()}") }

    private fun renderPreview(b: Brush, w: Int, h: Int): Bitmap {
        val pad = BrushPad(w, h)
        val d = (h * (0.08f + 0.5f * b[BP.PREVIEW_SIZE])).coerceAtMost(b[BP.MAX_SIZE].coerceAtLeast(3f) * 2f)
        pad.sampleStroke(b, d, Color.WHITE)
        val out = pad.base.copy(Bitmap.Config.ARGB_8888, false)
        pad.release()
        return out
    }

    // ------------------------------------------------------------ Speichern / Laden

    private fun changed() {
        version++
        persist()
    }

    private fun persist() {
        try {
            val o = JSONObject()
            o.put("version", 1)
            o.put("custom", JSONArray().also { a -> custom.values.forEach { a.put(it.toJson()) } })
            o.put("favorites", JSONArray(favorites.toList()))
            o.put("recent", JSONArray(recent))
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
            o.optJSONArray("custom")?.let { a ->
                for (i in 0 until a.length()) {
                    val b = Brush.fromJson(a.getJSONObject(i))
                    custom[b.id] = b
                }
            }
            o.optJSONArray("favorites")?.let { a -> for (i in 0 until a.length()) favorites.add(a.getString(i)) }
            o.optJSONArray("recent")?.let { a -> for (i in 0 until a.length()) recent.add(a.getString(i)) }
            favorites.retainAll { get(it) != null }
            recent.retainAll { get(it) != null }
        } catch (t: Throwable) {
            Log.w("Farbwerk", "Pinsel laden fehlgeschlagen", t)
        }
    }

    companion object {
        const val SET_RECENT = "_recent"
        const val SET_FAVORITES = "_favorites"
        const val SET_CUSTOM = "eigene"
    }
}
