@file:Suppress("unused")

package android.content

import android.net.Uri
import android.os.Parcelable

/**
 * Intent-Nachbildung. Auf dem Desktop werden nur „Teilen“-Intents (ACTION_SEND/ACTION_SEND_MULTIPLE,
 * ggf. in createChooser verpackt) ausgewertet: Dateien werden über einen „Speichern unter“-Dialog abgelegt
 * und im Explorer gezeigt, Text landet in der Zwischenablage (siehe compat.DesktopPlatform.handleIntent).
 */
class Intent() : Parcelable {
    var action: String? = null
    var type: String? = null
    var data: Uri? = null
    var flags: Int = 0
    var clipData: ClipData? = null
    val extras: MutableMap<String, Any?> = LinkedHashMap()

    constructor(action: String?) : this() { this.action = action }
    constructor(action: String?, uri: Uri?) : this() { this.action = action; this.data = uri }
    constructor(o: Intent) : this() {
        action = o.action; type = o.type; data = o.data; flags = o.flags; clipData = o.clipData; extras.putAll(o.extras)
    }

    fun setAction(a: String?): Intent = apply { action = a }
    fun setType(t: String?): Intent = apply { type = t }
    fun setDataAndType(u: Uri?, t: String?): Intent = apply { data = u; type = t }
    fun addFlags(f: Int): Intent = apply { flags = flags or f }
    fun setFlags(f: Int): Intent = apply { flags = f }
    fun addCategory(c: String): Intent = this

    fun putExtra(name: String, value: String?): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: CharSequence?): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: Int): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: Long): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: Boolean): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: Parcelable?): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: Array<String>?): Intent = apply { extras[name] = value }
    fun putParcelableArrayListExtra(name: String, value: ArrayList<out Parcelable>?): Intent = apply { extras[name] = value }
    fun putStringArrayListExtra(name: String, value: ArrayList<String>?): Intent = apply { extras[name] = value }

    fun getStringExtra(name: String): String? = extras[name]?.toString()
    fun getCharSequenceExtra(name: String): CharSequence? = extras[name] as? CharSequence
    fun getIntExtra(name: String, def: Int): Int = extras[name] as? Int ?: def
    fun getBooleanExtra(name: String, def: Boolean): Boolean = extras[name] as? Boolean ?: def
    fun hasExtra(name: String): Boolean = extras.containsKey(name)
    @Suppress("UNCHECKED_CAST")
    fun <T> getParcelableExtra(name: String): T? = extras[name] as? T

    companion object {
        const val ACTION_SEND = "android.intent.action.SEND"
        const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_CHOOSER = "android.intent.action.CHOOSER"
        const val ACTION_OPEN_DOCUMENT = "android.intent.action.OPEN_DOCUMENT"
        const val ACTION_CREATE_DOCUMENT = "android.intent.action.CREATE_DOCUMENT"
        const val CATEGORY_OPENABLE = "android.intent.category.OPENABLE"
        const val EXTRA_STREAM = "android.intent.extra.STREAM"
        const val EXTRA_TEXT = "android.intent.extra.TEXT"
        const val EXTRA_SUBJECT = "android.intent.extra.SUBJECT"
        const val EXTRA_TITLE = "android.intent.extra.TITLE"
        const val EXTRA_INTENT = "android.intent.extra.INTENT"
        const val EXTRA_MIME_TYPES = "android.intent.extra.MIME_TYPES"
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
        const val FLAG_GRANT_READ_URI_PERMISSION = 0x00000001
        const val FLAG_GRANT_WRITE_URI_PERMISSION = 0x00000002

        @JvmStatic fun createChooser(target: Intent, title: CharSequence?): Intent = Intent(ACTION_CHOOSER).apply {
            extras[EXTRA_INTENT] = target
            extras[EXTRA_TITLE] = title?.toString()
        }
    }
}

class ActivityNotFoundException(msg: String? = null) : RuntimeException(msg)
