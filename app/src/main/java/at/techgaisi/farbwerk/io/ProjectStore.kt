package at.techgaisi.farbwerk.io

import android.content.Context
import android.graphics.Bitmap
import at.techgaisi.farbwerk.model.ArtDocument

data class ProjectInfo(val id: String, val name: String, val width: Int, val height: Int, val modified: Long)

/** STUB – wird vom Datei-Modul ersetzt. */
class ProjectStore(private val context: Context) {
    fun list(): List<ProjectInfo> = emptyList()
    fun save(doc: ArtDocument) {}
    fun load(id: String): ArtDocument? = null
    fun delete(id: String) {}
    fun duplicate(id: String): String? = null
    fun thumbnail(id: String): Bitmap? = null
}
