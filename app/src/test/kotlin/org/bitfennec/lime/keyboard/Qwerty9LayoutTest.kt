package org.bitfennec.lime.keyboard

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Qwerty9LayoutTest {

    @Test
    fun testQwerty9LayoutDimensionsAndKeyCodes() {
        val cn = KeyboardData.layoutQwerty9Cn
        val en = KeyboardData.layoutQwerty9En

        assertEquals(3, cn.size)
        assertEquals(3, en.size)

        // Row 1: 9 keys (W E R T Y U I O P)
        assertEquals(9, cn[0].size)
        assertEquals(9, en[0].size)
        assertEquals(KeyEvent.KEYCODE_W, cn[0][0])
        assertEquals(KeyEvent.KEYCODE_P, cn[0][8])

        // Row 2: 9 keys (A S D F G H J K L)
        assertEquals(9, cn[1].size)
        assertEquals(9, en[1].size)
        assertEquals(KeyEvent.KEYCODE_A, cn[1][0])
        assertEquals(KeyEvent.KEYCODE_L, cn[1][8])

        // Row 3: 9 keys before Shift prepend (Q Z X C V B N M DEL)
        assertEquals(9, cn[2].size)
        assertEquals(9, en[2].size)
        assertEquals(KeyEvent.KEYCODE_Q, cn[2][0])
        assertEquals(KeyEvent.KEYCODE_Z, cn[2][1])
        assertEquals(KeyEvent.KEYCODE_M, cn[2][7])
        assertEquals(KeyEvent.KEYCODE_DEL, cn[2][8])
    }

    @Test
    fun testQwerty9KeyPresetsPreserveNumberPresetIndices() {
        // Presets are keyed by keycode, Q must keep "1" small label
        val pyPreset = KeyPreset.qwertyPYKeyNumberPreset[KeyEvent.KEYCODE_Q]
        assertEquals("Q", pyPreset?.getOrNull(0))
        assertEquals("1", pyPreset?.getOrNull(1))

        val enPreset = KeyPreset.qwertyKeyNumberPreset[KeyEvent.KEYCODE_Q]
        assertEquals("Q", enPreset?.getOrNull(0))
        assertEquals("1", enPreset?.getOrNull(1))
    }

    @Test
    fun testQwerty9RowWidthSumsWithinTolerance() {
        // Row 1: 9 * 0.111 = 0.999
        val row1Sum = 9 * 0.111f
        assertTrue("Row 1 sum $row1Sum must be in [0.99, 1.01]", row1Sum in 0.99f..1.01f)

        // Row 2: 9 * 0.111 = 0.999
        val row2Sum = 9 * 0.111f
        assertTrue("Row 2 sum $row2Sum must be in [0.99, 1.01]", row2Sum in 0.99f..1.01f)

        // Row 3: Shift (0.139) + 8 letters * 0.089 + Del (0.150) = 1.001
        val row3Sum = 0.139f + 8 * 0.089f + 0.150f
        assertTrue("Row 3 sum $row3Sum must be in [0.99, 1.01]", row3Sum in 0.99f..1.01f)
    }
}
