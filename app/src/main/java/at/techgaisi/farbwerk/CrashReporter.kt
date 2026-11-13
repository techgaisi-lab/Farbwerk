package at.techgaisi.farbwerk

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Speichert bei einem Absturz den Fehlerbericht in filesDir/last_crash.txt.
 * Beim nächsten Start bietet [CrashReportDialog] an, den Bericht zu teilen.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val version = try {
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName
                } catch (_: Throwable) { "?" }
                val text = buildString {
                    appendLine("Farbwerk $version – Absturz")
                    appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMANY).format(Date()))
                    appendLine("Gerät: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    append(sw.toString())
                }
                File(app.filesDir, FILE).writeText(text)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun pending(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}

/** Zeigt nach einem Absturz einmalig einen Dialog mit "Bericht teilen". */
@Composable
fun CrashReportDialog() {
    val ctx = LocalContext.current
    var report by remember { mutableStateOf(CrashReporter.pending(ctx)) }
    val text = report ?: return
    AlertDialog(
        onDismissRequest = { CrashReporter.clear(ctx); report = null },
        title = { Text("Farbwerk wurde beendet") },
        text = { Text("Beim letzten Mal ist die App abgestürzt. Du kannst den Fehlerbericht teilen, damit der Fehler behoben werden kann.") },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Farbwerk Absturzbericht")
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                ctx.startActivity(Intent.createChooser(send, "Absturzbericht teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                CrashReporter.clear(ctx); report = null
            }) { Text("Bericht teilen") }
        },
        dismissButton = {
            TextButton(onClick = { CrashReporter.clear(ctx); report = null }) { Text("Schließen") }
        },
    )
}
