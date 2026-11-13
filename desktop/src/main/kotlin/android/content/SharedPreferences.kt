@file:Suppress("unused")

package android.content

import org.json.JSONArray
import java.io.File
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList

interface SharedPreferences {
    fun getAll(): Map<String, *>
    fun getString(key: String, defValue: String?): String?
    fun getStringSet(key: String, defValues: Set<String>?): Set<String>?
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun getFloat(key: String, defValue: Float): Float
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun contains(key: String): Boolean
    fun edit(): Editor
    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener)
    fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener)

    fun interface OnSharedPreferenceChangeListener {
        fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?)
    }

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putStringSet(key: String, values: Set<String>?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun commit(): Boolean
        fun apply()
    }
}

/**
 * SharedPreferences, gespeichert als Properties-Datei. Werte werden typisiert abgelegt
 * (Präfix s:/i:/l:/f:/b:/S: für String, Int, Long, Float, Boolean, String-Set).
 */
class PropertiesSharedPreferences(private val file: File) : SharedPreferences {
    private val values = LinkedHashMap<String, Any?>()
    private val listeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    init { load() }

    private fun load() {
        if (!file.isFile) return
        try {
            val p = Properties()
            file.reader(Charsets.UTF_8).use { p.load(it) }
            for (name in p.stringPropertyNames()) {
                val raw = p.getProperty(name) ?: continue
                if (raw.length < 2 || raw[1] != ':') { values[name] = raw; continue }
                val v = raw.substring(2)
                values[name] = when (raw[0]) {
                    'i' -> v.toIntOrNull()
                    'l' -> v.toLongOrNull()
                    'f' -> v.toFloatOrNull()
                    'b' -> v == "true"
                    'S' -> JSONArray(v).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    else -> v
                }
            }
        } catch (t: Throwable) {
            System.err.println("Einstellungen konnten nicht gelesen werden: ${file.path}: $t")
        }
    }

    @Synchronized private fun store() {
        val p = Properties()
        for ((k, v) in values) {
            val enc = when (v) {
                null -> continue
                is Int -> "i:$v"
                is Long -> "l:$v"
                is Float -> "f:$v"
                is Boolean -> "b:$v"
                is Set<*> -> "S:" + JSONArray(v.map { it.toString() }).toString()
                else -> "s:$v"
            }
            p.setProperty(k, enc)
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writer(Charsets.UTF_8).use { p.store(it, "Farbwerk") }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    @Synchronized override fun getAll(): Map<String, *> = LinkedHashMap(values)
    @Synchronized override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    @Synchronized override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = values[key] as? Set<String> ?: defValues
    @Synchronized override fun getInt(key: String, defValue: Int): Int = (values[key] as? Number)?.toInt() ?: (values[key] as? String)?.toIntOrNull() ?: defValue
    @Synchronized override fun getLong(key: String, defValue: Long): Long = (values[key] as? Number)?.toLong() ?: (values[key] as? String)?.toLongOrNull() ?: defValue
    @Synchronized override fun getFloat(key: String, defValue: Float): Float = (values[key] as? Number)?.toFloat() ?: (values[key] as? String)?.toFloatOrNull() ?: defValue
    @Synchronized override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    @Synchronized override fun contains(key: String): Boolean = values.containsKey(key)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.add(listener) }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.remove(listener) }

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    private inner class EditorImpl : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any?>()
        private val removed = LinkedHashSet<String>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { changes[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { removed.add(key); changes.remove(key) }
        override fun clear() = apply { clear = true }

        override fun commit(): Boolean {
            val changedKeys: Set<String>
            synchronized(this@PropertiesSharedPreferences) {
                if (clear) values.clear()
                removed.forEach { values.remove(it) }
                for ((k, v) in changes) if (v == null) values.remove(k) else values[k] = v
                changedKeys = removed + changes.keys
                try { store() } catch (t: Throwable) { System.err.println("Einstellungen konnten nicht gespeichert werden: $t"); return false }
            }
            for (k in changedKeys) listeners.forEach { it.onSharedPreferenceChanged(this@PropertiesSharedPreferences, k) }
            return true
        }

        override fun apply() { commit() }
    }
}
