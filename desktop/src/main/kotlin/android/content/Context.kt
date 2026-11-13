@file:Suppress("unused", "UNUSED_PARAMETER")

package android.content

import android.content.pm.PackageManager
import android.content.res.Resources
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import compat.DesktopPlatform
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Desktop-Kontext. Datenverzeichnisse liegen unter %APPDATA%\Farbwerk (Windows) bzw. ~/.farbwerk:
 * files/ (= filesDir), cache/ (= cacheDir), shared_prefs/ (SharedPreferences als Properties-Dateien).
 */
abstract class Context {
    abstract val applicationContext: Context
    abstract val filesDir: File
    abstract val cacheDir: File
    abstract val packageName: String
    abstract val resources: Resources
    abstract val contentResolver: ContentResolver
    abstract val packageManager: PackageManager

    abstract fun getSharedPreferences(name: String, mode: Int): SharedPreferences
    abstract fun getSystemService(name: String): Any?
    abstract fun startActivity(intent: Intent)

    fun getExternalFilesDir(type: String?): File? = File(filesDir, type ?: "external").apply { mkdirs() }
    fun getDir(name: String, mode: Int): File = File(filesDir.parentFile, "app_$name").apply { mkdirs() }
    fun getString(id: Int): String = resources.getString(id)
    fun openFileOutput(name: String, mode: Int): FileOutputStream = FileOutputStream(File(filesDir, name), mode == MODE_APPEND)
    fun openFileInput(name: String): FileInputStream = FileInputStream(File(filesDir, name))
    fun deleteFile(name: String): Boolean = File(filesDir, name).delete()
    fun fileList(): Array<String> = filesDir.list() ?: emptyArray()

    companion object {
        const val MODE_PRIVATE = 0
        const val MODE_APPEND = 0x8000
        const val ACTIVITY_SERVICE = "activity"
        const val CLIPBOARD_SERVICE = "clipboard"
        const val INPUT_METHOD_SERVICE = "input_method"
        const val VIBRATOR_SERVICE = "vibrator"
        const val WINDOW_SERVICE = "window"
    }
}

/** Basis für Activity: delegiert an einen anderen Kontext (wie auf Android). */
open class ContextWrapper(base: Context?) : Context() {
    private var mBase: Context? = base
    open val baseContext: Context get() = mBase ?: DesktopContext

    protected fun attachBaseContext(base: Context) { mBase = base }

    override val applicationContext: Context get() = baseContext.applicationContext
    override val filesDir: File get() = baseContext.filesDir
    override val cacheDir: File get() = baseContext.cacheDir
    override val packageName: String get() = baseContext.packageName
    override val resources: Resources get() = baseContext.resources
    override val contentResolver: ContentResolver get() = baseContext.contentResolver
    override val packageManager: PackageManager get() = baseContext.packageManager
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = baseContext.getSharedPreferences(name, mode)
    override fun getSystemService(name: String): Any? = baseContext.getSystemService(name)
    override fun startActivity(intent: Intent) = baseContext.startActivity(intent)
}

/** Der (einzige) Anwendungskontext der Desktop-Version. */
object DesktopContext : Context() {
    private val prefs = HashMap<String, PropertiesSharedPreferences>()

    override val applicationContext: Context get() = this
    override val filesDir: File get() = File(DesktopPlatform.dataDir, "files").apply { mkdirs() }
    override val cacheDir: File get() = File(DesktopPlatform.dataDir, "cache").apply { mkdirs() }
    override val packageName: String get() = "at.techgaisi.farbwerk"
    override val resources: Resources get() = Resources.getSystem()
    override val contentResolver: ContentResolver = ContentResolver()
    override val packageManager: PackageManager = PackageManager()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = synchronized(prefs) {
        prefs.getOrPut(name) {
            PropertiesSharedPreferences(File(File(DesktopPlatform.dataDir, "shared_prefs").apply { mkdirs() }, "$name.properties"))
        }
    }

    override fun getSystemService(name: String): Any? = when (name) {
        ACTIVITY_SERVICE -> android.app.ActivityManager
        CLIPBOARD_SERVICE -> ClipboardManager
        INPUT_METHOD_SERVICE -> android.view.inputmethod.InputMethodManager
        else -> null
    }

    override fun startActivity(intent: Intent) = DesktopPlatform.handleIntent(intent)
}

/**
 * Zugriff auf Inhalte über [Uri]. Auf dem Desktop sind alle URIs Datei-URIs (file:…) bzw. einfache Pfade.
 */
class ContentResolver {
    fun openInputStream(uri: Uri): InputStream? = uri.toFile()?.takeIf { it.isFile }?.let { FileInputStream(it) }

    fun openOutputStream(uri: Uri): OutputStream? = uri.toFile()?.let { it.parentFile?.mkdirs(); FileOutputStream(it) }

    fun openOutputStream(uri: Uri, mode: String): OutputStream? =
        uri.toFile()?.let { it.parentFile?.mkdirs(); FileOutputStream(it, mode.contains('a')) }

    fun getType(uri: Uri): String? = uri.toFile()?.let { DesktopPlatform.mimeOf(it.name) }

    /** Unterstützt nur [OpenableColumns.DISPLAY_NAME] und [OpenableColumns.SIZE]. */
    fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
        val f = uri.toFile() ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = cols.map { c ->
            when (c) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }
        return Cursor(cols, listOf(row))
    }
}
