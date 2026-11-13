package at.techgaisi.farbwerk.desktop

import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import compat.DesktopPlatform
import java.awt.Window

/** Dunkle Titelleiste unter Windows 10 (ab 1809) / 11 über DwmSetWindowAttribute. */
internal object WindowsTitleBar {
    @Suppress("FunctionName")
    private interface Dwm : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int
    }

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE_OLD = 19
    private const val DWMWA_CAPTION_COLOR = 35

    fun applyDark(window: Window) {
        if (!DesktopPlatform.isWindows) return
        try {
            val dwm = Native.load("dwmapi", Dwm::class.java)
            val hwnd = WinDef.HWND(Native.getComponentPointer(window))
            val on = IntByReference(1)
            if (dwm.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, on, 4) != 0) {
                dwm.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_OLD, on, 4)
            }
            // Windows 11: Titelleiste in der Hintergrundfarbe der App (COLORREF 0x00BBGGRR)
            dwm.DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, IntByReference(0x001A1A1A), 4)
            // Fenster neu zeichnen, damit die Änderung sofort sichtbar wird
            window.isVisible = true
        } catch (t: Throwable) {
            System.err.println("Dunkle Titelleiste nicht verfügbar: $t")
        }
    }
}
