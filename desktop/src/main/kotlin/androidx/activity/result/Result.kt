@file:Suppress("unused")

package androidx.activity.result

import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

abstract class ActivityResultLauncher<I> {
    abstract val contract: ActivityResultContract<I, *>
    abstract fun launch(input: I)
    open fun launch(input: I, options: Any?) = launch(input)
    abstract fun unregister()
}

/** Anfrage für den Foto-Auswähler. */
class PickVisualMediaRequest(
    val mediaType: ActivityResultContracts.PickVisualMedia.VisualMediaType = ActivityResultContracts.PickVisualMedia.ImageAndVideo,
) {
    class Builder {
        private var type: ActivityResultContracts.PickVisualMedia.VisualMediaType = ActivityResultContracts.PickVisualMedia.ImageAndVideo
        fun setMediaType(t: ActivityResultContracts.PickVisualMedia.VisualMediaType) = apply { type = t }
        fun build() = PickVisualMediaRequest(type)
    }
}

