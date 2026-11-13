@file:Suppress("unused")

package androidx.activity.result.contract

import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import compat.FileDialogs

/**
 * Ergebnis-Vertrag. Auf dem Desktop öffnet [launchOnDesktop] einen nativen Dateidialog und liefert
 * das Ergebnis (Datei-URI bzw. null bei Abbruch) auf dem UI-Thread zurück.
 */
abstract class ActivityResultContract<I, O> {
    abstract fun launchOnDesktop(input: I, done: (O) -> Unit)
}

object ActivityResultContracts {
    /** Datei öffnen; Eingabe = MIME-Typen. */
    open class OpenDocument : ActivityResultContract<Array<String>, Uri?>() {
        override fun launchOnDesktop(input: Array<String>, done: (Uri?) -> Unit) =
            done(FileDialogs.open("Datei öffnen", input.toList())?.let { Uri.fromFile(it) })
    }

    open class OpenMultipleDocuments : ActivityResultContract<Array<String>, List<Uri>>() {
        override fun launchOnDesktop(input: Array<String>, done: (List<Uri>) -> Unit) =
            done(FileDialogs.openMultiple("Dateien öffnen", input.toList()).map { Uri.fromFile(it) })
    }

    /** Inhalt wählen; Eingabe = ein MIME-Typ (z. B. "image/ *"). */
    open class GetContent : ActivityResultContract<String, Uri?>() {
        override fun launchOnDesktop(input: String, done: (Uri?) -> Unit) =
            done(FileDialogs.open("Datei öffnen", listOf(input))?.let { Uri.fromFile(it) })
    }

    /** Neue Datei anlegen; Eingabe = vorgeschlagener Dateiname. */
    open class CreateDocument(private val mimeType: String = "*/*") : ActivityResultContract<String, Uri?>() {
        override fun launchOnDesktop(input: String, done: (Uri?) -> Unit) =
            done(FileDialogs.save("Speichern unter", input)?.let { Uri.fromFile(it) })
    }

    /** Foto-Auswahl → Bilddatei-Dialog. */
    open class PickVisualMedia : ActivityResultContract<PickVisualMediaRequest, Uri?>() {
        sealed interface VisualMediaType
        object ImageOnly : VisualMediaType
        object VideoOnly : VisualMediaType
        object ImageAndVideo : VisualMediaType
        class SingleMimeType(val mimeType: String) : VisualMediaType

        override fun launchOnDesktop(input: PickVisualMediaRequest, done: (Uri?) -> Unit) {
            val mimes = when (val t = input.mediaType) {
                ImageOnly -> listOf("image/*")
                VideoOnly -> listOf("video/*")
                ImageAndVideo -> listOf("image/*", "video/*")
                is SingleMimeType -> listOf(t.mimeType)
            }
            done(FileDialogs.open("Bild auswählen", mimes)?.let { Uri.fromFile(it) })
        }
    }

    /**
     * Foto aufnehmen: Auf dem Desktop gibt es keine Kamera-App – stattdessen wird ein vorhandenes Foto
     * gewählt und an das Ziel kopiert. Ergebnis true, wenn ein Bild übernommen wurde.
     */
    open class TakePicture : ActivityResultContract<Uri, Boolean>() {
        override fun launchOnDesktop(input: Uri, done: (Boolean) -> Unit) {
            val src = FileDialogs.open("Foto auswählen (keine Kamera verfügbar)", listOf("image/*"))
            val dst = input.toFile()
            if (src == null || dst == null) { done(false); return }
            val ok = try { dst.parentFile?.mkdirs(); src.copyTo(dst, overwrite = true); true } catch (_: Exception) { false }
            done(ok)
        }
    }
}
