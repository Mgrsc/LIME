package org.bitfennec.lime.inputmethod

import org.bitfennec.lime.core.CandidateListItem
import org.bitfennec.lime.utils.PreeditCursorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PreeditCursorTest {

    @Test
    fun testEngineStateCursorPos() {
        val state = EngineState(
            candidates = listOf(CandidateListItem("🔤", "v")),
            showComposition = "v",
            compositionCursorPos = 1,
            isFinish = false
        )
        assertEquals(1, state.compositionCursorPos)
        assertEquals("v", state.showComposition)
        assertEquals(1, state.candidates.size)
        assertEquals("🔤", state.candidates[0].comment)
        assertEquals("v", state.candidates[0].text)
        assertFalse(state.isFinish)
    }

    @Test
    fun testMoveCompositionCursorActionContracts() {
        val moveAction = EngineAction.MoveCompositionCursor(3)
        assertEquals(3, moveAction.targetIndex)

        val stepLeftAction = EngineAction.StepCompositionCursor(-1)
        assertEquals(-1, stepLeftAction.direction)

        val stepRightAction = EngineAction.StepCompositionCursor(1)
        assertEquals(1, stepRightAction.direction)
    }

    @Test
    fun testRawCursorPosToDisplayCursorPos() {
        val raw = "nihao"
        val show = "ni'hao"

        // 0: |ni'hao
        assertEquals(0, PreeditCursorUtils.rawCursorPosToDisplayCursorPos(raw, 0, show))
        // 1: n|i'hao
        assertEquals(1, PreeditCursorUtils.rawCursorPosToDisplayCursorPos(raw, 1, show))
        // 2: ni'|hao (after syllable 'ni' and delimiter '\'')
        assertEquals(3, PreeditCursorUtils.rawCursorPosToDisplayCursorPos(raw, 2, show))
        // 3: ni'h|ao
        assertEquals(4, PreeditCursorUtils.rawCursorPosToDisplayCursorPos(raw, 3, show))
        // 4: ni'ha|o
        assertEquals(5, PreeditCursorUtils.rawCursorPosToDisplayCursorPos(raw, 4, show))
        // 5: ni'hao| (end of string)
        assertEquals(6, PreeditCursorUtils.rawCursorPosToDisplayCursorPos(raw, 5, show))
    }

    @Test
    fun testRawCursorPosToDisplayCursorPosMultiSyllable() {
        val raw = "zhongguoren"
        val show = "zhong'guo'ren"

        assertEquals(0, PreeditCursorUtils.rawCursorPosToDisplayCursorPos(raw, 0, show))
        // After 'zhong' (5 chars) -> display index 6 (after "zhong'")
        assertEquals(6, PreeditCursorUtils.rawCursorPosToDisplayCursorPos(raw, 5, show))
        // After 'zhongguo' (8 chars) -> display index 10 (after "zhong'guo'")
        assertEquals(10, PreeditCursorUtils.rawCursorPosToDisplayCursorPos(raw, 8, show))
        // End (11 chars) -> display index 13 ("zhong'guo'ren")
        assertEquals(13, PreeditCursorUtils.rawCursorPosToDisplayCursorPos(raw, 11, show))
    }

    @Test
    fun testTouchOffsetToRawIndex() {
        val raw = "nihao"
        val show = "ni'hao"
        val displayCursorPos = 3 // "ni'▎hao"

        // Click before cursor placeholder (touchOffset <= 3)
        assertEquals(0, PreeditCursorUtils.touchOffsetToRawIndex(0, displayCursorPos, show, raw))
        assertEquals(1, PreeditCursorUtils.touchOffsetToRawIndex(1, displayCursorPos, show, raw))
        assertEquals(2, PreeditCursorUtils.touchOffsetToRawIndex(2, displayCursorPos, show, raw))
        assertEquals(2, PreeditCursorUtils.touchOffsetToRawIndex(3, displayCursorPos, show, raw))

        // Click after cursor placeholder (touchOffset > 3, should deduct 1 for ▎)
        // touchOffset = 4 -> letter 'h' -> raw index 2 ('ni|hao') or 3
        assertEquals(2, PreeditCursorUtils.touchOffsetToRawIndex(4, displayCursorPos, show, raw))
        // touchOffset = 5 -> letter 'a' -> raw index 3
        assertEquals(3, PreeditCursorUtils.touchOffsetToRawIndex(5, displayCursorPos, show, raw))
        // touchOffset = 6 -> letter 'o' -> raw index 4
        assertEquals(4, PreeditCursorUtils.touchOffsetToRawIndex(6, displayCursorPos, show, raw))
        // touchOffset = 7 -> end -> raw index 5 ("nihao")
        assertEquals(5, PreeditCursorUtils.touchOffsetToRawIndex(7, displayCursorPos, show, raw))
    }

    @Test
    fun testPreeditCursorUtilsEdgeCases() {
        assertEquals(0, PreeditCursorUtils.rawCursorPosToDisplayCursorPos("", 0, ""))
        assertEquals(0, PreeditCursorUtils.touchOffsetToRawIndex(0, 0, "", ""))

        val rawSingle = "v"
        val showSingle = "v"
        assertEquals(0, PreeditCursorUtils.rawCursorPosToDisplayCursorPos(rawSingle, -1, showSingle))
        assertEquals(1, PreeditCursorUtils.rawCursorPosToDisplayCursorPos(rawSingle, 1, showSingle))
        assertEquals(1, PreeditCursorUtils.rawCursorPosToDisplayCursorPos(rawSingle, 2, showSingle))
        assertEquals(1, PreeditCursorUtils.touchOffsetToRawIndex(2, 1, showSingle, rawSingle))
    }

    @Test
    fun testT9LockedCountContract() {
        val metadata = org.bitfennec.lime.core.RimeT9Metadata("ni'hao", arrayOf("gan", "gao"), 1)
        assertEquals("ni'hao", metadata.preedit)
        assertEquals(1, metadata.lockedCount)
        assertEquals(2, metadata.prefixOptions.size)

        val state = EngineState(
            showComposition = "ni'hao",
            t9LockedCount = 1,
            isFinish = false
        )
        assertEquals(1, state.t9LockedCount)
    }
}
