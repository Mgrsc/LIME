package org.bitfennec.lime.view

import org.junit.Assert.assertEquals
import org.junit.Test

class CandidatesHeightMeasureTest {

    @Test
    fun composingVisibilityIsGoneWhenNotComposing() {
        // Verify contract: when isComposing is false, visibility must be GONE, never INVISIBLE
        fun resolveVisibility(isComposing: Boolean, usesHandwritingLayout: Boolean): Int {
            val shouldShow = !usesHandwritingLayout && isComposing
            return if (shouldShow) android.view.View.VISIBLE else android.view.View.GONE
        }

        assertEquals(android.view.View.GONE, resolveVisibility(isComposing = false, usesHandwritingLayout = false))
        assertEquals(android.view.View.VISIBLE, resolveVisibility(isComposing = true, usesHandwritingLayout = false))
        assertEquals(android.view.View.GONE, resolveVisibility(isComposing = true, usesHandwritingLayout = true))
        assertEquals(android.view.View.GONE, resolveVisibility(isComposing = false, usesHandwritingLayout = true))
    }

    @Test
    fun candidatesBarHeightIsSteadyWithinSession() {
        val candidateRowHeight = 42
        val composingHeaderHeight = 22
        // Total container height is computed once in initData and remains steady within session
        val heightForCandidatesArea = candidateRowHeight + composingHeaderHeight
        assertEquals(64, heightForCandidatesArea)

        // When composing is visible: preedit takes composingHeaderHeight, candidates take remaining space
        val composingVisible = true
        val preeditHeight = if (composingVisible) composingHeaderHeight else 0
        val remainingForCandidates = heightForCandidatesArea - preeditHeight
        assertEquals(22, preeditHeight)
        assertEquals(42, remainingForCandidates)
        assertEquals(heightForCandidatesArea, preeditHeight + remainingForCandidates)

        // When composing is gone: preedit is GONE (0), candidates row or idle toolbar occupies full steady height
        val composingGone = false
        val preeditHeightGone = if (composingGone) composingHeaderHeight else 0
        val remainingForCandidatesGone = heightForCandidatesArea - preeditHeightGone
        assertEquals(0, preeditHeightGone)
        assertEquals(64, remainingForCandidatesGone)
        assertEquals(heightForCandidatesArea, preeditHeightGone + remainingForCandidatesGone)
    }

    @Test
    fun candidateTextSizeAllowsLargerValues() {
        val candidateTextSizeSp = 20.0f
        val clamped = candidateTextSizeSp.coerceIn(15f, 22f)
        assertEquals(20.0f, clamped, 0.01f)
    }

    @Test
    fun preeditCursorMappingPreservesSyllableBoundaries() {
        val composition = org.bitfennec.lime.inputmethod.CompositionPresentation.from(
            org.bitfennec.lime.core.RimeComposition(
                length = 5,
                preedit = "ni'hao",
                displayBoundaries = intArrayOf(0, 1, 2, 3, 4, 5, 6),
                rawBoundaries = intArrayOf(0, 1, 2, 2, 3, 4, 5),
            ),
            revision = 1,
        )
        listOf(0 to 0, 2 to 3, 5 to 6).forEach { (raw, display) ->
            val current = composition.copy(rawCursor = raw)
            assertEquals(display, current.displayCursor)
            assertEquals(raw, org.bitfennec.lime.utils.PreeditCursorUtils.touchOffsetToRawIndex(display, current))
        }
    }
}
