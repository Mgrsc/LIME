package org.bitfennec.lime.utils

import org.bitfennec.lime.inputmethod.CompositionPresentation
import org.bitfennec.lime.inputmethod.PreeditBoundary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreeditCursorDragTest {
    @Test
    fun countsActivationDistanceTowardFirstStepInBothDirections() {
        for (direction in listOf(-1f, 1f)) {
            val drag = drag()
            val targets = mutableListOf<Int>()
            drag.start(100f, composition(4))
            drag.move(100f + 4f * direction) { targets.add(it) }
            assertFalse(drag.isDragging)
            drag.move(100f + 9f * direction) { targets.add(it) }
            assertTrue(drag.isDragging)
            assertTrue(targets.isEmpty())
            drag.move(100f + 10f * direction) { targets.add(it) }
            assertEquals(listOf(4 + direction.toInt()), targets)
            drag.move(100f + 19f * direction) { targets.add(it) }
            assertEquals(1, targets.size)
            drag.move(100f + 20f * direction) { targets.add(it) }
            assertEquals(listOf(4 + direction.toInt(), 4 + 2 * direction.toInt()), targets)
        }
    }

    @Test
    fun keepsSubStepRemainderAndRequiresDistanceForReversal() {
        val drag = drag()
        val targets = mutableListOf<Int>()
        drag.start(100f, composition(4))
        for (x in listOf(85f, 83f, 87f, 99f, 100f)) {
            drag.move(x) { targets.add(it) }
        }
        assertEquals(listOf(3, 4), targets)
    }

    @Test
    fun batchesLongMovesByKeyBoundaries() {
        val drag = drag()
        val targets = mutableListOf<Int>()
        drag.start(100f, composition(4))
        drag.move(134f) { targets.add(it) }
        drag.move(110f) { targets.add(it) }
        assertEquals(listOf(7, 5), targets)
    }

    @Test
    fun discardsOvershootAtEdgesBeforeReversing() {
        val drag = drag()
        val targets = mutableListOf<Int>()
        drag.start(100f, composition(7))
        drag.move(164f) { targets.add(it) }
        drag.move(184f) { targets.add(it) }
        drag.move(174f) { targets.add(it) }
        assertEquals(listOf(8, 7), targets)
    }

    @Test
    fun retriesRejectedMovesWithoutConsumingDistance() {
        val drag = drag()
        val targets = mutableListOf<Int>()
        drag.start(100f, composition(4))
        drag.move(114f) { targets.add(it); false }
        drag.move(114f) { targets.add(it); true }
        drag.move(124f) { targets.add(it); true }
        assertEquals(listOf(5, 5, 6), targets)
    }

    @Test
    fun neverCreatesPositionsInsideSelectedWordsOrExpandedSpellings() {
        val drag = drag()
        val targets = mutableListOf<Int>()
        val composition = CompositionPresentation(
            rawCursor = 0,
            boundaries = listOf(PreeditBoundary(0, 0), PreeditBoundary(2, 5), PreeditBoundary(4, 5), PreeditBoundary(6, 7)),
        )
        drag.start(100f, composition)
        drag.move(114f) { targets.add(it) }
        drag.move(124f) { targets.add(it) }
        assertEquals(listOf(5, 7), targets)
    }

    @Test
    fun resetAndMissingMappingsCannotDispatchMoves() {
        val drag = drag()
        val targets = mutableListOf<Int>()
        drag.start(100f, composition(4))
        drag.move(103f) { targets.add(it) }
        assertFalse(drag.isDragging)
        drag.reset()
        drag.move(200f) { targets.add(it) }
        drag.start(100f, CompositionPresentation.Empty)
        drag.move(200f) { targets.add(it) }
        assertFalse(drag.isDragging)
        assertTrue(targets.isEmpty())
    }

    private fun drag() = PreeditCursorDrag(touchSlop = 4f, stepDistance = 10f)

    private fun composition(cursor: Int) = CompositionPresentation(
        rawCursor = cursor,
        boundaries = (0..8).map { PreeditBoundary(it, it) },
    )
}
