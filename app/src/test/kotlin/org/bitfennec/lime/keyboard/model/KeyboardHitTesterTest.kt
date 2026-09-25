package org.bitfennec.lime.keyboard.model

import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardHitTesterTest {
    @Test
    fun expandsOuterEdgeHitRects() {
        val left = key(0, 0, 100, 50)
        val right = key(110, 0, 210, 50)
        val rows = listOf(listOf(left, right))

        KeyboardHitTester.updateHitRects(rows)

        assertSame(left, KeyboardHitTester.mapToKey(rows, -10, 25))
        assertSame(right, KeyboardHitTester.mapToKey(rows, 220, 25))
        assertNull(KeyboardHitTester.mapToKey(rows, -30, 25))
    }

    @Test
    fun hysteresisKeepsPreviousKeyInsideItsHitRect() {
        val left = key(0, 0, 100, 50)
        val right = key(110, 0, 210, 50)
        val rows = listOf(listOf(left, right))

        KeyboardHitTester.updateHitRects(rows)

        assertSame(left, KeyboardHitTester.mapToKey(rows, 104, 25, hysteresisKey = left))
        assertSame(right, KeyboardHitTester.mapToKey(rows, 112, 25, hysteresisKey = left))
    }

    @Test
    fun fillsVerticalGapBetweenRows() {
        val top = key(0, 0, 100, 50)
        val bottom = key(0, 60, 100, 110)
        val rows = listOf(listOf(top), listOf(bottom))

        KeyboardHitTester.updateHitRects(rows)

        assertSame(bottom, KeyboardHitTester.mapToKey(rows, 50, 55))
    }

    @Test
    fun appliesLearnedOffsetOnlyToCoreHitArea() {
        val left = key(0, 0, 100, 50)
        val right = key(100, 0, 200, 50)
        val rows = listOf(listOf(left, right))
        val provider = KeyCenterOffsetProvider { layoutId, key ->
            if (layoutId == "layout" && key === left) KeyCenterOffset(20f, 0f) else null
        }

        KeyboardHitTester.updateHitRects(rows)

        assertSame(
            left,
            KeyboardHitTester.mapToKey(rows, 105, 25, layoutId = "layout", offsetProvider = provider),
        )
        assertSame(
            right,
            KeyboardHitTester.mapToKey(rows, 125, 25, layoutId = "layout", offsetProvider = provider),
        )
    }

    @Test
    fun resolvesOverlappingShiftedCoreByNearestShiftedCenter() {
        val left = key(0, 0, 100, 50)
        val right = key(100, 0, 200, 50)
        val rows = listOf(listOf(left, right))
        val provider = KeyCenterOffsetProvider { _, key ->
            when {
                key === left -> KeyCenterOffset(20f, 0f)
                key === right -> KeyCenterOffset(-20f, 0f)
                else -> null
            }
        }

        KeyboardHitTester.updateHitRects(rows)

        assertSame(right, KeyboardHitTester.mapToKey(rows, 105, 25, offsetProvider = provider))
    }

    private fun key(left: Int, top: Int, right: Int, bottom: Int) = SoftKey().apply {
        mLeft = left
        mTop = top
        mRight = right
        mBottom = bottom
    }
}
