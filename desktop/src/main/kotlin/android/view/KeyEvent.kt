@file:Suppress("unused")

package android.view

/** Tastaturereignis mit Android-Keycodes und Meta-Zustand (vom Desktop-Host aus AWT-Tasten erzeugt). */
class KeyEvent(
    val downTime: Long,
    override val eventTime: Long,
    val action: Int,
    val keyCode: Int,
    val repeatCount: Int,
    val metaState: Int = 0,
    /** Unicode-Zeichen der Taste (0 = keins). */
    private val unicode: Int = 0,
) : InputEvent() {
    constructor(action: Int, code: Int) : this(0L, android.os.SystemClock.uptimeMillis(), action, code, 0)

    val isShiftPressed: Boolean get() = metaState and META_SHIFT_ON != 0
    val isAltPressed: Boolean get() = metaState and META_ALT_ON != 0
    val isCtrlPressed: Boolean get() = metaState and META_CTRL_ON != 0
    val isMetaPressed: Boolean get() = metaState and META_META_ON != 0
    val isLongPress: Boolean get() = false
    val isCanceled: Boolean get() = false
    val flags: Int get() = 0
    val unicodeChar: Int get() = unicode
    fun getUnicodeChar(meta: Int): Int = unicode
    fun hasModifiers(mods: Int): Boolean = (metaState and META_MODIFIER_MASK) == mods
    fun hasNoModifiers(): Boolean = (metaState and META_MODIFIER_MASK) == 0
    fun isPrintingKey(): Boolean = unicode > 0x20

    override fun toString(): String = "KeyEvent(action=$action, keyCode=$keyCode, meta=$metaState)"

    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MULTIPLE = 2
        const val META_SHIFT_ON = 0x1
        const val META_ALT_ON = 0x2
        const val META_CTRL_ON = 0x1000
        const val META_META_ON = 0x10000
        const val META_SHIFT_LEFT_ON = 0x40
        const val META_ALT_LEFT_ON = 0x10
        const val META_CTRL_LEFT_ON = 0x2000
        const val META_MODIFIER_MASK = META_SHIFT_ON or META_ALT_ON or META_CTRL_ON or META_META_ON
        const val KEYCODE_UNKNOWN = 0
        const val KEYCODE_BACK = 4
        const val KEYCODE_HOME = 3
        const val KEYCODE_DPAD_UP = 19
        const val KEYCODE_DPAD_DOWN = 20
        const val KEYCODE_DPAD_LEFT = 21
        const val KEYCODE_DPAD_RIGHT = 22
        const val KEYCODE_DPAD_CENTER = 23
        const val KEYCODE_COMMA = 55
        const val KEYCODE_PERIOD = 56
        const val KEYCODE_ALT_LEFT = 57
        const val KEYCODE_ALT_RIGHT = 58
        const val KEYCODE_SHIFT_LEFT = 59
        const val KEYCODE_SHIFT_RIGHT = 60
        const val KEYCODE_TAB = 61
        const val KEYCODE_SPACE = 62
        const val KEYCODE_ENTER = 66
        const val KEYCODE_DEL = 67
        const val KEYCODE_GRAVE = 68
        const val KEYCODE_MINUS = 69
        const val KEYCODE_EQUALS = 70
        const val KEYCODE_LEFT_BRACKET = 71
        const val KEYCODE_RIGHT_BRACKET = 72
        const val KEYCODE_BACKSLASH = 73
        const val KEYCODE_SEMICOLON = 74
        const val KEYCODE_APOSTROPHE = 75
        const val KEYCODE_SLASH = 76
        const val KEYCODE_AT = 77
        const val KEYCODE_PLUS = 81
        const val KEYCODE_MENU = 82
        const val KEYCODE_PAGE_UP = 92
        const val KEYCODE_PAGE_DOWN = 93
        const val KEYCODE_ESCAPE = 111
        const val KEYCODE_FORWARD_DEL = 112
        const val KEYCODE_CTRL_LEFT = 113
        const val KEYCODE_CTRL_RIGHT = 114
        const val KEYCODE_CAPS_LOCK = 115
        const val KEYCODE_META_LEFT = 117
        const val KEYCODE_META_RIGHT = 118
        const val KEYCODE_MOVE_HOME = 122
        const val KEYCODE_MOVE_END = 123
        const val KEYCODE_INSERT = 124
        const val KEYCODE_NUMPAD_DIVIDE = 154
        const val KEYCODE_NUMPAD_MULTIPLY = 155
        const val KEYCODE_NUMPAD_SUBTRACT = 156
        const val KEYCODE_NUMPAD_ADD = 157
        const val KEYCODE_NUMPAD_DOT = 158
        const val KEYCODE_NUMPAD_COMMA = 159
        const val KEYCODE_NUMPAD_ENTER = 160
        const val KEYCODE_NUMPAD_EQUALS = 161
        const val KEYCODE_STYLUS_BUTTON_PRIMARY = 308
        const val KEYCODE_STYLUS_BUTTON_SECONDARY = 309
        const val KEYCODE_STYLUS_BUTTON_TERTIARY = 310
        const val KEYCODE_STYLUS_BUTTON_TAIL = 311
        const val KEYCODE_0 = 7
        const val KEYCODE_NUMPAD_0 = 144
        const val KEYCODE_1 = 8
        const val KEYCODE_NUMPAD_1 = 145
        const val KEYCODE_2 = 9
        const val KEYCODE_NUMPAD_2 = 146
        const val KEYCODE_3 = 10
        const val KEYCODE_NUMPAD_3 = 147
        const val KEYCODE_4 = 11
        const val KEYCODE_NUMPAD_4 = 148
        const val KEYCODE_5 = 12
        const val KEYCODE_NUMPAD_5 = 149
        const val KEYCODE_6 = 13
        const val KEYCODE_NUMPAD_6 = 150
        const val KEYCODE_7 = 14
        const val KEYCODE_NUMPAD_7 = 151
        const val KEYCODE_8 = 15
        const val KEYCODE_NUMPAD_8 = 152
        const val KEYCODE_9 = 16
        const val KEYCODE_NUMPAD_9 = 153
        const val KEYCODE_A = 29
        const val KEYCODE_B = 30
        const val KEYCODE_C = 31
        const val KEYCODE_D = 32
        const val KEYCODE_E = 33
        const val KEYCODE_F = 34
        const val KEYCODE_G = 35
        const val KEYCODE_H = 36
        const val KEYCODE_I = 37
        const val KEYCODE_J = 38
        const val KEYCODE_K = 39
        const val KEYCODE_L = 40
        const val KEYCODE_M = 41
        const val KEYCODE_N = 42
        const val KEYCODE_O = 43
        const val KEYCODE_P = 44
        const val KEYCODE_Q = 45
        const val KEYCODE_R = 46
        const val KEYCODE_S = 47
        const val KEYCODE_T = 48
        const val KEYCODE_U = 49
        const val KEYCODE_V = 50
        const val KEYCODE_W = 51
        const val KEYCODE_X = 52
        const val KEYCODE_Y = 53
        const val KEYCODE_Z = 54
        const val KEYCODE_F1 = 131
        const val KEYCODE_F2 = 132
        const val KEYCODE_F3 = 133
        const val KEYCODE_F4 = 134
        const val KEYCODE_F5 = 135
        const val KEYCODE_F6 = 136
        const val KEYCODE_F7 = 137
        const val KEYCODE_F8 = 138
        const val KEYCODE_F9 = 139
        const val KEYCODE_F10 = 140
        const val KEYCODE_F11 = 141
        const val KEYCODE_F12 = 142

        @JvmStatic fun isModifierKey(code: Int): Boolean = code in intArrayOf(KEYCODE_SHIFT_LEFT, KEYCODE_SHIFT_RIGHT,
            KEYCODE_ALT_LEFT, KEYCODE_ALT_RIGHT, KEYCODE_CTRL_LEFT, KEYCODE_CTRL_RIGHT, KEYCODE_META_LEFT, KEYCODE_META_RIGHT)
    }
}
