package org.bitfennec.lime.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEditSelectionTest {

    @Test
    fun invalidSelectionIndicesDoNotTriggerSelection() {
        // Framework -1 values must never trigger selection
        assertFalse(isTextSelectionActive(-1, -1))
        assertFalse(isTextSelectionActive(-1, 0))
        assertFalse(isTextSelectionActive(0, -1))
        assertFalse(isTextSelectionActive(-1, 5))
    }

    @Test
    fun collapsedCursorDoesNotTriggerSelection() {
        assertFalse(isTextSelectionActive(0, 0))
        assertFalse(isTextSelectionActive(10, 10))
    }

    @Test
    fun validRangeTriggersSelection() {
        assertTrue(isTextSelectionActive(0, 5))
        assertTrue(isTextSelectionActive(5, 0))
        assertTrue(isTextSelectionActive(3, 12))
    }
}
