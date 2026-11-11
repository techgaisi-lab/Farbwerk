package at.techgaisi.farbwerk.color

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Eine Farbpalette mit [Palette.SLOTS] Feldern; `null` = leeres Feld. */
class Palette(
    name: String,
    colors: List<Int?> = emptyList(),
    val id: String = UUID.randomUUID().toString(),
) {
    var name by mutableStateOf(name)
    val colors: SnapshotStateList<Int?> = mutableStateListOf<Int?>().apply {
        addAll(colors.take(SLOTS))
        while (size < SLOTS) add(null)
    }

    companion object { const val SLOTS = 30 }
}

/**
 * Verwaltet alle Paletten (Compose-State) und speichert sie als JSON in `filesDir/palettes.json`.
 * Eine Instanz pro Prozess über [get].
 */
class PaletteStore private constructor(context: Context) {
    private val file = File(context.filesDir, "palettes.json")

    val palettes = mutableStateListOf<Palette>()
    var defaultId by mutableStateOf<String?>(null)
        private set
    /** true = kompakte Ansicht, false = Kartenansicht. */
    var compactView by mutableStateOf(true)
        private set

    val defaultPalette: Palette? get() = palettes.firstOrNull { it.id == defaultId } ?: palettes.firstOrNull()

    init {
        load()
        if (palettes.isEmpty()) {
            palettes += Palette("Standard", BUILTIN_DEFAULT)
            palettes += Palette("Graustufen", (0 until 10).map {
                val v = (it * 255 / 9); ColorMath.argb(v, v, v)
            })
            defaultId = palettes.first().id
            save()
        }
    }

    // ---------------------------------------------------------------- Operationen

    fun create(name: String = "Neue Palette", colors: List<Int?> = emptyList()): Palette {
        val p = Palette(uniqueName(name), colors)
        palettes.add(0, p)
        save()
        return p
    }

    fun rename(p: Palette, name: String) {
        p.name = name.trim().ifEmpty { "Ohne Titel" }
        save()
    }

    fun delete(p: Palette) {
        palettes.remove(p)
        if (defaultId == p.id) defaultId = palettes.firstOrNull()?.id
        save()
    }

    fun duplicate(p: Palette): Palette {
        val c = Palette(uniqueName(p.name), p.colors.toList())
        val i = palettes.indexOf(p)
        palettes.add(if (i < 0) 0 else i + 1, c)
        save()
        return c
    }

    fun setDefault(p: Palette) { defaultId = p.id; save() }

    fun setCompact(compact: Boolean) { compactView = compact; save() }

    fun setSlot(p: Palette, index: Int, color: Int?) {
        if (index !in 0 until Palette.SLOTS) return
        p.colors[index] = color?.let { ColorMath.opaque(it) }
        save()
    }

    /** Speichert [color] im ersten freien Feld der Palette. @return false, wenn voll. */
    fun addToFirstFree(p: Palette, color: Int): Boolean {
        val i = p.colors.indexOfFirst { it == null }
        if (i < 0) return false
        setSlot(p, i, color)
        return true
    }

    private fun uniqueName(base: String): String {
        if (palettes.none { it.name == base }) return base
        var n = 2
        while (palettes.any { it.name == "$base $n" }) n++
        return "$base $n"
    }

    // ---------------------------------------------------------------- Persistenz

    fun save() {
        try {
            val root = JSONObject()
            root.put("version", 1)
            root.put("default", defaultId ?: JSONObject.NULL)
            root.put("compact", compactView)
            val arr = JSONArray()
            for (p in palettes) {
                val o = JSONObject()
                o.put("id", p.id)
                o.put("name", p.name)
                val cs = JSONArray()
                p.colors.forEach { cs.put(it?.let { c -> "#" + ColorMath.toHex(c) } ?: JSONObject.NULL) }
                o.put("colors", cs)
                arr.put(o)
            }
            root.put("palettes", arr)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) { file.writeText(root.toString()); tmp.delete() }
        } catch (t: Throwable) {
            Log.e("Farbwerk", "Paletten speichern fehlgeschlagen", t)
        }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("palettes") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val cs = o.optJSONArray("colors") ?: JSONArray()
                val colors = (0 until cs.length()).map { j ->
                    if (cs.isNull(j)) null else ColorMath.parseHex(cs.getString(j))
                }
                palettes += Palette(o.optString("name", "Palette"), colors, o.optString("id", UUID.randomUUID().toString()))
            }
            defaultId = if (root.isNull("default")) palettes.firstOrNull()?.id else root.optString("default")
            compactView = root.optBoolean("compact", true)
        } catch (t: Throwable) {
            Log.e("Farbwerk", "Paletten laden fehlgeschlagen", t)
        }
    }

    companion object {
        @Volatile private var instance: PaletteStore? = null

        fun get(context: Context): PaletteStore =
            instance ?: synchronized(this) {
                instance ?: PaletteStore(context.applicationContext).also { instance = it }
            }

        /** Eigene Auswahl an Grundfarben für die Standard-Palette (3 Reihen à 10). */
        private val BUILTIN_DEFAULT: List<Int> = listOf(
            // kräftig
            0xFF111111, 0xFFFFFFFF, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835,
            0xFF43A047, 0xFF00ACC1, 0xFF1E88E5, 0xFF5E35B1, 0xFFD81B60,
            // Hauttöne & Erdtöne
            0xFFFFE0CC, 0xFFF1C27D, 0xFFE0AC69, 0xFFC68642, 0xFF8D5524,
            0xFF5C3A21, 0xFF6D4C41, 0xFFA1887F, 0xFF7B8D42, 0xFF3E5641,
            // gedeckte / Pastelltöne
            0xFFF8BBD0, 0xFFFFCCBC, 0xFFFFF59D, 0xFFC5E1A5, 0xFFB2EBF2,
            0xFFBBDEFB, 0xFFD1C4E9, 0xFF546E7A, 0xFF37474F, 0xFF9E9E9E,
        ).map { it.toInt() }
    }
}
