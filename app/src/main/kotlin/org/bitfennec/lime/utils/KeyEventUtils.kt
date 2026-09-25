package org.bitfennec.lime.utils

import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * Deterministic key character derivation and KeyEvent construction utility.
 */
object KeyEventUtils {

    /**
     * Constructs standard virtual soft keyboard KeyEvent.
     */
    fun createSoftKeyEvent(
        action: Int = KeyEvent.ACTION_UP,
        keyCode: Int,
        metaState: Int = 0,
        flags: Int = KeyEvent.FLAG_SOFT_KEYBOARD
    ): KeyEvent {
        return KeyEvent(
            0L, 0L,
            action, keyCode, 0,
            metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            flags,
            InputDevice.SOURCE_KEYBOARD
        )
    }

    /**
     * Deterministically derives character code from keyCode and metaState (ASCII/Unicode).
     */
    fun getResolvedKeyChar(keyCode: Int, metaState: Int = 0): Int {
        val isShift = (metaState and KeyEvent.META_SHIFT_ON) != 0 || (metaState and KeyEvent.META_CAPS_LOCK_ON) != 0
        return when {
            keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                val base = 'a'.code + (keyCode - KeyEvent.KEYCODE_A)
                if (isShift) base.toChar().uppercaseChar().code else base
            }
            keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                '0'.code + (keyCode - KeyEvent.KEYCODE_0)
            }
            keyCode == KeyEvent.KEYCODE_APOSTROPHE -> '\''.code
            keyCode == KeyEvent.KEYCODE_SEMICOLON -> ';'.code
            keyCode == KeyEvent.KEYCODE_COMMA -> ','.code
            keyCode == KeyEvent.KEYCODE_PERIOD -> '.'.code
            keyCode == KeyEvent.KEYCODE_SLASH -> '/'.code
            keyCode == KeyEvent.KEYCODE_MINUS -> '-'.code
            keyCode == KeyEvent.KEYCODE_EQUALS -> '='.code
            keyCode == KeyEvent.KEYCODE_LEFT_BRACKET -> '['.code
            keyCode == KeyEvent.KEYCODE_RIGHT_BRACKET -> ']'.code
            keyCode == KeyEvent.KEYCODE_BACKSLASH -> '\\'.code
            keyCode == KeyEvent.KEYCODE_GRAVE -> '`'.code
            else -> 0
        }
    }

    /**
     * Extracts character code from KeyEvent with fallback to event.unicodeChar.
     */
    fun getResolvedKeyChar(event: KeyEvent): Int {
        val resolved = getResolvedKeyChar(event.keyCode, event.metaState)
        return if (resolved > 0) resolved else event.unicodeChar
    }
}
