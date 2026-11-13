@file:Suppress("unused")

package android.net

import android.os.Parcelable
import java.io.File
import java.net.URI

/**
 * Uri-Nachbildung für den Desktop. Dateien werden als `file:`-URIs dargestellt; [toFile] liefert die Datei.
 */
class Uri private constructor(private val raw: String) : Parcelable, Comparable<Uri> {
    private val parsed: URI? by lazy { try { URI(raw) } catch (_: Exception) { null } }

    val scheme: String? get() = parsed?.scheme ?: if (raw.startsWith("/") || raw.matches(Regex("^[A-Za-z]:\\\\.*"))) "file" else null
    val path: String? get() = toFile()?.path ?: parsed?.path
    val authority: String? get() = parsed?.authority
    val host: String? get() = parsed?.host
    val query: String? get() = parsed?.query
    val fragment: String? get() = parsed?.fragment
    val lastPathSegment: String? get() = toFile()?.name ?: parsed?.path?.trimEnd('/')?.substringAfterLast('/')
    val pathSegments: List<String> get() = (path ?: "").split('/', '\\').filter { it.isNotEmpty() }
    val isAbsolute: Boolean get() = scheme != null

    fun getQueryParameter(key: String): String? = query?.split('&')?.firstOrNull { it.substringBefore('=') == key }?.substringAfter('=', "")

    /** Datei hinter dieser URI (file:-Schema oder einfacher Pfad), sonst null. */
    fun toFile(): File? = when {
        raw.startsWith("file:") -> try { File(URI(raw)) } catch (_: Exception) { File(raw.removePrefix("file://").removePrefix("file:")) }
        parsed?.scheme == null || raw.matches(Regex("^[A-Za-z]:[\\\\/].*")) -> File(raw)
        else -> null
    }

    fun buildUpon(): Builder = Builder().also { it.raw = raw }

    override fun toString(): String = raw
    override fun equals(other: Any?): Boolean = other is Uri && other.raw == raw
    override fun hashCode(): Int = raw.hashCode()
    override fun compareTo(other: Uri): Int = raw.compareTo(other.raw)

    class Builder {
        internal var raw = ""
        private var scheme: String? = null
        private var authority: String? = null
        private val segments = ArrayList<String>()
        fun scheme(s: String?) = apply { scheme = s }
        fun authority(a: String?) = apply { authority = a }
        fun appendPath(p: String) = apply { segments.add(p) }
        fun path(p: String?) = apply { segments.clear(); p?.let { segments.addAll(it.split('/').filter { s -> s.isNotEmpty() }) } }
        fun build(): Uri = if (scheme == null && segments.isEmpty()) Uri(raw)
            else Uri((scheme?.let { "$it:" } ?: "") + (authority?.let { "//$it" } ?: "") + "/" + segments.joinToString("/"))
    }

    companion object {
        @JvmField val EMPTY: Uri = Uri("")
        @JvmStatic fun parse(s: String): Uri = Uri(s)
        @JvmStatic fun fromFile(f: File): Uri = Uri(f.absoluteFile.toURI().toString())
        @JvmStatic fun encode(s: String?): String? = s?.let { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        @JvmStatic fun decode(s: String?): String? = s?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    }
}
