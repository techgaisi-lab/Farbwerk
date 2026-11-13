package android.database

import java.io.Closeable

/** Minimaler, speicherbasierter Cursor (für ContentResolver.query auf Dateien). */
class Cursor(private val columns: Array<String>, private val rows: List<List<Any?>>) : Closeable {
    private var pos = -1
    val count: Int get() = rows.size
    val columnCount: Int get() = columns.size
    val position: Int get() = pos
    fun moveToFirst(): Boolean { pos = 0; return rows.isNotEmpty() }
    fun moveToNext(): Boolean { pos++; return pos < rows.size }
    fun moveToPosition(p: Int): Boolean { pos = p; return p in rows.indices }
    fun getColumnIndex(name: String): Int = columns.indexOf(name)
    fun getColumnIndexOrThrow(name: String): Int = getColumnIndex(name).also { require(it >= 0) { "Spalte $name fehlt" } }
    fun getColumnName(i: Int): String = columns[i]
    fun isNull(i: Int): Boolean = rows[pos][i] == null
    fun getString(i: Int): String? = rows[pos][i]?.toString()
    fun getLong(i: Int): Long = (rows[pos][i] as? Number)?.toLong() ?: 0L
    fun getInt(i: Int): Int = (rows[pos][i] as? Number)?.toInt() ?: 0
    override fun close() {}
}
