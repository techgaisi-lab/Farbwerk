package androidx.compose.ui.text.font

/** Android-Überladung: FontFamily aus einer android.graphics.Typeface (Skia-Schrift). */
fun FontFamily(typeface: android.graphics.Typeface): FontFamily =
    FontFamily(androidx.compose.ui.text.platform.Typeface(typeface.skia))
