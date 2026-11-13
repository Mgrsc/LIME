package org.bitfennec.lime.inputmethod

import org.bitfennec.lime.core.RimeComposition
import org.bitfennec.lime.utils.PreeditCursorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreeditCursorTest {
    @Test
    fun mapsInsertedSeparatorsInBothDirections() {
        val composition = presentation("ni hao", 5, intArrayOf(0, 1, 2, 3, 4, 5, 6), intArrayOf(0, 1, 2, 2, 3, 4, 5))
        assertEquals(3, composition.copy(rawCursor = 2).displayCursor)
        (0..5).forEach { raw ->
            val current = composition.copy(rawCursor = raw)
            assertEquals(raw, current.rawOffsetForDisplay(current.displayCursor))
        }
        assertEquals(2, composition.rawOffsetForDisplay(2))
        assertEquals(2, composition.rawOffsetForDisplay(3))
    }

    @Test
    fun mapsAlternativeSpellingWithoutComparingCharacters() {
        val composition = presentation("lü you", 5, intArrayOf(0, 1, 2, 3, 4, 5, 6), intArrayOf(0, 1, 2, 2, 3, 4, 5))
        assertEquals(5, composition.copy(rawCursor = 4).displayCursor)
        assertEquals(4, composition.copy(rawCursor = 3).displayCursor)
        assertEquals(1, composition.rawOffsetForDisplay(1))
        assertEquals(4, composition.rawOffsetForDisplay(5))
    }

    @Test
    fun snapsDoublePinyinExpansionsToRealKeyBoundaries() {
        val composition = presentation("zhong guo", 4, intArrayOf(0, 2, 5, 6, 7, 9), intArrayOf(0, 1, 2, 2, 3, 4))
        assertEquals(2, composition.copy(rawCursor = 1).displayCursor)
        assertEquals(6, composition.copy(rawCursor = 2).displayCursor)
        assertEquals(1, composition.rawOffsetForDisplay(3))
        assertEquals(2, composition.rawOffsetForDisplay(4))
        assertEquals(4, composition.rawOffsetForDisplay(8))
    }

    @Test
    fun mapsUnconvertedTextAfterSelectedCharacters() {
        val composition = presentation("\u4f60\u597dshi jie", 11, intArrayOf(0, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(0, 5, 6, 7, 8, 8, 9, 10, 11))
        assertEquals(3, composition.copy(rawCursor = 6).displayCursor)
        assertEquals(6, composition.rawOffsetForDisplay(3))
        assertEquals(10, composition.rawOffsetForDisplay(8))
        assertEquals(5, composition.rawOffsetForDisplay(1))
    }

    @Test
    fun excludesSurrogateInteriorsAndCopiesNativeArrays() {
        val display = intArrayOf(0, 2, 3)
        val raw = intArrayOf(0, 2, 3)
        val composition = presentation("😀a", 3, display, raw)
        display[1] = 1
        raw[1] = 1
        assertEquals(2, composition.rawOffsetForDisplay(1))
        assertTrue(composition.boundaries.none { it.displayOffset == 1 })
    }

    @Test
    fun rejectsMalformedOrNonMonotonicMappings() {
        listOf(
            intArrayOf(0, 1, 2) to intArrayOf(0, 1),
            intArrayOf(0, 1, 3) to intArrayOf(0, 1, 2),
            intArrayOf(0, 2, 1) to intArrayOf(0, 1, 2),
            intArrayOf(0, 1, 2) to intArrayOf(0, 2, 1),
        ).forEach { (display, raw) ->
            assertNull(presentation("ab", 2, display, raw).rawOffsetForDisplay(1))
        }
        assertNull(presentation("😀", 2, intArrayOf(0, 1, 2), intArrayOf(0, 1, 2)).rawOffsetForDisplay(1))
    }

    @Test
    fun preservesNativeCaretWhenOpaqueTextHasNoEditableMapping() {
        val composition = CompositionPresentation.from(
            RimeComposition(length = 5, cursorPos = 2, preedit = "opaque", displayCursorPos = 3, selStart = 1, selEnd = 5),
            revision = 1,
        )
        assertEquals(3, composition.displayCursor)
        assertEquals(1, composition.selectionStart)
        assertEquals(5, composition.selectionEnd)
        assertNull(composition.rawOffsetForDisplay(3))
    }

    @Test
    fun rejectsNativeCaretInsideSurrogatePair() {
        val composition = CompositionPresentation.from(
            RimeComposition(length = 2, cursorPos = 2, preedit = "\uD83D\uDE00", displayCursorPos = 1),
            revision = 1,
        )
        assertNull(composition.nativeDisplayCursor)
        assertEquals(2, composition.displayCursor)
    }

    @Test
    fun accountsForVisibleCursorWithoutCreatingEditablePositions() {
        val composition = presentation("ni hao", 5, intArrayOf(0, 1, 2, 3, 4, 5, 6), intArrayOf(0, 1, 2, 2, 3, 4, 5))
            .copy(rawCursor = 2)
        assertEquals(2, PreeditCursorUtils.touchOffsetToRawIndex(3, composition))
        assertEquals(2, PreeditCursorUtils.touchOffsetToRawIndex(4, composition))
        assertEquals(3, PreeditCursorUtils.touchOffsetToRawIndex(5, composition))
        assertEquals(5, PreeditCursorUtils.touchOffsetToRawIndex(100, composition))
        assertNull(PreeditCursorUtils.touchOffsetToRawIndex(0, CompositionPresentation.Empty))
    }

    @Test
    fun stateKeepsOneCompositionObjectWhenCandidatesAreAppended() {
        val presentation = presentation("lü you", 5, intArrayOf(0, 1, 2, 3, 4, 5, 6), intArrayOf(0, 1, 2, 2, 3, 4, 5))
        val state = EngineState(composition = presentation, sessionId = 7, isFinish = false)
        val next = state.copy(candidatePageNo = 1)
        assertTrue(state.composition === next.composition)
        assertEquals("lü you", next.showComposition)
        assertEquals(5, next.compositionCursorPos)
        val action = EngineAction.MoveCompositionCursor(3, expectedRevision = presentation.revision, expectedSessionId = state.sessionId)
        assertEquals(3, action.targetIndex)
        assertEquals(7L, action.expectedSessionId)
    }

    private fun presentation(text: String, length: Int, display: IntArray, raw: IntArray) = CompositionPresentation.from(
        RimeComposition(length = length, cursorPos = length, preedit = text, displayBoundaries = display, rawBoundaries = raw),
        revision = 4,
    )
}
