package compat

import android.os.SystemClock
import android.view.KeyEvent
import java.awt.event.KeyEvent as AwtKey

/** AWT-Tastencodes → Android-Keycodes und Umwandlung von Tastaturereignissen. */
object KeyMapping {
    private val map = HashMap<Int, Int>().apply {
        for (i in 0..25) put(AwtKey.VK_A + i, KeyEvent.KEYCODE_A + i)
        for (i in 0..9) put(AwtKey.VK_0 + i, KeyEvent.KEYCODE_0 + i)
        for (i in 0..9) put(AwtKey.VK_NUMPAD0 + i, KeyEvent.KEYCODE_NUMPAD_0 + i)
        for (i in 0..11) put(AwtKey.VK_F1 + i, KeyEvent.KEYCODE_F1 + i)
        put(AwtKey.VK_SPACE, KeyEvent.KEYCODE_SPACE)
        put(AwtKey.VK_ENTER, KeyEvent.KEYCODE_ENTER)
        put(AwtKey.VK_BACK_SPACE, KeyEvent.KEYCODE_DEL)
        put(AwtKey.VK_DELETE, KeyEvent.KEYCODE_FORWARD_DEL)
        put(AwtKey.VK_ESCAPE, KeyEvent.KEYCODE_ESCAPE)
        put(AwtKey.VK_TAB, KeyEvent.KEYCODE_TAB)
        put(AwtKey.VK_LEFT, KeyEvent.KEYCODE_DPAD_LEFT)
        put(AwtKey.VK_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT)
        put(AwtKey.VK_UP, KeyEvent.KEYCODE_DPAD_UP)
        put(AwtKey.VK_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
        put(AwtKey.VK_COMMA, KeyEvent.KEYCODE_COMMA)
        put(AwtKey.VK_PERIOD, KeyEvent.KEYCODE_PERIOD)
        put(AwtKey.VK_MINUS, KeyEvent.KEYCODE_MINUS)
        put(AwtKey.VK_EQUALS, KeyEvent.KEYCODE_EQUALS)
        put(AwtKey.VK_PLUS, KeyEvent.KEYCODE_PLUS)
        put(AwtKey.VK_OPEN_BRACKET, KeyEvent.KEYCODE_LEFT_BRACKET)
        put(AwtKey.VK_CLOSE_BRACKET, KeyEvent.KEYCODE_RIGHT_BRACKET)
        put(AwtKey.VK_SEMICOLON, KeyEvent.KEYCODE_SEMICOLON)
        put(AwtKey.VK_QUOTE, KeyEvent.KEYCODE_APOSTROPHE)
        put(AwtKey.VK_BACK_QUOTE, KeyEvent.KEYCODE_GRAVE)
        put(AwtKey.VK_SLASH, KeyEvent.KEYCODE_SLASH)
        put(AwtKey.VK_BACK_SLASH, KeyEvent.KEYCODE_BACKSLASH)
        put(AwtKey.VK_ADD, KeyEvent.KEYCODE_NUMPAD_ADD)
        put(AwtKey.VK_SUBTRACT, KeyEvent.KEYCODE_NUMPAD_SUBTRACT)
        put(AwtKey.VK_MULTIPLY, KeyEvent.KEYCODE_NUMPAD_MULTIPLY)
        put(AwtKey.VK_DIVIDE, KeyEvent.KEYCODE_NUMPAD_DIVIDE)
        put(AwtKey.VK_DECIMAL, KeyEvent.KEYCODE_NUMPAD_DOT)
        put(AwtKey.VK_PAGE_UP, KeyEvent.KEYCODE_PAGE_UP)
        put(AwtKey.VK_PAGE_DOWN, KeyEvent.KEYCODE_PAGE_DOWN)
        put(AwtKey.VK_HOME, KeyEvent.KEYCODE_MOVE_HOME)
        put(AwtKey.VK_END, KeyEvent.KEYCODE_MOVE_END)
        put(AwtKey.VK_INSERT, KeyEvent.KEYCODE_INSERT)
        put(AwtKey.VK_CAPS_LOCK, KeyEvent.KEYCODE_CAPS_LOCK)
    }

    private val down = HashMap<Int, Int>()

    /** Wandelt ein AWT-Tastenereignis (KEY_PRESSED/KEY_RELEASED) um; null bei unbekannten Tasten. */
    fun convert(e: AwtKey): KeyEvent? {
        val right = e.keyLocation == AwtKey.KEY_LOCATION_RIGHT
        val code = when (e.keyCode) {
            AwtKey.VK_SHIFT -> if (right) KeyEvent.KEYCODE_SHIFT_RIGHT else KeyEvent.KEYCODE_SHIFT_LEFT
            AwtKey.VK_CONTROL -> if (right) KeyEvent.KEYCODE_CTRL_RIGHT else KeyEvent.KEYCODE_CTRL_LEFT
            AwtKey.VK_ALT -> KeyEvent.KEYCODE_ALT_LEFT
            AwtKey.VK_ALT_GRAPH -> KeyEvent.KEYCODE_ALT_RIGHT
            AwtKey.VK_META, AwtKey.VK_WINDOWS -> if (right) KeyEvent.KEYCODE_META_RIGHT else KeyEvent.KEYCODE_META_LEFT
            AwtKey.VK_EQUALS -> if (e.keyLocation == AwtKey.KEY_LOCATION_NUMPAD) KeyEvent.KEYCODE_NUMPAD_EQUALS else KeyEvent.KEYCODE_EQUALS
            else -> map[e.keyCode] ?: return null
        }
        var meta = 0
        if (e.isShiftDown) meta = meta or KeyEvent.META_SHIFT_ON
        if (e.isAltDown) meta = meta or KeyEvent.META_ALT_ON
        if (e.isControlDown) meta = meta or KeyEvent.META_CTRL_ON
        if (e.isMetaDown) meta = meta or KeyEvent.META_META_ON
        val now = SystemClock.uptimeMillis()
        return when (e.id) {
            AwtKey.KEY_PRESSED -> {
                val repeat = down[code]?.plus(1) ?: 0
                down[code] = repeat
                val ch = e.keyChar.takeIf { it != AwtKey.CHAR_UNDEFINED && !it.isISOControl() }?.code ?: 0
                KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, repeat, meta, ch)
            }
            AwtKey.KEY_RELEASED -> {
                down.remove(code)
                KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta)
            }
            else -> null
        }
    }

    /** Beim Fokusverlust: alle gedrückten Tasten vergessen (sonst „klemmt“ z. B. die Leertaste). */
    fun releaseAll(): List<KeyEvent> {
        val now = SystemClock.uptimeMillis()
        return down.keys.map { KeyEvent(now, now, KeyEvent.ACTION_UP, it, 0, 0) }.also { down.clear() }
    }
}
