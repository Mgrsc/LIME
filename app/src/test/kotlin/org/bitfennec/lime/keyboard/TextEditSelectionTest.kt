package org.bitfennec.lime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun swipeDeleteSelectionRangeMaintainsStableAnchor() {
        val anchor = 9
        val step2 = calculateSwipeDeleteSelectionRange(initialCursor = anchor, charCount = 2)
        assertEquals(Pair(7, 9), step2)

        val step3 = calculateSwipeDeleteSelectionRange(initialCursor = anchor, charCount = 3)
        assertEquals(Pair(6, 9), step3)

        val step10 = calculateSwipeDeleteSelectionRange(initialCursor = anchor, charCount = 10)
        assertEquals(Pair(0, 9), step10)
    }

    @Test
    fun swipeDeleteSelectionRangeReturnsNullForInvalidOrEmptyInputs() {
        assertNull(calculateSwipeDeleteSelectionRange(initialCursor = 0, charCount = 1))
        assertNull(calculateSwipeDeleteSelectionRange(initialCursor = -1, charCount = 1))
        assertNull(calculateSwipeDeleteSelectionRange(initialCursor = 5, charCount = 0))
        assertNull(calculateSwipeDeleteSelectionRange(initialCursor = 5, charCount = -2))
    }
}
