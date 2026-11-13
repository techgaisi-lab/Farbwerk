@file:Suppress("unused")

package androidx.compose.ui.graphics

import compat.SkiaComposeInterop

/**
 * Android-Interop von Compose (androidMain) für den Desktop:
 * [nativeCanvas] liefert – wie auf Android – einen android.graphics.Canvas (der den Skia-Canvas umhüllt),
 * [asImageBitmap] macht aus einem android.graphics.Bitmap ein Compose-ImageBitmap,
 * [asAndroidPath] wandelt einen Compose-Pfad in einen android.graphics.Path.
 */
val Canvas.nativeCanvas: android.graphics.Canvas
    get() = android.graphics.Canvas(SkiaComposeInterop.skiaCanvas(this))

fun android.graphics.Bitmap.asImageBitmap(): ImageBitmap = this.skiaBitmap.asComposeImageBitmap()

fun Path.asAndroidPath(): android.graphics.Path = android.graphics.Path(SkiaComposeInterop.skiaPath(this))
