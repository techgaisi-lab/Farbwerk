@file:Suppress("unused")

package android.content.res

import compat.DesktopPlatform

/** Bildschirmmaße; density = Compose-Dichte des Fensters (UI-Skalierung des Betriebssystems). */
class DisplayMetrics {
    val density: Float get() = DesktopPlatform.density
    val scaledDensity: Float get() = DesktopPlatform.density * DesktopPlatform.fontScale
    val densityDpi: Int get() = (160 * DesktopPlatform.density).toInt()
    val xdpi: Float get() = 96f * DesktopPlatform.density
    val ydpi: Float get() = 96f * DesktopPlatform.density
    val widthPixels: Int get() = DesktopPlatform.windowWidthPx
    val heightPixels: Int get() = DesktopPlatform.windowHeightPx
}

class Configuration {
    var screenWidthDp: Int = 1440
    var screenHeightDp: Int = 960
    var smallestScreenWidthDp: Int = 960
    var densityDpi: Int = 160
    var fontScale: Float = 1f
    var orientation: Int = ORIENTATION_LANDSCAPE
    var uiMode: Int = UI_MODE_NIGHT_YES

    companion object {
        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_LANDSCAPE = 2
        const val UI_MODE_NIGHT_MASK = 0x30
        const val UI_MODE_NIGHT_YES = 0x20
        const val UI_MODE_NIGHT_NO = 0x10
    }
}

class Resources private constructor() {
    val displayMetrics = DisplayMetrics()
    val configuration: Configuration get() = DesktopPlatform.configuration

    fun getString(id: Int): String = at.techgaisi.farbwerk.R.stringValue(id)

    class NotFoundException(msg: String? = null) : RuntimeException(msg)

    companion object {
        private val system: Resources = Resources()
        @JvmStatic fun getSystem(): Resources = system
    }
}
