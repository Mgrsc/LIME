package org.bitfennec.lime.utils

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyEventUtilsTest {

    @Test
    fun testResolvedKeyCharForLetters() {
        assertEquals('a'.code, KeyEventUtils.getResolvedKeyChar(KeyEvent.KEYCODE_A, 0))
        assertEquals('z'.code, KeyEventUtils.getResolvedKeyChar(KeyEvent.KEYCODE_Z, 0))
        assertEquals('A'.code, KeyEventUtils.getResolvedKeyChar(KeyEvent.KEYCODE_A, KeyEvent.META_CAPS_LOCK_ON))
        assertEquals('A'.code, KeyEventUtils.getResolvedKeyChar(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON))
    }

    @Test
    fun testResolvedKeyCharForDigits() {
        assertEquals('0'.code, KeyEventUtils.getResolvedKeyChar(KeyEvent.KEYCODE_0, 0))
        assertEquals('9'.code, KeyEventUtils.getResolvedKeyChar(KeyEvent.KEYCODE_9, 0))
    }

    @Test
    fun testResolvedKeyCharForPunctuation() {
        assertEquals('\''.code, KeyEventUtils.getResolvedKeyChar(KeyEvent.KEYCODE_APOSTROPHE, 0))
        assertEquals(';'.code, KeyEventUtils.getResolvedKeyChar(KeyEvent.KEYCODE_SEMICOLON, 0))
        assertEquals(','.code, KeyEventUtils.getResolvedKeyChar(KeyEvent.KEYCODE_COMMA, 0))
        assertEquals('.'.code, KeyEventUtils.getResolvedKeyChar(KeyEvent.KEYCODE_PERIOD, 0))
        assertEquals('/'.code, KeyEventUtils.getResolvedKeyChar(KeyEvent.KEYCODE_SLASH, 0))
        assertEquals('-'.code, KeyEventUtils.getResolvedKeyChar(KeyEvent.KEYCODE_MINUS, 0))
        assertEquals('='.code, KeyEventUtils.getResolvedKeyChar(KeyEvent.KEYCODE_EQUALS, 0))
        assertEquals('['.code, KeyEventUtils.getResolvedKeyChar(KeyEvent.KEYCODE_LEFT_BRACKET, 0))
        assertEquals(']'.code, KeyEventUtils.getResolvedKeyChar(KeyEvent.KEYCODE_RIGHT_BRACKET, 0))
        assertEquals('\\'.code, KeyEventUtils.getResolvedKeyChar(KeyEvent.KEYCODE_BACKSLASH, 0))
        assertEquals('`'.code, KeyEventUtils.getResolvedKeyChar(KeyEvent.KEYCODE_GRAVE, 0))
    }
}
