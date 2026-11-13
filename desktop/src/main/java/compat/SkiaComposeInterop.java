package compat;

/**
 * Zugriff auf die originalen Compose-Desktop-Brücken (nativeCanvas, asSkiaPath), die in Kotlin durch
 * gleichnamige Android-kompatible Erweiterungen dieses Moduls überschattet werden.
 */
public final class SkiaComposeInterop {
    private SkiaComposeInterop() {}

    public static org.jetbrains.skia.Canvas skiaCanvas(androidx.compose.ui.graphics.Canvas canvas) {
        return androidx.compose.ui.graphics.SkiaBackedCanvas_skikoKt.getNativeCanvas(canvas);
    }

    public static org.jetbrains.skia.Path skiaPath(androidx.compose.ui.graphics.Path path) {
        return androidx.compose.ui.graphics.SkiaBackedPath_skikoKt.asSkiaPath(path);
    }
}
