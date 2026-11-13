package androidx.core.content

import android.content.Context
import android.net.Uri
import java.io.File

/** Auf dem Desktop werden Dateien direkt über Datei-URIs geteilt. */
open class FileProvider {
    companion object {
        @JvmStatic fun getUriForFile(context: Context, authority: String, file: File): Uri = Uri.fromFile(file)
    }
}
